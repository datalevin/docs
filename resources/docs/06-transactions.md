---
title: "Transactions and Atomic Updates"
chapter: 6
part: "II — Core APIs: Datalog First, KV When Needed"
---

# Chapter 6: Transactions and Atomic Updates

Every read or write to a Datalevin database happens within a **transaction**.
This chapter focuses on write transactions, so "transaction" here means a write
transaction.

Transactions are the cornerstone of database reliability, ensuring that your
data moves from one consistent state to another, even in the face of concurrent
operations or system crashes. Datalevin provides ACID (Atomicity, Consistency,
Isolation, Durability) guarantees [1], and this chapter explores how.

Figure 6.1 gives an overview of a Datalog transaction's lifecycle. The sections
below explain the details.

![The transaction lifecycle: input data, resolution and validation, datom changes, index updates, and the resulting report](/images/diagrams/transaction-lifecycle.svg)


## 1. The Transaction Model

Datalevin supports several transaction modes with different properties.

### 1.1 Default Mode: Synchronous LMDB Transactions

In its default (non-WAL) mode, a Datalevin transaction is a direct mapping to an
underlying **LMDB transaction**, which has strict ACID guarantees.

- **Atomicity**: All changes within a single `transact!` call are applied as a
  single, atomic unit. They either all succeed or all fail.
- **Durability**: By default, every transaction is synchronously flushed to disk
  (`msync`, the OS flush-to-disk call) before it is confirmed. This guarantees
  that once a transaction is committed, it is durable and will survive a system
  crash. Note that the `msync` call is often an expensive system call that
  takes significant time to finish, depending on the hardware and workload.

Transaction scope is one Datalevin store, i.e. one LMDB environment whose
primary data lives in one memory-mapped data file. In the default non-WAL mode,
a Datalog transaction is ultimately a KV transaction over that store. It can
atomically update the Datalog indexes and any other DBIs that live in the same
store, but it cannot atomically write to two separately opened Datalevin stores
or two different LMDB environments. If you need one atomic unit across multiple
logical datasets, put those datasets in the same store; separate files require
application-level coordination. WAL mode changes the commit path, but it does
not turn Datalevin into a cross-file distributed transaction manager.

### 1.2 Durability Settings

For use cases where maximum write speed is more important than crash-proof
durability (e.g., bulk loading, caching), you can relax the `msync` behavior by
setting LMDB flags during connection creation. For example, using `:nosync` can
significantly improve write throughput at the cost of durability: the `msync`
call is left to OS discretion, and an untimely system crash can result in a
corrupted database. There are also other non-durable flags; see Chapter 10 for
details.

These settings are not only open-time choices. You can explicitly force a flush
with `d/sync`, and you can change LMDB environment flags at runtime with
`d/set-env-flags`. Both functions operate on the KV store handle; for a Datalog
connection, use the backing KV handle returned by `d/datalog-kv`,
`conn.datalogKV()`, `conn.datalog_kv()`, or `await conn.datalogKv()`. This is a
KV store capability, not a Clojure-only Datalog escape hatch: Java, Python, and
JavaScript expose the same operations on their KV objects, using host-language
method names such as `sync`, `setEnvFlags`, and `set_env_flags`.

<div class="multi-lang">

```clojure
(def kv (d/datalog-kv conn))

;; Force a synchronous flush to disk after a burst of faster writes.
(d/sync kv)

;; Change sync behavior for subsequent transactions.
(d/set-env-flags kv #{:nosync} true)  ; relax commit-time sync
(d/set-env-flags kv #{:nosync} false)
```

```java
KV kv = conn.datalogKV();

// Force a synchronous flush to disk after a burst of faster writes.
kv.sync();

// Change sync behavior for subsequent transactions.
kv.setEnvFlags(Set.of("nosync"), true);  // relax commit-time sync
kv.setEnvFlags(Set.of("nosync"), false);
```

```python
kv = conn.datalog_kv()

# Force a synchronous flush to disk after a burst of faster writes.
kv.sync()

# Change sync behavior for subsequent transactions.
kv.set_env_flags({"nosync"}, True)  # relax commit-time sync
kv.set_env_flags({"nosync"}, False)
```

```javascript
const kv = await conn.datalogKv();

// Force a synchronous flush to disk after a burst of faster writes.
await kv.sync();

// Change sync behavior for subsequent transactions.
await kv.setEnvFlags(["nosync"], true);  // relax commit-time sync
await kv.setEnvFlags(["nosync"], false);
```

</div>

Use runtime flag changes deliberately. They affect subsequent transactions on
that store, so they belong in controlled ingestion or maintenance workflows, not
as incidental per-request toggles.

### 1.3 Asynchronous Transactions

Asynchronous transactions change when the caller waits, not what a transaction
means. `d/transact-async` in Clojure, `transactAsync` in Java and JavaScript,
and `transact_async` in Python submit ordinary Datalevin transaction data and
return a future or promise immediately. The transaction still commits as one
atomic unit, produces the usual transaction report, and either succeeds or fails
as a whole.

The important application rule is simple: if later code depends on the write,
wait for the returned future or promise before reading or issuing dependent
writes. Treating async transactions as fire-and-forget can hide validation
errors, constraint violations, and write failures until too late. For independent
high-volume writes, async transactions let Datalevin batch work internally and
improve throughput. Chapter 20 shows ingestion patterns in all four bindings,
and Chapter 19 covers the durability profiles behind those choices.

### 1.4 Choosing a Transaction API

The transaction APIs differ mostly in where the transaction boundary is placed
and when the caller waits. For live commits, they share the same core rule:
within one Datalevin store, committed writes have one serial order.

| API or pattern | Best use | Transaction boundary | Reads inside |
| --- | --- | --- | --- |
| `transact!` / `conn.transact(...)` | Ordinary application writes. | One collection of transaction data commits or aborts as one unit; the call returns after commit. | Application code does not run between tx-data forms, but transaction functions in the data run inside the same transaction. |
| `transact-async` / `transactAsync` / `transact_async` | High-volume independent writes where the caller can wait later. | Same transaction semantics as `transact!`, but the caller receives a future or promise immediately. | Dependent code must wait for the future or promise before reading or submitting dependent writes. |
| `with-transaction` / `withTransaction` / `with_transaction` | Read-modify-write logic and multi-step writes that must be one atomic operation. | One explicit read/write transaction around the callback, with an optional timeout. Nested synchronous transaction calls reuse this transaction instead of opening another one. | Queries through the transaction-bound connection have read-your-writes behavior. No other writer can interleave. |
| Transaction functions such as `:db/cas` or `:db.fn/call` | Conditional or server-side write logic expressed as transaction data. | The function runs inside the containing transaction and returns more transaction data. | The function receives the current Db for that transaction. Its result is committed atomically with the rest of the transaction. |
| `tx-data->simulated-report` and variants | Tests, dry runs, validation previews, and explanations of what a transaction would do. | No live commit; Datalevin prepares the transaction against the supplied Db and returns a report shape. | Reads the supplied Db only. It does not serialize with a live connection. |
| KV-only transaction APIs | Raw KV work without Datalog, or borrowed KV work from a Datalog store. | `transact-kv` applies one KV batch; `with-transaction-kv` opens one explicit KV transaction, with an optional timeout. Chapter 10 covers the full KV API. | Explicit KV transactions also have read-your-writes behavior, and nested KV transactions reuse the active transaction. |

