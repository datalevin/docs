---
title: "Combining Indexes: Hybrid Queries Across KV, Logic, and Search"
chapter: 18
part: "IV — Indexes as Capabilities"
---

# Chapter 18: Combining Indexes: Hybrid Queries Across KV, Logic, and Search

The true power of Datalevin lies not in any single index, but in how they all
work together. Because Datalog, full-text search, vector similarity, text
embedding search, document indexes and KV store can all live in the same storage
system, you can combine them in a single query.

By default, full-text, vector, embedding, and idoc indexes are updated
synchronously with source datoms. Full-text, vector, and embedding domains can
opt into `:indexing-mode :async`; in that case source datoms commit with durable
index jobs and index queries are eventually consistent until the worker catches
up.

In Datalevin, **indexes are programmable capabilities**. This chapter
demonstrates how to build hybrid queries that bridge structured logic and
unstructured search.

---

## 1. Unified Retrieval: The Single-Engine Advantage

In a multi-database architecture (e.g., Postgres + Elasticsearch + Pinecone), a
hybrid query requires fan-out to multiple services, followed by manual result
merging in application code. This is slow, complex, and leads to consistency
issues.

In Datalevin, a hybrid query is a single Datalog expression. The engine manages
joins between different index types automatically.

Clojure snippets below use an explicit `db` value. Java, Python, and JavaScript
snippets assume an open connection named `conn`, whose `query` method supplies
the current database as `$`.

---

## 2. Index Return Formats

All specialized index functions return datoms for consistent destructuring:

| Function | Default Return | Score/Distance Option |
|----------|----------------|-----------------------|
| `fulltext` | `[e a v]` | `:display :refs+scores` returns `[e a v score]` |
| `vec-neighbors` | `[e a v]` | `:display :refs+dists` returns `[e a v dist]` |
| `embedding-neighbors` | `[e a v]` | `:display :refs+dists` returns `[e a v dist]` |
| `idoc-match` | `[e a v]` | — |

The shared entity ID (`?e`) enables seamless joins across all index types.

Full-text scores and vector distances are different quantities. A full-text
score is a relevance score where higher is better. A vector distance is a
metric distance where lower is usually closer. They are not on the same scale,
so production code should not combine the raw numbers directly. Prefer a
rank-based method such as reciprocal rank fusion (RRF), or keep one index as the
candidate generator and use the other as a filter.

RRF fuses ranked lists by rank position instead of raw score. A common formula
is:

```clojure
(/ 1.0 (+ 60 rank))
```

where `rank` is 1 for the first item, 2 for the second item, and so on. The
constant `60` dampens the effect of any one list. The same entity can appear in
the full-text list, vector list, and graph list; add its reciprocal-rank
contributions from each list, then sort by the fused score.

---

## 3. Order of Execution: How the Optimizer Thinks

When you mix different index types, the query optimizer uses cost-based analysis
for ordinary Datalog clauses and joins the result tuples produced by specialized
index functions.

A **candidate set** is the intermediate set of entities produced by one clause
or index function. Specialized functions such as `fulltext`, `vec-neighbors`,
`embedding-neighbors`, and `idoc-match` create candidate sets from their own
indexes. Ordinary Datalog clauses then join, filter, or extend those candidates.

- **Selective Retrieval First**: Keep `:top`, domains, and index-specific
  filters tight so specialized functions produce useful candidate sets.
- **Join Order**: All index results are sets of entity IDs. The join is an
  intersection of these IDs—an extremely fast operation in memory-mapped
  architecture.

You usually do not need to reorder clauses manually. State constraints clearly,
then tune the candidate-producing options when a search/vector function returns
too much or too little.

---

## 4. Mixing KV and Datalog

KV and Datalog storage do not have to live in separate files or separate
services. A Datalevin Datalog store is built on the same DLMDB substrate as the
key-value APIs, so application-owned KV data can live beside Datalog data in the
same database file. Use separate DBIs for those KV datasets when they need their
own key/value encoding, range access pattern, or lifecycle, while Datalog keeps
managing its own internal DBIs.

For absolute performance, combine raw Key-Value layer access with Datalog using
function bindings:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [?e :user/id ?id]
              [(my-app.kv/check-blacklist? ?id) ?blocked?]
              [(false? ?blocked?)]]
     db)
```

```java
conn.query("[:find ?e " +
           " :where [?e :user/id ?id] " +
           "        [(my-app.kv/check-blacklist? ?id) ?blocked?] " +
           "        [(false? ?blocked?)]]");
