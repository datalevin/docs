---
title: "Query Planning and Diagnostics"
chapter: 21
part: "V — Performance and Operations"
---

# Chapter 21: Query Planning and Diagnostics

Datalevin has a novel query optimizer that leverages the unique strengths of
triple stores in facilitating cardinality estimation, one of the hardest
problems in database design. With that foundation, Datalevin can handle very
complex queries and return results quickly.

A fast query depends on both the plan Datalevin chooses and the evidence you can
collect when that plan surprises you. This chapter combines optimizer mechanics,
recursive rule evaluation, and the `explain` workflow used to diagnose real
query behavior.


## 1. Query Planning and Optimization

As discussed in Chapter 8, Datalog is **declarative**. You describe *what* you
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


### 1.1 The Selinger-Style Optimizer

Datalevin uses a **Selinger-style cost-based query optimizer**, following the
System R tradition described by Selinger et al. [1], with dynamic programming,
similar to enterprise-grade relational databases like PostgreSQL, Oracle, and so
on.

#### 1.1.1 How it Works

Figure 21.1 describes the query planner pipeline.

![The query planner pipeline: parse, rewrite, estimate, plan, and execute, with rewrite through plan forming the cost-based optimizer](/images/diagrams/query-planner-pipeline.svg)

When a query is submitted:

1.  **Parsing**: The engine breaks down the `:where` clauses into individual
    constraints.
2.  **Query Graph Simplification**: Star-like attributes (multiple attributes on
    the same entity) are handled via merge scan, defined in Section 1.4,
    reducing the graph to chains between stars.
3.  **Cardinality Estimation**: The engine uses DLMDB's order statistics to
    estimate how many results each clause will produce.
4.  **Join Planning**: It explores possible join orders using dynamic
    programming, generating **left-deep join trees**.
5.  **Dynamic Search Policy**: The planner starts with exhaustive search but
    switches to greedy after considering `P(n, 2)` plans, since only the first
    two joins have the most accurate size estimates.

A **left-deep join tree** is a plan shape where each step joins one new base
relation into the intermediate result built so far. The alternative is a bushy
tree, where two independently built subplans are joined together. Datalevin uses
left-deep plans because its join methods are index-scan oriented: keeping one
side as a base relation preserves accurate counts and keeps planning cost
bounded [4] [5].

`P(n, 2)` is permutation notation. It means the number of ordered ways to choose
the first two join steps from `n` candidates: `n * (n - 1)`. Datalevin searches
that early space carefully, then uses greedier choices later because later
estimates are more dependent on previously chosen intermediates.

#### 1.1.2 A Concrete Join-Order Example

Join order matters because every join produces an intermediate relation. A bad
plan can create millions of temporary candidates and then throw almost all of
them away. A good plan starts with the most selective facts and keeps the
intermediate relation small.

Suppose an e-commerce database has:

- 10,000,000 orders.
- 80,000,000 line items.
- 500,000 products priced above `$100`, stored as `10000` cents.
- A unique order id, so `:order/id` lookup returns exactly one order.
- About 12 line items per order.

The logical query is simple: "For one order, find the expensive products in its
line items."

<div class="multi-lang">

```clojure
(d/q '[:find ?sku ?qty
       :in $ ?order-id
       :where
       [?order :order/id ?order-id]
       [?line :line-item/order ?order]
       [?line :line-item/product ?product]
       [?line :line-item/quantity ?qty]
       [?product :product/sku ?sku]
       [?product :product/price ?price]
       [(> ?price 10000)]]
     db
     "o-2026-000001")
```

```java
Object result = conn.query("[:find ?sku ?qty " +
    " :in $ ?order-id " +
    " :where " +
    " [?order :order/id ?order-id] " +
    " [?line :line-item/order ?order] " +
    " [?line :line-item/product ?product] " +
    " [?line :line-item/quantity ?qty] " +
    " [?product :product/sku ?sku] " +
    " [?product :product/price ?price] " +
    " [(> ?price 10000)]]",
    "o-2026-000001");
```

