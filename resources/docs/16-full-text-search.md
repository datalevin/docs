---
title: "Full-Text Search: Queries and Ranking"
chapter: 16
part: "IV — Indexes as Capabilities"
---

# Chapter 16: Full-Text Search: Queries and Ranking

Most databases require a separate engine like Lucene, Elasticsearch or Solr for
full-text search. Datalevin includes a high-performance, integrated search
engine built directly on top of its KV substrate. In the default synchronous
indexing mode, your search index is transactionally consistent with your data
and stored in the same LMDB data file. For heavier ingestion workloads,
full-text domains can opt into asynchronous indexing.

This chapter explains how to enable full-text search, craft powerful search
queries using boolean logic, and understand the underlying ranking algorithm.

---

## 1. Enabling Full-Text Search

To search for text within an attribute, declare `:db/fulltext true` in your
schema:

<div class="multi-lang">

```clojure
(def schema
  {:post/title   {:db/valueType :db.type/string
                  :db/fulltext  true
                  :db.fulltext/autoDomain true}
   :post/content {:db/valueType :db.type/string
                  :db/fulltext  true}})
```

```java
Schema schema = Datalevin.schema()
    .attr("post/title",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true)
              .prop("db.fulltext/autoDomain", true))
    .attr("post/content",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true));
```

```python
schema = {
    ":post/title": {
        ":db/valueType": ":db.type/string",
        ":db/fulltext": True,
        ":db.fulltext/autoDomain": True
    },
    ":post/content": {
        ":db/valueType": ":db.type/string",
        ":db/fulltext": True
    }
}
```

```javascript
const schema = {
  ":post/title": {
    ":db/valueType": ":db.type/string",
    ":db/fulltext": true,
    ":db.fulltext/autoDomain": true
  },
  ":post/content": {
    ":db/valueType": ":db.type/string",
    ":db/fulltext": true
  }
};
```

</div>

The concept of search domains and `:db.fulltext/autoDomain` will be explained in
section 3.

The examples use `:db.type/string` because full-text search is usually applied
to human text. It is not a hard requirement of `:db/fulltext`: during
transaction processing, Datalevin calls `str` on the value before sending it to
the analyzer. Embedding search is different; `:db/embedding` requires a string
attribute.

---

## 2. Querying with `fulltext`

The `fulltext` function returns matching datoms as `[e a v]` triples, ordered
by relevance. In a query, `fulltext` behaves like a relation-producing
function: it takes a database and a search expression, then yields rows that can
be joined with ordinary Datalog clauses through shared variables such as `?e`.

<div class="multi-lang">

```clojure
(d/q '[:find ?e ?a ?v
       :in $ ?q
       :where [(fulltext $ ?q) [[?e ?a ?v]]]]
     db
     "red fox")
```

```java
conn.query("[:find ?e ?a ?v " +
           " :in $ ?q " +
           " :where [(fulltext $ ?q) [[?e ?a ?v]]]]",
           "red fox");
```

```python
conn.query('[:find ?e ?a ?v '
           ' :in $ ?q '
           ' :where [(fulltext $ ?q) [[?e ?a ?v]]]]',
           "red fox")
```

```javascript
await conn.query('[:find ?e ?a ?v ' +
                 ' :in $ ?q ' +
                 ' :where [(fulltext $ ?q) [[?e ?a ?v]]]]',
                 "red fox");
```

</div>

Use `fulltext-datoms` / `fulltextDatoms` / `fulltext_datoms` when you want
matching datoms directly instead of joining through a Datalog query:

<div class="multi-lang">

```clojure
(d/fulltext-datoms db "red fox" {:top 10})
;=> (#datalevin/Datom [123 :post/body "The red fox ..."] ...)
```

```java
import java.util.Map;

conn.fulltextDatoms("red fox", Map.of(":top", 10));
```

```python
conn.fulltext_datoms("red fox", opts={":top": 10})
```

```javascript
await conn.fulltextDatoms("red fox", { opts: { ":top": 10 } });
```

</div>

### 2.1 Attribute-Specific Search

To search within a specific attribute, that attribute must have
`:db.fulltext/autoDomain true` in the schema. An auto domain is a separate
per-attribute full-text index, so `:post/title` can be searched without also
searching `:post/content`; section 3 explains domains in detail.

<div class="multi-lang">

