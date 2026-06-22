---
title: "Reading Data"
chapter: 7
part: "II — Core APIs: Datalog First, KV When Needed"
---

# Chapter 7: Reading Data

Once data is in your database, you need a way to retrieve it in the shape your
application actually uses. Datalevin gives you several read APIs, each with a
different job:

- **Lookup refs** let you address an entity by a domain identifier such as an
  email, SKU, username, or order number.
- **`d/pull`** fetches a plain map in a declared shape, including nested
  references and recursive trees.
- **`d/entity`** returns a lazy, map-like entity object for interactive or
  conditional navigation.
- **`d/q`** finds sets of values and entities using Datalog constraints.

This chapter covers the first three. Chapter 8 focuses on Datalog query syntax,
but queries will appear here when they help explain how lookup refs and pull
work together.

The Java, Python, and JavaScript snippets assume an open connection named
`conn`. Clojure snippets use `conn` for a connection and `db` for a Datalog DB
object obtained with `(d/db conn)`.

Datalevin's `db` is not a Datomic-style immutable database-as-value object. It
is a mutable DB object/reference used by read APIs. Treat it as access to the
current database state for the current operation, not as durable application
state.

Call `(d/db conn)` when you need to read the connection's current state. Do not
save a `db` object or an entity object and expect it to remain meaningful after
later transactions; if you are making a new decision, get a new `db` from the
connection before reading. There is no application-level concept of an "old
db" in Datalevin. A saved DB object is better understood as an expired
reference, not as something the application can treat as an earlier world.

You may see `@conn` in older examples or REPL notes. Treat that as Clojure
deref shorthand that relies on connection implementation details, not as the
recommended public style. This book uses `(d/db conn)` consistently.


## 1. Reading by Identity with Lookup Refs

Datalevin stores entities internally by numeric entity id. Application code,
however, usually knows a natural key: a user's email, an account number, a URL
slug, or an external system id. A **lookup ref** is the bridge between those two
worlds. Prefer lookup refs and unique identity attributes at application
boundaries because eids are database-local handles; the same logical entity may
have different eids in two databases.

A lookup ref is a two-element vector:

<div class="multi-lang">

```clojure
[:user/email "alice@example.com"]
```

```java
List.of("user/email", "alice@example.com")
```

```python
[":user/email", "alice@example.com"]
```

```javascript
[":user/email", "alice@example.com"]
```

</div>

The first element must be an attribute marked `:db/unique`, usually
`:db.unique/identity` for domain identity. The second element is the value of
that unique attribute. Datalevin resolves the vector to the matching entity id
when an API expects an entity.

<div class="multi-lang">

```clojure
(def schema
  {:user/email    {:db/valueType :db.type/string
                   :db/unique    :db.unique/identity}
   :user/name     {:db/valueType :db.type/string}
   :user/friends  {:db/valueType   :db.type/ref
                   :db/cardinality :db.cardinality/many}
   :order/id      {:db/valueType :db.type/string
                   :db/unique    :db.unique/identity}
   :order/customer {:db/valueType :db.type/ref}})

(d/transact! conn
  [{:user/email "alice@example.com"
    :user/name  "Alice"}
   {:user/email "bob@example.com"
    :user/name  "Bob"
    :user/friends [[:user/email "alice@example.com"]]}
   {:order/id "o-1001"
    :order/customer [:user/email "alice@example.com"]}])

(def db (d/db conn))
```

```java
Schema schema = Datalevin.schema()
    .attr("user/email",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY))
    .attr("user/name",
          Schema.attribute().valueType(Schema.ValueType.STRING))
    .attr("user/friends",
          Schema.attribute()
              .valueType(Schema.ValueType.REF)
              .cardinality(Schema.Cardinality.MANY))
    .attr("order/id",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY))
    .attr("order/customer",
          Schema.attribute().valueType(Schema.ValueType.REF));

Connection conn = Datalevin.createConn("/tmp/reading-demo", schema);

conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "alice@example.com")
        .put("user/name", "Alice"))
    .entity(Tx.entity()
        .put("user/email", "bob@example.com")
        .put("user/name", "Bob")
        .put("user/friends",
             List.of(List.of("user/email", "alice@example.com"))))
    .entity(Tx.entity()
        .put("order/id", "o-1001")
        .put("order/customer",
             List.of("user/email", "alice@example.com"))));
```

```python
schema = {
    ":user/email": {
        ":db/valueType": ":db.type/string",
        ":db/unique": ":db.unique/identity",
    },
    ":user/name": {":db/valueType": ":db.type/string"},
    ":user/friends": {
        ":db/valueType": ":db.type/ref",
        ":db/cardinality": ":db.cardinality/many",
    },
    ":order/id": {
        ":db/valueType": ":db.type/string",
        ":db/unique": ":db.unique/identity",
    },
    ":order/customer": {":db/valueType": ":db.type/ref"},
}

conn = connect("/tmp/reading-demo", schema=schema)

conn.transact([
    {":user/email": "alice@example.com",
     ":user/name": "Alice"},
    {":user/email": "bob@example.com",
     ":user/name": "Bob",
     ":user/friends": [[":user/email", "alice@example.com"]]},
    {":order/id": "o-1001",
     ":order/customer": [":user/email", "alice@example.com"]},
])
```

