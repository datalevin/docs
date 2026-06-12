---
title: "Durability, Database Maintenance, and Storage Tuning"
chapter: 20
part: "V — Performance and Operations"
---

# Chapter 20: Durability, Database Maintenance, and Storage Tuning

Performance and durability are the two sides of the same operational problem:
the database must be fast, bounded, recoverable, and predictable under failure.
This chapter starts with durability, backup, and maintenance practices, then
covers memory and storage tuning.

---

## 1. Durability, Snapshots, and Maintenance

Performance is useless if your data is not safe. Datalevin's storage engine
(LMDB/DLMDB) is famous for its **reliability** and **immediate recovery** in the
face of system crashes.

This section covers how Datalevin ensures durability, how to perform online
snapshots, and how to build a robust backup strategy for your production
environment.

---

### 1.1 Durability: The Power of Copy-on-Write (CoW)

Traditional databases such as Postgres use a Write-Ahead Log (WAL) with an
update-in-place storage strategy. If the system crashes before dirty pages are
checkpointed, the database recovers by replaying the log before normal service
resumes.

Datalevin's storage engine (LMDB) uses a **Copy-on-Write (CoW) B+Tree**.

1.  **Never Overwrite**: When you write a new datom, LMDB doesn't modify
    existing pages. Instead, it creates a *new version* of the affected pages.
2.  **Atomic Commits**: Once all the new pages are written and synced to disk,
    the "Root" of the B+Tree is updated in a single, atomic operation.
3.  **Instant Recovery**: If the power fails during a write, the root pointer is
    never updated. When you restart the database, it simply points to the last
    known-good state. **There is no "recovery process" needed because the
    database is never in an inconsistent state on disk.**

---

### 1.2 Syncing to Disk: `msync` and Durability

In standard mode (the default for KV stores), the speed of your writes is
primarily limited by the speed of your disk's **synchronous flush (`msync`)**.

- **Safe by Default**: On every transaction commit, Datalevin tells the OS to
  flush the Page Cache to disk. This ensures that even if the OS crashes, your
  data is safe.
- **Hardware Impact**: On high-speed NVMe drives, this flush is very fast. On
  older magnetic drives or cloud-based block storage (like AWS EBS), it can be a
  major bottleneck.

---

### 1.3 Non-Durable Environment Flags

Chapter 19 shows the concrete LMDB environment flags for repeatable imports and
cache rebuilds. From an operations perspective, the rule is simple: every flag
that reduces commit-time flushing changes the crash boundary. `:nometasync` is
the least aggressive option because database integrity is retained, though the
last transaction may be lost. `:nosync` and `:writemap`/`:mapasync` trade much
more safety for speed and can leave the database corrupted after an untimely
system crash.

Use these modes only when the data can be rebuilt from a durable source or when
the application has an explicit recovery plan. For raw KV stores, call the KV
`sync` operation at explicit checkpoints if you need to force a flush. For the
full flag table, setup examples, and ingestion trade-offs, see Chapter 19.

---

### 1.4 WAL-Based Durability: Performance + Safety

While standard LMDB is extremely safe, it can be limited by disk I/O.
Datalevin's **Write-Ahead Log (WAL) mode** is an explicit opt-in for local
embedded Datalog and KV stores, is required on the primary for non-HA async read
replicas, and is forced on for consensus-lease HA. Use WAL when you need higher
write throughput from concurrent callers, WAL replay, replication, or HA
behavior.

#### 1.4.1 The LSN Lifecycle

In WAL mode, every transaction is assigned a **Log Sequence Number (LSN)**. This
number is the canonical source of truth for the database's progress.

- **`:last-committed-lsn`**: The latest transaction that has been successfully
  committed in the database.
- **`:last-durable-lsn`**: The latest transaction that has been safely synced to
  the physical WAL file on disk.
- **`:last-applied-lsn`**: The latest transaction known to be applied to the
  LMDB state or covered by recovery marker state.

