package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.exceptions.{
  NoSuchNamespaceException,
  NoSuchTableException,
  NotFoundException
}

import java.io.IOException

/** Classification and wording of user-facing errors. */
class FailureSuite extends munit.FunSuite:

  private val describing = Invocation.Describe("/wh", "prod.events.clicks")
  private val listing    = Invocation.Ls("/wh", Some("prod.events"), 10)

  // -- what the invocation knows about itself --------------------------------

  test("every invocation exposes the warehouse it was pointed at") {
    val all = List(
      Invocation.Ls("/wh", None, 10),
      Invocation.Describe("/wh", "a.b"),
      Invocation.Snapshots("/wh", "a.b", 10),
      Invocation.Refs("/wh", "a.b", 10)
    )
    assertEquals(all.map(_.warehouse).distinct, List("/wh"))
  }

  test("the subject is the namespace for ls and the table for the rest") {
    assertEquals(listing.subject, "prod.events")
    assertEquals(describing.subject, "prod.events.clicks")
    assertEquals(Invocation.Ls("/wh", None, 10).subject, "")
  }

  // -- classification --------------------------------------------------------

  test("a missing table is reported against the name that was asked for") {
    assertEquals(
      Failure.from(new NoSuchTableException("Table does not exist: whatever"), describing),
      Failure.TableNotFound("prod.events.clicks", "/wh")
    )
  }

  test("a missing namespace is distinguished from a missing table") {
    assertEquals(
      Failure.from(new NoSuchNamespaceException("Namespace does not exist: "), listing),
      Failure.NamespaceNotFound("prod.events", "/wh")
    )
  }

  test("a missing warehouse is caught before Iceberg is asked anything") {
    assertEquals(
      Failure.from(WarehouseMissing("/nope"), listing),
      Failure.WarehouseNotFound("/nope")
    )
  }

  test("storage problems are grouped, whichever exception carried them") {
    val fromIceberg = Failure.from(new NotFoundException("gone"), describing)
    val fromJava    = Failure.from(new IOException("disk on fire"), describing)
    assertEquals(fromIceberg, Failure.StorageUnreadable("gone"))
    assertEquals(fromJava, Failure.StorageUnreadable("disk on fire"))
  }

  test("a wrapped exception is classified by what it wraps") {
    // Hadoop, HTTP clients and thread pools all wrap; the outermost type says nothing.
    val wrapped = new RuntimeException("boom", new NoSuchTableException("Table does not exist"))
    assertEquals(Failure.from(wrapped, describing), Failure.TableNotFound("prod.events.clicks", "/wh"))
  }

  test("a cyclic cause chain does not hang the classifier") {
    // The JVM refuses self-causation, but a two-step cycle is allowed and does occur.
    val outer = new RuntimeException("outer")
    val inner = new RuntimeException("inner", outer)
    outer.initCause(inner)
    assertEquals(Failure.from(outer, describing), Failure.Unexpected("RuntimeException", "outer"))
  }

  test("anything unrecognised keeps its class name so a bug report can start somewhere") {
    assertEquals(
      Failure.from(new IllegalStateException("no TableMetadata"), describing),
      Failure.Unexpected("IllegalStateException", "no TableMetadata")
    )
  }

  test("a null message does not become the string null") {
    val Failure.Unexpected(_, detail) =
      Failure.from(new RuntimeException(), describing): @unchecked
    assertEquals(detail, "java.lang.RuntimeException")
  }

  // -- wording ---------------------------------------------------------------

  test("a missing table suggests listing the namespace it should have been in") {
    assertEquals(
      Failure.TableNotFound("prod.events.clicks", "/wh").lines,
      List(
        "error: table not found: prod.events.clicks",
        "       list what is there: iceberg-doctor ls /wh prod.events"
      )
    )
  }

  test("a top-level namespace suggests listing the warehouse root, with no trailing argument") {
    assertEquals(
      Failure.NamespaceNotFound("prod", "/wh").lines.last,
      "       list what is there: iceberg-doctor ls /wh"
    )
  }

  test("parentOf drops the last segment") {
    assertEquals(Failure.parentOf("a.b.c"), "a.b")
    assertEquals(Failure.parentOf("a"), "")
  }

  // -- the whole path, end to end, without a subprocess ----------------------

  test("a failing command yields the failure instead of printing and exiting") {
    val warehouse = Warehouse.createTemporary()
    try
      assertEquals(
        Main.attempt(Invocation.Describe(warehouse.toString, "prod.events.nosuch")),
        Left(Failure.TableNotFound("prod.events.nosuch", warehouse.toString))
      )
      assertEquals(
        Main.attempt(Invocation.Ls("/tmp/no-such-warehouse-here", None, 10)),
        Left(Failure.WarehouseNotFound("/tmp/no-such-warehouse-here"))
      )
    finally Warehouse.deleteRecursively(warehouse)
  }

  test("a command that works reports no failure") {
    val warehouse = Warehouse.createTemporary()
    try assertEquals(Main.attempt(Invocation.Refs(warehouse.toString, "prod.events.clicks", 10)), Right(()))
    finally Warehouse.deleteRecursively(warehouse)
  }
