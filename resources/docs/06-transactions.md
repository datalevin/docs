---
title: "Transactions and Atomic Updates"
chapter: 6
part: "II — Core APIs: Datalog First, KV When Needed"
---

# Chapter 6: Transactions and Atomic Updates

Every write to a Datalevin database happens within a **transaction**.
Transactions are the cornerstone of database reliability, ensuring that your
data moves from one consistent state to another, even in the face of concurrent
operations or system crashes. Datalevin provides ACID (Atomicity, Consistency,
Isolation, Durability) guarantees, and this chapter explores how.

![The transaction lifecycle: input data, resolution and validation, datom changes, index updates, and the resulting report](/images/diagrams/transaction-lifecycle.svg)

---

## 1. The Transaction Model

### 1.1 Default Mode: Synchronous LMDB Transactions

In its default (non-WAL) mode, a Datalevin transaction is a direct mapping to an
underlying **LMDB transaction**.

- **Atomicity**: All changes within a single `transact!` call are applied as a
  single, atomic unit. They either all succeed or all fail.
- **Durability**: By default, every transaction is synchronously flushed to disk
  (`msync`, the OS flush-to-disk call) before it is confirmed. This guarantees
  that once a transaction is committed, it is durable and will survive a system
  crash.

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
significantly improve write throughput at the cost of durability.

---

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
metadata supplied by the caller:

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

That report is useful not only as a return value. It is also the shape delivered
to transaction listeners, covered later in this chapter.

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

When you omit `:db/id`, Datalevin assigns a new unique entity ID automatically.
That entity ID is a Datalevin internal `long`. Do not use `:db/id` for a UUID,
slug, email address, or external primary key; put application identity in a
separate unique attribute and address the entity with a lookup ref.

### 2.2 Updating Existing Entities

To update an existing entity, include its entity ID in the map:

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

This updates the entity with ID 101 to set `:user/active?` to false.

### 2.3 Automatic Entity Timestamps

Many applications want to know when an entity was first created and when it was
last modified. Datalevin can maintain this information automatically when the
connection is opened with `:auto-entity-time? true`:

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
operations such as the WAL and txlog tools covered in Chapter 20.

### 2.4 Tempids

When creating new entities that will be referenced by other entities in the
same transaction, use **tempids**: temporary entity IDs that act as
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
tempids during the transaction, replacing them with real entity IDs.

Tempids are especially useful when the data has cycles. Two new entities can
refer to each other before either one has a permanent entity ID:

```clojure
(d/transact! conn
  [{:db/id -1
    :user/name "Alice"
    :user/friend -2}
   {:db/id -2
    :user/name "Bob"
    :user/friend -1}])
```

Both references are resolved in the same transaction, so the database never
needs a half-built intermediate state where only one side of the cycle exists.

### 2.5 Lookup Refs

Instead of knowing the entity ID, you can use a **lookup ref** to identify an existing entity by a unique attribute. A lookup ref is a vector `[attribute value]`:

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

This finds the entity with `:user/email` equal to `"alice@example.com"` and updates it. If no entity exists with that email, the transaction will fail.

Lookup refs are particularly useful when you only have a unique identifier (like an email) but not the entity ID.

### 2.6 Unique Attributes and Upsert

When an attribute is marked as `:db.unique/identity`, Datalevin automatically performs an **upsert**: if an entity with that value exists, it updates that entity; otherwise, it creates a new one:

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

If `"alice@example.com"` already exists, this updates the existing entity. If not, it creates a new one. This eliminates the need to check for existence before transacting.

### 2.7 Raw Datom Vectors

For fine-grained control, you can express changes as raw datom vectors `[op entity attribute value]`:

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

`retractEntity` accepts an entity id or a lookup ref. It retracts facts where
the entity is the subject, and it also retracts declared `:db.type/ref` facts
that point to the entity. If the entity owns child entities through attributes
declared with `:db/isComponent true`, those component children are retracted
recursively. This is usually the right operation for deleting an entity that may
be referenced elsewhere.

For example, with this schema:

```clojure
{:order/id    {:db/valueType :db.type/string
               :db/unique    :db.unique/identity}
 :order/items {:db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/many
               :db/isComponent true}
 :line/sku    {:db/valueType :db.type/string}}
```

Deleting the order also deletes its owned line-item entities:

```clojure
(d/transact! conn
  [[:db/retractEntity [:order/id "o-1001"]]])
```

Use component relationships only for ownership. If a referenced entity has an
independent lifecycle, retract the relationship with `:db/retract` instead of
declaring the relationship as a component.

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

This flexibility allows you to choose the most convenient form for each operation in your transaction.

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
as an EDN binary blob. It can sometimes behave unexpectedly:

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

---

## 3. Observing Committed Transactions with `listen!`

Many applications need to react after a write commits: invalidate a cache,
notify a UI session, enqueue a background job, update an in-process projection,
or append an audit record in another system. `listen!` registers an in-process
callback on a Datalevin connection. After a transaction commits, Datalevin calls
the callback with the transaction report:

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

`listen!` itself returns the listener key. When you pass a key explicitly, as
`:audit-log` above, that same key is returned and can be passed to `unlisten!`.
When you call the two-argument form `(d/listen! conn callback)`, Datalevin
generates and returns a key for you.

The callback receives the same transaction report returned by `transact!`:

| Report key | Value |
| :--- | :--- |
| `:db-before` | The Datalog DB object before the transaction. |
| `:db-after` | The Datalog DB object after the transaction. |
| `:tx-data` | The full datom objects actually added or retracted by the transaction; Chapter 15 explains their `:e`, `:a`, `:v`, `:tx`, and `:added` fields. |
| `:tempids` | Map from transaction tempids to assigned entity ids. |
| `:tx-meta` | The metadata value supplied as the optional third argument to `transact!`, or `nil`. |

The listener key can be any unique value meaningful to the application.
Registering another listener with the same key replaces the previous callback,
so listener registration is idempotent:

```clojure
(d/listen! conn :cache (fn [report] (invalidate-cache! report)))
(d/listen! conn :cache (fn [report] (enqueue-cache-refresh! report)))
```

Listeners observe committed transactions; they do not make the listener's side
effects part of the original transaction. If a callback writes to a log file,
publishes to a queue, or calls another service, that work can fail separately
from the Datalevin commit. Keep listener callbacks small and predictable. For
expensive work, enqueue a lightweight task and let a worker handle it.

For durable domain events, the strongest pattern is to transact the event as
data in the same Datalevin transaction, then use `listen!` only to wake a
publisher or projector:

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

This keeps the fact that the event happened inside the database transaction,
while allowing delivery to be retried independently.

---

## 4. Atomic Read-Modify-Write with `with-transaction`

Often, you need to read a value, modify it, and write it back as a single
atomic operation. This is a classic race condition if not handled carefully.
In embedded Clojure, Datalevin provides the `with-transaction` macro for this
purpose:

```clojure
(d/with-transaction [tx conn]
  (let [current-balance (d/q '[:find ?bal . :where [101 :account/balance ?bal]] (d/db tx))
        new-balance (- current-balance 100)]
    (d/transact! tx [{:db/id 101, :account/balance new-balance}])))
```

The `with-transaction` macro ensures that the reads (e.g., `d/q`) and the write
(`d/transact!`) happen within the same isolated transaction, preventing any
other concurrent write from interfering. Java and Python expose the same
embedded pattern as `conn.withTransaction(...)` and `conn.with_transaction(...)`.
JavaScript does not expose a Datalog transaction callback because of JVM bridge
re-entry constraints; use conditional transaction forms such as `:db/cas`,
transaction functions, a single `conn.transact(...)`, or an application command
running inside the Datalevin-hosting process when the read-modify-write logic
must be serialized.

`with-transaction` is a serialization tool, not a general retry loop. If the
body throws, the transaction aborts. Datalevin retries its own safe internal
map-resize condition, but logical failures such as CAS mismatch, lookup-ref
miss, validation failure, or unique conflict are returned to the caller. Chapter
22 pulls those cases together into a production retry and error-handling policy.

### 4.1 Mixing Datalog and KV Writes

