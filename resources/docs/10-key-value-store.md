---
title: "Using Datalevin as a Key–Value Store"
chapter: 10
part: "II — Core APIs: Datalog First, KV When Needed"
---

# Chapter 10: Using Datalevin as a Key–Value Store

As explored in Chapter 4, Datalevin is built on a high-performance Key-Value
(KV) foundation. While Datalog is powerful for complex queries, sometimes a
direct KV interface is faster, simpler, or more appropriate for specific data
shapes.

This chapter covers the practical usage of the Datalevin KV API, including
sub-database management, public data types, transactions, and range scans.

Because Datalog is the main focus of this book, Part II introduces
transactions, read APIs, Datalog, and rules before returning to direct KV
access. Treat this chapter as the direct-storage chapter you reach for when you
need sorted-key access, custom indexes, list DBIs, scripting primitives, or
performance-critical storage structures. For a compact list of all public KV
functions, see Appendix B.

---

## 1. Opening a KV Store

Opening a KV store is distinct from opening a Datalog connection. You specify a
directory location when calling `open-kv`, and Datalevin initializes the LMDB
environment there. The directory does not need to exist already, but the process
must have permission to create it.

Although you open a directory, the main LMDB data lives in a single
memory-mapped data file inside that directory, conventionally `data.mdb`.
Auxiliary files, such as `lock.mdb`, track reader/lock state, and Datalevin may
create additional support files for features such as WAL. DBIs are named logical
sub-databases inside the same store; opening more DBIs does not create one data
file per DBI.

Like a Datalog connection, a KV handle is a stateful resource. A normal
application should open one KV handle for a local store, share it with the code
that needs direct KV access, and close it during application shutdown. Do not
open and close the same local KV store repeatedly for individual operations or
requests.

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

;; Open the store
(def kv (d/open-kv "/tmp/my-kv-store"))

;; KV ops ...

;; Always remember to close it when the application shuts down
(d/close-kv kv)
```

```java
import datalevin.Datalevin;
import datalevin.KV;

// Open the store
try (KV kv = Datalevin.openKV("/tmp/my-kv-store")) {
    // KV ops ...
}
```

```python
from datalevin import open_kv

# Open the store
with open_kv("/tmp/my-kv-store") as kv:
    # KV ops ...
```

```javascript
import { openKv } from "datalevin-node";