```clojure
(d/q '[:find ?e ?a ?v
       :in $ ?q
       :where [(fulltext $ :post/title ?q) [[?e ?a ?v]]]]
     db
     "clojure")
```

```java
conn.query("[:find ?e ?a ?v " +
           " :in $ ?q " +
           " :where [(fulltext $ :post/title ?q) [[?e ?a ?v]]]]",
           "clojure");
```

```python
conn.query('[:find ?e ?a ?v '
           ' :in $ ?q '
           ' :where [(fulltext $ :post/title ?q) [[?e ?a ?v]]]]',
           "clojure")
```

```javascript
await conn.query('[:find ?e ?a ?v ' +
                 ' :in $ ?q ' +
                 ' :where [(fulltext $ :post/title ?q) [[?e ?a ?v]]]]',
                 "clojure");
```

</div>

### 2.2 Search Options

Pass an option map as the last argument:

<div class="multi-lang">

```clojure
(d/q '[:find ?e ?a ?v
       :in $ ?q
       :where [(fulltext $ ?q {:top 5}) [[?e ?a ?v]]]]
     db
     "database")
```

```java
conn.query("[:find ?e ?a ?v " +
           " :in $ ?q " +
           " :where [(fulltext $ ?q {:top 5}) [[?e ?a ?v]]]]",
           "database");
```

```python
conn.query('[:find ?e ?a ?v '
           ' :in $ ?q '
           ' :where [(fulltext $ ?q {:top 5}) [[?e ?a ?v]]]]',
           "database")
```

```javascript
await conn.query('[:find ?e ?a ?v ' +
                 ' :in $ ?q ' +
                 ' :where [(fulltext $ ?q {:top 5}) [[?e ?a ?v]]]]',
                 "database");
```

</div>

Available options:
- `:top` — number of results (default 10)
- `:display` — `:refs` (default), `:refs+scores`, `:texts`, `:offsets`, or
  `:texts+offsets`
- `:domains` — list of search domains to query
- `:doc-filter` — predicate function to filter results

`:refs` returns document references. In a Datalog store, the document reference
is the indexed datom itself, so it can be destructured into `?e`, `?a`, and `?v`
component variables.

Scores are relevance scores for one full-text query. Higher scores mean a better
match within that query, but scores are not stable identifiers and should not be
compared across unrelated queries, domains, analyzers, or ranking modes.
`:texts` are the original text, available only when the search engine stores raw
text with `:include-text? true`.

`:offsets` report where matched terms occur in the original document text. They
are useful for highlighting snippets. This requires `:index-position? true`
(default is `false`) because the index must store term positions and character
offsets at indexing time.

Full-text domains store document references and the inverted index by default,
not a second copy of the original document text. Datalog and standalone search
use the same model: standalone `add-doc` receives an application-supplied
`doc-ref`, while Datalog uses the indexed datom as the `doc-ref`. The source
datom remains in the Datalog database, but the full-text index itself stores raw
text only when the domain option `:include-text? true` is enabled. That option
is required for `:display :texts`, `:display :texts+offsets`, and search-engine
re-indexing.

```clojure
(d/create-conn
  "/tmp/search-db"
  schema
  {:search-opts {:include-text? true}})

(d/create-conn
  "/tmp/search-db"
  schema
  {:search-domains {"public" {:include-text? true}}})
```

When `:display` is not `:refs`, destructure the extra values in the returned
tuple. For example, relevance scores use `[e a v score]`:

```clojure
(d/q '[:find ?e ?score
       :in $ ?q
       :where [(fulltext $ ?q {:top 5 :display :refs+scores})
               [[?e _ _ ?score]]]]
     db
     "database")
```

---

## 3. Search Domains

A search domain is a named full-text index. Each domain has its own search
engine, term statistics, analyzer settings, position index setting, and indexing
mode. Domains let one database support different search surfaces without
running a separate service: public site search, private draft search,
autocomplete, admin-only search, or an attribute-specific index can all live in
the same Datalevin store.

Domains are strings. They are not Datalog namespaces, and they are not inferred
from an attribute namespace unless you ask Datalevin to create an automatic
attribute domain.

There are two separate decisions:

- Schema properties assign attributes to domains.
- Store options configure the search engine used by each domain.

![Search domain membership: :post/title (domains public + autoDomain) belongs to the named domain public and its own attribute domain post/title; :post/body belongs to public; :post/draft belongs to private; :note/body, with no domain keys, belongs to the default datalevin domain — showing the default, named, and automatic attribute domain kinds and that one attribute can join several domains](/images/diagrams/search-domains.svg)

