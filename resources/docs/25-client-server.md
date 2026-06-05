---
title: "Client-Server Architecture: Protocols, HA, and Security"
chapter: 25
part: "VI — Systems and Operations"
---

# Chapter 25: Client-Server Architecture: Protocols, HA, and Security

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

## 5. Replication and High Availability

Datalevin server has two different replication paths: non-HA async read replicas for read scaling, and consensus-lease HA for automatic leader promotion and follower reads.

### 5.1 Non-HA Async Read Replicas

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

### 5.2 Consensus-Lease HA and Follower Reads

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

### 5.3 Operator-Driven HA Membership Updates

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