The explicit Datalog transaction callback is available in Clojure, Java, and
Python. JavaScript can still express many conditional writes with one
`conn.transact(...)`, `:db/cas`, transaction functions, or a server-side command
running in the Datalevin-hosting process.

Nested transactions in Datalevin are not independent subtransactions. When code
is already inside a write transaction, another synchronous transaction call uses
that active transaction. There is still only one transaction, not a stack of
separately committed transactions. If the inner code aborts or throws, the
whole active transaction aborts; Datalevin does not provide nested savepoints.

## 2. Transacting Data

The primary function for writing data is `d/transact!`. It takes a connection
and a vector of transaction data. Datalevin is flexible in how you can express
changes.

Here a **transactable value** means one item in the transaction data collection
that Datalevin can prepare into datoms. In normal application code this is most
often an entity map, such as `{:user/name "Alice"}`. Raw datom vectors such as
`[:db/add eid attr value]`, transaction function calls, and Datalevin's
transactable Entity objects can also be transaction data. The examples in this
chapter start with maps because they are the clearest shape for ordinary creates
and updates, then introduce the lower-level forms when they become useful.
Transactable Entity objects are a Clojure-side staging convenience; the Java,
Python, and JavaScript entity wrappers are read/navigation wrappers, so
non-Clojure code should use explicit entity maps, `:db/add` vectors, and
binding helper builders. Chapter 7 covers transactable entities as part of the
Entity API.

Every successful `transact!` returns a **transaction report**. The report
contains the database before and after the transaction, the datoms that were
actually added or retracted, the tempid resolution map, and any transaction
metadata supplied by the caller. When a transaction introduces attributes that
were not previously present in the database schema, the report also includes
`:new-attributes`:

The `:db-before` and `:db-after` fields are mainly for code that needs the
immediate transaction boundary for the datoms affected by this transaction.
`:db-after` lets the caller or an embedded listener interpret the state produced
by the transaction, for example after resolving tempids. `:db-before` lets tools
compare the affected input state with the affected output state, and is
especially useful for simulated transaction reports. These fields are not a
general history API, and they are not meant to answer arbitrary questions such
as "what would this unrelated query have returned before the transaction?" They
are transaction-report context, not a reason to hold on to old Db handles. For
normal application reads, call `d/db` on the connection when you need the
current database state.

The most obvious use is a simulated report: ask Datalevin what a transaction
would do, inspect the produced state, and leave the live connection unchanged.

```clojure
(let [preview (d/tx-data->simulated-report
                (d/db conn)
                [{:db/id -1
                  :user/email "preview@example.com"
                  :user/name "Preview User"}])
      eid     (get (:tempids preview) -1)]
  {:preview-user (d/pull (:db-after preview)
                   [:user/email :user/name]
                   eid)
   :live-user    (d/q '[:find ?e .
                        :where [?e :user/email "preview@example.com"]]
                      (d/db conn))})
;=> {:preview-user {:db/id 101
;                   :user/email "preview@example.com"
;                   :user/name "Preview User"}
;    :live-user nil}
```

For a committed transaction, use `:db-before` and `:db-after` when the report
needs to explain the state change at the application level. The raw `:tx-data`
shows datoms added or retracted; the two Db handles let you ask the same domain
question over the affected data before and after the transaction. Assume account
entity `101` currently has status `:open`:

```clojure
(let [report (d/transact! conn
               [{:db/id 101
                 :account/status :closed}])]
  {:before (d/pull (:db-before report)
             [:account/status]
             101)
   :after  (d/pull (:db-after report)
             [:account/status]
             101)
   :datoms (:tx-data report)})
;=> {:before {:db/id 101, :account/status :open}
;    :after  {:db/id 101, :account/status :closed}
;    :datoms [#datalevin/Datom [101 :account/status :open ... false]
;             #datalevin/Datom [101 :account/status :closed ... true]]}
```

<div class="multi-lang">

```clojure
(def report
  (d/transact! conn
    [{:db/id -1
      :user/email "alice@example.com"
      :user/name "Alice"}]
    {:source :signup-form}))

(select-keys report [:tempids :tx-meta])
;=> {:tempids {-1 101}
;    :tx-meta {:source :signup-form}}
```

```java
Map<?, ?> report = conn.transact(
    Datalevin.tx()
        .entity(Tx.entity(-1)
            .put("user/email", "alice@example.com")
            .put("user/name", "Alice")),
    Map.of(Datalevin.kw("source"), Datalevin.kw("signup-form")));

Map<?, ?> sample = Map.of(
    "tempids", report.get(Datalevin.kw("tempids")),
    "tx-meta", report.get(Datalevin.kw("tx-meta")));
```

```python
report = conn.transact(
    [{":db/id": -1,
      ":user/email": "alice@example.com",
      ":user/name": "Alice"}],
    {":source": ":signup-form"})

sample = {
    ":tempids": report[":tempids"],
    ":tx-meta": report[":tx-meta"],
}
```

```javascript
const report = await conn.transact(
  [{ ":db/id": -1,
     ":user/email": "alice@example.com",
     ":user/name": "Alice" }],
  { ":source": ":signup-form" }
);

const sample = {
  ":tempids": report[":tempids"],
  ":tx-meta": report[":tx-meta"]
};
```

</div>

In the example above, we select two keys of the map to show, because the full
report is large. That report is useful not only as a return value. It is also
the data delivered to transaction listeners, covered later in this chapter.

### 2.1 Entity Maps

The most common way to express an entity is using a map. Each map represents an
entity with its attributes and values:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:user/name "Alice" :user/email "alice@example.com"}
   {:user/name "Bob" :user/email "bob@example.com"}])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/name", "Alice")
        .put("user/email", "alice@example.com"))
    .entity(Tx.entity()
        .put("user/name", "Bob")
        .put("user/email", "bob@example.com")));
```

```python
conn.transact([
    {":user/name": "Alice", ":user/email": "alice@example.com"},
    {":user/name": "Bob", ":user/email": "bob@example.com"}])
```

```javascript
await conn.transact([
  { ":user/name": "Alice", ":user/email": "alice@example.com" },
  { ":user/name": "Bob", ":user/email": "bob@example.com" }
]);
```

</div>

When you omit `:db/id`, Datalevin assigns a new unique entity id automatically.
That entity id is a Datalevin internal `long`. Do not use `:db/id` for a UUID,
slug, email address, or external primary key; put application identity in a
separate unique attribute and address the entity with a lookup ref.

### 2.2 Updating Existing Entities

To update an existing entity, include its entity id in the map:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id 101, :user/active? false}])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity(101)
        .put("user/active?", false)));
```

```python
conn.transact([
    {":db/id": 101, ":user/active?": False}])
```