### 3.1 Assigning Attributes to Domains

Full-text attributes enter domains according to these rules:

| Schema on a `:db/fulltext` attribute | Domain membership |
| --- | --- |
| No domain keys | The default `"datalevin"` domain |
| `:db.fulltext/domains ["public"]` | Only the listed domains |
| `:db.fulltext/domains ["datalevin" "public"]` | The default domain plus `public` |
| `:db.fulltext/autoDomain true` | Also an attribute domain, such as `"post/title"` |

If both `:db.fulltext/domains` and `:db.fulltext/autoDomain` are present, the
attribute participates in all listed domains plus its attribute domain. Include
`"datalevin"` explicitly when an attribute with listed domains should also be
searched through the default domain.

Full-text attribute domains use the attribute name without the leading colon:
`:title` becomes `"title"` and `:post/title` becomes `"post/title"`. This rule
is specific to full-text search. Vector and embedding domains use a different
storage naming rule for namespaced attributes.

<div class="multi-lang">

```clojure
(def search-schema
  {:post/title {:db/valueType :db.type/string
                :db/fulltext  true
                :db.fulltext/domains ["public"]
                :db.fulltext/autoDomain true}
   :post/body  {:db/valueType :db.type/string
                :db/fulltext  true
                :db.fulltext/domains ["public"]}
   :post/draft {:db/valueType :db.type/string
                :db/fulltext  true
                :db.fulltext/domains ["private"]}
   :note/body  {:db/valueType :db.type/string
                :db/fulltext  true}})
```

```java
Schema searchSchema = Datalevin.schema()
    .attr("post/title",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true)
              .prop("db.fulltext/domains", List.of("public"))
              .prop("db.fulltext/autoDomain", true))
    .attr("post/body",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true)
              .prop("db.fulltext/domains", List.of("public")))
    .attr("post/draft",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true)
              .prop("db.fulltext/domains", List.of("private")))
    .attr("note/body",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true));
```

```python
search_schema = {
    ":post/title": {
        ":db/valueType": ":db.type/string",
        ":db/fulltext": True,
        ":db.fulltext/domains": ["public"],
        ":db.fulltext/autoDomain": True
    },
    ":post/body": {
        ":db/valueType": ":db.type/string",
        ":db/fulltext": True,
        ":db.fulltext/domains": ["public"]
    },
    ":post/draft": {
        ":db/valueType": ":db.type/string",
        ":db/fulltext": True,
        ":db.fulltext/domains": ["private"]
    },
    ":note/body": {
        ":db/valueType": ":db.type/string",
        ":db/fulltext": True
    }
}
```

```javascript
const searchSchema = {
  ":post/title": {
    ":db/valueType": ":db.type/string",
    ":db/fulltext": true,
    ":db.fulltext/domains": ["public"],
    ":db.fulltext/autoDomain": true
  },
  ":post/body": {
    ":db/valueType": ":db.type/string",
    ":db/fulltext": true,
    ":db.fulltext/domains": ["public"]
  },
  ":post/draft": {
    ":db/valueType": ":db.type/string",
    ":db/fulltext": true,
    ":db.fulltext/domains": ["private"]
  },
  ":note/body": {
    ":db/valueType": ":db.type/string",
    ":db/fulltext": true
  }
};
```

</div>

In this schema, `:post/title` is indexed in `"public"` and `"post/title"`,
`:post/body` is indexed in `"public"`, `:post/draft` is indexed in
`"private"`, and `:note/body` is indexed in the default `"datalevin"` domain.

### 3.2 Configuring Domains

Use `:search-domains` to configure named domains. A domain mentioned in schema
but omitted from `:search-domains` is still created with default search-engine
settings. Use `:search-opts` for the default `"datalevin"` domain.

```clojure
(def conn
  (d/create-conn
    "/tmp/search-domains"
    search-schema
    {:search-opts
     {:index-position? true}

     :search-domains
     {"public"     {:index-position? true}
      "private"    {:indexing-mode :async}
      "post/title" {:index-position? true}}}))
```

Use per-domain configuration when search surfaces need different behavior. For
example, a public documentation domain may enable phrase search with
`:index-position? true`, while a high-ingestion private domain may use
`:indexing-mode :async`. Autocomplete can be another domain over the same
attribute with a prefix analyzer.