```

```python
conn.query(
    '[:find ?e '
    ' :where [?e :user/id ?id] '
    '        [(my-app.kv/check-blacklist? ?id) ?blocked?] '
    '        [(false? ?blocked?)]]'
)
```

```javascript
await conn.query(
  '[:find ?e ' +
  ' :where [?e :user/id ?id] ' +
  '        [(my-app.kv/check-blacklist? ?id) ?blocked?] ' +
  '        [(false? ?blocked?)]]'
);
```

</div>

The underlying KV layer is a first-class capability, allowing custom logic and
specialized indexes.

The example above uses a Datalog function binding: the function receives a value
from the query and returns another value that the query can bind. In embedded
Clojure, that function can close over an open KV handle or call application code
that reads a same-file DBI. Keep such functions deterministic and cheap. If the
function performs network calls, writes state, or scans a large KV range, it can
make query behavior surprising and hard to optimize.

When a write must update Datalog datoms and application-owned KV DBIs atomically,
use the `with-transaction` pattern from Chapter 6. Function bindings are for
read-time lookup and filtering, not for writing during query evaluation.

---

## 5. Practical Pattern: Retrieval-Augmented Generation (RAG)

Hybrid search is the foundation of modern AI applications. Combining **keyword
matching** (for exact terms like product IDs or jargon) with **vector
similarity** (for semantic meaning) provides the most relevant context to LLMs.
The permission input below is a set of allowed owners.

This example assumes `:doc/content` has `:db/fulltext true` and
`:db.fulltext/autoDomain true`, and `:doc/embedding` is a vector attribute.
Both search clauses bind the same `?e`; in Datalog, shared variables are joins,
so no explicit equality clause is needed.

The variable `query-embedding` in the examples below is a vector produced by the
application, often by the same embedding model used to index documents. If the
document text is stored with `:db/embedding true` instead of a user-supplied
`:db.type/vec` attribute, use `embedding-neighbors` and pass the query text
directly.

<div class="multi-lang">

```clojure
(d/q '[:find ?title ?chunk ?dist
       :in $ ?query ?query-vec ?allowed-owner-set
       :where
       ;; semantic search
       [(vec-neighbors $ :doc/embedding ?query-vec
                       {:top 10 :display :refs+dists})
        [[?e _ _ ?dist]]]
       ;; keyword search
       [(fulltext $ :doc/content ?query {:top 50}) [[?e _ ?chunk]]]
       ;; reusing ?e makes this an intersection
       ;; permission filter
       [?e :doc/owner ?owner]
       [(contains? ?allowed-owner-set ?owner)]
       [?e :doc/title ?title]]
     db user-query query-embedding allowed-owner-set)
```

```java
conn.query("[:find ?title ?chunk ?dist " +
           " :in $ ?query ?query-vec ?allowed-owner-set " +
           " :where " +
           " [(vec-neighbors $ :doc/embedding ?query-vec {:top 10 :display :refs+dists}) [[?e _ _ ?dist]]] " +
           " [(fulltext $ :doc/content ?query {:top 50}) [[?e _ ?chunk]]] " +
           " [?e :doc/owner ?owner] " +
           " [(contains? ?allowed-owner-set ?owner)] " +
           " [?e :doc/title ?title]]",
           userQuery, queryEmbedding, allowedOwnerSet);