```javascript
const schema = {
  ":user/email": {
    ":db/valueType": ":db.type/string",
    ":db/unique": ":db.unique/identity"
  },
  ":user/name": { ":db/valueType": ":db.type/string" },
  ":user/friends": {
    ":db/valueType": ":db.type/ref",
    ":db/cardinality": ":db.cardinality/many"
  },
  ":order/id": {
    ":db/valueType": ":db.type/string",
    ":db/unique": ":db.unique/identity"
  },
  ":order/customer": { ":db/valueType": ":db.type/ref" }
};

const conn = await connect("/tmp/reading-demo", { schema });

await conn.transact([
  { ":user/email": "alice@example.com",
    ":user/name": "Alice" },
  { ":user/email": "bob@example.com",
    ":user/name": "Bob",
    ":user/friends": [[":user/email", "alice@example.com"]] },
  { ":order/id": "o-1001",
    ":order/customer": [":user/email", "alice@example.com"] }
]);
```

</div>

Lookup refs work in the same places where you would otherwise pass an entity
id:

<div class="multi-lang">

```clojure
;; Resolve to the numeric entity id when you explicitly need it.
(d/entid db [:user/email "alice@example.com"])
;; => 1

;; Pull by lookup ref.
(d/pull db '[:user/name :user/email] [:user/email "alice@example.com"])
;; => {:user/name "Alice", :user/email "alice@example.com"}

;; Navigate by lookup ref.
(def alice (d/entity db [:user/email "alice@example.com"]))
(:user/name alice)
;; => "Alice"
```

```java
// Resolve to the numeric entity id when you explicitly need it.
Object aliceId = conn.entid(List.of("user/email", "alice@example.com"));

// Pull by lookup ref.
Map<?, ?> alice =
    conn.pull("[:user/name :user/email]",
              List.of("user/email", "alice@example.com"));

// Materialize by lookup ref.
Map<?, ?> aliceEntity =
    conn.entityMap(List.of("user/email", "alice@example.com"));
```

```python
# Resolve to the numeric entity id when you explicitly need it.
alice_id = conn.entid([":user/email", "alice@example.com"])

# Pull by lookup ref.
alice = conn.pull('[:user/name :user/email]',
                  [":user/email", "alice@example.com"])

# Navigate by lookup ref.
alice_entity = conn.entity([":user/email", "alice@example.com"])
alice_entity[":user/name"]
```

```javascript
// Resolve to the numeric entity id when you explicitly need it.
const aliceId = await conn.entid([":user/email", "alice@example.com"]);

// Pull by lookup ref.
const alice = await conn.pull('[:user/name :user/email]',
                              [':user/email', 'alice@example.com']);

// Navigate by lookup ref.
const aliceEntity = await conn.entity([":user/email", "alice@example.com"]);
await aliceEntity.get(":user/name");
```

</div>

Lookup refs are also valid inputs to Datalog queries. The query engine resolves
them when they occur in positions that require an entity id:

<div class="multi-lang">

```clojure
(d/q '[:find [?order-id ...]
       :in $ ?customer
       :where
       [?order :order/customer ?customer]
       [?order :order/id ?order-id]]
     db
     [:user/email "alice@example.com"])
;; => ["o-1001"]
```

```java
Object orderIds =
    conn.query("[:find [?order-id ...] " +
               " :in $ ?customer " +
               " :where [?order :order/customer ?customer] " +
               "        [?order :order/id ?order-id]]",
               List.of(List.of("user/email", "alice@example.com")));
```

```python
order_ids = conn.query(
    '[:find [?order-id ...] '
    ':in $ ?customer '
    ':where [?order :order/customer ?customer] '
    '       [?order :order/id ?order-id]]',
    [":user/email", "alice@example.com"])
```

```javascript
const orderIds = await conn.query(
  '[:find [?order-id ...] ' +
  ':in $ ?customer ' +
  ':where [?order :order/customer ?customer] ' +
  '       [?order :order/id ?order-id]]',
  [':user/email', 'alice@example.com']
);
```

</div>

If a lookup ref does not resolve, `d/entid`, `d/entity`, and `d/pull` return
`nil`. A numeric id is different: it is already an id, so `d/entid` returns it
without proving that the entity has datoms. Use a lookup ref, a query, or a
specific attribute read when you need an existence check.


## 2. Pull: Fetching a Declared Shape

`d/pull` is the easiest way to fetch a plain data structure for one entity. You
provide a DB object, a **pull pattern**, and an entity id or lookup ref:

<div class="multi-lang">

```clojure
(d/pull db pattern eid-or-lookup-ref)
```

```java
Map<?, ?> result = conn.pull(pattern, eidOrLookupRef);
```

```python
result = conn.pull(pattern, eid_or_lookup_ref)
```

```javascript
const result = await conn.pull(pattern, eidOrLookupRef);
```

</div>

The pattern is a vector describing which attributes to retrieve. The result is a
plain map, not a lazy entity.

<div class="multi-lang">

```clojure
(d/pull db '[:user/name :user/email]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice", :user/email "alice@example.com"}
```

```java
Map<?, ?> alice =
    conn.pull("[:user/name :user/email]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[:user/name :user/email]',
                  [":user/email", "alice@example.com"])
```

```javascript
const alice = await conn.pull('[:user/name :user/email]',
                              [':user/email', 'alice@example.com']);
```

