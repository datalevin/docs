---
title: "Persistent Agent Memory Architectures"
chapter: 23
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 23: Persistent Agent Memory Architectures

The greatest limitation of modern Large Language Models (LLMs) is their lack of
state. While they possess vast general knowledge, they are "reset" at the start
of every session. To build true **Intelligent Agents**, we must provide them
with a persistent memory—a place to store past experiences, learned facts, and
evolving goals.

This chapter explores how Datalevin serves as a unified substrate for agent
memory, bridging the gap between unstructured semantic search and structured
logical reasoning.

---

## Part VI Contract: What Datalevin Provides

Part VI is about application architecture on top of Datalevin. It is not a
description of a bundled agent framework. Datalevin gives you the database
capabilities needed to build durable agent memory:

- ACID transactions over facts, documents, indexes, and task state.
- Datalog queries, rules, lookup refs, schema, and graph traversal.
- Full-text search, vector search, embedding indexes, and idoc fields.
- Transaction reports, listeners, WAL, replication, server access, and MCP
  tooling for operational integration.

You build the agent-specific machinery:

- The ingestion loop that turns messages, tool calls, and observations into
  episode records.
- The consolidation job that extracts candidate facts from episodes.
- The working-memory refresh policy, slot scoring, and eviction rules.
- The apperception or truth-maintenance layer that decides which facts become
  current, superseded, rejected, or pending review.
- The operating envelope, tool authorization checks, budgets, and model-calling
  policy.

![The Datalevin/application boundary: Datalevin provides ACID transactions, Datalog and graph, search indexes, and operations, while your application code builds the ingestion loop, consolidation, working memory, truth maintenance, and policy](/images/diagrams/agent-memory-boundary.svg)

The examples in this part model those pieces as ordinary entities, references,
attributes, and idocs. When a snippet demonstrates a basic Datalevin API call,
it may use the same multi-language style as earlier chapters. When a snippet
shows an end-to-end application loop, it is Clojure-first so the control flow,
transactions, and data shapes stay runnable in one place. The same model can be
implemented from Java, Python, JavaScript, or over the server API by composing
the same `transact`, `query`, `pull`, full-text, vector, and embedding
operations shown earlier in the book. For exact language coverage, use the
compatibility matrix linked in Chapter 2. The notable gaps for Part VI examples
are the same as elsewhere: JavaScript does not expose a Datalog transaction
callback, and staged mutation of existing Entity objects is Clojure-only.

Many of the patterns in Part VI were informed by Xia, a separate open-source
personal AI assistant project [3]. Xia is a reference implementation for these
agent-memory ideas: long-term memory, scoped task state, local tool boundaries,
scheduled work, and persistent assistant state. It is not part of Datalevin and
does not change what Datalevin provides; it shows how an application can build
the agent-specific machinery on top of ordinary database capabilities.

The following minimal in-memory setup is enough to run many of the Clojure
examples in Part VI:

```clojure
(require '[datalevin.core :as d])

(def memory-schema
  {:user/id          {:db/valueType :db.type/string
                     :db/unique    :db.unique/identity}
   :session/id       {:db/valueType :db.type/string
                     :db/unique    :db.unique/identity}
   :session/user     {:db/valueType :db.type/ref}
   :episode/id       {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :episode/user     {:db/valueType :db.type/ref}
   :episode/session  {:db/valueType :db.type/ref}
   :episode/timestamp {:db/valueType :db.type/instant}
   :episode/summary  {:db/valueType :db.type/string
                     :db/fulltext  true}
   :fact/id          {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :fact/subject     {:db/valueType :db.type/ref}
   :fact/kind        {:db/valueType :db.type/keyword}
   :fact/source-episode {:db/valueType :db.type/ref}
   :fact/supersedes  {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :fact/status      {:db/valueType :db.type/keyword}
   :fact/content     {:db/valueType :db.type/string
                     :db/fulltext  true}
   :fact/confidence  {:db/valueType :db.type/double}
   :fact/created-at  {:db/valueType :db.type/instant}
   :task/id          {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :task/state       {:db/valueType :db.type/keyword}
   :task/contract    {:db/valueType :db.type/idoc}
   :wm/id            {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :wm/session       {:db/valueType :db.type/ref}
   :wm.slot/wm       {:db/valueType :db.type/ref}
   :wm.slot/entity   {:db/valueType :db.type/ref}
   :wm.slot/relevance {:db/valueType :db.type/double}
   :wm.slot/reason   {:db/valueType :db.type/string}
   :wm.slot/pinned?  {:db/valueType :db.type/boolean}})

(def conn
  (d/create-conn nil memory-schema {:kv-opts {:inmemory? true}}))

(d/transact! conn
  [{:user/id "alice"}
   {:session/id "release-standup"
    :session/user [:user/id "alice"]}])
```

