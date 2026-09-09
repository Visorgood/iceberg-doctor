package io.github.visorgood.icebergdoctor

import org.apache.iceberg.exceptions.{
  NoSuchNamespaceException,
  NoSuchTableException,
  NotFoundException
}

import java.io.{IOException, UncheckedIOException}
import scala.util.control.NoStackTrace

/** Raised before Iceberg is asked anything, so the real problem can be named.
  *
  * A missing warehouse otherwise surfaces as `NoSuchNamespaceException` with an empty namespace,
  * which tells the user nothing. `NoStackTrace` because this is control flow, not a defect: the
  * trace is never printed and filling it in is the expensive part of throwing.
  */
final class WarehouseMissing(val path: String) extends RuntimeException(path) with NoStackTrace

/** Something the user has to fix, stated in their terms rather than the Java API's.
  *
  * Every failure here ends the run, so these are values only at the edge: `Iceberg` throws as
  * Iceberg does and `Main` translates once. A check that fails without ending the run — which
  * `diagnose` will have — is a different thing and belongs in the answer, not here.
  */
enum Failure:
  case TableNotFound(table: String, warehouse: String)
  case NamespaceNotFound(namespace: String, warehouse: String)
  case WarehouseNotFound(path: String)
  case StorageUnreadable(detail: String)
  case Unexpected(kind: String, detail: String)

object Failure:

  /** Classifies whatever was thrown, taking the names it needs from the invocation itself.
    *
    * The whole cause chain is searched, not just the outermost throwable: Hadoop, HTTP clients
    * and thread pools all wrap, and a wrapped `NoSuchTableException` is still a missing table.
    */
  def from(error: Throwable, invocation: Invocation): Failure =
    chain(error)
      .collectFirst(recognised(invocation))
      .getOrElse(Unexpected(error.getClass.getSimpleName, describe(error)))

  private def recognised(invocation: Invocation): PartialFunction[Throwable, Failure] =
    case missing: WarehouseMissing    => WarehouseNotFound(missing.path)
    case _: NoSuchTableException      => TableNotFound(invocation.subject, invocation.warehouse)
    case _: NoSuchNamespaceException  => NamespaceNotFound(invocation.subject, invocation.warehouse)
    case failed: NotFoundException    => StorageUnreadable(describe(failed))
    case failed: UncheckedIOException => StorageUnreadable(describe(failed))
    case failed: IOException          => StorageUnreadable(describe(failed))

  /** An exception and everything it wraps, outermost first.
    *
    * Lazy and bounded because a cause chain is allowed to be cyclic; nothing sane is this deep.
    */
  private def chain(error: Throwable): LazyList[Throwable] =
    LazyList
      .unfold(Option(error))(_.map(current => (current, Option(current.getCause))))
      .take(16)

  /** `getMessage` is a nullable Java getter, so it needs lifting before it can be trusted. */
  private def describe(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

  /** The namespace a table lives in — where `ls` should be pointed to find the right name. */
  private[icebergdoctor] def parentOf(table: String): String =
    table.split('.').dropRight(1).mkString(".")
