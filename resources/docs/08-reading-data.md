---
title: "Reading Data: Queries and the Pull API"
chapter: 8
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 8: Reading Data: Queries and the Pull API

Once data is in your database, you need a way to retrieve it. Datalevin provides two powerful, complementary mechanisms for reading data: `d/q` for Datalog queries, and `d/pull` for retrieving document-like data structures.

This chapter focuses on the **Pull API**, a convenient way to fetch the data for a specific entity, especially when it involves nested relationships.

---

## 1. Introducing the Pull API

While `d/q` is designed to find a *set* of entities that match a complex query, `d/pull` is designed to retrieve a rich, hierarchical document for a *single* entity whose ID you already know.

The basic syntax is:
```clojure
(d/pull db pattern eid)
```
- **`db`**: The database value.
- **`pattern`**: A vector describing which attributes to retrieve.
- **`eid`**: The entity ID to pull data for.

---

## 2. Basic Pull and the Wildcard Pattern

The simplest way to use `d/pull` is with the wildcard `[*]` pattern, which retrieves all attributes for a given entity.

```clojure
;; Assuming entity 101 has :user/name and :user/email attributes
(d/pull (d/db conn) '[*] 101)
```

**Result:**
```clojure
{:db/id 101,
 :user/name "Alice",
 :user/email "alice@example.com"}
```
The result is a map, which is often much more convenient to work with in application code than the set of tuples returned by `d/q`.

---

## 3. The Power of Pull: Nested Data

The true strength of the Pull API is its ability to traverse relationships and create nested data structures automatically. This is something that is difficult to achieve with a single Datalog query.

Imagine you have an order that refers to a customer:
```clojure
(d/transact! conn
  [{:db/id 1, :user/name "Alice"}
   {:order/id "o123", :order/customer 1}]) ; :order/customer is a ref
```

You can pull the order and "expand" the customer information in one go:
```clojure
(d/pull (d/db conn)
        '[:order/id {:order/customer [:user/name]}]
        [:order/id "o123"])
```

**Result:**
```clojure
{:order/id "o123",
 :order/customer {:user/name "Alice"}}
```
The Pull API automatically followed the `:order/customer` reference and pulled the specified attributes for that entity, nesting the result. This is a level of convenience `d/q` does not provide.

---

## 4. Performance: Pull vs. Query in Datalevin

In some Datalog databases (like Datomic or Datascript), the Pull API can be significantly faster than `d/q` for certain access patterns because it can use direct index lookups, bypassing the query engine.

In Datalevin, this is **not necessarily the case**. 
- Datalevin's query engine is highly optimized, and many `d/pull` operations can be expressed as an equally fast (or even faster) `d/q` query.
- A `d/pull` on a complex, nested pattern might internally perform several lookups.

Therefore, in Datalevin, you should think of the Pull API primarily as a **tool for convenience and shaping data**, not as a performance optimization.
- **Use `d/q`** when you need to find entities that match complex criteria.
- **Use `d/pull`** when you have an entity ID and need to retrieve its data in a specific, possibly nested, shape.

---

## 5. Advanced Pull Patterns

The Pull API supports several advanced patterns for fine-grained control over the data shape.

### 5.1 Reverse Lookups
You can find all entities that *refer to* the current entity. The syntax is `_attribute`.

```clojure
;; Find all orders placed by Alice (entity 1)
(d/pull (d/db conn)
        '[:user/name :_order/customer] ; a "reverse" lookup
        1)
```
**Result:**
```clojure
{:user/name "Alice",
 :_order/customer [{:order/id "o123"}]}
```

### 5.2 Limiting `:cardinality/many` Attributes
You can limit the number of results for a multi-valued attribute:
```clojure
;; Pull the first 5 tags for an entity
(pull db '[(:user/tags :limit 5)] eid)
```

### 5.3 Default Values
You can provide a default value if an attribute is not present:
```clojure
;; If :user/active? is missing, return true
(pull db '[:user/name (:user/active? :default true)] eid)
```

---

## Summary

The Pull API is an essential tool for reading data from Datalevin. While `d/q` is your tool for discovery, `d/pull` is your tool for retrieval. Its unique ability to fetch nested hierarchical data makes it an invaluable convenience for building applications, even if it doesn't offer the same performance guarantees over `d/q` as in other Datalog systems.
