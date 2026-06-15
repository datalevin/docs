---
title: "Recall and Context Assembly"
chapter: 25
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 25: Recall and Context Assembly

Chapter 24 defined the records an agent stores: episodes, semantic facts,
working-memory slots, and session documents. This chapter explains how those
records become useful to an LLM at inference time.

The key distinction is that recall is not just search. Search finds candidates.
Recall selects, ranks, filters, explains, and formats the small context packet
that should guide the next model call. Datalevin helps because symbolic
constraints, full-text matching, vector similarity, graph expansion, and task
state all live in the same queryable substrate.

---

## 1. Recall Is a Control Problem

In many AI architectures, Retrieval-Augmented Generation (RAG) is treated as a
vector search step: embed the user question, fetch similar chunks, paste them
into the prompt. That can work for simple question answering, but agent memory
needs more control.

An agent usually needs to ask:

1. Which goal and task are active?
2. Which user, session, document scope, or permission boundary applies?
3. Which recent episodes matter?
4. Which semantic facts are current and reliable?
5. Which working-memory slots are pinned or stale?
6. Which evidence should be shown to the LLM, and in what order?

Those are database questions as much as model questions. If retrieval ignores
goal state, policy, permissions, or evidence quality, the model receives a
plausible-looking but unsafe context. Good recall makes the boundary explicit:
deterministic code retrieves and organizes candidate context; the LLM reasons
over the curated packet.

---

## 2. Why Vectors Are Not Enough

Vector embeddings are good at capturing semantic similarity, but they are fuzzy.
A search for "Datalevin 1.0.15" might return results for "Datomic 1.0.1015"
because the texts are semantically related, even though the software and
versions are wrong. Product IDs, exact names, incident numbers, schema
attributes, dates, and permissions often need symbolic precision.

The useful mental model is symbolic structure as the skeleton and data-driven
models as flexible functions. Embeddings, classifiers, and LLM calls are
excellent at broad matching, similarity, extraction, and judgment under
uncertainty. They should produce candidate facts, candidate entities, candidate
answers, or scores. Datalog then gives those candidates a place in a structured
world: permissions, identities, timestamps, task state, and graph relationships.

This keeps model calls from becoming unreviewable authority. A model may say
"this message appears to be about billing," but Datalevin can store that as a
classification with a score, join it with the user's account permissions, and
route it through explicit task logic. If the classification was wrong, the
stored score and source give you something to inspect and correct, and the
transactions or the queries will tell you exactly where things went wrong.

---

## 3. The Multi-Lens Retrieval Pattern

A production-grade agent usually combines several retrieval lenses:

1. **Episodic lens**: "Have I talked about this specific topic with this user or
   task recently?"
2. **Semantic lens**: "What durable facts or concepts are known about this
   topic?"
3. **Textual lens**: "Which documents contain the exact terms, identifiers, or
   phrases?"
4. **Vector lens**: "Which records are semantically similar even if they use
   different wording?"
5. **Logical lens**: "Which candidates are allowed, current, scoped to this
   session, and relevant to the active goal?"

A simple double-lens query can combine recent episodic context with semantic
knowledge:

<div class="multi-lang">

```clojure
(d/q '[:find ?fact
       :in $ % ?topic ?user ?since
       :where (or ;; Lens 1: recent episodic context.
                  (and [?e :episode/user ?user]
                       [?e :episode/timestamp ?ts]
                       [(> ?ts ?since)]
                       [?e :episode/summary ?fact])

                  ;; Lens 2: semantic knowledge.
                  (and [?c :concept/name ?topic]
                       [?c :concept/description ?fact]))]
     db memory-rules "Vector Search" user-id one-hour-ago)
```

```java
// Double-lens query: episodic + semantic.
Object results = conn.query(
    "[:find ?fact " +
    " :in $ % ?topic ?user ?since " +
    " :where (or (and [?e :episode/user ?user] " +
    "                 [?e :episode/timestamp ?ts] " +
    "                 [(> ?ts ?since)] " +
    "                 [?e :episode/summary ?fact]) " +
    "            (and [?c :concept/name ?topic] " +
    "                 [?c :concept/description ?fact]))]",
    memoryRules, "Vector Search", userId, oneHourAgo);
```

