# iceberg-doctor

A command-line tool for inspecting, diagnosing and maintaining Apache Iceberg tables.

Working with Iceberg today means hand-writing queries against metadata tables or calling Spark
procedures one at a time, every time. `iceberg-doctor` turns those recurring questions into
commands: navigate a catalog, inspect a table's logical and physical shape, find out why it is
slow or expensive, and fix it.

**Status:** early design. Nothing is implemented yet. This document defines what the tool is
meant to do, so that design decisions can be checked against it.

---

## Personas

| | Persona | What they own | What they ask the tool |
|---|---|---|---|
| **DE** | Data engineer | Pipelines that write to tables | Did my write do what I expected? Why is this table degrading? |
| **PE** | Platform engineer | The lakehouse and its storage bill | Which tables are unhealthy? What is wasted storage costing? Is maintenance actually running? |
| **AE** | Analytics engineer | Transformations and models built on tables | How is this table partitioned and sorted? Will filtering on this column help? |
| **DC** | Data consumer | Nothing — reads what others produce | What tables exist? What is in them? How fresh are they? |

`DE` is the primary persona and the one the MVP is designed around. `PE` is served next, and is
where the tool becomes something an organisation adopts rather than an individual. `AE` is served
largely by the same read commands. `DC` is served incidentally — most data consumers live in
notebooks and BI tools rather than terminals, so this persona shapes defaults (readable output,
sensible summaries) rather than driving features of its own.

---

## Use cases — read path

Read commands never modify a table. The **Reads** column says how deep into the metadata tree a
command has to go, which is what determines whether it answers in milliseconds or minutes.

| ID | Use case | Personas | Reads | Tier |
|---|---|---|---|---|
| **R1** | **Browse the catalog.** List namespaces (including nested ones), tables and views; filter by pattern. | all | catalog | v0.1 |
| **R2** | **Describe logical shape.** Schema with field IDs, types, nullability, docs and identifier fields; current partition spec; sort order; table properties. | all | metadata | v0.1 |
| **R3** | **Describe physical shape.** Format version, location, current snapshot, total data and delete file counts and bytes, manifest count, metadata file size, last updated. | DE, PE | metadata | v0.1 |
| **R4** | **Snapshot history.** Timeline of snapshots: operation, timestamp, parent, rows and files added or removed, writing engine, and which refs point at it. | DE, PE | metadata | v0.1 |
| **R5** | **List refs.** Branches and tags with their target snapshot and retention settings. | DE | metadata | v0.1 |
| **R6** | **File statistics.** File count, total size, size distribution (min / p50 / p95 / max), breakdown by format. Surfaces the small-file tail. | DE, PE | manifest lists | v0.1 |
| **R7** | **Partition statistics.** Per-partition file count, record count, bytes and delete count; skew between partitions; files still written under a non-current spec. Restrictable to a subset of partitions. | DE, AE | manifests | v0.1 |
| **R8** | **Column statistics.** Per-column null counts, value counts, size on disk, and min/max bounds; distinct-value estimates where a Puffin sketch exists. | AE, DE | manifests | v0.2 |
| **R9** | **Diagnose.** Check the table against the thresholds it declares in its own properties, and report where it does not conform — with the evidence, the property the threshold came from, the impact, and the fix. Designed in [docs/diagnostics.md](docs/diagnostics.md). | DE, PE | manifests | v0.1 |
| **R10** | **Write-pattern analysis.** Over the last N days: commits per day, operation mix, rows and bytes per commit, files added per commit, writing engines, time-of-day distribution. Answers "how is this table actually being written?" | DE, PE | metadata | v0.1 |
| **R11** | **Delete-file analysis.** Position deletes vs equality deletes vs deletion vectors; delete-to-data ratio; which data files carry the most deletes; accumulation of unmerged deletes. | DE, PE | manifests | v0.2 |
| **R12** | **Snapshot diff.** Between two snapshots or refs: files added and removed, row deltas, partitions touched. For incident forensics. | DE | manifests | v0.2 |
| **R13** | **Offline inspection.** Read a table directly from a `metadata.json` path with no catalog involved — for when the catalog is unavailable or all you have is a storage path. | DE, PE | metadata | v0.1 |
| **R14** | **Query pruning simulation.** Given a predicate over columns, report how many partitions and files survive partition pruning and bounds-based file pruning. Answers "will filtering on this column actually help?" | AE, DE | manifests | v0.3 |
| **R15** | **File internals.** Inspect a single Parquet file (row groups, column chunks, encodings, compression, page statistics) or Puffin file (blob types, referenced fields, sketch contents). | DE | data files | v0.3 |
| **R16** | **Orphan file report.** List files under the table location that no retained snapshot references, with an age threshold. Reports only; deleting is a separate operation. | PE | storage listing | v0.2 |

## Use cases — write path

Write commands are grouped by what they actually do, because the three kinds differ in how
reversible they are. A **metadata commit** can be undone — the previous metadata document still
exists. **File deletion** cannot. **Data rewriting** additionally needs an execution engine, which
is the one capability this tool may reasonably lack.