```javascript
await conn.transact([
  { ":db/id": 101, ":user/active?": false }
]);
```

</div>

This updates the entity with id 101 to set `:user/active?` to false.
If a positive id does not already exist, Datalevin still treats it as a
concrete entity id and asserts facts for that id, advancing the internal entity
id sequence if necessary. That behavior is useful for controlled imports or
restores, but normal application code should not invent positive ids; omit
`:db/id` for new entities or use negative tempids for within-transaction
references.

### 2.3 Automatic Entity Timestamps

Many applications want to know when an entity was first created and when it was
last modified. Datalevin can maintain this information automatically when the
connection is opened with `:auto-entity-time? true`:

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

(def conn
  (d/get-conn
    "/tmp/entity-time-demo"
    {:user/email {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}}
    {:auto-entity-time? true}))

(d/transact! conn
  [{:user/email "alice@example.com"
    :user/name  "Alice"}])

(d/transact! conn
  [{:user/email "alice@example.com"
    :user/name  "Alice Smith"}])
```

```java
import datalevin.Connection;
import datalevin.Datalevin;
import datalevin.Schema;
import datalevin.Tx;

import java.util.Map;

Connection conn = Datalevin.createConn(
    "/tmp/entity-time-demo",
    Datalevin.schema()
        .attr("user/email",
              Schema.attribute()
                  .valueType(Schema.ValueType.STRING)
                  .unique(Schema.Unique.IDENTITY)),
    Map.of("auto-entity-time?", true));

conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "alice@example.com")
        .put("user/name", "Alice")));

conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "alice@example.com")
        .put("user/name", "Alice Smith")));
```

```python
from datalevin import connect

conn = connect(
    "/tmp/entity-time-demo",
    schema={
        ":user/email": {
            ":db/valueType": ":db.type/string",
            ":db/unique": ":db.unique/identity",
        },
    },
    opts={":auto-entity-time?": True})

conn.transact([
    {":user/email": "alice@example.com",
     ":user/name": "Alice"}])

conn.transact([
    {":user/email": "alice@example.com",
     ":user/name": "Alice Smith"}])
```

```javascript
import { connect } from "datalevin-node";

const conn = await connect("/tmp/entity-time-demo", {
  schema: {
    ":user/email": {
      ":db/valueType": ":db.type/string",
      ":db/unique": ":db.unique/identity"
    }
  },
  opts: { ":auto-entity-time?": true }
});

await conn.transact([
  { ":user/email": "alice@example.com",
    ":user/name": "Alice" }
]);

await conn.transact([
  { ":user/email": "alice@example.com",
    ":user/name": "Alice Smith" }
]);
```

</div>

With this option enabled, a newly created entity receives both `:db/created-at`
and `:db/updated-at`. Later transactions that modify the entity update
`:db/updated-at` while preserving `:db/created-at`. The values are stored as
epoch milliseconds using `:db.type/long`.

These attributes describe Datalevin's system-level mutation time. They are not a
replacement for domain event times. If your application needs to know when an
order was placed, when an invoice was paid, or when an episode happened, model
that explicitly with attributes such as `:order/placed-at`,
`:invoice/paid-at`, or `:episode/timestamp`. Automatic entity timestamps answer
"when did this entity change in the database?", not "when did the real-world
event occur?"

Automatic timestamps also store only the current created/updated values. For a
full audit trail, model audit events explicitly or use transaction-log based
operations, if WAL mode is enabled, such as the txlog tools covered in
Chapter 19.

### 2.4 Tempids

When creating new entities that will be referenced by other entities in the
same transaction, use **tempids**: temporary entity ids that act as
placeholders. Negative numbers are commonly used as tempids:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id -1, :user/name "Alice" :user/friend -2}
   {:db/id -2, :user/name "Bob"}])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("user/name", "Alice")
        .put("user/friend", -2))
    .entity(Tx.entity(-2)
        .put("user/name", "Bob")));
```

```python
conn.transact([
    {":db/id": -1, ":user/name": "Alice", ":user/friend": -2},
    {":db/id": -2, ":user/name": "Bob"}])
```

```javascript
await conn.transact([
  { ":db/id": -1, ":user/name": "Alice", ":user/friend": -2 },
  { ":db/id": -2, ":user/name": "Bob" }
]);
```

</div>

In this example, `-1` references `-2` as a friend. Datalevin resolves these
tempids during the transaction, replacing them with real entity ids.

Tempids are especially useful when the data has cycles. Two new entities can
refer to each other before either one has a permanent entity id:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id -1
    :user/name "Alice"
    :user/friend -2}
   {:db/id -2
    :user/name "Bob"
    :user/friend -1}])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("user/name", "Alice")
        .put("user/friend", -2))
    .entity(Tx.entity(-2)
        .put("user/name", "Bob")
        .put("user/friend", -1)));
```

```python
conn.transact([
    {":db/id": -1,
     ":user/name": "Alice",
     ":user/friend": -2},
    {":db/id": -2,
     ":user/name": "Bob",
     ":user/friend": -1}])
```

```javascript
await conn.transact([
  { ":db/id": -1,
    ":user/name": "Alice",
    ":user/friend": -2 },
  { ":db/id": -2,
    ":user/name": "Bob",
    ":user/friend": -1 }
]);
```

</div>

Both references are resolved in the same transaction, so the database never
needs a half-built intermediate state where only one side of the cycle exists.

### 2.5 Lookup Refs

Instead of knowing the entity id, you can use a **lookup ref** to identify an existing entity by a unique attribute. A lookup ref is a vector `[attribute value]`:

<div class="multi-lang">

```clojure
(d/transact! conn
  [[:db/add [:user/email "alice@example.com"] :user/active? false]])
```

```java
conn.transact(Datalevin.tx()
    .add(List.of("user/email", "alice@example.com"),
         "user/active?",
         false));
```

```python
conn.transact([
    [":db/add", [":user/email", "alice@example.com"], ":user/active?", False]])
```

```javascript
await conn.transact([
  [":db/add", [":user/email", "alice@example.com"], ":user/active?", false]
]);
```

</div>

This finds the entity with `:user/email` equal to `"alice@example.com"` and
updates it. If no entity exists with that email, the transaction will fail.

Lookup refs are particularly useful when you only have a unique identifier (like
an email) but not the entity id.

### 2.6 Unique Attributes and Upsert

When an attribute is marked as `:db.unique/identity`, Datalevin automatically
performs an **upsert**: if an entity with that value exists, it updates that
entity; otherwise, it creates a new one:

<div class="multi-lang">

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

```java
// First, add a schema with unique identity
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("db/ident", Datalevin.kw("user/email"))
        .put("db/valueType", Datalevin.kw("db.type/string"))
        .put("db/unique", Datalevin.kw("db.unique/identity"))));

// Now upsert based on email
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "alice@example.com")
        .put("user/name", "Alice v2")));
```

```python
# First, add a schema with unique identity
conn.transact([
    {":db/ident": ":user/email",
     ":db/valueType": ":db.type/string",
     ":db/unique": ":db.unique/identity"}])