</div>

If you already have the numeric entity id, pass it directly to `d/pull` for a
shaped map or to `d/entity` for lazy navigation. You do not need a Datalog query
just to retrieve one known entity:

<div class="multi-lang">

```clojure
(d/pull db '[:user/name :user/email] 1)
;; => {:user/name "Alice", :user/email "alice@example.com"}
```

```java
Map<?, ?> alice = conn.pull("[:user/name :user/email]", 1);
```

```python
alice = conn.pull('[:user/name :user/email]', 1)
```

```javascript
const alice = await conn.pull('[:user/name :user/email]', 1);
```

</div>

The entity APIs are another option when you want navigation rather than a fixed
pull shape:

<div class="multi-lang">

```clojure
(:user/name (d/entity db 1))
;; => "Alice"
```

```java
conn.entityMap(1).get("user/name");
```

```python
conn.entity(1)[":user/name"]
```

```javascript
const alice = await conn.entity(1);
await alice.get(":user/name");
```

</div>

Use `[*]` to fetch all forward attributes of the entity:

<div class="multi-lang">

```clojure
(d/pull db '[*] [:user/email "alice@example.com"])
;; => {:db/id 1,
;;     :user/name "Alice",
;;     :user/email "alice@example.com"}
```

```java
Map<?, ?> alice =
    conn.pull("[*]", List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[*]', [":user/email", "alice@example.com"])
```

```javascript
const alice = await conn.pull('[*]',
                              [':user/email', 'alice@example.com']);
```

</div>

Wildcard pull is convenient for exploration, but production APIs should usually
name the attributes they return. Explicit patterns keep responses stable as the
schema grows.

### 2.1 Nested Pull

Pull patterns can follow reference attributes. If `:order/customer` has
`:db/valueType :db.type/ref`, a nested map in the pattern tells Datalevin how to
shape the referenced entity:

This example uses the schema from Section 1, where `:order/id` is declared
`:db.unique/identity`. Without that uniqueness declaration, `[:order/id
"o-1001"]` is just a two-element vector, not a valid lookup ref.

<div class="multi-lang">

```clojure
(d/pull db
        '[:order/id {:order/customer [:user/name :user/email]}]
        [:order/id "o-1001"])
```

```java
Map<?, ?> order =
    conn.pull("[:order/id {:order/customer [:user/name :user/email]}]",
              List.of("order/id", "o-1001"));
```

```python
order = conn.pull('[:order/id {:order/customer [:user/name :user/email]}]',
                  [":order/id", "o-1001"])
```

```javascript
const order =
  await conn.pull('[:order/id {:order/customer [:user/name :user/email]}]',
                  [':order/id', 'o-1001']);
```

</div>

Result:

```clojure
{:order/id "o-1001",
 :order/customer {:user/name "Alice",
                  :user/email "alice@example.com"}}
```

Without the nested pattern, an ordinary reference is returned as a small entity
reference:

<div class="multi-lang">

```clojure
(d/pull db '[:order/id :order/customer] [:order/id "o-1001"])
;; => {:order/id "o-1001", :order/customer {:db/id 1}}
```

```java
Map<?, ?> order =
    conn.pull("[:order/id :order/customer]",
              List.of("order/id", "o-1001"));
```

```python
order = conn.pull('[:order/id :order/customer]',
                  [":order/id", "o-1001"])
```

```javascript
const order = await conn.pull('[:order/id :order/customer]',
                              [':order/id', 'o-1001']);
```

</div>

That behavior is intentional. Pull only expands what the pattern asks it to
expand.

Component references are the important exception. If a reference attribute is
declared with `:db/isComponent true`, Datalevin treats the child as owned by the
parent. Pulling that component attribute without an explicit nested pattern can
expand the component recursively, because the component is part of the parent's
logical document. For stable API responses, prefer an explicit nested pattern
or a bounded recursive pattern even for components.

### 2.2 Pulling Many Entities

When you already have several entity ids or lookup refs, use `d/pull-many`
rather than calling `d/pull` in a loop:

<div class="multi-lang">

```clojure
(d/pull-many db
             '[:user/name]
             [[:user/email "alice@example.com"]
              [:user/email "bob@example.com"]])
;; => [{:user/name "Alice"} {:user/name "Bob"}]
```

```java
List<?> users =
    conn.pullMany("[:user/name]",
                  List.of(List.of("user/email", "alice@example.com"),
                          List.of("user/email", "bob@example.com")));
```

```python
users = conn.pull_many(
    '[:user/name]',
    [[":user/email", "alice@example.com"],
     [":user/email", "bob@example.com"]])
```

```javascript
const users = await conn.pullMany(
  '[:user/name]',
  [[':user/email', 'alice@example.com'],
   [':user/email', 'bob@example.com']]
);
```

</div>

`d/q` can also use pull in its `:find` specification:

<div class="multi-lang">

```clojure
(d/q '[:find [(pull ?user [:user/name :user/email]) ...]
       :where [?user :user/email]]
     db)
```

```java
Object users =
    conn.query("[:find [(pull ?user [:user/name :user/email]) ...] " +
               " :where [?user :user/email]]");
```

```python
users = conn.query(
    '[:find [(pull ?user [:user/name :user/email]) ...] '
    ':where [?user :user/email]]')
```

