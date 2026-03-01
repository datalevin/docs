---
title: "Inside Datalevin: Storage, Indexes, and Execution"
chapter: 36
part: "VIII — Internals and Extensions"
---

# Chapter 36: Inside Datalevin: Storage, Indexes, and Execution

For those who want to understand *why* Datalevin performs the way it does, this chapter provides a deep dive into its internal engine. We will look at the custom storage fork, the physical layout of indexes, the concurrency model, and the stages of the query execution pipeline.

---

## 1. DLMDB: The Forked Foundation

At the heart of Datalevin is **DLMDB**, a specialized fork of the Lightning Memory-Mapped Database (LMDB). While standard LMDB is a world-class KV store, Datalevin required enhancements to support high-performance Datalog.

### 1.1 Page-Level Prefix Compression
Datalog indexes contain vast amounts of redundant data (e.g., the same attribute ID repeated millions of times). DLMDB adds **page-level prefix compression** for both keys and values.
- **Impact**: Reduces on-disk size by over 40% and significantly improves CPU cache locality.
- **DUPSORT Optimization**: Compression is especially effective for "Duplicate Sets" (used in AVE indexes), where many values share the same key.

### 1.2 Order Statistics
Standard B+Trees don't know the count of items in a range without scanning them. DLMDB maintains **subtree node counts**.
- This enables O(log N) cardinality estimates, which are the primary input for the query optimizer.

---

## 2. The Physical Triple Layout

Every Datalog triple `[E A V]` is mapped into the KV store using a compact binary encoding.

- **EAV Index**: The Key is `(E, A)` and the Value is `V`. This allows for localized reads of an entity's attributes.
- **AVE Index**: The Key is `(A, V)` and the Value is `E`. Because multiple entities can share the same attribute-value pair, this index heavily utilizes LMDB's `DUPSORT` (nested B+Trees).

---

## 3. Concurrency: MVCC and ACID

Datalevin achieves high performance through a **Multi-Version Concurrency Control (MVCC)** architecture.

### 3.1 MVCC: Readers Never Block Writers
Datalevin uses a Copy-on-Write (CoW) mechanism:
1.  **Readers** get a "snapshot" of the database at a specific point in time. They can read as much as they want, as fast as they want, and they will always see a consistent view of the data.
2.  **Writers** update the database by writing to *new* pages, leaving the pages the readers are using untouched.

**The Result**: Readers never block writers, and writers never block readers. This is the secret to Datalevin's near-linear read scaling.

### 3.2 Thread-Local Reused Read-only Transactions
For regular (non-virtual) threads, Datalevin **reuses read-only transactions and cursors** to minimize overhead. Each regular thread maintains its own thread-local read-only transaction and cursor pool.

- **Read Reuse**: The same read-only transaction is reused across multiple read operations within the same thread.
- **Virtual Threads**: This reuse is **disabled for virtual threads** because virtual threads are short-lived. Each virtual thread creates fresh transactions to avoid pinning carrier threads.

### 3.3 The Single-Writer Model vs. Concurrent WAL
While there can be thousands of concurrent readers, Datalevin follows a **Single-Writer Model** per database environment by default.

- **Locking**: When a transaction starts a write, it acquires a **Writer Lock**. Only one thread can hold this lock at a time.
- **Concurrent Writes with WAL Mode**: With the introduction of **WAL mode**, Datalevin significantly improves write concurrency. While a single lock still coordinates the final commit to the B+tree, the **WAL append process** is highly optimized for concurrent callers, allowing multiple writer threads to achieve significantly higher aggregate throughput.

---

## 4. The Write-Ahead Log (WAL) Engine

The WAL engine is designed to bridge the gap between B+Tree read performance and LSM-Tree write throughput.

### 4.1 LSN Architecture
Every transaction in WAL mode is assigned a contiguous **Log Sequence Number (LSN)**. The LSN is the canonical position of a commit in the database's history.
- **Watermarks**: Datalevin tracks `:last-committed-lsn` (application view), `:last-durable-lsn` (disk view), and `:last-applied-lsn` (main DB view).
- **Segmentation**: The WAL is stored in **log segments** that are rolled based on size or age.

### 4.2 Recovery and Replay
On database startup, if the `last-applied-lsn` in the LMDB file is behind the `last-durable-lsn` in the WAL, Datalevin enters **Recovery Mode**:
1.  **Scan**: It scans all WAL segments newer than the last applied checkpoint.
2.  **Validate**: It validates the checksums and integrity of each log record.
3.  **Replay**: It replays the transactions directly into the LMDB environment.

---

## 5. The Query Pipeline: Rewrite and Optimize

When you execute a query, it passes through several stages before a single byte is read from disk.

### 5.1 Rewrite Passes
The engine first simplifies the query through multiple rewrite passes:
- **Predicate Pushdown**: Moving filters as close to the data source as possible.
- **Inequality Conversion**: Rewriting `[(> ?age 21)]` into a direct index range scan on the AVE index.

### 5.2 Selinger-Style Optimization
Datalevin uses a dynamic programming algorithm to explore the join space. It uses the DLMDB order statistics to calculate the cost of millions of possible join orders and selects the one that minimizes intermediate results.

### 5.3 Hash Joins
While nested-loop joins are the default, the optimizer can choose a **Hash Join** for large, non-selective inputs.

---

## 6. The Rule Engine: Efficient Recursion

Datalevin's rule engine is a modern implementation of deductive logic.

- **Semi-Naive Evaluation**: This strategy ensures that recursive rules only process "new" facts found in the previous iteration, preventing exponential work.
- **Magic-Set Rewrites**: This technique "seeds" recursive evaluation with bindings from the outer query, ensuring the engine doesn't calculate the entire global state if only a subset is requested.

---

## Summary: Primitives for the Future

The internals of Datalevin are designed for **predictability** and **efficiency**.

- **Storage** is compressed and statistical.
- **Concurrency** is lock-free for readers and optimized for writers via WAL.
- **Optimization** is cost-based and enterprise-grade.

By building on these robust primitives, Datalevin provides a platform that is not only a great database for today's relational needs but also a powerful substrate for tomorrow's intelligent systems.
