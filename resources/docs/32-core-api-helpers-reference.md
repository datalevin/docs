---
title: "Appendix E: Core API Helpers Reference"
chapter: 32
part: "VII — Appendices"
---

# Appendix E: Core API Helpers Reference

This appendix covers public Datalevin functions that are useful in real
programs but too specialized for the main narrative chapters. Prefer the main
chapters for conceptual guidance; use this appendix when you need to remember
the name of a lifecycle, inspection, simulation, or compatibility helper.

---

## 1. Connection and DB Object Helpers

Most applications should open one connection with `get-conn` or `create-conn`,
share that connection, and call `db` when they need the current readable Datalog
DB object. The helpers in this section are for lifecycle checks, inspection, and
specialized construction.

| Function | Use |
| :--- | :--- |
| `conn?` | Returns true for an open local Datalog connection. |
| `closed?` | Returns true when a connection is closed, nil, or points at a nil DB. |
| `db` | Returns the current Datalog DB object behind a connection. Call it again after writes. |
| `opts` | Returns the option map of the Datalog DB. |
| `schema` | Returns the current effective schema. |
| `datalog-kv` | Returns the KV handle backing a Datalog connection or DB. Use this when custom KV DBIs must live in the same store as Datalog data. |
| `close` | Closes a Datalog connection. |
| `close-db` | Closes a raw Datalog DB object. Usually `close` is what application code needs. |
| `conn-from-db` | Wraps an existing DB object in a mutable connection. |
| `conn-from-datoms` | Creates a connection from trusted datoms, with optional dir, schema, and opts. |
| `reset-conn!` | Replaces the contents of a connection with a supplied DB and returns a tx report. |

```clojure
(when (and (d/conn? conn)
           (not (d/closed? conn)))
  {:schema (d/schema conn)
   :opts   (d/opts conn)
   :db     (d/db conn)})
```

`conn-from-db`, `conn-from-datoms`, and `reset-conn!` are specialist tools.
They are useful for tests, import pipelines, and migration utilities, but they
are not the normal way to open an application database.

`datalog-kv` is the supported bridge from a Datalog connection to the
underlying KV APIs. The returned handle is owned by the Datalog connection; do
not close it separately. The same capability is exposed in Java as
`conn.datalogKV()` or `Datalevin.datalogKV(conn)`, in Python as
`conn.datalog_kv()` or `datalog_kv(conn)`, and in JavaScript as
`await conn.datalogKv()` or `await datalogKv(conn)`. Chapter 6 shows how to use
the borrowed KV handle inside `with-transaction` when one atomic operation needs
both Datalog datoms and custom KV writes.

---

## 2. Pull Pattern Syntax Reference

Chapter 7 explains when to use pull. This table is the compact syntax reference
for pull selectors:

| Selector form | Meaning |
| :--- | :--- |
| `[:user/name :user/email]` | Pull named forward attributes. |
| `[*]` | Pull all forward attributes. Datalevin includes `:db/id` if the pattern does not name it. |
| `[[:user/name :as :name]]` | Rename a pulled value in the returned map. |
| `[[:user/friends :limit 5]]` | Limit a cardinality-many attribute. `nil` means no limit. |
| `[[:user/active? :default true]]` | Return a default value when the attribute is missing. |
| `[[:user/name :xform clojure.string/upper-case]]` | Transform the pulled value before returning it. Prefer resolvable symbols across process or language boundaries. |
| `[{:order/customer [:user/name]}]` | Follow a forward reference and pull a nested shape. |
| `[{[:user/friends :limit 5] [:user/name]}]` | Use an attribute expression as a nested map key. |
| `[:order/_customer]` | Pull reverse references for entities that point to the current entity. |
| `[{:order/_customer [:order/id]}]` | Pull a nested shape through a reverse reference. |
| `[{:category/children ...}]` | Recursively pull through a reference until no new entities remain. |
| `[{:category/children 2}]` | Recursively pull through a reference with a positive depth limit. |
| `[{:category/_children 2}]` | Recursively pull through a reverse reference with a positive depth limit. |
| `[(limit :user/friends 5)]` | Legacy limit form. Prefer `[[:user/friends :limit 5]]` in new code. |
| `[(default :user/nickname "unknown")]` | Legacy default form. Prefer `[[:user/nickname :default "unknown"]]` in new code. |

