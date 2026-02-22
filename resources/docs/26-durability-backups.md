---
title: "Durability, Snapshots, and Backup Strategies"
chapter: 26
part: "VI — Systems and Operations"
---

# Chapter 26: Durability, Snapshots, and Backup Strategies

Performance is useless if your data is not safe. Datalevin's storage engine (LMDB/DLMDB) is famous for its **reliability** and **immediate recovery** in the face of system crashes.

This chapter covers how Datalevin ensures durability, how to perform online snapshots, and how to build a robust backup strategy for your production environment.

---

## 1. Durability: The Power of Copy-on-Write (CoW)

Traditional databases (like Postgres) use a Write-Ahead Log (WAL) and an "update-in-place" strategy. If the system crashes mid-write, the database may be corrupted and must "replay" the log to recover.

Datalevin's storage engine (LMDB) uses a **Copy-on-Write (CoW) B+Tree**.

1.  **Never Overwrite**: When you write a new datom, LMDB doesn't modify existing pages. Instead, it creates a *new version* of the affected pages.
2.  **Atomic Commits**: Once all the new pages are written and synced to disk, the "Root" of the B+Tree is updated in a single, atomic operation.
3.  **Instant Recovery**: If the power fails during a write, the root pointer is never updated. When you restart the database, it simply points to the last known-good state. **There is no "recovery process" needed because the database is never in an inconsistent state on disk.**

---

## 2. Syncing to Disk: `msync` and Durability

The speed of your writes is primarily limited by the speed of your disk's **synchronous flush (`msync`)**.

- **Safe by Default**: On every transaction commit, Datalevin tells the OS to flush the Page Cache to disk. This ensures that even if the OS crashes, your data is safe.
- **Hardware Impact**: On high-speed NVMe drives, this flush is very fast. On older magnetic drives or cloud-based block storage (like AWS EBS), it can be a major bottleneck.
- **WAL Mode**: As discussed in Chapter 4, the **WAL mode** can mitigate this by turning many random `msync` calls into a single sequential log write.

---

## 3. Online Snapshots: `d/snapshot`

Backing up a live database can be tricky. If you simply copy the file while a write is happening, the copy might be corrupted.

Datalevin provides a specialized **`d/snapshot`** function for safe, online backups.

```clojure
;; Create a consistent snapshot of the live database
(d/snapshot conn "/path/to/backup-file")
```

- **Live Backups**: You can run `snapshot` while the database is being actively queried and written to.
- **Consistency**: The snapshot represents a single, transactionally consistent point in time.
- **Zero-Impact**: Because of LMDB's MVCC architecture (Chapter 27), the snapshot doesn't block writers or other readers.

---

## 4. Backup Strategies: File Copies

Because LMDB is just a single file (or a directory with two files), backups are straightforward.

### 4.1 Simple File Copy (Offline)
If you can stop the database, you can simply use `cp`, `rsync`, or `tar` to copy the database directory. The resulting copy is a perfectly valid Datalevin database.

### 4.2 Incremental Backups (Rsync)
Because LMDB only writes to new pages (CoW), an `rsync` of the database file can be very efficient, as it only needs to copy the changed pages.

> **Tip**: Always use `d/snapshot` for live backups to ensure the copy is never "torn" by a concurrent write.

---

## 5. Recovery: Restoring from a Backup

Restoring a Datalevin database is as simple as:
1.  Stopping the application.
2.  Replacing the database directory with your backup.
3.  Restarting the application.

Since there is no "log replay" or "database warming" needed, your application can be back online in seconds.

---

## 6. Summary: The Operations Checklist

To ensure your data is safe and recoverable, follow this checklist:

1.  **Monitor disk space**: LMDB needs enough free space to perform its CoW operations.
2.  **Automate `d/snapshot`**: Run a daily or hourly snapshot and move the result to an off-site location (e.g., AWS S3).
3.  **Test your restores**: Regularly practice restoring from a snapshot to a fresh server.
4.  **Use NVMe for speed**: The durability of your database is directly tied to the IOPS and latency of your disk.

By leveraging Datalevin's rock-solid CoW architecture, you can sleep soundly knowing your data is safe from crashes and easy to recover from disasters.
