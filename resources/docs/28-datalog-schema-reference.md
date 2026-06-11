---
title: "Appendix A: Datalog Schema Reference"
chapter: 28
part: "VII — Appendices"
---

# Appendix A: Datalog Schema Reference

This appendix is a compact reference for Datalevin Datalog schema maps. For
design guidance, see Chapter 5 and Chapter 11. For specialized index behavior,
see the full-text, vector, embedding, and idoc chapters.

---

## 1. Schema Shape

A Datalevin Datalog schema is a map from attribute keywords to property maps:

```clojure
(def schema
  {:user/email {:db/valueType :db.type/string
                :db/unique    :db.unique/identity
                :db/doc       "Primary account email."}
   :user/roles {:db/valueType   :db.type/ref
                :db/cardinality :db.cardinality/many}
   :post/body  {:db/valueType            :db.type/string
                :db/fulltext             true
                :db.fulltext/autoDomain true}})
```

Pass the schema when opening or creating a connection:

```clojure
(def conn (d/get-conn "/data/app" schema))
```

Use `update-schema` to change schema:

```clojure
(d/schema conn)
(d/update-schema conn schema-update)
(d/update-schema conn schema-update #{:old/attr})
(d/update-schema conn nil nil {:old/name :new/name})
```

---

## 2. Defaults and Built-Ins

- Schema is optional. Undefined attributes are created on write and store values
  as generic EDN data.
- `:db/cardinality` defaults to `:db.cardinality/one`.
- Attribute names must be keywords.
- Built-in schema includes `:db/ident`, which is unique and keyword-valued. It
  also includes internal/system attributes such as `:db/created-at`,
  `:db/updated-at`, `:db/fn`, and `:db/udf`.
- Use the store option `{:closed-schema? true}` to reject transactions that
  mention attributes not already defined in the schema.

---

## 3. Attribute Properties

All the acceptable attributes properties are the following:

| Property | Values | Meaning |
| :--- | :--- | :--- |
| `:db/valueType` | See value type table below | Encoded value type. Required for refs, range-friendly ordering, tuples, vectors, idocs, and typed validation. |
| `:db/cardinality` | `:db.cardinality/one`, `:db.cardinality/many` | One value or a set of values per entity. Defaults to one. |
| `:db/unique` | `:db.unique/value`, `:db.unique/identity` | Enforces uniqueness. Identity also enables lookup refs and upsert. |
| `:db/isComponent` | `true`, `false` | Owned child relationship. Must be used with `:db/valueType :db.type/ref`. |
| `:db/doc` | string | Human-readable attribute documentation. |
| `:db/fulltext` | `true`, `false` | Maintains a full-text secondary index. Values are converted with `str` before indexing. |
| `:db.fulltext/domains` | sequence of strings | Adds a full-text attribute to explicit search domains. |
| `:db.fulltext/autoDomain` | `true`, `false` | Adds an attribute-specific full-text domain named by the attribute without the leading colon. |
| `:db/embedding` | `true`, `false` | Maintains an embedding index for string values. Requires `:db/valueType :db.type/string`. |
| `:db.embedding/domains` | non-empty sequence of non-blank strings | Adds an embedding attribute to explicit embedding domains. |
| `:db.embedding/autoDomain` | `true`, `false` | Adds an attribute-specific embedding domain using vector-domain naming. |
| `:db.vec/domains` | sequence of strings | Adds a `:db.type/vec` attribute to explicit vector domains. |
| `:db/idocFormat` | `:edn`, `:json`, `:markdown` | Format hint for `:db.type/idoc`; defaults to `:edn`. |
| `:db/tupleAttrs` | non-empty sequence of attribute keywords | Composite tuple derived from other attributes. |
| `:db/tupleType` | single non-tuple value type | Homogeneous tuple element type. |
| `:db/tupleTypes` | sequence of more than one non-tuple value type | Heterogeneous tuple element types. |

---

## 4. Value Types

