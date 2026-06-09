---
title: "Query Planning, Optimization, and Diagnostics"
chapter: 21
part: "V — Performance and Operations"
---

# Chapter 21: Query Planning, Optimization, and Diagnostics

Datalevin has a novel query optimizer that leverages the unique strengths of
triple stores in facilitating cardinality estimation, one of the hardest
problems in databases design. With such, Datalevin is able to handle very
complex query and return results quickly.

A fast query depends on both the plan Datalevin chooses and the evidence you can
collect when that plan surprises you. This chapter combines optimizer mechanics
with the `explain` workflow used to diagnose real query behavior.

---

## 1. Query Planning and Optimization

As discussed in Chapter 9, Datalog is **declarative**. You describe *what* you
want, and Datalevin's **Query Optimizer** decides *how* to find it. This
decoupling is critical for performance because even a simple change in the
**order of joins** can make a query 1,000x faster or slower, even though the
results are the same.

This section explores how Datalevin's **Cost-Based Optimizer (CBO)** uses the
unique properties of DLMDB to create efficient execution plans.

Before a query touches storage, Datalevin rewrites it into a simpler execution
shape. Predicate pushdown, inequality conversion, constant parameter plugging,
and complex-clause dependency analysis all happen before join planning. The
optimizer then chooses access paths and join methods against that rewritten
query.

---

### 1. The Selinger-Style Optimizer

Datalevin uses a **Selinger-style cost-based query optimizer**, following the
System R tradition described by Selinger et al. [1], with dynamic programming,
similar to enterprise-grade relational databases like PostgreSQL, Oracle, and so
on.

#### 1.1 How it Works

When a query is submitted:

1.  **Parsing**: The engine breaks down the `:where` clauses into individual
    constraints.
2.  **Query Graph Simplification**: Star-like attributes (multiple attributes on
    the same entity) are handled via merge scan, reducing the graph to chains
    between stars.
3.  **Cardinality Estimation**: The engine uses DLMDB's order statistics to
    estimate how many results each clause will produce.
4.  **Join Planning**: It explores possible join orders using dynamic
    programming, generating **left-deep join trees**.
5.  **Dynamic Search Policy**: The planner starts with exhaustive search but
    switches to greedy after considering `P(n, 2)` plans, since only the first
    two joins have the most accurate size estimates.

---

### 2. Accurate Cardinality Estimation

The quality of a query plan depends on accurate cardinality estimates. Datalevin
excels here because counting is cheap in its nested triple storage. The
resulting join order is often similar to a hand-written join plan.

#### 2.1 Direct Counting

Some counts can be obtained in **O(1) time** directly from the index without
scanning. For example, `[?e :user/city "London"]` returns an exact count from
the AVE index.

For range queries, DLMDB's order statistics provide **O(log n)** counting.

#### 2.2 Query-Specific Sampling

For complex joins where counting isn't feasible, Datalevin uses **online
reservoir sampling** under actual query conditions. It:

1.  Collects sample entity IDs
2.  Performs merge scans to get selectivity ratios
3.  Uses empirical-Bayes shrinkage with priors
4.  Applies skew-aware upper-bound correction for extreme data distributions

#### 2.3 Directional Estimation

Unlike traditional RDBMS that assume attribute independence, Datalevin's
estimation is **directional**—different join directions produce different
estimates. This matters for `:ref` and `:_ref` joins which are inherently
directional.

---

### 3. Predicate Push-Down

Datalevin rewrites queries to push selection predicates down to index scans.

#### 3.1 Inequality Predicates

Comparison operators are converted to range boundaries in the index scan:
```clojure
[(> ?age 21)]  ;; Becomes a range scan on :user/age >= 21
```

#### 3.2 Constant Parameter Plugging

Query parameters are plugged directly into the query to avoid expensive joins
with bound values.

---

### 4. Merge Scan

For star-like queries (multiple attributes on the same entity), Datalevin uses
**merge scan**—a technique similar to pivot scan.

Instead of joining each attribute separately, a single index scan on the EAV
index retrieves all matching attributes at once. This is the **bulk of query
execution time** and provides massive speedup.

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

