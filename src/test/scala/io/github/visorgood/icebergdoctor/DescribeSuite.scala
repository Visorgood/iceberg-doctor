package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.hadoop.HadoopCatalog
import org.apache.iceberg.types.Types
import org.apache.iceberg.{PartitionSpec, Schema, SortOrder}

import java.nio.file.Path

/** Tests `Main.describe` and the `Render` instances behind it — R2 and R3. */
class DescribeSuite extends munit.FunSuite:

  private case class Fixture(dir: Path, catalog: HadoopCatalog)

  private val warehouse = FunFixture[Fixture](
    setup = _ =>
      val dir = Warehouse.createTemporary()
      Fixture(dir, Warehouse.open(dir))
    ,
    teardown = fixture =>
      fixture.catalog.close()
      Warehouse.deleteRecursively(fixture.dir)
  )

  // -- rendering is pure, so most cases need no catalog -----------------------

  private val simpleSchema = new Schema(
    Types.NestedField.required(1, "id", Types.LongType.get()),
    Types.NestedField.optional(2, "name", Types.StringType.get())
  )

  private val neverWritten = TableLayout(
    formatVersion = 2,
    location = "file:/wh/db/plain",
    uuid = "3f1b0c8a-0000-0000-0000-000000000001",
    lastUpdatedMs = 1788782400000L, // 2026-09-07 12:00:00Z
    snapshotCount = 0,
    refs = Nil,
    current = None
  )

  private def description(
      layout: TableLayout = neverWritten,
      schema: Schema = simpleSchema,
      properties: Map[String, String] = Map.empty
  ) =
    TableDescription("db.plain", layout, schema, PartitionSpec.unpartitioned, SortOrder.unsorted, properties)

  test("a table with nothing configured still renders every section") {
    assertEquals(
      description().lines,
      List(
        "db.plain",
        "",
        "SCHEMA  current-schema-id 0",
        "  1  id    long    required",
        "  2  name  string  optional",
        "",
        "PARTITION SPEC  unpartitioned",
        "",
        "SORT ORDER  unsorted",
        "",
        "PROPERTIES  none set",
        "",
        "STORAGE",
        "  format version  2",
        "  location        file:/wh/db/plain",
        "  uuid            3f1b0c8a-0000-0000-0000-000000000001",
        "  last updated    2026-09-07 12:00:00Z",
        "  snapshots       0",
        "  refs            none",
        "",
        "SNAPSHOT  none — the table has never been written to"
      )
    )
  }

  test("a snapshot renders its totals in Iceberg's own units") {
    val written = neverWritten.copy(
      snapshotCount = 2,
      refs = List("main"),
      current = Some(
        CurrentSnapshot(
          id = 8231847263847L,
          timestampMs = 1788782400000L,
          operation = "append",
          dataFiles = Some(5),
          deleteFiles = Some(0),
          records = Some(150000),
          sizeInBytes = Some(62914560) // 60 MiB
        )
      )
    )
    val rendered = description(layout = written).lines
    assertEquals(
      rendered.dropWhile(_ != "SNAPSHOT"),
      List(
        "SNAPSHOT",
        "  current-snapshot-id  8231847263847",
        "  committed            2026-09-07 12:00:00Z",
        "  operation            append",
        "  data files           5",
        "  delete files         0",
        "  records              150 000",
        "  total size           60.0 MiB"
      )
    )
  }

  test("a total missing from the summary is left out rather than guessed") {
    val partial = neverWritten.copy(current =
      Some(
        CurrentSnapshot(1L, 1788782400000L, "append", dataFiles = Some(3), None, None, None)
      )
    )
    val rendered = partial.lines
    assert(rendered.contains("  data files           3"), rendered.mkString("\n"))
    assert(!rendered.exists(_.contains("records")), rendered.mkString("\n"))
  }

  test("properties are sorted by key and aligned") {
    val rendered = description(properties =
      Map("write.target-file-size-bytes" -> "134217728", "gc.enabled" -> "true")
    ).lines
    assertEquals(
      rendered.dropWhile(_ != "PROPERTIES").takeWhile(_.nonEmpty),
      List(
        "PROPERTIES",
        "  gc.enabled                    true",
        "  write.target-file-size-bytes  134217728"
      )
    )
  }

  test("identifier fields are listed when the schema has them") {
    val keyed = new Schema(
      java.util.List.of(
        Types.NestedField.required(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "name", Types.StringType.get())
      ),
      java.util.Set.of(Integer.valueOf(1))
    )
    val rendered = description(schema = keyed).lines
    assert(rendered.contains("  identifier  id"), rendered.mkString("\n"))
  }

  // -- describe against a real catalog ---------------------------------------

  warehouse.test("describe reads the spec, sort order and properties of a table") { fixture =>
    val described = Main.describe(fixture.catalog, Warehouse.configured)
    val rendered  = described.lines

    assertEquals(described.name, "prod.events.clicks")
    assert(rendered.contains("  1000  event_ts_day  day(event_ts)"), rendered.mkString("\n"))
    assert(rendered.contains("  1001  country       identity(country)"), rendered.mkString("\n"))
    // Sort order shows the plain column name; the identity transform is not spelled out.
    assert(rendered.contains("  country   asc   nulls first"), rendered.mkString("\n"))
    assert(rendered.contains("  event_ts  desc  nulls last"), rendered.mkString("\n"))
    Warehouse.properties.foreach { (key, value) =>
      assert(described.properties.get(key).contains(value), s"$key missing from properties")
    }
  }

  warehouse.test("the layout comes from metadata.json, with the summary totals") { fixture =>
    val layout = Main.describe(fixture.catalog, Warehouse.configured).layout

    assertEquals(layout.formatVersion, 2)
    assertEquals(layout.refs, List("main"))
    // The fixture commits two appends, so there are two snapshots.
    assertEquals(layout.snapshotCount, 2)

    val current = layout.current.getOrElse(fail("expected a current snapshot"))
    assertEquals(current.operation, "append")
    assertEquals(current.dataFiles, Some(5L))   // 2 + 3 across the two commits
    assertEquals(current.deleteFiles, Some(0L))
    assertEquals(current.records, Some(90000L)) // (10k+20k) + (10k+20k+30k)
    assertEquals(current.sizeInBytes, Some((4L + 8 + 4 + 8 + 12) * 1024 * 1024))
  }

  warehouse.test("a table that was never written to has no current snapshot") { fixture =>
    val layout = Main.describe(fixture.catalog, Warehouse.plain).layout
    assertEquals(layout.current, None)
    assertEquals(layout.snapshotCount, 0)
    assert(layout.location.endsWith("prod/events/impressions"), layout.location)
  }
