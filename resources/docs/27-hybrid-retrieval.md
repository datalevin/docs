---
title: "Hybrid Retrieval: Datalog + Full-Text + Vector"
chapter: 27
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 27: Hybrid Retrieval: Datalog + Full-Text + Vector

In many AI architectures, Retrieval-Augmented Generation (RAG) is synonymous with "Vector Search." However, relying solely on vectors often leads to poor results for exact matches, such as product IDs, technical versions, or specific names.

True intelligent retrieval requires a **Hybrid Approach**. This chapter demonstrates how to combine Datalog, Full-Text Search, and Vector Similarity into a single, high-fidelity retrieval pipeline.

---

## 1. Why Vectors Aren't Enough

Vector embeddings are great at capturing "vibes" and semantic meaning, but they are "fuzzy."
- **Vector weakness**: A search for "Datalevin 0.10.15" might return results for "Datomic 0.10" because they are semantically similar, even though the version is wrong.
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

<div class="multi-lang">

```clojure
(d/q '[:find ?content ?combined-score
       :in $ ?q-text ?q-vec ?user-id
       :where ;; 1. Keyword search (FTS)
              [(fulltext $ :doc/content ?q-text {:top 50
                                                  :display :refs+scores})
               [[?e _ _ ?fts-score]]]

              ;; 2. Semantic search (Vector)
              [(vec-neighbors $ :doc/vec ?q-vec {:top 50
                                                  :display :refs+dists})
               [[?e _ _ ?vec-dist]]]

              ;; 3. Logical constraints (Datalog)
              [?e :doc/content ?content]
              [?e :doc/status :published]
              [?user-id :user/permissions ?e] ; Security join

              ;; 4. Combined Ranking
              ;; Smaller vector distance is better.
              [(- ?fts-score ?vec-dist) ?combined-score]]
     db "performance tuning" query-embedding user-id)
```

```java
import datalevin.core.*;

var results = Datalevin.q(
    "[:find ?content ?combined-score " +
    " :in $ ?q-text ?q-vec ?user-id " +
    " :where [(fulltext $ :doc/content ?q-text {:top 50 :display :refs+scores}) [[?e _ _ ?fts-score]]]" +
    "        [(vec-neighbors $ :doc/vec ?q-vec {:top 50 :display :refs+dists}) [[?e _ _ ?vec-dist]]]" +
    "        [?e :doc/content ?content]" +
    "        [?e :doc/status :published]" +
    "        [?user-id :user/permissions ?e]" +
    "        [(- ?fts-score ?vec-dist) ?combined-score]]",
    db, "performance tuning", queryEmbedding, userId);
```

```python
results = d.q(
    """[:find ?content ?combined-score
        :in $ ?q-text ?q-vec ?user-id
        :where [(fulltext $ :doc/content ?q-text {:top 50 :display :refs+scores})
                [[?e _ _ ?fts-score]]]
               [(vec-neighbors $ :doc/vec ?q-vec {:top 50 :display :refs+dists})
                [[?e _ _ ?vec-dist]]]
               [?e :doc/content ?content]
               [?e :doc/status :published]
               [?user-id :user/permissions ?e]
               [(- ?fts-score ?vec-dist) ?combined-score]]""",
    db, "performance tuning", query_embedding, user_id)
```

```javascript
const results = d.q(
    `[:find ?content ?combined-score
      :in $ ?q-text ?q-vec ?user-id
      :where [(fulltext $ :doc/content ?q-text {:top 50 :display :refs+scores})
              [[?e _ _ ?fts-score]]]
             [(vec-neighbors $ :doc/vec ?q-vec {:top 50 :display :refs+dists})
              [[?e _ _ ?vec-dist]]]
             [?e :doc/content ?content]
             [?e :doc/status :published]
             [?user-id :user/permissions ?e]
             [(- ?fts-score ?vec-dist) ?combined-score]]`,
    db, 'performance tuning', queryEmbedding, userId);
```

