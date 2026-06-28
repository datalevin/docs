---
title: "Appendix E: Core API Helpers"
chapter: 32
part: "VII — Appendices"
web: false
---

# Appendix E: Core API Helpers

This appendix is a lookup reference for public Datalevin helpers that sit
around the main connection, transaction, query, pull, indexing, and provider
APIs. For examples and rationale, use the narrative chapters referenced below.
Host-language bindings follow the naming conventions and coverage notes in
Appendix A.


## 1. Connection and DB Object Helpers

| Function | Use |
| --- | ------ |
| `conn?` | Returns true for an open local Datalog connection. |
| `closed?` | Returns true when a connection is closed, nil, or points at a nil DB. |
| `db` | Returns the current Datalog DB object behind a connection. Call it again after writes. |
| `opts` | Returns the option map of the Datalog DB. |
| `schema` | Returns the current effective schema. |
| `empty-db` | Creates a raw Datalog DB object without wrapping it in an application connection. |
| `datalog-kv` | Returns the KV handle backing a Datalog connection or DB. |
| `datalog-index-cache-limit` | Gets or sets the Datalog index cache limit. |
| `close` | Closes a Datalog connection. |
| `close-db` | Closes a raw Datalog DB object. |
| `with-conn` | Clojure macro that opens or reuses a connection for a body and closes it afterward. |
| `conn-from-db` | Wraps an existing DB object in a mutable connection. |
| `conn-from-datoms` | Creates a connection from trusted datoms, with optional dir, schema, and opts. |
| `reset-conn!` | Replaces the contents of a connection with a supplied DB and returns a tx report. |

Use `get-conn` or `create-conn` for normal application opening. `datalog-kv`
returns a borrowed handle owned by the Datalog connection; close the connection,
not the borrowed KV handle.


## 2. Pull Pattern Syntax Reference

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

Pull patterns are EDN data. `:limit` applies only to cardinality-many
attributes. Recursive pull tracks visited entities and represents cycles with
`{:db/id ...}` rather than expanding forever. For multi-source Datalog queries,
qualify pull with the source that owns the entity ids. Chapter 7 gives the
worked examples.


## 3. Transaction Observation and Simulation

| Function | Use |
| --- | ------ |
| `transact-async` | Submits a Datalog transaction and returns a future/promise immediately. |
| `transact` | Blocks for the same asynchronous transaction machinery and returns an already realized future. |
| `with-transaction` | Groups multiple Datalog writes, and optionally borrowed KV writes, into one explicit transaction; accepts `{:timeout-ms n}` in the binding vector. |
| `abort-transact` | Aborts the open explicit Datalog transaction from inside `with-transaction`. |
| `explicit-transaction-timeout` | Gets or sets the default timeout, in milliseconds, for explicit Datalog and KV transactions. |
| `set-explicit-transaction-timeout!` | Sets or clears the default explicit transaction timeout; pass `nil` to disable it. |
| `listen!`, `unlisten!` | Register or remove transaction-report callbacks on a connection. |
| `tx-data->simulated-report` | Builds a transaction report from a DB object and tx data without mutating the connection. |
| `resolve-tempid` | Datomic-compatibility helper for looking up tempids in a transaction report. |
| `update-schema` | Applies explicit schema changes, deletions, or renames to an open connection. |

`listen!` replaces an existing callback when the same key is registered again.
A simulated report has the normal report shape, including `:new-attributes`
when the tx data introduce attributes not already present in the schema.
Chapter 6 covers transaction semantics and callbacks.


## 4. Datom, Entity, and Type Helpers

| Function | Use |
| --- | ------ |
| `db?` | Tests whether a value is a Datalog DB object. |
| `datom` | Creates a raw datom for trusted low-level import APIs such as `init-db` and `fill-db`. |
| `datom?` | Tests whether a value is a Datalevin datom. |
| `datom-e`, `datom-a`, `datom-v` | Return the entity, attribute, or value component of a datom. |
| `entity` | Returns a lazy, map-like entity for an eid or lookup ref. |
| `entid` | Resolves a lookup ref to a numeric eid, returns nil when it does not exist, and returns numeric eids unchanged. |
| `entity-db` | Returns the DB object from which an entity was created. |
| `touch` | Eagerly realizes an entity's forward attributes, mainly for REPL inspection and debugging. |
| `add`, `retract` | Clojure transactable-entity helpers for staging additions or retractions on an entity before transacting it. |

Application transactions should normally use maps or transaction datom vectors.
Raw datoms are for trusted bulk-loading paths. Lazy entity reads are available
across Clojure, Java, Python, and JavaScript; staged entity mutation is
Clojure-only.


## 5. Datalog Index, Statistics, and Bulk Helpers

