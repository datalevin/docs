---
title: "Combining Indexes: Hybrid Queries Across KV, Logic, and Search"
chapter: 20
part: "IV — Indexes as Capabilities"
---

# Chapter 20: Combining Indexes: Hybrid Queries Across KV, Logic, and Search

The true power of Datalevin lies not in any single index, but in how they all work together. Because Datalog, full-text search, vector similarity, text embedding search, and document indexes all live in the same storage system, you can combine them in a single query.

By default, full-text, vector, embedding, and idoc indexes are updated synchronously with source datoms. Full-text, vector, and embedding domains can opt into `:indexing-mode :async`; in that case source datoms commit with durable index jobs and index queries are eventually consistent until the worker catches up.

In Datalevin, **indexes are programmable capabilities**. This chapter demonstrates how to build hybrid queries that bridge structured logic and unstructured search.

---

## 1. Unified Retrieval: The Single-Engine Advantage

In a multi-database architecture (e.g., Postgres + Elasticsearch + Pinecone), a hybrid query requires fan-out to multiple services, followed by manual result merging in application code. This is slow, complex, and leads to consistency issues.

In Datalevin, a hybrid query is a single Datalog expression. The engine manages joins between different index types automatically.

### Example: The "Smart" Product Search

Find products that:
1. Match the term "clojure" in description (full-text)
2. Are semantically similar to a user's interest vector or query text
3. Are in stock and in a specific category (Datalog)
4. Have specific nested metadata (idoc)

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
       [?e :product/title ?title]
       ;; idoc metadata filter
       [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]
     db "clojure" user-embedding :books)
```

```java
Datalevin.q("[:find ?title ?dist " +
            " :in $ ?search-term ?target-vec ?category " +
            " :where " +
            " [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] " +
            " [(vec-neighbors $ :product/embedding ?target-vec " +
            "                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] " +
            " [?e :product/status :status/in-stock] " +
            " [?e :product/category ?category] " +
            " [?e :product/title ?title] " +
            " [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]",
            db, "clojure", userEmbedding, "books");
```

```python
d.q('[:find ?title ?dist '
     ' :in $ ?search-term ?target-vec ?category '
     ' :where '
     ' [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] '
     ' [(vec-neighbors $ :product/embedding ?target-vec '
     '                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] '
     ' [?e :product/status :status/in-stock] '
     ' [?e :product/category ?category] '
     ' [?e :product/title ?title] '
     ' [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]',
     db, "clojure", user_embedding, "books")
```

```javascript
d.q('[:find ?title ?dist ' +
     ' :in $ ?search-term ?target-vec ?category ' +
     ' :where ' +
     ' [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] ' +
     ' [(vec-neighbors $ :product/embedding ?target-vec ' +
     '                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] ' +
     ' [?e :product/status :status/in-stock] ' +
     ' [?e :product/category ?category] ' +
     ' [?e :product/title ?title] ' +
     ' [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]',
     db, 'clojure', userEmbedding, 'books');
```

</div>

Each index function returns datoms as `[e a v]` triples (or `[e a v dist]` with `:refs+dists`). The shared `?e` variable joins all results together.

---

## 2. Order of Execution: How the Optimizer Thinks

When you mix different index types, the query optimizer uses cost-based analysis for ordinary Datalog clauses and joins the result tuples produced by specialized index functions.

- **Selective Retrieval First**: Keep `:top`, domains, and index-specific filters tight so specialized functions produce useful candidate sets.
- **Join Order**: All index results are sets of entity IDs. The join is an intersection of these IDs—an extremely fast operation in memory-mapped architecture.

You usually do not need to reorder clauses manually. State constraints clearly, then tune the candidate-producing options when a search/vector function returns too much or too little.

---

## 3. Practical Pattern: Retrieval-Augmented Generation (RAG)

Hybrid search is the foundation of modern AI applications. Combining **keyword matching** (for exact terms like product IDs or jargon) with **vector similarity** (for semantic meaning) provides the most relevant context to LLMs.

<div class="multi-lang">

```clojure
(d/q '[:find ?title ?chunk ?dist
       :in $ ?query ?query-vec ?allowed-users
       :where
       ;; semantic search
       [(vec-neighbors $ :doc/embedding ?query-vec
                       {:top 10 :display :refs+dists})
        [[?e _ ?chunk ?dist]]]
       ;; optional keyword boost
       [(fulltext $ :doc/content ?query {:top 50}) [[?ft-e _ _]]]
       ;; both must match (intersection)
       [(= ?e ?ft-e)]
       ;; permission filter
       [?e :doc/owner ?owner]
       [(contains? ?allowed-users ?owner)]
       [?e :doc/title ?title]]
     db user-query query-embedding allowed-users)