# Now upsert based on email
conn.transact([
    {":user/email": "alice@example.com", ":user/name": "Alice v2"}])
```

```javascript
// First, add a schema with unique identity
await conn.transact([
  { ":db/ident": ":user/email",
    ":db/valueType": ":db.type/string",
    ":db/unique": ":db.unique/identity" }
]);

// Now upsert based on email
await conn.transact([
  { ":user/email": "alice@example.com", ":user/name": "Alice v2" }
]);
```

</div>

If `"alice@example.com"` already exists, this updates the existing entity. If
not, it creates a new one. This eliminates the need to check for existence
before transacting.

### 2.7 Raw Datom Vectors

For fine-grained control, you can express changes as raw datom vectors `[op
entity attribute value]`:

<div class="multi-lang">

```clojure
(d/transact! conn
  [[:db/add -1 :user/name "Alice"]
   [:db/add -1 :user/email "alice@example.com"]
   [:db/retract 101 :user/active? true]])
```

```java
conn.transact(Datalevin.tx()
    .add(-1, "user/name", "Alice")
    .add(-1, "user/email", "alice@example.com")
    .retract(101, "user/active?", true));
```

```python
conn.transact([
    [":db/add", -1, ":user/name", "Alice"],
    [":db/add", -1, ":user/email", "alice@example.com"],
    [":db/retract", 101, ":user/active?", True]])
```

```javascript
await conn.transact([
  [":db/add", -1, ":user/name", "Alice"],
  [":db/add", -1, ":user/email", "alice@example.com"],
  [":db/retract", 101, ":user/active?", true]
]);
```

</div>

The operations are:
- `:db/add` — adds or updates an attribute value
- `:db/retract` — removes an attribute value

### 2.8 Retracting Attributes and Entities

Use `:db/retract` when you want to remove one attribute value. Use
`:db.fn/retractAttribute` when you want to remove an entire attribute from an
entity, regardless of its current value. Use `:db/retractEntity` when you want
to remove an entity as a logical object.

<div class="multi-lang">

```clojure
;; Remove one known value.
(d/transact! conn
  [[:db/retract [:user/email "alice@example.com"]
                :user/status
                "inactive"]])

;; Remove the whole :user/status attribute from Alice.
(d/transact! conn
  [[:db.fn/retractAttribute [:user/email "alice@example.com"]
                            :user/status]])

;; Remove Alice as an entity.
(d/transact! conn
  [[:db/retractEntity [:user/email "alice@example.com"]]])
```

```java
// Remove one known value.
conn.transact(Datalevin.tx()
    .retract(List.of("user/email", "alice@example.com"),
             "user/status",
             "inactive"));

// Remove the whole :user/status attribute from Alice.
conn.transact(Datalevin.tx()
    .raw(List.of(Datalevin.kw("db.fn/retractAttribute"),
                 List.of(Datalevin.kw("user/email"), "alice@example.com"),
                 Datalevin.kw("user/status"))));

// Remove Alice as an entity.
conn.transact(Datalevin.tx()
    .retractEntity(List.of("user/email", "alice@example.com")));
```

```python
# Remove one known value.
conn.transact([
    [":db/retract",
     [":user/email", "alice@example.com"],
     ":user/status",
     "inactive"]])

# Remove the whole :user/status attribute from Alice.
conn.transact([
    [":db.fn/retractAttribute",
     [":user/email", "alice@example.com"],
     ":user/status"]])

# Remove Alice as an entity.
conn.transact([
    [":db/retractEntity", [":user/email", "alice@example.com"]]])
```

```javascript
// Remove one known value.
await conn.transact([
  [":db/retract",
   [":user/email", "alice@example.com"],
   ":user/status",
   "inactive"]
]);

// Remove the whole :user/status attribute from Alice.
await conn.transact([
  [":db.fn/retractAttribute",
   [":user/email", "alice@example.com"],
   ":user/status"]
]);

// Remove Alice as an entity.
await conn.transact([
  [":db/retractEntity", [":user/email", "alice@example.com"]]
]);
```

</div>

`retractEntity` accepts an entity id or a lookup ref. It retracts facts where
the entity is the subject, and it also retracts declared `:db.type/ref` facts
that point to the entity. If the entity owns child entities through attributes
declared with `:db/isComponent true`, those component children are retracted
recursively. This is usually the right operation for deleting an entity that may
be referenced elsewhere.

For example, with this schema:

<div class="multi-lang">

```clojure
{:order/id    {:db/valueType :db.type/string
               :db/unique    :db.unique/identity}
 :order/items {:db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/many
               :db/isComponent true}
 :line/sku    {:db/valueType :db.type/string}}
```

```java
Schema schema = Datalevin.schema()
    .attr("order/id",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY))
    .attr("order/items",
          Schema.attribute()
              .valueType(Schema.ValueType.REF)
              .cardinality(Schema.Cardinality.MANY)
              .isComponent(true))
    .attr("line/sku",
          Schema.attribute().valueType(Schema.ValueType.STRING));
```

```python
schema = {
    ":order/id": {
        ":db/valueType": ":db.type/string",
        ":db/unique": ":db.unique/identity",
    },
    ":order/items": {
        ":db/valueType": ":db.type/ref",
        ":db/cardinality": ":db.cardinality/many",
        ":db/isComponent": True,
    },
    ":line/sku": {
        ":db/valueType": ":db.type/string",
    },
}
```

```javascript
const schema = {
  ":order/id": {
    ":db/valueType": ":db.type/string",
    ":db/unique": ":db.unique/identity"
  },
  ":order/items": {
    ":db/valueType": ":db.type/ref",
    ":db/cardinality": ":db.cardinality/many",
    ":db/isComponent": true
  },
  ":line/sku": {
    ":db/valueType": ":db.type/string"
  }
};
```

</div>

Deleting the order also deletes its owned line-item entities:

<div class="multi-lang">

```clojure
(d/transact! conn
  [[:db/retractEntity [:order/id "o-1001"]]])
```

```java
conn.transact(Datalevin.tx()
    .retractEntity(List.of("order/id", "o-1001")));
```

```python
conn.transact([
    [":db/retractEntity", [":order/id", "o-1001"]]])
```

```javascript
await conn.transact([
  [":db/retractEntity", [":order/id", "o-1001"]]
]);
```

</div>

Use component relationships only for ownership. The `:db/isComponent` flag
matters when `:db/retractEntity` is applied to the owning entity: owned children,
such as line items inside an order, are retracted recursively. It is not the
mechanism for merely removing a link. To remove one relationship value, use
`:db/retract`; to remove all values for a relationship attribute, use
`:db.fn/retractAttribute`.

For example, `:order/items` is a component relationship because a line item has
no useful lifecycle apart from its order. By contrast, `:order/customer`,
`:issue/assignee`, or `:team/member` should usually not be components: deleting
an order should not delete the customer, changing an assignee should not delete
the user, and removing a member from a team should not delete the member
entity. If either side of a non-component relationship is later retracted with
`:db/retractEntity`, Datalevin removes the reference datoms that point at the
retracted entity; it does not recursively delete the other independent entity.

### 2.9 Mixed Forms

You can mix all these forms in a single transaction:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:user/email "new@example.com" :user/name "New User"}  ; upsert
   [:db/add [:user/email "alice@example.com"] :user/status "active"] ; lookup ref
   {:db/id -1, :user/friend [[:user/email "bob@example.com"]]} ; tempid + lookup ref
   [:db/add -1 :user/notes "Added via datom vector"]]) ; raw datom
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "new@example.com")
        .put("user/name", "New User")) // upsert
    .add(List.of("user/email", "alice@example.com"),
         "user/status",
         "active") // lookup ref
    .entity(Tx.entity(-1)
        .put("user/friend",
             List.of(List.of("user/email", "bob@example.com")))) // tempid + lookup ref
    .add(-1, "user/notes", "Added via datom vector")); // raw datom
```

