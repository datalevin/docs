---
title: "Relational Modeling"
chapter: 12
part: "III — Modeling Across Paradigms"
---

# Chapter 12: Relational Modeling

Because Datalevin prefers normalized data, industry-standard
**Entity-Relationship (ER) modeling** [1] is the most effective way to
design your database.

While SQL databases require "Object-Relational Mapping" (ORM) to bridge the gap
between your mental model and rigid tables, Datalevin's fact-based model maps
almost 1:1 to how we reason about the world. This chapter explores how to apply
classic relational modeling patterns to a Datalog-powered triplestore.


## 1. The Grammar of Data: Nouns, Verbs, and Adjectives

When modeling a domain, think of your schema as a language.

### 1.1 Nouns (Entities)

Nouns are the "things" in your system: users, products, orders, invoices,
courses, enrollments, and so on. In Datalevin, an entity is not represented by
a table row, but by a set of facts that share one internal entity id. You usually
describe that entity with a set of namespaced attributes such as `:user/name`,
`:user/gender`, or `:user/email`.

- **Rule**: Use **singular nouns** for attribute namespaces.
- **Example**: Use `:user/name`, not `:users/name`. Use `:product/price`, not
  `:products/price`.

The namespace suggests which domain concept the attribute primarily describes.
It is a modeling **convention**, not a table declaration or a class tag. If an
entity has `:user/email` and `:user/name`, readers can recognize it as a user;
Datalevin still stores ordinary datoms.

Singular namespacing makes Datalog queries feel like natural sentences:
`[?e :user/name ?n]` reads as "the entity `?e` has a user name `?n`."

### 1.2 Adjectives (Attributes)

Adjectives are properties that describe an entity. These are the attributes
already shown above. Attributes have value types: strings, longs, booleans,
instants, keywords, and other types.

- **Example**: `:product/color "Red"`, `:order/status :status/pending`.

### 1.3 Verbs (Relationships)

Verbs describe how entities interact. In Datalevin, relationships are ordinary
attributes whose value type is `:db.type/ref`.

- **Example**: `:user/follows`, `:order/customer`, `:line-item/product`.


## 2. Normalization: The Path to Performance

In a document-oriented database, you are often taught to denormalize — to
"pre-join" data into nested documents. In Datalevin, this is an anti-pattern.
**Normalize by default.**

### 2.1 The "Join Entity" Pattern

Chapter 11 gives the Datalevin decision framework for many-to-many
relationships. In ER terms, the normalized version is an **associative entity**:
the relationship is promoted to its own entity. If you have `Users` and
`Groups`, a `Membership` entity records the user's membership in a group.

```clojure
;; Associative Entity: Membership
{:membership/user  101 ; ref to User
 :membership/group 202 ; ref to Group
 :membership/role  :role/admin}
```

The ER point is that membership is not just a pointer; it can have its own role,
join time, inviter, validity interval, or audit facts. For the performance trade
offs between small `:db.cardinality/many` refs and join entities, use Chapter
11, Section 2.2.


## 3. ER Design Decisions in Datalevin

ER modeling is useful because it forces you to make a few decisions explicitly
before you write schema. Datalevin does not require tables, but the same
questions still produce better facts.

### 3.1 Choose Stable Keys

Every important entity type should have a stable domain identifier when the
domain has one. Use Datalevin's internal entity ids for storage and joins, but
put natural identifiers in unique attributes so transactions, imports, and APIs
can use lookup refs.

<div class="multi-lang">

```clojure
{:user/id      {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
 :product/sku  {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
 :invoice/uuid {:db/valueType :db.type/uuid
                :db/unique    :db.unique/identity}}
```

```java
Schema identitySchema = Datalevin.schema()
    .attr("user/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("product/sku", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("invoice/uuid", Schema.attribute()
        .valueType(Schema.ValueType.UUID)
        .unique(Schema.Unique.IDENTITY));
```

```python
identity_schema = {
    ":user/id": {":db/valueType": ":db.type/string",
                 ":db/unique": ":db.unique/identity"},
    ":product/sku": {":db/valueType": ":db.type/string",
                     ":db/unique": ":db.unique/identity"},
    ":invoice/uuid": {":db/valueType": ":db.type/uuid",
                      ":db/unique": ":db.unique/identity"}}
```

```javascript
const identitySchema = {
  ":user/id": {":db/valueType": ":db.type/string",
               ":db/unique": ":db.unique/identity"},
  ":product/sku": {":db/valueType": ":db.type/string",
                   ":db/unique": ":db.unique/identity"},
  ":invoice/uuid": {":db/valueType": ":db.type/uuid",
                    ":db/unique": ":db.unique/identity"}
};
```

</div>

Prefer stable identifiers such as account IDs, SKUs, slugs, or UUIDs. Avoid
using mutable labels such as display names as identities unless the domain
treats them as immutable.

### 3.2 Record Cardinality and Participation

ER diagrams distinguish one-to-one, one-to-many, and many-to-many
relationships. Datalevin represents these with reference attributes and
cardinality, but it does not enforce all ER participation constraints for you.

