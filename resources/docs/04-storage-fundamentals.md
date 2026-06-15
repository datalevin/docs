---
title: "Storage Fundamentals: LMDB, Key–Value Layout, and Persistence"
chapter: 4
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 4: Storage Fundamentals: LMDB, Key–Value Layout, and Persistence

Datalevin's query model is fact-centric, but its performance and reliability are
grounded in its storage architecture. Unlike many databases that build custom
buffer managers and page caches, Datalevin leverages the operating system's
existing strengths by building on **LMDB** (Lightning Memory-Mapped Database).

This chapter explores why Datalevin chose this foundation, how it extends LMDB
into **DLMDB**, and how the physical layout of data supports efficient Datalog
queries, secondary indexes, and high-throughput writes.

---

## 1. The Foundation: Why LMDB?

LMDB is a small embedded key-value store with a deliberately narrow design. Its
philosophy is to do as little as possible in user space, offloading complex tasks
like memory management to the operating system kernel.

### 1.1 Why Memory-Mapping Fits Datalevin

Traditional databases often manage their own "buffer pool", a chunk of RAM where
they keep data pages. That design makes sense when the database is the main
program on the machine and wants tight control over every page of memory. But
many modern databases do not own the whole computer. They run in cloud VMs and
containers beside other services, on desktops beside user applications, or
embedded inside application servers. In those environments, the operating system
has a broader view of memory pressure than any one database process can have.

LMDB uses **memory-mapping (`mmap`)**. It treats the database file as if it were
a large array in the application's address space. When Datalevin reads data, the
OS handles fetching the required pages from disk into the **Page Cache**. This
does not mean the whole database file is loaded into RAM. The map reserves a
range of virtual address space; physical memory is used for pages that are
actually touched, and clean pages can be evicted by the kernel when memory is
needed elsewhere. This is not just an implementation shortcut; it is an
architectural choice. The OS already manages file-backed pages across all
processes on the machine, and it can evict, prefetch, and share those pages with
knowledge of the whole system.

Datalevin APIs usually ask for a directory path, but an LMDB environment is not
a directory full of table files. In the normal directory layout, the durable
B+tree pages live in one memory-mapped data file, conventionally `data.mdb`. The
directory may also contain support files, such as LMDB's lock/readers file
(`lock.mdb`) and Datalevin-managed WAL or snapshot files when those features are
enabled. Named DBIs are logical key-value spaces inside the same LMDB
environment; they are not separate data files.

A database-owned buffer pool sees only the database's allocation. It may keep a
page in its own cache while the OS also keeps the same page in the file-system
cache, causing "double buffering". It also competes with the rest of the machine
through a fixed memory budget chosen ahead of time. With mmap, Datalevin lets the
OS page cache be the buffer cache. If another process needs memory, clean
file-backed pages can be reclaimed by the kernel and read again later from the
database file.

This does not mean mmap is always the right design for every database. It shifts
some control from the database engine to the kernel, so systems that need custom
I/O scheduling, specialized eviction policies, or tight control over page-fault
latency may choose a private buffer manager. Datalevin's use case is different:
it favors an embedded, low-administration engine that cooperates well with the
rest of the host.

- **Zero-Copy Reads**: On the read hot-path, Datalevin doesn't copy data from a
  kernel buffer to a user-space buffer. Instead, it returns a `DirectByteBuffer`
  that points directly to the OS Page Cache. This bypasses JVM heap allocation
  and minimizes Garbage Collection (GC) overhead. By default, this memory map is
  **read-only**. That matters because many objections to mmap focus on writable
  mappings: accidental memory corruption, dirty-page writeback surprises, or
  weaker control over when modified pages reach storage. In LMDB's default mode,
  the read path does not modify the database file through the mapping. Writes go
  through LMDB's transaction machinery and copy-on-write discipline: new pages
  are prepared by LMDB and committed through file-system write and sync calls.
  Writable-map mode belongs to a different discussion: it is a non-default,
  non-durable write mode for cases where speed matters more than making the LMDB
  file durable at every commit, usually with another durability mechanism such as
  WAL. Datalevin's mmap argument here is about the default read-only map used for
  reads, not writable mappings for ordinary writes.