```python
conn.transact([
    {":user/email": "new@example.com", ":user/name": "New User"},  # upsert
    [":db/add", [":user/email", "alice@example.com"], ":user/status", "active"],  # lookup ref
    {":db/id": -1, ":user/friend": [[":user/email", "bob@example.com"]]},  # tempid + lookup ref
    [":db/add", -1, ":user/notes", "Added via datom vector"]])  # raw datom
```

```javascript
await conn.transact([
  { ":user/email": "new@example.com", ":user/name": "New User" },  // upsert
  [":db/add", [":user/email", "alice@example.com"], ":user/status", "active"],  // lookup ref
  { ":db/id": -1, ":user/friend": [[":user/email", "bob@example.com"]] },  // tempid + lookup ref
  [":db/add", -1, ":user/notes", "Added via datom vector"]  // raw datom
]);
```

</div>

This flexibility allows you to choose the most convenient form for each
operation in your transaction.

### 2.10 Typed Values, Validation, and Coercion

Datalevin works best when important attributes have explicit value types in the
schema, such as `:db.type/long`, `:db.type/string`, `:db.type/boolean`,
`:db.type/uuid`, or `:db.type/instant`. A declared `:db/valueType` tells
Datalevin how to encode values, compare them, build indexes, and canonicalize
transaction input.

Strict input validation is controlled separately by `:validate-data?`:

| Configuration | Write Behavior |
| :--- | :--- |
| No `:db/valueType` | Values are stored as serialized EDN data. This is flexible, but not ideal for typed range access or predictable cross-language input. |
| Declared `:db/valueType`, default `:validate-data? false` | Datalevin uses the declared type and coerces or canonicalizes values where possible before encoding. |
| Declared `:db/valueType` with `{:validate-data? true}` | Datalevin rejects values that do not already match the declared runtime type. |

When you transact data without a value type in schema, Datalevin serializes it
as an EDN binary blob. In Clojure/JVM code, where numeric literals and casts can
produce different boxed numeric types, this can sometimes behave unexpectedly:

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

The query returns no results because the value was stored as an integer
(32-bit), but the query uses a long (64-bit). They are not equal.

However, if you specify a type for `:my-entity/val`, e.g. `:db.type/long`,
Datalevin does type coercion for you, so your `(int 42)` will be stored as a
`long` instead. If you open the database with `{:validate-data? true}`, the
same declared schema becomes stricter: malformed input fails before it is
written instead of being corrected where possible.

Use coercion for trusted internal inputs and imports where convenience matters.
Use `:validate-data? true` at system boundaries where malformed user, JSON, or
remote-client data should fail early. Chapter 11 covers schema validation,
coercion, and schema evolution in more detail.


## 3. Observing Committed Transactions with `listen!`

Many applications need to react after a write commits: invalidate a cache,
notify a UI session, enqueue a background job, update an in-process projection,
or append an audit record in another system. `listen!` in Clojure, and
`listen` in Java, Python, and JavaScript, register an in-process callback on a
Datalevin connection. After a transaction commits, Datalevin calls the callback
with the transaction report:

<div class="multi-lang">

```clojure
(def listener-key
  (d/listen!
    conn
    :audit-log
    (fn [{:keys [tx-data tx-meta tempids]}]
      (println "committed" (count tx-data) "datoms")
      (println "metadata" tx-meta)
      (println "tempids" tempids))))

(d/transact!
  conn
  [{:db/id -1
    :user/email "ada@example.com"
    :user/name "Ada"}]
  {:request-id "req-123"})

(d/unlisten! conn listener-key)
```

```java
Object listenerKey =
    conn.listen("audit-log", report -> {
        List<?> txData = (List<?>) report.get(Datalevin.kw("tx-data"));
        System.out.println("committed " + txData.size() + " datoms");
        System.out.println("metadata " + report.get(Datalevin.kw("tx-meta")));
        System.out.println("tempids " + report.get(Datalevin.kw("tempids")));
    });

conn.transact(
    Datalevin.tx()
        .entity(Tx.entity()
            .put("user/email", "ada@example.com")
            .put("user/name", "Ada")),
    Map.of(Datalevin.kw("request-id"), "req-123"));

conn.unlisten(listenerKey);
```

```python
def audit_log(report):
    print("committed", len(report[":tx-data"]), "datoms")
    print("metadata", report[":tx-meta"])
    print("tempids", report[":tempids"])

listener_key = conn.listen(audit_log, key="audit-log")

conn.transact(
    [{":db/id": -1,
      ":user/email": "ada@example.com",
      ":user/name": "Ada"}],
    {":request-id": "req-123"})

conn.unlisten(listener_key)
```

```javascript
const listenerKey = await conn.listen((report) => {
  console.log("committed", report[":tx-data"].length, "datoms");
  console.log("metadata", report[":tx-meta"]);
  console.log("tempids", report[":tempids"]);
}, "audit-log");

await conn.transact(
  [{ ":db/id": -1,
     ":user/email": "ada@example.com",
     ":user/name": "Ada" }],
  { ":request-id": "req-123" }
);

await conn.unlisten(listenerKey);
```

</div>

The listener registration call returns the listener key. When you pass a key
explicitly, as above, that same key is returned and can be passed to
`unlisten!` in Clojure or `unlisten` in Java, Python, and JavaScript. When you
register without a key, Datalevin generates and returns one for you.

The callback receives the same transaction report returned by `transact!`:

| Report key | Value |
| :--- | :--- |
| `:db-before` | The Datalog Db handle used as the transaction input. |
| `:db-after` | The Datalog Db handle produced by the transaction. |
| `:tx-data` | The full datom objects actually added or retracted by the transaction; Chapter 15 explains their `:e`, `:a`, `:v`, `:tx`, and `:added` fields. |
| `:tempids` | Map from transaction tempids to assigned entity ids. |
| `:tx-meta` | The metadata value supplied as the optional third argument to `transact!`, or `nil`. |
| `:new-attributes` | Optional vector of attribute idents first introduced by this transaction. This appears only when the transaction adds facts for attributes that were not already in the schema. |

The listener key can be any unique value meaningful to the application.
Registering another listener with the same key replaces the previous callback,
so listener registration is idempotent:

<div class="multi-lang">

