---
title: "Episodic, Semantic, and Working Memory"
chapter: 25
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 25: Episodic, Semantic, and Working Memory

Chapter 24 described the overall architecture: a persistent agent needs scoped
state, a context graph, and a transactional memory substrate. This chapter
defines the memory records themselves. The goal is to separate kinds of memory
by lifetime and purpose, while keeping them queryable together.

The three useful categories are:

1. **Episodic memory**: the chronological stream of events, turns, tool calls,
   and user interactions.
2. **Semantic memory**: generalized facts, concepts, profiles, preferences, and
   graph relationships derived from experience or curated by the application.
3. **Working memory**: the bounded, current projection that should influence the
   next few model calls.

Datalevin can store all three in one database without forcing them into one
shape. Episodes can be full-text and embedding indexed. Semantic facts can be
joined and checked with Datalog. Working-memory slots can point back to the
entities they came from, so a prompt can carry provenance instead of anonymous
text.

---

## 1. Episodic Memory: The Stream of Experience

Episodic memory is a chronological record of specific events. For an AI agent,
this usually means user messages, assistant responses, task executions, tool
calls, observations, approvals, and failures.

### 1.1 Implementing the Episode Schema

An episode should be a lightweight entity that captures the who, what, when, and
where:

```clojure
{:episode/id        #uuid "00000000-0000-0000-0000-000000000301"
 :episode/timestamp #inst "2024-02-19T10:00:00Z"
 :episode/type      :event.type/chat
 :episode/summary   "User Alice asked about vector search."
 :episode/vector    [...] ; Optional user-supplied vector
 :episode/content   "..." ; The raw log, full-text indexed
 :episode/context   [:session/id "session-123"]}
```

If Datalevin should compute embeddings for you, define `:episode/summary` or
`:episode/content` as a string attribute with `:db/embedding true`. Query it
with `embedding-neighbors` using text input. Use `:db.type/vec` and
`vec-neighbors` only when your application supplies vectors directly.

Keep raw content and summaries separate. Raw content is useful for audit and
reprocessing. Summaries are useful for retrieval and prompt assembly. If a model
or background worker creates the summary, store enough metadata to know which
episode version, model, or extraction policy produced it.

### 1.2 Temporal Retrieval

The primary way to query episodic memory is by time. Because Datalevin indexes
every attribute in AVE, range queries on `:episode/timestamp` are efficient.

<div class="multi-lang">

```clojure
;; Find all chat episodes from the last 24 hours.
(d/q '[:find ?summary
       :in $ ?since
       :where [?e :episode/type :event.type/chat]
              [?e :episode/timestamp ?ts]
              [(> ?ts ?since)]
              [?e :episode/summary ?summary]]
     db twenty-four-hours-ago)
```

```java
// Find all chat episodes from the last 24 hours.
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
# Find all chat episodes from the last 24 hours.
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
// Find all chat episodes from the last 24 hours.
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

The time cutoff should be computed by the application, not hidden inside the
query. That keeps recency policy testable: one agent may need the last hour,
another may need the last week, and a scheduled report may need everything since
the previous run.

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

This also keeps consolidation (section 5) honest. An episode should be marked
processed only after the semantic facts, summaries, or task updates derived from
it have been written. If consolidation fails, record the error and leave enough
metadata for a later maintenance run to retry or skip the episode deliberately.
Otherwise, agents accumulate invisible gaps: the raw conversation exists, but
the knowledge graph never learned from it.

---

## 2. Semantic Memory: The Graph of Knowledge

Semantic memory stores generalized knowledge: facts that are useful beyond the
single episode where they were learned. This is where user profiles, preferences,
domain concepts, policies, and concept graphs live.

### 2.1 Implementing the Knowledge Graph

Semantic memory can use idents (Chapter 11) and graph relationships (Chapter 13):

```clojure
;; Defining a concept.
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

Treat these facts as claims about the world, not as the world itself. Multiple
facts can refer to the same subject, disagree with each other, or describe
different time periods. The subject reference gives Datalog something stable to
join on, while the content and evidence fields preserve the natural-language
claim that the agent may later quote, summarize, or challenge.

### 2.2 Inference over Semantic Data

