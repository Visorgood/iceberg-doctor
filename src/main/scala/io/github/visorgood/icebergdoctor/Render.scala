package io.github.visorgood.icebergdoctor

import org.apache.iceberg.{PartitionSpec, Schema, SortOrder}

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

import scala.jdk.CollectionConverters.*

/** How a value is presented as lines of text.
  *
  * Contravariant in `A` because `A` only ever appears in parameter position: a renderer that
  * handles any `Failure` handles a `Failure.TableNotFound` too, and the enum's cases have their
  * own types, so without this every call site would need an ascription.
  *
  * Kept separate from the domain values so that a second output format — `--json` — becomes
  * another interpreter of the same value, not a second set of functions.
  */
trait Render[-A]:
  def lines(value: A): List[String]

object Render:

  def apply[A](using render: Render[A]): Render[A] = render

  /** Says how much `--limit` cut off, when it cut anything. */
  def limitNote(total: Int, limit: Int): Option[String] =
    Option.when(limit > 0 && total > limit)(
      s"showing $limit of $total — raise --limit to see the rest"
    )

  extension [A](value: A)(using render: Render[A]) def lines: List[String] = render.lines(value)

  // -- shared layout helpers -------------------------------------------------

  /** Pads cells so columns line up, then trims the trailing padding of the last one.
    *
    * Short rows are filled out with empty cells first: `transpose` requires every row to be the
    * same length and throws otherwise, which would turn a missing optional column into a crash.
    */
  private[icebergdoctor] def columns(rows: List[List[String]]): List[String] =
    if rows.isEmpty then Nil
    else
      val filled = rows.map(_.padTo(rows.map(_.length).max, ""))
      val widths = filled.transpose.map(_.map(_.length).max)
      filled.map { row =>
        row.zip(widths).map((cell, width) => cell.padTo(width, ' ')).mkString("  ").stripTrailing
      }

  private def indent(lines: List[String]): List[String] = lines.map("  " + _)

  private val timestamp =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

  private def at(epochMs: Long): String = timestamp.format(Instant.ofEpochMilli(epochMs))

  private def count(value: Long): String = f"$value%,d".replace(',', ' ')

  private val dash = "\u2014"

  /** Milliseconds as the coarsest whole unit that divides them: `7d`, `12h`, `90s`. */
  private def duration(ms: Long): String =
    List("d" -> 86400000L, "h" -> 3600000L, "m" -> 60000L, "s" -> 1000L)
      .collectFirst { case (unit, size) if ms >= size && ms % size == 0 => s"${ms / size}$unit" }
      .getOrElse(s"${ms}ms")

  /** A commit's change to a counter: `+3`, `+8 -8`, or nothing at all. */
  private def delta(added: Option[Long], removed: Option[Long]): String =
    val parts = List(
      added.filter(_ > 0).map(value => s"+${count(value)}"),
      removed.filter(_ > 0).map(value => s"-${count(value)}")
    ).flatten
    if parts.isEmpty then dash else parts.mkString(" ")

  /** Binary units, because that is what Iceberg's own size properties are counted in. */
  private def bytes(value: Long): String =
    val units = List("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    val scale = math.min(if value <= 0 then 0 else (math.log(value.toDouble) / math.log(1024)).toInt, units.size - 1)
    if scale == 0 then s"$value B"
    else f"${value / math.pow(1024, scale.toDouble)}%.1f ${units(scale)}"

  /** Joins non-empty groups with a blank line between them. */
  private def sections(groups: List[List[String]]): List[String] =
    groups.filter(_.nonEmpty).reduceLeftOption(_ ::: "" :: _).getOrElse(Nil)

  // -- instances -------------------------------------------------------------

  /** A whole listing at once, so columns can be aligned across its rows. */
  given catalogListing: Render[List[CatalogEntry]] = new Render[List[CatalogEntry]]:
    def lines(entries: List[CatalogEntry]): List[String] =
      columns(entries.map(entry => List(entry.name, entry.kind.toString.toLowerCase)))

  /** Two lines at most: what went wrong, then what to do about it. */
  given failure: Render[Failure] = new Render[Failure]:
    def lines(value: Failure): List[String] = value match
      case Failure.TableNotFound(table, warehouse) =>
        List(
          problem(s"table not found: $table"),
          advice(s"list what is there: ${lsCommand(warehouse, Failure.parentOf(table))}")
        )
      case Failure.NamespaceNotFound(namespace, warehouse) =>
        List(
          problem(s"namespace not found: $namespace"),
          advice(s"list what is there: ${lsCommand(warehouse, Failure.parentOf(namespace))}")
        )
      case Failure.WarehouseNotFound(path) =>
        List(problem(s"not a warehouse: $path"), advice("no such directory"))
      case Failure.StorageUnreadable(detail) =>
        List(problem("cannot read from storage"), advice(detail))
      case Failure.Unexpected(kind, detail) =>
        // No verdict on whose fault it is: a permission or network problem lands here too.
        List(problem(s"unexpected $kind"), advice(detail))

  private def problem(text: String): String = s"error: $text"
  private def advice(text: String): String  = s"       $text"

  private def lsCommand(warehouse: String, namespace: String): String =
    if namespace.isEmpty then s"${Cli.ProgramName} ls $warehouse"
    else s"${Cli.ProgramName} ls $warehouse $namespace"

  given refListing: Render[List[RefRow]] = new Render[List[RefRow]]:
    def lines(rows: List[RefRow]): List[String] =
      if rows.isEmpty then List("no refs")
      else
        val header =
          List("NAME", "TYPE", "SNAPSHOT ID", "MIN SNAPSHOTS", "MAX SNAPSHOT AGE", "MAX REF AGE")
        val body = rows.map { row =>
          List(
            row.name,
            row.kind.toString.toLowerCase,
            row.snapshotId.toString,
            // Unset means the table's history.expire.* property applies instead; showing the
            // resolved value here would hide which of the two a reader is looking at.
            row.minSnapshotsToKeep.fold(dash)(_.toString),
            row.maxSnapshotAgeMs.fold(dash)(duration),
            row.maxRefAgeMs.fold(dash)(duration)
          )
        }
        columns(header :: body)

  /** Reads as a timeline: newest first, one row per commit, header included. */
  given snapshotHistory: Render[List[SnapshotRow]] = new Render[List[SnapshotRow]]:
    def lines(rows: List[SnapshotRow]): List[String] =
      if rows.isEmpty then List("no snapshots — the table has never been written to")
      else
        val header =
          List("SNAPSHOT ID", "PARENT", "COMMITTED", "OPERATION", "SEQ", "FILES", "RECORDS", "ENGINE", "REFS")
        val body = rows.map { row =>
          List(
            row.id.toString,
            row.parentId.fold(dash)(_.toString),
            at(row.timestampMs),
            row.operation,
            row.sequenceNumber.toString,
            delta(row.addedFiles, row.removedFiles),
            delta(row.addedRecords, row.removedRecords),
            row.engine.getOrElse(dash),
            if row.refs.isEmpty then "" else row.refs.mkString(", ")
          )
        }
        columns(header :: body)

  // Anonymous givens are named after the type constructor, so two Render[List[?]]
// instances would both be called given_Render_List. Name them.
  given anyList[A](using inner: Render[A]): Render[List[A]] = new Render[List[A]]:
    def lines(values: List[A]): List[String] = values.flatMap(inner.lines)

  given tableLayout: Render[TableLayout] = new Render[TableLayout]:
    def lines(layout: TableLayout): List[String] =
      val storage = "STORAGE" :: indent(
        columns(
          List(
            List("format version", layout.formatVersion.toString),
            List("location", layout.location),
            List("uuid", layout.uuid),
            List("last updated", at(layout.lastUpdatedMs)),
            List("snapshots", layout.snapshotCount.toString),
            List("refs", if layout.refs.isEmpty then "none" else layout.refs.mkString(", "))
          )
        )
      )
      val snapshot = layout.current match
        case None =>
          List("SNAPSHOT  none — the table has never been written to")
        case Some(current) =>
          def row(label: String, value: Option[String]) = value.map(List(label, _)).toList
          "SNAPSHOT" :: indent(
            columns(
              List(
                // A snapshot id is too long to sit in a heading the way a schema id does.
                List("current-snapshot-id", current.id.toString),
                List("committed", at(current.timestampMs)),
                List("operation", current.operation)
              ) ::: row("data files", current.dataFiles.map(count))
                ::: row("delete files", current.deleteFiles.map(count))
                ::: row("records", current.records.map(count))
                ::: row("total size", current.sizeInBytes.map(bytes))
            )
          )
      sections(List(storage, snapshot))

  given tableDescription: Render[TableDescription] = new Render[TableDescription]:
    def lines(description: TableDescription): List[String] =
      sections(
        List(
          List(description.name),
          schemaLines(description.schema),
          specLines(description.spec, description.schema),
          sortOrderLines(description.sortOrder, description.schema),
          propertyLines(description.properties),
          Render[TableLayout].lines(description.layout)
        )
      )

  // -- table description sections --------------------------------------------

  private def schemaLines(schema: Schema): List[String] =
    // Top-level fields only. A struct, list or map column prints its full type string; laying
    // nested fields out properly is its own problem and is not solved here.
    val fields = schema.columns.asScala.toList.map { field =>
      List(
        field.fieldId.toString,
        field.name,
        field.`type`.toString,
        if field.isRequired then "required" else "optional",
        Option(field.doc).getOrElse("")
      )
    }
    val identifiers = schema.identifierFieldNames.asScala.toList.sorted
    val identifierLine =
      if identifiers.isEmpty then Nil else List(s"identifier  ${identifiers.mkString(", ")}")
    s"SCHEMA  current-schema-id ${schema.schemaId}" :: indent(columns(fields) ::: identifierLine)

  private def specLines(spec: PartitionSpec, schema: Schema): List[String] =
    if spec.isUnpartitioned then List("PARTITION SPEC  unpartitioned")
    else
      val fields = spec.fields.asScala.toList.map { field =>
        val source = schema.findColumnName(field.sourceId)
        List(field.fieldId.toString, field.name, s"${field.transform}($source)")
      }
      s"PARTITION SPEC  default-spec-id ${spec.specId}" :: indent(columns(fields))

  private def sortOrderLines(order: SortOrder, schema: Schema): List[String] =
    if order.isUnsorted then List("SORT ORDER  unsorted")
    else
      val fields = order.fields.asScala.toList.map { field =>
        val source = schema.findColumnName(field.sourceId)
        val term   = if field.transform.isIdentity then source else s"${field.transform}($source)"
        // NullOrder renders as "NULLS FIRST" / "NULLS LAST" — Iceberg's own spelling, kept.
        List(term, field.direction.toString.toLowerCase, field.nullOrder.toString.toLowerCase)
      }
      s"SORT ORDER  default-sort-order-id ${order.orderId}" :: indent(columns(fields))

  private def propertyLines(properties: Map[String, String]): List[String] =
    if properties.isEmpty then List("PROPERTIES  none set")
    else
      val rows = properties.toList.sorted.map((key, value) => List(key, value))
      "PROPERTIES" :: indent(columns(rows))
