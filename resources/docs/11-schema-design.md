---
title: "Schema Design"
chapter: 11
part: "III — Modeling Across Paradigms"
---

# Chapter 11: Schema Design

While Chapter 5 introduced the mechanics of attributes and namespaces, this
chapter dives into the *art* of schema design. In a multi-paradigm database like
Datalevin, your schema is the blueprint for how the query engine, search
indexes, and storage layer interact.

A well-designed schema in Datalevin enables efficient joins, powerful graph
traversals, full-text search, embedding search, vector search, and path-indexed
documents.

For a compact reference of every Datalog schema property and its accepted
values, see Appendix C, "Datalog Schema Reference."


## 1. The Power of Identity: `:db.unique/identity`

One of the most important decisions in schema design is how you identify your
entities. While Datalevin provides internal 64-bit integer ids, application
code usually wants to refer to entities using natural keys from your domain
(like an email, a SKU, a URL slug, or synthetic keys like a UUID).

### 1.1 Lookup Refs

Any attribute marked `:db/unique` can be used in **lookup refs**. This allows
you to refer to an entity by an attribute/value pair in any part of the API —
transactions, queries, or `d/pull` — without knowing its internal integer id.
For domain identifiers such as emails, SKUs, or URL slugs,
`:db.unique/identity` is usually the right choice because it also gives you
upsert behavior.

<div class="multi-lang">

```clojure
;; Schema definition
{:user/email {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}}

;; Use the email as a handle to pull data
(d/pull db '[*] [:user/email "alice@example.com"])

;; Update a user by their email
(d/transact! conn [[:db/add [:user/email "alice@example.com"] :user/active? true]])
```

```java
// Schema definition
Schema schema = Datalevin.schema()
    .attr("user/email", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY));

// Use the email as a handle to pull data
Map<?, ?> result = conn.pull("[*]", List.of("user/email", "alice@example.com"));

// Update a user by their email
conn.transact(Datalevin.tx()
    .add(List.of("user/email", "alice@example.com"), "user/active?", true));
```

```python
# Schema definition
schema = {":user/email": {":db/valueType": ":db.type/string",
                          ":db/unique": ":db.unique/identity"}}

# Use the email as a handle to pull data
result = conn.pull("[*]", [":user/email", "alice@example.com"])

# Update a user by their email
conn.transact([[":db/add", [":user/email", "alice@example.com"], ":user/active?", True]])
```

```javascript
// Schema definition
const schema = {":user/email": {":db/valueType": ":db.type/string",
                                ":db/unique": ":db.unique/identity"}};

// Use the email as a handle to pull data
const result = await conn.pull("[*]", [":user/email", "alice@example.com"]);

// Update a user by their email
await conn.transact([[":db/add", [":user/email", "alice@example.com"], ":user/active?", true]]);
```

</div>

### 1.2 Upsert Behavior

Attributes with `:db.unique/identity` enable **upsert** behavior. If you
transact a map with a unique identity that already exists in the database,
Datalevin will merge the new attributes into the existing entity instead of
creating a duplicate.

### 1.3 Composite Identity with Tuple Attributes

When people ask for a custom index in a Datalevin Datalog database, they often
mean a composite lookup over several attributes. Datalevin's usual answer is a
derived tuple attribute: define an attribute with `:db/tupleAttrs`, and
Datalevin maintains a composite index entry from its component attributes.

This is different from the stored tuple data type. `:db/tupleAttrs` does not
mean the application stores a special tuple value directly, and it does not
create a separate storage structure outside the normal Datalog indexes. It is a
derived composite access path over ordinary attributes. The stored tuple value
forms are `:db.type/tuple` with `:db/tupleType` or `:db/tupleTypes`, covered in
Appendix C.

A line item, for example, may be identified by the pair of order id and SKU. The
application writes `:line-item/order-id` and `:line-item/sku`; Datalevin
makes `:line-item/order+sku` available as a derived composite lookup, as
illustrated in Figure 11.1.

![Composite identity from a derived tuple: a line-item entity stores the component attributes order-id and sku; Datalevin derives the tuple attribute :line-item/order+sku via :db/tupleAttrs, marks it unique, and never requires you to write it; the unique derived tuple then enables a composite lookup ref and upsert on the component values](/images/diagrams/composite-tuple-identity.svg)

<div class="multi-lang">

```clojure
(def schema
  {:line-item/order-id   {:db/valueType :db.type/string}
   :line-item/sku        {:db/valueType :db.type/string}
   :line-item/quantity   {:db/valueType :db.type/long}
   :line-item/order+sku  {:db/tupleAttrs [:line-item/order-id :line-item/sku]
                          :db/unique     :db.unique/identity}})
```

```java
Schema schema = Datalevin.schema()
    .attr("line-item/order-id", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("line-item/sku", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("line-item/quantity", Schema.attribute()
        .valueType(Schema.ValueType.LONG))
    .attr("line-item/order+sku", Schema.attribute()
        .tupleAttrs("line-item/order-id", "line-item/sku")
        .unique(Schema.Unique.IDENTITY));
```

```python
schema = {
    ":line-item/order-id": {":db/valueType": ":db.type/string"},
    ":line-item/sku": {":db/valueType": ":db.type/string"},
    ":line-item/quantity": {":db/valueType": ":db.type/long"},
    ":line-item/order+sku": {
        ":db/tupleAttrs": [":line-item/order-id", ":line-item/sku"],
        ":db/unique": ":db.unique/identity"}}
```

