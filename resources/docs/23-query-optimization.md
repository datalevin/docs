---
title: "Query Planning and Optimization"
chapter: 23
part: "V — Performance and Dataflow"
---

# Chapter 23: Query Planning and Optimization

As discussed in Chapter 9, Datalog is **declarative**. You describe *what* you want, and Datalevin's **Query Optimizer** decides *how* to find it. This decoupling is critical for performance because even a simple change in the order of joins can make a query 1,000x faster or slower.

This chapter explores how Datalevin's **Cost-Based Optimizer (CBO)** uses the unique properties of DLMDB to create efficient execution plans.

---

## 1. The Selinger-Style Optimizer

Datalevin uses a **Selinger-style cost-based query optimizer** with dynamic
programming, similar to enterprise-grade relational databases like PostgreSQL,
Oracle, and so on.

### 1.1 How it Works
When a query is submitted:
1.  **Parsing**: The engine breaks down the `:where` clauses into individual constraints.
2.  **Query Graph Simplification**: Star-like attributes (multiple attributes on the same entity) are handled via merge scan, reducing the graph to chains between stars.
3.  **Cardinality Estimation**: The engine uses DLMDB's order statistics to estimate how many results each clause will produce.
4.  **Join Planning**: It explores possible join orders using dynamic programming, generating **left-deep join trees**.
5.  **Dynamic Search Policy**: The planner starts with exhaustive search but
    switches to greedy after considering `P(n, 2)` plans, since only the first
    two joins have the most accurate size estimates.

---

## 2. Accurate Cardinality Estimation

The quality of a query plan depends on accurate cardinality estimates. Datalevin excels here because counting is cheap in its nested triple storage.

### 2.1 Direct Counting
Some counts can be obtained in **O(1) time** directly from the index without scanning. For example, `[?e :user/city "London"]` returns an exact count from the AVE index.

For range queries, DLMDB's order statistics provide **O(log n)** counting.

### 2.2 Query-Specific Sampling
For complex joins where counting isn't feasible, Datalevin uses **online reservoir sampling** under actual query conditions. It:
1.  Collects sample entity IDs
2.  Performs merge scans to get selectivity ratios
3.  Uses empirical-Bayes shrinkage with priors
4.  Applies skew-aware upper-bound correction for extreme data distributions

### 2.3 Directional Estimation
Unlike traditional RDBMS that assume attribute independence, Datalevin's estimation is **directional**—different join directions produce different estimates. This matters for `:ref` and `:_ref` joins which are inherently directional.

---

## 3. Predicate Push-Down

Datalevin rewrites queries to push selection predicates down to index scans.

### 3.1 Inequality Predicates
Comparison operators are converted to range boundaries in the index scan:
```clojure
[(> ?age 21)]  ;; Becomes a range scan on :user/age >= 21
```

### 3.2 Constant Parameter Plugging
Query parameters are plugged directly into the query to avoid expensive joins with bound values.

---

## 4. Merge Scan

For star-like queries (multiple attributes on the same entity), Datalevin uses **merge scan**—a technique similar to pivot scan.

Instead of joining each attribute separately, a single index scan on the EAV index retrieves all matching attributes at once. This is the **bulk of query execution time** and provides massive speedup.

---

## 5. Join Methods

Datalevin considers five join methods and picks the best based on cost estimation:

| Method | Use Case |
|--------|----------|
| **Forward ref** `:ref` | `[?e :user/friend ?f]` — merge scans ref values |
| **Reverse ref** `:_ref` | `[?f :user/_friend ?e]` — scans AVE then retrieves entities |
| **Value equality** `:val-eq` | When variables unify via attribute values |
| **Hash join** `:hash-join` | Large non-selective joins, chooses build/probe side adaptively |
| **Or-join** `:or-join` | Handles `or-join` clauses with sideway information passing (SIP) |

### 5.1 Recency-Based Link Choice
When multiple paths exist to reach the same node, Datalevin prefers the link from the **most recently resolved** node, since recent data distribution is more accurate.

---

## 6. Parallel Processing

Datalevin's query engine uses parallelism at multiple levels:

- **Planning**: Counting and sampling are parallelized
- **Execution**: **Pipelining** keeps multiple tuples in flight across execution steps, with each step processed by a dedicated thread

---

## 7. Complex Clauses and Rules

The optimizer handles complex clauses in stages:

1.  **Index access clauses** produce intermediate results first
2.  **Heuristics and variable dependencies** reorder remaining complex clauses (`and`, `or`, `not`, `not-join`, predicates, function bindings)
3.  **Rules** are executed last (see Chapter 25)

Currently, `or-join` is optimized; other complex clauses use heuristics.

---

## 8. Benchmarks

Datalevin's query engine has been validated against industry benchmarks:

- **Join Order Benchmark (JOB)**: 113 complex SQL queries ported to Datalog. Datalevin averages **2X faster** than PostgreSQL with more consistent performance.
- **LDBC SNB**: Industry graph database benchmark. Datalevin is orders of magnitude faster on Short Interactive queries, often faster on Complex Interactive.

---

## 9. Summary: The Optimizer's Principles

Datalevin's optimizer is designed to be **unobtrusive**. Trust the engine to find the optimal path.

- **Order Doesn't Matter**: The physical order of `:where` clauses has no impact.
- **Indexes are Automatic**: Every attribute is indexed in AVE.
- **Real-Time Stats**: No "vacuum" needed—DLMDB provides instant counts.
- **Nested Storage Advantage**: The nested triple storage makes counting cheap, enabling accurate cardinality estimation that outperforms traditional RDBMS.
