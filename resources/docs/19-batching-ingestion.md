---
title: "Batching, Sorting, and High-Throughput Ingestion"
chapter: 19
part: "V — Performance and Operations"
---

# Chapter 19: Batching, Sorting, and High-Throughput Ingestion

## 1. High-Throughput Ingestion: The Infrastructure

Ingesting large datasets requires a different strategy than standard interactive
writes or OLTP. Datalevin provides several infrastructure features to support
extreme throughput.

### 1.1 WAL Mode for Ingestion

For high-throughput ingestion where data must remain queryable as it arrives,
enable **WAL mode**. WAL turns write pressure into sequential log appends while
preserving LMDB's B+Tree read path.

- **Benefit**: Sequential WAL appends are much faster than random B+Tree
  flushes.
- **Durability policy**: Choose the WAL durability profile using the operational
  guidance in Chapter 20, Section 1.4.2.

### 1.2 Asynchronous Transactions: `d/transact-async`

In embedded Clojure, the `d/transact-async` function automatically batches
multiple transactions together. When combined with WAL mode, this provides the
highest possible OLTP throughput.

```clojure
;; Fire multiple async transactions
(d/transact-async conn batch-1)
(d/transact-async conn batch-2)
(d/transact-async conn batch-3)

;; Block on the last one to ensure all are committed
(deref (d/transact-async conn batch-4))
```

The batching is **adaptive**: the higher the write load, the bigger the batch
size. This combines with manual batching for compound performance gains.

An asynchronous transaction returns a future that requires some programmatic
management work.

### 1.3 Sync + Async Combo

For embedded Clojure applications that need both good throughput and
deterministic commit points, use a sequence of async transactions followed by a
sync call:

```clojure
;; Async writes for throughput
(d/transact-async conn batch-1)
(d/transact-async conn batch-2)
(d/transact-async conn batch-3)

;; Sync commit to ensure all are persisted
(d/transact! conn [])
```

Since async transactions are still committed in order, the last realized future
indicates all prior calls are already committed.

> **Note**: In the default direct LMDB commit path, writes are still coordinated
> by the single-writer B+Tree transaction model. WAL mode changes the throughput
> profile: concurrent writer callers can benefit from sequential WAL append and
> group commit, though scaling is still sub-linear. For most ingestion jobs,
> combine manual batches with `transact-async` and measure before adding writer
> threads.

### 1.4 Non-Durable LMDB Flags for Repeatable Imports

WAL mode and async transactions are the first tools to try when data must remain
durable. For one-time imports, cache rebuilds, temporary KV stores, and other
repeatable jobs, LMDB also exposes environment flags that reduce disk-sync work
at commit time. These flags can apply to both Datalog connections and raw KV
stores because both ultimately write through LMDB.

| Flags | What changes | Use when | Risk |
|-------|--------------|----------|------|
| `:nometasync` | Sync data pages but not meta pages on commit | You want a modest speedup while preserving database integrity after a crash | The last transaction may be lost |
| `:nosync` | Skip `msync` on commit and leave flushing to the OS | The import is rebuildable and write speed matters more than crash recovery | A system crash may corrupt the database |
| `:writemap` + `:mapasync` | Use a writable memory map with asynchronous flushing | Maximum raw write speed for rebuildable data | A crash may corrupt the database; buggy native code can overwrite mapped DB memory; some OSes preallocate the full map size |

