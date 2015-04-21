package org.apache.spark.sql.hive

import org.apache.spark.sql.catalyst.analysis.OverrideCatalog

class Catalog(hive: HiveContext) extends HiveMetastoreCatalog(hive) with OverrideCatalog
