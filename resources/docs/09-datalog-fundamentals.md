---
title: "Datalog Fundamentals"
chapter: 9
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 9: Datalog Fundamentals

Datalevin's primary query language is Datalog. It is a powerful, **declarative**
language for finding complex patterns in your data. Unlike SQL, which often
requires you to think about *how* to get your data, Datalog lets you focus on
*what* data you want.

The flavor of Datalog used by Datalevin follows the Datomic-style query form
that Datomic pioneered and popularized in the Clojure ecosystem [1]. Instead of
writing Prolog-like rule syntax, you write queries as EDN data: vectors,
keywords, symbols, lists, and maps. This makes queries easier to compose, pass
through APIs, quote in Clojure, and read in non-Clojure client languages. The
surface syntax is more application-friendly, but the underlying concepts are
still the classic Datalog concepts: variables, unification, conjunction,
predicates, rules, and recursive derivation.

This chapter covers the fundamentals of writing Datalog queries, the role of the
query optimizer, and best practices for performance.

---

## 1. The Declarative Nature of Datalog

The most important concept to understand about Datalog is that it is
declarative. The `:where` clauses in a query define a *set of constraints*. You
are not providing a step-by-step procedure for the database to follow.

The Java, Python, and JavaScript snippets in this chapter assume an open
connection named `conn`. Calling `conn.query` supplies the connection's current
database as `$`; pass only additional `:in` values after the query string.

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

In this query, you are asking for "entities that have a name of 'Alice' AND a
city of 'London'". You are not telling the database to "first find people named
Alice, then filter them by city."

---

## 2. The `:find` Specification: Shaping Your Results

The `:find` clause determines in what shape your query should be returned. While
its basic use is to return a collection of values, it has several powerful
variations for aggregation and shaping data.

### 2.1 Find Specifications

By default, `:find` returns a **relation**: a set of tuples, where each tuple
contains the values named in the `:find` clause.

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

### 2.2 Aggregation

Datalog supports aggregate functions directly in the `:find` clause, similar to
SQL's `GROUP BY`.

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

Aggregate expressions can also appear in `:find`. For example, `(+ (sum ?x)
(sum ?y))` returns a value derived from two aggregate results. This is useful
when a query needs a computed metric rather than several separate aggregate
columns.

### 2.3 Pull Expressions in `:find`

Perhaps the most powerful feature of `:find` is that you can include a **Pull
expression** (see Chapter 8). This allows you to run a query to *find* a set of
entities and then immediately *pull* their data in a nested shape, all in one
operation.

<div class="multi-lang">

```clojure
(d/q '[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...]
       :in $ ?min-age
       :where [?e :user/age ?age]
              [(> ?age ?min-age)]]
     db 30)
```

```java
Object result = conn.query("[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...] " +
    ":in $ ?min-age " +
    ":where [?e :user/age ?age] " +
    "       [(> ?age ?min-age)]]",
    30);
```

```python
result = conn.query('[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...] '
    ':in $ ?min-age '
    ':where [?e :user/age ?age] '
    '       [(> ?age ?min-age)]]',
    30)
```

```javascript
const result = await conn.query('[:find [(pull ?e [:user/name {:user/orders [:order/id]}]) ...] ' +
    ':in $ ?min-age ' +
    ':where [?e :user/age ?age] ' +
    '       [(> ?age ?min-age)]]',
    30);
```

</div>

This query finds all users older than 30 and, for each one, pulls their name and
a nested list of their orders. The result is a collection of maps, ready to be
used by your application.

---

## 3. Declarative Power: The Query Optimizer

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

---

## 4. Predicates and Function Bindings

You can go beyond simple data patterns by using functions within your `:where`
clauses.

### 4.1 Predicate Clauses
A predicate is a clause that filters results based on a function that returns
`true` or `false`.

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

### 4.2 Binding Clauses

You can also use functions to compute and "bind" new variables.

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

### 4.3 Qualification of Functions and Predicates

