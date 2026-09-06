# Conceptual model

The entities the tool works with, their attributes, and what can be done with each. This is a
domain picture, not a CLI design — the command surface follows from it later.

Scope discipline: this covers what the `v0.1` use cases (`R1`–`R7`, `R9`, `R10`, `R13`) actually
touch, with the structure left open where the rest of the spec will slot in. Each entity notes
the use cases that need it. Nothing is modelled "because the spec has it".

Every entity below is Iceberg's, named as Iceberg names it, except the ones in
[Derived views](#derived-views), which are explicitly the tool's own.

## Two planes and one seam

```
CATALOG PLANE                          TABLE PLANE
                                       
  Catalog                                TableMetadata
    └─ Namespace                           ├─ Schema*        / current
         ├─ Table  ──────seam──────▶       ├─ PartitionSpec* / default
         └─ View                           ├─ SortOrder*     / default
                                           ├─ Snapshot*      / current
                                           ├─ SnapshotRef    (branches, tags)
                                           └─ snapshot log, metadata log
                                                    │
                                              Snapshot
                                                    │  manifest list
                                              ManifestFile*
                                                    │
                                              ManifestEntry*
                                                    │
                                              DataFile | DeleteFile
```

The two planes meet at exactly one point: **a table identifier resolves to a `TableMetadata`**.
Everything left of the seam is naming and discovery; everything right of it is the table's own
content.

This is a real boundary, not a tidy diagram. `R13` (offline inspection) enters the table plane
from a `metadata.json` path with no catalog at all. If the seam is an assumption rather than an
interface, `R13` cannot exist.

## Catalog plane

### Catalog — `R1`

The resolver for names. Not a thing that is inspected; a thing that is asked.

| Attribute | Notes |
|---|---|
| `type` | `rest`, `hadoop`, and others later |
| `properties` | connection configuration |
| `warehouse` location | where tables live, when the catalog exposes it |
| capabilities | see below |

**Actions:** list namespaces · list tables · list views · load table · load view

**Capabilities are part of the model, not an afterthought.** The REST spec standardises the wire
protocol but not what a given catalog supports. A catalog is asked, never assumed: nested
namespaces, views, credential vending, server-side scan planning. A tool that assumes uniformity
fails on the second catalog it meets.

### Namespace — `R1`

| Attribute | Notes |
|---|---|
| `levels` | multi-part; `a.b.c` is one namespace, not three, and nesting is catalog-dependent |
| `properties` | key/value |

**Actions:** list child namespaces · list tables · list views · read properties

### Table and View — `R1`

A table identifier is a namespace plus a name. `View` is modelled as a sibling of `Table` from
the start — it has its own metadata, versions and representations — but nothing beyond listing it
is in scope before `v0.2`. Making it a sibling now costs nothing; retrofitting it later would
cost a refactor.

## Table plane

### TableMetadata — `R2`, `R3`, `R13`

The root document, and the only entity loaded in full.

| Group | Attributes |
|---|---|
| Identity | `format-version`, `table-uuid`, `location` |
| Counters | `last-sequence-number`, `last-column-id`, `last-partition-id`, `last-updated-ms`, `next-row-id` (v3) |
| Registries | schemas, partition specs, sort orders, snapshots — each with a current/default pointer |
| Refs | name → `SnapshotRef` |
| Logs | snapshot log, metadata log |
| Properties | the table's declared intent — the source of every conformance threshold (`R9`) |
| Statistics | `statistics`, `partition-statistics` — Puffin side-cars, detail deferred |

**Actions:** load (from catalog, or from a path) · read any of the above · resolve a ref or
timestamp to a snapshot

### The registry pattern

Four of those groups are the same shape: **a list of every version ever defined, plus a pointer
to the current one.**

| Registry | Pointer |
|---|---|
| schemas | `current-schema-id` |
| partition specs | `default-spec-id` |
| sort orders | `default-sort-order-id` |
| snapshots | `current-snapshot-id` |

Worth naming once because it explains a whole class of findings: **files keep referencing the
member of the registry they were written under.** A data file carries its `sort_order_id`; a
manifest carries its `partition_spec_id`. When the pointer moves and the files do not, that gap
is exactly what the `default-spec-id` and `default-sort-order-id` checks measure.

### Schema — `R2`

`schema-id`, fields, identifier field IDs.

A **field** has `id`, `name`, `required`, type, doc, and (v3) initial and write defaults. Types
are primitive, struct, list or map; v3 adds `variant`, geometry and geography, nanosecond
timestamps and `unknown`.

**The field ID is the identity.** Names are free to change. Every metrics map, bounds map,
partition source and sort source keys on ID.

**Actions:** read fields · resolve a field by ID or by name at a given schema

### PartitionSpec and SortOrder — `R2`, `R7`

A **partition spec** has `spec-id` and fields, each with source field ID(s), its own field ID, a
name and a transform (`identity`, `bucket[N]`, `truncate[W]`, `year`, `month`, `day`, `hour`,
`void`; v3 adds multi-argument transforms).

A **sort order** has `order-id` and fields, each with a source ID, transform, direction and null
ordering.

**Actions:** read · resolve the spec a manifest or file was written under

### Snapshot — `R4`, `R10`, `R12`

| Attribute | Notes |
|---|---|
| `snapshot-id`, `parent-snapshot-id` | history is a DAG, not a list — branches fork it |
| `sequence-number` | the ordering that delete files are applied by |
| `timestamp-ms` | |
| `manifest-list` | the entry point to this snapshot's files |
| `summary` | `operation` (append, replace, overwrite, delete) plus counters and the writing engine — the whole basis of `R10` |
| `schema-id` | the schema in force when it was written |
| `first-row-id`, `added-rows` | v3 row lineage |

**Actions:** read · walk to parent · load its manifest list · diff against another snapshot
(`R12`)

### SnapshotRef — `R5`

A named pointer: branch or tag. `main` is the branch that `current-snapshot-id` follows.

| Attribute | Branch | Tag |
|---|---|---|
| `snapshot-id` | ✓ | ✓ |
| `min-snapshots-to-keep` | ✓ | — |
| `max-snapshot-age-ms` | ✓ | — |
| `max-ref-age-ms` | ✓ | ✓ |

Retention lives here, not only on the table. **This is why retention checks are per-ref**: a
branch's own settings override the table property for snapshots reachable from it.

**Actions:** read · resolve to a snapshot · list snapshots reachable from it

### ManifestFile — `R3`, `R6`

An entry in a snapshot's manifest list. Carries enough summary to answer some questions without
opening the manifest itself.

`manifest_path`, `manifest_length`, `partition_spec_id`, `content` (data or deletes),
`sequence_number`, `min_sequence_number`, `added_snapshot_id`, added/existing/deleted file and
row counts, and per-partition-field summaries (null and NaN presence, bounds).

**Actions:** read summary · open to yield entries

### ManifestEntry — `R6`, `R7`, `R11`

The record that binds a file to a snapshot: `status` (added, existing, deleted), `snapshot_id`,
`sequence_number`, `file_sequence_number`, and the file itself.

**Status is what makes a file live or dead**, so it is the entry — not the file — that decides
membership of a snapshot.

### DataFile and DeleteFile — `R6`, `R7`, `R8`, `R11`

One shape with a `content` discriminator: data, position deletes, or equality deletes.

| Group | Attributes |
|---|---|
| Identity | `file_path`, `file_format`, `content` |
| Placement | `partition` tuple, `sort_order_id` |
| Size | `record_count`, `file_size_in_bytes`, `split_offsets` |
| Column metrics | column sizes, value counts, null counts, NaN counts, lower and upper bounds — **all keyed by field ID** |
| Deletes only | `equality_ids`, `referenced_data_file`, `content_offset` / `content_size_in_bytes` (deletion vectors) |
| v3 | `first_row_id` |

`file_format` is an open set. Iceberg 1.11.0 made formats pluggable; Vortex and Lance are queued
behind that API.

**Actions:** read attributes · read a column metric by field ID

## Derived views

Not in the spec. Computed by the tool, and marked as such wherever they surface.

| View | Derived from | Used by |
|---|---|---|
| **FileSet** | manifest entries at a snapshot, filtered by status and sequence number | everything file-level |
| **Partition** | a FileSet grouped by partition tuple **under a given spec** | `R7` |
| **Size distribution** | percentiles over a FileSet | `R6`, `R9` |
| **Commit history** | snapshot log joined to snapshot summaries | `R10` |
| **Finding** | a check applied to any of the above — see [diagnostics.md](diagnostics.md) | `R9` |

Two of these carry warnings worth stating in the model itself:

**FileSet is the central derived concept.** Nearly every file-level answer is a projection of it.
Getting its definition right — which entries count as live at a given snapshot, and how delete
files apply by sequence number — is the single highest-leverage piece of the domain.

**Partition is not an Iceberg entity.** It is a grouping, and it is only meaningful relative to a
spec. A table whose spec has evolved holds files under several specs at once, so any table-wide
partition aggregate must say which spec it grouped by, or it is wrong.

## Actions, in general

For `v0.1` every action is one of four, and none of them mutate:

| | | Cost |
|---|---|---|
| **Resolve** | a name or a path to an entity | catalog call, or one file read |
| **Read** | attributes of an entity already loaded | free |
| **Traverse** | to child entities | I/O — this is where cost lives |
| **Derive** | a view from the above | CPU over what was traversed |

Traversal cost is the depth axis used throughout: `metadata` → `manifest-lists` → `manifests` →
`data`. The same names appear in [diagnostics.md](diagnostics.md); a command states how deep it
went, because an answer from manifest-list summaries and an answer counted from manifests are
different claims.

**Mutation is deliberately absent, and when it arrives it will not be invented.** The spec
already defines the algebra: a commit is a base metadata document, a set of assertions that must
still hold, and a set of atomic updates — `AddSnapshot`, `SetSnapshotRef`, `RemoveSnapshots`,
`SetProperties`, and about twenty more. The write path adopts that vocabulary rather than
building one.

## Deliberately out of scope for now

Left out to keep the base small; none of them require the model to change shape.

- View metadata beyond listing — versions, representations, version log
- Puffin blob contents — statistics files and partition statistics files are modelled as
  references only
- Encryption keys (v3)
- Row lineage semantics beyond carrying the fields
- Anything the File Format API will bring for non-Parquet formats