```clojure
(d/listen! conn :cache (fn [report] (invalidate-cache! report)))
(d/listen! conn :cache (fn [report] (enqueue-cache-refresh! report)))
```

```java
conn.listen("cache", report -> invalidateCache(report));
conn.listen("cache", report -> enqueueCacheRefresh(report));
```

```python
conn.listen(invalidate_cache, key="cache")
conn.listen(enqueue_cache_refresh, key="cache")
```

```javascript
await conn.listen((report) => invalidateCache(report), "cache");
await conn.listen((report) => enqueueCacheRefresh(report), "cache");
```

</div>

Listeners observe committed transactions; they do not make the listener's side
effects part of the original transaction. If a callback writes to a log file,
publishes to a queue, or calls another service, that work can fail separately
from the Datalevin commit. Keep listener callbacks small and predictable. For
expensive work, enqueue a lightweight task and let a worker handle it.

For durable domain events, the strongest pattern is to transact the event as
data in the same Datalevin transaction, then use the listener only to wake a
publisher or projector:

<div class="multi-lang">

```clojure
(d/transact!
  conn
  [{:user/email "ada@example.com"
    :user/name "Ada"}
   {:event/type :event.type/user-created
    :event/user [:user/email "ada@example.com"]
    :event/request-id "req-123"}])

(d/listen!
  conn
  :event-publisher
  (fn [_report]
    (publish-pending-events! conn)))
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "ada@example.com")
        .put("user/name", "Ada"))
    .entity(Tx.entity()
        .put("event/type", Datalevin.kw("event.type/user-created"))
        .put("event/user", List.of("user/email", "ada@example.com"))
        .put("event/request-id", "req-123")));

conn.listen("event-publisher", report -> publishPendingEvents(conn));
```

```python
conn.transact([
    {":user/email": "ada@example.com",
     ":user/name": "Ada"},
    {":event/type": ":event.type/user-created",
     ":event/user": [":user/email", "ada@example.com"],
     ":event/request-id": "req-123"}])

conn.listen(lambda _report: publish_pending_events(conn),
            key="event-publisher")
```

```javascript
await conn.transact([
  { ":user/email": "ada@example.com",
    ":user/name": "Ada" },
  { ":event/type": ":event.type/user-created",
    ":event/user": [":user/email", "ada@example.com"],
    ":event/request-id": "req-123" }
]);

await conn.listen((_report) => publishPendingEvents(conn), "event-publisher");
```

</div>

This keeps the fact that the event happened inside the database transaction,
while allowing delivery to be retried independently.


## 4. Atomic Read-Modify-Write with `with-transaction`

Often, you need to read a value, modify it, and write it back as a single
atomic operation. This is a classic race condition if not handled carefully.
In Clojure, Datalevin provides the `with-transaction` macro for this purpose:

<div class="multi-lang">

```clojure
(d/with-transaction [tx conn]
  (let [current-balance (d/q '[:find ?bal . :where [101 :account/balance ?bal]] (d/db tx))
        new-balance (- current-balance 100)]
    (d/transact! tx [{:db/id 101, :account/balance new-balance}])))
```

```java
conn.withTransaction(tx -> {
    Number currentBalance =
        (Number) tx.query("[:find ?bal . :where [101 :account/balance ?bal]]");
    long newBalance = currentBalance.longValue() - 100;
    tx.transact(Datalevin.tx()
        .entity(Tx.entity(101)
            .put("account/balance", newBalance)));
    return null;
});
```

```python
def withdraw(tx):
    current_balance = tx.query(
        "[:find ?bal . :where [101 :account/balance ?bal]]")
    tx.transact([
        {":db/id": 101,
         ":account/balance": current_balance - 100}])

conn.with_transaction(withdraw)
```

</div>

The transaction callback ensures that the read and the write happen within the
same isolated transaction, preventing another concurrent write from interfering.
In isolation terminology, Datalevin write transactions are serializable within
one store: committed writes have a single order, and a `with-transaction` body
runs inside one read/write transaction in that order. Queries against the
transaction-bound connection, such as `(d/db tx)` or `@tx`, read that
transaction's state, including writes already performed earlier in the body. No
other writer can commit in the middle of that read-modify-write sequence. In
other words, code inside `with-transaction` has the usual read-your-writes
behavior that application developers expect from a transaction.

This guarantee is narrower than saying that every query is a transaction.
Outside `with-transaction`, a query runs against the Db supplied to it. That read
view is stable for the query and isolated from concurrent writes, but it is not
an application-level read/write transaction unless the related reads and writes
are placed inside `with-transaction` or encoded as one conditional transaction.

In Clojure this is the `with-transaction` macro; Java and Python expose the same
embedded Datalog transaction callback as `conn.withTransaction(...)` and
`conn.with_transaction(...)`. JavaScript does not expose this Datalog callback
because of JVM bridge re-entry constraints; use conditional transaction forms
such as `:db/cas`, transaction functions, a single `conn.transact(...)`, or an
application command running inside the Datalevin-hosting process when the
read-modify-write logic must be serialized.

Explicit transaction callbacks can also take a timeout for the callback body:

<div class="multi-lang">

```clojure
(d/with-transaction [tx conn {:timeout-ms 5000}]
  (d/transact! tx [{:db/id 101 :account/checked true}]))
```

```java
conn.withTransaction(5000L, tx -> {
    tx.transact(Datalevin.tx()
        .entity(Tx.entity(101)
            .put("account/checked", true)));
    return null;
});
```

```python
conn.with_transaction(
    lambda tx: tx.transact([
        {":db/id": 101, ":account/checked": True}]),
    timeout_ms=5000)
```

</div>

The default explicit transaction timeout is unset. It can be read or changed
with `d/explicit-transaction-timeout` and
`d/set-explicit-transaction-timeout!`, with corresponding
`Datalevin.explicitTransactionTimeout(...)` /
`Datalevin.setExplicitTransactionTimeout(...)` methods in Java and
`explicit_transaction_timeout` / `set_explicit_transaction_timeout` in Python. A
per-call timeout overrides the default; `nil`, `null`, or `None`
disables the default for that call. If the timeout expires, Datalevin interrupts
the transaction body, aborts the transaction, and reports
`:type :transaction/timeout` with `:timeout-ms`. Code that ignores interruption
may continue running until it returns, so transaction callbacks should remain
small and bounded.

`with-transaction` is a serialization tool, not a general retry loop. If the
body throws, the transaction aborts. Logical failures such as CAS mismatch,
lookup-ref miss, validation failure, or unique conflict are returned to the
caller. Chapter 22 pulls those cases together into a production retry and
error-handling policy.

### 4.1 Mixing Datalog and KV Writes

Because a local Datalog store is built on Datalevin's KV store, embedded
Clojure, Java, and Python code can use the Datalog transaction callback to
update Datalog data and custom KV DBIs as one atomic unit. The important point
is to use the KV instance inside the transactional connection, not a separately
opened `open-kv` handle on the same directory.

Use the public Datalog/KV helper to get the KV handle that belongs to a Datalog
connection or DB:

<div class="multi-lang">

```clojure
(def kv (d/datalog-kv conn))
```

