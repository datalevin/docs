---
title: "Attributes, Entities, and Namespaces"
chapter: 5
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 5: Attributes, Entities, and Namespaces

In Chapter 4, we looked at how Datalevin stores triples (datoms) in LMDB. Now,
we move up the stack to the logical model. Datalevin's data model is built on
three pillars: **Attributes**, **Entities**, and **Namespaces**.

Unlike a relational database, Datalevin does not require you to declare a full
schema before writing data. Its model is **schema-on-write**, in two senses:
the schema *grows* on write — each new attribute is added to the schema the
first time it appears in a transaction — and, once you declare types and
constraints for an attribute, they are *enforced* on write. The database is
flexible by default, but provides powerful controls when you need performance
and integrity.

---

## 1. Attributes: The Schema-on-Write Model

An attribute (the "A" in EAV) defines a property that can be associated with an
entity.

![Schema-on-write lifecycle: a new attribute appears on transact and is added automatically; by default it has an implicit EDN-blob type supporting exact-match lookup; declaring :db/valueType with :db/cardinality and :db/unique unlocks range and sorted queries, validation, upsert, and compact storage; adding the :db/fulltext and :db/embedding flags unlocks full-text and embedding search — a progression from flexible, zero schema to strict, enforced types and indexes](/images/diagrams/schema-on-write-lifecycle.svg)

### 1.1 Automatic Attribute Creation

By default, you don't need to "create a table" or "define a schema" to start
using Datalevin. When you transact a new attribute keyword that the database hasn't
seen before, Datalevin automatically adds it to the internal schema.

<div class="multi-lang">

```clojure
;; This works even if :user/name was never defined
(d/transact! conn [{:user/name "Alice"}])
```

```java
// This works even if user/name was never defined
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/name", "Alice")));
```

```python
# This works even if user/name was never defined
conn.transact([{":user/name": "Alice"}])
```

```javascript
// This works even if user/name was never defined
await conn.transact([{ ":user/name": "Alice" }]);
```

</div>

This convenience has a flip side: a misspelled attribute does not cause an
error. Transacting `:user/emial` quietly creates a new attribute alongside
`:user/email`, and queries against the correct name will simply not see the
misspelled facts. During development this is a minor annoyance; in production
you can open the connection with the option `{:closed-schema? true}`, which
rejects any transaction that mentions an attribute not already defined in the
schema. Chapter 11 covers this and other write-time validation options.

### 1.2 The Default Type: EDN Binary

If an attribute is added automatically, Datalevin treats its value as a generic
**EDN blob**, which has some trade-offs.

- **Pros**: You can store any Clojure data structure (maps, vectors, sets)
  directly.
