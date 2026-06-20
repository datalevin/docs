---
title: "Testing, Deployment, and Production Operations"
chapter: 22
part: "V — Performance and Operations"
---

# Chapter 22: Testing, Deployment, and Production Operations

Chapter 19 covered durability, snapshots, copy, dump/load, and storage tuning.
This chapter covers the operational lifecycle around that storage: how to test
an application against Datalevin, choose a deployment shape, run a server when
needed, secure remote access, operate replicas or HA, and monitor a production
system.

It begins with testing, because the cheapest place to catch a modeling, schema,
or query mistake is in a test, long before deployment. It also pulls together
Datalevin's concurrency and failure model, because production code needs a
clear policy for which transaction failures are data bugs, which are logical
conflicts, and which are transient operational conditions.

---

## Version Target and API Stability

This review draft targets Datalevin master branch commit `9142da07`. If you
are reading a local checkout, older generated copy, or printed edition, check
your installed Datalevin version or source checkout against the target shown
here and the package metadata for your language binding. Released package
snippets elsewhere in the book still use `{{datalevin-version}}` where a Maven,
Leiningen, Gradle, Babashka pod, or standalone-jar version string is required.

Datalevin follows the common Clojure library practice of keeping public APIs
stable and evolving them mostly by accumulation. Core APIs such as schema,
transactions, Datalog queries, pull, entity navigation, rules, key-value access,
and ordinary connection management should be expected to remain stable across
normal upgrades. Breaking changes should be rare and called out in release
notes, especially when they affect on-disk data formats, server protocols, or
public function signatures.

Some parts of the surface area are newer or more operationally sensitive than
the core database API. WAL, replication, consensus high availability, idoc
indexes, embedding and vector indexes, asynchronous indexing, MCP tooling, and
language bindings may gain options, commands, or operational guidance as the
project evolves. Unless this book marks a feature as experimental, the API
described here is intended to be usable in the targeted release, but production
deployments should still pin a Datalevin version, read the release notes before
upgrading, and verify behavior in staging.

Platform support can also differ by feature. For example, the core Datalog and
KV APIs may be available on a platform where optional vector or embedding
support is still experimental. Appendix A calls out current platform and runtime
requirements.

---

## 1. Testing Datalevin Applications

Datalevin is easy to test because the same library that runs in production can
run entirely inside a test process. There is no separate test server to manage
and no external service to mock. Most application logic can be exercised against
a real Datalevin database that is created and discarded inside a single test.

Three levels of test are useful, from fastest to most realistic:

1. **Pure logic tests** over in-memory tuples, with no database at all.
2. **In-memory database tests** that exercise schema, transactions, indexes,
   search, and queries against a real but ephemeral store.
3. **Temporary on-disk tests** for behavior that depends on files, persistence,
   or WAL.

Start at the cheapest level that can fail for the reason you care about, and move
to a more realistic level only when the test needs it.

### 1.1 Pure Logic Tests Over Tuples

Chapter 8 showed that the query engine accepts any sequence of `[e a v]` tuples
as a data source. This is the fastest way to unit-test query and rule logic,
because there is no store to open, transact, or clean up. The fixture data sits
in the test itself.

```clojure
(ns my-app.query-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]))

(def people
  [[1 :user/name "Alice"] [1 :user/age 30]
   [2 :user/name "Bob"]   [2 :user/age 25]])

(deftest adults-are-selected
  (is (= #{["Alice"]}
         (d/q '[:find ?name
                :in $ ?min
                :where [?e :user/name ?name]
                       [?e :user/age ?age]
                       [(>= ?age ?min)]]
              people 28))))
```

Rules are data too, so a rule set can be passed straight into a tuple-backed
query as the `%` input. Use this level for query shape, rule composition,
predicates, and aggregation logic. It does not exercise schema, indexes, upsert,
or search; those need a real store.

### 1.2 In-Memory Database Tests

When a test needs schema, transactions, indexes, or search, open a real
in-memory connection. An in-memory database uses the same API as a persistent
one, but keeps nothing on disk and is discarded when the connection closes
(Chapter 2). Each test gets a fresh, isolated database with no cleanup step.

