---
title: "Attributes, Entities, and Namespaces"
chapter: 5
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 5: Attributes, Entities, and Namespaces

In Chapter 4, we looked at how Datalevin stores triples (datoms) in LMDB. Now,
we move up the stack to the logical model. Datalevin's data model is built on
three pillars: **Attributes**, **Entities**, and **Namespaces**.

Unlike rigid relational databases, Datalevin is **schema-on-write**. This means
the database is flexible by default, but provides powerful controls when you
need performance and integrity.

---

## 1. Attributes: The Schema-on-Write Model

An attribute (the "A" in EAV) defines a property that can be associated with an
entity.

### 1.1 Automatic Attribute Creation

By default, you don't need to "create a table" or "define a schema" to start
using Datalevin. When you transact a new attribute keyword that the database hasn't
seen before, Datalevin automatically adds it to the internal schema.

<div class="multi-lang">

```clojure
;; This works even if :user/name was never defined
(d/transact! conn [{:user/name "Alice"}])
```

```java
// This works even if user/name was never defined
Datalevin.transact(conn, List.of(Map.of("user/name", "Alice")));
```

```python
# This works even if user/name was never defined
d.transact(conn, [{"user/name": "Alice"}])
```

```javascript
// This works even if user/name was never defined
d.transact(conn, [{"user/name": "Alice"}]);
```

</div>

### 1.2 The Default Type: EDN Binary

If an attribute is added automatically, Datalevin treats its value as a generic
**EDN blob**, which has some trade-offs.

- **Pros**: You can store any Clojure data structure (maps, vectors, sets)
  directly.
- **Cons**: Because the values are stored as opaque binary blobs, the database
  cannot perform efficient **range queries** (e.g., "find all orders with total
  > 100") because it doesn't know how to sort the binary data numerically or
  alphabetically.

### 1.3 Why Explicit Types Matter

To enable range queries, validation, compact storage, and specialized indexes,
you are encouraged to specify a data type. Explicit types allow Datalevin to use
specialized codecs for sorting values in the B+Tree instead of treating values
as opaque EDN blobs.

Some common Datalog value types for `:db/valueType`:

| Type | Use |
| :--- | :--- |
| `:db.type/string` | Strings. Use for names, emails, external ids, and text fields. |
| `:db.type/keyword` | Keywords. Good for enums such as `:order.status/paid`. |
| `:db.type/boolean` | `true` or `false`. |
| `:db.type/long` | 64-bit signed integers. |
| `:db.type/bigint` | Large integer values. |
| `:db.type/double` | 64-bit floating-point numbers. |
| `:db.type/instant` | Instants, as `java.util.Date` values or integer epoch milliseconds. |
| `:db.type/uuid` | UUID values. |
| `:db.type/bytes` | Byte arrays. |
| `:db.type/ref` | Entity references. Required for reverse lookup refs and component relationships. |

For a complete list of acceptable value types, please see Appendix A.

---

## 2. Attribute Properties

While attributes are created automatically, you can provide a schema map to
`d/create-conn` or `d/update-schema` to define specific behaviors for the
attributes. Table below lists some example attribute properties.

| Property | Description |
| :--- | :--- |
| `:db/valueType` | The data type (e.g., `:db.type/long`). |
| `:db/cardinality` | `:db.cardinality/one` (allow single value, default) or `:db.cardinality/many` (allow multiple values). |
| `:db/unique` | `:db.unique/identity` (unique, upsert when duplicate) or `:db.unique/value` (unique, reject duplicate). |
| `:db/fulltext` | Set to `true` to enable full-text search on string values. |
| `:db/embedding` | Set to `true` on string attributes to maintain an embedding similarity index. |
| `:db/idocFormat` | Format for `:db.type/idoc` attributes: `:edn`, `:json`, or `:markdown`. |

> **Note on Indexing**: Unlike some other Datalog databases, Datalevin indexes **every attribute** by default in the AVE (Attribute-Value-Entity) index. You do not need to specify a `:db/index` property to enable fast lookups.

Appendix A includes a complete description of acceptable properties in the schema.

**Example Schema Definition:**
```clojure
(def schema
  {:user/email       {:db/valueType :db.type/string
                      :db/unique    :db.unique/identity}
   :user/login-count {:db/valueType :db.type/long}
   :user/tags        {:db/cardinality :db.cardinality/many}
   :user/bio         {:db/valueType :db.type/string
                      :db/fulltext  true
                      :db/embedding true}})
```
---

## 3. Namespaces: Semantic Grouping

In Datalevin, attribute names are keywords, and by convention, they almost
always include a **namespace**.

- **Convention, not Constraint**: Namespaces like `:user/name` or `:order/id`
  are semantic groupings. They don't restrict where an attribute can be used (an
  entity could theoretically have both `:user/name` and `:product/price`), but
  they prevent naming collisions.
- **Semantic Clarity**: Namespacing allows different parts of an application to
  share the same database without accidentally overwriting each other's "name"
  or "id" fields.

---

## 4. Entities and IDs

An **Entity** (the "E" in EAV) is simply a collection of datoms that share the
same Entity ID.

### 4.1 System-Managed IDs

Datalevin uses 64-bit integers for Entity IDs.

- **Auto-Increment**: When you transact a new map without an ID, Datalevin
  automatically assigns a new, incrementing ID.
- **Permanent**: Once assigned, an ID is the permanent handle for that entity.

### 4.2 Tempids

During a transaction, you often use **tempids** (temporary IDs) to express
relationships between new entities before the database has assigned permanent
IDs. Tempids can be a negative integer or a string.

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id -1 :user/name "Alice"}
   {:db/id -2 :user/name "Bob" :user/friend -1}]) ; Bob follows Alice using her tempid
```

```java
Datalevin.transact(conn, List.of(
    Map.of("db/id", -1, "user/name", "Alice"),
    Map.of("db/id", -2, "user/name", "Bob", "user/friend", -1))); // Bob follows Alice using her tempid
```

```python
d.transact(conn, [
    {"db/id": -1, "user/name": "Alice"},
    {"db/id": -2, "user/name": "Bob", "user/friend": -1}])  # Bob follows Alice using her tempid
```

```javascript
d.transact(conn, [
  {"db/id": -1, "user/name": "Alice"},
  {"db/id": -2, "user/name": "Bob", "user/friend": -1}  // Bob follows Alice using her tempid
]);
```

</div>

---

## 5. Practical Summary: Schema Workflow

1. **Prototyping**: Start without a schema. Just transact maps.
2. **Optimization**: Once you know your access patterns, add `:db/valueType` to
   enable range queries on specific attributes. Chapter 11 covers the
   `update-schema` workflow, including Datalevin's supported migration from
   untyped EDN values to typed values.
3. **Integrity**: Add `:db/unique/identity` for fields like emails or slugs so
   you can use them as "lookup refs" (e.g., `[:user/email
   "alice@example.com"]`).
4. **Relations**: Use `:db.type/ref` to connect entities, forming the graph that
   Datalog traverses so well.

---

## Summary

Datalevin's approach to schema is **"Pay as you go."** You get the speed of a
schema-less store during development, but the power of a strictly typed, indexed
database as your application matures. Namespaces keep your attributes organized,
and system-managed IDs ensure your entities remain stable across transactions.