```python
result = conn.query(
    '[:find ?sku ?qty '
    ' :in $ ?order-id '
    ' :where '
    ' [?order :order/id ?order-id] '
    ' [?line :line-item/order ?order] '
    ' [?line :line-item/product ?product] '
    ' [?line :line-item/quantity ?qty] '
    ' [?product :product/sku ?sku] '
    ' [?product :product/price ?price] '
    ' [(> ?price 10000)]]',
    'o-2026-000001',
)
```

```javascript
const result = await conn.query(
  '[:find ?sku ?qty ' +
  ' :in $ ?order-id ' +
  ' :where ' +
  ' [?order :order/id ?order-id] ' +
  ' [?line :line-item/order ?order] ' +
  ' [?line :line-item/product ?product] ' +
  ' [?line :line-item/quantity ?qty] ' +
  ' [?product :product/sku ?sku] ' +
  ' [?product :product/price ?price] ' +
  ' [(> ?price 10000)]]',
  'o-2026-000001'
);
```

</div>

These clauses have one logical meaning, but the physical join order can be
wildly different.

| Plan step | Good plan: start from order id | Approx. candidates |
| :--- | :--- | ---: |
| 1 | AVE lookup `[:order/id "o-2026-000001"]` | 1 order |
| 2 | Reverse AVE lookup for `:line-item/order` | 12 line items |
| 3 | Join each line item to its product | 12 products |
| 4 | Check each product's price | a few rows |

The good plan does work proportional to one order.

| Plan step | Bad plan: start from product price | Approx. candidates |
| :--- | :--- | ---: |
| 1 | AVE range scan for `:product/price > 10000` | 500,000 products |
| 2 | Find line items for those products | millions of line items |
| 3 | Join those line items to orders | millions of order links |
| 4 | Keep only order `"o-2026-000001"` | same final rows |

The result is identical, but the bad plan may process millions of candidates to
answer a question about one order. This is why Datalevin invests in cardinality
estimation and join planning. The textual order of `:where` clauses does not
matter; the optimizer is choosing this physical order for you.

The good plan is a left-deep tree: the optimizer starts from the single order,
then folds in one base relation at a time, keeping the intermediate result small
at every step. Figure 21.2 shows the tree for the example above.

![The good plan as a left-deep join tree: the order leaf (AVE :order/id, one order) joins the line-item leaf (:line-item/order, 12 rows), and that 12-row intermediate joins the product leaf (:line-item/product, price > 10000) to yield a few rows; every right child is a base relation, unlike a bushy tree](/images/diagrams/left-deep-join-tree.svg)


### 1.2 Accurate Cardinality Estimation

The quality of a query plan depends on accurate cardinality estimates. Datalevin
excels here because counting is cheap in its nested triple storage. The
resulting join order is often similar to a hand-written join plan.

#### 1.2.1 Direct Counting

Some counts can be obtained in **O(1) time** directly from the index without
scanning. For example, `[?e :user/city "London"]` returns an exact count from
the AVE index.

For range queries, DLMDB's order statistics provide **O(log n)** counting.

#### 1.2.2 Query-Specific Sampling

For complex joins where counting isn't feasible, Datalevin uses **online
reservoir sampling** under actual query conditions. It:

1.  Collects sample entity ids
2.  Performs merge scans to get selectivity ratios
3.  Uses empirical-Bayes shrinkage with priors
4.  Applies skew-aware upper-bound correction for extreme data distributions

The last two steps are statistical guardrails. **Empirical-Bayes shrinkage**
means the planner does not trust a small or noisy sample completely; it pulls the
sample estimate toward a prior expectation so a few sampled entities do not
dominate the whole plan [6]. **Skew-aware upper-bound correction** means the
planner treats heavy-tailed distributions cautiously. If a few values are much
more frequent than average, the estimate is adjusted upward so the optimizer
does not choose a plan that only works for the average case [7]. These
statistical techniques are what often prevent the edge cases that create
catastrophic query slowdown incidents in production.

#### 1.2.3 Directional Estimation

Unlike traditional RDBMS that assume attribute independence, Datalevin's
estimation is **directional** — different join directions produce different
estimates. This matters for `:ref` and `:_ref` joins which are inherently
directional.


### 1.3 Predicate Push-Down

