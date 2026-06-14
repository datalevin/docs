---
title: "Core Index Architecture: EAV and AVE"
chapter: 15
part: "IV — Indexes as Capabilities"
---

# Chapter 15: Core Index Architecture: EAV and AVE

In a traditional relational database, an index is an "extra" structure you
create to speed up specific queries. In Datalevin, the index **is** the
database. Every piece of information you transact is automatically decomposed
into multiple sorted representations.

This "index-first" architecture is what allows Datalog to perform complex joins
and graph traversals with predictable, high-speed performance. This chapter
explores the two primary workhorses of Datalevin: the **EAV** and **AVE**
indexes.

---

## 1. The Logic of Sorted Triples

Every fact in Datalevin is a triple (a datom): `[Entity Attribute Value]`. To
make searching efficient, Datalevin stores every datom in two different sorted
orders within the underlying KV store (DLMDB).

### 1.1 The Full Datom Object

Most chapters write datoms as `[e a v]` because those are the three fields used
by Datalog patterns and by the EAV/AVE index keys. That is the logical shape of
a fact:

```clojure
[101 :user/email "alice@example.com"]
```

The implementation of datom is a richer Datom object. A full Datom has five fields:

| Field | Meaning |
| :--- | :--- |
| `:e` | Entity id. This is Datalevin's internal numeric entity id. |
| `:a` | Attribute. Usually a namespaced keyword such as `:user/email`. |
| `:v` | Value. The value asserted for that entity and attribute. |
| `:tx` | Transaction id associated with the datom in transaction-report contexts. |
| `:added` | `true` for an assertion, `false` for a retraction. |

The `:tx` value is Datalevin's internal numeric transaction id for the
transaction report. Datalevin maintains the latest transaction counter as store
metadata (`max-tx`), but per-datom transaction ids are not stored in the
persistent EAV/AVE indexes. A transaction id is also not a Datomic-style
reified transaction entity with automatically stored attributes. The optional
`tx-meta` argument to `transact!` is returned in the transaction report and sent
to listeners, but it is not stored as queryable transaction-entity metadata
unless your application explicitly transacts its own audit facts.

The printed form emphasizes the logical triple:

```clojure
(first (d/datoms db :ave :user/email "alice@example.com"))
;=> #datalevin/Datom [101 :user/email "alice@example.com"]
```

In a transaction report, the transaction fields are available on `:tx-data`:

```clojure
(let [report (d/transact! conn [{:user/email "alice@example.com"}])
      datom  (first (:tx-data report))]
  {:e     (d/datom-e datom)
   :a     (d/datom-a datom)
   :v     (d/datom-v datom)
   :tx    (:tx datom)
   :added (:added datom)})
;=> {:e 101, :a :user/email, :v "alice@example.com", :tx 42, :added true}
```

Datalevin's current database indexes expose current facts, so datoms returned
from `datoms`, `search-datoms`, and `index-range` should be treated as current
`[e a v]` facts, not as historical records keyed by transaction. Transaction
reports are where `:tx` and `:added false` most often matter: a report's
`:tx-data` can include datoms that were retracted by the transaction.

This distinction explains a recurring convention in the book: `[e a v]` means
"the logical fact used by a query or index key"; the full Datom object carries
the extra transaction metadata needed by lower-level APIs and transaction
reports.

The connection to database history is direct. One way to support
database-as-value history is to store the transaction dimension with every
datom: keep each assertion and retraction with its transaction id, then
reconstruct an old database value by selecting the datoms whose transaction ids
are visible at that point in time. Datalevin deliberately does not make that
per-datom transaction dimension part of the persistent EAV/AVE indexes.

**Why not database-as-value history?** Datalevin is designed first as an OLTP
database: an operational store for current application state. For that workload,
the ordinary developer mental model is that a database represents mutable state:
users update profiles, orders change status, jobs move through queues, and the
application asks questions about the current world. Automatically retaining every
historical datom would make that model harder to explain and would make every
application pay for a history feature that many operational systems do not need.

