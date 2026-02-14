---
title: "Using Datalevin as a Key–Value Store"
chapter: 6
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 6: Using Datalevin as a Key–Value Store

As explored in Chapter 4, Datalevin is built on a high-performance Key-Value (KV) foundation. While Datalog is powerful for complex queries, sometimes a direct KV interface is faster, simpler, or more appropriate for specific data shapes.

This chapter covers the practical usage of the Datalevin KV API, including sub-database management, transactions, and range scans.

---

## 1. Opening a KV Store

Opening a KV store is distinct from opening a Datalog connection. You specify a directory, and Datalevin initializes the LMDB environment.

```clojure
(require '[datalevin.core :as d])

;; Open the store
(def kv (d/open-kv "/tmp/my-kv-store"))

;; Always remember to close it when the application shuts down
;; (d/close-kv kv)
```

### Options
Common options for `open-kv` include:
- `:mapsize`: The maximum size the database can grow to (in MiB).
- `:max-dbs`: The maximum number of named sub-databases (DBIs).
- `:max-readers`: The maximum number of concurrent reader threads.

---

## 2. Managing Sub-Databases (DBIs)

LMDB supports multiple logical namespaces within a single file, called **DBIs**. In Datalevin, you must "open" a DBI before you can use it.

```clojure
;; Create or open a DBI named "sessions"
(d/open-dbi kv "sessions")

;; List all open DBIs
(d/list-dbis kv)
```

---

## 3. Basic Operations

Datalevin supports typed keys and values. While values are typically EDN (Clojure data), keys can be explicitly typed to ensure correct sorting in the B+Tree.

### 3.1 Putting and Getting Data
```clojure
;; Put a value
;; Signature: [:put dbi-name key value key-type]
(d/transact-kv kv
  [[:put "sessions" "user:123" {:login-at #inst "2026-01-01"} :string]])

;; Get a value
(d/get-value kv "sessions" "user:123")
;; => {:login-at #inst "2026-01-01"}
```

### 3.2 Deleting Data
```clojure
(d/transact-kv kv
  [[:del "sessions" "user:123"]])
```

---

## 4. Range Scans and Prefixes

Because the underlying storage is a sorted B+Tree, range scans are extremely efficient.

```clojure
;; Scan all keys starting with "user:"
(d/get-range kv "sessions" [:prefix "user:"])

;; Scan a closed range
(d/get-range kv "sessions" [:closed "user:100" "user:200"])
```

---

## 5. Transactions

`transact-kv` is atomic. You can perform multiple operations across different DBIs in a single transaction.

```clojure
(d/transact-kv kv
  [[:put "sessions" "u1" {:data 1}]
   [:put "logs" "u1" "logged in"]
   [:del "cache" "u1"]])
```

---

## 6. When to use KV vs. Datalog

| Use Case | Recommended API | Why? |
| :--- | :--- | :--- |
| Complex Joins | **Datalog** | Built-in join optimization. |
| Graph Traversal | **Datalog** | Recursive rules and logic. |
| High-Speed Counters | **KV** | Lower overhead per transaction. |
| Blob/File Storage | **KV** | Direct buffer access, no triple overhead. |
| Session Caching | **KV** | Simple point lookups by ID. |

---

## Summary

The KV API is not a "sidecar" feature; it is the bedrock of Datalevin. By using it directly, you can build specialized storage patterns that complement your Datalog model, all within the same ACID-compliant storage file.
