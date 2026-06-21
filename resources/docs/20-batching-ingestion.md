---
title: "High-Throughput Ingestion"
chapter: 20
part: "V — Performance and Operations"
---

# Chapter 20: High-Throughput Ingestion

High write throughput is a different problem from low-latency reads or
interactive transactions. This chapter collects the tools Datalevin offers for
loading large volumes of data quickly: WAL mode and asynchronous transactions
for durable online ingestion, non-durable LMDB flags for rebuildable imports,
pre-sorting to keep B+Tree inserts cheap, and the `init-db`/`fill-db` bulk-load
path that bypasses the transaction layer entirely. It closes with a decision
flow for choosing among them. For the durability trade-offs behind these
choices, see Chapter 19.


## 1. Online Ingestion: WAL and Async Transactions

For durable ingestion while the database remains queryable, the usual recipe is
manual batches submitted through `transact-async`, often with WAL mode enabled.
Chapter 19 explains WAL durability profiles and LSN maintenance; here the point
is how to submit work without making every caller wait for its own commit.

`d/transact-async` in Clojure, `transactAsync` in Java and JavaScript, and
`transact_async` in Python submit ordinary transaction data and return a future
or promise. Datalevin can then batch concurrent submissions internally.

<div class="multi-lang">

```clojure
;; Fire multiple async transactions
(def f1 (d/transact-async conn batch-1))
(def f2 (d/transact-async conn batch-2))
(def f3 (d/transact-async conn batch-3))

;; Block on the last one to ensure all are committed
@f3
```

```java
import java.util.Map;
import java.util.concurrent.CompletableFuture;

CompletableFuture<Map<?, ?>> f1 = conn.transactAsync(batch1);
CompletableFuture<Map<?, ?>> f2 = conn.transactAsync(batch2);
CompletableFuture<Map<?, ?>> f3 = conn.transactAsync(batch3);

// Block on the last one to ensure all are committed.
f3.join();
```

```python
future1 = conn.transact_async(batch_1)
future2 = conn.transact_async(batch_2)
future3 = conn.transact_async(batch_3)

# Block on the last one to ensure all are committed.
future3.result(timeout=30)
```

```javascript
const p1 = conn.transactAsync(batch1);
const p2 = conn.transactAsync(batch2);
const p3 = conn.transactAsync(batch3);

// Block on the last one to ensure all are committed.
await p3;
```

</div>

Block on the last submitted future when later work depends on the whole burst.
Since async transactions are still committed in order, the last realized future
indicates all prior calls in that burst have also committed.

### 1.1 Durable Checkpoints After Async Work

For embedded applications that need both good throughput and deterministic
commit points, use a sequence of async transactions followed by a sync call:

<div class="multi-lang">

```clojure
;; Async writes for throughput
(d/transact-async conn batch-1)
(d/transact-async conn batch-2)
(d/transact-async conn batch-3)

;; Sync commit to ensure all are persisted
(d/transact! conn [])
```

```java
import java.util.List;

// Async writes for throughput.
conn.transactAsync(batch1);
conn.transactAsync(batch2);
conn.transactAsync(batch3);

// Sync commit to ensure all are persisted.
conn.transact(List.of());
```

```python
# Async writes for throughput.
conn.transact_async(batch_1)
conn.transact_async(batch_2)
conn.transact_async(batch_3)

# Sync commit to ensure all are persisted.
conn.transact([])
```

```javascript
// Async writes for throughput.
conn.transactAsync(batch1);
conn.transactAsync(batch2);
conn.transactAsync(batch3);

// Sync commit to ensure all are persisted.
await conn.transact([]);
```

</div>

> **Note**: In the default direct LMDB commit path, writes are still coordinated
> by the single-writer B+Tree transaction model. WAL mode changes the throughput
> profile: concurrent writer callers can benefit from sequential WAL append and
> group commit, though scaling is still sub-linear. For most ingestion jobs,
> combine manual batches with `transact-async` and measure before adding writer
> threads.

### 1.2 Non-Durable LMDB Flags for Repeatable Imports

WAL mode and async transactions are the first tools to try when data must remain
durable. For one-time imports, cache rebuilds, temporary KV stores, and other
repeatable jobs, LMDB also exposes environment flags that reduce disk-sync work
at commit time. These flags can apply to both Datalog connections and raw KV
stores because both ultimately write through LMDB.