Keeping full transaction history is not free. It increases storage, widens the
indexes the system must maintain, and makes reads and writes account for old
states as well as current state. That tradeoff can be right for domains such as
financial ledgers, audit stores, event logs, or analytical systems where history
is the product. It is a poor default for a small embedded or operational
database whose common path is "store the latest state and query it quickly."

This point matters especially for the agent-memory use cases in Part VI. A
useful agent memory is not an append-only transcript. It needs deletion, expiry,
consolidation, summarization, and retraction. Human memory research treats
forgetting as an adaptive feature of cognition: transience can reduce
interference, support abstraction, and help a system stay responsive when the
world changes [3]. An agent that can only accumulate memories will eventually
retrieve stale, irrelevant, or harmful context. Datalevin's current-state model
makes forgetting a normal database operation rather than an exception to the
model.

When applications need temporal or provenance-aware data, the requirements are
usually richer than "keep every transaction forever": valid time, bitemporal
time, source confidence, derivation, user/process provenance, and
semiring-style annotations are domain models, not just storage history [2].
Datalevin's direction is to support that kind of explicit provenance rather than
treating transaction history as the one built-in answer. In the meantime, model
audit and provenance facts explicitly when the domain needs them, and keep the
core indexes optimized for current OLTP state.

### 1.2 Why Sorted Indexes Speed Up Queries

Sorted indexes are fast because they avoid looking at unrelated facts. The
basic idea is the same reason binary search is faster than scanning a list: if
the data is ordered, the engine can jump toward the place where a key must be
instead of checking every item.

LMDB stores each Datalevin index as a B+Tree [1]. A point lookup, such as "find
the datom for entity `101` and attribute `:user/email`", starts at the root
page, chooses the child page whose key range could contain the target, repeats
that step through internal pages, and lands on the leaf page where the key is
stored or would be stored. Because B+Tree pages have high fanout, this is
usually only a few page hops even for a large database.

Range queries use the sorted order in a second way. To answer "find all users
with age between `21` and `65`", the engine first seeks to the lower bound in
the AVE index. From there, it walks forward through neighboring leaf entries in
sorted order until the upper bound is reached. It does not restart from the root
for every matching datom, and it does not scan unrelated attributes.

This is why the order of an index matters. EAV makes facts for one entity
contiguous. AVE makes facts for one attribute/value range contiguous. Datalog
clauses become cheap when the bound variables line up with one of those sorted
orders.

### 1.3 EAV (Entity-Attribute-Value)

The EAV index is sorted primarily by the **Entity ID**, then by the
**Attribute**, and finally by the **Value**.

- **Structure**: `E -> A -> V`
- **Primary Use Case**: "Give me everything about Entity 101."
- **Query Role**: This index powers the **Pull API** and any join where the
  Entity is already known. Because all attributes for a single entity are stored
  contiguously in the B+Tree, retrieving a complete "document" for an entity is
  a single localized scan.

### 1.4 AVE (Attribute-Value-Entity)

The AVE index is sorted primarily by the **Attribute**, then by the **Value**,
and finally by the **Entity ID**.

- **Structure**: `A -> V -> E`
- **Primary Use Case**: "Find all entities where the `:user/age` is `30`."
- **Query Role**: This is the "search" index. It powers all `:where` clauses
  that filter by value.
- **Uniqueness**: In Datalevin, **every attribute is indexed in AVE by
  default**. Unlike other Datalog databases, you do not need to explicitly
  opt-in to indexing.

---

## 2. Leveraging the AVE Index for Range Queries

Because the AVE index is sorted by Value, it is the engine behind all comparison
and range operations in Datalog.

When you write a query like `[(> ?age 21)]`, the Datalevin query optimizer
doesn't scan the entire database. Instead, it:
1. Jumps to the first entry in the AVE index for `:user/age` where the value is
   greater than `21`.
2. Scans forward through the sorted values.

This makes range queries (finding dates, prices, or ages) extremely efficient.
Because DLMDB supports **order statistics** (Chapter 4), the engine can even
know how many items are in a range instantly, allowing it to pick the most
efficient join order (see Chapter 21).

