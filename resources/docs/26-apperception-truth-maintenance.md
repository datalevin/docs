---
title: "Apperception, Truth Maintenance, and Long-Term State"
chapter: 26
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 26: Apperception, Truth Maintenance, and Long-Term State

The previous chapters built the memory substrate and the recall pipeline. This
chapter covers the next problem: what happens when new information should change
the agent's long-term model?

Memory is about storage and retrieval. **Apperception** is about integrating new
information into a coherent internal model of the world [1]. In an AI
application, the apperception layer does not just record events; it applies
rules, model outputs, review policy, and domain logic to decide how those events
should change current beliefs, goal hierarchy, operating envelope, and future
behavior.

Datalevin is suited for storing and checking the state behind this layer because
it combines Datalog's logical rigor, graph relationships, full-text and vector
evidence, and transactional updates in one engine.

---

## 1. What Is an Apperception Engine?

In philosophy, apperception is the process of perceiving new things through the
lens of past experience to form a unified sense of self. In AI architecture, an
apperception engine is application code that:

1. **Validates**: Ensures new information is logically consistent with what is
   already known.
2. **Synthesizes**: Uses inference to derive new facts from raw input.
3. **Updates**: Modifies the agent's world model, identity, and goal hierarchy.
4. **Maintains**: Archives stale beliefs, preserves evidence, and tracks why
   current facts are believed.

This chapter deliberately comes after recall and context assembly. A retrieval
pipeline can decide what to show the LLM for this turn. Apperception decides
what should become part of long-term state after the turn.

Datalevin does not ship this engine. It supplies the transaction boundary,
indexes, query language, and constraints that the engine uses.

The term is also useful because of recent AI work on the Apperception Engine,
which frames "making sense" as constructing an interpretable symbolic theory
that explains observations while satisfying coherence or unity conditions [1].
This chapter does not implement that system directly; it borrows the same design
ideas. New observations should be integrated into an explicit, coherent,
inspectable model rather than left as disconnected prompt fragments.

---

## 2. Maintaining a Coherent World Model

A major challenge in AI is hallucination or drift. An agent may accept
contradictory information, over-promote a temporary user statement, or let old
facts linger after they have been superseded. In this application layer, use
Datalevin constraints, Datalog checks, and explicit evidence records to keep the
world model coherent.

### 2.1 Logical Validation with Datalog

Before committing a new belief to memory, the engine can run a Datalog query to
check for contradictions.

<div class="multi-lang">

```clojure
;; Query to check if a new fact contradicts existing knowledge.
(d/q '[:find ?contradiction
       :in $ ?new-fact
       :where [(is-contradictory? $ ?new-fact) ?contradiction]]
     db proposed-fact)
```

```java
// Query to check if a new fact contradicts existing knowledge.
Object results = conn.query(
    "[:find ?contradiction " +
    " :in $ ?new-fact " +
    " :where [(is-contradictory? $ ?new-fact) ?contradiction]]",
    proposedFact);
```

```python
# Query to check if a new fact contradicts existing knowledge.
results = conn.query(
    '[:find ?contradiction '
    ' :in $ ?new-fact '
    ' :where [(is-contradictory? $ ?new-fact) ?contradiction]]',
    proposed_fact)
```

```javascript
// Query to check if a new fact contradicts existing knowledge.
const results = await conn.query(
    '[:find ?contradiction ' +
    ' :in $ ?new-fact ' +
    ' :where [(is-contradictory? $ ?new-fact) ?contradiction]]',
    proposedFact);
```

</div>

The contradiction predicate may be a built-in rule, a registered UDF, or an
application function that compares a candidate fact with existing facts in the
same subject, time range, or policy scope. The important property is that the
check is explicit and repeatable. Do not rely on a prompt instruction such as
"do not contradict yourself" as the only guardrail.

### 2.2 Truth Maintenance

If new data supersedes old data, application code can use Datalevin's atomic
transactions to update the state. Because Datalevin is fact-centric, you can
retract or replace specific old facts while keeping the rest of the model intact.

This is the practical version of a classic AI idea: a **truth-maintenance
system** (TMS) records beliefs together with the reasons or dependencies that
support them, then updates the current belief set when assumptions or evidence
change [2]. In Datalevin, those dependencies can be ordinary facts: source
episodes, rule identifiers, supersession links, confidence scores, and review
status.

