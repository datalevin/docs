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

---

## 2. Transaction Observation and Simulation

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

## 3. Datom, Entity, and Type Helpers

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

## 4. Secondary Index Job Helpers

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

## 5. Direct Provider Helpers

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

## 6. Small Compatibility and Utility Helpers

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

## Summary

The central Datalevin API is small: open a connection, transact data, call
`db`, query, pull, and close the connection. The helpers in this appendix fill
in the edges: observing transactions, simulating transaction reports, checking
types, managing async secondary indexes, calling model providers directly, and
using compatibility utilities when porting code.
