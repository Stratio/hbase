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
package org.apache.spark.sql.hbase

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.{Get, HTable, Put, Result, Scan}
import org.apache.hadoop.hbase.filter._
import org.apache.log4j.Logger
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.hbase.catalyst.NotPusher
import org.apache.spark.sql.hbase.types.PartitionRange
import org.apache.spark.sql.hbase.util.{DataTypeUtils, HBaseKVHelper, BytesUtils, Util}
import org.apache.spark.sql.sources.{BaseRelation, CatalystScan, LogicalRelation}
import org.apache.spark.sql.sources.{RelationProvider, InsertableRelation}
import org.apache.spark.sql.catalyst.expressions.AttributeReference

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

class HBaseSource extends RelationProvider {
  // Returns a new HBase relation with the given parameters
  override def createRelation(
                               sqlContext: SQLContext,
                               parameters: Map[String, String]): BaseRelation = {
    val catalog = sqlContext.catalog.asInstanceOf[HBaseCatalog]

    val tableName = parameters("tableName")
    val rawNamespace = parameters("namespace")
    val namespace: Option[String] = if (rawNamespace == null || rawNamespace.isEmpty) None
    else Some(rawNamespace)
    val hbaseTable = parameters("hbaseTableName")
    val colsSeq = parameters("colsSeq").split(",")
    val keyCols = parameters("keyCols").split(";")
      .map { case c => val cols = c.split(","); (cols(0), cols(1))}
    val nonKeyCols = parameters("nonKeyCols").split(";")
      .filterNot(_ == "")
      .map { case c => val cols = c.split(","); (cols(0), cols(1), cols(2), cols(3))}

    val keyMap: Map[String, String] = keyCols.toMap
    val allColumns = colsSeq.map {
      case name =>
        if (keyMap.contains(name)) {
          KeyColumn(
            name,
            catalog.getDataType(keyMap.get(name).get),
            keyCols.indexWhere(_._1 == name))
        } else {
          val nonKeyCol = nonKeyCols.find(_._1 == name).get
          NonKeyColumn(
            name,
            catalog.getDataType(nonKeyCol._2),
            nonKeyCol._3,
            nonKeyCol._4
          )
        }
    }
    catalog.createTable(tableName, rawNamespace, hbaseTable, allColumns, null)
  }
}

/**
 *
 * @param tableName SQL table name
 * @param hbaseNamespace physical HBase table namespace
 * @param hbaseTableName physical HBase table name
 * @param allColumns schema
 * @param context HBaseSQLContext
 */
