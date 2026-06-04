---
title: "Key-Value API Reference"
chapter: 41
part: "IX — Appendices"
---

# Appendix B: Key-Value API Reference

This appendix is a compact reference for public Datalevin KV functions exposed
from `datalevin.core`. For tutorial examples, see Chapter 6. Search, vector,
embedding, and idoc APIs may use KV stores internally, but they are covered in
their own chapters.

All examples assume:

```clojure
(require '[datalevin.core :as d])
```

---

## 1. Store Lifecycle

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `open-kv` | `(d/open-kv dir)`, `(d/open-kv dir opts)` | Open a local KV store or a remote `dtlv://` KV database. |
| `close-kv` | `(d/close-kv db)` | Close a KV store. |
| `closed-kv?` | `(d/closed-kv? db)` | Return true if the KV store is closed. |
| `dir` | `(d/dir db)` | Return the path or URI string for the store. |
| `copy` | `(d/copy db dest)`, `(d/copy db dest compact?)` | Copy a Datalog or KV database to another directory, optionally compacting. |
| `sync` | `(d/sync db)` | Force a synchronous flush to disk. Useful when non-default write flags are used. |

Common `open-kv` options include `:mapsize`, `:max-dbs`, `:max-readers`,
`:temp?`, `:inmemory?`, `:wal?`, `:wal-durability-profile`,
`:wal-group-commit`, `:wal-group-commit-ms`, `:client-opts`, and
`:spill-opts`.

---

## 2. DBI Management

A DBI is an LMDB sub-database inside a KV store. Use regular DBIs for one value
per key and list DBIs for sorted multi-values per key.

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `open-dbi` | `(d/open-dbi db name)`, `(d/open-dbi db name opts)` | Open a regular named DBI. |
| `open-list-dbi` | `(d/open-list-dbi db name)`, `(d/open-list-dbi db name opts)` | Open a DUPSORT/list DBI. |
| `clear-dbi` | `(d/clear-dbi db name)` | Remove data from a DBI but leave the DBI open. |
| `drop-dbi` | `(d/drop-dbi db name)` | Clear and delete a DBI. |
| `list-dbis` | `(d/list-dbis db)` | Return names of sub-databases in the store. |
| `stat` | `(d/stat db)`, `(d/stat db name)` | Return LMDB statistics for the environment or a DBI. |
| `entries` | `(d/entries db name)` | Return the number of entries in a DBI. |

Common DBI options include `:key-size`, `:val-size`, `:validate-data?`,
`:closed-schema?`, and LMDB `:flags`.

---

## 3. Writes and Transactions

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `transact-kv` | `(d/transact-kv db txs)`, `(d/transact-kv db name txs k-type v-type)` | Synchronously apply KV `:put` and `:del` operations. |
| `transact-kv-async` | `(d/transact-kv-async db txs)`, `(d/transact-kv-async db name txs k-type v-type callback)` | Batch asynchronous KV writes; returns a future. |
| `with-transaction-kv` | `(d/with-transaction-kv [tx db] body...)` | Run reads and writes in one explicit KV transaction. |
| `abort-transact-kv` | `(d/abort-transact-kv tx)` | Roll back writes from inside `with-transaction-kv`. |

Transaction items are vectors:

```clojure
[:put dbi-name key value key-type value-type flags]
[:del dbi-name key key-type]
```

`key-type`, `value-type`, and `flags` are optional. When all items target the
same DBI and use the same types, pass `dbi-name`, `key-type`, and `value-type`
as function arguments instead.

---

## 4. Encoding Helpers

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `put-buffer` | `(d/put-buffer bf x)`, `(d/put-buffer bf x x-type)` | Encode a value into a `ByteBuffer`. Mostly useful in raw visitors. |
| `read-buffer` | `(d/read-buffer bf)`, `(d/read-buffer bf v-type)` | Decode a value from a `ByteBuffer`. Mostly useful in raw visitors. |
| `k` | `(d/k kv)` | Return the raw key buffer from an `IKV` visitor object. |
| `v` | `(d/v kv)` | Return the raw value buffer from an `IKV` visitor object. |

