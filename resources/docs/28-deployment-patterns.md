---
title: "Deployment Patterns: Embedded, Server, Replicas, HA, MCP, and Pods"
chapter: 28
part: "VI — Systems and Operations"
---

# Chapter 28: Deployment Patterns: Embedded, Server, Replicas, HA, MCP, and Pods

Datalevin is designed to be highly portable. Whether you are building a small CLI tool or a massive distributed system, you can deploy Datalevin in the mode that best fits your operational requirements.

This chapter explores the architectural trade-offs of the primary deployment modes: **Embedded**, **Server**, **Async Read Replica**, **HA Server**, **MCP**, and **Babashka Pods**.

---

## 1. Embedded Mode: Maximum Performance

Embedded mode is the primary way to use Datalevin. You include it as a standard library in your application.

- **Architecture**: Your application and Datalevin share the same process and address space.
- **Performance**: This is the fastest possible deployment. Because Datalevin uses memory-mapping (mmap), your application reads data directly from the OS Page Cache with **zero-copy overhead**.
- **Operational Simplicity**: No separate database process to manage, monitor, or secure. The database file is just another part of your application's state.
- **Ops Note**: On small single-node VMs, leave RAM headroom for the OS page cache and provision at least **1 GB of swap** so brief memory spikes do not immediately kill the process.

**Best for**:
- High-performance, single-node services.
- Containerized applications where each container has its own volume.
- Desktop applications or local tools.

---

## 2. Server Mode: Centralized Management

Server mode (detailed in **Chapter 26**) is used when you need multiple services to share a single Datalevin instance, centralized RBAC, or remote access over `dtlv://` URIs.

- **Architecture**: A standalone `dtlv` server process manages the storage and provides a network API.
- **Benefits**: Centralized backup management, fine-grained RBAC, and the ability to share a single database across microservices.
- **Trade-offs**: Network latency and serialization overhead (Nippy/Transit).

**Best for**:
- Microservices architectures where data must be shared.
- Shared development environments for teams.
- Non-JVM applications needing Datalog or KV storage.

---

## 3. Non-HA Async Read Replicas

Async read replicas are server databases opened with `:replica/read-only? true` and a `:replica/source` pointing at a primary `dtlv://` database. They are separate from consensus HA.

- **Architecture**: One primary writer database with WAL enabled, plus one or more read-only replica databases that bootstrap from primary copy and then tail durable WAL records.
- **Benefits**: Read scaling, geographic or workload isolation for queries, and simpler operations than a consensus cluster.
- **Trade-offs**: Eventually consistent reads, no automatic promotion, no fencing, no quorum, and no write failover.

**Best for**:
- Read-heavy services where stale-by-lag reads are acceptable.
- Reporting, analytics, or search-serving nodes that should not accept writes.
- Teams that want read capacity without operating consensus HA.

---

## 4. HA Server Mode: Leader, Followers, and Failover

Consensus-lease HA is the operational mode for services that need automatic write-leader promotion and read-capable followers.

- **Architecture**: One write leader per database, follower replicas that replay WAL records, and a Raft-backed control plane for leases, terms, membership, and promotion decisions.
- **Benefits**: Automatic leader promotion, follower read capacity, WAL/snapshot-based rejoin, and explicit fail-closed behavior when membership, clock skew, lag, or fencing is unsafe.
- **Trade-offs**: Explicit operator-managed membership updates, required fencing hooks, quorum operational discipline, and no multi-leader writes.

**Best for**:
- Production services that need automatic failover.
- Read-heavy systems that benefit from follower reads.
- Operator-managed clusters where safety is more important than accepting writes during uncertainty.

---

## 5. MCP Mode: Tool Surface for AI Applications

`dtlv mcp` runs a local MCP server over `stdio`. It is a process adapter over Datalevin APIs and can open local databases or remote `dtlv://` targets.

- **Architecture**: A local MCP client launches `dtlv mcp`; Datalevin handles database operations behind the MCP tool calls.
- **Safety**: Read-only tools are the default. Write tools require `dtlv --allow-writes mcp`.
- **Scope**: MCP handle ids are session-scoped, and responses include structured payloads suitable for machine use.

**Best for**:
- AI applications that need controlled read access to Datalevin.
- Local tooling that wants a stable JSON-shaped tool surface.
- Workflows that should use the same database APIs without embedding application-specific client code.

