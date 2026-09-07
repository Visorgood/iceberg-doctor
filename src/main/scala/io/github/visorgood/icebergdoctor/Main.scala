package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.hadoop.conf.Configuration
import org.apache.iceberg.catalog.{Namespace, TableIdentifier}
import org.apache.iceberg.hadoop.HadoopCatalog
import org.apache.iceberg.{HasTableOperations, PartitionSpec, Schema, SortOrder, Table}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** One row of `ls`: something that lives in a catalog, named in full.
  *
  * Iceberg has no noun for "a namespace or a table" — it lists them through separate calls — so
  * this is ours. Full names mean every row can be pasted straight into another command.
  */
final case class CatalogEntry(name: String, kind: CatalogEntry.Kind)

object CatalogEntry:
  /** Declared namespace-first so sorting groups namespaces above tables. */
  enum Kind:
    case Namespace, Table

/** The snapshot a table currently points at, with the running totals Iceberg keeps in its
  * summary. The totals are optional because a summary is only as complete as its writer made it.
  */
final case class CurrentSnapshot(
    id: Long,
    timestampMs: Long,
    operation: String,
    dataFiles: Option[Long],
    deleteFiles: Option[Long],
    records: Option[Long],
    sizeInBytes: Option[Long]
)

/** The physical shape of a table (R3).
  *
  * Every field here comes out of `metadata.json`: Iceberg maintains the file and byte totals in
  * each snapshot summary, so no manifest is read to produce this.
  */
final case class TableLayout(
    formatVersion: Int,
    location: String,
    uuid: String,
    lastUpdatedMs: Long,
    snapshotCount: Int,
    refs: List[String],
    current: Option[CurrentSnapshot]
)

/** The logical shape of a table (R2).
  *
  * Iceberg's own `Schema`, `PartitionSpec` and `SortOrder` are carried as-is: we only read
  * fields off them, so mirroring them in Scala would duplicate the spec for no gain.
  */
final case class TableDescription(
    name: String,
    layout: TableLayout,
    schema: Schema,
    spec: PartitionSpec,
    sortOrder: SortOrder,
    properties: Map[String, String]
)

/** First cuts at R1 (`ls`) and R2 (`describe`).
  *
  * Deliberately not general yet — one catalog type, no output formats.
  */
object Main:

  private def openCatalog(warehouse: String): HadoopCatalog =
    val catalog = new HadoopCatalog()
    catalog.setConf(new Configuration())
    catalog.initialize("local", Map("warehouse" -> warehouse).asJava)
    catalog

  // ---------------------------------------------------------------- R1: ls

  /** What sits directly inside `ns` — one level only, like `ls` itself.
    *
    * Sorted because the catalog returns filesystem order, so which rows `--limit` keeps would
    * otherwise vary between runs.
    */
  private[icebergdoctor] def list(catalog: HadoopCatalog, ns: Namespace): List[CatalogEntry] =
    val namespaces = catalog
      .listNamespaces(ns)
      .asScala
      .toList
      .map(child => CatalogEntry(child.toString, CatalogEntry.Kind.Namespace))

    // HadoopCatalog rejects listTables on the root namespace — in its layout a table always
    // lives inside a namespace directory, never at the warehouse root.
    val tables =
      if ns.isEmpty then Nil
      else
        catalog
          .listTables(ns)
          .asScala
          .toList
          .map(id => CatalogEntry(id.toString, CatalogEntry.Kind.Table))

    (namespaces ::: tables).sortBy(entry => (entry.kind.ordinal, entry.name))

  /** Says how much the limit cut off, when it cut anything. */
  private[icebergdoctor] def limitNote(total: Int, limit: Int): Option[String] =
    Option.when(limit > 0 && total > limit)(
      s"showing $limit of $total — raise --limit to see the rest"
    )

  // ---------------------------------------------------------- R2: describe

  private def layoutOf(table: Table): TableLayout =
    // formatVersion, uuid and lastUpdatedMillis live on TableMetadata, which `Table` itself does
    // not expose; every catalog-loaded table implements HasTableOperations to reach it.
    val metadata = table.asInstanceOf[HasTableOperations].operations.current
    TableLayout(
      formatVersion = metadata.formatVersion,
      location = table.location,
      uuid = metadata.uuid,
      lastUpdatedMs = metadata.lastUpdatedMillis,
      snapshotCount = table.snapshots.asScala.size,
      refs = table.refs.asScala.keys.toList.sorted,
      current = Option(table.currentSnapshot).map { snapshot =>
        val summary = snapshot.summary.asScala
        def total(key: String) = summary.get(key).flatMap(_.toLongOption)
        CurrentSnapshot(
          id = snapshot.snapshotId,
          timestampMs = snapshot.timestampMillis,
          operation = snapshot.operation,
          dataFiles = total("total-data-files"),
          deleteFiles = total("total-delete-files"),
          records = total("total-records"),
          sizeInBytes = total("total-files-size")
        )
      }
    )

  private[icebergdoctor] def describe(
      catalog: HadoopCatalog,
      id: TableIdentifier
  ): TableDescription =
    val table = catalog.loadTable(id)
    TableDescription(
      name = id.toString,
      layout = layoutOf(table),
      schema = table.schema,
      spec = table.spec,
      sortOrder = table.sortOrder,
      properties = table.properties.asScala.toMap
    )

  // -------------------------------------------------------------- dispatch

  private def parseNamespace(text: String): Namespace =
    Namespace.of(text.split('.').filter(_.nonEmpty)*)

  private def run(invocation: Invocation): Unit = invocation match
    case Invocation.Ls(warehouse, namespace, limit) =>
      Using.resource(openCatalog(warehouse)) { catalog =>
        val entries = list(catalog, namespace.fold(Namespace.empty)(parseNamespace))
        val shown   = if limit <= 0 then entries else entries.take(limit)
        shown.lines.foreach(println)
        limitNote(entries.size, limit).foreach(println)
      }

    case Invocation.Describe(warehouse, table) =>
      Using.resource(openCatalog(warehouse)) { catalog =>
        describe(catalog, TableIdentifier.parse(table)).lines.foreach(println)
      }

  def main(args: Array[String]): Unit =
    Cli.parse(args.toList) match
      case Right(invocation) => run(invocation)
      // decline returns Help for both `--help` and a parse failure; only the latter has errors.
      case Left(help) if help.errors.isEmpty => println(help)
      case Left(help) =>
        System.err.println(help)
        sys.exit(2)