For agent systems, truth maintenance should usually preserve evidence even when
the current belief changes. If a user says "my deployment window is Tuesday" and
later says "move it to Thursday," the current scheduling fact should change, but
the old episode remains historically true. This distinction matters when the
agent explains a decision, audits a mistake, or reconstructs why a task was
scheduled at a particular time.

![Truth maintenance: an observation is extracted into a candidate fact, accepted into a current fact, and superseded by newer evidence, while source-episode evidence links are preserved through every status change](/images/diagrams/truth-maintenance.svg)

A practical pattern is to separate evidence from current assertions:

```clojure
{:episode/id      #uuid "00000000-0000-0000-0000-000000000701"
 :episode/summary "Alice moved the deployment window from Tuesday to Thursday."
 :episode/timestamp #inst "2026-06-09T11:00:00Z"}

{:fact/id             #uuid "00000000-0000-0000-0000-000000000702"
 :fact/subject        [:user/id "alice"]
 :fact/kind           :preference/deployment-window
 :fact/content        "Alice's deployment window is Thursday."
 :fact/source-episode [:episode/id #uuid "00000000-0000-0000-0000-000000000701"]
 :fact/status         :fact.status/current
 :fact/supersedes     [:fact/id #uuid "00000000-0000-0000-0000-000000000699"]}
```

The older fact can be marked superseded rather than deleted. Retrieval can then
prefer current facts while audit and explanation paths can still follow the
evidence chain.

Here is a small runnable helper using the Chapter 23 schema. The policy decision
to accept a fact remains outside Datalevin; this function only makes the state
transition atomic and inspectable:

```clojure
(defn current-fact-ids
  [db user-id kind]
  (d/q '[:find [?fact-id ...]
         :in $ ?user-id ?kind
         :where [?user :user/id ?user-id]
                [?fact :fact/subject ?user]
                [?fact :fact/kind ?kind]
                [?fact :fact/status :fact.status/current]
                [?fact :fact/id ?fact-id]]
       db user-id kind))

(defn accept-current-fact!
  [conn {:keys [user-id source-episode-id kind content confidence]}]
  (let [fact-id (random-uuid)
        now     (java.util.Date.)]
    (d/with-transaction [tx conn]
      (let [old-ids (current-fact-ids @tx user-id kind)
            retired (mapv (fn [old-id]
                             {:fact/id old-id
                              :fact/status :fact.status/superseded})
                           old-ids)
            current (cond-> {:fact/id fact-id
                              :fact/subject [:user/id user-id]
                              :fact/kind kind
                              :fact/content content
                              :fact/source-episode [:episode/id source-episode-id]
                              :fact/status :fact.status/current
                              :fact/confidence confidence
                              :fact/created-at now}
                      (seq old-ids)
                      (assoc :fact/supersedes
                             (mapv (fn [old-id] [:fact/id old-id]) old-ids)))]
        (d/transact! tx (conj retired current))))
    fact-id))

(let [old-episode-id (random-uuid)
      new-episode-id (random-uuid)]
  (d/transact! conn
    [{:episode/id old-episode-id
      :episode/user [:user/id "alice"]
      :episode/session [:session/id "release-standup"]
      :episode/timestamp #inst "2026-06-08T10:00:00Z"
      :episode/summary "Alice said her deployment window is Tuesday."}
     {:episode/id new-episode-id
      :episode/user [:user/id "alice"]
      :episode/session [:session/id "release-standup"]
      :episode/timestamp #inst "2026-06-09T11:00:00Z"
      :episode/summary "Alice moved the deployment window to Thursday."}])

  (accept-current-fact!
    conn
    {:user-id "alice"
     :source-episode-id old-episode-id
     :kind :preference/deployment-window
     :content "Alice's deployment window is Tuesday."
     :confidence 0.8})

  (accept-current-fact!
    conn
    {:user-id "alice"
     :source-episode-id new-episode-id
     :kind :preference/deployment-window
     :content "Alice's deployment window is Thursday."
     :confidence 0.9}))

(d/q '[:find ?status ?content
       :in $ ?kind
       :where [?fact :fact/kind ?kind]
              [?fact :fact/status ?status]
              [?fact :fact/content ?content]]
     @conn :preference/deployment-window)
```

---

## 3. Confidence, Utility, and Evidence

Long-lived agents should not treat every extracted fact as equally durable. A
practical world model records where a fact came from, how confident the system
is, and how useful that fact has been during later turns.

