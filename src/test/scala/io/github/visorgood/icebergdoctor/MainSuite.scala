package io.github.visorgood.icebergdoctor

import org.apache.iceberg.catalog.Namespace
import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.hadoop.HadoopCatalog

import java.nio.file.Path

/** Tests `Main.walk` and the `Render` instance for `NamespaceTree` — R1. */
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

  private def walkAll(fixture: Fixture): List[NamespaceTree] =
    Main.walk(fixture.catalog, Namespace.empty)

  warehouse.test("walk finds every namespace the fixture created") { fixture =>
    val found = walkAll(fixture).map(_.name).toSet
    assertEquals(found, Set("prod", "staging"))
  }

  warehouse.test("walk descends into nested namespaces") { fixture =>
    val prod = walkAll(fixture).find(_.name == "prod").get
    assertEquals(prod.children.map(_.name), List("events"))
  }

  warehouse.test("walk attaches tables to the namespace that holds them") { fixture =>
    val events = walkAll(fixture).find(_.name == "prod").get.children.head
    assertEquals(events.tables.toSet, Set("clicks", "impressions"))
    // prod itself holds no tables — only the nested namespace does.
    assertEquals(walkAll(fixture).find(_.name == "prod").get.tables, Nil)
  }

  test("render indents tables under their namespace and children under their parent") {
    val tree = List(
      NamespaceTree("prod", Nil, List(NamespaceTree("events", List("clicks"), Nil))),
      NamespaceTree("staging", List("raw"), Nil)
    )
    assertEquals(
      tree.lines,
      List(
        "prod/",
        "  events/",
        "    clicks",
        "staging/",
        "  raw"
      )
    )
  }

  test("render of nothing is nothing") {
    assertEquals(List.empty[NamespaceTree].lines, Nil)
  }
