---
title: "Getting Started: Embedded, Server, CLI, Babashka, and MCP Modes"
chapter: 2
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 2: Getting Started: Embedded, Server, CLI, Babashka, and MCP Modes

Datalevin can be used as an embedded database in the form of a library, a
standalone server, a server with read-only replicas, a high availability server
cluster, a command-line tool, a Babashka pod, or a local MCP tool server. The
same core database concepts appear in each mode: a path or URI names the
database, schema describes attribute behavior, transactions add or retract
facts, and Datalog queries read through a current DB object.

This chapter gives the shortest reliable path into each supported mode.

---

## Before You Start: Choose a Mode

Most readers should start with embedded mode. It has the fewest moving parts and
uses the same transactions, schema, and Datalog queries as the other modes.
Choose another mode when the deployment shape requires it:

| Mode | Start here when | What you need first |
| :--- | :--- | :--- |
| Embedded library | One application process owns the database path. | Java 21+ and the language package. |
| Standalone server | Multiple services or machines need a shared database endpoint. | `dtlv` or the standalone JVM jar. |
| `dtlv` CLI | You want a REPL, shell scripts, import/export, backup, or maintenance commands. | The `dtlv` executable. |
| Babashka pod | You write fast Clojure scripts with `bb`. | Babashka and either the pod release or local `dtlv`. |
| MCP server | An AI client or agent needs local database tools. | `dtlv` and an MCP-compliant client. |
| Replication or HA | You need read scaling or automatic failover. | Server mode, WAL, and Chapter 22. |

The rest of this chapter follows that order.

---

## 1. Embedded Mode

Datalevin can serve as a library to be embedded in your applications written in
Clojure, Java, Python, or Node.js.

Embedded mode gives your application process direct access to the database
environment. This is the fastest and simplest deployment shape when one
application owns the database path.

Use the language-specific documentation and package pages below for API details,
current package metadata, and additional examples:

