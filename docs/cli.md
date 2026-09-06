# CLI

The command surface for `v0.1`, derived from [the conceptual model](model.md). Output samples
show shape, not final formatting.

## Shape

**Every command that corresponds to something Iceberg already names uses that name exactly.**
Read commands take the names of Iceberg's metadata tables, so a user who knows
`SELECT * FROM tbl.snapshots` already knows `iceberg-doctor snapshots tbl`. Write commands take
the names of Iceberg's stored procedures: `expire_snapshots`, `rewrite_manifests`,
`rewrite_data_files`, `remove_orphan_files`.

There are **no higher-level aliases** — no `compact` standing in for `rewrite_data_files`. An
alias would be friendlier to a newcomer for exactly one command and would then have to be
explained, translated in every remedy, and remembered as a second name for one thing. Using
Iceberg's name throughout means the tool teaches the platform rather than itself, and a user can
move between `iceberg-doctor`, Spark SQL and the Iceberg documentation without a translation
step.

So the surface splits the way the model does: nouns for reading, verbs for acting, and neither
set is ours.

### Naming convention

Iceberg names both its metadata tables and its procedures in `snake_case`. The canonical command
name is therefore the Iceberg name character for character, so that copy-paste between the CLI,
SQL and the documentation always works:

```
iceberg-doctor metadata_log_entries prod.events.clicks
iceberg-doctor rewrite_data_files   prod.events.clicks
```

`kebab-case` is accepted as an alias for typing comfort — `rewrite-data-files` resolves to the
same command — but the canonical spelling is what help text, JSON output and error messages
print.

## Addressing a table

```
iceberg-doctor snapshots prod.events.clicks                                   via catalog
iceberg-doctor snapshots s3://lake/warehouse/events/clicks/metadata/v42.json  direct  (R13)
```

An argument containing `/` or `://` is a metadata file path and no catalog is contacted;
otherwise it is a table identifier. Iceberg identifiers cannot contain `/`, so the two never
collide.

## Global options

| Option | Effect |
|---|---|
| `--catalog <name>` | which configured catalog to use; otherwise the default one |
| `--format human\|json\|ndjson` | `human` by default |
| `--snapshot <id>` · `--ref <name>` · `--as-of <ts>` | the coordinate to read at; `main` by default |
| `--depth metadata\|manifest-lists\|manifests\|data` | how deep to read; commands pick a sensible default |
| `--no-color` | |

The snapshot coordinate is global rather than per-command because it is the model's coordinate:
almost every table-plane question is asked *at* some snapshot. **Every table-plane command prints
the snapshot id it actually read**, so a result can be reproduced and a follow-up action can be
pinned to the same state.

## Command tree

Grouped by where each name comes from — which is itself the point.

```
iceberg-doctor
│
│  Catalog navigation · Iceberg has no metadata table for these
├─ catalogs                       configured catalogs
├─ ls [<namespace>]               namespaces, tables and views           R1
│
│  Named after an Iceberg metadata table · same name, same content
├─ snapshots  <table>             tbl.snapshots                          R4
├─ history    <table>             tbl.history — ancestry of current ref  R4
├─ refs       <table>             tbl.refs — branches and tags           R5
├─ manifests  <table>             tbl.manifests                          R3
├─ files      <table>             tbl.files                              R6
├─ partitions <table>             tbl.partitions                         R7
├─ metadata_log_entries <table>   tbl.metadata_log_entries               R3
│
│  Named after an Iceberg concept that has no metadata table
├─ describe   <table>             schema, spec, sort order, properties   R2 R3
├─ properties <table>             table properties                       R2
│
│  Derived by the tool · marked as derived wherever they surface
├─ commits    <table>             write pattern over the last N days     R10
└─ diagnose   <table>             conformance checks                     R9
```

