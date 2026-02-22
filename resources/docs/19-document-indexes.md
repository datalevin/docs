---
title: "Automatic Document Indexes and Path Queries"
chapter: 19
part: "IV — Indexes as Capabilities"
---

# Chapter 19: Automatic Document Indexes and Path Queries

Datalevin includes a document database feature called **idoc (Indexed
Document)**. This allows you to store nested maps as single values while
maintaining path-level indexing for fast queries. Idoc is orthogonal to
full-text and vector indices, so you can index the same document multiple ways and combine them in a single Datalog query.

---

## 1. Defining idoc Attributes

Declare idoc attributes with `:db/valueType :db.type/idoc`:

```clojure
(def schema
  {:doc/edn  {:db/valueType  :db.type/idoc
              :db/domain     "profiles"}
   :doc/json {:db/valueType  :db.type/idoc
              :db/idocFormat :json}
   :doc/md   {:db/valueType  :db.type/idoc
              :db/idocFormat :markdown}})
```

- **`:db/idocFormat`** — `:edn` (default), `:json`, or `:markdown`
- **`:db/domain`** — Optional domain string for scoped search. Defaults to attribute name (namespace becomes underscore: `:doc/edn` → `"doc_edn"`)

---

## 2. Transacting Documents

Idoc values must be maps with keyword or string keys. Vectors are allowed as arrays:

```clojure
(d/transact! conn
  [{:db/id   1
    :doc/edn {:status  "active"
              :profile {:age 30 :name "Alice"}
              :tags    ["a" "b" "c"]}
    :doc/json "{\"name\":\"Alice\",\"age\":30}"
    :doc/md   "# User Profile\n## Getting Started\nName: Alice"}])
```

**Document rules:**
- Top-level must be a map
- Lists are not allowed—use vectors
- `nil` values are normalized to `:json/null`
- `:json/null` is reserved and rejected on input

---

## 3. Patching Documents

Use `:db.fn/patchIdoc` for partial updates without rewriting the full document:

```clojure
(d/transact! conn
  [[:db.fn/patchIdoc 1 :doc/edn
    [[:set    [:profile :age] 31]
     [:unset  [:profile :middle]]
     [:update [:tags] :conj "d"]]]])
```

Patch operations:
- `:set` — Set value at path
- `:unset` — Remove map key or vector element
- `:update` — Apply `:conj` (vector), `:merge`/`:assoc`/`:dissoc` (map), `:inc`/`:dec` (number)

Paths are vectors of keys and indices. For cardinality-many attributes, provide the old value to identify which document to patch.

---

## 4. Querying with `idoc-match`

`idoc-match` returns matching datoms as `[e a v]` triples:

```clojure
(d/q '[:find ?e ?a ?v
       :in $ ?q
       :where [(idoc-match $ :doc/edn ?q) [[?e ?a ?v]]]]
     db
     {:status "active" :profile {:age 30}})
```

### 4.1 Nested Maps and Arrays

Vectors are treated as arrays—a match succeeds if **any** element matches:

```clojure
(d/q '[:find ?e
       :in $
       :where [(idoc-match $ :doc/edn {:tags "b"}) [[?e ?a ?v]]]]
     db)
```

### 4.2 Logical Combinators

Use `[:and ...]`, `[:or ...]`, `[:not ...]` inside query maps:

```clojure
(d/q '[:find ?e
       :in $
       :where [(idoc-match $ :doc/edn
                            {:profile [:or {:age 30} {:age 40}]})
               [[?e ?a ?v]]]]
     db)
```

### 4.3 Predicates

Predicates can be inline or path-based:

```clojure
;; inline predicate
(d/q '[:find ?e
       :in $
       :where [(idoc-match $ :doc/edn {:age (> 21)}) [[?e ?a ?v]]]]
     db)

;; path predicate (quoted)
(d/q '[:find ?e
       :in $ ?q
       :where [(idoc-match $ :doc/edn ?q) [[?e ?a ?v]]]]
     db
     '(>= [:profile :age] 30))

;; range via multi-arity comparison
(d/q '[:find ?e
       :in $ ?q
       :where [(idoc-match $ :doc/edn ?q) [[?e ?a ?v]]]]
     db
     '(< 20 [:profile :age] 40))
```

Supported predicates: `nil?`, `>`, `>=`, `<`, `<=`

### 4.4 Wildcard Paths

- `:?` matches exactly one path segment
- `:*` matches any depth (zero or more segments)

```clojure
;; any single key under :profile with value >= 30
'(>= [:profile :?] 30)

;; match at any depth
{:* {:product "B"}}
```

### 4.5 Null Matching

To match null values, use `(nil?)`:

```clojure
(d/q '[:find ?e
       :in $
       :where [(idoc-match $ :doc/json {"middle" (nil?)}) [[?e ?a ?v]]]]
     db)
```

Note: JSON keys are strings—match with `"middle"`, not `:middle`.

---

## 5. Extracting Values with `idoc-get`

`idoc-get` extracts a value by path from an idoc document:

```clojure
(def doc (:doc/edn (d/entity db 1)))
(idoc-get doc :profile :age)     ;; => 30
(idoc-get doc :tags)             ;; => ["a" "b" "c"]
```

Returns a vector when the path traverses arrays.

---

## 6. Implementation

- Documents are stored in datom values; indices store references
- Each domain has: doc-ref map, path dictionary, inverted index
- Indexing is synchronous during transactions
- Path ids are 32-bit integers; ~2.1 billion docs per domain
- Markdown is parsed into nested maps with normalized header keys

---

## Summary

Idoc provides flexible document modeling with path-level indexing. Use it for
evolving schemas, sparse metadata, or migrating from NoSQL, all while integrating with Datalog queries, full-text search, and vector similarity.
