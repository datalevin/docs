---
title: "Getting Started: Your First Datalevin Session"
chapter: 2
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 2: Your First Datalevin Session

To get started, this chapter gives you one small interactive session with
embedded Datalevin: open a database, transact facts, query them, pull an entity,
close the connection, and reopen the same database. The rest of the book builds
on the first example.

For details of getting Datalevin to work in various deployment modes, Appendix A
covers installation, supported platforms, Java/Clojure/Python/JavaScript
packages, `dtlv`, server mode, Babashka pod, Docker, MCP, and troubleshooting.

The print book edition uses Clojure code examples to conserve space and keep the
executable path clear. Again, you do not need to know Clojure to understand the
examples. To understand the shape of the EDN data format we use, consult
Appendix B. The web edition includes parallel Java, Python, and JavaScript
examples where the bindings expose equivalent APIs.

---

## Before You Start

You need Java 21 or newer. First we will create a Clojure project that depends
on the Datalevin library. The examples use released package version
`{{datalevin-version}}`. If you are reading an older copy, check the package
page or the Datalevin GitHub repository for the current release.

For Clojure command line, add the embedded Datalevin dependency:

```clojure
{:deps {org.datalevin/datalevin-embedded {:mvn/version "{{datalevin-version}}"}}}
```

Datalevin uses native storage code through the JVM. For Clojure and Java
programs, pass the required JVM options when starting the process:

```clojure
{:aliases
 {:datalevin/jvm
  {:jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
              "--enable-native-access=ALL-UNNAMED"]}}}
```

Then start a REPL with `clojure -M:datalevin/jvm`.

Appendix A has the full installation matrix, including Leiningen, Java, Python,
Node.js, the standalone `dtlv` executable, Docker, and Babashka setup.

---

## 1. Open a Database

Import the public API namespace `datalevin.core`, define a directory path on
disk, and define a small schema:

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

(def db-path "/tmp/datalevin-book-getting-started")

(def schema
  {:user/name {:db/valueType :db.type/string
               :db/unique    :db.unique/identity}
   :user/age  {:db/valueType :db.type/long}
   :user/city {:db/valueType :db.type/string}})
```

```java
import datalevin.*;

import java.util.List;
import java.util.Map;

String dbPath = "/tmp/datalevin-book-getting-started";

Schema schema = Datalevin.schema()
    .attr("user/name",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY))
    .attr("user/age",
          Schema.attribute().valueType(Schema.ValueType.LONG))
    .attr("user/city",
          Schema.attribute().valueType(Schema.ValueType.STRING));
```

```python
from datalevin import connect

db_path = "/tmp/datalevin-book-getting-started"

schema = {
    ":user/name": {":db/valueType": ":db.type/string",
                   ":db/unique": ":db.unique/identity"},
    ":user/age": {":db/valueType": ":db.type/long"},
    ":user/city": {":db/valueType": ":db.type/string"}}
```

```javascript
import { connect } from "datalevin-node";

const dbPath = "/tmp/datalevin-book-getting-started";

const schema = {
  ":user/name": { ":db/valueType": ":db.type/string",
                  ":db/unique": ":db.unique/identity" },
  ":user/age": { ":db/valueType": ":db.type/long" },
  ":user/city": { ":db/valueType": ":db.type/string" }
};
```

</div>

This example gives the `datalevin.core` namespace an alias `d` for abbreviation
purpose, so that when calling functions in the namespace, we do not have to
write the long namespace, just its alias: `d/q` rather than `datalevin.core/q`,
for example.

A Datalevin schema is a map from attribute keywords to a map of attribute
properties. Here, `:user/name` is an attribute, and its property map says that
this attribute should have values of string type, and it is also a unique
identity, meaning that this attribute can uniquely identify an entity. If a
later transaction uses the same user name, Datalevin can resolve it to the same
entity instead of blindly creating a duplicate.

Open a database connection at the directory path we defined above:

<div class="multi-lang">

```clojure
(def conn (d/get-conn db-path schema))
```

```java
Connection conn = Datalevin.getConn(dbPath, schema);
```

```python
conn = connect(db_path, schema=schema)
```

```javascript
const conn = await connect(dbPath, { schema });
```

</div>

The path names the local database directory. The directory does not need to be
existent, but the parent directory needs to be writable, because if the
directory does not exist, Datalevin creates it. If the database already exists,
Datalevin opens it and uses the supplied schema to ensure the attributes are
available.

---

## 2. Transact Facts

Transact two entities about users to the database:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:user/name "Alice"
    :user/age  30
    :user/city "Paris"}
   {:user/name "Bob"
    :user/age  25
    :user/city "Berlin"}])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/name", "Alice")
        .put("user/age", 30L)
        .put("user/city", "Paris"))
    .entity(Tx.entity()
        .put("user/name", "Bob")
        .put("user/age", 25L)
        .put("user/city", "Berlin")));
```