- **One-to-many**: Put a cardinality-one ref on the many side, such as
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
relationship facts. `nil`/`null` is not a valid value in Datalevin.

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


## 4. Choosing Between Similar Shapes

Several Datalevin features can look like solutions to the same modeling
problem. The right choice depends on whether you are modeling a relationship,
an identity, or a value.

| Modeling problem | Prefer | Why |
| :--- | :--- | :--- |
| One entity points to one parent | Cardinality-one `:db.type/ref` on the child | Normalized, easy to join, and easy to reverse-pull. |
| A small owned set of refs or values | `:db.cardinality/many` | Convenient when the set is small and the members have no facts of their own; each member is still a separate datom. |
| A relationship has attributes, lifecycle, uniqueness, or direct queries | Relationship entity | The relationship itself is a thing the domain talks about. |
| A combination of attributes identifies an entity | Composite tuple attribute with `:db/tupleAttrs` | Datalevin maintains a derived lookup/index entry from ordinary attributes. |
| A small vector-like value whose elements all have one type | Stored homogeneous tuple with `:db/tupleType` | The tuple is one value, such as a coordinate or numeric interval. |
| A small record-like value with fixed positions of different types | Stored heterogeneous tuple with `:db/tupleTypes` | The tuple is one value with typed positions, such as `[amount currency]`. |
| Arbitrary nested imported data | `:db.type/idoc` or query-time tuples | Use document modeling when the structure is open-ended or path-oriented. |

### 4.1 Composite Tuple Attributes Are Access Paths

When users ask for a "custom index" in a Datalog database, they often mean:
"I want to find or upsert an entity by several fields together." That is the
job of `:db/tupleAttrs`.

<div class="multi-lang">

```clojure
{:price/vendor     {:db/valueType :db.type/string}
 :price/sku        {:db/valueType :db.type/string}
 :price/region     {:db/valueType :db.type/string}
 :price/cents      {:db/valueType :db.type/long}
 :price/vendor+sku+region
 {:db/tupleAttrs [:price/vendor :price/sku :price/region]
  :db/unique     :db.unique/identity}}
```

```java
Schema priceSchema = Datalevin.schema()
    .attr("price/vendor", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("price/sku", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("price/region", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("price/cents", Schema.attribute()
        .valueType(Schema.ValueType.LONG))
    .attr("price/vendor+sku+region", Schema.attribute()
        .tupleAttrs("price/vendor", "price/sku", "price/region")
        .unique(Schema.Unique.IDENTITY));
```

```python
price_schema = {
    ":price/vendor": {":db/valueType": ":db.type/string"},
    ":price/sku": {":db/valueType": ":db.type/string"},
    ":price/region": {":db/valueType": ":db.type/string"},
    ":price/cents": {":db/valueType": ":db.type/long"},
    ":price/vendor+sku+region": {
        ":db/tupleAttrs": [":price/vendor", ":price/sku", ":price/region"],
        ":db/unique": ":db.unique/identity"}}
```

```javascript
const priceSchema = {
  ":price/vendor": {":db/valueType": ":db.type/string"},
  ":price/sku": {":db/valueType": ":db.type/string"},
  ":price/region": {":db/valueType": ":db.type/string"},
  ":price/cents": {":db/valueType": ":db.type/long"},
  ":price/vendor+sku+region": {
    ":db/tupleAttrs": [":price/vendor", ":price/sku", ":price/region"],
    ":db/unique": ":db.unique/identity"}
};
```

</div>

The application writes the component attributes. Datalevin derives the tuple
value and keeps it consistent when components change:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:price/vendor "acme"
    :price/sku    "SKU-42"
    :price/region "us-west"
    :price/cents  1999}])

(d/pull (d/db conn)
        '[:price/cents]
        [:price/vendor+sku+region ["acme" "SKU-42" "us-west"]])
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("price/vendor", "acme")
        .put("price/sku", "SKU-42")
        .put("price/region", "us-west")
        .put("price/cents", 1999L)));

Map<?, ?> price = conn.pull(
    "[:price/cents]",
    List.of("price/vendor+sku+region",
            List.of("acme", "SKU-42", "us-west")));
```

```python
conn.transact([{":price/vendor": "acme",
                ":price/sku": "SKU-42",
                ":price/region": "us-west",
                ":price/cents": 1999}])

price = conn.pull(
    "[:price/cents]",
    [":price/vendor+sku+region", ["acme", "SKU-42", "us-west"]])
```

```javascript
await conn.transact([{":price/vendor": "acme",
                      ":price/sku": "SKU-42",
                      ":price/region": "us-west",
                      ":price/cents": 1999}]);

const price = await conn.pull(
  "[:price/cents]",
  [":price/vendor+sku+region", ["acme", "SKU-42", "us-west"]]);
