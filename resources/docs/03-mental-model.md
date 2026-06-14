---
title: "The Datalevin Mental Model: Values, Facts, and Relationships"
chapter: 3
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 3: The Datalevin Mental Model: Values, Facts, and Relationships

Transitioning to Datalevin often requires a fundamental shift in how you perceive
data. If you are coming from a relational (SQL) or document (NoSQL) background,
your mental model likely revolves around "containers": tables with fixed columns
or documents with nested structures. In these systems, the container is the
primary unit of truth.

Datalevin asks you to look deeper, past the containers, to the atomic facts
themselves. Moving from SQL to Datalevin is less like moving between different
spreadsheet programs and more like moving from a spreadsheet to a knowledge
graph: a network of things and relationships represented as facts. This chapter
provides the narrative framework to help you navigate this shift.

## 1. Think in Datoms, Not Containers

In traditional databases, if you want to record that Alice's email address is
`alice@example.com`, you must first decide which "box" that fact belongs in.
You might create a `Users` table and add a row for Alice. The fact of her email
address is then trapped within the context of that row and those other columns.

At the conceptual level, Datalevin discards these rigid boxes. Instead, it
stores data as a stream of atomic facts called **datoms**. Each datom is a
simple statement of truth represented as a triple: `[entity attribute value]`.

- **Entity (E):** The "who" or "what" (a unique 64-bit integer ID in Datalevin).
- **Attribute (A):** The "property" (a keyword like `:user/email`).
- **Value (V):** The "data" (like `"alice@example.com"`).


From a traditional perspective, `e` and `a` are coordinates in a two-dimensional
table view of values, and the value `v` is contained in the cell identified by
the coordinates.

However, a better view is to imagine a giant ledger where every single cell of
every spreadsheet in your entire company is cut out and pasted as an individual
line. Each line tells you exactly which "row" it came from (Entity), what
"column" it represents (Attribute), and what the "value" is.

This "fact-first" approach is powerful because it is **additive**.
To add a new piece of information, like her preferred language, you
don't need to "alter a table." You just add another datom:
`[101 :user/preferred-language "en"]`.


## 2. Attributes Over Tables: The Fluid Entity

Because facts are stored individually, the concept of a "Table" disappears.
In its place is **attribute-centric modeling**: you describe the behavior of
each attribute instead of defining a fixed row shape for each entity type.

In SQL, the schema belongs to the table. In Datalevin, the schema belongs to the
**attribute**. A schema is a map from attribute names to behavior: value type,
cardinality, uniqueness, full-text indexing for searching text, references to
other entities, and other properties. You define what a `:user/email` is, for
example a string and perhaps a value that must be unique, but you don't define
what a "User" is in a rigid sense.

Three schema terms appear often:

- **Value type** describes what kind of value an attribute stores, such as a
  string, integer, timestamp, or reference.
- **Cardinality** describes how many values one entity can have for one
  attribute. `:db.cardinality/one` means at most one value; it is the default.
  `:db.cardinality/many` means the entity can have a set of values.
- **Uniqueness** describes whether a value can identify only one entity.
  `:db.unique/identity` is commonly used for natural keys such as emails,
  slugs, and external ids. It also enables **upsert**, meaning "update the
  existing entity if the identity already exists, otherwise insert a new one."

An "Entity" in Datalevin is just a collection of datoms that happen to share
the same Entity ID. This makes entities **fluid**. A single ID could have
attributes associated with a `:user`, a `:customer`, and an `:employee`
simultaneously. You don't "join" these tables; you simply query for an ID
that possesses all those attributes.

To update a value, e.g. Alice's email address, you don't modify a row; you
transact a new fact about the same attribute of the same entity. For a
cardinality-one attribute, the new value replaces the old value for that entity
and attribute. For a cardinality-many attribute, the new value is added to the
entity's set of values. Uniqueness is a separate concern: it prevents two
entities from claiming the same unique value and lets you look up an entity by a
domain value such as an email address.

