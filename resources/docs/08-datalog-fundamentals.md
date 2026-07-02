---
title: "Datalog Fundamentals"
chapter: 8
part: "II — Core APIs: Datalog First, KV When Needed"
---

# Chapter 8: Datalog Fundamentals

Datalevin's primary query language is Datalog. It is a powerful, **declarative**
language for finding complex patterns in your data. Unlike SQL, which exposes a
large table-oriented surface of joins, aliases, grouping forms, subqueries, and
dialect-specific syntax, Datalog lets you focus on the facts that must be true
for an answer to exist.

Datalevin writes Datalog queries as EDN data: vectors, keywords, symbols,
lists, and maps. Compared with older Prolog-like rule syntax, EDN queries are
easier to compose as program data and pass through APIs. The surface syntax is
application-friendly, but the underlying concepts are still the classic Datalog
concepts: variables, shared constraints, predicates, rules, and recursive
reasoning.

This chapter covers the fundamentals of writing Datalog queries, the role of the
query optimizer, and best practices for performance.


## 1. Basic Attribute Filters

The most important concept to understand about Datalog is that it is
declarative. The `:where` clauses in a query define a *set of constraints*. You
describe what must be true for an answer to exist; you are not giving the
database a step-by-step procedure.

### 1.1 A Single Filter on an Entity

A Datalog query usually starts with `:find`, which says what to return, and
`:where`, which says what facts must match. The most common `:where` clause is a
data pattern:

```clojure
(d/q '[:find ?e
       :where [?e :user/name "Alice"]]
     db)
```

A data pattern is positional: entity, attribute, then value. The pattern above
asks for every entity `?e` that has the attribute `:user/name` with value
`"Alice"`.

### 1.2 Two Filters on the Same Entity

To add another condition on the same entity, reuse the same variable:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [?e :user/name "Alice"]
              [?e :user/city "London"]]
     db)
```

```java
Object result = conn.query("[:find ?e " +
    ":where [?e :user/name \"Alice\"] " +
    "       [?e :user/city \"London\"]]");
```

```python
result = conn.query('[:find ?e '
    ':where [?e :user/name "Alice"] '
    '       [?e :user/city "London"]]')
```

```javascript
const result = await conn.query('[:find ?e ' +
    ':where [?e :user/name "Alice"] ' +
    '       [?e :user/city "London"]]');
```

</div>

This query asks for entities that have a name of `"Alice"` and a city of
`"London"`. Each occurrence of `?e` must refer to the same entity within a
result row.[^query-terms] That shared variable is the logical connection between
the two clauses.

The clause order is not a procedure. The query does not mean "first find people
named Alice, then filter by city." It means both constraints must hold.

### 1.3 Leaving Out Trailing Positions

Datalevin allows trailing positions to be omitted when you do not need them.
The pattern `[?e :user/name]` means "some datom exists for entity `?e` and
attribute `:user/name`, regardless of value":

```clojure
(d/q '[:find [?e ...]
       :where [?e :user/name]]
     db)
```

This is different from binding the value to a variable. Use
`[?e :user/name ?name]` when the value matters later in the query or should be
returned from `:find`.

### 1.4 Ignoring Positions with `_`

When the position you want to ignore is not trailing, use `_` as a non-binding
placeholder. For example, this query asks for all user names. Each matching
entity must have some entity id, but the query does not need to name it:

```clojure
(d/q '[:find [?name ...]
       :where [_ :user/name ?name]]
     db)
```

Each `_` is independent. In `[?e _ _]`, the first `_` means "some attribute" and
the second `_` means "some value"; they are not the same variable. If two
positions need to be the same, or if a value must be reused in another clause,
give it a normal variable name:

```clojure
(d/q '[:find ?edge
       :where [?edge :edge/from ?node]
              [?edge :edge/to ?node]]
     db)
```

Use `_` when a position must be present but should not bind a variable. Use
omission when later positions of a data pattern are simply not part of the
question. The same placeholder syntax also appears in binding patterns for
query functions; Section 4.3 shows an example.


## 2. Joins Across Entities

A join is just another use of shared variables. When two data patterns mention
the same variable, they must agree on its value. If those patterns describe
different entities, the shared variable relates those entities.

Before adding more query features, it is useful to walk through one ordinary
Datalog query mechanically.

Assume `:order/customer` is a ref attribute and the database contains these
facts[^edb-idb], shown as `[e a v]` datoms. The names `alice`, `bob`, `cara`, and
`order-1` through `order-4` are explanatory labels; Datalevin stores numeric
entity ids internally.

```text pdf-keep
[[alice   :user/name      "Alice"]
 [alice   :user/city      "London"]
 [bob     :user/name      "Bob"]
 [bob     :user/city      "London"]
 [cara    :user/name      "Cara"]
 [cara    :user/city      "Paris"]
 [order-1 :order/customer alice]
 [order-1 :order/total    125]
 [order-2 :order/customer alice]
 [order-2 :order/total    60]
 [order-3 :order/customer bob]
 [order-3 :order/total    200]
 [order-4 :order/customer cara]
 [order-4 :order/total    300]]
```

Now ask for London users with orders over 100:

```clojure
(d/q '[:find ?name ?total
       :where
       [?u :user/city "London"]
       [?u :user/name ?name]
       [?o :order/customer ?u]
       [?o :order/total ?total]
       [(> ?total 100)]]
     db)
