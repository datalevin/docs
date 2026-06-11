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

2.  **Semantic memory** is organized knowledge, the so called facts, about the
    world. They can often evaluated as true or false. For a person, this is
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
experience all live under the same transaction boundary, so there is no risk of
introducing data inconsistency. At retrieval time, a single Datalog query can
get relevant pieces of information in one semantically coherent fashion.

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

Because Datalevin supports recursive graph traversal (Chapter 13), an agent can
navigate this graph to find non-obvious connections in its memory—mimicking the
"associative" nature of human thought.

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
25 defines the concrete memory records: episodes, semantic facts, working-memory
slots, and session documents. Chapter 25 explains how those records are
retrieved, ranked, and assembled into a prompt. Chapter 26 covers the harder
problem of keeping long-term state coherent as new evidence arrives.

---

## Summary

Datalevin is not just a database for AI; it is a **memory substrate**. By
consolidating episodic, semantic, scoped task state, and working-memory
projections into a single engine, you reduce the impedance mismatch between the
LLM and its data, enabling agents that learn and remember across time.

## References

[1] Endel Tulving, "Episodic and Semantic Memory," in *Organization of Memory*,
Academic Press, 1972, pp. 381-403.

[2] Alan D. Baddeley and Graham Hitch, "Working Memory," in *Psychology of
Learning and Motivation*, vol. 8, Academic Press, 1974, pp. 47-89.
