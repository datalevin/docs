---
title: "Attributes, Entities, and Namespaces"
chapter: 5
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 5: Attributes, Entities, and Namespaces

In Chapter 4, we looked at how Datalevin stores triples (datoms) in DLMDB. Now,
we move up the stack to the logical model. Datalevin's data model is built on
three pillars: **Attributes**, **Entities**, and **Namespaces**.

Unlike a relational database, Datalevin does not require you to declare a full
schema before writing data. When transaction data mentions an attribute that is
not yet in the schema, Datalevin creates a schema entry for that attribute
automatically. Schema can be created as data is written, instead of being fully
declared before data is accepted. Declared attribute properties still matter:
types, uniqueness, cardinality, indexes, and other schema properties control how
attributes are stored, constrained, indexed, and queried. The database is
flexible by default, but provides powerful controls when you need performance
and integrity.

![Attribute schema control spectrum: undeclared attributes are flexible and added automatically; declared attribute behavior such as :db/valueType, :db/cardinality, and :db/unique adds typed encoding, range queries, references, upsert, and compact storage; optional capabilities such as :db/fulltext, :db/embedding, and :db.type/idoc add search and nested-value indexes; connection-wide options such as :validate-data? and :closed-schema? add write-time checks](/images/diagrams/attribute-schema-spectrum.svg)

Figure 5.1 shows Datalevin's schema controls as a spectrum. An attribute can
start with no declaration, but you can add declared behavior when the
application needs typed storage, range access, identity, references, specialized
indexes, or stricter write-time checks. The detailed description of each control
comes in the following sections.


## 1. Attributes: Flexible Schema

An attribute (the "A" in EAV) defines a property that can be associated with an
entity. Attributes are the main subjects of a Datalevin schema.


### 1.1 Automatic Attribute Creation

By default, you don't need to "create a table" or "define a schema" to start
using Datalevin. When you transact a new attribute keyword that the database hasn't
seen before, Datalevin automatically adds it to the schema for you.

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
**EDN blob**, serialized EDN in binary form, which has some trade-offs.

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

### 1.3 Why Explicit Types Are Preferred

To enable range queries, validation, compact storage, and specialized indexes,
you are strongly encouraged to specify a data type. Explicit types allow
Datalevin to use specialized codecs for sorting values in the B+Tree instead of
treating values as opaque EDN blobs.

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

> **Note on Indexing**: Datalevin indexes **every attribute** by default in the AVE (Attribute-Value-Entity) index.

Appendix C includes a complete reference for the schema properties that
Datalevin interprets.

**Example Schema Definition:**

<div class="multi-lang">

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

```java
Schema schema = Datalevin.schema()
    .attr("user/email",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY))
    .attr("user/login-count",
          Schema.attribute().valueType(Schema.ValueType.LONG))
    .attr("user/tags",
          Schema.attribute().cardinality(Schema.Cardinality.MANY))
    .attr("user/bio",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true)
              .prop("db/embedding", true));
```

```python
schema = {
    ":user/email": {
        ":db/valueType": ":db.type/string",
        ":db/unique": ":db.unique/identity",
    },
    ":user/login-count": {":db/valueType": ":db.type/long"},
    ":user/tags": {":db/cardinality": ":db.cardinality/many"},
    ":user/bio": {
        ":db/valueType": ":db.type/string",
        ":db/fulltext": True,
        ":db/embedding": True,
    },
}
```

```javascript
const schema = {
  ":user/email": {
    ":db/valueType": ":db.type/string",
    ":db/unique": ":db.unique/identity"
  },
  ":user/login-count": { ":db/valueType": ":db.type/long" },
  ":user/tags": { ":db/cardinality": ":db.cardinality/many" },
  ":user/bio": {
    ":db/valueType": ":db.type/string",
    ":db/fulltext": true,
    ":db/embedding": true
  }
};
```

</div>

### 2.1 Cardinality: One Value or a Set of Values

