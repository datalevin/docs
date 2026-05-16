---
title: "Vector Search: Embeddings and Similarity Queries"
chapter: 18
part: "IV — Indexes as Capabilities"
---

# Chapter 18: Vector Search: Embeddings and Similarity Queries

In the era of Large Language Models (LLMs), a database is not just a place to store structured facts; it is a repository for semantic meaning. Datalevin provides two related similarity-search features:

- `:db.type/vec` stores user-supplied dense vectors and indexes them for nearest-neighbor search.
- `:db/embedding true` indexes string datoms by embedding similarity. Datalevin computes the vectors during transaction processing and returns the original source datoms in queries.

This chapter explains how to configure both paths, perform nearest-neighbor searches, and understand the underlying HNSW engine.

---

## 1. Configuring Vector Search

To store vectors in Datalevin, define an attribute with type `:db.type/vec`. Vector dimensions and similarity metrics are configured at the database level, not per-attribute:

<div class="multi-lang">

```clojure
(def conn (d/create-conn
           "/tmp/mydb"
           {:id        {:db/valueType :db.type/string
                        :db/unique    :db.unique/identity}
            :embedding {:db/valueType :db.type/vec}}
           {:vector-opts {:dimensions  300
                          :metric-type :cosine}}))
```

```java
Connection conn = Datalevin.createConn(
    "/tmp/mydb",
    Map.of(
        "id", Map.of("db/valueType", "db.type/string",
                      "db/unique", "db.unique/identity"),
        "embedding", Map.of("db/valueType", "db.type/vec")
    ),
    Map.of("vector-opts", Map.of(
        "dimensions", 300,
        "metric-type", "cosine"
    ))
);
```

```python
conn = d.create_conn(
    "/tmp/mydb",
    {
        "id": {"db/valueType": "db.type/string",
               "db/unique": "db.unique/identity"},
        "embedding": {"db/valueType": "db.type/vec"}
    },
    {"vector_opts": {"dimensions": 300, "metric_type": "cosine"}}
)
```

```javascript
const conn = d.createConn(
  '/tmp/mydb',
  {
    id: { 'db/valueType': 'db.type/string',
          'db/unique': 'db.unique/identity' },
    embedding: { 'db/valueType': 'db.type/vec' }
  },
  { vectorOpts: { dimensions: 300, metricType: 'cosine' } }
);
```

</div>

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

<div class="multi-lang">

```clojure
(d/q '[:find ?i ?v
       :in $ ?q
       :where
       [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]]
       [?e :id ?i]]
     (d/db conn) query-vector)
```

```java
Datalevin.q("[:find ?i ?v " +
            " :in $ ?q " +
            " :where " +
            " [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]] " +
            " [?e :id ?i]]",
            Datalevin.db(conn), queryVector);
```

```python
d.q('[:find ?i ?v '
     ' :in $ ?q '
     ' :where '
     ' [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]] '
     ' [?e :id ?i]]',
     d.db(conn), query_vector)
```

```javascript
d.q('[:find ?i ?v ' +
     ' :in $ ?q ' +
     ' :where ' +
     ' [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]] ' +
     ' [?e :id ?i]]',
     d.db(conn), queryVector);
```

</div>

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

## 3. Text Embedding Indexes

Use `:db/embedding true` when the source data is text and you want Datalevin to maintain the embedding vector index for you. Unlike `:db.type/vec`, the stored datom remains the original string. The embedding vector is a secondary index detail.

```clojure
(def schema
  {:doc/id   {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}
   :doc/text {:db/valueType            :db.type/string
              :db/embedding            true
              :db.embedding/domains    ["docs"]
              :db.embedding/autoDomain true}})

(def conn
  (d/create-conn
    "/tmp/embedding-db"
    schema
    {:embedding-opts {:provider    :default
                      :metric-type :cosine}}))
```

The built-in default provider uses a bundled CPU-only llama.cpp embedder. If no model path is supplied, Datalevin uses `multilingual-e5-small-Q8_0.gguf`; the model is downloaded under the database root on first use when missing. For larger models or hosted embedding APIs, use an OpenAI-compatible provider:

```clojure
{:embedding-opts
 {:provider           :openai-compatible
  :model              "text-embedding-3-small"
  :base-url           "https://api.openai.com/v1"
  :api-key-env        "OPENAI_API_KEY"
  :request-dimensions 1536
  :metric-type        :cosine}}
```

Query with `embedding-neighbors`. The query input is text, not a vector:

```clojure
(d/q '[:find ?id ?text
       :in $ ?q
       :where
       [(embedding-neighbors $ ?q {:domains ["docs"] :top 5})
        [[?e _ ?text]]]
       [?e :doc/id ?id]]
     (d/db conn)
     "vector search docs")
```

Attribute-specific embedding search requires `:db.embedding/autoDomain true`:

```clojure
(d/q '[:find ?id ?dist
       :in $ ?q
       :where
       [(embedding-neighbors $ :doc/text ?q {:top 5 :display :refs+dists})
        [[?e _ _ ?dist]]]
       [?e :doc/id ?id]]
     (d/db conn)
     "semantic database")
```

Important rules:
- `:db/embedding` applies to string attributes.
- If no embedding domain is specified, the attribute participates in the default `"datalevin"` embedding domain.
- `:db/embedding` may coexist with `:db/fulltext` on the same attribute.
- Changing embedding-related schema on populated attributes requires an explicit rebuild workflow.

---

## 4. Standalone Vector Database

