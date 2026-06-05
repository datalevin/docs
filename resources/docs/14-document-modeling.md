---
title: "Document Modeling and Path-Based Access"
chapter: 14
part: "III — Modeling Across Paradigms"
---

# Chapter 14: Document Modeling and Path-Based Access

Datalevin gives you two ways to model document-shaped data:
**logical documents**, built from normal datoms and component references, and
**indexed documents** (`idoc`), stored as single values with automatic
path-level indexes.

Use logical documents when the nested structure is part of your core domain
model. Use `idoc` when the nested structure is flexible metadata, imported JSON,
Markdown-derived structure, or application-defined data that should be searched
by path without forcing every field into the schema.

---

## 1. Logical Documents with `:db/isComponent`

The most common way to model durable domain documents in Datalevin is to use
component attributes. A component reference stores a nested entity as ordinary
datoms, but treats that entity as owned by its parent.

### Example: A Nested Blog Post
<div class="multi-lang">

```clojure
;; Schema: :post/comments is a component ref
{:post/comments {:db/valueType   :db.type/ref
                 :db/cardinality :db.cardinality/many
                 :db/isComponent true}}
```

```java
// Schema: post/comments is a component ref
Map<String, Object> schema = Map.of(
    "post/comments", Map.of(
        "db/valueType", "db.type/ref",
        "db/cardinality", "db.cardinality/many",
        "db/isComponent", true
    )
);
```

```python
# Schema: post/comments is a component ref
schema = {
    "post/comments": {
        "db/valueType": "db.type/ref",
        "db/cardinality": "db.cardinality/many",
        "db/isComponent": True
    }
}
```

```javascript
// Schema: post/comments is a component ref
const schema = {
  'post/comments': {
    'db/valueType': 'db.type/ref',
    'db/cardinality': 'db.cardinality/many',
    'db/isComponent': true
  }
};
```

</div>

Logical documents are still normal facts. Every nested field can be typed,
indexed, joined, pulled, and updated independently. This is useful when nested
data has identity, relationships, constraints, or a lifecycle that should remain
visible to Datalog.

For example, comments can be modeled as components of a post while their authors
remain separate top-level entities:

```clojure
(d/transact! conn
  [{:db/id 1
    :post/title "Indexes as Capabilities"
    :post/comments [{:comment/body "This clarifies the mental model."
                     :comment/author [:user/email "ada@example.com"]}]}])
```

For triple-based documents, use pull patterns to navigate nested paths:

```clojure
(d/pull db
        [:post/title {:post/comments [:comment/body {:comment/author [:user/name]}]}]
        1)
```

---

## 2. Native Indexed Documents with `idoc`

An `idoc` stores a nested document as one datom value, while Datalevin maintains
an index for paths inside the document. This gives you document-database style
path queries without giving up Datalog joins or transactions.

Idoc indexes are orthogonal to full-text and vector indexes. The same entity can
have structured facts, full-text fields, embeddings, and idoc metadata, and a
single Datalog query can combine all of those constraints.

### 2.1 Defining an `idoc` Attribute

Declare an idoc attribute with `:db/valueType :db.type/idoc`. The optional
`:db/idocFormat` controls how payloads are interpreted, and the optional
`:db/domain` gives the backing index a stable domain name.

<div class="multi-lang">

```clojure
(def schema
  {:user/metadata {:db/valueType :db.type/idoc
                   :db/domain    "user_metadata"}
   :user/raw-json {:db/valueType  :db.type/idoc
                   :db/idocFormat :json}})
```

```java
Map<String, Object> schema = Map.of(
    "user/metadata", Map.of(
        "db/valueType", "db.type/idoc",
        "db/domain", "user_metadata"
    ),
    "user/raw-json", Map.of(
        "db/valueType", "db.type/idoc",
        "db/idocFormat", "json"
    )
);
```

```python
schema = {
    "user/metadata": {
        "db/valueType": "db.type/idoc",
        "db/domain": "user_metadata"
    },
    "user/raw-json": {
        "db/valueType": "db.type/idoc",
        "db/idocFormat": "json"
    }
}
```

```javascript
const schema = {
  'user/metadata': {
    'db/valueType': 'db.type/idoc',
    'db/domain': 'user_metadata'
  },
  'user/raw-json': {
    'db/valueType': 'db.type/idoc',
    'db/idocFormat': 'json'
  }
};
```

</div>