### 3.3 Querying Domains

An unqualified `fulltext` call searches all full-text domains in the database.
Use `:domains` to limit the query to one or more named domains:

```clojure
;; Search every full-text domain.
(d/q '[:find ?e ?a ?v
       :where [(fulltext $ "clojure") [[?e ?a ?v]]]]
     db)

;; Search only public text.
(d/q '[:find ?e ?a ?v
       :where [(fulltext $ "clojure" {:domains ["public"]}) [[?e ?a ?v]]]]
     db)

;; Search only private drafts.
(d/q '[:find ?e ?a ?v
       :where [(fulltext $ "roadmap" {:domains ["private"]}) [[?e ?a ?v]]]]
     db)

;; Search only :post/title through its auto-created attribute domain.
(d/q '[:find ?e ?v
       :where [(fulltext $ :post/title "clojure") [[?e _ ?v]]]]
     db)
```

The attribute-specific form is a convenience for querying the attribute domain.
It is available only when the attribute has `:db.fulltext/autoDomain true`.
Domain-specific search is more general: it can search any named domain, including
domains that combine several attributes.

In summary, when no explicit domains are supplied, full-text attributes
participate in the default `"datalevin"` domain. Attributes with
`:db.fulltext/autoDomain true` also become their own search domain, named after
the attribute without the leading colon, such as `"post/title"`.

---

## 4. Search Expressions and Boolean Logic

Datalevin uses Clojure data structures for search expressions, enabling
arbitrary boolean combinations. A string query is analyzed into search terms; a
structured expression lets you say how those terms should combine. A **term** is
the normalized token produced by the analyzer, not necessarily the exact word
typed by the user. For example, a stemming analyzer may turn `"running"` into
`"run"`.

```clojure
[:and "clojure" "database"]              ; both terms
[:or "fox" "red"]                        ; either term
[:not "java"]                            ; exclude term
[:and "clojure" [:not "java"]]           ; clojure but not java
```

### 4.1 Phrase Search

Phrases are encoded as maps. Requires `:index-position? true` at index time:

```clojure
[:and {:phrase "little lamb"} "fleece"]
```

### 4.2 Complex Expressions

Boolean operators can be arbitrarily nested:

```clojure
[:or "fox" "red" [:and "black" "sheep" [:not "yellow"]]]
```

---

## 5. Analyzers: Tokenization and Normalization

When you transact a value into a full-text attribute, Datalevin first converts
the value with `str`, then passes the resulting text through an **Analyzer**:

1. **Tokenization**: Breaking text into terms
2. **Normalization**: Lowercasing, punctuation handling
3. **Stop-word removal**: Filtering common words (optional)

Custom analyzers can be provided via the `:analyzer` option when creating a
search engine. Utility functions for stemming, ngrams, and more are in
`datalevin.search-utils`.

An analyzer turns text into `[term position offset]` triples. The term is the
searchable token. The position is the token number inside the document, used for
phrase and proximity scoring. The offset is the character offset in the original
text, used for highlighting.

![Analyzer pipeline: document d1 "Running runners in databases" is split by a regexp tokenizer into tokens with positions and offsets; token filters lower-case, drop the stop word "in", and stem the rest, preserving positions to yield ["run" 0 0], ["runner" 1 8], ["databas" 3 19]; a second document d2 "database run" runs through the same analyzer, and both are indexed by term in the inverted index, which maps each term to the documents and positions where it appears (run to d1·0 and d2·1, runner to d1·1, databas to d1·3 and d2·0)](/images/diagrams/analyzer-pipeline.svg)

### 5.1 Analyzer Pipelines with `datalevin.search-utils`

The `datalevin.search-utils` namespace provides tokenizer and token-filter
functions for building analyzers. An analyzer returns tokens as `[term position
offset]` triples.

```clojure
(require '[datalevin.search-utils :as su])

(def stem-analyzer
  (su/create-analyzer
    {:tokenizer (su/create-regexp-tokenizer #"\W+")
     :token-filters [su/lower-case-token-filter
                     su/en-stop-words-token-filter
                     (su/create-stemming-token-filter "english")]}))

(mapv vec (stem-analyzer "Running runners in databases"))
;; => [["run" 0 0] ["runner" 1 8] ["databas" 3 19]]
```

Use the analyzer in Datalog full-text options when the search domain should use
the same normalization at index time and query time:

```clojure
(def conn
  (d/create-conn
    "/tmp/posts"
    {:post/body {:db/valueType :db.type/string
                 :db/fulltext  true}}
    {:search-opts {:analyzer stem-analyzer}}))

(d/transact! conn
  [{:db/id 10 :post/body "Runners are running fast"}
   {:db/id 11 :post/body "Databases store data"}])

(d/q '[:find [?e ...]
       :where [(fulltext $ "run") [[?e _ _]]]]
     (d/db conn))
;; => [10]
```

### 5.2 Prefix and N-Gram Indexing

For autocomplete, apply `prefix-token-filter` at index time, but use a normal
query analyzer. This indexes `"search"` as `"s"`, `"se"`, `"sea"`, and so on,
while a query for `"sea"` remains a single token.

```clojure
(def prefix-index-analyzer
  (su/create-analyzer
    {:tokenizer (su/create-regexp-tokenizer #"\W+")
     :token-filters [su/lower-case-token-filter
                     su/prefix-token-filter]}))

(def query-analyzer
  (su/create-analyzer
    {:tokenizer (su/create-regexp-tokenizer #"\W+")
     :token-filters [su/lower-case-token-filter]}))

(def engine
  (d/new-search-engine
    (d/open-kv "/tmp/search-autocomplete")
    {:analyzer       prefix-index-analyzer
     :query-analyzer query-analyzer}))

(d/add-doc engine 1 "search")
(d/search engine "sea")
;; => (1)
```

For fuzzy or substring-style matching, use ngrams. The example below indexes
3-character grams, so `"Datalevin"` contributes tokens such as `"dat"`, `"ata"`,
`"tal"`, and `"ale"`.

In practice, prefer ngrams of 3 characters or longer unless the domain is small
and tightly controlled. One- and two-character grams generate many short,
high-frequency tokens, which makes posting lists less selective and can grow the
full-text index quickly.

```clojure
(def trigram-analyzer
  (su/create-analyzer
    {:tokenizer (su/create-regexp-tokenizer #"\W+")
     :token-filters [su/lower-case-token-filter
                     (su/create-min-length-token-filter 3)
                     (su/create-ngram-token-filter 3)]}))

(def trigram-engine
  (d/new-search-engine
    (d/open-kv "/tmp/search-trigrams")
    {:analyzer       trigram-analyzer
     :query-analyzer trigram-analyzer}))

(d/add-doc trigram-engine 1 "Datalevin")
(d/add-doc trigram-engine 2 "Search")
(d/search trigram-engine "tale")
;; => (1)
```

---

## 6. Ranking: TF-IDF and T-Wand Algorithm

Full-text ranking uses a different kind of vector from Chapter 17's embedding
vectors. In the full-text **Vector Space Model**, a document is a sparse vector
whose coordinates are terms. A query is another sparse vector over the same term
space. Ranking asks: which document vectors are most similar to the query
vector?

Datalevin uses TF-IDF weighting described by Manning, Raghavan, and Schütze in
*Introduction to Information Retrieval* [1], with the `lnu.ltn` weighting
schema. The important ideas are:

- **Term frequency (TF)**: a term that appears more often in a document is more
  important to that document, with logarithmic damping so repetition does not
  dominate.
- **Inverse document frequency (IDF)**: a term that appears in fewer documents
  is more discriminating than a term that appears almost everywhere.
- **Normalization**: document length affects ranking, so Datalevin normalizes
  document vectors to avoid blindly favoring longer documents.

In `lnu.ltn` notation, the document side and query side use different
weighting:

- **Document vectors**: log-weighted term frequency, no idf, pivoted unique
  normalization
- **Query vectors**: log-weighted term frequency, idf weighting, no
  normalization

This weighting scheme handles document length well without penalizing longer
documents.

### 6.1 T-Wand Algorithm

Datalevin uses **Tiered WAND** (T-Wand), described in the T-Wand blog post [3].
To understand what T-Wand adds, first understand ordinary WAND [2].

WAND is a top-k retrieval algorithm. A query such as `"red fox database"` has
one posting iterator per query term. Each iterator walks the document references
that contain that term, and each term has an upper bound: the largest score that
term could possibly contribute to any document. During search, the engine keeps
a heap of the current best `k` documents. Once the heap is full, the lowest
score in that heap becomes the threshold a new document must beat.