Datalevin rewrites queries to push selection predicates down to index scans.

#### 1.3.1 Inequality Predicates

Comparison operators are converted to range boundaries in the index scan. For
example:
```clojure
[(> ?age 21)]  ;; Becomes a range scan on :user/age >= 21
```

#### 1.3.2 Constant Parameter Plugging

Query parameters are plugged directly into the query to avoid expensive joins
with bound values.


### 1.4 Merge Scan

For star-like queries (multiple attributes on the same entity), Datalevin uses
**merge scan** — a technique similar to pivot scan [9].

Instead of joining each attribute separately, a single index scan on the EAV
index retrieves all matching attributes at once. The optimizer can then plan
over a simplified query graph whose nodes are star-shaped groups and whose edges
are links between those groups, reducing the join search space [10] [11] [12].
This is the **bulk of query execution time** and provides massive speedup.


### 1.5 Join Methods

Datalevin considers six join methods and picks the best based on cost estimation:

| Method | Use Case |
|--------|----------|
| **Forward ref** `:ref` | `[?e :user/friend ?f]` — merge scans ref values |
| **Reverse ref** `:_ref` | `[?f :user/_friend ?e]` — scans AVE then retrieves entities |
| **Value equality** `:val-eq` | When variables unify via attribute values |
| **Hash join** `:hash-join` | Large non-selective joins, chooses build/probe side adaptively |
| **Or-join** `:or-join` | Handles `or-join` clauses with sideways information passing (SIP) |
| **Not-join** `:not-join` | Optimizes conservative anti-join shapes instead of always deferring negative filters |

**Sideways information passing (SIP)** means passing already-known variable
bindings from one part of the plan into another part that is not simply the next
linear data pattern. In ordinary joins this happens naturally through shared
variables: once `?user` is bound, a later clause such as
`[?order :order/customer ?user]` can use that value to do a narrow lookup. An
`or-join` is more complicated because it contains several alternative branches.
SIP lets the planner evaluate each branch with the outer bindings it is allowed
to see, rather than evaluating every branch as an independent broad subquery and
joining the results afterward.

For example, an `or-join` whose declared join variables include `?user` can
receive the current `?user` bindings from earlier indexed clauses. Each branch
then asks a bounded question such as "does this user match by email?" or "does
this user match by phone?" instead of scanning all users that match either
branch. The declared join-variable list matters: it is the contract that tells
the optimizer which outer variables may be passed sideways into the alternatives
and which branch-local variables must stay internal. If a complex clause cannot
use the available bindings, it is less selective and may be planned later.

An **anti-join** is the negative counterpart of a join: it keeps a candidate row
only when no matching row exists on the excluded side. In SQL this is the shape
behind `NOT EXISTS`; in Datalevin it is usually expressed with `not` or
`not-join`. A planned `not-join` is useful when the engine has already bound the
declared join variables and can check the negative pattern with indexed lookups.
For example, after `?start` and `?person` are bound, a `not-join [?start
?person]` can ask "is there a direct friendship edge between these two?" and
discard the candidate only if that edge exists. The result is still declarative
negation, but the physical plan can avoid treating the negative pattern as a
late row-by-row filter.

In hash-join terminology, the **build side** is the input used to construct an
in-memory hash table, and the **probe side** is the input scanned to look up
matches in that table. Building on the smaller side usually saves memory and
work. Datalevin chooses that side from actual input sizes when possible, so a
hash join can be robust when earlier cardinality estimates are imperfect.

#### 1.5.1 Recency-Based Link Choice

When multiple paths exist to reach the same node, Datalevin prefers the link
from the **most recently resolved** node, since the estimation for the recent
data distribution is more accurate.


### 1.6 Parallel Processing

Datalevin's query engine uses parallelism at multiple levels:

- **Planning**: Counting and sampling are parallelized
- **Execution**: **Pipelining** keeps multiple tuples in flight across execution
  steps, with each step processed by a dedicated thread


### 1.7 Complex Clauses and Rules

The optimizer handles complex clauses in stages:

1.  **Index access clauses** produce intermediate results first
2.  **Heuristics and variable dependencies** reorder remaining complex clauses
    such as `and`, `or`, `not`, complex predicates, and function bindings that are
    not pushed down.