```javascript
const users = await conn.query(
  '[:find [(pull ?user [:user/name :user/email]) ...] ' +
  ':where [?user :user/email]]'
);
```

</div>

This is useful when Datalog should discover the matching entities and pull
should shape the result.

The pull pattern in a query can also be an input, which is useful when the
application chooses the response shape:

<div class="multi-lang">

```clojure
(def public-user-pattern [:user/name :user/email])

(d/q '[:find [(pull ?user ?pattern) ...]
       :in $ ?pattern
       :where [?user :user/email]]
     db public-user-pattern)
```

```java
List<?> publicUserPattern =
    List.of(Datalevin.kw("user/name"), Datalevin.kw("user/email"));

Object users =
    conn.query("[:find [(pull ?user ?pattern) ...] " +
               " :in $ ?pattern " +
               " :where [?user :user/email]]",
               List.of(publicUserPattern));
```

```python
public_user_pattern = [":user/name", ":user/email"]

users = conn.query(
    '[:find [(pull ?user ?pattern) ...] '
    ':in $ ?pattern '
    ':where [?user :user/email]]',
    public_user_pattern)
```

```javascript
const publicUserPattern = [":user/name", ":user/email"];

const users = await conn.query(
  '[:find [(pull ?user ?pattern) ...] ' +
  ':in $ ?pattern ' +
  ':where [?user :user/email]]',
  publicUserPattern
);
```

</div>

In multi-source queries, a pull expression can name the source to pull from. In
the connection query helpers, the receiver connection supplies the first source
listed in `:in`; additional Datalevin connections can be passed as query inputs.

<div class="multi-lang">

```clojure
(d/q '[:find ?user (pull $users ?user [:user/name])
       :in $users $orders
       :where [$users ?user :user/email ?email]
              [$orders ?order :order/customer-email ?email]]
     (d/db users-conn) (d/db orders-conn))
```

```java
Object users = usersConn.query(
    "[:find ?user (pull $users ?user [:user/name]) " +
    " :in $users $orders " +
    " :where [$users ?user :user/email ?email] " +
    "        [$orders ?order :order/customer-email ?email]]",
    ordersConn);
```

```python
users = users_conn.query(
    '[:find ?user (pull $users ?user [:user/name]) '
    ' :in $users $orders '
    ' :where [$users ?user :user/email ?email] '
    '        [$orders ?order :order/customer-email ?email]]',
    orders_conn)
```

```javascript
const users = await usersConn.query(
  '[:find ?user (pull $users ?user [:user/name]) ' +
  ' :in $users $orders ' +
  ' :where [$users ?user :user/email ?email] ' +
  '        [$orders ?order :order/customer-email ?email]]',
  ordersConn
);
```

</div>


## 3. Reverse Pull

A reference attribute points forward from one entity to another. For example,
an order has `:order/customer` pointing to a user. Sometimes you want to start
from the user and ask, "which orders point here?"

Pull supports this with **reverse attributes**. For a namespaced attribute like
`:order/customer`, the reverse attribute is `:order/_customer`. For an
unqualified attribute like `:friend`, the reverse attribute is `:_friend`.

<div class="multi-lang">

```clojure
(d/pull db
        '[:user/name {:order/_customer [:order/id]}]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice",
;;     :order/_customer [{:order/id "o-1001"}]}
```

```java
Map<?, ?> alice =
    conn.pull("[:user/name {:order/_customer [:order/id]}]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[:user/name {:order/_customer [:order/id]}]',
                  [":user/email", "alice@example.com"])
```

```javascript
const alice =
  await conn.pull('[:user/name {:order/_customer [:order/id]}]',
                  [':user/email', 'alice@example.com']);
```

</div>

As with forward references, a bare reverse attribute returns only small entity
reference maps:

<div class="multi-lang">

```clojure
(d/pull db
        '[:user/name :order/_customer]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice",
;;     :order/_customer [{:db/id 3}]}
```

```java
Map<?, ?> alice =
    conn.pull("[:user/name :order/_customer]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[:user/name :order/_customer]',
                  [":user/email", "alice@example.com"])
```

```javascript
const alice =
  await conn.pull('[:user/name :order/_customer]',
                  [':user/email', 'alice@example.com']);
```

</div>

Use a nested reverse pattern when you want attributes from the entities that
point back to the current entity.

Reverse pull is not a special side table. As Chapter 15 explains, references
are values in Datalevin's indexes, so reverse reference lookup is an indexed
value-to-entity lookup.

Reverse navigation works best when the attribute is declared as
`:db.type/ref`. Without that schema information, Datalevin cannot know that the
value should be treated as an entity reference in higher-level navigation APIs.


## 4. Pull Pattern Options

Pull supports attribute options for common response-shaping needs.

### 4.1 Limiting Cardinality-Many Attributes

Pull returns at most 1000 values for a cardinality-many attribute by default.
Use `:limit` to choose a smaller limit, or `nil` for no limit. The option is
written as an attribute expression:

<div class="multi-lang">

```clojure
(d/pull db '[[:user/friends :limit 5]]
        [:user/email "bob@example.com"])
```

```java
Map<?, ?> bob =
    conn.pull("[[:user/friends :limit 5]]",
              List.of("user/email", "bob@example.com"));
```

```python
bob = conn.pull('[[:user/friends :limit 5]]',
                [":user/email", "bob@example.com"])
```

