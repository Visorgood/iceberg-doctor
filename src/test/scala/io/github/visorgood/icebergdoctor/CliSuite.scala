package io.github.visorgood.icebergdoctor

/** Parsing is separated from running, so it can be checked without touching a catalog. */
class CliSuite extends munit.FunSuite:

  test("ls takes a warehouse") {
    assertEquals(Cli.parse(List("ls", "/tmp/wh")), Right(Invocation.Ls("/tmp/wh")))
  }

  test("describe takes a warehouse and a table") {
    assertEquals(
      Cli.parse(List("describe", "/tmp/wh", "prod.events.clicks")),
      Right(Invocation.Describe("/tmp/wh", "prod.events.clicks"))
    )
  }

  test("a missing argument is an error, not a crash") {
    val help = Cli.parse(List("describe", "/tmp/wh")).left.getOrElse(fail("expected a failure"))
    assert(help.errors.nonEmpty, help.toString)
  }

  test("an unknown subcommand is an error") {
    val help = Cli.parse(List("frobnicate")).left.getOrElse(fail("expected a failure"))
    assert(help.errors.nonEmpty, help.toString)
  }

  test("--help is not an error, so it must not exit non-zero") {
    val help = Cli.parse(List("--help")).left.getOrElse(fail("expected help"))
    assert(help.errors.isEmpty, help.toString)
  }