```python
conn.transact([
    {":user/name": "Alice", ":user/age": 30, ":user/city": "Paris"},
    {":user/name": "Bob", ":user/age": 25, ":user/city": "Berlin"}])
```

```javascript
await conn.transact([
  { ":user/name": "Alice", ":user/age": 30, ":user/city": "Paris" },
  { ":user/name": "Bob", ":user/age": 25, ":user/city": "Berlin" }
]);
```

</div>

In this example, the transaction data is a vector of two maps, and each map
describes facts about one user entity. Datalevin turns those maps into a list of
datoms: entity-attribute-value facts. Logically, this transaction creates six
datoms:

```clojure
[10001 :user/name "Alice"]
[10001 :user/age  30]
[10001 :user/city "Paris"]

[10002 :user/name "Bob"]
[10002 :user/age  25]
[10002 :user/city "Berlin"]
```
In Datalevin, entity ids are 64 bits integers automatically assigned by the
system. The exact numeric entity ids are illustrative only in the example above.

After the transaction data are converted into datoms, the datoms are encoded into
binary form and inserted into underlying key-value storage.

`d/transact!` function returns a transaction report. In a REPL you may see keys
such as `:db-before`, `:db-after`, `:tx-data`, and `:tempids` in the report.
Some application code may want to inspect the report when it needs to see what
datoms are transacted, what ids are generated, or to audit other details.

---

## 3. Query the Database

Read the current database snapshot from the connection and query it:

<div class="multi-lang">

```clojure
(d/q '[:find [?name ...]
       :where
       [?e :user/name ?name]
       [?e :user/age ?age]
       [(> ?age 28)]]
     (d/db conn))
;; => ["Alice"]
```

```java
Object names = conn.query(
    "[:find [?name ...] " +
    " :where " +
    " [?e :user/name ?name] " +
    " [?e :user/age ?age] " +
    " [(> ?age 28)]]");
// => ["Alice"]
```

```python
names = conn.query("""
    [:find [?name ...]
     :where
     [?e :user/name ?name]
     [?e :user/age ?age]
     [(> ?age 28)]]""")
# => ["Alice"]
```

```javascript
const names = await conn.query(
  `[:find [?name ...]
    :where
    [?e :user/name ?name]
    [?e :user/age ?age]
    [(> ?age 28)]]`);
// => ["Alice"]
```

</div>

Here, the Datalog query function `d/q` takes as arguments a query and a database
object.

For now, read the query as: find the names of users whose age is greater than
28. Chapter 8 explains Datalog in detail. The important first point is that a
query is also a piece of data: a vector of keywords and nested vectors.

The `:find` keyword is followed by a vector that specifies what the query
result should be returned, while the `:where` keyword are followed by a list of
vectors, each is called a **where clause**. Two kinds of where clauses are shown
here.

`[?e :user/name ?name]` is a triple pattern, which describes facts that must be
true for the query to return non-empty results. The entity position of the match
pattern is a `?e` variable, the attribute is given, and the value position is a
`?name` variable. When a datom in the database matches this triple pattern, its
entity id and value will be bound to these two variables, respectively.

`[(> ?age 28)]` is a predicate, a boolean function that must be evaluated as
`true` for the query to return non-empty results. `>` is the function name. This
predicate tests if the `?age` variable, if bound, has a value greater than 28.

Careful readers may notice that the whole query was prefixed by a quotation mark
`'`. This is to prevent the query data from being evaluated as running code. The
variable symbols in the query are meaningful only inside that query, not for the
surrounding code, where they would be considered as undefined.

`(d/db conn)` returns an database object that holds a snapshot of the current
database view. Reads use a stable view while writes advance the connection to a
newer database object.

---

## 4. Pull One Entity

Unique attributes can be used as lookup refs to find an entity. A lookup ref is
a pair of attribute and value that uniquely identifies an entity:

<div class="multi-lang">

```clojure
(d/pull (d/db conn)
        [:user/name :user/age :user/city]
        [:user/name "Alice"])
;; => {:user/name "Alice", :user/age 30, :user/city "Paris"}
```

```java
Map<?, ?> alice =
    conn.pull("[:user/name :user/age :user/city]",
              List.of("user/name", "Alice"));
// => {user/name=Alice, user/age=30, user/city=Paris}
```