- **Cons**: Because the values are stored as opaque binary blobs, the database
  cannot perform efficient **range queries** (e.g., "find all orders with total
  > 100") because it doesn't know how to sort the binary data numerically or
  alphabetically.

Note that exact-value matching still works on untyped attributes: equal values
serialize to equal bytes, so a query such as "find the order whose total is
exactly 100" can still use the index. What you lose is meaningful *ordering* —
range scans, sorted results, and comparisons. You also can't enable specialized
indexes that require typed values. Embedding search requires a declared string
type. Full-text indexing is more permissive: Datalevin calls `str` on the
transacted value before indexing it, though string attributes are still the
usual choice for human text.

### 1.3 Why Explicit Types Matter

To enable range queries, validation, compact storage, and specialized indexes,
you are encouraged to specify a data type. Explicit types allow Datalevin to use
specialized codecs for sorting values in the B+Tree instead of treating values
as opaque EDN blobs.

Some common Datalog value types for `:db/valueType`:

| Type | Use |
| :--- | :--- |
| `:db.type/string` | Strings. Use for names, emails, external ids, and text fields. |
| `:db.type/keyword` | Keywords. Good for enums such as `:order.status/paid`. |
| `:db.type/boolean` | `true` or `false`. |
| `:db.type/long` | 64-bit signed integers. |
| `:db.type/bigint` | Large integer values. |
| `:db.type/double` | 64-bit floating-point numbers. |
| `:db.type/instant` | Instants, as `java.util.Date` values or integer epoch milliseconds. |
| `:db.type/uuid` | UUID values. |
| `:db.type/bytes` | Byte arrays. |
| `:db.type/ref` | Entity references. Required for reverse lookup refs and component relationships. |

Beyond these scalar types, Datalevin offers specialized value types:
`:db.type/tuple` for stored tuple values, `:db.type/vec` for
similarity-indexed vectors (Chapter 17), and `:db.type/idoc` for path-indexed
nested documents (Chapter 14). Composite indexes over several attributes use
`:db/tupleAttrs`, not a special stored data type; Chapter 11 explains that
pattern. For a complete list of acceptable value types, please see Appendix C.

---

## 2. Attribute Properties

While attributes are created automatically, you can provide a schema map when
opening a connection with `d/get-conn` or `d/create-conn`, or later with
`d/update-schema`, to define specific behaviors for the attributes. The table
below lists some example attribute properties.

| Property | Description |
| :--- | :--- |
| `:db/valueType` | The data type (e.g., `:db.type/long`). |
| `:db/cardinality` | `:db.cardinality/one` (allow single value, default) or `:db.cardinality/many` (allow multiple values). |
| `:db/unique` | `:db.unique/identity` (unique, upsert when duplicate) or `:db.unique/value` (unique, reject duplicate). |
| `:db/fulltext` | Set to `true` to enable full-text search. Values are converted with `str` before indexing. |
| `:db/embedding` | Set to `true` on string attributes to maintain an embedding similarity index. |
| `:db/idocFormat` | Format for `:db.type/idoc` attributes: `:edn`, `:json`, or `:markdown`. |
| `:db/doc` | Human-readable documentation string for the attribute. |

> **Note on Indexing**: Unlike some other Datalog databases, Datalevin indexes **every attribute** by default in the AVE (Attribute-Value-Entity) index. You do not need to specify a `:db/index` property to enable fast lookups.

Appendix C includes a complete description of acceptable properties in the schema.

**Example Schema Definition:**
```clojure
(def schema
  {:user/email       {:db/valueType :db.type/string
                      :db/unique    :db.unique/identity}
   :user/login-count {:db/valueType :db.type/long}
   :user/tags        {:db/cardinality :db.cardinality/many}
   :user/bio         {:db/valueType :db.type/string
                      :db/fulltext  true
                      :db/embedding true}})
```

### 2.1 Cardinality: One Value or a Set of Values

Cardinality controls how many values one entity may hold for one attribute.

With the default `:db.cardinality/one`, an entity holds at most one value, and
transacting a new value **replaces** the old one. There is no separate "update"
operation: asserting `{:db/id 1 :user/email "new@example.com"}` retracts the
old email and asserts the new one in a single step.

With `:db.cardinality/many`, transacting a new value **accumulates** instead.
The values behave as a set: duplicates are ignored, and there is no ordering.

```clojure
;; :user/tags is :db.cardinality/many
(d/transact! conn [{:db/id 1 :user/tags "clojure"}])
(d/transact! conn [{:db/id 1 :user/tags ["databases" "clojure"]}])
;; entity 1 now has tags "clojure" and "databases" — a set, no duplicates
```

If you need an ordered or duplicated collection, model it differently: store a
vector in an EDN-valued attribute, or model the list items as entities that
carry a position attribute (a variant of the join-entity pattern in
Chapter 11).

### 2.2 Uniqueness: Identity vs. Value

The two `:db/unique` settings both enforce that no two entities share a value
for the attribute, but they differ in how a duplicate write is handled:

- `:db.unique/value` is a pure constraint: transacting a duplicate is an
  **error**. Use it for values that must never collide but are not used as
  handles, such as a serial number.
- `:db.unique/identity` treats the value as the entity's identity: transacting
  a map with an existing value **upserts**, i.e. it updates the existing entity
  instead of creating a new one. It also enables **lookup refs**, which let you
  address an entity as `[:user/email "alice@example.com"]` anywhere an entity
  ID is expected.

Chapter 6 shows lookup refs and upserts in transactions; Chapter 11 goes deeper
into identity modeling.

---

## 3. Namespaces: Semantic Grouping

In Datalevin, attribute names are keywords, and by convention, they almost
always include a **namespace**.

In EDN, `:user/email` is a qualified keyword. The part before the slash,
`user`, is the keyword namespace. The part after the slash, `email`, is the
keyword name.

```clojure
:user/email          ;; namespace: user, name: email
:order/id            ;; namespace: order, name: id
:line-item/quantity  ;; namespace: line-item, name: quantity
```

The namespace is part of the attribute's identity. `:user/name`,
`:product/name`, and `:company/name` are three different attributes. They may
all store strings called "name" in English, but Datalevin stores and indexes
them as distinct facts.

### 3.1 Namespaces Are Not Tables

Namespaces are a naming convention, not a storage container or type constraint.
An entity can have attributes from more than one namespace:

```clojure
{:user/email "ada@example.com"
 :account/id "acct-1"
 :account/plan :account.plan/pro}
```

That is legal because entities are collections of facts, not rows in a table.
The namespace tells readers what the attribute means; it does not prevent the
attribute from appearing on a particular entity. If your application needs to
enforce that only certain entities have certain attributes, enforce that in the
write path or with higher-level validation.

### 3.2 Why Namespaces Matter

Namespaces give Datalevin's flexible schema enough structure to remain
maintainable:

- **Collision avoidance**: `:user/id`, `:order/id`, and `:product/id` can
  coexist without fighting over a generic `:id`.
- **Query readability**: `[?o :order/customer ?u]` says more than
  `[?o :customer ?u]`.
- **Schema evolution**: A well-named attribute can be moved, indexed, or
  renamed deliberately. Chapter 11 shows the `update-schema` workflow for
  schema changes and attribute renames.
- **Shared databases**: Multiple modules can write to the same database without
  accidentally reusing vague names such as `:name`, `:status`, or `:type`.

### 3.3 Attribute Namespaces and Value Namespaces

The namespace on an attribute describes the fact. The namespace on a keyword
value describes the value domain. These often differ:

```clojure
{:order/status :order.status/paid}
```

Here, `:order/status` is an attribute on an order. The value
`:order.status/paid` is an enum-like keyword from the order-status value domain.
This pattern keeps the attribute and its possible values distinct.

System namespaces such as `:db`, `:db.type`, `:db.cardinality`,
`:db.unique`, and `:datalevin` are used by Datalevin itself. Treat them as
reserved for schema properties, built-in values, and Datalevin-specific
metadata.

### 3.4 Datalevin Attributes and RDF Predicates

Readers who know RDF will notice the similarity: RDF has triples of
subject-predicate-object, while Datalevin has datoms of entity-attribute-value.
That resemblance is useful, but the design goals are different.

In RDF, predicates are often IRIs chosen from shared vocabularies or ontologies.
They are meant to carry portable meaning across datasets, organizations, and
reasoning systems. RDF Schema and OWL can then attach richer ontology semantics
to those predicates: class hierarchies, property relationships, domain/range
constraints, and inference rules.

Datalevin attributes are intentionally more local and operational. An attribute
such as `:order/placed-at` or `:invoice/issued-at` is not trying to be a
universal ontology term for time. It is a precise fact name inside your
database. Its schema properties tell Datalevin how to store, compare, index, and
validate values; they do not assert a global ontology.

That difference makes Datalevin's model more open in day-to-day application
design. You can create many specific attributes when they make the data clearer:

```clojure
:order/placed-at
:invoice/issued-at
:shipment/delivered-at
:support-ticket/closed-at
```

Those attributes may all be `:db.type/instant`, but they mean different things
to the application. You do not need to collapse them into a generic
`:event/time` attribute just to make the vocabulary look smaller. In Datalevin,
a larger set of clear, specific attributes is often better than a small set of
overloaded general attributes.

There is still a practical boundary. Datalevin stores attributes in indexes by
an internal 32-bit attribute id, exposed in schema inspection as `:db/aid`. A
database can therefore have at most about 2.1 billion distinct attribute ids,
including built-in attributes. That ceiling is far above ordinary application
schemas, but it is a reminder that attributes are schema vocabulary, not an
unbounded place to put user-generated keys. If a key space can grow without
control, model the key as data, an enum/value, an idoc path, or a referenced
entity instead of minting a new Datalevin attribute for every key.

This does not prevent ontology-like modeling when you need it. You can still
use enum entities, references, rules, and Datalog queries to represent domain
knowledge. The point is that Datalevin does not require every attribute to be a
general-purpose ontology predicate before it can be useful.

### 3.5 Rules of Thumb

Use these conventions unless your domain has a strong reason to differ:

1.  Use qualified keywords for application attributes: `:user/email`, not
    `:email`.
2.  Use singular domain nouns: `:user/email`, not `:users/email`.
3.  Put the namespace on the entity or relationship that owns the fact:
    `:order/customer`, `:comment/post`, `:role-assignment/user`.
4.  Use separate value namespaces for enums: `:order.status/paid`,
    `:order.status/cancelled`.
5.  Avoid using namespaces as security or type boundaries. They clarify meaning;
    they do not enforce authorization or entity classes.

In Clojure source, these names are EDN keywords. In Java's builder API, the
examples use strings such as `"user/email"` and Datalevin converts them to
keywords. In Python and JavaScript examples, colon-prefixed strings such as
`":user/email"` represent the same keyword values at the binding boundary.

---

## 4. Entities and IDs

An **Entity** (the "E" in EAV) is simply a collection of datoms that share the
same Entity ID. There is no separate entity record in storage: the ID is the
only thing tying the datoms together. Assert a datom with a new ID and an
entity comes into existence; retract the last datom carrying an ID and that
entity is gone. As Chapter 3 discussed, this is what makes entities fluid: an
entity has no declared type, only whatever attributes have been asserted about
it.

### 4.1 System-Managed IDs

Datalevin uses 64-bit integers for Entity IDs.

- **Auto-Increment**: When you transact a new map without an ID, Datalevin
  automatically assigns a new, incrementing ID.
- **Permanent**: Once assigned, an ID is the permanent handle for that entity.
  Updating or adding attributes never changes the ID, so references between
  entities remain stable across transactions.

In transaction maps, the system attribute `:db/id` carries the entity ID. To
add facts to an existing entity, include its ID; to create a new entity, omit
`:db/id` (or use a tempid, below).

`:db/id` is not a place for application identity. A real entity ID is a
Datalevin-managed `long`; you cannot assign a UUID, slug, email address, or
other domain key as the permanent `:db/id`. Store those values in ordinary
attributes declared with `:db.unique/identity`, then use lookup refs such as
`[:user/id some-uuid]` or `[:user/email "alice@example.com"]`.

### 4.2 Tempids

During a transaction, you often use **tempids** (temporary IDs) to express
relationships between new entities before the database has assigned permanent
IDs. Tempids can be a negative integer or a string.

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id -1 :user/name "Alice"}
   {:db/id -2 :user/name "Bob" :user/friend -1}]) ; Bob follows Alice using her tempid
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("user/name", "Alice"))
    .entity(Tx.entity(-2)
        .put("user/name", "Bob")
        .put("user/friend", -1))); // Bob follows Alice using her tempid
