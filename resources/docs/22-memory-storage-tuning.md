---
title: "Memory Layout and Storage Tuning"
chapter: 22
part: "V — Performance and Dataflow"
---

# Chapter 22: Memory Layout and Storage Tuning

Datalevin is a "zero-copy" database, which means its memory management is very different from standard JVM-based applications. While many databases fight the JVM Garbage Collector (GC), Datalevin works *with* the operating system's native memory management.

This chapter covers the best practices for tuning Datalevin's memory and storage parameters for maximum performance and stability.

---

## 1. The LMDB Map: `:mapsize`

The most critical parameter for Datalevin is **`:mapsize`**. This defines the maximum size the database file can grow to in your address space.

### 1.1 Virtual Memory vs. Resident Memory
Unlike a traditional Java heap, the `:mapsize` is a **virtual memory** reservation. Setting a 1TB mapsize does *not* mean the database will consume 1TB of RAM. It simply means the OS will reserve a 1TB "address space" for the database file.

- **Recommendation**: Set `:mapsize` to be significantly larger than your expected data size (e.g., if you expect 50GB of data, set it to 256GB).
- **Default**: The default mapsize is often small (e.g., 10GB). You should almost always increase this for production use.

```clojure
;; Set mapsize to 128GB (in bytes)
(d/get-conn path schema {:mapsize (* 128 1024 1024 1024)})
```

> **Note**: Datalevin will automatically grow the mapsize if it runs out of space, but this is an expensive operation that causes a significant performance spike. Setting an appropriately large mapsize upfront avoids this overhead.

---

## 2. Leveraging the OS Page Cache

Because Datalevin uses **memory-mapped files (`mmap`)**, it does not manage its own buffer pool. Instead, it relies on the **Operating System Page Cache**.

- **Zero-Copy Reads**: When you perform a read, the OS handles fetching the required pages from disk into the Page Cache. Datalevin then returns a pointer directly into that cache.
- **GC Independence**: Because the database data lives in the Page Cache (outside the JVM heap), your queries do not trigger JVM garbage collection. This allows Datalevin to handle datasets much larger than your JVM heap size with sub-millisecond latency.

> **Tuning Tip**: For best performance, ensure your server has enough free RAM to hold your **active working set** (the most frequently queried data) in the OS Page Cache.

---

## 3. Reader Threads and Locking: `:max-readers`

LMDB uses a "lock-free" reader model, but it still needs to track active readers to prevent writers from overwriting pages that are still being read.

- **`:max-readers`**: This parameter (default: 126) defines how many concurrent reader threads can access the database.
- **When to Increase**: If your application uses highly concurrent web servers or background workers, you may need to increase this to 512 or 1024.

```clojure
(d/get-conn path schema {:max-readers 512})
```

---

## 4. Storage Efficiency: Prefix Compression

Datalevin's storage engine (DLMDB) uses **prefix compression** to minimize the on-disk footprint of your indexes.

In a Datalog AVE index, many keys share the same Attribute and Value (e.g., thousands of users in the same city). DLMDB only stores the "difference" between consecutive keys, which:
- **Reduces Storage Size**: Often by 30-50% compared to uncompressed B+Trees.
- **Improves Cache Locality**: More keys fit into a single CPU cache line, making index scans significantly faster.

---

## 5. Summary: The Tuning Checklist

When deploying Datalevin to production, follow this checklist:

1.  **Set a large `:mapsize`**: Reserve enough virtual address space for your future growth.
2.  **Monitor Page Cache usage**: Ensure your server has enough RAM to keep your working set in memory.
3.  **Adjust `:max-readers`**: If you have a high-concurrency application, increase the reader limit.

By tuning these parameters, you ensure that Datalevin's zero-copy architecture remains fast, stable, and efficient across any dataset size.
