package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.RefRow.Kind
import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.hadoop.HadoopCatalog

import java.nio.file.Path

/** Tests `Iceberg.refs` and its `Render` instance — R5. */
class RefsSuite extends munit.FunSuite:

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

  private def ref(
      name: String,
      kind: Kind = Kind.Branch,
      minSnapshotsToKeep: Option[Int] = None,
      maxSnapshotAgeMs: Option[Long] = None,
      maxRefAgeMs: Option[Long] = None
  ) = RefRow(name, kind, snapshotId = 7L, minSnapshotsToKeep, maxSnapshotAgeMs, maxRefAgeMs)

  // -- rendering -------------------------------------------------------------

  test("a table with no refs says so rather than printing a bare header") {
    assertEquals(List.empty[RefRow].lines, List("no refs"))
  }

  test("retention the ref does not set renders as a dash, not as a resolved value") {
    // Showing the table's fallback here would hide which of the two a reader is looking at.
    val rendered = List(ref("main")).lines
    assertEquals(rendered.last.count(_ == '—'), 3, rendered.mkString("\n"))
  }

  test("ages render as the coarsest whole unit") {
    val rendered = List(
      ref("weekly", maxSnapshotAgeMs = Some(30L * 24 * 60 * 60 * 1000)),
      ref("hourly", maxSnapshotAgeMs = Some(90L * 60 * 1000))
    ).lines
    assert(rendered.exists(_.contains("30d")), rendered.mkString("\n"))
    // 90 minutes is not a whole number of hours, so it stays in minutes.
    assert(rendered.exists(_.contains("90m")), rendered.mkString("\n"))
  }

  // -- against a real catalog ------------------------------------------------

  warehouse.test("main comes first, then other branches, then tags") { fixture =>
    val rows = Iceberg.refs(fixture.catalog, Warehouse.configured)
    assertEquals(rows.map(_.name), List("main", Warehouse.backfillBranch, Warehouse.weeklyTag))
    assertEquals(rows.map(_.kind), List(Kind.Branch, Kind.Branch, Kind.Tag))
  }

  warehouse.test("a branch carries snapshot retention, a tag only its own age") { fixture =>
    val rows     = Iceberg.refs(fixture.catalog, Warehouse.configured)
    val backfill = rows.find(_.name == Warehouse.backfillBranch).getOrElse(fail("no backfill"))
    val weekly   = rows.find(_.name == Warehouse.weeklyTag).getOrElse(fail("no tag"))

    assertEquals(backfill.minSnapshotsToKeep, Some(5))
    assertEquals(backfill.maxSnapshotAgeMs, Some(30L * 24 * 60 * 60 * 1000))
    assertEquals(backfill.maxRefAgeMs, None)

    // minSnapshotsToKeep and maxSnapshotAgeMs are branch-only concepts.
    assertEquals(weekly.minSnapshotsToKeep, None)
    assertEquals(weekly.maxSnapshotAgeMs, None)
    assertEquals(weekly.maxRefAgeMs, Some(365L * 24 * 60 * 60 * 1000))
  }

  warehouse.test("main is left without retention of its own") { fixture =>
    val main = Iceberg.refs(fixture.catalog, Warehouse.configured).head
    assertEquals(main.name, "main")
    assertEquals(main.minSnapshotsToKeep, None)
    assertEquals(main.maxSnapshotAgeMs, None)
  }

  warehouse.test("branches and tags can point at different snapshots") { fixture =>
    val rows = Iceberg.refs(fixture.catalog, Warehouse.configured)
    val main = rows.head
    val tag  = rows.find(_.name == Warehouse.weeklyTag).getOrElse(fail("no tag"))
    // The fixture tags the first commit while main has moved on to the second.
    assertNotEquals(main.snapshotId, tag.snapshotId)
  }

  warehouse.test("a table that was never written to has no refs") { fixture =>
    assertEquals(Iceberg.refs(fixture.catalog, Warehouse.plain), Nil)
  }