---

## 3. Reverse Relationships: The "Missing" VAE Index

Some Datalog databases  include a **VAE (Value-Attribute-Entity)**
index specifically for reverse references. This increase write and storage
burden. Datalevin chooses a simpler approach.

In Datalevin, a reference (`:db.type/ref`) is just a value that happens to be an
Entity ID. Therefore, a reverse reference (e.g., "who points to Alice?") is
simply an AVE lookup: `[?who :user/friend ?alice-id]`, because in most cases
the attribute is given.

Because **every attribute has an AVE index**, reverse navigation is just as fast
as forward navigation. There is no need for a separate VAE index, which reduces
on-disk storage size and write overhead.

---

## 4. Index-Level APIs: Direct Access

One of the most powerful features of Datalevin is that it exposes these indexes
directly to the developer. You are not limited to the Datalog query engine; you
can treat the indexes as **programmable capabilities**.

Most non-Clojure examples earlier in the book use high-level convenience
methods such as `conn.query`, `conn.transact`, and `conn.pull`. Direct index
access is lower-level, so the non-Clojure bindings expose a JSON operation API:
pass an operation name such as `"datoms"` plus a data map of arguments, and get
JSON-shaped values back. The Clojure examples below use `datalevin.core` as
`d`. Java examples assume a high-level `Connection conn` and call the supported
`conn.exec(...)` escape hatch for connection-scoped JSON operations. Python
examples use `exec_json` from `datalevin`; JavaScript examples use `execJson`
from `datalevin-node`. For Python and JavaScript, `conn_handle` is the handle
returned by the JSON API `create-conn` operation. Colon-prefixed attribute
strings are decoded as keywords by the JSON API.

Direct index APIs return datoms in index order. They are best for simple, known
access paths where you want the index itself, not the Datalog planner, to be the
API boundary.

### 4.1 Choosing an Index Access Function

| Function | Use it when | Index behavior |
| --- | --- | --- |
| `datoms` | You know the target index and a prefix of its sort key. | Scans `:eav` or `:ave` in that index's natural order. |
| `search-datoms` | You have an `(e, a, v)` pattern with wildcards. | Chooses an efficient index for the supplied components. |
| `count-datoms` | You only need the number of matching datoms. | Counts the same wildcard pattern without materializing results. |
| `cardinality` | You need the number of distinct values currently present for one attribute. | Counts unique values for that attribute through the AVE index. |
| `seek-datoms` | You need a forward cursor starting at a lower bound. | Starts at the first datom greater than or equal to the supplied index key. |
| `rseek-datoms` | You need a reverse cursor starting near an upper bound. | Same as `seek-datoms`, but walks backward. |
| `index-range` | You need all values of one attribute between two bounds. | Scans the AVE range for one attribute. |

For `:eav`, the positional components are `c1 = e`, `c2 = a`, and `c3 = v`. For
`:ave`, they are `c1 = a`, `c2 = v`, and `c3 = e`.

### 4.2 Prefix Scans with `datoms`

Use `datoms` when you already know whether the lookup is entity-local (`:eav`)
or value-local (`:ave`).

<div class="multi-lang">

```clojure
;; All datoms for entity 101, ordered by attribute and value.
(d/datoms db :eav 101)

;; A single attribute on entity 101.
(d/datoms db :eav 101 :user/email)

;; All entities whose :user/age value is 30.
(d/datoms db :ave :user/age 30)
```

```java
// All datoms for entity 101, ordered by attribute and value.
conn.exec("datoms", Map.of("index", "eav", "c1", 101));

// A single attribute on entity 101.
conn.exec("datoms",
          Map.of("index", "eav", "c1", 101, "c2", ":user/email"));

// All entities whose :user/age value is 30.
conn.exec("datoms",
          Map.of("index", "ave", "c1", ":user/age", "c2", 30));
```

