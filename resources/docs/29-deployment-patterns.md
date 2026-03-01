---
title: "Deployment Patterns: Embedded, Server, and Pods"
chapter: 29
part: "VI — Systems and Operations"
---

# Chapter 29: Deployment Patterns: Embedded, Server, and Pods

Datalevin is designed to be highly portable. Whether you are building a small CLI tool or a massive distributed system, you can deploy Datalevin in the mode that best fits your operational requirements.

This chapter explores the architectural trade-offs of the three primary deployment modes: **Embedded**, **Server**, and **Babashka Pods**.

---

## 1. Embedded Mode: Maximum Performance

Embedded mode is the primary way to use Datalevin. You include it as a standard library in your application.

- **Architecture**: Your application and Datalevin share the same process and address space.
- **Performance**: This is the fastest possible deployment. Because Datalevin uses memory-mapping (mmap), your application reads data directly from the OS Page Cache with **zero-copy overhead**.
- **Operational Simplicity**: No separate database process to manage, monitor, or secure. The database file is just another part of your application's state.

**Best for**:
- High-performance, single-node services.
- Containerized applications where each container has its own volume.
- Desktop applications or local tools.

---

## 2. Server Mode: Centralized Management

Server mode (detailed in **Chapter 27**) is used when you need multiple services to share a single Datalevin instance, or when you require cross-language access (e.g., Python/Go clients).

- **Architecture**: A standalone `dtlv` server process manages the storage and provides a network API.
- **Benefits**: Centralized backup management, fine-grained RBAC, and the ability to share a single database across microservices.
- **Trade-offs**: Network latency and serialization overhead (Nippy/Transit).

**Best for**:
- Microservices architectures where data must be shared.
- Shared development environments for teams.
- Non-JVM applications needing Datalog or KV storage.

---

## 3. Babashka Pods: Rapid Scripting

Babashka is a fast-starting Clojure interpreter for scripting. Datalevin provides a **Pod** that allows you to use its Datalog and KV capabilities within a script without the overhead of the full JVM startup.

- **Architecture**: The Pod runs as a separate process and communicates with Babashka via IPC.
- **Performance**: High (IPC overhead is minimal compared to network), but lacks the zero-copy speed of the embedded mode.
- **Convenience**: Single-binary installation and instantaneous startup.

**Best for**:
- CLI tools that store state.
- Database maintenance and migration scripts.
- Ephemeral tasks or "Lambda"-style functions.

---

## 4. Architectural Comparison Table

| Feature | Embedded | Server | Babashka Pod |
| :--- | :--- | :--- | :--- |
| **Communication** | Direct (Function calls) | Network (TCP) | IPC (Stdio/Sockets) |
| **Overhead** | None (Zero-copy) | High (Serialization + TCP) | Medium (Serialization + IPC) |
| **Security** | OS-level (File permissions) | Full RBAC (Users/Roles) | OS-level |
| **Concurrency** | Single-Writer (process) | Multi-client (server-side) | Single-Writer (process) |
| **Tech Stack** | JVM (Clojure/Java) | Language Independent | Babashka |

---

## Summary: Designing for Your Lifecycle

Datalevin's deployment flexibility allows your application to evolve.

1.  **Prototype with Pods**: Use the Babashka Pod for quick scripts and local experiments.
2.  **Scale with Embedded**: When building your production application, use Embedded mode for maximum performance.
3.  **Expand with Server**: If you need to share data across microservices or teams, "promote" to Server mode.

Because the underlying data format is identical in all modes, you can move between patterns without data migration.