;; => #{["Alice" 125] ["Bob" 200]}
```

Conceptually, read the clauses as constraints over a set of variable bindings.
The optimizer may evaluate the physical query in a different order, but the
logical meaning is the same.

![Worked query binding flow: the binding rows gain a ?u column, reuse ?u to add ?name, keep orders whose customer is the same ?u (dropping order-4 whose customer is not a London user), add ?total, filter out the row whose total is 60, and keep ?name and ?total in the result](/images/diagrams/query-binding-flow.svg)

Figure 8.1 illustrates a possible variable binding flow that resolves this query.
The steps below trace that relation one clause at a time.

The first clause finds entities whose city is London:

```text pdf-keep
after [?u :user/city "London"]
{?u alice}
{?u bob}
```

The second clause reuses `?u` and adds `?name`. Each occurrence of `?u` must
refer to the same entity within a row.

```text pdf-keep
after [?u :user/name ?name]
{?u alice, ?name "Alice"}
{?u bob,   ?name "Bob"}
```

The third clause finds orders whose customer is the same `?u`. The `?u` in
`[:order/customer ?u]` must agree with the `?u` already bound by the user
clauses.

```text pdf-keep
after [?o :order/customer ?u]
{?u alice, ?name "Alice", ?o order-1}
{?u alice, ?name "Alice", ?o order-2}
{?u bob,   ?name "Bob",   ?o order-3}
```

`order-4` is not present because its customer is `cara`, and `cara` was not in
the London-user rows.

The fourth clause adds totals for those orders:

```text pdf-keep
after [?o :order/total ?total]
{?u alice, ?name "Alice", ?o order-1, ?total 125}
{?u alice, ?name "Alice", ?o order-2, ?total 60}
{?u bob,   ?name "Bob",   ?o order-3, ?total 200}
```

The predicate clause keeps only rows whose total is greater than 100:

```text pdf-keep
after [(> ?total 100)]
{?u alice, ?name "Alice", ?o order-1, ?total 125}
{?u bob,   ?name "Bob",   ?o order-3, ?total 200}
```

Finally, `:find ?name ?total` keeps only the requested values from each
surviving row. Intermediate variables such as `?u` and `?o` helped express the
query, but they are not returned:

```text pdf-keep
["Alice" 125]
["Bob"   200]
```

This is the core Datalog mental model: data patterns create candidate bindings,
shared variables keep those bindings consistent, predicates filter rows, and
`:find` shapes the surviving values into the result.

The join also affects result cardinality. Alice has two orders in the source
facts, so the intermediate relation has two Alice rows after joining users to
orders. The later predicate removes the smaller order, but without that
predicate both Alice orders would contribute rows.


## 3. Pull in Queries

When a query discovers the matching entities, `pull` can shape those entities
immediately in the `:find` clause. This is the usual pattern for "find the
matching entities and return application maps":

<div class="multi-lang">

```clojure
(d/q '[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...]
       :where [?e :user/city "London"]]
     db)
```

```java
Object result = conn.query("[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...] " +
    ":where [?e :user/city \"London\"]]");
```

```python
result = conn.query('[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...] '
    ':where [?e :user/city "London"]]')
```

```javascript
const result = await conn.query('[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...] ' +
    ':where [?e :user/city "London"]]');
```

</div>

This query finds all users in London and, for each one, pulls their name and a
nested list of their orders. Chapter 7 covers pull patterns in detail. In this
chapter, the important point is that `:where` discovers the entities and `pull`
shapes only the entities that survived the query.


## 4. Variables and Functions in `:where`

Data patterns are the foundation, but `:where` clauses can also use variables
in the attribute position, call predicates to filter rows, and call functions
that bind new values.

### 4.1 Variable Attributes

The attribute position can be a variable, so you can ask questions about the
shape of your data:

<div class="multi-lang">

```clojure
(d/q '[:find ?attr
       :where
       [?p :person/name]
       [?p ?attr]]
     db)
```

```java
Object attrs = conn.query("[:find ?attr " +
    ":where [?p :person/name] " +
    "       [?p ?attr]]");
```

```python
attrs = conn.query('[:find ?attr '
    ':where [?p :person/name] '
    '       [?p ?attr]]')
```

```javascript
const attrs = await conn.query('[:find ?attr ' +
    ':where [?p :person/name] ' +
    '       [?p ?attr]]');
```

</div>

The first clause finds people with a `:person/name`. The second clause reuses
the same entity variable and binds `?attr` to every attribute asserted for those
entities. With `:find ?attr`, the result is a one-column relation. Use the
collection form `:find [?attr ...]` when you want a flat collection of
attribute keywords. If you also need the values, write `[?p ?attr ?value]` and
include `?value` in `:find`.

### 4.2 Predicate Clauses

`:where` clauses are not limited to data patterns. They can also call functions
to filter rows or compute new variable bindings.

A predicate clause filters results based on a function that returns `true` or
`false`.

<div class="multi-lang">

```clojure
;; Find users older than 30
(d/q '[:find ?name ?age
       :where [?e :user/name ?name]
              [?e :user/age ?age]
              [(> ?age 30)]]
     db)
```

```java
// Find users older than 30
Object result = conn.query("[:find ?name ?age " +
    ":where [?e :user/name ?name] " +
    "       [?e :user/age ?age] " +
    "       [(> ?age 30)]]");
```

```python
# Find users older than 30
result = conn.query('[:find ?name ?age '
    ':where [?e :user/name ?name] '
    '       [?e :user/age ?age] '
    '       [(> ?age 30)]]')
```

```javascript
// Find users older than 30
const result = await conn.query('[:find ?name ?age ' +
    ':where [?e :user/name ?name] ' +
    '       [?e :user/age ?age] ' +
    '       [(> ?age 30)]]');
```

</div>

This returns only users who are older than 30. Notice that the single predicate
function call is wrapped in a vector form. Common built-in predicates include
comparisons such as `=`, `<`, `>`, `<=`, and `>=`, and Datalevin-specific
helpers such as `like` and `in`. Appendix D lists the built-in query and
aggregate functions.

### 4.3 Binding Clauses

You can also use functions to compute and bind new variables.

<div class="multi-lang">

```clojure
;; Compute an order total from subtotal and tax, and bind the result to ?total
(d/q '[:find ?order-id ?total
       :where [?o :order/id ?order-id]
              [?o :order/subtotal ?subtotal]
              [?o :order/tax ?tax]
              [(+ ?subtotal ?tax) ?total]]
     db)
```

```java
// Compute an order total from subtotal and tax
Object result = conn.query("[:find ?order-id ?total " +
    ":where [?o :order/id ?order-id] " +
    "       [?o :order/subtotal ?subtotal] " +
    "       [?o :order/tax ?tax] " +
    "       [(+ ?subtotal ?tax) ?total]]");
```

```python
# Compute an order total from subtotal and tax
result = conn.query('[:find ?order-id ?total '
    ':where [?o :order/id ?order-id] '
    '       [?o :order/subtotal ?subtotal] '
    '       [?o :order/tax ?tax] '
    '       [(+ ?subtotal ?tax) ?total]]')
