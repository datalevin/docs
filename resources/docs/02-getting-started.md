---
title: "Getting Started: Embedded, Server, and Command Line Modes"
chapter: 2
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 2: Getting Started: Embedded, Server, and Command Line Modes

Datalevin can be used as an embedded database in the form of a library, a
standalone server, a server with read only replicas, a high availability server
cluster, a command-line tool, a Babashka pod, or a local MCP tool server. The
same core database concepts appear in each mode: a path or URI names the
database, schema describes attribute behavior, transactions add or retract
facts, and Datalog queries read a database value.

This chapter gives the shortest reliable path into each supported mode.

---


## 1. Embedded Mode

Datalevin can serve as a library to be embedded in your applications written in
Clojure, Java, Python, or Node.js.

### 1.1 Runtime Requirements

All the language libraries load a JVM Datalevin runtime, and they require **Java
21 or newer**. Some language bindings have their own host-runtime requirements:

- Python: Python 3.10+ and Java 21+
- Node.js: Node.js 20+ and Java 21+

These JVM flags are also required to be passed to the JVM:

```console
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

Python and Node.js bindings add the above open-module flags automatically. On
Java 24 and newer, `--enable-native-access=ALL-UNNAMED` suppresses native-access
warnings.

### 1.2 Native Dependencies

Datalevin bundles its non-JVM native libraries for the supported platforms. If
loading fails with `java.lang.UnsatisfiedLinkError`, first confirm that the
system `libc` for your OS is present, e.g. some Linux distributions may not
include it. If the bundled OpenMP/vector math libraries do not work on your
machine, install the platform packages directly:

```console
# Debian/Ubuntu
sudo apt-get install libgomp1

# macOS
brew install libomp llvm
```

---

Embedded mode gives your application process direct access to the database
environment. This is the fastest and simplest deployment shape when one
application owns the database path.

### 1.3 Add the Dependency

<div class="multi-lang">

```clojure
;; Embedded-only with clj
{:deps {org.datalevin/datalevin-embedded {:mvn/version "0.10.18"}}}

;; Embedded-only with Leiningen
:dependencies [[org.datalevin/datalevin-embedded "0.10.18"]]
```

```java
<!-- Maven pom.xml -->
<dependency>
  <groupId>org.datalevin</groupId>
  <artifactId>datalevin-java</artifactId>
  <version>0.10.18</version>
</dependency>

// Gradle Kotlin DSL
repositories {
    mavenCentral()
}

dependencies {
    implementation("org.datalevin:datalevin-java:0.10.18")
}
```

```python
# Python 3.10+
python -m pip install datalevin
```

```javascript
// Node.js 20+
npm install datalevin-node
```

</div>


### 1.4 Your First Transaction and Query

Open a REPL or write a small program to create a database, transact data, and query it.


<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

(def schema
  {:user/name {:db/valueType :db.type/string
               :db/unique    :db.unique/identity}
   :user/age  {:db/valueType :db.type/long}})

(def conn (d/get-conn "/tmp/mydb" schema))

(d/transact! conn [{:user/name "Alice" :user/age 30}
                   {:user/name "Bob"   :user/age 25}])

(d/q '[:find [?name ...]
       :where [?e :user/name ?name]
              [?e :user/age ?age]
              [(> ?age 28)]]
     (d/db conn))
;; => ["Alice"]

(d/close conn)
```

```java
import datalevin.Connection;
import datalevin.Datalevin;
import datalevin.DatalogQuery;
import datalevin.Schema;
import datalevin.Tx;

try (Connection conn = Datalevin.createConn(
        "/tmp/mydb",
        Datalevin.schema()
            .attr("user/name",
                  Schema.attribute()
                      .valueType(Schema.ValueType.STRING)
                      .unique(Schema.Unique.IDENTITY))
            .attr("user/age",
                  Schema.attribute()
                      .valueType(Schema.ValueType.LONG)))) {

    conn.transact(Datalevin.tx()
        .entity(Tx.entity(-1).put("user/name", "Alice").put("user/age", 30))
        .entity(Tx.entity(-2).put("user/name", "Bob").put("user/age", 25)));

    DatalogQuery query = Datalevin.query()
        .findAll("?name")
        .whereDatom(Datalevin.var("e"), "user/name", Datalevin.var("name"))
        .whereDatom(Datalevin.var("e"), "user/age", Datalevin.var("age"))
        .wherePredicate(">", Datalevin.var("age"), 28);

    System.out.println(conn.queryCollection(query, String.class));
}
```