By monitoring these watermarks with `txlog-watermarks`, you can precisely track
the "lag" between application commits and physical disk durability.

#### 1.4.2 Durability Profiles

- **`:strict`**: The database waits for the WAL to be synced to disk for *every*
  transaction using a standard `fsync`. This is the default for consensus-lease
  HA.
- **`:relaxed`**: Transactions are batched before syncing. This is significantly
  faster but risks losing the last few milliseconds of work in a crash. This is
  the default for local embedded WAL opt-in when no profile is specified.
- **`:extra`**: Uses even stricter durability guarantees (e.g.,
  `fcntl(F_FULLSYNC)` on macOS) to protect against hardware write-cache
  failures.

On startup, a WAL-enabled store compares the LSN applied to the LMDB file with
the durable LSN in the log. If the log is ahead, Datalevin validates newer log
records and replays them into the LMDB environment before opening the database
for normal reads and writes.

---

### 1.5 Maintenance in WAL Mode

WAL mode introduces snapshot and retention maintenance. Datalevin can run
snapshot creation from its built-in scheduler when the WAL-enabled store is
opened with `:snapshot-scheduler? true`; otherwise, `create-snapshot!` remains
available for explicit operator-controlled snapshots.

#### 1.5.1 Snapshots and Checkpoints

When the snapshot scheduler is enabled, Datalevin creates snapshots
automatically according to the configured interval and size/LSN thresholds.
Calling **`create-snapshot!`** manually is for an on-demand checkpoint or for
deployments that choose to run their own scheduler.

Snapshot creation forces the WAL and LMDB state to disk, copies the LMDB
environment into the `current` snapshot slot, rotates the previous `current`
snapshot into the `previous` slot, and advances snapshot-floor bookkeeping used
by WAL retention.

```clojure
(def kv
  (d/open-kv "/data/app-kv"
             {:wal? true
              :snapshot-scheduler? true}))

(d/txlog-watermarks kv)
(d/list-snapshots kv)

;; Optional: force an on-demand snapshot now.
(d/create-snapshot! kv)
```

The JSON API also exposes `txlog-watermarks`, `create-snapshot!`,
`list-snapshots`, and `gc-txlog-segments!`; those operations can target either a
KV handle or a Datalog connection handle. The high-level Java, Python, and
JavaScript wrappers do not currently expose dedicated convenience methods for
these WAL maintenance calls.

#### 1.5.2 Garbage Collection

Use **`gc-txlog-segments!`** to reclaim disk space by deleting old WAL segments
that are no longer needed by snapshots, replicas, vector checkpoints, or
operator retention pins. Datalevin respects the `:wal-retention-bytes` and
`:wal-retention-ms` policies, but deletion happens when this cleanup operation
runs.

```clojure
;; Reclaim disk space
(d/gc-txlog-segments! kv)

;; Keep WAL records from LSN 5000 onward
(d/gc-txlog-segments! kv 5000)
```

---

### 1.6 Online Snapshots: `d/copy`

In other databases, backing up a live database can be tricky. If you simply copy
the file while a write is happening, the copy might be corrupted.

Datalevin's **`d/copy`** function creates a consistent snapshot of a Datalog
database value or a KV handle. It can run while the database is actively used,
so readers can continue accessing the source while the copy is being made. In
the Java, Python, and JavaScript bindings, use the KV-backed copy operation.

<div class="multi-lang">

```clojure
;; Copy a live Datalog database from a connection
(d/copy @conn "/path/to/backup-db")

;; Copy a raw KV store
(d/copy kv "/path/to/backup-kv")
```

```java
import datalevin.*;
import java.util.Map;

try (KV kv = Datalevin.openKV("/data/app-kv")) {
    kv.exec("copy", Map.of("dest", "/path/to/backup-kv"));
}
```