```

</div>

This is not a stored application tuple. It is a derived composite access path
over separate facts. Use it when the separate facts are meaningful on their
own and the combination needs identity or lookup behavior. Component attributes
can be scalar attributes or cardinality-one refs; Datalevin derives the tuple
from the actual component values.

### 4.2 Stored Tuple Values Are Values

Stored tuple values are different. Use them when the tuple itself is a compact
value object and its parts are not usually joined independently.

<div class="multi-lang">

```clojure
{:place/latlon {:db/valueType :db.type/tuple
                :db/tupleType :db.type/double}

 :reading/value+unit
 {:db/valueType  :db.type/tuple
  :db/tupleTypes [:db.type/double :db.type/keyword]}}
```

```java
Schema tupleSchema = Datalevin.schema()
    .attr("place/latlon", Schema.attribute()
        .valueType(Schema.ValueType.TUPLE)
        .tupleType(Schema.ValueType.DOUBLE))
    .attr("reading/value+unit", Schema.attribute()
        .valueType(Schema.ValueType.TUPLE)
        .tupleTypes(Schema.ValueType.DOUBLE, Schema.ValueType.KEYWORD));
```

```python
tuple_schema = {
    ":place/latlon": {":db/valueType": ":db.type/tuple",
                      ":db/tupleType": ":db.type/double"},
    ":reading/value+unit": {":db/valueType": ":db.type/tuple",
                            ":db/tupleTypes": [":db.type/double",
                                               ":db.type/keyword"]}}
```

```javascript
const tupleSchema = {
  ":place/latlon": {":db/valueType": ":db.type/tuple",
                    ":db/tupleType": ":db.type/double"},
  ":reading/value+unit": {":db/valueType": ":db.type/tuple",
                          ":db/tupleTypes": [":db.type/double",
                                             ":db.type/keyword"]}
};
```

</div>

`:place/latlon` is homogeneous: every element is a double. `:reading/value+unit`
is heterogeneous: the first position is a double and the second is a keyword.

If you frequently ask "which places are north of this latitude?" or "which
readings use this unit?", model those parts as ordinary attributes instead:

<div class="multi-lang">

```clojure
{:place/lat {:db/valueType :db.type/double}
 :place/lon {:db/valueType :db.type/double}

 :reading/value {:db/valueType :db.type/double}
 :reading/unit  {:db/valueType :db.type/keyword}}
```

```java
Schema separateSchema = Datalevin.schema()
    .attr("place/lat", Schema.attribute()
        .valueType(Schema.ValueType.DOUBLE))
    .attr("place/lon", Schema.attribute()
        .valueType(Schema.ValueType.DOUBLE))
    .attr("reading/value", Schema.attribute()
        .valueType(Schema.ValueType.DOUBLE))
    .attr("reading/unit", Schema.attribute()
        .valueType(Schema.ValueType.KEYWORD));
```

```python
separate_schema = {
    ":place/lat": {":db/valueType": ":db.type/double"},
    ":place/lon": {":db/valueType": ":db.type/double"},
    ":reading/value": {":db/valueType": ":db.type/double"},
    ":reading/unit": {":db/valueType": ":db.type/keyword"}}
