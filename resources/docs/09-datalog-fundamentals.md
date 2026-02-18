---
title: "Datalog Fundamentals"
chapter: 9
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 9: Datalog Fundamentals

Datalevin's primary query language is Datalog. It is a powerful, **declarative** language for finding complex patterns in your data. Unlike SQL, which often requires you to think about *how* to get your data, Datalog lets you focus on *what* data you want.

This chapter covers the fundamentals of writing Datalog queries, the role of the query optimizer, and best practices for performance.

---

## 1. The Declarative Nature of Datalog

The most important concept to understand about Datalog is that it is declarative. The `:where` clauses in a query define a *set of constraints* or a *pattern* to match. You are not providing a step-by-step procedure for the database to follow.

```clojure
(d/q '[:find ?e
       :where [?e :user/name "Alice"]
              [?e :user/city "London"]]
     db)
```

In this query, you are asking for "entities that have a name of 'Alice' AND a city of 'London'". You are not telling the database to "first find people named Alice, then filter them by city."

---

## 2. The `:find` Specification: Shaping Your Results

The `:find` clause determines what your query will return. While its basic use is to return a collection of values, it has several powerful variations for aggregation and shaping data.

### 2.1 Find Specifications
By default, `:find` returns a **collection of tuples**.

```clojure
;; Returns: #{["Alice" 30] ["Bob" 42]}
(d/q '[:find ?name ?age :where ...])
```

You can change this with a `.` at the end of the spec:
- **Single Tuple**: `:find [?name ?age] .` returns the first tuple found, e.g., `["Alice" 30]`.
- **Single Scalar**: `:find ?name .` returns the first value of the first tuple, e.g., `"Alice"`.

### 2.2 Aggregation
Datalog supports aggregate functions directly in the `:find` clause, similar to SQL's `GROUP BY`.

```clojure
;; Find the average age of all users
(d/q '[:find (avg ?age) . ; The dot returns a single scalar result
       :where [_ :user/age ?age]])

;; Find the count of users per city
(d/q '[:find ?city (count ?e)
       :where [?e :user/city ?city]])
```
Common aggregates include `sum`, `avg`, `count`, `min`, `max`, and `median`.

### 2.3 Pull Expressions in `:find`
Perhaps the most powerful feature of `:find` is that you can include a **Pull expression** (see Chapter 8). This allows you to run a query to *find* a set of entities and then immediately *pull* their data in a nested shape, all in one operation.

```clojure
(d/q '[:find (pull ?e [:user/name {:user/orders [:order/id]}])
       :in $ ?min-age
       :where [?e :user/age ?age]
              [(> ?age ?min-age)]]
     db 30)
```
This query finds all users older than 30 and, for each one, pulls their name and a nested list of their orders. The result is a collection of maps, ready to be used by your application.

---

## 3. Declarative Power: The Query Optimizer

Because Datalog is declarative, the **order of your `:where` clauses does not matter**. Datalevin has a sophisticated **query optimizer** that analyzes your clauses and automatically determines the most efficient execution plan.

The optimizer uses the order statistics from the underlying storage layer (see Chapter 4) to make informed decisions. For example, if there are only 3 users in "London" but 10,000 users named "Alice", it will almost certainly start by finding the London users first, as it's a much smaller set to filter.

> **Key takeaway**: Do not try to manually optimize your queries by reordering `:where` clauses. State your constraints clearly and let the optimizer do its job.

---

## 4. Predicates and Function Bindings

You can go beyond simple data patterns by using functions within your `:where` clauses.

### 4.1 Predicate Clauses
A predicate is a clause that filters results based on a function that returns `true` or `false`.

```clojure
;; Find users older than 30
(d/q '[:find ?name ?age
       :where [?e :user/name ?name]
              [?e :user/age ?age]
              [(> ?age 30)]]
     db)
```

### 4.2 Binding Clauses
You can also use functions to compute and "bind" new variables.