```python
from datalevin import exec_json

kv = exec_json("open-kv", {"dir": "/data/app-kv"})
try:
    exec_json("copy", {"kv": kv, "dest": "/path/to/backup-kv"})
finally:
    exec_json("release-handle", {"handle": kv})
```

```javascript
import { execJson } from "datalevin-node";

const kv = await execJson("open-kv", { dir: "/data/app-kv" });
try {
  await execJson("copy", { kv, dest: "/path/to/backup-kv" });
} finally {
  await execJson("release-handle", { handle: kv });
}
```

```console
$ dtlv -d /data/companydb copy /backup/companydb-2024-01-15
```

</div>

- **Live Backups**: You can run `copy` while the database is being actively
  queried and written to.
- **Consistency**: The copy represents a single, transactionally consistent
  point in time.
- **Zero-Impact**: Because of LMDB's MVCC architecture, the copy doesn't block
  writers or other readers.

---

### 1.7 Database Maintenance: Copy, Dump, and Load

Datalevin provides comprehensive command-line and API tools for database
maintenance, backup, and migration.

#### 1.7.1 Compacting with `d/copy`

LMDB's copy functionality can create a compacted copy of the database. This
reclaims free pages left by deleted data in the destination copy and improves
B+Tree locality.

```clojure
;; Copy and compact a Datalog database
(d/copy @conn "/path/to/compact-backup-db" true)

;; Copy and compact a raw KV store
(d/copy kv "/path/to/compact-backup-kv" true)
```

The copy operation can run **regardless of whether the database is currently in
use**; readers can continue accessing the source while the copy is being made.

#### 1.7.2 The `dtlv` Command Line Tool

The `dtlv` CLI tool provides interactive and batch database operations:

```console
# Interactive REPL
$ dtlv

# Backup with compaction
$ dtlv -d /data/companydb -c copy /backup/companydb-2024-01-15

# Dump database to file
$ dtlv -d /data/companydb -g -f ~/dump.edn dump

# Load dump into new database
$ dtlv -d /data/newdb -f ~/dump.edn -g load

# View database statistics
$ dtlv -d /data/companydb stat

# List sub-databases
$ dtlv -d /data/companydb -l dump
```

Key options:
- `-c, --compact`: Compact while copying
- `-g, --datalog`: Dump/load as Datalog database
- `-n, --nippy`: Use Nippy binary format for faster serialization

#### 1.7.3 Dump and Load Formats

Datalevin supports multiple dump/load formats:

- **EDN (default)**: Human-readable, version-independent text format
- **Nippy**: Binary format for faster serialization of large databases

```console
# Binary dump/load (faster)
$ dtlv -d /data/companydb -g -n -f ~/backup.nippy dump
$ dtlv -d /data/newdb -g -n -f ~/backup.nippy load
```

---

### 1.8 Re-Indexing

Datalevin provides a `re-index` function that dumps and reloads data to rebuild
indexes. This can be useful in recovery scenarios, when changing index options,
or when index structures need to be rebuilt. It is only safe when no other
thread or process is accessing the same database.

```clojure
;; Re-index a Datalog connection with existing schema/options
(def reindexed-conn (d/re-index conn {}))

;; Re-index with an updated schema and options
(def reindexed-conn
  (d/re-index conn new-schema {:backup? true}))
```

---

### 1.9 Database Upgrades

When upgrading Datalevin to a new minor version, you may need to migrate your
database. Datalevin provides automatic migration for databases newer than
version 0.9.27.

#### 1.9.1 Automatic Migration

For databases created with Datalevin 0.9.27 or later, opening with a newer
version triggers automatic migration. This process downloads the old version's
uberjar to dump the data, then loads it with the new version. Automatic
migration detects Datalog stores by their `datalevin/eav` DBI; if the same file
also contains extra KV DBIs, dump and load those manually.

#### 1.9.2 Manual Migration
For older databases, use the command line tool:

