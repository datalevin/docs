---
title: "Storage Fundamentals: LMDB, Key–Value Layout, and Persistence"
chapter: 4
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 4: Storage Fundamentals: LMDB, Key–Value Layout, and Persistence

Datalevin's query model is fact-centric, but its performance depends on storage
choices. This chapter explains why Datalevin builds on LMDB, how DLMDB extends
it, and how the physical layout supports both normalized queries and secondary
index capabilities.

## 1. Why LMDB as the Foundation

LMDB's core strength is simplicity. It relies heavily on operating-system
services (memory mapping, virtual memory, filesystem sync) instead of rebuilding
its own buffer manager and page cache in user space.

### 1.1 Key Features and Architecture

- **Performance**: very high read efficiency via memory-mapped pages and a
  zero-copy design. On the read hot path, data can be returned directly from
  mapped memory without per-read `malloc`/`memcpy`. Although Datalevin reaches
  LMDB through JNI, it preserves this property by mapping native values to Java
  direct `ByteBuffer` views (with unsafe/native fast paths) instead of copying
  payloads into heap arrays.
- **Concurrency**: MVCC snapshots give non-blocking reads alongside writes.
  Readers scale well with thread count for read-heavy workloads, while writes are
  serialized through a single writer.
- **Storage model**: single-level ordered key-value store (B+tree), with fast
  cursor-based iteration and range traversal.
- **Reliability**: copy-on-write page updates preserve integrity; recovery is
  immediate to the last committed state without a separate log/journal replay
  process.

### 1.2 Configuration and Usage

- **Embedded**: LMDB runs in-process with the application.
- **Footprint**: very small code base (commonly cited around ~32KB object code
  for the core library).
- **Environment layout**: typically one mapped data file plus a lock file in a
  directory (or a direct data path with a corresponding `-lock` file).
- **Tuning model**: comparatively configuration-light relative to many server
  databases; most applications need only a small set of options.

### 1.3 Core Implementation

- **Language core**: LMDB is implemented in C.

Datalevin's Datalog/KV transactions map directly to underlying LMDB
transactions, so the semantic path from API to storage is short and explicit.

## 2. Why B+tree (Here) Instead of LSM

This is not a universal claim that B+tree is always better than LSM. It is a
design choice for Datalevin's goals.

For Datalevin, B+tree storage is a strong fit because:

- it handles variable and large values naturally (including overflow pages),
- sorted access paths are first-class for both point and range queries,
- adding new index structures as additional DBIs is straightforward,
- no compaction pipeline is required to keep read paths coherent.

Here, **DBI** means an LMDB sub-database (a named key-value namespace) inside
the same LMDB environment/file. You can think of each DBI as a logical index or
table-like keyspace with its own key ordering and flags, while still sharing the
same transactional boundary.

By contrast, LSM-based systems often trade this for higher write throughput via
background compaction, which can complicate read-path predictability and
operational behavior when many secondary indexes are present.

That flexibility matters because Datalevin keeps extending index capabilities
(full-text, vector, idoc, and others) while keeping them in one storage engine.

## 3. From LMDB to DLMDB

Datalevin uses DLMDB, a fork of LMDB with extensions that are specifically useful
for datalog query planning and execution:

- **Order statistics (`:counted` DBIs)**: efficient rank lookup, sampling, and
  range count.
- **Prefix compression**: better space efficiency and cache behavior, especially
  for redundant key patterns.
- **DUPSORT iteration optimizations**: faster traversal in duplicate-heavy
  patterns.
- **Robust interrupt handling**: operational hardening for real workloads.

These are not cosmetic changes. They directly power query-planning primitives:
cheap counting and sampling under query conditions.

## 4. Nested Triple Index Layout with DUPSORT

Datalevin stores datoms in multiple orderings (notably EAV and AVE). Instead of
literal flat triples everywhere, it uses LMDB's DUPSORT (section 6.4) to
nest repeated heads.

Conceptually:

```text
EAV view:  E  -> [(A,V), (A,V), ...]
AVE view: (A,V) -> [E, E, E, ...]
```

This "B+trees of B+trees" style layout reduces redundancy and improves counting
behavior. For example, exact-pattern counts like `[?e :person/age 30]` can be
obtained very cheaply from AVE-oriented structures, while range patterns benefit
from counted metadata.

Short query examples that map naturally to these paths:

```clojure
;; exact value match
(d/q '[:find ?e
       :in $
       :where [?e :person/age 30]]
     db)

;; range predicate (pushed down to index range scans)
(d/q '[:find ?e
       :in $
       :where [?e :person/age ?age]
              [(< 20 ?age 40)]]
     db)
```

## 5. Secondary Indexes in the Same KV Environment

Datalevin's secondary indexes are not external services. They are stored as
additional DBIs (sub-databases) in the same key-value environment and same data
file.
User-defined KV DBIs can live alongside these Datalog DBIs in that same file.

That includes capabilities such as:

- full-text index structures,
- vector index domains,
- idoc path indexes.

Because they live in the same storage system, they can be joined with regular
triple clauses in one Datalog query context.

## 6. KV API in Practice

