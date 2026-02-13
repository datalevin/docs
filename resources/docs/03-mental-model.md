---
title: "The Datalevin Mental Model: Values, Facts, and Relationships"
chapter: 3
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 3: The Datalevin Mental Model: Attributes, Facts, and Relationships

Transitioning to Datalevin often requires a fundamental shift in how you perceive
data. If you are coming from a relational (SQL) or document (NoSQL) background,
your mental model likely revolves around "containers": tables with fixed columns
or documents with nested structures. In these systems, the container is the
primary unit of truth.

Datalevin asks you to look deeper, past the containers, to the atomic facts
themselves. Moving from SQL to Datalevin is less like moving between different
spreadsheet programs and more like moving from a spreadsheet to a knowledge
graph. This chapter provides the narrative framework to help you navigate this
shift.

## 1. Think in Datoms, Not Containers

In traditional databases, if you want to record that "Alice is 30 years old,"
you must first decide which "box" that fact belongs in. You might create a
`Users` table and add a row for Alice. The fact of her age is then trapped
within the context of that row and those other columns.

At the conceptual level, Datalevin discards these rigid boxes. Instead, it
stores data as a stream of atomic facts called **datoms**. Each datom is a
simple statement of truth represented as a triple: `[entity attribute value]`.

- **Entity (E):** The "who" or "what" (a unique ID).
- **Attribute (A):** The "property" (a name like `:user/name`).
- **Value (V):** The "data" (like `"Alice"` or `30`).

Imagine a giant ledger where every single cell of every spreadsheet in your
entire company is cut out and pasted as an individual line. Each line tells
you exactly which "row" it came from (Entity), what "column" it represents
(Attribute), and what the "value" is.

This "fact-first" approach is powerful because it is **additive**. To update
Alice's age, you don't modify a row; you simply assert a new fact. To add a
new piece of information—like her favorite color, you don't need to "alter a
table." You just add another datom: `[AliceID :user/favorite-color "Blue"]`.

## 2. Attributes Over Tables: The Fluid Entity

Because facts are stored individually, the concept of a "Table" disappears.
In its place is **Attribute-Centric Modeling**.

In SQL, the schema belongs to the table. In Datalevin, the schema belongs to
the **attribute**. You define what a `:person/email` is, e.g. a string, unique,
full-text indexed, but you don't define what a "Person" is in a rigid sense.

An "Entity" in Datalevin is just a collection of datoms that happen to share
the same Entity ID. This makes entities **fluid**. A single ID could have
attributes associated with a `:user`, a `:customer`, and an `:employee`
simultaneously. You don't "join" these tables; you simply query for an ID
that possesses all those attributes.

```clojure
;; The schema defines the behavior of properties, not the shape of rows.
(def schema
  {:person/name   {:db/valueType :db.type/string}
   :person/age    {:db/valueType :db.type/long}
   :person/school {:db/valueType :db.type/ref} ; A reference to another entity
   :school/name   {:db/valueType :db.type/string}})

;; Data is asserted as a collection of facts.
;; Notice how 'Alice' and 'MIT' are just sets of attributes.
(d/transact! conn
  [{:db/id -1 :person/name "Alice" :person/age 30 :person/school -10}
   {:db/id -10 :school/name "MIT" :school/country "USA"}])
```

This fluidity allows your data model to evolve alongside your application. You
never have to worry about "breaking the schema" by adding a new attribute to an
existing entity, because there is no rigid "row" to break. Entity types are
conventions you create by naming your attributes (e.g., using the `person/`
namespace), not constraints enforced by the storage engine.

## 3. Normalize First: Efficiency through Deconstruction

In many NoSQL databases, you are encouraged to denormalize: to nest data inside
documents—to avoid "expensive" joins. In Datalevin, this advice is reversed:
**Normalize by default.**

In a relational database, a join often requires scanning two different tables
and matching IDs. In Datalevin, all facts are stored in a unified set of
indexes. Joining an `Order` to a `Customer` isn't a cross-table operation; it's
just a matter of looking up datoms that share a value.

### Why Normalization Wins in Datalevin:

1.  **Granular Updates:** You can update a customer's email without touching
    thousands of order records. In a denormalized document store, this update
    would be a massive, expensive operation.
2.  **Universal Indexing:** Every attribute is indexed. When you normalize,
    you allow the engine to use its core EAV (Entity-Attribute-Value) and AVE
    (Attribute-Value-Entity) indexes to jump directly to the relevant facts.
3.  **The Optimizer's Secret Sauce:** Datalevin uses a cost-based optimizer.
    It samples the data to understand the "cardinality" of your query clauses.
    By keeping data normalized, you give the optimizer a clearer picture of
    how to order joins for maximum speed.

