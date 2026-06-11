---
title: "Transactions and Atomic Updates"
chapter: 7
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 7: Transactions and Atomic Updates

Every write to a Datalevin database happens within a **transaction**.
Transactions are the cornerstone of database reliability, ensuring that your
data moves from one consistent state to another, even in the face of concurrent
operations or system crashes. Datalevin provides ACID (Atomicity, Consistency,
Isolation, Durability) guarantees, and this chapter explores how.

---

## 1. The Transaction Model

### 1.1 Default Mode: Synchronous LMDB Transactions

In its default (non-WAL) mode, a Datalevin transaction is a direct mapping to an
underlying **LMDB transaction**.

- **Atomicity**: All changes within a single `transact!` call are applied as a
  single, atomic unit. They either all succeed or all fail.
- **Durability**: By default, every transaction is synchronously flushed to disk
  (`msync`) before it is confirmed. This guarantees that once a transaction is
  committed, it is durable and will survive a system crash.

### 1.2 Durability Settings

For use cases where maximum write speed is more important than crash-proof
durability (e.g., bulk loading, caching), you can relax the `msync` behavior by
setting LMDB flags during connection creation. For example, using `:nosync` can
significantly improve write throughput at the cost of durability.

---

## 2. Transacting Data

The primary function for writing data is `d/transact!`. It takes a connection
and a vector of transactable entities. Datalevin is flexible in how you can
express changes.

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

### 2.3 Temporary Entity IDs (Temp Eids)

When creating new entities that will be referenced by other entities in the same transaction, use **temporary entity IDs** (temp eids). These are negative numbers that act as placeholders:

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

In this example, `-1` references `-2` as a friend. Datalevin resolves these temp eids during the transaction, replacing them with real entity IDs.

### 2.4 Lookup Refs

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

### 2.5 Unique Attributes and Upsert

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

### 2.6 Raw Datom Vectors

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

### 2.7 Mixed Forms

You can mix all these forms in a single transaction:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:user/email "new@example.com" :user/name "New User"}  ; upsert
   [:db/add [:user/email "alice@example.com"] :user/status "active"] ; lookup ref
   {:db/id -1, :user/friend [[:user/email "bob@example.com"]]} ; temp eid + lookup ref
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
             List.of(List.of("user/email", "bob@example.com")))) // temp eid + lookup ref
    .add(-1, "user/notes", "Added via datom vector")); // raw datom
```

```python
conn.transact([
    {":user/email": "new@example.com", ":user/name": "New User"},  # upsert
    [":db/add", [":user/email", "alice@example.com"], ":user/status", "active"],  # lookup ref
    {":db/id": -1, ":user/friend": [[":user/email", "bob@example.com"]]},  # temp eid + lookup ref
    [":db/add", -1, ":user/notes", "Added via datom vector"]])  # raw datom