3.  **Rules** are handled by the specialized bottom-up evaluation machinery
    described later in this chapter.

`or-join` participates in link planning, and common `not-join` shapes can become
anti-join steps when their join variables are bound in a single plan component.
More complex negative clauses still fall back to late resolution.


### 1.8 Benchmarks

Datalevin's query engine has been tested against standard benchmark workloads,
but the published comparisons are Datalevin project benchmarks, not independent
third-party certifications. Treat the numbers as reproducible workload
observations: useful for understanding design tradeoffs, but not a substitute
for testing your own schema, data distribution, hardware, and database
configuration.

- **Join Order Benchmark (JOB)**: 113 complex SQL queries ported to Datalog.
  In the Datalevin JOB benchmark [13], the reported run used a November 2023
  16-inch MacBook Pro with an Apple M3 Pro, 36GB RAM, and a 1TB SSD. PostgreSQL
  was Homebrew PostgreSQL@18, Datalevin was the repository version under test,
  and all systems used default configuration without tuning. Each system was run
  once for warmup and once for the reported timing. Under that setup, Datalevin
  finished the 113-query workload in 71 seconds versus 171 seconds for
  PostgreSQL, about 2.4x faster overall. SQLite was included as a second SQL
  comparison and was much weaker on this workload: it completed the non-timeout
  portion in 295 seconds, already more than 4x slower than Datalevin, and 9
  queries hit the benchmark timeout. Counting those queries to completion would
  make the SQLite gap larger.
- **LDBC SNB**: The Datalevin LDBC-SNB benchmark [14] is an unofficial
  implementation of the Interactive workload for Datalevin and Neo4j. The
  reported run used scale factor 1, about 3.2M entities and 17.3M edges, on a
  2023 Apple M2 Max machine with 12 cores, 32GB RAM, a 1TB SSD, macOS 15.2,
  OpenJDK 21, and Clojure 1.12.4. Queries were run twice and the second run was
  reported. In that setup, Datalevin was much faster on the Interactive Short
  queries and about 13% faster on average across the Interactive Complex
  queries, while Neo4j was faster on several individual complex queries.

Benchmark ratios are especially sensitive to scale factor, cache state, data
modeling choices, query parameters, JVM settings, memory settings, and indexes.
Use these results as evidence that Datalevin's optimizer is competitive on
these workloads, not as a guarantee that Datalevin will be 2x or 100x faster in
another deployment.


### 1.9 Optimizer Principles

In summary, Datalevin has a sophisticated query optimizer that compiles
declarative query clauses into execution steps that use index scans where they
help, take advantage of cheap counts from triple storage, and often produce
plans similar to a hand-written one. Users can usually write the logical query
first, then use `explain` when a specific workload needs inspection or tuning.

- **Order Doesn't Matter**: The physical order of `:where` clauses has no
  impact.
- **Indexes are Automatic**: Every attribute is indexed in AVE.
- **Real-Time Stats**: No "vacuum" needed — DLMDB provides instant counts.
- **Nested Storage Advantage**: The nested triple storage makes counting cheap,
  enabling accurate cardinality estimation that outperforms traditional RDBMS.


## 2. Recursive Rules as Specialized Query Evaluation

Datalog rules are still query clauses, but recursive rules need different
execution machinery from ordinary joins. A non-recursive query can be planned as
a finite join graph. A recursive rule, such as an ancestor rule, describes a
process that may discover new facts over many rounds:

```clojure
[(ancestor ?x ?y) [?x :parent ?y]]
[(ancestor ?x ?y) [?x :parent ?z] (ancestor ?z ?y)]
```

A naive evaluator would repeatedly join all known ancestors against all parents.
That repeats work. In the third round, for example, it may rediscover
grandparent facts already found in the second round. On large graphs, this
redundant computation can dominate the query.