```javascript
const schema = {
  ":line-item/order-id": {":db/valueType": ":db.type/string"},
  ":line-item/sku": {":db/valueType": ":db.type/string"},
  ":line-item/quantity": {":db/valueType": ":db.type/long"},
  ":line-item/order+sku": {
    ":db/tupleAttrs": [":line-item/order-id", ":line-item/sku"],
    ":db/unique": ":db.unique/identity"}
};
```

</div>

Now the composite key can be used anywhere a lookup ref can be used:

<div class="multi-lang">

```clojure
;; Create or update by component attributes. The tuple is maintained.
(d/transact! conn
  [{:line-item/order-id "o-1001"
    :line-item/sku      "SKU-42"
    :line-item/quantity 2}])

;; Update the same line item by composite lookup ref.
(d/transact! conn
  [[:db/add [:line-item/order+sku ["o-1001" "SKU-42"]]
            :line-item/quantity
            3]])

;; Pull by the same composite identity.
(d/pull (d/db conn)
        '[:line-item/sku :line-item/quantity]
        [:line-item/order+sku ["o-1001" "SKU-42"]])
```

```java
// Create or update by component attributes. The tuple is maintained.
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("line-item/order-id", "o-1001")
        .put("line-item/sku", "SKU-42")
        .put("line-item/quantity", 2L)));

// Update the same line item by composite lookup ref.
conn.transact(Datalevin.tx()
    .add(List.of("line-item/order+sku", List.of("o-1001", "SKU-42")),
         "line-item/quantity",
         3L));

// Pull by the same composite identity.
Map<?, ?> item = conn.pull(
    "[:line-item/sku :line-item/quantity]",
    List.of("line-item/order+sku", List.of("o-1001", "SKU-42")));
```

```python
# Create or update by component attributes. The tuple is maintained.
conn.transact([{":line-item/order-id": "o-1001",
                ":line-item/sku": "SKU-42",
                ":line-item/quantity": 2}])

# Update the same line item by composite lookup ref.
conn.transact([[":db/add",
                [":line-item/order+sku", ["o-1001", "SKU-42"]],
                ":line-item/quantity",
                3]])

# Pull by the same composite identity.
item = conn.pull("[:line-item/sku :line-item/quantity]",
                 [":line-item/order+sku", ["o-1001", "SKU-42"]])
```

```javascript
// Create or update by component attributes. The tuple is maintained.
await conn.transact([{":line-item/order-id": "o-1001",
                      ":line-item/sku": "SKU-42",
                      ":line-item/quantity": 2}]);

// Update the same line item by composite lookup ref.
await conn.transact([[":db/add",
                      [":line-item/order+sku", ["o-1001", "SKU-42"]],
                      ":line-item/quantity",
                      3]]);

// Pull by the same composite identity.
const item = await conn.pull("[:line-item/sku :line-item/quantity]",
                             [":line-item/order+sku", ["o-1001", "SKU-42"]]);
```

</div>

This pattern is useful for user-defined secondary access paths: tenant plus
external id, account plus date, document plus section number, order plus SKU, or
student plus course plus term. If the derived tuple attribute is also unique, it
becomes a composite identity and participates in upsert.

There are a few important rules:

- Write the component attributes, not the derived tuple attribute. Datalevin
  updates the derived composite index when components change.
- `:db/tupleAttrs` must name cardinality-one attributes.
- A derived tuple cannot depend on another derived tuple.
- If a component is missing, the derived tuple may contain `nil` in that
  position. Datalevin still compares the whole tuple value structurally, so two
  tuples with `nil` in the same position and the same remaining values are not
  distinct for uniqueness checks. This differs from SQL databases that allow
  multiple `NULL` values in a unique index. Decide whether that is acceptable
  before making the tuple unique.
- Composite lookup refs are simplest when the tuple components are scalar
  identity values such as tenant id, order id, SKU, date, or source-system id.
  If a component attribute is a reference, the tuple stores the resolved entity
  id; keep that in mind when constructing tuple lookup values.
- For ad hoc query-side tuple construction and destructuring, use the built-in
  `tuple` and `untuple` functions:

<div class="multi-lang">

```clojure
;; Build a tuple in the query and match it against the derived attribute.
(d/q '[:find ?line
       :where [?line :line-item/order-id ?order-id]
              [?line :line-item/sku ?sku]
              [(tuple ?order-id ?sku) ?order+sku]
              [?line :line-item/order+sku ?order+sku]]
     (d/db conn))

;; Destructure a tuple value back into its components.
(d/q '[:find ?line ?order-id ?sku
       :where [?line :line-item/order+sku ?order+sku]
              [(untuple ?order+sku) [?order-id ?sku]]]
     (d/db conn))
```

```java
Object lines = conn.query(
    "[:find ?line " +
    " :where [?line :line-item/order-id ?order-id] " +
    "        [?line :line-item/sku ?sku] " +
    "        [(tuple ?order-id ?sku) ?order+sku] " +
    "        [?line :line-item/order+sku ?order+sku]]");

Object parts = conn.query(
    "[:find ?line ?order-id ?sku " +
    " :where [?line :line-item/order+sku ?order+sku] " +
    "        [(untuple ?order+sku) [?order-id ?sku]]]");
```