This fluidity allows your data model to evolve alongside your application. You
never have to worry about "breaking the schema" by adding a new attribute to an
existing entity, because there is no rigid "row" to break. Entity types are
conventions you create by naming your attributes (e.g., using the `person/`
namespace, the part before `/` in `:person/name`), not constraints enforced by
the storage engine.

## 3. Transactions: Controlled Change

A database is not only a place to read facts. It is also a system for changing
facts without leaving the application in a half-updated state. A
**transaction** is a single atomic unit of change: all changes in the
transaction succeed together, or none are applied. Transaction processing is the
database discipline of making those changes atomic, isolated, and durable while
many users or programs read and write concurrently [2].

Jim Gray's deeper motivation for transactions is that a database acts as a
surrogate for the part of the external world your application cares about [2].
Real-world events do not happen halfway. A customer either placed an order or
did not. A payment either settled or did not. A job either moved from pending to
running or it did not. The database may need several internal writes to record
one such event, but the application wants the event itself to appear as one
coherent change.

This is the core workload of **Online Transaction Processing (OLTP)** systems:
applications record orders, messages, jobs, profile edits, tool results, and
other current operational state. OLTP databases are usually optimized for many
small concurrent reads and writes over current state, not for large historical
scans or immutable log analysis.

In Datalevin, a transaction is the boundary around assertions and retractions.
An assertion adds a fact; a retraction removes a fact. One transaction can
create entities, update cardinality-one attributes, add cardinality-many values,
retract facts, and update custom key-value data in the same Datalevin store.
After the transaction commits, readers see a consistent database state.

In the example below, one transaction creates two entities and relates them. A
**reference** attribute, declared with `:db.type/ref`, stores the entity id of
another entity. This is how Datalevin represents graph edges.

When a transaction creates several related entities at once, you can use
temporary entity IDs, or **tempids**, so the new entities can refer to one
another before Datalevin assigns permanent IDs. In entity maps, `:db/id` is the
system attribute used to provide an explicit entity id or tempid. Negative
integers and strings can be used as tempids. If the transaction data does not
need to connect new entities to one another, `:db/id` can be omitted and
Datalevin will assign permanent entity IDs automatically.

Do not put application identifiers in `:db/id`. A permanent Datalevin entity id
is a system-managed `long`, not a UUID, email, slug, or external primary key.
It is also local to one database: two databases with the same application data
may assign different eids. Use a separate unique identity attribute, such as
`:user/id` or `:user/email`, for application identity.

Chapter 6 covers transaction data shapes, transaction reports, listeners,
transaction functions, and durability settings in detail. For now, the important
mental model is simple: Datalevin changes the database in coherent committed
steps, not by mutating one fact at a time in isolation.

<div class="multi-lang">

```clojure
;; The schema defines the behavior of attributes, not the shape of rows.
(def schema
  {:person/name    {:db/valueType :db.type/string}
   :person/email   {:db/valueType :db.type/string}
   :person/school  {:db/valueType :db.type/ref} ; A reference to the ID of another entity
   :school/name    {:db/valueType :db.type/string}
   :school/country {:db/valueType :db.type/string}})

;; Data is written as a collection of entities here.
;; Notice how 'Alice' and 'MIT' are just sets of attributes.
;; :db/id is a system attribute for entity ID; tempids are used here
(d/transact! conn
  [{:db/id -1 :person/name "Alice" :person/email "alice@example.com" :person/school -10}
   {:db/id -10 :school/name "MIT" :school/country "USA"}])
```

```java
// The schema defines the behavior of attributes, not the shape of rows.
Schema schema = Datalevin.schema()
    .attr("person/name",
          Schema.attribute().valueType(Schema.ValueType.STRING))
    .attr("person/email",
          Schema.attribute().valueType(Schema.ValueType.STRING))
    .attr("person/school",
          Schema.attribute().valueType(Schema.ValueType.REF)) // A reference to the ID of another entity
    .attr("school/name",
          Schema.attribute().valueType(Schema.ValueType.STRING))
    .attr("school/country",
          Schema.attribute().valueType(Schema.ValueType.STRING));

// Data is written as a collection of entities.
// Notice how 'Alice' and 'MIT' are just sets of attributes.
conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("person/name", "Alice")
        .put("person/email", "alice@example.com")
        .put("person/school", -10))
    .entity(Tx.entity(-10)
        .put("school/name", "MIT")
        .put("school/country", "USA")));
```

