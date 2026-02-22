---
title: "Core Index Architecture: EAV and AVE"
chapter: 16
part: "IV — Indexes as Capabilities"
---

# Chapter 16: Core Index Architecture: EAV and AVE

In a traditional relational database, an index is an "extra" structure you create to speed up specific queries. In Datalevin, the index **is** the database. Every piece of information you transact is automatically decomposed into multiple sorted representations.

This "index-first" architecture is what allows Datalog to perform complex joins and graph traversals with predictable, high-speed performance. This chapter explores the two primary workhorses of Datalevin: the **EAV** and **AVE** indexes.

---

## 1. The Logic of Sorted Triples

Every fact in Datalevin is a triple (a datom): `[Entity Attribute Value]`. To make searching efficient, Datalevin stores every datom in two different sorted orders within the underlying KV store (LMDB).

### 1.1 EAV (Entity-Attribute-Value)
The EAV index is sorted primarily by the **Entity ID**, then by the **Attribute**, and finally by the **Value**.

- **Structure**: `E -> A -> V`
- **Primary Use Case**: "Give me everything about Entity 101."
- **Query Role**: This index powers the **Pull API** and any join where the Entity is already known. Because all attributes for a single entity are stored contiguously in the B+Tree, retrieving a complete "document" for an entity is a single localized scan.

### 1.2 AVE (Attribute-Value-Entity)
The AVE index is sorted primarily by the **Attribute**, then by the **Value**, and finally by the **Entity ID**.

- **Structure**: `A -> V -> E`
- **Primary Use Case**: "Find all entities where the `:user/age` is `30`."
- **Query Role**: This is the "search" index. It powers all `:where` clauses that filter by value.
- **Uniqueness**: In Datalevin, **every attribute is indexed in AVE by default**. Unlike other Datalog databases, you do not need to explicitly opt-in to indexing.

---

## 2. Leveraging the AVE Index for Range Queries

Because the AVE index is sorted by Value, it is the engine behind all comparison and range operations in Datalog.

When you write a query like `[(> ?age 21)]`, the Datalevin query optimizer doesn't scan the entire database. Instead, it:
1. Jumps to the first entry in the AVE index for `:user/age` where the value is greater than `21`.
2. Scans forward through the sorted values.

This makes range queries (finding dates, prices, or ages) extremely efficient. Because DLMDB supports **order statistics** (Chapter 4), the engine can even estimate how many items are in a range instantly, allowing it to pick the most efficient join order.

---

## 3. Reverse Relationships: The "Missing" VAE Index

Some Datalog databases (like Datomic) include a **VAE (Value-Attribute-Entity)** index specifically for reverse references. Datalevin chooses a simpler approach.

In Datalevin, a reference (`:db.type/ref`) is just a value that happens to be an Entity ID. Therefore, a reverse reference (e.g., "who points to Alice?") is simply an AVE lookup:
`[?who :user/friend ?alice-id]`

Because **every attribute has an AVE index**, reverse navigation is just as fast as forward navigation. There is no need for a separate VAE index, which reduces on-disk storage size and write overhead.

---

## 4. Index-Level APIs: Direct Access

One of the most powerful features of Datalevin is that it exposes these indexes directly to the developer. You are not limited to the Datalog query engine; you can treat the indexes as **programmable capabilities**.

### 4.1 `d/datoms`: Low-Level Index Scans
The `d/datoms` function allows you to perform a raw scan of an index. This is often faster than a full Datalog query for simple lookups.

```clojure
;; Get all attributes for entity 101 from the EAV index
(d/datoms db :eav 101)

;; Get all entities with age 30 from the AVE index
(d/datoms db :ave :user/age 30)
```

### 4.2 `d/index-range`: Precise Range Scans
The `d/index-range` function allows you to scan a specific part of the AVE index using a range of values.

```clojure
;; Find users with age between 20 and 30
(d/index-range db :user/age 20 30)
```

---

## 5. Physical Representation and DUPSORT

Physically, these indexes are implemented using LMDB's `DUPSORT` feature (see Chapter 6). This allows Datalevin to store many values for a single key efficiently.

- **In EAV**: The Key is `E`, and the Values are `(A, V)` pairs.
- **In AVE**: The Key is `(A, V)`, and the Values are `E` (entity IDs).

Thinking in terms of traditional database storage models:

- **EAV is a row store**: Each entity ID (key) maps to a list of attribute-value pairs, analogous to a row where all column values are stored together. Retrieving an entity is a single key lookup.
- **AVE is a column store**: Each `(A, V)` combination (key) maps to a tightly packed list of entity IDs—the "row IDs" that share that column value. This is ideal for analytical queries that scan a column.

This nested storage eliminates redundant prefixes. In EAV, an entity with 10 attributes stores the entity ID once as the key, with 10 `(A, V)` pairs as values. In AVE, each `(A, V)` combination is stored once as the key, with all matching entity IDs as values—so finding all entities where `:user/age` is `30` is a single key lookup. This results in approximately 20% space reduction, with additional savings from page-based prefix compression in the underlying KV storage.

---

## 6. Summary: Indexes as Capabilities

By making every attribute indexed by default and providing direct API access to those indexes, Datalevin transforms the database from a "black box" into a set of **programmable capabilities**.

- **EAV** provides locality for entities and documents.
- **AVE** provides fast lookups and range scans for values.
- **Direct Access** allows you to build custom traversal logic that bypasses the query optimizer when absolute performance is required.

Understanding these indexes is the first step toward mastering the more specialized capabilities of Datalevin, such as full-text search and vector similarity, which we will explore in the following chapters.
