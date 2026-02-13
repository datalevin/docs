---
title: "Why Datalevin: Logic, Graphs, and Key–Value in One Engine"
chapter: 1
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 1: Why Datalevin: Logic, Graphs, and Key–Value in One Engine

Modern applications often need several data capabilities at once: transactional
updates, relational joins, graph traversal, document-style flexibility, and
semantic retrieval. Teams frequently assemble these from multiple products, then
pay the operational cost in data pipelines, synchronization lag, and glue code.

Datalevin[^name-origin] is designed as a single durable substrate for these
mixed workloads.
It combines a high-performance key-value storage layer with a Datalog query
engine and integrated indexing and search capabilities, so you can model and
query data through one system instead of stitching several engines together.

This chapter explains the problem Datalevin addresses, how the architecture
unifies multiple access patterns, and where it fits in a modern stack.

## 1. A Unifying Data Model

At the Datalog layer, Datalevin represents facts as EAV triples (Entity,
Attribute, Value). This model is compact, sparse, and composable:

- **Entity (E):** the subject being described (for example, a user id).
- **Attribute (A):** the property name (for example, `:user/email`).
- **Value (V):** the property value (for example, `"alice@example.com"`).

In Datalevin terminology, one such atomic fact is often called a **datom**.
When surfaced in query functions, datoms are commonly represented as `[e a v]`.

### Aggregate Models vs Triple Model

Relational and document databases are typically aggregate-first: a document or
row is the main unit of mutation and retrieval, and nested fields or columns are
scoped under that aggregate.

Triple stores are fact-first: each E-A-V assertion is atomic and independently
queryable, which makes cross-aggregate joins, inference, and graph traversal a
natural part of the model.

Datalevin keeps the triple model at the core and supports document workflows
through `idoc` indexing. In other words, documents are supported, but facts are
foundational.

Another way to see this: Datalevin treats aggregate shapes as derived views,
not the foundational truth. This preserves modeling flexibility and delays
workload-specific optimization decisions.

Compared with rigid table layouts, EAV handles sparse and evolving data
naturally because missing values are simply omitted, not stored as placeholder
nulls.

The layer boundary is important: triples are the conceptual model; key-value is
the physical storage implementation. Datalevin stores facts and indexes in its
DLMDB key-value substrate, and also exposes that substrate as a low-level API.

## 2. One Engine, Multiple Access Patterns

Datalevin exposes several complementary capabilities in one runtime.

### Relational and Graph Queries with Datalog

Datalog provides declarative joins, recursive rules, and reusable query
composition. In practice, this lets Datalevin support both relational workloads
and graph traversal workloads through one query model.

### Direct Key-Value Access

The same key-value substrate that persists triples and indexes is also available
directly as a durable key-value API for EDN values (Extensible Data Notation, a
Clojure data format similar to JSON but richer: keywords, symbols, sets, tagged
literals). This is useful for
low-latency state access, caches, and simple lookup-oriented components.

### Path-Indexed Documents with idoc

Datalevin supports path-indexed documents (`:db.type/idoc`) for EDN, JSON, and
Markdown-oriented workflows. You can query nested structures with
`idoc-match`, including logical combinators and path predicates, while keeping
the documents in the same transactional store.

### Integrated Full-Text and Vector Search

Datalevin includes built-in full-text search and vector similarity search.
Because these indexes live in the same database system, you can combine
retrieval and logic filtering in one Datalog query instead of synchronizing
external search services.
Performance comparisons referenced in this chapter are workload-dependent and
hardware-dependent.[^benchmark-notes]

## 3. Developer Ergonomics and Deployment Modes

Datalevin is designed to run where your application runs:

- Embedded mode for local, process-level access.
- Client/server mode for shared deployment and centralized operations.
- Babashka pod mode for scriptable workflows.

This flexibility lets teams start small and evolve deployment architecture
without changing core data and query concepts.

## 4. Where Datalevin Fits

Datalevin is a strong fit when you want one operational system to support:

- application state with ACID transactions,
- relational and graph-style query patterns,
- document-style payloads with path indexing,
- retrieval pipelines that combine full-text, vector similarity, and logic
  constraints.

If your workload is narrowly specialized, a single-purpose engine may still be
the better choice. Datalevin is most compelling when you need these
capabilities together.

## 5. A Minimal Unified Query Example

Suppose you are building a documentation assistant. You want to find English
documents that mention a term in free text, while also requiring a structured
module status inside nested document metadata.

The example below combines full-text retrieval, idoc filtering, and metadata
constraints in one query:

```clojure
(require '[datalevin.core :as d])

(def schema
  {:doc/body {:db/valueType :db.type/string
              :db/fulltext  true}
   :doc/lang {:db/valueType :db.type/keyword}
   :doc/idoc {:db/valueType :db.type/idoc}})

(def conn (d/get-conn "data/ch1" schema))

(d/transact! conn
  [{:db/id    -1
    :doc/lang :en
    :doc/body "Datalevin adds idoc indexing and vector search."
    :doc/idoc {:module {:name "search" :status "stable"}}}])

(d/q '[:find ?e
       :in $ ?term
       :where
       ;; fulltext/idoc-match return datoms as [e a v].
       ;; [[?e _ _]] destructures the triple: keep entity id, ignore attribute/value.
       [(fulltext $ :doc/body ?term) [[?e _ _]]]
       [(idoc-match $ :doc/idoc {:module {:status "stable"}}) [[?e _ _]]]
       [?e :doc/lang :en]]
     (d/db conn)
     "vector")
```

What happens in this query:

1. `fulltext` retrieves candidate entities by searching `:doc/body` for the
   term `"vector"`.
2. `idoc-match` narrows candidates to documents whose nested idoc metadata has
   `{:module {:status "stable"}}`.
3. The datalog clause `[?e :doc/lang :en]` applies an exact metadata constraint.

The key point is not code size. The key point is composition: lexical retrieval,
structure-aware filtering, and exact logical predicates are executed in one
query context, over one database state.

This example also shows the architectural core: the triple model links all
capabilities together, so full-text matches, idoc constraints, and relational
facts can be joined in one logical query.

In a split architecture, this often requires multiple systems and intermediate
joins. Here it is one declarative query.

## Summary

Datalevin is built for workloads that cross boundaries between key-value
storage, logical querying, graph relationships, document modeling, and semantic
retrieval. The rest of this book shows each capability in depth and, more
importantly, how to compose them into one coherent system.

[^benchmark-notes]: Performance numbers in this chapter come from Datalevin benchmark suites and should be treated as reference points, not universal constants.
    - Join Order Benchmark (JOB): about 2.4x faster than PostgreSQL and about 4x faster than SQLite on the documented run.
    - LDBC SNB (Neo4j comparison): very large gains on interactive short queries and about 13% faster average time on interactive complex queries on the documented SF1 run.
    - Search benchmark (Lucene comparison): higher search throughput and lower median latency, while Lucene indexes faster in bulk and Datalevin shows longer latency tails on some queries.
    - Sources: https://github.com/datalevin/datalevin/tree/master/benchmarks/JOB-bench ; https://github.com/datalevin/datalevin/tree/master/benchmarks/LDBC-SNB-bench ; https://github.com/datalevin/datalevin/tree/master/benchmarks/search-bench

[^name-origin]: Datalevin builds on LMDB, and "levin" is an old English word
    for lightning.