Confidence and utility answer different questions. Confidence asks "how likely
is this fact to be true?" Utility asks "how often does this fact help the
agent?" A low-confidence fact should not be promoted aggressively into prompts
even if it sounds relevant. A high-confidence but low-utility fact can stay in
the database without taking up working-memory space. Keeping these scores
separate prevents retrieval from becoming a popularity contest over whatever was
mentioned most recently.

```clojure
{:fact/id                #uuid "00000000-0000-0000-0000-000000000201"
 :fact/content           "Alice prefers release notes with risk summaries."
 :fact/subject           [:user/id "alice"]
 :fact/source-episode    [:episode/id #uuid "00000000-0000-0000-0000-000000000202"]
 :fact/confidence        0.74
 :fact/utility           0.6
 :fact/created-at        #inst "2026-06-01T10:00:00Z"
 :fact/last-confirmed-at #inst "2026-06-08T09:30:00Z"}
```

Confidence can be reinforced when new evidence agrees with an existing fact,
lowered when the fact is contradicted, and decayed when it has not been
confirmed for a long time. Utility captures a different signal: whether the fact
actually helped answer questions, choose tools, or personalize behavior.

```clojure
(d/q '[:find ?fact ?confidence ?utility
       :in $ ?min-confidence ?min-utility
       :where [?f :fact/content ?fact]
              [?f :fact/confidence ?confidence]
              [?f :fact/utility ?utility]
              [(< ?confidence ?min-confidence)]
              [(< ?utility ?min-utility)]]
     db 0.3 0.2)
```

This makes maintenance explicit. A background job can archive low-confidence,
low-utility facts while preserving the original episodes as historical evidence.
It can also promote repeatedly confirmed facts by increasing confidence or
utility, which gives the agent a controlled path from raw episode to durable
semantic memory.

---

## 4. Synthesizing Higher-Level Insights

Sensory data such as chat logs, tool results, and pasted documents is
low-level. Apperception requires higher-level abstractions.

Datalevin's recursive rules (Chapter 9) allow you to define these abstractions
logically:

- **Raw data**: "User Alice asked about Clojure five times this week."
- **Derived fact**: "Alice is an active Clojure learner."
- **Inference**: "The tutor should prioritize advanced Clojure resources."

By running these rules periodically or during ingestion, the application
compresses many episodic memories into a smaller set of high-value semantic
facts.

### 4.1 Example: Deriving an Active Interest

Suppose the episode store contains recent questions from Alice:

```clojure
[{:episode/id        #uuid "00000000-0000-0000-0000-000000000801"
  :episode/user      [:user/id "alice"]
  :episode/type      :event.type/question
  :episode/topic     :concept/clojure
  :episode/timestamp #inst "2026-06-02T10:00:00Z"
  :episode/summary   "Alice asked how Clojure protocols differ from interfaces."}

 {:episode/id        #uuid "00000000-0000-0000-0000-000000000802"
  :episode/user      [:user/id "alice"]
  :episode/type      :event.type/question
  :episode/topic     :concept/clojure
  :episode/timestamp #inst "2026-06-04T14:30:00Z"
  :episode/summary   "Alice asked about transducer performance."}

 {:episode/id        #uuid "00000000-0000-0000-0000-000000000803"
  :episode/user      [:user/id "alice"]
  :episode/type      :event.type/question
  :episode/topic     :concept/clojure
  :episode/timestamp #inst "2026-06-08T09:15:00Z"
  :episode/summary   "Alice asked for macro debugging examples."}]
```

A synthesis job can query for repeated interest in a topic over a time window:

```clojure
(d/q '[:find ?user (count ?episode)
       :keys user question-count
       :in $ ?topic ?since
       :where [?episode :episode/type :event.type/question]
              [?episode :episode/topic ?topic]
              [?episode :episode/user ?user]
              [?episode :episode/timestamp ?ts]
              [(> ?ts ?since)]]
     db :concept/clojure #inst "2026-06-01T00:00:00Z")
```

If the count crosses the application's threshold, the job writes a derived
insight and keeps the evidence explicit:

```clojure
{:insight/id       #uuid "00000000-0000-0000-0000-000000000811"
 :insight/kind     :insight.kind/active-interest
 :insight/subject  [:user/id "alice"]
 :insight/topic    :concept/clojure
 :insight/content  "Alice is actively learning Clojure."
 :insight/rule     :rule/active-interest-v1
 :insight/window   {:since #inst "2026-06-01T00:00:00Z"
                    :until #inst "2026-06-09T00:00:00Z"}
 :insight/evidence [[:episode/id #uuid "00000000-0000-0000-0000-000000000801"]
                    [:episode/id #uuid "00000000-0000-0000-0000-000000000802"]
                    [:episode/id #uuid "00000000-0000-0000-0000-000000000803"]]
 :insight/confidence 0.78
 :insight/status     :insight.status/current}
```

