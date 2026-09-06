# CLI

Command reference for `v0.1`. Output samples show shape, not final formatting.

## Quick start

```bash
iceberg-doctor ls                             # what namespaces exist
iceberg-doctor ls prod.events                 # what is in one
iceberg-doctor describe prod.events.clicks    # schema, spec, sort order, size
iceberg-doctor diagnose prod.events.clicks    # what is wrong with it
```

## Commands

Commands are named after the Iceberg metadata table or procedure they correspond to, so anything
with a `=` below can also be reached with SQL.

```
catalogs                          configured catalogs
ls [<namespace>]                  namespaces, tables and views          R1

describe   <table>                schema, spec, sort order, size        R2 R3
properties <table>                table properties                      R2

snapshots  <table>                = tbl.snapshots                       R4
history    <table>                = tbl.history                         R4
refs       <table>                = tbl.refs                            R5
manifests  <table>                = tbl.manifests                       R3
files      <table>                = tbl.files                           R6
partitions <table>                = tbl.partitions                      R7
metadata_log_entries <table>      = tbl.metadata_log_entries            R3

commits    <table>                write pattern over the last N days    R10
diagnose   <table>                conformance checks                    R9
```

`commits` and `diagnose` are the only two that compute something Iceberg does not store.

`snake_case` is canonical; `kebab-case` works too.

## Addressing a table

```bash
iceberg-doctor snapshots prod.events.clicks                    # via catalog
iceberg-doctor snapshots s3://lake/events/metadata/v42.json    # direct, no catalog   R13
```

An argument containing `/` is a metadata file path; otherwise it is a table identifier.

## Options

| Option | |
|---|---|
| `--catalog <name>` | which configured catalog; otherwise the default |
| `--snapshot <id>` · `--ref <name>` · `--as-of <ts>` | what to read at; `main` by default |
| `--depth metadata\|manifest-lists\|manifests\|data` | how deep to read |
| `--format human\|json\|ndjson` | `human` by default |
| `--limit <n>` · `--no-color` | |

Every command prints the snapshot id it read, so a result can be reproduced.

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

`--section schema` narrows it to one block.

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

### `files`

Summarised by default; `--list` prints rows.

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
```

### `partitions`

```
$ iceberg-doctor partitions prod.events.clicks --top 3

PARTITION                              FILES     RECORDS      SIZE  DELETES  AVG FILE
event_ts_day=2026-09-06/country=US       412  41,204,882    3.2 GB       12    7.9 MB
event_ts_day=2026-09-06/country=DE        88   6,102,441    486 MB        0    5.5 MB
event_ts_day=2026-09-05/country=US       401  40,882,004    3.1 GB        9    7.9 MB

grouped by spec 1 · 1,842 partitions
12 further partitions under spec 0, written before the spec changed
```

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

Design in [diagnostics.md](diagnostics.md).

## Machine-readable output

`--format json` emits one document, `--format ndjson` one object per line. Both carry
`schema_version` and the snapshot read, so a downstream job can confirm the table has not moved.

```json
{"schema_version":1,"table":"prod.events.clicks","snapshot_id":8231847263847,
 "depth":"manifests","findings":[
   {"property":"write.target-file-size-bytes","severity":"high",
    "threshold":{"value":536870912,"source":"table-property"},
    "measurement":{"p50_bytes":3250585,"file_count":4812,"under_open_file_cost":2904},
    "impact":{"bytes_below_target":41018654720,"excess_file_opens":2904},
    "remedy":{"command":"rewrite_data_files"}}]}
```

Exit codes: `0` clean · `1` findings at or above the gate · `2` the tool failed.

Human output may change between releases. JSON is versioned.

## Later

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