Supported `:db/idocFormat` values are `:edn` (the default), `:json`, and
`:markdown`. If `:db/domain` is omitted, Datalevin derives a domain from the
attribute name, for example `:user/metadata` becomes `"user_metadata"`.

### 2.2 Transacting Documents

Idoc values must be document-like maps. Nested vectors are treated as arrays.

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id 101
    :user/metadata {:theme "dark"
                    :profile {:age 30 :name "Alice"}
                    :tags ["beta" "admin"]}}])
```

```java
Datalevin.transact(conn, List.of(
    Map.of("db/id", 101,
           "user/metadata", Map.of(
               "theme", "dark",
               "profile", Map.of("age", 30, "name", "Alice"),
               "tags", List.of("beta", "admin")))
));
```

```python
d.transact(conn, [
    {"db/id": 101,
     "user/metadata": {
         "theme": "dark",
         "profile": {"age": 30, "name": "Alice"},
         "tags": ["beta", "admin"]}}
])
```

```javascript
d.transact(conn, [
  { 'db/id': 101,
    'user/metadata': {
      theme: 'dark',
      profile: { age: 30, name: 'Alice' },
      tags: ['beta', 'admin']
    } }
]);
```

</div>

Document rules:

- The top-level value must be a map.
- Keys may be keywords or strings, depending on the format and client language.
- Lists are not allowed; use vectors for arrays.
- `nil` values are normalized to `:json/null`.
- `:json/null` is reserved and rejected on input.
- For `:edn` format, string payloads are read as EDN and must yield a map.
- JSON and Markdown attributes accept string payloads in those formats.

### 2.3 Patching Documents

Use `:db.fn/patchIdoc` for partial updates when you do not want to resend the
whole document.

<div class="multi-lang">

```clojure
(d/transact! conn
  [[:db.fn/patchIdoc 101 :user/metadata
    [[:set    [:theme] "light"]
     [:unset  [:profile :middle]]
     [:update [:tags] :conj "active"]]]])
```

```java
Datalevin.transact(conn, List.of(
    List.of("db.fn/patchIdoc", 101, "user/metadata",
        List.of(
            List.of("set", List.of("theme"), "light"),
            List.of("unset", List.of("profile", "middle")),
            List.of("update", List.of("tags"), "conj", "active")
        ))
));
```

```python
d.transact(conn, [
    ["db.fn/patchIdoc", 101, "user/metadata",
     [["set", ["theme"], "light"],
      ["unset", ["profile", "middle"]],
      ["update", ["tags"], "conj", "active"]]]
])
```

```javascript
d.transact(conn, [
  ['db.fn/patchIdoc', 101, 'user/metadata',
    [['set', ['theme'], 'light'],
     ['unset', ['profile', 'middle']],
     ['update', ['tags'], 'conj', 'active']]]
]);
```

</div>

Patch paths are vectors of map keys and vector indices. Supported operations are:

- `:set` sets the value at a path.
- `:unset` removes a map key or vector element.
- `:update` applies `:conj`, `:merge`, `:assoc`, `:dissoc`, `:inc`, or `:dec`.

For cardinality-many idoc attributes, provide the old document value so
Datalevin can identify which value should be patched.

---

## 3. Choosing Between Logical Documents and `idoc`

| Feature | Logical document (`:db/isComponent`) | Native `idoc` |
| :--- | :--- | :--- |
| **Storage** | Decomposed into many datoms | Stored as one datom value |
| **Schema** | Attributes are declared explicitly | Nested fields can evolve freely |
| **Indexing** | Standard EAV/AVE and attribute indexes | Automatic path-level indexing |
| **Querying** | Datalog joins, pull, entity API | `idoc-match` and `idoc-get` |
| **Updates** | Fine-grained datom updates | `patchIdoc` or full replacement |
| **Best for** | Core domain state and relationships | Flexible metadata and imported documents |

Use logical documents when nested data needs type checking, referential
integrity, joins to other entities, or independent updates as facts. Use `idoc`
when nested data is sparse, user-defined, imported from another document store,
or expected to change shape frequently.

These models can coexist on the same entity. A product can have structured facts
for price and inventory, component entities for variants, full-text fields for
descriptions, and an idoc attribute for merchant-specific metadata.

---

## 4. Querying Path-Indexed Documents

Use `idoc-match` inside Datalog to find entities with matching nested document
content. It returns matching datoms as `[e a v]` triples, so the result can be
joined with ordinary facts.

<div class="multi-lang">

```clojure
;; Find users with a dark theme in their metadata idoc.
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ _]]]]
     db)
