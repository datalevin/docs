---
title: "Durability and Maintenance"
chapter: 19
part: "V — Performance and Operations"
---

# Chapter 19: Durability and Maintenance

Performance and durability are the two sides of the same operational problem:
the database must be fast, bounded, recoverable, and predictable under failure.
This chapter starts with durability, backup, and maintenance practices, then
covers memory and storage tuning.


## 1. Durability, Snapshots, and Maintenance

Performance is useless if your data is not safe. Datalevin's storage engine
(LMDB/DLMDB) is famous for its **reliability** and **immediate recovery** in the
face of system crashes.

This section covers how Datalevin ensures durability, how to perform online
snapshots, and how to build a robust backup strategy for your production
environment.


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


### 1.2 Syncing to Disk: `msync` and Durability

In standard mode (the default for KV stores), the speed of your writes is
primarily limited by the speed of your disk's **synchronous flush (`msync`)**.

- **Safe by Default**: On every transaction commit, Datalevin tells the OS to
  flush the Page Cache to disk. This ensures that even if the OS crashes, your
  data is safe.
- **Hardware Impact**: On high-speed NVMe drives, this flush is very fast. On
  older magnetic drives or cloud-based block storage (like AWS EBS), it can be a
  major bottleneck.


### 1.3 Non-Durable Environment Flags

Chapter 20 shows the concrete LMDB environment flags for repeatable imports and
cache rebuilds. From an operations perspective, the rule is simple: every flag
that reduces commit-time flushing changes the crash boundary. `:nometasync` is
the least aggressive option because database integrity is retained, though the
last transaction may be lost. `:nosync` and `:writemap`/`:mapasync` trade much
more safety for speed and can leave the database corrupted after an untimely
system crash.

Use these modes only when the data can be rebuilt from a durable source or when
the application has an explicit recovery plan. For raw KV stores, call the KV
`sync` operation at explicit checkpoints if you need to force a flush. For the
full flag table, setup examples, and ingestion trade-offs, see Chapter 20.


### 1.4 WAL-Based Durability: Performance + Safety

While standard LMDB is extremely safe, it can be limited by disk I/O.
Datalevin's **Write-Ahead Log (WAL) mode** is an explicit opt-in for local
embedded Datalog and KV stores, is required on the primary for non-HA async read
replicas, and is forced on for consensus-lease HA. Use WAL when you need higher
write throughput from concurrent callers, WAL replay, replication, or HA
behavior.

#### 1.4.1 The LSN Lifecycle

In WAL mode, every transaction is assigned a **Log Sequence Number (LSN)**. LSNs
are positive, strictly increasing commit positions in the WAL stream. They are
the unit Datalevin uses for durability tracking, recovery replay, snapshots,
replication, and WAL garbage collection.

Figure 19.1 should be read from left to right. A transaction first becomes
**committed** when Datalevin accepts it into the WAL commit stream and assigns
an LSN. It becomes **durable** when the WAL record containing that LSN has
reached the durability boundary selected by the current profile. It becomes
**applied** when Datalevin knows the corresponding state is present in the LMDB
file or covered by recovery-marker state. Later, `create-snapshot!` records a
checkpoint that covers applied state, and `gc-txlog-segments!` can reclaim WAL
segments that are older than all retention floors.

![The WAL LSN lifecycle: a transaction advances from committed to durable to applied (the three watermarks read with txlog-watermarks), then is checkpointed by create-snapshot! and finally reclaimed by gc-txlog-segments!](/images/diagrams/wal-lsn-lifecycle.svg)

Each watermark answers a different operational question. Has Datalevin accepted
the transaction into the WAL stream? Has the WAL record reached storage under
the selected durability policy? Has the LMDB file or recovery-marker state
caught up to that record? Under `:relaxed` group commit, a transaction can be
committed before it is durable. A durable transaction can also be ahead of the
applied marker until checkpoint work catches up. This is normal operational lag,
not logical inconsistency: committed data is visible according to the
transaction semantics of the open database, durable data survives the crash
boundary, and unapplied durable WAL can be replayed during recovery.

- **`:last-committed-lsn`**: The latest transaction that has been successfully
  committed in the database.
- **`:last-durable-lsn`**: The latest transaction that has been safely synced to
  the physical WAL file on disk.
