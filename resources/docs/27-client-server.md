---
title: "Client-Server Architecture: Protocols, HA, and Security"
chapter: 27
part: "VI — Systems and Operations"
---

# Chapter 27: Client-Server Architecture: Protocols, HA, and Security

While Datalevin is often used in embedded mode for maximum performance, its **Client-Server mode** is essential for multi-user environments, microservices architectures, and cross-language integration.

This chapter covers how to set up the Datalevin server, connect from remote clients, manage security through Role-Based Access Control (RBAC), and tune the protocol for high-performance distributed systems.

---

## 1. The Datalevin Server

The Datalevin server is a high-performance, non-blocking network service that exposes the Datalog and KV APIs over a TCP connection.

### 1.1 Starting the Server
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

### 1.2 Under the Hood: The Wire Protocol
Datalevin uses a custom wire protocol designed for efficiency:
- **Asynchronous**: The server uses an event-driven architecture to handle thousands of concurrent connections.
- **Message Format**: A TLV (Type-Length-Value) format inspired by PostgreSQL.
- **Serialization**: The default serialization is **Nippy**, which is extremely fast for Clojure-to-Clojure communication. For other languages, **Transit+JSON** is used.
- **JSON API**: Datalevin also has a language-neutral JSON command API with handle-based sessions, Datalog/KV operations, admin operations, search, and vector support. The Python and Node bindings use JVM interop today, but the JSON API is the stable shape exposed to MCP and other adapters that need machine-readable request and response payloads.

---

## 2. Connecting to the Server

Datalevin provides a unified connection API. The same `d/get-conn` function used for local databases works for remote ones by providing a URI.

### 2.1 Connection URIs
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

### 2.2 The `datalevin.client` Namespace
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

## 3. Security: Role-Based Access Control (RBAC)

Datalevin Server includes a sophisticated RBAC system to ensure data security in shared environments.

### 3.1 Permission Hierarchy
Permissions are hierarchical:
- `:view`: Can read data.
- `:alter`: Can read and write data.
- `:create`: Can create new databases.
- `:control`: Full administrative access (can manage users/roles).

### 3.2 Managing Users and Roles
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

### 3.3 Default Credentials
The server initializes with a default admin:
- **Username**: `datalevin`
- **Password**: `datalevin` (or set via `DATALEVIN_DEFAULT_PASSWORD` env var).

**Security Warning**: Always change the default password immediately after installation.
When the server binds to a non-loopback address, `DATALEVIN_DEFAULT_PASSWORD` is required at startup.

---

## 4. Client-Side Tuning Knobs

These parameters control how the client interacts with the server and can be tuned via dynamic variables or client options.

### 4.1 Freshness and Latency
- **`datalevin.constants/*remote-db-last-modified-check-interval-ms*`**: 
    - Sets the interval for checking if the remote DB has changed (default: `0`).
    - `0` ensures strict freshness (check on every call); higher values reduce network round trips.

### 4.2 Connection Pooling
Passed as a map to `datalevin.client/new-client`:
- **`:pool-size`**: Maximum pooled connections per client instance (default: `3`).
- **`:time-out`**: Timeout in milliseconds for obtaining a connection and retrying requests (default: `60000`).
- **`:ha-write-retry-timeout-ms`**: Extra bounded retry budget for retryable HA write failover.
- **`:ha-write-retry-delay-ms`**: Delay between HA write retry rounds.

### 4.3 Wire Compression (zstd)
These affect the protocol payload size and CPU usage:
- **`datalevin.constants/*wire-compression-threshold*`**: Minimum payload size in bytes before attempting compression (default: `8192`).
- **`datalevin.constants/*wire-compression-level*`**: The zstd compression level (default: `3`).

---

## 5. High Availability and Replica Reads

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

---

## 6. Runtime UDFs in Server Mode

Descriptor-backed `:db/udf` functions resolve in the runtime where the query or transaction executes. For embedded databases, pass a runtime registry in store options. For remote transactions, the UDF registry must be installed in the server process; client-local registries are not consulted.

This distinction matters in HA deployments. If `:ha-require-udf-ready? true` is set, a leader rejects writes until all installed `:db/udf` transaction descriptors can be resolved by the server runtime.

---

## 7. Performance Considerations

### 7.1 Latency and Batching
Every network call introduces latency. While `d/q` and `d/transact!` are optimized, frequent small operations can be slow over a network.
- **Batching**: Use `d/transact!` with multiple datoms or `d/transact-async` to reduce the number of round trips.
- **Pull**: Use the `d/pull` API to fetch large entity trees in a single request rather than performing multiple lookups.

### 7.2 Serialization Overheads
While Nippy is fast, serializing large result sets can still take time. Ensure your queries are specific (`:find` only what you need) to minimize the amount of data sent over the wire.

---

## Summary: Scaling with Datalevin Server

The Client-Server architecture allows Datalevin to move beyond a single-process library into a centralized data hub for your entire infrastructure. By combining the speed of the underlying engine with a robust network protocol and security model, Datalevin provides a bridge between the simplicity of SQLite and the power of enterprise databases.
