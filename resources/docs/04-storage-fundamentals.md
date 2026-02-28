---
title: "Storage Fundamentals: LMDB, Key–Value Layout, and Persistence"
chapter: 4
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 4: Storage Fundamentals: LMDB, Key–Value Layout, and Persistence

Datalevin's query model is fact-centric, but its performance and reliability are grounded in its storage architecture. Unlike many databases that build custom buffer managers and page caches, Datalevin leverages the operating system's existing strengths by building on **LMDB** (Lightning Memory-Mapped Database).

This chapter explores why Datalevin chose this foundation, how it extends LMDB into **DLMDB**, and how the physical layout of data supports efficient Datalog queries, secondary indexes, and high-throughput writes.

---

## 1. The Foundation: Why LMDB?

LMDB is a "tiny" but incredibly powerful key-value store. Its design philosophy is to do as little as possible in user space, offloading complex tasks like memory management to the operating system kernel.

### 1.1 The Power of Memory-Mapping (mmap)
Traditional databases often manage their own "buffer pool", a chunk of RAM where they keep data pages. This often conflicts with the OS's own file system cache, leading to "double buffering" and inefficient memory use.

LMDB uses **memory-mapping (`mmap`)**. It treats the database file as if it were a large array in the application's address space. When Datalevin reads data, the OS handles fetching the required pages from disk into the **Page Cache**.

- **Zero-Copy Reads**: On the read hot-path, Datalevin doesn't copy data from a kernel buffer to a user-space buffer. Instead, it returns a `DirectByteBuffer` that points directly to the OS Page Cache. This bypasses JVM heap allocation and minimizes Garbage Collection (GC) overhead. By default, this memory map is **read-only**, allowing the OS to manage the pages with minimal overhead and perfect safety, as it knows the application cannot directly modify the underlying file through the map.
- **Immediate Recovery**: LMDB uses a Copy-on-Write (CoW) B+tree. It never
  overwrites data in place. When a transaction commits, it simply updates a
  pointer to the new root of the tree. If the system crashes, the database is
  always by default in a consistent state: no expensive "recovery" or log-replay
  process is needed. For write intensive workloads, a Write Ahead Log (WAL) mode
  is also available in Datalevin.

### 1.2 Read-Heavy Concurrency
LMDB provides **MVCC (Multi-Version Concurrency Control)**. Readers never block writers, and writers never block readers. Because readers are zero-copy and lockless, Datalevin can scale to thousands of concurrent readers with near-linear performance.

---

## 2. B+Trees vs. LSM-Trees

Database storage engines generally fall into two camps: B+Trees (used by Postgres, Oracle, and LMDB) and Log-Structured Merge-Trees (LSM) (used by Cassandra, RocksDB, and LevelDB).

| Feature | B+Tree (Datalevin/LMDB) | LSM-Tree (RocksDB) |
| :--- | :--- | :--- |
| **Read Performance** | Excellent (O(log N) point/range) | Variable (may check multiple levels) |
| **Write Performance** | Good (constrained by random I/O) | Excellent (sequential appends) |
| **Complexity** | Simple (no background compaction) | High (compaction, bloom filters) |
| **Large Values Handling** | Good (little write amplification) | Poor (rewrite data repeatedly during compaction) |
| **Space Efficiency** | Lower (due to fragmentation) | Higher (compressed on disk) |
| **Consistency** | Strong and immediate | Often eventual or complex to tune |

**Why Datalevin chose B+Trees:**
Datalog queries rely heavily on **range scans** and **index joins**. A B+Tree
keeps data perfectly sorted and provides predictable read latency. By using
LMDB, Datalevin avoids the "write stalls" and CPU spikes associated with LSM
background compaction, making it a better fit for predictable, real-time
applications. In addition, the multi-paradigm nature of Datalevin demands the
building of secondary indices (Section 5), which often require storing **large values**.

---

## 3. From LMDB to DLMDB

Standard LMDB is a general-purpose tool, but Datalevin uses a specialized fork called **DLMDB** to support advanced Datalog features.

### 3.1 Order Statistics (`:counted`)
A standard B+Tree doesn't know how many items are in a range without scanning them. DLMDB adds **counted B+Trees**, where each node in the tree stores the count of items in its sub-tree.

- **Instant Counts**: All count/sample operations are O(log n) or O(1). For example, `(d/count-datoms db nil :person/age nil)` is an O(log n) operation, while `(d/count-datoms db nil :person/age 30)` is O(1).
- **Fast Pagination**: You can skip the first 1,000,000 items in a sorted range
  and start reading the 1,000,001st item instantly using "rank-based" lookups.
- **Efficient Query Planning**: The Datalog engine uses these counts and samples to decide the most efficient join order (e.g., "should I filter by age first, or by city?").

### 3.2 Prefix Compression
Datalog indexes often contain many keys that start with the same prefix (e.g., many datoms for the same attribute). DLMDB compresses these prefixes, significantly reducing the on-disk footprint and improving CPU cache locality.

---

## 4. Physical Layout: The Nested Triple Index

Datalevin doesn't just store "triples" (E, A, V). It stores them in multiple sorted orders to make queries fast. The primary indexes are **EAV** (Entity-Attribute-Value) and **AVE** (Attribute-Value-Entity).