| Function | Use |
| --- | ------ |
| `datoms` | Reads datoms from a named index, currently `:eav` or `:ave`, using supplied leading key components. |
| `search-datoms` | Describes an `(e, a, v)` pattern with nil wildcards and lets Datalevin choose an efficient index. |
| `count-datoms` | Counts datoms matching an `(e, a, v)` wildcard pattern without realizing the datoms. |
| `cardinality` | Counts distinct current values for one attribute. This is a statistic, not the schema property `:db/cardinality`. |
| `max-eid` | Returns the current maximum entity id, useful when a bulk import allocates numeric eids. |
| `seek-datoms`, `rseek-datoms` | Starts a forward or reverse index cursor near a supplied key prefix. |
| `index-range` | Reads the AVE range for one attribute between lower and upper bounds. |
| `fulltext-datoms` | Runs a full-text query and returns matching datoms rather than shaped search results; accepts search options such as `:top`, `:limit`, and `:offset`. |
| `analyze` | Refreshes attribute statistics used by the query planner. |
| `init-db` | Creates a database quickly from trusted prepared datoms. |
| `fill-db` | Adds trusted prepared datoms to an existing DB object. |
| `re-index` | Dumps, clears, reloads, and rebuilds a Datalog store, KV store, search engine, or vector index with supplied options. |

`init-db` and `fill-db` bypass ordinary transaction processing and WAL; use
them only with trusted prepared datoms. Chapter 15 covers direct index reads,
Chapter 20 covers bulk loading, and Chapter 21 covers `analyze`.


## 6. Secondary Index Job Helpers

| Function | Use |
| --- | ------ |
| `secondary-index-status` | Returns async secondary-index job status for a local Datalog connection. |
| `process-secondary-index-jobs!` | Processes pending jobs, optionally bounded by options such as `:max-jobs`. |
| `wait-for-secondary-index` | Waits until matching secondary-index jobs are complete, with options such as `:tx`, `:type`, `:domain`, `:timeout-ms`, and `:process?`. |

These helpers apply to domains configured for async full-text, vector, or
embedding indexing. Synchronous domains update the secondary index before the
transaction returns.


## 7. Standalone Search and Vector Helpers

### 7.1 Full-Text Search

| Function | Use |
| :--- | :--- |
| `new-search-engine` | Creates a full-text search engine backed by an `open-kv` handle. |
| `add-doc`, `remove-doc`, `clear-docs` | Add, remove, or clear indexed documents. |
| `doc-indexed?`, `doc-count` | Inspect document presence and document count. |
| `search` | Runs a full-text query and returns refs, scores, offsets, or stored texts depending on `:display`. |
| `search-index-writer`, `write`, `commit` | Bulk-writer API for faster standalone search indexing. Call `commit` after all `write` calls. |

### 7.2 Vector Search

| Function | Use |
| :--- | :--- |
| `new-vector-index` | Creates an HNSW vector index backed by an `open-kv` handle. |
| `add-vec`, `remove-vec`, `search-vec` | Add, remove, and search vectors by semantic refs. |
| `vector-index-info` | Returns size, memory, capacity, dimensions, metric, quantization, domain, and checkpoint metadata. |
| `force-vec-checkpoint!`, `vector-checkpoint-state` | Persist or inspect the vector checkpoint stored in LMDB. |
| `close-vector-index`, `clear-vector-index` | Close index resources, or close and delete all vectors. |

Use Datalog full-text, vector, embedding, or idoc attributes when search
results should participate in entity modeling and transactions. Use standalone
engines for direct KV-backed search and vector indexes. Chapters 16 and 17 own
the tutorial coverage.


## 8. Direct Provider Helpers

### 8.1 Embedding Providers

| Function | Use |
| --- | ------ |
| `new-embedding-provider` | Creates a local or OpenAI-compatible embedding provider. |
| `embedding-metadata` | Returns stable metadata describing the embedding space. |
| `embedding-dimensions` | Returns the vector dimension count. |
| `embed-text`, `embed-texts` | Embed one string or a batch of strings. |
| `token-count`, `token-counts` | Count embedding input tokens. |
| `truncate-item`, `truncate-text` | Truncate input to fit a token limit. |
| `close-embedding-provider` | Releases provider resources. Safe to call more than once. |

### 8.2 LLM Providers

| Function | Use |
| --- | ------ |
| `new-llm-provider` | Creates a local llama.cpp generation provider. |
| `llm-metadata` | Returns metadata for the configured LLM provider. |
| `llm-context-size` | Returns the model context size. |
| `generate-text` | Generates completion text for a prompt. |
| `summarize-text` | Summarizes an input string. |
| `llm-token-count` | Counts tokens for one LLM input string. |
| `close-llm-provider` | Releases provider resources. Safe to call more than once. |

