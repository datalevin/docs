---
title: "Durability, Backups, and Database Maintenance"
chapter: 26
part: "VI — Systems and Operations"
---

# Chapter 26: Durability, Backups, and Database Maintenance

Performance is useless if your data is not safe. Datalevin's storage engine (LMDB/DLMDB) is famous for its **reliability** and **immediate recovery** in the face of system crashes.

This chapter covers how Datalevin ensures durability, how to perform online snapshots, and how to build a robust backup strategy for your production environment.

---

## 1. Durability: The Power of Copy-on-Write (CoW)

Traditional databases (like Postgres) use a Write-Ahead Log (WAL) and an "update-in-place" strategy. If the system crashes mid-write, the database may be corrupted and must "replay" the log to recover.

Datalevin's storage engine (LMDB) uses a **Copy-on-Write (CoW) B+Tree**.

1.  **Never Overwrite**: When you write a new datom, LMDB doesn't modify existing pages. Instead, it creates a *new version* of the affected pages.
2.  **Atomic Commits**: Once all the new pages are written and synced to disk, the "Root" of the B+Tree is updated in a single, atomic operation.
3.  **Instant Recovery**: If the power fails during a write, the root pointer is never updated. When you restart the database, it simply points to the last known-good state. **There is no "recovery process" needed because the database is never in an inconsistent state on disk.**

---

## 2. Syncing to Disk: `msync` and Durability

In standard mode (the default for KV stores), the speed of your writes is primarily limited by the speed of your disk's **synchronous flush (`msync`)**.

- **Safe by Default**: On every transaction commit, Datalevin tells the OS to flush the Page Cache to disk. This ensures that even if the OS crashes, your data is safe.
- **Hardware Impact**: On high-speed NVMe drives, this flush is very fast. On older magnetic drives or cloud-based block storage (like AWS EBS), it can be a major bottleneck.

---

## 3. Non-Durable Environment Flags

Datalevin supports faster, albeit less durable write modes by passing environment flags when opening the database. These trade safety for speed and are useful for temporary bulk imports where you can re-run the import if needed.

| Flag | Speedup | Implication |
|------|---------|-------------|
| `:nometasync` | up to 5X | Last transaction may be lost at crash, but DB integrity retained |
| `:nosync` | up to 20X | OS syncs data; crash may corrupt DB |
| `:writemap` + `:mapasync` | up to 25X | Crash may corrupt DB; OS preallocates disk to map size |

### 3.1 Setting Flags

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

## 4. WAL-Based Durability: Performance + Safety

While standard LMDB is extremely safe, it can be limited by disk I/O. Datalevin's **Write-Ahead Log (WAL) mode** is an explicit opt-in for local embedded Datalog and KV stores, and is forced on for consensus-lease HA. Use WAL when you need higher write throughput from concurrent callers, WAL replay, replication, or HA behavior.

### 4.1 The LSN Lifecycle
In WAL mode, every transaction is assigned a **Log Sequence Number (LSN)**. This number is the canonical source of truth for the database's progress.

- **`:last-committed-lsn`**: The latest transaction that has been successfully committed in the database.
- **`:last-durable-lsn`**: The latest transaction that has been safely synced to the physical WAL file on disk.

By monitoring these watermarks with `txlog-watermarks`, you can precisely track the "lag" between application commits and physical disk durability.

### 4.2 Durability Profiles
- **`:strict`**: The database waits for the WAL to be synced to disk for *every* transaction using a standard `fsync`. This is the default for consensus-lease HA.
- **`:relaxed`**: Transactions are batched before syncing. This is significantly faster but risks losing the last few milliseconds of work in a crash. This is the default for local embedded WAL opt-in when no profile is specified.
- **`:extra`**: Uses even stricter durability guarantees (e.g., `fcntl(F_FULLSYNC)` on macOS) to protect against hardware write-cache failures.

---

## 5. Maintenance in WAL Mode

WAL mode introduces two critical maintenance operations that should be performed periodically (e.g., via a background thread or a cron job).

### 5.1 Snapshots and Checkpoints
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

### 5.2 Garbage Collection
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

## 6. Online Snapshots: `d/copy`

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
- **Zero-Impact**: Because of LMDB's MVCC architecture (Chapter 27), the copy doesn't block writers or other readers.

---

## 7. Database Maintenance: Copy, Dump, and Load

Datalevin provides comprehensive command-line and API tools for database maintenance, backup, and migration.

### 7.1 Compacting with `d/copy`

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

### 7.2 The `dtlv` Command Line Tool

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

### 7.3 Dump and Load Formats

Datalevin supports multiple dump/load formats:

- **EDN (default)**: Human-readable, version-independent text format
- **Nippy**: Binary format for faster serialization of large databases

```console
# Binary dump/load (faster)
$ dtlv -d /data/companydb -n -f ~/backup.nippy dump
$ dtlv -d /data/newdb -n -f ~/backup.nippy load
```

---

## 8. Re-Indexing

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

## 9. Database Upgrades

When upgrading Datalevin to a new minor version, you may need to migrate your database. Datalevin provides automatic migration for databases newer than version 0.9.27.

### 9.1 Automatic Migration
For databases created with Datalevin 0.9.27 or later, opening with a newer version triggers automatic migration. This process downloads the old version's uberjar to dump the data, then loads it with the new version.

### 9.2 Manual Migration
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

## 10. Summary: The Operations Checklist

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