```

```javascript
// Compute an order total from subtotal and tax
const result = await conn.query('[:find ?order-id ?total ' +
    ':where [?o :order/id ?order-id] ' +
    '       [?o :order/subtotal ?subtotal] ' +
    '       [?o :order/tax ?tax] ' +
    '       [(+ ?subtotal ?tax) ?total]]');
```

</div>

`?total` is a new variable that takes the result of `(+ ?subtotal ?tax)` as its
value. This binding clause vector has two elements. Read from left to right:
the value flows from the function call into the bound variable.

Some query functions return tuples or relations rather than one scalar value.
Use a binding pattern on the right side to describe how the returned values
should bind to variables. `_` works in these binding patterns too:

```clojure
;; Assumes :doc/body has :db/fulltext true and :db.fulltext/autoDomain true.
(d/q '[:find ?e
       :where [(fulltext $ :doc/body "clojure") [[?e _ _]]]]
     db)
```

The `fulltext` function is covered in Chapter 16. It appears here because its
return binding is a compact example: bind the entity id to `?e`, and ignore the
attribute and value positions.

### 4.4 User-Defined Query Functions

The function name at the start of a predicate or binding clause is resolved
before the clause runs. Common built-in functions and predicates, such as `+`,
`-`, `*`, `/`, `>`, `<`, `like`, `re-find`, `empty?`, and `true?`, are
available without namespace qualification.

Custom Clojure functions can also be used in predicate and binding clauses, but
they must be fully qualified with their namespace:

```clojure
(ns my-app.queries
  (:require [datalevin.core :as d]))

(defn adult? [age]
  (> age 18))

(d/q '[:find ?name
       :where [?e :user/name ?name]
              [?e :user/age ?age]
              [(my-app.queries/adult? ?age)]]
     db)
```

The `adult?` function is not built in, so the query includes
`my-app.queries/adult?`. Without that namespace, Datalevin cannot resolve the
function.

### 4.5 Predicate Performance

Predicate clauses are constraints, but not all constraints give the optimizer
the same information. When a simple built-in comparison applies to a value from
a datom pattern, Datalevin can turn it into an index range:

```clojure
[:find ?name
 :where [?e :user/name ?name]
        [?e :user/age ?age]
        [(> ?age 18)]]
```

Here `[(> ?age 18)]` can narrow the scan over `:user/age` values. A predicate
that involves one variable can also be pushed down to the scan as a value
filter, even when it is a custom predicate. The difference is that a custom
predicate is opaque to the range planner:

```clojure
[:find ?name
 :where [?e :user/name ?name]
        [?e :user/age ?age]
        [(my-app.queries/adult? ?age)]]
```

Datalevin can attach this predicate to the `:user/age` scan, so it is evaluated
as candidate age values are read. It still cannot look inside `adult?` and
convert the body into an index range. Predicates that involve more than one
variable, such as `[(my-app.queries/eligible? ?age ?status)]`, cannot be
attached to a single attribute scan and are handled later, after the needed
variables have been bound. Use built-in comparisons, equality, `like`, and `in`
for simple value tests when possible. Keep custom predicates for logic that
cannot be expressed directly, and combine them with selective data patterns so
they run over a small candidate set.

## 5. Alternatives and Negation

By default, all `:where` clauses are joined with an implicit `and`. Datalog also
provides forms for alternatives and exclusion:

-   `or`: one of several alternative branches may match.
-   `or-join`: like `or`, but explicitly lists the variables that connect the
    alternatives to the surrounding query.
-   `not`: excludes rows that match a negative pattern.
-   `not-join`: like `not`, but explicitly lists the variables that connect the
    negative pattern to the surrounding query.

### 5.1 `or`

Use `or` when each branch is an alternative way to bind the same logical
variables. For example, this query finds users whose status is either
`:pending` or `:flagged`:

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where (or [?e :user/status :pending]
                  [?e :user/status :flagged])]
     db)
```

```java
Object result1 = conn.query("[:find ?e " +
    ":where (or [?e :user/status :pending] " +
    "           [?e :user/status :flagged])]");
```

```python
result1 = conn.query('[:find ?e '
    ':where (or [?e :user/status :pending] '
    '           [?e :user/status :flagged])]')
```

```javascript
const result1 = await conn.query('[:find ?e ' +
    ':where (or [?e :user/status :pending] ' +
    '           [?e :user/status :flagged])]');
```

</div>

When an alternative needs more than one clause, wrap those clauses in
`(and ...)`:

```clojure
(or (and [?e :user/status :pending]
         [?e :user/priority :high])
    (and [?e :user/status :flagged]
         [?e :user/review-required? true]))
```

Plain `or` requires the branches to use the same set of free variables. That is
what lets the rest of the query continue with a consistent binding shape.

### 5.2 `or-join`

Use `or-join` when an alternative branch needs variables that should stay local
to that branch. The vector after `or-join` names the variables that connect the
alternatives to the surrounding query.

```clojure
(d/q '[:find ?e
       :where [?e :user/name]
              (or-join [?e]
                [?e :user/status :flagged]
                (and [?e :user/manager ?manager]
                     [?manager :user/status :flagged]))]
     db)
```

This query finds users who are flagged directly or whose manager is flagged.
The variable `?manager` is needed only inside the second branch. With plain
`or`, the first branch would bind `?e` while the second branch would bind both
`?e` and `?manager`, so the branch shapes would not match. `or-join [?e]`
states that only `?e` is the branch result that must unify with the rest of the
query.

### 5.3 `not`

Use `not` to remove rows that match a negative pattern. A `not` clause is a
test against bindings already produced by surrounding positive clauses; it does
not introduce new rows by itself. At least one variable in the negated pattern
must already be bound by the surrounding query.

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where [?e :user/name]
              (not [?e :user/is-admin? true])]
     db)
```

```java
Object result2 = conn.query("[:find ?e " +
    ":where [?e :user/name] " +
    "       (not [?e :user/is-admin? true])]");
```

```python
result2 = conn.query('[:find ?e '
    ':where [?e :user/name] '
    '       (not [?e :user/is-admin? true])]')
```

```javascript
const result2 = await conn.query('[:find ?e ' +
    ':where [?e :user/name] ' +
    '       (not [?e :user/is-admin? true])]');