```python
# All datoms for entity 101, ordered by attribute and value.
exec_json("datoms", {"conn": conn_handle, "index": "eav", "c1": 101})

# A single attribute on entity 101.
exec_json("datoms", {"conn": conn_handle, "index": "eav",
                     "c1": 101, "c2": ":user/email"})

# All entities whose :user/age value is 30.
exec_json("datoms", {"conn": conn_handle, "index": "ave",
                     "c1": ":user/age", "c2": 30})
```

```javascript
// All datoms for entity 101, ordered by attribute and value.
await execJson('datoms', { conn: connHandle, index: 'eav', c1: 101 });

// A single attribute on entity 101.
await execJson('datoms', {
  conn: connHandle,
  index: 'eav',
  c1: 101,
  c2: ':user/email'
});

// All entities whose :user/age value is 30.
await execJson('datoms', {
  conn: connHandle,
  index: 'ave',
  c1: ':user/age',
  c2: 30
});
```

</div>

### 4.3 Wildcard Lookup with `search-datoms`

Use `search-datoms` when you want to describe the logical datom pattern as `(e,
a, v)` and let Datalevin choose the index. A `nil` component is a wildcard. In
Java and JSON API calls, omit a wildcard key from the argument map.

<div class="multi-lang">

```clojure
;; All attributes and values for entity 101.
(d/search-datoms db 101 nil nil)

;; All entities whose :user/age value is 30.
(d/search-datoms db nil :user/age 30)

;; The exact datom, if present.
(d/search-datoms db 101 :user/email "ada@example.com")
```

```java
// All attributes and values for entity 101.
conn.exec("search-datoms", Map.of("e", 101));

// All entities whose :user/age value is 30.
conn.exec("search-datoms", Map.of("a", ":user/age", "v", 30));

// The exact datom, if present.
conn.exec("search-datoms",
          Map.of("e", 101, "a", ":user/email", "v", "ada@example.com"));
```

```python
# All attributes and values for entity 101.
exec_json("search-datoms", {"conn": conn_handle, "e": 101})

# All entities whose :user/age value is 30.
exec_json("search-datoms", {"conn": conn_handle, "a": ":user/age", "v": 30})

# The exact datom, if present.
exec_json("search-datoms", {
    "conn": conn_handle,
    "e": 101,
    "a": ":user/email",
    "v": "ada@example.com"
})
```

```javascript
// All attributes and values for entity 101.
await execJson('search-datoms', { conn: connHandle, e: 101 });

// All entities whose :user/age value is 30.
await execJson('search-datoms', {
  conn: connHandle,
  a: ':user/age',
  v: 30
});

// The exact datom, if present.
await execJson('search-datoms', {
  conn: connHandle,
  e: 101,
  a: ':user/email',
  v: 'ada@example.com'
});
```

</div>

### 4.4 Counting with `count-datoms`

Use `count-datoms` when a query path needs selectivity, pagination totals, or a
cheap existence check.

<div class="multi-lang">

```clojure
;; How many datoms are attached to entity 101?
(d/count-datoms db 101 nil nil)

;; How many users have age 30?
(d/count-datoms db nil :user/age 30)
```

```java
// How many datoms are attached to entity 101?
conn.exec("count-datoms", Map.of("e", 101));

// How many users have age 30?
conn.exec("count-datoms", Map.of("a", ":user/age", "v", 30));
```

```python
# How many datoms are attached to entity 101?
exec_json("count-datoms", {"conn": conn_handle, "e": 101})

# How many users have age 30?
exec_json("count-datoms", {"conn": conn_handle, "a": ":user/age", "v": 30})
```

```javascript
// How many datoms are attached to entity 101?
await execJson('count-datoms', { conn: connHandle, e: 101 });

// How many users have age 30?
await execJson('count-datoms', {
  conn: connHandle,
  a: ':user/age',
  v: 30
});
```

</div>

`count-datoms` is more direct than `(count (search-datoms ...))` because it does
not have to allocate the matching datoms.

### 4.5 Cardinality and Size Helpers

