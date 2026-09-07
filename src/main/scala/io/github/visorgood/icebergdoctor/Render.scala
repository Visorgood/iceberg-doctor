package io.github.visorgood.icebergdoctor

import org.apache.iceberg.{PartitionSpec, Schema, SortOrder}

import scala.jdk.CollectionConverters.*

/** How a value is presented as lines of text.
  *
  * Kept separate from the domain values so that a second output format — `--json` — becomes
  * another interpreter of the same value, not a second set of functions.
  */
trait Render[A]:
  def lines(value: A): List[String]

object Render:

  def apply[A](using render: Render[A]): Render[A] = render

  extension [A](value: A)(using render: Render[A]) def lines: List[String] = render.lines(value)

  // -- shared layout helpers -------------------------------------------------

  /** Pads cells so columns line up, then trims the trailing padding of the last one. */
  private def columns(rows: List[List[String]]): List[String] =
    if rows.isEmpty then Nil
    else
      val widths = rows.transpose.map(_.map(_.length).max)
      rows.map { row =>
        row.zip(widths).map((cell, width) => cell.padTo(width, ' ')).mkString("  ").stripTrailing
      }

  private def indent(lines: List[String]): List[String] = lines.map("  " + _)

  /** Joins non-empty groups with a blank line between them. */
  private def sections(groups: List[List[String]]): List[String] =
    groups.filter(_.nonEmpty).reduceLeftOption(_ ::: "" :: _).getOrElse(Nil)

  // -- instances -------------------------------------------------------------

  /** A whole listing at once, so columns can be aligned across its rows. */
  given Render[List[CatalogEntry]] = new Render[List[CatalogEntry]]:
    def lines(entries: List[CatalogEntry]): List[String] =
      columns(entries.map(entry => List(entry.name, entry.kind.toString.toLowerCase)))

  given [A](using inner: Render[A]): Render[List[A]] = new Render[List[A]]:
    def lines(values: List[A]): List[String] = values.flatMap(inner.lines)

  given Render[TableDescription] = new Render[TableDescription]:
    def lines(description: TableDescription): List[String] =
      sections(
        List(
          List(description.name),
          schemaLines(description.schema),
          specLines(description.spec, description.schema),
          sortOrderLines(description.sortOrder, description.schema),
          propertyLines(description.properties)
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
      s"PROPERTIES  ${properties.size} set" :: indent(columns(rows))
