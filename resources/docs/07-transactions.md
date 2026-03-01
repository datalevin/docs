---
title: "Transactions and Atomic Updates"
chapter: 7
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 7: Transactions and Atomic Updates

Every write to a Datalevin database happens within a **transaction**. Transactions are the cornerstone of database reliability, ensuring that your data moves from one consistent state to another, even in the face of concurrent operations or system crashes. Datalevin provides ACID (Atomicity, Consistency, Isolation, Durability) guarantees, and this chapter explores how.

---

## 1. The Transaction Model

### 1.1 Default Mode: Synchronous LMDB Transactions
In its default (non-WAL) mode, a Datalevin transaction is a direct mapping to an underlying **LMDB transaction**.
- **Atomicity**: All changes within a single `transact!` call are applied as a single, atomic unit. They either all succeed or all fail.
- **Durability**: By default, every transaction is synchronously flushed to disk (`msync`) before it is confirmed. This guarantees that once a transaction is committed, it is durable and will survive a system crash.

### 1.2 Durability Settings
For use cases where maximum write speed is more important than crash-proof durability (e.g., bulk loading, caching), you can relax the `msync` behavior by setting LMDB flags during connection creation. For example, using `:nosync` can significantly improve write throughput at the cost of durability.

---

## 2. Transacting Data

The primary function for writing data is `d/transact!`. It takes a connection and a vector of transactable entities. Datalevin is flexible in how you can express changes.

### 2.1 Entity Maps

The most common way to express an entity is using a map. Each map represents an entity with its attributes and values:

```clojure
(d/transact! conn
  [{:user/name "Alice" :user/email "alice@example.com"}
   {:user/name "Bob" :user/email "bob@example.com"}])
```

When you omit `:db/id`, Datalevin assigns a new unique entity ID automatically.

### 2.2 Updating Existing Entities

To update an existing entity, include its entity ID in the map:

```clojure
(d/transact! conn
  [{:db/id 101, :user/active? false}])
```

This updates the entity with ID 101 to set `:user/active?` to false.

### 2.3 Temporary Entity IDs (Temp Eids)

When creating new entities that will be referenced by other entities in the same transaction, use **temporary entity IDs** (temp eids). These are negative numbers that act as placeholders:

```clojure
(d/transact! conn
  [{:db/id -1, :user/name "Alice" :user/friend -2}
   {:db/id -2, :user/name "Bob"}])
```

In this example, `-1` references `-2` as a friend. Datalevin resolves these temp eids during the transaction, replacing them with real entity IDs.

### 2.4 Lookup Refs

Instead of knowing the entity ID, you can use a **lookup ref** to identify an existing entity by a unique attribute. A lookup ref is a vector `[attribute value]`:

```clojure
(d/transact! conn
  [[[:user/email "alice@example.com"] :user/active? false]])
```

This finds the entity with `:user/email` equal to `"alice@example.com"` and updates it. If no entity exists with that email, the transaction will fail.

Lookup refs are particularly useful when you only have a unique identifier (like an email) but not the entity ID.

### 2.5 Unique Attributes and Upsert

When an attribute is marked as `:db.unique/identity`, Datalevin automatically performs an **upsert**: if an entity with that value exists, it updates that entity; otherwise, it creates a new one:

```clojure
;; First, add a schema with unique identity
(d/transact! conn
  [{:db/ident :user/email
    :db/valueType :db.type/string
    :db/unique :db.unique/identity}])

;; Now upsert based on email
(d/transact! conn
  [{:user/email "alice@example.com" :user/name "Alice v2"}])
```

If `"alice@example.com"` already exists, this updates the existing entity. If not, it creates a new one. This eliminates the need to check for existence before transacting.

### 2.6 Raw Datom Vectors

For fine-grained control, you can express changes as raw datom vectors `[op entity attribute value]`:

```clojure
(d/transact! conn
  [[:db/add -1 :user/name "Alice"]
   [:db/add -1 :user/email "alice@example.com"]
   [:db/retract 101 :user/active? true]])
```

The operations are:
- `:db/add` — adds or updates an attribute value
- `:db/retract` — removes an attribute value

### 2.7 Mixed Forms

You can mix all these forms in a single transaction:

```clojure
(d/transact! conn
  [{:user/email "new@example.com" :user/name "New User"}  ; upsert
   [[:user/email "alice@example.com"] :user/status "active"] ; lookup ref
   {:db/id -1, :user/friend [[:user/email "bob@example.com"]]} ; temp eid + lookup ref
   [:db/add -1 :user/notes "Added via datom vector"]}) ; raw datom
```