| ID | Use case | Kind | Personas | Tier |
|---|---|---|---|---|
| **W1** | **Ref management.** Create, drop and fast-forward branches and tags. | metadata commit | DE | v0.2 |
| **W2** | **Rollback.** Move a ref back to an earlier snapshot. | metadata commit | DE | v0.2 |
| **W3** | **Table properties.** Set and unset table properties. | metadata commit | DE, PE | v0.2 |
| **W4** | **Expire snapshots.** Drop snapshots past a retention policy, and optionally delete the files that become unreachable. | metadata commit + file deletion | PE | v0.3 |
| **W5** | **Rewrite manifests.** Recluster manifests to reduce the metadata scanned per query. Moves no data. | metadata commit | PE | v0.2 |
| **W6** | **Remove orphan files.** Delete the unreachable files identified by R16. | file deletion | PE | v0.3 |
| **W7** | **Rewrite data files.** Combine small data files into larger ones. | data rewrite | DE, PE | v0.4 |
| **W8** | **Rewrite position delete files.** Fold position deletes and deletion vectors back into their data files. | data rewrite | DE, PE | v0.4 |
| **W9** | **Copy table.** Copy a whole table, or a subset of partitions, to a new table. | data rewrite | DE | later |
| **W10** | **Table DDL.** Create tables; evolve schema and partition spec. | metadata commit | DE | later |

---

## Cross-cutting requirements

These are not features to be scheduled — they are properties every command must have from the
first release, because retrofitting them is expensive.

- **`--json` on every command.** Human-readable output is the default; machine-readable output is
  what lets the tool be called from Airflow, CI and alerting. A tool without it stays a toy.
- **`--dry-run` on every write command, and never writing without an explicit intent.** Anything
  that deletes files states what it would delete and requires confirmation.
- **Catalog profiles in a config file.** Connection details are named once, not passed on every
  invocation.
- **Meaningful exit codes.** `diagnose` can fail a CI job when a table is unhealthy.
- **Read-only by default.** The tool must be safe to point at production before it is trusted to
  change anything there.

## Non-goals

- **Not a query engine, and no embedded one.** DuckDB, Polars and DataFusion already do this well.
  The domain here is metadata, health and maintenance — not `SELECT`. Integration runs the other
  way: structured output that those tools consume.
- **Not a catalog server.** It connects to catalogs; it does not become one.
- **Not a web UI.** Terminal first. A TUI may follow; a browser will not.
- **Not a replacement for Spark or Flink for data rewriting.** Operations that need an execution
  engine are the last tier deliberately, and may delegate rather than reimplement.

---

## Roadmap

**v0.1 — read-only inspection.** `R1`–`R7`, `R9`, `R10`, `R13`. Hadoop and REST catalogs, JSON
output, profiles. No writes at all: the tool has to be indispensable at reading before it earns
the right to change anything.

These ten commands are less work than they look. They are projections over one loaded metadata
graph, so the marginal cost of each after the first three is small — most of the effort is in the
catalog connection, the metadata reader, and the output layer, all of which they share.

**v0.2 — first writes and deeper reads.** `R8`, `R11`, `R12`, `R16` plus the metadata-commit tier
`W1`, `W2`, `W3`, `W5`. Every one of these writes is reversible, which is what makes them the
right place to start.

Also **server-side scan planning** (Iceberg 1.11's `/v1/.../plan` endpoint): let the catalog plan
the scan and return filtered scan tasks instead of fanning out over manifests ourselves. It turns
minutes into seconds on `R7`, `R8` and `R14` — and the Java client already exists, while
iceberg-go has no support for it at all.

**v0.3 — file deletion and pruning analysis.** `R14`, `R15`, `W4`, `W6`. Irreversible operations
arrive only once the reporting that justifies them is trusted.

**v0.4 — engine-backed maintenance.** `W7`, `W8`. Requires an execution capability; the design has
to allow the tool to run without one.

**Later.** `W9`, `W10`, catalog-wide diagnosis across many tables at once, a TUI, a container image
so the tool drops into Airflow and Dagster as a step, and OpenTelemetry export from `diagnose` so
`PE` can run it as a fleet health exporter rather than a command.

## Watching

Not scheduled — direction the ecosystem is moving, tracked so the design does not preclude it.

- **File Format API** (shipped in Iceberg 1.11.0) makes file formats pluggable. Consequence today:
  treat `FileFormat` as an open set, never a closed `parquet | orc | avro`, and keep
  format-specific work (`R15`) in a plugin rather than a branch in the core.
- **Vortex** — [PR #15915](https://github.com/apache/iceberg/pull/15915) is open, not merged,
  blocked on how Iceberg should admit new formats. Until it lands, `vortex` is not a legal
  `file_format`, so there is nothing to support. Revisit when it merges. Lance follows for
  AI/ML workloads.
- **Arrow** — not used internally: the data here is metadata rows, and Arrow Java's off-heap
  allocators buy nothing for that. Possible later as an output codec, behind the same pluggable
  interface as NDJSON.

---

## Licence

Licensed under the [Apache License 2.0](LICENSE) — the same licence as Apache Iceberg itself.

Apache®, Apache Iceberg™ and the Apache Iceberg logo are trademarks of
[The Apache Software Foundation](https://apache.org). This project is an independent tool for
working with Apache Iceberg tables; it is not affiliated with, endorsed by, or sponsored by the
Apache Software Foundation.