```python
# Double-lens query: episodic + semantic.
results = conn.query(
    '[:find ?fact '
    ' :in $ % ?topic ?user ?since '
    ' :where (or (and [?e :episode/user ?user] '
    '                 [?e :episode/timestamp ?ts] '
    '                 [(> ?ts ?since)] '
    '                 [?e :episode/summary ?fact]) '
    '            (and [?c :concept/name ?topic] '
    '                 [?c :concept/description ?fact]))]',
    memory_rules, "Vector Search", user_id, one_hour_ago)
```

```javascript
// Double-lens query: episodic + semantic.
const results = await conn.query(
    '[:find ?fact ' +
    ' :in $ % ?topic ?user ?since ' +
    ' :where (or (and [?e :episode/user ?user] ' +
    '                 [?e :episode/timestamp ?ts] ' +
    '                 [(> ?ts ?since)] ' +
    '                 [?e :episode/summary ?fact]) ' +
    '            (and [?c :concept/name ?topic] ' +
    '                 [?c :concept/description ?fact]))]',
    memoryRules, "Vector Search", userId, oneHourAgo);
```

</div>

For production RAG, add text, vector, and authorization lenses. Not every query
must use every lens; the application should instead select the lenses that
match the task, then make that retrieval policy explicit.

---

## 4. Implementing a Hybrid Query

In Datalevin, full-text search, vector search, and logical constraints can be
combined in one `:where` block. The retrieval is semantically precise and fast,
avoiding the need for fragile and slow glue code.

The practical pattern is not to subtract a vector distance from a text score.
Those numbers come from different scoring systems. Instead, run bounded queries
for each lens, apply the same logical constraints to each candidate set, and
fuse the resulting ranked lists with reciprocal rank fusion (RRF).

```clojure
(def text-hits
  (d/q '[:find ?e ?content ?score
         :in $ ?q-text ?user-id
         :where
         [(fulltext $ :doc/content ?q-text {:top 50
                                            :display :refs+scores})
          [[?e _ ?content ?score]]]
         [?e :doc/status :published]
         [?user-id :user/permissions ?e]
         :order-by [?score :desc]]
       db
       "performance tuning"
       user-id))

(def vector-hits
  (d/q '[:find ?e ?content ?dist
         :in $ ?q-vec ?user-id
         :where
         [(vec-neighbors $ :doc/vec ?q-vec {:top 50
                                            :display :refs+dists})
          [[?e _ _ ?dist]]]
         [?e :doc/content ?content]
         [?e :doc/status :published]
         [?user-id :user/permissions ?e]
         :order-by [?dist :asc]]
       db
       query-embedding
       user-id))

(defn rrf-fuse
  "Fuse ordered result rows. The first column must be the entity id."
  [ranked-lists]
  (let [k 60]
    (->> ranked-lists
         (mapcat
           (fn [rows]
             (map-indexed
               (fn [idx row]
                 (let [rank (inc idx)
                       e    (first row)]
                   [e (/ 1.0 (+ k rank)) row]))
               rows)))
         (reduce
           (fn [acc [e score row]]
             (-> acc
                 (update-in [e :rrf] (fnil + 0.0) score)
                 (update-in [e :evidence] (fnil conj []) row)))
           {})
         (map (fn [[e result]] (assoc result :e e)))
         (sort-by :rrf >))))

(take 10 (rrf-fuse [text-hits vector-hits]))
```

RRF uses only each candidate's rank in each list. The top full-text hit
contributes `1 / (60 + 1)`, the second contributes `1 / (60 + 2)`, and so on.
If the same entity appears in both the full-text and vector lists, its
contributions are added. This makes RRF robust when lexical scores and vector
distances have incompatible scales.

When Datalevin owns the text embedding index with `:db/embedding true`, replace
`vec-neighbors` with `embedding-neighbors`. The query input is text, not a
vector:

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

---

## 5. Bounded Retrieval Pipelines

Agent applications usually need a retrieval pipeline, not one unbounded query. A
practical pipeline looks like this:

1. Extract cheap lexical hints from the user turn.
2. Search knowledge graph nodes, facts, episodes, and uploaded document chunks
   with small `:top` limits.
3. Merge lexical and semantic hits with Reciprocal Rank Fusion (RRF) or another
   deterministic ranker.
