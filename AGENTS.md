# AGENTS.md

Context for AI coding agents working in this repository. For what the product is meant to do,
read `README.md` first – it defines the personas, the use cases and the roadmap.

This file is young and deliberately incomplete. Conventions get added here as they are decided,
not invented ahead of time.

## Project

`iceberg-doctor` is a CLI for inspecting, diagnosing and maintaining Apache Iceberg tables.

**Current status: design phase.** The repository contains an sbt scaffold and documentation.
No application code exists yet. Do not assume any package, module or abstraction is in place –
check before referring to one.

## Toolchain

| | Version | Pinned in |
|---|---|---|
| Scala | 3.9.0 | `build.sbt` |
| sbt | 2.0.8 | `project/build.properties` |
| JDK | 21 | `.java-version` (jenv) |

- Organisation / groupId: `io.github.visorgood`
- Root package: `io.github.visorgood.icebergdoctor`
- Licence: Apache 2.0

**Do not bump the JDK.** 21 is deliberate: Apache Iceberg targets 11/17/21, and the Hadoop client
libraries needed for `HadoopCatalog` and S3A break on newer JDKs. A JDK 26 is present on the
development machine and must not be selected.

## Commands

```bash
sbt compile      # compile
sbt test         # run tests
sbt run          # run the application
sbt console      # REPL with the project on the classpath
```

## Intended architecture

Decided in principle, not yet implemented. Treat as direction, not as fact on disk.

- **Iceberg Java `iceberg-core`** is the metadata layer. This is the project's main differentiator:
  the Java implementation is the reference one, so format v3 features (deletion vectors, row
  lineage, `variant` and geospatial types) are available here before they reach PyIceberg or
  iceberg-go. Prefer core APIs over reimplementing spec logic.
- **Typelevel stack** for the application layer: `cats`, `cats-effect`, `fs2`, `decline-effect`.
- **Catalogs**: Hadoop (filesystem) and REST first. Polaris, Lakekeeper, Unity, Nessie and
  Gravitino all speak the REST spec, so REST support covers all of them.
- The imperative Iceberg Java API is expected to be wrapped in a typed, `IO`-based facade written
  by hand. That wrapper is intentional, not accidental complexity – see below.

### Design constraints

- **Do not build a vocabulary on top of Iceberg's.** Users work in Iceberg's own terms —
  snapshots, manifests, data files, partition specs, sort orders, refs, and table properties by
  their real names. The tool navigates and measures; it does not rename or reinterpret. Before
  introducing any new noun, check whether Iceberg already has one. `docs/diagnostics.md` lists
  the three exceptions and the bar for a fourth.
- **`diagnose` (`R9`) ships conformance checks only** in the first versions: does the table match
  the thresholds it declares in its own properties. Every threshold is either a table property or
  Iceberg's documented default, and each finding says which. Policy and sanity checks — anything
  where the threshold is the tool's or the user's opinion — are deferred. See
  `docs/diagnostics.md`.

Decided 2026-09-06 while reviewing which ecosystem technologies to adopt. All three cost nothing
now and prevent a refactor later.

- **`FileFormat` is an open set**, never a closed `parquet | orc | avro`. Iceberg 1.11.0 shipped
  the File Format API, which makes formats pluggable — Vortex and Lance are queued behind it.
  Keep format-specific work (`R15`) in a plugin, not a branch in the core.
- **Output format is a pluggable codec**, not a `--json` flag branching inside each command.
  NDJSON ships first; Parquet and Arrow IPC become leaves added later.
- **Catalogs are modelled by capability, not assumed uniform.** The REST spec standardises the
  wire protocol — namespaces, commits, credential vending — but not RBAC, masking, lineage or
  federation. Ask what a catalog supports (nested namespaces, views, credential vending,
  server-side scan planning); do not assume.

No new build dependency follows from any of this. Adding one requires a reason beyond the
technology being current.

## Working with the author

The author is an experienced data engineer returning to hands-on programming, and is using this
project to learn Scala and the Typelevel stack in depth. **Learning is the primary goal; shipping
speed is not.** This changes what good help looks like here:

- Explain the space of options and the trade-offs rather than producing a finished answer.
  Where a fast path and an instructive path diverge, name both.
- Do not reach for a library that hides a mechanism worth understanding. Hand-writing the
  wrapper, the parser or the renderer is often the point.
- Prefer showing why a decision is made over asserting what to do.
- Leave the mechanical steps — `git commit`, project scaffolding, IDE setup — to the author.
  Prepare the change and hand over the command.

## Conventions

Not yet established. To be filled in as decisions are made:

- Code style / formatting – `.scalafmt.conf` not yet added
- Error modelling – typed errors vs `MonadError`, undecided
- Effect abstraction – tagless final vs concrete `IO`, undecided
- Test framework – `weaver-test` or `munit-cats-effect`, undecided
- Output rendering – must support both human-readable and `--json` from the first command

When referring to functionality, use the use-case IDs from `README.md` (`R1`–`R16` for read path,
`W1`–`W10` for write path). They are stable identifiers and are used in issues and design notes.

## Gotchas

Things that have already cost time, or will.

- **sbt 2 changed the output layout.** Compiled classes land in
  `target/out/jvm/scala-3.9.0/<project>/classes/`, not sbt 1's `target/scala-3.9.0/classes/`.
  Recipes found online that reference the old path are for sbt 1.
- **Most sbt documentation online is for sbt 1.** Simple settings are identical, but custom task
  definitions differ. Check which major version a source is describing before following it.
- **`scala-library` is now published at the Scala 3 version.** Since Scala 3.8 the standard
  library is compiled with Scala 3 and published as `org.scala-lang:scala-library:3.x`, not
  `2.13.x`. Advice about a `scala-library:2.13.x` on the classpath predates this and does not
  apply.
- **Two sbt plugins have no sbt 2 release yet.** `sbt-tpolecat` — write `scalacOptions` by hand
  instead. `sbt-native-image` — use `GraalVMNativeImage` from `sbt-native-packager`, which is
  released for sbt 2.
- **`Partition` is not an entity in the Iceberg spec.** Partitions are derived by grouping live
  files by partition tuple under a given spec. A table with evolved specs has files under several
  specs at once, so any table-wide aggregate must be spec-aware.
- **Column identity is the field ID, not the name.** Every metric map, bound map, partition
  source and sort source keys on field ID. Indexing by name breaks silently after a rename, and
  renames are free in Iceberg.
- **`iceberg-arrow` constrains the JDK.** It pulls in Arrow's Netty and unsafe allocators, which
  [break on Java 25](https://github.com/apache/iceberg/issues/15930). Another reason not to reach
  for Arrow internally.
- **Do not embed DuckDB, Polars or DataFusion.** They are Rust/C++, so linking them means JNI or
  a native driver, which rules out a GraalVM native-image build. It also puts the project head to
  head with TableSleuth on its strongest axis. Emit output those tools consume instead.

## Related projects

Checked when scoping, worth knowing before claiming novelty:

- [TableSleuth](https://github.com/jamesbconner/TableSleuth) — Python, read-only forensics across
  Parquet, Iceberg and Delta. No writes, no REST catalog yet. Closest neighbour.
- `apache/iceberg-go` — official Go CLI. Has expire-snapshots, orphan cleanup, rollback. No
  compaction, no rewrite-manifests.
- PyIceberg CLI — read-mostly, no maintenance operations.