At each step, WAND sorts the term iterators by their current document id. It
then adds the per-term upper bounds from left to right until the sum can beat
the current threshold. The iterator where that happens is the pivot. Any
document id before the pivot cannot possibly beat the threshold, even under the
most generous score estimate, so the earlier iterators can be advanced directly
to the pivot document id. If all iterators up to the pivot point at the same
document, the engine scores that document exactly and maybe updates the top-k
heap. Otherwise it keeps advancing. The important property is that the skipping
is exact, not approximate: skipped documents are skipped only when their maximum
possible score is already too low.

T-Wand keeps that WAND score pruning and adds a relevance constraint based on
term coverage. It divides matching documents into tiers:

1. First, documents containing all `n` query terms.
2. Then documents containing `n - 1` query terms.
3. Then documents containing `n - 2` query terms, and so on until enough
   results are found.

Within a tier, documents are still ranked by TF-IDF score. Across tiers, term
coverage wins: a document that contains all query terms is considered before
documents that contain only some of them. This addresses a common search
frustration where a high-scoring partial match outranks a complete match.

The tiering also gives the engine more ways to prune. Suppose a query has `n`
terms and the current tier requires at least `t` matching terms. Sort the query
terms from rarest to most common. Any document with `t` matches must contain at
least one of the rarest `n - t + 1` terms. If it contained none of those rare
terms, it could match at most the remaining `t - 1` terms. Therefore the engine
can use the union of those rare-term posting lists as the candidate set for the
tier, instead of considering every document that matches any query term.

There is a second tier-specific pruning rule while checking a candidate. If the
candidate has matched `h` terms so far and only `r` unchecked query terms remain,
then its best possible final coverage is `h + r`. If `h + r < t`, the candidate
can no longer reach the tier and can be discarded before exact scoring. Combined
with WAND's score upper bounds, this lets Datalevin avoid both low-coverage
candidates and high-coverage candidates that still cannot enter the current
top-k result set.

### 6.2 Term Proximity Scoring

When `:index-position? true` is enabled, a two-stage ranking applies:

1. TF-IDF scoring produces top `m * k` candidates.
2. Proximity scoring re-ranks those candidates and returns the top `k` results.

This reflects the intuition that query terms appearing closer together indicate
higher relevance. Here `k` is the requested result count, normally `:top`.
`m` is controlled by `:proximity-expansion`; raising it considers more
candidates before re-ranking, which can improve quality at higher cost.
`:proximity-max-dist` controls how far apart terms may be while still
contributing to a proximity span.

---

## 7. Standalone Search Engine

Datalevin can be used as a standalone search engine outside of Datalog from
embedded Clojure:

```clojure
(def lmdb (d/open-kv "/tmp/search-db"))
(def engine (d/new-search-engine lmdb {:index-position? true}))

(d/add-doc engine 1 "The quick red fox jumped over the lazy red dogs.")
(d/add-doc engine 2 "Mary had a little lamb whose fleece was red as fire.")

(d/search engine "red")
;=> (1 2)

(d/search engine "red" {:display :offsets})
;=> ([1 (["red" [10 39]])] [2 (["red" [40]])])
```

Standalone search engines follow the same rule: by default they store the
`doc-ref` and the inverted index, assuming the application can recover the
source document from `doc-ref`. Enable `:include-text? true` when the search
engine should keep raw text for `:display :texts`, `:display :texts+offsets`, or
`re-index`:

```clojure
(def engine
  (d/new-search-engine
    lmdb
    {:include-text? true
     :index-position? true}))
```

**Document References**: Full-text search returns document references by
default. In Datalog's `fulltext`, the document reference is the datom `[e a v]`.
In standalone mode, the `doc-ref` can be anything—numbers, strings,
maps—whatever uniquely identifies a document in your application. This
flexibility lets you index external content without storing it in the search
index.

### 7.1 Document Lifecycle and Bulk Loading

The standalone search engine exposes the normal lifecycle operations you expect
from a search index:

```clojure
(d/doc-indexed? engine 1)
;=> true

(d/doc-count engine)
;=> 2

(d/remove-doc engine 2)

(d/clear-docs engine)
```

`add-doc` checks for an existing `doc-ref` by default and updates the index when
the document is already present. During a trusted initial import, pass `false`
as the fourth argument to skip that existence check:

```clojure
(d/add-doc engine doc-ref doc-text false)
```