4. Expand graph hits by one hop to activate adjacent concepts.
5. Enforce permissions, freshness, and task scope.
6. Materialize only the best references into working memory.

The important design choice is the boundary: the model should receive the final
curated context, not every intermediate hit from every index.

Once deterministic retrieval has selected the best references, materialize that
selection as ordinary working-memory entities. The database does not decide the
ranking policy; it stores the result so later prompt assembly, audit, and
refresh logic can inspect it.

```clojure
(defn materialize-working-memory!
  [conn session-id selected-facts]
  (let [wm-id (random-uuid)]
    (d/transact! conn
      (into
        [{:wm/id      wm-id
          :wm/session [:session/id session-id]}]
        (map (fn [{:keys [fact-id relevance reason pinned?]}]
               {:wm.slot/wm        [:wm/id wm-id]
                :wm.slot/entity    [:fact/id fact-id]
                :wm.slot/relevance relevance
                :wm.slot/reason    reason
                :wm.slot/pinned?   (boolean pinned?)}))
        selected-facts))
    wm-id))

(materialize-working-memory!
  conn
  "release-standup"
  [{:fact-id (d/q '[:find ?id .
                    :where [?f :fact/status :fact.status/pending]
                           [?f :fact/id ?id]]
                  @conn)
    :relevance 0.91
    :reason "The current task asks for release-note guidance."
    :pinned? true}])
```

In production code, `selected-facts` would come from the fused and filtered
retrieval results. The example is deliberately small: it shows the boundary
between retrieval policy and durable state.

This is especially important when the agent can call tools repeatedly. If every
full-text hit, embedding neighbor, and document chunk enters the transcript, the
model spends its context budget sorting retrieval artifacts instead of solving
the task. A bounded pipeline lets deterministic code do the repetitive work:
search several domains, merge rankings, remove duplicates, enforce permissions,
and return only the selected evidence.

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

The run record can be small. You do not need to store every rejected candidate
forever. In production systems, it is often enough to store the query, limits,
selected references, ranking method, and the versions or epochs of the indexed
state that were searched. That gives you reproducibility without turning
retrieval logs into another unbounded memory store.

---

## 6. Retrieval Epochs and Working-Memory Refresh

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

Epochs are not a replacement for database consistency. They are a cheap signal
for prompt assembly. The database is already consistent after each transaction;
the question is whether the agent's current working-memory projection is stale
enough to rebuild. Keeping that decision explicit avoids both extremes: stale
context during long tasks and wasteful retrieval after every harmless state
change.

---

## 7. Prompt Assembly from Memory

The final model prompt should not be a raw dump of retrieved memories. Raw
concatenation is hard for an LLM to use reliably: old facts, current task state,
user preferences, retrieved episodes, and tool policy all compete in the same
flat text. A better pattern is to assemble a labeled context packet from the
different memory sources, then give the model an explicit output contract.

Suppose the refresh step has produced the following Datalevin-backed context:

```clojure
{:goal
 {:id        #uuid "00000000-0000-0000-0000-000000000101"
  :objective "Monitor release readiness"
  :success   "Summarize open blockers before 09:00"
  :policy    {:do-not-deploy-with-open-migrations true}}

 :task
 {:id      #uuid "00000000-0000-0000-0000-000000000102"
  :state   :task.state/running
  :step    :summarize
  :user    [:user/id "alice"]}

 :working-memory
 [{:entity    :concept/migrations
   :reason    "Current release has pending schema changes."
   :relevance 0.82
   :pinned?   false}
  {:entity    :preference/release-summary-style
   :reason    "User asked for short deployment summaries."
   :relevance 1.0
   :pinned?   true}]

 :semantic-facts
 [{:fact/id      :fact/release-2026-06-09-has-migration
   :statement    "Release 2026-06-09 includes database migration M184."
   :confidence   0.98
   :source       [:episode/id #uuid "00000000-0000-0000-0000-000000000211"]}
  {:fact/id      :fact/m184-blocked
   :statement    "Migration M184 is blocked until backup verification finishes."
   :confidence   0.93
   :source       [:tool.call/id #uuid "00000000-0000-0000-0000-000000000212"]}]

 :episodic-evidence
 [{:episode/id #uuid "00000000-0000-0000-0000-000000000213"
   :when       #inst "2026-06-09T07:40:00Z"
   :summary    "CI passed, but the DBA asked for manual backup verification."
   :score      0.76}
  {:episode/id #uuid "00000000-0000-0000-0000-000000000214"
   :when       #inst "2026-06-08T18:15:00Z"
   :summary    "A previous migration was rolled back because verification was skipped."
   :score      0.68}]

 :recent-turn
 {:user-request "Can we ship this morning?"
  :tool-results [{:tool :deploy/status
                  :result {:ci :passed
                           :open-blockers ["backup verification"]}}]}}
```

