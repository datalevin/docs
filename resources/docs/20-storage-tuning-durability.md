---
title: "Storage Tuning, Durability, and Database Maintenance"
chapter: 20
part: "V — Performance and Operations"
---

# Chapter 20: Storage Tuning, Durability, and Database Maintenance

Performance and durability are the same operational problem: the database must be fast, bounded, recoverable, and predictable under failure. This chapter combines memory and storage tuning with durability, backup, and maintenance practices.

---

## 1. Memory Layout and Storage Tuning

Datalevin is a "zero-copy" database, which means its memory management is very different from standard JVM-based applications. While many databases fight the JVM Garbage Collector (GC), Datalevin works *with* the operating system's native memory management.

This section covers the best practices for tuning Datalevin's memory and storage parameters for maximum performance and stability.

---

### 1. The LMDB Map: `:mapsize`

The most critical parameter for Datalevin is **`:mapsize`**. This defines the maximum size the database file can grow to in your address space.

#### 1.1 Virtual Memory vs. Resident Memory
Unlike a traditional Java heap, the `:mapsize` is a **virtual memory** reservation. Setting a 1TB mapsize does *not* mean the database will consume 1TB of RAM. It simply means the OS will reserve a 1TB "address space" for the database file.

- **Recommendation**: Set `:mapsize` to be significantly larger than your expected data size (e.g., if you expect 50GB of data, set it to 256GB).
- **Default**: The default mapsize is often small (e.g., 10GB). You should almost always increase this for production use.

<div class="multi-lang">

```clojure
;; Set mapsize to 128GB (in bytes)
(d/get-conn path schema {:mapsize (* 128 1024 1024 1024)})
```

```java
// Set mapsize to 128GB (in bytes)
Connection conn = Datalevin.getConn(path, schema,
    Map.of("mapsize", 128L * 1024 * 1024 * 1024));
```

```python
# Set mapsize to 128GB (in bytes)
conn = d.get_conn(path, schema, {"mapsize": 128 * 1024 * 1024 * 1024})
```

```javascript
// Set mapsize to 128GB (in bytes)
const conn = d.getConn(path, schema, { mapsize: 128 * 1024 * 1024 * 1024 });
```

</div>

> **Note**: Datalevin will automatically grow the mapsize if it runs out of space, but this is an expensive operation that causes a significant performance spike. Setting an appropriately large mapsize upfront avoids this overhead.

---

### 2. Leveraging the OS Page Cache

Because Datalevin uses **memory-mapped files (`mmap`)**, it does not manage its own buffer pool. Instead, it relies on the **Operating System Page Cache**.

- **Zero-Copy Reads**: When you perform a read, the OS handles fetching the required pages from disk into the Page Cache. Datalevin then returns a pointer directly into that cache.
- **GC Independence**: Because the database data lives in the Page Cache (outside the JVM heap), your queries do not trigger JVM garbage collection. This allows Datalevin to handle datasets much larger than your JVM heap size with sub-millisecond latency.

> **Tuning Tip**: For best performance, ensure your server has enough free RAM to hold your **active working set** (the most frequently queried data) in the OS Page Cache.

---

### 3. Reader Threads and Locking: `:max-readers`

LMDB uses a "lock-free" reader model, but it still needs to track active readers to prevent writers from overwriting pages that are still being read.

- **`:max-readers`**: This parameter (default: 1024) defines how many concurrent reader slots can access the database.
- **When to Increase**: If your application uses highly concurrent web servers or bounded worker pools above the default reader count, increase this before deployment.

Datalevin also tracks cached reader transactions and the owning thread so reader slots can be released when threads disappear. This reduces the common LMDB failure mode where abandoned thread pools exhaust reader slots. Virtual-thread handling is hardened by disabling thread-local read reuse for short-lived virtual threads.

<div class="multi-lang">

```clojure
(d/get-conn path schema {:max-readers 512})
```

```java
Connection conn = Datalevin.getConn(path, schema,
    Map.of("max-readers", 512));
```

```python
conn = d.get_conn(path, schema, {"max_readers": 512})
```

```javascript
const conn = d.getConn(path, schema, { maxReaders: 512 });
```

</div>

---

### 4. Storage Efficiency: Prefix Compression

Datalevin's storage engine (DLMDB) uses **prefix compression** to minimize the on-disk footprint of your indexes.

In a Datalog AVE index, many keys share the same Attribute and Value (e.g., thousands of users in the same city). DLMDB only stores the "difference" between consecutive keys, which:
- **Reduces Storage Size**: Often by 30-50% compared to uncompressed B+Trees.
- **Improves Cache Locality**: More keys fit into a single CPU cache line, making index scans significantly faster.

DLMDB is a Datalevin-specific LMDB fork. In addition to page-level prefix
compression, it maintains subtree counts that let Datalevin count ranges without
materializing them. Those counts feed both direct index APIs, such as
`count-datoms`, and the query optimizer's cardinality estimates.

