---
title: "Incremental Computation and Semi-Naive Evaluation"
chapter: 25
part: "V — Performance and Dataflow"
---

# Chapter 25: Rules Engine and Recursive Evaluation

Datalog's power lies in its ability to define **recursive rules** that derive new facts from base data. However, evaluating recursive rules efficiently requires sophisticated algorithms to avoid exponential blowup.

This chapter explores Datalevin's **bottom-up rules engine**, which implements the latest research advances in Datalog evaluation with several innovations of its own.

---

## 1. The Challenge of Naive Recursion

Consider a simple recursive rule for finding ancestors:
```clojure
[(ancestor ?x ?y) [?x :parent ?y]]
[(ancestor ?x ?y) [?x :parent ?z] (ancestor ?z ?y)]
```

In a **Naive Evaluation** model:
1.  **Iteration 1**: Find all parents (the base case).
2.  **Iteration 2**: Join all parents with themselves to find grandparents.
3.  **Iteration 3**: Join all parents with the *entire set* of ancestors found in iterations 1 and 2.

The problem is **redundant computation**. In iteration 3, you re-calculate grandparents already found in iteration 2. This exponential blowup makes naive recursion unusable for large graphs.

---

## 2. Semi-Naive Fixpoint Evaluation

Datalevin uses **Semi-Naive Evaluation (SNE)**, the standard bottom-up strategy:

1.  **Delta tracking**: Only process *new* tuples discovered in each iteration
2.  **Fixpoint**: Continue until no new tuples are produced
3.  **Stratification**: Rules run in strongly connected components (strata) in topological order

This ensures every fact is derived exactly once, providing linear performance for deep graph traversals.

---

## 3. Magic Set Rewrite

Datalevin implements the **magic set rewrite** algorithm. This technique adds "magic rules" that leverage bound variables to prune unnecessary intermediate results.

When querying for ancestors of a *specific person* (e.g., "who are Alice's
ancestors?"), magic sets push that filter down into the recursive rule, so the
engine doesn't compute the entire family tree, just the paths leading from Alice.

---

## 4. Innovations in Datalevin's Rules Engine

### 4.1 Seeding Tuples

Unlike a standalone SNE engine, Datalevin's rules engine doesn't work from a blank slate. It receives **seeding tuples** from outer query clauses.

These seeds:
- Come from index scans powered by the cost-based optimizer
- Act as filters to prevent unnecessary tuple generation during SNE
- Provide a "warm start" for recursive evaluation

### 4.2 Inline Non-Recursive Clauses

Datalevin identifies clauses not involved in recursion, pulls them out, and adds them to regular query clauses where the **cost-based optimizer** can work on them.

SNE only operates on rules actually involved in recursion. This innovation leverages index-based joins (faster than SNE) for non-recursive parts.

### 4.3 Temporal Elimination

For recursive rules that meet **T-stratification** criteria, Datalevin implements temporal elimination—an optimization that:
- Saves only the results of the *last* iteration
- Avoids storing intermediate delta sets
- Significantly reduces memory usage for long recursion chains

---

## 5. Benchmarks

Datalevin's rules engine has been validated against industry benchmarks:

- **LDBC SNB Benchmark**: Industry graph database benchmark. Datalevin compares favorably with Neo4j, particularly on queries leveraging rules.
- **Math Genealogy Benchmark**: Compared with Datomic and Datascript. Datalevin is significantly faster, with **several orders of magnitude** speedup on recursive rules.

---

## 7. Summary: Why Bottom-Up Wins

Datalevin's rules engine combines academic rigor with practical innovations:

- **Semi-Naive Evaluation**: Process only new facts at each step
- **Magic Set Rewrite**: Push filters into recursive rules
- **Seeding**: Leverage the query optimizer for warm starts
- **Inline Non-Recursive Clauses**: Let the CBO handle non-recursive parts
- **Temporal Elimination**: Save memory for T-stratified rules

The result is a rules engine that can handle millions of nodes in recursive queries while remaining memory-efficient and fast.