### 4.1 Leveraging DUPSORT
Instead of storing a flat list of triples, Datalevin uses LMDB's `DUPSORT` feature to "nest" values. This is effectively a "B+Tree of B+Trees."

**Conceptual AVE Layout:**
```text
Key: (:person/age, 30) -> Values: [101, 102, 105, 200, ...] (Entity IDs)
Key: (:person/age, 31) -> Values: [103, 104, 201, ...]
```

In this layout, the Attribute and Value form the **Key**, and all the Entities
that share that value are stored in a sorted **Duplicate List**. This makes
finding "all people aged 30" extremely fast: it is a single key lookup followed by a sequential read of entity IDs.

### 4.2 Storing Raw Bytes

All triples are encoded into raw bytes, using header byte for different data types.
Datalevin binary encoding ensures that the binary sort order always matches the
inherent data sort order, so that queries can efficiently pinpoint data items.
The bytes are also prefix-compressed on the page level.

---

## 5. Secondary Indexes and Blob Storage

Beyond the primary triple indexes, Datalevin supports specialized secondary indexes such as **Full-Text Search (FTS)**, **Vector Search**, and **Document (idoc) Indexes**. These are not external plugins; they are integrated directly into the same LMDB environment.

### 5.1 Leveraging the KV Substrate
Secondary indexes are implemented as additional named sub-databases (DBIs) within the same storage file. This allows Datalevin to maintain transactional atomicity across both Datalog datoms and secondary index updates.

### 5.2 Blob Storage Capabilities
While a Datalog triple is often a small piece of data, secondary indexes often need to
store larger, more complex structures, such as search term postings lists, vector embeddings, or serialized JSON documents.
- **Large Value Support**: Datalevin leverages LMDB's ability to store values up to 2GB in size (blobs).
- **Efficiency**: By storing these as blobs in specialized DBIs, Datalevin can
  perform high-speed searches without cluttering the main triple indexes. For
  example, a search index might store a large bitmap of compressed postings list as a single KV value, which is then retrieved and processed with the same zero-copy efficiency as a simple triple.

This capability is what makes Datalevin a "multi-paradigm" database: it uses the same robust KV foundation to power everything from relational Datalog queries to modern vector searches.

---

## 6. The Key-Value API: Direct Access

While Datalog is the primary interface, Datalevin exposes the underlying KV store as a first-class citizen. This is a deliberate design choice: the Datalog engine is built *on top* of this KV layer, rather than as a separate opaque engine.

Exposing the KV layer allows developers to bypass the triple-model when it isn't
the best fit. For example, when building custom indexes, high-frequency counters, or storing large binary blobs. This "multi-paradigm" approach ensures that you aren't forced to fit every data shape into a Datalog triple if a simple key-pair is more efficient.

The details of the KV API and its practical usage are covered in **Chapter 6**.

---

## 7. Persistence and Durability: WAL Mode

By default, LMDB is extremely safe but can be limited by disk I/O because every write transaction requires a synchronous flush of the memory-mapped pages to disk (`msync`) to ensure durability. Datalevin introduces a **WAL (Write-Ahead Log) Mode** to overcome this.

### 7.1 How WAL Mode Works
When WAL mode is enabled (`:wal? true`):

1.  **Transaction**: A write request arrives.
2.  **WAL Append**: The change is appended to a sequential log file and synced to disk. Sequential writes are significantly faster than random B+Tree updates.
3.  **No Sync LMDB**: The change is also added to the KV store without syncing
    to disk.
4.  **Acknowledgment**: The application receives a "Success" response.
5.  **Snapshots**: A background job makes consistent snapshots of the database
   from time to time.
6.  **Checkpoint**: Periodically, a background indexer call `msync` on the LMDB
    B+tree to flush all pages to disk and clear the pending WAL. If a crash
    happens when the `msync` has not being called, the database can be recovered
    from the WAL using a recent snapshot.

This provides the **write throughput of an LSM-tree** with the **read performance and stability of a B+Tree**.

### 7.2 Programmable WAL API and Datalog CDC
The WAL is not just an internal implementation detail; it is exposed as a first-class API at the **Key-Value (KV) level**. This allows for low-level interaction with the physical stream of changes.

- **KV-Level (WAL)**: Using `open-tx-log` and `gc-wal-segments!`, you can access the raw, physical transaction log. This is ideal for system-level tasks like **replication** or building low-level Change Data Capture (CDC) for non-Datalog data stored in the same KV environment.
- **Datalog-Level (Listeners)**: For most application-level needs, Datalevin provides a higher-level CDC mechanism via **Datalog listeners**. By using `d/listen!`, you can subscribe to logical Datalog transactions, receiving the set of added and retracted datoms. This is the preferred way to trigger application logic, update search indexes, or sync with other high-level systems.

By providing these two mechanisms at different levels, Datalevin gives you the choice between physical stream processing at the storage layer and logical event processing at the database layer.

---

## Summary

Datalevin's storage layer is a masterclass in pragmatic engineering:
- It uses **LMDB** for its rock-solid stability and zero-copy performance.
- It uses **B+Trees** to ensure predictable read performance for complex Datalog queries.
- It adds **DLMDB** extensions like order statistics to power the query planner.
- It provides a **Hybrid WAL** mode to scale write performance without sacrificing the B+Tree model.

By understanding these fundamentals, you can better tune your schema and queries for maximum performance.
