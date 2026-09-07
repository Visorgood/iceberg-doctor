package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.CatalogEntry.Kind
import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.hadoop.HadoopCatalog

import java.nio.file.Path

/** Tests `Main.list`, `Main.limitNote` and the `Render` instance for a listing — R1. */
class MainSuite extends munit.FunSuite:

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

  // -- listing one level at a time -------------------------------------------

  warehouse.test("the root lists top-level namespaces only") { fixture =>
    assertEquals(
      Iceberg.list(fixture.catalog, Namespace.empty),
      List(CatalogEntry("prod", Kind.Namespace), CatalogEntry("staging", Kind.Namespace))
    )
  }

  warehouse.test("a namespace lists its own children, not its grandchildren") { fixture =>
    assertEquals(
      Iceberg.list(fixture.catalog, Namespace.of("prod")),
      List(CatalogEntry("prod.events", Kind.Namespace))
    )
  }

  warehouse.test("tables are listed with their full name") { fixture =>
    assertEquals(
      Iceberg.list(fixture.catalog, Namespace.of("prod", "events")),
      List(
        CatalogEntry("prod.events.clicks", Kind.Table),
        CatalogEntry("prod.events.impressions", Kind.Table)
      )
    )
  }

  test("namespaces sort above tables, so the limit keeps the same rows each run") {
    // The catalog returns filesystem order; the ordering below is ours.
    val mixed = List(
      CatalogEntry("a.t", Kind.Table),
      CatalogEntry("a.z", Kind.Namespace),
      CatalogEntry("a.b", Kind.Namespace)
    ).sortBy(entry => (entry.kind.ordinal, entry.name))
    assertEquals(mixed.map(_.name), List("a.b", "a.z", "a.t"))
  }

  // -- rendering and paging --------------------------------------------------

  test("a listing renders as aligned name and kind") {
    val entries = List(
      CatalogEntry("prod.events", Kind.Namespace),
      CatalogEntry("prod.events.clicks", Kind.Table)
    )
    assertEquals(
      entries.lines,
      List(
        "prod.events         namespace",
        "prod.events.clicks  table"
      )
    )
  }

  test("an empty listing renders as nothing") {
    assertEquals(List.empty[CatalogEntry].lines, Nil)
  }

  test("no note when everything fits") {
    assertEquals(Render.limitNote(total = 3, limit = 10), None)
  }

  test("the note says how much was cut off") {
    assertEquals(
      Render.limitNote(total = 312, limit = 10),
      Some("showing 10 of 312 — raise --limit to see the rest")
    )
  }

  test("no note when the total exactly fills the limit") {
    assertEquals(Render.limitNote(total = 10, limit = 10), None)
  }

  test("limit 0 means all, so there is nothing to note") {
    assertEquals(Render.limitNote(total = 312, limit = 0), None)
  }
