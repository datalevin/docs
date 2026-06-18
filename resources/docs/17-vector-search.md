---
title: "Vector Search: Embeddings and Similarity Queries"
chapter: 17
part: "IV — Indexes as Capabilities"
---

# Chapter 17: Vector Search: Embeddings and Similarity Queries

In the era of Large Language Models (LLMs), a database is not just a place to
store structured facts, but also a repository for semantic meanings, which are
often measured by vector similarity. Datalevin provides two related
similarity-search features:

- `:db.type/vec` stores user-supplied dense vectors and indexes them for
  nearest-neighbor search.
- `:db/embedding true` indexes string datoms by embedding similarity. Datalevin
  computes the vectors during transaction processing and returns the original
  source datoms in queries.

This chapter explains how to configure both paths, perform semantic searches,
and understand the underlying Hierarchical Navigable Small World (HNSW) engine.
The Java snippets use the high-level `Connection` API. Python snippets use
`datalevin.connect`, and JavaScript snippets use `connect` from
`datalevin-node`.

A **vector** is an ordered array of numbers. A **dense vector** usually has a
fixed number of dimensions, such as 384, 768, or 1536, and every position has a
numeric value. An **embedding** is a vector produced by a model from some input,
often text. Texts with similar meanings should land near each other in the
embedding space.

Vector search is **nearest-neighbor search**: given a query vector, find stored
vectors that are closest under a chosen metric. The metric defines what
"closest" means. For example, cosine distance compares direction, Euclidean
distance compares geometric distance, and dot product is often used when vectors
have already been normalized by the model or application.

---

## 1. Configuring Vector Search

To store vectors in Datalevin, define an attribute with type `:db.type/vec`.
Vector dimensions and similarity metrics are configured at the domain level (see
1.2):

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
Schema schema = Datalevin.schema()
    .attr("id",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY))
    .attr("embedding",
          Schema.attribute().valueType(Schema.ValueType.VEC));

Connection conn = Datalevin.createConn(
    "/tmp/mydb",
    schema,
    Map.of("vector-opts", Map.of(
        "dimensions", 300,
        "metric-type", ":cosine"
    ))
);
```

```python
from datalevin import connect

conn = connect(
    "/tmp/mydb",
    schema={
        ":id": {":db/valueType": ":db.type/string",
                ":db/unique": ":db.unique/identity"},
        ":embedding": {":db/valueType": ":db.type/vec"}
    },
    opts={":vector-opts": {":dimensions": 300,
                           ":metric-type": ":cosine"}}
)
```

```javascript
import { connect } from "datalevin-node";