```

```python
conn.query(
    '[:find ?title ?chunk ?dist '
    ' :in $ ?query ?query-vec ?allowed-owner-set '
    ' :where '
    ' [(vec-neighbors $ :doc/embedding ?query-vec {:top 10 :display :refs+dists}) [[?e _ _ ?dist]]] '
    ' [(fulltext $ :doc/content ?query {:top 50}) [[?e _ ?chunk]]] '
    ' [?e :doc/owner ?owner] '
    ' [(contains? ?allowed-owner-set ?owner)] '
    ' [?e :doc/title ?title]]',
    user_query,
    query_embedding,
    allowed_owner_set,
)
```

```javascript
await conn.query(
  '[:find ?title ?chunk ?dist ' +
  ' :in $ ?query ?query-vec ?allowed-owner-set ' +
  ' :where ' +
  ' [(vec-neighbors $ :doc/embedding ?query-vec {:top 10 :display :refs+dists}) [[?e _ _ ?dist]]] ' +
  ' [(fulltext $ :doc/content ?query {:top 50}) [[?e _ ?chunk]]] ' +
  ' [?e :doc/owner ?owner] ' +
  ' [(contains? ?allowed-owner-set ?owner)] ' +
  ' [?e :doc/title ?title]]',
  userQuery,
  queryEmbedding,
  allowedOwnerSet
);
```

</div>

Structured filters ensure AI applications are not only smart but also secure and
accurate—only searching documents the user has permission to see.

---

## 6. End-to-End Example: The "Smart" Product Search

After the retrieval pieces are clear, a larger query can combine all of them.
This example finds products that:

1. Match the term "clojure" in description (full-text)
2. Are semantically similar to a user's interest vector or query text
3. Are in stock and in a specific category (Datalog)
4. Have specific nested metadata (idoc)
5. Pass a fast operational policy check stored in a KV DBI (KV)

This example assumes `:product/desc` has `:db/fulltext true` and
`:db.fulltext/autoDomain true`, `:product/embedding` is a vector attribute, and
`:product/metadata` is an idoc attribute.

Chapter 14 introduced `idoc-match`: it searches inside a path-indexed document
value. In this example it filters products whose nested metadata says
`{:discounted true}` without requiring that nested field to become a separate
Datalog attribute.

<div class="multi-lang">

```clojure
(d/q '[:find ?title ?dist
       :in $ ?search-term ?target-vec ?category
       :where
       ;; full-text search
       [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]]
       ;; vector search with distance
       [(vec-neighbors $ :product/embedding ?target-vec
                       {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]]
       ;; structured filters
       [?e :product/status :status/in-stock]
       [?e :product/category ?category]
       [?e :product/sku ?sku]
       [?e :product/title ?title]
       ;; KV-backed policy check
       [(my-app.kv/sellable-sku? ?sku) ?sellable?]
       [(true? ?sellable?)]
       ;; idoc metadata filter
       [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]
     db "clojure" user-embedding :books)
```

```java
conn.query("[:find ?title ?dist " +
           " :in $ ?search-term ?target-vec ?category " +
           " :where " +
           " [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] " +
           " [(vec-neighbors $ :product/embedding ?target-vec " +
           "                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] " +
           " [?e :product/status :status/in-stock] " +
           " [?e :product/category ?category] " +
           " [?e :product/sku ?sku] " +
           " [?e :product/title ?title] " +
           " [(my-app.kv/sellable-sku? ?sku) ?sellable?] " +
           " [(true? ?sellable?)] " +
           " [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]",
           "clojure", userEmbedding, Datalevin.kw("books"));
```

```python
conn.query(
    '[:find ?title ?dist '
    ' :in $ ?search-term ?target-vec ?category '
    ' :where '
    ' [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] '
    ' [(vec-neighbors $ :product/embedding ?target-vec '
    '                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] '
    ' [?e :product/status :status/in-stock] '
    ' [?e :product/category ?category] '
    ' [?e :product/sku ?sku] '
    ' [?e :product/title ?title] '
    ' [(my-app.kv/sellable-sku? ?sku) ?sellable?] '
    ' [(true? ?sellable?)] '
    ' [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]',
    "clojure",
    user_embedding,
    ":books",
)
```

```javascript
await conn.query(
  '[:find ?title ?dist ' +
  ' :in $ ?search-term ?target-vec ?category ' +
  ' :where ' +
  ' [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] ' +
  ' [(vec-neighbors $ :product/embedding ?target-vec ' +
  '                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] ' +
  ' [?e :product/status :status/in-stock] ' +
  ' [?e :product/category ?category] ' +
  ' [?e :product/sku ?sku] ' +
  ' [?e :product/title ?title] ' +
  ' [(my-app.kv/sellable-sku? ?sku) ?sellable?] ' +
  ' [(true? ?sellable?)] ' +
  ' [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]',
  'clojure',
  userEmbedding,
  ':books'
);
```

</div>

The shared `?e` variable joins all results together. The
`my-app.kv/sellable-sku?` predicate is ordinary application code that can read a
small KV DBI, for example a same-file operational table of suppressed SKUs,
real-time availability flags, or rollout policy.

---

## Summary

The multi-paradigm nature of Datalevin is about **synergy**:

- **Consistency**: Indexes are synchronous by default, with explicit async modes for expensive secondary indexing
- **Performance**: Specialized indexes produce bounded candidate sets that
  Datalog can join with ordinary facts in the same engine
- **Simplicity**: One database, one query language, one operational model

By treating every index as a programmable capability, you can answer complex questions that traditional single-paradigm databases cannot express.