```clojure
;; Extract the year from a registration date
(d/q '[:find ?name ?year
       :where [?e :user/name ?name]
              [?e :user/registered-at ?date]
              [(get-year ?date) ?year]] ; Binds the result of get-year to ?year
     db)
```

### 4.3 Qualification of Functions and Predicates

-   **Built-in Functions/Predicates**: Common built-in functions and predicates (like `+`, `-`, `*`, `/`, `>`, `<`, `str/starts-with?`, `empty?`, `true?`) do **not** need to be fully qualified. They are implicitly available.

-   **Custom Functions**: If you define your own Clojure functions and want to use them in binding or predicate clauses, they **must** be fully qualified with their namespace.

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

Beyond the core `where` clauses, Datalog provides additional clauses to refine, filter, order, and paginate your query results.

### 5.1 `:with`: Introducing Temporary Variables
The `:with` clause allows you to introduce variables that are bound in the `:where` clause but are not part of the final `:find` result. This is useful for intermediate computations or for creating more readable queries.

```clojure
;; Find users who are active, but don't return the :user/active? attribute in the result
(d/q '[:find ?name
       :with ?is-active
       :where [?e :user/name ?name]
              [?e :user/active? ?is-active]
              [(true? ?is-active)]]
     db)
```

### 5.2 `:having`: Filtering Aggregated Results
Similar to SQL's `HAVING` clause, `:having` allows you to filter results that have been aggregated in the `:find` clause.

```clojure
;; Find cities with more than 10 active users
(d/q '[:find ?city (count ?e)
       :where [?e :user/city ?city]
              [?e :user/active? true]
       :having (> (count ?e) 10)]
     db)
```

### 5.3 `:order-by`: Sorting Results
The `:order-by` clause sorts your results based on specified variables. You can specify ascending or descending order.

```clojure
;; Find users, ordered by age descending, then name ascending
(d/q '[:find ?name ?age
       :where [?e :user/name ?name]
              [?e :user/age ?age]
       :order-by [?age :desc] [?name :asc]]
     db)
```

### 5.4 `:limit` and `:offset`: Pagination
For pagination, you can use `:limit` to restrict the number of results and `:offset` to skip a certain number of results.

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

---

## 6. Logical `or` and `not`

By default, all clauses are joined with an implicit `and`. You can use `or` and `not` for more complex logic.

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

---

## 7. Subqueries: The Right Way and the Wrong Way

A common requirement is to filter a set of results based on another query. 

### 5.1 The Performance Trap: Nested `q`
It is technically possible to nest a `d/q` call inside a predicate. **You should almost never do this.**

```clojure
;; ANTI-PATTERN: VERY SLOW!
(d/q '[:find ?e
       :where [?e :user/city "London"]
              [(empty? (d/q '[:find ?o
                               :in $ ?e
                               :where [?o :order/customer ?e]]
                             $ ?e))]] ; This subquery runs for EVERY London user
     db)
```
The inner query is executed once for *every single tuple* (in this case, every London user) found by the outer query. This is extremely inefficient.

### 5.2 The Correct Way: `or-join` and `not-join`
Datalog provides `or-join` and `not-join` clauses for performing efficient subqueries. These are integrated directly into the query optimizer.

```clojure
;; Find users in London with no orders
(d/q '[:find ?e
       :where [?e :user/city "London"]
              (not-join [?e]
                [?o :order/customer ?e])]
     db)
```
Here, the `not-join` efficiently finds all users who do *not* appear as a customer in any order, and joins that result with the set of London users. This is vastly more performant than the nested `q` approach.

---

## Summary

Datalog's declarative nature frees you from thinking about the implementation details of your query. By stating your constraints clearly and using built-in constructs like `or-join` and `not-join` for subqueries, you can write expressive, powerful, and highly performant queries, trusting that Datalevin's optimizer will find the most efficient path to your data.