```python
lines = conn.query("""
[:find ?line
 :where [?line :line-item/order-id ?order-id]
        [?line :line-item/sku ?sku]
        [(tuple ?order-id ?sku) ?order+sku]
        [?line :line-item/order+sku ?order+sku]]
""")

parts = conn.query("""
[:find ?line ?order-id ?sku
 :where [?line :line-item/order+sku ?order+sku]
        [(untuple ?order+sku) [?order-id ?sku]]]
""")
```

```javascript
const lines = await conn.query(
  `[:find ?line
    :where [?line :line-item/order-id ?order-id]
           [?line :line-item/sku ?sku]
           [(tuple ?order-id ?sku) ?order+sku]
           [?line :line-item/order+sku ?order+sku]]`);

const parts = await conn.query(
  `[:find ?line ?order-id ?sku
    :where [?line :line-item/order+sku ?order+sku]
           [(untuple ?order+sku) [?order-id ?sku]]]`);
```

</div>

For homogeneous and heterogeneous tuple values that are not derived from other
attributes, use `:db/tupleType` or `:db/tupleTypes`; Appendix C summarizes those
forms.

### 1.4 System-Wide Identifiers: `:db/ident`

While `:db.unique/identity` is used for attributes that uniquely identify a
domain entity (like a user's email), **`:db/ident`** is a built-in attribute
used to assign a globally unique keyword to an entity.

This is the standard way to represent **enums** or system-wide constants. Once
an entity has a `:db/ident`, you can use that keyword anywhere you would use an
entity id or a lookup ref.

<div class="multi-lang">

```clojure
;; Schema includes a ref attribute for the enum-valued field
(def schema {:order/id     {:db/valueType :db.type/string
                            :db/unique    :db.unique/identity}
             :order/status {:db/valueType :db.type/ref}})

;; Define an "enum" entity for order status
(d/transact! conn [{:db/ident :order.status/shipped}])

;; Use the keyword directly in another transaction
(d/transact! conn [{:order/id "123" :order/status :order.status/shipped}])
```

```java
// Schema includes a ref attribute for the enum-valued field
Schema schema = Datalevin.schema()
    .attr("order/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("order/status", Schema.attribute()
        .valueType(Schema.ValueType.REF));

// Define an "enum" entity for order status
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("db/ident", Datalevin.kw("order.status/shipped"))));

// Use the keyword directly in another transaction
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("order/id", "123")
        .put("order/status", Datalevin.kw("order.status/shipped"))));
```

```python
from datalevin import interop

kw = interop().keyword

# Schema includes a ref attribute for the enum-valued field
schema = {":order/id": {":db/valueType": ":db.type/string",
                        ":db/unique": ":db.unique/identity"},
          ":order/status": {":db/valueType": ":db.type/ref"}}

# Define an "enum" entity for order status
conn.transact([{":db/ident": kw(":order.status/shipped")}])

# Use the keyword directly in another transaction
conn.transact([{":order/id": "123",
                ":order/status": kw(":order.status/shipped")}])
```

```javascript
import { interop } from "datalevin-node";

const raw = interop();
const shipped = await raw.keyword(":order.status/shipped");

// Schema includes a ref attribute for the enum-valued field
const schema = {
  ":order/id": {":db/valueType": ":db.type/string",
                ":db/unique": ":db.unique/identity"},
  ":order/status": {":db/valueType": ":db.type/ref"}
};

// Define an "enum" entity for order status
await conn.transact([{":db/ident": shipped}]);

// Use the keyword directly in another transaction
await conn.transact([{":order/id": "123", ":order/status": shipped}]);
```

</div>

The important point is that `:order/status` is a `:db.type/ref`, so it takes an
entity id. Here a `:db/ident` serves the purpose of an entity id.

The advantage of using `:db/ident` over raw strings or keywords is that the enum
itself is a full-fledged entity. You can attach additional metadata to it (like
`:order.status/label "Shipped"`) without changing your data.


## 2. Modeling Relationships: References and Cardinality

In Datalevin, relationships are first-class citizens. They are defined using the
`:db.type/ref` value type and the `:db/cardinality` property. **Crucially, the
value of a reference attribute is always the entity id (a 64-bit integer) of the
target entity.** In transaction data, you can still use tempids, lookup refs, or
`:db/ident` keywords as convenient inputs; Datalevin resolves them to entity ids
when it writes the datoms.

One of the most important decisions when modeling a relationship is its
cardinality [1], also called association multiplicity: one-to-one, one-to-many,
or many-to-many.

### 2.1 One-to-One and One-to-Many

- **One-to-One**: Use `:db.type/ref` with `:db.cardinality/one`.
  - Example: A `User` has one `Profile`.
- **One-to-Many**: Usually put a cardinality-one ref on the many side.
  - Example: A `Post` has many `Comment` entities, so each `Comment` carries a
    reference to its `Post` through `:comment/post`.

The one-to-many example is worth stating carefully: you do not need a
`:post/comments` cardinality-many attribute just to find a post's comments.
Store `:comment/post` on each comment, then query or pull the reverse direction
when you need the collection. The broader performance rationale for normalized
relationship facts is covered in the many-to-many discussion below.

### 2.2 Many-to-Many: Cardinality vs. Join Entities

For many-to-many relationships, you have two main ways to model them, as
compared in Figure 11.2.

1.  **`:db.cardinality/many`**: You can add an attribute with
    `:db.cardinality/many` to one or both entities. This is highly convenient
    and results in a "cleaner" data structure when using `d/pull`.
2.  **Join Entities**: You can create a third entity that "joins" the other two,
    similar to a join table in SQL.

![Many-to-many models compared: a :db.cardinality/many ref puts :user/roles holding role/admin and role/editor on user u-1 — convenient and pulls as one nested list, but each member is still a separate datom, the relationship can't carry attributes, and very large sets get costly; a join entity uses role-assignment entities linking u-1 to each role — normalized facts the optimizer can count, join, and filter, scaling to large relationships and able to carry edge attributes like assigned-at, validity, rank, or source](/images/diagrams/many-to-many-models.svg)

**Performance Tip: Prefer Normalized Relationship Facts.**

While `:db.cardinality/many` is convenient, it is not a compact array stored
inside one datom. Each member is a separate datom, logically shaped like
`[entity attribute value]`, and it participates in the indexes. DUPSORT reduces
some repeated-prefix cost at the storage layer, but a large many-valued
attribute still carries real index-entry overhead and repeated entity/attribute
structure. In addition, scanning a cardinality-many attribute in the EAV index
is slower than scanning a cardinality-one attribute.

Datalevin is highly optimized for **normalized data** [2]: small relationship facts
whose access paths are explicit. For one-to-many relationships, this often means
placing a cardinality-one reference on the many side. For many-to-many
relationships, it often means creating a join entity.

This shape gives the query optimizer more granular facts to count, join, and
filter. It also avoids treating a very large relationship set as if it were a
small property of one entity. If you expect a single entity to have thousands of
references, such as a "Public" group with millions of members, a join entity is
the better default for performance and operational clarity. It is also the right
model when the relationship itself has attributes, such as role assignment time,
quantity, rank, validity interval, or source system.

<div class="multi-lang">

```clojure
;; Instead of many references in one entity:
{:user/id "u-1" :user/roles [:role/admin :role/editor]}

;; Use join entities for better performance:
(d/transact! conn
  [{:role-assignment/user [:user/id "u-1"]
    :role-assignment/role :role/admin}
   {:role-assignment/user [:user/id "u-1"]
    :role-assignment/role :role/editor}])
```

```java
// Instead of many references in one entity:
Tx.entity()
    .put("user/id", "u-1")
    .put("user/roles", List.of(Datalevin.kw("role/admin"),
                               Datalevin.kw("role/editor")));

// Use join entities for better performance:
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("role-assignment/user", List.of("user/id", "u-1"))
        .put("role-assignment/role", Datalevin.kw("role/admin")))
    .entity(Tx.entity()
        .put("role-assignment/user", List.of("user/id", "u-1"))
        .put("role-assignment/role", Datalevin.kw("role/editor"))));
```

```python
from datalevin import interop

kw = interop().keyword

# Instead of many references in one entity:
many_roles = {":user/id": "u-1",
              ":user/roles": [kw(":role/admin"), kw(":role/editor")]}

# Use join entities for better performance:
conn.transact([
    {":role-assignment/user": [":user/id", "u-1"],
     ":role-assignment/role": kw(":role/admin")},
    {":role-assignment/user": [":user/id", "u-1"],
     ":role-assignment/role": kw(":role/editor")}])
```

```javascript
import { interop } from "datalevin-node";

const raw = interop();
const admin = await raw.keyword(":role/admin");
const editor = await raw.keyword(":role/editor");

// Instead of many references in one entity:
const manyRoles = {":user/id": "u-1", ":user/roles": [admin, editor]};

// Use join entities for better performance:
await conn.transact([
  {":role-assignment/user": [":user/id", "u-1"],
   ":role-assignment/role": admin},
  {":role-assignment/user": [":user/id", "u-1"],
   ":role-assignment/role": editor}
]);
```

</div>

### 2.3 Reference Integrity

Because Datalevin is fact-oriented and flexible, it does not enforce traditional
"foreign key" constraints by default. If you delete an entity that is referred
to by others, the references to its id will remain (pointing to a non-existent
entity).

To ensure a clean removal of an entity and its associated facts, it is highly
recommended to use the **`[:db/retractEntity <eid>]`** transaction function.
This operation will:

1.  Retract all facts where the entity is the **subject** (E).
2.  Retract all facts where the entity is the **value** (V) for any
    `:db.type/ref` attribute.
3.  Recursively retract any entities marked as **components** of the retracted
    entity.

By using `:db/retractEntity`, you ensure that no dangling `:db.type/ref`
references point to a non-existent entity id, effectively maintaining reference
integrity for declared references.


## 3. Attribute Properties: Search and Ownership

Datalevin allows you to attach properties to attributes that change how the
database indexes, searches, or interprets relationships. These are different
from value types: `:db/valueType` says what kind of value an attribute stores,
while properties such as `:db/fulltext`, `:db/embedding`, and
`:db/isComponent` add behavior around that value.

### 3.1 Full-Text Search (`:db/fulltext`)

Setting `:db/fulltext true` tells Datalevin to maintain a specialized full-text
index for that attribute, indexing values as text. This enables the
`(fulltext ...)` predicate in Datalog (see Chapter 16).

This property is not limited to `:db.type/string`: the system calls the `str`
function on the value to convert it to a string before performing full-text
indexing.

### 3.2 Embedding Search (`:db/embedding`)

Setting `:db/embedding true` on a `:db.type/string` attribute tells Datalevin to
compute text embeddings and maintain an embedding similarity index. Query it
with `embedding-neighbors` using query text, not a vector (see Chapter 17).
Embedding indexing is synchronous by default, but embedding domains can opt into
`:indexing-mode :async` when provider calls are expensive.

### 3.3 Component Attributes (`:db/isComponent`)

When a reference attribute is marked as `:db/isComponent true`, it signals a
"parent-child" relationship.

- **Recursive Pull**: A wildcard `d/pull` on the parent will automatically
  include all attributes of the child component.
- **Cascading Deletes**: When the parent entity is deleted (via the
  **`:db/retractEntity`** transaction function), the child component entities
  are also automatically deleted.

This is ideal for modeling "owned" data, like line items in an invoice or
segments of a document.


## 4. Specialized Value Types

Two specialized value types have secondary indexing behavior: vector values and
indexed documents. They are declared with `:db/valueType`, just like strings,
longs, refs, and tuples. They appear here only to clarify where they fit in
schema design.

### 4.1 Vector Values (`:db.type/vec`)

Use `:db.type/vec` when your application supplies vectors directly. Query these
attributes with `vec-neighbors`. Vector dimensions and metric settings belong in
store options such as `:vector-opts` or `:vector-domains`, not in each
individual datom.

### 4.2 Indexed Documents (`:db.type/idoc`)

Use `:db.type/idoc` for nested maps that should be stored as one value but
queried by path with `idoc-match`. This is useful for flexible metadata, JSON
import, and Markdown-derived structures.


## 5. Best Practices: Designing for Evolution

Datalevin's flexibility requires some care in schema maintenance. Here are best
practices to keep your schema maintainable.

### 5.1 Namespace Everything

Always use namespaces for your attributes. A common pattern is
`[domain]/[property]`, such as `:account/balance` or `:sensor/reading`. This
prevents collisions if you later integrate third-party data or modularize your
application.

### 5.2 Prefer Flat Entities

While `:db/isComponent` is useful for true ownership, don't over-nest your data.
Datalevin excels at flat, normalized facts. The query engine is designed to join
flat facts efficiently, so you don't need to "pre-join" data into complex
documents as you might in a document store like MongoDB.

### 5.3 Modeling Enums with `:db/ident`

For attributes that have a fixed set of values (like status or type), you can
use raw Clojure keywords as values, but it is often better to model them as
**enum entities** using `:db/ident`.

<div class="multi-lang">

```clojure
;; Schema includes a ref attribute for order status
(def schema {:order/id     {:db/valueType :db.type/string
                            :db/unique    :db.unique/identity}
             :order/status {:db/valueType :db.type/ref}})

;; 1. Define your "enum" entities first
(d/transact! conn [{:db/ident :status/shipped}
                   {:db/ident :status/pending}])

;; 2. Your data refers to these entities by their ident
(d/transact! conn [{:order/id "123" :order/status :status/shipped}])
```

```java
// Schema includes a ref attribute for order status
Schema schema = Datalevin.schema()
    .attr("order/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("order/status", Schema.attribute()
        .valueType(Schema.ValueType.REF));

// 1. Define your "enum" entities first
conn.transact(Datalevin.tx()
    .entity(Tx.entity().put("db/ident", Datalevin.kw("status/shipped")))
    .entity(Tx.entity().put("db/ident", Datalevin.kw("status/pending"))));

// 2. Your data refers to these entities by their ident
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("order/id", "123")
        .put("order/status", Datalevin.kw("status/shipped"))));
```

```python
from datalevin import interop

kw = interop().keyword

# Schema includes a ref attribute for order status
schema = {":order/id": {":db/valueType": ":db.type/string",
                        ":db/unique": ":db.unique/identity"},
          ":order/status": {":db/valueType": ":db.type/ref"}}

# 1. Define your "enum" entities first
conn.transact([{":db/ident": kw(":status/shipped")},
               {":db/ident": kw(":status/pending")}])

# 2. Your data refers to these entities by their ident
conn.transact([{":order/id": "123",
                ":order/status": kw(":status/shipped")}])
```

```javascript
import { interop } from "datalevin-node";

const raw = interop();
const shipped = await raw.keyword(":status/shipped");
const pending = await raw.keyword(":status/pending");

// Schema includes a ref attribute for order status
const schema = {
  ":order/id": {":db/valueType": ":db.type/string",
                ":db/unique": ":db.unique/identity"},
  ":order/status": {":db/valueType": ":db.type/ref"}
};

// 1. Define your "enum" entities first
await conn.transact([{":db/ident": shipped},
                     {":db/ident": pending}]);

// 2. Your data refers to these entities by their ident
await conn.transact([{":order/id": "123", ":order/status": shipped}]);
```

</div>

By modeling enums as entities with `:db/ident`, you gain:

1.  **Metadata**: You can attach display names, descriptions, or translations
    directly to the enum entity.
2.  **Reference Semantics**: If you use a `:db.type/ref` for the status
    attribute, Datalog joins and pulls treat the status as an entity reference
    rather than an arbitrary string.
3.  **Discovery**: You can query for all possible statuses using Datalog.

### 5.4 Evolve Schema Explicitly

Datalevin can create schema entries when new attributes appear in transaction
data, but schema is still operational state. Initial schema is passed when
opening a connection; later changes go through `update-schema` on the open
connection. Schema changes are not transacted as ordinary datoms.

Use `d/schema` to inspect the current effective schema before and after a
change:

<div class="multi-lang">

```clojure
(d/schema conn)
```

```java
Map<?, ?> effectiveSchema = conn.schema();
```

```python
effective_schema = conn.schema()
```

```javascript
const effectiveSchema = await conn.schema();
```

</div>

The returned map includes the schema you supplied, built-in attributes such as
`:db/ident`, and Datalevin's internal attribute ids. This is useful for checking
what the database actually knows about an attribute, debugging unexpected value
encoding or lookup-ref behavior, and verifying that a schema migration did what
you expected.

Do not confuse `d/schema` with the Java helper `Datalevin.schema()` used in
multi-language examples. `d/schema` reads schema from an open database
connection; `Datalevin.schema()` is a host-language builder for constructing a
schema map before passing it to Datalevin.

Use schema evolution for three common cases:

1.  **Add or refine schema properties** for an attribute your application now
    wants to query, sort, search, or validate more precisely.
2.  **Rename an attribute** when the model name has changed but the existing
    facts should remain. Datalevin keeps the same internal attribute id, so
    existing datoms become readable under the new attribute name.
3.  **Delete schema metadata** for an attribute only after all datoms using
    that attribute have been removed. Datalevin rejects deletion while facts
    still exist for the attribute.

<div class="multi-lang">

```clojure
;; Add or refine schema for an open connection.
(d/update-schema conn
                 {:user/last-login {:db/valueType :db.type/instant}})

;; Rename an attribute. Existing facts are read as :user/contact-email.
(d/update-schema conn nil nil {:user/email :user/contact-email})

;; Delete schema metadata only after no facts remain for the attribute.
(d/update-schema conn nil #{:user/temporary-note})
```

```java
// Add or refine schema for an open connection.
Schema update = Datalevin.schema()
    .attr("user/last-login", Schema.attribute()
        .valueType(Schema.ValueType.INSTANT));

conn.updateSchema(update);

// Rename an attribute. Existing facts are read as :user/contact-email.
conn.updateSchema((Schema) null, null,
    Map.of("user/email", "user/contact-email"));

// Delete schema metadata only after no facts remain for the attribute.
conn.updateSchema((Schema) null, List.of("user/temporary-note"), null);
```

```python
# Add or refine schema for an open connection.
conn.update_schema({":user/last-login":
                    {":db/valueType": ":db.type/instant"}})

# Rename an attribute. Existing facts are read as :user/contact-email.
conn.update_schema(None,
                   rename_map={":user/email": ":user/contact-email"})

# Delete schema metadata only after no facts remain for the attribute.
conn.update_schema(None, del_attrs=[":user/temporary-note"])
```

```javascript
// Add or refine schema for an open connection.
await conn.updateSchema({
  ":user/last-login": {":db/valueType": ":db.type/instant"}
});

// Rename an attribute. Existing facts are read as :user/contact-email.
await conn.updateSchema(null, {
  renameMap: {":user/email": ":user/contact-email"}
});

// Delete schema metadata only after no facts remain for the attribute.
await conn.updateSchema(null, {delAttrs: [":user/temporary-note"]});
```

</div>

One especially useful evolution path is moving a prototype attribute from
untyped EDN storage to a typed encoding. When you use `update-schema` to add
`:db/valueType` to an existing attribute that was previously untyped, Datalevin
migrates the stored values as part of the schema update:

1.  It reads the existing EDN binary values for the attribute.
2.  It attempts to decode each value into the new specified type.
3.  It rewrites the index entries to use the typed encoding, enabling efficient
    typed comparisons and range scans.

This path supports the common workflow of starting flexible during prototyping
and tightening the schema once access patterns are known. It applies to the
untyped-to-typed case; changing one declared type to another declared type is a
different, incompatible migration once data exists.

### 5.5 Use Schema Validation and Coercion Deliberately

Datalevin schema also participates in transaction preparation. Declared
`:db/valueType` properties tell Datalevin how to encode values, and store
options decide how strictly the transaction input is checked before those
values are written.

Two options are especially important:

- **`:validate-data? true`** checks transaction values against declared
  `:db/valueType` before writing. The default is `false`.
- **`:closed-schema? true`** rejects transactions that mention attributes not
  already present in the schema. The default is `false`, which allows new
  attributes to be added as data is written.

One validation rule is always important: Datalevin does **not** store `nil`
values. In JavaScript and Python client code, this also means application-level
`null` or `None` must not be used as a stored attribute value. A missing value is
represented by the absence of a datom, not by a stored null marker.

<div class="multi-lang">

```clojure
(def schema
  {:user/id        {:db/valueType :db.type/string
                   :db/unique    :db.unique/identity}
   :user/age       {:db/valueType :db.type/long}
   :user/signup-at {:db/valueType :db.type/instant}})

(def conn
  (d/get-conn "/tmp/datalevin/users"
              schema
              {:validate-data? true
               :closed-schema? true}))

;; This fails: Datalevin rejects nil values.
(d/transact! conn [{:user/id "u-1"
                    :user/age nil}])
```

```java
Schema schema = Datalevin.schema()
    .attr("user/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("user/age", Schema.attribute()
        .valueType(Schema.ValueType.LONG))
    .attr("user/signup-at", Schema.attribute()
        .valueType(Schema.ValueType.INSTANT));

Connection conn = Datalevin.getConn(
    "/tmp/datalevin/users",
    schema,
    Map.of("validate-data?", true,
           "closed-schema?", true));

// This fails: Datalevin rejects null values.
Map<String, Object> badUser = new LinkedHashMap<>();
badUser.put("user/id", "u-1");
badUser.put("user/age", null);
conn.transact(Datalevin.tx().entity(badUser));
```

```python
from datalevin import connect

schema = {":user/id": {":db/valueType": ":db.type/string",
                       ":db/unique": ":db.unique/identity"},
          ":user/age": {":db/valueType": ":db.type/long"},
          ":user/signup-at": {":db/valueType": ":db.type/instant"}}

conn = connect("/tmp/datalevin/users",
               schema=schema,
               opts={":validate-data?": True,
                     ":closed-schema?": True})

# This fails: Datalevin rejects None values.
conn.transact([{":user/id": "u-1",
                ":user/age": None}])
```

```javascript
import { connect } from "datalevin-node";

const schema = {
  ":user/id": {":db/valueType": ":db.type/string",
               ":db/unique": ":db.unique/identity"},
  ":user/age": {":db/valueType": ":db.type/long"},
  ":user/signup-at": {":db/valueType": ":db.type/instant"}
};

const conn = await connect("/tmp/datalevin/users", {
  schema,
  opts: {":validate-data?": true, ":closed-schema?": true}
});

// This fails: Datalevin rejects null values.
await conn.transact([{":user/id": "u-1",
                      ":user/age": null}]);
```

</div>

With `:validate-data? true`, a transaction that writes `"42"` to
`:user/age`, a string UUID to a `:db.type/uuid` attribute, or a timestamp string
to a `:db.type/instant` attribute is rejected unless the value already has the
declared runtime type. This is useful at system boundaries where you want
malformed input to fail early.

With the default `:validate-data? false`, Datalevin still uses the declared type
to canonicalize values where possible before encoding ordinary attribute values.
For example, values may be converted to strings, numeric values may be narrowed
to `long`, `float`, or `double`, strings may be parsed as UUIDs, integers may be
converted to instants, keywords and symbols may be normalized, and tuple values
are stored as vectors. This is convenient for trusted inputs, but it is not a
substitute for application validation when user input quality matters. In
particular, use correctly typed values for identities and lookup-facing
attributes; Datalevin may need those values before general value correction.

Schema validation can enforce declared value types, uniqueness semantics,
cardinality behavior, closed-schema writes, tuple shape, specialized parsers
such as `:db.type/idoc`, and per-attribute predicates with `:db.attr/preds`.
Use attribute predicates for checks that depend only on the value being written
for that attribute. Attribute predicates are named by qualified symbols, receive
the normalized attribute value, and must return `true`:

```clojure
{:bank/routing-number {:db/valueType  :db.type/string
                       :db.attr/preds ['my.app.payments/routing-number?]}
 :venmo/handle        {:db/valueType  :db.type/string
                       :db.attr/preds ['my.app.payments/venmo-handle?]}}
```

An attribute predicate runs when that attribute is present. It is not an entity
shape rule: it does not decide that every bank account must have both a routing
number and an account number, or that every Venmo account must have a handle.
Use `:db/ensure` for entity invariants over the would-be transaction result.

An ensure predicate receives the would-be database after applying the
transaction followed by its resolved arguments, and should return truthy on
success. Because `:db/ensure` runs after transaction functions, tempid
resolution, upserts, map expansion, retractions, and other transaction data have
produced the would-be final datoms, it checks the would-be entity state rather
than only the inputs that appeared before it in `tx-data`.

```clojure
(ns my.app.payments
  (:require [clojure.string :as str]
            [datalevin.core :as d]))

(defn non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(defn routing-number? [v]
  (boolean (re-matches #"\d{9}" v)))

(defn venmo-handle? [v]
  (and (non-blank-string? v)
       (str/starts-with? v "@")))

(defn bank-account? [db eid]
  (let [account (d/pull db
                        '[:account/owner
                          :account/identity
                          :account/type
                          :bank/routing-number
                          :bank/account-number]
                        eid)]
    (and (= :account.type/bank (:account/type account))
         (some? (:account/owner account))
         (non-blank-string? (:account/identity account))
         (non-blank-string? (:bank/routing-number account))
         (non-blank-string? (:bank/account-number account)))))

(defn venmo-account? [db eid]
  (let [account (d/pull db
                        '[:account/owner
                          :account/identity
                          :account/type
                          :venmo/handle]
                        eid)]
    (and (= :account.type/venmo (:account/type account))
         (some? (:account/owner account))
         (non-blank-string? (:account/identity account))
         (non-blank-string? (:venmo/handle account)))))

(def schema
  {:account/identity   {:db/valueType  :db.type/string
                        :db.attr/preds ['my.app.payments/non-blank-string?]}
   :account/type       {:db/valueType  :db.type/keyword}
   :account/created-at {:db/valueType  :db.type/instant}
   :account/owner      {:db/valueType  :db.type/ref}
   :bank/routing-number {:db/valueType  :db.type/string
                         :db.attr/preds ['my.app.payments/routing-number?]}
   :bank/account-number {:db/valueType  :db.type/string
                         :db.attr/preds ['my.app.payments/non-blank-string?]}
   :venmo/handle        {:db/valueType  :db.type/string
                         :db.attr/preds ['my.app.payments/venmo-handle?]}})

(defn ->base-account [input]
  {:account/owner      (:owner input)
   :account/identity   (:identity input)
   :account/created-at (:created-at input)})

(defn ->bank-account [input]
  (assoc (->base-account input)
         :account/type        :account.type/bank
         :bank/routing-number (:routing-number input)
         :bank/account-number (:account-number input)))

(defn ->venmo-account [input]
  (assoc (->base-account input)
         :account/type  :account.type/venmo
         :venmo/handle  (:venmo-handle input)))

;; Fails because the would-be bank account has no account number.
(d/transact! conn
  [(assoc (->bank-account {:owner          42
                           :identity       "acct-1"
                           :routing-number "021000021"})
          :db/id "acct")
   [:db/ensure 'my.app.payments/bank-account? "acct"]])

;; Succeeds; the tempid in :db/ensure is resolved before the predicate runs.
(d/transact! conn
  [(assoc (->venmo-account {:owner        42
                            :identity     "acct-2"
                            :created-at   #inst "2026-07-01T00:00:00.000-00:00"
                            :venmo-handle "@ada"})
          :db/id "acct")
   [:db/ensure 'my.app.payments/venmo-account? "acct"]])
```

The account constructors remain ordinary map builders, so the base and
type-specific constructors are still composable. The invariant guarantee comes
from the explicit `:db/ensure` form in the transaction. A transaction function is
still useful when you want a named command to construct transaction data, but it
is not a substitute for a post-condition: transaction functions expand while the
transaction is being prepared, while `:db/ensure` observes the would-be result
before commit. If a value becomes unknown or inapplicable, retract the existing
datom or omit the attribute in new transaction data; do not replace it with
`nil`.

Not every property can be changed freely once data exists. Datalevin validates
schema mutations against stored datoms:

- Changing `:db/valueType` is rejected when typed data already exists, except
  for migrating an untyped EDN attribute to a specific type.
- Changing `:db/cardinality` from many to one is rejected when the attribute has
  existing datoms.
- Adding `:db/unique` is allowed only when the existing values are consistent
  with the requested uniqueness.
- Changing embedding-related schema on a populated attribute requires an
  explicit secondary-index rebuild.

For incompatible changes, treat the change as a data migration: introduce the
new attribute, backfill it from the old one, move readers and writers, retract
the old facts, then delete the old schema metadata. This is usually safer than
trying to change the meaning of a populated attribute in place.


## Summary: The Schema Checklist

When adding a new attribute to your Datalevin database, ask yourself:

1.  **What is the type?** Use a specific type (`:db.type/long`,
    `:db.type/string`, etc.) for performance and range queries.
2.  **Is it a reference?** Use `:db.type/ref` to enable joins and graph
    traversals.
3.  **Is it a unique identity?** Use `:db.unique/identity` for natural keys
    (like emails) that you will use to lookup or upsert entities.
4.  **Is it a system-wide constant?** Use **`:db/ident`** to give a unique,
    globally namespaced keyword to an entity, perfect for enums and static
    system data.
5.  **Is it many-valued?** Use `:db.cardinality/many` for small sets of values,
    or join entities when the relationship is large or needs its own facts.
6.  **Does it need keyword search?** Use `:db/fulltext` for attributes you want
    to search as text.
7.  **Does it need semantic text search?** Use `:db/embedding` for string
    attributes Datalevin should embed.
8.  **Do you already have vectors?** Use `:db.type/vec` and configure vector
    domains.
9.  **Is it flexible nested data?** Use `:db.type/idoc` for path-indexed
    documents.
10. **Is it a component?** Use `:db/isComponent` for nested, "owned" entities.
11. **How will it evolve?** Use `update-schema` for explicit schema changes,
    and plan migrations for incompatible changes on populated attributes.
12. **How strict should writes be?** Use `:validate-data?` and
    `:closed-schema?` when transaction input should be checked against the
    declared schema.
13. **Can it be absent?** Represent absence by omitting or retracting datoms,
    not by storing `nil`, `null`, or sentinel strings.

By thoughtfully applying these properties, you create a schema that is both
flexible enough for rapid development and robust enough for complex,
high-performance applications.


## References

[1] Peter Pin-Shan Chen, "The Entity-Relationship Model: Toward a Unified View
of Data," *ACM Transactions on Database Systems* 1(1):9-36, 1976. DOI:
<https://doi.org/10.1145/320434.320440>.

[2] E. F. Codd, "Further Normalization of the Data Base Relational Model,"
IBM Research Report RJ909, August 31, 1971. Republished in Randall J. Rustin,
ed., *Data Base Systems: Courant Computer Science Symposia Series 6*,
Prentice-Hall, 1972.