```

```javascript
const separateSchema = {
  ":place/lat": {":db/valueType": ":db.type/double"},
  ":place/lon": {":db/valueType": ":db.type/double"},
  ":reading/value": {":db/valueType": ":db.type/double"},
  ":reading/unit": {":db/valueType": ":db.type/keyword"}
};
```

</div>

Separate attributes give the query planner direct facts and indexes for each
part. A stored tuple is best when the application normally treats the whole
vector as one value.

### 4.3 A Practical Decision Checklist

Ask these questions before reaching for a tuple:

1.  Is this a relationship between entities? Use refs, and use a relationship
    entity if the relationship has its own facts.
2.  Is this a uniqueness or lookup problem over several existing facts? Use
    `:db/tupleAttrs`.
3.  Is this one compact value whose components do not have independent domain
    meaning? Use a stored tuple.
4.  Are all stored tuple elements the same type? Use `:db/tupleType`.
5.  Do the stored tuple positions have different types and fixed meanings? Use
    `:db/tupleTypes`.
6.  Do you need to query, validate, document, or evolve the parts separately?
    Prefer separate attributes.

The short version: relationships should remain facts, composite identities
should be derived from facts, and stored tuples should be reserved for values.


## 5. A Worked ER Example: Course Enrollment

Consider a small school registration system. In an ER diagram you would likely
draw several strong entity types, one scheduled offering, and one associative
entity:

- `Student`: identified by a student id.
- `Course`: identified by a course code.
- `Term`: identified by an academic term id.
- `Instructor`: identified by an instructor id.
- `CourseOffering`: a particular section of a course in a term, taught by an
  instructor.
- `Enrollment`: the fact that a student is enrolled in a course offering.

Figure 12.1 is an ER diagram showing the relationships among the entities.

![ER model for course enrollment: Student, Course, Term, and Instructor are strong entities; CourseOffering joins Course, Term, and Instructor for a scheduled section; Enrollment joins Student to CourseOffering and carries status, grade, enrollment time, and a composite unique key over student and offering](/images/diagrams/er-course-enrollment.svg)

The important modeling move is to separate the catalog course from the thing a
student actually enrolls in. `CS101` is a course. `CS101, Fall 2026, Section
001` is a course offering. `Enrollment` is not just a many-to-many link between
student and course; it has attributes of its own: status, grade, enrollment
time, and perhaps the source system that created the record. That makes it a
proper entity in Datalevin.

Listing 12.1 shows the actual schema.

<!-- pdf-listing: Course enrollment schema with scheduled offerings -->

<div class="multi-lang">

```clojure
(def school-schema
  {:student/id   {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :student/name {:db/valueType :db.type/string}

   :course/code  {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :course/title {:db/valueType :db.type/string}

   :term/id        {:db/valueType :db.type/string
                    :db/unique    :db.unique/identity}
   :term/starts-at {:db/valueType :db.type/instant}

   :instructor/id   {:db/valueType :db.type/string
                     :db/unique    :db.unique/identity}
   :instructor/name {:db/valueType :db.type/string}

   ;; A scheduled section of a catalog course.
   :course-offering/id         {:db/valueType :db.type/string
                                :db/unique    :db.unique/identity}
   :course-offering/course     {:db/valueType :db.type/ref}
   :course-offering/term       {:db/valueType :db.type/ref}
   :course-offering/instructor {:db/valueType :db.type/ref}
   :course-offering/section    {:db/valueType :db.type/string}
   :course-offering/capacity   {:db/valueType :db.type/long}

   ;; Optional: enforce one offering per course/term/section.
   :course-offering/key        {:db/tupleAttrs [:course-offering/course
                                                :course-offering/term
                                                :course-offering/section]
                                :db/unique     :db.unique/identity}

   ;; Enrollment is the relationship between a student and an offering.
   :enrollment/student     {:db/valueType :db.type/ref}
   :enrollment/offering    {:db/valueType :db.type/ref}

   ;; Optional: enforce one enrollment per student/offering.
   :enrollment/key         {:db/tupleAttrs [:enrollment/student
                                            :enrollment/offering]
                            :db/unique     :db.unique/identity}

   :enrollment/status      {:db/valueType :db.type/keyword}
   :enrollment/grade       {:db/valueType :db.type/string}
   :enrollment/enrolled-at {:db/valueType :db.type/instant}})
```

```java
Schema schoolSchema = Datalevin.schema()
    .attr("student/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("student/name", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("course/code", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("course/title", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("term/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("term/starts-at", Schema.attribute()
        .valueType(Schema.ValueType.INSTANT))
    .attr("instructor/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("instructor/name", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("course-offering/id", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("course-offering/course", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("course-offering/term", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("course-offering/instructor", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("course-offering/section", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("course-offering/capacity", Schema.attribute()
        .valueType(Schema.ValueType.LONG))
    .attr("course-offering/key", Schema.attribute()
        .tupleAttrs("course-offering/course",
                    "course-offering/term",
                    "course-offering/section")
        .unique(Schema.Unique.IDENTITY))
    .attr("enrollment/student", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("enrollment/offering", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("enrollment/key", Schema.attribute()
        .tupleAttrs("enrollment/student", "enrollment/offering")
        .unique(Schema.Unique.IDENTITY))
    .attr("enrollment/status", Schema.attribute()
        .valueType(Schema.ValueType.KEYWORD))
    .attr("enrollment/grade", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("enrollment/enrolled-at", Schema.attribute()
        .valueType(Schema.ValueType.INSTANT));
```

```python
school_schema = {
    ":student/id": {":db/valueType": ":db.type/string",
                    ":db/unique": ":db.unique/identity"},
    ":student/name": {":db/valueType": ":db.type/string"},
    ":course/code": {":db/valueType": ":db.type/string",
                     ":db/unique": ":db.unique/identity"},
    ":course/title": {":db/valueType": ":db.type/string"},
    ":term/id": {":db/valueType": ":db.type/string",
                 ":db/unique": ":db.unique/identity"},
    ":term/starts-at": {":db/valueType": ":db.type/instant"},
    ":instructor/id": {":db/valueType": ":db.type/string",
                       ":db/unique": ":db.unique/identity"},
    ":instructor/name": {":db/valueType": ":db.type/string"},
    ":course-offering/id": {":db/valueType": ":db.type/string",
                            ":db/unique": ":db.unique/identity"},
    ":course-offering/course": {":db/valueType": ":db.type/ref"},
    ":course-offering/term": {":db/valueType": ":db.type/ref"},
    ":course-offering/instructor": {":db/valueType": ":db.type/ref"},
    ":course-offering/section": {":db/valueType": ":db.type/string"},
    ":course-offering/capacity": {":db/valueType": ":db.type/long"},
    ":course-offering/key": {
        ":db/tupleAttrs": [":course-offering/course",
                           ":course-offering/term",
                           ":course-offering/section"],
        ":db/unique": ":db.unique/identity"},
    ":enrollment/student": {":db/valueType": ":db.type/ref"},
    ":enrollment/offering": {":db/valueType": ":db.type/ref"},
    ":enrollment/key": {
        ":db/tupleAttrs": [":enrollment/student", ":enrollment/offering"],
        ":db/unique": ":db.unique/identity"},
    ":enrollment/status": {":db/valueType": ":db.type/keyword"},
    ":enrollment/grade": {":db/valueType": ":db.type/string"},
    ":enrollment/enrolled-at": {":db/valueType": ":db.type/instant"}}
```

```javascript
const schoolSchema = {
  ":student/id": {":db/valueType": ":db.type/string",
                  ":db/unique": ":db.unique/identity"},
  ":student/name": {":db/valueType": ":db.type/string"},
  ":course/code": {":db/valueType": ":db.type/string",
                   ":db/unique": ":db.unique/identity"},
  ":course/title": {":db/valueType": ":db.type/string"},
  ":term/id": {":db/valueType": ":db.type/string",
               ":db/unique": ":db.unique/identity"},
  ":term/starts-at": {":db/valueType": ":db.type/instant"},
  ":instructor/id": {":db/valueType": ":db.type/string",
                     ":db/unique": ":db.unique/identity"},
  ":instructor/name": {":db/valueType": ":db.type/string"},
  ":course-offering/id": {":db/valueType": ":db.type/string",
                          ":db/unique": ":db.unique/identity"},
  ":course-offering/course": {":db/valueType": ":db.type/ref"},
  ":course-offering/term": {":db/valueType": ":db.type/ref"},
  ":course-offering/instructor": {":db/valueType": ":db.type/ref"},
  ":course-offering/section": {":db/valueType": ":db.type/string"},
  ":course-offering/capacity": {":db/valueType": ":db.type/long"},
  ":course-offering/key": {
    ":db/tupleAttrs": [":course-offering/course",
                       ":course-offering/term",
                       ":course-offering/section"],
    ":db/unique": ":db.unique/identity"},
  ":enrollment/student": {":db/valueType": ":db.type/ref"},
  ":enrollment/offering": {":db/valueType": ":db.type/ref"},
  ":enrollment/key": {
    ":db/tupleAttrs": [":enrollment/student", ":enrollment/offering"],
    ":db/unique": ":db.unique/identity"},
  ":enrollment/status": {":db/valueType": ":db.type/keyword"},
  ":enrollment/grade": {":db/valueType": ":db.type/string"},
  ":enrollment/enrolled-at": {":db/valueType": ":db.type/instant"}
};
```

</div>

The refs are the relationship model. `?offering` points to the course, term,
and instructor entities. `?enrollment` points to the student and offering
entities. Ordinary joins recover the corresponding domain identifiers when
needed. There is no need to copy `:course/code`, `:term/id`, or
`:instructor/id` onto the enrollment just to make later queries convenient. If
you need uniqueness for either relationship, derive a composite tuple from the
refs themselves.

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:student/id "s-100" :student/name "Ada"}
   {:course/code "CS101" :course/title "Intro to Databases"}
   {:term/id "2026-fall"}
   {:instructor/id "i-10" :instructor/name "Grace Hopper"}

   {:course-offering/id         "2026-fall-CS101-001"
    :course-offering/course     [:course/code "CS101"]
    :course-offering/term       [:term/id "2026-fall"]
    :course-offering/instructor [:instructor/id "i-10"]
    :course-offering/section    "001"
    :course-offering/capacity   30}

   {:enrollment/student     [:student/id "s-100"]
    :enrollment/offering    [:course-offering/id "2026-fall-CS101-001"]
    :enrollment/status      :enrollment.status/active}])
```

```java
Object courseRef = Datalevin.listOf(Datalevin.kw("course/code"), "CS101");
Object termRef = Datalevin.listOf(Datalevin.kw("term/id"), "2026-fall");
Object instructorRef = Datalevin.listOf(Datalevin.kw("instructor/id"), "i-10");
Object offeringRef = Datalevin.listOf(
    Datalevin.kw("course-offering/id"),
    "2026-fall-CS101-001");

conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("student/id", "s-100")
        .put("student/name", "Ada"))
    .entity(Tx.entity()
        .put("course/code", "CS101")
        .put("course/title", "Intro to Databases"))
    .entity(Tx.entity()
        .put("term/id", "2026-fall"))
    .entity(Tx.entity()
        .put("instructor/id", "i-10")
        .put("instructor/name", "Grace Hopper"))
    .entity(Tx.entity()
        .put("course-offering/id", "2026-fall-CS101-001")
        .put("course-offering/course", courseRef)
        .put("course-offering/term", termRef)
        .put("course-offering/instructor", instructorRef)
        .put("course-offering/section", "001")
        .put("course-offering/capacity", 30L))
    .entity(Tx.entity()
        .put("enrollment/student",
             Datalevin.listOf(Datalevin.kw("student/id"), "s-100"))
        .put("enrollment/offering", offeringRef)
        .put("enrollment/status",
             Datalevin.kw("enrollment.status/active"))));
```

```python
from datalevin import interop

kw = interop().keyword

conn.transact([
    {":student/id": "s-100", ":student/name": "Ada"},
    {":course/code": "CS101", ":course/title": "Intro to Databases"},
    {":term/id": "2026-fall"},
    {":instructor/id": "i-10", ":instructor/name": "Grace Hopper"},
    {":course-offering/id": "2026-fall-CS101-001",
     ":course-offering/course": [kw(":course/code"), "CS101"],
     ":course-offering/term": [kw(":term/id"), "2026-fall"],
     ":course-offering/instructor": [kw(":instructor/id"), "i-10"],
     ":course-offering/section": "001",
     ":course-offering/capacity": 30},
    {":enrollment/student": [kw(":student/id"), "s-100"],
     ":enrollment/offering": [kw(":course-offering/id"),
                              "2026-fall-CS101-001"],
     ":enrollment/status": kw(":enrollment.status/active")}])
```

```javascript
import { interop } from "datalevin-node";

const raw = interop();
const courseCode = await raw.keyword(":course/code");
const termId = await raw.keyword(":term/id");
const instructorId = await raw.keyword(":instructor/id");
const studentId = await raw.keyword(":student/id");
const offeringId = await raw.keyword(":course-offering/id");
const active = await raw.keyword(":enrollment.status/active");

await conn.transact([
  {":student/id": "s-100", ":student/name": "Ada"},
  {":course/code": "CS101", ":course/title": "Intro to Databases"},
  {":term/id": "2026-fall"},
  {":instructor/id": "i-10", ":instructor/name": "Grace Hopper"},
  {":course-offering/id": "2026-fall-CS101-001",
   ":course-offering/course": [courseCode, "CS101"],
   ":course-offering/term": [termId, "2026-fall"],
   ":course-offering/instructor": [instructorId, "i-10"],
   ":course-offering/section": "001",
   ":course-offering/capacity": 30},
  {":enrollment/student": [studentId, "s-100"],
   ":enrollment/offering": [offeringId, "2026-fall-CS101-001"],
   ":enrollment/status": active}
]);
```

</div>

To update that enrollment later, find the relationship entity by joining through
the domain identifiers. Notice that the query moves from course and term to the
offering, then from offering and student to enrollment:

<div class="multi-lang">

```clojure
(let [enrollment
      (d/q '[:find ?enrollment .
             :in $ ?student-id ?course-code ?term-id ?section
             :where
             [?student :student/id ?student-id]
             [?course :course/code ?course-code]
             [?term :term/id ?term-id]
             [?offering :course-offering/course ?course]
             [?offering :course-offering/term ?term]
             [?offering :course-offering/section ?section]
             [?enrollment :enrollment/student ?student]
             [?enrollment :enrollment/offering ?offering]]
           (d/db conn)
           "s-100"
           "CS101"
           "2026-fall"
           "001")]
  (d/transact! conn
    [[:db/add enrollment :enrollment/grade "A"]]))
```

```java
Object enrollment = conn.query(
    "[:find ?enrollment . " +
    " :in $ ?student-id ?course-code ?term-id ?section " +
    " :where [?student :student/id ?student-id] " +
    "        [?course :course/code ?course-code] " +
    "        [?term :term/id ?term-id] " +
    "        [?offering :course-offering/course ?course] " +
    "        [?offering :course-offering/term ?term] " +
    "        [?offering :course-offering/section ?section] " +
    "        [?enrollment :enrollment/student ?student] " +
    "        [?enrollment :enrollment/offering ?offering]]",
    "s-100",
    "CS101",
    "2026-fall",
    "001");

conn.transact(Datalevin.tx()
    .add(enrollment, "enrollment/grade", "A"));
```

```python
enrollment = conn.query("""
[:find ?enrollment .
 :in $ ?student-id ?course-code ?term-id ?section
 :where
 [?student :student/id ?student-id]
 [?course :course/code ?course-code]
 [?term :term/id ?term-id]
 [?offering :course-offering/course ?course]
 [?offering :course-offering/term ?term]
 [?offering :course-offering/section ?section]
 [?enrollment :enrollment/student ?student]
 [?enrollment :enrollment/offering ?offering]]
""", "s-100", "CS101", "2026-fall", "001")

conn.transact([[":db/add", enrollment, ":enrollment/grade", "A"]])
```

```javascript
const enrollment = await conn.query(
  `[:find ?enrollment .
    :in $ ?student-id ?course-code ?term-id ?section
    :where
    [?student :student/id ?student-id]
    [?course :course/code ?course-code]
    [?term :term/id ?term-id]
    [?offering :course-offering/course ?course]
    [?offering :course-offering/term ?term]
    [?offering :course-offering/section ?section]
    [?enrollment :enrollment/student ?student]
    [?enrollment :enrollment/offering ?offering]]`,
  "s-100",
  "CS101",
  "2026-fall",
  "001");

await conn.transact([[":db/add", enrollment, ":enrollment/grade", "A"]]);
```

</div>

The read query uses the same ref edges:

<div class="multi-lang">

```clojure
(d/q '[:find ?student-name ?course-title ?section ?instructor-name ?status
       :in $ ?term-id
       :where
       [?term :term/id ?term-id]
       [?offering :course-offering/term ?term]
       [?offering :course-offering/course ?course]
       [?offering :course-offering/section ?section]
       [?offering :course-offering/instructor ?instructor]
       [?enrollment :enrollment/offering ?offering]
       [?enrollment :enrollment/student ?student]
       [?enrollment :enrollment/status ?status]
       [?student :student/name ?student-name]
       [?course :course/title ?course-title]
       [?instructor :instructor/name ?instructor-name]]
     (d/db conn)
     "2026-fall")
```

```java
Object enrollments = conn.query(
    "[:find ?student-name ?course-title ?section ?instructor-name ?status " +
    " :in $ ?term-id " +
    " :where [?term :term/id ?term-id] " +
    "        [?offering :course-offering/term ?term] " +
    "        [?offering :course-offering/course ?course] " +
    "        [?offering :course-offering/section ?section] " +
    "        [?offering :course-offering/instructor ?instructor] " +
    "        [?enrollment :enrollment/offering ?offering] " +
    "        [?enrollment :enrollment/student ?student] " +
    "        [?enrollment :enrollment/status ?status] " +
    "        [?student :student/name ?student-name] " +
    "        [?course :course/title ?course-title] " +
    "        [?instructor :instructor/name ?instructor-name]]",
    "2026-fall");
```

```python
enrollments = conn.query("""
[:find ?student-name ?course-title ?section ?instructor-name ?status
 :in $ ?term-id
 :where
 [?term :term/id ?term-id]
 [?offering :course-offering/term ?term]
 [?offering :course-offering/course ?course]
 [?offering :course-offering/section ?section]
 [?offering :course-offering/instructor ?instructor]
 [?enrollment :enrollment/offering ?offering]
 [?enrollment :enrollment/student ?student]
 [?enrollment :enrollment/status ?status]
 [?student :student/name ?student-name]
 [?course :course/title ?course-title]
 [?instructor :instructor/name ?instructor-name]]
""", "2026-fall")
```

```javascript
const enrollments = await conn.query(
  `[:find ?student-name ?course-title ?section ?instructor-name ?status
    :in $ ?term-id
    :where
    [?term :term/id ?term-id]
    [?offering :course-offering/term ?term]
    [?offering :course-offering/course ?course]
    [?offering :course-offering/section ?section]
    [?offering :course-offering/instructor ?instructor]
    [?enrollment :enrollment/offering ?offering]
    [?enrollment :enrollment/student ?student]
    [?enrollment :enrollment/status ?status]
    [?student :student/name ?student-name]
    [?course :course/title ?course-title]
    [?instructor :instructor/name ?instructor-name]]`,
  "2026-fall");
```

</div>

This example illustrates the layers that often appear together in a real
model:

1.  **Domain entities** such as students, courses, terms, and instructors.
2.  **Scheduled or contextual entities** such as course offerings, reservations,
    shipments, or price lists. These are real things in the domain, not just
    attributes copied onto another entity.
3.  **Relationship entities** such as enrollments, line items, memberships, and
    assignments.
4.  **Composite identities** for relationship entities that need upsert,
    lookup, or import stability.

Use composite identities as constraints and access paths, not as a reason to
duplicate facts. When the relationship is already expressed by refs, Datalevin's
joins are the natural way to move from external domain keys to the relationship
entity.


## 6. Documenting the Schema: `:db/doc`

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

This matters even more when LLMs or other tools help write queries. SQL
databases usually attach comments to table and column objects, and the exact
mechanism differs by vendor. Datalevin attaches documentation to the attribute
itself. The same attribute name that appears in a Datalog clause can carry its
meaning, constraints, and domain intent in schema metadata. A query assistant
can inspect `:db/doc` before choosing between attributes such as
`:order/total`, `:order/subtotal`, and `:order/tax`, instead of guessing from
names alone. Good `:db/doc` strings therefore improve both human maintenance
and machine-assisted query generation.


## 7. From Tables to Facts: Migrating SQL Models

Migrating from SQL to Datalevin is not a rejection of relational algebra. In a
sense, it is a migration to a better implementation of relational algebra by
rejecting the SQL user interface and container-centered storage. Datalevin keeps the
relational idea that facts can be joined by shared values, but represents the
data as explicit datoms that can be joined, traversed, pulled, counted, sampled,
and indexed in several ways.

When a SQL database adds JSON, text search, vector indexes, or graph-like
extensions, it may reduce the number of services you run, but the application is
still written against a container-first language and a planner that must
estimate joins over row or column containers. Datalevin starts from facts and
uses Datalog as the surface language, so migration is not merely a change of
syntax; it is a change in what the database exposes as its basic unit of
reasoning.

Figure 12.2 shows the conceptual steps that are often involved in such a
migration.

![From SQL tables to Datalog joins: a SQL orders row decomposes into datoms on entity 2001, where its NULL discount_code column produces no datom at all; its customer_id foreign key becomes an :order/customer ref edge to the customer entity; and a Datalog query joins by sharing the variable ?c between the order and customer patterns, with no JOIN keyword](/images/diagrams/sql-to-datalog.svg)

### 7.1 Translate the Vocabulary

The familiar SQL concepts still have Datalevin equivalents, but the boundaries
move from tables to attributes and facts:

| SQL Concept | Datalevin Equivalent | Migration Note |
| :--- | :--- | :--- |
| **Table** | **Namespace** such as `:order/` | A namespace groups attributes by domain meaning; it is not a physical table. |
| **Row** | **entity id** | An entity is the set of facts that share the same entity id. |
| **Column** | **Attribute** | Attributes are schema objects with their own value type, cardinality, uniqueness, and index settings. |
| **Primary Key** | **Unique identity attribute** | Use stable domain IDs with `:db.unique/identity`; keep Datalevin entity ids internal. |
| **Foreign Key** | **Ref attribute** | A `:db.type/ref` stores another entity id and joins naturally in Datalog. |
| **Join Table** | **Associative entity** | Model the join as its own entity when the relationship is large, queried directly, or has attributes. |
| **NULL** | **No datom** | Datalevin does not store `nil`/`null`; absence means the fact is not present. |

### 7.2 Decompose Rows into Facts

In SQL, a row is the unit that receives a full set of column values:

```sql
INSERT INTO orders (order_id, customer_id, status, total_cents, discount_code)
VALUES ('ord-1001', 'cust-42', 'paid', 4299, NULL);
```

In Datalevin, the transaction can still be written as an entity map, but the
stored representation is separate facts. The unknown `discount_code` is simply
left out of the map rather than set to a null:

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

Notice there is no `:order/discount` datom at all. Where SQL stores a `NULL` in
the `discount_code` cell, Datalevin stores nothing: the fact is simply absent.
This is why sparse and evolving domains work well in Datalevin. Optional fields
do not require nullable columns. If a discount code, shipment, or cancellation
reason is unknown, the corresponding datom is absent.

### 7.3 Recast Joins as Shared Variables

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

### 7.4 Model Foreign Keys and Join Tables Deliberately

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

### 7.5 Use a Practical Migration Checklist

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

### 7.6 Example: A Normalized E-commerce Schema

Suppose the SQL source has four familiar tables: `products`, `customers`,
`orders`, and `line_items`. A direct migration might be tempted to make an
order entity contain a nested list of products, quantities, and prices. That
would recreate an object graph, not a normalized relational model.

In Datalevin, keep the same conceptual separation that made the SQL model
useful. Products and customers are stable domain entities. Orders are events or
business records that point to customers. Line items are relationship entities:
each one connects one order to one product and carries facts about that
relationship, such as quantity. The result is still relational, but the joins
are expressed as Datalog variable sharing over refs rather than SQL `JOIN`
clauses over table names.

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

The key decision is the `:line-item/` namespace. A line item is not a product
attribute and not an order attribute; it is the relationship between an order
and a product. Modeling it as its own entity lets the application ask precise
questions: which products appeared in paid orders, which customers bought a
given SKU, how many units of each product were sold, or which orders contain
backordered items. Those queries can start from whichever fact is selective and
join through the two refs.

The schema also shows how migration preserves stable identifiers without
exposing Datalevin entity ids. `:product/sku`, `:customer/id`, and `:order/id`
are unique identity attributes, so imported rows and application commands can
use lookup refs. `:order/customer`, `:line-item/order`, and
`:line-item/product` store entity ids internally, but callers can transact them
using the domain keys they already have.

This is intentionally only the starting point. A production schema might add
order status, timestamps, shipment entities, payment attempts, discounts,
inventory reservations, or a composite `:line-item/key` over order and product
when the domain allows only one line per product per order. The modeling rule is
the same: keep durable nouns as entities, represent foreign keys as refs, and
promote relationships with their own facts into relationship entities.


## Summary: Relational Best Practices

1.  **Think in singular namespaces**: `:user/email`, not `:users/emails`.
2.  **Choose stable keys**: Use `:db.unique/identity` for domain identifiers you
    will use in imports, APIs, and lookup refs.
3.  **Normalize relationships**: Prefer many-side refs and join entities for
    large or directly queried relationships; see Chapter 11, Section 2.2.
4.  **Model ownership carefully**: Use `:db/isComponent` for true lifecycle
    ownership, not just for convenient nesting.
5.  **Represent subtypes as facts**: Use enum entities or subtype-specific
    namespaces rather than inheritance-table patterns.
6.  **Use tuple features deliberately**: Use `:db/tupleAttrs` for composite
    identity and stored tuple types only for compact value objects.
7.  **Document as you go**: Use `:db/doc` to encode the "why" behind every
    attribute, especially derived or denormalized facts.
8.  **Migrate SQL incrementally**: Preserve stable keys, convert foreign keys to
    refs, and validate migrated facts with the business queries the application
    already depends on.

By applying these time-tested ER principles, you ensure your Datalevin database
remains clean, performant, and understandable as your domain complexity grows.

## References

[1] Peter P. Chen, ["The Entity-Relationship Model: Toward a Unified
View of Data"](https://doi.org/10.1145/320434.320440), *ACM Transactions on
Database Systems* 1, no. 1, 1976, pp. 9-36.