Pull selectors are EDN data. In Clojure, they are usually quoted vectors. In
Java, Python, and JavaScript, they can be supplied as EDN strings or host
language lists/maps accepted by the binding.

Map-spec keys may be attribute names, reverse attribute names, or attribute
expressions with options. `:limit` is valid only for cardinality-many
attributes. Recursive pull tracks visited entities; if a cycle is encountered,
the repeated entity is represented by `{:db/id ...}` instead of expanding
forever.

Component references are owned children. A bare component reference may expand
recursively, because components are part of the parent's logical document. Use
explicit nested patterns or bounded recursion when the response is part of an
external API.

In Datalog queries, pull appears in the `:find` clause:

```clojure
(d/q '[:find [(pull ?e [:user/name]) ...]
       :where [?e :user/email]]
     db)
```

The pattern can also be supplied as an input variable:

```clojure
(d/q '[:find [(pull ?e ?pattern) ...]
       :in $ ?pattern
       :where [?e :user/email]]
     db [:user/name])
```

For multi-source queries, qualify the pull with the source that owns the entity
ids being shaped. In Clojure, pass the DB values in the same order as the
source variables. In Java, Python, and JavaScript connection query helpers, the
receiver connection supplies the first source and additional `Connection`
objects can be passed as query inputs:

<div class="multi-lang">

```clojure
(d/q '[:find ?customer (pull $users ?customer [:user/name])
       :in $users $orders
       :where [$users ?customer :user/email ?email]
              [$orders ?order :order/customer-email ?email]]
     users-db orders-db)
```

```java
Object users = usersConn.query(
    "[:find ?customer (pull $users ?customer [:user/name]) " +
    " :in $users $orders " +
    " :where [$users ?customer :user/email ?email] " +
    "        [$orders ?order :order/customer-email ?email]]",
    ordersConn);
```

```python
users_conn.query(
    '[:find ?customer (pull $users ?customer [:user/name]) '
    ' :in $users $orders '
    ' :where [$users ?customer :user/email ?email] '
    '        [$orders ?order :order/customer-email ?email]]',
    orders_conn)
```

```javascript
const users = await usersConn.query(
  '[:find ?customer (pull $users ?customer [:user/name]) ' +
  ' :in $users $orders ' +
  ' :where [$users ?customer :user/email ?email] ' +
  '        [$orders ?order :order/customer-email ?email]]',
  ordersConn
);
```

</div>

---

## 3. Transaction Observation and Simulation

Chapter 6 gives `listen!` the full transaction-observation treatment. The short
reference form is:

```clojure
(d/listen!
  conn
  :audit
  (fn [{:keys [tx-data tx-meta]}]
    (record-audit-event! tx-data tx-meta)))

(d/unlisten! conn :audit)
```

`listen!` is idempotent for the same key; registering a new callback with the
same key replaces the old callback.

Use `tx-data->simulated-report` when you need the shape of a transaction report
without mutating the database:

```clojure
(def report
  (d/tx-data->simulated-report
    (d/db conn)
    [{:user/email "ada@example.com"
      :user/name  "Ada"}]))

(:tx-data report)
(:db-after report)
```

Like `transact!`, a simulated report may include `:new-attributes` when the
transaction data introduce attributes not already present in the schema.

`resolve-tempid` is a Datomic-compatibility helper for looking up a tempid in a
transaction report's `:tempids` map:

```clojure
(d/resolve-tempid nil (:tempids report) "new-user")
```

In ordinary Clojure code, direct map lookup is usually clearer:

```clojure
(get (:tempids report) "new-user")
```

---

## 4. Datom, Entity, and Type Helpers