```

</div>

A single `not` form with multiple clauses negates their conjunction: the row is
excluded only when all clauses inside the `not` match together.

### 5.4 `not-join`

Use `not-join` when the negative pattern needs local variables or when only
some variables should connect the negative pattern to the surrounding query.
The vector after `not-join` names those connecting variables. Variables not
listed there are local to the negative pattern. The listed variables must
already be bound before the `not-join` runs.

## 6. When You Think You Need a Subquery

Readers coming from SQL often reach for a subquery when they want to filter
outer rows by related facts. Datalevin can run a nested `q`, but ordinary
queries are usually clearer and faster when you express that logic directly in
Datalog. Use `not-join` for "no matching related fact" checks, and `or-join` for
branching existence checks with branch-local variables.

### 6.1 The Performance Trap: Nested `q`

It is technically possible to run a nested `q` subquery inside a predicate.
**You should almost never do this.**

<div class="multi-lang">

```clojure
;; ANTI-PATTERN: VERY SLOW!
(d/q '[:find ?e
       :where [?e :user/city "London"]
              [(empty? (q (quote [:find ?o
                                   :in $ ?e
                                   :where [?o :order/customer ?e]])
                          $ ?e))]] ; This subquery runs for EVERY London user
     db)
```

```java
// ANTI-PATTERN: VERY SLOW!
Object result = conn.query("[:find ?e " +
    ":where [?e :user/city \"London\"] " +
    "       [(empty? (q (quote [:find ?o " +
    "                            :in $ ?e " +
    "                            :where [?o :order/customer ?e]]) " +
    "                   $ ?e))]]");
```

```python
# ANTI-PATTERN: VERY SLOW!
result = conn.query('[:find ?e '
    ':where [?e :user/city "London"] '
    '       [(empty? (q (quote [:find ?o '
    '                            :in $ ?e '
    '                            :where [?o :order/customer ?e]]) '
    '                   $ ?e))]]')
```

```javascript
// ANTI-PATTERN: VERY SLOW!
const result = await conn.query('[:find ?e ' +
    ':where [?e :user/city "London"] ' +
    '       [(empty? (q (quote [:find ?o ' +
    '                            :in $ ?e ' +
    '                            :where [?o :order/customer ?e]]) ' +
    '                   $ ?e))]]');
```

</div>

The inner query is executed once for *every single tuple* (in this case, every
London user) found by the outer query. This is extremely inefficient.

### 6.2 Excluding Matches with `not-join`

For this kind of "find things with no matching related facts" question, express
the exclusion directly with `not-join`. Datalevin can plan it together with the
surrounding data patterns.

<div class="multi-lang">

```clojure
;; Find users in London with no orders
(d/q '[:find ?e
       :where [?e :user/city "London"]
              (not-join [?e]
                [?o :order/customer ?e])]
     db)
```

```java
// Find users in London with no orders
Object result = conn.query("[:find ?e " +
    ":where [?e :user/city \"London\"] " +
    "       (not-join [?e] " +
    "         [?o :order/customer ?e])]");
```

```python
# Find users in London with no orders
result = conn.query('[:find ?e '
    ':where [?e :user/city "London"] '
    '       (not-join [?e] '
    '         [?o :order/customer ?e])]')
```

```javascript
// Find users in London with no orders
const result = await conn.query('[:find ?e ' +
    ':where [?e :user/city "London"] ' +
    '       (not-join [?e] ' +
    '         [?o :order/customer ?e])]');
```

</div>

Here, `not-join` declares that `?e` is the variable shared with the surrounding
query. Datalevin can plan the negative pattern together with the positive
clauses, so it can exclude users with matching orders without invoking a
separate query for each candidate user. This is vastly more performant than the
nested `q` approach.

![Nested q versus not-join for "London users with no orders": the nested-q anti-pattern produces N candidate rows and runs an inner subquery once per row (N invocations whose cost scales with the candidates), while not-join folds the positive clause and a planned negative pattern into one query graph evaluated by the planner with index lookups as a single optimized query](/images/diagrams/subquery-vs-notjoin.svg)

Figure 8.2 shows the difference visually: the nested `q` version runs one inner
query per candidate user, while the `not-join` version keeps the exclusion in
one planned Datalog query.

### 6.3 Branching Checks with `or-join`

Use `or-join` when you want to keep an outer row if any of several related
patterns exists. The branch-local variables stay inside the branch, and the
declared join variables connect the branch result back to the surrounding query.

<div class="multi-lang">

```clojure
;; Find London users who either have a large order or an open support ticket.
(d/q '[:find ?e
       :where [?e :user/city "London"]
              (or-join [?e]
                (and [?o :order/customer ?e]
                     [?o :order/total ?total]
                     [(> ?total 1000)])
                (and [?ticket :ticket/customer ?e]
                     [?ticket :ticket/status :open]))]
     db)
```

```java
// Find London users who either have a large order or an open support ticket.
Object result = conn.query("[:find ?e " +
    ":where [?e :user/city \"London\"] " +
    "       (or-join [?e] " +
    "         (and [?o :order/customer ?e] " +
    "              [?o :order/total ?total] " +
    "              [(> ?total 1000)]) " +
    "         (and [?ticket :ticket/customer ?e] " +
    "              [?ticket :ticket/status :open]))]");
```

```python
# Find London users who either have a large order or an open support ticket.
result = conn.query('[:find ?e '
    ':where [?e :user/city "London"] '
    '       (or-join [?e] '
    '         (and [?o :order/customer ?e] '
    '              [?o :order/total ?total] '
    '              [(> ?total 1000)]) '
    '         (and [?ticket :ticket/customer ?e] '
    '              [?ticket :ticket/status :open]))]')
```

```javascript
// Find London users who either have a large order or an open support ticket.
const result = await conn.query('[:find ?e ' +
    ':where [?e :user/city "London"] ' +
    '       (or-join [?e] ' +
    '         (and [?o :order/customer ?e] ' +
    '              [?o :order/total ?total] ' +
    '              [(> ?total 1000)]) ' +
    '         (and [?ticket :ticket/customer ?e] ' +
    '              [?ticket :ticket/status :open]))]');