- **`:last-applied-lsn`**: The latest transaction known to be applied to the
  LMDB state or covered by recovery marker state.

By monitoring these watermarks with `txlog-watermarks`, you can precisely track
the "lag" between application commits and physical disk durability.

In Clojure, call `txlog-watermarks` on the local KV handle. For a Datalog
connection, use `datalog-kv` to get the backing KV handle:

<div class="multi-lang">

```clojure
(def conn
  (d/get-conn "/data/app-db" schema {:wal? true}))

(d/transact! conn [{:db/id -1 :event/name "started"}])

(-> (d/txlog-watermarks (d/datalog-kv conn))
    (select-keys [:wal?
                  :durability-profile
                  :last-committed-lsn
                  :last-durable-lsn
                  :last-applied-lsn]))
;; => {:wal? true
;;     :durability-profile :relaxed
;;     :last-committed-lsn 1
;;     :last-durable-lsn 1
;;     :last-applied-lsn 1}
```

```java
try (Connection conn = Datalevin.getConn(
        "/data/app-db",
        schema,
        Map.of(":wal?", true))) {
    conn.transact(List.of(
        Map.of(":db/id", -1L, ":event/name", "started")));

    Map<?, ?> watermarks = conn.txLogWatermarks();
    // Inspect :last-committed-lsn, :last-durable-lsn, and :last-applied-lsn.
}
```

```python
with connect("/data/app-db",
             schema=schema,
             opts={":wal?": True}) as conn:
    conn.transact([{":db/id": -1, ":event/name": "started"}])

    watermarks = conn.tx_log_watermarks()
    lsn_view = {
        key: watermarks[key]
        for key in [
            ":last-committed-lsn",
            ":last-durable-lsn",
            ":last-applied-lsn",
        ]
    }
```

```javascript
const conn = await connect("/data/app-db", {
  schema,
  opts: { ":wal?": true }
});

try {
  await conn.transact([
    { ":db/id": -1, ":event/name": "started" }
  ]);

  const watermarks = await conn.txLogWatermarks();
  const lsnView = {
    committed: watermarks[":last-committed-lsn"] ?? watermarks.lastCommittedLsn,
    durable: watermarks[":last-durable-lsn"] ?? watermarks.lastDurableLsn,
    applied: watermarks[":last-applied-lsn"] ?? watermarks.lastAppliedLsn
  };
} finally {
  await conn.close();
}
```

</div>

If `:last-committed-lsn` is ahead of `:last-durable-lsn`, the WAL has accepted
transactions that have not yet reached the selected durability boundary. If
`:last-durable-lsn` is ahead of `:last-applied-lsn`, durable WAL records exist
that are not yet covered by the LMDB applied marker or current snapshot state.
Under `:strict`, the committed-to-durable gap is usually small or zero in steady
state. Under `:relaxed`, short committed-to-durable lag is expected during group
commit. The durable-to-applied gap is controlled by checkpoint and snapshot
progress rather than only by the durability profile.

#### 1.4.2 Durability Profiles

WAL mode durability is specified with a database open-time option called
durability profile.

- **`:strict`**: The database waits for the WAL to be synced to disk for *every*
  transaction using a standard `fsync`. This is the default for consensus-lease
  HA.
- **`:relaxed`**: Transactions are batched before syncing. This is significantly
  faster but risks losing the last few milliseconds of work in a crash. This is
  the default for local embedded WAL opt-in when no profile is specified.
- **`:extra`**: Uses even stricter durability guarantees (e.g.,
  `fcntl(F_FULLSYNC)` on macOS) to protect against hardware write-cache
  failures.

Specify the profile in the same open-time options map that enables WAL. The
following opens a local Datalog database with strict WAL durability:

<div class="multi-lang">

```clojure
(def conn
  (d/get-conn "/data/app-db"
              schema
              {:wal? true
               :wal-durability-profile :strict}))
```

```java
try (Connection conn = Datalevin.getConn(
        "/data/app-db",
        schema,
        Map.of(":wal?", true,
               ":wal-durability-profile", ":strict"))) {
    // Use conn here.
}
```

```python
with connect("/data/app-db",
             schema=schema,
             opts={":wal?": True,
                   ":wal-durability-profile": ":strict"}) as conn:
    # Use conn here.
    pass
```