These helpers are mostly for inspection, low-level import, and generic tooling:

| Function | Use |
| :--- | :--- |
| `db?` | Tests whether a value is a Datalog DB object. |
| `datom` | Creates a raw datom for trusted low-level import APIs such as `init-db` and `fill-db`. |
| `datom?` | Tests whether a value is a Datalevin datom. |
| `datom-e`, `datom-a`, `datom-v` | Return the entity, attribute, or value component of a datom. |
| `entity-db` | Returns the DB object from which an entity was created. |

```clojure
(def d1 (d/datom 1 :user/email "ada@example.com"))

[(d/datom? d1) (d/datom-e d1) (d/datom-a d1) (d/datom-v d1)]
;=> [true 1 :user/email "ada@example.com"]
```

Application transactions should normally use maps or transaction datom vectors.
Raw datoms are for trusted bulk-loading paths where the caller is responsible
for entity ids, schema compatibility, and value correctness.

---

## 5. Secondary Index Job Helpers

Full-text, vector, and embedding secondary indexes are synchronous by default.
When a domain uses async indexing, these helpers expose the durable job queue:

| Function | Use |
| :--- | :--- |
| `secondary-index-status` | Returns async secondary-index job status for a local Datalog connection. |
| `process-secondary-index-jobs!` | Processes pending jobs, optionally bounded by options such as `:max-jobs`. |
| `wait-for-secondary-index` | Waits until matching secondary-index jobs are complete, with options such as `:tx`, `:type`, `:domain`, `:timeout-ms`, and `:process?`. |

```clojure
(d/secondary-index-status conn)

(d/process-secondary-index-jobs! conn {:max-jobs 100})

(d/wait-for-secondary-index
  conn
  {:tx tx-id
   :timeout-ms 5000
   :process? true})
```

Use these only for async secondary indexes. In synchronous mode, the index is
updated before the transaction returns.

---

## 6. Direct Provider Helpers

Chapter 17 covers vector and embedding indexes. Datalevin also exposes direct
provider helpers for code that wants to call embedding or generation models
outside a transaction.

Embedding providers:

| Function | Use |
| :--- | :--- |
| `new-embedding-provider` | Creates a local or OpenAI-compatible embedding provider. |
| `embedding-metadata` | Returns stable metadata describing the embedding space. |
| `embedding-dimensions` | Returns the vector dimension count. |
| `embed-text`, `embed-texts` | Embed one string or a batch of strings. |
| `token-count`, `token-counts` | Count embedding input tokens. |
| `truncate-item`, `truncate-text` | Truncate input to fit a token limit. |
| `close-embedding-provider` | Releases provider resources. Safe to call more than once. |

LLM providers:

| Function | Use |
| :--- | :--- |
| `new-llm-provider` | Creates a local llama.cpp generation provider. |
| `llm-metadata` | Returns metadata for the configured LLM provider. |
| `llm-context-size` | Returns the model context size. |
| `generate-text` | Generates completion text for a prompt. |
| `summarize-text` | Summarizes an input string. |
| `llm-token-count` | Counts tokens for one LLM input string. |
| `close-llm-provider` | Releases provider resources. Safe to call more than once. |

```clojure
(def llm
  (d/new-llm-provider
    {:provider :llama.cpp
     :model    "/models/model.gguf"}))

(try
  {:context-size (d/llm-context-size llm)
   :tokens       (d/llm-token-count llm "Summarize this")
   :summary      (d/summarize-text llm long-text 256)}
  (finally
    (d/close-llm-provider llm)))
```

The direct LLM provider API is local-provider oriented. For hosted LLM APIs,
applications often call their preferred client library directly and store the
resulting facts, episodes, embeddings, or summaries in Datalevin.

---

## 7. Small Compatibility and Utility Helpers