Datalevin therefore uses **semi-naive evaluation**, the standard bottom-up
strategy for recursive Datalog [2]. The key idea is delta tracking: each round
only uses the *new* tuples discovered in the previous round. Evaluation continues
until a fixpoint, meaning a round produces no new tuples. For stratified rules,
the engine divides rules into **strata**: layers evaluated in dependency order
so recursion and negation have one well-defined result. A later stratum can use
facts computed by an earlier stratum, but not the other way around. Strongly
connected rule components run in this dependency order, so facts needed by a
later stratum are available before that stratum runs.

This bottom-up model is set-oriented. Joins operate over candidate sets and
iterators, so the storage layer can use sequential page scans, merge-style
operations, and bulk filtering rather than tuple-at-a-time random lookup.

Bottom-up evaluation has one obvious risk: it can compute more of the world than
the user asked for. If a query asks only for Alice's ancestors, the engine should
not materialize every ancestor relation in the database. Datalevin uses
**magic-set rewriting**, a classic Datalog transformation for making bottom-up
evaluation goal-directed [2] [3]. Magic-set rules push bound variables from the
outer query into the recursive rule, pruning intermediate results to the part of
the graph relevant to the question.

### 2.1 Semi-Naive Evaluation

The examples in this section use Clojure/EDN notation for the rule data. The
query and rule forms are ordinary EDN; Java, Python, and JavaScript pass the
same forms as EDN strings or parsed EDN values, as shown in Chapter 9.

Consider a small directed graph, using letters as stand-ins for entity ids. The
example EDB is the following set of base edge facts:

```clojure
(def graph-edb
  '[[a :link/to b]
    [b :link/to c]
    [b :link/to d]
    [d :link/to e]
    [p :link/to q]
    [q :link/to r]])
```

In a real Datalevin database, each row in `graph-edb` corresponds to a stored
`:link/to` datom. These base edge facts are the **EDB** (extensional database):
facts read from Datalevin rather than derived by the recursive rule. The rule
predicate `reachable` is the **IDB** (intensional database): derived tuples
produced by the rules. The EDB stays fixed during the evaluation; each round adds
a delta of new IDB tuples.

The recursive rule says that `?end` is reachable from `?start` if there is a
direct edge, or if there is an edge to `?mid` and `?end` is reachable from
`?mid`:

```clojure
[(reachable ?start ?end)
 [?start :link/to ?end]]

[(reachable ?start ?end)
 [?start :link/to ?mid]
 (reachable ?mid ?end)]
```

Semi-naive evaluation tracks a **delta**, the new IDB tuples from the previous
round, and joins only against that delta in the recursive step. The base rule
derives round 1 directly from the EDB; later rounds join the previous IDB delta
against the same EDB edge facts. For this graph:

| Round | New IDB `reachable` tuples |
| ----- | --------------------------------------------- |
| 1 | `(reachable a b)`, `(reachable b c)`, `(reachable b d)`, `(reachable d e)`, `(reachable p q)`, `(reachable q r)` |
| 2 | `(reachable a c)`, `(reachable a d)`, `(reachable b e)`, `(reachable p r)` |
| 3 | `(reachable a e)` |
| 4 | No new tuples; fixpoint reached. |

A naive bottom-up evaluator would keep rejoining all known `reachable` tuples
in every round, rediscovering many results it already had. Semi-naive evaluation
uses only the previous round's delta to produce the next delta, then unions the
deltas into the final IDB relation. Chapter 9 shows the same idea with a longer
`reports-to` example.

### 2.2 Magic-Set Rewrite

Now suppose the query asks only for nodes reachable from `a`:

```clojure
[:find ?end
 :where (reachable a ?end)]
```

Plain bottom-up evaluation of `reachable` would also derive the disconnected
`p -> q -> r` portion of the graph, even though it cannot affect the answer.
A magic-set rewrite makes the bound start node explicit. Conceptually, the
engine adds a seed relation and threads it through the recursive rule:

```text
;; Seed fact from the query binding.
(magic-reachable a)

;; Propagate the seed to starts that can matter for this query.
[(magic-reachable ?mid)
 (magic-reachable ?start)
 [?start :link/to ?mid]]

;; Original base rule, guarded by the magic predicate.
[(reachable ?start ?end)
 (magic-reachable ?start)
 [?start :link/to ?end]]

;; Original recursive rule, also guarded.
[(reachable ?start ?end)
 (magic-reachable ?start)
 [?start :link/to ?mid]
 (reachable ?mid ?end)]
```