- **Clojure:** [Clojure API documentation on cljdoc](https://cljdoc.org/d/datalevin/datalevin).
- **Java:** [JavaDoc](https://javadoc.io/doc/org.datalevin/datalevin-java/latest/datalevin/package-summary.html).
- **Python:** [PyPI package page for `datalevin`](https://pypi.org/project/datalevin/).
- **Node.js:** [npm package page for `datalevin-node`](https://www.npmjs.com/package/datalevin-node).

### 1.1 Supported Platforms

For local embedded storage, the standalone JVM jar, and pre-built `dtlv`
command-line/server binaries, Datalevin supports the following host platforms:

| Operating system | Architecture |
| :--- | :--- |
| Windows | x86_64 / AMD64 |
| macOS | ARM64 / AArch64 |
| Linux | x86_64 / AMD64 |
| Linux | ARM64 / AArch64 |

The language libraries bundle native Datalevin components for these platforms.
If your application must run on a different local platform, prefer server mode
with Datalevin running on a supported host, or build and package the native
components for that platform yourself.

Core Datalog and KV support is not always the same as support for every optional
native feature. Vector and embedding search depend on vector-index native code;
they are supported on Linux x86_64, Linux ARM64, and macOS ARM64, while Windows
support is experimental.

### 1.2 Runtime Requirements

All the language libraries load a JVM Datalevin runtime, and they require **Java
21 or newer**. Some language bindings have their own host-runtime requirements:

- Python: Python 3.10+ and Java 21+
- Node.js: Node.js 20+ and Java 21+

For Clojure and Java applications, pass these JVM flags to the JVM:

```console
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

Python and Node.js bindings add the required JVM flags automatically. On Java 24
and newer, `--enable-native-access=ALL-UNNAMED` suppresses native-access
warnings.

For Clojure CLI, put the flags in an alias:

```clojure
{:aliases
 {:datalevin/jvm
  {:jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
              "--enable-native-access=ALL-UNNAMED"]}}}
```

Then run your program or REPL with that alias:

```console
clojure -M:datalevin/jvm
```

For Leiningen, add top-level `:jvm-opts` in `project.clj`:

```clojure
:jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
           "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
           "--enable-native-access=ALL-UNNAMED"]
```

For Java launch commands, pass the same options before `-cp` or `-jar`:

```console
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -cp target/classes:your-dependencies.jar your.main.Class
```

### 1.3 Native Dependencies

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


### 1.4 Add the Dependency

The examples in this chapter use version `{{datalevin-version}}`. The live docs
site fills this value from the current release configuration; package pages
remain the source of truth if you are reading an older copy.

<div class="multi-lang">

```clojure
;; Embedded-only with clj
{:deps {org.datalevin/datalevin-embedded {:mvn/version "{{datalevin-version}}"}}}

;; Embedded-only with Leiningen
:dependencies [[org.datalevin/datalevin-embedded "{{datalevin-version}}"]]
```

```java
<!-- Maven pom.xml -->
<dependency>
  <groupId>org.datalevin</groupId>
  <artifactId>datalevin-java</artifactId>
  <version>{{datalevin-version}}</version>
</dependency>

// Gradle Kotlin DSL
repositories {
    mavenCentral()
}

dependencies {
    implementation("org.datalevin:datalevin-java:{{datalevin-version}}")
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


### 1.5 Your First Transaction and Query

Open a REPL or write a small program to create a database, transact data, and query it.

This is your first Datalog query in the book. Do not worry if the syntax is not
fully familiar yet: for now, read it as "find the names of users whose age is
greater than 28." Chapter 3 explains the fact model, and Chapter 8 explains the
query syntax in detail.

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

For tests, examples, and scratch work, an embedded Datalog database can be
opened in memory only:

```clojure
(def conn
  (d/create-conn nil schema {:kv-opts {:inmemory? true}}))
```

This mode does not persist data to disk; closing the connection or ending the
process loses the database contents. Use a real directory path, as in the first
example above, when the data must survive process restart. Chapter 10 shows the
same option for direct KV stores with `open-kv`. This example uses
`d/create-conn` because it is constructing a fresh in-memory connection; the
next section explains how that differs from `d/get-conn`.

### 1.6 Connection Lifecycle

The examples above close the connection at the end because they are complete
small programs. A long-running application should normally keep **one shared
connection per Datalevin database** in a process and pass that connection to the
parts of the application that need it. Do not open and close a local embedded
connection for every web request, job, command, or repository call.

Treat a Datalevin connection as a stateful application resource, like a server
socket or component-system dependency. Create it during application startup,
reuse it across threads, and close it during application shutdown. In Clojure,
`d/get-conn` will reuse an existing connection to the same database when one is
already open.

`d/get-conn` and `d/create-conn` both open Datalog connections, but they have
different lifecycle intent:

| Function | Use it when |
| :--- | :--- |
| `d/get-conn` | You want the normal application path: create the database if needed, open it if closed, and reuse the already-open connection for the same path or `dtlv://` URI. |
| `d/create-conn` | You deliberately want a new connection object, such as for an in-memory database, a controlled test fixture, or a specialized construction path. |

For a persistent application database, prefer `d/get-conn` at the application
boundary and share the returned connection. Java's `Datalevin.createConn`,
Python's `connect`, and Node.js's `connect` are the corresponding language
binding entry points; manage their returned connection objects with the same
"open once, share, close on shutdown" discipline.

Short-lived connections are fine for REPL experiments, scripts, tests, and
single-shot examples. The production pattern is different: hold the connection,
share it deliberately, and make shutdown responsible for closing it. Remote
`dtlv://` clients still need normal client lifecycle management, but that is
separate from repeatedly opening the same local embedded database path.

---

## 2. Running as a Standalone Server

Server mode is the right choice when multiple clients or services need a shared
database endpoint.

You can run a Datalevin server either in the form of a JVM standalone jar or a
GraalVM native image. For production use, the JVM standalone jar is recommended:
HotSpot's JIT and mature garbage collectors are well suited to long-running
server workloads and can outperform a native image over time.

For getting started, the examples below use the GraalVM native image.


### 2.1 Install `dtlv`

Datalevin's native image is a command-line program called `dtlv`. It can query,
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

The Docker image exposes the same `dtlv` command surface. To start a local
server from Docker:

```console
docker run --rm \
  -p 8898:8898 \
  -e DATALEVIN_DEFAULT_PASSWORD=secret \
  -v "$PWD/dtlv-data:/data" \
  huahaiy/datalevin serv --host 0.0.0.0 -r /data -p 8898
```

### 2.2 Start a Server

Start a local server with a root data directory:

```console
DATALEVIN_DEFAULT_PASSWORD=secret dtlv serv -r /path/to/data -p 8898
```

By default the server listens on `127.0.0.1:8898`. Use `--host` to bind another
address. Binding to a non-loopback address such as `0.0.0.0` requires
`DATALEVIN_DEFAULT_PASSWORD`; exposing the server beyond localhost must not rely
on an implicit or empty administrative credential.

You can start the same server from the standalone JVM jar when you prefer a
plain Java process:

```console
DATALEVIN_DEFAULT_PASSWORD=secret \
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -jar datalevin-{{datalevin-version}}-standalone.jar serv --host 0.0.0.0 -r /data/dtlv
```

In Clojure/JVM applications, server mode can also be embedded in the host
process with `datalevin.server/create`, `datalevin.server/start`, and
`datalevin.server/stop`. This lets an application keep local embedded access
while exposing a `dtlv://` endpoint for tools, workers, or non-JVM clients. See
Chapter 22 for the lifecycle pattern.

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

### 2.4 Smoke Test the Server

Once connected to a server, the same API for embedded mode can be used to
transact and query data. A quick smoke test with `dtlv exec` is:

The `dtlv exec` snippets use Clojure syntax. In Clojure, `@conn` dereferences
the connection and returns the current DB object, the same value you would get
from `(d/db conn)` in namespace-qualified application code.

```console
dtlv exec <<'EOF'
(def conn (get-conn "dtlv://datalevin:secret@localhost:8898/getting-started"))
(transact! conn [{:user/name "Server Alice"}])
(q '[:find [?name ...] :where [_ :user/name ?name]] @conn)
(close conn)
EOF
```

The query should return `["Server Alice"]`, possibly with set/vector rendering
depending on the client surface.

### 2.5 Using the Same API

A remote connection uses the same transaction and query APIs as an embedded
connection. The main difference is that the database is named by a `dtlv://`
URI instead of a local filesystem path.

---

## 3. Replication and High Availability (HA)

Datalevin has two distinct server-side replication modes.

For simple read scaling without automatic failover, configure a non-HA async
read-only replica with `:replica/read-only? true` and `:replica/source`. The
primary database must have WAL enabled. The replica bootstraps from the primary
copy interface, tails durable WAL records, serves normal reads, and rejects user
writes.

For failover, use consensus-lease HA. Think of it as one writable server at a
time, with followers keeping copies and serving reads. If the writer fails, a
follower can be promoted only after coordination confirms that it is safe to
accept writes. HA databases require WAL, use the `:strict` WAL durability
profile by default, and treat membership changes as explicit operator actions
through `datalevin.client/ha-update-membership!`.

Chapter 22 covers the details: server behavior, client tuning, async read
replicas, the Raft-backed control plane, bounded leases, replica lag checks,
fencing hooks that stop stale leaders from accepting writes, and HA operations.

---

## 4. Running from the Command Line and Scripts

`dtlv` itself can be used in shell scripting directly, as it supports common
features such as pipes and heredocs for standard I/O based transactions and
queries. The command-line tool uses the same Clojure-oriented Datalevin API as
embedded mode.

For a copy-paste local CLI example:

```console
dtlv exec <<'EOF'
(def conn (get-conn "/tmp/dtlv-cli"))
(transact! conn [{:msg "Hello from dtlv"}])
(q '[:find [?m ...] :where [_ :msg ?m]] @conn)
(close conn)
EOF
```

This creates a local database under `/tmp/dtlv-cli`, writes one fact, queries it,
and closes the connection.

### 4.1 Babashka Pod

For sophisticated scripting needs, Babashka (bb) is a great command line tool.
When a database becomes necessary in your Babashka scripts, Datalevin can serve
as a Babashka pod to support Datalog transaction and query:

```clojure
;; save as script.clj
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "{{datalevin-version}}")
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
`stdio` process. The same `dtlv mcp` command can be launched by any
MCP-compatible client.

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

### 5.2 Using Datalevin MCP from Claude Desktop or Claude Code

Claude Desktop uses a JSON `mcpServers` configuration. In the Developer settings,
edit the Claude Desktop config file and add a server like this:

```json
{
  "mcpServers": {
    "datalevin": {
      "command": "dtlv",
      "args": ["mcp"]
    }
  }
}
```

Claude Code can add the same local stdio server from the command line:

```console
claude mcp add --transport stdio datalevin -- dtlv mcp
```

Restart the client, then ask it to inspect a local Datalevin database path or a
remote `dtlv://` database URI.

---

## 6. Troubleshooting and Cleanup

If the first examples do not run, check these common issues before moving deeper
into the book:

- Run `java -version` and confirm Java 21 or newer.
- For Clojure and Java, make sure the JVM flags in Section 1.2 are actually
  passed to the process that starts Datalevin.
- If you see `java.lang.UnsatisfiedLinkError`, revisit the native dependency
  notes in Section 1.3.
- Do not repeatedly open the same local database path in the same process; use
  the connection lifecycle pattern from Section 1.6.
- If examples use `/tmp/mydb`, `/tmp/dtlv-cli`, `/tmp/bb-db`, or
  `$PWD/dtlv-data`, remove those directories before rerunning from a clean
  slate.
- For server examples, confirm the server process is still running, the port is
  `8898`, and the password in the URI matches `DATALEVIN_DEFAULT_PASSWORD`.
- Keep MCP servers read-only until you deliberately need write tools.

---

## 7. Next Steps

Now we have covered the many modes of using Datalevin that serve different use
cases. The same simple database principles are behind all the operational modes,
which we will cover in the next chapters.

- **Chapter 3** explains the mental model of facts and datoms.
- **Chapter 4** explains LMDB, DLMDB, WAL, and storage trade-offs.
- **Chapter 5** covers attributes, entities, and namespaces.
