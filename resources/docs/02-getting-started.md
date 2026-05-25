---
title: "Getting Started: Embedded, Server, Native Language Libraries, and Babashka Pod Modes"
chapter: 2
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 2: Getting Started: Embedded, Server, Native Language Libraries, and Babashka Pod Modes

Datalevin can be used as an embedded database, a standalone server, a native-language library, a command-line tool, a Babashka pod, or a local MCP tool server. The same core database concepts appear in each mode: a path or URI names the database, schemas describe attribute behavior, transactions add or retract facts, and Datalog queries read a database value.

This chapter gives the shortest reliable path into each supported mode.

---

## 1. Runtime Requirements

Current Datalevin releases require **Java 21 or newer**. The Clojure library, Java library, Python package, and Node.js package all load a JVM Datalevin runtime. Python wheels and the Node package vendor the Datalevin runtime jar, but they still need a Java 21+ runtime available.

Optional JVM flags improve performance and quiet newer Java native-access warnings:

```console
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

---

## 2. Embedded Mode

Embedded mode gives your process direct access to the LMDB/DLMDB environment. This is the fastest and simplest deployment shape when one application owns the database path.

### 2.1 Add the Dependency

<div class="multi-lang">

```clojure
;; deps.edn
{:deps {datalevin/datalevin {:mvn/version "0.10.15"}}}

;; Embedded-only JVM artifact without server/HA/CLI/pod runtime code
{:deps {org.datalevin/datalevin-embedded {:mvn/version "0.10.15"}}}
```

```java
<!-- Maven pom.xml -->
<dependency>
  <groupId>org.datalevin</groupId>
  <artifactId>datalevin-java</artifactId>
  <version>0.10.15</version>
</dependency>
```

```python
# pip
python -m pip install datalevin
```

```javascript
// npm
npm install datalevin-node
```

</div>

### 2.2 Your First Query

Open a REPL or small program and create a database, transact data, and query it.

> **Note**: For local embedded Datalog and KV stores, WAL mode is off by default. Enable it explicitly with `{:wal? true}` when you want WAL throughput, recovery, or replication behavior. In non-HA WAL mode, the default durability profile is `:relaxed`; use `:strict` or `:extra` when the opt-in is primarily for crash durability.

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

## 3. Running as a Standalone Server

Server mode is the right choice when multiple clients or services need a shared database endpoint.

### 3.1 Download and Start

Install the `dtlv` command-line tool with Homebrew, Scoop, Docker, or a release download, then start the server:

```console
DATALEVIN_DEFAULT_PASSWORD=secret dtlv serv -r /path/to/data -p 8898
```

By default the server listens on `127.0.0.1:8898`. Use `--host` to bind another address. Binding to a non-loopback address such as `0.0.0.0` requires `DATALEVIN_DEFAULT_PASSWORD`, because the built-in administrative user is named `datalevin`.

For demanding long-running server workloads, prefer the JVM standalone jar so HotSpot can use a production GC:

```console
DATALEVIN_DEFAULT_PASSWORD=secret \
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     -jar datalevin-0.10.15-standalone.jar serv --host 0.0.0.0 -r /data/dtlv
```

### 3.2 Connect with the Same API

Remote databases use `dtlv://` URIs:

```clojure
(def conn
  (d/get-conn "dtlv://datalevin:secret@localhost:8898/mydb"))
```

The URI shape is:

```text
dtlv://<user>:<pass>@<host>:<port>/<db-name>?store=datalog|kv
```

The `store` parameter defaults to `datalog`; use `store=kv` for the direct key-value store. Database names must be unique within a server.

---

## 4. Replication and High Availability

Datalevin has two distinct server-side replication modes.

For simple read scaling without automatic failover, configure a non-HA async read-only replica with `:replica/read-only? true` and `:replica/source`. The primary database must have WAL enabled. The replica bootstraps from the primary copy interface, tails durable WAL records, serves normal reads, and rejects user writes.

For failover, use consensus-lease HA. In this mode, each database has one write leader at a time; followers replicate WAL records and can serve reads. Promotion is conservative and uses a Raft-backed control plane, bounded leases, replica lag checks, and fencing hooks before a new leader accepts writes. HA databases force `:wal? true` and default to the `:strict` WAL durability profile. Membership changes are explicit operator actions through `datalevin.client/ha-update-membership!`.

Chapter 27 covers server behavior, client tuning, async read replicas, and HA details.

---

## 5. Scripting with Babashka Pods

For quick scripts or CLI tools, use the Datalevin Babashka pod:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.10.15")
(require '[pod.huahaiy.datalevin :as d])

(let [conn (d/get-conn "/tmp/bb-db")]
  (d/transact! conn [{:msg "Hello from Babashka"}])
  (println (d/q '[:find ?m :where [_ :msg ?m]] (d/db conn))))
```

Run it with `bb script.clj`.

---

## 6. MCP Tool Server

For local AI applications and MCP-compliant clients, `dtlv mcp` runs a Datalevin MCP server over `stdio`:

```console
dtlv mcp
dtlv --allow-writes mcp
```

Read-only mode is the default. Write tools must be enabled explicitly. The MCP process can open local databases directly or use `dtlv://` URIs behind the local `stdio` process.

---

## 7. Next Steps

- **Chapter 3** explains the mental model of facts and datoms.
- **Chapter 4** explains LMDB, DLMDB, WAL, and storage trade-offs.
- **Chapter 5** covers attributes, entities, and namespaces.
