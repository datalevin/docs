---
title: "Batching, Sorting, and High-Throughput Ingestion"
chapter: 21
part: "V — Performance and Dataflow"
---

# Chapter 21: Batching, Sorting, and High-Throughput Ingestion

While Datalevin is highly efficient for single transactions, its performance truly shines when you need to ingest millions of datoms. Ingesting large datasets requires a different strategy than standard interactive writes.

This chapter covers the best practices for **High-Throughput Ingestion**, focusing on batching, sorting, and leveraging the low-level storage capabilities of DLMDB.

---

## 1. Batching Transactions

The most important rule for high-speed ingestion is to **batch your writes**. Every `d/transact!` call involves substantial overhead:
- Acquiring the writer lock.
- Walking the B+Tree to find insertion points.
- Committing and flushing (syncing) to disk.

### 1.1 The Golden Ratio: 1,000 - 10,000 Datoms
Avoid transacting one datom at a time. Instead, collect them into batches.
- **Good**: `(d/transact! conn batch-of-5000-datoms)`
- **Bad**: `(doseq [d datoms] (d/transact! conn [d]))`

By batching, you amortize the cost of the writer lock and disk sync across many datoms. A batch size of 1,000 to 10,000 is often the "sweet spot" for maximizing throughput without consuming too much memory for the transaction state.

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

## 3. High-Throughput Mode: WAL and Syncing

As discussed in Chapter 4, Datalevin's **Hybrid WAL mode** is the secret to high write throughput.

### 3.1 Enabling WAL Mode
By setting `:datalog-wal? true` and `:kv-wal? true` in your configuration, you enable sequential, append-only writes. This can increase write throughput by **10x or more** on traditional SSDs.

### 3.2 Controlling Sync Behavior
By default, Datalevin is extremely safe, performing a synchronous disk flush (`msync`) on every transaction. For bulk imports where you can afford to re-run the import in case of a crash, you can disable synchronous syncing to maximize speed.

```clojure
;; ONLY use for bulk imports where you have a backup!
(def conn (d/get-conn path schema {:nosync true}))
```

> **Warning**: Using `:nosync` means that a system crash could lead to data loss or even index corruption. Always re-enable syncing for standard application use.

---

## 4. Initial Bulk Load: `d/bulk-transact!`

For the initial loading of a new database, Datalevin provides specialized bulk-loading functions that bypass the overhead of standard transactions. These functions assume that the database is not being used by other readers or writers during the load.

Contact the Datalevin team or refer to the internal API documentation for using these high-performance low-level bulk loaders, which can ingest millions of datoms per second by writing directly to the B+Tree pages.

---

## 5. Async Ingestion with `d/transact-async!`

If your application needs to handle a high volume of writes without blocking the main thread, use **`d/transact-async!`**.

```clojure
(let [future-tx (d/transact-async! conn my-batch)]
  ;; Do other work while the transaction happens in the background
  (deref future-tx)) ; Wait for completion later
```

`transact-async!` puts your transaction into a queue and returns a future. This allows your application to "fire and forget" writes, letting Datalevin's background writer handle the ingestion as fast as the disk allows.

---

## 6. Summary: The Ingestion Checklist

When you need to ingest data at scale, follow this checklist:

1.  **Batch your writes**: Aim for 1,000 to 10,000 datoms per transaction.
2.  **Enable WAL mode**: Use `:datalog-wal? true` for sequential write performance.
3.  **Sort your data**: Sort by Entity ID before transacting to minimize B+Tree fragmentation.
4.  **Use async writes**: Use `d/transact-async!` to prevent blocking your application's main thread.
5.  **Disable sync (if safe)**: Use `:nosync` only for temporary bulk imports.

By following these patterns, you can ensure that your Datalevin database stays fast and responsive, even when handling the most demanding data ingestion workloads.
