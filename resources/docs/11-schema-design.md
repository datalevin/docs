---
title: "Schema Design and Attribute Semantics"
chapter: 11
part: "III — Modeling Across Paradigms"
---

# Chapter 11: Schema Design and Attribute Semantics

While Chapter 5 introduced the mechanics of attributes and namespaces, this chapter dives into the *art* of schema design. In a multi-paradigm database like Datalevin, your schema isn't just a set of constraints; it's the blueprint for how the query engine, search indexes, and storage layer interact.

A well-designed schema in Datalevin enables efficient joins, powerful graph traversals, and lightning-fast full-text searches.

---

## 1. The Power of Identity: `:db.unique/identity`

One of the most important decisions in schema design is how you identify your entities. While Datalevin provides internal 64-bit integer IDs, you often need to refer to entities using natural keys from your domain (like an email, a SKU, or a URL slug).

### 1.1 Lookup Refs
When an attribute is marked as `:db.unique/identity`, it becomes a **Lookup Ref**. This allows you to refer to an entity by its natural key in any part of the API—transactions, queries, or `d/pull`—without knowing its internal integer ID.

```clojure
;; Schema definition
{:user/email {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}}

;; Use the email as a handle to pull data
(d/pull db '[*] [:user/email "alice@example.com"])

;; Update a user by their email
(d/transact! conn [[:db/add [:user/email "alice@example.com"] :user/active? true]])
```

### 1.2 Upsert Behavior
Attributes with `:db.unique/identity` enable **upsert** behavior. If you transact a map with a unique identity that already exists in the database, Datalevin will merge the new attributes into the existing entity instead of creating a duplicate.