// Open the store
const kv = await openKv("/tmp/my-kv-store");
try {
  // KV ops ...
} finally {
  await kv.close();
}
```

</div>

### Options

The last parameter of `open-kv` can be an option map. There are many options.
Some common options for `open-kv` include:

- `:mapsize`: The maximum size the database can grow to (in MiB).
- `:max-dbs`: The maximum number of named sub-databases (DBIs).
- `:max-readers`: The maximum number of concurrent reader threads. The current default is 1024.
- `:wal?`: Set to `true` to enable WAL mode that benefits from
  concurrent writers. WAL is **disabled by default** for both local KV stores
  and local embedded Datalog stores. Non-HA async read replicas require WAL on
  the primary; consensus-lease HA enables WAL automatically.
- `:wal-durability-profile`: Choose between `:strict` (standard `fsync`),
  `:relaxed` (batched syncs), and `:extra` (e.g., `F_FULLSYNC` on macOS).
  Local WAL opt-in defaults to `:relaxed`; HA defaults to `:strict`.
- `:wal-retention-bytes` and `:wal-retention-ms`: Set policies for how long to keep
  old WAL segments.
- `:temp?`: Set to `true` to create a temporary store that is deleted on JVM
  exit. It automatically enables `:nosync`, bypassing the `msync` overhead.
- `:inmemory?`: Set to `true` to create a KV store in memory. There is no file persistence
  and data is lost on close. This is even faster than a `:temp?` store.
- `:spill-opts`: Control when eager range APIs spill large intermediate results
  to temporary disk storage. Common keys are `:spill-threshold`, a heap-pressure
  percentage, and `:spill-root`, the directory for temporary spill files. Set
  `:spill-threshold` to `100` to disable spill-to-disk.

`:temp?` or `:inmemory?` stores are ideal for ephemeral data like session caches,
intermediate computation results, or high-speed buffers.

<div class="multi-lang">

```clojure
;; Open an in-memory KV store (no file persistence), directory can be nil
(def mem-kv (d/open-kv nil {:inmemory? true}))
```

```java
// Open an in-memory KV store (no file persistence), directory can be null
KV memKv = Datalevin.openKV(null, Map.of("inmemory?", true));
```

```python
# Open an in-memory KV store (no file persistence), directory can be None
mem_kv = open_kv(None, opts={":inmemory?": True})
```

```javascript
// Open an in-memory KV store (no file persistence), directory can be null
const memKv = await openKv(null, { ":inmemory?": true });
```

</div>

---

## 2. Sub-Databases (DBIs) and DUPSORT

Datalevin allows multiple KV sub-databases (DBIs) to reside in the same KV
store. Each DBI requires a unique string name. A DBI needs to be opened
before use, and DBI opening is idempotent, i.e. it is OK to open a DBI multiple
times.

A KV transaction is scoped to one store. It may write to several DBIs in that
store atomically, because they share the same underlying LMDB transaction. It
cannot span two different KV stores or database files.

The same file can contain Datalevin's Datalog DBIs and application-defined KV
DBIs. Use distinct names for your KV DBIs and do not write directly to internal
Datalog DBIs such as `datalevin/eav` or `datalevin/ave`; those are maintained
by the Datalog engine.

There are two types of DBI:

### 2.1 Regular DBI

A standard KV mapping where one key points to exactly one value, and this type
of DBI is opened with `open-dbi`.

<div class="multi-lang">

```clojure
;; Open a regular dbi called "people" in kv store
(d/open-dbi kv "people")
```

```java
// Open a regular dbi called "people" in kv store
kv.openDbi("people");
```

```python
# Open a regular dbi called "people" in kv store
kv.open_dbi("people")
```

```javascript
// Open a regular dbi called "people" in kv store
await kv.openDbi("people");
```

</div>

### 2.2 List DBI

Leverages LMDB's `DUPSORT` feature. A single key can map to **multiple sorted
values** (i.e. a list). This is effectively a "B+Tree of B+Trees." This type of
DBI is opened with `open-list-dbi`.

<div class="multi-lang">

```clojure
;; Open a list-dbi (DUPSORT DBI) called "tags"
(d/open-list-dbi kv "tags")
```

```java
// Open a list-dbi (DUPSORT DBI) called "tags"
kv.openListDbi("tags");
```

```python
# Open a list-dbi (DUPSORT DBI) called "tags"
kv.open_list_dbi("tags")
```

```javascript
// Open a list-dbi (DUPSORT DBI) called "tags"
await kv.openListDbi("tags");
```

</div>

---

## 3. Data Types

While LMDB deals with raw bytes, Datalevin adds a layer of encoded data types to
ensure correct sorting and efficient storage. These types can be specified as
`key-type` or `val-type` in KV operations.

KV type validation is separate from Datalog schema validation. In Datalog,
`:db/valueType` belongs to an attribute schema. In the KV API, types are passed
as operation descriptors or DBI options for keys and values. A DBI opened with
`:validate-data? true` checks KV writes against those key/value types instead
of checking Datalog attributes.

This section comes before transactions because KV writes are not just "put this
Clojure value somewhere." A write must also tell Datalevin how to encode the
key, and usually how to encode the value. The encoding determines equality,
ordering, range-scan behavior, and whether a value can fit in an LMDB key
position.

### 3.1 Scalar types

| Type Keyword | Clojure/Java Type | Notes |
| :--- | :--- | :--- |
| `:data` | Any EDN data | Default. Stored as opaque binary; **not sortable** for range queries. |
| `:string` | `String` | UTF-8 encoded, lexicographically sorted. |
| `:long` | `Long` (64-bit) | Numerically sorted. |
| `:id` | `Long` (64-bit) | Specialized ID encoding used for Entity IDs. |
| `:int` | `Integer` (32-bit) | Numerically sorted. |
| `:float` | `Float` (32-bit) | IEEE 754 floating point. |
| `:double` | `Double` (64-bit) | IEEE 754 floating point. |
| `:bigint` | `java.math.BigInteger` | Arbitrary-precision integer within Datalevin's encoded range; numerically sorted. |
| `:bigdec` | `java.math.BigDecimal` | Arbitrary-precision decimal within Datalevin's encoded range; numerically sorted. |
| `:keyword` | `clojure.lang.Keyword` | EDN keyword, sorted by encoded namespace/name. |
| `:symbol` | `clojure.lang.Symbol` | EDN symbol, sorted by encoded namespace/name. |
| `:boolean` | `Boolean` | |
| `:instant` | `java.util.Date` | Chronologically sorted. |
| `:uuid` | `java.util.UUID` | |
| `:bytes` | `byte[]` | Raw binary payloads. |

### 3.2 Tuple types

Tuple types are composite KV type descriptors. They are used when a key or
DUPSORT value needs to sort by more than one field, such as
`[customer-id created-at]` or `[tenant-id order-total order-id]`.

This is different from Datalog schema `:db.type/tuple`. In the KV API, a tuple
type is written as a vector of scalar KV types:

| Type Descriptor | Meaning |
| :--- | :--- |
| `[:string :instant]` | Heterogeneous tuple: exactly two elements, first a string, second an instant. |
| `[:keyword :long :string]` | Heterogeneous tuple: exactly three elements with the listed types. |
| `[:long]` | Homogeneous tuple: any number of elements, all encoded as longs. |

Tuple values are ordinary vectors whose shape must match the type descriptor.
Heterogeneous tuple descriptors are fixed arity: `[:string :instant]` matches
`["acct-42" #inst "2026-05-31T00:00:00.000-00:00"]`. Homogeneous descriptors
have one element type and can encode vectors such as `[2026 5 31]`.

Datalevin encodes typed KV keys and values into byte buffers internally. Tuple
elements must use encodings that preserve the intended sort order in those
buffers: `:string`, `:long`, `:float`, `:double`, `:bigint`, `:bigdec`,
`:bytes`, `:keyword`, `:symbol`, `:boolean`, `:instant`, and `:uuid`. Do not
use `:data` inside a tuple; arbitrary EDN data is not a sortable tuple
component. For numeric IDs inside tuples, use `:long`.

Tuples sort lexicographically by their encoded elements: first element first,
then the second element to break ties, and so on. Put the field you most often
range over or group by at the front of the tuple.

```clojure
(def event-key-type [:string :instant])

(d/open-dbi kv "events")

(d/transact-kv kv
  [[:put "events"
    ["acct-42" #inst "2026-05-31T10:00:00.000-00:00"]
    {:event/type :login}
    event-key-type
    :data]
   [:put "events"
    ["acct-42" #inst "2026-05-31T12:00:00.000-00:00"]
    {:event/type :purchase}
    event-key-type
    :data]])

;; Scan one account for a time window.
(d/get-range kv
  "events"
  [:closed
   ["acct-42" #inst "2026-05-31T00:00:00.000-00:00"]
   ["acct-42" #inst "2026-06-01T00:00:00.000-00:00"]]
  event-key-type)
```

In list DBIs, tuple values are useful for sorted secondary lists:

```clojure
(def score-type [:double :string])

(d/open-list-dbi kv "scores-by-board")

(d/transact-kv kv
  [[:put "scores-by-board" "daily" [98.5 "alice"] :string score-type]
   [:put "scores-by-board" "daily" [87.0 "bob"] :string score-type]])

(d/get-list kv "scores-by-board" "daily" :string score-type)
;; => ([87.0 "bob"] [98.5 "alice"])
```

Each tuple element can encode to at most 255 bytes. If the tuple is used as an
LMDB key, the whole encoded key must still fit within Datalevin's 511-byte key
limit.

### 3.3 Important Constraints: Key Size Limit

LMDB (and thus Datalevin) has a hard limit on the size of a key. In Datalevin,
the maximum key size is **511 bytes**.

- **Impact on Tuples**: When using composite keys (tuples), the sum of the
  encoded parts must stay within this 511-byte limit.
- **Impact on List-DBIs (DUPSORT)**: In a `list-dbi`, the values are stored in a
  secondary B+Tree where they essentially act as keys. Therefore, **each value
  in a list-dbi is also subject to the 511-byte limit**.
- **Large Values**: Standard (non-list) DBI values are not subject to this limit
  and can grow up to 2GB.

---

## 4. KV Operations

### 4.1 Transactions

Data is written to a KV store with `transact-kv`. One call applies a batch of
operations atomically: either all items commit or none of them do. A single KV
transaction may write several DBIs in the same store, but it cannot span two
different stores or database files.

Transaction items are vectors. There are two common shapes.

The **self-contained shape** is used with `(d/transact-kv kv txs)`. Each item
names the DBI and may carry its own key and value types:

```clojure
[:put dbi-name key value]
[:put dbi-name key value key-type value-type]
[:put dbi-name key value key-type value-type flags]
[:del dbi-name key]
[:del dbi-name key key-type]
```

When `key-type` or `value-type` is omitted, Datalevin uses `:data`. That is fine
for opaque EDN values, but it is usually the wrong choice for keys you plan to
scan by range. Use explicit sortable types such as `:string`, `:long`,
`:instant`, or tuple descriptors for range-oriented DBIs.

The optional `flags` slot applies to `:put` operations. It is a set of LMDB
write flags for specialized cases:

| Flag | Meaning |
| :--- | :--- |
| `:append` | Hint that keys are inserted in sorted order. This can speed up bulk loading, but use it only when the input order is correct. |
| `:appenddup` | Like `:append`, but for sorted duplicate values in a `DUPSORT`/list DBI. |
| `:nooverwrite` | Do not overwrite an existing key. The write fails if the key already exists. |
| `:nodupdata` | In a duplicate-sorted DBI, do not add the same key/value pair twice. |
| `:current`, `:reserve`, `:multiple` | Low-level LMDB flags for cursor replacement, reserved buffers, and batched duplicate values. Most application code should not need them. |

Most transactions should omit `flags`. Reach for them only when you know the
LMDB write-mode semantics you need, especially for trusted bulk loading.

<div class="multi-lang">

```clojure
;; Add multiple values to the same key in a list dbi
(d/transact-kv kv
  [[:put "tags" "clojure" "fast" :string :string]
   [:put "tags" "clojure" "expressive" :string :string]])
```

```java
// Add multiple values to the same key in a list dbi
kv.transact(List.of(
    List.of(Datalevin.kw("put"), "tags", "clojure", "fast", "string", "string"),
    List.of(Datalevin.kw("put"), "tags", "clojure", "expressive", "string", "string")));
```

```python
# Add multiple values to the same key in a list dbi
kv.transact([
    [":put", "tags", "clojure", "fast", ":string", ":string"],
    [":put", "tags", "clojure", "expressive", ":string", ":string"]])
```

```javascript
// Add multiple values to the same key in a list dbi
await kv.transact([
  [":put", "tags", "clojure", "fast", ":string", ":string"],
  [":put", "tags", "clojure", "expressive", ":string", ":string"]
]);
```

</div>

The **single-DBI shape** is used when every item targets the same DBI and uses
the same key/value types. Pull the DBI name and types out to the function call,
then each item contains only the operation, key, and value:

```clojure
(d/transact-kv kv
  "people"
  [[:put 1 "Alice"]
   [:put 2 "Bob"]
   [:del 3]]
  :long
  :string)
```

This call is equivalent to:

```clojure
(d/transact-kv kv
  [[:put "people" 1 "Alice" :long :string]
   [:put "people" 2 "Bob" :long :string]
   [:del "people" 3 :long]])
```

Use the self-contained shape when a batch touches multiple DBIs or mixed data
types. Use the single-DBI shape when you are bulk-writing one typed DBI; it is
shorter, clearer, and avoids repeating the same descriptors on every item.

For list DBIs, `:put` adds one sorted value under the key. To add or delete many
list values under one key, the dedicated `put-list-items` and `del-list-items`
helpers are often clearer; Appendix B lists the full list-DBI API.

### 4.2 Point query

The value of a key can be retrieved with `get-value`. If the DBI is a list DBI,
the list of a key can be retrieved with `get-list`. These are single key point
queries.

<div class="multi-lang">

```clojure
;; Get all values for a key
(d/get-list kv "tags" "clojure" :string :string)
;; => ("expressive" "fast") ; sorted values
```

```java
// Get all values for a key
List<?> values = kv.getList("tags", "clojure", "string", "string");
// => ["expressive", "fast"] ; sorted values
```

</div>

### 4.3 Range query

Because Datalevin keeps keys (and DUPSORT values) sorted, you can perform highly
efficient range scans. Datalevin provides a rich set of range keywords that
specify how the scan should start, end, and in which direction it should move.

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

Technically, one DBI can contain keys encoded with different KV data types.
Datalevin uses that capability internally in several places. For application
DBIs, avoid mixed key types unless you have a deliberate encoding plan. Range
queries operate on the encoded byte ordering, so a DBI that mixes strings,
numbers, keywords, tuples, and raw data can produce ranges that are surprising
to read and hard to maintain. In most application DBIs, pick one key type, or
one tuple key type, and use it consistently.

`get-range` and `range-filter` are eager APIs: they try to realize the requested
range as a result collection. For very large ranges, Datalevin's spillable
collections can move intermediate data to temporary disk storage under memory
pressure. Configure this behavior with `:spill-opts` when opening the KV store:

```clojure
(def kv
  (d/open-kv "/tmp/my-kv"
             {:spill-opts {:spill-threshold 100}}))
```

For a Datalog connection, pass the same setting through `:kv-opts`, because the
Datalog store owns an underlying KV store:

```clojure
(def conn
  (d/get-conn "/tmp/my-db" schema
              {:kv-opts {:spill-opts {:spill-threshold 100}}}))
```

Set `:spill-threshold` to `100` to disable spill-to-disk. Use lower values to
spill earlier under heap pressure. The `:spill-root` option can move temporary
spill files away from the system temp directory. Spill files are implementation
storage for large reads; they are not durable application data. If you want to
process a large range without realizing it eagerly, prefer `range-seq` and close
the returned sequence when finished.

### 4.4 Visitor functions, `read-buffer`, and `put-buffer`

`range-seq` is useful when you want a lazy sequence. Sometimes you do not want a
sequence at all: you want to stream over a range, update a counter, write to an
output file, build a summary, or stop as soon as enough data has been seen. For
those cases, use the visitor APIs.

The main visitor functions are:

- `visit-key-range`, which visits keys only.
- `visit`, which visits key-value pairs in a regular DBI.
- `visit-list`, which visits the values stored under one list-DBI key.
- `visit-list-range`, which visits key-value pairs across a list-DBI range.

Visitors return `nil` from the outer call and are intended for side effects. If
a visitor returns `:datalevin/terminate-visit`, Datalevin stops the scan early.

By default, visitors run in **raw mode**. A raw `visit` callback receives an
`IKV` object whose key and value are `java.nio.ByteBuffer`s. Use `d/k` and `d/v`
to access those buffers, then decode them with `d/read-buffer` and the same KV
type descriptors used when writing the data. When a visitor needs an encoded
comparison value, use `d/put-buffer` to write a Datalevin-encoded value into a
`ByteBuffer`.

The raw callback argument depends on the visitor. `visit-key-range` receives a
key buffer directly. `visit-list` receives a value buffer directly. `visit` and
`visit-list-range` receive an `IKV` pair, because both key and value are
available.

```clojure
(def kv (d/open-kv nil {:inmemory? true}))
(d/open-dbi kv "events")

(def event-key [:string :instant])

(d/transact-kv kv
  [[:put "events"
    ["acct-1" #inst "2026-06-01T00:00:00Z"] 12
    event-key :long]
   [:put "events"
    ["acct-1" #inst "2026-06-02T00:00:00Z"] 5
    event-key :long]
   [:put "events"
    ["acct-2" #inst "2026-06-01T00:00:00Z"] 100
    event-key :long]])

(def total (atom 0))
(def seen  (atom []))

(d/visit kv "events"
  (fn [kvp]
    (let [[account at] (d/read-buffer (d/k kvp) event-key)
          n            (d/read-buffer (d/v kvp) :long)]
      (swap! total + n)
      (swap! seen conj {:account account
                        :at      at
                        :count   n})))
  [:closed ["acct-1" #inst "2026-06-01T00:00:00Z"]
           ["acct-1" #inst "2026-06-30T00:00:00Z"]]
  event-key
  :long)

@total
;; => 17

@seen
;; => [{:account "acct-1"
;;      :at #inst "2026-06-01T00:00:00.000-00:00"
;;      :count 12}
;;     {:account "acct-1"
;;      :at #inst "2026-06-02T00:00:00.000-00:00"
;;      :count 5}]
```

`read-buffer` is the inverse of Datalevin's KV encoding. It reads a value out of
the current position of a `ByteBuffer` according to the type descriptor you
provide: `:string`, `:long`, `:instant`, `:data`, a tuple descriptor such as
`[:string :instant]`, and so on. `put-buffer` goes the other direction: it writes
an application value into a `ByteBuffer` using the same type descriptor. This is
useful when a raw visitor or raw range predicate wants to compare encoded values
without decoding every item.

For example, using the earlier `scores-by-board` list DBI, you can encode a
score cutoff once and compare raw list values against it. Raw byte comparison is
meaningful here because both buffers use the same `score-type` descriptor:

```clojure
(import '[java.nio ByteBuffer])

(def score-type [:double :string])

(def cutoff
  (doto (ByteBuffer/allocate 64)
    (d/put-buffer [90.0 ""] score-type)
    (.flip)))

(d/visit-list kv "scores-by-board"
  (fn [value-buf]
    ;; ByteBuffer.compareTo compares the remaining encoded bytes without
    ;; advancing either buffer's position.
    (when-not (neg? (.compareTo (.duplicate value-buf)
                                (.duplicate cutoff)))
      ;; read-buffer advances position, so decode a duplicate if you also
      ;; need to keep using the original raw buffer.
      (println (d/read-buffer (.duplicate value-buf) score-type))))
  "daily"
  :string
  score-type)
```

Read or copy what you need inside the visitor; do not retain raw buffers outside
the callback.

Raw mode is the default because it avoids decoding data that the visitor may
not need. If you prefer a simpler callback and are willing to decode every
visited item, pass `false` as the final `raw-pred?` argument. In decoded mode,
`visit` calls the visitor with two arguments, the decoded key and decoded value:

```clojure
(d/visit kv "events"
  (fn [[account at] n]
    (println account at n))
  [:closed ["acct-1" #inst "2026-06-01T00:00:00Z"]
           ["acct-1" #inst "2026-06-30T00:00:00Z"]]
  event-key
  :long
  false)
```

Use decoded mode for clarity. Use raw mode when you are doing a very large scan,
need to skip many items cheaply, or need direct control over decoding.

### 4.5 Other Data Access Functions

Beyond point and range queries, Datalevin provides several specialized functions
for the KV layer:

| Function | Purpose |
| :--- | :--- |
| `get-first` | Get the very first key-value pair in a DBI. |
| `get-first-n` | Get the first N key-value pairs in a DBI. |
| `get-rank` | Find the numerical rank (position) of a key in the sorted order. |
| `get-by-rank` | Retrieve the key at a specific numerical index. |
| `sample-kv` | Take a random sample of KV pairs from a DBI. |

`sample-kv` is useful for quick inspection, smoke tests after import, or getting
representative examples without scanning a whole DBI. By default it returns
values only. Pass `false` as `ignore-key?` when you want `[key value]` pairs:

```clojure
(d/open-dbi kv "people")

(d/transact-kv kv
  [[:put "people" 1 "Alice" :long :string]
   [:put "people" 2 "Bob" :long :string]
   [:put "people" 3 "Cara" :long :string]])

(d/sample-kv kv "people" 2 :long :string false)
;; => [[2 "Bob"] [1 "Alice"]] ; example only, samples are random
```

If `n` is larger than the number of entries in the DBI, `sample-kv` returns
`nil`.

---

## 5. Explicit Transaction `with-transaction-kv`

`transact-kv` is the standard way to write data. However, for complex logic
involving reads and writes that must be atomic, use the `with-transaction-kv`
macro.

```clojure
(d/open-dbi kv "counters")

(d/with-transaction-kv [tx kv]
  (let [current (or (d/get-value tx "counters" "hits" :string :long) 0)
        new-val (inc current)]
    (d/transact-kv tx
      "counters"
      [[:put "hits" new-val]]
      :string
      :long)))
```

This macro ensures that:
1. All reads and writes inside the block share the same transaction snapshot.
2. The transaction is automatically committed at the end of the block.
3. If an exception occurs, the transaction is safely aborted.

Inside the body, use the `tx` handle anywhere you would normally use `kv`.
`transact-kv` accepts the same self-contained and single-DBI transaction shapes
inside an explicit transaction.

Use `with-transaction-kv` for KV-only work. When one atomic operation needs to
write both Datalog data and custom KV DBIs in the same store, use Datalog
`with-transaction` and access the transactional KV instance from inside that
block; Chapter 6 shows the pattern.

---

## Summary

The Datalevin KV API is a robust, typed, and highly performant alternative to
the Datalog API. By leveraging **typed keys**, **DUPSORT (list-DBIs)**, and
**order statistics (rank functions)**, you can build specialized data structures
that operate with the same ACID guarantees as the rest of the database.
