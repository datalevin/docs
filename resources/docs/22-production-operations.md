---
title: "Deployment and Production Operations"
chapter: 22
part: "V — Performance and Operations"
---

# Chapter 22: Deployment and Production Operations

Chapter 20 covered durability, snapshots, copy, dump/load, and storage tuning.
This chapter focuses on the next layer: how to choose a deployment shape, run
Datalevin as a server when needed, secure remote access, operate replicas or HA,
and monitor a production system.

---

## 1. Choose a Deployment Mode

Datalevin can run as an embedded library, a TCP server, a read-replica topology,
a consensus-lease HA cluster, an MCP tool process, or a Babashka pod. The data
model and storage format stay the same; the operational boundary changes.

| Mode | Best Fit | Main Trade-Off |
| :--- | :--- | :--- |
| Embedded | Single-process services, desktop apps, local tools | Fastest path, but access is local to the process and storage path |
| Server | Shared databases, remote clients, centralized RBAC | Adds network latency and serialization |
| Async read replica | Read scaling or workload isolation without failover | Eventually consistent and read-only; no automatic promotion |
| Consensus-lease HA | Automatic write-leader promotion and follower reads | Requires quorum discipline, fencing, and explicit membership management |
| MCP | AI tools that need controlled Datalevin access | Tool-call boundary; writes disabled unless explicitly allowed |
| Babashka pod | Scripts and maintenance tasks | IPC boundary; convenient but not the zero-copy embedded path |

In HA discussions, **fencing** means preventing a stale or deposed writer from
continuing to accept writes after another node has been allowed to lead. It is
the safety mechanism that keeps failover from becoming split-brain.

### 1.1 Embedded Mode

Embedded mode is the default choice when one application owns the database path.
It keeps the application and Datalevin in the same process, so reads use the OS
page cache directly and avoid network serialization. It is a good fit for
single-node services, local-first applications, desktop tools, and containers
with per-instance storage.

Leave RAM headroom for the OS page cache. On small VMs, a small amount of swap
can prevent brief memory spikes from killing the process, but Datalevin
performance still depends on keeping the active working set in memory.

Embedded mode and server mode are not mutually exclusive. A JVM application can
run Datalevin directly in-process and also start a Datalevin server inside the
same process. This is useful when the application owns the local database but
wants to expose a `dtlv://` endpoint for administrative tools, background
workers, non-JVM clients, or controlled inspection.

### 1.2 Server Mode

Use server mode when multiple applications need to share a Datalevin instance,
when non-JVM clients need a remote database, or when you need server-side RBAC.
The server exposes Datalog and KV APIs over `dtlv://` URIs and also provides a
JSON command surface used by MCP and other adapters.

### 1.3 Read Replicas and HA

Async read replicas and consensus HA both depend on WAL records, but they solve
different problems:

- **Async read replicas** scale or isolate reads behind one primary writer. They
  do not elect a leader, fence writes, or promote automatically.
- **Consensus-lease HA** keeps one write leader per database, uses a Raft-backed
  control plane for leases and membership, and lets followers serve reads. Raft
  is a replicated-consensus algorithm for agreeing on cluster state [1].

Choose async replicas when stale-by-lag reads are acceptable. Choose HA only
when automatic failover is worth the extra operational surface.

### 1.4 Tooling Modes

`dtlv mcp` runs a local MCP server over `stdio`. It can open local databases or
remote `dtlv://` targets. Read-only tools are the default; write tools require
starting MCP with write access enabled.

The Babashka pod is useful for fast scripts, maintenance jobs, and ad hoc data
tasks. It runs as a separate process and communicates with Babashka over IPC.

### 1.5 Native Language Libraries

Datalevin publishes libraries for several host languages:

- **Clojure**: `datalevin/datalevin` on Clojars, plus
  `org.datalevin/datalevin-embedded` for embedded-only JVM use.
- **Java**: `org.datalevin:datalevin-java` on Maven Central.
- **Python**: `datalevin` on PyPI.
- **Node.js**: `datalevin-node` on npm.

These libraries can open embedded databases. They can also connect to remote
Datalevin servers where the wrapper supports remote `dtlv://` connections.

### 1.6 High-Density Multi-Tenancy

A useful Datalevin pattern is one database file per tenant, workspace, or user.
This is common in personal knowledge management and local-first systems.

The advantages are operational:

- Per-tenant backup, restore, migration, and deletion are file-level operations.
- The OS page cache shares memory across many idle databases efficiently.
- Tenant isolation is physical rather than a repeated query predicate.
- The same database file can often move between server and local devices.