| Value type | Expected values | Notes |
| :--- | :--- | :--- |
| `:db.type/keyword` | Clojure keywords | Common for enums and small symbolic values. |
| `:db.type/symbol` | Clojure symbols | Symbolic values. |
| `:db.type/string` | strings | Required for `:db/embedding`; typical for full-text. |
| `:db.type/boolean` | booleans | `true` or `false`. |
| `:db.type/long` | 64-bit integers | Use for integer range queries and timestamps stored as epoch values. |
| `:db.type/double` | double-precision numbers | Numeric range queries. |
| `:db.type/float` | single-precision numbers | Numeric range queries with float storage. |
| `:db.type/ref` | entity ids, idents, or lookup refs in transactions | Enables joins, reverse pull navigation, and component ownership. |
| `:db.type/bigint` | arbitrary precision integers | Larger integer values. |
| `:db.type/bigdec` | arbitrary precision decimals | Decimal values. |
| `:db.type/instant` | `java.util.Date` / instants | Time values. |
| `:db.type/uuid` | UUID values | Stable external ids. |
| `:db.type/bytes` | byte arrays | Binary values. |
| `:db.type/tuple` | vectors | Composite, homogeneous, or heterogeneous tuple values. |
| `:db.type/vec` | numeric vectors | Maintains vector similarity indexes. Configure dimensions and metric in store options. |
| `:db.type/idoc` | nested maps/vectors or supported document payloads | Stored as one datom and indexed by path. |

There is no `:db.type/uri`; store URI values as strings.

If `:db/valueType` is omitted, Datalevin stores values as serialized EDN data,
which is flexible but not as useful for ordered range access.

---

## 5. Identity and Uniqueness

`:db.unique/value` means no two entities may have the same value for that
attribute. Duplicates will be rejected with a transaction error.

`:db.unique/identity` enables lookup refs and upsert:

```clojure
{:user/email {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}}

(d/pull db '[*] [:user/email "a@example.com"])

(d/transact! conn
  [{:user/email "a@example.com"
    :user/name  "Ada"}])
```

If an entity with that email already exists, the map transaction updates that
entity instead of creating a duplicate.

`:db/ident` is the built-in identity attribute for globally named entities:

```clojure
(d/transact! conn [{:db/ident :order.status/shipped}])
```

---

## 6. References and Components

Reference attributes must declare `:db/valueType :db.type/ref`:

```clojure
{:order/customer {:db/valueType :db.type/ref}
 :order/items    {:db/valueType   :db.type/ref
                  :db/cardinality :db.cardinality/many
                  :db/isComponent true}}
```

Use `:db/isComponent true` only for owned children. Component entities are pulled recursively by wildcard pull and are retracted with the parent when using `:db/retractEntity`.

`:db/isComponent true` without `:db/valueType :db.type/ref` is invalid.

---

## 7. Tuples

Datalevin supports three tuple forms.

Composite tuple derived from other attributes:

```clojure
{:order/customer {:db/valueType :db.type/ref}
 :order/date     {:db/valueType :db.type/instant}
 :order/customer+date
 {:db/tupleAttrs [:order/customer :order/date]
  :db/unique     :db.unique/identity}}
```

Rules for `:db/tupleAttrs`:

- It must be a non-empty sequential collection.
- The tuple attribute must be cardinality one.
- It cannot depend on another tuple attribute.
- It cannot depend on a cardinality-many attribute.

Homogeneous tuple:

```clojure
{:point/xy {:db/valueType :db.type/tuple
            :db/tupleType :db.type/double}}
```

Heterogeneous tuple:

```clojure
{:artist/name+country {:db/valueType  :db.type/tuple
                       :db/tupleTypes [:db.type/string :db.type/ref]}}
```

Rules for typed tuples:

- `:db.type/tuple` must include one of `:db/tupleAttrs`, `:db/tupleType`, or `:db/tupleTypes`.
- `:db/tupleType` must be a single non-tuple value type.
- `:db/tupleTypes` must contain more than one non-tuple value type.

---

## 8. Full-Text Schema Keys

Use `:db/fulltext true` for attributes that should participate in full-text search:

```clojure
{:post/body {:db/valueType            :db.type/string
             :db/fulltext             true
             :db.fulltext/domains     ["posts"]
             :db.fulltext/autoDomain true}}
```

If no explicit domain is given, full-text attributes participate in the default
`"datalevin"` domain. `:db.fulltext/autoDomain true` also creates an
attribute-specific domain named by the attribute without the leading colon:
`:title` becomes `"title"` and `:post/body` becomes `"post/body"`. Unlike
vector and embedding domains, full-text auto domains keep `/` in namespaced
attribute names.

Store-level `:search-opts` and `:search-domains` configure search domain options such as `:indexing-mode :async`.

---