This is a conceptual rewrite, not Datalevin's literal internal representation.
The effect is the important part: the query seed `(magic-reachable a)` expands
only through nodes reachable from `a`. The disconnected `p`, `q`, and `r`
component is never seeded, so recursive evaluation does not spend work deriving
answers that cannot contribute to `[:find ?end :where (reachable a ?end)]`.
For the graph above, the seeded starts are `a`, `b`, `c`, `d`, and `e`, and the
answers are `b`, `c`, `d`, and `e`.

Figure 21.3 visually compares plain bottom-up evaluation with magic-set
rewriting.

![Magic-set rewrite turning broad recursion into goal-directed recursion: for the query reachable from a, plain bottom-up recursion derives the whole relation including the disconnected p→q→r component (wasted work), while the magic-set rewrite seeds the recursion at a and expands only the nodes reachable from a (a, b, c, d, e), leaving p, q, r never seeded and skipped; both return the same answer b c d e](/images/diagrams/magic-set-rewrite.svg)

### 2.3 Seeding from Prior Evaluation

Datalevin also connects rule evaluation back to the cost-based optimizer:

1.  **Seeding tuples**: Rule evaluation can receive bindings from earlier indexed
    query clauses. These seeds prevent unnecessary tuple generation and give
    recursion a warm start.
2.  **Inlining non-recursive clauses**: Clauses not involved in recursion can be
    pulled back into the ordinary query plan, where the cost-based optimizer can
    use indexes and join estimates.
3.  **Temporal elimination**: Recursive rules that meet T-stratification
    criteria, a time-aware form of the stratum ordering above, can keep only the
    last iteration's results, reducing memory use for long chains [8].
4.  **Stratified negation**: `not` and `not-join` in rules are evaluated only
    after the positive facts they depend on have been computed, giving recursive
    queries a single well-defined result.

The practical takeaway is that rules are not interpreted as repeated application
callbacks. They are compiled into set-oriented recursive evaluation, constrained
by outer query bindings, and integrated with the same storage and planning
principles used by ordinary Datalog queries.


## 3. Inspecting Query Plans with `explain`

While Datalevin's query optimizer is highly intelligent, it is not omniscient.
Sometimes a query that "should be fast" takes longer than expected. To debug
these scenarios, you need to "look under the hood" of the execution plan.

Datalevin's public diagnostic API for Datalog queries is **`explain`**. By
default, `explain` plans the query without running it. When called with `{:run?
true}`, it executes the query as well and adds measured result-size and timing
information to the explain output.


### 3.1 Plan-Only Explain

Use plan-only `explain` when you want to inspect join order, access paths, and
estimated cardinalities without paying query execution cost.

<div class="multi-lang">

```clojure
(def query
  '[:find ?name
    :where [?e :user/name ?name]
           [?e :user/age ?age]
           [(> ?age 30)]])

(d/explain {} query (d/db conn))
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
  estimated `:size`. The order of the `:steps` is the chosen join order, and the
  text of each step describes the join method or index access path.
- **`:opt-clauses` / `:late-clauses`**: Clauses handled by the optimizer versus
  clauses processed after the optimized plan
- **Planning times**: `:parsing-time`, `:building-time`, `:planning-time`, and
  `:prepare-time`

The actual value returned by `explain` is ordinary structured data and is much
larger than what is useful to print in a book. It includes the query graph, plan
steps, rewritten clauses, timing fields, and many implementation details
that are useful when debugging a specific query. The following is an abbreviated,
human-friendly excerpt from the small query above:

```text pdf-keep
{:planning-time "3.217",
 :opt-clauses
 [[?e :user/name ?name]
  [?e :user/age ?age]
  [(> ?age 30)]],
 :query-graph
 {$
  {?e
   {:free
    [{:attr :user/name, :var ?name}
     {:attr :user/age,
      :var ?age,
      :range [[[:open 30] [:closed :db.value/sysMax]]]}]}}},
 :plan
 {$
  [[{:steps
     ["Initialize [?e ?age] by range ... on :user/age."
      "Merge [?name] by scanning [:user/name]."],
     :cost nil,
     :size nil}]]},
 :late-clauses ()}