```python
# The schema defines the behavior of attributes, not the shape of rows.
schema = {
    ":person/name":    {":db/valueType": ":db.type/string"},
    ":person/email":   {":db/valueType": ":db.type/string"},
    ":person/school":  {":db/valueType": ":db.type/ref"},  # A reference to the ID of another entity
    ":school/name":    {":db/valueType": ":db.type/string"},
    ":school/country": {":db/valueType": ":db.type/string"}}

# Data is written as a collection of entities.
# Notice how 'Alice' and 'MIT' are just sets of attributes.
conn.transact([
    {":db/id": -1,
     ":person/name": "Alice",
     ":person/email": "alice@example.com",
     ":person/school": -10},
    {":db/id": -10,
     ":school/name": "MIT",
     ":school/country": "USA"}])
```

```javascript
// The schema defines the behavior of attributes, not the shape of rows.
const schema = {
  ":person/name":    { ":db/valueType": ":db.type/string" },
  ":person/email":   { ":db/valueType": ":db.type/string" },
  ":person/school":  { ":db/valueType": ":db.type/ref" },  // A reference to the ID of another entity
  ":school/name":    { ":db/valueType": ":db.type/string" },
  ":school/country": { ":db/valueType": ":db.type/string" }
};

// Data is written as a collection of entities.
// Notice how 'Alice' and 'MIT' are just sets of attributes.
await conn.transact([
  { ":db/id": -1,
    ":person/name": "Alice",
    ":person/email": "alice@example.com",
    ":person/school": -10 },
  { ":db/id": -10,
    ":school/name": "MIT",
    ":school/country": "USA" }
]);
```

</div>

## 4. Store Each Fact Once, Then Link Entities

Once you start thinking in datoms, the next modeling habit is simple: store each
durable fact in one place, then connect entities with references. Suppose a
customer places many orders. You could copy the customer's email address into
every order, but then one email change would require many writes. In Datalevin,
you usually store the customer's email once on the customer entity, and store a
reference from each order to that customer.

The connection is itself a fact. If entity `1001` is a customer and entity
`2001` is an order, the relationship can be represented as another datom:

```clojure
[1001 :customer/email "alice@example.com"]
[2001 :order/number "A-100"]
[2001 :order/customer 1001]
```

The third datom says that order `2001` points to customer `1001`. Nothing is
embedded inside the order, and the customer's email is not copied into the order.
The facts stay separate, but the shared entity id connects the order facts to
the customer facts.

Database terminology calls this **normalization** [1]: representing each durable
fact once and connecting related entities by references. The opposite is
**denormalization**: copying or nesting the same information into several larger
records so each read can fetch a preassembled shape. Both styles have uses, but
Datalevin's fact model makes the normalized style natural.

The database term for following this kind of connection is a **join**. A join
combines facts that share a value. In this case, a query can read the
`:order/customer` value from the order, treat that value as the customer entity
id, and then read the customer's email from that entity.

In logic-programming terminology, this matching process is called
**unification**. A variable such as `?customer` may appear in several clauses,
and the query engine must find values that make all appearances of that variable
refer to the same entity. When the order clause binds `?customer` to `1001`, the
customer clauses must use that same value. That is the logical basis of a join in
Datalog.

This may sound like extra work if you are coming from document databases, where
joins are often avoided. In Datalevin, all facts are stored in a unified set of
indexes. An **index** is a sorted access path optimized for a particular lookup
order. Joining an `Order` to a `Customer` isn't a cross-table operation; it's
just a matter of looking up datoms that share a value.

### Why Storing Facts Once Works Well in Datalevin:

1.  **Granular Updates:** You can update a customer's email without touching
    thousands of order records. In a denormalized document store, this update
    would be a massive, expensive operation.
2.  **Universal Indexing:** Every attribute is indexed. When each fact is stored
    once, the engine can use its core EAV (Entity-Attribute-Value) and AVE
    (Attribute-Value-Entity) indexes to jump directly to the relevant facts.
3.  **The Optimizer's Secret Sauce:** Datalevin uses a cost-based optimizer:
    a planner that chooses a query execution order by estimating which steps are
    cheapest. It counts or samples the data to estimate how many datoms a query
    clause might match. This estimate is also called cardinality in query
    planning, but it is different from the schema property `:db/cardinality`.
    By storing each fact once, you give the optimizer a clearer picture of how
    to order joins for maximum speed.

For a deeper dive into how this approach often beats traditional relational
engines in query performance, see the discussion on the [Join Order
Benchmark](https://yyhh.org/blog/2024/09/competing-for-the-job-with-a-triplestore/).

## 5. Three Views of the Same Database

It is useful to separate three views of Datalevin: the logical view you model
with, the storage view the engine persists, and the query view that connects the
two at runtime. They are not three different databases. They are three ways of
looking at the same facts.

### Logical View: Datoms and Schema

This is the view you use when designing your application. You think in entities,
attributes, values, references, and schema rules. At this level, a fact such as
"Alice's email is alice@example.com" is simply a datom:
`[101 :user/email "alice@example.com"]`.

The logical view is intentionally small. It gives you a uniform way to describe
people, documents, orders, permissions, embeddings, and relationships without
inventing a new container shape for every case.

### Storage View: Indexes and Sorted Keys

Underneath the logical view is a high-performance **Key-Value Store** (DLMDB).
DLMDB is Datalevin's extension of LMDB, a memory-mapped sorted key-value store.
At this level, Datalevin maps datoms into sorted byte keys so it can answer
common lookup patterns efficiently.

This is where indexes matter. A **range scan** reads a contiguous run of sorted
keys, for example all facts for one attribute or all values between two bounds.
Datalevin also exposes this storage layer through a direct key-value API for
cases where you need raw speed or simple state management without the overhead
of a query engine.

### Query View: Datalog, Planning, and Evaluation

The query view is the **Datalog Engine**. Datalog is Datalevin's declarative
query language; the engine evaluates Datalog queries by translating logical
clauses into index lookups and joins over the storage layer. It uses a
cost-based optimizer to decide the most efficient order for retrieving and
combining facts.

This view is what lets you stay focused on the shape of the answer instead of
manual traversal. You describe the relationships that must hold, and the engine
handles joins, filtering, recursion, and lookup order.

```text
+------------------------------------------------------------+
| Logical view                                                |
| Datoms and schema: [101 :user/email "alice@example.com"]    |
+----------------------------+-------------------------------+
                             |
                             v
+------------------------------------------------------------+
| Query view                                                  |
| Datalog planner and evaluator: clauses -> index lookups     |
+----------------------------+-------------------------------+
                             |
                             v
+------------------------------------------------------------+
| Storage view                                                |
| DLMDB indexes: sorted keys and range scans                  |
+------------------------------------------------------------+
```

## 6. Datalog: Querying as Logic Programming

If SQL is like giving the database a set of instructions on how to assemble a
table, Datalog is like giving the database a **description** of the answer you
want. That declarative style comes from logic programming [3]:
programs are expressed as logical clauses, and evaluation searches for values
that make those clauses true.

In Datalevin, a query is a set of logical "clauses." You aren't telling the
engine to "JOIN table A with table B." You are stating: "I am looking for an
Entity `?e` that has Attribute `:user/name` with Value `'Alice'`, and that same
Entity `?e` must also have an Attribute `:user/email` which is `?email`."

The syntax has a few pieces:

- `:find` describes what the query returns.
- `:where` lists the clauses that must all be true.
- Symbols beginning with `?`, such as `?e` and `?email`, are variables.
- Reusing the same variable in multiple clauses creates a join by
  **unification**: the engine finds values that make all occurrences of that
  variable agree.
- `db` is a Datalevin DB object used as the query input.

Unlike Datomic, Datalevin does not provide "database as a value" semantics. A
DB object is a mutable database object/reference, not a persistent immutable
value. In application code, keep and share the connection. When you
need to read the latest committed state, call `(d/db conn)` and use that DB
object for the query instead of saving an old `db` object and expecting it to
stay fresh.

<div class="multi-lang">

```clojure
(d/q '[:find ?email
       :where
       [?e :user/name "Alice"]
       [?e :user/email ?email]]
     db)
```

```java
Object results = conn.query(
    "[:find ?email " +
    " :where " +
    " [?e :user/name \"Alice\"] " +
    " [?e :user/email ?email]]");
```

```python
results = conn.query("""
    [:find ?email
     :where
     [?e :user/name "Alice"]
     [?e :user/email ?email]]""")
```

```javascript
const results = await conn.query(
  `[:find ?email
    :where
    [?e :user/name "Alice"]
    [?e :user/email ?email]]`);
```

</div>

The engine takes these statements and finds the set of values that makes all of
them true simultaneously. As mentioned, this is what is called **logic
programming** in the literature. Because joins are implicit (represented by the
shared variable `?e`), the complexity of your query doesn't grow with the number
of joins; it only grows with the logic you want to express.

Attributes are queryable too. In a datom pattern, the entity, attribute, and
value positions can all be variables. For example, this query asks which
attributes are present on entities that have a `:person/name`:

```clojure
(d/q '[:find ?attr
       :where
       [?p :person/name]
       [?p ?attr]]
     db)
```

The first pattern finds entities with a `:person/name`; the second pattern binds
`?attr` to each attribute asserted for those same entities.

## 7. Unified Retrieval: Plugging into the Datom Flow

One of the most powerful aspects of the Datalevin mental model is that
"special" features like full-text search, vector similarity, and document
indexing are not separate services running beside the database. Full-text
search finds text by terms, vector similarity finds nearby numeric vectors, and
document indexing makes paths inside nested document values queryable. They are
integrated directly into the Datalog flow.

When you perform a full-text search, the search function behaves just like any
other Datalog clause. It returns a set of datoms (`[e a v]`) that can be
immediately joined with other relational facts. The examples below destructure
each returned datom as `[[?e _ _]]`: keep the entity id, and ignore the
attribute and value.

<div class="multi-lang">

```clojure
;; A hybrid query combines text search with exact metadata filters.
(d/q '[:find ?title
       :where
       [(fulltext $ :doc/body "clojure") [[?e _ _]]] ; Keep entity id from returned datoms
       [?e :doc/status :published]                   ; Standard datom join
       [?e :doc/title ?title]]                       ; Retrieve the title
     db)
```

```java
// A hybrid query combines text search with exact metadata filters.
Object results = conn.query(
    "[:find ?title " +
    " :where " +
    " [(fulltext $ :doc/body \"clojure\") [[?e _ _]]] " +
    " [?e :doc/status :published] " +
    " [?e :doc/title ?title]]");
```

```python
# A hybrid query combines text search with exact metadata filters.
results = conn.query("""
    [:find ?title
     :where
     [(fulltext $ :doc/body "clojure") [[?e _ _]]]
     [?e :doc/status :published]
     [?e :doc/title ?title]]""")
```

```javascript
// A hybrid query combines text search with exact metadata filters.
const results = await conn.query(
  `[:find ?title
    :where
    [(fulltext $ :doc/body "clojure") [[?e _ _]]]
    [?e :doc/status :published]
    [?e :doc/title ?title]]`);
```

</div>

This "Unified Retrieval" model means you never have to worry about syncing
your database with an external search engine. Your search results are always
transactionally consistent with your data: they reflect the same committed
database state as the ordinary datoms in the query.

## 8. Rules: Teaching the Database New Tricks

Rules are how you encapsulate and reuse logic in Datalevin. A **rule** is a
named Datalog pattern. Query clauses can call the rule by name, and Datalevin
expands that call into the rule body during query evaluation. If you find
yourself frequently querying for "Active Users who have Premium subscriptions,"
you can define a rule called `premium-user`.

Rules can also be **recursive**, meaning a rule can call itself. Recursion is
essential for graph traversal because it lets a query follow an unknown number
of edges. A classic example is finding ancestors in a family tree:

<div class="multi-lang">

```clojure
(def ancestry-rules
  '[[(ancestor ?x ?y)      ; Rule name and arguments
     [?x :parent ?y]]      ; Case 1: y is the direct parent of x

    [(ancestor ?x ?y)      ; Case 2: Recursion
     [?x :parent ?z]       ; z is the parent of x
     (ancestor ?z ?y)]])   ; y is an ancestor of z
```

```java
// Rules are passed as a Datalog string
String ancestryRules =
    "[[(ancestor ?x ?y) " +
    "  [?x :parent ?y]] " +
    " [(ancestor ?x ?y) " +
    "  [?x :parent ?z] " +
    "  (ancestor ?z ?y)]]";
```

```python
# Rules are passed as a Datalog string
ancestry_rules = """
    [[(ancestor ?x ?y)
      [?x :parent ?y]]
     [(ancestor ?x ?y)
      [?x :parent ?z]
      (ancestor ?z ?y)]]"""
```

```javascript
// Rules are passed as a Datalog string
const ancestryRules = `
    [[(ancestor ?x ?y)
      [?x :parent ?y]]
     [(ancestor ?x ?y)
      [?x :parent ?z]
      (ancestor ?z ?y)]]`;
```

</div>

By defining this rule, you've taught the database the *concept* of an ancestor.
The `%` input below supplies the rule set to the query, and `(ancestor 42
?ancestor)` calls the rule:

<div class="multi-lang">

```clojure
(d/q '[:find [?ancestor ...]
       :in $ %
       :where
       (ancestor 42 ?ancestor)]
     db ancestry-rules)
```

```java
Object ancestors = conn.query(
    "[:find [?ancestor ...] " +
    " :in $ % " +
    " :where " +
    " (ancestor 42 ?ancestor)]",
    ancestryRules);
```

```python
ancestors = conn.query("""
    [:find [?ancestor ...]
     :in $ %
     :where
     (ancestor 42 ?ancestor)]""",
    ancestry_rules)
```

```javascript
const ancestors = await conn.query(
  `[:find [?ancestor ...]
    :in $ %
    :where
    (ancestor 42 ?ancestor)]`,
  ancestryRules);
```

</div>

You can now ask "Who are all the ancestors of Entity 42?" and the engine will
navigate the graph for you.

## Summary

The Datalevin mental model is about moving from "data-in-boxes" to "data-as-facts."

- **Datoms** are the atoms of truth.
- **Attributes** define how facts behave.
- **Transactions** move the database from one coherent state to the next.
- **Storing each fact once and linking entities** keeps the model clean and the
  engine fast.
- **Datalog** allows you to query by describing the logic of your answer.
- **Rules** allow you to build complex, reusable, and recursive logic.

By embracing this model, you unlock a level of flexibility and power that
traditional databases struggle to match.

## References

[1] E. F. Codd, "Further Normalization of the Data Base Relational Model,"
IBM Research Report RJ909, August 31, 1971. Republished in Randall J. Rustin,
ed., *Data Base Systems: Courant Computer Science Symposia Series 6*,
Prentice-Hall, 1972.

[2] Jim Gray and Andreas Reuter, *Transaction Processing: Concepts and
Techniques*, Morgan Kaufmann, 1993.

[3] Robert A. Kowalski, ["Predicate Logic as Programming
Language"](https://www.doc.ic.ac.uk/~rak/papers/IFIP74.pdf), IFIP Congress,
1974, pp. 569-574.
