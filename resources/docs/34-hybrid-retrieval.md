---
title: "Hybrid Retrieval: Datalog + Full-Text + Vector"
chapter: 34
part: "VII — Datalevin for Intelligent Systems"
---

# Chapter 34: Hybrid Retrieval: Datalog + Full-Text + Vector

In many AI architectures, Retrieval-Augmented Generation (RAG) is synonymous with "Vector Search." However, relying solely on vectors often leads to poor results for exact matches, such as product IDs, technical versions, or specific names.

True intelligent retrieval requires a **Hybrid Approach**. This chapter demonstrates how to combine Datalog, Full-Text Search, and Vector Similarity into a single, high-fidelity retrieval pipeline.

---

## 1. Why Vectors Aren't Enough

Vector embeddings are great at capturing "vibes" and semantic meaning, but they are "fuzzy."
- **Vector weakness**: A search for "Datalevin 0.9.22" might return results for "Datomic 0.9" because they are semantically similar, even though the version is wrong.
- **The Solution**: Use **Full-Text Search** for exact keyword matching and **Datalog** for hard constraints.

---

## 2. The "Triple-Lens" Retrieval Pattern

A production-grade RAG system using Datalevin uses three lenses simultaneously:

1.  **Textual (FTS)**: "Find documents containing these specific technical terms."
2.  **Semantic (Vector)**: "Find documents that talk about similar concepts."
3.  **Logical (Datalog)**: "Only include documents that the current user has permission to see and that were updated this month."

---

## 3. Implementing the Hybrid Query

In Datalevin, these three lenses are combined in a single `:where` block.

```clojure
(d/q '[:find ?content ?combined-score
       :in $ ?q-text ?q-vec ?user-id ?k
       :where ;; 1. Keyword search (FTS)
              [(fulltext $ :doc/content ?q-text) [[?e _ ?fts-score]]]
              
              ;; 2. Semantic search (Vector)
              [(vsearch $ :doc/vec ?q-vec ?k) [[?e _ ?vec-score]]]
              
              ;; 3. Logical constraints (Datalog)
              [?e :doc/content ?content]
              [?e :doc/status :published]
              [?user-id :user/permissions ?e] ; Security join
              
              ;; 4. Combined Ranking
              [(+ ?fts-score ?vec-score) ?combined-score]]
     db "performance tuning" query-embedding user-id 10)
```

### 3.1 Reciprocal Rank Fusion (RRF)
While the example above uses simple addition for scoring, many advanced systems use **Reciprocal Rank Fusion (RRF)** to combine results from different indexes. You can implement RRF logic directly in your Datalog query using custom functions.

---

## 4. Performance: Filtering Before Searching

Vector similarity search is computationally expensive. One of Datalevin's biggest advantages is the ability to **filter before the vector search**.

If your Datalog constraints (e.g., `:doc/status :published`) reduce the candidate set from 1,000,000 to 1,000, Datalevin's optimizer will apply those filters first. The HNSW vector engine then only has to calculate distances for those 1,000 entities, resulting in a **100x to 1000x speedup** compared to a post-filtering approach.

---

## 5. Summary: High-Fidelity Retrieval

By using Datalevin for RAG, you move beyond the limitations of simple vector databases.

- **Accuracy**: Exact matches via FTS ensure technical precision.
- **Meaning**: Semantic search via Vectors provides broad context.
- **Safety**: Datalog joins ensure that the agent only retrieves data it is authorized to see.

This "Unified Retrieval" model is the foundation for building AI applications that are not only smart but also reliable and production-ready.