```javascript
const conn = await connect("/data/app-db", {
  schema,
  opts: {
    ":wal?": true,
    ":wal-durability-profile": ":strict"
  }
});

try {
  // Use conn here.
} finally {
  await conn.close();
}
```

</div>

Use the same option keys with `open-kv` / `openKV` / `open_kv` / `openKv` for
raw KV stores. Change the profile value to `:relaxed` when the workload accepts
a short crash-loss window for higher throughput, or to `:extra` when the
platform-specific stronger flush is required.

On startup, a WAL-enabled store compares the LSN applied to the LMDB file with
the durable LSN in the log. If the log is ahead, Datalevin validates newer log
records and replays them into the LMDB environment before opening the database
for normal reads and writes.


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

The Java, Python, and JavaScript bindings expose the same WAL maintenance
helpers directly on local KV handles and Datalog connections:

<div class="multi-lang">

```java
import datalevin.*;
import java.util.Map;

try (KV kv = Datalevin.openKV("/data/app-kv",
         Map.of(":wal?", true, ":snapshot-scheduler?", true))) {
    kv.txLogWatermarks();
    kv.listSnapshots();

    // Optional: force an on-demand snapshot now.
    kv.createSnapshot();
}
```

```python
from datalevin import open_kv

with open_kv("/data/app-kv",
             opts={":wal?": True, ":snapshot-scheduler?": True}) as kv:
    kv.tx_log_watermarks()
    kv.list_snapshots()

    # Optional: force an on-demand snapshot now.
    kv.create_snapshot()
```

```javascript
import { openKv } from "datalevin-node";

const kv = await openKv("/data/app-kv", {
  ":wal?": true,
  ":snapshot-scheduler?": true
});

try {
  await kv.txLogWatermarks();
  await kv.listSnapshots();

  // Optional: force an on-demand snapshot now.
  await kv.createSnapshot();
} finally {
  await kv.close();
}
```

</div>

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

In Java, Python, and JavaScript, use `gcTxLogSegments`,
`gc_tx_log_segments`, and `gcTxLogSegments` on the local KV or connection
handle. The optional retain-floor LSN is passed as the method argument in Java
and Python, or as `{ retainFloorLsn: 5000 }` in JavaScript.


### 1.6 Online Backup Copies: `d/copy`

In other databases, backing up a live database can be tricky. If you simply copy
the file while a write is happening, the copy might be corrupted.

Datalevin's **`d/copy`** function creates a transactionally consistent backup
copy from a Datalog connection or DB object, or from a KV handle. This is the
ordinary online backup path: use it when you want a recoverable point-in-time
copy of the current database without changing the source. It can run while the
database is active. Readers can continue using the source, and writers do not
have to stop for the copy to be consistent.

This section is about the default, non-compacting copy. It preserves the
database content and layout closely enough for fast backup creation. If the goal
is to reclaim free pages after large deletes or to rewrite the destination into
a smaller file, use the compacting form in Section 1.7.1.

The Java, Python, and JavaScript bindings expose `copy` directly on both
connection and KV handles.

<div class="multi-lang">

```clojure
;; Copy a live Datalog database from a connection
(d/copy (d/db conn) "/path/to/backup-db")

;; Copy a raw KV store
(d/copy kv "/path/to/backup-kv")
```

```java
import datalevin.*;

try (Connection conn = Datalevin.getConn("/data/app-db", schema)) {
    conn.copy("/path/to/backup-db");
}

try (KV kv = Datalevin.openKV("/data/app-kv")) {
    kv.copy("/path/to/backup-kv");
}
```

```python
from datalevin import connect, open_kv

with connect("/data/app-db", schema=schema) as conn:
    conn.copy("/path/to/backup-db")

with open_kv("/data/app-kv") as kv:
    kv.copy("/path/to/backup-kv")
```

```javascript
import { connect, openKv } from "datalevin-node";

const conn = await connect("/data/app-db", { schema });
try {
  await conn.copy("/path/to/backup-db");
} finally {
  await conn.close();
}

const kv = await openKv("/data/app-kv");
try {
  await kv.copy("/path/to/backup-kv");
} finally {
  await kv.close();
}
```

```console
$ dtlv -d /data/companydb copy /backup/companydb-2024-01-15
```

</div>

- **Backup purpose**: Use ordinary `copy` for routine online backup when the
  destination does not need compaction.
- **Consistency**: The copy represents a single transactionally consistent point
  in time.
