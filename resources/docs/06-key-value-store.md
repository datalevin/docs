---
title: "Using Datalevin as a Key–Value Store"
chapter: 6
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 6: Using Datalevin as a Key–Value Store

As explored in Chapter 4, Datalevin is built on a high-performance Key-Value (KV) foundation. While Datalog is powerful for complex queries, sometimes a direct KV interface is faster, simpler, or more appropriate for specific data shapes.

This chapter covers the practical usage of the Datalevin KV API, including public data types, sub-database management, transactions, and range scans.

---

## 1. Opening a KV Store

Opening a KV store is distinct from opening a Datalog connection. You specify a
directory location when calling `open-kv`, and Datalevin initializes the LMDB
environment there. The directory needs not to exists already, but the permission
to create it is needed.

```clojure
(require '[datalevin.core :as d])

;; Open the store
(def kv (d/open-kv "/tmp/my-kv-store"))

;; KV ops ...

;; Always remember to close it when the application shuts down
(d/close-kv kv)

```

### Options

The last parameter of `open-kv` can be an option map. There are many options.
Some common options for `open-kv` include:
- `:mapsize`: The maximum size the database can grow to (in MiB).
- `:max-dbs`: The maximum number of named sub-databases (DBIs).
- `:max-readers`: The maximum number of concurrent reader threads.
- `:wal?`: Set to `true` to enable high-throughput WAL mode that benefits from
  concurrent writers.
- `:temp?`: Set to `true` to create a temporary store that is deleted on JVM exit. It automatically enables `:nosync`, bypassing the `msync` overhead.
- `:inmemory`: Set to `true` to create a KV store in memory. There is no file persistence
  and data is lost on close. This is even faster than a `:temp?` store.

`:temp?` or `:inmemory` stores are ideal for ephemeral data like session caches,
intermediate computation results, or high-speed buffers.

```clojure
;; Open an in-memory KV store (no file persistence), directory can be nil
(def mem-kv (d/open-kv nil {:inmemory true}))

```

---

## 2. Sub-Databases (DBIs) and DUPSORT

Datalevin allows multiple KV sub-databases (DBIs) to reside in the same KV
store. Each DBI requires a unique string name. A DBI needs to be opened
before use, and DBI opening is idempotent, i.e. it is OK to open a DBI multiple
times.

There are two types of DBI:

### 3.1 Regular DBI

A standard KV mapping where one key points to exactly one value, and this type
of DBI is opened with `open-dbi`.

```clojure
;; Open a regular dbi called "people" in kv store
(d/open-dbi kv "people")
```

### 3.2 List DBI

Leverages LMDB's `DUPSORT` feature. A single key can map to **multiple sorted
values** (i.e. a list). This is effectively a "B+Tree of B+Trees.". This type of
DBI is opened with `open-list-dbi`.

```clojure
;; Open a list-dbi (DUPSORT DBI) called "tags"
(d/open-list-dbi kv "tags")
```
---

## 3. KV Operations

### 3.1 Transaction

Data are transacted to a KV store using `transact-kv` function.

```clojure
;; Add multiple values to the same key in a list dbi
(d/transact-kv kv
  [[:put "tags" "clojure" "fast" :string]
   [:put "tags" "clojure" "expressive" :string]])
```

The transaction data is a sequence of transaction item, which is a vector of
[operation, DBI name, key, value, key type, and value type]. The operation can
be `:put` or `:del`.

### 3.2 Point query

The value of a key can be retrieved with `get-value`. If the DBI is a list DBI, the list of a
key can be retrieved with `get-list`. These are single key point queries.

```clojure
;; Get all values for a key
(d/get-list kv "tags" "clojure")
;; => ("expressive" "fast") ; sorted values
```

### 3.3 Range query

Because Datalevin keeps keys (and DUPSORT values) sorted, you can perform highly efficient range scans. Datalevin provides a rich set of range keywords that specify how the scan should start, end, and in which direction it should move.

A **range** is specified as a vector: `[range-type start-value end-value]`.