```javascript
const bob = await conn.pull('[[:user/friends :limit 5]]',
                            [':user/email', 'bob@example.com']);
```

</div>

The same attribute expression can be used as the key in a nested map spec:

<div class="multi-lang">

```clojure
(d/pull db
        '[:user/name {[:user/friends :limit 5] [:user/name]}]
        [:user/email "bob@example.com"])
```

```java
Map<?, ?> bob =
    conn.pull("[:user/name {[:user/friends :limit 5] [:user/name]}]",
              List.of("user/email", "bob@example.com"));
```

```python
bob = conn.pull('[:user/name {[:user/friends :limit 5] [:user/name]}]',
                [":user/email", "bob@example.com"])
```

```javascript
const bob =
  await conn.pull('[:user/name {[:user/friends :limit 5] [:user/name]}]',
                  [':user/email', 'bob@example.com']);
```

</div>

### 4.2 Default Values

Use `:default` when the response should include a value even if the entity does
not currently have that attribute:

<div class="multi-lang">

```clojure
(d/pull db
        '[:user/name [:user/active? :default true]]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice", :user/active? true}
```

```java
Map<?, ?> alice =
    conn.pull("[:user/name [:user/active? :default true]]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[:user/name [:user/active? :default true]]',
                  [":user/email", "alice@example.com"])
```

```javascript
const alice =
  await conn.pull('[:user/name [:user/active? :default true]]',
                  [':user/email', 'alice@example.com']);
```

</div>

### 4.3 Attribute Renaming

Use `:as` when the external response should use a different key:

<div class="multi-lang">

```clojure
(d/pull db
        '[[:user/name :as :name]
          [:user/email :as :email]]
        [:user/email "alice@example.com"])
;; => {:name "Alice", :email "alice@example.com"}
```

```java
Map<?, ?> alice =
    conn.pull("[[:user/name :as :name] [:user/email :as :email]]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[[:user/name :as :name] [:user/email :as :email]]',
                  [":user/email", "alice@example.com"])
```

```javascript
const alice =
  await conn.pull('[[:user/name :as :name] [:user/email :as :email]]',
                  [':user/email', 'alice@example.com']);
```

</div>

Renaming is a presentation choice. It does not change the stored attributes or
the schema.

### 4.4 Transforming Pull Values

Use `:xform` when a value should be transformed before it is placed in the pull
result. The transform may be a Clojure function value or a resolvable symbol,
such as `vector` or `clojure.string/upper-case`:

<div class="multi-lang">

```clojure
(d/pull db
        '[[:user/name :as :name :xform clojure.string/upper-case]]
        [:user/email "alice@example.com"])
;; => {:name "ALICE"}
```

```java
Map<?, ?> alice =
    conn.pull("[[:user/name :as :name :xform clojure.string/upper-case]]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull(
    '[[:user/name :as :name :xform clojure.string/upper-case]]',
    [":user/email", "alice@example.com"])
```

```javascript
const alice =
  await conn.pull(
    '[[:user/name :as :name :xform clojure.string/upper-case]]',
    [':user/email', 'alice@example.com']);
```

</div>

For pull patterns that cross language or process boundaries, prefer resolvable
symbols and make sure the runtime can load them. Anonymous functions are
Clojure values and are best kept to embedded Clojure code.

If an attribute is missing and no default is supplied, the transform receives
`nil`:

<div class="multi-lang">

```clojure
(d/pull db
        '[[:user/nickname :xform vector]]
        [:user/email "alice@example.com"])
;; => {:user/nickname [nil]}
```

```java
Map<?, ?> alice =
    conn.pull("[[:user/nickname :xform vector]]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[[:user/nickname :xform vector]]',
                  [":user/email", "alice@example.com"])
```

```javascript
const alice =
  await conn.pull('[[:user/nickname :xform vector]]',
                  [':user/email', 'alice@example.com']);
```

</div>

If `:default` is present and the attribute is missing, the default wins and is
returned as-is:

<div class="multi-lang">

```clojure
(d/pull db
        '[[:user/nickname :default "unknown" :xform vector]]
        [:user/email "alice@example.com"])
;; => {:user/nickname "unknown"}
```

```java
Map<?, ?> alice =
    conn.pull("[[:user/nickname :default \"unknown\" :xform vector]]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.pull('[[:user/nickname :default "unknown" :xform vector]]',
                  [":user/email", "alice@example.com"])
```

```javascript
const alice =
  await conn.pull('[[:user/nickname :default "unknown" :xform vector]]',
                  [':user/email', 'alice@example.com']);
```

</div>

For reference, reverse-reference, and nested pull attributes, `:xform` receives
the pulled value for that attribute. This is useful for small response-shaping
steps. For transformations with business rules, error handling, or host-language
portability requirements, prefer ordinary application code after the pull.


## 5. Recursive Pull and Component Trees

Nested pull is not limited to one fixed level. When a reference points to the
same kind of entity repeatedly, such as a category tree, organization chart,
filesystem, or bill of materials, use a recursive pull map. The map value can be
`...` for unbounded recursion, or a positive number for a bounded depth.

The examples below assume `:category/slug` is unique and
`:category/children` is a reference attribute.

Use a bounded depth for user-facing APIs unless the graph is naturally small:

<div class="multi-lang">