```java
KV kv = conn.datalogKV();
```

```python
kv = conn.datalog_kv()
```

```javascript
const kv = await conn.datalogKv();
```

</div>

The returned KV handle is owned by the Datalog connection. Do not close it
separately; close the Datalog connection instead. Java, Python, and JavaScript
expose the same borrowed handle as `conn.datalogKV()`, `conn.datalog_kv()`, and
`await conn.datalogKv()` for setup and direct same-store KV operations. The
transaction-bound mixed-write pattern below is available in Clojure, Java, and
Python because those bindings expose Datalog transaction callbacks. In
JavaScript, use a transaction function or an application command on the server
side if the Datalog and KV updates must commit atomically. Open application DBIs
during setup, then use the transaction-bound connection inside
`with-transaction` for both parts of the write:

<!-- pdf-listing: Mixing Datalog and key-value writes in one transaction -->

<div class="multi-lang">

```clojure
(let [kv (d/datalog-kv conn)]
  ;; DBI opening is idempotent, so this is safe during application startup.
  (d/open-dbi kv "audit-log")

  (d/with-transaction [tx conn]
    (let [tx-kv (d/datalog-kv tx)]
      (d/transact! tx
        [{:order/id     "o-1001"
          :order/status :order.status/paid}])

      (d/transact-kv tx-kv "audit-log"
        [[:put "o-1001"
          {:event/type :order/paid
           :order/id   "o-1001"}]]
        :string :data))))
```

```java
KV kv = conn.datalogKV();
// DBI opening is idempotent, so this is safe during application startup.
kv.openDbi("audit-log");

conn.withTransaction(tx -> {
    KV txKv = tx.datalogKV();

    tx.transact(Datalevin.tx()
        .entity(Tx.entity()
            .put("order/id", "o-1001")
            .put("order/status", Datalevin.kw("order.status/paid"))));

    txKv.transact(
        "audit-log",
        List.of(List.of(
            Datalevin.kw("put"),
            "o-1001",
            Map.of(Datalevin.kw("event/type"), Datalevin.kw("order/paid"),
                   Datalevin.kw("order/id"), "o-1001"))),
        "string",
        "data");

    return null;
});
```

```python
kv = conn.datalog_kv()
# DBI opening is idempotent, so this is safe during application startup.
kv.open_dbi("audit-log")

def mark_paid(tx):
    tx_kv = tx.datalog_kv()

    tx.transact([
        {":order/id": "o-1001",
         ":order/status": ":order.status/paid"}])

    tx_kv.transact(
        [(":put",
          "o-1001",
          {":event/type": ":order/paid",
           ":order/id": "o-1001"})],
        dbi_name="audit-log",
        k_type=":string",
        v_type=":data")

conn.with_transaction(mark_paid)
```

</div>

If the block returns normally, both the Datalog datoms and the KV entry are
committed. If the block throws, both parts are aborted; in Clojure, an explicit
`d/abort-transact` has the same effect. This only works for DBIs that live in
the same local Datalevin store; it is not a cross-file transaction mechanism.


## 5. Transaction Functions

Datalevin does not use a bare symbolic list form such as `(my-tx-fn arg)` for
transaction functions. Transaction functions are transaction data vectors that
expand to more transaction data while the transaction is being prepared. They
run against the current DB object and are committed atomically with the rest of
the transaction.

A transaction function can be one of the supported vector forms:

- `[:db.fn/call f arg ...]` calls an inline function, an installed function, or
  a user defined function (UDF) descriptor.
- `[:some/installed-fn arg ...]` calls an installed transaction function whose
  entity has `:db/ident :some/installed-fn`.
- `[:db/cas e a old new]` or `[:db.fn/cas e a old new]` performs
  compare-and-swap.
- `[:db.fn/retractAttribute e a]`, `[:db.fn/retractEntity e]`, and
  `[:db/retractEntity e]` are built-in transaction functions for retraction.
- `[:db.fn/patchIdoc ...]` patches an idoc value; idoc document modeling is
  covered in Chapter 14.

There are two ways to install a named application transaction function. Choose
based on where the implementation lives:

| Mechanism | Stored attribute | Use when |
| :--- | :--- | :--- |
| Descriptor-backed UDF | `:db/udf` | The function must be callable from Java, Python, JavaScript, client/server deployments, or another runtime that resolves descriptors through a registry. |
| Interpreted Clojure function | `:db/fn` | The function is Clojure code for embedded Clojure-style environments and can be represented with `datalevin.interpret` helpers. |

Both mechanisms install an entity with `:db/ident`, and both can be invoked with
`[:some/ident arg ...]` or through `:db.fn/call`. The difference is not the call
shape; it is how the implementation is stored and resolved.

### 5.1 Compare-and-Swap

Use `:db/cas` when the write should succeed only if the current value is what
you expect. The entity position may be an entity id or a lookup ref.

<div class="multi-lang">

```clojure
(d/transact! conn
  [[:db/cas [:user/email "alice@example.com"]
    :account/balance
    100
    75]])
```

```java
conn.transact(Datalevin.tx()
    .raw(List.of(Datalevin.kw("db/cas"),
                 List.of("user/email", "alice@example.com"),
                 Datalevin.kw("account/balance"),
                 100,
                 75)));
```

```python
conn.transact([
    [":db/cas",
     [":user/email", "alice@example.com"],
     ":account/balance",
     100,
     75]])
```

```javascript
await conn.transact([
  [":db/cas",
   [":user/email", "alice@example.com"],
   ":account/balance",
   100,
   75]
]);
```

</div>

If Alice's balance is not currently `100`, the whole transaction fails. This is
useful for conditional updates that must not silently overwrite newer data. A
CAS failure is a logical conflict, not an automatic retry signal; Chapter 22
shows how to re-read and retry when that is the correct application behavior.

### 5.2 Transaction UDFs

For arbitrary user defined functions (UDF), Datalevin allows descriptor-backed
transaction functions. The same descriptor and registry model is available from
Clojure, Java, Python, and JavaScript, and it also works in client/server
deployments as long as the runtime that executes the transaction can resolve the
UDF implementation.

Store the descriptor in `:db/udf`, open the database with a
runtime UDF registry or resolver, and call it by descriptor or by installed
`:db/ident`. Transaction UDF descriptors use `:udf/kind :tx-fn`.

<div class="multi-lang">

<!-- pdf-listing: Descriptor-backed transaction function -->

```clojure
(require '[datalevin.udf :as udf])

(def descriptor
  {:udf/lang :java
   :udf/kind :tx-fn
   :udf/id   :user/bootstrap})

(def registry
  (doto (udf/create-registry)
    (udf/register! descriptor
      (fn [_db email name]
        [{:db/id       -1
          :user/email  email
          :user/name   name}]))))

(def conn
  (d/create-conn
    "/tmp/tx-udf-demo"
    {:user/email {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}}
    {:runtime-opts {:udf-registry registry}}))

;; Call the descriptor directly.
(d/transact! conn
  [[:db.fn/call descriptor "ada@example.com" "Ada"]])

;; Or install the descriptor and call it by :db/ident.
(d/transact! conn
  [{:db/ident :user/bootstrap
    :db/udf   descriptor}])

(d/transact! conn
  [[:user/bootstrap "bob@example.com" "Bob"]])
```

