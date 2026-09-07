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
sbt test         # run AFFECTED tests only — sbt 2 delegates this to testQuick
sbt "testOnly *" # run every test
sbt run          # run the application
sbt console      # REPL with the project on the classpath
```

## Design documents

- `docs/model.md` — the conceptual model: entities, attributes, actions, and the seam between
  the catalog plane and the table plane.
- `docs/diagnostics.md` — design of `diagnose` (`R9`).
- `docs/cli.md` — the `v0.1` command surface and output shapes. Read commands are named after
  Iceberg's metadata tables; write commands after Iceberg's operations.

## Intended architecture

Decided in principle, not yet implemented. Treat as direction, not as fact on disk.

- **Iceberg Java `iceberg-core`** is the metadata layer. This is the project's main differentiator:
  the Java implementation is the reference one, so format v3 features (deletion vectors, row
  lineage, `variant` and geospatial types) are available here before they reach PyIceberg or
  iceberg-go. Prefer core APIs over reimplementing spec logic.
- **Typelevel stack** for the application layer: `cats`, `cats-effect`, `fs2`, `decline-effect`.
  **Introduce each piece only when a task needs it, never in advance.** Cats Effect was added and
  removed again on 2026-09-06: for listing a catalog it bought `Resource` over
  `scala.util.Using`, `traverse_` over `foreach` and an `IO.blocking` that guarded nothing,
  while making the code harder to read. Because learning is the point, applying a tool before
  the problem it solves exists is worse than not applying it — the tool becomes ritual instead
  of a decision. Concrete triggers to bring it back: `parTraverseN` with a `Semaphore` when
  `R6`/`R7` read hundreds of manifests against object storage; `Resource` when catalog, FileIO
  and an HTTP client have to nest; cancellation when a scan needs a timeout; `fs2` when a
  listing stops fitting in memory. Plain Scala and the standard library until then.
- **Catalogs**: Hadoop (filesystem) and REST first. Polaris, Lakekeeper, Unity, Nessie and
  Gravitino all speak the REST spec, so REST support covers all of them.
- The imperative Iceberg Java API is expected to be wrapped in a typed, `IO`-based facade written
  by hand. That wrapper is intentional, not accidental complexity – see below.

### Design constraints

- **Command names are Iceberg names, spelled exactly.** Read commands take the names of Iceberg's
  metadata tables (`snapshots`, `manifests`, `files`, `partitions`, `refs`, `history`,
  `metadata_log_entries`); write commands take the names of its stored procedures
  (`expire_snapshots`, `rewrite_data_files`, `rewrite_manifests`, `rollback_to_snapshot`,
  `fast_forward`). Canonical spelling is Iceberg's `snake_case`, with `kebab-case` accepted as an
  alias. No friendlier synonyms — an alias has to be explained, translated in every remedy, and
  remembered as a second name. Only `catalogs`, `ls`, `describe`, `properties`, `commits`,
  `diagnose` and `diff` have no Iceberg counterpart; the last three are derived and say so.
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

- Test framework – **`munit` 1.3.6**, plain `FunSuite` and `FunFixture`. Move to
  `munit-cats-effect` if and when Cats Effect returns.
- Code style / formatting – `.scalafmt.conf` not yet added
- Error modelling – exceptions surface for now; typed errors when there is something to
  distinguish
- Effect abstraction – not applicable while the code is plain Scala; revisit with Cats Effect
- Output rendering – must support both human-readable and `--json` from the first command

When referring to functionality, use the use-case IDs from `README.md` (`R1`–`R16` for read path,
`W1`–`W10` for write path). They are stable identifiers and are used in issues and design notes.

## Gotchas

Things that have already cost time, or will.

- **sbt 2 changed the output layout.** Compiled classes land in
  `target/out/jvm/scala-3.9.0/<project>/classes/`, not sbt 1's `target/scala-3.9.0/classes/`.
  Recipes found online that reference the old path are for sbt 1.
- **`sbt test` does not run every test.** In sbt 2 it delegates to `testQuick`, which runs only
  what changed since the last run, and its record lives in the shared cache under
  `~/Library/Caches/sbt/v2/` — so it survives `clean`. A suite that is already green is simply
  skipped, which reads as "the tests disappeared". Use `sbt "testOnly *"` for a full run.
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
- **Nothing in the metadata has to be parsed by hand.** `TableMetadata`, `ManifestFile`,
  `DataFile` / `DeleteFile` and `PuffinReader` (all in `iceberg-core`) expose every field of
  `metadata.json`, manifest lists, manifests and Puffin files. The `metadata → manifest-lists →
  manifests → data` depth axis is a **cost model** — how much I/O the API is asked to do — not a
  parsing plan.
- **`ManifestEntry` is package-private.** `ManifestFiles.read(manifest, io)` yields the files but
  not the entry `status` (`ADDED` / `EXISTING` / `DELETED`), which is what decides whether a file
  is live at a snapshot. Use the metadata tables instead:
  `MetadataTableUtils.createMetadataTableInstance(table, MetadataTableType.ENTRIES)` is public and
  is the same mechanism behind Spark's `tbl.entries`. `FILES`, `MANIFESTS`, `PARTITIONS`,
  `HISTORY`, `SNAPSHOTS` and `REFS` are available the same way — which is also why the read
  commands are named after those tables. Scanning them with `IcebergGenerics` needs the
  `iceberg-data` module; `DataTask.rows()` works with `iceberg-core` alone.
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