Cardinality controls how many values one entity may hold for one attribute.

With the default `:db.cardinality/one`, an entity holds at most one value, and
transacting a new value **replaces** the old one. There is no separate "update"
operation: asserting `{:db/id 1 :user/email "new@example.com"}` retracts the
old email and asserts the new one in a single step.

With `:db.cardinality/many`, transacting a new value **accumulates** instead.
The values behave as a set: duplicates are ignored, and there is no ordering.

<div class="multi-lang">

```clojure
;; :user/tags is :db.cardinality/many
(d/transact! conn [{:db/id 1 :user/tags "clojure"}])
(d/transact! conn [{:db/id 1 :user/tags ["databases" "clojure"]}])
;; entity 1 now has tags "clojure" and "databases" — a set, no duplicates
```

```java
// user/tags is db.cardinality/many
conn.transact(Datalevin.tx()
    .entity(Tx.entity(1)
        .put("user/tags", "clojure")));

conn.transact(Datalevin.tx()
    .entity(Tx.entity(1)
        .put("user/tags", Datalevin.listOf("databases", "clojure"))));

// Entity 1 now has tags "clojure" and "databases": a set, no duplicates.
```

```python
# :user/tags is :db.cardinality/many
conn.transact([{":db/id": 1, ":user/tags": "clojure"}])
conn.transact([{":db/id": 1, ":user/tags": ["databases", "clojure"]}])

# Entity 1 now has tags "clojure" and "databases": a set, no duplicates.
```

```javascript
// :user/tags is :db.cardinality/many
await conn.transact([{ ":db/id": 1, ":user/tags": "clojure" }]);
await conn.transact([{ ":db/id": 1, ":user/tags": ["databases", "clojure"] }]);

// Entity 1 now has tags "clojure" and "databases": a set, no duplicates.
```

</div>

If you need an ordered or duplicated collection, model it differently: store the
collection in a tuple value if the number of items is limited and the total length
is less than 511 bytes, store a vector in an EDN-valued attribute, or model the
list items as entities that carry a position attribute (a variant of the
join-entity pattern in Chapter 11).

### 2.2 Uniqueness: Identity vs. Value

The two `:db/unique` settings both enforce that no two entities share a value
for the attribute, and both can be used in lookup refs. They differ in how a
duplicate write is handled:

- `:db.unique/value` is a pure constraint: transacting a duplicate is an
  **error**. Use it for values that must never collide but should not trigger
  upsert behavior, such as a serial number.
- `:db.unique/identity` treats the value as the entity's identity: transacting
  a map with an existing value **upserts**, i.e. it updates the existing entity
  instead of creating a new one. Use it for natural keys such as email
  addresses, slugs, or external ids that application code uses to identify an
  entity.

Chapter 6 shows lookup refs and upserts in transactions; Chapter 11 goes deeper
into identity modeling.


## 3. Namespaces: Semantic Grouping

In Datalevin, attribute names are keywords, and by convention, they almost
always include a **namespace**.

In EDN, `:user/email` is a qualified keyword. The part before the slash,
`user`, is the keyword namespace. The part after the slash, `email`, is the
keyword name. The same syntax is used for attribute names and for keyword
values, so the examples below include both.

<div class="multi-lang">

```clojure
:user/email          ;; namespace: user, name: email
:order/id            ;; namespace: order, name: id
:line-item/quantity  ;; namespace: line-item, name: quantity
:order.status/paid   ;; namespace: order.status, name: paid
```

```java
String userEmail = "user/email";              // namespace: user, name: email
String orderId = "order/id";                  // namespace: order, name: id
String lineItemQuantity = "line-item/quantity"; // namespace: line-item, name: quantity
String orderStatusPaid = "order.status/paid"; // namespace: order.status, name: paid
```

```python
user_email = ":user/email"          # namespace: user, name: email
order_id = ":order/id"              # namespace: order, name: id
line_item_quantity = ":line-item/quantity"  # namespace: line-item, name: quantity
order_status_paid = ":order.status/paid"    # namespace: order.status, name: paid
```