```clojure
(d/pull db
        '[:category/name {:category/children 2}]
        [:category/slug "databases"])
```

```java
Map<?, ?> tree =
    conn.pull("[:category/name {:category/children 2}]",
              List.of("category/slug", "databases"));
```

```python
tree = conn.pull('[:category/name {:category/children 2}]',
                 [":category/slug", "databases"])
```

```javascript
const tree =
  await conn.pull('[:category/name {:category/children 2}]',
                  [':category/slug', 'databases']);
```

</div>

Use `...` when the recursive structure itself is the response, and the data
model guarantees a sensible boundary:

<div class="multi-lang">

```clojure
(d/pull db
        '[:category/name {:category/children ...}]
        [:category/slug "databases"])
```

```java
Map<?, ?> tree =
    conn.pull("[:category/name {:category/children ...}]",
              List.of("category/slug", "databases"));
```

```python
tree = conn.pull('[:category/name {:category/children ...}]',
                 [":category/slug", "databases"])
```

```javascript
const tree =
  await conn.pull('[:category/name {:category/children ...}]',
                  [':category/slug', 'databases']);
```

</div>

Recursive pull also works in reverse. If `:category/children` points from a
parent to a child, `:category/_children` walks from a child back toward its
parents:

<div class="multi-lang">

```clojure
(d/pull db
        '[:category/name {:category/_children 3}]
        [:category/slug "lmdb"])
```

```java
Map<?, ?> path =
    conn.pull("[:category/name {:category/_children 3}]",
              List.of("category/slug", "lmdb"));
```

```python
path = conn.pull('[:category/name {:category/_children 3}]',
                 [":category/slug", "lmdb"])
```

```javascript
const path =
  await conn.pull('[:category/name {:category/_children 3}]',
                  [':category/slug', 'lmdb']);
```

</div>

Datalevin tracks entities already seen during a recursive pull. If a cycle is
encountered, the repeated entity is represented by `{:db/id ...}` instead of
being expanded forever. That makes recursion safe, but it does not make every
recursive response small. Choose explicit depth limits for large graphs.

Component attributes deserve special care. Because a component child is owned
by its parent, a bare component reference can expand recursively. That is useful
for logical documents, but it can also return more than an API endpoint is meant
to expose. Prefer an explicit pattern when the external shape matters:

<div class="multi-lang">

```clojure
(d/pull db
        '[:order/id
          {:order/items [:line/sku :line/qty]}]
        [:order/id "o-1001"])
```

```java
Map<?, ?> order =
    conn.pull("[:order/id {:order/items [:line/sku :line/qty]}]",
              List.of("order/id", "o-1001"));
```

```python
order = conn.pull('[:order/id {:order/items [:line/sku :line/qty]}]',
                  [":order/id", "o-1001"])
```

```javascript
const order =
  await conn.pull('[:order/id {:order/items [:line/sku :line/qty]}]',
                  [':order/id', 'o-1001']);
```

</div>

Appendix E has the complete pull pattern syntax table.


## 6. Entity API: Lazy Map-Like Navigation

`d/entity` returns an entity object tied to the Datalog DB object used to create
it. Python and JavaScript expose the same lazy entity idea as `conn.entity`.
Java's public `Connection.entityMap` materializes a bridge-safe map for the
entity; use `pull` when Java code needs a declared shape.

<div class="multi-lang">

```clojure
(def alice (d/entity db [:user/email "alice@example.com"]))
```

```java
Map<?, ?> alice =
    conn.entityMap(List.of("user/email", "alice@example.com"));
```

```python
alice = conn.entity([":user/email", "alice@example.com"])
```

```javascript
const alice = await conn.entity([":user/email", "alice@example.com"]);
```

</div>

An entity behaves like a Clojure map for attribute lookup:

<div class="multi-lang">

```clojure
(:db/id alice)
;; => 1

(:user/name alice)
;; => "Alice"

(alice :user/email)
;; => "alice@example.com"

(contains? alice :user/name)
;; => true
```

```java
alice.get("db/id");
alice.get("user/name");
alice.get("user/email");
alice.containsKey("user/name");
```

```python
alice[":db/id"]
alice.get(":user/name")
alice[":user/email"]
":user/name" in alice
```

```javascript
await alice.id();
await alice.get(":user/name");
await alice.get(":user/email");
await alice.has(":user/name");
```

</div>

The important difference from `d/pull` is laziness. Creating `alice` does not
fetch every attribute. Reading `(:user/name alice)` fetches and caches that
attribute. Reading another attribute may touch storage again. This makes
`d/entity` or a lazy binding entity pleasant for conditional application logic:

<div class="multi-lang">

```clojure
(when-let [alice (d/entity db [:user/email "alice@example.com"])]
  (if (:user/active? alice)
    (:user/name alice)
    "inactive"))
```

```java
Map<?, ?> alice = conn.entityMap(List.of("user/email", "alice@example.com"));
String displayName =
    Boolean.TRUE.equals(alice.get("user/active?"))
        ? (String) alice.get("user/name")
        : "inactive";
```

```python
alice = conn.entity([":user/email", "alice@example.com"])
display_name = (
    alice[":user/name"]
    if alice is not None and alice.get(":user/active?")
    else "inactive")
```

