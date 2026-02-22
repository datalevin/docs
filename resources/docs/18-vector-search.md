---
title: "Vector Search: Embeddings and Similarity Queries"
chapter: 18
part: "IV — Indexes as Capabilities"
---

# Chapter 18: Vector Search: Embeddings and Similarity Queries

In the era of Large Language Models (LLMs), a database is not just a place to store structured facts; it is a repository for semantic meaning. Datalevin provides first-class support for **Vector Similarity Search**, allowing you to store and query high-dimensional embeddings directly alongside your Datalog triples.

This chapter explains how to configure vector attributes, perform nearest neighbor searches, and understand the underlying HNSW engine.

---

## 1. Configuring Vector Search

To store vectors in Datalevin, define an attribute with type `:db.type/vec`. Vector dimensions and similarity metrics are configured at the database level, not per-attribute:

```clojure
(def conn (d/create-conn
           "/tmp/mydb"
           {:id        {:db/valueType :db.type/string
                        :db/unique    :db.unique/identity}
            :embedding {:db/valueType :db.type/vec}}
           {:vector-opts {:dimensions  300
                          :metric-type :cosine}}))
```

### 1.1 Vector Options

Configure these in `:vector-opts`:

- **`:dimensions`** — Number of dimensions. **Required**.
- **`:metric-type`** — Similarity metric:
  - `:euclidean` (default) — Euclidean distance
  - `:cosine` — Cosine similarity
  - `:dot-product` — Dot product
  - `:haversine` — Great-circle distance for geo coordinates
  - `:divergence` — Jensen-Shannon divergence for probability distributions
  - `:pearson`, `:jaccard`, `:hamming`, `:tanimoto`, `:sorensen` — other specialized metrics
- **`:quantization`** — Scalar type: `:float` (default), `:double`, `:float16`, `:int8`, `:byte`
- **`:connectivity`** — Connections per node (default 16). Range 5-48.
- **`:expansion-add`** — Candidates during indexing (default 128)
- **`:expansion-search`** — Candidates during search (default 64)

### 1.2 Vector Search Domains

By default, each `:db.type/vec` attribute becomes its own search domain. For `:embedding`, the domain name is `"embedding"`. For `:user/profile`, it becomes `"user_profile"` (namespaces become underscores).

---

## 2. Similarity Search with `vec-neighbors`

The `vec-neighbors` function returns matching datoms as `[e a v]` triples, ordered by similarity:

```clojure
(d/q '[:find ?i ?v
       :in $ ?q
       :where
       [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]]
       [?e :id ?i]]
     (d/db conn) query-vector)
```

### 2.1 Search Options

- `:top` — Number of results (default 10)
- `:domains` — List of domains to search
- `:display` — Result format:
  - `:refs` (default) — `[e a v]` triples
  - `:refs+dists` — `[e a v dist]` with distance/similarity score
- `:vec-filter` — Predicate function to filter by vec-ref

### 2.2 Domain-Specific Search

Search across multiple domains:

```clojure
(vec-neighbors $ query-vec {:top 4 :domains ["embedding" "profile_vec"]})
```

---

## 3. Standalone Vector Database

Datalevin can be used as a standalone vector database:

```clojure
(def lmdb (d/open-kv "/tmp/vector-db"))
(def index (d/new-vector-index lmdb {:dimensions 300}))

(d/add-vec index "cat" cat-vector)
(d/add-vec index "dog" dog-vector)

(d/search-vec index query-vector {:top 2})
;=> ("cat" "dog")
```

**Vector References**: In Datalog's `vec-neighbors`, results are datoms `[e a v]`. In standalone mode, `vec-ref` can be any Clojure data (under 512 bytes)—strings, numbers, maps, or any identifier meaningful to your application.

Multiple vectors can share the same `vec-ref`. For example, different image embeddings might all reference the same tag `"cat"`, or document chunks in a RAG system might all reference the same document ID.

---

## 4. The Core Engine: HNSW

Datalevin's vector search uses **Hierarchical Navigable Small World (HNSW)** graphs, implemented via the [usearch](https://github.com/unum-cloud/usearch) library with SIMD optimizations.

### 4.1 How HNSW Works

HNSW builds a multi-layer graph where each node is a vector:
- **Higher layers** have long-range connections for fast "skipping"
- **Lower layers** have short-range connections for precision

Search starts at the top layer and "zooms in" through descending layers to find nearest neighbors.

### 4.2 Performance Trade-offs

- **`:connectivity`** (M) — Higher = better recall, more memory
- **`:expansion-add`** (efConstruction) — Higher = better index quality, slower writes
- **`:expansion-search`** (ef) — Higher = better recall, slower queries

---

## 5. Hybrid Retrieval: Combining Logic and Similarity

Vector search integrates with Datalog, enabling hybrid queries that combine semantic similarity with structured filters:

```clojure
(d/q '[:find ?title ?v
       :in $ ?target-vec
       :where
       [(vec-neighbors $ :product/embedding ?target-vec {:top 10}) [[?e _ ?v]]]
       [?e :product/title ?title]
       [?e :product/status :status/in-stock]
       [?e :product/price ?price]
       [(< ?price 100.0)]]
     db query-vec)
```

This is essential for **Retrieval-Augmented Generation (RAG)**: find semantically similar documents, then filter by metadata, access control, or recency.

---

## Summary

Vector search transforms Datalevin into a semantic database. With `:db.type/vec` attributes and the `vec-neighbors` function, you can build recommendation engines, semantic search, and AI-powered applications—without a separate vector database service.