```

</div>

Here `?o`, `?total`, and `?ticket` are local to their branches. Only `?e` is
joined back to the outer query. A nested `q` version would ask a separate
question for each candidate user; `or-join` keeps the alternatives in one query
plan.


## 7. The `:find` Specification: Shaping Your Results

The `:find` clause determines in what shape your query should be returned. While
its basic use is to return a collection of values, it has several powerful
variations for aggregation and shaping data.

### 7.1 Find Specifications

By default, `:find` returns a **relation**: a set of tuples, where each tuple
contains the values named in the `:find` clause.

The word **set** is important. Even if two different internal rows produce the
same `:find` tuple, that tuple appears only once in the final result set.

<div class="multi-lang">

```clojure
;; Returns: #{["Alice" 30] ["Bob" 42]}
(d/q '[:find ?name ?age
       :where [?e :user/name ?name]
              [?e :user/age ?age]]
     db)
```

```java
Object result = conn.query("[:find ?name ?age " +
    ":where [?e :user/name ?name] " +
    "       [?e :user/age ?age]]");
```

```python
result = conn.query('[:find ?name ?age '
    ':where [?e :user/name ?name] '
    '       [?e :user/age ?age]]')
```

```javascript
const result = await conn.query('[:find ?name ?age ' +
    ':where [?e :user/name ?name] ' +
    '       [?e :user/age ?age]]');
```

</div>

Datalevin supports the standard Datalog find shapes:

| Shape | Syntax | Result shape |
| :--- | :--- | :--- |
| Relation | `:find ?name ?age` | A set of tuples, e.g. `#{["Alice" 30] ["Bob" 42]}`. |
| Collection | `:find [?name ...]` | A collection of scalar values, e.g. `["Alice" "Bob"]`. |
| Tuple | `:find [?name ?age]` | One tuple, e.g. `["Alice" 30]`. |
| Scalar | `:find ?name .` | One scalar value, e.g. `"Alice"`. |

Figure 8.3 shows the differences graphically.

![The four :find shapes applied to the same result set: relation selects all cells and returns a set of tuples, collection selects one column flattened into a vector, tuple selects one row, and scalar selects one cell](/images/diagrams/find-result-shapes.svg)

Use collection find when you only need one value from each result tuple:

<div class="multi-lang">

```clojure
(d/q '[:find [?name ...]
       :where [?e :user/name ?name]]
     db)
```

```java
Object names = conn.query("[:find [?name ...] " +
    ":where [?e :user/name ?name]]");
```

```python
names = conn.query('[:find [?name ...] '
    ':where [?e :user/name ?name]]')
```

```javascript
const names = await conn.query('[:find [?name ...] ' +
    ':where [?e :user/name ?name]]');
```

</div>

Use tuple or scalar find only when the query logically identifies one result,
for example through a unique attribute or an entity id:

<div class="multi-lang">

```clojure
;; One tuple
(d/q '[:find [?name ?email]
       :in $ ?user-id
       :where [?user-id :user/name ?name]
              [?user-id :user/email ?email]]
     db user-id)

;; One scalar
(d/q '[:find ?email .
       :in $ ?user-id
       :where [?user-id :user/email ?email]]
     db user-id)
```

```java
// One tuple
Object user = conn.query("[:find [?name ?email] " +
    ":in $ ?user-id " +
    ":where [?user-id :user/name ?name] " +
    "       [?user-id :user/email ?email]]",
    userId);

// One scalar
Object email = conn.query("[:find ?email . " +
    ":in $ ?user-id " +
    ":where [?user-id :user/email ?email]]",
    userId);
```

```python
# One tuple
user = conn.query('[:find [?name ?email] '
    ':in $ ?user-id '
    ':where [?user-id :user/name ?name] '
    '       [?user-id :user/email ?email]]',
    user_id)

# One scalar
email = conn.query('[:find ?email . '
    ':in $ ?user-id '
    ':where [?user-id :user/email ?email]]',
    user_id)
```

```javascript
// One tuple
const user = await conn.query('[:find [?name ?email] ' +
    ':in $ ?user-id ' +
    ':where [?user-id :user/name ?name] ' +
    '       [?user-id :user/email ?email]]',
    userId);

// One scalar
const email = await conn.query('[:find ?email . ' +
    ':in $ ?user-id ' +
    ':where [?user-id :user/email ?email]]',
    userId);
```

</div>

If more than one result matches a tuple or scalar find, Datalevin returns one
matching result. Do not rely on the one unless the query itself is unique.

For relation and tuple results, you can also ask Datalevin to return maps with
`:keys`, `:strs`, or `:syms`. The number of names must match the number of
`:find` elements, and return maps are not valid with collection or scalar find.

<div class="multi-lang">

```clojure
;; Returns: #{{:name "Alice" :age 30} {:name "Bob" :age 42}}
(d/q '[:find ?name ?age
       :keys name age
       :where [?e :user/name ?name]
              [?e :user/age ?age]]
     db)
```

```java
Object users = conn.query("[:find ?name ?age " +
    ":keys name age " +
    ":where [?e :user/name ?name] " +
    "       [?e :user/age ?age]]");
```

```python
users = conn.query('[:find ?name ?age '
    ':keys name age '
    ':where [?e :user/name ?name] '
    '       [?e :user/age ?age]]')
```

```javascript
const users = await conn.query('[:find ?name ?age ' +
    ':keys name age ' +
    ':where [?e :user/name ?name] ' +
    '       [?e :user/age ?age]]');
```

</div>

Use `:keys` for keyword keys, `:strs` for string keys, and `:syms` for symbol
keys.

### 7.2 Aggregation

Datalog supports aggregate functions directly in the `:find` clause. Aggregates
compute over the found result set and can produce summaries similar to SQL
`GROUP BY`. Grouping is determined by the non-aggregate variables that remain in
`:find`: `:find ?city (count ?e)` groups by `?city` and returns one count per
city. If `:find` contains only aggregate expressions, Datalevin treats the whole
result as one group.

<div class="multi-lang">

```clojure
;; Find the average age of all users
(d/q '[:find (avg ?age) . ; The dot returns a single scalar result
       :where [_ :user/age ?age]]
     db)

;; Find the count of users per city
(d/q '[:find ?city (count ?e)
       :where [?e :user/city ?city]]
     db)
```

```java
// Find the average age of all users
Object avgAge = conn.query("[:find (avg ?age) . " +
    ":where [_ :user/age ?age]]");

// Find the count of users per city
Object cityCounts = conn.query("[:find ?city (count ?e) " +
    ":where [?e :user/city ?city]]");
```