```

```javascript
await conn.transact([
  { ":user/email": "new@example.com", ":user/name": "New User" },  // upsert
  [":db/add", [":user/email", "alice@example.com"], ":user/status", "active"],  // lookup ref
  { ":db/id": -1, ":user/friend": [[":user/email", "bob@example.com"]] },  // temp eid + lookup ref
  [":db/add", -1, ":user/notes", "Added via datom vector"]  // raw datom
]);
```

</div>

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
other concurrent write from interfering. In Java, Python, and JavaScript, prefer
the conditional transaction forms below, such as `:db/cas` and transaction
functions, when you need portable read-modify-write behavior.

---

## 4. Transaction Functions

Datalevin does not use a bare symbolic list form such as `(my-tx-fn arg)` for
transaction functions. Transaction functions are transaction data vectors that expand to more
transaction data while the transaction is being prepared. They run against the
current database value and are committed atomically with the rest of the
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
- `[:db.fn/patchIdoc ...]` patches an idoc value.

### 4.1 Compare-and-Swap

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
useful for conditional updates that must not silently overwrite newer data.

### 4.2 Transaction UDFs

For arbitrary user defined functions (UDF), Datalevin allows descriptor-backed
transaction functions. This works for non-Clojure runtimes and client/server
deployments as well, as long as the runtime can resolve the UDF to find its
implementation.

Store the descriptor in `:db/udf`, open the database with a
runtime UDF registry or resolver, and call it by descriptor or by installed
`:db/ident`. Transaction UDF descriptors use `:udf/kind :tx-fn`.

<div class="multi-lang">

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
import javaBridge from "java-bridge";
import { connect, interop } from "datalevin-node";

const { newProxy } = javaBridge;
const raw = interop();

const descriptor = {
  ":udf/lang": ":java",
  ":udf/kind": ":tx-fn",
  ":udf/id": ":user/bootstrap"
};

const registry = await raw.createUdfRegistry();
const ident = await raw.keyword(":user/bootstrap");

const bootstrap = newProxy("datalevin.UdfFunction", {
  invoke: async (args) => {
    const email = args.getSync(1);
    const name = args.getSync(2);
    return raw.txData([{
      ":db/id": -1,
      ":user/email": email,
      ":user/name": name
    }]);
  }
}, { keepAsDaemon: true });

await raw.registerUdf(registry, descriptor, bootstrap);

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
  bootstrap.reset(true);
}
```

</div>

Only the descriptor is stored in the database. The implementation comes from the
runtime registry or resolver, so server processes must be configured with the
same UDF implementation before they can execute the transaction function.

### 4.3 Inline Transaction Functions

In embedded Clojure, `:db.fn/call` can call a regular Clojure function. The
function receives the current database value as its first argument and must
return transaction data.

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

### 4.4 Installed Transaction Functions

For stored transaction functions written in Clojure, install an entity with
`:db/ident` and `:db/fn`. The function value should be created with
`datalevin.interpret` helpers such as `inter-fn` or `definterfn`, which keeps it
usable in native image, server, and Babashka contexts.

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

## 5. High-Throughput: WAL and Asynchronous Transactions

While the default synchronous model is extremely safe, it can limit write
throughput. For demanding workloads, Datalevin provides two advanced features:
WAL mode and asynchronous transactions.

### 5.1 WAL Mode

As discussed in Chapter 4, **WAL (Write-Ahead Log) mode** increases write
performance, especially for concurrent writers. By writing to a sequential log
file first, Datalevin can achieve the durability of `msync` with the throughput
of an LSM-tree.

- **Durability Profiles**: Choose `:strict` for standard safety (sync on every
  commit), `:relaxed` for maximum throughput (batched disk syncs), or `:extra`
  for even stronger durability guarantees (e.g., `F_FULLSYNC` on macOS).
- **Bulk Loading**: Note that bulk load operations like `init-db` and `fill-db`
  bypass the WAL for maximum performance and will not appear in the transaction
  log.
- **Concurrent Throughput**: WAL allows multiple writer threads to achieve
  significantly higher aggregate throughput than a single thread.
- **Explicit Opt-In**: Local embedded Datalog stores now default to `:wal?
  false`. Enable WAL with `{:wal? true}` when the workload needs WAL throughput,
  replay, or replication behavior. Local WAL opt-in defaults to
  `:wal-durability-profile :relaxed`; consensus-lease HA forces WAL and defaults
  to `:strict`.

### 5.2 Asynchronous Transactions (`transact-async`)

For the absolute highest throughput, the embedded Clojure API offers
`d/transact-async`. Instead of waiting for the transaction to be confirmed, this
function returns a `Future` immediately.

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
best-in-class Online Transaction Processing (OLTP) performance.

---

## Summary

Datalevin provides a flexible and powerful transaction model that scales from
simple, safe synchronous writes to high-performance asynchronous batching. By
using tools like `with-transaction` for atomic updates and `transact-async` for
high throughput, you can build applications that are both correct and fast.
