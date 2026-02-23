---
title: "Profiling Queries and Understanding Execution"
chapter: 24
part: "V — Performance and Dataflow"
---

# Chapter 24: Profiling Queries and Understanding Execution

While Datalevin's query optimizer (Chapter 23) is highly intelligent, it is not omniscient. Sometimes a query that "should be fast" takes longer than expected. To debug these scenarios, you need to "look under the hood" of the execution plan.

This chapter covers how to **Profile** your Datalog queries and understand the performance of each join and filter in your execution path.

---

## 1. The Profiling API: `trace`

Datalevin provides a specialized profiling mechanism called **`trace`**. By adding a `:trace true` modifier to your query, the engine will return not just the results, but a detailed **Execution Trace**.

```clojure
(d/q '[:find ?name
       :where [?e :user/name ?name]
              [?e :user/age ?age]
              [(> ?age 30)]
       :trace true] ; Enable profiling
     db)
```

The trace output includes the **Cost**, **Cardinality**, and **Execution Time** for every single clause in the query.

---

## 2. Understanding Query Plans with `d/explain`

Datalevin provides the **`d/explain`** function to inspect the query plan without executing the query. This helps you understand how the optimizer will process your query.

```clojure
(d/explain db
  '[:find ?name
    :where [?e :user/name ?name]
           [?e :user/age ?age]
           [(> ?age 30)]])
```

The output shows:
- **Join order**: The sequence of clauses the optimizer chose
- **Join methods**: Which method (forward ref, reverse ref, hash join, etc.) is used for each join
- **Index scans**: What indexes are used for each clause
- **Estimated cardinalities**: The optimizer's predicted result sizes

Use `explain` to verify that your query is using the expected indexes and join strategies before running it with real data.

---

## 3. Reading the Execution Trace

A trace is a nested data structure that describes how the engine "walked" through your query.

### 3.1 Key Metrics to Watch
- **Index Scans**: How many datoms were read from the B+Tree for each clause?
- **Joins**: How many entities were matched at each step?
- **Filters**: How many results were discarded by predicates like `(> ?age 30)`?
- **Execution Time**: Which specific clause consumed the most CPU time?

### 3.2 Identifying Bottlenecks
A common performance bottleneck is a **"Large Intermediate Result Set."**
If the first join returns 1,000,000 entities, but the second join filters them down to 10, the engine still had to process 1,000,000 records.

**The Fix**: Can you add a more selective filter earlier in the query? The optimizer tries to do this automatically, but sometimes a complex predicate (like a custom Clojure function) cannot be accurately estimated.

---

## 4. Profiling Slow Predicates

If your query uses custom functions (Chapter 9), Datalevin's optimizer treats them as "black boxes" with an unknown cost.

If a trace shows that a predicate clause like `[(my-ns/is-complex? ?x)]` is taking a long time, it means that function is being called for **every single tuple** in the result set at that point.

**Optimization Tip**: Try to move your custom predicates as late as possible in the query (after the most selective filters have already reduced the number of tuples).

---

## 5. Understanding Join Orders

The trace will show you the **Join Order** chosen by the optimizer.

1.  **Leading Clause**: This is the first clause the engine used to find the initial set of entities.
2.  **Sequential Joins**: The order in which subsequent clauses were applied.

If you see that the engine is starting with a non-selective clause (e.g., searching for `:user/active? true`), it might be because the optimizer's statistics are out of date or the selective filter you expect is hidden inside a complex rule.

---

## 6. Summary: The Profiling Workflow

When a query is slow, follow this workflow:

1.  **Use `d/explain`**: Inspect the query plan to verify join methods and index usage
2.  **Enable `:trace true`**: Get the raw execution data.
3.  **Find the "Heavy" Clause**: Look for the clause with the highest execution time or the largest number of index scans.
4.  **Check Cardinalities**: Are the actual number of results much higher than what you (or the optimizer) expected?
5.  **Simplify and Isolate**: Remove clauses one by one to find the specific part of the query that is slow.
6.  **Refine the Logic**: Use more selective attributes or move slow custom predicates to the end of the `:where` block.

By mastering the profiling API, you gain the transparency needed to ensure that every query in your Datalevin database is running at peak performance.
