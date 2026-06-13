---
title: "Relational Modeling Patterns"
chapter: 12
part: "III — Modeling Across Paradigms"
---

# Chapter 12: Relational Modeling Patterns

Because Datalevin prefers normalized data, industry-standard
**Entity-Relationship (ER) modeling** [1] is the most effective way to
design your database.

While SQL databases require "Object-Relational Mapping" (ORM) to bridge the gap
between your mental model and rigid tables, Datalevin’s fact-based model maps
almost 1:1 to how we reason about the world. This chapter explores how to apply
classic relational modeling patterns to a Datalog-powered triplestore.

---

## 1. The Grammar of Data: Nouns, Verbs, and Adjectives

When modeling a domain, think of your schema as a language.

### 1.1 Nouns (Entities)

Nouns are the "things" in your system: the **Entities**. In Datalevin, we
represent these using **Namespaced Keywords**.

- **Rule**: Use **singular nouns** for namespaces.
- **Example**: Use `:user/name`, not `:users/name`. Use `:product/price`, not
  `:products/price`.

Singular namespacing makes Datalog queries feel like natural sentences:
`[?e :user/name ?n]` reads as "the entity `?e` has a user name `?n`."

### 1.2 Adjectives (Attributes)

Adjectives are the properties that describe a noun. These are your standard
value-type attributes like strings, longs, and booleans.

- **Example**: `:product/color "Red"`, `:order/status :status/pending`.

### 1.3 Verbs (Relationships)

Verbs describe how nouns interact. In Datalevin, verbs are modeled using
`:db.type/ref`.

- **Example**: `:user/follows`, `:order/items`.

---

## 2. Normalization: The Path to Performance

In a document-oriented database, you are often taught to denormalize—to
"pre-join" data into nested documents. In Datalevin, this is an anti-pattern.
**Normalize by default.**

### 2.1 The "Join Entity" Pattern

As discussed in Chapter 11, for many-to-many relationships, it is often better
to use a **Join Entity** than a `:db.cardinality/many` attribute.

In ER terms, this is an **Associative Entity**. If you have `Users` and
`Groups`, instead of a list of group IDs on the user, create a `Membership`
entity.

```clojure
;; Associative Entity: Membership
{:membership/user  101 ; ref to User
 :membership/group 202 ; ref to Group
 :membership/role  :role/admin}
```

This approach allows you to:

1.  **Attach Metadata to the Relationship**: You can record *when* a user joined
    a group, or *who* invited them.
2.  **Optimize Query Execution**: Datalevin's query optimizer can jump to a
    specific membership record faster than scanning a large list of IDs inside a
    single user entity.

---

## 3. ER Design Decisions in Datalevin

ER modeling is useful because it forces you to make a few decisions explicitly
before you write schema. Datalevin does not require tables, but the same
questions still produce better facts.

### 3.1 Choose Stable Keys

Every important entity type should have a stable domain identifier when the
domain has one. Use Datalevin's internal entity IDs for storage and joins, but
put natural identifiers in unique attributes so transactions, imports, and APIs
can use lookup refs.

```clojure
{:user/id      {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
 :product/sku  {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
 :invoice/uuid {:db/valueType :db.type/uuid
                :db/unique    :db.unique/identity}}
```

Prefer stable identifiers such as account IDs, SKUs, slugs, or UUIDs. Avoid
using mutable labels such as display names as identities unless the domain
treats them as immutable.

### 3.2 Record Cardinality and Participation

ER diagrams distinguish one-to-one, one-to-many, and many-to-many
relationships. Datalevin represents these with reference attributes and
cardinality, but it does not enforce all ER participation constraints for you.

- **One-to-many**: Put a cardinality-one ref on the "many" side, such as
  `:order/user` or `:comment/post`.
- **Many-to-many**: Use an associative entity when the relationship is queried
  directly, may grow large, or has attributes of its own.
- **Optional participation**: Omit the datom when the relationship is unknown or
  not applicable; do not invent sentinel values like `"N/A"`.
- **Required participation**: Enforce it in transaction construction,
  application validation, or import checks. The schema documents the model, but
  a missing datom is still possible unless your write path rejects it.

For the performance rationale behind many-side refs and join entities, see
Chapter 11, Section 2.2.

This distinction matters in Datalevin because absence is a normal fact shape.
A query for users without an active subscription is a query over missing
relationship facts. `nil`/ `null` is not a valid value in Datalevin.

### 3.3 Promote Relationships That Have Attributes

In ER modeling, a relationship can have attributes. In Datalevin, that is your
signal to make the relationship an entity.

