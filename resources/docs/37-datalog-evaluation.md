---
title: "Datalog Evaluation Strategies"
chapter: 37
part: "VIII — Internals and Extensions"
---

# Chapter 37: Datalog Evaluation Strategies

Datalog is a language with a long history in computer science. Over the decades, two primary strategies have emerged for evaluating logic queries: **Top-Down** and **Bottom-Up**.

This chapter explains these strategies and how Datalevin uses a hybrid approach to provide both the flexibility of a logic language and the performance of a high-speed database.

---

## 1. Top-Down Evaluation (Goal-Oriented)

Top-down evaluation, often associated with languages like Prolog, starts with the "Goal" (the query) and works backwards toward the facts.

- **Pros**: It only explores paths that are relevant to the specific question asked.
- **Cons**: It can easily fall into infinite loops if the data has cycles (e.g., recursive paths) and often performs redundant work because it doesn't "remember" previously calculated results.

---

## 2. Bottom-Up Evaluation (Fact-Oriented)

Bottom-up evaluation starts with the raw facts in the database and repeatedly applies rules to derive new facts until no more can be found.

- **Pros**: It is set-oriented, perfectly suited for database engines, and **guaranteed to terminate** even with cyclic data.
- **Cons**: It can be inefficient because it might derive many facts that are not relevant to the user's specific query.

---

## 3. Datalevin's Strategy: Magic Hybrid

Datalevin primarily uses **Bottom-Up evaluation** because of its stability and suitability for the set-based operations of LMDB. However, to solve the efficiency problem, it employs a technique called **Magic Sets**.

### 3.1 How Magic Sets Work
Magic Sets take a bottom-up evaluator and make it behave like it's goal-oriented. 
1.  The engine analyzes your query to find the "constants" (e.g., a specific user ID).
2.  It creates a temporary "Magic" rule that restricts fact derivation to only those paths related to that constant.
3.  The result is a query that is as stable as a bottom-up engine but as focused as a top-down engine.

---

## 4. Set-Oriented Processing and Iterators

In Datalevin, evaluation is not performed one tuple at a time. Instead, it uses **Set-Oriented Iterators**.

- When joining two clauses, the engine operates on entire sets of candidate IDs. 
- This allows the storage layer (DLMDB) to use sequential page scans and bulk operations, which are significantly faster than random point lookups.

---

## 5. Stratified Negation and Consistency

Datalevin supports logical negation via `not` and `not-join`. To keep the logic sound, it uses **Stratified Negation**.

- The engine ensures that all facts used in a `not` clause are fully calculated before the negation is applied. 
- This prevents logical paradoxes and ensures that every query has a single, well-defined result (the "Minimal Model").

---

## Summary: The Best of Both Worlds

By combining bottom-up stability, magic-set rewrites, and stratified negation, Datalevin provides a logic engine that is:
- **Fast**: Focused only on relevant data.
- **Stable**: Guaranteed to terminate.
- **Predictable**: Consistent results regardless of join order.

This sophisticated evaluation model is what allows you to use Datalevin for everything from simple relational joins to complex recursive graph analytics.
