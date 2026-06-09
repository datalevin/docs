---
title: "Episodic and Semantic Memory"
chapter: 26
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 26: Episodic and Semantic Memory

Building on the cognitive analogies from Chapter 24, this chapter dives into the practical implementation of **Episodic** and **Semantic** memory systems in Datalevin. We will look at the schema patterns and query strategies that make these systems efficient at scale.

---

## 1. Episodic Memory: The Stream of Experience

Episodic memory is a chronological record of specific events. For an AI agent, this usually means chat histories, task executions, or sensory logs.

### 1.1 Implementing the Episode Schema
An "Episode" should be a lightweight entity that captures the *who, what, when, and where*.

```clojure
{:episode/id        #uuid "00000000-0000-0000-0000-000000000301"
 :episode/timestamp #inst "2024-02-19T10:00:00Z"
 :episode/type      :event.type/chat
 :episode/summary   "User Alice asked about vector search."
 :episode/vector    [...] ; Optional user-supplied vector
 :episode/content   "..." ; The raw log (Full-text indexed)
 :episode/context   [:session/id "session-123"]}
```

If Datalevin should compute embeddings for you, define `:episode/summary` or
`:episode/content` as a string attribute with `:db/embedding true`. Query it
with `embedding-neighbors` using text input. Use `:db.type/vec` and
`vec-neighbors` only when your application supplies vectors directly.

### 1.2 Temporal Retrieval
The primary way to query episodic memory is by time. Because Datalevin indexes every attribute in AVE, range queries on `:episode/timestamp` are extremely fast.

<div class="multi-lang">

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

```java
// Find all chat episodes from the last 24 hours
Collection results = Datalevin.q(
    "[:find ?summary " +
    " :in $ ?since " +
    " :where [?e :episode/type :event.type/chat] " +
    "        [?e :episode/timestamp ?ts] " +
    "        [(> ?ts ?since)] " +
    "        [?e :episode/summary ?summary]]",
    db, twentyFourHoursAgo);
```

```python
# Find all chat episodes from the last 24 hours
results = d.q(
    '[:find ?summary '
    ' :in $ ?since '
    ' :where [?e :episode/type :event.type/chat] '
    '        [?e :episode/timestamp ?ts] '
    '        [(> ?ts ?since)] '
    '        [?e :episode/summary ?summary]]',
    db, twenty_four_hours_ago)
```

```javascript
// Find all chat episodes from the last 24 hours
const results = d.q(
    '[:find ?summary ' +
    ' :in $ ?since ' +
    ' :where [?e :episode/type :event.type/chat] ' +
    '        [?e :episode/timestamp ?ts] ' +
    '        [(> ?ts ?since)] ' +
    '        [?e :episode/summary ?summary]]',
    db, twentyFourHoursAgo);
```

</div>

### 1.3 Retention and Downsampling

Episodic memory grows without bound unless you design a lifecycle. A common
policy is:

1. Keep recent episodes at full resolution.
2. Mark episodes as processed after consolidation.
3. Retain older episodes by importance, recency, and diversity.
4. Preserve compact summaries even when raw detail is archived or deleted.

Represent the policy as data so maintenance jobs can be tested:

```clojure
{:episode/id         #uuid "00000000-0000-0000-0000-000000000302"
 :episode/summary    "Release discussion about migration lock timing."
 :episode/timestamp  #inst "2026-06-08T17:20:00Z"
 :episode/importance 0.85
 :episode/processed? true
 :episode/retention  :episode.retention/full-resolution}
```

The important point is not the exact formula. It is that retention should depend
on explicit fields, not on hidden prompt history. Datalevin can then query,
copy, compact, and audit old memory with ordinary database operations.

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

Facts extracted from episodes should carry evidence and confidence, not only
content:

```clojure
{:fact/id             #uuid "00000000-0000-0000-0000-000000000303"
 :fact/subject        [:user/id "alice"]
 :fact/content        "Alice is evaluating Datalevin for agent memory."
 :fact/source-episode [:episode/id #uuid "00000000-0000-0000-0000-000000000301"]
 :fact/confidence     0.8
 :fact/utility        0.5}
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

## 4. Session-Scoped Documents and Chunks

Agent memory often includes documents that users explicitly upload or paste:
PDFs, notes, spreadsheets, logs, and drafts. Store the parent document and its
chunks separately. Retrieve chunks, but keep the parent summary nearby for
prompt assembly.

```clojure
{:local.doc/id          #uuid "00000000-0000-0000-0000-000000000304"
 :local.doc/session     [:session/id #uuid "00000000-0000-0000-0000-000000000305"]
 :local.doc/name        "release-notes-draft.md"
 :local.doc/media-type  "text/markdown"
 :local.doc/summary     "Draft release notes for Datalevin 0.10."
 :local.doc/chunk-count 12}

{:local.doc.chunk/id      #uuid "00000000-0000-0000-0000-000000000306"
 :local.doc.chunk/doc     [:local.doc/id #uuid "00000000-0000-0000-0000-000000000304"]
 :local.doc.chunk/index   3
 :local.doc.chunk/heading "Migration notes"
 :local.doc.chunk/text    "The migration changes the write path ..."}
```

Use `:db/fulltext true` and `:db/embedding true` on both summaries and chunk
text when the agent needs hybrid recall. Chunk-level retrieval prevents a large
document from crowding out more precise evidence, while parent-level summaries
help the model understand where an excerpt came from.

---

## 5. Retrieval Strategy: The "Double-Lens" Query

When an agent needs context, it should query both memory types:

1.  **Episodic Lens**: "Have I talked about this specific topic with this user recently?"
2.  **Semantic Lens**: "What are the fundamental facts about this topic?"

<div class="multi-lang">

```clojure
(d/q '[:find ?fact
       :in $ % ?topic ?user ?since
       :where (or ;; Lens 1: Recent episodic context
                  (and [?e :episode/user ?user]
                       [?e :episode/timestamp ?ts]
                       [(> ?ts ?since)]
                       [?e :episode/summary ?fact])

                  ;; Lens 2: Semantic knowledge
                  (and [?c :concept/name ?topic]
                       [?c :concept/description ?fact]))]
     db memory-rules "Vector Search" user-id one-hour-ago)
```

```java
// Double-Lens Query: episodic + semantic
Collection results = Datalevin.q(
    "[:find ?fact " +
    " :in $ % ?topic ?user ?since " +
    " :where (or (and [?e :episode/user ?user] " +
    "                 [?e :episode/timestamp ?ts] " +
    "                 [(> ?ts ?since)] " +
    "                 [?e :episode/summary ?fact]) " +
    "            (and [?c :concept/name ?topic] " +
    "                 [?c :concept/description ?fact]))]",
    db, memoryRules, "Vector Search", userId, oneHourAgo);
```

```python
# Double-Lens Query: episodic + semantic
results = d.q(
    '[:find ?fact '
    ' :in $ % ?topic ?user ?since '
    ' :where (or (and [?e :episode/user ?user] '
    '                 [?e :episode/timestamp ?ts] '
    '                 [(> ?ts ?since)] '
    '                 [?e :episode/summary ?fact]) '
    '            (and [?c :concept/name ?topic] '
    '                 [?c :concept/description ?fact]))]',
    db, memory_rules, "Vector Search", user_id, one_hour_ago)
```

```javascript
// Double-Lens Query: episodic + semantic
const results = d.q(
    '[:find ?fact ' +
    ' :in $ % ?topic ?user ?since ' +
    ' :where (or (and [?e :episode/user ?user] ' +
    '                 [?e :episode/timestamp ?ts] ' +
    '                 [(> ?ts ?since)] ' +
    '                 [?e :episode/summary ?fact]) ' +
    '            (and [?c :concept/name ?topic] ' +
    '                 [?c :concept/description ?fact]))]',
    db, memoryRules, 'Vector Search', userId, oneHourAgo);
```

</div>

---

## Summary

By separating Episodic and Semantic memory while keeping them in the same engine, you give your AI agent the ability to be both **historically aware** and **logically grounded**.

- **Episodes** provide the "flavor" and "context" of specific interactions.
- **Semantic facts** provide the "rules" and "constants" of the agent's world.

Datalevin's multi-paradigm nature ensures that these two systems can interact seamlessly, transactionally, and with sub-millisecond performance.
