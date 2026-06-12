---
title: "Schema Design and Attribute Semantics"
chapter: 11
part: "III — Modeling Across Paradigms"
---

# Chapter 11: Schema Design and Attribute Semantics

While Chapter 5 introduced the mechanics of attributes and namespaces, this
chapter dives into the *art* of schema design. In a multi-paradigm database like
Datalevin, your schema is the blueprint for how the query engine, search
indexes, and storage layer interact.

A well-designed schema in Datalevin enables efficient joins, powerful graph
traversals, full-text search, embedding search, vector search, and path-indexed
documents.

The Java, Python, and JavaScript snippets in this chapter assume an open
connection named `conn`. Schema snippets show the shape to pass when opening a
connection or updating a schema.

For a compact reference of every Datalog schema property and its accepted
values, see Appendix A, "Datalog Schema Reference."

---

## 1. The Power of Identity: `:db.unique/identity`

One of the most important decisions in schema design is how you identify your
entities. While Datalevin provides internal 64-bit integer IDs, you often need
to refer to entities using natural keys from your domain (like an email, a SKU,
or a URL slug).

### 1.1 Lookup Refs

When an attribute is marked as `:db.unique/identity`, it becomes a **Lookup
Ref**. This allows you to refer to an entity by its natural key in any part of
the API, transactions, queries, or `d/pull`, without knowing its internal integer
ID.

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

### 1.3 System-Wide Identifiers: `:db/ident`