```

```java
Datalevin.q("[:find ?e " +
            " :where [(idoc-match $ :user/metadata {:theme \"dark\"}) [[?e _ _]]]]",
            db);
```

```python
d.q('[:find ?e '
     ' :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ _]]]]',
     db)
```

```javascript
d.q('[:find ?e ' +
     ' :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ _]]]]',
     db);
```

</div>

Nested maps express nested path matches:

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata
                            {:profile {:age 30 :name "Alice"}})
               [[?e _ _]]]]
     db)
```

Vectors are treated as arrays. A match succeeds if any element matches:

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata {:tags "admin"}) [[?e _ _]]]]
     db)
```

### 4.1 Logical Combinators

Use `[:and ...]`, `[:or ...]`, and `[:not ...]` inside query maps:

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata
                            {:profile [:or {:age 30} {:age 40}]})
               [[?e _ _]]]]
     db)
```

### 4.2 Predicates and Ranges

Predicates can appear inline in query maps, or as path predicates supplied as
query inputs:

```clojure
;; Inline predicate.
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata {:profile {:age (> 21)}})
               [[?e _ _]]]]
     db)

;; Path predicate supplied as data.
(d/q '[:find ?e
       :in $ ?q
       :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]
     db
     '(>= [:profile :age] 30))

;; Range via multi-arity comparison.
(d/q '[:find ?e
       :in $ ?q
       :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]
     db
     '(< 20 [:profile :age] 40))
```

Supported predicates are `nil?`, `>`, `>=`, `<`, and `<=`.

### 4.3 Wildcard Paths

Use wildcard path segments when the exact nested key is not known:

```clojure
;; Match any single key under :profile with value >= 30.
'(>= [:profile :?] 30)

;; Match a product field at any depth.
{:* {:product "B"}}
```

`:?` matches exactly one path segment. `:*` matches any depth, including zero
segments.

### 4.4 Null Matching

To match null values, use `(nil?)`:

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/raw-json {"middle" (nil?)}) [[?e _ _]]]]
     db)
```

JSON keys are strings, so JSON documents should be matched with `"middle"`, not
`:middle`.

### 4.5 Extracting Values with `idoc-get`

`idoc-get` extracts a nested value from an idoc document by path:

<div class="multi-lang">

```clojure
(def doc (:user/metadata (d/entity db 101)))
(idoc-get doc :profile :age) ;; => 30
(idoc-get doc :tags)         ;; => ["beta" "admin"]
```

```java
Object doc = Datalevin.entity(db, 101).get("user/metadata");
Datalevin.idocGet(doc, "profile", "age"); // => 30
Datalevin.idocGet(doc, "tags");           // => ["beta", "admin"]
```

```python
doc = d.entity(db, 101)["user/metadata"]
d.idoc_get(doc, "profile", "age")  # => 30
d.idoc_get(doc, "tags")            # => ["beta", "admin"]
```

```javascript
const doc = d.entity(db, 101)['user/metadata'];
d.idocGet(doc, 'profile', 'age'); // => 30
d.idocGet(doc, 'tags');           // => ['beta', 'admin']
```

</div>

You can also bind the idoc value from `idoc-match` and extract a nested value in
the same query:

```clojure
(d/q '[:find ?e ?email
       :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ ?doc]]]
              [(idoc-get ?doc :contact :email) ?email]]
     db)
```

When a path traverses arrays, `idoc-get` returns a vector of matching values.

---

## 5. How `idoc` Indexes Work

Datalevin stores the idoc itself as the datom value. The path index stores
references into those datoms, so path queries can narrow candidates quickly
without duplicating full documents.

- Each idoc domain maintains a document-reference map, a path dictionary, and an
  inverted index.
- Indexing is synchronous during transactions, so committed idoc queries see the
  committed document state.
- Path ids are 32-bit integers.
- Markdown idocs are parsed into nested maps with normalized header keys.

This implementation detail matters for modeling: idoc gives you fast path
filters, but the document remains one value from the perspective of ordinary
datoms. If a nested field deserves identity, constraints, independent history, or
joins as a first-class fact, model that part with normal attributes instead.

---

## 6. Summary

Datalevin lets you choose the right representation for each part of your data.
Use logical documents for structured domain state where facts, relationships,
and constraints matter. Use `idoc` for flexible document-shaped data that should
be stored as one value but queried by path. Both models participate in Datalog,
so you can join document filters with graph, relational, full-text, and vector
queries in one transactionally consistent database.