Use Datalog rules to navigate the graph. For example, an agent can infer that a
user interested in Clojure may also be interested in Datalog if the concept
graph records that Clojure is related to Datalog and the user has repeatedly
asked about Clojure.

Semantic inference should remain explicit. If a rule derives `:user/interested`
from several episodes, write the derived fact with a source rule, supporting
evidence, and confidence. This lets the apperception layer in Chapter 27 later
revise or retract the derived fact without losing the original episodes.

---

## 3. Working Memory: The Current Projection

Working memory should not be the whole database. Treat it as a bounded,
session-scoped projection over long-term memory, as well as new information just
learned in the session:

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
 :wm.slot/pinned?   false
 :wm.slot/reason    "Current release has pending schema changes."}
```

Each turn can refresh this projection by retrieving candidate concepts,
episodes, and documents, expanding one hop through the context graph, then
decaying entries that were not refreshed. Pinned slots preserve user-important
or application-critical context without making every retrieved fact permanent
prompt material.

This is a prompt-management pattern as much as a storage pattern. The database
can hold years of facts, but a model call needs a small, current, explainable
slice. Working memory gives that slice a shape: each slot has a reason to be
present, a relevance score, and an entity it came from. If the process crashes,
the projection can be restored from its last snapshot or rebuilt from long-term
memory; it is not the only copy of the knowledge.

Working memory is also where goal/task/session boundaries matter most. A fact
may be true globally but irrelevant to the current task. A session note may be
important right now but should not become a durable preference. Keeping
working-memory slots separate from long-term semantic facts prevents temporary
context from silently becoming permanent truth.

---

## 4. Session-Scoped Documents and Chunks

Agent memory often includes documents that users explicitly upload or paste:
PDFs, notes, spreadsheets, logs, and drafts. Store the parent document and its
chunks separately. Retrieve chunks, but keep the parent summary nearby for
prompt assembly.

The parent document is the stable object: it has a name, media type, checksum,
summary, and ownership or session scope. Chunks are retrieval units. They should
be sized around natural boundaries when possible, such as headings, paragraphs,
or spreadsheet sections, because those boundaries preserve enough local context
for the model to understand an excerpt.

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

Scope matters here. A document uploaded into one session may be temporary
working material, while a curated artifact or knowledge-base page may be global
semantic memory. Model that distinction explicitly with references and status
attributes instead of relying on path names or prompt instructions.

---

## 5. The Consolidation Loop: From Episode to Knowledge

A powerful agent architecture needs a consolidation loop on the side to move
data from episodic memory into semantic memory:

1. **Ingest Episodes**: Store every interaction as a raw episode.
2. **Analyze**: Periodically use an LLM or Datalog rule set to scan recent
   episodes.
3. **Abstract**: Identify recurring patterns, preferences, or durable facts.
4. **Update Semantic State**: Update user profiles, concept graphs, or task
   state with derived facts.
5. **Refresh Working Memory**: Pull the currently relevant subset into a bounded
   projection for the next turn.

By doing this, you prevent episodic memory from becoming a dumping ground and
ensure the agent's core knowledge stays relevant and structured.

This is similar in function, though not in biological mechanism, to the role
often assigned to the hippocampus in systems-consolidation theories of human
memory. In that view, the hippocampus rapidly binds new experiences and supports
their later reactivation, while longer-term knowledge is gradually reorganized
into broader cortical networks [1], and this often happens during sleep! In an
agent architecture, the episode store plays the rapid binding role, and the
consolidation job turns selected episodes into durable semantic facts, profiles,
and graph relationships.

Good consolidation is conservative. Store the extracted fact, the source
episode, the confidence, and any review status in one transaction. If the
extraction came from an LLM, consider leaving lower-confidence facts out of
working memory until they are reinforced by later evidence or reviewed by a
human. Datalevin makes that easy because raw episodes, candidate facts, review
state, and working-memory slots can live in the same database.

### 5.1 Example: Consolidating Episodes into a Knowledge Graph

Suppose an agent records three release-planning episodes:

```clojure
[{:episode/id        #uuid "00000000-0000-0000-0000-000000000311"
  :episode/timestamp #inst "2026-06-08T17:20:00Z"
  :episode/user      [:user/id "alice"]
  :episode/summary   "Alice said migration M184 changes the write path."}

 {:episode/id        #uuid "00000000-0000-0000-0000-000000000312"
  :episode/timestamp #inst "2026-06-09T07:40:00Z"
  :episode/user      [:user/id "alice"]
  :episode/summary   "The DBA asked for backup verification before M184 ships."}

 {:episode/id        #uuid "00000000-0000-0000-0000-000000000313"
  :episode/timestamp #inst "2026-06-09T08:10:00Z"
  :episode/user      [:user/id "alice"]
  :episode/summary   "CI passed, but M184 is still blocked on backup verification."}]