## 1. The Human Memory Analogy

When people say "memory" in ordinary conversation, they often mean one thing:
the ability to keep things for later, similar to a box to hold things. Cognitive
science uses a more useful distinction. Human memory is not a single box where
all remembered material has the same shape and lifetime. Remembering what
happened yesterday, knowing that Paris is a city, and keeping a phone number in
mind long enough to type it are different operations. They interact, but they
are not the same operation.

This distinction is useful for agent design because LLM applications fail in
similar ways when all context is treated as one undifferentiated pile of text.
A chat transcript, a durable user preference, a current task constraint, and a
retrieved document excerpt should not have the same authority. Some information
is evidence. Some is current state. Some is background knowledge. Some is only a
temporary focus for the next model call.

Cognitive science often distinguishes three types of memory:

1.  **Episodic memory** is memory of specific events that often have a time
    dimension. For a person, this is "what happened when I met Alice yesterday."
    For an agent, it is the stream of interactions, tool calls, observations,
    approvals, failures, and task steps. Episodic memory answers questions such
    as "what did the user ask last time?", "what did the tool call this morning
    return?", and "why did the agent make that decision last night?" In
    Datalevin, this is usually powered by timestamped entities, full-text
    search, embedding/vector search, and links to sessions or tasks.

2.  **Semantic memory** is organized knowledge, the so-called facts, about the
    world. They can often be evaluated as true or false. For a person, this is
    knowing that Clojure is a Lisp, or that a release must pass tests before
    deployment. For an agent, it includes user profiles, durable preferences,
    domain facts, concept graphs, policies, and learned relationships. Semantic
    memory answers questions such as "what is true about this user?", "which
    concepts are related?", and "which new facts supersede older facts?" In
    Datalevin, this is represented with triples, Datalog, idents, schema, and
    graph relationships.

3.  **Working memory** is the small amount of information currently active for a
    task. For a person, this is the few things being held in mind while solving
    a problem. For an agent, it is the bounded context assembled for the current
    turn: active goal, task state, relevant facts, selected episodes, pending
    tool results, and output constraints. Working memory answers "what should
    the model pay attention to right now?" In Datalevin, it can be modeled with
    session-scoped entities, idocs, triples, and links back to the long-term
    records they summarize.

This analogy follows standard cognitive-science distinctions: Tulving's
episodic/semantic memory distinction [1] and Baddeley and Hitch's
working-memory model [2].

The analogy should not be taken too literally. A database is not a brain, and an
LLM does not have human memory. The value of the analogy is architectural. It
reminds us not to store everything as one transcript and not to paste everything
into one prompt. Episodes, semantic facts, and working-memory projections have
different lifetimes, different query patterns, and different authority.

By using Datalevin, an agent can query past experiences, organized world
knowledge, and current task state through multiple lenses at the same time. That
is the foundation for the rest of Part VI.

---

## 2. Perception Is Not Organized Knowledge

Modern generative models are very strong perceptual systems, which are bottom-up
systems that map text, images, audio, and other raw inputs into labels,
concepts, and their high dimensional vector representations. This is enormously
useful, but it is not the same thing as an organized world model. A model can
recognize that two passages are similar without connecting with semantic
knowledge, such as which facts are current, which goal they support, who is
allowed to see them, or which old belief they supersede.

Agent memory needs both bottom-up and top-down machinery. Bottom-up perception
turns messy input into candidates: entities, summaries, embeddings, extracted
facts, classifications, and possible next actions. Top-down state decides what
matters: the active goal, the task contract, the user's preferences, the current
agenda, and the policies that bound tool use. Datalevin is useful because it can
store the perceptual outputs and the symbolic control state under one
transactional model.