### 1.3 System-Wide Identifiers: `:db/ident`
While `:db.unique/identity` is used for attributes that uniquely identify a domain entity (like a user's email), **`:db/ident`** is a built-in attribute used to assign a globally unique keyword to an entity.

This is the standard way to represent **enums** or system-wide constants. Once an entity has a `:db/ident`, you can use that keyword anywhere you would use an entity ID or a lookup ref.

```clojure
;; Define an "enum" entity for order status
(d/transact! conn [{:db/ident :order.status/shipped}])

;; Use the keyword directly in another transaction
(d/transact! conn [{:order/id "123" :order/status :order.status/shipped}])
```

The advantage of using `:db/ident` over raw strings or keywords is that the enum itself is a full-fledged entity. You can attach additional metadata to it (like `:order.status/label "Shipped"`) without changing your data.

---

## 2. Modeling Relationships: References and Cardinality

In Datalevin, relationships are first-class citizens. They are defined using the `:db.type/ref` value type and the `:db/cardinality` property. **Crucially, the value of a reference attribute is always the entity ID (a 64-bit integer) of the target entity.**

### 2.1 One-to-One and One-to-Many
- **One-to-One**: Use `:db.type/ref` with `:db.cardinality/one`.
  - Example: A `User` has one `Profile`.
- **One-to-Many**: Use `:db.type/ref` with `:db.cardinality/many`.
  - Example: A `Post` has many `Comments`.

### 2.2 Many-to-Many: Cardinality vs. Join Entities
Datalevin handles many-to-many relationships naturally, but you have two main choices for how to model them.

1.  **`:db.cardinality/many`**: You can add an attribute with `:db.cardinality/many` to one or both entities. This is highly convenient and results in a "cleaner" data structure when using `d/pull`.
2.  **Join Entities**: You can create a third entity that "joins" the other two, similar to a join table in SQL.

**Performance Tip: Prefer Join Entities.** 
While `:db.cardinality/many` is convenient, Datalevin is highly optimized for **normalized data**. In a query, a "join entity" often performs better because it allows the query optimizer to make more granular decisions. If you expect a single entity to have thousands of references (e.g., a "Public" group with millions of members), a join entity is the superior choice for performance.

```clojure
;; Instead of many references in one entity:
;; {:user/id 1 :user/roles [:role/admin :role/editor ...]}

;; Use a join entity for better performance:
;; {:role-assignment/user 1 :role-assignment/role :role/admin}
;; {:role-assignment/user 1 :role-assignment/role :role/editor}
```

### 2.3 Reference Integrity
Because Datalevin is "schema-on-write" and flexible, it does not enforce traditional "foreign key" constraints by default. If you delete an entity that is referred to by others, the references to its ID will remain (pointing to a non-existent entity).

To ensure a clean removal of an entity and its associated facts, it is highly recommended to use the **`[:db/retractEntity <eid>]`** transaction function. This operation will:
1.  Retract all facts where the entity is the **subject** (E).
2.  Retract all facts where the entity is the **value** (V) for any `:db.type/ref` attribute.
3.  Recursively retract any entities marked as **components** of the retracted entity.

By using `:db/retractEntity`, you ensure that no "dangling" references point to a non-existent entity ID, effectively maintaining reference integrity.

---

## 3. Attribute Semantics: Beyond Simple Types

Datalevin allows you to attach additional semantic meaning to your attributes, which changes how the database processes them.

### 3.1 Full-Text Search (`:db/fulltext`)
Setting `:db/fulltext true` on a `:db.type/string` attribute tells Datalevin to maintain a specialized full-text index. This enables the `(fulltext ...)` predicate in Datalog (see Chapter 17).

### 3.2 Component Attributes (`:db/isComponent`)
When a reference attribute is marked as `:db/isComponent true`, it signals a "parent-child" relationship. 
- **Recursive Pull**: A wildcard `d/pull` on the parent will automatically include all attributes of the child component.
- **Cascading Deletes**: When the parent entity is deleted (via the **`:db/retractEntity`** transaction function), the child component entities are also automatically deleted.

This is ideal for modeling "owned" data, like line items in an invoice or segments of a document.

---

## 4. Best Practices: Designing for Evolution

Datalevin's flexibility is a double-edged sword. Here are best practices to keep your schema maintainable.

### 4.1 Namespace Everything
Always use namespaces for your attributes. A common pattern is `[domain]/[property]`, such as `:account/balance` or `:sensor/reading`. This prevents collisions if you later integrate third-party data or modularize your application.

### 4.2 Prefer Flat Entities
While `:db/isComponent` is useful for true ownership, don't over-nest your data. Datalevin excels at flat, normalized facts. The query engine is designed to join flat facts efficiently, so you don't need to "pre-join" data into complex documents as you might in a document store like MongoDB.

### 4.3 Modeling Enums with `:db/ident`
For attributes that have a fixed set of values (like status or type), you can use raw Clojure keywords as values, but it is often better to model them as **enum entities** using `:db/ident`.

```clojure
;; 1. Define your "enum" entities first
(d/transact! conn [{:db/ident :status/shipped}
                   {:db/ident :status/pending}])

;; 2. Your data refers to these entities by their ident
(d/transact! conn [{:order/id "123" :order/status :status/shipped}])
```

By modeling enums as entities with `:db/ident`, you gain:
1.  **Metadata**: You can attach display names, descriptions, or translations directly to the enum entity.
2.  **Reference Integrity**: If you use a `:db.type/ref` for the status attribute, Datalevin can ensure that every status points to a valid ident.
3.  **Discovery**: You can query for all possible statuses using Datalog.

---

## 5. Summary: The Schema Checklist

When adding a new attribute to your Datalevin database, ask yourself:

1.  **What is the type?** Use a specific type (`:db.type/long`, `:db.type/string`, etc.) for performance and range queries.
2.  **Is it a reference?** Use `:db.type/ref` to enable joins and graph traversals.
3.  **Is it a unique identity?** Use `:db.unique/identity` for natural keys (like emails) that you will use to lookup or upsert entities.
4.  **Is it a system-wide constant?** Use **`:db/ident`** to give a unique, globally namespaced keyword to an entity, perfect for enums and static system data.
5.  **Is it many-valued?** Use `:db.cardinality/many` for sets of values.
6.  **Does it need search?** Use `:db/fulltext` for string attributes you want to search.
7.  **Is it a component?** Use `:db/isComponent` for nested, "owned" entities.

By thoughtfully applying these properties, you create a schema that is both flexible enough for rapid development and robust enough for complex, high-performance applications.