Use `cardinality` when you need the number of distinct values currently present
for one attribute. Do not confuse this with the schema property
`:db/cardinality`, which says whether an attribute is single-valued or
multi-valued. `d/cardinality` is a data statistic:

<div class="multi-lang">

```clojure
;; How many distinct ages exist?
(d/cardinality db :user/age)

;; How many datoms use :user/age at all?
(d/count-datoms db nil :user/age nil)

;; How many datoms say :user/age is 30?
(d/count-datoms db nil :user/age 30)
```

```java
// How many distinct ages exist?
conn.exec("cardinality", Map.of("attr", ":user/age"));

// How many datoms use :user/age at all?
conn.exec("count-datoms", Map.of("a", ":user/age"));

// How many datoms say :user/age is 30?
conn.exec("count-datoms", Map.of("a", ":user/age", "v", 30));
```

```python
# How many distinct ages exist?
exec_json("cardinality", {"conn": conn_handle, "attr": ":user/age"})

# How many datoms use :user/age at all?
exec_json("count-datoms", {"conn": conn_handle, "a": ":user/age"})

# How many datoms say :user/age is 30?
exec_json("count-datoms", {"conn": conn_handle, "a": ":user/age", "v": 30})
```

```javascript
// How many distinct ages exist?
await execJson('cardinality', { conn: connHandle, attr: ':user/age' });

// How many datoms use :user/age at all?
await execJson('count-datoms', { conn: connHandle, a: ':user/age' });

// How many datoms say :user/age is 30?
await execJson('count-datoms', { conn: connHandle, a: ':user/age', v: 30 });
```

</div>

You may see lower-level names such as `a-size`, `e-size`, `av-size`, `v-size`,
and `av-range-size` in Datalevin internals or optimizer discussions. Those are
storage/protocol helpers used by the query planner, not the public
`datalevin.core` API. From application code, use `count-datoms` for pattern
counts, `cardinality` for distinct attribute values, and `index-range` when you
need the matching datoms in a value range.

Datalevin periodically collects planner statistics in the background. When you
have just bulk-loaded or substantially reshaped data, `analyze` can refresh
statistics for one attribute or all attributes:

```clojure
(d/analyze db :user/age)
(d/analyze db)
```

### 4.6 Cursor Scans with `seek-datoms` and `rseek-datoms`

Use `seek-datoms` when you want to start at an index key and continue forward.
If no datom exactly matches the supplied components, Datalevin starts at the
first datom that is greater in that index order. `rseek-datoms` uses the same
idea in reverse. These are cursors, so they do not stop at a prefix
automatically; use `datoms`, `index-range`, `take`, `limit`, or filtering when
the scan must stay inside one logical range.

<div class="multi-lang">

```clojure
;; Start a forward scan at :user/age 30 in AVE order.
(d/seek-datoms db :ave :user/age 30 nil 10)

;; Start a reverse scan near the high end of :user/created-at.
(d/rseek-datoms db :ave :user/created-at 4102444800000 nil 5)

;; Start at the :user/email position inside entity 101.
(d/seek-datoms db :eav 101 :user/email)
```

```java
// Start a forward scan at :user/age 30 in AVE order.
conn.exec("seek-datoms",
          Map.of("index", "ave", "c1", ":user/age", "c2", 30, "limit", 10));

// Start a reverse scan near the high end of :user/created-at.
conn.exec("rseek-datoms",
          Map.of("index", "ave",
                 "c1", ":user/created-at",
                 "c2", 4102444800000L,
                 "limit", 5));

// Start at the :user/email position inside entity 101.
conn.exec("seek-datoms",
          Map.of("index", "eav", "c1", 101, "c2", ":user/email"));
```

```python
# Start a forward scan at :user/age 30 in AVE order.
exec_json("seek-datoms", {"conn": conn_handle, "index": "ave",
                          "c1": ":user/age", "c2": 30, "limit": 10})

# Start a reverse scan near the high end of :user/created-at.
exec_json("rseek-datoms", {"conn": conn_handle, "index": "ave",
                           "c1": ":user/created-at",
                           "c2": 4102444800000,
                           "limit": 5})

# Start at the :user/email position inside entity 101.
exec_json("seek-datoms", {"conn": conn_handle, "index": "eav",
                          "c1": 101, "c2": ":user/email"})
```