```python
# Find the average age of all users
avg_age = conn.query('[:find (avg ?age) . '
    ':where [_ :user/age ?age]]')

# Find the count of users per city
city_counts = conn.query('[:find ?city (count ?e) '
    ':where [?e :user/city ?city]]')
```

```javascript
// Find the average age of all users
const avgAge = await conn.query('[:find (avg ?age) . ' +
    ':where [_ :user/age ?age]]');

// Find the count of users per city
const cityCounts = await conn.query('[:find ?city (count ?e) ' +
    ':where [?e :user/city ?city]]');
```

</div>
Common aggregates include `sum`, `avg`, `count`, `min`, `max`, and `median`.
The function `vec` is also useful as an aggregate when you want to collect the
values in each group into a vector.

Arithmetic expressions over aggregate results can also appear in `:find`. The
expression is evaluated after grouping, so each aggregate operand sees the same
group it would see if it appeared as a separate `:find` column. In this query,
`?customer` is the grouping key, and the final column adds two aggregate results:

<div class="multi-lang">

```clojure
(d/q '[:find ?customer
              (sum ?subtotal)
              (sum ?tax)
              (+ (sum ?subtotal) (sum ?tax))
       :where [?order :order/customer ?customer]
              [?order :order/subtotal ?subtotal]
              [?order :order/tax ?tax]]
     db)
```

```java
Object totals = conn.query("[:find ?customer " +
    "(sum ?subtotal) (sum ?tax) " +
    "(+ (sum ?subtotal) (sum ?tax)) " +
    ":where [?order :order/customer ?customer] " +
    "       [?order :order/subtotal ?subtotal] " +
    "       [?order :order/tax ?tax]]");
```

```python
totals = conn.query('[:find ?customer '
    '(sum ?subtotal) (sum ?tax) '
    '(+ (sum ?subtotal) (sum ?tax)) '
    ':where [?order :order/customer ?customer] '
    '       [?order :order/subtotal ?subtotal] '
    '       [?order :order/tax ?tax]]')
```

```javascript
const totals = await conn.query('[:find ?customer ' +
    '(sum ?subtotal) (sum ?tax) ' +
    '(+ (sum ?subtotal) (sum ?tax)) ' +
    ':where [?order :order/customer ?customer] ' +
    '       [?order :order/subtotal ?subtotal] ' +
    '       [?order :order/tax ?tax]]');
```

</div>

Aggregate expressions support `+`, `-`, `*`, `/`, `mod`, `rem`, and `quot`.
Their arguments may be aggregate calls, constants, or nested aggregate
expressions, for example `(* 2 (+ (sum ?x) (sum ?y)))`.

## 8. Declarative Power: The Query Optimizer

Because Datalog is declarative, the **order of your `:where` clauses does not
matter**. Datalevin has a sophisticated **query optimizer** that analyzes your
clauses and automatically determines the most efficient execution plan.

The optimizer uses the order statistics from the underlying storage layer (see
Chapter 4) to make informed decisions. For example, if there are only 3 users in
"London" but 10,000 users named "Alice", it will almost certainly start by
finding the London users first, as it's a much smaller set to filter.

> **Key takeaway**: Do not try to manually optimize your queries by reordering
> `:where` clauses. State your constraints clearly and let the optimizer do its
> job.


## 9. Query Modifiers and Post-Processing

Beyond the core `where` clauses, Datalog provides additional clauses to refine,
filter, order, and paginate your query results.

### 9.1 `:with`: Preserving Rows for Aggregates

Datalog query results have set semantics. Duplicate result tuples collapse. The
`:with` clause exists for the cases where you need a variable to remain part of
the intermediate row identity even though it should not be returned.

This most often matters with aggregates. Suppose three orders have amounts `20`,
`20`, and `15`. If the query only finds `?amount`, the two `20` rows are
indistinguishable and collapse before `sum` sees them:

<div class="multi-lang">

```clojure
;; Wrong for revenue: duplicate amount values collapse.
(d/q '[:find (sum ?amount) .
       :where [?order :order/amount ?amount]]
     db)
;; => 35
```

```java
// Wrong for revenue: duplicate amount values collapse.
Object total = conn.query("[:find (sum ?amount) . " +
    ":where [?order :order/amount ?amount]]");
```

```python
# Wrong for revenue: duplicate amount values collapse.
total = conn.query('[:find (sum ?amount) . '
    ':where [?order :order/amount ?amount]]')
```

```javascript
// Wrong for revenue: duplicate amount values collapse.
const total = await conn.query('[:find (sum ?amount) . ' +
    ':where [?order :order/amount ?amount]]');
```

</div>

Add `:with ?order` to keep each order distinct while still returning only the
aggregate:

<div class="multi-lang">

```clojure
;; Correct: each order contributes its amount.
(d/q '[:find (sum ?amount) .
       :with ?order
       :where [?order :order/amount ?amount]]
     db)
;; => 55
```

```java
// Correct: each order contributes its amount.
Object total = conn.query("[:find (sum ?amount) . " +
    ":with ?order " +
    ":where [?order :order/amount ?amount]]");
```

```python
# Correct: each order contributes its amount.
total = conn.query('[:find (sum ?amount) . '
    ':with ?order '
    ':where [?order :order/amount ?amount]]')
```

```javascript
// Correct: each order contributes its amount.
const total = await conn.query('[:find (sum ?amount) . ' +
    ':with ?order ' +
    ':where [?order :order/amount ?amount]]');
```

</div>

`:with` does not make a hidden column in the returned result. It only changes
the set of variables that define distinct intermediate rows for aggregation.
For a non-aggregate query, adding `:with` often has no observable effect because
the final `:find` result is still a set.

### 9.2 `:having`: Filtering Aggregated Results

Similar to SQL's `HAVING` clause, `:having` allows you to filter results that
have been aggregated in the `:find` clause by applying additional conditions.

<div class="multi-lang">

```clojure
;; Find customers whose total spend and largest order both pass thresholds.
(d/q '[:find ?customer (sum ?total) (max ?total)
       :with ?order
       :where [?order :order/customer ?customer]
              [?order :order/total ?total]
       :having [(> (sum ?total) 1000)]
               [(>= (max ?total) 250)]]
     db)
```

```java
// Find customers whose total spend and largest order both pass thresholds.
Object result = conn.query("[:find ?customer (sum ?total) (max ?total) " +
    ":with ?order " +
    ":where [?order :order/customer ?customer] " +
    "       [?order :order/total ?total] " +
    ":having [(> (sum ?total) 1000)] " +
    "        [(>= (max ?total) 250)]]");
```