---

## 6. Babashka Pods: Rapid Scripting

Babashka is a fast-starting Clojure interpreter for scripting. Datalevin provides a **Pod** that allows you to use its Datalog and KV capabilities within a script without the overhead of the full JVM startup.

- **Architecture**: The Pod runs as a separate process and communicates with Babashka via IPC.
- **Performance**: High (IPC overhead is minimal compared to network), but lacks the zero-copy speed of the embedded mode.
- **Convenience**: Single-binary installation and instantaneous startup.

**Best for**:
- CLI tools that store state.
- Database maintenance and migration scripts.
- Ephemeral tasks or "Lambda"-style functions.

---

## 7. Native Language Libraries

Datalevin also publishes embedded libraries for several host languages:

- **Clojure**: `datalevin/datalevin` on Clojars, plus `org.datalevin/datalevin-embedded` for embedded-only JVM use.
- **Java**: `org.datalevin:datalevin-java` on Maven Central.
- **Python**: `datalevin` on PyPI.
- **Node.js**: `datalevin-node` on npm.

These packages are still embedded runtimes. They are excellent when a Java, Python, or Node application owns a local database path, or when the language wrapper is used as a remote Datalevin client.

---

## 8. Architectural Comparison Table

| Feature | Embedded | Server | Async Read Replica | HA Server | MCP | Babashka Pod |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Communication** | Direct calls | TCP | TCP + WAL tailing | TCP + replication/control plane | stdio | IPC |
| **Overhead** | Lowest | Network serialization | Network + replica lag | Network + HA coordination | Tool-call serialization | IPC |
| **Security** | OS/file permissions | RBAC | RBAC + server-side write rejection | RBAC + HA fencing | Read-only by default | OS-level |
| **Writes** | Local single-writer storage path; WAL can improve concurrent caller throughput | Multi-client, server-coordinated | Primary only; rejected on replica | One leader per database | Disabled unless allowed | Local process path |
| **Reads** | Local zero-copy | Remote client reads | Replica reads, eventually consistent | Leader or follower reads | Local or remote target | Pod process |
| **Tech Stack** | Clojure, Java, Python, Node | Datalevin clients | Datalevin clients | Datalevin clients | MCP clients | Babashka |

---

## 9. Case Study: High-Density Multi-Tenancy

A powerful but less common deployment pattern is **high-density multi-tenancy**. Instead of a single massive database for all users, you create a separate Datalevin database for every user or workspace.

### The PKM Pattern
One known use case of Datalevin is in **Personal Knowledge Management (PKM)** systems. Some PKM companies deploy thousands of individual Datalevin databases on a single physical machine. In this architecture, each user or workspace gets its own dedicated database file.

### Why It Works
Datalevin is exceptionally lightweight because it is built on LMDB, which uses memory-mapped files (mmap). 

- **Minimal Overhead**: The memory overhead for an idle Datalevin instance is nearly zero. The OS kernel manages the page cache across all database files, ensuring that only the active data for active users resides in physical RAM.
- **Strict Isolation**: Because each user has a physically separate database, there is zero risk of "cross-talk" or accidental data leakage between tenants at the database level.
- **Operational Simplicity**: Tasks like per-user backups, data migrations, or even "deleting an account" become simple file-system operations. You don't need complex `WHERE user_id = ?` logic for every single query.
- **Local-First Synchronization**: The same database file used on the server can be transferred to a user's local device and opened with the same Datalevin library, making it an ideal choice for local-first applications that require robust offline capabilities.

---

## Summary: Designing for Your Lifecycle

Datalevin's deployment flexibility allows your application to evolve.

1.  **Prototype with Pods**: Use the Babashka Pod for quick scripts and local experiments.
2.  **Scale with Embedded**: When building your production application, use Embedded mode for maximum performance.
3.  **Expose with MCP**: For AI tools, expose a read-only local tool surface before adding write tools.
4.  **Expand with Server**: If you need to share data across microservices or teams, "promote" to Server mode.
5.  **Add read replicas**: If reads need isolation or capacity but writes can stay on one primary, add non-HA async read-only replicas.
6.  **Harden with HA**: If the server becomes critical infrastructure and needs automatic failover, move selected databases to consensus-lease HA.

Because the underlying data format is identical in all modes, you can move between patterns without data migration.
