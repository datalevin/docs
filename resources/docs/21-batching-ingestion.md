---
title: "Batching, Sorting, and High-Throughput Ingestion"
chapter: 21
part: "V — Performance and Dataflow"
---

## 1. High-Throughput Ingestion: The Infrastructure

Ingesting large datasets requires a different strategy than standard interactive writes. Datalevin provides several infrastructure features to support extreme throughput.

### 1.1 WAL Mode with `:relaxed` Durability
For the best balance of safety and speed during ingestion, enable **WAL mode** with the **`:relaxed` durability profile**. This allows Datalevin to batch disk syncs, achieving the write throughput of an LSM-tree while maintaining the read performance of a B+Tree.

- **Benefit**: Sequential WAL appends are much faster than random B+Tree flushes.
- **Tuning**: Adjust `:wal-group-commit` and `:wal-group-commit-ms` to optimize batching for your hardware.

### 1.2 Asynchronous Transactions: `d/transact-async!`
The `d/transact-async!` function automatically batches multiple transactions together. When combined with WAL mode, this provides the highest possible OLTP throughput.

<div class="multi-lang">

```clojure
;; Fire multiple async transactions
(d/transact-async! conn batch-1)
(d/transact-async! conn batch-2)
(d/transact-async! conn batch-3)

;; Block on the last one to ensure all are committed
(deref (d/transact-async! conn batch-4))
```

```java
// Fire multiple async transactions
Datalevin.transactAsync(conn, batch1);
Datalevin.transactAsync(conn, batch2);
Datalevin.transactAsync(conn, batch3);

// Block on the last one to ensure all are committed
Datalevin.transactAsync(conn, batch4).get();
```

```python
# Fire multiple async transactions
d.transact_async(conn, batch_1)
d.transact_async(conn, batch_2)
d.transact_async(conn, batch_3)

# Block on the last one to ensure all are committed
d.transact_async(conn, batch_4).result()
```

```javascript
// Fire multiple async transactions
d.transactAsync(conn, batch1);
d.transactAsync(conn, batch2);
d.transactAsync(conn, batch3);

// Block on the last one to ensure all are committed
await d.transactAsync(conn, batch4);
```

</div>

The batching is **adaptive**: the higher the write load, the bigger the batch size. This combines with manual batching for compound performance gains.

### 1.2 Sync + Async Combo

For applications that need both good throughput and deterministic commit points, use a sequence of async transactions followed by a sync call:

<div class="multi-lang">

```clojure
;; Async writes for throughput
(d/transact-async! conn batch-1)
(d/transact-async! conn batch-2)
(d/transact-async! conn batch-3)

;; Sync commit to ensure all are persisted
(d/transact! conn [])
```

```java
// Async writes for throughput
Datalevin.transactAsync(conn, batch1);
Datalevin.transactAsync(conn, batch2);
Datalevin.transactAsync(conn, batch3);

// Sync commit to ensure all are persisted
Datalevin.transact(conn, List.of());
```

```python
# Async writes for throughput
d.transact_async(conn, batch_1)
d.transact_async(conn, batch_2)
d.transact_async(conn, batch_3)

# Sync commit to ensure all are persisted
d.transact(conn, [])
```

```javascript
// Async writes for throughput
d.transactAsync(conn, batch1);
d.transactAsync(conn, batch2);
d.transactAsync(conn, batch3);

// Sync commit to ensure all are persisted
d.transact(conn, []);
```

</div>

Since async transactions are still committed in order, the last realized future indicates all prior calls are already committed.

> **Note**: Datalevin supports only a single write thread at a time. Parallel transactions actually slow writes down due to mutex contention and thread switching overhead. Stick to sequential async calls.

---

## 2. Sorting Before Ingestion

A B+Tree performs best when keys are inserted in **sorted order**. If you insert random Entity IDs and random Values, the engine must "jump around" the B+Tree, leading to frequent cache misses and page splits.

### 2.1 Pre-Sorting Your Data
If you are performing a bulk import (e.g., from a CSV or a SQL dump), sort your datoms *before* they reach the database.

1.  **Group by Entity ID**: This ensures that all attributes for a single entity are written to the EAV index contiguously.
2.  **Group by Attribute and Value**: This ensures that updates to the AVE index are also localized.

<div class="multi-lang">

```clojure
;; Sort data by Entity, then Attribute
(let [sorted-datoms (sort-by (juxt :db/id identity) raw-data)]
  (doseq [batch (partition-all 5000 sorted-datoms)]
    (d/transact! conn batch)))
```

```java
// Sort data by Entity, then Attribute
rawData.sort(Comparator.comparing(m -> (Long) m.get("db/id")));
List<List<Map>> batches = partition(rawData, 5000);
for (List<Map> batch : batches) {
    Datalevin.transact(conn, batch);
}
```

```python
# Sort data by Entity, then Attribute
sorted_datoms = sorted(raw_data, key=lambda x: x["db/id"])
for batch in partition_all(sorted_datoms, 5000):
    d.transact(conn, batch)
```

```javascript
// Sort data by Entity, then Attribute
const sortedDatoms = rawData.sort((a, b) => a['db/id'] - b['db/id']);
for (const batch of partitionAll(sortedDatoms, 5000)) {
  d.transact(conn, batch);
}
```

</div>

---

## 3. Bulk Load: `d/init-db` and `d/fill-db`

For the fastest possible initial load into an empty database, bypass the transaction overhead entirely using `d/init-db` and `d/fill-db`. Note that these functions **bypass the WAL** and will not appear in the transaction log.

- **`d/init-db`**: Load a collection of prepared datoms into a fresh, empty database
- **`d/fill-db`**: Bulk load additional datoms into an existing database

<div class="multi-lang">

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

```java
// Prepare datoms with approximate entity IDs (caller's responsibility)
List<Object[]> preparedDatoms = List.of(
    new Object[]{1, "user/name", "Alice"},
    new Object[]{1, "user/age", 30},
    new Object[]{2, "user/name", "Bob"},
    new Object[]{2, "user/age", 25}
);

// Load into empty database
Database db = Datalevin.initDb(preparedDatoms, schema);
```

```python
# Prepare datoms with approximate entity IDs (caller's responsibility)
prepared_datoms = [
    [1, "user/name", "Alice"],
    [1, "user/age", 30],
    [2, "user/name", "Bob"],
    [2, "user/age", 25],
]

# Load into empty database
db = d.init_db(prepared_datoms, schema)
```

```javascript
// Prepare datoms with approximate entity IDs (caller's responsibility)
const preparedDatoms = [
  [1, 'user/name', 'Alice'],
  [1, 'user/age', 30],
  [2, 'user/name', 'Bob'],
  [2, 'user/age', 25],
];

// Load into empty database
const db = d.initDb(preparedDatoms, schema);
```

</div>

> **Note**: These functions skip data integrity checks and temporary entity ID resolution. You must ensure the datoms are correct before calling them.

---

## 4. Summary: The Ingestion Checklist

When you need to ingest data at scale, follow this checklist:

1.  **Use WAL mode with `:relaxed` durability**: This provides the underlying storage performance needed for massive ingestion.
2.  **Use async transactions**: `d/transact-async!` provides adaptive batching for orders-of-magnitude throughput improvement.
3.  **Combine with manual batching**: The effects of auto-batching and manual batching compound.
4.  **Sort your data**: Sort by Entity ID before transacting to minimize B+Tree fragmentation.
5.  **Use `init-db`/`fill-db`** for the fastest possible initial load into an empty database.

For non-durable modes that trade safety for speed, see Chapter 26.

By following these patterns, you can achieve extreme write throughput.
