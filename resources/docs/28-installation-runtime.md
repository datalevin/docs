---
title: "Appendix A: Installation, Runtimes, and Deployment Modes"
chapter: 28
part: "VII — Appendices"
---

# Appendix A: Installation, Runtimes, and Deployment Modes

This appendix collects setup material that would otherwise interrupt the main
chapters. Use Chapter 2 for the first embedded session. Use this appendix when
you need platform requirements, package coordinates, server startup commands,
`dtlv`, Docker, Babashka, MCP, or troubleshooting notes.

---

## 1. Choose a Mode

Datalevin can be used as an embedded database, a standalone server, a server
with read-only replicas, a high-availability server cluster, a command-line
tool, a Babashka pod, or a local MCP tool server. The same core concepts appear
in each mode: a path or URI names the database, schema describes attribute
behavior, transactions add or retract facts, and Datalog queries read through a
current database value.

Most readers should start with embedded mode. It has the fewest moving parts
and uses the same transactions, schema, and Datalog queries as the other modes.

| Mode | Start here when | What you need first |
| :--- | :--- | :--- |
| Embedded library | One application process owns the database path. | Java 21+ and the language package. |
| Standalone server | Multiple services or machines need a shared database endpoint. | `dtlv` or the standalone JVM jar. |
| `dtlv` CLI | You want a REPL, shell scripts, import/export, backup, or maintenance commands. | The `dtlv` executable. |
| Babashka pod | You write fast Clojure scripts with `bb`. | Babashka and either the pod release or local `dtlv`. |
| MCP server | An AI client or agent needs local database tools. | `dtlv` and an MCP-compliant client. |
| Replication or HA | You need read scaling or automatic failover. | Server mode, WAL, and Chapter 22. |

---

## 2. Supported Platforms

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

---

## 3. Runtime Requirements

All language libraries load a JVM Datalevin runtime and require Java 21 or
newer. Some language bindings have their own host-runtime requirements:

- Python: Python 3.10+ and Java 21+
- Node.js: Node.js 20+ and Java 21+

For Clojure and Java applications, pass these JVM flags to the JVM:

```console
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

Python and Node.js bindings add the required JVM flags automatically. On Java
24 and newer, `--enable-native-access=ALL-UNNAMED` suppresses native-access
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

---

## 4. Native Dependencies

Datalevin bundles its non-JVM native libraries for the supported platforms. If
loading fails with `java.lang.UnsatisfiedLinkError`, first confirm that the
system `libc` for your OS is present. Some Linux distributions may not include
it by default.

If bundled OpenMP/vector math libraries do not work on your machine, install
the platform packages directly:

```console
# Debian/Ubuntu
sudo apt-get install libgomp1

# macOS
brew install libomp llvm
```

---

## 5. Embedded Language Packages

Use the language-specific documentation and package pages below for API details,
current package metadata, and additional examples:

- **Clojure:** [Clojure API documentation on cljdoc](https://cljdoc.org/d/datalevin/datalevin).
- **Java:** [JavaDoc](https://javadoc.io/doc/org.datalevin/datalevin-java/latest/datalevin/package-summary.html).
- **Python:** [PyPI package page for `datalevin`](https://pypi.org/project/datalevin/).
- **Node.js:** [npm package page for `datalevin-node`](https://www.npmjs.com/package/datalevin-node).

For the current cross-language API surface, use Datalevin's
[language compatibility matrix](https://github.com/datalevin/datalevin/blob/master/doc/language-compatibility.md)
as the source of truth. This book does not reproduce that matrix.

The notable gaps to keep in mind while reading are:

- JavaScript does not expose the Datalog transaction callback API
  (`with-transaction` / `withTransaction`). Use a single `conn.transact(...)`,
  `conn.transactAsync(...)`, KV transaction APIs, or move the callback logic
  into a transaction function or application command.
- Existing entity objects are transactable only in Clojure. Java, Python, and
  JavaScript support lazy entity reads, but staged entity-object mutation should
  be written as transaction maps, datom forms, or binding transaction builders.

The dependency examples below use released package version
`{{datalevin-version}}`.

<div class="multi-lang">

```clojure
;; Embedded-only with Clojure CLI
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

---

## 6. Install `dtlv`

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

---

## 7. Standalone Server Mode

Server mode is the right choice when multiple clients or services need a shared
database endpoint.

You can run a Datalevin server either as a JVM standalone jar or a GraalVM
native image. For production use, the JVM standalone jar is recommended:
HotSpot's JIT and mature garbage collectors are well suited to long-running
server workloads and can outperform a native image over time.