- **Operational impact**: LMDB's MVCC architecture lets the copy run while the
  source remains available. A long-running copy still consumes I/O and can keep
  older pages live until it finishes, so schedule large copies deliberately.


### 1.7 Database Maintenance: Copy, Dump, and Load

Datalevin provides comprehensive command-line and API tools for database
maintenance, backup, and migration.

#### 1.7.1 Compacting with `d/copy`

The compacting form of `copy` uses the same consistency guarantees as Section
1.6, but it serves a different maintenance purpose. LMDB reuses deleted pages
inside the source file; it does not shrink the file in place. A compacting copy
rewrites live pages into a new destination, leaving free pages behind. Use it
after large deletes, after major data reshaping, or before archiving a smaller
backup artifact.

```clojure
;; Copy and compact a Datalog database
(d/copy (d/db conn) "/path/to/compact-backup-db" true)

;; Copy and compact a raw KV store
(d/copy kv "/path/to/compact-backup-kv" true)
```

In Java and Python, pass `true` / `True` as the compact argument to `copy`. In
JavaScript, pass `{ compact: true }`.

Compaction is not an in-place operation. It creates a new compacted destination.
If you want the compacted copy to replace the production database, treat that as
a controlled maintenance step: stop writers, close the old connection, move or
restore the compacted directory into place, and reopen.

#### 1.7.2 The `dtlv` Command-Line Tool

The `dtlv` CLI tool is the operator entry point for maintenance tasks. Use it
when a backup, compaction, dump/load, or inspection task belongs in a shell
script or runbook rather than in application code. The command-specific help is
usually the best reference while operating a system:

```console
$ dtlv help
$ dtlv help copy
$ dtlv help dump
$ dtlv help load

# View database statistics
$ dtlv -d /data/companydb stat
```

The examples above this section showed `dtlv copy` for backup and compaction.
The next section covers `dtlv dump` and `dtlv load`.

#### 1.7.3 Dump and Load Formats

Dump/load is a logical export/import path. Use it when you need a portable data
file, a version-independent migration path, or a way to move data into a new
database directory. For routine same-version backups, `copy` is usually simpler
and faster.

Datalevin supports two dump/load encodings:

- **EDN (default)**: Human-readable, version-independent text.
- **Nippy**: Binary serialization for faster dump/load of large databases.

For a Datalog database, pass `-g` / `--datalog`:

```console
# EDN Datalog dump/load
$ dtlv -d /data/companydb -g -f ~/company.edn dump
$ dtlv -d /data/newdb -g -f ~/company.edn load

# Nippy Datalog dump/load
$ dtlv -d /data/companydb -g -n -f ~/company.nippy dump
$ dtlv -d /data/newdb -g -n -f ~/company.nippy load
```

For a named KV sub-database, omit `-g` and pass the DBI name to `dump` or
`load`:

```console
# EDN dump/load for a named KV DBI
$ dtlv -d /data/companydb -f ~/sales.edn dump sales
$ dtlv -d /data/newdb -f ~/sales.edn load sales
```

Use `-n` / `--nippy` when speed and file size matter more than human
inspectability. Keep EDN when the dump needs to be reviewed, transformed, or
stored as a long-lived interchange artifact.


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

The embedded Java, Python, and JavaScript bindings expose the same operation as
`conn.reIndex(...)`, `conn.re_index(...)`, and `conn.reIndex(...)`. KV stores
and standalone search engines expose corresponding `reIndex` / `re_index`
methods. Client/server users should schedule re-indexing as an operator task on
the host that owns the local database files.


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

For older databases, use the command-line tool:

```console
# 1. Backup and compact with old version
$ dtlv-0.4 -d /src/dir -c copy /backup/dir

# 2. Dump with old version
$ dtlv-0.4 -d /src/dir -g -f dump.edn dump

# 3. Load with new version
$ dtlv -d /dest/dir -f dump.edn -g load
```


### 1.10 Operations Checklist

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


## 2. Memory Layout and Storage Tuning

Datalevin is a "zero-copy" database, which means its memory management is very
different from standard JVM-based applications. While many databases fight the
JVM Garbage Collector (GC), Datalevin works *with* the operating system's native
memory management.

This section covers the best practices for tuning Datalevin's memory and storage
parameters for maximum performance and stability.