const conn = await connect(
  '/tmp/mydb',
  {
    schema: {
      ':id': { ':db/valueType': ':db.type/string',
               ':db/unique': ':db.unique/identity' },
      ':embedding': { ':db/valueType': ':db.type/vec' }
    },
    opts: { ':vector-opts': { ':dimensions': 300,
                              ':metric-type': ':cosine' } }
  }
);
```

</div>

### 1.1 Vector Options

Configure these in `:vector-opts`:

- **`:dimensions`** — Number of dimensions. **Required**.
- **`:metric-type`** — Similarity metric:
  - `:euclidean` (default) — Euclidean distance
  - `:cosine` — Cosine-based distance, comparing vector direction
  - `:dot-product` — Dot product
  - `:haversine` — Great-circle distance for geo coordinates
  - `:divergence` — Jensen-Shannon divergence for probability distributions
  - `:pearson`, `:jaccard`, `:hamming`, `:tanimoto`, `:sorensen` — other
    specialized metrics
- **`:quantization`** — Scalar type: `:float` (default), `:double`, `:float16`,
  `:int8`, `:byte`
- **`:connectivity`** — Connections per node (default 16). Range 5-48.
- **`:expansion-add`** — Candidates during indexing (default 128)
- **`:expansion-search`** — Candidates during search (default 64)

Quantization controls how vector numbers are stored. `:float` and `:double`
preserve more numeric precision. Smaller formats such as `:float16`, `:int8`,
and `:byte` reduce memory and storage, but may slightly reduce search quality
because each coordinate is represented less precisely.

### 1.2 Vector and Embedding Domains

Chapter 16 introduced domains as named secondary indexes. Vector search uses the
same idea, but the backing index is an HNSW index rather than an inverted
full-text index, so the operational rules are different.

The most important difference is that vector search is always domain-scoped.
Unlike `fulltext`, `vec-neighbors` and `embedding-neighbors` do not search every
domain when no domain is specified. Use either the attribute-specific form or an
explicit `:domains` option.

For `:db.type/vec` attributes:

- Each vector attribute gets an attribute domain automatically.
- Namespaced attributes use underscores in domain names: `:embedding` becomes
  `"embedding"`, and `:user/profile` becomes `"user_profile"`.
- `:db.vec/domains` adds shared vector domains for an attribute; configure all
  participating domains, including the attribute domain, with compatible vector
  options.
- All vectors in a vector domain must have the same dimensions, metric, and
  quantization settings.

For `:db/embedding` attributes:

- Embedding domains are configured separately from raw vector domains.
- If no `:db.embedding/domains` is specified, the attribute participates in the
  default `"datalevin"` embedding domain.
- `:db.embedding/autoDomain true` adds the attribute domain and enables
  attribute-specific `embedding-neighbors`.
- Each embedding domain is tied to an embedding provider as well as vector
  search options such as metric and dimensions.

Configure defaults with `:vector-opts` and `:embedding-opts`; use
`:vector-domains` and `:embedding-domains` for per-domain overrides. The same
domain string can appear in full-text, vector, and embedding configuration, but
those are separate indexes.

---

## 2. Similarity Search with `vec-neighbors`

The `vec-neighbors` function returns matching datoms as `[e a v]` triples,
ordered by similarity:

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
conn.query("[:find ?i ?v " +
           " :in $ ?q " +
           " :where " +
           " [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]] " +
           " [?e :id ?i]]",
           queryVector);
```

```python
conn.query(
    "[:find ?i ?v "
    " :in $ ?q "
    " :where "
    " [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]] "
    " [?e :id ?i]]",
    query_vector,
)
```

```javascript
await conn.query(
  '[:find ?i ?v ' +
  ' :in $ ?q ' +
  ' :where ' +
  ' [(vec-neighbors $ :embedding ?q {:top 4}) [[?e ?a ?v]]] ' +
  ' [?e :id ?i]]',
  queryVector
);
```

</div>

### 2.1 Search Options

- `:top` — Number of results (default 10)
- `:domains` — List of domains to search
- `:display` — Result format:
  - `:refs` (default) — `[e a v]` triples
  - `:refs+dists` — `[e a v dist]` with the metric distance
- `:vec-filter` — Predicate function to filter by vec-ref

When `:display :refs+dists` is used, `dist` is a metric distance. Datalevin
returns nearest neighbors first. Lower distances usually mean closer neighbors,
but the numeric scale depends on the metric, model, quantization, and domain.
Do not compare vector distances directly with full-text scores or distances
from a different embedding space.

### 2.2 Domain-Specific Search

The attribute-specific form searches the attribute domain:

<div class="multi-lang">

```clojure
(d/q '[:find ?e ?dist
       :in $ ?q
       :where [(vec-neighbors $ :embedding ?q
                              {:top 4 :display :refs+dists})
               [[?e _ _ ?dist]]]]
     db
     query-vec)
```

```java
conn.query("[:find ?e ?dist " +
           " :in $ ?q " +
           " :where [(vec-neighbors $ :embedding ?q " +
           "                          {:top 4 :display :refs+dists}) " +
           "         [[?e _ _ ?dist]]]]",
           queryVector);
```