This distinction also explains why a plain vector database is not enough for
long-lived agents. Vectors help recall things that feel similar. They do not, by
themselves, maintain propositions, graph structure, task state, authority, or
evidence. Those are explicit knowledge-organization problems.

---

## 3. Why a Unified Memory Engine?

Many agent architectures attempt to combine a Vector DB (for similarity) with a
SQL DB (for state). This "polyglot" approach creates significant friction:

- **Consistency**: The vector index and the relational database can get out of
  sync.
- **Complexity**: The agent must manage two different APIs and data models.
- **Latency**: Multiple network hops to different services slow down the
  "think-act" loop.

**The Datalevin Advantage**: In Datalevin, the text description, optional
user-supplied vector, automatic embedding index, and logical facts about an
experience can live under the same transaction boundary. That removes a common
source of inconsistency between separately updated stores. It does not decide
what an experience means; your ingestion and consolidation code does that. At
retrieval time, a Datalog query can combine the relevant pieces of information
in one semantically coherent fashion.

```clojure
;; An "Experience" entity in Datalevin
{:experience/id      #uuid "00000000-0000-0000-0000-000000000001"
 :experience/text    "I helped the user debug a Clojure macro today."
 :experience/vector  [...] ; Optional user-supplied vector
 :experience/user    [:user/id "alice"]
 :experience/success true
 :experience/topics  [:clojure :macros]}
```

For many text-memory workloads, mark the text attribute with `:db/embedding
true` instead of storing a separate vector datom. Datalevin keeps the original
text as the source of truth and maintains the embedding index as a secondary
index.

---

## 4. The "Context Graph" Pattern

True agency requires understanding the relationships between things. Instead of
storing memories as isolated silos, Datalevin allows you to build a **Context
Graph**.

- **Nodes**: Memorable events, users, concepts, and goals.
- **Edges**: How these things relate (e.g., "Goal A was superseded by Goal B",
  "User X is an expert in Topic Y").

Because Datalevin supports recursive graph traversal (Chapter 13), application
code can navigate this graph to find non-obvious connections in stored memory.
The graph supplies explainable associations; the agent runtime decides how those
associations affect a turn.

---

## 5. Memory Scopes: Goal, Task, Session, Turn

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

Keeping these scopes separate prevents a common failure mode in agent systems:
temporary context gets promoted into durable truth, while durable instructions
get buried in chat history. A task may need a retry counter, a partially
completed plan, or a tool result cache; those are not the same thing as the
user's long-term goal. A session may contain copied notes, a recap, or a pending
approval; those should influence the next turn, but they should not silently
rewrite the user's preferences or the goal contract.

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

Use top-level attributes for fields you will join, filter, or index frequently:
status, owner, timestamps, parent references, and current lifecycle state. Use
`idoc` fields for nested data that is usually loaded as a whole, such as a task
spec or an operating envelope. This gives the agent a durable object model
without forcing every prompt-facing detail into schema.

The rest of Part VI expands this sketch in the order an agent uses it. Chapter
24 defines the concrete memory records: episodes, semantic facts, working-memory
slots, and session documents. Chapter 25 explains how those records are
retrieved, ranked, and assembled into a prompt. Chapter 26 covers the harder
problem of keeping long-term state coherent as new evidence arrives.

---

## Summary

Datalevin is a database substrate for AI memory, not an agent runtime. By
consolidating episodic records, semantic facts, scoped task state, and
working-memory projections into a single engine, you reduce the impedance
mismatch between the LLM-facing application and its durable state. Learning,
review, consolidation, and action selection remain application responsibilities.

## References

[1] Endel Tulving, "Episodic and Semantic Memory," in *Organization of Memory*,
Academic Press, 1972, pp. 381-403.

[2] Alan D. Baddeley and Graham Hitch, "Working Memory," in *Psychology of
Learning and Motivation*, vol. 8, Academic Press, 1974, pp. 47-89.

[3] Huahai Yang, [Xia](https://github.com/huahaiy/xia), GitHub repository.