The trade-off is fleet management. You need naming, discovery, backup, and
retention automation for many small databases instead of one large database.

---

## 2. Run a Datalevin Server

The server is a non-blocking network service that exposes Datalevin APIs over a
TCP connection. It listens on `127.0.0.1:8898` by default.

### 2.1 Start the Server

Use the native `dtlv` binary for lightweight deployments. Use the JVM standalone
jar for highly concurrent or long-running server workloads where normal JVM
monitoring and GC choices matter.

```console
# Native CLI
$ DATALEVIN_DEFAULT_PASSWORD=secret \
  dtlv serv -r /data/datalevin -p 8898 --host 0.0.0.0

# JVM standalone jar
$ DATALEVIN_DEFAULT_PASSWORD=secret \
  java --add-opens=java.base/java.nio=ALL-UNNAMED \
       --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
       -jar datalevin-standalone.jar serv --host 0.0.0.0 -r /data/datalevin
```

An embedded Clojure service can also start the same server programmatically and
tie its lifecycle to the application's lifecycle:

```clojure
(require '[datalevin.server :as srv])

(defonce datalevin-server (atom nil))

(defn start-datalevin-server! []
  (let [server (srv/create {:host "127.0.0.1"
                            :port 8898
                            :root "/data/datalevin"})]
    (srv/start server)
    (reset! datalevin-server server)))

(defn stop-datalevin-server! []
  (when-let [server @datalevin-server]
    (srv/stop server)
    (reset! datalevin-server nil)))
```

This pattern keeps Datalevin embedded from the application's point of view while
making databases under the server root reachable to remote clients. Use the same
security rules as a standalone server: bind to loopback unless remote access is
intended, set `DATALEVIN_DEFAULT_PASSWORD` before exposing a non-loopback
address, and shut the server down cleanly with the rest of the application.

Important options:

- **`-r <path>`**: Root directory for databases. The default is
  `/var/lib/datalevin` on POSIX systems and `C:\ProgramData\Datalevin` on
  Windows.
- **`-p <port>`**: Listening port. The default is `8898`.
- **`--host <host>`**: Listening address. Binding to a non-loopback address
  requires `DATALEVIN_DEFAULT_PASSWORD`.
- **`--idle-timeout <ms>`**: Disconnect inactive sessions. The default is
  `172800000` ms, or 48 hours.
- **`-v`**: Enable verbose server logs on stdout.

Run the server under the platform's service manager, such as `systemd`,
`launchd`, or `sc.exe`, and make sure the service account has read/write access
to the data root.

### 2.2 Connect to a Remote Database

Remote Datalog and KV databases use this URI shape:

```text
dtlv://<user>:<pass>@<host>:<port>/<db-name>?store=datalog|kv
```

`store` is optional and defaults to `datalog`. Database names must be unique
across the whole server, so a Datalog database and a KV database cannot share
the same server-side name.

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

(def conn
  (d/get-conn "dtlv://datalevin:secret@localhost:8898/app"))