```console
# 1. Backup and compact with old version
$ dtlv-0.4 -d /src/dir -c copy /backup/dir

# 2. Dump with old version
$ dtlv-0.4 -d /src/dir -g -f dump.edn dump

# 3. Load with new version
$ dtlv -d /dest/dir -f dump.edn -g load
```

---

### 1.10 Summary: The Operations Checklist

To ensure your data is safe and recoverable, follow this checklist:

1.  **Choose the right write mode**: Use default LMDB commits for simple local
    durability; enable WAL when you need WAL throughput, replay, replication, or
    HA.
2.  **Use WAL snapshots**: Enable `:snapshot-scheduler? true`, or run your own
    scheduled `create-snapshot!`, so WAL/LMDB state is checkpointed and snapshot
    slots are rotated.
3.  **Run `gc-txlog-segments!`**: Reclaim disk space regularly.
4.  **Monitor disk space**: LMDB and WAL need enough free space to perform their
    operations.
5.  **Automate `d/copy` or `dtlv copy`**: Run a daily or hourly snapshot and
    move the result to an off-site location (e.g., AWS S3).
6.  **Test your restores**: Regularly practice restoring from a snapshot or WAL
    log to a fresh server.
7.  **Use NVMe for speed**: The durability of your database is directly tied to
    the IOPS and latency of your disk.
8.  **Compact periodically**: Use `d/copy` with `true` as the third argument, or
    `dtlv -c copy`, to reclaim disk space in a destination copy after large
    deletions.
9.  **Plan upgrades**: When upgrading Datalevin versions, use dump/load for
    databases older than 0.9.27.

By leveraging Datalevin's rock-solid CoW architecture, you can sleep soundly
knowing your data is safe from crashes and easy to recover from disasters.

---

## 2. Memory Layout and Storage Tuning

Datalevin is a "zero-copy" database, which means its memory management is very
different from standard JVM-based applications. While many databases fight the
JVM Garbage Collector (GC), Datalevin works *with* the operating system's native
memory management.

This section covers the best practices for tuning Datalevin's memory and storage
parameters for maximum performance and stability.

---

### 2.1 The LMDB Map: `:mapsize`

The most critical parameter for Datalevin is **`:mapsize`**. This is the
OS allocated size for the memory mapped database file, which defines the
maximum size the file can grow to in the address space.

#### 2.1.1 Virtual Memory vs. Resident Memory

Unlike a traditional Java heap, the `:mapsize` is a **virtual memory**
reservation. Setting a 1TB mapsize does *not* mean the database will consume 1TB
of RAM. It simply means the OS will reserve a 1TB "address space" for the
database file.

- **Recommendation**: Set `:mapsize` to be significantly larger than your
  expected data size (e.g., if you expect 50GB of data, set it to 256GB).
- **Default**: The default initial mapsize is 1000 MiB and grows
  automatically. You should almost always set a larger value upfront for
  production use.

<div class="multi-lang">

```clojure
;; Set mapsize to 128 GiB (in MiB)
(d/get-conn path schema {:kv-opts {:mapsize 131072}})
```

```java
// Set mapsize to 128 GiB (in MiB)
Connection conn = Datalevin.getConn(path, schema,
    Map.of("kv-opts", Map.of("mapsize", 131072)));
```

```python
# Set mapsize to 128 GiB (in MiB)
conn = connect(path, schema=schema,
               opts={":kv-opts": {":mapsize": 131072}})
```

```javascript
// Set mapsize to 128 GiB (in MiB)
const conn = await connect(path, {
  schema,
  opts: { ":kv-opts": { ":mapsize": 131072 } }
});
```

</div>

> **Note**: Datalevin will automatically grow the mapsize if it runs out of
> space, but this is an expensive operation that causes a significant
> performance spike. Setting an appropriately large mapsize upfront avoids this
> overhead.

---