This flexibility allows you to choose the most convenient form for each operation in your transaction.

### 2.8 Strong Typing and Data Type Considerations

Datalevin is a **strongly typed** database. It is strongly recommended to give
every attribute value an explicit type defined in the schema (e.g.,
`:db.type/long`, `:db.type/string`, `:db.type/boolean`). When you specify a type
in your schema, Datalevin enforces it. This ensures predictable behavior.

When you transact data without a data type in schema, Datalevin serializes it as
an EDN binary blob. It can sometimes behave unexpectedly:

```clojure
;; Transacting with an explicit int
(d/transact! conn [{:db/id -1, :my-entity/val (int 42)}])

;; Querying with a plain integer literal (defaults to long)
(d/q
  '[:find ?e :in $
    :where [?e :my-entity/val 42]]
  (d/db conn))
;;=> ()  ;; Unexpected! No results because (int 42) ≠ 42
```

The query returns no results because the value was stored as an integer (32-bit)
but the query uses a long (64-bit). They are not equal.

However, if you specify a type for `:my-entity/val`, e.g. `:db.type/long`,
Datalevin does type coercion for you, so your `(int 42)` will be stored as a
`long` instead.

---

## 3. Atomic Read-Modify-Write with `with-transaction`

Often, you need to read a value, modify it, and write it back as a single atomic operation. This is a classic race condition if not handled carefully. Datalevin provides the `with-transaction` macro for this purpose.

```clojure
(d/with-transaction [tx conn]
  (let [current-balance (d/q '[:find ?bal . :where [101 :account/balance ?bal]] (d/db tx))
        new-balance (- current-balance 100)]
    (d/transact! tx [{:db/id 101, :account/balance new-balance}])))
```
The `with-transaction` macro ensures that the reads (e.g., `d/q`) and the write (`d/transact!`) happen within the same isolated transaction, preventing any other concurrent write from interfering.

---

## 4. Transaction Functions

For more complex, reusable logic, you can use **transaction functions**. These are functions that are executed *inside* the transaction, allowing them to be composed with other data.

A transaction function must be a symbol that resolves to a function taking at least one argument (the `db` value).

```clojure
;; Define an upsert function
(defn upsert-user [db email name]
  (if-let [eid (d/q '[:find ?e . :in $ ?email :where [?e :user/email ?email]] db email)]
    [{:db/id eid, :user/name name}]  ; Update existing user
    [{:user/email email, :user/name name}])) ; Insert new user

;; Use it in a transaction
(d/transact! conn `[(upsert-user ~"bob@example.com" "Bob V2")])
```

---

## 5. High-Throughput: WAL and Asynchronous Transactions

While the default synchronous model is extremely safe, it can limit write throughput. For demanding workloads, Datalevin provides two advanced features: WAL mode and asynchronous transactions.

### 5.1 WAL Mode
As discussed in Chapter 4, **WAL (Write-Ahead Log) mode** dramatically increases write performance, especially for concurrent writers. By writing to a sequential log file first, Datalevin can achieve the durability of `msync` with the throughput of an LSM-tree.

- **Durability Profiles**: Choose `:strict` for maximum safety (sync on every commit) or `:relaxed` for maximum throughput (batched syncs).
- **Concurrent Throughput**: WAL allows multiple writer threads to achieve significantly higher aggregate throughput than a single thread.
- **Enabled by Default**: For new Datalog databases, WAL is enabled by default to provide the best balance of safety and performance.

### 5.2 Asynchronous Transactions (`transact-async`)
For the absolute highest throughput, Datalevin offers `d/transact-async`. Instead of waiting for the transaction to be confirmed, this function returns a `Future` immediately.

```clojure
(let [fut (d/transact-async conn [{:user/name "Charlie"}])]
  ;; ... do other work ...
  @fut) ; Dereference the future to wait for completion and get the result
```

### 5.3 Adaptive Batching
Under the hood, `transact-async` uses a powerful **adaptive batching** strategy. It collects multiple concurrent asynchronous transactions and commits them together in batches. The batch size dynamically adjusts based on system load, allowing Datalevin to achieve both high throughput during busy periods and low latency when the system is quiet. This advanced technique is key to Datalevin's best-in-class OLTP performance.

---

## Summary

Datalevin provides a flexible and powerful transaction model that scales from simple, safe synchronous writes to high-performance asynchronous batching. By using tools like `with-transaction` for atomic updates and `transact-async` for high throughput, you can build applications that are both correct and fast.