For getting started, start a local server with `dtlv`:

```console
DATALEVIN_DEFAULT_PASSWORD=secret dtlv serv -r /path/to/data -p 8898
```

By default the server listens on `127.0.0.1:8898`. Use `--host` to bind another
address. Binding to a non-loopback address such as `0.0.0.0` requires
`DATALEVIN_DEFAULT_PASSWORD`; exposing the server beyond localhost must not rely
on an implicit or empty administrative credential.

You can start the same server from the standalone JVM jar:

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

---

## 8. Connect to a Server

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

Once connected to a server, the same transaction and query APIs used in embedded
mode can be used with the remote connection.

---

## 9. Smoke Test the Server

A quick smoke test with `dtlv exec` is:

```console
dtlv exec <<'EOF'
(def conn (get-conn "dtlv://datalevin:secret@localhost:8898/getting-started"))
(transact! conn [{:user/name "Server Alice"}])
(q '[:find [?name ...] :where [_ :user/name ?name]] @conn)
(close conn)
EOF
```

The query should return `["Server Alice"]`, possibly with set/vector rendering
depending on the client surface. In Clojure, `@conn` dereferences the connection
and returns the current database value, the same value you would get from
`(d/db conn)` in namespace-qualified application code.

---

## 10. Replication and High Availability

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

## 11. Command Line and Scripts

`dtlv` can be used in shell scripts directly. It supports common patterns such
as pipes and heredocs for standard-I/O transactions and queries. The command
line tool uses the same Clojure-oriented Datalevin API as embedded mode.

For a copy-paste local CLI example:

```console
dtlv exec <<'EOF'
(def conn (get-conn "/tmp/dtlv-cli"))
(transact! conn [{:msg "Hello from dtlv"}])
(q '[:find [?m ...] :where [_ :msg ?m]] @conn)
(close conn)
EOF
```

This creates a local database under `/tmp/dtlv-cli`, writes one fact, queries
it, and closes the connection.

---

## 12. Babashka Pod

For scripting, Babashka (`bb`) is a fast-starting Clojure runtime. When a
database becomes necessary in a Babashka script, Datalevin can serve as a pod:

```clojure
;; save as script.clj
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "{{datalevin-version}}")
(require '[pod.huahaiy.datalevin :as d])

(let [conn (d/get-conn "/tmp/bb-db")]
  (d/transact! conn [{:msg "Hello from Babashka"}])
  (println (d/q '[:find ?m :where [_ :msg ?m]] (d/db conn))))
```

Run it with:

```console
bb script.clj
```

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

## 13. MCP Server

For local AI agent applications and MCP-compliant clients, `dtlv mcp` runs a
Datalevin MCP server over `stdio`:

```console
dtlv mcp
dtlv --allow-writes mcp
```

Read-only mode is the default. Write tools must be enabled explicitly. The MCP
process can open local databases directly or use `dtlv://` URIs behind the local
`stdio` process.

### 13.1 Codex

Codex can launch MCP servers from `~/.codex/config.toml`. To make the Datalevin
tools available in read-only mode, add a server definition:

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

### 13.2 Claude Desktop and Claude Code

Claude Desktop uses a JSON `mcpServers` configuration. In the Developer
settings, edit the Claude Desktop config file and add a server:

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

## 14. Troubleshooting and Cleanup

If the first examples do not run, check these common issues before moving deeper
into the book:

- Run `java -version` and confirm Java 21 or newer.
- For Clojure and Java, make sure the JVM flags in Section 3 are actually
  passed to the process that starts Datalevin.
- If you see `java.lang.UnsatisfiedLinkError`, revisit the native dependency
  notes in Section 4.
- Do not repeatedly open the same local database path in the same process; use
  the connection lifecycle pattern from Chapter 2.
- If examples use `/tmp/mydb`, `/tmp/dtlv-cli`, `/tmp/bb-db`, or
  `$PWD/dtlv-data`, remove those directories before rerunning from a clean
  slate.
- For server examples, confirm the server process is still running, the port is
  `8898`, and the password in the URI matches `DATALEVIN_DEFAULT_PASSWORD`.
- Keep MCP servers read-only until you deliberately need write tools.

---

## Summary

Embedded mode is the easiest starting point, but the same database model carries
across the server, CLI, Babashka, Docker, and MCP surfaces. Chapter 2 gives the
minimal embedded path; Chapter 22 explains production lifecycle and operations.
