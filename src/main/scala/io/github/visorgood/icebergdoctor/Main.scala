package io.github.visorgood.icebergdoctor

import io.github.visorgood.icebergdoctor.Render.lines
import org.apache.iceberg.catalog.{Namespace, TableIdentifier}

import scala.util.{Try, Using}

/** Parses the command line, runs the matching read, prints the result.
  *
  * Reading lives in `Iceberg.scala`, formatting in `Render.scala`; what is left here is the glue.
  */
object Main:

  private def parseNamespace(text: String): Namespace =
    Namespace.of(text.split('.').filter(_.nonEmpty)*)

  /** Prints at most `limit` rows, then says what was left out.
    *
    * The instance is on `List[A]`, not `A`: a listing is rendered whole so its columns line up.
    */
  private def printLimited[A](rows: List[A], limit: Int)(using Render[List[A]]): Unit =
    val shown = if limit <= 0 then rows else rows.take(limit)
    shown.lines.foreach(println)
    Render.limitNote(rows.size, limit).foreach(println)

  private def run(invocation: Invocation): Unit = invocation match
    case Invocation.Ls(warehouse, namespace, limit) =>
      Using.resource(Iceberg.hadoopCatalog(warehouse)) { catalog =>
        printLimited(Iceberg.list(catalog, namespace.fold(Namespace.empty)(parseNamespace)), limit)
      }

    case Invocation.Describe(warehouse, table) =>
      Using.resource(Iceberg.hadoopCatalog(warehouse)) { catalog =>
        Iceberg.describe(catalog, TableIdentifier.parse(table)).lines.foreach(println)
      }

    case Invocation.Snapshots(warehouse, table, limit) =>
      Using.resource(Iceberg.hadoopCatalog(warehouse)) { catalog =>
        printLimited(Iceberg.snapshots(catalog, TableIdentifier.parse(table)), limit)
      }

    case Invocation.Refs(warehouse, table, limit) =>
      Using.resource(Iceberg.hadoopCatalog(warehouse)) { catalog =>
        printLimited(Iceberg.refs(catalog, TableIdentifier.parse(table)), limit)
      }

  /** Runs a command and hands back what went wrong instead of printing it.
    *
    * Separating the decision from the exit makes the whole failure path testable; `Try` catches
    * exactly `NonFatal`, so `OutOfMemoryError` and friends still go uncaught.
    */
  private[icebergdoctor] def attempt(invocation: Invocation): Either[Failure, Unit] =
    Try(run(invocation)).toEither.left.map(Failure.from(_, invocation))

  def main(args: Array[String]): Unit =
    Cli.parse(args.toList) match
      case Right(invocation) =>
        attempt(invocation).left.foreach { failure =>
          failure.lines.foreach(System.err.println)
          sys.exit(2)
        }
      // decline returns Help for both `--help` and a parse failure; only the latter has errors.
      case Left(help) if help.errors.isEmpty => println(help)
      case Left(help) =>
        System.err.println(help)
        sys.exit(2)
