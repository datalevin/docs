---
title: "Full-Text Search: Analyzers, Vector Space Model and Boolean Search Expression"
chapter: 17
part: "IV — Indexes as Capabilities"
---

# Chapter 17: Full-Text Search: Analyzers, Vector Space Model and Boolean Search Expression

Most databases require a separate engine like Elasticsearch or Solr for full-text search. Datalevin includes a high-performance, integrated search engine built directly on top of its KV substrate. Your search index is always transactionally consistent with your data, stored in the same LMDB data file.

This chapter explains how to enable full-text search, craft powerful search queries using boolean logic, and understand the underlying ranking algorithm.

---

## 1. Enabling Full-Text Search

To search for text within an attribute, declare `:db/fulltext true` in your schema:

```clojure
(def schema
  {:post/title   {:db/valueType :db.type/string
                  :db/fulltext  true
                  :db.fulltext/autoDomain true}
   :post/content {:db/valueType :db.type/string
                  :db/fulltext  true}})
```

Attributes with `:db.fulltext/autoDomain true` become their own search domain (named after the attribute). By default, all full-text attributes participate in the `datalevin` domain.

---

## 2. Querying with `fulltext`

The `fulltext` function returns matching datoms as `[e a v]` triples, ordered by relevance:

```clojure
(d/q '[:find ?e ?a ?v
       :in $ ?q
       :where [(fulltext $ ?q) [[?e ?a ?v]]]]
     db
     "red fox")
```

### 2.1 Attribute-Specific Search

To search within a specific attribute:

```clojure
(d/q '[:find ?e ?a ?v
       :in $ ?q
       :where [(fulltext $ :post/title ?q) [[?e ?a ?v]]]]
     db
     "clojure")
```

### 2.2 Search Options

Pass an option map as the last argument:

```clojure
(d/q '[:find ?e ?a ?v
       :in $ ?q
       :where [(fulltext $ :post/content ?q {:top 5}) [[?e ?a ?v]]]]
     db
     "database")
```

Available options:
- `:top` — number of results (default 10)
- `:display` — `:refs` (default), `:texts`, `:offsets`, or `:texts+offsets`
- `:domains` — list of search domains to query
- `:doc-filter` — predicate function to filter results

---

## 3. Search Expressions and Boolean Logic

Datalevin uses Clojure data structures for search expressions, enabling arbitrary boolean combinations:

```clojure
[:and "clojure" "database"]              ; both terms
[:or "fox" "red"]                        ; either term
[:not "java"]                            ; exclude term
[:and "clojure" [:not "java"]]           ; clojure but not java
```

### 3.1 Phrase Search

Phrases are encoded as maps. Requires `:index-position? true` at index time:

```clojure
[:and {:phrase "little lamb"} "fleece"]
```

### 3.2 Complex Expressions

Boolean operators can be arbitrarily nested:

```clojure
[:or "fox" "red" [:and "black" "sheep" [:not "yellow"]]]
```

---

## 4. Analyzers: Tokenization and Normalization

When you transact a string into a full-text attribute, it passes through an **Analyzer**:

1. **Tokenization**: Breaking text into terms
2. **Normalization**: Lowercasing, punctuation handling
3. **Stop-word removal**: Filtering common words (optional)

Custom analyzers can be provided via the `:analyzer` option when creating a search engine. Utility functions for stemming, ngrams, and more are in `datalevin.search-utils`.

---

## 5. Ranking: TF-IDF and T-Wand Algorithm

Datalevin uses the **Vector Space Model** with `lnu.ltn` weighting:
- **Document vectors**: log-weighted term frequency, no idf, pivoted unique normalization
- **Query vectors**: log-weighted term frequency, idf weighting, no normalization

This weighting scheme handles document length well without penalizing longer documents.

### 5.1 T-Wand Algorithm

The search uses a **Tiered WAND** algorithm that processes documents in tiers by term coverage:
1. First, documents containing *all* query terms
2. Then documents with *n-1* terms
3. Continue until enough results are found

This ensures documents with better term coverage rank higher, addressing a common frustration with search engines where partial matches outrank complete matches.

### 5.2 Term Proximity Scoring

When `:index-position? true` is enabled, a two-stage ranking applies:
1. TF-IDF scoring produces top `m * k` candidates
2. Proximity scoring re-ranks the top `k` results

This reflects the intuition that query terms appearing closer together indicate higher relevance.

---

## 6. Standalone Search Engine

Datalevin can be used as a standalone search engine outside of Datalog:

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

**Document References**: In Datalog's `fulltext`, results are datoms `[e a v]`. In standalone mode, the "doc-ref" can be anything—numbers, strings, maps—whatever uniquely identifies a document in your application. This flexibility lets you index external content without storing it in the database.

---

## 7. Implementation Details

The search indices are stored in LMDB sub-databases:
- `terms` — map of term → term-info (id, max-weight, doc-frequency list)
- `docs` — map of document id → doc reference and norm
- `positions` — inverted lists for proximity (optional)

Pre-computed norms load into memory on initialization. Term information loads per query. Document frequency lists use compressed bitmaps (Roaring Bitmaps) for efficient intersection/union operations.

---

## Summary

Full-text search in Datalevin is a first-class capability: transactionally consistent, tightly integrated, and high-performance. Enable `:db/fulltext`, use the `fulltext` function with Clojure-style boolean expressions, and leverage search domains for organized indexing—all without operating a separate search cluster.