```python
# Find customers whose total spend and largest order both pass thresholds.
result = conn.query('[:find ?customer (sum ?total) (max ?total) '
    ':with ?order '
    ':where [?order :order/customer ?customer] '
    '       [?order :order/total ?total] '
    ':having [(> (sum ?total) 1000)] '
    '        [(>= (max ?total) 250)]]')
```

```javascript
// Find customers whose total spend and largest order both pass thresholds.
const result = await conn.query('[:find ?customer (sum ?total) (max ?total) ' +
    ':with ?order ' +
    ':where [?order :order/customer ?customer] ' +
    '       [?order :order/total ?total] ' +
    ':having [(> (sum ?total) 1000)] ' +
    '        [(>= (max ?total) 250)]]');
```

</div>

Multiple `:having` predicates are conjunctive: a group is returned only if all
of them are true. In the example above, `:with ?order` preserves each order as a
distinct contributing row, so repeated order totals are still included in the
sum.

### 9.3 `:order-by`: Sorting Results

The `:order-by` clause sorts your results based on specified variables. You can
specify ascending or descending order.

<div class="multi-lang">

```clojure
;; Find users, ordered by age descending, then name ascending
(d/q '[:find ?name ?age
       :where [?e :user/name ?name]
              [?e :user/age ?age]
       :order-by [?age :desc ?name :asc]]
     db)
```

```java
// Find users, ordered by age descending, then name ascending
Object result = conn.query("[:find ?name ?age " +
    ":where [?e :user/name ?name] " +
    "       [?e :user/age ?age] " +
    ":order-by [?age :desc ?name :asc]]");
```

```python
# Find users, ordered by age descending, then name ascending
result = conn.query('[:find ?name ?age '
    ':where [?e :user/name ?name] '
    '       [?e :user/age ?age] '
    ':order-by [?age :desc ?name :asc]]')
```

```javascript
// Find users, ordered by age descending, then name ascending
const result = await conn.query('[:find ?name ?age ' +
    ':where [?e :user/name ?name] ' +
    '       [?e :user/age ?age] ' +
    ':order-by [?age :desc ?name :asc]]');
```

</div>

When sorting by aggregate output, `:order-by` can also use zero-based result
column indices. This is useful because aggregate expressions are result
columns, not variables you can name later in `:order-by`:

<div class="multi-lang">

```clojure
;; Sort customers by total spend descending, then customer name ascending.
(d/q '[:find ?customer (sum ?total)
       :with ?order
       :where [?order :order/customer ?customer]
              [?order :order/total ?total]
       :order-by [1 :desc 0 :asc]]
     db)
```

```java
// Sort customers by total spend descending, then customer name ascending.
Object result = conn.query("[:find ?customer (sum ?total) " +
    ":with ?order " +
    ":where [?order :order/customer ?customer] " +
    "       [?order :order/total ?total] " +
    ":order-by [1 :desc 0 :asc]]");
```

```python
# Sort customers by total spend descending, then customer name ascending.
result = conn.query('[:find ?customer (sum ?total) '
    ':with ?order '
    ':where [?order :order/customer ?customer] '
    '       [?order :order/total ?total] '
    ':order-by [1 :desc 0 :asc]]')
```

```javascript
// Sort customers by total spend descending, then customer name ascending.
const result = await conn.query('[:find ?customer (sum ?total) ' +
    ':with ?order ' +
    ':where [?order :order/customer ?customer] ' +
    '       [?order :order/total ?total] ' +
    ':order-by [1 :desc 0 :asc]]');
```

</div>

Here column `0` is `?customer`, and column `1` is `(sum ?total)`. The
`:order-by` clause sorts by total spend first, then uses customer as a stable
tie-breaker.

### 9.4 `:limit` and `:offset`: Pagination

For pagination, you can use `:limit` to restrict the number of results and
`:offset` to skip a certain number of results.

<div class="multi-lang">

```clojure
;; Get the second page of users (limit 10, offset 10)
(d/q '[:find ?name ?age
       :where [?e :user/name ?name]
              [?e :user/age ?age]
       :order-by [?name :asc]
       :limit 10
       :offset 10]
     db)
```

```java
// Get the second page of users (limit 10, offset 10)
Object result = conn.query("[:find ?name ?age " +
    ":where [?e :user/name ?name] " +
    "       [?e :user/age ?age] " +
    ":order-by [?name :asc] " +
    ":limit 10 " +
    ":offset 10]");
```

```python
# Get the second page of users (limit 10, offset 10)
result = conn.query('[:find ?name ?age '
    ':where [?e :user/name ?name] '
    '       [?e :user/age ?age] '
    ':order-by [?name :asc] '
    ':limit 10 '
    ':offset 10]')
```

```javascript
// Get the second page of users (limit 10, offset 10)
const result = await conn.query('[:find ?name ?age ' +
    ':where [?e :user/name ?name] ' +
    '       [?e :user/age ?age] ' +
    ':order-by [?name :asc] ' +
    ':limit 10 ' +
    ':offset 10]');
```

</div>

### 9.5 Query Map Form for Programmatic Queries

Most examples in this book use the compact vector form:

```clojure
[:find ?name ?age
 :where [?e :user/name ?name]
        [?e :user/age ?age]
 :order-by [?name :asc]
 :limit 10]
```

Datalevin also accepts a map form. This is the engine's internal query
representation, and it is useful when application code needs to build or
parameterize query options such as `:limit`, `:offset`, or `:order-by`:

```clojure
(defn page-users
  [conn {:keys [offset limit]
         :or   {offset 0
                limit  50}}]
  (d/q {:find     '[?name ?age]
        :in       '[$]
        :where    '[[?e :user/name ?name]
                    [?e :user/age ?age]]
        :order-by '[?name :asc]
        :limit    limit
        :offset   offset}
       (d/db conn)))
```

In map form, the query clauses are ordinary data. Quote static query
fragments such as `:find`, `:in`, `:where`, and `:order-by`, but leave runtime
values such as `limit` and `offset` unquoted. This avoids string concatenation
and keeps pagination, sorting, and optional clauses easy to assemble with normal
data operations.