```

Read this in three parts. `:late-clauses` is empty, so the optimizer handled all
clauses in the main plan. The predicate `[(> ?age 30)]` has been converted into
a range bound on `:user/age`; it is not a separate row-by-row filter. The plan
starts from that bounded age scan and then merges `:user/name` for the same
entity. The `:cost` and `:size` fields are `nil` here because this minimal
example has no data for the planner to estimate from. On a populated database,
those fields normally contain numbers, and `:count`, `:cost`, and `:size` are
the places to inspect estimated work for the chosen plan.

Use plan-only explain to verify that a query is using the expected indexes and
join strategies before running it on large data.


### 3.2 Explain with Execution Measurements

Use `{:run? true}` when you also need measured execution information. This runs
the query and augments the explain map with fields such as `:execution-time`,
`:actual-result-size`, `:result`, and per-plan `:actual-size` where available.

<div class="multi-lang">

```clojure
(d/explain {:run? true} query (d/db conn))
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

In either mode, start with the `:plan` section. Each planned component contains
`:steps`, estimated `:cost`, and estimated `:size`; with `{:run? true}`, the
engine also reports actual sizes where available. Use `:count` in `:query-graph`
and `:size` in the plan for estimated cardinalities, `:actual-size` and
`:actual-result-size` for measured cardinalities, and `:prepare-time` versus
`:execution-time` to separate planning overhead from execution time.

The key comparison is estimated `:size` versus measured `:actual-size` for each
planned component. `:size` is the optimizer's cardinality estimate; `:actual-size`
is what the plan actually produced when the query ran. When those values are
close, the planner's model matched the current data. Datalevin is normally very
accurate in the first few plan steps because it can combine exact index counts
with query-specific sampling, and those early steps matter most for keeping
intermediate results small.

If `:size` and `:actual-size` differ by orders of magnitude, cardinality
estimation was wrong for that part of the plan. The cause may be a predicate or
a join whose selectivity is hard to estimate, but it can also be stale statistics:
Datalevin samples entity ids and attribute counts in the background, and a very
fast load or rapidly changing data distribution may outrun that process. After a
bulk load or major reshaping of data, running `d/analyze` for the affected
attribute, or for the database as a whole, can refresh the samples and counts
used by the optimizer. See Chapter 15 for the `analyze` examples.

A common performance bottleneck is a **"Large Intermediate Result Set."**
If the first join returns 1,000,000 entities, but the second join filters them
down to 10, the engine still had to process 1,000,000 records.

**The Fix**: Can you express a more selective indexed constraint in the query?
The optimizer tries to do this automatically, but a complex predicate or rule may
hide the selective condition from estimation.


### 3.3 Diagnosing Slow Predicates

Custom predicates are not automatically opaque to the optimizer. A predicate
that can be pushed down, typically one involving a single free variable and no
source expression, is executed during query-specific sampling, so Datalevin can
estimate its selectivity from the current data. For example, a predicate such as
`[(my-ns/is-complex? ?x)]` can be planned differently from a late filter if `?x`
is attached to a sampled value path.

The harder cases are predicates that cannot be pushed down, such as predicates
over several variables, predicates that depend on an explicit source, or
functions whose own runtime cost dominates even when selectivity is estimated
well. `explain` can show whether the predicate became part of the optimized plan
or remained in `:late-clauses`, but it does not time each predicate call
separately. If a query becomes slow only after adding a predicate, isolate that
predicate by comparing `explain` output and query runtime before and after adding
it.

**Optimization Tip**: Prefer indexed constraints that reduce the candidate set
before expensive non-pushdownable predicates run.


### 3.4 Understanding Join Orders

The `:plan` section shows the **join order** chosen by the optimizer.

1.  **Leading Clause**: This is the first clause the engine used to find the
    initial set of entities.
2.  **Sequential Joins**: The order in which subsequent clauses were applied.

If you see that the engine is starting with a non-selective clause (e.g.,
searching for `:user/active? true`), it might be because the selective filter you
expect is hidden inside a complex predicate or rule.