When multiple paths exist to reach the same node, Datalevin prefers the link
from the **most recently resolved** node, since recent data distribution is more
accurate.


---

### 6. Parallel Processing

Datalevin's query engine uses parallelism at multiple levels:

- **Planning**: Counting and sampling are parallelized
- **Execution**: **Pipelining** keeps multiple tuples in flight across execution
  steps, with each step processed by a dedicated thread

---

### 7. Complex Clauses and Rules

The optimizer handles complex clauses in stages:

1.  **Index access clauses** produce intermediate results first
2.  **Heuristics and variable dependencies** reorder remaining complex clauses
    (`and`, `or`, `not`, `not-join`, predicates, function bindings)
3.  **Rules** are executed last (see Chapter 22)

`or-join` participates in link planning, and common `not-join` shapes can become
anti-join steps when their join variables are bound in a single plan component.
More complex negative clauses still fall back to late resolution.

---

### 8. Benchmarks

Datalevin's query engine has been validated against industry benchmarks:

- **Join Order Benchmark (JOB)**: 113 complex SQL queries ported to Datalog.
  Datalevin averages **2X faster** than PostgreSQL with more consistent
  performance.
- **LDBC SNB**: Industry graph database benchmark. Datalevin is orders of
  magnitude faster on Short Interactive queries, often faster on Complex
  Interactive, than Neo4j.

---

### 9. Summary: The Optimizer's Principles

In summary, Datalevin has a sophisticated query optimizer that compiles
declarative query clauses into optimized execution steps that leverages index
scans as much as possible, utilizes cheap counts of the triple storage, and
generate plans similar to a hand written one. Users can trust the engine to find
the optimal path.

- **Order Doesn't Matter**: The physical order of `:where` clauses has no
  impact.
- **Indexes are Automatic**: Every attribute is indexed in AVE.
- **Real-Time Stats**: No "vacuum" needed—DLMDB provides instant counts.
- **Nested Storage Advantage**: The nested triple storage makes counting cheap,
  enabling accurate cardinality estimation that outperforms traditional RDBMS.

---

## 2. Inspecting Query Plans with `explain`

While Datalevin's query optimizer is highly intelligent, it is not omniscient.
Sometimes a query that "should be fast" takes longer than expected. To debug
these scenarios, you need to "look under the hood" of the execution plan.

Datalevin's public diagnostic API for Datalog queries is **`explain`**. By
default, `explain` plans the query without running it. When called with `{:run?
true}`, it executes the query as well and adds measured result-size and timing
information to the explain output.

---

### 1. Plan-Only Explain

Use plan-only `explain` when you want to inspect join order, access paths, and
estimated cardinalities without paying query execution cost.

<div class="multi-lang">

```clojure
(def query
  '[:find ?name
    :where [?e :user/name ?name]
           [?e :user/age ?age]
           [(> ?age 30)]])

(d/explain {} query @conn)
```

```java
String query =
    "[:find ?name " +
    " :where [?e :user/name ?name] " +
    "        [?e :user/age ?age] " +
    "        [(> ?age 30)]]";

Object plan = conn.explain(query);
```

```python
query = """[:find ?name
           :where [?e :user/name ?name]
                  [?e :user/age ?age]
                  [(> ?age 30)]]"""

plan = conn.explain(query)
```

```javascript
const query = `[:find ?name
               :where [?e :user/name ?name]
                      [?e :user/age ?age]
                      [(> ?age 30)]]`;

const plan = await conn.explain(query);
```

</div>

Plan-only output includes:
- **`:query-graph`**: The optimizer's graph of clauses and estimated counts
- **`:plan`**: Planned steps, access paths, join methods, estimated `:cost`, and
  estimated `:size`
- **`:opt-clauses` / `:late-clauses`**: Clauses handled by the optimizer versus
  clauses processed after the optimized plan
- **Planning times**: `:parsing-time`, `:building-time`, `:planning-time`, and
  `:prepare-time`

Use plan-only explain to verify that a query is using the expected indexes and
join strategies before running it on large data.