Public scalar KV types include `:data`, `:string`, `:long`, `:float`,
`:double`, `:bigint`, `:bigdec`, `:bytes`, `:keyword`, `:symbol`, `:boolean`,
`:instant`, and `:uuid`. Tuple types are vectors of scalar types, such as
`[:string :instant]` or `[:long]`.

---

## 5. Point Reads and Order Statistics

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `get-value` | `(d/get-value db name k)`, `(d/get-value db name k k-type v-type ignore-key?)` | Get one key's value, or `[k v]` when `ignore-key?` is false. |
| `get-rank` | `(d/get-rank db name k)`, `(d/get-rank db name k k-type)` | Return a key's zero-based rank in sorted key order. |
| `get-by-rank` | `(d/get-by-rank db name rank)`, `(d/get-by-rank db name rank k-type v-type ignore-key?)` | Return the value or pair at a zero-based key rank. |
| `sample-kv` | `(d/sample-kv db name n)`, `(d/sample-kv db name n k-type v-type ignore-key?)` | Return random samples from a DBI. |

Rank and sampling functions are backed by DLMDB order-statistics support.

---

## 6. Key Ranges

Range specs are vectors such as `[:all]`, `[:closed from to]`, or
`[:greater-than k]`. Reverse variants add `-back`, for example `:all-back` and
`:closed-back`.

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `get-first` | `(d/get-first db name k-range)`, `(d/get-first db name k-range k-type v-type ignore-key?)` | Return the first value or pair in a key range. |
| `get-first-n` | `(d/get-first-n db name n k-range)`, `(d/get-first-n db name n k-range k-type v-type ignore-key?)` | Return the first `n` values or pairs in a key range. |
| `get-range` | `(d/get-range db name k-range)`, `(d/get-range db name k-range k-type v-type ignore-key?)` | Eagerly return values or pairs in a key range. |
| `range-seq` | `(d/range-seq db name k-range)`, `(d/range-seq db name k-range k-type v-type ignore-key? opts)` | Lazily stream a key range in batches; close it with `with-open`. |
| `key-range` | `(d/key-range db name k-range)`, `(d/key-range db name k-range k-type)` | Return only keys in a range. |
| `range-count` | `(d/range-count db name k-range)`, `(d/range-count db name k-range k-type)` | Count KV pairs in a key range without materializing them. |
| `key-range-count` | `(d/key-range-count db name k-range)`, `(d/key-range-count db name k-range k-type cap)` | Count keys in a range, optionally stopping at `cap`. |
| `key-range-list-count` | `(d/key-range-list-count db name k-range k-type)`, `(d/key-range-list-count db name k-range k-type cap)` | Count list items across keys in a list DBI key range. |

Use `get-range` when the result is known to be bounded. Use `range-seq` for
large scans, and always close the returned sequence.

---

## 7. Range Predicates and Visitors

Predicate and visitor functions can receive either raw `IKV` objects or decoded
values. The `raw-pred?` argument controls this. Raw mode is the default and is
best when you want to avoid decoding values that may be skipped.

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `get-some` | `(d/get-some db name pred k-range ...)` | Return the first KV pair whose predicate is true. |
| `range-filter` | `(d/range-filter db name pred k-range ...)` | Eagerly return KV pairs whose predicate is true. |
| `range-keep` | `(d/range-keep db name pred k-range ...)` | Return non-nil predicate results over a key range. |
| `range-some` | `(d/range-some db name pred k-range ...)` | Return the first logical true predicate result. |
| `range-filter-count` | `(d/range-filter-count db name pred k-range ...)` | Count KV pairs whose predicate is true. |
| `visit-key-range` | `(d/visit-key-range db name visitor k-range ...)` | Visit only keys in a key range for side effects. |
| `visit` | `(d/visit db name visitor k-range ...)` | Visit KV pairs in a key range for side effects. |

Visitors may return `:datalevin/terminate-visit` to stop a scan early.
For server usage, define predicates and visitors with
`datalevin.interpret/inter-fn`. For Babashka pod usage, use `defpodfn`.

---

## 8. List DBI Operations