```python
from datalevin import connect

schema = {
    ":user/name": {
        ":db/valueType": ":db.type/string",
        ":db/unique": ":db.unique/identity",
    },
    ":user/age": {":db/valueType": ":db.type/long"},
}

with connect("/tmp/mydb", schema=schema) as conn:
    conn.transact([
        {":user/name": "Alice", ":user/age": 30},
        {":user/name": "Bob", ":user/age": 25},
    ])

    print(conn.query(
        """[:find [?name ...]
           :where [?e :user/name ?name]
                  [?e :user/age ?age]
                  [(> ?age 28)]]"""))
```

```javascript
import { connect } from "datalevin-node";

const conn = await connect("/tmp/mydb", {
  schema: {
    ":user/name": {
      ":db/valueType": ":db.type/string",
      ":db/unique": ":db.unique/identity"
    },
    ":user/age": { ":db/valueType": ":db.type/long" }
  }
});

try {
  await conn.transact([
    { ":user/name": "Alice", ":user/age": 30 },
    { ":user/name": "Bob", ":user/age": 25 }
  ]);

  console.log(await conn.query(
    `[:find [?name ...]
      :where [?e :user/name ?name]
             [?e :user/age ?age]
             [(> ?age 28)]]`));
} finally {
  await conn.close();
}
```

</div>

---

## 2. Running as a Standalone Server

Server mode is the right choice when multiple clients or services need a shared
database endpoint.

You can run a Datalevin server either in the form of a JVM standalone jar or a
GraalVM native image. For production use, the former is recommended, as a JVM
process is highly optimized for long running server workload, as HotSpot's JIT
and advanced garbage collectors will outperform native image over time.

For getting started, we show below how to run Datalevin server as a GraalVM native
image.


### 2.1 Install `dtlv`

Datalevin's native image is a command-line program called  `dtlv`. It can query,
transact, import, export, back up, compact, and administer Datalevin databases.
The same executable can also run a server or an interactive Datalevin REPL.

Pre-built `dtlv` distributions are available for Windows AMD64, macOS ARM64,
Linux AMD64, and Linux ARM64.


On macOS or Linux with Homebrew:

```console
brew install huahaiy/brew/datalevin
```

On Windows with Scoop:

```console
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add extras
scoop install datalevin
```

With Docker:

```console
docker pull huahaiy/datalevin
```

### 2.2 Start a Server

Start a local server with a root data directory:

```console
DATALEVIN_DEFAULT_PASSWORD=secret dtlv serv -r /path/to/data -p 8898
```

By default the server listens on `127.0.0.1:8898`. Use `--host` to bind another
address. Binding to a non-loopback address such as `0.0.0.0` requires
`DATALEVIN_DEFAULT_PASSWORD`, because the built-in administrative user is named
`datalevin`.


```console
DATALEVIN_DEFAULT_PASSWORD=secret \
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     -jar datalevin-0.10.18-standalone.jar serv --host 0.0.0.0 -r /data/dtlv
```

### 2.3 Connect to a Server

Remote databases use `dtlv://` URIs:

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

(def conn
  (d/get-conn "dtlv://datalevin:secret@localhost:8898/mydb"))
```

```java
import datalevin.Connection;
import datalevin.Datalevin;

Connection conn =
    Datalevin.getConn("dtlv://datalevin:secret@localhost:8898/mydb");
```

```python
from datalevin import connect

conn = connect("dtlv://datalevin:secret@localhost:8898/mydb")
```

```javascript
import { connect } from "datalevin-node";