```python
conn.query(
    "[:find ?e ?dist "
    " :in $ ?q "
    " :where [(vec-neighbors $ :embedding ?q "
    "                          {:top 4 :display :refs+dists}) "
    "         [[?e _ _ ?dist]]]]",
    query_vector,
)
```

```javascript
await conn.query(
  '[:find ?e ?dist ' +
  ' :in $ ?q ' +
  ' :where [(vec-neighbors $ :embedding ?q ' +
  '                          {:top 4 :display :refs+dists}) ' +
  '         [[?e _ _ ?dist]]]]',
  queryVector
);
```

</div>

Use `:domains` when the query should search named vector domains directly:

<div class="multi-lang">

```clojure
(d/q '[:find ?e ?dist
       :in $ ?q
       :where [(vec-neighbors $ ?q
                              {:top 4
                               :domains ["embedding"]
                               :display :refs+dists})
               [[?e _ _ ?dist]]]]
     db
     query-vec)
```

```java
conn.query("[:find ?e ?dist " +
           " :in $ ?q " +
           " :where [(vec-neighbors $ ?q " +
           "                          {:top 4 " +
           "                           :domains [\"embedding\"] " +
           "                           :display :refs+dists}) " +
           "         [[?e _ _ ?dist]]]]",
           queryVector);
```

```python
conn.query(
    "[:find ?e ?dist "
    " :in $ ?q "
    " :where [(vec-neighbors $ ?q "
    "                          {:top 4 "
    "                           :domains [\"embedding\"] "
    "                           :display :refs+dists}) "
    "         [[?e _ _ ?dist]]]]",
    query_vector,
)
```

```javascript
await conn.query(
  '[:find ?e ?dist ' +
  ' :in $ ?q ' +
  ' :where [(vec-neighbors $ ?q ' +
  '                          {:top 4 ' +
  '                           :domains ["embedding"] ' +
  '                           :display :refs+dists}) ' +
  '         [[?e _ _ ?dist]]]]',
  queryVector
);
```

</div>

---

## 3. Text Embedding Indexes

Use `:db/embedding true` when the source data is text and you want Datalevin to
maintain the embedding vector index for you. Unlike `:db.type/vec`, the stored
datom remains the original string. The embedding vector is a secondary index
detail.

An embedding index has two moving parts:

- An **embedding provider** turns text into vectors. It may be the built-in
  local llama.cpp provider or an OpenAI-compatible HTTP provider.
- A **vector index** stores those generated vectors and finds nearest neighbors.

The provider, model, dimensions, metric, and quantization together define an
embedding space. Values from different embedding spaces should not be mixed in
one domain.