```java
import datalevin.Connection;
import datalevin.Datalevin;
import datalevin.Schema;
import datalevin.Tx;

import java.util.List;
import java.util.Map;

Map<String, Object> descriptor = Map.of(
    "udf/lang", ":java",
    "udf/kind", ":tx-fn",
    "udf/id", ":user/bootstrap");

Object registry = Datalevin.createUdfRegistry();
Datalevin.registerUdf(registry, descriptor, args -> {
    String email = (String) args.get(1);
    String name = (String) args.get(2);
    return List.of(Tx.entity(-1)
        .put("user/email", email)
        .put("user/name", name)
        .build());
});

try (Connection conn = Datalevin.createConn(
        "/tmp/tx-udf-demo",
        Datalevin.schema()
            .attr("user/email",
                  Schema.attribute()
                      .valueType(Schema.ValueType.STRING)
                      .unique(Schema.Unique.IDENTITY)),
        Map.of("runtime-opts", Map.of("udf-registry", registry)))) {

    conn.transact(Datalevin.tx()
        .raw(List.of(Datalevin.kw("db.fn/call"),
                     descriptor,
                     "ada@example.com",
                     "Ada")));

    conn.transact(Datalevin.tx()
        .entity(Tx.entity()
            .put("db/ident", Datalevin.kw("user/bootstrap"))
            .put("db/udf", descriptor)));

    conn.transact(Datalevin.tx()
        .raw(List.of(Datalevin.kw("user/bootstrap"),
                     "bob@example.com",
                     "Bob")));
}
```

```python
from datalevin import connect, create_udf_registry, interop

descriptor = {
    ":udf/lang": ":java",
    ":udf/kind": ":tx-fn",
    ":udf/id": ":user/bootstrap",
}

registry = create_udf_registry()
registry.register(
    descriptor,
    lambda _db, email, name: [{
        ":db/id": -1,
        ":user/email": email,
        ":user/name": name,
    }])

raw = interop()
ident = raw.keyword(":user/bootstrap")

with connect(
    "/tmp/tx-udf-demo",
    schema={
        ":user/email": {
            ":db/valueType": ":db.type/string",
            ":db/unique": ":db.unique/identity",
        },
    },
    opts={":runtime-opts": {":udf-registry": registry}},
) as conn:
    conn.transact([[":db.fn/call", descriptor, "ada@example.com", "Ada"]])
    conn.transact([{":db/ident": ident, ":db/udf": descriptor}])
    conn.transact([[":user/bootstrap", "bob@example.com", "Bob"]])
```

```javascript
import {
  connect,
  createUdfRegistry,
  keyword,
  udfDescriptor
} from "datalevin-node";

const registry = await createUdfRegistry();
const descriptor = udfDescriptor(":user/bootstrap", { kind: ":tx-fn" });
const ident = await keyword(":user/bootstrap");

await registry.txUdf(":user/bootstrap", (_db, email, name) => [{
  ":db/id": -1,
  ":user/email": email,
  ":user/name": name
}]);

const conn = await connect("/tmp/tx-udf-demo", {
  schema: {
    ":user/email": {
      ":db/valueType": ":db.type/string",
      ":db/unique": ":db.unique/identity"
    }
  },
  opts: { ":runtime-opts": { ":udf-registry": registry } }
});

try {
  await conn.transact([
    [":db.fn/call", descriptor, "ada@example.com", "Ada"]
  ]);
  await conn.transact([
    { ":db/ident": ident, ":db/udf": descriptor }
  ]);
  await conn.transact([
    [ident, "bob@example.com", "Bob"]
  ]);
} finally {
  await conn.close();
}
```

</div>

Only the descriptor is stored in the database. The implementation comes from the
runtime registry or resolver, so server processes must be configured with the
same UDF implementation before they can execute the transaction function.

### 5.3 Inline Transaction Functions

In embedded Clojure, `:db.fn/call` can call a regular Clojure function. The
function receives the current DB object as its first argument and must return
transaction data.

```clojure
(defn rename-user [db email new-name]
  (if-let [eid (d/q '[:find ?e .
                     :in $ ?email
                     :where [?e :user/email ?email]]
                   db email)]
    [[:db/add eid :user/name new-name]]
    (throw (ex-info "No user with email" {:email email}))))

(d/transact! conn
  [[:db.fn/call rename-user "bob@example.com" "Bob V2"]])
```

The returned transaction data is prepared and committed as part of the same
write transaction.

### 5.4 Installed Transaction Functions

For stored transaction functions written in Clojure, install an entity with
`:db/ident` and `:db/fn`. The function value should be created with
`datalevin.interpret` helpers such as `inter-fn` or `definterfn`, which keeps it
usable in native image, server, and Babashka contexts.

Use this mechanism when the transaction logic is naturally Clojure and the
environment can evaluate Datalevin interpreted functions. If the same installed
function must be implemented outside Clojure or resolved by different server
processes and language bindings, use the `:db/udf` descriptor mechanism from
Section 5.2 instead.

```clojure
(require '[datalevin.interpret :as i])

(def rename-user*
  (i/inter-fn [db email new-name]
    (if-some [ent (d/entity db [:user/email email])]
      [[:db/add (:db/id ent) :user/name new-name]]
      (throw (ex-info "No user with email" {:email email})))))

(d/transact! conn
  [{:db/ident :user/rename
    :db/fn    rename-user*}])

(d/transact! conn
  [[:user/rename "bob@example.com" "Bob V2"]])
```

You can also call an installed function through `:db.fn/call`:

```clojure
(d/transact! conn
  [[:db.fn/call :user/rename "bob@example.com" "Bob V3"]])
```


## 6. Throughput and Durability Pointers

The functions in this chapter define transaction semantics: what data can be
transacted, how atomic read-modify-write works, how transaction functions run,
and how committed transactions are observed. Throughput and durability tuning
are operational choices layered on top of those semantics.

When write throughput becomes the bottleneck, start with the ingestion recipes
in Chapter 20: manual batching, `transact-async`, sorted input, non-durable
flags for rebuildable imports, and `init-db`/`fill-db` for trusted prepared
datoms. When the question is crash behavior, WAL profiles, snapshots, or
recovery, use Chapter 19. These choices can change when a caller waits and
where the durability boundary falls, but they do not change the logical rule
that one transaction succeeds or fails as a unit.


## Summary

Datalevin provides a flexible and powerful transaction model that scales from
simple, safe synchronous writes to high-performance asynchronous batching. By
using tools like `listen!` for committed-write observation, `with-transaction`
for atomic updates, and `transact-async` for high throughput, you can build
applications that are both correct and fast.

## References

[1] Theo Haerder and Andreas Reuter, "Principles of Transaction-Oriented
Database Recovery," *ACM Computing Surveys* 15(4):287-317, 1983. DOI:
<https://doi.org/10.1145/289.291>.