| Flags | What changes | Use when | Risk |
|-------|--------------|----------|------|
| `:nometasync` | Sync data pages but not meta pages on commit | You want a modest speedup while preserving database integrity after a crash | The last transaction may be lost |
| `:nosync` | Skip `msync` on commit and leave flushing to the OS | The import is rebuildable and write speed matters more than crash recovery | A system crash may corrupt the database |
| `:writemap` + `:mapasync` | Use a writable memory map with asynchronous flushing | Maximum raw write speed for rebuildable data | A crash may corrupt the database; buggy native code can overwrite mapped DB memory; some OSes preallocate the full map size |

The Datalevin write benchmark tests these flags with both `transact!` and
`transact-async`, and with both `transact-kv` and `transact-kv-async`. In the KV
pure-write benchmark, `:nosync` and `:writemap`/`:mapasync` move throughput from
the durable baseline into the hundreds of thousands to over a million writes per
second depending on batch size. The same benchmark also shows that KV batching
has a stronger effect than Datalog batching, so do not treat environment flags as
a substitute for large batches.

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])
(require '[datalevin.constants :as c])

;; Raw KV store for a rebuildable import.
(def kv
  (d/open-kv "/tmp/import-kv"
             {:flags (-> c/default-env-flags
                         (conj :writemap)
                         (conj :mapasync))}))

;; Datalog connection using the same LMDB flags.
(def conn
  (d/get-conn "/tmp/import-dl" schema
              {:kv-opts {:flags (conj c/default-env-flags :nometasync)}}))
```

```java
import datalevin.*;
import java.util.List;
import java.util.Map;

// Raw KV store for a rebuildable import.
KV kv = Datalevin.openKV("/tmp/import-kv",
    Map.of("flags", List.of(":writemap", ":mapasync")));

// Datalog connection using the same LMDB flags.
Connection conn = Datalevin.getConn("/tmp/import-dl", schema,
    Map.of("kv-opts", Map.of("flags", List.of(":nometasync"))));
```

```python
from datalevin import connect, open_kv

# Raw KV store for a rebuildable import.
kv = open_kv("/tmp/import-kv",
    opts={":flags": [":writemap", ":mapasync"]})

# Datalog connection using the same LMDB flags.
conn = connect("/tmp/import-dl", schema=schema,
    opts={":kv-opts": {":flags": [":nometasync"]}},
    shared=True)
```

```javascript
import { connect, openKv } from "datalevin-node";

// Raw KV store for a rebuildable import.
const kv = await openKv("/tmp/import-kv",
  { ":flags": [":writemap", ":mapasync"] });

// Datalog connection using the same LMDB flags.
const conn = await connect("/tmp/import-dl", {
  schema,
  opts: { ":kv-opts": { ":flags": [":nometasync"] } },
  shared: true
});
```

</div>

In the Java, Python, and JavaScript examples, the flag values use
colon-prefixed strings because these option values are Datalevin keywords. The
Python `shared=True` and JavaScript `shared: true` options select the shared
connection form corresponding to Java's `getConn`.

If you use `:nosync` or `:writemap`/`:mapasync`, write the import so it can be
replayed from a durable source. For raw KV imports, call the KV `sync` operation
(`d/sync` in Clojure) at explicit checkpoints or before handing the database to
normal durable workloads. For online systems with irreplaceable writes, prefer
WAL mode with async transactions and tune durability there instead.


## 2. Sorting Before Ingestion

A B+Tree performs best when keys are inserted in **sorted order**. If you insert
random entity ids and random Values, the engine must "jump around" the B+Tree,
leading to frequent cache misses and page splits. A **page split** happens when
a B+Tree page becomes full and must be divided into two pages, with parent pages
updated to point at the new key ranges. Random insert order causes more of this
rebalancing work; sorted input lets the tree append and fill pages more
predictably.

### 2.1 Pre-Sorting Your Data

If you are performing a bulk import (e.g., from a CSV or a SQL dump), sort your
datoms *before* they reach the database.

1.  **Group by entity id**: This ensures that all attributes for a single entity
    are written to the EAV index contiguously.
2.  **Group by Attribute and Value**: This ensures that updates to the AVE index
    are also localized.

<div class="multi-lang">

```clojure
;; Sort data by entity id
(let [sorted-datoms (sort-by :db/id raw-data)]
  (doseq [batch (partition-all 5000 sorted-datoms)]
    (d/transact! conn batch)))
```

```java
// Sort data by entity id
rawData.sort(Comparator.comparing(m -> (Long) m.get("db/id")));
List<List<Map>> batches = partition(rawData, 5000);
for (List<Map> batch : batches) {
    conn.transact(batch);
}
```

```python
# Sort data by entity id
sorted_datoms = sorted(raw_data, key=lambda x: x[":db/id"])
for batch in partition_all(sorted_datoms, 5000):
    conn.transact(batch)
```

```javascript
// Sort data by entity id
const sortedDatoms = rawData.sort((a, b) => a[":db/id"] - b[":db/id"]);
for (const batch of partitionAll(sortedDatoms, 5000)) {
  await conn.transact(batch);
}
```

</div>

### 2.2 Disable the Datalog Index Cache During Bulk Transactions

Datalevin keeps a small Datalog index cache for normal interactive workloads.
During a large bulk transaction job, that cache can add memory pressure and cache
maintenance work without helping much, especially when the import touches a wide
range of entities and attributes only once. In every binding, set the cache
limit to `0` for the import phase, then restore the previous limit:

<div class="multi-lang">

```clojure
(let [db             (d/db conn)
      previous-limit (d/datalog-index-cache-limit db)]
  (try
    (d/datalog-index-cache-limit db 0)
    (doseq [batch (partition-all 5000 sorted-datoms)]
      (d/transact! conn batch))
    (finally
      (d/datalog-index-cache-limit (d/db conn) previous-limit))))
```

```java
long previousLimit = conn.datalogIndexCacheLimit();

try {
    conn.datalogIndexCacheLimit(0);
    for (List<Map<String, Object>> batch : partition(sortedDatoms, 5000)) {
        conn.transact(batch);
    }
} finally {
    conn.datalogIndexCacheLimit(previousLimit);
}
```

```python
previous_limit = conn.datalog_index_cache_limit()

try:
    conn.datalog_index_cache_limit(0)
    for batch in partition_all(sorted_datoms, 5000):
        conn.transact(batch)
finally:
    conn.datalog_index_cache_limit(previous_limit)
```

```javascript
const previousLimit = await conn.datalogIndexCacheLimit();

try {
  await conn.datalogIndexCacheLimit(0);
  for (const batch of partitionAll(sortedDatoms, 5000)) {
    await conn.transact(batch);
  }
} finally {
  await conn.datalogIndexCacheLimit(previousLimit);
}
```

</div>

This is a bulk-ingestion tuning knob, not a general query-performance setting.
Leave the cache enabled for mixed read/write application traffic unless a
measured workload shows that disabling it helps.


## 3. Bulk Load: `d/init-db` and `d/fill-db`

For the fastest possible bulk load into a database, bypass the transaction
overhead entirely using `d/init-db` and `d/fill-db`. Note that these functions
**bypass the WAL** and will not appear in the transaction log.

- **`d/init-db`**: Load a collection of prepared datoms into a fresh, empty
  database
- **`d/fill-db`**: Bulk load additional datoms into an existing database

The same bulk-load path is exposed in the Java, Python, and JavaScript
bindings: `Datalevin.initDb` / `fillDb`, `init_db` / `fill_db`, and `initDb` /
`fillDb`, respectively. Use normal transactions instead when you need tempids,
lookup refs, upserts, transaction functions, or transaction-level integrity
checks.

### 3.1 Assigning Entity Ids for Prepared Datoms

`init-db` and `fill-db` do not accept transaction maps, tempids, lookup refs, or
upserts. They accept prepared `Datom` values. That means the subject entity id
and every `:db.type/ref` value must already be the final numeric entity id.

A simple technique works well for relational imports whose source tables already
have integer primary keys: reserve a non-overlapping entity-id range for each
source table, then convert every primary key and foreign key with the same
function [1] [2]. For example:

<div class="multi-lang">

<!-- pdf-listing: Preparing stable entity ids for bulk datom ingestion -->

```clojure
(def schema
  {:user/id        {:db/valueType :db.type/long
                   :db/unique    :db.unique/identity}
   :user/name      {:db/valueType :db.type/string}
   :order/id       {:db/valueType :db.type/long
                   :db/unique    :db.unique/identity}
   :order/customer {:db/valueType :db.type/ref}
   :order/total    {:db/valueType :db.type/double}})

;; Reserve disjoint numeric ranges. This is the same idea used by the
;; JOB benchmark, where each imported table has its own eid base.
(def user-base  1000000)
(def order-base 2000000)

(defn user-eid [source-id]
  (+ user-base (parse-long source-id)))

(defn order-eid [source-id]
  (+ order-base (parse-long source-id)))

(def users
  [{:id "1" :name "Alice"}
   {:id "2" :name "Bob"}])

(def orders
  [{:id "10" :customer-id "1" :total 42.5}
   {:id "11" :customer-id "2" :total 19.0}])

(defn user-datoms [{:keys [id name]}]
  (let [eid (user-eid id)]
    [(d/datom eid :user/id (parse-long id))
     (d/datom eid :user/name name)]))

(defn order-datoms [{:keys [id customer-id total]}]
  (let [eid (order-eid id)]
    [(d/datom eid :order/id (parse-long id))
     (d/datom eid :order/customer (user-eid customer-id))
     (d/datom eid :order/total total)]))

(def prepared-datoms
  (vec (concat (mapcat user-datoms users)
               (mapcat order-datoms orders))))

;; Load into empty database
(def db (d/init-db prepared-datoms "/tmp/import-db" schema))
```

```java
import datalevin.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

static long userEid(String sourceId) {
    return 1_000_000L + Long.parseLong(sourceId);
}

static long orderEid(String sourceId) {
    return 2_000_000L + Long.parseLong(sourceId);
}

static List<Object> userDatoms(Map<String, ?> user) {
    String id = (String) user.get("id");
    long eid = userEid(id);

    return Datalevin.listOf(
        Datalevin.datom(eid, ":user/id", Long.parseLong(id)),
        Datalevin.datom(eid, ":user/name", user.get("name")));
}

static List<Object> orderDatoms(Map<String, ?> order) {
    String id = (String) order.get("id");
    String customerId = (String) order.get("customerId");
    long eid = orderEid(id);

    return Datalevin.listOf(
        Datalevin.datom(eid, ":order/id", Long.parseLong(id)),
        Datalevin.datom(eid, ":order/customer", userEid(customerId)),
        Datalevin.datom(eid, ":order/total", order.get("total")));
}

Map<?, ?> schema = Map.of(
    ":user/id", Map.of(
        ":db/valueType", ":db.type/long",
        ":db/unique", ":db.unique/identity"),
    ":user/name", Map.of(":db/valueType", ":db.type/string"),
    ":order/id", Map.of(
        ":db/valueType", ":db.type/long",
        ":db/unique", ":db.unique/identity"),
    ":order/customer", Map.of(":db/valueType", ":db.type/ref"),
    ":order/total", Map.of(":db/valueType", ":db.type/double"));

List<Map<String, ?>> users = List.of(
    Map.of("id", "1", "name", "Alice"),
    Map.of("id", "2", "name", "Bob"));

List<Map<String, ?>> orders = List.of(
    Map.of("id", "10", "customerId", "1", "total", 42.5),
    Map.of("id", "11", "customerId", "2", "total", 19.0));

List<Object> preparedDatoms = new ArrayList<>();
for (Map<String, ?> user : users) {
    preparedDatoms.addAll(userDatoms(user));
}
for (Map<String, ?> order : orders) {
    preparedDatoms.addAll(orderDatoms(order));
}

try (Connection conn =
         Datalevin.initDb(preparedDatoms, "/tmp/import-db", schema)) {
    // Bulk-loaded database is ready to query.
}
```

```python
from datalevin import datom, init_db

schema = {
    ":user/id": {
        ":db/valueType": ":db.type/long",
        ":db/unique": ":db.unique/identity",
    },
    ":user/name": {":db/valueType": ":db.type/string"},
    ":order/id": {
        ":db/valueType": ":db.type/long",
        ":db/unique": ":db.unique/identity",
    },
    ":order/customer": {":db/valueType": ":db.type/ref"},
    ":order/total": {":db/valueType": ":db.type/double"},
}

USER_BASE = 1_000_000
ORDER_BASE = 2_000_000


def user_eid(source_id):
    return USER_BASE + int(source_id)


def order_eid(source_id):
    return ORDER_BASE + int(source_id)


users = [
    {"id": "1", "name": "Alice"},
    {"id": "2", "name": "Bob"},
]

orders = [
    {"id": "10", "customer_id": "1", "total": 42.5},
    {"id": "11", "customer_id": "2", "total": 19.0},
]


def user_datoms(user):
    eid = user_eid(user["id"])
    return [
        datom(eid, ":user/id", int(user["id"])),
        datom(eid, ":user/name", user["name"]),
    ]


def order_datoms(order):
    eid = order_eid(order["id"])
    return [
        datom(eid, ":order/id", int(order["id"])),
        datom(eid, ":order/customer", user_eid(order["customer_id"])),
        datom(eid, ":order/total", order["total"]),
    ]

prepared_datoms = [
    item
    for row in users
    for item in user_datoms(row)
] + [
    item
    for row in orders
    for item in order_datoms(row)
]

with init_db(prepared_datoms, dir="/tmp/import-db", schema=schema) as conn:
    # Bulk-loaded database is ready to query.
    pass
```

```javascript
import { datom, initDb } from "datalevin-node";

const schema = {
  ":user/id": {
    ":db/valueType": ":db.type/long",
    ":db/unique": ":db.unique/identity"
  },
  ":user/name": { ":db/valueType": ":db.type/string" },
  ":order/id": {
    ":db/valueType": ":db.type/long",
    ":db/unique": ":db.unique/identity"
  },
  ":order/customer": { ":db/valueType": ":db.type/ref" },
  ":order/total": { ":db/valueType": ":db.type/double" }
};

const userBase = 1_000_000;
const orderBase = 2_000_000;

const userEid = sourceId => userBase + Number.parseInt(sourceId, 10);
const orderEid = sourceId => orderBase + Number.parseInt(sourceId, 10);

const users = [
  { id: "1", name: "Alice" },
  { id: "2", name: "Bob" }
];

const orders = [
  { id: "10", customerId: "1", total: 42.5 },
  { id: "11", customerId: "2", total: 19.0 }
];

function userDatoms(user) {
  const eid = userEid(user.id);
  return [
    datom(eid, ":user/id", Number.parseInt(user.id, 10)),
    datom(eid, ":user/name", user.name)
  ];
}

function orderDatoms(order) {
  const eid = orderEid(order.id);
  return [
    datom(eid, ":order/id", Number.parseInt(order.id, 10)),
    datom(eid, ":order/customer", userEid(order.customerId)),
    datom(eid, ":order/total", order.total)
  ];
}

const preparedDatoms = [
  ...users.flatMap(userDatoms),
  ...orders.flatMap(orderDatoms)
];

const conn = await initDb(preparedDatoms, {
  dir: "/tmp/import-db",
  schema
});

try {
  // Bulk-loaded database is ready to query.
} finally {
  await conn.close();
}
```

</div>

The same id functions are used on both sides of the relationship:
`:order/customer` stores `(user-eid customer-id)`, not the external customer id
and not a lookup ref. The source ids are also stored as unique identity
attributes (`:user/id`, `:order/id`) so later normal transactions can use lookup
refs such as `[:user/id 1]`. Treat Datalevin's numeric entity ids as internal
implementation ids even when you assign them during a bulk load. They are not a
durable interchange format: another database loaded from the same source data
may use different eids, while the unique source-id attributes stay stable.

For a large import, it is common to stream one table at a time with `fill-db`,
using the same id functions:

<div class="multi-lang">

```clojure
(def db
  (-> (d/empty-db "/tmp/import-db" schema {:closed-schema? true})
      (d/fill-db (mapcat user-datoms users))
      (d/fill-db (mapcat order-datoms orders))))
```

```java
List<Object> userChunk = new ArrayList<>();
for (Map<String, ?> user : users) {
    userChunk.addAll(userDatoms(user));
}

List<Object> orderChunk = new ArrayList<>();
for (Map<String, ?> order : orders) {
    orderChunk.addAll(orderDatoms(order));
}

try (Connection conn = Datalevin.initDb(
         userChunk,
         "/tmp/import-db",
         schema,
         Map.of(":closed-schema?", true))) {
    conn.fillDb(orderChunk);
}
```

```python
user_chunk = [
    item
    for row in users
    for item in user_datoms(row)
]

order_chunk = [
    item
    for row in orders
    for item in order_datoms(row)
]

with init_db(user_chunk,
             dir="/tmp/import-db",
             schema=schema,
             opts={":closed-schema?": True}) as conn:
    conn.fill_db(order_chunk)
```

```javascript
const userChunk = users.flatMap(userDatoms);
const orderChunk = orders.flatMap(orderDatoms);

const conn = await initDb(userChunk, {
  dir: "/tmp/import-db",
  schema,
  opts: { ":closed-schema?": true }
});

try {
  await conn.fillDb(orderChunk);
} finally {
  await conn.close();
}
```

</div>

If the source does not have dense integer ids, build an `eid-by-key` map first:
collect stable keys such as `[:user external-id]` and `[:order external-id]`,
sort or otherwise deterministically order them, assign fresh positive integers,
and use that map when emitting subject ids and reference values. With
`fill-db`, any new ids must be greater than the database's current maximum eid
unless you reserved non-overlapping ranges up front. In Clojure, inspect this
with `(d/max-eid db)`; in Java, use `conn.maxEid()`. In Python and JavaScript,
prefer deterministic reserved ranges in the import plan.

> **Note**: These functions skip transaction-level data integrity checks and
> temporary entity id resolution. You must ensure the datoms are correct before
> calling them: no duplicate entity-id ranges, no unresolved references, values
> must match the declared schema, and every item must use the prepared datom
> shape accepted by the binding, such as `d/datom`, `Datalevin.datom`,
> `datom(...)`, or compact `[eid attr value]` tuples/arrays.


## 4. Choosing an Ingestion Strategy

After the individual tools are clear, the decision flow in Figure 20.1
summarizes when to use each ingestion path.

![Choosing an ingestion strategy: if you can supply final prepared datoms, use bulk load (init-db / fill-db); otherwise, for a one-time rebuildable import use non-durable flags (:nosync / :writemap+:mapasync); otherwise, for interactive or modest write rates use normal transactions (transact!); otherwise use async transactions, with a sync checkpoint when you need durable checkpoints, or async plus WAL for the highest durable throughput](/images/diagrams/ingestion-strategy.svg)

Use bulk load when you can prepare final datoms up front. Use non-durable LMDB
flags only for rebuildable imports. Use normal transactions for interactive
or modest write rates. Use async transactions, often with WAL, when online
ingestion needs higher throughput; add explicit sync checkpoints when the
application needs durable handoff points.


## Summary: The Ingestion Checklist

When you need to ingest data at scale, follow this checklist:

1.  **Use WAL mode when OLTP data must stay queryable**: Choose the durability
    profile using Chapter 19's operational guidance.
2.  **Use async transactions**: `d/transact-async` provides adaptive batching
    for orders-of-magnitude throughput improvement.
3.  **Combine with manual batching**: The effects of auto-batching and manual
    batching compound.
4.  **Sort your data**: Sort by entity id before transacting to minimize B+Tree
    fragmentation.
5.  **Disable the Datalog index cache during large bulk transactions**:
    temporarily set `datalog-index-cache-limit` to `0`, then restore it after
    the import.
6.  **Use `init-db`/`fill-db` and their binding equivalents** for the fastest
    possible bulk load into a database for static data sets.
7.  **Use non-durable LMDB flags only for rebuildable imports**: `:nometasync`,
    `:nosync`, and `:writemap`/`:mapasync` can improve write speed, but they
    change durability (i.e., crash behavior).

For the full durability discussion and maintenance implications, see Chapter
19.

By following these patterns, you can achieve extreme write throughput with
Datalevin.

## References

[1] Huahai Yang,
   [Competing for the JOB with a Triplestore](https://yyhh.org/blog/2024/09/competing-for-the-job-with-a-triplestore/),
   yyhh.org, 2024.

[2] Viktor Leis, Andrey Gubichev, Atanas Mirchev, Peter Boncz, Alfons Kemper,
   and Thomas Neumann,
   [How Good Are Query Optimizers, Really?](https://www.vldb.org/pvldb/vol9/p204-leis.pdf),
   Proceedings of the VLDB Endowment, vol. 9, no. 3, 2015, pp. 204-215.