```javascript
// Start a forward scan at :user/age 30 in AVE order.
await execJson('seek-datoms', {
  conn: connHandle,
  index: 'ave',
  c1: ':user/age',
  c2: 30,
  limit: 10
});

// Start a reverse scan near the high end of :user/created-at.
await execJson('rseek-datoms', {
  conn: connHandle,
  index: 'ave',
  c1: ':user/created-at',
  c2: 4102444800000,
  limit: 5
});

// Start at the :user/email position inside entity 101.
await execJson('seek-datoms', {
  conn: connHandle,
  index: 'eav',
  c1: 101,
  c2: ':user/email'
});
```

</div>

The JSON API uses `limit` and `offset` for paging sequence results. In Clojure,
`seek-datoms` and `rseek-datoms` also have an arity that accepts `n` as the
final argument:

```clojure
(d/seek-datoms db index c1 c2 c3 n)
(d/rseek-datoms db index c1 c2 c3 n)
```

Without `n`, the cursor keeps walking to the end of that index direction. That
is occasionally useful for low-level scans, but it is often broader than
application code intends. Prefer passing `n`, using `datoms` for prefix-bound
lookups, or using `index-range` when there is a real upper bound.

The `n` argument is positional. If you want to limit a scan that only binds
`c1` and `c2`, pass `nil` for `c3`:

```clojure
;; Ten datoms starting at [:user/age 30] in AVE order.
(d/seek-datoms db :ave :user/age 30 nil 10)
```

### 4.7 Range Scans with `index-range`

Use `index-range` for the most common AVE range pattern: one attribute, lower
bound, upper bound.

<div class="multi-lang">

```clojure
;; Find users with age between 20 and 30, inclusive.
(d/index-range db :user/age 20 30)

;; Return just the matching entity ids.
(map d/datom-e (d/index-range db :user/age 20 30))
```

```java
// Find users with age between 20 and 30, inclusive.
conn.exec("index-range",
          Map.of("attr", ":user/age", "start", 20, "end", 30));

// Return just the matching entity ids.
List<?> datoms = (List<?>) conn.exec(
    "index-range",
    Map.of("attr", ":user/age", "start", 20, "end", 30));
List<?> entityIds = datoms.stream()
    .map(datom -> ((Map<?, ?>) datom).get("e"))
    .toList();
```

```python
# Find users with age between 20 and 30, inclusive.
datoms = exec_json("index-range", {"conn": conn_handle,
                                   "attr": ":user/age",
                                   "start": 20,
                                   "end": 30})

# Return just the matching entity ids.
entity_ids = [datom["e"] for datom in datoms]
```

```javascript
// Find users with age between 20 and 30, inclusive.
const datoms = await execJson('index-range', {
  conn: connHandle,
  attr: ':user/age',
  start: 20,
  end: 30
});

// Return just the matching entity ids.
const entityIds = datoms.map((datom) => datom.e);
```

</div>

### 4.8 Reading Datom Fields

Clojure datoms have dedicated accessors for the logical triple and keyword
lookup for transaction metadata. JSON API results encode datoms as maps with
`e`, `a`, `v`, `tx`, and `added` fields. Java `conn.exec` returns the same
bridge-safe maps with string keys such as `"e"`, `"a"`, `"v"`, `"tx"`, and
`"added"`.

<div class="multi-lang">

```clojure
(for [datom (d/datoms db :ave :user/age 30)]
  {:entity (d/datom-e datom)
   :attr   (d/datom-a datom)
   :value  (d/datom-v datom)
   :tx     (:tx datom)
   :added  (:added datom)})
```