![Text embedding indexing pipeline: on write, transacting :doc/text stores the datom as-is in the Datalog store (the text stays the source of truth) and also sends the text through the embedding provider to a vector that is inserted into the HNSW index keyed to the entity — the vector is only a secondary index, unlike :db.type/vec which stores the vector itself; on query, embedding-neighbors runs the query text through the same provider and embedding space to a query vector, the HNSW index does nearest-neighbor search, and the nearest entities' stored :doc/text is pulled](/images/diagrams/embedding-index-pipeline.svg)

<div class="multi-lang">

```clojure
(def embedding-schema
  {:doc/id   {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}
   :doc/text {:db/valueType            :db.type/string
              :db/embedding            true
              :db.embedding/domains    ["docs"]
              :db.embedding/autoDomain true}})

(def conn
  (d/create-conn
    "/tmp/embedding-db"
    embedding-schema
    {:embedding-opts {:provider    :default
                      :metric-type :cosine}}))
```

```java
Schema embeddingSchema = Datalevin.schema()
    .attr("doc/id",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY))
    .attr("doc/text",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .prop("db/embedding", true)
              .prop("db.embedding/domains", List.of("docs"))
              .prop("db.embedding/autoDomain", true));

Connection conn = Datalevin.createConn(
    "/tmp/embedding-db",
    embeddingSchema,
    Map.of("embedding-opts", Map.of(
        "provider", ":default",
        "metric-type", ":cosine"
    ))
);
```

```python
from datalevin import connect

embedding_schema = {
    ":doc/id": {
        ":db/valueType": ":db.type/string",
        ":db/unique": ":db.unique/identity",
    },
    ":doc/text": {
        ":db/valueType": ":db.type/string",
        ":db/embedding": True,
        ":db.embedding/domains": ["docs"],
        ":db.embedding/autoDomain": True,
    },
}

conn = connect(
    "/tmp/embedding-db",
    schema=embedding_schema,
    opts={":embedding-opts": {":provider": ":default",
                              ":metric-type": ":cosine"}},
)
```

```javascript
import { connect } from "datalevin-node";

const embeddingSchema = {
  ':doc/id': {
    ':db/valueType': ':db.type/string',
    ':db/unique': ':db.unique/identity'
  },
  ':doc/text': {
    ':db/valueType': ':db.type/string',
    ':db/embedding': true,
    ':db.embedding/domains': ['docs'],
    ':db.embedding/autoDomain': true
  }
};

const conn = await connect('/tmp/embedding-db', {
  schema: embeddingSchema,
  opts: { ':embedding-opts': { ':provider': ':default',
                               ':metric-type': ':cosine' } }
});
```

</div>

The built-in default provider uses a bundled CPU-only llama.cpp embedder. If no
model path is supplied, Datalevin uses `multilingual-e5-small-Q8_0.gguf`; the
model is downloaded under the database root on first use when missing. GGUF is
the local model file format used by llama.cpp. For larger models or hosted
embedding APIs, use an OpenAI-compatible provider:

<div class="multi-lang">

```clojure
{:embedding-opts
 {:provider           :openai-compatible
  :model              "text-embedding-3-small"
  :base-url           "https://api.openai.com/v1"
  :api-key-env        "OPENAI_API_KEY"
  :request-dimensions 1536
  :metric-type        :cosine}}
```

```java
Map<String, Object> opts = Map.of(
    "embedding-opts", Map.of(
        "provider", ":openai-compatible",
        "model", "text-embedding-3-small",
        "base-url", "https://api.openai.com/v1",
        "api-key-env", "OPENAI_API_KEY",
        "request-dimensions", 1536,
        "metric-type", ":cosine"
    )
);
```

```python
opts = {
    ":embedding-opts": {
        ":provider": ":openai-compatible",
        ":model": "text-embedding-3-small",
        ":base-url": "https://api.openai.com/v1",
        ":api-key-env": "OPENAI_API_KEY",
        ":request-dimensions": 1536,
        ":metric-type": ":cosine",
    }
}
```

```javascript
const opts = {
  ':embedding-opts': {
    ':provider': ':openai-compatible',
    ':model': 'text-embedding-3-small',
    ':base-url': 'https://api.openai.com/v1',
    ':api-key-env': 'OPENAI_API_KEY',
    ':request-dimensions': 1536,
    ':metric-type': ':cosine'
  }
};
```

</div>

Query with `embedding-neighbors`. The query input is text, not a vector:

<div class="multi-lang">

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

```java
conn.query("[:find ?id ?text " +
           " :in $ ?q " +
           " :where " +
           " [(embedding-neighbors $ ?q {:domains [\"docs\"] :top 5}) " +
           "  [[?e _ ?text]]] " +
           " [?e :doc/id ?id]]",
           "vector search docs");
```

```python
conn.query(
    "[:find ?id ?text "
    " :in $ ?q "
    " :where "
    " [(embedding-neighbors $ ?q {:domains [\"docs\"] :top 5}) "
    "  [[?e _ ?text]]] "
    " [?e :doc/id ?id]]",
    "vector search docs",
)
```

```javascript
await conn.query(
  '[:find ?id ?text ' +
  ' :in $ ?q ' +
  ' :where ' +
  ' [(embedding-neighbors $ ?q {:domains ["docs"] :top 5}) ' +
  '  [[?e _ ?text]]] ' +
  ' [?e :doc/id ?id]]',
  'vector search docs'
);
```

</div>

Attribute-specific embedding search requires `:db.embedding/autoDomain true`:

<div class="multi-lang">

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

```java
conn.query("[:find ?id ?dist " +
           " :in $ ?q " +
           " :where " +
           " [(embedding-neighbors $ :doc/text ?q " +
           "                      {:top 5 :display :refs+dists}) " +
           "  [[?e _ _ ?dist]]] " +
           " [?e :doc/id ?id]]",
           "semantic database");
```

```python
conn.query(
    "[:find ?id ?dist "
    " :in $ ?q "
    " :where "
    " [(embedding-neighbors $ :doc/text ?q "
    "                      {:top 5 :display :refs+dists}) "
    "  [[?e _ _ ?dist]]] "
    " [?e :doc/id ?id]]",
    "semantic database",
)
```

```javascript
await conn.query(
  '[:find ?id ?dist ' +
  ' :in $ ?q ' +
  ' :where ' +
  ' [(embedding-neighbors $ :doc/text ?q ' +
  '                      {:top 5 :display :refs+dists}) ' +
  '  [[?e _ _ ?dist]]] ' +
  ' [?e :doc/id ?id]]',
  'semantic database'
);
```

</div>

### 3.1 Direct Embedding Provider API

Most applications use `:db/embedding` and let Datalevin embed string datoms
during transactions. The direct provider API is useful when application code
needs embeddings before a transaction, wants to check token counts, or needs to
truncate text to a model limit:

```clojure
(def provider
  (d/new-embedding-provider
    {:provider    :openai-compatible
     :model       "text-embedding-3-small"
     :base-url    "https://api.openai.com/v1"
     :api-key-env "OPENAI_API_KEY"}))

(try
  {:metadata   (d/embedding-metadata provider)
   :dimensions (d/embedding-dimensions provider)
   :tokens     (d/token-count provider "Datalevin vector search")
   :vectors    (d/embed-texts
                 provider
                 [(d/truncate-text provider
                                   "Datalevin vector search"
                                   512)])}
  (finally
    (d/close-embedding-provider provider)))
```

`embed-text` returns one float array. `embed-texts` returns one float array per
input string. `token-count`, `token-counts`, `truncate-item`, and
`truncate-text` are provider helpers for preparing inputs before indexing or
prompt assembly.

Important rules:
- `:db/embedding` applies to string attributes.
- If no embedding domain is specified, the attribute participates in the default
  `"datalevin"` embedding domain.
- `:db/embedding` may coexist with `:db/fulltext` on the same attribute.
- Changing embedding-related schema on populated attributes requires an explicit
  rebuild workflow. In practice, changing the provider, model, dimensions,
  metric, or quantization means old indexed vectors were produced in a different
  embedding space; rebuild or re-index before relying on search results.

---

## 4. Standalone Vector Database

Datalevin can be used as a standalone vector database:

The standalone vector-index API is shown in Clojure. In Java, Python, and
JavaScript, use the Datalog vector examples above unless the binding layer you
are using exposes standalone vector-index handles.

```clojure
(def lmdb (d/open-kv "/tmp/vector-db"))
(def index (d/new-vector-index lmdb {:dimensions 300}))

(d/add-vec index "cat" cat-vector)
(d/add-vec index "dog" dog-vector)

(d/search-vec index query-vector {:top 2})
;=> ("cat" "dog")
```

**Vector References**: In Datalog's `vec-neighbors`, results are datoms `[e a
v]`. In standalone mode, `vec-ref` can be any Clojure data (under 512
bytes)—strings, numbers, maps, or any identifier meaningful to your application.

Multiple vectors can share the same `vec-ref`. For example, different image
embeddings might all reference the same tag `"cat"`, or document chunks in a RAG
system might all reference the same document ID.

### 4.1 Vector Index Lifecycle and Introspection

Standalone vector indexes also expose cleanup and inspection functions:

```clojure
(d/vector-index-info index)
;=> {:size 2, :dimensions 300, :metric-type :euclidean, ...}