The application can also project the accepted insight into the user knowledge
graph:

```clojure
{:user/id              "alice"
 :user/interested-in   :concept/clojure
 :user/learning-state  {:topic      :concept/clojure
                        :state      :learning.state/active
                        :evidence   [:insight/id #uuid "00000000-0000-0000-0000-000000000811"]
                        :updated-at #inst "2026-06-09T00:00:00Z"}}
```

The important property is explainability. The agent can later retrieve the
compact fact "Alice is actively learning Clojure" for personalization, but the
system can still inspect the episodes, rule version, time window, and confidence
that produced it. If Alice stops asking about Clojure or starts focusing on a
different topic, a later apperception pass can supersede the insight rather than
silently rewriting history.

The compression should remain inspectable. A derived fact should carry the rule
or model version that produced it, the source episodes or facts, and the time
range over which the pattern was observed. This prevents "insight" from becoming
an unexplained assertion.

---

## 5. Identity and Long-Term State

For an agent to have a persistent style, role, or strategy, it must have a
stable model of itself.

- **Identity store**: system prompt fragments, behavioral constraints, role
  definitions, and self-description entities.
- **Performance history**: records of which strategies led to successful
  outcomes.
- **Goal hierarchy**: a graph of active, pending, blocked, superseded, and
  completed goals.

Unlike a static config file, this state is dynamic. As the application reviews
the agent's performance, it can update strategy entities in Datalevin to improve
over time. That does not mean the model should rewrite its own policy at will.
It means the application can record evidence about strategy performance, then
use rules, review workflows, or explicit approvals to update the long-term agent
configuration.

### 5.1 Example: Updating an Agent Strategy

Consider a release-writing agent whose identity and strategy are stored as data:

```clojure
{:agent/id       :agent/release-writer
 :agent/role     "Prepare release-readiness summaries."
 :agent/style    {:brevity :short
                  :tone    :direct}
 :agent/strategy [:strategy/release-summary-v1]}

{:strategy/id          :strategy/release-summary-v1
 :strategy/status      :strategy.status/current
 :strategy/instruction "Summarize CI, migrations, blockers, and next action."
 :strategy/constraints {:max-words 120
                        :include-risk? true}}
```

After several runs, the agent has performance evidence:

```clojure
[{:run/id          #uuid "00000000-0000-0000-0000-000000000901"
  :run/agent       :agent/release-writer
  :run/strategy    :strategy/release-summary-v1
  :run/outcome     :run.outcome/revised-by-user
  :run/feedback    "Summary omitted migration risk."
  :run/timestamp   #inst "2026-06-02T09:10:00Z"}

 {:run/id          #uuid "00000000-0000-0000-0000-000000000902"
  :run/agent       :agent/release-writer
  :run/strategy    :strategy/release-summary-v1
  :run/outcome     :run.outcome/revised-by-user
  :run/feedback    "Need explicit backup verification status."
  :run/timestamp   #inst "2026-06-05T09:05:00Z"}]
```

An apperception pass can detect the pattern:

```clojure
(d/q '[:find ?strategy (count ?run)
       :keys strategy revision-count
       :in $ ?since
       :where [?run :run/strategy ?strategy]
              [?run :run/outcome :run.outcome/revised-by-user]
              [?run :run/timestamp ?ts]
              [(> ?ts ?since)]]
     db #inst "2026-06-01T00:00:00Z")
```

If the revision count crosses a threshold, the system should not let the model
silently rewrite its own identity. Instead, write a proposed strategy update
with evidence and review state:

```clojure
{:strategy.proposal/id       #uuid "00000000-0000-0000-0000-000000000911"
 :strategy.proposal/agent    :agent/release-writer
 :strategy.proposal/replaces :strategy/release-summary-v1
 :strategy.proposal/body     {:instruction "Summarize CI, migrations, backup verification, blockers, and next action."
                              :constraints {:max-words 120
                                            :include-risk? true
                                            :include-backup-status? true}}
 :strategy.proposal/evidence [[:run/id #uuid "00000000-0000-0000-0000-000000000901"]
                              [:run/id #uuid "00000000-0000-0000-0000-000000000902"]]
 :strategy.proposal/status   :review.status/pending}
```