| Function | Use |
| :--- | :--- |
| `tempid` | Datomic-compatibility helper that returns a negative temporary id. Prefer explicit negative ids when possible. |
| `squuid`, `squuid-time-millis` | Datomic-compatible semi-sequential UUID helpers. Use application identity attributes for durable domain identity. |
| `read-csv` | Reads CSV data from a string or reader into lazy vectors of strings. |
| `write-csv` | Writes CSV rows to a writer. |
| `hexify-string`, `unhexify-string` | Encode a string as hex text and decode it back. |

```clojure
(d/read-csv "email,name\nada@example.com,Ada\n")
;=> (["email" "name"] ["ada@example.com" "Ada"])

(with-open [w (java.io.StringWriter.)]
  (d/write-csv w [["email" "name"]
                  ["ada@example.com" "Ada"]])
  (str w))
```

---

## 8. Other Public API Namespaces

Not every public namespace needs its own appendix. The following namespaces are
public, but narrower than `datalevin.core`, `datalevin.lmdb`, or
`datalevin.client`.

| Namespace | Main use |
| :--- | :--- |
| `datalevin.interpret` | Interpreted functions and code execution for CLI, pods, remote calls, transaction functions, and KV visitor functions. |
| `datalevin.udf` | Runtime registry and descriptor handling for descriptor-backed user-defined functions. |
| `datalevin.constants` | Stable defaults, documented tuning vars, and system constants shared by the implementation. |
| `datalevin.main` | Programmatic support for the `dtlv` command-line tool. |

### 8.1 `datalevin.interpret`

Use `datalevin.interpret` when a function must be represented as data/source
and later interpreted by Datalevin. Ordinary embedded Clojure code can usually
use ordinary Clojure functions instead.

| Var | Use |
| :--- | :--- |
| `load-edn` | Loads EDN forms from a file in Datalevin's interpreter, often for schema or setup data. |
| `exec-code` | Executes a string of Datalevin REPL-compatible code and prints results. |
| `inter-fn` | Creates a serializable interpreted function. |
| `definterfn` | Defines a named `inter-fn`. |
| `inter-fn?` | Tests whether a value is an interpreted function. |
| `inter-fn-from-reader` | Reads a printed interpreted function back into a callable value. |

`inter-fn` is the important one for application code. It appears when an
installed transaction function, query predicate, or KV visitor function must be
stored, sent over the wire, or used outside the original Clojure runtime.

```clojure
(require '[datalevin.interpret :refer [inter-fn]])

(def keep-large-values
  (inter-fn [k v]
    (when (> v 1000)
      [k v])))
```

### 8.2 `datalevin.udf`

Use `datalevin.udf` for descriptor-backed user-defined functions, especially
when the function may come from another runtime, a server process, or an
application registry rather than from embedded Clojure source. Chapter 6 uses
this mechanism for transaction UDFs; Chapter 18 uses the same idea for query
UDFs.

The registry and descriptor model is available across the supported host
languages:

- Clojure: `create-registry`, `descriptor`, and `register!` in
  `datalevin.udf`.
- Java: `Datalevin.udfRegistry()`, `queryUdf(...)`, `predicateUdf(...)`,
  `txUdf(...)`, and the corresponding `UdfRegistry` methods.
- Python: `create_udf_registry()` and `udf_descriptor()`. The registry exposes
  `query_udf`, `predicate_udf`, and `tx_udf`.
- JavaScript: `createUdfRegistry()` and `udfDescriptor()`. The registry exposes
  `queryUdf`, `predicateUdf`, and `txUdf`.

A UDF descriptor is a map with these keys:

| Key | Meaning |
| :--- | :--- |
| `:udf/lang` | Runtime or language namespace, such as `:java`, `:python`, `:javascript`, or an application-defined keyword. |
| `:udf/kind` | Function role, such as `:tx-fn`, `:query-fn`, or `:predicate`. |
| `:udf/id` | Stable function id keyword. |
| `:udf/version` | Optional keyword, string, or integer version. |