```clojure
(deftest upsert-updates-existing-user
  (let [schema {:user/email {:db/valueType :db.type/string
                             :db/unique    :db.unique/identity}
                :user/name  {:db/valueType :db.type/string}}
        conn   (d/create-conn nil schema {:kv-opts {:inmemory? true}})]
    (try
      (d/transact! conn [{:user/email "ada@example.com" :user/name "Ada"}])
      (d/transact! conn [{:user/email "ada@example.com" :user/name "Ada L."}])
      (is (= "Ada L."
             (d/q '[:find ?name .
                    :where [?e :user/email "ada@example.com"]
                           [?e :user/name ?name]]
                  (d/db conn))))
      (finally
        (d/close conn)))))
```

Because in-memory stores are cheap to create, the simplest isolation strategy is
one fresh store per test. A `clojure.test` fixture removes the repeated setup and
teardown:

```clojure
(def ^:dynamic *conn* nil)

(defn with-fresh-db [f]
  (let [conn (d/create-conn nil test-schema {:kv-opts {:inmemory? true}})]
    (binding [*conn* conn]
      (try (f) (finally (d/close conn))))))

(use-fixtures :each with-fresh-db)
```

Each test then reads `*conn*` and starts from an empty database. Prefer
`:each` over `:once` so one test cannot leave state that another test depends on.

### 1.3 Temporary On-Disk Tests

Some behavior only appears on disk: persistence across reopen, WAL replay,
snapshots, compaction, or `copy`/`dump`/`load`. For those, open a database in a
unique temporary directory and delete it afterward. The `with-conn` macro opens
the connection, runs the body, and closes it, so only the directory needs
explicit cleanup:

```clojure
(ns my-app.persistence-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [datalevin.util :as u]))

(deftest data-survives-reopen
  (let [dir (u/tmp-dir (str "test-" (random-uuid)))]
    (try
      (d/with-conn [conn dir test-schema]
        (d/transact! conn [{:user/email "ada@example.com"}]))
      ;; Reopen the same path; the fact should still be there.
      (d/with-conn [conn dir test-schema]
        (is (some? (d/entity (d/db conn) [:user/email "ada@example.com"]))))
      (finally
        (u/delete-files dir)))))
```

`datalevin.util/tmp-dir` returns a platform-neutral temporary path, and
`delete-files` removes the directory tree; both are utility helpers used by
Datalevin's own test suite. Adding a `random-uuid` keeps parallel test runs from
colliding on the same path.

### 1.4 Make Tests Strict to Catch Typos

Schema-on-write is convenient in production but unhelpful in a test: a misspelled
attribute such as `:user/emial` is silently accepted as a new attribute, and the
assertion simply fails to find the data (Chapter 5). Tests are the right place to
turn that leniency off. Open test connections with `:validate-data?` and
`:closed-schema?` so unknown attributes and ill-typed values fail loudly:

```clojure
(d/create-conn nil schema
  {:kv-opts        {:inmemory? true}
   :validate-data? true
   :closed-schema? true})
```

With these options, a transaction that mentions an undeclared attribute or writes
a value that does not match its declared `:db/valueType` throws instead of
quietly storing surprising data. This converts a class of modeling bugs into
test failures (Chapter 11).

### 1.5 Dry-Run Transactions With `tx-data->simulated-report`

To test how transaction data resolves — tempids, upserts, the datoms that would
be added or retracted — without mutating the database, use
`tx-data->simulated-report`. It returns the same transaction-report shape as
`transact!` but leaves the connection untouched (Appendix E):

```clojure
(deftest signup-adds-email-datom
  (let [conn   (d/create-conn nil test-schema {:kv-opts {:inmemory? true}})
        report (d/tx-data->simulated-report
                 (d/db conn)
                 [{:user/email "ada@example.com" :user/name "Ada"}])]
    (try
      ;; The report shows the datoms that *would* be written ...
      (is (some #(and (= :user/email (d/datom-a %))
                      (= "ada@example.com" (d/datom-v %)))
                (:tx-data report)))
      ;; ... but the database itself is left untouched.
      (is (nil? (d/entity (d/db conn) [:user/email "ada@example.com"])))
      (finally
        (d/close conn)))))
```

This is useful for unit-testing transaction-function output and validation logic,
where you care about the datoms a transaction would produce rather than
committing them.

### 1.6 Testing Search, Vectors, and Async Indexing

Full-text, idoc, and vector indexes are maintained synchronously by default
(Chapters 16-17), so an in-memory database is enough to test them:
read-your-writes holds, and a `fulltext`, `idoc-match`, or `vec-neighbors` query
sees data committed earlier in the same test.

Two pitfalls are worth avoiding:

- **Keep vector tests deterministic.** Prefer `:db.type/vec` with literal vectors
  and `vec-neighbors` rather than depending on a remote embedding provider in a
  unit test. A network call to an embedding API makes the test slow,
  nondeterministic, and dependent on credentials. Reserve provider-backed
  `:db/embedding` tests for a small, clearly separated integration suite.
- **Wait for async indexing before asserting.** If a domain opts into
  `:indexing-mode :async`, the source datoms commit before the secondary index
  catches up, so an immediate search may miss them. Call
  `wait-for-secondary-index` (or `process-secondary-index-jobs!`) before the
  assertion (Appendix E):

```clojure
(d/transact! conn [{:doc/content "vector search guide"}])
(d/wait-for-secondary-index conn)
(is (seq (d/q '[:find [?e ...]
                :where [(fulltext $ :doc/content "vector") [[?e _ _]]]]
              (d/db conn))))
```

### 1.7 Testing From Other Language Bindings

The helpers in this section that are Clojure APIs — `with-conn`, the
`clojure.test` fixtures, and `tx-data->simulated-report` — do not have direct
equivalents in the Java, Python, and JavaScript bindings. From those languages,
test with your usual test runner and two portable techniques:

- **Tuple-based logic tests** (Section 1.1) work everywhere, because passing an
  EAV tuple sequence to `query` needs no database.
- **Per-test temporary databases** give realistic coverage. Use the test
  runner's own temporary-directory facility for isolation and cleanup — for
  example, pytest's `tmp_path` fixture in Python — and open a normal connection
  on that path:

```python
def test_upsert_updates_existing_user(tmp_path):
    from datalevin import connect

    schema = {
        ":user/email": {":db/valueType": ":db.type/string",
                        ":db/unique": ":db.unique/identity"},
        ":user/name": {":db/valueType": ":db.type/string"},
    }
    with connect(str(tmp_path / "db"), schema=schema) as conn:
        conn.transact([{":user/email": "ada@example.com", ":user/name": "Ada"}])
        conn.transact([{":user/email": "ada@example.com", ":user/name": "Ada L."}])
        assert conn.query(
            '[:find ?name . '
            ' :where [?e :user/email "ada@example.com"] '
            '        [?e :user/name ?name]]') == "Ada L."
```

The `with` form closes the connection at the end of the test, and the runner
deletes the temporary directory. This is the pattern Datalevin's own Python test
suite uses.

---

## 2. Choose a Deployment Mode

Datalevin can run as an embedded library, a TCP server, a read-replica topology,
a consensus-lease HA cluster, an MCP tool process, or a Babashka pod. The data
model and storage format stay the same; the operational boundary changes.