Even if you mostly use Datalog, the KV API is important because it exposes the
same storage substrate directly.
In other words, KV and Datalog keyspaces are not forced into separate files.

In Datalevin documentation and APIs, "EDN values" means Clojure data structures
encoded with EDN (Extensible Data Notation): a JSON-like notation with extra
data primitives such as keywords, symbols, sets, and tagged literals.

Use KV API when:

- your access path is direct key lookup or range scan,
- you want explicit control of DBIs/key types,
- you do not need Datalog joins/rules for that path.

The KV surface area is intentionally broad. For the canonical signatures,
arglists, and option details, use the API docs:
https://cljdoc.org/d/datalevin/datalevin

### 6.1 KV Function Map

| Category | Functions | Purpose |
| --- | --- | --- |
| Environment and lifecycle | `open-kv`, `close-kv`, `closed-kv?`, `dir`, `sync`, `set-env-flags`, `get-env-flags` | Open/close store, inspect handle, and control environment durability/flags |
| DBI management and inspection | `open-dbi`, `clear-dbi`, `drop-dbi`, `list-dbis`, `stat`, `entries`, `copy` | Manage sub-databases and inspect/copy physical structures |
| Transactions and WAL | `transact-kv`, `transact-kv-async`, `with-transaction-kv`, `abort-transact-kv`, `kv-wal-watermarks`, `flush-kv-indexer!` | Write path, explicit transaction scope, and WAL/indexer coordination |
| KV pair and codec helpers | `k`, `v`, `put-buffer`, `read-buffer` | Access raw key/value buffers and encode/decode typed KV data |
| Point and rank reads | `get-value`, `get-rank`, `get-by-rank` | Direct lookup and order-stat reads |
| Key-range reads, counts, and sampling | `get-first`, `get-first-n`, `get-range`, `key-range`, `key-range-count`, `key-range-list-count`, `range-seq`, `range-count`, `sample-kv` | Cursor/range traversal, efficient counting, and planner-friendly sampling |
| Predicate and visitor range ops | `get-some`, `range-filter`, `range-keep`, `range-some`, `range-filter-count`, `visit-key-range`, `visit` | Range filtering, projection, existence checks, and side-effect visitors |
| List DBI lifecycle and point ops | `open-list-dbi`, `put-list-items`, `del-list-items`, `get-list`, `visit-list`, `list-count`, `in-list?` | Multi-value-per-key DBIs (dupsort-backed) |
| List-range operations | `list-range`, `list-range-count`, `list-range-first`, `list-range-first-n`, `list-range-filter`, `list-range-keep`, `list-range-filter-count`, `list-range-some`, `visit-list-range` | Range/query operations over list DBIs by key and value |
| Cross-cutting maintenance | `re-index` | Rebuild/re-index storage structures (also supports KV stores) |

`clear` exists in the same namespace but applies to Datalog connections rather
than KV stores.
When using raw KV records from `visit`/`visit-key-range` and related APIs,
`k`/`v` extract buffers and `read-buffer`/`put-buffer` are the necessary codec
helpers for decoding/encoding typed values.

### 6.2 Public KV Data Types

While LMDB deals with raw bytes, Datalevin adds a layer of encoded data types.
Datalevin's public/stable KV type keywords (for `k-type`/`v-type`,
`put-buffer`, `read-buffer`) are:

| Type keyword | Value form | Notes |
| --- | --- | --- |
| `:data` | arbitrary EDN | Default; avoid for range-query keys; not allowed inside tuple types |
| `:string` | UTF-8 string | Ordered string key/value support |
| `:long` | 64-bit integer | Common for sortable numeric keys |
| `:id` | 64-bit integer id | Specialized id-ordered encoding used heavily by core indexes |
| `:float` | 32-bit IEEE754 float | Numeric |
| `:double` | 64-bit IEEE754 float | Numeric |
| `:bigint` | `java.math.BigInteger` | Bounded to documented encoding range |
| `:bigdec` | `java.math.BigDecimal` | Unscaled value bounded to documented encoding range |
| `:bytes` | byte array | Binary payloads |
| `:keyword` | EDN keyword | Useful for symbolic keys |
| `:symbol` | EDN symbol | Symbolic values/keys |
| `:boolean` | `true` / `false` | Boolean values |
| `:instant` | `java.util.Date` | Timestamp semantics |
| `:uuid` | `java.util.UUID` | UUID values/keys |

Tuple typing is also public:

- heterogeneous tuple type: vector of scalar types (for example
  `[:string :long :uuid]`)
- homogeneous tuple type: vector with one scalar type (for example `[:long]`)

Constraints called out by the API:

- each tuple element must be at most 255 bytes
- key size is capped at 511 bytes (the LMDB/Datalevin key-size class)
- in `open-list-dbi` (DUPSORT) stores, each list item is stored on the
  value/duplicate side but follows this same 511-byte bound
- values can be large (Datalevin supports large document values up to
  approximately 2 GiB); `:val-size` is the initial value-buffer size and can
  grow as larger values are transacted

### 6.3 `open-kv` Options

The documented `open-kv` shape is:

```clojure
(d/open-kv dir opts)
```

`dir` can be a local directory path or a `dtlv://` URI for remote access.

