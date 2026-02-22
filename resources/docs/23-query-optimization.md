---
title: "Query Planning and Optimization"
chapter: 23
part: "V — Performance and Dataflow"
---

# Chapter 23: Query Planning and Optimization

As discussed in Chapter 9, Datalog is **declarative**. You describe *what* you want, and Datalevin's **Query Optimizer** decides *how* to find it. This decoupling is critical for performance because even a simple change in the order of joins can make a query 1,000x faster or slower.

This chapter explores how Datalevin's **Cost-Based Optimizer (CBO)** uses the internal structure of DLMDB to create the most efficient execution plan for your queries.

---

## 1. The Selinger-Style Optimizer

Datalevin uses a sophisticated **Selinger-style dynamic programming algorithm** to optimize Datalog queries. This is the same class of optimizer found in enterprise-grade relational databases like PostgreSQL and SQL Server.

### 1.1 How it Works
When a query is submitted:
1.  **Parsing**: The engine breaks down the `:where` clauses into individual constraints.
2.  **Cardinality Estimation**: The engine uses DLMDB's **order statistics** (Chapter 4) to estimate how many datoms match each constraint.
3.  **Cost Estimation**: It considers several join strategies (e.g., nested-loop vs. hash joins) for each step.
4.  **Path Selection**: It explores millions of possible join orders to find the "cheapest" path—the one that minimizes the number of intermediate results.

---

## 2. The Secret Sauce: Accurate Cardinality Estimates

The quality of a query plan depends entirely on the accuracy of its cardinality estimates. If the optimizer *thinks* a join will return 10 items but it actually returns 1,000,000, the resulting plan will be disastrous.

Datalevin's storage engine (DLMDB) provides **instant, exact counts** for any attribute-value pair in the AVE index.

- **Exact Counts**: When you query `[?e :user/city "London"]`, Datalevin doesn't guess; it looks at the B+Tree and knows exactly how many "London" entities exist in O(1) time.
- **Sampling**: For complex range queries like `[(> ?age 25)]`, the engine performs high-speed sampling of the B+Tree to estimate the distribution of values.

By having precise, real-time statistics, Datalevin can make incredibly accurate decisions about which filter to apply first.

---

## 3. Advanced Rewrite Passes

Before the optimizer even begins its cost analysis, Datalevin applies several **rewrite passes** to simplify the query logic.

### 3.1 Constant Folding
If a query contains fixed values or computable expressions, the engine "folds" them into constants before execution.

### 3.2 Magic-Set Rewrites for Rules
For recursive rules (Chapter 10), Datalevin uses **magic-set rewrites**. This "pushes" filters from the main query down into the recursive definition.

If you are searching for the ancestors of *one specific person*, the engine doesn't calculate the entire family tree of the database. Instead, it rewrites the rule to only explore paths that start with that person, effectively "pruning" the search space.

---

## 4. Join Strategies: Nested-Loop vs. Hash Joins

Datalevin supports multiple join algorithms and picks the best one for each situation.

### 4.1 Nested-Loop Join (NLJ)
This is the default for most Datalog clauses. The engine finds a set of entities and then "walks" through the index for the next clause to see if they match.
- **Strength**: Excellent for small, selective joins.
- **Cost**: Becomes slow if the first join returns millions of results.

### 4.2 Hash Join
For large, non-selective joins, Datalevin can switch to a **hash join**. It builds a hash table of the smaller set and then probes it with the larger set.
- **Strength**: High performance for joining large datasets that don't fit in CPU caches.

---

## 5. Summary: The Optimizer's Principles

Datalevin's optimizer is designed to be **unobtrusive**. You should focus on writing clear, readable logic, trusting that the engine will find the most efficient path.

- **Order Doesn't Matter**: Unlike some other Datalog implementations, the physical order of your `:where` clauses has no impact on performance.
- **Indexes are Automatic**: Every attribute is indexed in AVE, so the optimizer always has a range of choices.
- **Real-Time Stats**: Because DLMDB provides instant counts, the optimizer doesn't need "vacuum" or "analyze" commands to keep its statistics up to date.

By combining enterprise-grade relational optimization with the power of Datalog, Datalevin provides a query engine that is both expressive and incredibly fast at scale.