![Deployment topologies around the same database: embedded keeps the app and DB in one process; server exposes the DB to remote clients over dtlv://; an async read replica ships WAL from a primary to a read-only replica; consensus-lease HA coordinates a leader and follower DBs via Raft with fencing; MCP wraps the DB in a tool-call boundary with writes off by default; a Babashka pod reaches the DB over IPC](/images/diagrams/deployment-topologies.svg)

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

### 2.1 Embedded Mode

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

### 2.2 Server Mode

Use server mode when multiple applications need to share a Datalevin instance,
when non-JVM clients need a remote database, or when you need server-side RBAC.
The server exposes Datalog and KV APIs over `dtlv://` URIs and also provides a
JSON command surface used by MCP and other adapters.

### 2.3 Read Replicas and HA

Async read replicas and consensus HA both depend on WAL records, but they solve
different problems:

- **Async read replicas** scale or isolate reads behind one primary writer. They
  do not elect a leader, fence writes, or promote automatically.
- **Consensus-lease HA** keeps one write leader per database, uses a Raft-backed
  control plane for leases and membership, and lets followers serve reads. Raft
  is a replicated-consensus algorithm for agreeing on cluster state [1].

Choose async replicas when stale-by-lag reads are acceptable. Choose HA only
when automatic failover is worth the extra operational surface.

### 2.4 Tooling Modes

`dtlv mcp` runs a local MCP server over `stdio`. It can open local databases or
remote `dtlv://` targets. Read-only tools are the default; write tools require
starting MCP with write access enabled.

The Babashka pod is useful for fast scripts, maintenance jobs, and ad hoc data
tasks. It runs as a separate process and communicates with Babashka over IPC.

### 2.5 Native Language Libraries

Datalevin publishes libraries for several host languages:

- **Clojure**: `datalevin/datalevin` on Clojars, plus
  `org.datalevin/datalevin-embedded` for embedded-only JVM use.
- **Java**: `org.datalevin:datalevin-java` on Maven Central.
- **Python**: `datalevin` on PyPI.
- **Node.js**: `datalevin-node` on npm.

These libraries can open embedded databases. They can also connect to remote
Datalevin servers where the wrapper supports remote `dtlv://` connections.

### 2.6 High-Density Multi-Tenancy

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

## 3. Run a Datalevin Server

The server is a non-blocking network service that exposes Datalevin APIs over a
TCP connection. It listens on `127.0.0.1:8898` by default.

### 3.1 Start the Server

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

### 3.2 Connect to a Remote Database

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

### 3.3 Use the Admin Client

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

Appendix G lists the public `datalevin.client` administrative API.

### 3.4 Protocol and Client Tuning

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

### 3.5 Runtime UDFs in Server Mode

Descriptor-backed `:db/udf` functions resolve where the query or transaction
executes. Embedded databases can receive a runtime registry in store options.
Remote transactions execute in the server process, so the UDF registry or
resolver must be installed on the server. Client-local registries are not
consulted for remote transaction execution.

In HA deployments, `:ha-require-udf-ready? true` makes a leader reject writes
until installed transaction UDF descriptors can be resolved by the server
runtime.

---

## 4. Security and Access Control

Security has two separate layers: physical protection of database files and
server authorization for remote access.

### 4.1 Default Admin Account

Every server has a built-in administrative user:

- **Username**: `datalevin`
- **Default password**: `datalevin`

Set `DATALEVIN_DEFAULT_PASSWORD` when starting the server. It is required when
binding to a non-loopback address and should be treated as mandatory in
production. Leave the `datalevin` user for administration and create separate
application users with narrower roles.

### 4.2 RBAC Model

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

### 4.3 Create a Least-Privilege User

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

### 4.4 Encryption at Rest

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

## 5. Replication and High Availability

Replication and HA are server-mode features. Chapter 19 covers WAL durability
and snapshots; this section covers the operational shape.

### 5.1 Non-HA Async Read Replicas

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

### 5.2 Consensus-Lease HA

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

### 5.3 HA Membership Updates

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

## 6. Monitoring and Diagnostics

Datalevin relies heavily on the operating system for memory mapping and disk
I/O. Production monitoring should combine host metrics with Datalevin-specific
checks.

### 6.1 Host Metrics

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

Chapter 19 shows concrete Linux and macOS commands for checking page-cache
residency, major page faults, swap activity, and file-backed cache usage.

### 6.2 Database Statistics

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
After large deletions, use the compact-copy workflow from Chapter 19 during
controlled maintenance.

### 6.3 Query and Write Diagnostics

- Use `explain` from Chapter 21 to inspect query plans and, with `{:run? true}`,
  compare estimates with actual result sizes.
- If writes are slow and disk is busy, check storage latency and the selected
  durability profile.
- If writes are slow while disk is idle, look for writer contention, many small
  transactions, or WAL group-commit settings that do not fit the workload.
- Log transaction latency, query latency, and result sizes at the application
  boundary. Datalevin cannot know which latency budget matters to your service.

### 6.4 Replication and HA Checks

For async replicas, monitor `datalevin.client/replica-status`, especially
`:replica-lag-lsn`, `:replica-degraded-reason`, and `:replica-last-error`.

For HA clusters, monitor leader identity, follower lag, membership hash
agreement, clock skew, and fencing health. Keep each node's `:ha-members` and
promotable control-plane voters aligned with the authoritative membership hash.

### 6.5 Health Checks

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

## 7. Concurrency and Failure Handling

Datalevin's normal transaction examples focus on successful writes. Production
code also needs a failure policy. Separate failures into three groups:

1. **Semantic transaction failures**: the transaction data is invalid for the
   current database state or schema.
2. **Logical conflicts**: the transaction was well-formed, but a condition such
   as `:db/cas` did not hold.
3. **Operational failures**: the local store, WAL, remote server, or HA topology
   could not complete the write.

Handle those groups differently. Retrying a validation error just repeats a
bug. Retrying a CAS conflict can be correct if the operation re-reads current
state on every attempt. Retrying an operational failure is only safe when the
application operation is idempotent or has an idempotency key.

![Failure and retry policy: a failed write is classified into a semantic failure (invalid data for the schema or state — do not retry, fix the cause), a logical conflict (a well-formed transaction whose condition such as :db/cas did not hold — re-read current state, recompute, and retry), or an operational failure (the store, WAL, remote server, or HA topology could not complete the write — retry only when the operation is idempotent and the topology is healthy)](/images/diagrams/failure-retry-policy.svg)

### 7.1 The Write Serialization Model

Datalevin inherits LMDB's MVCC read model: readers do not block writers, and
writers do not block readers. A reader sees a stable snapshot. A writer commits
a new version that later readers can observe.

Writes are different. For a single Datalevin store, the durable B+Tree commit
path has one writer at a time. In embedded Clojure, `transact!` serializes
writes through the connection and the underlying LMDB write transaction. WAL mode
can queue and batch concurrent callers, but the committed writes still have a
single order. The caller either receives a transaction report or an exception.

`with-transaction` opens one read/write transaction around its body. Reads and
writes inside the body observe the same write transaction, so no other write can
interleave between the read and the write within that store. If the body throws,
the transaction is aborted. `with-transaction` does not retry logical conflicts
such as CAS failures or unique-constraint violations. It only retries Datalevin's
internal map-resize condition, where the LMDB map was grown and the write can be
replayed safely.

Normal Datalevin writes should serialize rather than deadlock. If a write path
appears stuck, look for a long-running transaction function, application locks
held around database calls, callbacks that synchronously wait on other writes,
slow storage flushes, or HA/network waits. Keep transaction functions small and
side-effect-free, and perform external side effects after the transaction
commits.

### 7.2 Common Transaction Failures

Most Datalevin-originated transaction failures are
`clojure.lang.ExceptionInfo`. When Datalevin supplies structured `ex-data`, use
that for application branching and treat the message as human diagnostics.
Native storage and remote failures may wrap lower-level exceptions, so log the
full exception and its cause chain at the service boundary.

| Failure | When it happens | Typical `ex-data` | Retry policy |
| :--- | :--- | :--- | :--- |
| Unique constraint violation | A transaction adds a value already present for a `:db.unique/value` attribute, or creates an inconsistent unique assertion. `:db.unique/identity` normally upserts when used as identity. | `{:error :transact/unique, :attribute attr, ...}` | Do not retry blindly. Return a conflict or use identity upsert intentionally. |
| Closed schema or type validation | `:closed-schema?` rejects an undeclared attribute, or `:validate-data?` rejects a value that does not match `:db/valueType`. | Often `:transact/syntax` for syntax errors; type and closed-schema checks may only include input context. | Do not retry. Fix schema, data, or caller validation. |
| Lookup-ref syntax or non-unique attribute | A lookup ref is not `[attr value]`, or `attr` is not unique. | `:lookup-ref/syntax` or `:lookup-ref/unique` | Do not retry. Fix the transaction shape or schema. |
| Lookup-ref miss in a transaction | A transaction form requires an existing entity and `[attr value]` resolves to nothing. | `{:error :entity-id/missing, :entity-id [...]}` | Usually do not retry. Create the referenced entity first, use identity upsert, or treat it as a domain conflict. |
| `:db/cas` or `:db.fn/cas` failure | The current value is not the expected old value. | `{:error :transact/cas, :old old, :expected old-value, :new new-value}` | Retry only by re-reading current state and recomputing the new transaction. |
| Upsert conflict | Multiple identity assertions in one entity resolve to different existing entities. | `{:error :transact/upsert, ...}` | Do not retry. The input identifies two different entities. |
| Transaction function failure | An installed transaction function or descriptor-backed UDF throws, cannot be resolved, or returns invalid transaction data. | Depends on the function or UDF validation. | Depends on the function. Keep transaction functions deterministic and side-effect-free. |
| LMDB map full / map resize | The write exceeds the current memory map size. | Normally handled internally as `"DB resized"` with `{:resized true}` and retried. | Usually no application action. If it happens often, set a larger `:mapsize` up front. |
| WAL, remote, or HA write failure | WAL sync fails or times out, a remote server returns an error, or HA rejects a write because leadership or fencing changed. | Often `:type :txlog/...` or `:error :ha/...` | Retry only if the operation is idempotent and the topology is healthy. For HA, use the HA retry settings and still design writes to be replay-safe. |

The important distinction is that Datalevin does not turn logical conflicts into
transparent retries. If the old value for `:db/cas` is wrong, the transaction
fails because the caller's premise is false. The application must decide whether
to report the conflict or compute a new transaction against current state.

### 7.3 Recommended Retry Patterns

In embedded Clojure, Java, or Python, prefer the Datalog transaction callback
(`with-transaction`, `withTransaction`, or `with_transaction`) for
read-modify-write logic that must be atomic within one store:

```clojure
(defn increment-counter! [conn counter-id]
  (d/with-transaction [tx conn]
    (let [db  @tx
          n   (or (d/q '[:find ?n .
                         :in $ ?id
                         :where [?e :counter/id ?id]
                                [?e :counter/value ?n]]
                       db counter-id)
                  0)]
      (d/transact! tx
        [{:counter/id counter-id
          :counter/value (inc n)}]))))
```

This pattern does not need a CAS retry loop because the read and write happen
inside the same write transaction. Use it only for short critical sections. Do
not perform HTTP calls, file uploads, email delivery, or long CPU work inside
the transaction body.

When a write is intentionally optimistic, use CAS and retry at the application
operation boundary. Re-read inside every attempt:

<!-- pdf-listing: Optimistic CAS retry pattern -->

```clojure
(defn cas-conflict?
  [e]
  (= :transact/cas (:error (ex-data e))))

(defn retry-cas
  [f]
  (loop [attempt 1]
    (let [result (try
                   {:ok? true :value (f)}
                   (catch clojure.lang.ExceptionInfo e
                     {:ok? false :error e}))]
      (if (:ok? result)
        (:value result)
        (let [e (:error result)]
          (if (and (cas-conflict? e) (< attempt 5))
            (do
              (Thread/sleep (* 25 attempt))
              (recur (inc attempt)))
            (throw e)))))))

(defn deposit! [conn email amount]
  (retry-cas
    #(let [db      @conn
           balance (or (d/q '[:find ?balance .
                              :in $ ?email
                              :where [?e :account/email ?email]
                                     [?e :account/balance ?balance]]
                            db email)
                       0)]
       (d/transact! conn
         [[:db/cas [:account/email email]
           :account/balance balance (+ balance amount)]]))))
```

The retry wrapper does not retry unique, lookup-ref, schema, or validation
failures. It retries only CAS conflicts, and each retry re-reads the balance
from a fresh DB snapshot before preparing a new transaction.

For operational retries, apply the same rule at a higher level:

- Retry whole application operations, not half-built transaction data.
- Make every retried operation idempotent. Use a request id, event id, or
  unique identity attribute so a replay updates the same logical fact instead of
  creating a duplicate.
- Keep external side effects outside the transaction, and make listeners
  idempotent because they observe committed transactions after the write.
- Use bounded retries with backoff. Infinite retries can hide a broken schema,
  a missing entity, full disk, or an HA cluster that cannot safely accept writes.

### 7.4 Production Policy

A useful HTTP or job-worker policy is:

- Return `400` for transaction syntax, closed-schema, and type-validation
  failures.
- Return `404` or `409` for lookup-ref misses, depending on whether the missing
  entity is a resource path or a domain precondition.
- Return `409` for unique conflicts and CAS conflicts unless the operation has a
  specific retry loop.
- Return `503` for operational storage, remote, WAL, or HA failures that may
  clear after repair or failover.
- Log the exception class, message, `ex-data`, and cause chain. Do not log full
  transaction data if it can contain secrets.

This policy gives production readers a consistent mental model: Datalevin
serializes writes for a store, exposes logical conflicts as exceptions, retries
only safe internal resize events, and leaves application-level retries to the
code that understands the business operation.

---

## Summary

### Production Checklist

Use this as a final review before production:

- Cover schema, queries, rules, and transaction logic with tests against
  in-memory or temporary Datalevin databases, and run tests with
  `:validate-data?` and `:closed-schema?` enabled (Section 1).
- Choose the simplest deployment mode that satisfies the availability and access
  requirements.
- Run the server under a service manager with a dedicated data directory and
  restricted filesystem permissions.
- Set `DATALEVIN_DEFAULT_PASSWORD`; do not expose a server using the built-in
  default password.
- Create application users and roles with least-privilege RBAC permissions.
- Use disk or volume encryption for the data directory, and application-level
  encryption for sensitive fields.
- Follow Chapter 19 for `:mapsize`, `:max-readers`, WAL, durability profile,
  copy, dump/load, snapshots, and compaction.
- Define an application retry policy for CAS conflicts, uniqueness conflicts,
  validation failures, lookup-ref misses, and operational write failures
  (Section 7).
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
