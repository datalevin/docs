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

The Java snippets in this chapter assume an open `Connection` named `conn`.
Python and JavaScript snippets assume open `conn` and `db` handles. When an
example passes a Datalog form as data, non-Clojure snippets use EDN helpers such
as `Datalevin.edn`, `interop().read_edn`, and `await interop().readEdn`.

---

## 1. Logical Documents with `:db/isComponent`

The most common way to model durable domain documents in Datalevin is to use
component attributes. A component reference stores a nested entity as ordinary
datoms, but treats that entity as owned by its parent.

### Example: A Nested Blog Post
<div class="multi-lang">

```clojure
;; Schema: :post/comments owns comment entities.
{:post/comments {:db/valueType   :db.type/ref
                 :db/cardinality :db.cardinality/many
                 :db/isComponent true}
 :comment/author {:db/valueType :db.type/ref}}
```

```java
Schema schema = Datalevin.schema()
    .attr("post/comments",
          Schema.attribute()
              .valueType(Schema.ValueType.REF)
              .cardinality(Schema.Cardinality.MANY)
              .isComponent(true))
    .attr("comment/author",
          Schema.attribute()
              .valueType(Schema.ValueType.REF));
```

```python
schema = {
    ":post/comments": {
        ":db/valueType": ":db.type/ref",
        ":db/cardinality": ":db.cardinality/many",
        ":db/isComponent": True
    },
    ":comment/author": {
        ":db/valueType": ":db.type/ref"
    }
}
```

```javascript
const schema = {
  ":post/comments": {
    ":db/valueType": ":db.type/ref",
    ":db/cardinality": ":db.cardinality/many",
    ":db/isComponent": true
  },
  ":comment/author": {
    ":db/valueType": ":db.type/ref"
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

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id 200
    :user/name "Ada"
    :user/email "ada@example.com"}
   {:db/id 1
    :post/title "Indexes as Capabilities"
    :post/comments [{:comment/body "This clarifies the mental model."
                     :comment/author 200}]}])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity(200)
        .put("user/name", "Ada")
        .put("user/email", "ada@example.com"))
    .entity(Tx.entity(1)
        .put("post/title", "Indexes as Capabilities")
        .put("post/comments", List.of(
            Tx.entity()
                .put("comment/body", "This clarifies the mental model.")
                .put("comment/author", 200)
                .build()))));
```

```python
conn.transact([
    {":db/id": 200,
     ":user/name": "Ada",
     ":user/email": "ada@example.com"},
    {":db/id": 1,
     ":post/title": "Indexes as Capabilities",
     ":post/comments": [
         {":comment/body": "This clarifies the mental model.",
          ":comment/author": 200}]}
])
```

```javascript
await conn.transact([
  {
    ":db/id": 200,
    ":user/name": "Ada",
    ":user/email": "ada@example.com"
  },
  {
    ":db/id": 1,
    ":post/title": "Indexes as Capabilities",
    ":post/comments": [
      {
        ":comment/body": "This clarifies the mental model.",
        ":comment/author": 200
      }
    ]
  }
]);
```

</div>

For triple-based documents, use pull patterns to navigate nested paths:

<div class="multi-lang">

```clojure
(d/pull db
        [:post/title {:post/comments [:comment/body {:comment/author [:user/name]}]}]
        1)
```

```java
conn.pull(
    "[:post/title {:post/comments [:comment/body {:comment/author [:user/name]}]}]",
    1);
```

```python
conn.pull(
    [":post/title", {":post/comments": [":comment/body",
                                        {":comment/author": [":user/name"]}]}],
    1)
```

```javascript
await conn.pull(
  [':post/title', { ':post/comments': [':comment/body',
                                       { ':comment/author': [':user/name'] }] }],
  1
);
```

</div>

### Aside: Ad Hoc Queries over Nested EDN

Sometimes you do not want to persist a nested value at all. You have a map from
an API response, a fixture, or a one-off data export, and you want Datalog's
joins and predicates for exploration. Chapter 8 showed that Datalevin can query
an in-memory sequence of tuples. A small boundary helper can turn a nested
EDN value into such a relation.

The following Clojure helper walks maps and vectors and emits one tuple for
each leaf value. By default, the tuple is the path segments followed by the
leaf value:

<!-- pdf-listing: Turning nested EDN leaves into Datalog tuples -->

