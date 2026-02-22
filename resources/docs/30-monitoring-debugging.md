---
title: "Monitoring, Debugging, and Production Checklist"
chapter: 30
part: "VI — Systems and Operations"
---

# Chapter 30: Monitoring, Debugging, and Production Checklist

Running Datalevin in production requires a shift from a "developer" mindset to an "operator" mindset. Because Datalevin offloads much of its work to the operating system, monitoring the database often means monitoring the host environment.

This chapter provides a comprehensive guide to monitoring Datalevin, debugging performance issues, and a checklist for a production-ready deployment.

---

## 1. Monitoring the Environment

Datalevin's performance is tied directly to the health of the host's memory and disk.

### 1.1 Disk I/O and Latency
Every commit in Datalevin is a synchronous flush to disk (unless using `:nosync`).
- **Monitor**: Disk IOPS and `iowait`.
- **Recommendation**: Use **NVMe SSDs** for the best performance. High-latency block storage (like network-attached drives) will severely limit your write throughput.

### 1.2 The Page Cache and RSS
Because Datalevin uses memory-mapping, the OS Page Cache is your "buffer pool."
- **Resident Set Size (RSS)**: This shows how much of the database is currently "hot" in RAM.
- **Virtual Size**: This will match your `:mapsize` and is not a cause for concern.
- **Tuning**: Monitor "Page Faults." A high number of major page faults indicates that your working set doesn't fit in RAM, causing the OS to fetch data from disk frequently.

---

## 2. Database Health: `d/db-stats`

You can inspect the internal health of the B+Tree using the `db-stats` function.

```clojure
(d/db-stats db)
```

This returns metrics such as:
- **`branch_pages` / `leaf_pages`**: The structure of your tree.
- **`overflow_pages`**: Pages used for large values.
- **`entries`**: Total number of key-value pairs.

### 2.1 The Free List
LMDB reuses deleted pages rather than shrinking the file. `db-stats` will show you how many pages are currently in the **Free List**. If this number is very high relative to your total pages, it may be time for a `d/compact` operation (Chapter 22).

---

## 3. Debugging and Profiling

When queries are slow or the system feels sluggish, use these tools to find the bottleneck.

- **Query Tracing**: Use `:trace true` in your queries (Chapter 24) to see which joins are consuming the most time.
- **Writer Contention**: If `d/transact!` calls are slow but the disk is idle, check if multiple threads are competing for the single Writer Lock.
- **Logging**: Use a library like Timbre to log transaction times and query latencies.

---

## 4. The Production Checklist

Before you "go live," ensure your configuration matches these best practices.

### 4.1 Memory and Storage
- [ ] **`:mapsize`**: Set to at least 2x your expected data size.
- [ ] **`:max-readers`**: Increase to 512 or 1024 for high-concurrency web apps.
- [ ] **WAL Mode**: Enable `:datalog-wal? true` for high write throughput.
- [ ] **`:nosync`**: Ensure this is **FALSE** (default) for production data safety.

### 4.2 Operating System Tuning
- [ ] **`vm.swappiness`**: Set to `1` or `10` to prevent the OS from swapping database pages to disk.
- [ ] **`vm.dirty_ratio`**: Adjust to ensure the OS flushes writes to disk consistently.
- [ ] **Transparent Huge Pages (THP)**: Often recommended to be disabled or set to `madvise` for database workloads.

### 4.3 Operations
- [ ] **Automated Backups**: Use `d/snapshot` to create transactionally consistent backups without downtime.
- [ ] **Monitoring Hooks**: Use `d/listen!` to track transaction volume and data growth.
- [ ] **Health Checks**: Implement a `/health` endpoint that performs a simple `(d/q '[:find ?e :limit 1] db)` to verify end-to-end connectivity.

---

## Summary

Datalevin is a "quiet" database. When tuned correctly, it requires very little maintenance. By monitoring the OS Page Cache, keeping your B+Tree compacted, and following the production checklist, you can ensure that your Datalevin deployment remains fast and reliable for years.