@SerialVersionUID(15298736227428789L)
private[hbase] case class HBaseRelation(
                                         tableName: String,
                                         hbaseNamespace: String,
                                         hbaseTableName: String,
                                         allColumns: Seq[AbstractColumn])
                                       (@transient var context: SQLContext)
  extends BaseRelation with CatalystScan with InsertableRelation with Serializable {
  @transient lazy val logger = Logger.getLogger(getClass.getName)

  @transient lazy val keyColumns = allColumns.filter(_.isInstanceOf[KeyColumn])
    .asInstanceOf[Seq[KeyColumn]].sortBy(_.order)

  // The sorting is by the ordering of the Column Family and Qualifier. This is for avoidance
  // to sort cells per row, as needed in bulk loader
  @transient lazy val nonKeyColumns = allColumns.filter(_.isInstanceOf[NonKeyColumn])
    .asInstanceOf[Seq[NonKeyColumn]].sortWith(
      (a: NonKeyColumn, b: NonKeyColumn) => {
        val empty = new HBaseRawType(0)
        KeyValue.COMPARATOR.compare(
          new KeyValue(empty, a.familyRaw, a.qualifierRaw),
          new KeyValue(empty, b.familyRaw, b.qualifierRaw)) < 0
      }
    )

  lazy val partitionKeys = keyColumns.map(col =>
    logicalRelation.output.find(_.name == col.sqlName).get)

  @transient lazy val columnMap = allColumns.map {
    case key: KeyColumn => (key.sqlName, key.order)
    case nonKey: NonKeyColumn => (nonKey.sqlName, nonKey)
  }.toMap

  allColumns.zipWithIndex.foreach(pi => pi._1.ordinal = pi._2)

  private var serializedConfiguration: Array[Byte] = _

  def setConfig(inconfig: Configuration) = {
    config = inconfig
    if (inconfig != null) {
      serializedConfiguration = Util.serializeHBaseConfiguration(inconfig)
    }
  }

  @transient var config: Configuration = _

  private def getConf: Configuration = {
    if (config == null) {
      config = {
        if (serializedConfiguration != null) {
          Util.deserializeHBaseConfiguration(serializedConfiguration)
        }
        else {
          HBaseConfiguration.create
        }
      }
    }
    config
  }

  logger.debug(s"HBaseRelation config has zkPort="
    + s"${getConf.get("hbase.zookeeper.property.clientPort")}")

  @transient private var htable_ : HTable = _

  def htable = {
    if (htable_ == null) htable_ = new HTable(getConf, hbaseTableName)
    htable_
  }

  def isNonKey(attr: AttributeReference): Boolean = {
    keyIndex(attr) < 0
  }

  def keyIndex(attr: AttributeReference): Int = {
    // -1 if nonexistent
    partitionKeys.indexWhere(_.exprId == attr.exprId)
  }

  // find the index in a sequence of AttributeReferences that is a key; -1 if not present
  def rowIndex(refs: Seq[Attribute], keyIndex: Int): Int = {
    refs.indexWhere(_.exprId == partitionKeys(keyIndex).exprId)
  }

  def flushHTable() = {
    if (htable_ != null) {
      htable_.flushCommits()
    }
  }

  def closeHTable() = {
    if (htable_ != null) {
      htable_.close()
      htable_ = null
    }
  }

  // corresponding logical relation
  @transient lazy val logicalRelation = LogicalRelation(this)

  lazy val output = logicalRelation.output

  @transient lazy val dts: Seq[DataType] = allColumns.map(_.dataType)

  /**
   * partitions are updated per table lookup to keep the info reasonably updated
   */
  @transient lazy val partitionExpiration =
    context.conf.asInstanceOf[HBaseSQLConf].partitionExpiration * 1000
  @transient var partitionTS: Long = _

  private[hbase] def fetchPartitions(): Unit = {
    if (System.currentTimeMillis - partitionTS >= partitionExpiration) {
      partitionTS = System.currentTimeMillis
      partitions = {
        val regionLocations = htable.getRegionLocations.asScala.toSeq
        logger.info(s"Number of HBase regions for " +
          s"table ${htable.getName.getNameAsString}: ${regionLocations.size}")
        regionLocations.zipWithIndex.map {
          case p =>
            val start: Option[HBaseRawType] = {
              if (p._1._1.getStartKey.length == 0) {
                None
              } else {
                Some(p._1._1.getStartKey)
              }
            }
            val end: Option[HBaseRawType] = {
              if (p._1._1.getEndKey.length == 0) {
                None
              } else {
                Some(p._1._1.getEndKey)
              }
            }
            new HBasePartition(
              p._2, p._2,
              start,
              end,
              Some(p._1._2.getHostname), relation = this)
        }
      }
    }
  }

  @transient var partitions: Seq[HBasePartition] = _

  @transient private[hbase] lazy val dimSize = keyColumns.size

  val scannerFetchSize = context.conf.asInstanceOf[HBaseSQLConf].scannerFetchSize

  private[hbase] def generateRange(partition: HBasePartition, pred: Expression,
                                   index: Int): PartitionRange[_] = {
    def getData(dt: AtomicType,
                bound: Option[HBaseRawType]): Option[Any] = {
      if (bound.isEmpty) {
        None
      } else {
        /**
         * the partition start/end could be incomplete byte array, so we need to make it
         * a complete key first
         */
        val finalRowKey = getFinalKey(bound)
        val (start, length) = HBaseKVHelper.decodingRawKeyColumns(finalRowKey, keyColumns)(index)
        Some(DataTypeUtils.bytesToData(finalRowKey, start, length, dt).asInstanceOf[dt.InternalType])
      }
    }

    val dt = keyColumns(index).dataType.asInstanceOf[AtomicType]
    val isLastKeyIndex = index == (keyColumns.size - 1)
    val start = getData(dt, partition.start)
    val end = getData(dt, partition.end)
    val startInclusive = start.nonEmpty
    val endInclusive = end.nonEmpty && !isLastKeyIndex
    new PartitionRange(start, startInclusive, end, endInclusive, partition.index, dt, pred)
  }


  /**
   * Return the start keys of all of the regions in this table,
   * as a list of SparkImmutableBytesWritable.
   */
  def getRegionStartKeys = {
    val byteKeys: Array[HBaseRawType] = htable.getStartKeys
    val ret = ArrayBuffer[HBaseRawType]()

    // Since the size of byteKeys will be 1 if there is only one partition in the table,
    // we need to omit that null element.
    for (byteKey <- byteKeys if !(byteKeys.length == 1 && byteKeys(0).length == 0)) {
      ret += byteKey
    }

    ret
  }

  /**
   * build filter list based on critical point ranges
   * @param output the projection list
   * @param filterPred the predicate
   * @param cprs the sequence of critical point ranges
   * @return the filter list and expression tuple
   */
  def buildCPRFilterList(output: Seq[Attribute], filterPred: Option[Expression],
                         cprs: Seq[MDCriticalPointRange[_]]):
  (Option[FilterList], Option[Expression], Seq[Expression]) = {
    val finalFilterList = new FilterList(FilterList.Operator.MUST_PASS_ALL)
    val cprFilterList: FilterList = new FilterList(FilterList.Operator.MUST_PASS_ONE)
    var expressionList: List[Expression] = List[Expression]()
    var anyNonpushable = false
    val pushablePreds = new ListBuffer[Expression]()
    for (cpr <- cprs) {
      val cprAndPushableFilterList: FilterList = new FilterList(FilterList.Operator.MUST_PASS_ALL)
      val startKey: Option[Any] = cpr.lastRange.start
      val endKey: Option[Any] = cpr.lastRange.end
      val startInclusive = cpr.lastRange.startInclusive
      val endInclusive = cpr.lastRange.endInclusive
      val keyType: AtomicType = cpr.lastRange.dt
      val predicate = if (cpr.lastRange.pred == null) None else Some(cpr.lastRange.pred)

      val (pushable, nonPushable, pushablePred) = buildPushdownFilterList(predicate)

      val items: Seq[(Any, AtomicType)] = cpr.prefix
      val head: Seq[(HBaseRawType, AtomicType)] = items.map {
        case (itemValue, itemType) =>
          (DataTypeUtils.dataToBytes(itemValue, itemType), itemType)
      }

      val headExpression: Seq[Expression] = items.zipWithIndex.map { case (item, index) =>
        val keyCol = keyColumns.find(_.order == index).get

        val left = filterPred.get.references.find(_.name == keyCol.sqlName).get
        val right = Literal.create(item._1, item._2)
        EqualTo(left, right)
      }

      val tailExpression: Expression = {
        val index = items.size
        val keyCol = keyColumns.find(_.order == index).get
        val left = filterPred.get.references.find(_.name == keyCol.sqlName).get
        val startInclusive = cpr.lastRange.startInclusive
        val endInclusive = cpr.lastRange.endInclusive
        if (cpr.lastRange.isPoint) {
          val right = Literal.create(cpr.lastRange.start.get, cpr.lastRange.dt)
          EqualTo(left, right)
        } else if (cpr.lastRange.start.isDefined && cpr.lastRange.end.isDefined) {
          var right = Literal.create(cpr.lastRange.start.get, cpr.lastRange.dt)
          val leftExpression = if (startInclusive) {
            GreaterThanOrEqual(left, right)
          } else {
            GreaterThan(left, right)
          }
          right = Literal.create(cpr.lastRange.end.get, cpr.lastRange.dt)
          val rightExpress = if (endInclusive) {
            LessThanOrEqual(left, right)
          } else {
            LessThan(left, right)
          }
          And(leftExpression, rightExpress)
        } else if (cpr.lastRange.start.isDefined) {
          val right = Literal.create(cpr.lastRange.start.get, cpr.lastRange.dt)
          if (startInclusive) {
            GreaterThanOrEqual(left, right)
          } else {
            GreaterThan(left, right)
          }
        } else if (cpr.lastRange.end.isDefined) {
          val right = Literal.create(cpr.lastRange.end.get, cpr.lastRange.dt)
          if (endInclusive) {
            LessThanOrEqual(left, right)
          } else {
            LessThan(left, right)
          }
        } else {
          null
        }
      }

      val combinedExpression: Seq[Expression] = headExpression :+ tailExpression
      var andExpression: Expression = combinedExpression.reduceLeft(
        (e1: Expression, e2: Expression) => And(e1, e2))

      if (nonPushable.isDefined) {
        anyNonpushable = true
        andExpression = And(andExpression, nonPushable.get)
      }
      expressionList = expressionList :+ andExpression

      val filter = {
        if (cpr.lastRange.isPoint) {
          // the last range is a point
          val tail: (HBaseRawType, AtomicType) =
            (DataTypeUtils.dataToBytes(startKey.get, keyType), keyType)
          val rowKeys = head :+ tail
          val row = HBaseKVHelper.encodingRawKeyColumns(rowKeys)
          if (cpr.prefix.size == keyColumns.size - 1) {
            // full dimension of row key
            new RowFilter(CompareFilter.CompareOp.EQUAL, new BinaryComparator(row))
          }
          else {
            new RowFilter(CompareFilter.CompareOp.EQUAL, new BinaryPrefixComparator(row))
          }
        } else {
          // the last range is not a point
          val startFilter: RowFilter = if (startKey.isDefined) {
            val tail: (HBaseRawType, AtomicType) =
              (DataTypeUtils.dataToBytes(startKey.get, keyType), keyType)
            val rowKeys = head :+ tail
            val row = HBaseKVHelper.encodingRawKeyColumns(rowKeys)
            if (cpr.prefix.size == keyColumns.size - 1) {
              // full dimension of row key
              if (startInclusive) {
                new RowFilter(CompareFilter.CompareOp.GREATER_OR_EQUAL, new BinaryComparator(row))
              } else {
                new RowFilter(CompareFilter.CompareOp.GREATER, new BinaryComparator(row))
              }
            }
            else {
              if (startInclusive) {
                new RowFilter(CompareFilter.CompareOp.GREATER_OR_EQUAL,
                  new BinaryPrefixComparator(row))
              } else {
                new RowFilter(CompareFilter.CompareOp.GREATER, new BinaryPrefixComparator(row))
              }
            }
          } else {
            null
          }
          val endFilter: RowFilter = if (endKey.isDefined) {
            val tail: (HBaseRawType, AtomicType) =
              (DataTypeUtils.dataToBytes(endKey.get, keyType), keyType)
            val rowKeys = head :+ tail
            val row = HBaseKVHelper.encodingRawKeyColumns(rowKeys)
            if (cpr.prefix.size == keyColumns.size - 1) {
              // full dimension of row key
              if (endInclusive) {
                new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL, new BinaryComparator(row))
              } else {
                new RowFilter(CompareFilter.CompareOp.LESS, new BinaryComparator(row))
              }
            } else {
              if (endInclusive) {
                new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL,
                  new BinaryPrefixComparator(row))
              } else {
                new RowFilter(CompareFilter.CompareOp.LESS, new BinaryPrefixComparator(row))
              }
            }
          } else {
            null
          }
          /*
          * create the filter, for example, k1 = 10, k2 < 5
          * it will create 2 filters, first RowFilter = 10 (PrefixComparator),
          * second, RowFilter < (10, 5) (PrefixComparator / Comparator)
          */
          val prefixFilter = if (head.size > 0) {
            val row = HBaseKVHelper.encodingRawKeyColumns(head)
            new RowFilter(CompareFilter.CompareOp.EQUAL, new BinaryPrefixComparator(row))
          } else {
            null
          }
          if (startKey.isDefined && endKey.isDefined) {
            // both start and end filters exist
            val filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL)
            if (prefixFilter != null) {
              filterList.addFilter(prefixFilter)
            }
            filterList.addFilter(startFilter)
            filterList.addFilter(endFilter)
            filterList
          } else if (startKey.isDefined) {
            // start filter exists only
            if (prefixFilter != null) {
              val filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL)
              filterList.addFilter(prefixFilter)
              filterList.addFilter(startFilter)
              filterList
            } else {
              startFilter
            }
          } else {
            // end filter exists only
            if (prefixFilter != null) {
              val filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL)
              filterList.addFilter(prefixFilter)
              filterList.addFilter(endFilter)
              filterList
            } else {
              endFilter
            }
          }
        }
      }
      cprAndPushableFilterList.addFilter(filter)
      if (pushable.isDefined) {
        require(pushablePred.isDefined && pushablePred.nonEmpty,
          "Internal logic error: non-empty pushable predicate expected")
        pushablePreds += pushablePred.get
        cprAndPushableFilterList.addFilter(pushable.get)
      }
      cprFilterList.addFilter(cprAndPushableFilterList)
    }

    val orExpression = if (anyNonpushable) {
      Some(expressionList.reduceLeft((e1: Expression, e2: Expression) => Or(e1, e2)))
    } else {
      None
    }
    if (cprFilterList.getFilters.size() == 1) {
      finalFilterList.addFilter(cprFilterList.getFilters.get(0))
    } else if (cprFilterList.getFilters.size() > 1) {
      finalFilterList.addFilter(cprFilterList)
    }
    (Some(finalFilterList), orExpression, pushablePreds)
  }

  /**
   * create pushdown filter list based on predicate
   * @param pred the predicate
   * @return tuple(filter list, non-pushdownable expression, pushdown predicates)
   */
  def buildPushdownFilterList(pred: Option[Expression]):
  (Option[FilterList], Option[Expression], Option[Expression]) = {
    if (pred.isDefined) {
      val predExp: Expression = pred.get
      // build pred pushdown filters:
      // 1. push any NOT through AND/OR
      val notPushedPred = NotPusher(predExp)
      // 2. classify the transformed predicate into pushdownable and non-pushdownable predicates
      val classier = new ScanPredClassifier(this) // Right now only on primary key dimension
      val (pushdownFilterPred, otherPred) = classier(notPushedPred)
      // 3. build a FilterList mirroring the pushdownable predicate
      val predPushdownFilterList = {
        if (pushdownFilterPred.isEmpty) None else buildFilterListFromPred(pushdownFilterPred)
      }
      // 4. merge the above FilterList with the one from the projection
      (predPushdownFilterList, otherPred, pushdownFilterPred)
    } else {
      (None, None, None)
    }
  }

  /**
   * add the filter to the filter list
   * @param filters the filter list
   * @param filtersToBeAdded the filter to be added
   * @param operator the operator of the filter to be added
   */
  private def addToFilterList(filters: java.util.ArrayList[Filter],
                              filtersToBeAdded: Option[FilterList],
                              operator: FilterList.Operator) = {
    if (filtersToBeAdded.isDefined) {
      val filterList = filtersToBeAdded.get
      val size = filterList.getFilters.size
      if (size == 1 || filterList.getOperator == operator) {
        filterList.getFilters.map(p => filters.add(p))
      } else {
        filters.add(filterList)
      }
    }
  }

  def createSingleColumnValueFilter(left: AttributeReference, right: Literal,
                                    compareOp: CompareFilter.CompareOp): Option[FilterList] = {
    val nonKeyColumn = nonKeyColumns.find(_.sqlName == left.name)
    if (nonKeyColumn.isDefined) {
      val column = nonKeyColumn.get
      val filter = new SingleColumnValueFilter(column.familyRaw,
        column.qualifierRaw,
        compareOp,
        DataTypeUtils.getBinaryComparator(BytesUtils.create(right.dataType), right))
      filter.setFilterIfMissing(true)
      Some(new FilterList(filter))
    } else {
      None
    }
  }

  /**
   * recursively create the filter list based on predicate
   * @param pred the predicate
   * @return the filter list, or None if predicate is not defined
   */
  private def buildFilterListFromPred(pred: Option[Expression]): Option[FilterList] = {
    if (pred.isEmpty) {
      None
    }
    val expression = pred.get
    expression match {
      case And(left, right) =>
        val filters = new java.util.ArrayList[Filter]
        if (left != null) {
          val leftFilterList = buildFilterListFromPred(Some(left))
          addToFilterList(filters, leftFilterList, FilterList.Operator.MUST_PASS_ALL)
        }
        if (right != null) {
          val rightFilterList = buildFilterListFromPred(Some(right))
          addToFilterList(filters, rightFilterList, FilterList.Operator.MUST_PASS_ALL)
        }
        Some(new FilterList(FilterList.Operator.MUST_PASS_ALL, filters))
      case Or(left, right) =>
        val filters = new java.util.ArrayList[Filter]
        if (left != null) {
          val leftFilterList = buildFilterListFromPred(Some(left))
          addToFilterList(filters, leftFilterList, FilterList.Operator.MUST_PASS_ONE)
        }
        if (right != null) {
          val rightFilterList = buildFilterListFromPred(Some(right))
          addToFilterList(filters, rightFilterList, FilterList.Operator.MUST_PASS_ONE)
        }
        Some(new FilterList(FilterList.Operator.MUST_PASS_ONE, filters))
      case InSet(value@AttributeReference(name, dataType, _, _), hset) =>
        val column = nonKeyColumns.find(_.sqlName == name)
        if (column.isDefined) {
          val filterList = new FilterList(FilterList.Operator.MUST_PASS_ONE)
          for (item <- hset) {
            val filter = new SingleColumnValueFilter(column.get.familyRaw,
              column.get.qualifierRaw,
              CompareFilter.CompareOp.EQUAL,
              DataTypeUtils.getBinaryComparator(BytesUtils.create(dataType),
                Literal.create(item, dataType)))
            filterList.addFilter(filter)
          }
          Some(filterList)
        } else {
          None
        }
      case GreaterThan(left: AttributeReference, right: Literal) =>
        createSingleColumnValueFilter(left, right, CompareFilter.CompareOp.GREATER)
      case GreaterThan(left: Literal, right: AttributeReference) =>
        createSingleColumnValueFilter(right, left, CompareFilter.CompareOp.GREATER)
      case GreaterThanOrEqual(left: AttributeReference, right: Literal) =>
        createSingleColumnValueFilter(left, right,
          CompareFilter.CompareOp.GREATER_OR_EQUAL)
      case GreaterThanOrEqual(left: Literal, right: AttributeReference) =>
        createSingleColumnValueFilter(right, left,
          CompareFilter.CompareOp.GREATER_OR_EQUAL)
      case EqualTo(left: AttributeReference, right: Literal) =>
        createSingleColumnValueFilter(left, right, CompareFilter.CompareOp.EQUAL)
      case EqualTo(left: Literal, right: AttributeReference) =>
        createSingleColumnValueFilter(right, left, CompareFilter.CompareOp.EQUAL)
      case LessThan(left: AttributeReference, right: Literal) =>
        createSingleColumnValueFilter(left, right, CompareFilter.CompareOp.LESS)
      case LessThan(left: Literal, right: AttributeReference) =>
        createSingleColumnValueFilter(right, left, CompareFilter.CompareOp.LESS)
      case LessThanOrEqual(left: AttributeReference, right: Literal) =>
        createSingleColumnValueFilter(left, right, CompareFilter.CompareOp.LESS_OR_EQUAL)
      case LessThanOrEqual(left: Literal, right: AttributeReference) =>
        createSingleColumnValueFilter(right, left, CompareFilter.CompareOp.LESS_OR_EQUAL)
      case _ => None
    }
  }

  def buildPut(row: Row): Put = {
    // TODO: revisit this using new KeyComposer
    val rowKey: HBaseRawType = null
    new Put(rowKey)
  }

  def sqlContext = context

  def schema: StructType = StructType(allColumns.map {
    case KeyColumn(name, dt, _) => StructField(name, dt, nullable = false)
    case NonKeyColumn(name, dt, _, _) => StructField(name, dt, nullable = true)
  })

  override def insert(data: DataFrame, overwrite: Boolean) = {
    if (!overwrite) {
      sqlContext.sparkContext.runJob(data.rdd, writeToHBase _)
    } else {
      // TODO: Support INSERT OVERWRITE INTO
      sys.error("HBASE Table does not support INSERT OVERWRITE for now.")
    }
  }

  def writeToHBase(context: TaskContext, iterator: Iterator[Row]) = {
    // TODO:make the BatchMaxSize configurable
    val BatchMaxSize = 100

    var rowIndexInBatch = 0
    var colIndexInBatch = 0

    var puts = new ListBuffer[Put]()
    while (iterator.hasNext) {
      val row = iterator.next()
      val rawKeyCol = keyColumns.map(
        kc => {
          val rowColumn = DataTypeUtils.getRowColumnInHBaseRawType(
            row, kc.ordinal, kc.dataType)
          colIndexInBatch += 1
          (rowColumn, kc.dataType)
        }
      )
      val key = HBaseKVHelper.encodingRawKeyColumns(rawKeyCol)
      val put = new Put(key)
      nonKeyColumns.foreach(
        nkc => {
          val rowVal = DataTypeUtils.getRowColumnInHBaseRawType(
            row, nkc.ordinal, nkc.dataType)
          colIndexInBatch += 1
          put.add(nkc.familyRaw, nkc.qualifierRaw, rowVal)
        }
      )

      puts += put
      colIndexInBatch = 0
      rowIndexInBatch += 1
      if (rowIndexInBatch >= BatchMaxSize) {
        htable.put(puts.toList)
        puts.clear()
        rowIndexInBatch = 0
      }
    }
    if (puts.nonEmpty) {
      htable.put(puts.toList)
    }
    closeHTable()
  }


  def buildScan(requiredColumns: Seq[Attribute], filters: Seq[Expression]): RDD[Row] = {
    require(filters.size < 2, "Internal logical error: unexpected filter list size")
    val filterPredicate = if (filters.isEmpty) None
    else Some(filters(0))
    new HBaseSQLReaderRDD(
      this,
      context.conf.codegenEnabled,
      requiredColumns,
      filterPredicate, // PartitionPred : Option[Expression]
      None, // coprocSubPlan: SparkPlan
      context
    )
  }

  def buildScan(start: Option[HBaseRawType], end: Option[HBaseRawType],
                filters: Option[FilterList], otherFilters: Option[Expression],
                pushdownPreds: Seq[Expression],
                projectionList: Seq[NamedExpression]): Scan = {
    val scan = {
      (start, end) match {
        case (Some(lb), Some(ub)) => new Scan(lb, ub)
        case (Some(lb), None) => new Scan(lb)
        case (None, Some(ub)) => new Scan(Array[Byte](), ub)
        case _ => new Scan
      }
    }

    // set fetch size
    scan.setCaching(scannerFetchSize)

    // add Family to SCAN from projections
    addColumnFamiliesToScan(scan, filters, otherFilters, pushdownPreds, projectionList)
  }

  /**
   * add projection and column to the scan
   * @param scan the current scan
   * @param filters the filter list to be processed
   * @param otherFilters the non-pushdownable predicates
   * @param pushdownPreds the pushdownable predicates
   * @param projectionList the projection list
   * @return the proper scan
   */
  def addColumnFamiliesToScan(scan: Scan, filters: Option[FilterList],
                              otherFilters: Option[Expression],
                              pushdownPreds: Seq[Expression],
                              projectionList: Seq[NamedExpression]): Scan = {
    var distinctProjectionList = projectionList.map(_.name)
    if (otherFilters.isDefined) {
      distinctProjectionList =
        distinctProjectionList.union(otherFilters.get.references.toSeq.map(_.name))
    }
    // filter out the key columns
    distinctProjectionList =
      distinctProjectionList.filterNot(p => keyColumns.exists(_.sqlName == p))

    val finalFilters = if (distinctProjectionList.size == 0) {
      if (filters.isDefined && !filters.get.getFilters.isEmpty) {
        if (filters.get.getFilters.size() == 1) {
          filters.get.getFilters.get(0)
        } else {
          filters.get
        }
      } else {
        val filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL)
        val left = new FirstKeyOnlyFilter
        val right = new KeyOnlyFilter
        filterList.addFilter(left)
        filterList.addFilter(right)
        filterList
      }
    } else {
      if (filters.isDefined && !filters.get.getFilters.isEmpty) {
        if (filters.get.getFilters.size() == 1) {
          filters.get.getFilters.get(0)
        } else {
          filters.get
        }
      } else {
        null
      }
    }
    if (finalFilters != null) scan.setFilter(finalFilters)

    if (pushdownPreds.nonEmpty) {
      val pushdownNameSet = pushdownPreds.flatMap(_.references).map(_.name).
        filterNot(p => keyColumns.exists(_.sqlName == p)).toSet
      if (distinctProjectionList.toSet.subsetOf(pushdownNameSet)) {
        // If the pushed down predicate is present and the projection is a subset of the columns
        // of the pushed filters, use the columns as projections
        // to avoid a full projection
        distinctProjectionList = pushdownNameSet.toSeq.distinct
        if (distinctProjectionList.size > 0 && distinctProjectionList.size < nonKeyColumns.size) {
          distinctProjectionList.map {
            case p =>
              val nkc = nonKeyColumns.find(_.sqlName == p).get
              scan.addColumn(nkc.familyRaw, nkc.qualifierRaw)
          }
        }
      }
    }
    scan
  }

  def buildGet(projectionList: Seq[NamedExpression], rowKey: HBaseRawType) {
    new Get(rowKey)
    // TODO: add columns to the Get
  }

  def buildRow(projections: Seq[(Attribute, Int)],
               result: Result,
               row: MutableRow): Row = {
    assert(projections.size == row.length, "Projection size and row size mismatched")
    val rowKeys = HBaseKVHelper.decodingRawKeyColumns(result.getRow, keyColumns)
    projections.foreach {
      p =>
        columnMap.get(p._1.name).get match {
          case column: NonKeyColumn =>
            val colValue = result.getValue(column.familyRaw, column.qualifierRaw)
            DataTypeUtils.setRowColumnFromHBaseRawType(
              row, p._2, colValue, 0, colValue.length, column.dataType)
          case ki =>
            val keyIndex = ki.asInstanceOf[Int]
            val (start, length) = rowKeys(keyIndex)
            DataTypeUtils.setRowColumnFromHBaseRawType(
              row, p._2, result.getRow, start, length, keyColumns(keyIndex).dataType)
        }
    }
    row
  }

  /**
   * Convert the row key to its proper format. Due to the nature of HBase, the start and
   * end of partition could be partial row key, we may need to append 0x00 to make it comply
   * with the definition of key columns, for example, add four 0x00 if a key column type is
   * integer. Also string type will always be even number, so we need to add one 0x00 or two
   * depends on the length of the byte.
   * @param rawKey the original row key
   * @return the proper row key based on the definition of the key columns
   */
  def getFinalKey(rawKey: Option[HBaseRawType]): HBaseRawType = {
    val origRowKey: HBaseRawType = rawKey.get

    /**
     * Recursively run this function to check the key columns one by one.
     * If the input raw key contains the whole part of this key columns, then continue to
     * check the next one; otherwise, append the raw key by adding 0x00 to its proper format
     * and return it.
     * @param rowIndex the start point of unchecked bytes in the input raw key
     * @param curKeyIndex the next key column need to be checked
     * @return the proper row key based on the definition of the key columns
     */
    def getFinalRowKey(rowIndex: Int, curKeyIndex: Int): HBaseRawType = {
      if (curKeyIndex >= keyColumns.length) origRowKey
      else {
        val typeOfKey = keyColumns(curKeyIndex)
        if (typeOfKey.dataType == StringType) {
          val indexOfStringEnd = origRowKey.indexOf(0x00, rowIndex)
          if (indexOfStringEnd == -1) {
            val delta: Array[Byte] = if ((origRowKey.length - rowIndex) % 2 == 0) {
              new Array[Byte](1 + getMinimum(curKeyIndex + 1))
            } else {
              new Array[Byte](2 + getMinimum(curKeyIndex + 1))
            }
            origRowKey ++ delta
          } else {
            getFinalRowKey(indexOfStringEnd + 1, curKeyIndex + 1)
          }
        } else {
          val nextRowIndex = rowIndex +
            typeOfKey.dataType.asInstanceOf[AtomicType].defaultSize
          if (nextRowIndex <= origRowKey.length) {
            getFinalRowKey(nextRowIndex, curKeyIndex + 1)
          } else {
            val delta: Array[Byte] = {
              new Array[Byte](nextRowIndex - origRowKey.length + getMinimum(curKeyIndex + 1))
            }
            origRowKey ++ delta
          }
        }
      }
    }

    /**
     * Get the minimum key length based on the key columns definition
     * @param startKeyIndex the start point of the key column
     * @return the minimum length required for the remaining key columns
     */
    def getMinimum(startKeyIndex: Int): Int = {
      keyColumns.drop(startKeyIndex).map(k => {
        k.dataType match {
          case StringType => 1
          case _ => k.dataType.asInstanceOf[AtomicType].defaultSize
        }
      }
      ).sum
    }

    getFinalRowKey(0, 0)
  }

  /**
   * Convert a HBase row key into column values in their native data formats
   * @param rawKey the HBase row key
   * @return A sequence of column values from the row Key
   */
  def nativeKeyConvert(rawKey: Option[HBaseRawType]): Seq[Any] = {
    if (rawKey.isEmpty) Nil
    else {
      val finalRowKey = getFinalKey(rawKey)

      HBaseKVHelper.decodingRawKeyColumns(finalRowKey, keyColumns).
        zipWithIndex.map(pi => DataTypeUtils.bytesToData(finalRowKey,
        pi._1._1, pi._1._2, keyColumns(pi._2).dataType))
    }
  }
}

