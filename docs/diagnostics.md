# Diagnostics

Design for `R9` — the `diagnose` command. This is the feature the project is named after, so it
gets its own document.

## Governing principle

**Do not build a vocabulary on top of Iceberg's.** Everything a user sees — the thing being
checked, the threshold, the evidence, the fix — is expressed in terms Iceberg already defines:
snapshots, manifests, manifest lists, data files, delete files, partition specs, sort orders,
refs, sequence numbers, and table properties by their real names.

The tool navigates and measures. It does not reinterpret. A user who learns `iceberg-doctor`
should come away knowing Iceberg better, not knowing `iceberg-doctor` better.

## Scope: conformance checks only

A **conformance check** asks one question:

> Does the table's actual state match what the table itself declares it wants?

Nothing else ships in the first versions. Two other kinds of check are deliberately deferred:

- **Policy** — "does this table meet *our* standards?" Thresholds come from the user, not the
  table. Needs a config format and a scoping model; not yet.
- **Sanity** — "is the declared intent itself suspicious?" (`gc.enabled=false`,
  `write.metadata.metrics.default=none`). These are the tool's opinion. Valuable, but opinion has
  to be earned; conformance has to be trusted first.

The payoff of this restriction is large: **conformance checks have no thresholds of the tool's
own.** Every threshold comes from one of exactly two places, and the finding always says which:

| `threshold.source` | Meaning |
|---|---|
| `table-property` | The table set this value explicitly |
| `iceberg-default` | The table did not set it; this is Iceberg's documented default |

There is no third source. Nothing is invented, so nothing is arguable except the facts.

## A check is identified by what it checks

A conformance check does not get a made-up code. **Its identity is the Iceberg property or
metadata field it measures conformance against.**

```
write.target-file-size-bytes           not  SMALL_FILES
history.expire.max-snapshot-age-ms     not  SNAPSHOT_BLOAT
default-spec-id                        not  STALE_PARTITION_SPEC
```

This costs some verbosity on the command line. It buys three things: the user can look the
identifier up in the Iceberg documentation, suppression files reference real properties rather
than our labels, and no glossary has to be maintained.

## Every finding is verifiable by hand

Each finding names the Iceberg metadata table a user could query to reproduce it:

```
verify: SELECT file_path, file_size_in_bytes FROM prod.events.clicks.files
```

This is the principle made concrete. The tool is a faster path to an answer the user could always
have reached themselves — not a black box with its own truth.

## Finding structure

```
property     the Iceberg property or metadata field being checked
subject      table | partition | ref | spec — what this finding is about
measurement  the observed facts, in Iceberg's units
threshold    value + source (table-property | iceberg-default)
impact       bytes, or file opens (see below)
severity     derived, never assigned by the check
remedy       the Iceberg operation that fixes it, named as Iceberg names it
verify       the metadata-table query that reproduces the measurement
depth        which metadata layer was read to produce this
```

### Severity is derived, not written by the check

A check produces measurement, threshold and impact. The engine derives severity from deviation
(measured ÷ threshold) and absolute impact. A table ten times past target but 100 MB in total is
not urgent; a table twice past target but 40 TB is.

Keeping this out of the checks matters for two reasons: the scale stays consistent across checks,
and severity never appears without the raw numbers beside it. The label is a sort key for
automation, not the thing the user is meant to read.

### Impact is measured in Iceberg's own cost model

Two currencies, both grounded in the format rather than invented:

- **Bytes** — storage retained beyond what the table's declared retention allows.
- **File opens** — Iceberg declares `read.split.open-file-cost` (default 4 MB): its own estimate
  of what opening a file costs, expressed in bytes of scanning. A file smaller than that costs
  more to open than to read. So the waste from small files is
  `Σ max(0, open-file-cost − file_size)` — Iceberg's arithmetic, not ours.

A monetary figure, if shown, is derived from bytes with a user-supplied rate. It is not a third
measurement.

### Where opinion unavoidably leaks, make it visible

"Small file" needs a cutoff, and `write.target-file-size-bytes` alone does not give one — almost
every file is below target. Two responses, both staying inside Iceberg:

1. Report the **distribution relative to target** (p50, p95 as a ratio) and let the deviation
   speak, rather than classifying each file.
