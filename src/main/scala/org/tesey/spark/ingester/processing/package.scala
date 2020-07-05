package org.tesey.spark.ingester

import org.tesey.spark.ingester.meta._
import com.databricks.spark.avro.SchemaConverters
import com.typesafe.scalalogging.Logger
import org.apache.avro.Schema
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{StructField, StructType}

import scala.collection.JavaConverters._
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import java.util.Properties

package object processing {

  val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"

  def processTable(sparkSession: SparkSession, mode: String, format: String, location: String, schemasLocation: String,
                   sourceConfig: ConfigItem, sinkConfig: ConfigItem, credentials: Properties, logger: Option[Logger] = None)
                  (tableOptions: List[ConfigOption]): Unit = {

    val tableName = getOptionValueFromConfig("tableName", tableOptions, logger)

    // Read rows
    var result = ingestTable(sparkSession, tableName, mode, credentials, sourceConfig, tableOptions)

    val formatString = format match {
      case "avro" => "com.databricks.spark.avro"
      case _ => format
    }

    val writeMode = mode match {
      case "daily" => "append"
      case _ => "overwrite"
    }

    val tableSchemaLocation = getOptionFromConfig("schema", tableOptions)

    if (tableSchemaLocation.isDefined) {

      val fileSystem = FileSystem.get(new URI(schemasLocation), new Configuration())

      val schema: Schema = new Schema.Parser()
        .parse(fileSystem.open(new Path(s"$schemasLocation/${tableSchemaLocation.get.value}")))

      val sparkSqlSchema: StructType = mapAvroToSparkSqlTypes(schema)

      val fields: Seq[Column] = sparkSqlSchema.map(f => col(f.name).cast(f.dataType))

      result = result.select(fields:_*)

    }

    var resultWriter = result.write.format(formatString).mode(writeMode)

    val partitionKeys = getOptionFromConfig("partitionKeys", tableOptions)

    if (partitionKeys.isDefined) {
      resultWriter = resultWriter.partitionBy(partitionKeys.get.value)
    }

    // Write rows
    resultWriter.save(s"$location/$tableName")

  }

  def mapAvroToSparkSqlTypes(schema: Schema, nullable: Boolean = true): StructType =
    new StructType(
      schema.getFields.asScala.toList.map(f => {
        StructField(
          f.name(),
          SchemaConverters.toSqlType(f.schema()).dataType,
          nullable = nullable
        )
      }).toArray
    )

  def ingestTable(sparkSession: SparkSession, tableName: String, mode: String, credentials: Properties,
                  sourceConfig: ConfigItem, tableOptions: List[ConfigOption], logger: Option[Logger] = None): DataFrame = {

    val sourceOptions = sourceConfig.options.get

    val dbTypeOption = getOptionFromConfig("dbType", sourceOptions)

    if (!dbTypeOption.isDefined) {

      if (logger.isDefined) {

        logger.get.error(s"Error: Please specify option 'dbType' for source '${sourceConfig.name}'")

      }

      System.exit(1)

    }

    val dbType = dbTypeOption.get.value

    val dbUrl = getOptionFromConfig("url", tableOptions)

    var oracleJdbcConnectionString = ""

    if (dbUrl.isDefined) {

      oracleJdbcConnectionString = dbUrl.get.value

    } else {

      val host = getOptionFromConfig("host", sourceOptions)

      val port = getOptionFromConfig("port", sourceOptions)

      val dbName = getOptionFromConfig("dbName", sourceOptions)

      if (host.isDefined && port.isDefined && dbName.isDefined) {

        oracleJdbcConnectionString = dbType match {
          case "oracle" => s"jdbc:oracle:thin:/@${host.get.value}:${port.get.value}/${dbName.get.value}"
          case _ => ""
        }

      }  else {

        if (logger.isDefined) {

          logger.get
            .error(s"Error: Please specify options 'host', 'port' and 'dbName' for source '${sourceConfig.name}'")

        }

        System.exit(1)

      }

    }

    val query = mode match {
      case "incrementally" => getQueryToIngestIncrementally(tableName,
        getOptionValueFromConfig("checkField", tableOptions, logger),
        getOptionValueFromConfig("lastValue", tableOptions, logger))
      case "daily" => getQueryToIngestDaily(tableName,
        getOptionValueFromConfig("checkField", tableOptions, logger), dbType)
      case _ => s"${tableName}"
    }

    sparkSession.read.option("sessionInitStatement", "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD'")
      .option("sessionInitStatement", "alter session set nls_timestamp_format='YYYY-MM-DD HH24:MI:SS.FF6'")
      .option("oracle.jdbc.mapDateToTimestamp", "false")
      .option("driver", "oracle.jdbc.driver.OracleDriver")
      .option("isolationLevel", "READ_COMMITTED")
      .jdbc(
        oracleJdbcConnectionString,
        query,
        credentials
      )

  }

  def getQueryToIngestDaily(tableName: String, checkField: String, dbType: String): String = {

    val prevDate = ZonedDateTime.now(ZoneId.of("UTC")).minusDays(1)

    val formatter = DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT)

    val prevDateString = formatter format prevDate

    val checkFieldValue = dbType match {
      case "oracle" => s"TO_DATE('$prevDateString','$DEFAULT_DATE_FORMAT')"
      case _        => s"'$prevDateString'"
    }

    s"(SELECT * FROM $tableName WHERE $checkField = $checkFieldValue)"
  }

  def getQueryToIngestIncrementally(tableName: String, checkField: String, lastValue: String): String = {

    s"(SELECT * FROM $tableName WHERE $checkField >= $lastValue)"

  }

}