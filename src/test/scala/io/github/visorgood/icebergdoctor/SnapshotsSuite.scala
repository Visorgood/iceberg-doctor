package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.hadoop.HadoopCatalog

import java.nio.file.Path

/** Tests `Main.snapshots` and its `Render` instance — R4. */
class SnapshotsSuite extends munit.FunSuite:

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

  private def row(
      id: Long,
      parentId: Option[Long] = None,
      addedFiles: Option[Long] = None,
      removedFiles: Option[Long] = None,
      engine: Option[String] = None,
      refs: List[String] = Nil
  ) = SnapshotRow(
    id = id,
    parentId = parentId,
    sequenceNumber = 1,
    timestampMs = 1788782400000L, // 2026-09-07 12:00:00Z
    operation = "append",
    addedFiles = addedFiles,
    removedFiles = removedFiles,
    addedRecords = None,
    removedRecords = None,
    engine = engine,
    refs = refs
  )

  // -- rendering -------------------------------------------------------------

  test("an empty history says so rather than printing a bare header") {
    assertEquals(
      List.empty[SnapshotRow].lines,
      List("no snapshots — the table has never been written to")
    )
  }

  test("a commit that only adds shows one signed delta") {
    val rendered = List(row(id = 7, addedFiles = Some(3))).lines
    assert(rendered.last.contains(" +3 "), rendered.mkString("\n"))
  }

  test("a commit that replaces shows both directions") {
    val rendered = List(row(id = 7, addedFiles = Some(8), removedFiles = Some(8))).lines
    assert(rendered.last.contains("+8 -8"), rendered.mkString("\n"))
  }

  test("anything absent renders as a dash rather than an empty cell") {
    val rendered = List(row(id = 7)).lines
    // No parent, no file delta, no record delta, no engine — four dashes, no blank gaps.
    assertEquals(rendered.last.count(_ == '—'), 4, rendered.mkString("\n"))
  }

  test("refs are listed against the snapshot they point at") {
    val rendered = List(row(id = 7, refs = List("main", "nightly"))).lines
    assert(rendered.last.endsWith("main, nightly"), rendered.mkString("\n"))
  }

  // -- against a real catalog ------------------------------------------------

  warehouse.test("history is newest first and links each commit to its parent") { fixture =>
    val rows = Iceberg.snapshots(fixture.catalog, Warehouse.configured)

    assertEquals(rows.size, 2)
    val List(newest, oldest) = rows: @unchecked
    assert(newest.timestampMs >= oldest.timestampMs)
    assertEquals(newest.parentId, Some(oldest.id))
    assertEquals(oldest.parentId, None)
  }

  warehouse.test("per-commit deltas are the commit's own, not the running total") { fixture =>
    val List(newest, oldest) = Iceberg.snapshots(fixture.catalog, Warehouse.configured): @unchecked
    // The fixture appends 2 files then 3. The totals would be 2 and 5.
    assertEquals(oldest.addedFiles, Some(2L))
    assertEquals(newest.addedFiles, Some(3L))
    assertEquals(newest.operation, "append")
  }

  warehouse.test("only the snapshot a ref points at carries that ref") { fixture =>
    val List(newest, oldest) = Iceberg.snapshots(fixture.catalog, Warehouse.configured): @unchecked
    assertEquals(newest.refs, List("main"))
    assertEquals(oldest.refs, Nil)
  }

  warehouse.test("a table that was never written to has no snapshots") { fixture =>
    assertEquals(Iceberg.snapshots(fixture.catalog, Warehouse.plain), Nil)
  }