```clojure
(defn leaf-paths
  "Returns paths to scalar leaves in a nested map/vector."
  ([root]
   {:pre [(or (map? root) (vector? root))]}
   (leaf-paths [] root))
  ([parent x]
   (cond
     (map? x)
     (mapcat (fn [[k v]]
               (leaf-paths (conj parent k) v))
             x)

     (vector? x)
     (mapcat (fn [[i v]]
               (leaf-paths (conj parent i) v))
             (map-indexed vector x))

     :else
     [parent])))

(defn nested->tuples
  "Converts nested maps/vectors into tuples queryable by Datalog."
  ([x]
   (nested->tuples x nil))
  ([x {:keys [paths?] :or {paths? false}}]
   (with-meta
     (mapv (fn [path]
             (let [tuple (conj path (get-in x path))]
               (if paths?
                 (into [path] tuple)
                 tuple)))
           (leaf-paths x))
     {:datalevin.docs/original x})))
```

For example:

```clojure
(def order-doc
  {:order/id "o-1001"
   :customer {:email "ada@example.com"
              :name  "Ada"}
   :lines    [{:sku "book" :qty 2}
              {:sku "pen"  :qty 5}]})

(def tuples (nested->tuples order-doc))
;; => [[:order/id "o-1001"]
;;     [:customer :email "ada@example.com"]
;;     [:customer :name "Ada"]
;;     [:lines 0 :sku "book"]
;;     [:lines 0 :qty 2]
;;     [:lines 1 :sku "pen"]
;;     [:lines 1 :qty 5]]
```

Now the vector index in the path can be used as a join key:

```clojure
(d/q '[:find ?sku ?qty
       :where
       [:lines ?i :sku ?sku]
       [:lines ?i :qty ?qty]]
     tuples)
;; => #{["book" 2] ["pen" 5]}
```

When `:paths?` is true, the full path is also carried as the first element of
each tuple. This is useful when a query result should drive a later `get-in`,
`assoc-in`, or `update-in`:

```clojure
(def tuples-with-paths (nested->tuples order-doc {:paths? true}))

(d/q '[:find ?path ?sku
       :where
       [?path :lines ?i :sku ?sku]]
     tuples-with-paths)
;; => #{[[:lines 0 :sku] "book"]
;;      [[:lines 1 :sku] "pen"]}
```

This technique is for ad hoc analysis and local transformation. It does not
create attributes, schema, indexes, lookup refs, or durable entities. If the
nested data is part of the application's long-lived domain model, turn it into
normal Datalevin facts or component entities. If it is flexible document data
that should be stored and searched by path, use `:db.type/idoc`.

---

## 2. Native Indexed Documents with `idoc`

An `idoc` stores a nested document as one datom value, while Datalevin maintains
an index for paths inside the document. This gives you document-database style
path queries without giving up Datalog joins or transactions.

![Indexed documents: a nested idoc value is flattened to a path-to-value index whose leaf paths are queried with idoc-match and idoc-get](/images/diagrams/idoc-path-index.svg)

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
                   :db/idocFormat :json}
   :doc/title     {:db/valueType :db.type/string}
   :doc/markdown  {:db/valueType  :db.type/idoc
                   :db/idocFormat :markdown}})
```

```java
Schema schema = Datalevin.schema()
    .attr("user/metadata",
          Schema.attribute()
              .valueType(Schema.ValueType.IDOC)
              .prop("db/domain", "user_metadata"))
    .attr("user/raw-json",
          Schema.attribute()
              .valueType(Schema.ValueType.IDOC)
              .prop("db/idocFormat", Datalevin.kw("json")))
    .attr("doc/title",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING))
    .attr("doc/markdown",
          Schema.attribute()
              .valueType(Schema.ValueType.IDOC)
              .prop("db/idocFormat", Datalevin.kw("markdown")));
```

```python
schema = {
    ":user/metadata": {
        ":db/valueType": ":db.type/idoc",
        ":db/domain": "user_metadata"
    },
    ":user/raw-json": {
        ":db/valueType": ":db.type/idoc",
        ":db/idocFormat": ":json"
    },
    ":doc/title": {
        ":db/valueType": ":db.type/string"
    },
    ":doc/markdown": {
        ":db/valueType": ":db.type/idoc",
        ":db/idocFormat": ":markdown"
    }
}
```

```javascript
const schema = {
  ":user/metadata": {
    ":db/valueType": ":db.type/idoc",
    ":db/domain": "user_metadata"
  },
  ":user/raw-json": {
    ":db/valueType": ":db.type/idoc",
    ":db/idocFormat": ":json"
  },
  ":doc/title": {
    ":db/valueType": ":db.type/string"
  },
  ":doc/markdown": {
    ":db/valueType": ":db.type/idoc",
    ":db/idocFormat": ":markdown"
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
                    :tags ["beta" "admin"]
                    :contact {:email "alice@example.com"}}
    :user/raw-json "{\"middle\":null,\"theme\":\"dark\"}"}])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity(101)
        .put("user/metadata", Map.of(
            "theme", "dark",
            "profile", Map.of("age", 30, "name", "Alice"),
            "tags", List.of("beta", "admin"),
            "contact", Map.of("email", "alice@example.com")))
        .put("user/raw-json", "{\"middle\":null,\"theme\":\"dark\"}")));
