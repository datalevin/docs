---
title: "Persistent Agent Memory Architectures"
chapter: 24
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 24: Persistent Agent Memory Architectures

The greatest limitation of modern Large Language Models (LLMs) is their lack of state. While they possess vast general knowledge, they are "reset" at the start of every session. To build true **Intelligent Agents**, we must provide them with a persistent memory—a place to store past experiences, learned facts, and evolving goals.

This chapter explores how Datalevin serves as a unified substrate for agent memory, bridging the gap between unstructured semantic search and structured logical reasoning.

---

## 1. The Human Memory Analogy

In cognitive science, human memory is not a single "box." It is a multi-modal system. Datalevin's multi-paradigm architecture maps directly to these cognitive functions:

1.  **Episodic Memory**: Remembering specific events and experiences. (Powered by **Full-Text** and **Vector Search**).
2.  **Semantic Memory**: Structured knowledge about the world—facts, categories, and meanings. (Powered by **Datalog** and **Graph Relationships**).
3.  **Working Memory**: The current context and short-term goals. (Powered by **idocs** and **Triples**).

By using Datalevin, an agent doesn't just "retrieve a document"; it can query its past experiences through multiple logical lenses simultaneously.

---

## 2. Why a Unified Memory Engine?

Many agent architectures attempt to combine a Vector DB (for similarity) with a SQL DB (for state). This "polyglot" approach creates significant friction:
- **Consistency**: The vector index and the relational database can get out of sync.
- **Complexity**: The agent must manage two different APIs and data models.
- **Latency**: Multiple network hops to different services slow down the "think-act" loop.

**The Datalevin Advantage**: In Datalevin, the text description, optional user-supplied vector, automatic embedding index, and logical facts about an experience all live under the same transaction boundary.

```clojure
;; An "Experience" entity in Datalevin
{:experience/id      #uuid "00000000-0000-0000-0000-000000000001"
 :experience/text    "I helped the user debug a Clojure macro today."
 :experience/vector  [...] ; Optional user-supplied vector
 :experience/user    [:user/id "alice"]
 :experience/success true
 :experience/topics  [:clojure :macros]}
```

For many text-memory workloads, mark the text attribute with `:db/embedding true` instead of storing a separate vector datom. Datalevin keeps the original text as the source of truth and maintains the embedding index as a secondary index.

---

## 3. The "Context Graph" Pattern

True agency requires understanding the relationships between things. Instead of storing memories as isolated silos, Datalevin allows you to build a **Context Graph**.

- **Nodes**: Memorable events, users, concepts, and goals.
- **Edges**: How these things relate (e.g., "Goal A was superseded by Goal B", "User X is an expert in Topic Y").

Because Datalevin supports recursive graph traversal (Chapter 13), an agent can navigate this graph to find non-obvious connections in its memory—mimicking the "associative" nature of human thought.

---

## 4. Memory Scopes: Goal, Task, Session, Turn

Real agents need more than a bag of memories. They need scoped state with clear
lifetimes:

- **Goal**: the durable contract with the user: objective, constraints, budget,
  and success criteria.
- **Task**: an execution unit under a goal, with its own plan, runtime state,
  checkpoints, and boundary summaries.
- **Session**: an interaction channel with transient scratch context, recent
  turns, approvals, and working memory.
- **Turn**: the smallest reasoning/action cycle, useful for audit, token
  accounting, tool calls, and replay.

Model these scopes as ordinary entities and references:

```clojure
{:goal/id       #uuid "00000000-0000-0000-0000-000000000101"
 :goal/status   :goal.status/active
 :goal/contract {:objective "Monitor release readiness"
                 :success   "Open blockers are summarized before 09:00"
                 :budget    {:max-llm-calls 12}}}

{:task/id       #uuid "00000000-0000-0000-0000-000000000102"
 :task/goal     [:goal/id #uuid "00000000-0000-0000-0000-000000000101"]
 :task/state    :task.state/running
 :task/contract {:kind :task
                 :version 1
                 :steps [{:id :collect :kind :tool}
                         {:id :summarize :kind :llm}]}}

{:session/id      #uuid "00000000-0000-0000-0000-000000000103"
 :session/task    [:task/id #uuid "00000000-0000-0000-0000-000000000102"]
 :session/scratch {:notes ["User prefers short deployment summaries."]}}
```

The `:goal/contract`, `:task/contract`, and `:session/scratch` fields are good
uses for `:db.type/idoc`: they are structured documents that need to move
transactionally with the graph, but do not need every nested field promoted to a
top-level attribute.

### 4.1 Working Memory as a Projection

Working memory should not be the whole database. Treat it as a bounded,
session-scoped projection over long-term memory:

```clojure
{:wm/id      #uuid "00000000-0000-0000-0000-000000000104"
 :wm/session [:session/id #uuid "00000000-0000-0000-0000-000000000103"]
 :wm/topics  "Release readiness and database migration risk"
 :wm/config  {:max-slots 15
              :decay-factor 0.85
              :eviction-threshold 0.1}}

{:wm.slot/wm        [:wm/id #uuid "00000000-0000-0000-0000-000000000104"]
 :wm.slot/entity    [:db/ident :concept/migrations]
 :wm.slot/relevance 0.82
 :wm.slot/pinned?   false}
```

Each turn can refresh this projection by retrieving candidate concepts,
episodes, and documents, expanding one hop through the context graph, then
decaying entries that were not refreshed. Pinned slots preserve user- or
application-critical context without making every retrieved fact permanent
prompt material.

---

## 5. Architectural Implementation: The Memory Loop

A typical persistent agent memory loop follows these steps:

1.  **Perceive**: The agent receives an input.
2.  **Refresh Working Memory**: The agent retrieves relevant entities, facts,
    episodes, and documents into a bounded projection.
3.  **Recall**: The agent performs a **Hybrid Query** (Chapter 18) to find
    relevant past experiences and structured facts.
4.  **Reason**: The agent uses the recalled context to decide on an action.
5.  **Act and Audit**: Tool calls, approvals, and model usage are recorded as
    turn data.
6.  **Consolidate**: After the action, the agent transacts the new experience
    back into Datalevin, creating embeddings and updating graph relationships.

---

## Summary

Datalevin is not just a database for AI; it is a **memory substrate**. By
consolidating episodic, semantic, scoped task state, and working-memory
projections into a single engine, you reduce the impedance mismatch between the
LLM and its data, enabling agents that learn and remember across time.