```

```java
Datalevin.q("[:find ?title ?chunk ?dist " +
            " :in $ ?query ?query-vec ?allowed-users " +
            " :where " +
            " [(vec-neighbors $ :doc/embedding ?query-vec {:top 10 :display :refs+dists}) [[?e _ ?chunk ?dist]]] " +
            " [(fulltext $ :doc/content ?query {:top 50}) [[?ft-e _ _]]] " +
            " [(= ?e ?ft-e)] " +
            " [?e :doc/owner ?owner] " +
            " [(contains? ?allowed-users ?owner)] " +
            " [?e :doc/title ?title]]",
            db, userQuery, queryEmbedding, allowedUsers);
```

```python
d.q('[:find ?title ?chunk ?dist '
     ' :in $ ?query ?query-vec ?allowed-users '
     ' :where '
     ' [(vec-neighbors $ :doc/embedding ?query-vec {:top 10 :display :refs+dists}) [[?e _ ?chunk ?dist]]] '
     ' [(fulltext $ :doc/content ?query {:top 50}) [[?ft-e _ _]]] '
     ' [(= ?e ?ft-e)] '
     ' [?e :doc/owner ?owner] '
     ' [(contains? ?allowed-users ?owner)] '
     ' [?e :doc/title ?title]]',
     db, user_query, query_embedding, allowed_users)
```

```javascript
d.q('[:find ?title ?chunk ?dist ' +
     ' :in $ ?query ?query-vec ?allowed-users ' +
     ' :where ' +
     ' [(vec-neighbors $ :doc/embedding ?query-vec {:top 10 :display :refs+dists}) [[?e _ ?chunk ?dist]]] ' +
     ' [(fulltext $ :doc/content ?query {:top 50}) [[?ft-e _ _]]] ' +
     ' [(= ?e ?ft-e)] ' +
     ' [?e :doc/owner ?owner] ' +
     ' [(contains? ?allowed-users ?owner)] ' +
     ' [?e :doc/title ?title]]',
     db, userQuery, queryEmbedding, allowedUsers);
```

</div>

Structured filters ensure AI applications are not only smart but also secure and accurate—only searching documents the user has permission to see.

---

## 4. Mixing KV and Datalog

For absolute performance, combine raw Key-Value layer access with Datalog using function bindings:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [?e :user/id ?id]
              [(my-app.kv/check-blacklist? ?id) ?blocked?]
              [(false? ?blocked?)]]
     db)
```

```java
Datalevin.q("[:find ?e " +
            " :where [?e :user/id ?id] " +
            "        [(my-app.kv/check-blacklist? ?id) ?blocked?] " +
            "        [(false? ?blocked?)]]",
            db);
```

```python
d.q('[:find ?e '
     ' :where [?e :user/id ?id] '
     '        [(my-app.kv/check-blacklist? ?id) ?blocked?] '
     '        [(false? ?blocked?)]]',
     db)
```

```javascript
d.q('[:find ?e ' +
     ' :where [?e :user/id ?id] ' +
     '        [(my-app.kv/check-blacklist? ?id) ?blocked?] ' +
     '        [(false? ?blocked?)]]',
     db);
```

</div>

The underlying KV layer is a first-class capability, allowing custom logic and specialized indexes.

---

## 5. Index Return Formats

All specialized index functions return datoms for consistent destructuring:

| Function | Default Return | Score/Distance Option |
|----------|----------------|-----------------------|
| `fulltext` | `[e a v]` | `:display :refs+scores` returns `[e a v score]` |
| `vec-neighbors` | `[e a v]` | `:display :refs+dists` returns `[e a v dist]` |
| `embedding-neighbors` | `[e a v]` | `:display :refs+dists` returns `[e a v dist]` |
| `idoc-match` | `[e a v]` | — |

The shared entity ID (`?e`) enables seamless joins across all index types.

---

## Summary

The multi-paradigm nature of Datalevin is about **synergy**:

- **Consistency**: Indexes are synchronous by default, with explicit async modes for expensive secondary indexing
- **Performance**: Joins happen in-memory with zero-copy efficiency
- **Simplicity**: One database, one query language, one operational model

By treating every index as a programmable capability, you can answer complex questions that traditional single-paradigm databases cannot express.