```

```python
conn.transact([
    {":db/id": -1, ":user/name": "Alice"},
    {":db/id": -2, ":user/name": "Bob", ":user/friend": -1}])  # Bob follows Alice using her tempid
```

```javascript
await conn.transact([
  { ":db/id": -1, ":user/name": "Alice" },
  { ":db/id": -2, ":user/name": "Bob", ":user/friend": -1 }  // Bob follows Alice using her tempid
]);
```

</div>

A tempid is meaningful only within a single transaction; using `-1` again in a
later transaction denotes a different new entity. To learn which permanent IDs
were assigned, inspect the transaction report returned by the transact call:
its `:tempids` map translates each tempid to the entity ID it received.
String tempids are also only placeholders; they do not become string-valued
entity IDs.

```clojure
(let [report (d/transact! conn [{:db/id -1 :user/name "Alice"}])]
  (get-in report [:tempids -1])) ;=> Alice's permanent entity ID, e.g. 17
```

### 4.3 Entity IDs Are Internal Handles

Entity IDs are assigned by the database and carry no meaning outside it. Avoid
exposing them in URLs, API payloads, or other systems: data that is exported
and re-imported, merged from another database, or rebuilt from source may end
up with different IDs. Two databases can contain the same application data while
using different eids for the corresponding entities.

For a stable, externally visible identity, give the entity a natural key
declared as `:db.unique/identity` — an email, a slug, or a UUID. The example
schema in Section 2 declares `:user/email` this way, which lets the rest of
the application address that user as `[:user/email "alice@example.com"]`
without ever knowing the internal ID. Chapter 11 also introduces `:db/ident`,
a built-in identity attribute for system-wide named entities such as enums.

---

## 5. Schema Workflow in Practice

1. **Prototyping**: Start without a schema. Just transact maps.
2. **Optimization**: Once you know your access patterns, add `:db/valueType` to
   enable range queries on specific attributes. Chapter 11 covers the
   `update-schema` workflow, including Datalevin's supported migration from
   untyped EDN values to typed values.
3. **Integrity**: Add `:db.unique/identity` for fields like emails or slugs so
   you can use them as "lookup refs" (e.g., `[:user/email
   "alice@example.com"]`).
4. **Relations**: Use `:db.type/ref` to connect entities, forming the graph that
   Datalog traverses so well.
5. **Lock-down**: In production, consider opening the connection with
   `{:closed-schema? true}` to reject unknown attributes, and
   `{:validate-data? true}` to check values against their declared types.
   Chapter 11 covers both options.

---

## Summary

Datalevin's approach to schema is **"Pay as you go."** You get the speed of a
schema-less store during development, but the power of a strictly typed, indexed
database as your application matures. Namespaces keep your attributes organized,
and system-managed IDs ensure your entities remain stable across transactions —
while unique identity attributes, not raw IDs, provide the stable names the
outside world should use.
