---
title: "Reading Data: Lookup, Pull, and Entity APIs"
chapter: 7
part: "II — Core APIs: Datalog First, KV When Needed"
---

# Chapter 7: Reading Data: Lookup, Pull, and Entity APIs

Once data is in your database, you need a way to retrieve it in the shape your
application actually uses. Datalevin gives you several read APIs, each with a
different job:

- **Lookup refs** let you address an entity by a domain identifier such as an
  email, SKU, username, or order number.
- **`d/pull`** fetches a plain map in a declared shape, including nested
  references.
- **`d/entity`** returns a lazy, map-like entity object for interactive or
  conditional navigation.
- **`d/q`** finds sets of values and entities using Datalog constraints.

This chapter covers the first three. Chapter 8 focuses on Datalog query syntax,
but queries will appear here when they help explain how lookup refs and pull
work together.

The Java, Python, and JavaScript snippets assume an open connection named
`conn`. Clojure snippets use `conn` for a connection and `db` for a Datalog DB
object obtained with `(d/db conn)` or `@conn`.

Datalevin's `db` is not a Datomic-style immutable snapshot value. It is a
mutable DB object/reference used by read APIs. Treat it as a read handle for the
current operation, not as durable application state.

Call `(d/db conn)` when you need to read the connection's current state. Do not
save a `db` object or an entity object and expect it to follow later
transactions; if freshness matters after a write, get a new `db` from the
connection before reading.

---

## 1. Reading by Identity with Lookup Refs

Datalevin stores entities internally by numeric entity id. Application code,
however, usually knows a natural key: a user's email, an account number, a URL
slug, or an external system id. A **lookup ref** is the bridge between those two
worlds. Prefer lookup refs and unique identity attributes at application
boundaries because eids are database-local handles; the same logical entity may
have different eids in two databases.

A lookup ref is a two-element vector:

```clojure
[:user/email "alice@example.com"]
```

The first element must be an attribute marked `:db/unique`, usually
`:db.unique/identity` for domain identity. The second element is the value of
that unique attribute. Datalevin resolves the vector to the matching entity id
when an API expects an entity.

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

Lookup refs work in the same places where you would otherwise pass an entity
id:

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

In non-Clojure clients, the same idea is represented as a small list or array:

<div class="multi-lang">

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

Lookup refs are also valid inputs to Datalog queries. The query engine resolves
them when they occur in positions that require an entity id:

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

If a lookup ref does not resolve, `d/entid`, `d/entity`, and `d/pull` return
`nil`. A numeric id is different: it is already an id, so `d/entid` returns it
without proving that the entity has datoms. Use a lookup ref, a query, or a
specific attribute read when you need an existence check.

---

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

```clojure
(d/pull db '[:user/name :user/email]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice", :user/email "alice@example.com"}
```

If you already have the numeric entity id, pass it directly to `d/pull` for a
shaped map or to `d/entity` for lazy navigation. You do not need a Datalog query
just to retrieve one known entity:

```clojure
(d/pull db '[:user/name :user/email] 1)
;; => {:user/name "Alice", :user/email "alice@example.com"}

(:user/name (d/entity db 1))
;; => "Alice"
```

Use `[*]` to fetch all forward attributes of the entity:

```clojure
(d/pull db '[*] [:user/email "alice@example.com"])
;; => {:db/id 1,
;;     :user/name "Alice",
;;     :user/email "alice@example.com"}
```

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

Without the nested pattern, a reference is returned as a small entity reference:

```clojure
(d/pull db '[:order/id :order/customer] [:order/id "o-1001"])
;; => {:order/id "o-1001", :order/customer {:db/id 1}}
```

That behavior is intentional. Pull only expands what the pattern asks it to
expand.

### 2.2 Pulling Many Entities

When you already have several entity ids or lookup refs, use `d/pull-many`
rather than calling `d/pull` in a loop:

```clojure
(d/pull-many db
             '[:user/name]
             [[:user/email "alice@example.com"]
              [:user/email "bob@example.com"]])
;; => [{:user/name "Alice"} {:user/name "Bob"}]
```

`d/q` can also use pull in its `:find` specification:

```clojure
(d/q '[:find [(pull ?user [:user/name :user/email]) ...]
       :where [?user :user/email]]
     db)
```

This is useful when Datalog should discover the matching entities and pull
should shape the result.

---

## 3. Reverse Pull

A reference attribute points forward from one entity to another. For example,
an order has `:order/customer` pointing to a user. Sometimes you want to start
from the user and ask, "which orders point here?"