## 9. Vector and Embedding Schema Keys

Use `:db.type/vec` when your application supplies vectors:

```clojure
{:item/embedding {:db/valueType   :db.type/vec
                  :db.vec/domains ["items"]}}
```

By default, each vector attribute has an attribute-specific domain derived from
the attribute name with `/` replaced by `_`: `:embedding` becomes `"embedding"`
and `:item/embedding` becomes `"item_embedding"`. Configure dimensions, metric,
and indexing mode in `:vector-opts` or `:vector-domains`.

Use `:db/embedding true` when Datalevin should embed string datoms:

```clojure
{:doc/text {:db/valueType             :db.type/string
            :db/embedding             true
            :db.embedding/domains     ["docs"]
            :db.embedding/autoDomain true}}
```

Embedding rules:

- The attribute must have `:db/valueType :db.type/string`.
- `:db.embedding/domains` must be a non-empty sequence of non-blank strings.
- If no explicit domain is given, the attribute participates in the default `"datalevin"` embedding domain.
- `:db.embedding/autoDomain true` creates an attribute-specific embedding domain
  using the same slash-to-underscore naming rule as vector domains.
- Changing `:db/embedding`, `:db.embedding/domains`, or `:db.embedding/autoDomain` on a populated attribute is rejected; rebuild explicitly instead.

Store-level `:embedding-opts`, `:embedding-domains`, and `:embedding-providers` configure providers, dimensions, metric, and indexing mode.

---

## 10. Idoc Schema Keys

Use `:db.type/idoc` for nested document values that should be indexed by path:

```clojure
{:doc/json {:db/valueType  :db.type/idoc
            :db/idocFormat :json}
 :doc/edn  {:db/valueType :db.type/idoc}
 :doc/md   {:db/valueType  :db.type/idoc
            :db/idocFormat :markdown}}
```

Allowed `:db/idocFormat` values are `:edn`, `:json`, and `:markdown`. The default is `:edn`.

---

## 11. Schema Evolution

Use `d/update-schema` to add, change, delete, or rename schema attributes on an open connection:

```clojure
(d/update-schema conn
  {:user/age {:db/valueType :db.type/long}})

(d/update-schema conn
  {:new/name {:db/valueType :db.type/string}}
  #{:old/name})

(d/update-schema conn nil nil
  {:old/name :new/name})
```

Important mutation rules:

- Adding `:db/valueType` to a previously untyped populated attribute is allowed and migrates existing EDN values to the typed encoding when possible.
- Changing an existing typed `:db/valueType` is rejected when data exists for that attribute.
- Changing `:db/cardinality` from many to one is rejected when data exists for that attribute.
- Adding `:db/unique` to a populated attribute is allowed only if existing values do not violate uniqueness.
- Deleting a schema attribute is allowed only when no datoms are associated with it.
- Renaming changes the attribute identity in the schema and stored datoms.
- Embedding schema changes on populated attributes require an explicit rebuild.

---

## 12. Datomic and DataScript Differences

Datalevin's Datalog model is familiar to Datomic and DataScript users, but the schema mechanics differ:

- Schema is a map of maps, not a vector of transaction maps.
- Schema is passed to `get-conn`, `create-conn`, `empty-db`, or `init-db`, and later changed with `update-schema`.
- Undefined attributes are allowed unless `:closed-schema? true` is set.
- Unspecified value types are stored as EDN data.
- AVE indexing is automatic for every attribute; do not add `:db/index`.
- Use `:db/ident` for named enum/system entities.

---

## 13. Minimal Production Template

```clojure
(def schema
  {:user/id    {:db/valueType :db.type/uuid
                :db/unique    :db.unique/identity
                :db/doc       "Stable external user id."}
   :user/email {:db/valueType :db.type/string
                :db/unique    :db.unique/identity
                :db/doc       "Login and notification email."}
   :user/name  {:db/valueType :db.type/string}

   :post/id    {:db/valueType :db.type/uuid
                :db/unique    :db.unique/identity}
   :post/author {:db/valueType :db.type/ref}
   :post/body  {:db/valueType            :db.type/string
                :db/fulltext             true
                :db.fulltext/autoDomain true}
   :post/tags  {:db/valueType   :db.type/keyword
                :db/cardinality :db.cardinality/many}})

(def conn
  (d/get-conn "/data/app" schema
              {:closed-schema? true}))
```