---

### 2. Explain with Execution Measurements

Use `{:run? true}` when you also need measured execution information. This runs
the query and augments the explain map with fields such as `:execution-time`,
`:actual-result-size`, `:result`, and per-plan `:actual-size` where available.

<div class="multi-lang">

```clojure
(d/explain {:run? true} query @conn)
```

```java
import java.util.List;

Object measuredPlan = conn.explain("{:run? true}", query, List.of());
```

```python
measured_plan = conn.explain(query, opts_edn="{:run? true}")
```

```javascript
const measuredPlan = await conn.explain(query, {
  optsEdn: '{:run? true}'
});
```

</div>

`{:run? true}` is the runtime diagnostics mode of `explain`. It does not produce
a separate clause-by-clause trace, but it lets you compare estimated sizes
against actual sizes and distinguish planning cost from runtime cost.

---

### 3. Reading the Explain Output

Start with the `:plan` section. Each planned component contains `:steps`,
estimated `:cost`, and estimated `:size`; when `{:run? true}` is used, actual
sizes are included where the engine can report them.

Key fields to inspect:
- **Join order**: The order of the planned `:steps`
- **Join methods**: The step descriptions chosen by the optimizer
- **Estimated cardinalities**: `:count` in `:query-graph` and `:size` in plans
- **Actual cardinalities**: `:actual-size` and `:actual-result-size` when
  `{:run? true}` is used
- **Planning and execution time**: `:prepare-time` versus `:execution-time`

#### 3.1 Identifying Bottlenecks

A common performance bottleneck is a **"Large Intermediate Result Set."**
If the first join returns 1,000,000 entities, but the second join filters them
down to 10, the engine still had to process 1,000,000 records.

**The Fix**: Can you express a more selective indexed constraint in the query?
The optimizer tries to do this automatically, but a complex predicate or rule may
hide the selective condition from estimation.

---

### 4. Diagnosing Slow Predicates

If your query uses custom functions (Chapter 9), Datalevin's optimizer treats
them as black boxes with unknown selectivity.

`explain` can show where the predicate lands in the plan, but it does not time
each predicate call separately. If a query becomes slow only after adding a
predicate such as `[(my-ns/is-complex? ?x)]`, isolate that predicate by comparing
`explain` output and query runtime before and after adding it.

**Optimization Tip**: Prefer indexed constraints that reduce the candidate set
before expensive custom predicates run.

---

### 5. Understanding Join Orders

The `:plan` section shows the **join order** chosen by the optimizer.

1.  **Leading Clause**: This is the first clause the engine used to find the initial set of entities.
2.  **Sequential Joins**: The order in which subsequent clauses were applied.

If you see that the engine is starting with a non-selective clause (e.g.,
searching for `:user/active? true`), it might be because the selective filter you
expect is hidden inside a complex predicate or rule.

---

### 6. Summary: The Explain Workflow

When a query is slow, follow this workflow:

1.  **Use plan-only `explain`**: Inspect join methods, index usage, and estimated
    cardinalities.
2.  **Use `explain` with `{:run? true}`**: Compare estimates with actual result
    sizes and total execution time.
3.  **Check cardinality gaps**: Large gaps between estimated and actual sizes are
    a strong hint that a predicate, rule, or data distribution is hiding
    selectivity from the optimizer.
4.  **Simplify and isolate**: Remove clauses one by one to find the specific part
    of the query that is slow.
5.  **Refine the logic**: Use more selective indexed attributes before expensive
    custom predicates.

By using `explain` well, you gain the transparency needed to understand how
Datalevin plans a query and why a query may be slower than expected.

---

## References

[1] Patricia G. Selinger, Morton M. Astrahan, Donald D. Chamberlin,
   Raymond A. Lorie, and Thomas G. Price,
   [Access Path Selection in a Relational Database Management System](https://research.ibm.com/publications/access-path-selection-in-a-relational-database-management-system),
   SIGMOD 1979, pp. 23-34,
   [doi:10.1145/582095.582099](https://doi.org/10.1145/582095.582099).
