package com.crealytics.spark.excel

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType

class DefaultSource extends RelationProvider with SchemaRelationProvider with CreatableRelationProvider {

  /**
    * Creates a new relation for retrieving data from an Excel file
    */
  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]): ExcelRelation =
    createRelation(sqlContext, parameters, null)

  /**
    * Creates a new relation for retrieving data from an Excel file
    */
  override def createRelation(
    sqlContext: SQLContext,
    parameters: Map[String, String],
    schema: StructType
  ): ExcelRelation = {
    ExcelRelation(
      location = checkParameter(parameters, "path"),
      sheetName = parameters.get("sheetName"),
      useHeader = checkParameter(parameters, "useHeader").toBoolean,
      treatEmptyValuesAsNulls = parameters.get("treatEmptyValuesAsNulls").fold(true)(_.toBoolean),
      userSchema = Option(schema),
      inferSheetSchema = parameters.get("inferSchema").fold(false)(_.toBoolean),
      addColorColumns = parameters.get("addColorColumns").fold(false)(_.toBoolean),
      startColumn = parameters.get("startColumn").fold(0)(_.toInt),
      endColumn = parameters.get("endColumn").fold(Int.MaxValue)(_.toInt),
      timestampFormat = parameters.get("timestampFormat"),
      maxRowsInMemory = parameters.get("maxRowsInMemory").map(_.toInt),
      excerptSize = parameters.get("excerptSize").fold(10)(_.toInt),
      skipFirstRows = parameters.get("skipFirstRows").map(_.toInt),
      workbookPassword = parameters.get("workbookPassword")
    )(sqlContext)
  }

  override def createRelation(
    sqlContext: SQLContext,
    mode: SaveMode,
    parameters: Map[String, String],
    data: DataFrame
  ): BaseRelation = {
    val path = checkParameter(parameters, "path")
    val sheetName = parameters.getOrElse("sheetName", "Sheet1")
    val useHeader = checkParameter(parameters, "useHeader").toBoolean
    val dateFormat = parameters.getOrElse("dateFormat", ExcelFileSaver.DEFAULT_DATE_FORMAT)
    val timestampFormat = parameters.getOrElse("timestampFormat", ExcelFileSaver.DEFAULT_TIMESTAMP_FORMAT)
    val preHeader = parameters.get("preHeader")
    val filesystemPath = new Path(path)
    val fs = filesystemPath.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)
    val doSave = if (fs.exists(filesystemPath)) {
      mode match {
        case SaveMode.Append =>
          sys.error(s"Append mode is not supported by ${this.getClass.getCanonicalName}")
        case SaveMode.Overwrite =>
          fs.delete(filesystemPath, true)
          true
        case SaveMode.ErrorIfExists =>
          sys.error(s"path $path already exists.")
        case SaveMode.Ignore => false
      }
    } else {
      true
    }
    if (doSave) {
      // Only save data when the save mode is not ignore.
      (new ExcelFileSaver(fs)).save(
        filesystemPath,
        data,
        sheetName = sheetName,
        useHeader = useHeader,
        dateFormat = dateFormat,
        timestampFormat = timestampFormat,
        preHeader = preHeader
      )
    }

    createRelation(sqlContext, parameters, data.schema)
  }

  // Forces a Parameter to exist, otherwise an exception is thrown.
  private def checkParameter(map: Map[String, String], param: String): String = {
    if (!map.contains(param)) {
      throw new IllegalArgumentException(s"Parameter ${'"'}$param${'"'} is missing in options.")
    } else {
      map.apply(param)
    }
  }

  // Gets the Parameter if it exists, otherwise returns the default argument
  private def parameterOrDefault(map: Map[String, String], param: String, default: String) =
    map.getOrElse(param, default)
}