| Range Type | Direction | Bounds |
| :--- | :--- | :--- |
| `:all` / `:all-back` | Forward / Backward | Entire keyspace. |
| `:at-least` / `:at-most-back` | Forward / Backward | Inclusive start. |
| `:at-most` / `:at-least-back` | Forward / Backward | Inclusive end. |
| `:closed` / `:closed-back` | Forward / Backward | Inclusive start, inclusive end. |
| `:closed-open` / `:closed-open-back` | Forward / Backward | Inclusive start, exclusive end. |
| `:open-closed` / `:open-closed-back` | Forward / Backward | Exclusive start, inclusive end. |
| `:open` / `:open-back` | Forward / Backward | Exclusive start, exclusive end. |
| `:greater-than` / `:less-than-back` | Forward / Backward | Exclusive start. |
| `:less-than` / `:greater-than-back` | Forward / Backward | Exclusive end. |

*Note: `-back` variants traverse the B+Tree in reverse order.*

There are many range query functions:

- `(d/get-range kv dbi range key-type)`: Returns a sequence of KV pairs.
- `(d/range-count kv dbi range key-type)`: Efficiently counts items in a range (O(log N) in DLMDB).
- `(d/range-filter kv dbi range pred key-type)`: Scans a range and applies a predicate.
- `(d/range-seq kv dbi range key-type)`: Returns a lazy sequence of the range.


### 3.4 Other Data Access Functions

Beyond point and range queries, Datalevin provides several specialized functions for the KV layer:

| Function | Purpose |
| :--- | :--- |
| `get-first` | Get the very first key-value pair in a DBI. |
| `get-first-n` | Get the first N key-value pairs in a DBI. |
| `get-rank` | Find the numerical rank (position) of a key in the sorted order. |
| `get-by-rank` | Retrieve the key at a specific numerical index. |
| `sample-kv` | Take a random sample of KV pairs from a DBI. |

---

## 4 Data Types

While LMDB deals with raw bytes, Datalevin adds a layer of encoded data types to ensure correct sorting and efficient storage. These types can be specified as `key-type` or `val-type` in KV operations.

### 4.1 Scalar types

| Type Keyword | Clojure/Java Type | Notes |
| :--- | :--- | :--- |
| `:data` | Any EDN data | Default. Stored as opaque binary; **not sortable** for range queries. |
| `:string` | `String` | UTF-8 encoded, lexicographically sorted. |
| `:long` | `Long` (64-bit) | Numerically sorted. |
| `:id` | `Long` (64-bit) | Specialized ID encoding used for Entity IDs. |
| `:int` | `Integer` (32-bit) | Numerically sorted. |
| `:float` | `Float` (32-bit) | IEEE 754 floating point. |
| `:double` | `Double` (64-bit) | IEEE 754 floating point. |
| `:boolean` | `Boolean` | |
| `:instant` | `java.util.Date` | Chronologically sorted. |
| `:uuid` | `java.util.UUID` | |
| `:bytes` | `byte[]` | Raw binary payloads. |

### 4.2 Tuple types


### 4.3 Important Constraints: Key Size Limit

LMDB (and thus Datalevin) has a hard limit on the size of a key. In Datalevin, the maximum key size is **511 bytes**.
- **Impact on Tuples**: When using composite keys (tuples), the sum of the encoded parts must stay within this 511-byte limit.
- **Impact on List-DBIs (DUPSORT)**: In a `list-dbi`, the values are stored in a secondary B+Tree where they essentially act as keys. Therefore, **each value in a list-dbi is also subject to the 511-byte limit**.
- **Large Values**: Standard (non-list) DBI values are not subject to this limit and can grow up to 2GB.

---

## 5. Explicit Transaction `with-transaction-kv`

`transact-kv` is the standard way to write data. However, for complex logic involving reads and writes that must be atomic, use the `with-transaction-kv` macro.

```clojure
(d/with-transaction-kv [tx kv]
  (let [current (or (d/get-value tx "counters" "hits") 0)
        new-val (inc current)]
    (d/transact-kv tx
      [[:put "counters" "hits" new-val]])))
```

This macro ensures that:
1. All reads and writes inside the block share the same transaction snapshot.
2. The transaction is automatically committed at the end of the block.
3. If an exception occurs, the transaction is safely aborted.

---

## Summary

The Datalevin KV API is a robust, typed, and highly performant alternative to the Datalog API. By leveraging **typed keys**, **DUPSORT (list-DBIs)**, and **order statistics (rank functions)**, you can build specialized data structures that operate with the same ACID guarantees as the rest of the database.