- **OS-Wide Cache Management**: The page cache is shared with the rest of the
  machine. This is a strong fit for cloud deployments, desktop applications, and
  embedded application servers, where Datalevin is one component among many. The
  kernel can make better global decisions about clean page eviction than a
  database process that only sees its own heap and buffer pool.

- **Less Configuration**: A private buffer pool needs a size. Set it too small,
  and the database misses cache opportunities. Set it too large, and it starves
  the application or the rest of the host. mmap lets Datalevin rely on the OS to
  grow and shrink the effective cache according to real memory pressure.

- **Immediate Recovery for Direct Commits**: LMDB uses a Copy-on-Write (CoW)
  B+tree. It never overwrites data in place. When a transaction commits, it
  updates the metadata that points to the new root of the tree. If the system
  crashes during LMDB's direct commit path, the database can choose the last
  valid committed root; no long log-replay process is needed for the LMDB file
  itself. Datalevin's optional WAL mode, discussed later in this chapter, adds a
  separate write-ahead log for write throughput and replication. That WAL may be
  replayed into LMDB after a crash, but it is an extra Datalevin layer rather
  than LMDB's default recovery mechanism.

### 1.2 Read-Heavy Concurrency

LMDB provides **MVCC (Multi-Version Concurrency Control)** [1]. Readers never
block writers, and writers never block readers. The easiest way to understand this,
especially for Clojure programmers, is to think of LMDB's B+tree as a persistent
immutable data structure [2].

When a read transaction starts, it sees one stable root of the tree. That root is
a snapshot: the reader can keep using it while other transactions commit newer
versions. A writer does not mutate the pages that existing readers may still be
using. Instead, it copies and modifies only the path from the root to the changed
leaf pages. The unchanged parts of the tree are shared between the old and new
versions.

At commit time, LMDB publishes the new version by switching the database root to
the newly written tree. Readers that started earlier keep following the old root;
new readers see the new root. This is structural sharing at storage-engine scale:
small updates copy a small part of the tree, large unchanged regions are shared,
and readers do not need locks on the writer's working pages.

This design gives Datalevin fast and safe read concurrency. Reads are zero-copy
and lockless, while writes remain atomic because a committed transaction becomes
visible only when the root switch succeeds. In read-heavy workloads, throughput
is often limited by CPU, memory bandwidth, or storage latency rather than by
reader/writer lock contention.

---

## 2. B+Trees vs. LSM-Trees

Database storage engines generally fall into two families: sorted tree designs
and log-structured designs. LMDB stores data in a B+Tree. Systems such as
Postgres and Oracle also rely heavily on B-tree-family indexes, while systems
such as Cassandra, RocksDB, and LevelDB use Log-Structured Merge-Trees (LSM) as
their main storage pattern. The table below summarizes the performance
characteristics of these two approaches.

A **B+Tree** stores sorted keys in fixed-size pages [3]. A lookup starts at the
root, walks through internal pages, and ends at a leaf page that contains the
target key or the place where it would be. Because leaf entries are ordered by
key, range scans can move through adjacent entries in sorted order. An
**LSM-Tree** takes a different path: it buffers writes and flushes sorted runs
to disk, then merges those runs in the background. This can make writes very
fast, but reads may need to consult several levels, and background compaction
can add latency and CPU variation.

| Feature | B+Tree (Datalevin/LMDB) | LSM-Tree (RocksDB) |
| :--- | :--- | :--- |
| **Read Performance** | Excellent (O(log N) point/range) | Variable (may check multiple levels) |
| **Write Performance** | Good (constrained by random I/O) | Excellent (sequential appends) |
| **Complexity** | Simple (no background compaction) | High (compaction, bloom filters) |
| **Large Values Handling** | Good (little write amplification) | Poor (rewrite data repeatedly during compaction) |
| **Space Efficiency** | Lower (due to fragmentation) | Higher (compressed on disk) |
| **Read Amplification / Tuning** | Low and predictable for point and range reads | Can require bloom filters, block cache, and compaction tuning |

**Why Datalevin chose B+Trees:**

