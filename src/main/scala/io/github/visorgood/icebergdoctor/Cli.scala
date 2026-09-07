package io.github.visorgood.icebergdoctor

import cats.syntax.all.*
import com.monovore.decline.{Command as DeclineCommand, Help, Opts}

/** A parsed command line. Kept separate from running it so parsing can be tested on its own. */
enum Invocation:
  case Ls(warehouse: String, namespace: Option[String], limit: Int)
  case Describe(warehouse: String, table: String)

object Cli:

  /** Entries `ls` shows before stopping. A catalog can hold thousands of tables. */
  val DefaultLimit = 10

  private val warehouse =
    Opts.argument[String]("warehouse")

  private val ls =
    Opts.subcommand("ls", "List what sits directly inside a namespace, one level only.") {
      (
        warehouse,
        Opts.argument[String]("namespace").orNone,
        Opts
          .option[Int](
            "limit",
            s"How many entries to show (default $DefaultLimit; 0 means all).",
            short = "n"
          )
          .withDefault(DefaultLimit)
      ).mapN(Invocation.Ls.apply)
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