```clojure
;; A thin model: the relationship has nowhere to put its own facts.
{:order/products [[:product/sku "SKU-1"] [:product/sku "SKU-2"]]}

;; A stronger model: each line item is a relationship entity.
{:line-item/order    [:order/id "ord-1001"]
 :line-item/product  [:product/sku "SKU-1"]
 :line-item/quantity 2
 :line-item/price    1999}
```

This handles classic ER cases such as order line items, memberships,
assignments, reservations, approvals, and permissions. It also handles ternary
relationships cleanly: if a supplier sells a product to a store under a
contract, model the contract as an entity with three refs, not as three separate
binary links that lose the original meaning.

### 3.4 Model Weak Entities and Ownership

ER weak entities depend on an owner for identity or lifecycle. In Datalevin,
model them as ordinary entities with a reference to the owner, and use
`:db/isComponent` only when the child is truly owned by the parent and should be
deleted with it.

Good component candidates include invoice line items, document sections, or
profile settings that have no useful life outside the parent. Poor component
candidates include users, products, organizations, or tags that can be shared
or referenced independently.

### 3.5 Represent Types Without Inheritance Tables

ER specialization maps naturally to facts. For a small closed set of subtypes,
use an enum-style ref or `:db/ident` value:

```clojure
{:account/id   "acct-1"
 :account/type :account.type/business
 :account/name "Acme Corp"}
```

Use shared attributes in the common namespace and subtype-specific attributes
only where they apply, such as `:business-account/tax-id` or
`:personal-account/birth-date`. This keeps the model queryable without forcing
an inheritance-table pattern into a fact database.

### 3.6 Be Deliberate About Derived Facts

ER analysis often identifies derived attributes, such as order totals, account
balances, or comment counts. Prefer deriving these with Datalog when they are
cheap and needed inside a transactionally current view. Store them only when
they are expensive, part of an audit record, or must preserve a historical
snapshot.

When you do store a derived fact, document the source of truth and update rule
with `:db/doc`. Future readers should be able to tell whether `:order/total` is
authoritative, cached, or a historical snapshot.

---

## 4. Documenting the Schema: `:db/doc`

A schema is not just for the database engine; it is for the developers who will
maintain the system for years to come. Every attribute in your schema should be
documented.

Datalevin supports a **`:db/doc`** property in the schema map. Use it to explain
the purpose and constraints of an attribute.

<div class="multi-lang">

```clojure
(def schema
  {:user/email {:db/valueType :db.type/string
                :db/unique    :db.unique/identity
                :db/doc       "The primary unique identifier for a user account."}
   :order/total {:db/valueType :db.type/bigdec
                 :db/doc       "The total price of the order in USD, including tax."}})
```

```java
Schema schema = Datalevin.schema()
    .attr("user/email",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .unique(Schema.Unique.IDENTITY)
              .doc("The primary unique identifier for a user account."))
    .attr("order/total",
          Schema.attribute()
              .valueType(Schema.ValueType.BIGDEC)
              .doc("The total price of the order in USD, including tax."));
```

```python
schema = {
    ":user/email": {":db/valueType": ":db.type/string",
                    ":db/unique": ":db.unique/identity",
                    ":db/doc": "The primary unique identifier for a user account."},
    ":order/total": {":db/valueType": ":db.type/bigdec",
                     ":db/doc": "The total price of the order in USD, including tax."}}
```

```javascript
const schema = {
    ":user/email": {":db/valueType": ":db.type/string",
                    ":db/unique": ":db.unique/identity",
                    ":db/doc": "The primary unique identifier for a user account."},
    ":order/total": {":db/valueType": ":db.type/bigdec",
                     ":db/doc": "The total price of the order in USD, including tax."}};
```

</div>

Think of `:db/doc` as "comments that live in the database." They can be queried
and used to generate documentation automatically.

---

## 5. From Tables to Facts: Migrating SQL Models

Migrating from SQL to Datalevin is not a rejection of relational algebra. It is
a rejection of treating SQL text and table-shaped containers as the only
reasonable interface to relational data. Datalevin keeps the relational idea
that facts can be joined by shared values, but represents the data as explicit
datoms that can be joined, traversed, pulled, counted, sampled, and indexed in
several ways.

That distinction matters. If a SQL database adds JSON, text search, vector
indexes, or graph-like extensions, it may reduce the number of services you run,
but the application is still written against a table-first language and a
planner that must estimate joins over row or column containers. Datalevin starts
from facts and uses Datalog as the surface language, so migration is not merely
a change of syntax; it is a change in what the database exposes as its basic
unit of reasoning.

### 5.1 Translate the Vocabulary

The familiar SQL concepts still have Datalevin equivalents, but the boundaries
move from tables to attributes and facts:

