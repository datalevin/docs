---
title: "From Tables to Facts: Migrating from SQL"
chapter: 15
part: "III — Modeling Across Paradigms"
---

# Chapter 15: From Tables to Facts: Migrating from SQL

Migrating from a traditional relational database (SQL) to Datalevin is more than just a change in syntax; it’s a shift from "data in containers" to "data as a stream of facts." While SQL and Datalevin both handle relational data, their underlying mental models are fundamentally different.

This chapter provides a guide for SQL developers to translate their knowledge into Datalevin's fact-based world.

---

## 1. Mapping the Terminology

To get started, it’s helpful to map familiar SQL concepts to their Datalevin equivalents.

| SQL Concept | Datalevin Equivalent | Why the Difference? |
| :--- | :--- | :--- |
| **Table** | **Namespace** (e.g., `:user/`) | SQL tables define a rigid shape. Datalevin namespaces are a semantic convention for grouping attributes. |
| **Row** | **Entity ID** | A row is a container of values. An entity is just a collection of facts that share a common ID. |
| **Column** | **Attribute** | In SQL, a column belongs to a table. In Datalevin, an attribute is a first-class citizen with its own properties. |
| **Join** | **Implicit Join** (via shared variables) | SQL requires explicit `JOIN` clauses. Datalog joins by finding entities that satisfy multiple constraints simultaneously. |
| **Foreign Key** | **Ref** attribute (`:db.type/ref`) | SQL enforces referential integrity at the table level. Datalevin uses 64-bit integer references between entities. |
| **Schema** | **Attribute Schema** | SQL schemas are "rigid on write." Datalevin is "flexible on write, structured on query." |

---

## 2. From "Rows as Records" to "Datoms as Facts"

In SQL, a record is a single unit. If you update one column in a row, the entire row is logically affected.

In Datalevin, every piece of information is a **datom**: `[E A V]`.
- SQL: `INSERT INTO users (id, name, age) VALUES (101, 'Alice', 30);`
- Datalevin: Transact three separate facts:
  1. `[101 :user/id 101]` (implicit)
  2. `[101 :user/name "Alice"]`
  3. `[101 :user/age 30]`

**Why this matters:**
- **Sparse Data**: You don't need `NULL` values. If an entity doesn't have an attribute, the fact simply doesn't exist.
- **Additive Updates**: To update an entity, you just add or retract facts. You don't need to "re-insert" the entire record.

---

## 3. Querying: SQL vs. Datalog

The most visible difference between SQL and Datalog is query syntax, but the
mental shift is deeper. SQL queries describe *how* to retrieve data: which
tables to scan, which indexes to use, how to combine rows. There are often
multiple verbs in a SQL query. Datalog queries describe *what* data you want:
the patterns and constraints that results must satisfy. The query engine figures
out the how. The only verb is `:find`.

### 3.1 Simple Select

In SQL, you select columns from a table and filter rows. In Datalog, you declare
the shape of the data you're looking for. The `[?e :user/name ?name]` pattern
reads as "find entities where the `:user/name` attribute has some value, bind
that value to `?name`."

**SQL:**
```sql
SELECT u.name
FROM users u
WHERE u.age > 25;
```

**Datalog:**
```clojure
[:find ?name
 :where [?e :user/name ?name]
        [?e :user/age ?age]
        [(> ?age 25)]]
```

Notice that the same `?e` variable appears in both patterns. This is how Datalog
expresses "the same entity". In technical term, this is called *unification*.

### 3.2 Basic Join

This is where Datalog shines. SQL requires you to specify the join (a verb) condition
explicitly, hence it is more procedural. Datalog infers it from shared variables.

**SQL:**
```sql
SELECT u.name, o.order_id
FROM users u
JOIN orders o ON u.user_id = o.user_id;
```

**Datalog:**
```clojure
[:find ?name ?order-id
 :where [?u :user/name ?name]
        [?o :order/id ?order-id]
        [?o :order/user ?u]]
```

The join happens because `?u` appears in both the user pattern and the order
pattern. `[?o :order/user ?u]` says "the order's user is `?u`", so this is the
reference attribute acting as a foreign key. Multiple patterns sharing the same
variable automatically join on that variable. Join is implicit, and the query is
entirely declarative.

### 3.3 Aggregations

Aggregations follow a similar pattern. The key difference: Datalog doesn't have
an explicit `GROUP BY` clause (another verb). Instead, any variable in `:find`
that isn't wrapped in an aggregate function becomes a grouping key.

**SQL:**
```sql
SELECT u.city, COUNT(*) AS user_count
FROM users u
GROUP BY u.city;
```

**Datalog:**
```clojure
[:find ?city (count ?e)
 :where [?e :user/city ?city]]
```

Here `?city` groups the results, while `(count ?e)` counts entities per city.
The grouping is implicit: whatever variables remain "free" in `:find` define your
groups.

---

## 4. Modeling Relationships

One of the biggest hurdles for SQL developers is moving away from join tables for many-to-many relationships.

### 4.1 One-to-Many
In SQL, you add a `user_id` foreign key to the `orders` table. In Datalevin, you do the same by adding a `:order/user` attribute of type `:db.type/ref` to the order entity.

### 4.2 Many-to-Many
**SQL**: You must create a `user_groups` join table.
**Datalevin**: You have two choices:
1.  **`:db.cardinality/many`**: A single `:user/groups` attribute that holds a set of group IDs. (Chapter 11)
2.  **Join Entity**: A separate entity that links a user and a group. (Chapter 12)

**Recommendation**: Use a **Join Entity** if you need to store metadata about the relationship (e.g., "when was this user added to the group?").

---

## 5. Migration Strategy: A Practical Path

If you are migrating an existing SQL application to Datalevin, follow these steps:

1.  **Map Tables to Namespaces**: Convert each table into a namespace for its attributes.
2.  **Identify Primary Keys**: Use `:db.unique/identity` for any natural primary keys (like emails or UUIDs) to enable **Lookup Refs**.
3.  **Deconstruct Your Rows**: Think about each column as a separate fact.
4.  **Normalize**: Don't be afraid to break apart large, bloated tables. Datalevin handles many small facts more efficiently than a few large, complex records.
5.  **Use `d/pull` for Retrieval**: Instead of complex `JOIN` logic in your application, find the entity ID you need with `d/q` and then use `d/pull` to get all its data in a nested map.

---

## Summary

Migrating from SQL to Datalevin is a journey from rigid tables to a flexible, logical network of facts. While the syntax is different, the core relational principles remain the same. By embracing the "fact-first" model, you gain incredible flexibility in how you evolve your schema and how you query your data across relational, graph, and document paradigms.