List DBIs are opened with `open-list-dbi`. Each key maps to a sorted set of
values. List items must fit within the same 511-byte limit that applies to LMDB
keys.

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `put-list-items` | `(d/put-list-items db name k vs k-type v-type)` | Add multiple sorted values under one key. |
| `del-list-items` | `(d/del-list-items db name k k-type)`, `(d/del-list-items db name k vs k-type v-type)` | Delete a whole list or selected list items. |
| `get-list` | `(d/get-list db name k k-type v-type)` | Return all values for one key. |
| `visit-list` | `(d/visit-list db name visitor k k-type v-type raw-pred?)` | Visit values for one key. |
| `list-count` | `(d/list-count db name k k-type)` | Count values associated with one key. |
| `in-list?` | `(d/in-list? db name k v k-type v-type)` | Test whether one value is present under a key. |

List DBI value ranges use both a key range and a value range:

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `list-range` | `(d/list-range db name k-range k-type v-range v-type)` | Return key/value pairs matching both ranges. |
| `list-range-count` | `(d/list-range-count db name k-range k-type)` | Approximate count across a key range; ignores value range boundaries. |
| `list-range-first` | `(d/list-range-first db name k-range k-type v-range v-type)` | Return the first matching list item. |
| `list-range-first-n` | `(d/list-range-first-n db name n k-range k-type v-range v-type)` | Return the first `n` matching list items. |
| `list-range-filter` | `(d/list-range-filter db name pred k-range k-type v-range v-type raw-pred?)` | Filter list range results with a predicate. |
| `list-range-keep` | `(d/list-range-keep db name pred k-range k-type v-range v-type raw-pred?)` | Return non-nil predicate results over list range matches. |
| `list-range-filter-count` | `(d/list-range-filter-count db name pred k-range k-type v-range v-type raw-pred?)` | Count list range matches whose predicate is true. |
| `list-range-some` | `(d/list-range-some db name pred k-range k-type v-range v-type raw-pred?)` | Return the first logical true predicate result. |
| `visit-list-range` | `(d/visit-list-range db name visitor k-range k-type v-range v-type raw-pred?)` | Visit list range matches for side effects. |

---

## 9. WAL and Snapshots

These functions apply to KV stores opened with WAL support where appropriate.

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `txlog-watermarks` | `(d/txlog-watermarks db)` | Return WAL watermarks and runtime sync state. |
| `open-tx-log` | `(d/open-tx-log db from-lsn)`, `(d/open-tx-log db from-lsn upto-lsn)` | Read committed WAL records from an LSN range. |
| `create-snapshot!` | `(d/create-snapshot! db)` | Create or rotate LMDB snapshots and update WAL snapshot bookkeeping. |
| `list-snapshots` | `(d/list-snapshots db)` | Return available snapshot metadata. |
| `gc-txlog-segments!` | `(d/gc-txlog-segments! db)`, `(d/gc-txlog-segments! db retain-floor-lsn)` | Garbage-collect WAL segments, optionally retaining records from a floor LSN. |

---

## 10. Environment Flags

| Function | Common forms | Purpose |
| :--- | :--- | :--- |
| `set-env-flags` | `(d/set-env-flags db flags on?)` | Set or clear LMDB environment flags. |
| `get-env-flags` | `(d/get-env-flags db)` | Return currently active LMDB environment flags. |

Common flags include `:rdonly-env`, `:nosync`, `:nometasync`, `:writemap`,
`:mapasync`, `:notls`, `:nolock`, `:nordahead`, `:nomeminit`, `:nosubdir`, and
`:fixedmap`.

---

## 11. Range Keywords

| Range type | Meaning |
| :--- | :--- |
| `:all` / `:all-back` | Whole keyspace, forward or backward. |
| `:at-least` / `:at-most-back` | Inclusive lower bound, forward or backward. |
| `:at-most` / `:at-least-back` | Inclusive upper bound, forward or backward. |
| `:closed` / `:closed-back` | Inclusive lower and upper bounds. |
| `:closed-open` / `:closed-open-back` | Inclusive lower bound, exclusive upper bound. |
| `:open-closed` / `:open-closed-back` | Exclusive lower bound, inclusive upper bound. |
| `:open` / `:open-back` | Exclusive lower and upper bounds. |
| `:greater-than` / `:less-than-back` | Exclusive lower bound. |
| `:less-than` / `:greater-than-back` | Exclusive upper bound. |

The same range shapes are used for key ranges and list value ranges.
