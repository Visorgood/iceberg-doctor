package io.github.visorgood.icebergdoctor

import cats.syntax.all.*
import com.monovore.decline.{Command as DeclineCommand, Help, Opts}

/** A parsed command line. Kept separate from running it so parsing can be tested on its own. */
enum Invocation:
  case Ls(warehouse: String, namespace: Option[String], limit: Int)
  case Describe(warehouse: String, table: String)
  case Snapshots(warehouse: String, table: String, limit: Int)
  case Refs(warehouse: String, table: String, limit: Int)

  /** Abstract here, implemented by every case's `warehouse` parameter. */
  def warehouse: String

  /** What the command was pointed at: a namespace for `ls`, a table for the rest.
    *
    * An error message needs this, and the ADT is the only place that knows which field holds it.
    */
  def subject: String = this match
    case Ls(_, namespace, _)    => namespace.getOrElse("")
    case Describe(_, table)     => table
    case Snapshots(_, table, _) => table
    case Refs(_, table, _)      => table

object Cli:

  /** The binary's name. Used for the usage text and for the commands suggested in errors. */
  val ProgramName = "iceberg-doctor"

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

  private val snapshots =
    Opts.subcommand("snapshots", "Show a table's snapshot history, newest first.") {
      (
        warehouse,
        Opts.argument[String]("table"),
        Opts
          .option[Int]("limit", s"How many snapshots to show (default $DefaultLimit; 0 means all).", short = "n")
          .withDefault(DefaultLimit)
      ).mapN(Invocation.Snapshots.apply)
    }

  private val refs =
    Opts.subcommand("refs", "Show a table's branches and tags with their retention.") {
      (
        warehouse,
        Opts.argument[String]("table"),
        Opts
          .option[Int]("limit", s"How many refs to show (default $DefaultLimit; 0 means all).", short = "n")
          .withDefault(DefaultLimit)
      ).mapN(Invocation.Refs.apply)
    }

  private val command =
    DeclineCommand(
      name = ProgramName,
      header = "Inspect, diagnose and maintain Apache Iceberg tables."
    )(ls orElse describe orElse snapshots orElse refs)

  def parse(args: List[String]): Either[Help, Invocation] =
    command.parse(args, sys.env)