const conn = await connect("dtlv://datalevin:secret@localhost:8898/mydb");
```

</div>

The URI shape is:

```text
dtlv://<user>:<pass>@<host>:<port>/<db-name>?store=datalog|kv
```

The `store` parameter defaults to `datalog`; use `store=kv` for the direct
key-value store. Database names must be unique within a server.

### 2.4 Using the Same API

Once connected to a server, the same API for embedded mode can be used to
transact and query data.

---

## 3. Replication and High Availability (HA)

Datalevin has two distinct server-side replication modes.

For simple read scaling without automatic failover, configure a non-HA async
read-only replica with `:replica/read-only? true` and `:replica/source`. The
primary database must have WAL enabled. The replica bootstraps from the primary
copy interface, tails durable WAL records, serves normal reads, and rejects user
writes.

For failover, use consensus-lease HA. In this mode, each database has one write
leader at a time; followers replicate WAL records and can serve reads. Promotion
is conservative and uses a Raft-backed control plane, bounded leases, replica
lag checks, and fencing hooks before a new leader accepts writes. HA databases
force `:wal? true` and default to the `:strict` WAL durability profile.
Membership changes are explicit operator actions through
`datalevin.client/ha-update-membership!`.

Chapter 23 covers details of server behavior, client tuning, async read
replicas, and HA.

---

## 4. Running in Scripts

`dtlv` itself can be used in shell scripting directly, as it supports common
features (e.g. pipes and heredoc) of standard IO based transaction and query.

For sophisticated scripting needs, Babashka (bb) is a great command line tool.
When a database become necessary in your Babashka scripts, Datalevin can serve
as a Babashka pod to support Datalog transaction and query:

```clojure
;; save as script.clj
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.10.18")
(require '[pod.huahaiy.datalevin :as d])

(let [conn (d/get-conn "/tmp/bb-db")]
  (d/transact! conn [{:msg "Hello from Babashka"}])
  (println (d/q '[:find ?m :where [_ :msg ?m]] (d/db conn))))
```

Run it with `bb script.clj`.

If the `dtlv` executable is already on your `PATH`, Babashka can load that local
binary directly:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod "dtlv")
(require '[pod.huahaiy.datalevin :as d])
```

The pod also provides `defpodfn`, which lets a script define a custom function
and call it from a query:

```clojure
(d/defpodfn greeting [name] (str "hello " name))

(d/q '[:find ?message
       :where [(greeting "world") ?message]])
;; => #{["hello world"]}
```

---

## 5. Running as an MCP Server

For local AI agent applications and MCP-compliant clients, `dtlv mcp` runs a
Datalevin MCP server over `stdio`:

```console
dtlv mcp
dtlv --allow-writes mcp
```

Read-only mode is the default. Write tools must be enabled explicitly. The MCP
process can open local databases directly or use `dtlv://` URIs behind the local
`stdio` process.

We will show an example of using Datalevin MCP server from Codex. Other agents
should be similar.

### 5.1 Using Datalevin MCP from Codex

Codex can launch MCP servers from `~/.codex/config.toml`. To make the Datalevin
tools available in read-only mode, add a server definition like this:

```toml
[mcp_servers.datalevin]
command = "dtlv"
args = ["mcp"]
```

Restart Codex after changing the config. You can then ask Codex to inspect a
local Datalevin database path or a remote `dtlv://` database through the MCP
tools:

```text
Use the Datalevin MCP server to open /path/to/mydb and list the schema attributes.
```

Keep the default server read-only for exploration. If you want Codex to run
transactions, configure a separate, explicitly named writable server:

```toml
[mcp_servers.datalevin-writable]
command = "dtlv"
args = ["--allow-writes", "mcp"]
```

---

## 6. Next Steps

Now we have covered the many modes of using Datalevin that serve different use
cases. The same simple database principles are behind all the operational modes,
which we will cover in the next chapters.

- **Chapter 3** explains the mental model of facts and datoms.
- **Chapter 4** explains LMDB, DLMDB, WAL, and storage trade-offs.
- **Chapter 5** covers attributes, entities, and namespaces.
