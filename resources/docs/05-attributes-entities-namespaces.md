---
title: "Attributes, Entities, and Namespaces"
chapter: 5
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 5: Attributes, Entities, and Namespaces

In Chapter 4, we looked at how Datalevin stores triples (datoms) in LMDB. Now, we move up the stack to the logical model. Datalevin's data model is built on three pillars: **Attributes**, **Entities**, and **Namespaces**.

Unlike rigid relational databases, Datalevin is **schema-on-write**. This means the database is flexible by default, but provides powerful controls when you need performance or integrity.

---

## 1. Attributes: The Schema-on-Write Model

An attribute (the "A" in EAV) defines a property that can be associated with an entity.

### 1.1 Automatic Attribute Creation
By default, you don't need to "create a table" or "define a schema" to start using Datalevin. When you transact a new attribute name that the database hasn't seen before, Datalevin automatically adds it to the internal schema.

```clojure
;; This works even if :user/name was never defined
(d/transact! conn [{:user/name "Alice"}])
```

### 1.2 The Default Type: EDN Binary
If an attribute is added automatically, Datalevin treats its value as a generic **EDN blob**.
- **Pros**: You can store any Clojure data structure (maps, vectors, sets) directly.
- **Cons**: Because the values are stored as opaque binary blobs, the database cannot perform efficient **range queries** (e.g., "find all users with age > 20") because it doesn't know how to sort the binary data numerically or alphabetically.

### 1.3 Why Explicit Types Matter
To enable range queries and optimize storage, you are encouraged to specify a data type. Explicit types allow Datalevin to use specialized codecs for sorting in the B+Tree.

**Common Data Types:**
- `:db.type/string`: UTF-8 strings (sortable).
- `:db.type/long`: 64-bit integers (sortable).
- `:db.type/instant`: Dates/Timestamps (sortable).
- `:db.type/boolean`: True/False.
- `:db.type/ref`: A reference to another entity (enables joins).

---

## 2. Attribute Properties

While attributes are created automatically, you can provide a schema map to `d/create-conn` or `d/update-schema` to define specific behaviors.

| Property | Description |
| :--- | :--- |
| `:db/valueType` | The data type (e.g., `:db.type/long`). |
| `:db/cardinality` | `:db.cardinality/one` (default) or `:db.cardinality/many` (set of values). |
| `:db/unique` | `:db.unique/identity` (unique and lookupable) or `:db.unique/value` (just unique). |
| `:db/fulltext` | Set to `true` to enable full-text search on string values. |

> **Note on Indexing**: Unlike some other Datalog databases, Datalevin indexes **every attribute** by default in the AVE (Attribute-Value-Entity) index. You do not need to specify a `:db/index` property to enable fast lookups.

**Example Schema Definition:**
```clojure
(def schema
  {:user/email {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
   :user/age   {:db/valueType :db.type/long}
   :user/tags  {:db/cardinality :db.cardinality/many}})
```
---

## 3. Namespaces: Semantic Grouping

In Datalevin, attribute names are keywords, and by convention, they almost always include a **namespace**.

- **Convention, not Constraint**: Namespaces like `:user/name` or `:order/id` are semantic groupings. They don't restrict where an attribute can be used (an entity could theoretically have both `:user/name` and `:product/price`), but they prevent naming collisions.
- **Semantic Clarity**: Namespacing allows different parts of an application to share the same database without accidentally overwriting each other's "name" or "id" fields.

---

## 4. Entities and IDs

An **Entity** (the "E" in EAV) is simply a collection of datoms that share the same Entity ID.

### 4.1 System-Managed IDs
Datalevin uses 64-bit integers for Entity IDs.
- **Auto-Increment**: When you transact a new map without an ID, Datalevin automatically assigns a new, incrementing ID.
- **Permanent**: Once assigned, an ID is the permanent handle for that entity.

### 4.2 Tempids
During a transaction, you often use **tempids** (temporary IDs) to express
relationships between new entities before the database has assigned permanent
IDs. Tempids can be a negative integer or a string.

```clojure
(d/transact! conn
  [{:db/id -1 :user/name "Alice"}
   {:db/id -2 :user/name "Bob" :user/friend -1}]) ; Bob follows Alice using her tempid
```

---

## 5. Schema Evolution and Migration

Datalevin makes it easy to evolve your schema as your application requirements change. A common workflow is to start with untyped attributes during prototyping and then add types later for performance and range query support.

### 5.1 Automatic Type Migration
When you use `update-schema` to add a `:db/valueType` to an existing attribute
that was previously untyped (and thus stored as EDN binary), Datalevin performs
an **atomic data migration**.

It will:
1.  Read the existing EDN binary values.
2.  Attempt to decode them into the new specified type.
3.  Update the indexes (like AVE) to use the new typed encoding, which enables efficient range scans.

This means you don't need to manually write migration scripts to "upgrade" your data when moving from a flexible prototype to a structured production schema.

---

## 6. Practical Summary: Schema Workflow

1. **Prototyping**: Start without a schema. Just transact maps.
2. **Optimization**: Once you know your access patterns, add `:db/valueType` to enable range queries on specific attributes.
3. **Integrity**: Add `:db/unique/identity` for fields like emails or slugs so you can use them as "lookup refs" (e.g., `[:user/email "alice@example.com"]`).
4. **Relations**: Use `:db.type/ref` to connect entities, forming the graph that Datalog traverses so well.

---

## Summary

Datalevin's approach to schema is **"Pay as you go."** You get the speed of a schema-less store during development, but the power of a strictly typed, indexed database as your application matures. Namespaces keep your attributes organized, and system-managed IDs ensure your entities remain stable across transactions.