Datalog query engine relies heavily on **range scans** and **index joins**. A
B+Tree keeps data in key order and provides predictable read latency. By
using LMDB, Datalevin avoids the "write stalls" and CPU spikes associated with
LSM background compaction, making it a better fit for predictable, real-time
applications. In addition, the multi-paradigm nature of Datalevin demands the
building of secondary indices (Section 5), which often require storing **large
values**, a pattern that is often less suitable for LSM storage because large
values can be rewritten repeatedly during compaction.

---

## 3. From LMDB to DLMDB

Standard LMDB is a general-purpose tool, but Datalevin uses a specialized fork
called **DLMDB** to support advanced Datalog features. DLMDB improves LMDB in
two ways that matter directly to query execution:

### 3.1 Order Statistics (`:counted`)

A standard B+Tree doesn't know how many items are in a range without scanning
them. DLMDB adds **counted B+Trees**, where each node in the tree stores the
count of items in its sub-tree. `:counted` is the DLMDB DBI flag that enables
this per-node count metadata; Datalevin uses it for the indexes where fast
counts, samples, and rank-based access matter. The counted B+Tree gives
Datalevin these advantages:

- **Instant Counts**: All count/sample operations are O(log n) or O(1). For
  example, `(d/count-datoms db nil :person/age nil)` is an O(log n) operation,
  while `(d/count-datoms db nil :person/age 30)` is O(1).
- **Fast Pagination**: You can skip the first 1,000,000 items in a sorted range
  and start reading the 1,000,001st item instantly using "rank-based" lookups.