| SQL Concept | Datalevin Equivalent | Migration Note |
| :--- | :--- | :--- |
| **Table** | **Namespace** such as `:order/` | A namespace groups attributes by domain meaning; it is not a physical table. |
| **Row** | **Entity ID** | An entity is the set of facts that share the same entity ID. |
| **Column** | **Attribute** | Attributes are schema objects with their own value type, cardinality, uniqueness, and index settings. |
| **Primary Key** | **Unique identity attribute** | Use stable domain IDs with `:db.unique/identity`; keep Datalevin entity IDs internal. |
| **Foreign Key** | **Ref attribute** | A `:db.type/ref` stores another entity ID and joins naturally in Datalog. |
| **Join Table** | **Associative entity** | Model the join as its own entity when the relationship is large, queried directly, or has attributes. |
| **NULL** | **No datom** | Datalevin does not store `nil`/`null`; absence means the fact is not present. |

### 5.2 Decompose Rows into Facts

In SQL, a row is the unit that receives a full set of column values:

```sql
INSERT INTO orders (order_id, customer_id, status, total_cents)
VALUES ('ord-1001', 'cust-42', 'paid', 4299);
```

In Datalevin, the transaction can still be written as an entity map, but the
stored representation is separate facts:

```clojure
{:order/id          "ord-1001"
 :order/customer    [:customer/id "cust-42"]
 :order/status      :order.status/paid
 :order/total-cents 4299}
```

Conceptually, after lookup refs are resolved, the database contains datoms like:

```clojure
[2001 :order/id "ord-1001"]
[2001 :order/customer 1001]
[2001 :order/status :order.status/paid]
[2001 :order/total-cents 4299]
```

This is why sparse and evolving domains work well in Datalevin. Optional fields
do not require nullable columns. If a discount code, shipment, or cancellation
reason is unknown, the corresponding datom is absent.

### 5.3 Recast Joins as Shared Variables

The most visible change for SQL developers is query syntax. SQL names tables and
join conditions explicitly:

```sql
SELECT o.order_id, c.email
FROM orders o
JOIN customers c ON o.customer_id = c.customer_id
WHERE o.status = 'paid';
```

Datalog describes the facts that must be true. The join happens because `?c` is
shared by the order and customer patterns:

```clojure
[:find ?order-id ?email
 :where [?o :order/id ?order-id]
        [?o :order/status :order.status/paid]
        [?o :order/customer ?c]
        [?c :customer/email ?email]]
```

This is the same relational idea expressed declaratively. There is no `JOIN`
keyword because unification over shared variables is the join.

Aggregations follow the same principle. SQL uses `GROUP BY`; Datalog groups by
the non-aggregate variables in `:find`:

```clojure
[:find ?status (count ?o)
 :where [?o :order/status ?status]]
```

### 5.4 Model Foreign Keys and Join Tables Deliberately

One-to-many relationships map directly to a reference on the "many" side, such
as `:order/customer` or `:comment/post`.

Many-to-many relationships require a choice:

1.  Use a cardinality-many ref when the set is small, directly owned by the
    entity, and has no attributes of its own.
2.  Use an associative entity when the relationship needs metadata, lifecycle,
    uniqueness, or direct queries.

For SQL migrations, join tables usually become associative entities. A
`user_groups` table with `user_id`, `group_id`, `role`, and `joined_at` is not
just a link; it is a membership entity.

### 5.5 Use a Practical Migration Checklist

For an existing SQL application, migrate in this order:

1.  Map each table to a namespace and each column to an attribute.
2.  Preserve stable primary keys as `:db.unique/identity` attributes.
3.  Convert foreign keys to `:db.type/ref` attributes that point to lookup refs.
4.  Convert join tables with payload columns into associative entities.
5.  Decide which nullable columns represent absent facts, optional refs, or
    values that should be modeled differently.
6.  Add `:db/doc` to attributes whose meaning came from SQL constraints,
    comments, or application conventions.
7.  Validate imports by comparing row counts, relationship counts, uniqueness
    constraints, and representative business queries.

### 5.6 Example: A Normalized E-commerce Schema

<div class="multi-lang">

```clojure
(def ecommerce-schema
  {;; Noun: Product
   :product/sku   {:db/unique :db.unique/identity :db/valueType :db.type/string}
   :product/title {:db/fulltext true :db/valueType :db.type/string}
   :product/price {:db/valueType :db.type/long}

   ;; Noun: Customer
   :customer/id    {:db/unique :db.unique/identity :db/valueType :db.type/string}
   :customer/email {:db/unique :db.unique/identity :db/valueType :db.type/string}

   ;; Noun: Order
   :order/id      {:db/unique :db.unique/identity :db/valueType :db.type/string}
   :order/customer {:db/valueType :db.type/ref}

   ;; Verb/Associative Entity: Line Item (joins Order and Product)
   :line-item/order    {:db/valueType :db.type/ref}
   :line-item/product  {:db/valueType :db.type/ref}
   :line-item/quantity {:db/valueType :db.type/long}})
```