```javascript
const userEmail = ":user/email";          // namespace: user, name: email
const orderId = ":order/id";              // namespace: order, name: id
const lineItemQuantity = ":line-item/quantity"; // namespace: line-item, name: quantity
const orderStatusPaid = ":order.status/paid"; // namespace: order.status, name: paid
```

</div>

For attributes, the namespace is part of the attribute's identity. `:user/name`,
`:product/name`, and `:company/name` are three different attributes. They may
all store strings called "name" in English, but Datalevin stores and indexes
them as distinct facts.

A dot inside the namespace is just another character in the keyword namespace.
Datalevin does not treat `:order.status/paid` as nested under `:order/status`;
the slash is the delimiter that separates namespace from name. Dotted namespaces
are a common convention for enum value domains, such as order statuses, but
Datalevin assigns no special meaning to the dot.

### 3.1 Namespaces Are Not Tables

Namespaces are a naming convention, not a storage container or type constraint.
An entity can have attributes from more than one namespace:

<div class="multi-lang">

```clojure
{:user/email "ada@example.com"
 :account/id "acct-1"
 :account/plan :account.plan/pro}
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/email", "ada@example.com")
        .put("account/id", "acct-1")
        .put("account/plan", Datalevin.kw("account.plan/pro"))));
```

```python
from datalevin import interop

kw = interop().keyword

conn.transact([{":user/email": "ada@example.com",
                ":account/id": "acct-1",
                ":account/plan": kw(":account.plan/pro")}])
```

```javascript
import { interop } from "datalevin-node";

const raw = interop();
const pro = await raw.keyword(":account.plan/pro");

await conn.transact([{
  ":user/email": "ada@example.com",
  ":account/id": "acct-1",
  ":account/plan": pro
}]);
```

</div>

That is legal because entities are collections of facts, not rows in a table.
The namespace tells readers what the attribute means; it does not prevent the
attribute from appearing on a particular entity. If your application needs to
enforce that only certain entities have certain attributes, enforce that in the
write path or with higher-level validation.

### 3.2 Why Namespaces Are Preferred

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

<div class="multi-lang">

```clojure
{:order/status :order.status/paid}
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("order/status", Datalevin.kw("order.status/paid"))));
```

```python
from datalevin import interop

kw = interop().keyword

conn.transact([{":order/status": kw(":order.status/paid")}])
```

```javascript
import { interop } from "datalevin-node";

const raw = interop();
const paid = await raw.keyword(":order.status/paid");

await conn.transact([{ ":order/status": paid }]);
```

</div>

Here, `:order/status` is an attribute on an order. The value
`:order.status/paid` is an enum-like keyword from the order-status value domain.
This pattern keeps the attribute and its possible values distinct. It is a
naming convention, not a database requirement.

System namespaces such as `:db`, `:db.type`, `:db.cardinality`,
`:db.unique`, and `:datalevin` are used by Datalevin itself. Treat them as
reserved for schema properties, built-in values, and Datalevin-specific
metadata.

### 3.4 Datalevin Attributes and RDF Predicates

Readers who know RDF will notice the similarity: RDF has triples of
subject-predicate-object, while Datalevin has datoms of entity-attribute-value.
That resemblance is useful, but the design goals are different [1].

In RDF, predicates are often IRIs chosen from shared vocabularies or ontologies.
They are meant to carry portable meaning across datasets, organizations, and
reasoning systems. RDF Schema and OWL can then attach richer ontology semantics
to those predicates: class hierarchies, property relationships, domain/range
constraints, and inference rules [2,3].

Datalevin attributes are intentionally more local and operational. An attribute
such as `:order/placed-at` or `:invoice/issued-at` is not trying to be a
universal ontology term for time. It is a precise fact name inside your
database. Its schema properties tell Datalevin how to store, compare, index, and
validate values; they do not assert a global ontology.