(d/remove-vec index "dog")

(d/force-vec-checkpoint! index)
(d/vector-checkpoint-state index)

(d/close-vector-index index)
```

`remove-vec` removes all vectors associated with a `vec-ref`.
`clear-vector-index` closes the index and deletes all vectors.
`close-vector-index` only releases index resources. `vector-index-info` reports
size, memory, configuration, hardware, domain, and checkpoint metadata.

---

## 5. The Core Engine: HNSW

Datalevin's vector search uses **Hierarchical Navigable Small World (HNSW)**
graphs, the graph-based approximate nearest-neighbor method introduced by
Malkov and Yashunin [1], implemented via the
[usearch](https://github.com/unum-cloud/usearch) library with SIMD
optimizations.

HNSW is an **approximate nearest-neighbor** index. It trades a tiny chance of
missing the exact nearest vector for much faster search on large vector sets.
The quality measure for this trade-off is **recall**: the fraction of true
nearest neighbors that the approximate search returns. Higher recall usually
costs more memory, indexing time, or query time.

SIMD means "single instruction, multiple data": CPU vector instructions that
operate on several numeric coordinates at once. It is an implementation detail,
but it is one reason vector distance calculations can be fast on modern CPUs.

### 5.1 How HNSW Works

HNSW builds a multi-layer graph where each node is a vector:
- **Higher layers** have long-range connections for fast "skipping"
- **Lower layers** have short-range connections for precision

Search starts at the top layer and "zooms in" through descending layers to find
nearest neighbors.

![HNSW multi-layer graph search: the sparse top layer has long-range links for fast skipping and the dense bottom layer holds every vector with short-range links; search starts at the entry point and descends layer by layer, hopping to closer nodes until it reaches the nearest neighbor in Layer 0](/images/diagrams/hnsw-layers.svg)

### 5.2 Performance Trade-offs

- **`:connectivity`** (M) — Higher = better recall, more memory
- **`:expansion-add`** (efConstruction) — Higher = better index quality, slower
  writes
- **`:expansion-search`** (ef) — Higher = better recall, slower queries

`connectivity` controls how many graph neighbors each vector can keep.
`expansion-add` controls how thoroughly the graph is searched while inserting a
new vector. `expansion-search` controls how many candidates are explored during
a query. If search results look unstable or miss obvious neighbors, raise
`:expansion-search` first; if newly inserted data has poor recall, tune
`:connectivity` and `:expansion-add` before rebuilding.

---

## 6. Hybrid Retrieval: Combining Logic and Similarity

Vector search integrates with Datalog, enabling hybrid queries that combine
semantic similarity with structured filters:

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
conn.query("[:find ?title ?v " +
           " :in $ ?target-vec " +
           " :where " +
           " [(vec-neighbors $ :product/embedding ?target-vec {:top 10}) [[?e _ ?v]]] " +
           " [?e :product/title ?title] " +
           " [?e :product/status :status/in-stock] " +
           " [?e :product/price ?price] " +
           " [(< ?price 100.0)]]",
           queryVec);
```