Only the last two are the tool's own view of the data rather than a projection of something
Iceberg stores, and [the model](model.md#derived-views) says so. Everything else is a faster path
to a query the user could have written.

`describe` merges `R2` and `R3` into one sectioned view — the split in the README is about
content, not commands, and `DESCRIBE` is how people already ask this question. `--section` narrows
it. `properties` stays a command of its own because `properties set` and `unset` (`W3`) attach to
it in `v0.2`; `refs` likewise gains `create`, `drop` and `fast-forward` (`W1`).

## Output

### `ls`

```
$ iceberg-doctor ls prod.events

NAMESPACE   prod.events.archive

TABLE       prod.events.clicks           v2   2026-09-06 08:14
            prod.events.impressions      v3   2026-09-06 08:12

VIEW        prod.events.clicks_daily
```

### `describe`

```
$ iceberg-doctor describe prod.events.clicks

prod.events.clicks
  format version    2
  location          s3://lake/warehouse/events/clicks
  current snapshot  8231847263847   2026-09-06 08:14:22  append
  uuid              9f3c8a71-...

SCHEMA  current-schema-id 3
  1  event_id     string       required
  2  user_id      long         required
  3  event_ts     timestamptz  required
  7  country      string       optional
  identifier      event_id

PARTITION SPEC  default-spec-id 1
  1000  event_ts_day   day(event_ts)
  1001  country        identity(country)

SORT ORDER  default-sort-order-id 2
  event_ts  asc  nulls-last
  user_id   asc  nulls-last

PROPERTIES  4 set
  write.target-file-size-bytes        536870912
  history.expire.max-snapshot-age-ms  604800000
  write.format.default                parquet
  gc.enabled                          true

SIZE  depth manifest-lists
  data files      4,812    38.4 GB
  delete files      127     1.2 GB
  manifests          41    12.8 MB
  snapshots       1,204
  refs                3    main, backfill, weekly-2026-08
```

### `snapshots`

```
$ iceberg-doctor snapshots prod.events.clicks --limit 3

SNAPSHOT ID    PARENT         COMMITTED AT         OP         SEQ  +FILES  -FILES  +ROWS      ENGINE
8231847263847  8231847263801  2026-09-06 08:14:22  append     412      12       0  1,204,882  spark-3.5
8231847263801  8231847263755  2026-09-06 07:14:19  append     411      11       0  1,198,043  spark-3.5
8231847263755  8231847263702  2026-09-06 06:14:20  overwrite  410       8       8    904,112  spark-3.5

1,204 snapshots · oldest 2025-07-21 · depth metadata
```

### `refs`

```
$ iceberg-doctor refs prod.events.clicks

NAME             TYPE    SNAPSHOT ID    MIN SNAPSHOTS  MAX SNAPSHOT AGE  MAX REF AGE
main             branch  8231847263847              1                7d            –
backfill         branch  8231847001122              5               30d           90d
weekly-2026-08   tag     8230118273641              –                 –          365d
```

Retention columns come from the ref itself where set, and fall back to the table property —
which is why `diagnose` checks retention per ref rather than per table.

### `files`

Summarised by default. This is the one place the command deliberately departs from its metadata
table: `tbl.files` returns rows, and nobody wants 100,000 of them in a terminal.

```
$ iceberg-doctor files prod.events.clicks

prod.events.clicks @ snapshot 8231847263847   depth manifests

DATA FILES  4,812 · 38.4 GB
  size      min 12 KB · p50 3.1 MB · p95 41 MB · max 512 MB
  target    512 MB   ← write.target-file-size-bytes
  under read.split.open-file-cost (4 MB)   2,904 files   60%
  format    parquet 4,812

DELETE FILES  127 · 1.2 GB
  position 119 · equality 8 · deletion vectors 0

--list prints rows · --format ndjson pipes them
```

### `partitions`

```
$ iceberg-doctor partitions prod.events.clicks --top 3

PARTITION                              FILES     RECORDS      SIZE  DELETES  AVG FILE
event_ts_day=2026-09-06/country=US       412  41,204,882    3.2 GB       12    7.9 MB
event_ts_day=2026-09-06/country=DE        88   6,102,441    486 MB        0    5.5 MB
event_ts_day=2026-09-05/country=US       401  40,882,004    3.1 GB        9    7.9 MB

1,842 partitions under spec 1
   12 partitions under spec 0 — files written before the spec changed
```

The second line is not decoration. A partition aggregate is only meaningful relative to a spec,
so a table whose spec has evolved must say what it grouped by.

### `commits`

```
$ iceberg-doctor commits prod.events.clicks --last 14d

prod.events.clicks · 14 days · 336 commits · depth metadata

PER DAY     2026-09-06  ████████████████████████  24
            2026-09-05  ████████████████████████  24
            2026-09-04  ██████████████████        18
            …

OPERATION   append 312 (93%) · overwrite 22 (7%) · delete 2
ENGINE      spark-3.5 334 · trino-441 2
PER COMMIT  +11 files · +1.2M rows · +142 MB          (p50)
HOUR        68% of commits land 06:00–09:00 UTC
```

Answers "how is this table actually being written?" — the question that decides whether small
files are a maintenance problem or a writer configuration problem.

### `diagnose`

```
$ iceberg-doctor diagnose prod.events.clicks

prod.events.clicks @ snapshot 8231847263847   depth manifests

high    write.target-file-size-bytes
        p50 3.1 MB against target 512 MB · 2,904 of 4,812 files under
        read.split.open-file-cost
        threshold  536870912         ← table-property
        impact     38.2 GB below target · 2,904 excess file opens
        remedy     iceberg-doctor rewrite_data_files prod.events.clicks
        verify     SELECT file_path, file_size_in_bytes
                     FROM prod.events.clicks.files WHERE content = 0

medium  history.expire.max-snapshot-age-ms   ref main
        1,204 snapshots, oldest 412 d
        threshold  604800000 (7 d)   ← table-property
        impact     210 GB retained beyond declared retention
        remedy     iceberg-doctor expire_snapshots prod.events.clicks
        verify     SELECT * FROM prod.events.clicks.snapshots

ok      commit.manifest.min-count-to-merge    41 manifests, threshold 100
ok      default-sort-order-id                 all files at sort order 2
n/a     write.delete.target-file-size-bytes   no delete files at this snapshot

2 findings · 1 high · 1 medium · exit 1
```

## Machine-readable output

`--format json` emits one document; `--format ndjson` emits one object per line for piping into
DuckDB, Polars or `jq`. Both carry `schema_version` and the snapshot the answer was read at, so a
downstream action can confirm the table has not moved:

```json
{"schema_version":1,"table":"prod.events.clicks","snapshot_id":8231847263847,
 "depth":"manifests","findings":[
   {"property":"write.target-file-size-bytes","severity":"high",
    "threshold":{"value":536870912,"source":"table-property"},
    "measurement":{"p50_bytes":3250585,"file_count":4812,
                   "under_open_file_cost":2904},
    "impact":{"bytes_below_target":41018654720,"excess_file_opens":2904},
    "remedy":{"command":"rewrite_data_files"}}]}
```

Human output is for reading and may change between releases. JSON is an interface and is
versioned.

## Deferred to v0.2 and later

Listed so the tree above is understood as a first slice, not the whole design.

Each write command is the name of the Iceberg procedure it performs.

```
properties set / unset <table>                W3
refs create / drop <table>                    W1
fast_forward <table> <from-ref> <to-ref>      W1
rollback_to_snapshot <table> <snapshot>       W2
expire_snapshots <table>                      W4
rewrite_manifests <table>                     W5
remove_orphan_files <table>                   W6
rewrite_data_files <table>                    W7
rewrite_position_delete_files <table>         W8
diff <table> --from <snap> --to <snap>        R12
```

`diff` (`R12`) is derived, like `commits` and `diagnose` — Iceberg has no operation of that name.

## Open questions

- **`files` summarised by default** trades fidelity to `tbl.files` for terminal usability. The
  alternative — rows by default, `--summary` to aggregate — is more faithful and less useful.
- **`history` alongside `snapshots`** mirrors Iceberg, which has both, but the distinction
  (ancestry versus the snapshot records) is subtle enough that it may confuse more than it helps.
- **Colour and box-drawing** are unspecified. Whatever is chosen must degrade to plain ASCII under
  `--no-color` and when stdout is not a terminal.
