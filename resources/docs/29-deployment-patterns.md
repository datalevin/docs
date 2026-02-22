---
title: "Deployment Patterns: Embedded vs Client/Server vs Pods"
chapter: 29
part: "VI — Systems and Operations"
---

# Chapter 29: Deployment Patterns: Embedded vs Client/Server vs Pods

Datalevin is designed to be highly portable. Whether you are building a small CLI tool or a massive distributed system, you can deploy Datalevin in the mode that best fits your operational requirements.

This chapter explores the architectural trade-offs of the three primary deployment modes: **Embedded**, **Client/Server**, and **Babashka Pods**.

---

## 1. Embedded Mode: Direct Library Integration

Embedded mode is the "native" way to use Datalevin. You include it as a standard Clojure or Java library (`datalevin/datalevin`) in your application's `deps.edn` or `pom.xml`.

- **Architecture**: Your application and Datalevin share the same process and address space.
- **Performance**: This is the fastest possible deployment. Because Datalevin uses memory-mapping (mmap), your application reads data directly from the OS Page Cache with **zero-copy overhead**.
- **Locking**: A single process owns the "Writer Lock" on the database file. While multiple reader processes can technically access the same file (if configured carefully), it is best practice to have only one process manage the storage directory.

**Best for**:
- High-performance, single-node services.
- Containerized applications where each container has its own volume.
- Desktop applications or local tools.

---

## 2. Client/Server Mode: Centralized Data Management

If you have multiple services that need to share a single Datalevin database, or if you want to manage your database as a standalone system with its own resources and lifecycle, use **Client/Server Mode**.

- **Architecture**: You start a standalone `datalevin-server` process. Applications connect to it over a TCP port using a connection URI (e.g., `dtlv://user:pass@localhost:8898/mydb`).
- **Security**: This mode enables the full Role-Based Access Control (RBAC) system (Chapter 28).
- **Resource Isolation**: The database has its own memory and CPU footprint, separate from your application server. This prevents a complex query in the application from "starving" the database engine of resources.

**Best for**:
- Microservices architectures.
- Shared development environments.
- Scenarios requiring fine-grained user permissions.

---

## 3. Babashka Pods: High-Performance Scripting

Babashka is a fast-starting Clojure interpreter for scripting. Datalevin provides a **Pod** that allows you to use its Datalog and KV capabilities within a Babashka script without the overhead of starting a full JVM.

- **Architecture**: The Datalevin Pod is a pre-compiled binary that runs as a separate process. Babashka communicates with it via a fast, standard-input/output protocol.
- **Fast Startup**: Ideal for tasks where you need the power of a database but don't want the 5-10 second JVM startup time.

**Best for**:
- CLI tools that store state.
- Database maintenance scripts.
- Ephemeral tasks or "Lambda"-style functions.

---

## 4. Comparison Table: Which Mode to Choose?

| Feature | Embedded Mode | Client/Server Mode | Babashka Pods |
| :--- | :--- | :--- | :--- |
| **Performance** | **Maximum** (Zero-copy) | High (TCP overhead) | High (IPC overhead) |
| **Ease of Setup** | Simple (just a lib) | Moderate (separate process) | Simple (single binary) |
| **Security** | None (OS level) | Full RBAC | None (OS level) |
| **Concurrency** | Single-process writer | Multi-process writer | Single-process writer |
| **Language** | JVM (Clojure/Java) | Any (via TCP) | Babashka / Clojure |

---

## Summary: Designing for Your Lifecycle

Datalevin's deployment flexibility allows your application to evolve.

1.  **Prototype with Babashka**: Use the Pod for quick scripts and local experiments.
2.  **Scale with Embedded**: When building your production application, use Embedded mode for maximum performance and simplicity.
3.  **Expand with Server**: If you eventually need to share that data across multiple microservices or teams, "promote" your embedded database to a Datalevin Server.

Because the underlying data format is identical in all modes, you can move between these patterns without any data migration.
