---
title: "Episodic and Semantic Memory"
chapter: 33
part: "VII — Datalevin for Intelligent Systems"
---

# Chapter 33: Episodic and Semantic Memory

Building on the cognitive analogies from Chapter 31, this chapter dives into the practical implementation of **Episodic** and **Semantic** memory systems in Datalevin. We will look at the schema patterns and query strategies that make these systems efficient at scale.

---

## 1. Episodic Memory: The Stream of Experience

Episodic memory is a chronological record of specific events. For an AI agent, this usually means chat histories, task executions, or sensory logs.

### 1.1 Implementing the Episode Schema
An "Episode" should be a lightweight entity that captures the *who, what, when, and where*.

```clojure
{:episode/timestamp #inst "2024-02-19T10:00:00Z"
 :episode/type      :event.type/chat
 :episode/summary   "User Alice asked about vector search."
 :episode/vector    [...] ; Embedding of the summary or full log
 :episode/content   "..." ; The raw log (Full-text indexed)
 :episode/context   [:session/id "session-123"]}
```

### 1.2 Temporal Retrieval
The primary way to query episodic memory is by time. Because Datalevin indexes every attribute in AVE, range queries on `:episode/timestamp` are extremely fast.

```clojure
;; Find all chat episodes from the last 24 hours
(d/q '[:find ?summary
       :in $ ?since
       :where [?e :episode/type :event.type/chat]
              [?e :episode/timestamp ?ts]
              [(> ?ts ?since)]
              [?e :episode/summary ?summary]]
     db twenty-four-hours-ago)
```

---

## 2. Semantic Memory: The Graph of Knowledge

Semantic memory is the storage of generalized knowledge—facts that are true regardless of when they were learned. This is where your **Domain Model** and **Concept Graph** live.

### 2.1 Implementing the Knowledge Graph
Semantic memory uses **Idents** (Chapter 11) and **Graph Relationships** (Chapter 13).

```clojure
;; Defining a concept
{:db/ident :concept/clojure
 :concept/description "A modern, dynamic Lisp for the JVM."
 :concept/related-to [:concept/lisp :concept/jvm :concept/datalog]}
```

### 2.2 Inference over Semantic Data
Use Datalog rules to navigate this graph. For example, an agent can "understand" that if a user likes Clojure, they might also be interested in Datalog.

---

## 3. The Consolidation Loop: From Episode to Schema

A powerful agent architecture uses a **Consolidation Loop** to move data from episodic memory into semantic memory.

1.  **Ingest Episodes**: Store every interaction as a raw episode.
2.  **Analyze**: Periodically, use an LLM or a Datalog rule set to scan recent episodes.
3.  **Abstract**: Identify recurring patterns (e.g., "Alice frequently asks about performance").
4.  **Update Semantic State**: Update the `User Profile` or `Knowledge Graph` with these derived facts.

By doing this, you prevent the episodic memory from becoming a "dumping ground" and ensure the agent's core knowledge stays relevant and structured.

---

## 4. Retrieval Strategy: The "Double-Lens" Query

When an agent needs context, it should query both memory types:

1.  **Episodic Lens**: "Have I talked about this specific topic with this user recently?"
2.  **Semantic Lens**: "What are the fundamental facts about this topic?"

```clojure
(d/q '[:find ?fact
       :in $ % ?topic ?user ?now
       :where (or ;; Lens 1: Recent episodic context
                  (and [?e :episode/user ?user]
                       [?e :episode/timestamp ?ts]
                       [(> ?ts ?one-hour-ago)]
                       [?e :episode/summary ?fact])
                  
                  ;; Lens 2: Semantic knowledge
                  (and [?c :concept/name ?topic]
                       [?c :concept/description ?fact]))]
     db memory-rules "Vector Search" user-id now)
```

---

## Summary

By separating Episodic and Semantic memory while keeping them in the same engine, you give your AI agent the ability to be both **historically aware** and **logically grounded**.

- **Episodes** provide the "flavor" and "context" of specific interactions.
- **Semantic facts** provide the "rules" and "constants" of the agent's world.

Datalevin's multi-paradigm nature ensures that these two systems can interact seamlessly, transactionally, and with sub-millisecond performance.