Datalevin can be used as a standalone vector database:

<div class="multi-lang">

```clojure
(def lmdb (d/open-kv "/tmp/vector-db"))
(def index (d/new-vector-index lmdb {:dimensions 300}))

(d/add-vec index "cat" cat-vector)
(d/add-vec index "dog" dog-vector)

(d/search-vec index query-vector {:top 2})
;=> ("cat" "dog")
```

```java
LMDB lmdb = Datalevin.openKv("/tmp/vector-db");
VectorIndex index = Datalevin.newVectorIndex(lmdb,
    Map.of("dimensions", 300));

Datalevin.addVec(index, "cat", catVector);
Datalevin.addVec(index, "dog", dogVector);

Datalevin.searchVec(index, queryVector, Map.of("top", 2));
// => ["cat", "dog"]
```

```python
lmdb = d.open_kv("/tmp/vector-db")
index = d.new_vector_index(lmdb, {"dimensions": 300})

d.add_vec(index, "cat", cat_vector)
d.add_vec(index, "dog", dog_vector)

d.search_vec(index, query_vector, {"top": 2})
# => ["cat", "dog"]
```

```javascript
const lmdb = d.openKv('/tmp/vector-db');
const index = d.newVectorIndex(lmdb, { dimensions: 300 });

d.addVec(index, 'cat', catVector);
d.addVec(index, 'dog', dogVector);

d.searchVec(index, queryVector, { top: 2 });
// => ['cat', 'dog']
```

</div>

**Vector References**: In Datalog's `vec-neighbors`, results are datoms `[e a v]`. In standalone mode, `vec-ref` can be any Clojure data (under 512 bytes)—strings, numbers, maps, or any identifier meaningful to your application.

Multiple vectors can share the same `vec-ref`. For example, different image embeddings might all reference the same tag `"cat"`, or document chunks in a RAG system might all reference the same document ID.

---

## 5. The Core Engine: HNSW

Datalevin's vector search uses **Hierarchical Navigable Small World (HNSW)** graphs, implemented via the [usearch](https://github.com/unum-cloud/usearch) library with SIMD optimizations.

### 5.1 How HNSW Works

HNSW builds a multi-layer graph where each node is a vector:
- **Higher layers** have long-range connections for fast "skipping"
- **Lower layers** have short-range connections for precision

Search starts at the top layer and "zooms in" through descending layers to find nearest neighbors.

### 5.2 Performance Trade-offs

- **`:connectivity`** (M) — Higher = better recall, more memory
- **`:expansion-add`** (efConstruction) — Higher = better index quality, slower writes
- **`:expansion-search`** (ef) — Higher = better recall, slower queries

---

## 6. Hybrid Retrieval: Combining Logic and Similarity

Vector search integrates with Datalog, enabling hybrid queries that combine semantic similarity with structured filters:

<div class="multi-lang">

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

```java
Datalevin.q("[:find ?title ?v " +
            " :in $ ?target-vec " +
            " :where " +
            " [(vec-neighbors $ :product/embedding ?target-vec {:top 10}) [[?e _ ?v]]] " +
            " [?e :product/title ?title] " +
            " [?e :product/status :status/in-stock] " +
            " [?e :product/price ?price] " +
            " [(< ?price 100.0)]]",
            db, queryVec);
```

```python
d.q('[:find ?title ?v '
     ' :in $ ?target-vec '
     ' :where '
     ' [(vec-neighbors $ :product/embedding ?target-vec {:top 10}) [[?e _ ?v]]] '
     ' [?e :product/title ?title] '
     ' [?e :product/status :status/in-stock] '
     ' [?e :product/price ?price] '
     ' [(< ?price 100.0)]]',
     db, query_vec)
```

```javascript
d.q('[:find ?title ?v ' +
     ' :in $ ?target-vec ' +
     ' :where ' +
     ' [(vec-neighbors $ :product/embedding ?target-vec {:top 10}) [[?e _ ?v]]] ' +
     ' [?e :product/title ?title] ' +
     ' [?e :product/status :status/in-stock] ' +
     ' [?e :product/price ?price] ' +
     ' [(< ?price 100.0)]]',
     db, queryVec);
```

</div>

This is essential for **Retrieval-Augmented Generation (RAG)**: find semantically similar documents, then filter by metadata, access control, or recency.

---

## 7. Asynchronous Vector and Embedding Indexing

Vector and embedding indexing are synchronous by default. A transaction updates the source datoms and the secondary index before returning.

For ingestion-heavy workloads, configure async indexing:

```clojure
{:vector-opts {:dimensions    300
               :metric-type   :cosine
               :indexing-mode :async}}
```

or for embedding providers:

```clojure
{:embedding-opts
 {:provider      :openai-compatible
  :model         "text-embedding-3-small"
  :api-key-env   "OPENAI_API_KEY"
  :metric-type   :cosine
  :indexing-mode :async}}
```

In async mode, transactions commit the source datoms plus durable index jobs, and an in-process worker updates the secondary index after commit. Failed jobs retry with bounded backoff and worker leases, so a later worker can reclaim stalled work. Use `secondary-index-status` and `wait-for-secondary-index` to inspect lag or wait for a transaction's index work.

---

## Summary

Vector search transforms Datalevin into a semantic database. Use `:db.type/vec` and `vec-neighbors` when your application owns vectors directly; use `:db/embedding` and `embedding-neighbors` when Datalevin should embed string datoms and maintain the vector index for you.