Because a local Datalog store is built on Datalevin's KV store, embedded
Clojure code can also use `with-transaction` to update Datalog data and custom
KV DBIs as one atomic unit. The important point is to use the KV instance inside
the transactional connection, not a separately opened `open-kv` handle on the
same directory.

Use the public `d/datalog-kv` helper to get the KV handle that belongs to a
Datalog connection or DB:

```clojure
(def kv (d/datalog-kv conn))
```

The returned KV handle is owned by the Datalog connection. Do not close it
separately; close the Datalog connection instead. Java, Python, and JavaScript
expose the same borrowed handle as `conn.datalogKV()`, `conn.datalog_kv()`, and
`await conn.datalogKv()` for setup and direct same-store KV operations. The
transaction-bound mixed-write pattern below is Clojure-specific; in other
runtimes, use a transaction function or an application command on the server
side if the Datalog and KV updates must commit atomically. Open application DBIs
during setup, then use the transaction-bound connection inside
`with-transaction` for both parts of the write:

<!-- pdf-listing: Mixing Datalog and key-value writes in one transaction -->

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

If the block returns normally, both the Datalog datoms and the KV entry are
committed. If the block throws or calls `d/abort-transact`, both parts are
aborted. This only works for DBIs that live in the same local Datalevin store;
it is not a cross-file transaction mechanism.

---

## 5. Transaction Functions

Datalevin does not use a bare symbolic list form such as `(my-tx-fn arg)` for
transaction functions. Transaction functions are transaction data vectors that expand to more
transaction data while the transaction is being prepared. They run against the
current DB object and are committed atomically with the rest of the
transaction.

Transaction function can be one of the supported vector forms:

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
transaction functions. This works for non-Clojure runtimes and client/server
deployments as well, as long as the runtime can resolve the UDF to find its
implementation.

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

---

## 6. High-Throughput: WAL and Asynchronous Transactions

While the default synchronous model is extremely safe, it can limit write
throughput. For demanding workloads, Datalevin provides two advanced features:
WAL mode and asynchronous transactions.

### 6.1 WAL Mode

As introduced in Chapter 4, **WAL (Write-Ahead Log) mode** increases write
performance, especially for concurrent writers, by recording transactions in a
sequential log before applying them to LMDB.

- **Durability Policy**: Chapter 20 covers the choice of WAL durability profile,
  including crash-loss windows, group commit, and operational maintenance.
- **Bulk Loading**: Note that bulk load operations like `init-db` and `fill-db`
  bypass the WAL for maximum performance and will not appear in the transaction
  log.
- **Concurrent Throughput**: WAL allows multiple writer threads to achieve
  significantly higher aggregate throughput than a single thread.
- **Explicit Opt-In**: Local embedded Datalog stores now default to `:wal?
  false`. Enable WAL with `{:wal? true}` when the workload needs WAL throughput,
  replay, or replication behavior.

### 6.2 Asynchronous Transactions (`transact-async`)

For the absolute highest throughput, use the async transaction API:
`d/transact-async` in Clojure, `transactAsync` in Java and JavaScript, and
`transact_async` in Python. Instead of waiting for the transaction to be
confirmed, these calls return a future or promise immediately.

```clojure
(let [fut (d/transact-async conn [{:user/name "Charlie"}])]
  ;; ... do other work ...
  @fut) ; Dereference the future to wait for completion and get the result
```

Under the hood, `transact-async` uses a powerful **adaptive batching** strategy.
It collects multiple concurrent asynchronous transactions and commits them
together in a batch. The batch size dynamically adjusts based on system load,
allowing Datalevin to achieve both high throughput during busy periods and low
latency when the system is quiet.

WAL mode and asynchronous transactions can be used together to achieve
best-in-class Online Transaction Processing (OLTP) performance. Chapter 19
shows the ingestion patterns in all four bindings.

---

## Summary

Datalevin provides a flexible and powerful transaction model that scales from
simple, safe synchronous writes to high-performance asynchronous batching. By
using tools like `listen!` for committed-write observation, `with-transaction`
for atomic updates, and `transact-async` for high throughput, you can build
applications that are both correct and fast.