While `:db.unique/identity` is used for attributes that uniquely identify a
domain entity (like a user's email), **`:db/ident`** is a built-in attribute
used to assign a globally unique keyword to an entity.

This is the standard way to represent **enums** or system-wide constants. Once
an entity has a `:db/ident`, you can use that keyword anywhere you would use an
entity ID or a lookup ref.

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

The advantage of using `:db/ident` over raw strings or keywords is that the enum
itself is a full-fledged entity. You can attach additional metadata to it (like
`:order.status/label "Shipped"`) without changing your data.

---

## 2. Modeling Relationships: References and Cardinality

In Datalevin, relationships are first-class citizens. They are defined using the
`:db.type/ref` value type and the `:db/cardinality` property. **Crucially, the
value of a reference attribute is always the entity ID (a 64-bit integer) of the
target entity.** In transaction data, you can still use tempids, lookup refs, or
`:db/ident` keywords as convenient inputs; Datalevin resolves them to entity IDs
when it writes the datoms.

### 2.1 One-to-One and One-to-Many

- **One-to-One**: Use `:db.type/ref` with `:db.cardinality/one`.
  - Example: A `User` has one `Profile`.
- **One-to-Many**: Usually put a cardinality-one ref on the many side.
  - Example: A `Comment` has one `Post` through `:comment/post`.

The one-to-many example is worth stating carefully: you do not need a
`:post/comments` cardinality-many attribute just to find a post's comments.
Store `:comment/post` on each comment, then query or pull the reverse direction
when you need the collection. The broader performance rationale for normalized
relationship facts is covered in the many-to-many discussion below.

### 2.2 Many-to-Many: Cardinality vs. Join Entities

For many-to-many relationships, you naturally have two main choices to model
them.

1.  **`:db.cardinality/many`**: You can add an attribute with
    `:db.cardinality/many` to one or both entities. This is highly convenient
    and results in a "cleaner" data structure when using `d/pull`.
2.  **Join Entities**: You can create a third entity that "joins" the other two,
    similar to a join table in SQL.

**Performance Tip: Prefer Normalized Relationship Facts.**

While `:db.cardinality/many` is convenient, Datalevin is highly optimized for
**normalized data**: many small relationship facts are usually better than one
very large collection-valued fact. For one-to-many relationships, this often
means placing a cardinality-one reference on the many side. For many-to-many
relationships, it often means creating a join entity.

This shape gives the query optimizer more granular facts to count, join, and
filter. If you expect a single entity to have thousands of references, such as a
"Public" group with millions of members, a join entity is the better default
for performance. It is also the right model when the relationship itself has
attributes, such as role assignment time, quantity, rank, validity interval, or
source system.

```clojure
;; Instead of many references in one entity:
{:user/id "u-1" :user/roles [:role/admin :role/editor]}

;; Use a join entity for better performance:
{:role-assignment/user [:user/id "u-1"] :role-assignment/role :role/admin}
{:role-assignment/user [:user/id "u-1"] :role-assignment/role :role/editor}
```

### 2.3 Reference Integrity

Because Datalevin is "schema-on-write" and flexible, it does not enforce
traditional "foreign key" constraints by default. If you delete an entity that
is referred to by others, the references to its ID will remain (pointing to a
non-existent entity).

To ensure a clean removal of an entity and its associated facts, it is highly
recommended to use the **`[:db/retractEntity <eid>]`** transaction function.
This operation will:

1.  Retract all facts where the entity is the **subject** (E).
2.  Retract all facts where the entity is the **value** (V) for any
    `:db.type/ref` attribute.
3.  Recursively retract any entities marked as **components** of the retracted
    entity.

By using `:db/retractEntity`, you ensure that no dangling `:db.type/ref`
references point to a non-existent entity ID, effectively maintaining reference
integrity for declared references.

---

## 3. Attribute Semantics: Beyond Simple Types

Datalevin allows you to attach additional semantic meaning to your attributes,
which changes how the database processes them.

### 3.1 Full-Text Search (`:db/fulltext`)

Setting `:db/fulltext true` tells Datalevin to maintain a specialized full-text
index for that attribute, indexing values as text. This enables the
`(fulltext ...)` predicate in Datalog (see Chapter 16).

### 3.2 Embedding Search (`:db/embedding`)

Setting `:db/embedding true` on a `:db.type/string` attribute tells Datalevin to
compute text embeddings and maintain an embedding similarity index. Query it
with `embedding-neighbors` using query text, not a vector (see Chapter 17).
Embedding indexing is synchronous by default, but embedding domains can opt into
`:indexing-mode :async` when provider calls are expensive.

### 3.3 Vector Values (`:db.type/vec`)

Use `:db.type/vec` when your application supplies vectors directly. Query these
attributes with `vec-neighbors`. Vector dimensions and metric settings belong in
store options such as `:vector-opts` or `:vector-domains`, not in each
individual datom.

### 3.4 Indexed Documents (`:db.type/idoc`)

Use `:db.type/idoc` for nested maps that should be stored as one value but
queried by path with `idoc-match`. This is useful for flexible metadata, JSON
import, and Markdown-derived structures.

### 3.5 Component Attributes (`:db/isComponent`)

When a reference attribute is marked as `:db/isComponent true`, it signals a
"parent-child" relationship.

- **Recursive Pull**: A wildcard `d/pull` on the parent will automatically
  include all attributes of the child component.
- **Cascading Deletes**: When the parent entity is deleted (via the
  **`:db/retractEntity`** transaction function), the child component entities
  are also automatically deleted.

This is ideal for modeling "owned" data, like line items in an invoice or
segments of a document.

---

## 4. Best Practices: Designing for Evolution

Datalevin's flexibility requires some care in schema maintenance. Here are best
practices to keep your schema maintainable.

### 4.1 Namespace Everything

Always use namespaces for your attributes. A common pattern is
`[domain]/[property]`, such as `:account/balance` or `:sensor/reading`. This
prevents collisions if you later integrate third-party data or modularize your
application.

### 4.2 Prefer Flat Entities

While `:db/isComponent` is useful for true ownership, don't over-nest your data.
Datalevin excels at flat, normalized facts. The query engine is designed to join
flat facts efficiently, so you don't need to "pre-join" data into complex
documents as you might in a document store like MongoDB.

### 4.3 Modeling Enums with `:db/ident`

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

### 4.4 Evolve Schema Explicitly

Datalevin uses schema-on-write, but schema is still operational state. Initial
schema is passed when opening a connection; later changes go through
`update-schema` on the open connection. Schema changes are not transacted as
ordinary datoms.

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

### 4.5 Use Schema Validation and Coercion Deliberately

Datalevin schema also participates in transaction preparation. Declared
`:db/valueType` properties tell Datalevin how to encode values, and store
options decide how strictly the transaction input is checked before those
values are written.

Two options are especially important:

- **`:validate-data? true`** checks transaction values against declared
  `:db/valueType` before writing. The default is `false`.
- **`:closed-schema? true`** rejects transactions that mention attributes not
  already present in the schema. The default is `false`, which preserves
  schema-on-write behavior.

One validation rule is always important: Datalevin does **not** store `nil`
values. In JavaScript and Python client code, this also means application-level
`null` or `None` must not be used as a database value. A missing value is
represented by the absence of a datom, not by a stored null marker.

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

Schema validation is intentionally structural. It can enforce declared value
types, uniqueness semantics, cardinality behavior, closed-schema writes, tuple
shape, and specialized parsers such as `:db.type/idoc`. It does not by itself
enforce arbitrary business rules such as "every user must have an email", "age
must be positive", or "an order total must equal the sum of its line items."
Keep those checks in transaction construction, application validation, import
pipelines, or transaction functions. If a value becomes unknown or inapplicable,
retract the existing datom or omit the attribute in new transaction data; do not
replace it with `nil`.

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

---

## 5. Summary: The Schema Checklist

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
5.  **Is it many-valued?** Use `:db.cardinality/many` for sets of values.
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
