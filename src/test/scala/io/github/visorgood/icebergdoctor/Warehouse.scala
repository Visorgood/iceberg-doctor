package io.github.visorgood.icebergdoctor

import org.apache.hadoop.conf.Configuration
import org.apache.iceberg.{DataFiles, FileFormat, PartitionSpec, Schema, SortOrder, Table}
import org.apache.iceberg.catalog.{Namespace, TableIdentifier}
import org.apache.iceberg.hadoop.HadoopCatalog
import org.apache.iceberg.types.Types

import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Builds a throwaway Hadoop catalog on the local filesystem.
  *
  * To get a warehouse you can point the CLI at by hand:
  * {{{
  * sbt "Test/runMain io.github.visorgood.icebergdoctor.Warehouse /tmp/wh"
  * }}}
  */
object Warehouse:

  /** Namespace levels paired with the tables created inside them, in creation order.
    *
    * Nested (`prod.events`) and flat (`staging`) namespaces are both present because
    * HadoopCatalog treats them as directories and the difference has bitten us before.
    */
  val layout: List[(List[String], List[String])] = List(
    List("prod")           -> Nil,
    List("prod", "events") -> List("clicks", "impressions"),
    List("staging")        -> List("raw")
  )

  /** The one table that carries a partition spec, a sort order and properties.
    *
    * The others are left bare so that both shapes are covered — `describe` has to render an
    * unpartitioned, unsorted table without properties just as readably.
    */
  val configured: TableIdentifier =
    TableIdentifier.of(Namespace.of("prod", "events"), "clicks")

  /** A table left with no spec, no sort order and no properties of its own. */
  val plain: TableIdentifier =
    TableIdentifier.of(Namespace.of("prod", "events"), "impressions")

  val schema: Schema = new Schema(
    Types.NestedField.required(1, "id", Types.LongType.get()),
    Types.NestedField.optional(2, "event_ts", Types.TimestampType.withZone()),
    Types.NestedField.optional(3, "country", Types.StringType.get())
  )

  private def specOf(s: Schema) =
    PartitionSpec.builderFor(s).day("event_ts").identity("country").build()

  private def sortOrderOf(s: Schema) =
    SortOrder.builderFor(s).asc("country").desc("event_ts").build()

  /** Real Iceberg property names, so the values mean something to `diagnose` later. */
  val properties: Map[String, String] = Map(
    "write.target-file-size-bytes"       -> "134217728",
    "history.expire.max-snapshot-age-ms" -> "604800000"
  )

  def open(warehouse: Path): HadoopCatalog =
    val catalog = new HadoopCatalog()
    catalog.setConf(new Configuration())
    catalog.initialize("test", Map("warehouse" -> warehouse.toString).asJava)
    catalog

  /** Creates the namespaces and tables of [[layout]] under an existing directory. */
  def populate(warehouse: Path): Unit =
    Using.resource(open(warehouse)) { catalog =>
      for (levels, tables) <- layout do
        val ns = Namespace.of(levels*)
        catalog.createNamespace(ns)
        for table <- tables do
          val id = TableIdentifier.of(ns, table)
          if id == configured then
            val table = catalog
              .buildTable(id, schema)
              .withPartitionSpec(specOf(schema))
              .withSortOrder(sortOrderOf(schema))
              .withProperties(properties.asJava)
              .create()
            appendFiles(table, day = "2026-09-06", count = 2)
            appendFiles(table, day = "2026-09-07", count = 3)
          else catalog.createTable(id, schema)
    }

  /** Commits metadata about data files that do not exist.
    *
    * Nothing reads those files: the counts and sizes `R3` reports come from the totals Iceberg
    * keeps in each snapshot summary. This gives the fixture real snapshots without a Parquet
    * writer, which would mean two more dependencies.
    */
  private def appendFiles(table: Table, day: String, count: Int): Unit =
    val append = table.newAppend()
    for i <- 0 until count do
      append.appendFile(
        DataFiles
          .builder(table.spec)
          .withPath(s"${table.location}/data/$day-$i.parquet")
          .withPartitionPath(s"event_ts_day=$day/country=US")
          .withFileSizeInBytes((i + 1) * 4L * 1024 * 1024)
          .withRecordCount((i + 1) * 10_000L)
          .withFormat(FileFormat.PARQUET)
          .build()
      )
    append.commit()

  /** A populated warehouse in a fresh temp directory. The caller deletes it. */
  def createTemporary(): Path =
    val dir = Files.createTempDirectory("iceberg-doctor-")
    populate(dir)
    dir

  def deleteRecursively(dir: Path): Unit =
    if Files.exists(dir) then
      Using.resource(Files.walk(dir))(_.sorted(Comparator.reverseOrder()).forEach(Files.delete(_)))

  def main(args: Array[String]): Unit =
    args.toList match
      case dir :: Nil =>
        val path = Files.createDirectories(Path.of(dir))
        populate(path)
        println(s"populated $path")
      case _ =>
        println("usage: Warehouse <directory>")