```python
conn.query(
    "[:find ?title ?v "
    " :in $ ?target-vec "
    " :where "
    " [(vec-neighbors $ :product/embedding ?target-vec {:top 10}) [[?e _ ?v]]] "
    " [?e :product/title ?title] "
    " [?e :product/status :status/in-stock] "
    " [?e :product/price ?price] "
    " [(< ?price 100.0)]]",
    query_vec,
)
```

```javascript
await conn.query(
  '[:find ?title ?v ' +
  ' :in $ ?target-vec ' +
  ' :where ' +
  ' [(vec-neighbors $ :product/embedding ?target-vec {:top 10}) [[?e _ ?v]]] ' +
  ' [?e :product/title ?title] ' +
  ' [?e :product/status :status/in-stock] ' +
  ' [?e :product/price ?price] ' +
  ' [(< ?price 100.0)]]',
  queryVec
);
```

</div>

This is essential for **Retrieval-Augmented Generation (RAG)**: find
semantically similar documents, then filter by metadata, access control, or
recency.

---

## 7. Asynchronous Vector and Embedding Indexing

Vector and embedding indexing are synchronous by default. A transaction updates
the source datoms and the secondary index before returning.

For ingestion-heavy workloads, configure async indexing:

<div class="multi-lang">

```clojure
{:vector-opts {:dimensions    300
               :metric-type   :cosine
               :indexing-mode :async}}
```