-   **Built-in Functions/Predicates**: Common built-in functions and predicates
    (like `+`, `-`, `*`, `/`, `>`, `<`, `like`, `re-find`, `empty?`, `true?`) do
    **not** need to be fully qualified. They are implicitly available. Appendix
    C lists all built-in query and aggregate functions.

-   **Custom Functions**: If you define your own Clojure functions and want to
    use them in binding or predicate clauses, they **must** be fully qualified
    with their namespace.

    ```clojure
    ;; Example of a custom function that needs full qualification
    (ns my-app.queries
      (:require [datalevin.core :as d]))

    (defn is-adult? [age]
      (> age 18))

    (d/q '[:find ?name
           :where [?e :user/name ?name]
                  [?e :user/age ?age]
                  [(my-app.queries/is-adult? ?age)]] ; Fully qualified custom function
         db)
    ```

---

## 5. Query Modifiers and Post-Processing

Beyond the core `where` clauses, Datalog provides additional clauses to refine,
filter, order, and paginate your query results.

### 5.1 `:with`: Introducing Temporary Variables

The `:with` clause allows you to introduce variables that are bound in the
`:where` clause but are not part of the final `:find` result. This is useful for
intermediate computations or for creating more readable queries.

<div class="multi-lang">

```clojure
;; Find users who are active, but don't return the :user/active? attribute in the result
(d/q '[:find ?name
       :with ?is-active
       :where [?e :user/name ?name]
              [?e :user/active? ?is-active]
              [(true? ?is-active)]]
     db)
```

```java
// Find users who are active, but don't return the :user/active? attribute in the result
Object result = conn.query("[:find ?name " +
    ":with ?is-active " +
    ":where [?e :user/name ?name] " +
    "       [?e :user/active? ?is-active] " +
    "       [(true? ?is-active)]]");
```

```python
# Find users who are active, but don't return the :user/active? attribute in the result
result = conn.query('[:find ?name '
    ':with ?is-active '
    ':where [?e :user/name ?name] '
    '       [?e :user/active? ?is-active] '
    '       [(true? ?is-active)]]')
```

```javascript
// Find users who are active, but don't return the :user/active? attribute in the result
const result = await conn.query('[:find ?name ' +
    ':with ?is-active ' +
    ':where [?e :user/name ?name] ' +
    '       [?e :user/active? ?is-active] ' +
    '       [(true? ?is-active)]]');
```

</div>

### 5.2 `:having`: Filtering Aggregated Results

Similar to SQL's `HAVING` clause, `:having` allows you to filter results that
have been aggregated in the `:find` clause.

<div class="multi-lang">

```clojure
;; Find cities with more than 10 active users
(d/q '[:find ?city (count ?e)
       :where [?e :user/city ?city]
              [?e :user/active? true]
       :having [(> (count ?e) 10)]]
     db)
```

```java
// Find cities with more than 10 active users
Object result = conn.query("[:find ?city (count ?e) " +
    ":where [?e :user/city ?city] " +
    "       [?e :user/active? true] " +
    ":having [(> (count ?e) 10)]]");
```

```python
# Find cities with more than 10 active users
result = conn.query('[:find ?city (count ?e) '
    ':where [?e :user/city ?city] '
    '       [?e :user/active? true] '
    ':having [(> (count ?e) 10)]]')
```

```javascript
// Find cities with more than 10 active users
const result = await conn.query('[:find ?city (count ?e) ' +
    ':where [?e :user/city ?city] ' +
    '       [?e :user/active? true] ' +
    ':having [(> (count ?e) 10)]]');
```

</div>

### 5.3 `:order-by`: Sorting Results

The `:order-by` clause sorts your results based on specified variables. You can
specify ascending or descending order. When sorting by aggregate output,
`:order-by` can also use zero-based result column indices, such as `:order-by [1
:desc 0 :asc]`.

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

### 5.4 `:limit` and `:offset`: Pagination

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