## Summary: The Explain Workflow

When a query is slow, follow this workflow:

1.  **Use plan-only `explain`**: Inspect join methods, index usage, and estimated
    cardinalities.
2.  **Use `explain` with `{:run? true}`**: Compare estimates with actual result
    sizes and total execution time.
3.  **Check cardinality gaps**: Large gaps between estimated `:size` and measured
    `:actual-size` are a strong hint that a predicate, rule, stale sample, or
    changing data distribution is hiding selectivity from the optimizer. If the
    data changed quickly, run `d/analyze` before drawing conclusions from the
    plan.
4.  **Simplify and isolate**: Remove clauses one by one to find the specific part
    of the query that is slow.
5.  **Refine the logic**: Prefer more selective indexed attributes to expensive
    custom predicates.

By using `explain` well, you gain the transparency needed to understand how
Datalevin plans a query and why a query may be slower than expected.


## References

[1] Patricia G. Selinger, Morton M. Astrahan, Donald D. Chamberlin,
   Raymond A. Lorie, and Thomas G. Price,
   [Access Path Selection in a Relational Database Management System](https://research.ibm.com/publications/access-path-selection-in-a-relational-database-management-system),
   SIGMOD 1979, pp. 23-34,
   [doi:10.1145/582095.582099](https://doi.org/10.1145/582095.582099).

[2] Todd J. Green, Shan Shan Huang, Boon Thau Loo, and Wenchao Zhou,
   [Datalog and Recursive Query Processing](https://www.nowpublishers.com/article/Details/DBS-017),
   Foundations and Trends in Databases, vol. 5, no. 2, pp. 105-195, 2013,
   [doi:10.1561/1900000017](https://doi.org/10.1561/1900000017).

[3] Francois Bancilhon, David Maier, Yehoshua Sagiv, and Jeffrey D. Ullman,
   [Magic Sets and Other Strange Ways to Implement Logic Programs](https://doi.org/10.1145/6012.15399),
   PODS 1986, pp. 1-15,
   [doi:10.1145/6012.15399](https://doi.org/10.1145/6012.15399).

[4] Guido Moerkotte and Thomas Neumann, "Dynamic Programming Strikes Back",
   SIGMOD 2008, pp. 539-552.

[5] Hongjun Lan, Zhifeng Bao, and Yuwei Peng, "A Survey on Advancing the DBMS
   Query Optimizer: Cardinality Estimation, Cost Model, and Plan Enumeration",
   *Data Science and Engineering*, 2021.

[6] Max Heimel, Volker Markl, and Kartik Murthy, "A Bayesian Approach to
   Estimating the Selectivity of Conjunctive Predicates", DBIS 2009.

[7] Peter J. Haas and Arun N. Swami, "Sampling-Based Selectivity Estimation for
   Joins Using Augmented Frequent Value Statistics", ICDE 1995.

[8] Amir Shaikhha et al., "Optimizing Nested Recursive Queries", *Proceedings of
   the ACM on Management of Data* 2(1), SIGMOD 2024, pp. 1-27.

[9] Andre Brodt, Olaf Schiller, and Bernhard Mitschang, "Efficient Resource
   Attribute Retrieval in RDF Triple Stores", CIKM 2011.

[10] Andrey Gubichev and Thomas Neumann, "Exploiting the Query Structure for
   Efficient Join Ordering in SPARQL Queries", EDBT 2014.

[11] Marios Meimaris et al., "Extended Characteristic Sets: Graph Indexing for
   SPARQL Query Optimization", ICDE 2017.

[12] Thomas Neumann and Guido Moerkotte, "Characteristic Sets: Accurate
   Cardinality Estimation for RDF Queries with Multiple Joins", ICDE 2011.

[13] Datalevin project,
   [Join Order Benchmark](https://github.com/datalevin/datalevin/tree/master/benchmarks/JOB-bench),
   benchmark writeup and implementation.

[14] Datalevin project,
   [LDBC Social Network Benchmark](https://github.com/datalevin/datalevin/tree/master/benchmarks/LDBC-SNB-bench),
   benchmark writeup and implementation.