```java
Schema ecommerceSchema = Datalevin.schema()
    // Noun: Product
    .attr("product/sku",
          Schema.attribute()
              .unique(Schema.Unique.IDENTITY)
              .valueType(Schema.ValueType.STRING))
    .attr("product/title",
          Schema.attribute()
              .fulltext(true)
              .valueType(Schema.ValueType.STRING))
    .attr("product/price",
          Schema.attribute()
              .valueType(Schema.ValueType.LONG))
    // Noun: Customer
    .attr("customer/id",
          Schema.attribute()
              .unique(Schema.Unique.IDENTITY)
              .valueType(Schema.ValueType.STRING))
    .attr("customer/email",
          Schema.attribute()
              .unique(Schema.Unique.IDENTITY)
              .valueType(Schema.ValueType.STRING))
    // Noun: Order
    .attr("order/id",
          Schema.attribute()
              .unique(Schema.Unique.IDENTITY)
              .valueType(Schema.ValueType.STRING))
    .attr("order/customer",
          Schema.attribute()
              .valueType(Schema.ValueType.REF))
    // Verb/Associative Entity: Line Item (joins Order and Product)
    .attr("line-item/order",
          Schema.attribute()
              .valueType(Schema.ValueType.REF))
    .attr("line-item/product",
          Schema.attribute()
              .valueType(Schema.ValueType.REF))
    .attr("line-item/quantity",
          Schema.attribute()
              .valueType(Schema.ValueType.LONG));
```

```python
ecommerce_schema = {
    # Noun: Product
    ":product/sku": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    ":product/title": {":db/fulltext": True, ":db/valueType": ":db.type/string"},
    ":product/price": {":db/valueType": ":db.type/long"},
    # Noun: Customer
    ":customer/id": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    ":customer/email": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    # Noun: Order
    ":order/id": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    ":order/customer": {":db/valueType": ":db.type/ref"},
    # Verb/Associative Entity: Line Item (joins Order and Product)
    ":line-item/order": {":db/valueType": ":db.type/ref"},
    ":line-item/product": {":db/valueType": ":db.type/ref"},
    ":line-item/quantity": {":db/valueType": ":db.type/long"}}
```

```javascript
const ecommerceSchema = {
    // Noun: Product
    ":product/sku": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    ":product/title": {":db/fulltext": true, ":db/valueType": ":db.type/string"},
    ":product/price": {":db/valueType": ":db.type/long"},
    // Noun: Customer
    ":customer/id": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    ":customer/email": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    // Noun: Order
    ":order/id": {":db/unique": ":db.unique/identity", ":db/valueType": ":db.type/string"},
    ":order/customer": {":db/valueType": ":db.type/ref"},
    // Verb/Associative Entity: Line Item (joins Order and Product)
    ":line-item/order": {":db/valueType": ":db.type/ref"},
    ":line-item/product": {":db/valueType": ":db.type/ref"},
    ":line-item/quantity": {":db/valueType": ":db.type/long"}};
```

</div>

---

## 6. Summary: Relational Best Practices

1.  **Think in singular namespaces**: `:user/email`, not `:users/emails`.
2.  **Choose stable keys**: Use `:db.unique/identity` for domain identifiers you
    will use in imports, APIs, and lookup refs.
3.  **Normalize relationships**: Prefer many-side refs and join entities for
    large or directly queried relationships; see Chapter 11, Section 2.2.
4.  **Model ownership carefully**: Use `:db/isComponent` for true lifecycle
    ownership, not just for convenient nesting.
5.  **Represent subtypes as facts**: Use enum entities or subtype-specific
    namespaces rather than inheritance-table patterns.
6.  **Document as you go**: Use `:db/doc` to encode the "why" behind every
    attribute, especially derived or denormalized facts.
7.  **Migrate SQL incrementally**: Preserve stable keys, convert foreign keys to
    refs, and validate migrated facts with the business queries the application
    already depends on.

By applying these time-tested ER principles, you ensure your Datalevin database
remains clean, performant, and understandable as your domain complexity grows.

## References

[1] Peter P. Chen, ["The Entity-Relationship Model - Toward a Unified
View of Data"](https://doi.org/10.1145/320434.320440), *ACM Transactions on
Database Systems* 1, no. 1, 1976, pp. 9-36.