---

## 6. Logical `or` and `not`

By default, all clauses are joined with an implicit `and`. You can use `or` and
`not` for more complex logic.

<div class="multi-lang">

```clojure
(d/q '[:find ?e
       :where (or [?e :user/status :pending]
                  [?e :user/status :flagged])]
     db)

(d/q '[:find ?e
       :where [?e :user/name ?name]
              (not [?e :user/is-admin? true])]
     db)
```

```java
Object result1 = conn.query("[:find ?e " +
    ":where (or [?e :user/status :pending] " +
    "           [?e :user/status :flagged])]");

Object result2 = conn.query("[:find ?e " +
    ":where [?e :user/name ?name] " +
    "       (not [?e :user/is-admin? true])]");
```

```python
result1 = conn.query('[:find ?e '
    ':where (or [?e :user/status :pending] '
    '           [?e :user/status :flagged])]')

result2 = conn.query('[:find ?e '
    ':where [?e :user/name ?name] '
    '       (not [?e :user/is-admin? true])]')
```

```javascript
const result1 = await conn.query('[:find ?e ' +
    ':where (or [?e :user/status :pending] ' +
    '           [?e :user/status :flagged])]');

const result2 = await conn.query('[:find ?e ' +
    ':where [?e :user/name ?name] ' +
    '       (not [?e :user/is-admin? true])]');
```

</div>

---

## 7. Subqueries: The Right Way and the Wrong Way

A common requirement is to filter a set of results based on another query.

### 7.1 The Performance Trap: Nested `q`

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

### 7.2 The Correct Way: `or-join` and `not-join`

Datalog provides `or-join` and `not-join` clauses for performing efficient
subqueries. These are integrated directly into the query optimizer.

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

Here, the `not-join` efficiently finds all users who do *not* appear as a
customer in any order, and joins that result with the set of London users. This
is vastly more performant than the nested `q` approach.

---

## 8. Flexible Data Sources: Beyond a Single Database

One of the most powerful aspects of Datalevin's Datalog engine is that queries
are not limited to a single database. The `:in` clause allows you to specify
multiple data sources, enabling cross-database joins and ad-hoc data analysis.

### 8.1 Multiple Databases

You can pass multiple Datalevin databases to a single query, referenced by
different symbols in `:in`:

```clojure
;; Join across two databases: a user db and an orders db
(d/q '[:find ?name ?order-total
       :in $users $orders ?min-total
       :where [$users ?e :user/name ?name]
              [$orders ?order :order/customer ?e]
              [$orders ?order :order/total ?order-total]
              [(> ?order-total ?min-total)]]
     user-db order-db 100)
```

The Clojure `d/q` API accepts each database source explicitly. The Java,
Python, and JavaScript `conn.query` convenience APIs automatically supply the
connection database as `$`; use lower-level interop when a non-Clojure program
needs to supply every database source explicitly.

This is invaluable when:

- Keeping separate databases for different tenants or domains
- Performing migration or reconciliation between systems

### 8.2 EAV Sequences as Data Sources

Even more flexibly, the query engine accepts **any sequence of EAV tuples** as a
data source. If you can represent your data as `[entity attribute value]`
triples, you can query it with Datalog, no database required.

In Java, Python, and JavaScript, `conn.query` already supplies `$`, so these
examples bind the tuple sequence as a named source, `$data`.

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

---

## Summary

Datalog's declarative nature frees you from thinking about the implementation
details of your query. By stating your constraints clearly and using built-in
constructs like `or-join` and `not-join` for subqueries, you can write
expressive, powerful, and highly performant queries, trusting that Datalevin's
optimizer will find the most efficient path to your data.

In the next chapter, we will explore **Rules and Recursion**, which allow you to
encapsulate this logic for reuse and traverse complex, hierarchical data
structures.

## References

[1] Datomic, [Query Reference](https://docs.datomic.com/query/query-data-reference.html),
Datomic documentation.