| Function | Use |
| :--- | :--- |
| `descriptor?`, `descriptor` | Test or validate descriptor maps. |
| `create-registry` | Creates a mutable UDF registry atom. |
| `generation` | Returns the registry generation, which changes after registration updates. |
| `register!` | Registers a descriptor to a callable value. |
| `register-resolver!` | Registers a language-level resolver that can materialize functions on demand. |
| `unregister!`, `registered?`, `registered-descriptor` | Manage or inspect registry entries. |
| `ensure-kind`, `descriptor-or-registered` | Validate that a descriptor/id is suitable for a query, predicate, or transaction context. |
| `materialize`, `resolve` | Resolve a descriptor to a callable function. |

```clojure
(require '[datalevin.udf :as udf])

(def registry (udf/create-registry))

(def lower-desc
  {:udf/lang :app
   :udf/kind :query-fn
   :udf/id   :text/lower})

(udf/register! registry lower-desc clojure.string/lower-case)
(udf/resolve registry lower-desc)
```

If the function is embedded Clojure source that should travel with a
transaction or query, `datalevin.interpret/inter-fn` is usually simpler. If the
function should be looked up by descriptor in a host application, use
`datalevin.udf`.

### 8.3 `datalevin.constants`

`datalevin.constants` contains both public defaults and implementation
constants. Treat it as a place to read documented defaults or bind documented
tuning vars, not as a catalog of storage internals to persist in application
data.

| Var | Use |
| :--- | :--- |
| `version` | Current Datalevin version string. |
| `default-env-flags` | Default LMDB environment flags for `open-kv`. |
| `default-dbi-flags` | Default DBI flags, including `:create`, `:counted`, and `:prefix-compression`. |
| `default-put-flags` | Default LMDB put flags. |
| `+max-key-size+` | Maximum LMDB key size in bytes. |
| `default-spill-threshold`, `default-spill-root`, `tmp-dbi` | Defaults for spill-to-disk temporary storage. |
| `default-port`, `default-idle-timeout` | Server defaults. |
| `default-connection-pool-size`, `default-connection-timeout` | Client connection defaults. |
| `default-username`, `default-password` | Built-in development defaults; production servers should override them. |
| `default-domain`, `default-top` | Search defaults. |
| `default-metric-type`, `default-quantization`, `default-connectivity`, `default-expansion-add`, `default-expansion-search`, `default-multi?` | Vector index defaults. |
| `*datalog-wal?*`, `*datalog-wal-durability-profile*` | Dynamic defaults for new Datalog stores when WAL is enabled. |
| `*remote-db-last-modified-check-interval-ms*` | Remote freshness-check throttle for client DB checks. |
| `*wire-compression-threshold*`, `*wire-compression-level*` | Client/server wire compression tuning. |

Many other constants name internal DBIs, type tags, WAL markers, query planner
costs, and HA control-plane defaults. Prefer documented option maps and API
functions when possible.

### 8.4 `datalevin.main`

`datalevin.main` backs the `dtlv` command-line tool. Most users should call the
CLI directly from a shell, or use the higher-level Clojure APIs. The namespace
is useful when JVM tooling or tests need to invoke CLI behavior in process.

| Function | Use |
| :--- | :--- |
| `exec` | Executes Datalevin code supplied as command-line style argument strings. |
| `copy` | Copies a database directory, optionally compacting while copying. |
| `drop` | Drops or clears named key-value DBIs in a database directory. |
| `dump` | Dumps Datalog or key-value database content. |
| `load` | Loads Datalog or key-value content into a database directory. |
| `-main` | Entry point for the `dtlv` command. |
| `default-root-dir` | Default server root directory used by the CLI. |

```clojure
(require '[datalevin.main :as main])

(main/copy "/data/app-db" "/backup/app-db" true)
```

---

## Summary

The central Datalevin API is small: open a connection, transact data, call
`db`, query, pull, and close the connection. The helpers in this appendix fill
in the edges: reading pull syntax precisely, observing transactions, simulating
transaction reports, checking types, managing async secondary indexes, calling
model providers directly, using compatibility utilities when porting code, and
locating narrower public namespaces such as `interpret`, `udf`, `constants`,
and `main`.
