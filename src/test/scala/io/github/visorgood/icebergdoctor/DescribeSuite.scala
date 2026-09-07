package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.hadoop.HadoopCatalog
import org.apache.iceberg.types.Types
import org.apache.iceberg.{PartitionSpec, Schema, SortOrder}

import java.nio.file.Path

/** Tests `Main.describe` and the `Render` instance for `TableDescription` — R2. */
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

  // -- renderDescription is a pure function, so most cases need no catalog ----

  private val simpleSchema = new Schema(
    Types.NestedField.required(1, "id", Types.LongType.get()),
    Types.NestedField.optional(2, "name", Types.StringType.get())
  )

  test("a table with nothing configured still renders every section") {
    val bare = TableDescription(
      name = "db.plain",
      schema = simpleSchema,
      spec = PartitionSpec.unpartitioned,
      sortOrder = SortOrder.unsorted,
      properties = Map.empty
    )
    assertEquals(
      bare.lines.toList,
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
        "PROPERTIES  none set"
      )
    )
  }

  test("properties are sorted by key and aligned") {
    val described = TableDescription(
      "db.t",
      simpleSchema,
      PartitionSpec.unpartitioned,
      SortOrder.unsorted,
      Map("write.target-file-size-bytes" -> "134217728", "gc.enabled" -> "true")
    )
    val rendered = described.lines.toList
    assertEquals(
      rendered.dropWhile(_ != "PROPERTIES  2 set"),
      List(
        "PROPERTIES  2 set",
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
    val rendered = TableDescription("db.t", keyed, PartitionSpec.unpartitioned, SortOrder.unsorted, Map.empty).lines.toList
    assert(rendered.contains("  identifier  id"), rendered.mkString("\n"))
  }

  // -- describe against a real catalog ---------------------------------------

  warehouse.test("describe reads the spec, sort order and properties of a table") { fixture =>
    val described = Main.describe(fixture.catalog, Warehouse.configured)
    val rendered  = described.lines.toList

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

  warehouse.test("a table created without a spec or sort order describes as bare") { fixture =>
    val described = Main.describe(fixture.catalog, Warehouse.plain)
    val rendered  = described.lines.toList
    assert(rendered.contains("PARTITION SPEC  unpartitioned"), rendered.mkString("\n"))
    assert(rendered.contains("SORT ORDER  unsorted"), rendered.mkString("\n"))
  }
