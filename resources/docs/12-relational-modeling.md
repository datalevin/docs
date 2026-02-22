---
title: "Relational Modeling Patterns"
chapter: 12
part: "III — Modeling Across Paradigms"
---

# Chapter 12: Relational Modeling Patterns

Because Datalevin prefers normalized data, industry-standard **Entity-Relationship (ER) modeling** is not just applicable—it is the most effective way to design your database. 

While SQL databases require "Object-Relational Mapping" (ORM) to bridge the gap between your mental model and rigid tables, Datalevin’s fact-based model maps almost 1:1 to how we reason about the world. This chapter explores how to apply classic relational modeling patterns to a Datalog-powered triplestore.

---

## 1. The Grammar of Data: Nouns, Verbs, and Adjectives

When modeling a domain, think of your schema as a language.

### 1.1 Nouns (Entities)
Nouns are the "things" in your system—the **Entities**. In Datalevin, we represent these using **Namespaced Keywords**.
- **Rule**: Use **singular nouns** for namespaces.
- **Example**: Use `:user/name`, not `:users/name`. Use `:product/price`, not `:products/price`.

Singular namespacing makes Datalog queries feel like natural sentences: 
`[?e :user/name ?n]` reads as "the entity `?e` has a user name `?n`."

### 1.2 Adjectives (Attributes)
Adjectives are the properties that describe a noun. These are your standard value-type attributes like strings, longs, and booleans.
- **Example**: `:product/color "Red"`, `:order/status :status/pending`.

### 1.3 Verbs (Relationships)
Verbs describe how nouns interact. In Datalevin, verbs are modeled using `:db.type/ref`.
- **Example**: `:user/follows`, `:order/items`.

---

## 2. Normalization: The Path to Performance

In a document-oriented database, you are often taught to denormalize—to "pre-join" data into nested documents. In Datalevin, this is an anti-pattern. **Normalize by default.**

### 2.1 The "Join Entity" Pattern
As discussed in Chapter 11, for many-to-many relationships, it is often better to use a **Join Entity** than a `:db.cardinality/many` attribute. 

In ER terms, this is an **Associative Entity**. If you have `Users` and `Groups`, instead of a list of group IDs on the user, create a `Membership` entity.

```clojure
;; Associative Entity: Membership
{:membership/user  101 ; ref to User
 :membership/group 202 ; ref to Group
 :membership/role  :role/admin}
```

This approach allows you to:
1.  **Attach Metadata to the Relationship**: You can record *when* a user joined a group, or *who* invited them.
2.  **Optimize Query Execution**: Datalevin's query optimizer can jump to a specific membership record faster than scanning a large list of IDs inside a single user entity.

---

## 3. Documenting the Schema: `:db/doc`

A schema is not just for the database engine; it is for the developers who will maintain the system for years to come. Every attribute in your schema should be documented.

Datalevin supports a **`:db/doc`** property in the schema map. Use it to explain the purpose and constraints of an attribute.

```clojure
(def schema
  {:user/email {:db/valueType :db.type/string
                :db/unique    :db.unique/identity
                :db/doc       "The primary unique identifier for a user account."}
   :order/total {:db/valueType :db.type/bigdec
                 :db/doc       "The total price of the order in USD, including tax."}})
```

Think of `:db/doc` as "comments that live in the database." They can be queried and used to generate documentation automatically.

---

## 4. Modeling Patterns: From SQL to Datoms

| SQL Concept | Datalevin Equivalent |
| :--- | :--- |
| **Table** | A **Namespace** (e.g., `:user/`) |
| **Row** | An **Entity ID** |
| **Column** | An **Attribute** |
| **Foreign Key** | A **Ref** attribute (`:db.type/ref`) |
| **Join Table** | A **Join Entity** (Associative Entity) |

### Example: A Normalized E-commerce Model

```clojure
(def ecommerce-schema
  {;; Noun: Product
   :product/sku   {:db/unique :db.unique/identity :db/valueType :db.type/string}
   :product/title {:db/fulltext true :db/valueType :db.type/string}
   :product/price {:db/valueType :db.type/long}

   ;; Noun: Order
   :order/id      {:db/unique :db.unique/identity :db/valueType :db.type/string}
   :order/user    {:db/valueType :db.type/ref}

   ;; Verb/Associative Entity: Line Item (joins Order and Product)
   :line-item/order    {:db/valueType :db.type/ref}
   :line-item/product  {:db/valueType :db.type/ref}
   :line-item/quantity {:db/valueType :db.type/long}})
```

---

## 5. Summary: Relational Best Practices

1.  **Think in singular namespaces**: `:user/email`, not `:users/emails`.
2.  **Normalize everything**: Use Join Entities for many-to-many relationships or when you need metadata on the link.
3.  **Document as you go**: Use `:db/doc` to encode the "why" behind every attribute.
4.  **Use keywords for enums**: Model your "lookup tables" as entities with `:db/ident`.

By applying these time-tested ER principles, you ensure your Datalevin database remains clean, performant, and understandable as your domain complexity grows.