```python
alice = conn.pull("[:user/name :user/age :user/city]",
                  [":user/name", "Alice"])
# => {":user/name": "Alice", ":user/age": 30, ":user/city": "Paris"}
```

```javascript
const alice = await conn.pull(
  "[:user/name :user/age :user/city]",
  [":user/name", "Alice"]);
// => { ":user/name": "Alice", ":user/age": 30, ":user/city": "Paris" }
```

</div>

This works because attribute `:user/name` has `db.unique/identity` property in
the schema. The important part is that `[:user/name "Alice"]` names the entity
without requiring you to know its internal entity id.

---

## 5. Close and Reopen

Close the connection when the process is done with it:

<div class="multi-lang">

```clojure
(d/close conn)
```

```java
conn.close();
```

```python
conn.close()
```

```javascript
await conn.close();
```

</div>

Because the database was persistent on disk at the specified path, the facts
survive process restart. Open the same path again and query the data:

<div class="multi-lang">

```clojure
(def conn2 (d/get-conn db-path schema))

(d/q '[:find [?city ...]
       :where
       [?e :user/name "Bob"]
       [?e :user/city ?city]]
     (d/db conn2))
;; => ["Berlin"]

(d/close conn2)
```

```java
Connection conn2 = Datalevin.getConn(dbPath, schema);

Object cities = conn2.query(
    "[:find [?city ...] " +
    " :where " +
    " [?e :user/name \"Bob\"] " +
    " [?e :user/city ?city]]");
// => ["Berlin"]

conn2.close();
```

```python
conn2 = connect(db_path, schema=schema)

cities = conn2.query("""
    [:find [?city ...]
     :where
     [?e :user/name "Bob"]
     [?e :user/city ?city]]""")
# => ["Berlin"]

conn2.close()
```

```javascript
const conn2 = await connect(dbPath, { schema });

const cities = await conn2.query(
  `[:find [?city ...]
    :where
    [?e :user/name "Bob"]
    [?e :user/city ?city]]`);
// => ["Berlin"]

await conn2.close();
```

</div>

If you want to rerun the whole session from a blank database, remove the
directory named by `db-path` before starting again, or start with a new path.

---

## 6. Use an In-Memory Database for Scratch Work

For tests, examples, and one-off scratch work, a Datalog database can be opened
in memory:

<div class="multi-lang">

```clojure
(def mem-conn
  (d/create-conn nil schema {:kv-opts {:inmemory? true}}))
```

```java
Connection memConn = Datalevin.createConn(
    null,
    schema,
    Map.of("kv-opts", Map.of("inmemory?", true)));
```

```python
mem_conn = connect(
    None,
    schema=schema,
    opts={":kv-opts": {":inmemory?": True}})
```

```javascript
const memConn = await connect(null, {
  schema,
  opts: { ":kv-opts": { ":inmemory?": true } }
});
```

</div>

`{:kv-opts {:inmemory? true}}` is an option map given when opening the database
connection. It passes the `:inmemory? true` option to the underlying key value
store. With this, this database does not persist to disk. Closing the connection
or ending the process loses the contents. Use a real directory path when the
data must survive process restart.

---

## 7. Connection Lifecycle

A long-running application should normally keep one shared connection per
Datalevin database in a process. Create it during application startup, pass it
to the code that needs database access, and close it during application
shutdown. Do not open and close the same local embedded database for every web
request, job, or repository call.

`d/get-conn` and `d/create-conn` both open Datalog connections, but they express
different intent:

| Function | Use it when |
| :--- | :--- |
| `d/get-conn` | You want the normal application path: create the database if needed, open it if closed, and reuse the already-open connection for the same path or `dtlv://` URI. |
| `d/create-conn` | You deliberately want a new connection object, such as for an in-memory database, a controlled test fixture, or a specialized construction path. |

For a persistent application database, prefer `d/get-conn` at the application
boundary and share the returned connection. Short-lived connections are fine for
REPL experiments, scripts, and tests.

---

## Troubleshooting

If the first session does not run:

1. Confirm `java -version` reports Java 21 or newer.
2. Confirm the process includes the JVM options from "Before You Start".
3. Confirm the Datalevin dependency version exists in your package repository.
4. If you see `java.lang.UnsatisfiedLinkError`, use Appendix A's native library
   troubleshooting notes.
5. Remove `/tmp/datalevin-book-getting-started` if you want a completely fresh
   run.

---

## Next Steps

You have seen the core loop: open a connection, transact facts, query a database
snapshot, and close the connection. Chapter 3 explains the mental model behind
those operations. Chapter 4 explains the storage layer that makes the operations
fast and durable. Chapter 5 explains attributes and entities in depth. Appendix
A covers details of Datalevin installation and deployment options.
