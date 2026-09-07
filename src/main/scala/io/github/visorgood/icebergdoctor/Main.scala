package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.hadoop.conf.Configuration
import org.apache.iceberg.catalog.{Namespace, TableIdentifier}
import org.apache.iceberg.hadoop.HadoopCatalog
import org.apache.iceberg.{PartitionSpec, Schema, SortOrder}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** A namespace and everything found beneath it. */
final case class NamespaceTree(name: String, tables: List[String], children: List[NamespaceTree])

/** The logical shape of a table (R2).
  *
  * Iceberg's own `Schema`, `PartitionSpec` and `SortOrder` are carried as-is: we only read
  * fields off them, so mirroring them in Scala would duplicate the spec for no gain.
  */
final case class TableDescription(
    name: String,
    schema: Schema,
    spec: PartitionSpec,
    sortOrder: SortOrder,
    properties: Map[String, String]
)

/** First cuts at R1 (`ls`) and R2 (`describe`).
  *
  * Deliberately not general yet — one catalog type, no output formats.
  */
object Main:

  private def openCatalog(warehouse: String): HadoopCatalog =
    val catalog = new HadoopCatalog()
    catalog.setConf(new Configuration())
    catalog.initialize("local", Map("warehouse" -> warehouse).asJava)
    catalog

  /** Iceberg lists one level at a time, so walking the tree is our job.
    *
    * Only namespaces returned by `listNamespaces` are ever asked for their tables, so the root
    * namespace — which `HadoopCatalog.listTables` rejects — is never passed to it.
    */
  private[icebergdoctor] def walk(catalog: HadoopCatalog, ns: Namespace): List[NamespaceTree] =
    catalog.listNamespaces(ns).asScala.toList.map { child =>
      NamespaceTree(
        name = child.levels.last,
        tables = catalog.listTables(child).asScala.map(_.name).toList,
        children = walk(catalog, child)
      )
    }

  private[icebergdoctor] def describe(
      catalog: HadoopCatalog,
      id: TableIdentifier
  ): TableDescription =
    val table = catalog.loadTable(id)
    TableDescription(
      name = id.toString,
      schema = table.schema,
      spec = table.spec,
      sortOrder = table.sortOrder,
      properties = table.properties.asScala.toMap
    )

  private def run(invocation: Invocation): Unit = invocation match
    case Invocation.Ls(warehouse) =>
      Using
        .resource(openCatalog(warehouse))(walk(_, Namespace.empty))
        .lines
        .foreach(println)
    case Invocation.Describe(warehouse, table) =>
      Using
        .resource(openCatalog(warehouse))(describe(_, TableIdentifier.parse(table)))
        .lines
        .foreach(println)

  def main(args: Array[String]): Unit =
    Cli.parse(args.toList) match
      case Right(invocation) => run(invocation)
      // decline returns Help for both `--help` and a parse failure; only the latter has errors.
      case Left(help) if help.errors.isEmpty => println(help)
      case Left(help) =>
        System.err.println(help)
        sys.exit(2)