2. Count files below `read.split.open-file-cost` separately. That is not a judgement — by
   Iceberg's own cost model such a file is pathological.

Any cutoff beyond these is a named parameter printed in the output, never a hidden constant.

## A check result is three-valued

```
Applicable(findings)      checked; an empty list means the table conforms
NotApplicable(reason)     e.g. format v1 has no delete files
Failed(error)             a manifest could not be read
```

For a human the difference between the last two is cosmetic. For an Airflow DAG it is critical:
"checked and fine" and "could not check" must never collapse into the same result.

## Depth

Layers are named after the Iceberg structures they read:

| Depth | Reads | Example check |
|---|---|---|
| `metadata` | `metadata.json` only | `history.expire.max-snapshot-age-ms` |
| `manifest-lists` | + manifest lists | `commit.manifest.target-size-bytes` |
| `manifests` | + manifests | `write.target-file-size-bytes` |
| `data` | + data files | actual sortedness of file contents |

`diagnose` reads to `manifests` by default; `--deep` permits `data`. Each finding reports the
depth it came from, because "estimated from manifest-list summaries" and "counted exactly" are
different claims.

The same question can be two checks at different depths. Sort order is the clearest case:
comparing each data file's `sort_order_id` against the table's `default-sort-order-id` is a
manifest-level field lookup and catches most real problems; verifying that the rows inside a file
are actually ordered requires reading the file.

## Automation contract

`diagnose` is an interface other systems build on, so it is versioned like one.

1. **Identifiers are stable.** They are Iceberg property names, so they change only when Iceberg
   changes them. Adding a check is a minor version; changing what one means is breaking.
2. **`schema_version` is present in JSON output from the first release.**
3. **Exit codes**: `0` nothing at or above the gate, `1` findings at or above it, `2` the tool
   itself failed. The gate is a flag.
4. **Remedies are structured**, so a DAG branches on the operation instead of parsing prose. The
   operation is named as Iceberg names it — `rewrite_data_files`, `expire_snapshots` — which is
   also the command that performs it, so there is nothing to translate.
5. **Suppressions** map table + property to a reason and an expiry. Without them a DAG drowns in
   known findings within a month and stops being read.
6. **Findings are pinned to a snapshot id, and report it.** A diagnosis taken while writes are
   landing describes a state that no longer exists. Any action taken in response must carry that
   snapshot id and confirm it is still current before acting.

Point 6 is the one that makes `diagnose` safe to automate rather than merely convenient.

## Checks for v0.1

| Property or field | Depth | Compares |
|---|---|---|
| `write.target-file-size-bytes` | manifests | data file size distribution against target |
| `write.delete.target-file-size-bytes` | manifests | delete file sizes against target |
| `commit.manifest.target-size-bytes` | manifest-lists | manifest sizes against target |
| `commit.manifest.min-count-to-merge` | manifest-lists | manifest count against merge threshold |
| `history.expire.max-snapshot-age-ms` | metadata | snapshot ages against retention, per ref |
| `history.expire.min-snapshots-to-keep` | metadata | snapshot count against the floor |
| `write.metadata.previous-versions-max` | metadata | metadata log length against the cap |
| `default-spec-id` | manifests | files written under a non-current partition spec |
| `default-sort-order-id` | manifests | files written under a non-current sort order |

Refs carry their own `max-snapshot-age-ms` and `min-snapshots-to-keep`, which override the table
property for snapshots reachable from that ref. Retention checks are therefore per-ref, not
per-table.

**Build order:** two or three checks with the full frame around them — threshold provenance,
derived severity, three-valued results, depth reporting, the output contract — before adding the
rest. The frame has to settle on a small number of cases. Once it has, each further check is one
file.

## Vocabulary

Everything above is Iceberg's, except three words. Adding a fourth requires a reason.

| Term | Why it exists |
|---|---|
| **finding** | The unit of output. Iceberg has no word for it. |
| **check** | A single conformance question. |
| **severity** | An ordering, needed only so automation has a gate to compare against. Never shown without the measurement beside it. |

Deliberately **not** introduced: a health score. A number like "62/100" cannot be verified,
cannot be argued with, and teaches the user nothing about their table. Findings and their impact
are the output; a summary line counts them, and stops there.