After approval, the update becomes a normal graph transition:

```clojure
[{:strategy/id     :strategy/release-summary-v1
  :strategy/status :strategy.status/superseded
  :strategy/superseded-by :strategy/release-summary-v2}

 {:strategy/id          :strategy/release-summary-v2
  :strategy/status      :strategy.status/current
  :strategy/instruction "Summarize CI, migrations, backup verification, blockers, and next action."
  :strategy/constraints {:max-words 120
                         :include-risk? true
                         :include-backup-status? true}
  :strategy/derived-from [:strategy.proposal/id #uuid "00000000-0000-0000-0000-000000000911"]}

 {:agent/id       :agent/release-writer
  :agent/strategy :strategy/release-summary-v2}]
```

This is long-term state evolution without prompt drift. The agent's identity and
strategy changed, but the change is explainable: there is performance evidence,
a proposal, an approval state, and a supersession link from the old strategy to
the new one.

---

## 6. Operating Envelopes

Do not hide hard constraints in prompt prose. Store them as data, resolve them
before each turn, and attach the resolved envelope to the turn record.

An operating envelope is the effective set of constraints for a turn: tool
permissions, model budgets, output style, privacy limits, retry policy, and any
domain-specific guardrails. The model may see a summarized version of the
envelope, but enforcement should happen in application code before tools run and
before expensive model calls are made.

This is the agentic version of top-down attention. A pure bottom-up system waits
for input and reacts to whatever looks salient. A goal-directed system asks a
different question: given the current objective, which inputs deserve attention,
which memories should be recalled, and which tools are even eligible? The
operating envelope gives that top-down pressure a concrete representation.

A useful precedence order is:

```text
organization policy > goal contract > task constraints > user preferences > session scratch
```

Each layer can live in Datalevin as an `idoc`:

```clojure
{:policy/id      :policy/org
 :policy/source  :policy.source/organization
 :policy/envelope {:tools {:email-send :require-approval}
                   :limits {:max-llm-calls 20}}}

{:goal/id       #uuid "00000000-0000-0000-0000-000000000203"
 :goal/contract {:objective "Prepare a weekly account update"
                 :envelope {:style {:brevity :short}
                            :limits {:max-llm-calls 8}}}}

{:task/id          #uuid "00000000-0000-0000-0000-000000000204"
 :task/goal        [:goal/id #uuid "00000000-0000-0000-0000-000000000203"]
 :task/constraints {:tools {:calendar-event-create :deny}}}
```

The resolved envelope is ordinary data, so it can be audited, tested, replayed,
and queried. This is especially important for autonomous or scheduled work,
where the agent may run without a human watching every turn.

Precedence is important because the sources will sometimes disagree. A user
preference may ask for terse replies, while a task constraint may require a
detailed audit note. An organization policy may deny a tool that a task would
otherwise allow. Resolving these conflicts outside the prompt makes the behavior
predictable and gives tests a stable target.

The same envelope can also drive retrieval. For example, a task that is waiting
for user approval should retrieve the pending decision and the evidence behind
it, while a task that is executing a scheduled report should retrieve the last
successful run and the configured reporting interval. In both cases, the goal
and task state shape what the agent perceives as relevant.

---

## Summary

An apperception layer is application code that turns stored memory into
maintained long-term state. Datalevin supplies the durable facts, indexes,
constraints, and transaction boundary that make that layer auditable.

- **Consistency**: Datalog checks keep the model logically sound.
- **Inference**: Rules derive knowledge that is not explicitly stated.
- **Evidence**: Episodes and source links explain why current facts exist.
- **Evolution**: Graphs allow the world model, goals, and identity to grow over
  time.
- **Policy**: Operating envelopes keep behavioral constraints inspectable and
  separate from prompt text.

By building this layer on Datalevin, you give the AI application a substrate for
coherent long-term state without hiding the maintenance policy inside prompt
text.

## References

[1] Richard Evans, Jose Hernandez-Orallo, Johannes Welbl, Pushmeet Kohli, and
Marek Sergot, ["Making sense of sensory
input"](https://doi.org/10.1016/j.artint.2020.103438), *Artificial Intelligence*
293, 2021, article 103438.

[2] Jon Doyle, "A Truth Maintenance System", *Artificial Intelligence* 12(3),
251-272, 1979.