### 2.1 The LMDB Map: `:mapsize`

The most critical parameter for Datalevin is **`:mapsize`**. This is the
OS-allocated size for the memory-mapped database file, which defines the
maximum size the file can grow to in the address space.

#### 2.1.1 Virtual Memory vs. Resident Memory

Unlike a traditional Java heap, the `:mapsize` is a **virtual memory**
reservation, as shown in Figure 19.2. Setting a 1TB mapsize does *not* mean the
database will consume 1TB of RAM. It simply means the OS will reserve a 1TB
"address space" for the database file.

![mapsize, address space, and the page cache as three nested scales: :mapsize reserves a large virtual address space (not disk, not RAM, safe to set generously); the database file grows on disk up to that mapsize (pre-size to avoid a costly auto-resize); and only the hot working set is resident in RAM via the OS page cache (zero-copy reads, outside the JVM heap). Each layer is a subset of the one above](/images/diagrams/mapsize-address-space.svg)

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
> overhead. Chapter 22 explains how this internal resize path differs from
> application-level transaction failures such as validation, lookup-ref, unique,
> and CAS errors.


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

#### 2.2.1 Monitoring Page Cache Usage

There is no Datalevin buffer-pool hit ratio to watch, because Datalevin does
not own the buffer pool. The useful signals come from the operating system:
whether the LMDB data file is resident, whether queries cause major page faults,
whether the machine is swapping, and whether reads are blocked on storage.

On Linux, start with the host-level view:

```console
# Overall memory. Watch "available" and "buff/cache".
$ free -h

# Swap and I/O pressure. Sustained si/so or high wa is a warning sign.
$ vmstat 1
```

Then look at the Datalevin process:

```console
# VSZ can be large because of :mapsize. RSS is resident memory.
$ ps -o pid,vsz,rss,maj_flt,comm -p "$DATALEVIN_PID"

# Per-process page faults. Sustained majflt/s under normal queries means the
# active working set is not staying resident in RAM.
$ pidstat -r -p "$DATALEVIN_PID" 1
```

If the Linux `fincore` tool is available, inspect the LMDB data file directly:

```console
# Show how much of the mapped data file is currently in the page cache.
$ fincore /data/companydb/data.mdb
```

The interpretation is more important than any single number. A large virtual
size is expected when `:mapsize` is large, and by itself is not a problem. A
healthy steady-state read workload should show enough `available` memory, little
or no swap activity, low sustained `majflt/s`, and low disk read pressure after
the working set has warmed. If major faults and disk reads remain high during
ordinary repeated queries, the hot working set is larger than available RAM or
other processes are evicting Datalevin's file-backed pages.

On macOS, the built-in tools are less direct, but the same logic applies:

```console
$ vm_stat 1
$ memory_pressure
```

Watch page-ins, swap activity, and memory pressure while running representative
queries. For an exact per-file residency check on either Linux or macOS, use a
tool such as `vmtouch` when it is available:

```console
$ vmtouch /data/companydb/data.mdb
```


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
virtual threads, the lightweight JVM threads.

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


### 2.4 Storage Efficiency: Prefix Compression

Datalevin's storage engine (DLMDB) uses **prefix compression** to minimize the
on-disk footprint of your indexes.

Prefix compression is most effective when a large portion of neighboring encoded
keys is identical. Some KV workloads with long structured keys can therefore see
large savings. Datalevin's Datalog indexes also benefit, but the effect is more
modest than the largest KV cases because the shared part is usually a portion of
the encoded 8-byte entity id in EAV and a portion of the encoded 4-byte
attribute id in AVE. The `DUPSORT` nesting described in Chapter 15 is the main
reason Datalog indexes avoid repeating full entity ids or full `(A, V)`
prefixes.

- **Reduces Storage Size**: Workload dependent; high reductions are possible
  when key prefixes are substantial, while Datalog index savings are usually
  smaller because their common prefixes are compact encoded ids.
- **Improves Cache Locality**: More keys fit into a single CPU cache line,
  making index scans significantly faster.

DLMDB is a Datalevin-specific LMDB fork. In addition to page-level prefix
compression, it maintains subtree counts that let Datalevin count ranges without
materializing them. Those counts feed both direct index APIs, such as
`count-datoms`, and the query optimizer's cardinality estimates.


## Summary: The Tuning Checklist

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
