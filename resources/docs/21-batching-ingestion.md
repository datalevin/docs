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

```clojure
;; Fire multiple async transactions
(d/transact-async! conn batch-1)
(d/transact-async! conn batch-2)
(d/transact-async! conn batch-3)

;; Block on the last one to ensure all are committed
(deref (d/transact-async! conn batch-4))
```

The batching is **adaptive**: the higher the write load, the bigger the batch size. This combines with manual batching for compound performance gains.

### 1.2 Sync + Async Combo

For applications that need both good throughput and deterministic commit points, use a sequence of async transactions followed by a sync call:

```clojure
;; Async writes for throughput
(d/transact-async! conn batch-1)
(d/transact-async! conn batch-2)
(d/transact-async! conn batch-3)

;; Sync commit to ensure all are persisted
(d/transact! conn [])
```

Since async transactions are still committed in order, the last realized future indicates all prior calls are already committed.

> **Note**: Datalevin supports only a single write thread at a time. Parallel transactions actually slow writes down due to mutex contention and thread switching overhead. Stick to sequential async calls.

---

## 2. Sorting Before Ingestion

A B+Tree performs best when keys are inserted in **sorted order**. If you insert random Entity IDs and random Values, the engine must "jump around" the B+Tree, leading to frequent cache misses and page splits.

### 2.1 Pre-Sorting Your Data
If you are performing a bulk import (e.g., from a CSV or a SQL dump), sort your datoms *before* they reach the database.

1.  **Group by Entity ID**: This ensures that all attributes for a single entity are written to the EAV index contiguously.
2.  **Group by Attribute and Value**: This ensures that updates to the AVE index are also localized.

```clojure
;; Sort data by Entity, then Attribute
(let [sorted-datoms (sort-by (juxt :db/id identity) raw-data)]
  (doseq [batch (partition-all 5000 sorted-datoms)]
    (d/transact! conn batch)))
```

---

## 3. Bulk Load: `d/init-db` and `d/fill-db`

For the fastest possible initial load into an empty database, bypass the transaction overhead entirely using `d/init-db` and `d/fill-db`.

- **`d/init-db`**: Load a collection of prepared datoms into a fresh, empty database
- **`d/fill-db`**: Bulk load additional datoms into an existing database

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

1.  **Use WAL mode with `:relaxed` durability**: This provides the underlying storage performance needed for massive ingestion.
2.  **Use async transactions**: `d/transact-async!` provides adaptive batching for orders-of-magnitude throughput improvement.
3.  **Combine with manual batching**: The effects of auto-batching and manual batching compound.
4.  **Sort your data**: Sort by Entity ID before transacting to minimize B+Tree fragmentation.
5.  **Use `init-db`/`fill-db`** for the fastest possible initial load into an empty database.

For non-durable modes that trade safety for speed, see Chapter 26.

By following these patterns, you can achieve extreme write throughput.
