package org.apache.spark.sql.hbase

import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{TableExistsException, HColumnDescriptor, HTableDescriptor, TableName}
import org.apache.spark.Logging
import org.apache.spark.sql.SQLContext

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * CreateTableAndLoadData
 *
 */
class HBaseTestData extends HBaseIntegrationTestBase {
  val DefaultStagingTableName = "StageTable"
  val DefaultTableName = "TestTable"
  val DefaultHbaseStagingTableName = s"Hb$DefaultStagingTableName"
  val DefaultHbaseTabName = s"Hb$DefaultTableName"
  val DefaultHbaseColFamilies = Seq("cf1", "cf2")

  val CsvPaths = Array("src/test/resources", "sql/hbase/src/test/resources")
  val DefaultLoadFile = "testTable.txt"

  private val tpath = for (csvPath <- CsvPaths
                           if new java.io.File(csvPath).exists()
  ) yield {
    logInfo(s"Following path exists $csvPath")
    csvPath
  }
  private[hbase] val CsvPath = tpath(0)

  override protected def beforeAll() = {
    super.beforeAll()
    createTables(DefaultStagingTableName, DefaultTableName,
      DefaultHbaseStagingTableName, DefaultHbaseTabName)
    loadData(DefaultStagingTableName, DefaultTableName, s"$CsvPath/$DefaultLoadFile")
  }

  override protected def afterAll() = {
    super.afterAll()
    TestHbase.sql("DROP TABLE " + DefaultStagingTableName)
    TestHbase.sql("DROP TABLE " + DefaultTableName)
  }

  def createNativeHbaseTable(tableName: String, families: Seq[String]) = {
    val hbaseAdmin = TestHbase.hbaseAdmin
    val hdesc = new HTableDescriptor(TableName.valueOf(tableName))
    families.foreach { f => hdesc.addFamily(new HColumnDescriptor(f))}
    try {
      hbaseAdmin.createTable(hdesc)
    } catch {
      case e: TableExistsException =>
        logError(s"Table already exists $tableName", e)
    }
  }

  def createNativeHbaseTable(tableName: String, families: Seq[String],
                             splitKeys: Array[HBaseRawType]) = {
    val hbaseAdmin = TestHbase.hbaseAdmin
    val hdesc = new HTableDescriptor(TableName.valueOf(tableName))
    families.foreach { f => hdesc.addFamily(new HColumnDescriptor(f))}
    try {
      hbaseAdmin.createTable(hdesc, splitKeys)
    } catch {
      case e: TableExistsException =>
        logError(s"Table already exists $tableName", e)
    }
  }

  def dropNativeHbaseTable(tableName: String) = {
    try {
      val hbaseAdmin = TestHbase.hbaseAdmin
      hbaseAdmin.disableTable(tableName)
      hbaseAdmin.deleteTable(tableName)
    } catch {
      case e: TableExistsException =>
        logError(s"Table already exists $tableName", e)
    }
  }

  def createTables(
      stagingTableName: String,
      tableName: String,
      hbaseStagingTable: String,
      hbaseTable: String) = {
    val hbaseAdmin = TestHbase.hbaseAdmin
    if (!hbaseAdmin.tableExists(TableName.valueOf(hbaseStagingTable))) {
      createNativeHbaseTable(hbaseStagingTable, DefaultHbaseColFamilies)
    }
    if (!hbaseAdmin.tableExists(TableName.valueOf(hbaseTable))) {
      createNativeHbaseTable(hbaseTable, DefaultHbaseColFamilies)
    }

    if (TestHbase.catalog.checkLogicalTableExist(stagingTableName)) {
      val dropSql = s"DROP TABLE $stagingTableName"
      runSql(dropSql)
    }

    if (TestHbase.catalog.checkLogicalTableExist(tableName)) {
      val dropSql = s"DROP TABLE $tableName"
      runSql(dropSql)
    }

    val (stagingSql, tabSql) =
      ( s"""CREATE TABLE $stagingTableName(strcol STRING, bytecol STRING, shortcol STRING, intcol STRING,
            longcol STRING, floatcol STRING, doublecol STRING, PRIMARY KEY(doublecol, strcol, intcol))
            MAPPED BY ($hbaseStagingTable, COLS=[bytecol=cf1.hbytecol,
            shortcol=cf1.hshortcol, longcol=cf2.hlongcol, floatcol=cf2.hfloatcol])"""
        .stripMargin
        ,
        s"""CREATE TABLE $tableName(strcol STRING, bytecol BYTE, shortcol SHORT, intcol INTEGER,
            longcol LONG, floatcol FLOAT, doublecol DOUBLE, PRIMARY KEY(doublecol, strcol, intcol))
            MAPPED BY ($hbaseTable, COLS=[bytecol=cf1.hbytecol,
            shortcol=cf1.hshortcol, longcol=cf2.hlongcol, floatcol=cf2.hfloatcol])"""
          .stripMargin
        )
    try {
      logInfo(s"invoking $stagingSql ..")
      runSql(stagingSql)
    } catch {
      case e: TableExistsException =>
        logInfo("IF NOT EXISTS still not implemented so we get the following exception", e)
    }

    logDebug(s"Created table $tableName: " +
      s"isTableAvailable= ${hbaseAdmin.isTableAvailable(s2b(hbaseStagingTable))}" +
      s" tableDescriptor= ${hbaseAdmin.getTableDescriptor(s2b(hbaseStagingTable))}")

    try {
      logInfo(s"invoking $tabSql ..")
      runSql(tabSql)
    } catch {
      case e: TableExistsException =>
        logInfo("IF NOT EXISTS still not implemented so we get the following exception", e)
    }
  }

  def loadData(stagingTableName: String, tableName: String, loadFile: String) = {
    // then load data into table
    val loadSql = s"LOAD PARALL DATA LOCAL INPATH '$loadFile' INTO TABLE $tableName"
    runSql(loadSql)
  }

  def s2b(s: String) = Bytes.toBytes(s)

  def run(sqlCtx: SQLContext, testName: String, sql: String, exparr: Seq[Seq[Any]]) = {
    val execQuery1 = sqlCtx.executeSql(sql)
    val result1 = runSql(sql)
    assert(result1.size == exparr.length, s"$testName failed on size")
    verify(testName,
      sql,
      for (rx <- 0 until exparr.size)
      yield result1(rx).toSeq, exparr
    )
  }
}
