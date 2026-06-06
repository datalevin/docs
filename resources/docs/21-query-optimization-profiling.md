---
title: "Query Planning, Optimization, and Profiling"
chapter: 21
part: "V — Performance and Operations"
---

# Chapter 21: Query Planning, Optimization, and Profiling

A fast query depends on both the plan Datalevin chooses and the evidence you can collect when that plan surprises you. This chapter combines optimizer mechanics with the profiling workflow used to diagnose real query behavior.

---

## 1. Query Planning and Optimization

As discussed in Chapter 9, Datalog is **declarative**. You describe *what* you want, and Datalevin's **Query Optimizer** decides *how* to find it. This decoupling is critical for performance because even a simple change in the order of joins can make a query 1,000x faster or slower.

This section explores how Datalevin's **Cost-Based Optimizer (CBO)** uses the unique properties of DLMDB to create efficient execution plans.

Before a query touches storage, Datalevin rewrites it into a simpler execution
shape. Predicate pushdown, inequality conversion, constant parameter plugging,
and complex-clause dependency analysis all happen before join planning. The
optimizer then chooses access paths and join methods against that rewritten
query.

---

### 1. The Selinger-Style Optimizer

Datalevin uses a **Selinger-style cost-based query optimizer** with dynamic
programming, similar to enterprise-grade relational databases like PostgreSQL,
Oracle, and so on.

#### 1.1 How it Works
When a query is submitted:
1.  **Parsing**: The engine breaks down the `:where` clauses into individual constraints.
2.  **Query Graph Simplification**: Star-like attributes (multiple attributes on the same entity) are handled via merge scan, reducing the graph to chains between stars.
3.  **Cardinality Estimation**: The engine uses DLMDB's order statistics to estimate how many results each clause will produce.
4.  **Join Planning**: It explores possible join orders using dynamic programming, generating **left-deep join trees**.
5.  **Dynamic Search Policy**: The planner starts with exhaustive search but
    switches to greedy after considering `P(n, 2)` plans, since only the first
    two joins have the most accurate size estimates.

---

### 2. Accurate Cardinality Estimation

The quality of a query plan depends on accurate cardinality estimates. Datalevin excels here because counting is cheap in its nested triple storage.

#### 2.1 Direct Counting
Some counts can be obtained in **O(1) time** directly from the index without scanning. For example, `[?e :user/city "London"]` returns an exact count from the AVE index.

For range queries, DLMDB's order statistics provide **O(log n)** counting.

#### 2.2 Query-Specific Sampling
For complex joins where counting isn't feasible, Datalevin uses **online reservoir sampling** under actual query conditions. It:
1.  Collects sample entity IDs
2.  Performs merge scans to get selectivity ratios
3.  Uses empirical-Bayes shrinkage with priors
4.  Applies skew-aware upper-bound correction for extreme data distributions

#### 2.3 Directional Estimation
Unlike traditional RDBMS that assume attribute independence, Datalevin's estimation is **directional**—different join directions produce different estimates. This matters for `:ref` and `:_ref` joins which are inherently directional.

---

### 3. Predicate Push-Down

Datalevin rewrites queries to push selection predicates down to index scans.

#### 3.1 Inequality Predicates
Comparison operators are converted to range boundaries in the index scan:
```clojure
[(> ?age 21)]  ;; Becomes a range scan on :user/age >= 21
```

#### 3.2 Constant Parameter Plugging
Query parameters are plugged directly into the query to avoid expensive joins with bound values.

---

### 4. Merge Scan

For star-like queries (multiple attributes on the same entity), Datalevin uses **merge scan**—a technique similar to pivot scan.

Instead of joining each attribute separately, a single index scan on the EAV index retrieves all matching attributes at once. This is the **bulk of query execution time** and provides massive speedup.

---

### 5. Join Methods

Datalevin considers five join methods and picks the best based on cost estimation:

| Method | Use Case |
|--------|----------|
| **Forward ref** `:ref` | `[?e :user/friend ?f]` — merge scans ref values |
| **Reverse ref** `:_ref` | `[?f :user/_friend ?e]` — scans AVE then retrieves entities |
| **Value equality** `:val-eq` | When variables unify via attribute values |
| **Hash join** `:hash-join` | Large non-selective joins, chooses build/probe side adaptively |
| **Or-join** `:or-join` | Handles `or-join` clauses with sideway information passing (SIP) |
| **Not-join** `:not-join` | Optimizes conservative anti-join shapes instead of always deferring negative filters |

#### 5.1 Recency-Based Link Choice
When multiple paths exist to reach the same node, Datalevin prefers the link from the **most recently resolved** node, since recent data distribution is more accurate.

---

### 6. Parallel Processing

Datalevin's query engine uses parallelism at multiple levels:

- **Planning**: Counting and sampling are parallelized
- **Execution**: **Pipelining** keeps multiple tuples in flight across execution steps, with each step processed by a dedicated thread

---

### 7. Complex Clauses and Rules

The optimizer handles complex clauses in stages:

1.  **Index access clauses** produce intermediate results first
2.  **Heuristics and variable dependencies** reorder remaining complex clauses (`and`, `or`, `not`, `not-join`, predicates, function bindings)
3.  **Rules** are executed last (see Chapter 22)

`or-join` participates in link planning, and common `not-join` shapes can become anti-join steps when their join variables are bound in a single plan component. More complex negative clauses still fall back to late resolution.

---

### 8. Benchmarks

Datalevin's query engine has been validated against industry benchmarks:

- **Join Order Benchmark (JOB)**: 113 complex SQL queries ported to Datalog. Datalevin averages **2X faster** than PostgreSQL with more consistent performance.
- **LDBC SNB**: Industry graph database benchmark. Datalevin is orders of magnitude faster on Short Interactive queries, often faster on Complex Interactive.

---

### 9. Summary: The Optimizer's Principles

Datalevin's optimizer is designed to be **unobtrusive**. Trust the engine to find the optimal path.

- **Order Doesn't Matter**: The physical order of `:where` clauses has no impact.
- **Indexes are Automatic**: Every attribute is indexed in AVE.
- **Real-Time Stats**: No "vacuum" needed—DLMDB provides instant counts.
- **Nested Storage Advantage**: The nested triple storage makes counting cheap, enabling accurate cardinality estimation that outperforms traditional RDBMS.

---

## 2. Profiling and Execution Traces

While Datalevin's query optimizer (Chapter 21) is highly intelligent, it is not omniscient. Sometimes a query that "should be fast" takes longer than expected. To debug these scenarios, you need to "look under the hood" of the execution plan.

This section covers how to **Profile** your Datalog queries and understand the performance of each join and filter in your execution path.

---

### 1. The Profiling API: `trace`

Datalevin provides a specialized profiling mechanism called **`trace`**. By adding a `:trace true` modifier to your query, the engine will return not just the results, but a detailed **Execution Trace**.

<div class="multi-lang">

```clojure
(d/q '[:find ?name
       :where [?e :user/name ?name]
              [?e :user/age ?age]
              [(> ?age 30)]
       :trace true] ; Enable profiling
     db)
```

```java
Collection results = Datalevin.q(
    "[:find ?name " +
    " :where [?e :user/name ?name] " +
    "        [?e :user/age ?age] " +
    "        [(> ?age 30)] " +
    " :trace true]",
    db);
```

```python
results = d.q(
    """[:find ?name
        :where [?e :user/name ?name]
               [?e :user/age ?age]
               [(> ?age 30)]
        :trace true]""",
    db)
```

```javascript
const results = d.q(
  `[:find ?name
    :where [?e :user/name ?name]
           [?e :user/age ?age]
           [(> ?age 30)]
    :trace true]`,
  db
);
```

</div>

The trace output includes the **Cost**, **Cardinality**, and **Execution Time** for every single clause in the query.