```javascript
const alice = await conn.entity([":user/email", "alice@example.com"]);
const displayName =
  alice && (await alice.get(":user/active?"))
    ? await alice.get(":user/name")
    : "inactive";
```

</div>

Use `d/touch` when you intentionally want to realize all attributes of an entity
for debugging, logging, or exploratory REPL work:

<div class="multi-lang">

```clojure
(pr-str (d/entity db [:user/email "alice@example.com"]))
;; => "{:db/id 1}"

(d/touch (d/entity db [:user/email "alice@example.com"]))
;; prints like:
;; {:db/id 1,
;;  :user/name "Alice",
;;  :user/email "alice@example.com"}
```

```java
Map<?, ?> alice =
    conn.entityMap(List.of("user/email", "alice@example.com"));
```

```python
alice = conn.entity([":user/email", "alice@example.com"])
alice.touch()
```

```javascript
const alice = await conn.entity([":user/email", "alice@example.com"]);
await alice.touch();
```

</div>

`d/touch` realizes the attributes, but it does not turn the entity into a plain
persistent Clojure map. The value is still an entity object. That distinction
matters with functions such as `merge` that construct maps by conjoining
entries:

```clojure
(def alice-ent (d/touch (d/entity db [:user/email "alice@example.com"])))

;; Do this when ordinary map operations should own the value:
(merge (into {} alice-ent)
       {:api/source "profile"})
```

For API responses, prefer `d/pull` with an explicit pattern. `d/touch` says
"realize everything currently on this entity"; `d/pull` says "return this
declared shape."

### 6.1 Entity References

When an entity attribute is declared as `:db.type/ref`, lazy entity navigation
returns entity objects for the referenced entities. Java code can use `pull`
for the same nested read shape:

<div class="multi-lang">

```clojure
(def bob (d/entity db [:user/email "bob@example.com"]))

(:user/friends bob)
;; => a set containing Alice's entity

(map :user/name (:user/friends bob))
;; => ("Alice")
```

```java
Map<?, ?> bob =
    conn.pull("[:user/name {:user/friends [:user/name]}]",
              List.of("user/email", "bob@example.com"));
```

```python
bob = conn.entity([":user/email", "bob@example.com"])
friends = bob[":user/friends"]
[friend[":user/name"] for friend in friends]
```

```javascript
const bob = await conn.entity([":user/email", "bob@example.com"]);
const friends = await bob.get(":user/friends");
const names = await Promise.all(
  Array.from(friends).map((friend) => friend.get(":user/name"))
);
```

</div>

For lazy entity APIs, a cardinality-one reference returns one entity and a
cardinality-many reference returns a set of entities. Those referenced entities
are lazy too, so this kind of navigation can be very convenient in a REPL or in
small conditional branches. For large result sets, use Datalog plus pull or
`pull-many` so the read shape is explicit.

### 6.2 Reverse Navigation on Entities

Entity navigation also supports reverse attributes:

<div class="multi-lang">

```clojure
(def alice (d/entity db [:user/email "alice@example.com"]))

(map :order/id (:order/_customer alice))
;; => ("o-1001")
```

```java
Map<?, ?> alice =
    conn.pull("[:user/name {:order/_customer [:order/id]}]",
              List.of("user/email", "alice@example.com"));
```

```python
alice = conn.entity([":user/email", "alice@example.com"])
orders = alice[":order/_customer"]
[order[":order/id"] for order in orders]
```

```javascript
const alice = await conn.entity([":user/email", "alice@example.com"]);
const orders = await alice.get(":order/_customer");
const orderIds = await Promise.all(
  Array.from(orders).map((order) => order.get(":order/id"))
);
```

</div>

The naming rule is the same as reverse pull:

- `:order/customer` becomes `:order/_customer`
- `:friend` becomes `:_friend`

Reverse attributes return the entities that point to the current entity through
that reference. For non-component references the result is a set. Component
references can return a single owner because a component has at most one owner
in a well-formed model.

### 6.3 Entity Values and Stale Reads

An entity is bound to the DB object used to create it. If the connection later
receives a transaction, the old entity does not update itself:

<div class="multi-lang">

```clojure
(def db1 (d/db conn))
(def alice-v1 (d/entity db1 [:user/email "alice@example.com"]))

(d/transact! conn
  [[:db/add [:user/email "alice@example.com"] :user/name "Alice A."]])

(:user/name alice-v1)
;; => "Alice"

(:user/name (d/entity (d/db conn) [:user/email "alice@example.com"]))
;; => "Alice A."
```

```java
Map<?, ?> aliceV1 =
    conn.entityMap(List.of("user/email", "alice@example.com"));

conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "alice@example.com")
        .put("user/name", "Alice A.")));

aliceV1.get("user/name");
// => "Alice"

conn.entityMap(List.of("user/email", "alice@example.com"))
    .get("user/name");
// => "Alice A."
```

```python
alice_v1 = conn.entity([":user/email", "alice@example.com"])

conn.transact([
    {":user/email": "alice@example.com",
     ":user/name": "Alice A."}])

alice_v1[":user/name"]
# => "Alice"

conn.entity([":user/email", "alice@example.com"])[":user/name"]
# => "Alice A."
```

```javascript
const aliceV1 = await conn.entity([":user/email", "alice@example.com"]);

await conn.transact([
  { ":user/email": "alice@example.com",
    ":user/name": "Alice A." }
]);

await aliceV1.get(":user/name");
// => "Alice"

const aliceV2 = await conn.entity([":user/email", "alice@example.com"]);
await aliceV2.get(":user/name");
// => "Alice A."
```