The prompt sent to the LLM can then preserve the distinction between durable
instructions, current state, evidence, and the requested answer:

```text
You are assisting with a release-readiness task.

Priority order:
1. Follow the Goal Contract and Safety Policy.
2. Use Current Task State and Recent Tool Results as the freshest operational
   state.
3. Treat Semantic Facts as durable knowledge, but prefer higher-confidence facts
   when facts conflict.
4. Treat Episodic Evidence as supporting evidence, not as policy.
5. Do not invent deployment status. If required data is missing, say what must
   be checked.

Goal Contract:
- Objective: Monitor release readiness.
- Success condition: Summarize open blockers before 09:00.
- Safety policy: Do not recommend deployment while open migrations or backup
  verification blockers remain.

Current Task State:
- Task state: running.
- Current step: summarize.
- User: alice.
- User request: "Can we ship this morning?"

Working Memory:
- :concept/migrations, relevance 0.82
  Reason: Current release has pending schema changes.
- :preference/release-summary-style, pinned
  Reason: User asked for short deployment summaries.

Semantic Facts:
- [fact/release-2026-06-09-has-migration, confidence 0.98]
  Release 2026-06-09 includes database migration M184.
- [fact/m184-blocked, confidence 0.93]
  Migration M184 is blocked until backup verification finishes.

Episodic Evidence:
- [episode 00000000-0000-0000-0000-000000000213, score 0.76, 2026-06-09 07:40Z]
  CI passed, but the DBA asked for manual backup verification.
- [episode 00000000-0000-0000-0000-000000000214, score 0.68, 2026-06-08 18:15Z]
  A previous migration was rolled back because verification was skipped.

Recent Tool Results:
- deploy/status: CI passed; open blocker is backup verification.

Answer Format:
- Start with one sentence: "Ship?" followed by yes/no and the main reason.
- Then list blockers, if any.
- Then list the next concrete check or action.
- Keep the answer under 120 words.
```

This structure makes the prompt less error-prone in three ways. First, it keeps
policy and goal state above retrieved evidence, so a similar old episode cannot
override the current contract. Second, it labels provenance and confidence, so
the model can distinguish durable facts from supporting anecdotes. Third, it
turns working memory into a compact agenda rather than a second chat history.
The LLM receives enough context to reason, but the database remains the system
of record for state, evidence, and later audit.

---

## 8. Performance: Candidate Control

Vector similarity search is computationally expensive. Keep retrieval candidates
controlled by using domains, `:top`, and application-specific `:vec-filter` or
`:doc-filter` functions when those filters can be expressed against the index
reference itself.

General Datalog constraints such as `:doc/status :published` are still
valuable, but they are joins over the candidates returned by the search
function. If a hard constraint is very selective, consider using separate
search/vector/embedding domains for that slice, or overfetch with a larger
`:top` and let Datalog remove unauthorized or stale candidates.

There is a practical tradeoff here. Separate domains make candidate generation
cheap and precise, but they increase schema and indexing decisions. Overfetching
keeps the schema simpler, but wastes work when most candidates are filtered out
after retrieval. Start with simple domains and explicit `:top` limits, then add
specialized domains only for constraints that are both common and selective.

---

## Summary

Recall is the bridge between stored memory and model reasoning. The database can
hold years of episodes, facts, documents, and state, but the LLM needs a compact
packet with clear priority, provenance, and output expectations.

Datalevin's advantage is that the same engine can retrieve fuzzy semantic
matches, exact text matches, graph relationships, scoped state, permissions, and
evidence links. That makes context assembly inspectable instead of a pile of
prompt strings.
