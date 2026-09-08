package io.github.visorgood.icebergdoctor

import org.apache.hadoop.conf.Configuration
import org.apache.iceberg.catalog.{Catalog, Namespace, SupportsNamespaces, TableIdentifier}
import org.apache.iceberg.hadoop.HadoopCatalog
import org.apache.iceberg.{HasTableOperations, SnapshotRef, Table}

import scala.jdk.CollectionConverters.*

/** The boundary with the Iceberg Java API.
  *
  * Everything that reaches into Iceberg lives here; the rest of the application works with the
  * values in `Model.scala`. Functions take the narrowest Iceberg interface they actually use, so
  * a REST catalog will drop in without touching them — only [[hadoopCatalog]] is Hadoop-specific.
  */
object Iceberg:

  def hadoopCatalog(warehouse: String): HadoopCatalog =
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
  def list(catalog: Catalog & SupportsNamespaces, ns: Namespace): List[CatalogEntry] =
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

  // ------------------------------------------------------- R2 + R3: describe

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

  def describe(catalog: Catalog, id: TableIdentifier): TableDescription =
    val table = catalog.loadTable(id)
    TableDescription(
      name = id.toString,
      layout = layoutOf(table),
      schema = table.schema,
      spec = table.spec,
      sortOrder = table.sortOrder,
      properties = table.properties.asScala.toMap
    )

  // -------------------------------------------------------------- R5: refs

  /** Branches and tags, `main` first, then the rest of the branches, then the tags. */
  def refs(catalog: Catalog, id: TableIdentifier): List[RefRow] =
    catalog
      .loadTable(id)
      .refs
      .asScala
      .toList
      .map { (name, ref) =>
        RefRow(
          name = name,
          kind = if ref.isBranch then RefRow.Kind.Branch else RefRow.Kind.Tag,
          snapshotId = ref.snapshotId,
          // A tag carries only maxRefAgeMs; the other two are branch-only and stay null there.
          minSnapshotsToKeep = Option(ref.minSnapshotsToKeep).map(_.intValue),
          maxSnapshotAgeMs = Option(ref.maxSnapshotAgeMs).map(_.longValue),
          maxRefAgeMs = Option(ref.maxRefAgeMs).map(_.longValue)
        )
      }
      // MAIN_BRANCH is Iceberg's own name for the default ref, so putting it first is not a
      // preference of ours — it is the one every other ref is measured against.
      .sortBy(row => (row.kind.ordinal, if row.name == SnapshotRef.MAIN_BRANCH then 0 else 1, row.name))

  // --------------------------------------------------------- R4: snapshots

  /** The whole history, newest first. Everything comes from `metadata.json`. */
  def snapshots(catalog: Catalog, id: TableIdentifier): List[SnapshotRow] =
    val table = catalog.loadTable(id)
    // A snapshot does not know which refs point at it, so invert the table's ref map once.
    val refsBySnapshot = table.refs.asScala.toList
      .groupMap((_, ref) => ref.snapshotId)((name, _) => name)
      .view
      .mapValues(_.sorted)
      .toMap

    table.snapshots.asScala.toList
      .map { snapshot =>
        val summary = snapshot.summary.asScala
        def delta(key: String) = summary.get(key).flatMap(_.toLongOption)
        SnapshotRow(
          id = snapshot.snapshotId,
          parentId = Option(snapshot.parentId).map(_.longValue),
          sequenceNumber = snapshot.sequenceNumber,
          timestampMs = snapshot.timestampMillis,
          operation = snapshot.operation,
          addedFiles = delta("added-data-files"),
          removedFiles = delta("deleted-data-files"),
          addedRecords = delta("added-records"),
          removedRecords = delta("deleted-records"),
          // Only engines stamp this; a commit made through the Java API leaves it unset.
          engine = summary.get("engine-name").map { name =>
            summary.get("engine-version").fold(name)(version => s"$name $version")
          },
          refs = refsBySnapshot.getOrElse(snapshot.snapshotId, Nil)
        )
      }
      .sortBy(row => (row.timestampMs, row.sequenceNumber))
      .reverse