That difference makes Datalevin's model more open in day-to-day application
design. You can create many specific attributes when they make the data clearer:

<div class="multi-lang">

```clojure
:order/placed-at
:invoice/issued-at
:shipment/delivered-at
:support-ticket/closed-at
```

```java
List<String> attributes = List.of(
    "order/placed-at",
    "invoice/issued-at",
    "shipment/delivered-at",
    "support-ticket/closed-at");
```

```python
attributes = [
    ":order/placed-at",
    ":invoice/issued-at",
    ":shipment/delivered-at",
    ":support-ticket/closed-at",
]
```

```javascript
const attributes = [
  ":order/placed-at",
  ":invoice/issued-at",
  ":shipment/delivered-at",
  ":support-ticket/closed-at"
];
```

</div>

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

The following are conventions and suggestions, not hard requirements. Use them
unless your domain has a strong reason to differ:

1.  Use qualified keywords for application attributes: `:user/email`, not
    `:email`.
2.  Use singular domain nouns for namespaces: `:user/email`, not
    `:users/email`. The attribute name can still be plural when it represents a
    cardinality-many value, such as `:user/emails`.
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


## 4. Entities and Ids

An **Entity** (the "E" in EAV) is simply a collection of datoms that share the
same entity id. There is no separate entity record in storage: the id is the
only thing tying the datoms together. Assert a datom with a new id and an
entity comes into existence; retract the last datom carrying an id and that
entity is gone. As Chapter 3 discussed, this is what makes entities fluid: an
entity has no declared type, only whatever attributes have been asserted about
it.

### 4.1 System-Managed Ids

Datalevin uses 64-bit integers for entity ids.

- **Auto-Increment**: When you transact a new map without an id, Datalevin
  automatically assigns a new, incrementing id.
- **Permanent**: Once assigned, an id is the permanent handle for that entity.
  Updating or adding attributes never changes the id, so references between
  entities remain stable across transactions.

In transaction maps, the system attribute `:db/id` carries the entity id. To
add facts to an existing entity, include its id; to create a new entity, omit
`:db/id` (or use a tempid, below).

`:db/id` is not a place for application identity. A real entity id is a
Datalevin-managed `long`; you cannot assign a UUID, slug, email address, or
other domain key as the permanent `:db/id`. Store those values in ordinary
unique attributes, usually `:db.unique/identity` when you want upsert behavior,
then use lookup refs such as `[:user/id some-uuid]` or `[:user/email
"alice@example.com"]`.

A positive integer in `:db/id` is treated as a concrete entity id, not as a
tempid. If the current maximum entity id is `1000` and you transact
`{:db/id 2000 :foo "bar"}`, Datalevin asserts facts for entity `2000`, advances
the internal maximum entity id to `2000`, and the next automatically assigned
entity id will be `2001`. It does not create entities `1001` through `1999`.
Use explicit positive ids only for controlled imports, restores, or other
cases where you deliberately manage id allocation. Ordinary application code
should omit `:db/id` for new entities, or use negative tempids when new
entities need to refer to one another within the same transaction.

### 4.2 Tempids

During a transaction, you often use **tempids** (temporary ids) to express
relationships between new entities before the database has assigned permanent
ids. Tempids can be a negative integer or a string.

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id -1 :user/name "Alice"}
   {:db/id -2 :user/name "Bob" :user/friend -1}]) ; Bob befriends Alice using her tempid
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("user/name", "Alice"))
    .entity(Tx.entity(-2)
        .put("user/name", "Bob")
        .put("user/friend", -1))); // Bob befriends Alice using her tempid
```

```python
conn.transact([
    {":db/id": -1, ":user/name": "Alice"},
    {":db/id": -2, ":user/name": "Bob", ":user/friend": -1}])  # Bob befriends Alice using her tempid