---

### 5. Tuning WAL Mode

When WAL mode is enabled, several additional parameters can be tuned to balance performance and safety:

- **`:wal-durability-profile`**: `:strict` (standard `fsync`), `:relaxed` (batched syncs), or `:extra` (e.g., `F_FULLSYNC` on macOS).
- **`:wal-group-commit`**: Max writes per durability batch in `:relaxed` mode (default: 128).
- **`:wal-group-commit-ms`**: Max milliseconds between batches in `:relaxed` mode (default: 10ms).
- **Retention**: Control disk space with `:wal-retention-bytes` (default: 8GiB) and `:wal-retention-ms` (default: 7 days).

---

### 6. Server and Client Tuning

When using Datalevin in Client-Server mode, additional tuning parameters are available to optimize network performance, connection pooling, and protocol compression.

Key server and client-side knobs include:
- **`--idle-timeout`**: Reclaim server resources from inactive sessions.
- **Connection Pooling**: Control `:pool-size` and `:time-out` on the client.
- **Wire Compression**: Adjust `*wire-compression-threshold*` and `*wire-compression-level*` for large payloads.
- **Freshness Control**: Tune `*remote-db-last-modified-check-interval-ms*` to balance latency and data consistency.

Refer to **Chapter 23: Client-Server, Security, Deployment, and Production Operations** for a detailed guide on these parameters.

---

### 7. Summary: The Tuning Checklist

When deploying Datalevin to production, follow this checklist:

1.  **Set a large `:mapsize`**: Reserve enough virtual address space for your future growth.
2.  **Monitor Page Cache usage**: Ensure your server has enough RAM to keep your working set in memory.
3.  **Adjust `:max-readers`**: If you have a high-concurrency application, increase the reader limit.
4.  **Configure WAL profile**: Use `:relaxed` for high-throughput write workloads if a small risk of data loss is acceptable.
5.  **Monitor LSN Lag**: Use `txlog-watermarks` to ensure durability isn't lagging significantly behind commits.

By tuning these parameters, you ensure that Datalevin's zero-copy architecture remains fast, stable, and efficient across any dataset size.

---

## 2. Durability, Snapshots, and Maintenance

Performance is useless if your data is not safe. Datalevin's storage engine (LMDB/DLMDB) is famous for its **reliability** and **immediate recovery** in the face of system crashes.

This section covers how Datalevin ensures durability, how to perform online snapshots, and how to build a robust backup strategy for your production environment.

---

### 1. Durability: The Power of Copy-on-Write (CoW)

Traditional databases (like Postgres) use a Write-Ahead Log (WAL) and an "update-in-place" strategy. If the system crashes mid-write, the database may be corrupted and must "replay" the log to recover.

Datalevin's storage engine (LMDB) uses a **Copy-on-Write (CoW) B+Tree**.

1.  **Never Overwrite**: When you write a new datom, LMDB doesn't modify existing pages. Instead, it creates a *new version* of the affected pages.
2.  **Atomic Commits**: Once all the new pages are written and synced to disk, the "Root" of the B+Tree is updated in a single, atomic operation.
3.  **Instant Recovery**: If the power fails during a write, the root pointer is never updated. When you restart the database, it simply points to the last known-good state. **There is no "recovery process" needed because the database is never in an inconsistent state on disk.**

---

### 2. Syncing to Disk: `msync` and Durability

In standard mode (the default for KV stores), the speed of your writes is primarily limited by the speed of your disk's **synchronous flush (`msync`)**.

- **Safe by Default**: On every transaction commit, Datalevin tells the OS to flush the Page Cache to disk. This ensures that even if the OS crashes, your data is safe.
- **Hardware Impact**: On high-speed NVMe drives, this flush is very fast. On older magnetic drives or cloud-based block storage (like AWS EBS), it can be a major bottleneck.

---

### 3. Non-Durable Environment Flags

Datalevin supports faster, albeit less durable write modes by passing environment flags when opening the database. These trade safety for speed and are useful for temporary bulk imports where you can re-run the import if needed.

| Flag | Speedup | Implication |
|------|---------|-------------|
| `:nometasync` | up to 5X | Last transaction may be lost at crash, but DB integrity retained |
| `:nosync` | up to 20X | OS syncs data; crash may corrupt DB |
| `:writemap` + `:mapasync` | up to 25X | Crash may corrupt DB; OS preallocates disk to map size |