</div>

### 3.1 Text-to-Embedding Search

When Datalevin owns the text embedding index with `:db/embedding true`, replace `vec-neighbors` with `embedding-neighbors`. The query input is text, not a vector:

```clojure
(d/q '[:find ?content ?dist
       :in $ ?q
       :where
       [(embedding-neighbors $ :doc/content ?q {:top 20
                                                :display :refs+dists})
        [[?e _ ?content ?dist]]]
       [?e :doc/status :published]]
     db
     "performance tuning")
```

### 3.2 Reciprocal Rank Fusion (RRF)
While the example above uses simple addition for scoring, many advanced systems use **Reciprocal Rank Fusion (RRF)** to combine results from different indexes. You can implement RRF logic directly in your Datalog query using custom functions.

### 3.3 Bounded Retrieval Pipelines

Agent applications usually need a retrieval pipeline, not one unbounded query.
A practical pipeline looks like this:

1. Extract cheap lexical hints from the user turn.
2. Search knowledge graph nodes, facts, episodes, and uploaded document chunks
   with small `:top` limits.
3. Merge lexical and semantic hits with RRF or another deterministic ranker.
4. Expand graph hits by one hop to activate adjacent concepts.
5. Materialize only the best references into working memory.

The important design choice is the boundary: the model should receive the final
curated context, not every intermediate hit from every index.

```clojure
{:retrieval.run/id       #uuid "00000000-0000-0000-0000-000000000401"
 :retrieval.run/session  [:session/id #uuid "00000000-0000-0000-0000-000000000402"]
 :retrieval.run/query    "migration lock timing"
 :retrieval.run/terms    ["migration" "lock" "timing"]
 :retrieval.run/limits   {:nodes 10
                          :facts 15
                          :episodes 5
                          :doc-chunks 8}
 :retrieval.run/result   {:facts [...]
                          :episodes [...]
                          :chunks [...]}}
```

Storing the run record is optional for latency-sensitive paths, but it is useful
for audit, debugging, and prompt-quality evaluation. It also gives you a place
to record why a fact entered working memory.

---

## 4. Performance: Candidate Control

Vector similarity search is computationally expensive. Keep retrieval candidates controlled by using domains, `:top`, and application-specific `:vec-filter` or `:doc-filter` functions when those filters can be expressed against the index reference itself.

General Datalog constraints such as `:doc/status :published` are still valuable, but they are joins over the candidates returned by the search function. If a hard constraint is very selective, consider using separate search/vector/embedding domains for that slice, or overfetch with a larger `:top` and let Datalog remove unauthorized or stale candidates.

### 4.1 Retrieval Epochs

Long-running agent turns may write new facts, documents, or episodes before the
final response. Keep a cheap retrieval epoch so the agent can refresh working
memory only when search-visible state has changed.

```clojure
{:retrieval.scope/id        #uuid "00000000-0000-0000-0000-000000000403"
 :retrieval.scope/session   [:session/id #uuid "00000000-0000-0000-0000-000000000402"]
 :retrieval/knowledge-epoch 42
 :retrieval/local-doc-epoch 7}
```

When a transaction changes an indexed knowledge fact, episode summary, or local
document chunk, increment the relevant epoch in the same transaction. A running
turn can compare its baseline epoch with the current epoch before deciding
whether to run retrieval again. This keeps prompt context fresh without turning
every tool call into a full memory refresh.

---

## 5. Summary: High-Fidelity Retrieval

By using Datalevin for RAG, you move beyond the limitations of simple vector databases.

- **Accuracy**: Exact matches via FTS ensure technical precision.
- **Meaning**: Semantic search via Vectors provides broad context.
- **Safety**: Datalog joins ensure that the agent only retrieves data it is authorized to see.
- **Bounded Context**: Pipelines and retrieval epochs keep recall useful during
  long-running turns.

This "Unified Retrieval" model is the foundation for building AI applications that are not only smart but also reliable and production-ready.