```

```javascript
await conn.transact([
  { ":db/id": -1, ":user/name": "Alice" },
  { ":db/id": -2, ":user/name": "Bob", ":user/friend": -1 }  // Bob befriends Alice using her tempid
]);
```

</div>

A tempid is meaningful only within a single transaction; using `-1` again in a
later transaction denotes a different new entity. To learn which permanent ids
were assigned, inspect the transaction report returned by the transact call:
its `:tempids` map translates each tempid to the entity id it received.
String tempids are also only placeholders; they do not become string-valued
entity ids.

<div class="multi-lang">

```clojure
(let [report (d/transact! conn [{:db/id -1 :user/name "Alice"}])]
  (get-in report [:tempids -1])) ;=> Alice's permanent entity id, e.g. 17
```

```java
Map<?, ?> report = conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("user/name", "Alice")));

Map<?, ?> tempids = (Map<?, ?>) report.get(Datalevin.kw("tempids"));
Object aliceId = tempids.get(-1); // Alice's permanent entity id, e.g. 17
```

```python
report = conn.transact([{":db/id": -1, ":user/name": "Alice"}])
alice_id = report[":tempids"][-1]  # Alice's permanent entity id, e.g. 17
```

```javascript
const report = await conn.transact([{ ":db/id": -1, ":user/name": "Alice" }]);
const aliceId = report[":tempids"].get(-1); // Alice's permanent entity id, e.g. 17
```

</div>

### 4.3 Entity Ids Are Internal Handles

Entity ids are assigned by the database and carry no meaning outside it. Avoid
exposing them in URLs, API payloads, or other systems: data that is exported
and re-imported, merged from another database, or rebuilt from source may end
up with different ids. Two databases can contain the same application data while
using different eids for the corresponding entities.

For a stable, externally visible identity, give the entity a natural key
declared as `:db.unique/identity` — an email, a slug, or a UUID. The example
schema in Section 2 declares `:user/email` this way, which lets the rest of
the application address that user as `[:user/email "alice@example.com"]`
without ever knowing the internal id. Chapter 11 also introduces `:db/ident`,
a built-in identity attribute for system-wide named entities such as enums.


## 5. Schema Workflow in Practice

In practice, the schema workflow often follows this path:

1. **Prototyping**: Start without a schema. Just transact maps.
2. **Relations**: Use `:db.type/ref` to connect entities, forming the graph that
   Datalog traverses so well.
3. **Optimization**: Once you know your access patterns, add `:db/valueType` to
   enable range queries on specific attributes. Chapter 11 covers the
   `update-schema` workflow, including Datalevin's supported migration from
   untyped EDN values to typed values.
4. **Integrity**: Add `:db.unique/identity` for fields like emails or slugs so
   you can use them as "lookup refs" (e.g., `[:user/email
   "alice@example.com"]`).
5. **Lock-down**: In production, consider opening the connection with
   `{:closed-schema? true}` to reject unknown attributes, and
   `{:validate-data? true}` to check values against their declared types.
   Chapter 11 covers both options.


## Summary

Datalevin's approach to schema is **"Pay as you go."** You get the speed of a
schema-less store during development, but the power of a strictly typed, indexed
database as your application matures. Namespaces keep your attributes organized,
and system-managed ids ensure your entities remain stable across transactions —
while unique identity attributes, not raw ids, provide the stable names the
outside world should use.

## References

[1] Richard Cyganiak, David Wood, and Markus Lanthaler, "RDF 1.1 Concepts and
Abstract Syntax," W3C Recommendation, February 25, 2014. URL:
<https://www.w3.org/TR/rdf11-concepts/>.

[2] Dan Brickley and R. V. Guha, "RDF Schema 1.1," W3C Recommendation, February
25, 2014. URL: <https://www.w3.org/TR/rdf-schema/>.

[3] W3C OWL Working Group, "OWL 2 Web Ontology Language Document Overview
(Second Edition)," W3C Recommendation, December 11, 2012. URL:
<https://www.w3.org/TR/owl2-overview/>.