#### 3.1 Setting Flags

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])
(require '[datalevin.constants :as c])

;; Using :nosync for temporary bulk imports
(def conn (d/get-conn path schema {:kv-opts {:flags (conj c/default-env-flags :nosync)}}))

;; Using :writemap + :mapasync for maximum speed
(def conn (d/get-conn path schema {:kv-opts {:flags (-> c/default-env-flags
                                                         (conj :writemap)
                                                         (conj :mapasync))}}))
```

```java
import datalevin.core.*;

// Using NOSYNC for temporary bulk imports
Connection conn = Datalevin.getConn(path, schema,
    Map.of("kv-opts", Map.of("flags", List.of("nosync"))));

// Using WRITEMAP + MAPASYNC for maximum speed
Connection conn = Datalevin.getConn(path, schema,
    Map.of("kv-opts", Map.of("flags", List.of("writemap", "mapasync"))));
```

```python
# Using nosync for temporary bulk imports
conn = d.get_conn(path, schema,
    {"kv_opts": {"flags": ["nosync"]}})

# Using writemap + mapasync for maximum speed
conn = d.get_conn(path, schema,
    {"kv_opts": {"flags": ["writemap", "mapasync"]}})
```

```javascript
// Using nosync for temporary bulk imports
const conn = d.getConn(path, schema,
  { kvOpts: { flags: ['nosync'] } });

// Using writemap + mapasync for maximum speed
const conn2 = d.getConn(path, schema,
  { kvOpts: { flags: ['writemap', 'mapasync'] } });
```

</div>

You can also toggle flags dynamically:

<div class="multi-lang">

```clojure
(d/set-env-flags kv-db [:nosync] false)
```

```java
Datalevin.setEnvFlags(kvDb, List.of("nosync"), false);
```

```python
d.set_env_flags(kv_db, ["nosync"], False)
```

```javascript
d.setEnvFlags(kvDb, ['nosync'], false);
```

</div>

> **Warning**: These flags risk data loss or DB corruption on system crash. Only use for temporary bulk imports. Call `d/sync` manually to force flushes at safe points.

---

### 4. WAL-Based Durability: Performance + Safety

While standard LMDB is extremely safe, it can be limited by disk I/O. Datalevin's **Write-Ahead Log (WAL) mode** is an explicit opt-in for local embedded Datalog and KV stores, is required on the primary for non-HA async read replicas, and is forced on for consensus-lease HA. Use WAL when you need higher write throughput from concurrent callers, WAL replay, replication, or HA behavior.

#### 4.1 The LSN Lifecycle
In WAL mode, every transaction is assigned a **Log Sequence Number (LSN)**. This number is the canonical source of truth for the database's progress.

- **`:last-committed-lsn`**: The latest transaction that has been successfully committed in the database.
- **`:last-durable-lsn`**: The latest transaction that has been safely synced to the physical WAL file on disk.

By monitoring these watermarks with `txlog-watermarks`, you can precisely track the "lag" between application commits and physical disk durability.

#### 4.2 Durability Profiles
- **`:strict`**: The database waits for the WAL to be synced to disk for *every* transaction using a standard `fsync`. This is the default for consensus-lease HA.
- **`:relaxed`**: Transactions are batched before syncing. This is significantly faster but risks losing the last few milliseconds of work in a crash. This is the default for local embedded WAL opt-in when no profile is specified.
- **`:extra`**: Uses even stricter durability guarantees (e.g., `fcntl(F_FULLSYNC)` on macOS) to protect against hardware write-cache failures.

On startup, a WAL-enabled store compares the LSN applied to the LMDB file with
the durable LSN in the log. If the log is ahead, Datalevin validates newer log
records and replays them into the LMDB environment before opening the database
for normal reads and writes.

---

### 5. Maintenance in WAL Mode

WAL mode introduces two critical maintenance operations that should be performed periodically (e.g., via a background thread or a cron job).

#### 5.1 Snapshots and Checkpoints
Call **`create-snapshot!`** periodically. This function performs several vital tasks:
1.  **Flush**: It flushes all pending changes from the memory-mapped buffers to the main LMDB database file.
2.  **Rotate**: It rotates the current WAL segment, starting a fresh log.
3.  **Advance Floor**: It advances the "floor" for WAL retention, signaling that earlier log segments are no longer needed for basic recovery.

<div class="multi-lang">

```clojure
;; In a background loop for a WAL-enabled KV handle
(d/create-snapshot! kv)
```

```java
// In a background loop for a WAL-enabled KV handle
Datalevin.createSnapshot(kv);
```

```python
# In a background loop for a WAL-enabled KV handle
d.create_snapshot(kv)
```

```javascript
// In a background loop for a WAL-enabled KV handle
d.createSnapshot(kv);
```

</div>

#### 5.2 Garbage Collection
Use **`gc-txlog-segments!`** to reclaim disk space by deleting old WAL segments. Datalevin respects the `:wal-retention-bytes` and `:wal-retention-ms` policies but requires an explicit call to perform the cleanup.

<div class="multi-lang">

```clojure
;; Reclaim disk space
(d/gc-txlog-segments! kv)
```

```java
// Reclaim disk space
Datalevin.gcTxlogSegments(kv);
```

```python
# Reclaim disk space
d.gc_txlog_segments(kv)
```

```javascript
// Reclaim disk space
d.gcTxlogSegments(kv);
```

</div>

---

### 6. Online Snapshots: `d/copy`

Backing up a live database can be tricky. If you simply copy the file while a write is happening, the copy might be corrupted.

Datalevin's **`d/copy`** function creates a consistent snapshot of the database. It can run while the database is actively used—readers can continue accessing the source while the copy is being made.

<div class="multi-lang">

```clojure
;; Create a snapshot of the live database
(d/copy conn "/path/to/backup-db")
```

```java
// Create a snapshot of the live database
Datalevin.copy(conn, "/path/to/backup-db");
```

```python
# Create a snapshot of the live database
d.copy(conn, "/path/to/backup-db")
```

```javascript
// Create a snapshot of the live database
d.copy(conn, '/path/to/backup-db');
```

</div>

- **Live Backups**: You can run `copy` while the database is being actively queried and written to.
- **Consistency**: The copy represents a single, transactionally consistent point in time.
- **Zero-Impact**: Because of LMDB's MVCC architecture, the copy doesn't block writers or other readers.

---

### 7. Database Maintenance: Copy, Dump, and Load

Datalevin provides comprehensive command-line and API tools for database maintenance, backup, and migration.

#### 7.1 Compacting with `d/copy`

LMDB's copy functionality creates a compacted copy of the database. This reclaims all free space from deleted data and optimizes the B+Tree for maximum read speed.

<div class="multi-lang">

```clojure
;; Copy and compact the database
(d/copy conn "/path/to/backup-db" {:compact? true})
```

```java
// Copy and compact the database
Datalevin.copy(conn, "/path/to/backup-db", Map.of("compact", true));
```

```python
# Copy and compact the database
d.copy(conn, "/path/to/backup-db", {"compact": True})
```

```javascript
// Copy and compact the database
d.copy(conn, '/path/to/backup-db', { compact: true });
```

</div>

The copy operation can run **regardless of whether the database is currently in use**—readers can continue accessing the source while the copy is being made.

#### 7.2 The `dtlv` Command Line Tool

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

#### 7.3 Dump and Load Formats

Datalevin supports multiple dump/load formats:

- **EDN (default)**: Human-readable, version-independent text format
- **Nippy**: Binary format for faster serialization of large databases

```console
# Binary dump/load (faster)
$ dtlv -d /data/companydb -n -f ~/backup.nippy dump
$ dtlv -d /data/newdb -n -f ~/backup.nippy load
```

---

### 8. Re-Indexing

Datalevin provides a `re-index` function that rebuilds the in-memory index structures from the on-disk data. This can be useful in recovery scenarios or when index structures become stale.

<div class="multi-lang">

```clojure
;; Rebuild indexes
(def reindexed-db (d/re-index db))
```

```java
// Rebuild indexes
Database reindexedDb = Datalevin.reIndex(db);
```

```python
# Rebuild indexes
reindexed_db = d.re_index(db)
```

```javascript
// Rebuild indexes
const reindexedDb = d.reIndex(db);
```

</div>

---

### 9. Database Upgrades

When upgrading Datalevin to a new minor version, you may need to migrate your database. Datalevin provides automatic migration for databases newer than version 0.9.27.

#### 9.1 Automatic Migration
For databases created with Datalevin 0.9.27 or later, opening with a newer version triggers automatic migration. This process downloads the old version's uberjar to dump the data, then loads it with the new version.

#### 9.2 Manual Migration
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

### 10. Summary: The Operations Checklist

To ensure your data is safe and recoverable, follow this checklist:

1.  **Choose the right write mode**: Use default LMDB commits for simple local durability; enable WAL when you need WAL throughput, replay, replication, or HA.
2.  **Automate `create-snapshot!`**: Set up a background task to flush the DB and rotate logs.
3.  **Run `gc-txlog-segments!`**: Reclaim disk space regularly.
4.  **Monitor disk space**: LMDB and WAL need enough free space to perform their operations.
5.  **Automate `d/copy`**: Run a daily or hourly snapshot and move the result to an off-site location (e.g., AWS S3).
6.  **Test your restores**: Regularly practice restoring from a snapshot or WAL log to a fresh server.
7.  **Use NVMe for speed**: The durability of your database is directly tied to the IOPS and latency of your disk.
8.  **Compact periodically**: Use `d/copy` with `{:compact? true}` to reclaim disk space after large deletions.
9.  **Plan upgrades**: When upgrading Datalevin versions, use dump/load for databases older than 0.9.27.

By leveraging Datalevin's rock-solid CoW architecture, you can sleep soundly knowing your data is safe from crashes and easy to recover from disasters.