Pull supports this with **reverse attributes**. For a namespaced attribute like
`:order/customer`, the reverse attribute is `:order/_customer`. For an
unqualified attribute like `:friend`, the reverse attribute is `:_friend`.

```clojure
(d/pull db
        '[:user/name {:order/_customer [:order/id]}]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice",
;;     :order/_customer [{:order/id "o-1001"}]}
```

As with forward references, a bare reverse attribute returns only small entity
reference maps:

```clojure
(d/pull db
        '[:user/name :order/_customer]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice",
;;     :order/_customer [{:db/id 3}]}
```

Use a nested reverse pattern when you want attributes from the entities that
point back to the current entity.

Reverse pull is not a special side table. As Chapter 15 explains, references
are values in Datalevin's indexes, so reverse reference lookup is an indexed
value-to-entity lookup.

Reverse navigation works best when the attribute is declared as
`:db.type/ref`. Without that schema information, Datalevin cannot know that the
value should be treated as an entity reference in higher-level navigation APIs.

---

## 4. Pull Pattern Options

Pull supports attribute options for common response-shaping needs.

### 4.1 Limiting Cardinality-Many Attributes

Pull returns at most 1000 values for a cardinality-many attribute by default.
Use `:limit` to choose a smaller limit, or `nil` for no limit. The option is
written as an attribute expression:

```clojure
(d/pull db '[[:user/friends :limit 5]]
        [:user/email "bob@example.com"])
```

### 4.2 Default Values

Use `:default` when the response should include a value even if the entity does
not currently have that attribute:

```clojure
(d/pull db
        '[:user/name [:user/active? :default true]]
        [:user/email "alice@example.com"])
;; => {:user/name "Alice", :user/active? true}
```

### 4.3 Attribute Renaming

Use `:as` when the external response should use a different key:

```clojure
(d/pull db
        '[[:user/name :as :name]
          [:user/email :as :email]]
        [:user/email "alice@example.com"])
;; => {:name "Alice", :email "alice@example.com"}
```

Renaming is a presentation choice. It does not change the stored attributes or
the schema.

### 4.4 Transforming Pull Values

Use `:xform` when a value should be transformed before it is placed in the pull
result. The transform may be a Clojure function value or a resolvable symbol,
such as `vector` or `clojure.string/upper-case`:

```clojure
(d/pull db
        '[[:user/name :as :name :xform clojure.string/upper-case]]
        [:user/email "alice@example.com"])
;; => {:name "ALICE"}
```

For pull patterns that cross language or process boundaries, prefer resolvable
symbols and make sure the runtime can load them. Anonymous functions are
Clojure values and are best kept to embedded Clojure code.

If an attribute is missing and no default is supplied, the transform receives
`nil`:

```clojure
(d/pull db
        '[[:user/nickname :xform vector]]
        [:user/email "alice@example.com"])
;; => {:user/nickname [nil]}
```

If `:default` is present and the attribute is missing, the default wins and is
returned as-is:

```clojure
(d/pull db
        '[[:user/nickname :default "unknown" :xform vector]]
        [:user/email "alice@example.com"])
;; => {:user/nickname "unknown"}
```

For reference, reverse-reference, and nested pull attributes, `:xform` receives
the pulled value for that attribute. This is useful for small response-shaping
steps. For transformations with business rules, error handling, or host-language
portability requirements, prefer ordinary application code after the pull.

---

## 5. Entity API: Lazy Map-Like Navigation

`d/entity` returns an entity object tied to the Datalog DB object used to create
it:

```clojure
(def alice (d/entity db [:user/email "alice@example.com"]))
```

The lazy entity object is part of the Clojure API. Java exposes related
conveniences such as `conn.entityMap(eid)`, which returns a fully realized map
for the given id or lookup ref. In Python and JavaScript code, use `pull` when
you want an explicit map-shaped read.

An entity behaves like a Clojure map for attribute lookup:

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

The important difference from `d/pull` is laziness. Creating `alice` does not
fetch every attribute. Reading `(:user/name alice)` fetches and caches that
attribute. Reading another attribute may touch storage again. This makes
`d/entity` pleasant for conditional application logic:

```clojure
(when-let [alice (d/entity db [:user/email "alice@example.com"])]
  (if (:user/active? alice)
    (:user/name alice)
    "inactive"))
```

Use `d/touch` when you intentionally want to realize all attributes of an entity
for debugging, logging, or exploratory REPL work:

```clojure
(pr-str (d/entity db [:user/email "alice@example.com"]))
;; => "{:db/id 1}"

(d/touch (d/entity db [:user/email "alice@example.com"]))
;; prints like:
;; {:db/id 1,
;;  :user/name "Alice",
;;  :user/email "alice@example.com"}
```

`d/touch` realizes the attributes, but it does not turn the entity into a plain
persistent map. The value is still an entity object. That distinction matters
with functions such as `merge` that construct maps by conjoining entries:

```clojure
(def alice-ent (d/touch (d/entity db [:user/email "alice@example.com"])))

;; Do this when ordinary map operations should own the value:
(merge (into {} alice-ent)
       {:api/source "profile"})
```

For API responses, prefer `d/pull` with an explicit pattern. `d/touch` says
"realize everything currently on this entity"; `d/pull` says "return this
declared shape."

### 5.1 Entity References

When an entity attribute is declared as `:db.type/ref`, `d/entity` returns
entity objects for the referenced entities:

```clojure
(def bob (d/entity db [:user/email "bob@example.com"]))

(:user/friends bob)
;; => a set containing Alice's entity

(map :user/name (:user/friends bob))
;; => ("Alice")
```

A cardinality-one reference returns one entity. A cardinality-many reference
returns a set of entities. Those referenced entities are lazy too, so this kind
of navigation can be very convenient in a REPL or in small conditional branches.
For large result sets, use Datalog plus pull or `pull-many` so the read shape is
explicit.

### 5.2 Reverse Navigation on Entities

Entity navigation also supports reverse attributes:

```clojure
(def alice (d/entity db [:user/email "alice@example.com"]))

(map :order/id (:order/_customer alice))
;; => ("o-1001")
```

The naming rule is the same as reverse pull:

- `:order/customer` becomes `:order/_customer`
- `:friend` becomes `:_friend`

Reverse attributes return the entities that point to the current entity through
that reference. For non-component references the result is a set. Component
references can return a single owner because a component has at most one owner
in a well-formed model.

### 5.3 Entity Values and Stale Reads

An entity is bound to the DB object used to create it. If the connection later
receives a transaction, the old entity does not update itself:

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

The same rule applies to `db1` itself: it is the DB object that was current when
`(d/db conn)` was called. For the latest committed state, call `(d/db conn)`
again after the transaction. Reads are stable because they are against a
specific DB object, not a moving connection.

### 5.4 Why Chapter 6 Uses `d/entity`

Chapter 6 used `d/entity` inside a transaction function:

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

### 5.5 Transactable Entities

Datalevin Entity objects are not plain maps, but they do support a small
staging interface for writes. `assoc`, `update`, `d/add`, `d/retract`, and
`dissoc` return a new Entity value that carries pending changes. The original
entity and the DB object it came from are not modified. The staged changes
become transaction data only when the staged entity is passed to `d/transact!`.

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

---

## 6. Choosing the Right Read API

Use these rules of thumb:

| Need | Use |
| :--- | :--- |
| You know a natural key and need one entity | Lookup ref with `d/pull` or `d/entity` |
| You know an entity id and need its data | `d/pull` by id, or `d/entity` by id for lazy navigation |
| You need a stable response map | `d/pull` with an explicit pattern |
| You need many response maps from known ids | `d/pull-many` |
| You need to discover matching entities | `d/q` |
| You need discovered entities in map form | `d/q` with `(pull ?e pattern)` |
| You are navigating conditionally in Clojure | `d/entity` |
| You are debugging an entity at the REPL | `d/entity` plus `d/touch` |
| You want to stage a small Clojure update from an entity | Transactable Entity with `assoc`, `d/add`, `d/retract`, or `dissoc` |

Do not treat `d/pull` as a guaranteed performance shortcut over `d/q`.
Datalevin's query engine is optimized, and many pull-shaped reads can be
expressed as equally efficient Datalog queries. The main distinction is the
shape of the problem:

- `d/q` is for finding facts and entities that satisfy constraints.
- `d/pull` is for returning a declared nested map for known entities.
- `d/entity` is for lazy Clojure navigation from one known entity.
- Transactable Entity is for staging small Clojure-side updates before
  `d/transact!`.

---

## Summary

Datalevin gives you several ways to read the same logical facts. Lookup refs let
you use domain identifiers instead of internal ids. Pull returns explicit plain
maps for application boundaries. Entities provide lazy, map-like navigation for
Clojure code that wants to move through references one step at a time, and
transactable entities let that same Entity API stage small updates. The APIs
overlap by design, but they are not redundant: choose the one that matches
whether you are identifying, shaping, discovering, navigating, or updating data.