For larger embedded imports, use `search-index-writer`, `write`, and `commit`.
The Java, Python, and JavaScript bindings expose the same standalone search
writer as `SearchIndexWriter` / `search_index_writer` / `searchIndexWriter`.
The writer batches index changes and flushes final term metadata on `commit`,
so call `commit` before relying on search results:

<!-- pdf-listing: Bulk loading a standalone full-text search index -->

```clojure
(def lmdb (d/open-kv "/tmp/search-db"))
(def writer
  (d/search-index-writer
    lmdb
    {:domain "docs"
     :index-position? true
     :include-text? true}))

(doseq [[doc-ref doc-text] docs]
  (d/write writer doc-ref doc-text))

(d/commit writer)

(def engine
  (d/new-search-engine
    lmdb
    {:domain "docs"
     :index-position? true
     :include-text? true}))
```

The bulk writer is an embedded local API. It is not available through the
Datalevin client/server API; use Datalog full-text attributes and normal
transactions there, or run the writer in a local indexing process.

---

## 8. Implementation Details

The search indices are stored in LMDB sub-databases:

- `terms` — map of term → term-info (id, max-weight, doc-frequency list)
- `docs` — map of document id → doc reference and norm
- `positions` — inverted lists for proximity (optional)
- `rawtext` — original document text (optional, only when `:include-text? true`)

An **inverted index** maps from a term to the documents that contain it. A
**document frequency list** is the posting list for one term: the set of
documents where the term appears, plus enough statistics to rank them. A
**norm** is a precomputed document-length normalization factor used during
ranking. Datalevin loads norms into memory on initialization and loads term
information as queries need it. Document frequency lists use compressed bitmaps
(Roaring Bitmaps) for efficient intersection and union operations.

---

## 9. Asynchronous Indexing

Full-text indexing is synchronous by default: a transaction updates source
datoms and the full-text index before returning. This preserves read-your-writes
behavior for `fulltext`.

For high-ingestion workloads, a search domain can opt into async indexing:

```clojure
(def conn
  (d/create-conn
    "/tmp/search-db"
    {:post/content {:db/valueType :db.type/string
                    :db/fulltext  true}}
    {:search-opts {:indexing-mode :async}}))
```

or per domain, when the attribute is assigned to that domain:

```clojure
(def conn
  (d/create-conn
    "/tmp/search-db"
    {:post/content {:db/valueType :db.type/string
                    :db/fulltext  true
                    :db.fulltext/domains ["content"]}}
    {:search-domains
     {"content" {:index-position? true
                 :indexing-mode   :async}}}))
```

In async mode, Datalevin commits the source datoms and a durable secondary-index
job atomically. An in-process worker applies the search index update after
commit and after DB-open recovery. Queries over that index are eventually
consistent until the worker catches up. Use `secondary-index-status` or
`process-secondary-index-jobs!` to observe or manually process pending work, and
use `wait-for-secondary-index` when an application needs to wait for a specific
transaction's index work.

---

## Summary

Full-text search in Datalevin is a first-class capability: tightly integrated,
high-performance, and synchronous by default. Enable `:db/fulltext`, use the
`fulltext` function with Clojure-style boolean expressions, and leverage search
domains for organized indexing, without operating a separate search cluster.

---

## References

[1] Christopher D. Manning, Prabhakar Raghavan, and Hinrich Schütze,
   *Introduction to Information Retrieval*, Cambridge University Press, 2008,
   Chapter 6: [Scoring, term weighting and the vector space model](https://nlp.stanford.edu/IR-book/html/htmledition/scoring-term-weighting-and-the-vector-space-model-1.html),
   especially Section 6.3,
   [The vector space model for scoring](https://nlp.stanford.edu/IR-book/html/htmledition/the-vector-space-model-for-scoring-1.html).

[2] Andrei Z. Broder, David Carmel, Michael Herscovici, Aya Soffer, and Jason
   Zien, "Efficient Query Evaluation Using a Two-Level Retrieval Process,"
   *Proceedings of the Twelfth International Conference on Information and
   Knowledge Management* (CIKM '03), 2003,
   DOI: [10.1145/956863.956944](https://doi.org/10.1145/956863.956944).

[3] Huahai Yang, [T-Wand: Beat Lucene in Less Than 600 Lines of Code](https://yyhh.org/blog/2021/11/t-wand-beat-lucene-in-less-than-600-lines-of-code/),
   yyhh.org, November 5, 2021.
