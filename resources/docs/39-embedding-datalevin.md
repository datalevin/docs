---
title: "Embedding Datalevin in Applications"
chapter: 39
part: "VIII — Internals and Extensions"
---

# Chapter 39: Embedding Datalevin in Applications

Datalevin's primary deployment mode is **Embedded** (Chapter 29). This means the database is not a remote service you "talk to," but a library that your application "becomes."

This chapter covers the practical software engineering patterns for integrating Datalevin into your application lifecycle, testing suite, and deployment pipeline.

---

## 1. Project Setup and Lifecycle

To use Datalevin, add the dependency to your project:

```clojure
;; deps.edn
{datalevin/datalevin {:mvn/version "0.9.22"}}
```

### 1.1 Managing the Connection
Datalevin requires a clean shutdown to ensure the LMDB environment is closed correctly. In Clojure, it is best practice to manage this via a state management library like **Integrant** or **Mount**, or a simple `with-open` block.

<div class="multi-lang">

```clojure
(defn start-app [config]
  (let [conn (d/get-conn (:db-path config) schema)]
    {:conn conn}))

(defn stop-app [system]
  (d/close-conn (:conn system)))
```

```java
import datalevin.core.*;

// Start: open a connection
Connection conn = Datalevin.getConn(config.getDbPath(), schema);

// Stop: close the connection
Datalevin.closeConn(conn);
```

```python
# Start: open a connection
conn = d.get_conn(config["db_path"], schema)

# Stop: close the connection
d.close_conn(conn)
```

```javascript
// Start: open a connection
const conn = d.getConn(config.dbPath, schema);

// Stop: close the connection
d.closeConn(conn);
```

</div>

---

## 2. Schema Management in Embedded Apps

Since Datalevin is schema-on-write, your application code is the "source of truth" for your schema.

- **Centralized Schema**: Define your schema in a dedicated namespace.
- **Auto-Update**: On application startup, call `d/update-schema` to ensure the database matches your latest code. Datalevin handles the data migration for new types automatically (Chapter 5).

---

## 3. Testing Strategies

Datalevin makes testing incredibly fast and simple.

### 3.1 Fast In-Memory Tests
Use the `:temp? true` option when creating a connection for your tests. This creates a temporary database in a ramdisk or temporary directory that is automatically deleted when the JVM exits.

<div class="multi-lang">

```clojure
(def test-conn (d/get-conn "/tmp/test-db" schema {:temp? true}))
```

```java
import datalevin.core.*;

Connection testConn = Datalevin.getConn("/tmp/test-db", schema, Map.of("temp?", true));
```

```python
test_conn = d.get_conn("/tmp/test-db", schema, {"temp?": True})
```

```javascript
const testConn = d.getConn('/tmp/test-db', schema, { 'temp?': true });
```

</div>

### 3.2 Test Isolation
Because Datalevin startup is sub-millisecond, you can afford to create a **fresh database for every single test case**. This eliminates "leaky state" between tests and makes your suite much more reliable.

---

## 4. Handling Errors and Conflicts

- **Writer Lock Timeout**: If one thread holds the writer lock for too long, others will timeout. Ensure your transactions are small and fast.
- **Validation Errors**: Use Clojure Spec or Malli to validate your data *before* calling `d/transact!`. This provides better error messages than the low-level database errors.

---

## 5. Summary: The Integrated Experience

Embedding Datalevin transforms how you think about data.

- **No Network Latency**: Every query is a direct memory read.
- **Unified State**: Your application and database share the same lifecycle.
- **Simplified Ops**: No database server to provision, monitor, or upgrade separately.

By following these lifecycle and testing patterns, you can leverage the full power of a multi-paradigm database with the simplicity of a standard library.