For a deeper dive into how this approach often beats traditional relational
engines in query performance, see the discussion on the [Join Order
Benchmark](https://yyhh.org/blog/2024/09/competing-for-the-job-with-a-triplestore/).

## 4. The Three-Layer Architecture

To master Datalevin, you must keep three layers distinct in your mind. They
work together, but they serve different purposes.

### Layer 1: The Conceptual Model (The "What")
This is the world of **Datoms**. It is the logical view where everything is a
triple. This is where you design your schema and think about relationships. It
is designed for humans to reason about data clearly.

### Layer 2: The Physical Storage (The "How")
Underneath the logic is a high-performance **Key-Value Store** (DLMDB). While
Layer 1 sees "facts," Layer 2 sees sorted byte arrays of keys. Datalevin
efficiently maps those triples into specific key patterns that allow for
lightning-fast range scans. Importantly, this layer is exposed via a direct
API for cases where you need raw speed or simple state management without the
overhead of a query engine.

### Layer 3: The Execution Model (The "Action")
This is the **Datalog Engine**. It sits on top of the storage layer, using a
cost-based optimizer to decide the most efficient way to retrieve the facts
requested in Layer 1. It handles the complexity of joins, recursion, and
filtering so you don't have to.

```text
+-----------------------------------------------------------+
| Layer 1: CONCEPTUAL (Datoms: E-A-V)                        |
| "Alice is 30" -> [101 :user/age 30]                       |
+---------------------------+-------------------------------+
                            |
                            v
+-----------------------------------------------------------+
| Layer 3: EXECUTION (Datalog / Optimizer / Rules)          |
| "Find all users over 25" -> Plan: Scan AVE index for age  |
+---------------------------+-------------------------------+
                            |
                            v
+-----------------------------------------------------------+
| Layer 2: PHYSICAL (KV Store: DLMDB)                  |
| Sorted keys: 0x01... -> [bytes]                           |
+-----------------------------------------------------------+
```

## 5. Datalog: Querying as Logic Programming

If SQL is like giving the database a set of instructions on how to assemble a
table, Datalog is like giving the database a **description** of the answer you
want.

In Datalevin, a query is a set of logical "clauses." You aren't telling the
engine to "JOIN table A with table B." You are stating: "I am looking for an
Entity `?e` that has Attribute `:user/name` with Value `'Alice'`, and that same
Entity `?e` must also have an Attribute `:user/age` which is `?age`."

```clojure
(d/q '[:find ?age
       :where
       [?e :user/name "Alice"]
       [?e :user/age ?age]]
     db)
```

The engine takes these statements and finds the set of values that makes all
of them true simultaneously. This is **Logic Programming**. Because joins are
implicit (represented by the shared variable `?e`), the complexity of your
query doesn't grow with the number of joins; it only grows with the logic you
want to express.

## 6. Unified Retrieval: Plugging into the Datom Flow

One of the most powerful aspects of the Datalevin mental model is that
"special" features like Full-Text Search, Vector Similarity, and Document
Indexing aren't "sidecar" services. They are integrated directly into the
Datalog flow.

When you perform a full-text search, the search function behaves just like any
other Datalog clause. It returns a set of datoms (`[e a v]`) that can be
immediately joined with other relational facts.

```clojure
;; A "Hybrid" query: Search for text, then filter by metadata
(d/q '[:find ?title
       :where
       [(fulltext $ :doc/body "clojure") [[?e]]] ; Full-text search returns entities
       [?e :doc/status :published]               ; Standard datom join
       [?e :doc/title ?title]]                   ; Retrieve the title
     db)
```

This "Unified Retrieval" model means you never have to worry about syncing
your database with an external search engine. Your search results are always
transactionally consistent with your data.

## 7. Rules: Teaching the Database New Tricks

Rules are how you encapsulate and reuse logic in Datalevin. If you find yourself
frequently querying for "Active Users who have Premium subscriptions," you
can define a rule called `premium-user`.

Rules can also be **recursive**, which is essential for graph traversal. A
classic example is finding ancestors in a family tree:

```clojure
(def ancestry-rules
  '[[(ancestor ?x ?y)      ; Rule name and arguments
     [?x :parent ?y]]      ; Case 1: y is the direct parent of x

    [(ancestor ?x ?y)      ; Case 2: Recursion
     [?x :parent ?z]       ; z is the parent of x
     (ancestor ?z ?y)]])   ; y is an ancestor of z
```

By defining this rule, you've taught the database the *concept* of an ancestor.
You can now ask "Who are all the ancestors of Entity 42?" and the engine will
navigate the graph for you.

## Summary

The Datalevin mental model is about moving from "data-in-boxes" to "data-as-facts."

- **Datoms** are the atoms of truth.
- **Attributes** define how facts behave.
- **Normalization** keeps the model clean and the engine fast.
- **Datalog** allows you to query by describing the logic of your answer.
- **Rules** allow you to build complex, reusable, and recursive logic.

By embracing this model, you unlock a level of flexibility and power that
traditional databases struggle to match.