```java
List<?> datoms = (List<?>) conn.exec(
    "datoms",
    Map.of("index", "ave", "c1", ":user/age", "c2", 30));

for (Object item : datoms) {
    Map<?, ?> datom = (Map<?, ?>) item;
    Object entity = datom.get("e");
    Object attr = datom.get("a");
    Object value = datom.get("v");
    Object tx = datom.get("tx");
    Object added = datom.get("added");
}
```

```python
datoms = exec_json("datoms", {"conn": conn_handle, "index": "ave",
                              "c1": ":user/age", "c2": 30})

for datom in datoms:
    entity = datom["e"]
    attr = datom["a"]
    value = datom["v"]
    tx = datom["tx"]
    added = datom["added"]
```

```javascript
const datoms = await execJson('datoms', {
  conn: connHandle,
  index: 'ave',
  c1: ':user/age',
  c2: 30
});

for (const datom of datoms) {
  const entity = datom.e;
  const attr = datom.a;
  const value = datom.v;
  const tx = datom.tx;
  const added = datom.added;
}
```

</div>

These functions are intentionally low-level. Prefer Datalog for joins, rules,
result shaping, and complex predicates. Reach for direct index access when the
shape is simple enough that the index order itself is the query plan.

---

## 5. Physical Representation and DUPSORT

Physically, these indexes are implemented using LMDB's `DUPSORT` feature (see
Chapter 10). This allows Datalevin to store many values for a single key
efficiently.

- **In EAV**: The Key is `E`, and the Values are `(A, V)` pairs.
- **In AVE**: The Key is `(A, V)`, and the Values are `E` (entity IDs).

Thinking in terms of traditional database storage models:

- **EAV is a row store**: Each entity ID (key) maps to a list of attribute-value
  pairs, analogous to a row where all column values are stored together.
  Retrieving an entity is a single key lookup.
- **AVE is a column store**: Each `(A, V)` combination (key) maps to a tightly
  packed list of entity IDs—the "row IDs" that share that column value. This is
  ideal for analytical queries that scan a column.

This nested storage eliminates redundant prefixes. In EAV, an entity with 10
attributes stores the entity ID once as the key, with 10 `(A, V)` pairs as
values. In AVE, each `(A, V)` combination is stored once as the key, with all
matching entity IDs as values, so finding all entities where `:user/age` is
`30` is a single key lookup.

In the Join Order Benchmark database, this `DUPSORT` nesting reduced index space
by roughly 20%. Treat that number as an anecdotal workload observation, not a
general guarantee: the savings depend on the number of repeated entity IDs in
EAV and repeated `(A, V)` prefixes in AVE. This is separate from DLMDB's
page-level prefix compression, which can provide additional savings depending on
how much of neighboring encoded keys is actually shared.

---

## 6. Summary: Indexes as Capabilities

By making every attribute indexed by default and providing direct API access to
those indexes, Datalevin transforms the database from a "black box" into a set
of **programmable capabilities**.

- **EAV** provides locality for entities and documents.
- **AVE** provides fast lookups and range scans for values.
- **Direct Access** gives you `datoms`, `search-datoms`, `count-datoms`,
  `cardinality`, `seek-datoms`, `rseek-datoms`, `index-range`, and `analyze`
  for custom traversal and statistics logic when the access pattern is already
  known.

Understanding these indexes is the first step toward mastering the more
specialized capabilities of Datalevin, such as full-text search and vector
similarity, which we will explore in the following chapters.

## References

[1] Douglas Comer, ["The Ubiquitous
B-Tree"](https://doi.org/10.1145/356770.356776), *ACM Computing Surveys* 11(2),
121-137, 1979.

[2] Camille Bourgaux, Pierre Bourhis, Liat Peterfreund, and Michael Thomazo,
["Revisiting Semiring Provenance for
Datalog"](https://arxiv.org/abs/2202.10766), arXiv:2202.10766, 2022.

[3] Blake A. Richards and Paul W. Frankland, ["The Persistence and Transience
of Memory"](https://doi.org/10.1016/j.neuron.2017.04.037), *Neuron* 94(6),
1071-1084, 2017.