</div>

The same rule applies to `db1` itself: it was the DB object for the read you
were doing then. For a later read, call `(d/db conn)` again after the
transaction. In Java, Python, and JavaScript, reacquire the entity or map from
the connection after a write when you need to observe current state. Reads are
stable for the operation because they use a specific DB object, not a moving
connection.

### 6.4 Why Chapter 6 Uses `d/entity`

Chapter 6 used `d/entity` inside an embedded Clojure transaction function:

```clojure
(defn rename-user [db email new-name]
  (if-some [ent (d/entity db [:user/email email])]
    [[:db/add (:db/id ent) :user/name new-name]]
    (throw (ex-info "No user with email" {:email email}))))
```

That pattern is common. The transaction function receives a DB object, uses a
lookup ref to find the current entity, reads `:db/id`, and then returns
ordinary transaction data. It does not need a separate query just to resolve the
user.

That is a read use of `d/entity`: find an entity, inspect it, and return
ordinary transaction data. Datalevin also has a transactable Entity feature,
described next.

### 6.5 Transactable Entities

Datalevin Entity objects are not plain maps, but they do support a small
staging interface for writes. `assoc`, `update`, `d/add`, `d/retract`, and
`dissoc` return a new Entity value that carries pending changes. The original
entity and the DB object it came from are not modified. The staged changes
become transaction data only when the staged entity is passed to `d/transact!`.
This staged mutation interface is Clojure-only. Java, Python, and JavaScript
entity wrappers are for lazy reads and materialization; write updates from
those languages as explicit transaction maps/forms or binding transaction
builders.

```clojure
(def db1 (d/db conn))
(def alice (d/entity db1 [:user/email "alice@example.com"]))

(def staged-alice
  (-> alice
      (assoc :user/name "Alice A.")
      (d/add :user/friends [:user/email "bob@example.com"])))

(:user/name staged-alice)
;; => "Alice A."

(:user/name alice)
;; => "Alice"
```

At this point, only `staged-alice` sees the pending name change. The database
does not change until the staged entity is transacted:

```clojure
(d/transact! conn [staged-alice])

(d/pull (d/db conn)
        '[:user/name {:user/friends [:user/name]}]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice A.",
;;     :user/friends [{:user/name "Bob"}]}
```

Use `d/add` when adding a value to a cardinality-many attribute. Use
`d/retract` with a value when removing one value, or `dissoc`/`d/retract`
without a value when retracting an entire attribute:

```clojure
(def db2 (d/db conn))
(def alice2 (d/entity db2 [:user/email "alice@example.com"]))
(def bob (d/entity db2 [:user/email "bob@example.com"]))

(d/transact! conn
  [(d/retract alice2 :user/friends bob)])
```

This feature is useful at the REPL and in small update flows because the code
can look like ordinary map transformation while still producing normal
transaction data. For larger workflows, validation-heavy updates, or non-Clojure
clients, explicit transaction maps and datom vectors are usually easier to audit
and test.


## 7. Choosing the Right Read API

Use these rules of thumb:

| Need | Use |
| :--- | :--- |
| You know a natural key and need one entity | Lookup ref with `d/pull` or `d/entity` |
| You know an entity id and need its data | `d/pull` by id, or `d/entity` by id for lazy navigation |
| You need a stable response map | `d/pull` with an explicit pattern |
| You need many response maps from known ids | `d/pull-many` |
| You need a bounded tree or hierarchy | `d/pull` with a recursive pattern such as `{:category/children 2}` |
| You need to discover matching entities | `d/q` |
| You need discovered entities in map form | `d/q` with `(pull ?e pattern)` |
| You are navigating conditionally | Clojure/Python/JavaScript entity APIs, or Java `entityMap` for a realized map |
| You are debugging an entity at the REPL | `d/entity` plus `d/touch` |
| You want to stage a small Clojure update from an entity | Transactable Entity with `assoc`, `d/add`, `d/retract`, or `dissoc` |

Do not treat `d/pull` as a guaranteed performance shortcut over `d/q`.
Datalevin's query engine is optimized, and many pull-shaped reads can be
expressed as equally if not more efficient Datalog queries. `d/pull` returns and
works on datoms, which have instantiation cost: attributes are realized into
keywords and all values are materialized in memory, whereas `d/q` works on
relations and does not normally realize data that are not bound to variables.

The main distinction is the shape of the problem:

- `d/q` is for finding facts and entities that satisfy constraints.
- `d/pull` is for returning a declared nested map for known entities.
- `d/entity` and binding entity wrappers are for lazy navigation from one known entity; Java's `entityMap` returns a realized map.
- Transactable Entity is for staging small Clojure-side updates before
  `d/transact!`.


## Summary

Datalevin gives you several ways to read the same logical facts. Lookup refs let
you use domain identifiers instead of internal ids. Pull returns explicit plain
maps for application boundaries. Entity APIs provide lazy or realized
navigation for code that wants to move through references one step at a time,
and Clojure transactable entities let that same Entity API stage small updates.
The APIs overlap by design, but they are not redundant: choose the one that
matches whether you are identifying, shaping, discovering, navigating, or
updating data.
