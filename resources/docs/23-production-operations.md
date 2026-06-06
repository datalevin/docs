---
title: "Client-Server, Security, Deployment, and Production Operations"
chapter: 23
part: "V — Performance and Operations"
---

# Chapter 23: Client-Server, Security, Deployment, and Production Operations

Production Datalevin work combines protocol choices, access control, deployment topology, and day-two operations. This chapter collects those operational concerns in one place.

---

## 1. Client-Server Architecture

While Datalevin is often used in embedded mode for maximum performance, its **Client-Server mode** is essential for multi-user environments, microservices architectures, and cross-language integration.

This section covers how to set up the Datalevin server, connect from remote clients, manage security through Role-Based Access Control (RBAC), and tune the protocol for high-performance distributed systems.

---

### 1. The Datalevin Server

The Datalevin server is a high-performance, non-blocking network service that exposes the Datalog and KV APIs over a TCP connection.

#### 1.1 Starting the Server
You can start the server using the native `dtlv` CLI or via the JVM uberjar.

```console
# Using the native CLI tool
$ DATALEVIN_DEFAULT_PASSWORD=secret dtlv serv -r /data/datalevin -p 8898

# Using JVM uberjar (recommended for production)
$ DATALEVIN_DEFAULT_PASSWORD=secret \
  java --add-opens=java.base/java.nio=ALL-UNNAMED \
       --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
       -jar datalevin-0.10.15-standalone.jar serv --host 0.0.0.0 -r /data/datalevin
```

- **`-r <path>`**: The root directory for data storage (Default Posix: `/var/lib/datalevin`, Windows: `C:\ProgramData\Datalevin`).
- **`-p <port>`**: The port to listen on (default: `8898`).
- **`--host <host>`**: The address to bind to (default: `127.0.0.1`). Binding to a non-loopback address requires `DATALEVIN_DEFAULT_PASSWORD`.
- **`-v`**: Enable verbose server debug logs.
- **`--idle-timeout <ms>`**: Disconnect inactive sessions to reclaim resources (default: `172800000` ms, or 48 hours).

#### 1.2 Under the Hood: The Wire Protocol
Datalevin uses a custom wire protocol designed for efficiency:
- **Asynchronous**: The server uses an event-driven architecture to handle thousands of concurrent connections.
- **Message Format**: A TLV (Type-Length-Value) format inspired by PostgreSQL.
- **Serialization**: The default serialization is **Nippy**, which is extremely fast for Clojure-to-Clojure communication. For other languages, **Transit+JSON** is used.
- **JSON API**: Datalevin also has a language-neutral JSON command API with handle-based sessions, Datalog/KV operations, admin operations, search, and vector support. The Python and Node bindings use JVM interop today, but the JSON API is the stable shape exposed to MCP and other adapters that need machine-readable request and response payloads.

---

### 2. Connecting to the Server

Datalevin provides a unified connection API. The same `d/get-conn` function used for local databases works for remote ones by providing a URI.

#### 2.1 Connection URIs
The URI format follows this pattern:
`dtlv://<user>:<pass>@<host>:<port>/<db-name>?store=datalog|kv`

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

;; Connect to a remote Datalog database
(def conn (d/get-conn "dtlv://admin:pass@localhost:8898/mydb"))

;; Use it just like a local connection
(d/transact! conn [{:name "Alice"}])
```

```java
import datalevin.Connection;
import datalevin.Datalevin;

// Connect to a remote Datalog database
Connection conn = Datalevin.getConn("dtlv://admin:pass@localhost:8898/mydb");

// Use it just like a local connection
conn.transact(List.of(Map.of("name", "Alice")));
```

```python
from datalevin import connect

# Connect to a remote Datalog database
conn = connect("dtlv://admin:pass@localhost:8898/mydb")

# Use it just like a local connection
conn.transact([{"name": "Alice"}])
```

```javascript
import { connect } from "datalevin-node";

// Connect to a remote Datalog database
const conn = await connect("dtlv://admin:pass@localhost:8898/mydb");

