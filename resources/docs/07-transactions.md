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

The primary function for writing data is `d/transact!`. It takes a connection and a vector of transactable entities.

### 2.1 Transactable Entities
Datalevin is flexible in how you can express changes. You can transact a list of maps, where each map represents an entity:
```clojure
(d/transact! conn
  [{:user/name "Alice" :user/email "alice@example.com"} ; New entity
   {:db/id 101, :user/active? false}]) ; Update entity 101
```

Or you can provide a list of raw datom vectors `[Entity Attribute Value]`:
```clojure
(d/transact! conn
  [[:db/add -1 :user/name "Alice"]
   [:db/add 101 :user/active? false]])
```

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
As discussed in Chapter 4, **WAL (Write-Ahead Log) mode** dramatically increases write performance by first writing to a sequential log file. This provides the durability of `msync` with much lower latency.

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
