package io.github.visorgood.icebergdoctor

import org.apache.hadoop.conf.Configuration
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.hadoop.HadoopCatalog

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** A namespace and everything found beneath it. */
final case class NamespaceTree(name: String, tables: List[String], children: List[NamespaceTree])

/** First cut at R1: list what a Hadoop catalog contains.
  *
  * Deliberately not general yet — one catalog type, no option parsing, no output formats.
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

  private[icebergdoctor] def render(trees: List[NamespaceTree], indent: String = ""): List[String] =
    trees.flatMap { tree =>
      val here = s"$indent${tree.name}/" :: tree.tables.map(table => s"$indent  $table")
      here ::: render(tree.children, indent + "  ")
    }

  def main(args: Array[String]): Unit =
    args.toList match
      case warehouse :: Nil =>
        val tree = Using.resource(openCatalog(warehouse))(walk(_, Namespace.empty))
        render(tree).foreach(println)
      case _ =>
        println("usage: iceberg-doctor <warehouse-path>")
        sys.exit(2)