Provider helpers are direct model calls. Persisted facts, episodes,
embeddings, summaries, and provenance are ordinary application data.


## 9. Small Compatibility and Utility Helpers

| Function | Use |
| --- | ------ |
| `tempid` | Datomic-compatibility helper that returns a negative temporary id. Prefer explicit negative ids when possible. |
| `squuid`, `squuid-time-millis` | Datomic-compatible semi-sequential UUID helpers. Use application identity attributes for durable domain identity. |
| `read-csv` | Reads CSV data from a string or reader into lazy vectors of strings. |
| `write-csv` | Writes CSV rows to a writer. |
| `hexify-string`, `unhexify-string` | Encode a string as hex text and decode it back. |


## 10. Other Public API Namespaces

| Namespace | Main use |
| --- | ------ |
| `datalevin.interpret` | Interpreted functions and code execution for CLI, pods, remote calls, transaction functions, and KV visitor functions. |
| `datalevin.udf` | Runtime registry and descriptor handling for descriptor-backed user-defined functions. |
| `datalevin.constants` | Stable defaults, documented tuning vars, and system constants shared by the implementation. |
| `datalevin.main` | Programmatic support for the `dtlv` command-line tool. |

### 10.1 `datalevin.interpret`

| Var | Use |
| --- | ------ |
| `load-edn` | Loads EDN forms from a file in Datalevin's interpreter, often for schema or setup data. |
| `exec-code` | Executes a string of Datalevin REPL-compatible code and prints results. |
| `inter-fn` | Creates a serializable interpreted function. |
| `definterfn` | Defines a named `inter-fn`. |
| `inter-fn?` | Tests whether a value is an interpreted function. |
| `inter-fn-from-reader` | Reads a printed interpreted function back into a callable value. |

### 10.2 `datalevin.udf`

| Descriptor key | Meaning |
| --- | ------ |
| `:udf/lang` | Runtime or language namespace, such as `:java`, `:python`, `:javascript`, or an application-defined keyword. |
| `:udf/kind` | Function role, such as `:tx-fn`, `:query-fn`, or `:predicate`. |
| `:udf/id` | Stable function id keyword. |
| `:udf/version` | Optional keyword, string, or integer version. |

| Function | Use |
| --- | ------ |
| `descriptor?`, `descriptor` | Test or validate descriptor maps. |
| `create-registry` | Creates a mutable UDF registry atom. |
| `generation` | Returns the registry generation, which changes after registration updates. |
| `register!` | Registers a descriptor to a callable value. |
| `register-resolver!` | Registers a language-level resolver that can materialize functions on demand. |
| `unregister!`, `registered?`, `registered-descriptor` | Manage or inspect registry entries. |
| `ensure-kind`, `descriptor-or-registered` | Validate that a descriptor/id is suitable for a query, predicate, or transaction context. |
| `materialize`, `resolve` | Resolve a descriptor to a callable function. |

Use `datalevin.interpret/inter-fn` when embedded Clojure source should travel
with a transaction or query. Use `datalevin.udf` when a host application should
look up functions by descriptor.

### 10.3 `datalevin.constants`

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

Prefer documented option maps and API functions over persisting implementation
constants in application data.

### 10.4 `datalevin.main`

| Function | Use |
| --- | ------ |
| `exec` | Executes Datalevin code supplied as command-line style argument strings. |
| `copy` | Copies a database directory, optionally compacting while copying. |
| `drop` | Drops or clears named key-value DBIs in a database directory. |
| `dump` | Dumps Datalog or key-value database content. |
| `load` | Loads Datalog or key-value content into a database directory. |
| `-main` | Entry point for the `dtlv` command. |
| `default-root-dir` | Default server root directory used by the CLI. |

When neither `--datalog` nor explicit DBI names are supplied, `dump` and `load`
use auto mode. Auto mode detects a Datalog store and writes a mixed text dump:
the Datalog options, schema, and datoms, followed by user-created KV DBIs in the
same LMDB environment. This is the right export/import path for a Datalog
database that also keeps application state through `datalog-kv`. Datalevin skips
its own internal and derived Datalog DBIs, so full-text, vector, embedding, and
idoc indexes are treated as rebuildable indexes rather than separate user data.

```console
dtlv -d /data/companydb -f companydb.edn dump
dtlv -d /data/companydb-restored -f companydb.edn load
```

Use `--datalog` when you intentionally want only the Datalog schema and datoms.
Use `--all` or explicit DBI names when you want raw KV dumps. Mixed Datalog/KV
auto dump is a text format; for Nippy binary dumps, choose an explicit Datalog
or KV mode.