```

```python
conn.transact([
    {":db/id": 101,
     ":user/metadata": {
         "theme": "dark",
         "profile": {"age": 30, "name": "Alice"},
         "tags": ["beta", "admin"],
         "contact": {"email": "alice@example.com"}},
     ":user/raw-json": '{"middle":null,"theme":"dark"}'}
])
```

```javascript
await conn.transact([
  { ":db/id": 101,
    ":user/metadata": {
      theme: "dark",
      profile: { age: 30, name: "Alice" },
      tags: ["beta", "admin"],
      contact: { email: "alice@example.com" }
    },
    ":user/raw-json": "{\"middle\":null,\"theme\":\"dark\"}" }
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

### 2.3 Markdown Documents

Markdown idocs are useful when the source data is authored as prose but you
still want to query by section. Datalevin parses Markdown headings into nested
map paths and stores the section text as values.

```clojure
(d/transact! conn
  [{:db/id 201
    :doc/title "Search Guide"
    :doc/markdown
    "# Guide

## Install

Run `clj -M:dev`.

## Configure Search

Set `:index-position? true` for phrase search."}])
```

The parsed idoc value is equivalent to:

```clojure
{:guide {:install "Run clj -M:dev."
         :configure-search "Set :index-position? true for phrase search."}}
```

Heading text is normalized to keyword-like paths: case is folded, leading
numbering is removed, punctuation is dropped, and spaces become hyphens. Inline
Markdown markup is stripped from the stored text. Content before the first
heading is rejected, because Datalevin needs a heading path for the text.

You can match a section and extract its text in the same query:

```clojure
(d/q '[:find ?title ?install
       :where
       [?e :doc/title ?title]
       [(idoc-match $ :doc/markdown
                    {:guide {:install "Run clj -M:dev."}})
        [[?e _ ?doc]]]
       [(idoc-get ?doc :guide :install) ?install]]
     db)
```

For Markdown idocs, query paths are normalized too. This means string paths
that look like headings work:

```clojure
(d/q '[:find ?e
       :where
       [(idoc-match $ :doc/markdown
                    {"Guide" {"Configure Search"
                              "Set :index-position? true for phrase search."}})
        [[?e _ _]]]]
     db)
```

Use Markdown idocs for section-level lookup over authored documents. If a
section needs independent identity, permissions, relationships, or versioning,
model it as normal component entities instead.

### 2.4 Patching Documents

Use `:db.fn/patchIdoc` for partial updates when you do not want to resend the
whole document.

<div class="multi-lang">

```clojure
(d/transact! conn
  [[:db.fn/patchIdoc 101 :user/metadata
    [[:set    [:profile :display-name] "Alice A."]
     [:unset  [:profile :middle]]
     [:update [:tags] :conj "active"]]]])
```

```java
conn.transact(Datalevin.tx()
    .raw(Datalevin.edn(
        "[:db.fn/patchIdoc 101 :user/metadata " +
        " [[:set [:profile :display-name] \"Alice A.\"] " +
        "  [:unset [:profile :middle]] " +
        "  [:update [:tags] :conj \"active\"]]]")));
```

```python
conn.transact([
    [":db.fn/patchIdoc", 101, ":user/metadata",
     [[":set", [":profile", ":display-name"], "Alice A."],
      [":unset", [":profile", ":middle"]],
      [":update", [":tags"], ":conj", "active"]]]
])
```

```javascript
await conn.transact([
  [":db.fn/patchIdoc", 101, ":user/metadata",
    [[":set", [":profile", ":display-name"], "Alice A."],
     [":unset", [":profile", ":middle"]],
     [":update", [":tags"], ":conj", "active"]]]
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

![Logical documents vs. idoc, modeling the same nested user data: the logical document (:db/isComponent) decomposes it into a user entity that owns separate profile and contact component entities plus tag datoms — many facts that join, pull, and update independently; the native idoc (:db.type/idoc) stores the whole nested map as one datom value on :user/metadata with an automatic path index over leaf paths like profile.name, profile.age, contact.email, and tags. The two models can coexist on the same entity](/images/diagrams/logical-vs-idoc.svg)

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
conn.query("[:find ?e " +
           " :where [(idoc-match $ :user/metadata {:theme \"dark\"}) [[?e _ _]]]]");
```

```python
conn.query('[:find ?e '
           ' :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ _]]]]')
```

```javascript
await conn.query('[:find ?e ' +
                 ' :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ _]]]]');
```

</div>

Nested maps express nested path matches:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata
                            {:profile {:age 30 :name "Alice"}})
               [[?e _ _]]]]
     db)