(d/q '[:find ?e . :where [?e _ _]] @conn)
```

```java
import datalevin.Connection;
import datalevin.Datalevin;

Connection conn =
    Datalevin.getConn("dtlv://datalevin:secret@localhost:8898/app");

Object result = conn.query("[:find ?e . :where [?e _ _]]");
```

```python
from datalevin import connect

conn = connect("dtlv://datalevin:secret@localhost:8898/app")

result = conn.query("[:find ?e . :where [?e _ _]]")
```

```javascript
import { connect } from "datalevin-node";

const conn = await connect("dtlv://datalevin:secret@localhost:8898/app");

const result = await conn.query("[:find ?e . :where [?e _ _]]");
```

</div>

### 2.3 Use the Admin Client

Administrative operations use `datalevin.client/new-client` in Clojure and the
corresponding wrapper client in other host languages.

<div class="multi-lang">

```clojure
(require '[datalevin.client :as c])

(def client
  (c/new-client "dtlv://datalevin:secret@localhost:8898"))
```

```java
import datalevin.Client;
import datalevin.Datalevin;

Client client =
    Datalevin.newClient("dtlv://datalevin:secret@localhost:8898");
```

```python
from datalevin import new_client

client = new_client("dtlv://datalevin:secret@localhost:8898")
```

```javascript
import { newClient } from "datalevin-node";

const client = await newClient("dtlv://datalevin:secret@localhost:8898");
```

</div>

Appendix F lists the public `datalevin.client` administrative API.

### 2.4 Protocol and Client Tuning

The server protocol uses a TLV-style (type-length-value) message format. Clojure
clients use Nippy serialization by default; language-neutral adapters use
Transit/JSON, Cognitect's data interchange format encoded over JSON [2], or the
JSON command API.

Most deployments should start with defaults. Tune only when a measured workload
needs it:

- **Freshness checks**:
  `datalevin.constants/*remote-db-last-modified-check-interval-ms*` defaults to
  `0`, which checks remote modification state on every call. A positive interval
  reduces round trips but can delay observation of remote updates.
- **Connection pool**: `datalevin.client/new-client` accepts `:pool-size`
  (default `3`) and `:time-out` (default `60000` ms).
- **HA write retry**: `:ha-write-retry-timeout-ms` and
  `:ha-write-retry-delay-ms` bound automatic retries after retryable HA write
  rejections such as `:not-leader`.
- **Wire compression**:
  `datalevin.constants/*wire-compression-threshold*` defaults to `8192` bytes,
  and `datalevin.constants/*wire-compression-level*` defaults to `3`.

For network-heavy workloads, prefer fewer larger requests. Batch writes with
`transact!`, use `transact-async` when appropriate, use `pull` instead of many
small entity lookups, and keep `:find` clauses specific so large unused result
sets are not serialized over the wire.

### 2.5 Runtime UDFs in Server Mode

Descriptor-backed `:db/udf` functions resolve where the query or transaction
executes. Embedded databases can receive a runtime registry in store options.
Remote transactions execute in the server process, so the UDF registry or
resolver must be installed on the server. Client-local registries are not
consulted for remote transaction execution.

In HA deployments, `:ha-require-udf-ready? true` makes a leader reject writes
until installed transaction UDF descriptors can be resolved by the server
runtime.

---

## 3. Security and Access Control

Security has two separate layers: physical protection of database files and
server authorization for remote access.

### 3.1 Default Admin Account

Every server has a built-in administrative user:

- **Username**: `datalevin`
- **Default password**: `datalevin`

Set `DATALEVIN_DEFAULT_PASSWORD` when starting the server. It is required when
binding to a non-loopback address and should be treated as mandatory in
production. Leave the `datalevin` user for administration and create separate
application users with narrower roles.

### 3.2 RBAC Model

Server permissions are granted to roles, then roles are assigned to users. Every
user also has a private role named `:datalevin.role/<username>`.

Each permission has three parts:

- **Action**: `:datalevin.server/view`, `:datalevin.server/alter`,
  `:datalevin.server/create`, or `:datalevin.server/control`.
- **Object**: `:datalevin.server/database`, `:datalevin.server/user`,
  `:datalevin.server/role`, or `:datalevin.server/server`.
- **Target**: A database name, username, role keyword, or `nil` for all targets
  of that object type.

The actions are hierarchical: `:alter` includes read access, `:create` includes
lower actions, and `:control` is administrative control.

### 3.3 Create a Least-Privilege User

This example creates a user, creates a role, grants read-only access to one
database, and assigns the role to the user.

<div class="multi-lang">

```clojure
(c/create-user client "alice" "password123")
(c/create-role client :app.role/analyst)

(c/grant-permission client
                    :app.role/analyst
                    :datalevin.server/view
                    :datalevin.server/database
                    "sales-db")

(c/assign-role client :app.role/analyst "alice")
```

```java
import datalevin.PermissionAction;
import datalevin.PermissionObject;

client.createUser("alice", "password123");
client.createRole("app.role/analyst");

client.grantPermission("app.role/analyst",
                       PermissionAction.VIEW,
                       PermissionObject.DATABASE,
                       "sales-db");

client.assignRole("app.role/analyst", "alice");
```

```python
client.create_user("alice", "password123")
client.create_role("app.role/analyst")

client.grant_permission("app.role/analyst",
                        "datalevin.server/view",
                        "datalevin.server/database",
                        "sales-db")

client.assign_role("app.role/analyst", "alice")
```

```javascript
await client.createUser("alice", "password123");
await client.createRole("app.role/analyst");

await client.grantPermission("app.role/analyst",
                             "datalevin.server/view",
                             "datalevin.server/database",
                             "sales-db");

await client.assignRole("app.role/analyst", "alice");
```

</div>

### 3.4 Encryption at Rest

Datalevin does not currently provide database-level transparent data encryption.
Use encryption at the layer where it is easiest to operate and audit:

- Cloud volume encryption such as AWS EBS, GCP Persistent Disk, or Azure Disk
  Encryption.
- OS or filesystem encryption such as LUKS, FileVault, or `fscrypt`.
- Application-level encryption for sensitive fields such as PII or secrets.

For SaaS systems, envelope encryption is usually an application concern: keep
tenant keys in a managed KMS (Key Management Service), wrap data-encryption keys
with KMS-managed keys, and encrypt sensitive values before transacting them into
Datalevin. NIST SP 800-57 is a useful general reference for key-management
terminology and lifecycle concerns [3].

---

## 4. Replication and High Availability

Replication and HA are server-mode features. Chapter 20 covers WAL durability
and snapshots; this section covers the operational shape.

### 4.1 Non-HA Async Read Replicas

Use an async read replica when you want one primary writer and one or more
read-only servers. The primary must have WAL enabled. A strict WAL durability
profile is recommended because the replica tails durable source records.

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

On first open, Datalevin bootstraps the replica from the primary copy interface
if the local replica database does not exist. After that, the replica tails WAL
records in LSN order and reports its applied LSN to the primary so WAL garbage
collection preserves needed segments.

The source user in `:replica/source` must be able to open/copy the primary
database, read the transaction log, and update the replica floor. In practice,
grant the replicator user database `:datalevin.server/alter` permission. The
user opening the local replica needs permission to create or open the database
on the replica server.

Replica reads use normal APIs. User writes are rejected by the replica server:

```clojure
(d/q '[:find [?name ...] :where [?e :name ?name]] @replica-conn)

;; Throws: Replica is read-only
(d/transact! replica-conn [{:db/id -1 :name "blocked"}])
```

Replica status is available through the Clojure admin client:

```clojure
(def client
  (cl/new-client "dtlv://replica-admin:pass@replica-host:8898"))

(cl/replica-status client "app")
```

The returned map includes `:replica-applied-lsn`,
`:replica-source-durable-lsn`, `:replica-source-committed-lsn`,
`:replica-lag-lsn`, `:replica-last-sync-ms`,
`:replica-degraded-reason`, and `:replica-last-error`.

### 4.2 Consensus-Lease HA

Consensus HA gives each HA database exactly one write leader at a time.
Followers replicate from the leader using WAL records and snapshots and can
serve reads.

The design has three operational layers:

- **Data plane**: The leader appends writes to WAL; followers copy and replay
  records. A follower that falls behind retained WAL bootstraps from a snapshot.
- **Control plane**: A Raft-backed group stores the lease owner, term, leader
  endpoint, membership hash, and leader LSN.
- **Local state**: Each database on each node is a `:follower`, `:candidate`,
  `:leader`, or `:demoting`.

Writes are admitted only when the node has fresh proof that it is the current
leader and is not demoting. HA forces `:wal? true` and defaults to
`:wal-durability-profile :strict`.

For follower reads, connect to a follower endpoint with the normal APIs:

```clojure
(def follower
  (d/get-conn "dtlv://user:pass@replica-host:8898/app"))
```

Use RBAC to enforce read-only access. Grant only `:datalevin.server/view` to
users that should read from followers.

### 4.3 HA Membership Updates

Consensus HA membership is operator managed. Datalevin does not discover data
nodes automatically. Update the authoritative membership for an open HA database
with `datalevin.client/ha-update-membership!`.

`ha-members` endpoints use `host:port` strings and must be ordered by ascending
`:node-id`.

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

The request validates the member and voter lists, persists the new HA options on
the target server, optionally replaces the Raft voter set, CAS-updates the
authoritative membership hash, clears existing leases by default, and restarts
that server's local HA runtime.

Useful request keys:

- **`:ha-members`**: Replacement data-node member list.
- **`:ha-control-plane {:voters [...]}`** or
  **`:ha-control-plane-voters`**: Replacement control-plane voters.
- **`:expected-membership-hash`**: Optional CAS guard.
- **`:replace-voters?`**: Defaults to `true`.
- **`:clear-leases?`**: Defaults to `true`, forcing leadership to be reacquired.
- **`:timeout-ms`**: Optional control-plane operation timeout.

Run the same desired update on surviving or newly staged servers so local
persisted options match the authoritative membership hash. A node with a
membership mismatch fails closed and will not promote or admit writes until the
mismatch is resolved.

---

## 5. Monitoring and Diagnostics

Datalevin relies heavily on the operating system for memory mapping and disk
I/O. Production monitoring should combine host metrics with Datalevin-specific
checks.

### 5.1 Host Metrics

Watch these first:

- **Disk latency and `iowait`**: `iowait` is CPU time spent waiting for storage
  I/O. Durable direct-LMDB commits are bounded by synchronous flush latency. WAL
  commit latency depends on the chosen durability profile.
- **Free disk space**: LMDB copy-on-write and WAL files need enough room for
  new pages, snapshots, and retained WAL segments.
- **Page cache and major page faults**: Datalevin's effective buffer pool is the
  OS page cache. Major page faults indicate that the active working set is not
  resident.
- **RSS vs virtual size**: RSS is resident set size, the memory pages actually
  resident in RAM. A large virtual mapping reflects `:mapsize`; it is not the
  same as resident memory.

### 5.2 Database Statistics

Use `stat` to inspect LMDB B+Tree statistics. In Clojure, `stat` operates on a
KV handle. For a Datalog database directory, the `dtlv stat` command opens the
store and reports the same class of LMDB statistics.

<div class="multi-lang">

```clojure
(def kv (d/open-kv "/data/companydb"))

(d/stat kv)

(d/open-dbi kv "datalevin/eav")
(d/stat kv "datalevin/eav")
```

```java
KV kv = Datalevin.openKV("/data/companydb");

Map<?, ?> stats = kv.stat();

kv.openDbi("datalevin/eav");
Map<?, ?> eavStats = kv.stat("datalevin/eav");
```

```console
$ dtlv -d /data/companydb stat
$ dtlv -d /data/companydb stat datalevin/eav
```

</div>

Useful fields include `:branch-pages`, `:leaf-pages`, `:overflow-pages`, and
`:entries`. LMDB reuses deleted pages rather than shrinking the file in place.
After large deletions, use the compact-copy workflow from Chapter 20 during
controlled maintenance.

### 5.3 Query and Write Diagnostics

- Use `explain` from Chapter 21 to inspect query plans and, with `{:run? true}`,
  compare estimates with actual result sizes.
- If writes are slow and disk is busy, check storage latency and the selected
  durability profile.
- If writes are slow while disk is idle, look for writer contention, many small
  transactions, or WAL group-commit settings that do not fit the workload.
- Log transaction latency, query latency, and result sizes at the application
  boundary. Datalevin cannot know which latency budget matters to your service.

### 5.4 Replication and HA Checks

For async replicas, monitor `datalevin.client/replica-status`, especially
`:replica-lag-lsn`, `:replica-degraded-reason`, and `:replica-last-error`.

For HA clusters, monitor leader identity, follower lag, membership hash
agreement, clock skew, and fencing health. Keep each node's `:ha-members` and
promotable control-plane voters aligned with the authoritative membership hash.

### 5.5 Health Checks

A service health check should prove that the application can reach the database
path it depends on. For a Datalog database, use a scalar probe:

```clojure
(d/q '[:find ?e . :where [?e _ _]] db)
```

This is cheaper than returning a relation with `:limit 1`; the health check only
needs one scalar value or `nil`.

For server deployments, check both the server process and the application-level
operation that your service needs, such as a read-only query for read endpoints
or a small write in a dedicated health database for write-path checks.

---

## 6. Production Checklist

Use this as a final review before production:

- Choose the simplest deployment mode that satisfies the availability and access
  requirements.
- Run the server under a service manager with a dedicated data directory and
  restricted filesystem permissions.
- Set `DATALEVIN_DEFAULT_PASSWORD`; do not expose a server using the built-in
  default password.
- Create application users and roles with least-privilege RBAC permissions.
- Use disk or volume encryption for the data directory, and application-level
  encryption for sensitive fields.
- Follow Chapter 20 for `:mapsize`, `:max-readers`, WAL, durability profile,
  copy, dump/load, snapshots, and compaction.
- Automate transactionally consistent backups and test restores.
- Monitor disk latency, free space, page-cache behavior, query latency, write
  latency, and result sizes.
- For async replicas, monitor replica lag and degraded state.
- For HA, keep membership, voter mapping, clock discipline, and fencing checks
  part of normal operations.
- Install server-side UDF registries before relying on descriptor-backed UDFs in
  remote transactions.

Datalevin is operationally quiet when the deployment boundary is chosen
correctly. Most production issues come from choosing a topology that is more
complex than the application needs, starving the OS page cache, or failing to
test backup and restore paths.

## References

[1] Diego Ongaro and John Ousterhout, ["In Search of an Understandable Consensus
Algorithm"](https://raft.github.io/raft.pdf), USENIX ATC 2014.

[2] Cognitect, ["Transit Format"](https://github.com/cognitect/transit-format).

[3] Elaine Barker, ["Recommendation for Key Management: Part 1 -
General"](https://doi.org/10.6028/NIST.SP.800-57pt1r5), NIST Special Publication
800-57 Part 1 Revision 5, 2020.