### 2.2 Leveraging the OS Page Cache

Because Datalevin uses **memory-mapped files (`mmap`)**, it does not manage its
own buffer pool. Instead, it relies on the **Operating System Page Cache**.

- **Zero-Copy Reads**: When you perform a read, the OS handles fetching the
  required pages from disk into the Page Cache. Datalevin then returns a pointer
  directly into that cache.
- **GC Independence**: Because the database data lives in the Page Cache
  (outside the JVM heap), your queries do not trigger JVM garbage collection.
  This allows Datalevin to handle datasets much larger than your JVM heap size
  with sub-millisecond latency.

> **Tuning Tip**: For best performance, ensure your server has enough free RAM
> to hold your **active working set** (the most frequently queried data) in the
> OS Page Cache.

---

### 2.3 Reader Threads and Locking: `:max-readers`

LMDB uses a "lock-free" reader model, but it still needs to track active readers
to prevent writers from overwriting pages that are still being read.

- **`:max-readers`**: This parameter (default: 1024) defines how many concurrent
  reader slots can access the database.
- **When to Increase**: If your application uses highly concurrent web servers
  or bounded worker pools above the default reader count, increase this before
  deployment.

Datalevin also tracks cached reader transactions and the owning thread so reader
slots can be released when threads disappear. This reduces the common LMDB
failure mode where abandoned thread pools exhaust reader slots. Virtual-thread
handling is hardened by disabling thread-local read reuse for short-lived
virtual threads.

<div class="multi-lang">

```clojure
(d/get-conn path schema {:kv-opts {:max-readers 512}})
```

```java
Connection conn = Datalevin.getConn(path, schema,
    Map.of("kv-opts", Map.of("max-readers", 512)));
```

```python
conn = connect(path, schema=schema,
               opts={":kv-opts": {":max-readers": 512}})
```

```javascript
const conn = await connect(path, {
  schema,
  opts: { ":kv-opts": { ":max-readers": 512 } }
});
```

</div>

---

### 2.4 Storage Efficiency: Prefix Compression

Datalevin's storage engine (DLMDB) uses **prefix compression** to minimize the
on-disk footprint of your indexes.

Prefix compression is most effective when a large portion of neighboring encoded
keys is identical. Some KV workloads with long structured keys can therefore see
large savings. Datalevin's Datalog indexes also benefit, but the effect is more
modest than the largest KV cases because the shared part is usually a portion of
the encoded 8-byte entity ID in EAV and a portion of the encoded 4-byte
attribute ID in AVE.
The `DUPSORT` nesting described in Chapter 15 is the main reason Datalog indexes
avoid repeating full entity IDs or full `(A, V)` prefixes.

- **Reduces Storage Size**: Workload dependent; high reductions are possible
  when key prefixes are substantial, while Datalog index savings are usually
  smaller because their common prefixes are compact encoded IDs.
- **Improves Cache Locality**: More keys fit into a single CPU cache line,
  making index scans significantly faster.

DLMDB is a Datalevin-specific LMDB fork. In addition to page-level prefix
compression, it maintains subtree counts that let Datalevin count ranges without
materializing them. Those counts feed both direct index APIs, such as
`count-datoms`, and the query optimizer's cardinality estimates.

---

### 2.5 Summary: The Tuning Checklist

When deploying Datalevin to production, follow this checklist:

1.  **Set a large `:mapsize`**: Reserve enough virtual address space for your
    future growth.
2.  **Monitor Page Cache usage**: Ensure your server has enough RAM to keep your
    working set in memory.
3.  **Adjust `:max-readers`**: If you have a high-concurrency application,
    increase the reader limit.
4.  **Account for disk growth**: Leave room for LMDB free pages, compacted
    copies, snapshots, and WAL retention if WAL mode is enabled.

By tuning these parameters, you ensure that Datalevin's zero-copy architecture
remains fast, stable, and efficient across any dataset size.