```java
Map<String, Object> opts = Map.of(
    "vector-opts", Map.of(
        "dimensions", 300,
        "metric-type", ":cosine",
        "indexing-mode", ":async"
    )
);
```

```python
opts = {
    ":vector-opts": {
        ":dimensions": 300,
        ":metric-type": ":cosine",
        ":indexing-mode": ":async",
    }
}
```

```javascript
const opts = {
  ':vector-opts': {
    ':dimensions': 300,
    ':metric-type': ':cosine',
    ':indexing-mode': ':async'
  }
};
```

</div>

or for embedding providers:

<div class="multi-lang">

```clojure
{:embedding-opts
 {:provider      :openai-compatible
  :model         "text-embedding-3-small"
  :api-key-env   "OPENAI_API_KEY"
  :metric-type   :cosine
  :indexing-mode :async}}
```

```java
Map<String, Object> opts = Map.of(
    "embedding-opts", Map.of(
        "provider", ":openai-compatible",
        "model", "text-embedding-3-small",
        "api-key-env", "OPENAI_API_KEY",
        "metric-type", ":cosine",
        "indexing-mode", ":async"
    )
);
```

```python
opts = {
    ":embedding-opts": {
        ":provider": ":openai-compatible",
        ":model": "text-embedding-3-small",
        ":api-key-env": "OPENAI_API_KEY",
        ":metric-type": ":cosine",
        ":indexing-mode": ":async",
    }
}
```

```javascript
const opts = {
  ':embedding-opts': {
    ':provider': ':openai-compatible',
    ':model': 'text-embedding-3-small',
    ':api-key-env': 'OPENAI_API_KEY',
    ':metric-type': ':cosine',
    ':indexing-mode': ':async'
  }
};
```

</div>

In async mode, transactions commit the source datoms plus durable index jobs,
and an in-process worker updates the secondary index after commit. Failed jobs
retry with bounded backoff and worker leases, so a later worker can reclaim
stalled work. Use `secondary-index-status` and `process-secondary-index-jobs!`
to inspect or manually process pending work, and use
`wait-for-secondary-index` to wait for a transaction's index work.

---

## Summary

Vector search transforms Datalevin into a semantic database. Use `:db.type/vec`
and `vec-neighbors` when your application owns vectors directly; use
`:db/embedding` and `embedding-neighbors` when Datalevin should embed string
datoms and maintain the vector index for you.

---

## References

[1] Yu. A. Malkov and D. A. Yashunin,
   [Efficient and robust approximate nearest neighbor search using Hierarchical
   Navigable Small World graphs](https://arxiv.org/abs/1603.09320),
   arXiv:1603.09320, 2016; IEEE Transactions on Pattern Analysis and Machine
   Intelligence 42(4):824-836, 2020,
   [doi:10.1109/TPAMI.2018.2889473](https://doi.org/10.1109/TPAMI.2018.2889473).