```

```java
conn.query("[:find ?e " +
           " :where [(idoc-match $ :user/metadata " +
           "          {:profile {:age 30 :name \"Alice\"}}) [[?e _ _]]]]");
```

```python
conn.query('[:find ?e '
           ' :where [(idoc-match $ :user/metadata '
           '          {:profile {:age 30 :name "Alice"}}) [[?e _ _]]]]')
```

```javascript
await conn.query('[:find ?e ' +
                 ' :where [(idoc-match $ :user/metadata ' +
                 '          {:profile {:age 30 :name "Alice"}}) [[?e _ _]]]]');
```

</div>

Vectors are treated as arrays. A match succeeds if any element matches:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata {:tags "admin"}) [[?e _ _]]]]
     db)
```

```java
conn.query("[:find ?e " +
           " :where [(idoc-match $ :user/metadata {:tags \"admin\"}) [[?e _ _]]]]");
```

```python
conn.query('[:find ?e '
           ' :where [(idoc-match $ :user/metadata {:tags "admin"}) [[?e _ _]]]]')
```

```javascript
await conn.query('[:find ?e ' +
                 ' :where [(idoc-match $ :user/metadata {:tags "admin"}) [[?e _ _]]]]');
```

</div>

### 4.1 Logical Combinators

Use `[:and ...]`, `[:or ...]`, and `[:not ...]` inside query maps:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata
                            {:profile [:or {:age 30} {:age 40}]})
               [[?e _ _]]]]
     db)
```

```java
conn.query("[:find ?e " +
           " :where [(idoc-match $ :user/metadata " +
           "          {:profile [:or {:age 30} {:age 40}]}) " +
           "         [[?e _ _]]]]");
```

```python
conn.query('[:find ?e '
           ' :where [(idoc-match $ :user/metadata '
           '          {:profile [:or {:age 30} {:age 40}]}) '
           '         [[?e _ _]]]]')
```

```javascript
await conn.query('[:find ?e ' +
                 ' :where [(idoc-match $ :user/metadata ' +
                 '          {:profile [:or {:age 30} {:age 40}]}) ' +
                 '         [[?e _ _]]]]');
```

</div>

### 4.2 Predicates and Ranges

Predicates can appear inline in query maps, or as path predicates supplied as
query inputs:

<div class="multi-lang">

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

```java
// Inline predicate.
conn.query("[:find ?e " +
           " :where [(idoc-match $ :user/metadata " +
           "          {:profile {:age (> 21)}}) [[?e _ _]]]]");

// Path predicate supplied as data.
conn.query("[:find ?e " +
           " :in $ ?q " +
           " :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]",
           Datalevin.edn("(>= [:profile :age] 30)"));

// Range via multi-arity comparison.
conn.query("[:find ?e " +
           " :in $ ?q " +
           " :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]",
           Datalevin.edn("(< 20 [:profile :age] 40)"));
```

```python
# Inline predicate.
conn.query('[:find ?e '
           ' :where [(idoc-match $ :user/metadata '
           '          {:profile {:age (> 21)}}) [[?e _ _]]]]')

# Path predicate supplied as data.
conn.query('[:find ?e '
           ' :in $ ?q '
           ' :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]',
           interop().read_edn("(>= [:profile :age] 30)"))

# Range via multi-arity comparison.
conn.query('[:find ?e '
           ' :in $ ?q '
           ' :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]',
           interop().read_edn("(< 20 [:profile :age] 40)"))
```

```javascript
// Inline predicate.
await conn.query('[:find ?e ' +
                 ' :where [(idoc-match $ :user/metadata ' +
                 '          {:profile {:age (> 21)}}) [[?e _ _]]]]');

