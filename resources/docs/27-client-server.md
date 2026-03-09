---
title: "Client-Server Architecture: Protocols and Security"
chapter: 27
part: "VI — Systems and Operations"
---

# Chapter 27: Client-Server Architecture: Protocols and Security

While Datalevin is often used in embedded mode for maximum performance, its **Client-Server mode** is essential for multi-user environments, microservices architectures, and cross-language integration.

This chapter covers how to set up the Datalevin server, connect from remote clients, manage security through Role-Based Access Control (RBAC), and tune the protocol for high-performance distributed systems.

---

## 1. The Datalevin Server

The Datalevin server is a high-performance, non-blocking network service that exposes the Datalog and KV APIs over a TCP connection.

### 1.1 Starting the Server
You can start the server using the native `dtlv` CLI or via the JVM uberjar.

```console
# Using the native CLI tool
$ dtlv serv -r /data/datalevin -p 8898

# Using JVM uberjar (recommended for production)
$ java --add-opens=java.base/java.nio=ALL-UNNAMED \
       --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
       -jar datalevin-standalone.jar serv -r /data/datalevin
```

- **`-r <path>`**: The root directory for data storage (Default Posix: `/var/lib/datalevin`, Windows: `C:\ProgramData\Datalevin`).
- **`-p <port>`**: The port to listen on (default: `8898`).
- **`-b <host>`**: The address to bind to (default: `localhost`).
- **`-v`**: Enable verbose server debug logs.
- **`--idle-timeout <ms>`**: Disconnect inactive sessions to reclaim resources (default: `172800000` ms, or 48 hours).

### 1.2 Under the Hood: The Wire Protocol
Datalevin uses a custom wire protocol designed for efficiency:
- **Asynchronous**: The server uses an event-driven architecture to handle thousands of concurrent connections.
- **Message Format**: A TLV (Type-Length-Value) format inspired by PostgreSQL.
- **Serialization**: The default serialization is **Nippy**, which is extremely fast for Clojure-to-Clojure communication. For other languages, **Transit+JSON** is used.

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
import datalevin.core.*;

// Connect to a remote Datalog database
Connection conn = Datalevin.getConn("dtlv://admin:pass@localhost:8898/mydb");

// Use it just like a local connection
conn.transact(List.of(Map.of("name", "Alice")));
```

```python
import datalevin as d

# Connect to a remote Datalog database
conn = d.get_conn("dtlv://admin:pass@localhost:8898/mydb")

# Use it just like a local connection
d.transact(conn, [{"name": "Alice"}])
```

```javascript
import { Datalevin } from 'datalevin';
const d = new Datalevin();

// Connect to a remote Datalog database
const conn = d.getConn('dtlv://admin:pass@localhost:8898/mydb');

// Use it just like a local connection
d.transact(conn, [{ name: 'Alice' }]);
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
import datalevin.client.*;

// Create an administrative client
Client client = DatalevinClient.newClient("dtlv://admin:pass@localhost:8898");
```

```python
from datalevin import Client

# Create an administrative client
client = Client("dtlv://admin:pass@localhost:8898")
```

```javascript
import { Client } from 'datalevin';

// Create an administrative client
const client = new Client('dtlv://admin:pass@localhost:8898');
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

### 4.3 Wire Compression (zstd)
These affect the protocol payload size and CPU usage:
- **`datalevin.constants/*wire-compression-threshold*`**: Minimum payload size in bytes before attempting compression (default: `8192`).
- **`datalevin.constants/*wire-compression-level*`**: The zstd compression level (default: `3`).

---

## 5. Performance Considerations

### 5.1 Latency and Batching
Every network call introduces latency. While `d/q` and `d/transact!` are optimized, frequent small operations can be slow over a network.
- **Batching**: Use `d/transact!` with multiple datoms or `d/transact-async` to reduce the number of round trips.
- **Pull**: Use the `d/pull` API to fetch large entity trees in a single request rather than performing multiple lookups.

### 5.2 Serialization Overheads
While Nippy is fast, serializing large result sets can still take time. Ensure your queries are specific (`:find` only what you need) to minimize the amount of data sent over the wire.

---

## Summary: Scaling with Datalevin Server

The Client-Server architecture allows Datalevin to move beyond a single-process library into a centralized data hub for your entire infrastructure. By combining the speed of the underlying engine with a robust network protocol and security model, Datalevin provides a bridge between the simplicity of SQLite and the power of enterprise databases.
