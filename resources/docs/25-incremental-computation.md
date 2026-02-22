---
title: "Incremental Computation and Semi-Naive Evaluation"
chapter: 25
part: "V — Performance and Dataflow"
---

# Chapter 25: Incremental Computation and Semi-Naive Evaluation

Datalog is more than just a search engine; it is a **deductive logic** system. When you use recursive rules (Chapter 10), you are essentially deriving new facts from the base facts in your database. Calculating these new facts efficiently—especially in large graphs—requires a specialized approach to recursion.

This chapter explores **Semi-Naive Evaluation**, the engine that powers Datalevin's high-speed recursive rules.

---

## 1. The Challenge of Naive Recursion

Imagine a simple recursive rule for finding ancestors:
```clojure
[(ancestor ?x ?y) [?x :parent ?y]]
[(ancestor ?x ?y) [?x :parent ?z] (ancestor ?z ?y)]
```

In a **Naive Evaluation** model:
1.  **Iteration 1**: Find all parents (the base case).
2.  **Iteration 2**: Join all parents with themselves to find grandparents.
3.  **Iteration 3**: Join all parents with the *entire set* of ancestors found in iterations 1 and 2 to find great-grandparents.

The problem is **redundant computation**. In iteration 3, you are re-calculating the grandparents you already found in iteration 2. As the depth of the graph increases, the amount of redundant work grows exponentially, making the query incredibly slow for large datasets.

---

## 2. Semi-Naive Evaluation: The Efficient Alternative

Datalevin uses **Semi-Naive Evaluation** to solve this problem. Instead of joining with the *entire* set of previously found ancestors, it only joins with the **new** ancestors found in the *previous iteration*.

1.  **Iteration 1**: Find `Delta-1` (parents).
2.  **Iteration 2**: Join parents with `Delta-1` to find `Delta-2` (grandparents).
3.  **Iteration 3**: Join parents with `Delta-2` to find `Delta-3` (great-grandparents).

By only processing the **incremental changes** at each step, Datalevin ensures that every "fact" in the recursive path is calculated exactly once. This is the secret to why Datalevin can perform deep graph traversals across millions of nodes in milliseconds.

---

## 3. Incremental Updates and Fixpoints

Datalog evaluation continues until a **fixpoint** is reached—the point where no new facts can be derived.

### 3.1 Termination Guarantees
One of the key benefits of semi-naive evaluation is that it **guarantees termination**. Even if your graph has cycles (e.g., Alice follows Bob, Bob follows Alice), the engine detects that no *new* facts are being produced and stops the recursion. This prevents the infinite loops common in naive recursive functions.

---

## 4. Semi-Naive Evaluation in Datalevin

Datalevin's semi-naive engine is highly optimized for the LMDB storage layer.

- **Index Locality**: The `Delta` sets for each iteration are stored in high-speed in-memory structures or temporary LMDB sub-databases.
- **Unified Joins**: The engine uses the same cost-based optimizer (Chapter 23) for each iteration of the semi-naive loop, ensuring that every step of the recursion is as fast as possible.

---

## 5. Summary: Performance through Precision

Semi-naive evaluation is the "engine under the hood" of Datalevin's graph and recursive capabilities.

- **No Redundancy**: Only process new facts in each iteration.
- **Scalability**: Handle deep paths and large graphs with linear performance.
- **Safety**: Built-in termination guarantees for cyclic data.

By combining this efficient evaluation strategy with the power of Datalog, Datalevin provides a logic engine that is both incredibly expressive and fundamentally fast.