// Use it just like a local connection
await conn.transact([{ name: "Alice" }]);
```

</div>

- **`store`**: Specifies the storage engine. Options are `datalog` (default) or `kv`.
- **`db-name`**: Must be unique across the entire server instance.

#### 2.2 The `datalevin.client` Namespace
For administrative tasks (managing users, roles, and databases), use the `datalevin.client` namespace.

<div class="multi-lang">

```clojure
(require '[datalevin.client :as c])

;; Create an administrative client
(def client (c/new-client "dtlv://admin:pass@localhost:8898"))
```

```java
import datalevin.Client;
import datalevin.Datalevin;

// Create an administrative client
Client client = Datalevin.newClient("dtlv://admin:pass@localhost:8898");
```

```python
from datalevin import new_client

# Create an administrative client
client = new_client("dtlv://admin:pass@localhost:8898")
```

```javascript
import { newClient } from "datalevin-node";

// Create an administrative client
const client = await newClient("dtlv://admin:pass@localhost:8898");
```

</div>

---

### 3. Security: Role-Based Access Control (RBAC)

Datalevin Server includes a sophisticated RBAC system to ensure data security in shared environments.

#### 3.1 Permission Hierarchy
Permissions are hierarchical:
- `:view`: Can read data.
- `:alter`: Can read and write data.
- `:create`: Can create new databases.
- `:control`: Full administrative access (can manage users/roles).

#### 3.2 Managing Users and Roles
A typical security workflow involves creating a role with specific permissions and assigning it to a user.

<div class="multi-lang">

```clojure
;; 1. Create a user
(c/create-user client "alice" "password123")

;; 2. Create a role
(c/create-role client "analyst")

;; 3. Grant permission to the role for a specific database
(c/grant-permission client "analyst" :datalevin.server/view "sales-db")

;; 4. Assign the role to the user
(c/assign-role client "alice" "analyst")
```

```java
// 1. Create a user
client.createUser("alice", "password123");

// 2. Create a role
client.createRole("analyst");

// 3. Grant permission to the role for a specific database
client.grantPermission("analyst", "datalevin.server/view", "sales-db");

// 4. Assign the role to the user
client.assignRole("alice", "analyst");
```

```python
# 1. Create a user
client.create_user("alice", "password123")

# 2. Create a role
client.create_role("analyst")

# 3. Grant permission to the role for a specific database
client.grant_permission("analyst", "datalevin.server/view", "sales-db")

# 4. Assign the role to the user
client.assign_role("alice", "analyst")
```

```javascript
// 1. Create a user
client.createUser('alice', 'password123');

// 2. Create a role
client.createRole('analyst');

// 3. Grant permission to the role for a specific database
client.grantPermission('analyst', 'datalevin.server/view', 'sales-db');

// 4. Assign the role to the user
client.assignRole('alice', 'analyst');
```

</div>

#### 3.3 Default Credentials
The server initializes with a default admin:
- **Username**: `datalevin`
- **Password**: `datalevin` (or set via `DATALEVIN_DEFAULT_PASSWORD` env var).

**Security Warning**: Always change the default password immediately after installation.
When the server binds to a non-loopback address, `DATALEVIN_DEFAULT_PASSWORD` is required at startup.

---

### 4. Client-Side Tuning Knobs

These parameters control how the client interacts with the server and can be tuned via dynamic variables or client options.

#### 4.1 Freshness and Latency
- **`datalevin.constants/*remote-db-last-modified-check-interval-ms*`**: 
    - Sets the interval for checking if the remote DB has changed (default: `0`).
    - `0` ensures strict freshness (check on every call); higher values reduce network round trips.

#### 4.2 Connection Pooling
Passed as a map to `datalevin.client/new-client`:
- **`:pool-size`**: Maximum pooled connections per client instance (default: `3`).
- **`:time-out`**: Timeout in milliseconds for obtaining a connection and retrying requests (default: `60000`).
- **`:ha-write-retry-timeout-ms`**: Extra bounded retry budget for retryable HA write failover.
- **`:ha-write-retry-delay-ms`**: Delay between HA write retry rounds.

#### 4.3 Wire Compression (zstd)
These affect the protocol payload size and CPU usage:
- **`datalevin.constants/*wire-compression-threshold*`**: Minimum payload size in bytes before attempting compression (default: `8192`).
- **`datalevin.constants/*wire-compression-level*`**: The zstd compression level (default: `3`).

---

### 5. Replication and High Availability

Datalevin server has two different replication paths: non-HA async read replicas for read scaling, and consensus-lease HA for automatic leader promotion and follower reads.

#### 5.1 Non-HA Async Read Replicas

Use a non-HA async read replica when you want one primary writer server and one or more read-only servers, but do not need leader election, quorum, fencing, automatic promotion, or write failover.

The primary database must have WAL enabled. `:wal-durability-profile :strict` is recommended because the replica sync loop tails records up to the source durable LSN.

Configure the replica when opening the database on the replica server:

```clojure
(require '[datalevin.core :as d]
         '[datalevin.client :as cl])

(def schema
  {:name {:db/valueType :db.type/string
          :db/cardinality :db.cardinality/one}})

(def replica-conn
  (d/create-conn
    "dtlv://replica-admin:pass@replica-host:8898/app"
    schema
    {:replica/read-only? true
     :replica/source "dtlv://replicator:pass@primary-host:8898/app"
     :replica/id "app-replica-us-west"
     :replica/poll-ms 250
     :replica/report-ms 5000
     :wal? true
     :wal-durability-profile :strict}))
```

On first open, if the local replica database does not exist, Datalevin bootstraps it from the primary copy interface. After that, the replica tails source WAL records in LSN order and periodically reports its applied LSN back to the primary so WAL garbage collection preserves needed segments. If the local replica database already exists, it is reused; remove that local database directory to force a fresh bootstrap.

Replica reads use the normal APIs against the replica URI. User writes are rejected by the replica server, even if the authenticated user otherwise has write permission:

```clojure
(d/q '[:find [?name ...] :where [?e :name ?name]] @replica-conn)

;; Throws: replica is read-only
(d/transact! replica-conn [{:db/id -1 :name "blocked"}])
```

The source user in `:replica/source` must be allowed to copy/open the primary database, read the transaction log, and update the replica floor on the primary. In practice, grant that replicator user database `:datalevin.server/alter` permission. The user opening the local replica needs permission to create or open the database on the replica server.

Replica status is available through `datalevin.client`:

```clojure
(def client
  (cl/new-client "dtlv://replica-admin:pass@replica-host:8898"))

(cl/replica-status client "app")
```

The returned map includes `:replica/read-only?`, `:replica/source`, `:replica/id`, `:replica-applied-lsn`, `:replica-source-durable-lsn`, `:replica-source-committed-lsn`, `:replica-lag-lsn`, `:replica-last-sync-ms`, `:replica-degraded-reason`, and `:replica-last-error`.

#### 5.2 Consensus-Lease HA and Follower Reads

Datalevin server supports consensus-lease HA. Each HA database has exactly one write leader at a time. Followers replicate from the leader using WAL records and snapshots, and can serve reads.

The HA design has three layers:

- **Data plane**: Leader writes append to WAL; followers copy and replay records in order. If a follower falls too far behind retained WAL, it bootstraps from a snapshot and resumes replay.
- **Control plane**: A Raft-backed consensus group stores the current lease owner, term, leader endpoint, membership hash, and leader LSN.
- **Local runtime state**: Each database on each node is a `:follower`, `:candidate`, `:leader`, or `:demoting`.

Writes are admitted only when the local node has fresh proof that it is the current leader and not demoting. Promotion is conservative: candidates check membership, lag, lease state, and fencing before accepting writes. HA forces `:wal? true` and defaults to `:wal-durability-profile :strict`.

Clients do not need a separate replica API. Connect to a follower endpoint for follower reads:

```clojure
(def replica
  (d/get-conn "dtlv://user:pass@replica-host:8898/app"))
```

Use RBAC for enforced read-only access: grant only `:datalevin.server/view`. Ordinary one-shot writes can retry across known HA endpoints when a node responds with retryable states such as `:not-leader`. Explicit remote write transactions can retry while opening, but once opened they remain pinned to the server session that accepted them.

#### 5.3 Operator-Driven HA Membership Updates

Consensus HA membership is operator managed. Datalevin does not discover data nodes automatically, but an administrator can update the authoritative membership for an open HA database with `datalevin.client/ha-update-membership!`.

```clojure
(require '[datalevin.client :as cl])

(def client
  (cl/new-client "dtlv://admin:pass@node-a:8898"))

(cl/ha-update-membership!
  client
  "app"
  {:ha-members
   [{:node-id 1 :endpoint "node-a:8898"}
    {:node-id 2 :endpoint "node-b:8898"}
    {:node-id 3 :endpoint "node-c-new:8898"}]
   :ha-control-plane
   {:voters [{:peer-id "node-a:9098" :promotable? true :ha-node-id 1}
             {:peer-id "node-b:9098" :promotable? true :ha-node-id 2}
             {:peer-id "node-c-new:9098" :promotable? true :ha-node-id 3}]}})
```

The request validates the proposed `:ha-members` and control-plane voters, persists the new HA options on the target server, optionally replaces the Raft voter set, CAS-updates the authoritative membership hash, clears existing leases by default, and restarts that server's local HA runtime. The caller needs database alter permission plus server control permission, and the request must be issued outside `with-transaction`.

Useful request keys are:

- **`:ha-members`**: Replacement data-node member list.
- **`:ha-control-plane {:voters [...]}`** or **`:ha-control-plane-voters`**: Replacement control-plane voters. This live API only changes the voter list, not other control-plane settings.
- **`:expected-membership-hash`**: Optional CAS guard. If omitted, the server reads the current authoritative hash before applying the update.
- **`:replace-voters?`**: Defaults to `true`; set to `false` only when the control-plane voter set was changed separately.
- **`:clear-leases?`**: Defaults to `true`; this forces write leadership to be reacquired under the new membership.
- **`:timeout-ms`**: Optional control-plane operation timeout.

Run the same update on each surviving or newly staged server, or otherwise open those servers with the same new HA options, so local persisted options match the authoritative hash. A node whose local membership hash does not match the authoritative hash fails closed and will not promote or admit writes until the mismatch is resolved. Repeating the same desired update is idempotent and can be used to persist local options on nodes staged before the first cluster-wide hash update.

---

### 6. Runtime UDFs in Server Mode

Descriptor-backed `:db/udf` functions resolve in the runtime where the query or transaction executes. For embedded databases, pass a runtime registry in store options. For remote transactions, the UDF registry must be installed in the server process; client-local registries are not consulted.

This distinction matters in HA deployments. If `:ha-require-udf-ready? true` is set, a leader rejects writes until all installed `:db/udf` transaction descriptors can be resolved by the server runtime.

---

### 7. Performance Considerations

#### 7.1 Latency and Batching
Every network call introduces latency. While `d/q` and `d/transact!` are optimized, frequent small operations can be slow over a network.
- **Batching**: Use `d/transact!` with multiple datoms or `d/transact-async` to reduce the number of round trips.
- **Pull**: Use the `d/pull` API to fetch large entity trees in a single request rather than performing multiple lookups.

#### 7.2 Serialization Overheads
While Nippy is fast, serializing large result sets can still take time. Ensure your queries are specific (`:find` only what you need) to minimize the amount of data sent over the wire.

---

### Summary: Scaling with Datalevin Server

The Client-Server architecture allows Datalevin to move beyond a single-process library into a centralized data hub for your entire infrastructure. By combining the speed of the underlying engine with a robust network protocol and security model, Datalevin provides a bridge between the simplicity of SQLite and the power of enterprise databases.

---

## 2. Encryption and Security Models

Security is a multi-layered responsibility. While Datalevin provides robust tools for access control, protecting the physical data requires a strategy that spans from the hardware up to the application code.

This section provides guidance on how to secure your Datalevin data at rest and how to use the built-in Role-Based Access Control (RBAC) in Datalevin Server.

---

### 1. Encryption at Rest: A Layered Approach

Datalevin does not currently provide database-level encryption (e.g., TDE). Instead, it follows the industry-standard recommendation of securing data at the layers where encryption is most efficient and manageable.

#### 1.1 The Security Stack
To protect your data at rest, consider encryption at these levels, from bottom to top:

1.  **Hardware**: Use hardware-encrypted drives or Hardware Security Modules (HSMs) for key management.
2.  **Disk/Volume**: This is the most common and recommended starting point.
    *   **On-Premise**: Use **LUKS** (Linux) or **FileVault** (macOS).
    *   **Cloud**: Use managed encryption like **AWS EBS encryption**, **GCP Persistent Disk encryption**, or **Azure Disk Encryption**. These handles key rotation and management for you with zero performance overhead.
3.  **File System**: Use tools like `fscrypt` to encrypt specific directories at the OS level.
4.  **Application Level**: For extremely sensitive fields (e.g., PII, credit card numbers), encrypt the data in your application code before sending it to Datalevin.

#### 1.2 Multi-Tenancy and Envelope Encryption
If you are building a SaaS with multiple tenants, you may need **Envelope Encryption**.
- Use a cloud-managed service like **AWS KMS** or **GCP KMS** to manage per-tenant "Data Encryption Keys" (DEKs).
- Your application encrypts the sensitive fields with a tenant's DEK before transacting them as a blob or string into Datalevin.

---

### 2. Security Models: Datalevin Server RBAC

When running in server mode, Datalevin provides a comprehensive **Role-Based Access Control (RBAC)** system to ensure that only authorized users can query or modify specific data.

#### 2.1 Users and Roles
- **Users**: Access is secured by a username and a salted/hashed password.
- **Default User**: Every server has a default administrative user named `datalevin`. Set `DATALEVIN_DEFAULT_PASSWORD` when starting the server; it is required when binding to a non-loopback address and should be treated as mandatory in production.
- **Roles**: Permissions are granted to roles, which are then assigned to users. Every user also has a built-in private role named `:datalevin.role/<username>`.

#### 2.2 The Permission Triple: Act, Obj, Tgt
Permissions in Datalevin are defined by three components:

1.  **Action (`act`)**: What the user can do.
    *   `:datalevin.server/view`: Read-only access (query, pull).
    *   `:datalevin.server/alter`: Modify data (transact).
    *   `:datalevin.server/create`: Create new databases or users.
    *   `:datalevin.server/control`: Full administrative control.
2.  **Object (`obj`)**: What type of thing they are acting on (e.g., `:datalevin.server/database`, `:datalevin.server/user`).
3.  **Target (`tgt`)**: The specific name of the database, user, or role. Use `nil` to target all objects of that type.

#### 2.3 Managing Access via REPL
Administrative tasks are performed via the server REPL:

```clojure
;; 1. Create a new user
(create-user "alice" "secure-password")

;; 2. Grant 'view' permission on a specific database to Alice
(grant-permission :datalevin.role/alice 
                  :datalevin.server/view 
                  :datalevin.server/database 
                  "prod-db")

;; 3. Create a custom 'editor' role and assign it to Alice
(create-role :app.role/editor)
(grant-permission :app.role/editor :datalevin.server/alter :datalevin.server/database "prod-db")
(assign-role "alice" :app.role/editor)
```

---

### 3. Summary: Security Best Practices

1.  **Start with Cloud Disk Encryption**: If you are on AWS/GCP/Azure, enable volume encryption for your database storage.
2.  **Change the Default Admin Password**: Never leave the `datalevin` user with its default credentials.
3.  **Principle of Least Privilege**: Create specific users for your applications and grant them only the `:view` or `:alter` permissions they need for specific databases.
4.  **Use Application-Level Encryption for PII**: Don't rely on the database to protect highly sensitive fields; encrypt them before they leave your application server.

By combining infrastructure-level encryption with Datalevin's granular RBAC, you can build systems that are both highly functional and securely defended.

---

## 3. Deployment Patterns

Datalevin is designed to be highly portable. Whether you are building a small CLI tool or a massive distributed system, you can deploy Datalevin in the mode that best fits your operational requirements.

This section explores the architectural trade-offs of the primary deployment modes: **Embedded**, **Server**, **Async Read Replica**, **HA Server**, **MCP**, and **Babashka Pods**.

---

### 1. Embedded Mode: Maximum Performance

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

### 2. Server Mode: Centralized Management

Server mode is used when you need multiple services to share a single Datalevin instance, centralized RBAC, or remote access over `dtlv://` URIs.

- **Architecture**: A standalone `dtlv` server process manages the storage and provides a network API.
- **Benefits**: Centralized backup management, fine-grained RBAC, and the ability to share a single database across microservices.
- **Trade-offs**: Network latency and serialization overhead (Nippy/Transit).

**Best for**:
- Microservices architectures where data must be shared.
- Shared development environments for teams.
- Non-JVM applications needing Datalog or KV storage.

---

### 3. Non-HA Async Read Replicas

Async read replicas are server databases opened with `:replica/read-only? true` and a `:replica/source` pointing at a primary `dtlv://` database. They are separate from consensus HA.

- **Architecture**: One primary writer database with WAL enabled, plus one or more read-only replica databases that bootstrap from primary copy and then tail durable WAL records.
- **Benefits**: Read scaling, geographic or workload isolation for queries, and simpler operations than a consensus cluster.
- **Trade-offs**: Eventually consistent reads, no automatic promotion, no fencing, no quorum, and no write failover.

**Best for**:
- Read-heavy services where stale-by-lag reads are acceptable.
- Reporting, analytics, or search-serving nodes that should not accept writes.
- Teams that want read capacity without operating consensus HA.

---

### 4. HA Server Mode: Leader, Followers, and Failover

Consensus-lease HA is the operational mode for services that need automatic write-leader promotion and read-capable followers.

- **Architecture**: One write leader per database, follower replicas that replay WAL records, and a Raft-backed control plane for leases, terms, membership, and promotion decisions.
- **Benefits**: Automatic leader promotion, follower read capacity, WAL/snapshot-based rejoin, and explicit fail-closed behavior when membership, clock skew, lag, or fencing is unsafe.
- **Trade-offs**: Explicit operator-managed membership updates, required fencing hooks, quorum operational discipline, and no multi-leader writes.

**Best for**:
- Production services that need automatic failover.
- Read-heavy systems that benefit from follower reads.
- Operator-managed clusters where safety is more important than accepting writes during uncertainty.

---

### 5. MCP Mode: Tool Surface for AI Applications

`dtlv mcp` runs a local MCP server over `stdio`. It is a process adapter over Datalevin APIs and can open local databases or remote `dtlv://` targets.

- **Architecture**: A local MCP client launches `dtlv mcp`; Datalevin handles database operations behind the MCP tool calls.
- **Safety**: Read-only tools are the default. Write tools require `dtlv --allow-writes mcp`.
- **Scope**: MCP handle ids are session-scoped, and responses include structured payloads suitable for machine use.

**Best for**:
- AI applications that need controlled read access to Datalevin.
- Local tooling that wants a stable JSON-shaped tool surface.
- Workflows that should use the same database APIs without embedding application-specific client code.

---

### 6. Babashka Pods: Rapid Scripting

Babashka is a fast-starting Clojure interpreter for scripting. Datalevin provides a **Pod** that allows you to use its Datalog and KV capabilities within a script without the overhead of the full JVM startup.

- **Architecture**: The Pod runs as a separate process and communicates with Babashka via IPC.
- **Performance**: High (IPC overhead is minimal compared to network), but lacks the zero-copy speed of the embedded mode.
- **Convenience**: Single-binary installation and instantaneous startup.

**Best for**:
- CLI tools that store state.
- Database maintenance and migration scripts.
- Ephemeral tasks or "Lambda"-style functions.

---

### 7. Native Language Libraries

Datalevin also publishes embedded libraries for several host languages:

- **Clojure**: `datalevin/datalevin` on Clojars, plus `org.datalevin/datalevin-embedded` for embedded-only JVM use.
- **Java**: `org.datalevin:datalevin-java` on Maven Central.
- **Python**: `datalevin` on PyPI.
- **Node.js**: `datalevin-node` on npm.

These packages are still embedded runtimes. They are excellent when a Java, Python, or Node application owns a local database path, or when the language wrapper is used as a remote Datalevin client.

---

### 8. Architectural Comparison Table

| Feature | Embedded | Server | Async Read Replica | HA Server | MCP | Babashka Pod |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Communication** | Direct calls | TCP | TCP + WAL tailing | TCP + replication/control plane | stdio | IPC |
| **Overhead** | Lowest | Network serialization | Network + replica lag | Network + HA coordination | Tool-call serialization | IPC |
| **Security** | OS/file permissions | RBAC | RBAC + server-side write rejection | RBAC + HA fencing | Read-only by default | OS-level |
| **Writes** | Local single-writer storage path; WAL can improve concurrent caller throughput | Multi-client, server-coordinated | Primary only; rejected on replica | One leader per database | Disabled unless allowed | Local process path |
| **Reads** | Local zero-copy | Remote client reads | Replica reads, eventually consistent | Leader or follower reads | Local or remote target | Pod process |
| **Tech Stack** | Clojure, Java, Python, Node | Datalevin clients | Datalevin clients | Datalevin clients | MCP clients | Babashka |

---

### 9. Case Study: High-Density Multi-Tenancy

A powerful but less common deployment pattern is **high-density multi-tenancy**. Instead of a single massive database for all users, you create a separate Datalevin database for every user or workspace.

#### The PKM Pattern
One known use case of Datalevin is in **Personal Knowledge Management (PKM)** systems. Some PKM companies deploy thousands of individual Datalevin databases on a single physical machine. In this architecture, each user or workspace gets its own dedicated database file.

#### Why It Works
Datalevin is exceptionally lightweight because it is built on LMDB, which uses memory-mapped files (mmap). 

- **Minimal Overhead**: The memory overhead for an idle Datalevin instance is nearly zero. The OS kernel manages the page cache across all database files, ensuring that only the active data for active users resides in physical RAM.
- **Strict Isolation**: Because each user has a physically separate database, there is zero risk of "cross-talk" or accidental data leakage between tenants at the database level.
- **Operational Simplicity**: Tasks like per-user backups, data migrations, or even "deleting an account" become simple file-system operations. You don't need complex `WHERE user_id = ?` logic for every single query.
- **Local-First Synchronization**: The same database file used on the server can be transferred to a user's local device and opened with the same Datalevin library, making it an ideal choice for local-first applications that require robust offline capabilities.

---

### Summary: Designing for Your Lifecycle

Datalevin's deployment flexibility allows your application to evolve.

1.  **Prototype with Pods**: Use the Babashka Pod for quick scripts and local experiments.
2.  **Scale with Embedded**: When building your production application, use Embedded mode for maximum performance.
3.  **Expose with MCP**: For AI tools, expose a read-only local tool surface before adding write tools.
4.  **Expand with Server**: If you need to share data across microservices or teams, "promote" to Server mode.
5.  **Add read replicas**: If reads need isolation or capacity but writes can stay on one primary, add non-HA async read-only replicas.
6.  **Harden with HA**: If the server becomes critical infrastructure and needs automatic failover, move selected databases to consensus-lease HA.

Because the underlying data format is identical in all modes, you can move between patterns without data migration.

---

## 4. Monitoring, Debugging, and Production Checklist

Running Datalevin in production requires a shift from a "developer" mindset to an "operator" mindset. Because Datalevin offloads much of its work to the operating system, monitoring the database often means monitoring the host environment.

This section provides a comprehensive guide to monitoring Datalevin, debugging performance issues, and a checklist for a production-ready deployment.

---

### 1. Monitoring the Environment

Datalevin's performance is tied directly to the health of the host's memory and disk.

#### 1.1 Disk I/O and Latency
Every commit in Datalevin is a synchronous flush to disk (unless using `:nosync`).
- **Monitor**: Disk IOPS and `iowait`.
- **Recommendation**: Use **NVMe SSDs** for the best performance. High-latency block storage (like network-attached drives) will severely limit your write throughput.

#### 1.2 The Page Cache and RSS
Because Datalevin uses memory-mapping, the OS Page Cache is your "buffer pool."
- **Resident Set Size (RSS)**: This shows how much of the database is currently "hot" in RAM.
- **Virtual Size**: This will match your `:mapsize` and is not a cause for concern.
- **Tuning**: Monitor "Page Faults." A high number of major page faults indicates that your working set doesn't fit in RAM, causing the OS to fetch data from disk frequently.

---

### 2. Database Health: `d/db-stats`

You can inspect the internal health of the B+Tree using the `db-stats` function.

<div class="multi-lang">

```clojure
(d/db-stats db)
```

```java
Map<String, Object> stats = Datalevin.dbStats(db);
```

```python
stats = d.db_stats(db)
```

```javascript
const stats = d.dbStats(db);
```

</div>

This returns metrics such as:
- **`branch_pages` / `leaf_pages`**: The structure of your tree.
- **`overflow_pages`**: Pages used for large values.
- **`entries`**: Total number of key-value pairs.

#### 2.1 The Free List
LMDB reuses deleted pages rather than shrinking the file. `db-stats` will show you how many pages are currently in the **Free List**. If this number is very high relative to your total pages, it may be time for a `d/compact` operation (Chapter 20).

---

### 3. Debugging and Profiling

When queries are slow or the system feels sluggish, use these tools to find the bottleneck.

- **Query Analysis**: Use `d/explain` (Chapter 21) to inspect query plans and understand execution strategy.
- **Writer Contention**: If `d/transact!` calls are slow but the disk is idle, check whether multiple threads are competing for the direct LMDB writer path, or whether WAL group-commit settings are mismatched to the workload.
- **Logging**: Use a library like Timbre to log transaction times and query latencies.

---

### 4. The Production Checklist

Before you "go live," ensure your configuration matches these best practices.

#### 4.1 Memory and Storage
- [ ] **`:mapsize`**: Set to at least 2x your expected data size.
- [ ] **`:max-readers`**: Keep the default 1024 unless your bounded worker pool can exceed it.
- [ ] **WAL Mode**: Enable `:wal? true` when you need WAL throughput, replay, replication, or HA behavior.
- [ ] **Durability Profile**: Choose `:strict`, `:relaxed`, or `:extra` based on your safety requirements.
- [ ] **`:nosync`**: Ensure this is **FALSE** (default) for production data safety.

#### 4.2 Operating System Tuning
- [ ] **`vm.swappiness`**: Set to `1` or `10` to prevent the OS from swapping database pages to disk.
- [ ] **`vm.dirty_ratio`**: Adjust to ensure the OS flushes writes to disk consistently.
- [ ] **Transparent Huge Pages (THP)**: Often recommended to be disabled or set to `madvise` for database workloads.

#### 4.3 Operations
- [ ] **Automated Backups**: Use `d/copy` to create transactionally consistent backups without downtime.
- [ ] **Monitoring Hooks**: Use `d/listen!` to track transaction volume and data growth.
- [ ] **Replica Lag**: For non-HA async read replicas, monitor `datalevin.client/replica-status`, especially `:replica-lag-lsn`, `:replica-degraded-reason`, and `:replica-last-error`.
- [ ] **HA Membership Drift**: In consensus HA, keep each node's `:ha-members` and promotable control-plane voter mapping aligned with the authoritative membership hash. Use `datalevin.client/ha-update-membership!` for operator-driven membership changes.
- [ ] **Health Checks**: Implement a `/health` endpoint that performs a simple `(d/q '[:find ?e :limit 1] db)` to verify end-to-end connectivity.

---

### Summary

Datalevin is a "quiet" database. When tuned correctly, it requires very little maintenance. By monitoring the OS Page Cache, keeping your B+Tree compacted, and following the production checklist, you can ensure that your Datalevin deployment remains fast and reliable for years.