// Path predicate supplied as data.
await conn.query('[:find ?e ' +
                 ' :in $ ?q ' +
                 ' :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]',
                 await interop().readEdn("(>= [:profile :age] 30)"));

// Range via multi-arity comparison.
await conn.query('[:find ?e ' +
                 ' :in $ ?q ' +
                 ' :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]',
                 await interop().readEdn("(< 20 [:profile :age] 40)"));
```

</div>

Supported predicates are `nil?`, `>`, `>=`, `<`, and `<=`.

### 4.3 Wildcard Paths

Use wildcard path segments when the exact nested key is not known:

<div class="multi-lang">

```clojure
;; Match any single key under :profile with value >= 30.
(d/q '[:find ?e
       :in $ ?q
       :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]
     db
     '(>= [:profile :?] 30))

;; Match a name field at any depth.
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata {:* {:name "Alice"}})
               [[?e _ _]]]]
     db)
```

```java
// Match any single key under profile with value >= 30.
conn.query("[:find ?e " +
           " :in $ ?q " +
           " :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]",
           Datalevin.edn("(>= [:profile :?] 30)"));

// Match a name field at any depth.
conn.query("[:find ?e " +
           " :where [(idoc-match $ :user/metadata {:* {:name \"Alice\"}}) " +
           "         [[?e _ _]]]]");
```

```python
# Match any single key under profile with value >= 30.
conn.query('[:find ?e '
           ' :in $ ?q '
           ' :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]',
           interop().read_edn("(>= [:profile :?] 30)"))

# Match a name field at any depth.
conn.query('[:find ?e '
           ' :where [(idoc-match $ :user/metadata {:* {:name "Alice"}}) '
           '         [[?e _ _]]]]')
```

```javascript
// Match any single key under profile with value >= 30.
await conn.query('[:find ?e ' +
                 ' :in $ ?q ' +
                 ' :where [(idoc-match $ :user/metadata ?q) [[?e _ _]]]]',
                 await interop().readEdn("(>= [:profile :?] 30)"));

// Match a name field at any depth.
await conn.query('[:find ?e ' +
                 ' :where [(idoc-match $ :user/metadata {:* {:name "Alice"}}) ' +
                 '         [[?e _ _]]]]');
```

</div>

`:?` matches exactly one path segment. `:*` matches any depth, including zero
segments.

### 4.4 Null Matching

To match null values, use `(nil?)`:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [(idoc-match $ :user/raw-json {"middle" (nil?)}) [[?e _ _]]]]
     db)
```

```java
conn.query("[:find ?e " +
           " :where [(idoc-match $ :user/raw-json {\"middle\" (nil?)}) " +
           "         [[?e _ _]]]]");
```

```python
conn.query('[:find ?e '
           ' :where [(idoc-match $ :user/raw-json {"middle" (nil?)}) '
           '         [[?e _ _]]]]')
```

```javascript
await conn.query('[:find ?e ' +
                 ' :where [(idoc-match $ :user/raw-json {"middle" (nil?)}) ' +
                 '         [[?e _ _]]]]');
```

</div>

JSON keys are strings, so JSON documents should be matched with `"middle"`, not
`:middle`.

### 4.5 Extracting Values with `idoc-get`

`idoc-get` extracts a nested value from an idoc document inside a Datalog query:

<div class="multi-lang">

```clojure
(d/q '[:find ?e ?email
       :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ ?doc]]]
              [(idoc-get ?doc :contact :email) ?email]]
     db)
```

```java
conn.query("[:find ?e ?email " +
           " :where [(idoc-match $ :user/metadata {:theme \"dark\"}) [[?e _ ?doc]]] " +
           "        [(idoc-get ?doc :contact :email) ?email]]");
```

```python
conn.query('[:find ?e ?email '
           ' :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ ?doc]]] '
           '        [(idoc-get ?doc :contact :email) ?email]]')
```

```javascript
await conn.query('[:find ?e ?email ' +
                 ' :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e _ ?doc]]] ' +
                 '        [(idoc-get ?doc :contact :email) ?email]]');
```

</div>

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

## Summary

Datalevin lets you choose the right representation for each part of your data.
Use logical documents for structured domain state where facts, relationships,
and constraints matter. Use `idoc` for flexible document-shaped data that should
be stored as one value but queried by path. Both models participate in Datalog,
so you can join document filters with graph, relational, full-text, and vector
queries in one transactionally consistent database.