```

A consolidation job can extract candidate semantic facts from those episodes.
The extraction might be performed by an LLM, by rules, or by application code.
The transaction should preserve the source episode and review status:

```clojure
[{:fact/id             #uuid "00000000-0000-0000-0000-000000000321"
  :fact/subject        [:migration/id "M184"]
  :fact/kind           :fact.kind/changes-component
  :fact/object         [:component/id :component/write-path]
  :fact/content        "Migration M184 changes the write path."
  :fact/source-episode [:episode/id #uuid "00000000-0000-0000-0000-000000000311"]
  :fact/confidence     0.86
  :fact/review-status  :review.status/pending}

 {:fact/id             #uuid "00000000-0000-0000-0000-000000000322"
  :fact/subject        [:migration/id "M184"]
  :fact/kind           :fact.kind/requires-check
  :fact/object         [:check/id :check/backup-verification]
  :fact/content        "Migration M184 requires backup verification before shipping."
  :fact/source-episode [:episode/id #uuid "00000000-0000-0000-0000-000000000312"]
  :fact/confidence     0.92
  :fact/review-status  :review.status/accepted}

 {:fact/id             #uuid "00000000-0000-0000-0000-000000000323"
  :fact/subject        [:migration/id "M184"]
  :fact/kind           :fact.kind/blocked-by
  :fact/object         [:check/id :check/backup-verification]
  :fact/content        "Migration M184 is blocked on backup verification."
  :fact/source-episode [:episode/id #uuid "00000000-0000-0000-0000-000000000313"]
  :fact/confidence     0.94
  :fact/review-status  :review.status/accepted}]
```

Accepted facts can then update the knowledge graph in the same transaction or
in a second promotion step:

```clojure
[{:component/id   :component/write-path
  :component/name "Write path"}

 {:migration/id       "M184"
  :migration/status   :migration.status/blocked
  :migration/affects  [:component/id :component/write-path]
  :migration/blocked-by [:check/id :check/backup-verification]}

 {:check/id     :check/backup-verification
  :check/type   :check.type/manual
  :check/status :check.status/pending}]
```

Now the agent can answer operational questions from the graph instead of
re-reading the whole transcript:

```clojure
(d/q '[:find ?migration ?component ?check
       :where [?m :migration/id ?migration]
              [?m :migration/status :migration.status/blocked]
              [?m :migration/affects ?c]
              [?c :component/id ?component]
              [?m :migration/blocked-by ?b]
              [?b :check/id ?check]]
     db)
;; => #{["M184" :component/write-path :check/backup-verification]}
```

The episode store still matters. If the agent needs to justify the graph fact,
audit a mistaken extraction, or lower confidence after contradictory evidence,
the `:fact/source-episode` links lead back to the original episodes. The
knowledge graph gives the agent compact operational state; the episodes provide
the evidence trail.

Consolidation is not retrieval. Consolidation changes the long-term memory
model. Retrieval selects the small slice of that model needed right now. The
next chapter focuses on retrieval and context assembly.

---

## Summary

Episodic, semantic, and working memory solve different problems. Episodes
preserve history. Semantic facts organize what the agent believes or knows.
A consolidation loop move episodes into semantic facts. Working memory selects
what should matter now.

Datalevin's advantage is that these are not separate silos. They can be queried,
updated, audited, and joined in one transactional system, which gives the next
stage, recall and context assembly, a coherent substrate to work from.

## References

[1] James L. McClelland, Bruce L. McNaughton, and Randall C. O'Reilly, ["Why
There Are Complementary Learning Systems in the Hippocampus and Neocortex:
Insights from the Successes and Failures of Connectionist Models of Learning and
Memory"](https://doi.org/10.1037/0033-295X.102.3.419), *Psychological Review*
102, no. 3, 1995, pp. 419-457.
