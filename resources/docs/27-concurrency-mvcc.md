---
title: "Concurrency: Readers, Writers, and MVCC"
chapter: 27
part: "VI — Systems and Operations"
---

# Chapter 27: Concurrency: Readers, Writers, and MVCC

A modern database must handle many simultaneous users without they seeing each other's partial changes. Datalevin achieves this through a high-performance **Multi-Version Concurrency Control (MVCC)** architecture.

This chapter explains how Datalevin manages concurrent readers and writers, the trade-offs of the single-writer model, and how to ensure your application remains responsive under heavy load.

---

## 1. MVCC: Readers Never Block Writers

In a traditional database with "locking," a long-running report query might "lock" the table, preventing any new data from being inserted. This is a major source of latency and contention.

Datalevin uses **MVCC**, where:
1.  **Readers** get a "snapshot" of the database at a specific point in time. They can read as much as they want, as fast as they want, and they will always see a consistent view of the data.
2.  **Writers** can update the database at the same time. Their changes are written to *new* pages (Copy-on-Write), leaving the pages the readers are using untouched.

**The Result**: Readers never block writers, and writers never block readers. This is the secret to Datalevin's near-linear read scaling.

### 1.1 Thread-Local Reused Read-only Transactions

For regular (non-virtual) threads, Datalevin **reuses read-only transactions and cursors** to minimize overhead. Each regular thread maintains its own thread-local read-only transaction and cursor pool.

- **Read Reuse**: The same read-only transaction is reused across multiple read operations within the same thread, avoiding the cost of creating a new transaction each time.
- **Virtual Threads**: This reuse is **disabled for virtual threads**  because virtual threads are short-lived and abundant. Each virtual thread creates fresh transactions to avoid pinning carrier threads.


---

## 2. The Single-Writer Model

While there can be thousands of concurrent readers, Datalevin follows a **Single-Writer Model** per database environment.

- **Locking**: When a transaction starts a write, it acquires a **Writer Lock**. Only one thread can hold this lock at a time.
- **Queueing**: If another thread tries to write while the lock is held, it will wait until the first transaction is committed or aborted.

### 2.1 Write Transaction Isolation

Every write operation (via `d/transact!` or `d/with-transaction`) creates a **new read-write transaction**. All reads and writes within a `with-transaction` block use the **same write transaction**, providing full isolation:

```clojure
(d/with-transaction [tx conn]
  ;; Both queries use the same write transaction
  (d/q '[:find ?e :where [?e :user/name "Alice"]] (d/db tx))
  (d/transact! tx [[:db/add (d/tempid :db.part/user) :user/name "Bob"]]))
```

- **Isolation**: Changes made inside the transaction are **invisible to other readers** until the transaction commits.
- **Atomicity**: If the transaction is aborted, all effects are discarded, no partial changes are visible.
- **Single Writer**: Only one thread can hold the lock at a time, eliminating deadlocks.

### 2.2 Impact on Application Design
For most applications, the single-writer model is not a bottleneck because Datalevin transactions are extremely fast (milliseconds).

- **Best Practice**: Keep your write transactions short and focused. Don't perform long-running network calls or complex computations *inside* a `with-transaction` block.
- **Scaling Writes**: If your write volume is massive, use **Batching** (Chapter
  21) to perform thousands of updates in a single lock-held period.
  `transact-async` provides adaptive automatic batching.

---

## 3. Transaction Isolation: ACID Guarantees

Datalevin provides full **ACID (Atomicity, Consistency, Isolation, Durability)** guarantees.

- **Atomicity**: Either all datoms in a transaction are committed, or none are.
- **Consistency**: Every transaction moves the database from one valid state to another (enforcing unique constraints, etc.).
- **Isolation**: Every transaction is isolated from the partial changes of others.
- **Durability**: Once a transaction is committed, its changes are synced to disk and survive a system crash.

### 3.1 Reader Isolation
A reader in Datalevin sees the database exactly as it was when the `db` value was obtained.

```clojure
;; Get a snapshot of the database
(let [db-val (d/db conn)]
  ;; Every query using 'db-val' will see the same consistent view,
  ;; even if other threads are transacting millions of new datoms.
  (d/q '[:find ... :where ...] db-val))
```

---

## 4. Managing Reader Slots: `:max-readers`

As discussed in Chapter 22, the **`:max-readers`** parameter defines how many concurrent reader snapshots can be active.

- **Reader "Leaks"**: In some low-level KV operations, if you open a reader transaction but forget to close it, you can "exhaust" the reader slots.
- **The Datalog Advantage**: If you use the standard `d/q` and `d/pull` APIs, Datalevin manages these reader slots for you automatically, ensuring they are always safely released.

---

## 5. Summary: The Concurrency Model

Datalevin's concurrency model is designed for high-throughput, read-heavy workloads.

- **Lock-Free Readers**: Scale to thousands of concurrent users with zero-copy efficiency.
- **MVCC Isolation**: Ensure consistency without the overhead of complex table-level locks.
- **Single-Writer Simplicity**: Eliminate deadlocks and complex conflict resolution logic.

By understanding how Datalevin balances the needs of readers and writers, you can build applications that stay fast and responsive as your user base and data volume grow.