| Option | What it controls | Notes |
| --- | --- | --- |
| `:mapsize` | Initial LMDB map size (MiB) | Auto-expands as needed; larger initial values reduce resize events |
| `:max-readers` | Max concurrent reader slots | Important for read-heavy multithreaded workloads |
| `:max-dbs` | Max DBI/sub-database count | Capacity for core DBIs plus user DBIs in one file |
| `:flags` | LMDB environment flags | Examples: `:rdonly-env`, `:nosubdir`, `:nosync`, `:mapasync`, `:nordahead` |
| `:temp?` | Temporary KV store lifecycle | Temporary stores are deleted on JVM exit; Datalevin also applies `:nosync`, so this mode is a strong fit for cache-like workloads with very high read/write throughput |
| `:client-opts` | Remote client config | Applied when `dir` is a remote `dtlv://` URI |
| `:spill-opts` | Memory-pressure spill behavior for eager range APIs | Controls `get-range`/`range-filter` spill-to-disk behavior |

`spill-opts` keys:

- `:spill-threshold`: JVM memory-pressure percentage that triggers spill
- `:spill-root`: directory used for spill files

Use the API docs for current defaults, since exact default values may evolve:
https://cljdoc.org/d/datalevin/datalevin

Operational note: as with LMDB generally, keep one shared `open-kv` handle per
process per DB path/URI, rather than opening the same environment many times in
one process.
Operational tradeoff: `:temp?` implies `:nosync`, which improves write
throughput and is often ideal for caches, but reduces durability guarantees.
For write-throughput benchmark context, see:
https://github.com/datalevin/datalevin/tree/master/benchmarks/write-bench

Short example:

```clojure
(def kv
  (d/open-kv "/tmp/ch4-kv"
    {:mapsize     2048
     :max-readers 2048
     :max-dbs     256
     :spill-opts  {:spill-threshold 85
                   :spill-root      "/tmp/dtlv-spill"}}))
```

### 6.4 List DBI (DUPSORT) in Practice

`open-list-dbi` creates a DUPSORT-backed DBI: one logical key maps to many
sorted values.

```text
k -> [v1, v2, v3, ...]   ; duplicate values kept in sorted order
```

This is useful when a natural access path is "one key to many items" while
still needing efficient point/range operations over both keys and values.

- point/list ops: `put-list-items`, `get-list`, `del-list-items`, `list-count`,
  `in-list?`, `visit-list`
- range ops: `list-range`, `list-range-count`, `list-range-first`,
  `list-range-first-n`, `list-range-filter`, `list-range-keep`,
  `list-range-filter-count`, `list-range-some`, `visit-list-range`

Important limit detail: because list items are encoded on the DUPSORT duplicate
value side, the 511-byte key-size class effectively applies to each list item
value as well.

`transact-kv` operations are tuple-shaped. Common forms are:

- `[:put "<dbi>" key value]`
- `[:put "<dbi>" key value key-type]` (typed keys for range behavior)
- `[:del "<dbi>" key]`

Short example:

```clojure
(require '[datalevin.core :as d])
(import '[java.util Date])

(def kv (d/open-kv "/tmp/ch4-kv"))
(d/open-dbi kv "user")
(d/open-dbi kv "events")

;; One transaction can touch multiple DBIs atomically
(d/transact-kv kv
  [[:put "user" :u1 {:name "Alice" :tier :pro}]
   [:put "events" #inst "2026-01-01" {:kind :signup :uid :u1} :instant]])

;; Point read
(d/get-value kv "user" :u1)
;; => {:name "Alice" :tier :pro}

;; Range read on typed keys
(d/get-range kv "events" [:closed (Date. 0) (Date.)] :instant)

;; Explicit KV transaction when multiple steps must be one unit
(d/with-transaction-kv [kv-tx kv]
  (d/transact-kv kv-tx [[:del "user" :u1]]))

(d/close-kv kv)
```

For heavy write workloads, Datalevin also provides `transact-kv-async`, which
uses adaptive batching to improve throughput while preserving commit ordering.

## 7. Toward a Hybrid Mode: WAL + Overlay (WIP)

Datalevin is evolving toward a hybrid write path for write-intensive workloads.

The WAL mode design (in progress) is:

1. append a logical WAL record and sync it durably,
2. return transaction success at WAL durability boundary,
3. persist to base DLMDB structures at checkpoint,
4. serve non-checkpointed committed updates through an in-memory query overlay.

This preserves durability while improving write throughput/latency behavior. The
same WAL foundation is also intended to support replication, crash recovery, and
high-availability workflows. This capability is in progress and targeted to be
fully available by the time this book is published.

## Summary

Storage in Datalevin is intentionally layered:

- LMDB/DLMDB provides a simple, OS-leveraging key-value substrate,
- nested DUPSORT triple indexes provide efficient normalized access paths,
- secondary indexes are side DBIs in the same engine,
- KV API exposes that same substrate directly when you need low-level control,
- WAL+overlay mode extends this into a hybrid architecture for heavier write
  workloads.

This is why Datalevin can stay fact-centric and normalized at the model level
without giving up practical performance.