The same ordering rules apply in both forms: an `:order-by` variable must appear
in the query's result columns. When ordering by an aggregate result, use the
appropriate result-column index in `:order-by`, as shown earlier in this
section.


## 10. Flexible Data Sources: Beyond a Single Database

One of the most powerful aspects of Datalevin's Datalog engine is that queries
are not limited to a single database. The `:in` clause allows you to specify
multiple data sources, enabling cross-database joins and ad-hoc data analysis.
This is why the Clojure `d/q` API takes data sources rather than a connection:
queries run over data, while transactions run through connections.

### 10.1 Multiple Databases

You can pass multiple Datalevin databases to a single query, referenced by
different symbols in `:in`:

<div class="multi-lang">

```clojure
;; Join across two databases: a user db and an orders db
(d/q '[:find ?name ?order-total
       :in $users $orders ?min-total
       :where [$users ?e :user/name ?name]
              [$orders ?order :order/customer ?e]
              [$orders ?order :order/total ?order-total]
              [(> ?order-total ?min-total)]]
     (d/db users-conn) (d/db orders-conn) 100)
```

```java
// Join across two databases: a user db and an orders db.
Object result = usersConn.query(
    "[:find ?name ?order-total " +
    " :in $users $orders ?min-total " +
    " :where [$users ?e :user/name ?name] " +
    "        [$orders ?order :order/customer ?e] " +
    "        [$orders ?order :order/total ?order-total] " +
    "        [(> ?order-total ?min-total)]]",
    ordersConn,
    100);
```

```python
# Join across two databases: a user db and an orders db.
result = users_conn.query(
    '[:find ?name ?order-total '
    ' :in $users $orders ?min-total '
    ' :where [$users ?e :user/name ?name] '
    '        [$orders ?order :order/customer ?e] '
    '        [$orders ?order :order/total ?order-total] '
    '        [(> ?order-total ?min-total)]]',
    orders_conn,
    100)
```

```javascript
// Join across two databases: a user db and an orders db.
const result = await usersConn.query(
  '[:find ?name ?order-total ' +
  ' :in $users $orders ?min-total ' +
  ' :where [$users ?e :user/name ?name] ' +
  '        [$orders ?order :order/customer ?e] ' +
  '        [$orders ?order :order/total ?order-total] ' +
  '        [(> ?order-total ?min-total)]]',
  ordersConn,
  100
);
```

</div>

`$users` and `$orders` stand for two different database sources. The triple
patterns accept an extra position in front for a source symbol.

This is invaluable when:

- Keeping separate databases for different tenants or domains
- Performing migration or reconciliation between systems

### 10.2 EAV Sequences as Data Sources

Even more flexibly, the query engine accepts **any sequence of EAV tuples** as a
data source. If you can represent your data as `[entity attribute value]`
triples, you can query it with Datalog, no database required.

The examples below bind the tuple sequence as a named source, `$data`.

<div class="multi-lang">

```clojure
;; Query an in-memory collection of tuples
(def my-data
  [[1 :user/name "Alice"]
   [1 :user/age 30]
   [2 :user/name "Bob"]
   [2 :user/age 25]])

(d/q '[:find ?name
       :in $ ?min-age
       :where [$ ?e :user/name ?name]
              [$ ?e :user/age ?age]
              [(>= ?age ?min-age)]]
     my-data 28)
;; => #{["Alice"]}
```

```java
// Query an in-memory collection of tuples
List myData = List.of(
    List.of(1, Datalevin.kw("user/name"), "Alice"),
    List.of(1, Datalevin.kw("user/age"), 30),
    List.of(2, Datalevin.kw("user/name"), "Bob"),
    List.of(2, Datalevin.kw("user/age"), 25));

Object result = conn.query("[:find ?name " +
    ":in $ $data ?min-age " +
    ":where [$data ?e :user/name ?name] " +
    "       [$data ?e :user/age ?age] " +
    "       [(>= ?age ?min-age)]]",
    myData,
    28);
// => #{["Alice"]}
```

```python
# Query an in-memory collection of tuples
my_data = [
    [1, ":user/name", "Alice"],
    [1, ":user/age", 30],
    [2, ":user/name", "Bob"],
    [2, ":user/age", 25]]

result = conn.query('[:find ?name '
    ':in $ $data ?min-age '
    ':where [$data ?e :user/name ?name] '
    '       [$data ?e :user/age ?age] '
    '       [(>= ?age ?min-age)]]',
    my_data,
    28)
# => #{["Alice"]}
```

```javascript
// Query an in-memory collection of tuples
const myData = [
    [1, ':user/name', 'Alice'],
    [1, ':user/age', 30],
    [2, ':user/name', 'Bob'],
    [2, ':user/age', 25]];

const result = await conn.query('[:find ?name ' +
    ':in $ $data ?min-age ' +
    ':where [$data ?e :user/name ?name] ' +
    '       [$data ?e :user/age ?age] ' +
    '       [(>= ?age ?min-age)]]',
    myData,
    28);
// => #{["Alice"]}
```

</div>

This capability enables:

- **Ad-hoc analysis**: Query CSV files, JSON data, or API responses by
  transforming them into EAV tuples
- **Testing**: Write Datalog queries against fixture data without setting up a
  test database
- **Data transformation**: Use Datalog's pattern matching to reshape or filter
  in-memory collections
- **Prototyping**: Experiment with query logic on sample data before committing
  to a schema

The EAV model is not just a storage format, but also a universal data representation
that makes Datalevin's query engine applicable far beyond traditional database
use cases.


## Summary

Datalog queries start with simple facts: match an entity, match an attribute,
bind a value, and reuse variables when facts must agree. From there, the same
mental model scales to joins, pull expressions, predicates, aggregates,
alternatives, and multiple data sources. State the constraints clearly and let
Datalevin's optimizer choose the physical path to the data.

In the next chapter, we will explore **Rules and Recursion**, which allow you to
encapsulate this logic for reuse and traverse complex, hierarchical, or
graph-shaped data structures.

[^edb-idb]: Datalog literature often calls stored input facts the **EDB**
(extensional database). Relations derived by rules are the **IDB** (intensional
database). Chapter 9 uses those terms when it discusses rules and recursion.

[^query-terms]: In logic programming, making repeated variables agree is called
**unification**. In database language, a shared variable across clauses acts as a
join condition, and keeping only the requested `:find` values is a
**projection**.
