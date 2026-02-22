---
title: "Document Modeling and Path-Based Access"
chapter: 14
part: "III — Modeling Across Paradigms"
---

# Chapter 14: Document Modeling and Path-Based Access

In traditional databases, you often have to choose between a relational model and a document model. Datalevin gives you the best of both worlds by providing two distinct paths to document modeling: **Logical Documents** (built from triples) and **Native Indexed Documents** (`idoc`).

This chapter explains how to choose the right model for your data and how to navigate these structures efficiently.

---

## 1. The Triple-Based Model: `:db/isComponent`

The most common way to model documents in Datalevin is to use **Component Attributes**. This is a "logical" document where data is stored as individual datoms but behaves as a single unit.

### Example: A Nested Blog Post
```clojure
;; Schema: :post/comments is a component ref
{:post/comments {:db/valueType   :db.type/ref
                 :db/cardinality :db.cardinality/many
                 :db/isComponent true}}
```

**Why use Triples for Documents?**
- **Granularity**: Every piece of data is an individual datom that can be queried, joined, and updated independently.
- **Integrity**: You can enforce types and constraints on every nested field.
- **Relational Power**: You can easily join a nested "comment" to its "author" even if the author is a separate top-level entity.

---

## 2. The Native Document Model: `idoc`

For semi-structured data that changes frequently or doesn't fit a rigid schema, Datalevin provides a native document type called **`idoc` (Indexed Document)**. 

Unlike the triple model, an `idoc` stores an entire nested map or vector as a **single value** within a datom. However, Datalevin automatically builds a high-performance index for every path within that document.

### 2.1 Defining an `idoc`
You define an `idoc` attribute in your schema using the `:db.type/idoc` value type.

```clojure
(def schema
  {:user/metadata {:db/valueType :db.type/idoc
                   :db/idocFormat :json}}) ; Formats: :edn (default), :json, :markdown
```

### 2.2 Transacting and Patching
You can transact an entire document at once, or use the **`:db.fn/patchIdoc`** function to perform granular updates (set, unset, update) without re-sending the whole document.

```clojure
;; Transact a nested document
(d/transact! conn [{:db/id 101 :user/metadata {:theme "dark" :font-size 14}}])

;; Patch a single field deep in the document
(d/transact! conn [[:db.fn/patchIdoc 101 :user/metadata [:theme] :set "light"]])
```

---

## 3. Choosing Your Model: Triples vs. `idoc`

| Feature | Triple-Based (`:isComponent`) | Native `idoc` |
| :--- | :--- | :--- |
| **Storage** | Decomposed into many datoms | Stored as a single blob |
| **Schema** | Every attribute must be defined | Flexible; any map/vector allowed |
| **Indexing** | Standard EAV/AVE indexes | Automatic path-level indexing |
| **Querying** | Standard Datalog joins | `idoc-match` function |
| **Updates** | Fine-grained datom updates | `patchIdoc` or full replacement |

**Use Triples when**: Your data is structured, you need strict type checking, or you want to join nested parts to other entities.
**Use `idoc` when**: Your data is highly dynamic (e.g., user-defined metadata, logs), you want to avoid schema migrations for nested fields, or you are migrating from a document store like MongoDB.

---

## 4. Path-Based Access and Querying

Regardless of the model you choose, Datalevin makes path-based access intuitive.

### 4.1 Accessing Triples with Pull
For triple-based documents, use the **Pull API** to navigate paths:
`(d/pull db [{:post/comments [:comment/body]}] post-id)`

### 4.2 Querying `idoc` with `idoc-match`
For native documents, use the **`idoc-match`** function in Datalog to find entities based on values at specific paths.

```clojure
;; Find users with "dark" theme in their metadata idoc
(d/q '[:find ?e
       :where [(idoc-match $ :user/metadata {:theme "dark"}) [[?e]]]]
     db)
```

For a deep dive into the advanced path syntax and indexing mechanics of `idoc`, see **Chapter 19: Automatic Document Indexes**.

---

## 5. Summary

Datalevin's multi-paradigm nature means you never have to force your data into the wrong shape. Use **Triples** for your core domain model where structure and integrity matter, and use **`idoc`** for the flexible, semi-structured parts of your application. Both are fully integrated into the Datalog engine, allowing you to query across them seamlessly.