---

### 2. Understanding Query Plans with `d/explain`

Datalevin provides the **`d/explain`** function to inspect the query plan without executing the query. This helps you understand how the optimizer will process your query.

<div class="multi-lang">

```clojure
(d/explain db
  '[:find ?name
    :where [?e :user/name ?name]
           [?e :user/age ?age]
           [(> ?age 30)]])
```

```java
Object plan = Datalevin.explain(db,
    "[:find ?name " +
    " :where [?e :user/name ?name] " +
    "        [?e :user/age ?age] " +
    "        [(> ?age 30)]]");
```

```python
plan = d.explain(
    db,
    """[:find ?name
        :where [?e :user/name ?name]
               [?e :user/age ?age]
               [(> ?age 30)]]""")
```

```javascript
const plan = d.explain(
  db,
  `[:find ?name
    :where [?e :user/name ?name]
           [?e :user/age ?age]
           [(> ?age 30)]]`
);
```

</div>

The output shows:
- **Join order**: The sequence of clauses the optimizer chose
- **Join methods**: Which method (forward ref, reverse ref, hash join, etc.) is used for each join
- **Index scans**: What indexes are used for each clause
- **Estimated cardinalities**: The optimizer's predicted result sizes
- **Planning breakdown**: Recent explain output includes parsing and query-graph building time, which helps distinguish query compilation overhead from execution cost.

Use `explain` to verify that your query is using the expected indexes and join strategies before running it with real data.

---

### 3. Reading the Execution Trace

A trace is a nested data structure that describes how the engine "walked" through your query.

#### 3.1 Key Metrics to Watch
- **Index Scans**: How many datoms were read from the B+Tree for each clause?
- **Joins**: How many entities were matched at each step?
- **Filters**: How many results were discarded by predicates like `(> ?age 30)`?
- **Execution Time**: Which specific clause consumed the most CPU time?

#### 3.2 Identifying Bottlenecks
A common performance bottleneck is a **"Large Intermediate Result Set."**
If the first join returns 1,000,000 entities, but the second join filters them down to 10, the engine still had to process 1,000,000 records.

**The Fix**: Can you add a more selective filter earlier in the query? The optimizer tries to do this automatically, but sometimes a complex predicate (like a custom Clojure function) cannot be accurately estimated.

---

### 4. Profiling Slow Predicates

If your query uses custom functions (Chapter 9), Datalevin's optimizer treats them as "black boxes" with an unknown cost.

If a trace shows that a predicate clause like `[(my-ns/is-complex? ?x)]` is taking a long time, it means that function is being called for **every single tuple** in the result set at that point.

**Optimization Tip**: Try to move your custom predicates as late as possible in the query (after the most selective filters have already reduced the number of tuples).

---

### 5. Understanding Join Orders

The trace will show you the **Join Order** chosen by the optimizer.

1.  **Leading Clause**: This is the first clause the engine used to find the initial set of entities.
2.  **Sequential Joins**: The order in which subsequent clauses were applied.

If you see that the engine is starting with a non-selective clause (e.g., searching for `:user/active? true`), it might be because the optimizer's statistics are out of date or the selective filter you expect is hidden inside a complex rule.

---

### 6. Summary: The Profiling Workflow

When a query is slow, follow this workflow:

1.  **Use `d/explain`**: Inspect the query plan to verify join methods and index usage
2.  **Enable `:trace true`**: Get the raw execution data.
3.  **Find the "Heavy" Clause**: Look for the clause with the highest execution time or the largest number of index scans.
4.  **Check Cardinalities**: Are the actual number of results much higher than what you (or the optimizer) expected?
5.  **Simplify and Isolate**: Remove clauses one by one to find the specific part of the query that is slow.
6.  **Refine the Logic**: Use more selective attributes or move slow custom predicates to the end of the `:where` block.

By mastering the profiling API, you gain the transparency needed to ensure that every query in your Datalevin database is running at peak performance.