- **Efficient Query Planning**: The Datalog engine uses these counts and samples
  to decide the most efficient join order (e.g., "should I filter by age first,
  or by city?").

### 3.2 Prefix Compression

Datalog indexes often contain many keys that start with the same prefix (e.g.,
many datoms for the same attribute). DLMDB compresses data by sharing these
prefixes, significantly reducing the on-disk footprint and improving CPU cache
locality. Prefix compression happens on the page level.

---

## 4. Physical Layout: The Nested Triple Index

At the logical level, a Datalevin fact is a datom: `[entity attribute value]`.
At the storage level, the same datoms are encoded into sorted key-value entries.
Datalevin stores the primary Datalog indexes in two orders:

- **EAV** (Entity-Attribute-Value): optimized for finding the facts about one
  entity.
- **AVE** (Attribute-Value-Entity): optimized for finding entities that have a
  particular attribute/value pair, and for range scans over attribute values.

![From a datom to indexed access in the EAV and AVE orders](/images/diagrams/datom-eav-ave.svg)

### 4.1 Leveraging DUPSORT

Instead of storing every triple as a completely separate flat key, Datalevin uses
LMDB's `DUPSORT` feature to "nest" sorted values under one key. A DUPSORT
database lets one key map to many sorted values. In Datalevin, that means common
prefixes such as the entity id in EAV, or the attribute/value pair in AVE, do not
have to be repeated for every matching datom.

**Conceptual EAV Layout:**

```text
Key: 101 -> Values: [(:person/name, "Alice"),
                     (:person/age, 30),
                     (:person/email, "alice@example.com")]

Key: 102 -> Values: [(:person/name, "Bob"),
                     (:person/age, 31)]
```

In EAV, the entity id is the key. The duplicate values under that key are sorted
attribute/value pairs. This makes "what facts do we know about entity 101?" a
single key lookup followed by a sequential read of that entity's facts.

**Conceptual AVE Layout:**

```text
Key: (:person/age, 30) -> Values: [101, 102, 105, 200, ...] (Entity IDs)
Key: (:person/age, 31) -> Values: [103, 104, 201, ...]
```

In this layout, the Attribute and Value form the **Key**, and all the Entities
that share that value are stored in a sorted **Duplicate List**. This makes
finding "all people aged 30" extremely fast: it is a single key lookup followed
by a sequential read of entity IDs.

The AVE index also makes reverse reference lookup cheap. A reference attribute is
just an attribute whose value is another entity id. If orders point to customers
with `:order/customer`, then "find all orders for customer `1001`" is an AVE
lookup on the key `(:order/customer, 1001)`. Datalevin does not need a separate
reverse-reference index for that case.

### 4.2 Storing Raw Bytes

All triples are encoded into raw bytes, using a header byte for different data
types. Datalevin binary encoding ensures that the binary sort order matches the
logical data sort order. For example, encoded numbers sort as numbers, encoded
strings sort as strings, and encoded instants sort by time. This property lets
the storage engine use the same byte ordering for equality lookup, range lookup,
and ordered iteration.

---

## 5. Secondary Indexes and Blob Storage

Beyond the primary triple indexes, Datalevin supports specialized secondary
indexes such as **Full-Text Search (FTS)**, **Vector Search**, and **Document
(idoc) Indexes**. An idoc is Datalevin's indexed document value: a nested map-like
document whose paths can be queried efficiently. These features are not external
plugins; they are integrated directly into the same LMDB environment.

### 5.1 Leveraging the KV Substrate

Secondary indexes are implemented as additional named sub-databases (DBIs)
within the same storage file. A **DBI** is a named logical key-value space inside
one LMDB environment. It lets Datalevin keep the primary datom indexes, full-text
postings, vector metadata, document path indexes, and internal bookkeeping in one
transactional storage substrate.

By default, Datalevin updates these indexes synchronously in the same transaction
as the source datoms, preserving read-your-writes behavior (new writes are
immediately visible) for query functions such as `fulltext`, `vec-neighbors`,
`embedding-neighbors`, and `idoc-match`. In other words, the secondary indexes
are immediately available for query upon commit by default.

For high throughput data ingestion though, full-text, vector, and embedding
indexes can also opt into `:indexing-mode :async`. In async mode, the source
datoms and a durable secondary-index job are committed atomically, then an
in-process worker applies the index update after the transaction returns.
Queries over that index become eventually consistent until the worker catches
up. This is useful when indexing is expensive, especially for embedding
providers that may call a local model or remote API.

### 5.2 Blob Storage Capabilities

While a Datalog triple is often a small piece of data, Datalevin also needs to
handle large values. There are two common cases.

- **Large secondary-index structures**: Full-text, vector, embedding, and idoc
  indexes may need to store postings lists, vector metadata, serialized document
  structures, or other large internal values. Datalevin can store these as blobs
  in specialized DBIs instead of scattering them through the main triple indexes.
  For example, a search index might store a large compressed postings bitmap as a
  single KV value, then retrieve and process it with the same zero-copy
  efficiency as a simple triple.
- **Large datom values**: A user datom can also have a large value, such as a
  document, binary payload, or long text value. Datalevin does not put the whole
  payload inline in EAV and AVE. Instead, it stores the large value in a
  dedicated `:datalevin/giants` DBI, keyed by an auto-incrementing integer
  **giant id**. The EAV and AVE indexes store that giant id, not the full value.
  When the datom is read, Datalevin follows the giant id to retrieve the original
  value from `:datalevin/giants`.

This indirection keeps the primary indexes compact and ordered even when some
values are large. It also lets Datalevin leverage LMDB's ability to store values
up to 2GB in size while preserving efficient equality lookup, range scans, and
secondary-index maintenance.

This capability is what makes Datalevin a "multi-paradigm" database: it uses the
same robust KV foundation to power everything from relational Datalog queries to
modern vector searches.

---

## 6. The Key-Value API: Direct Access

While Datalog is the primary interface, Datalevin exposes the underlying KV
store as a first-class citizen. This is a deliberate design choice: the Datalog
engine is built *on top* of this KV layer, rather than as a separate opaque
engine.

Datalog and custom KV data can live in the same LMDB file. The Datalog engine
uses named DBIs for indexes such as EAV and AVE, and application code can use
other named DBIs in the same store for direct key-value data. Those DBIs share
the same LMDB environment, durability settings, backup/copy behavior, and
transaction boundary. Treat Datalevin's internal DBIs as owned by the Datalog
engine; use separate, application-named DBIs for your own KV structures.

Exposing the KV layer allows developers to bypass the triple model when it isn't
the best fit, for example when building custom indexes, high-frequency counters,
or large binary blob stores. This "multi-paradigm" approach ensures that you
aren't forced to fit every data shape into a Datalog triple if a simple key-value
pair is more efficient.

The details of the KV API and its practical usage are covered in **Chapter 10**.

---

## 7. Persistence and Durability: WAL Mode

By default, local embedded Datalevin stores use LMDB's direct commit path. This
is safe and fast for many workloads, and, as described above, LMDB's copy-on-write
root switching gives the LMDB file immediate recovery after a crash.

For write-intensive workloads, Datalevin can add a **Write-Ahead Log (WAL) mode**
above LMDB. A WAL is an append-only transaction log. Datalevin first records the
transaction in that log, then applies the change to LMDB in a faster non-durable
mode. Durability comes from the WAL record, while LMDB remains the indexed
B+Tree state used for reads. On restart, Datalevin can replay committed WAL
records that were not yet reflected in the LMDB file.

This is why WAL recovery does not contradict LMDB's direct-commit recovery. They
apply to different modes. Without WAL, LMDB recovers by finding the last valid
committed root. With WAL, Datalevin may replay its own committed log records into
LMDB.

WAL mode also supports multi-thread write concurrency and lays the foundation for
data replication and high availability (HA) server behavior. In Chapter 4, the
important point is the storage shape: append a durable log record first, then
update the indexed LMDB state. The operational details are covered in later
chapters. WAL mode can be enabled in these ways:

- **Datalog**: Disabled by default for local embedded databases; enable with
  `{:wal? true}` in `create-conn` or `get-conn` options.
- **Key-Value**: Disabled by default; enable with `{:wal? true}` in `open-kv`
  options.
- **Async read replicas**: A non-HA replica requires the primary to have WAL
  enabled so it can bootstrap from a copy and then tail durable records.
- **HA**: Consensus-lease HA forces WAL on.

### 7.1 How WAL Mode Works

At a high level, a WAL transaction follows this sequence:

1.  **Transaction**: A write request arrives.
2.  **WAL Append**: The change is encoded and appended to a sequential log
    segment file.
3.  **Position Tracking**: Every transaction receives a strictly increasing log
    position. Later chapters call this position an LSN, or Log Sequence Number.
4.  **Fast LMDB Update**: The change is applied to the LMDB B+tree in a
    non-durable mode. At this point, durability comes from the WAL record rather
    than from forcing the LMDB file itself to storage.
5.  **Durability Sync**: Depending on the chosen durability profile, Datalevin
    decides when the WAL record must be forced to storage.
6.  **Acknowledgment**: The application receives success once the selected
    durability condition has been met.
7.  **Recovery**: On restart, Datalevin scans the WAL segments and replays any
    committed transactions that weren't yet fully persisted in the LMDB file.

**Note**: Bulk load operations (like `init-db` and `fill-db`) bypass the WAL for
maximum performance and will not appear in the transaction log.

### 7.2 Replication and Operations

WAL mode has durability profiles that decide when appended log records are
forced to storage. Those choices are operational policy, not storage
fundamentals. Chapter 20 covers `:strict`, `:relaxed`, `:extra`, group commit,
LSN watermarks, snapshots, and WAL garbage collection.

WAL mode also introduces operational APIs such as `create-snapshot!`,
`gc-txlog-segments!`, `txlog-watermarks`, and `open-tx-log`. Those functions
matter for long-running services, replication, and low-level change capture, but
they are not storage fundamentals. Chapter 19 discusses batching and ingestion,
Chapter 20 covers storage tuning and durability choices, and Chapter 22 covers
deployment and production operations.

---

## Summary

Datalevin's storage layer focuses on pragmatic engineering:

- It uses **LMDB** for stable embedded storage and zero-copy reads.
- It uses **B+Trees** to ensure predictable read performance for complex Datalog
  queries.
- It adds **DLMDB** extensions like order statistics to power the query planner.
- It provides a **WAL mode** to scale write performance without sacrificing the
  B+Tree model.

By understanding these fundamentals, you can better tune your schema and queries
for maximum performance.

## References

[1] Howard Chu, ["LMDB"](https://www.youtube.com/watch?v=tEa5sAh-kVk), *The
Databaseology Lectures*, Carnegie Mellon University, Fall 2015, YouTube video.

[2] Chris Okasaki, [*Purely Functional Data
Structures*](https://doi.org/10.1017/CBO9780511530104), Cambridge University
Press, 1998.

[3] Rudolf Bayer and Edward M. McCreight, ["Organization and Maintenance of
Large Ordered Indexes"](https://doi.org/10.1007/BF00288683), *Acta
Informatica* 1, 173-189, 1972.