The Datalevin write benchmark tests these flags with both `transact!` and
`transact-async`, and with both `transact-kv` and `transact-kv-async`. In the KV
pure-write benchmark, `:nosync` and `:writemap`/`:mapasync` move throughput from
the durable baseline into the hundreds of thousands to over a million writes per
second depending on batch size. The same benchmark also shows that KV batching
has a stronger effect than Datalog batching, so do not treat environment flags as
a substitute for large batches.

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])
(require '[datalevin.constants :as c])

;; Raw KV store for a rebuildable import.
(def kv
  (d/open-kv "/tmp/import-kv"
             {:flags (-> c/default-env-flags
                         (conj :writemap)
                         (conj :mapasync))}))

;; Datalog connection using the same LMDB flags.
(def conn
  (d/get-conn "/tmp/import-dl" schema
              {:kv-opts {:flags (conj c/default-env-flags :nometasync)}}))
```

```java
import datalevin.*;
import java.util.List;
import java.util.Map;

// Raw KV store for a rebuildable import.
KV kv = Datalevin.openKV("/tmp/import-kv",
    Map.of("flags", List.of(":writemap", ":mapasync")));

// Datalog connection using the same LMDB flags.
Connection conn = Datalevin.getConn("/tmp/import-dl", schema,
    Map.of("kv-opts", Map.of("flags", List.of(":nometasync"))));
```

```python
from datalevin import connect, open_kv

# Raw KV store for a rebuildable import.
kv = open_kv("/tmp/import-kv",
    opts={":flags": [":writemap", ":mapasync"]})

# Datalog connection using the same LMDB flags.
conn = connect("/tmp/import-dl", schema=schema,
    opts={":kv-opts": {":flags": [":nometasync"]}},
    shared=True)
```

```javascript
import { connect, openKv } from "datalevin-node";

// Raw KV store for a rebuildable import.
const kv = await openKv("/tmp/import-kv",
  { ":flags": [":writemap", ":mapasync"] });

// Datalog connection using the same LMDB flags.
const conn = await connect("/tmp/import-dl", {
  schema,
  opts: { ":kv-opts": { ":flags": [":nometasync"] } },
  shared: true
});
```

</div>

In the Java, Python, and JavaScript examples, the flag values use
colon-prefixed strings because these option values are Datalevin keywords.

If you use `:nosync` or `:writemap`/`:mapasync`, write the import so it can be
replayed from a durable source. For raw KV imports, call the KV `sync` operation
(`d/sync` in Clojure) at explicit checkpoints or before handing the database to
normal durable workloads. For online systems with irreplaceable writes, prefer
WAL mode with async transactions and tune durability there instead.

---

## 2. Sorting Before Ingestion

A B+Tree performs best when keys are inserted in **sorted order**. If you insert
random Entity IDs and random Values, the engine must "jump around" the B+Tree,
leading to frequent cache misses and page splits.

### 2.1 Pre-Sorting Your Data

If you are performing a bulk import (e.g., from a CSV or a SQL dump), sort your
datoms *before* they reach the database.

1.  **Group by Entity ID**: This ensures that all attributes for a single entity
    are written to the EAV index contiguously.
2.  **Group by Attribute and Value**: This ensures that updates to the AVE index
    are also localized.

<div class="multi-lang">

```clojure
;; Sort data by Entity ID
(let [sorted-datoms (sort-by :db/id raw-data)]
  (doseq [batch (partition-all 5000 sorted-datoms)]
    (d/transact! conn batch)))
```

```java
// Sort data by Entity ID
rawData.sort(Comparator.comparing(m -> (Long) m.get("db/id")));
List<List<Map>> batches = partition(rawData, 5000);
for (List<Map> batch : batches) {
    conn.transact(batch);
}
```

```python
# Sort data by Entity ID
sorted_datoms = sorted(raw_data, key=lambda x: x[":db/id"])
for batch in partition_all(sorted_datoms, 5000):
    conn.transact(batch)
```

```javascript
// Sort data by Entity ID
const sortedDatoms = rawData.sort((a, b) => a[":db/id"] - b[":db/id"]);
for (const batch of partitionAll(sortedDatoms, 5000)) {
  await conn.transact(batch);
}
```

</div>

---

## 3. Bulk Load: `d/init-db` and `d/fill-db`

For the fastest possible bulk load into a database, bypass the transaction
overhead entirely using `d/init-db` and `d/fill-db`. Note that these functions
**bypass the WAL** and will not appear in the transaction log.

- **`d/init-db`**: Load a collection of prepared datoms into a fresh, empty
  database
- **`d/fill-db`**: Bulk load additional datoms into an existing database

The public high-level Java, Python, and JavaScript bindings do not currently
expose direct wrappers for `init-db` or `fill-db`. From those languages, use the
batched transaction pattern above unless your deployment exposes a lower-level
bulk-load bridge. The example below is Clojure-only; a non-Clojure `init-db`
snippet here would be an API sketch, not a current public binding.

```clojure
;; Prepare datoms with approximate entity IDs (caller's responsibility)
(def prepared-datoms
  [[1 :user/name "Alice"]
   [1 :user/age 30]
   [2 :user/name "Bob"]
   [2 :user/age 25]])

;; Load into empty database
(def db (d/init-db prepared-datoms schema))
```

> **Note**: These functions skip data integrity checks and temporary entity ID resolution. You must ensure the datoms are correct before calling them.

---

## 4. Summary: The Ingestion Checklist

When you need to ingest data at scale, follow this checklist:

1.  **Use WAL mode when OLTP data must stay queryable**: Choose the durability
    profile using Chapter 20's operational guidance.
2.  **Use async transactions**: `d/transact-async` provides adaptive batching
    for orders-of-magnitude throughput improvement.
3.  **Combine with manual batching**: The effects of auto-batching and manual
    batching compound.
4.  **Sort your data**: Sort by Entity ID before transacting to minimize B+Tree
    fragmentation.
5.  **Use Clojure or pod-level `init-db`/`fill-db`** for the fastest possible
    bulk load into a database for static data sets.
6.  **Use non-durable LMDB flags only for rebuildable imports**: `:nometasync`,
    `:nosync`, and `:writemap`/`:mapasync` can improve write speed, but they
    change durability (i.e. crash behavior).

For the full durability discussion and maintenance implications, see Chapter
20.

By following these patterns, you can achieve extreme write throughput with
Datalevin.
