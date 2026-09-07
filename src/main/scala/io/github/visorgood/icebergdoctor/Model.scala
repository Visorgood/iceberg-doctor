package io.github.visorgood.icebergdoctor

import org.apache.iceberg.{PartitionSpec, Schema, SortOrder}

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

/** One snapshot in a table's history (R4), with what that commit changed.
  *
  * The counts here are per-commit deltas from the snapshot summary, not the running totals of
  * [[CurrentSnapshot]] — a snapshot records both, and confusing them is easy.
  */
final case class SnapshotRow(
    id: Long,
    parentId: Option[Long],
    sequenceNumber: Long,
    timestampMs: Long,
    operation: String,
    addedFiles: Option[Long],
    removedFiles: Option[Long],
    addedRecords: Option[Long],
    removedRecords: Option[Long],
    engine: Option[String],
    refs: List[String]
)

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

/** The logical shape of a table (R2), with its physical shape attached (R3).
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
