package io.github.visorgood.icebergdoctor

import cats.syntax.all.*
import com.monovore.decline.{Command as DeclineCommand, Help, Opts}

/** A parsed command line. Kept separate from running it so parsing can be tested on its own. */
enum Invocation:
  case Ls(warehouse: String)
  case Describe(warehouse: String, table: String)

object Cli:

  private val warehouse =
    Opts.argument[String]("warehouse")

  private val ls =
    Opts.subcommand("ls", "List namespaces, tables and views in a catalog.") {
      warehouse.map(Invocation.Ls.apply)
    }

  private val describe =
    Opts.subcommand("describe", "Show a table's schema, partition spec, sort order and properties.") {
      (warehouse, Opts.argument[String]("table")).mapN(Invocation.Describe.apply)
    }

  private val command =
    DeclineCommand(
      name = "iceberg-doctor",
      header = "Inspect, diagnose and maintain Apache Iceberg tables."
    )(ls orElse describe)

  def parse(args: List[String]): Either[Help, Invocation] =
    command.parse(args, sys.env)
