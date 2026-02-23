---
title: "Deployment Patterns: Embedded, Server, and Pods"
chapter: 29
part: "VI — Systems and Operations"
---

# Chapter 29: Deployment Patterns: Embedded, Server, and Pods

Datalevin is designed to be highly portable. Whether you are building a small CLI tool or a massive distributed system, you can deploy Datalevin in the mode that best fits your operational requirements.

This chapter explores the architectural trade-offs of the three primary deployment modes: **Embedded**, **Server**, and **Babashka Pods**.

---

## 1. Embedded Mode: Direct Library Integration

Embedded mode is the "native" way to use Datalevin. You include it as a standard Clojure or Java library in your application's `deps.edn` or `project.clj`.

- **Architecture**: Your application and Datalevin share the same process and address space.
- **Performance**: This is the fastest possible deployment. Because Datalevin uses memory-mapping (mmap), your application reads data directly from the OS Page Cache with **zero-copy overhead**.
- **Locking**: A single process owns the "Writer Lock" on the database file.

### 1.1 Installation

```clojure
;; deps.edn
{:deps {datalevin/datalevin {:mvn/version "0.10.5"}}}
```

```clojure
;; project.clj (Leiningen)
[datalevin "0.10.5"]
```

### 1.2 Performance Tips

Add these JVM options for 5-20% better performance:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```

**Best for**:
- High-performance, single-node services.
- Containerized applications where each container has its own volume.
- Desktop applications or local tools.

---

## 2. Server Mode: Centralized Data Management

If you have multiple services that need to share a single Datalevin database, or if you want to manage your database as a standalone system, use **Server Mode**.

### 2.1 Starting the Server

```console
# Using the native CLI tool
$ dtlv serv -r /data/datalevin -p 8898

# Using JVM uberjar (recommended for high concurrency)
$ java --add-opens=java.base/java.nio=ALL-UNNAMED \
       --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
       -jar datalevin-0.10.5-standalone.jar serv -r /data/datalevin
```

> **Note**: The native CLI tool uses GraalVM with SerialGC, which limits throughput. Use the JVM uberjar for high-concurrency production workloads.

### 2.2 Default Credentials

The server comes with a built-in admin user:
- **Username**: `datalevin`
- **Password**: `datalevin`

Change the password using:
```clojure
(require '[datalevin.client :as c])
(def client (c/new-client "dtlv://datalevin:datalevin@localhost:8898"))
(c/reset-password client "datalevin" "new-password")
```

### 2.3 Client Connection

Connect to a remote database using a connection URI:

```clojure
(def conn (d/get-conn "dtlv://user:pass@localhost:8898/mydb"))
```

URI format: `dtlv://<user>:<pass>@<host>:<port>/<db-name>?store=datalog|kv`

- `store` defaults to `datalog`, use `kv` for key-value store
- Database is created automatically if it doesn't exist

### 2.4 Architecture

- **Non-blocking server**: Uses event-driven architecture with a work-stealing thread pool
- **Transparent API**: Same functions work for local and remote databases
- **Wire protocol**: Inspired by PostgreSQL, using TLV message format
- **Serialization**: Default is Nippy (fast, Clojure-specific); transit+json available for cross-language clients

### 2.5 Security: Role-Based Access Control

Datalevin server implements full **RBAC**:

- **Permissions**: `:view`, `:alter`, `:create`, `:control` (each implies the former)
- **Objects**: `:user`, `:role`, `:database`, `:server` (each implies the former)
- **Create users and roles**:
```clojure
(c/create-user client "alice" "password123")
(c/create-role client "reader")
(c/assign-role client "alice" "reader")
(c/grant-permission client "reader" :datalevin.server/view "mydb")
```

**Best for**:
- Microservices architectures.
- Shared development environments.
- Scenarios requiring fine-grained user permissions.

---

## 3. Babashka Pods: High-Performance Scripting

Babashka is a fast-starting Clojure interpreter for scripting. Datalevin provides a **Pod** that allows you to use its Datalog and KV capabilities within a Babashka script.

### 3.1 Installation

```bash
# Via Babashka pod registry
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.10.5")

# Or via homebrew
brew install huahaiy/brew/datalevin
```

### 3.2 Usage

```clojure
(require '[pod.huahaiy.datalevin :as d])

(def conn (d/get-conn "/tmp/test"))
(d/transact! conn [{:name "hello"}])
(d/q '[:find ?n :where [_ :name ?n]] (d/db conn))
```

**Best for**:
- CLI tools that store state.
- Database maintenance scripts.
- Ephemeral tasks or "Lambda"-style functions.

---

## 4. Comparison Table

| Feature | Embedded | Server | Babashka Pod |
| :--- | :--- | :--- | :--- |
| **Performance** | Maximum (Zero-copy) | High (TCP + serialization) | High (IPC) |
| **Setup** | Library dependency | Separate process | Single binary |
| **Security** | OS-level | Full RBAC | OS-level |
| **Concurrency** | Single writer | Multi-client | Single writer |
| **Language** | JVM (Clojure/Java) | Any via TCP | Babashka |

---

## Summary: Designing for Your Lifecycle

Datalevin's deployment flexibility allows your application to evolve.

1.  **Prototype with Pods**: Use the Babashka Pod for quick scripts and local experiments.
2.  **Scale with Embedded**: When building your production application, use Embedded mode for maximum performance.
3.  **Expand with Server**: If you need to share data across microservices or teams, "promote" to Server mode.

Because the underlying data format is identical in all modes, you can move between patterns without data migration.
