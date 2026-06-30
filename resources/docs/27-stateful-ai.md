---
title: "Stateful AI Applications"
chapter: 27
part: "VI — Datalevin for Intelligent Systems"
web: false
---

# Chapter 27: Stateful AI Applications

The true potential of AI is not found in isolated chat sessions, but in
applications that maintain a persistent state. A "Stateful AI" application is
one where the AI's knowledge, goals, and behavior evolve alongside its users.

This chapter synthesizes the Part VI arc into practical application examples:
memory architecture, concrete memory records, recall and context assembly,
truth maintenance, and application-level task control.


## 1. Pattern: The "Database-as-Environment"

In most applications, the database is treated as a passive storage layer. In a
Stateful AI application, a better pattern is **database-as-environment-state**:
the runtime treats durable database facts as the inspectable state of the
application environment.

- The agent runtime reads and writes durable environment state in Datalevin.
- Every interaction can update episode records, task state, audit records, or
  semantic facts.
- Persona, strategy, permissions, and style are entities interpreted by
  application code, not hidden prompt text.

An agent runtime also needs a control loop. In the standard agent model, an
agent receives percepts from its environment, updates internal state, and chooses
actions [1]. In application terms, perceptors convert external input into
structured observations, actuators call tools or services, and a central
controller decides which task, agenda item, or exception should run next. Memory
keeps the loop from starting over every turn, and plugins extend the loop with
domain-specific skills.

Datalevin is helpful in this control layer because the agenda can be data. For
example, Juji's agent-platform [2] has a main stack for the active task, an
agenda queue for planned work, an ad-lib queue for handling opportunistic
contingencies, and an exception queue as the last resort of guardrails. In
Datalevin, these can be task entities with different state, priority, and
scheduling attributes. The important modeling point is that each control surface
is durable, queryable state, regardless of the names your application uses.


## 2. Pattern: The Memory Loop

A typical persistent agent memory loop follows these steps:

1. **Perceive**: The agent receives input and records enough metadata to replay
   the turn later.
2. **Refresh Working Memory**: The agent retrieves relevant entities, facts,
   episodes, and documents into a bounded projection.
3. **Recall**: The agent performs a hybrid query (Chapter 25) to find relevant
   past experiences and structured facts.
4. **Reason**: The agent uses the recalled context to decide on an action.
5. **Act and Audit**: Tool calls, approvals, and model usage are recorded as
   turn data.
6. **Consolidate**: After the action, the agent transacts the new experience
   back into Datalevin, creating embeddings and updating graph relationships.

The loop is deliberately transactional at the boundaries. A turn should not
write a new episode without the tool calls, approvals, and task state that
explain it. Likewise, consolidation should preserve a link back to the source
episode so later maintenance jobs can explain why a semantic fact exists. This
is what makes memory useful for debugging and not only for retrieval.

In application code, this loop is also where policy enforcement belongs. The
model can propose an action, but the system should check the operating envelope,
tool permissions, budgets, and task state before executing it. The database is
the shared environment that makes the loop resumable and inspectable.

A minimal turn boundary can reuse the helper functions introduced in Chapters
24 and 25. This does not call a model; it shows the durable state transition
around one observed turn:

<div class="multi-lang">

```clojure
(defn memory-turn!
  [conn {:keys [user-id session-id summary fact-kind fact-content confidence]}]
  (let [{episode-id :episode/id
         fact-id    :fact/id}
        (record-episode-and-candidate-fact!
          conn
          {:user-id user-id
           :session-id session-id
           :summary summary
           :fact-kind fact-kind
           :fact-content fact-content
           :confidence confidence})

        wm-id
        (materialize-working-memory!
          conn
          session-id
          [{:fact-id fact-id
            :relevance 1.0
            :reason "Newly observed fact from this turn."
            :pinned? false}])]
    {:episode/id episode-id
     :fact/id fact-id
     :wm/id wm-id}))

(memory-turn!
  conn
  {:user-id "alice"
   :session-id "release-standup"
   :summary "Alice asked for release notes to emphasize migration risk."
   :fact-kind :preference/release-notes
   :fact-content "Alice prefers release notes that call out migration risk."
   :confidence 0.74})
```

```java
import datalevin.Connection;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

BiFunction<Connection, Map<String, Object>, Map<?, ?>> memoryTurn =
    (c, input) -> {
        Map<?, ?> recorded =
            recordEpisodeAndCandidateFact.apply(c, input);

        Object factId = recorded.get(":fact/id");
        Object wmId = materializeWorkingMemory(
            c,
            input.get("session-id"),
            List.of(Map.of(
                "fact-id", factId,
                "relevance", 1.0,
                "reason", "Newly observed fact from this turn.",
                "pinned?", false)));

        return Map.of(
            ":episode/id", recorded.get(":episode/id"),
            ":fact/id", factId,
            ":wm/id", wmId);
    };

memoryTurn.apply(conn, Map.of(
    "user-id", "alice",
    "session-id", "release-standup",
    "summary", "Alice asked for release notes to emphasize migration risk.",
    "fact-kind", ":preference/release-notes",
    "fact-content",
    "Alice prefers release notes that call out migration risk.",
    "confidence", 0.74));
```

```python
def memory_turn(conn, *, user_id, session_id, summary,
                fact_kind, fact_content, confidence):
    recorded = record_episode_and_candidate_fact(
        conn,
        user_id=user_id,
        session_id=session_id,
        summary=summary,
        fact_kind=fact_kind,
        fact_content=fact_content,
        confidence=confidence)

    fact_id = recorded[":fact/id"]
    wm_id = materialize_working_memory(
        conn,
        session_id,
        [{
            "fact_id": fact_id,
            "relevance": 1.0,
            "reason": "Newly observed fact from this turn.",
            "pinned": False,
        }])

    return {
        ":episode/id": recorded[":episode/id"],
        ":fact/id": fact_id,
        ":wm/id": wm_id,
    }

memory_turn(
    conn,
    user_id="alice",
    session_id="release-standup",
    summary="Alice asked for release notes to emphasize migration risk.",
    fact_kind=":preference/release-notes",
    fact_content="Alice prefers release notes that call out migration risk.",
    confidence=0.74)
```

```javascript
async function memoryTurn(
  conn,
  { userId, sessionId, summary, factKind, factContent, confidence }
) {
  const recorded = await recordEpisodeAndCandidateFact(conn, {
    userId,
    sessionId,
    summary,
    factKind,
    factContent,
    confidence
  });

  const factId = recorded[":fact/id"];
  const wmId = await materializeWorkingMemory(conn, sessionId, [
    {
      factId,
      relevance: 1.0,
      reason: "Newly observed fact from this turn.",
      pinned: false
    }
  ]);

  return {
    ":episode/id": recorded[":episode/id"],
    ":fact/id": factId,
    ":wm/id": wmId
  };
}

await memoryTurn(conn, {
  userId: "alice",
  sessionId: "release-standup",
  summary: "Alice asked for release notes to emphasize migration risk.",
  factKind: ":preference/release-notes",
  factContent: "Alice prefers release notes that call out migration risk.",
  confidence: 0.74
});
```

</div>

The application still decides when a candidate fact is promoted, rejected, or
sent for review. Datalevin stores the records, links, and working-memory
projection that make that decision auditable.


## 3. Scenario: The Adaptive Learning Assistant

Imagine an AI tutor that helps a student learn Clojure over six months.

- **Episodic Memory**: Every question the student asks and every mistake they
  make is stored as an episode.
- **Semantic State**: A "Student Knowledge Graph" tracks which concepts (e.g.,
  "Recursion", "Macros", "Transducers") the student has mastered.

The tutor also needs stateful logic. Given a student's current level, the
concepts they have mastered, and the prerequisites for each concept, a query can
select concepts that are good candidates for the next lesson:

<div class="multi-lang">

```clojure
;; Find concepts the student hasn't mastered but has prerequisite knowledge for
(d/q '[:find ?concept
       :where [?student :student/level ?level]
              [?concept :concept/difficulty ?d]
              [(< ?d ?level)]
              (not [?student :student/mastered ?concept])
              [?concept :concept/prereq ?pre]
              [?student :student/mastered ?pre]]
     db)
```

```java
// Find concepts the student hasn't mastered but has prerequisite knowledge for
Object results = conn.query(
    "[:find ?concept " +
    " :where [?student :student/level ?level]" +
    "        [?concept :concept/difficulty ?d]" +
    "        [(< ?d ?level)]" +
    "        (not [?student :student/mastered ?concept])" +
    "        [?concept :concept/prereq ?pre]" +
    "        [?student :student/mastered ?pre]]");
```

```python
# Find concepts the student hasn't mastered but has prerequisite knowledge for
results = conn.query(
    """[:find ?concept
        :where [?student :student/level ?level]
               [?concept :concept/difficulty ?d]
               [(< ?d ?level)]
               (not [?student :student/mastered ?concept])
               [?concept :concept/prereq ?pre]
               [?student :student/mastered ?pre]]""")
```

```javascript
// Find concepts the student hasn't mastered but has prerequisite knowledge for
const results = await conn.query(
    `[:find ?concept
      :where [?student :student/level ?level]
             [?concept :concept/difficulty ?d]
             [(< ?d ?level)]
             (not [?student :student/mastered ?concept])
             [?concept :concept/prereq ?pre]
             [?student :student/mastered ?pre]]`);
```

</div>

The query is not the whole tutor. The application still decides how to explain
the recommendation, how to handle motivation and pacing, and when to override
the next-concept suggestion. Datalevin's role is to keep the student's actual
history, current semantic state, and recommendation logic in one queryable
environment.


## 4. Where the Same Pattern Applies

The tutor is only one concrete setting. The same stateful shape appears whenever
an AI system needs durable memory, explicit tasks, and auditable tool use:

- **Multi-agent collaboration workspace**: Coding, testing, and documentation
  agents can share one Datalevin database. Tasks become entities, dependencies
  become graph relationships, and Datalevin Server RBAC (Chapter 22) can keep
  each agent inside its allowed authority.
- **Self-updating knowledge base**: A documentation site can store search
  queries and "not helpful" feedback as episodes, detect repeated gaps, and open
  review tasks for humans before generated text becomes documentation.
- **MCP-backed agent tools**: `dtlv mcp` can expose local or remote Datalevin
  databases through a `stdio` MCP server. The tool boundary remains explicit:
  writes are disabled by default, structured responses are machine-readable, and
  large results include truncation metadata.

These are not separate products Datalevin ships. They are application patterns
built from ordinary facts, transactions, queries, rules, permissions, and
operational APIs. The next two sections make those patterns more concrete by
showing durable task records and tool-audit records.


## 5. Pattern: Durable Tasks and Board Projections

For long-running work, store the plan as data. A task contract should be stable
and inspectable, while runtime state changes independently as the task runs.

The contract is what the system intends to do. Runtime state is what has
happened so far. Keeping them separate makes resumption and review much easier:
the agent can retry a failed step without rewriting the plan, and a human can
inspect whether the task is still following the original objective. Stable step
ids are important because later outputs, approvals, and boundary summaries need
to point back to the same step even after display text changes.

<div class="multi-lang">

```clojure
{:task/id       #uuid "00000000-0000-0000-0000-000000000501"
 :task/type     :task.type/task
 :task/state    :task.state/ready
 :task/title    "Prepare weekly release report"
 :task/contract {:kind :task
                 :version 1
                 :goal "Prepare weekly release report"
                 :steps [{:id :collect-data
                          :kind :tool
                          :tool :release-query}
                         {:id :summarize
                          :kind :llm
                          :depends-on :collect-data}
                         {:id :approve
                          :kind :approval}
                         {:id :publish
                          :kind :tool
                          :tool :workspace-publish
                          :depends-on :approve}]}
 :task/runtime  {:current-step :collect-data
                 :attempts {}
                 :outputs {}}}
```

```java
import java.util.List;
import java.util.Map;
import java.util.UUID;

Map<?, ?> task = Map.of(
    ":task/id", UUID.fromString("00000000-0000-0000-0000-000000000501"),
    ":task/type", ":task.type/task",
    ":task/state", ":task.state/ready",
    ":task/title", "Prepare weekly release report",
    ":task/contract", Map.of(
        ":kind", ":task",
        ":version", 1,
        ":goal", "Prepare weekly release report",
        ":steps", List.of(
            Map.of(
                ":id", ":collect-data",
                ":kind", ":tool",
                ":tool", ":release-query"),
            Map.of(
                ":id", ":summarize",
                ":kind", ":llm",
                ":depends-on", ":collect-data"),
            Map.of(
                ":id", ":approve",
                ":kind", ":approval"),
            Map.of(
                ":id", ":publish",
                ":kind", ":tool",
                ":tool", ":workspace-publish",
                ":depends-on", ":approve"))),
    ":task/runtime", Map.of(
        ":current-step", ":collect-data",
        ":attempts", Map.of(),
        ":outputs", Map.of()));
```

```python
from uuid import UUID

task = {
    ":task/id": UUID("00000000-0000-0000-0000-000000000501"),
    ":task/type": ":task.type/task",
    ":task/state": ":task.state/ready",
    ":task/title": "Prepare weekly release report",
    ":task/contract": {
        ":kind": ":task",
        ":version": 1,
        ":goal": "Prepare weekly release report",
        ":steps": [
            {":id": ":collect-data",
             ":kind": ":tool",
             ":tool": ":release-query"},
            {":id": ":summarize",
             ":kind": ":llm",
             ":depends-on": ":collect-data"},
            {":id": ":approve",
             ":kind": ":approval"},
            {":id": ":publish",
             ":kind": ":tool",
             ":tool": ":workspace-publish",
             ":depends-on": ":approve"},
        ],
    },
    ":task/runtime": {
        ":current-step": ":collect-data",
        ":attempts": {},
        ":outputs": {},
    },
}
```

```javascript
import { readEdn } from "datalevin-node";

const task = {
  ":task/id": await readEdn(
    '#uuid "00000000-0000-0000-0000-000000000501"'
  ),
  ":task/type": ":task.type/task",
  ":task/state": ":task.state/ready",
  ":task/title": "Prepare weekly release report",
  ":task/contract": {
    ":kind": ":task",
    ":version": 1,
    ":goal": "Prepare weekly release report",
    ":steps": [
      {
        ":id": ":collect-data",
        ":kind": ":tool",
        ":tool": ":release-query"
      },
      {
        ":id": ":summarize",
        ":kind": ":llm",
        ":depends-on": ":collect-data"
      },
      {
        ":id": ":approve",
        ":kind": ":approval"
      },
      {
        ":id": ":publish",
        ":kind": ":tool",
        ":tool": ":workspace-publish",
        ":depends-on": ":approve"
      }
    ]
  },
  ":task/runtime": {
    ":current-step": ":collect-data",
    ":attempts": {},
    ":outputs": {}
  }
};
```

</div>

A Kanban board, scheduler, or branch-worker queue should be a projection over
tasks, not a separate store. For example:

<div class="multi-lang">

```clojure
{:task/id           #uuid "00000000-0000-0000-0000-000000000502"
 :task/board        {:visible? true
                     :lane :board.lane/open
                     :priority :normal}
 :task/claim-token  "opaque-worker-token"
 :task/claimed-by   [:agent/id :agent/release-writer]}
```

```java
import java.util.List;
import java.util.Map;
import java.util.UUID;

Map<?, ?> boardTask = Map.of(
    ":task/id", UUID.fromString("00000000-0000-0000-0000-000000000502"),
    ":task/board", Map.of(
        ":visible?", true,
        ":lane", ":board.lane/open",
        ":priority", ":normal"),
    ":task/claim-token", "opaque-worker-token",
    ":task/claimed-by", List.of(":agent/id", ":agent/release-writer"));
```

```python
from uuid import UUID

board_task = {
    ":task/id": UUID("00000000-0000-0000-0000-000000000502"),
    ":task/board": {
        ":visible?": True,
        ":lane": ":board.lane/open",
        ":priority": ":normal",
    },
    ":task/claim-token": "opaque-worker-token",
    ":task/claimed-by": [":agent/id", ":agent/release-writer"],
}
```

```javascript
import { readEdn } from "datalevin-node";

const boardTask = {
  ":task/id": await readEdn(
    '#uuid "00000000-0000-0000-0000-000000000502"'
  ),
  ":task/board": {
    ":visible?": true,
    ":lane": ":board.lane/open",
    ":priority": ":normal"
  },
  ":task/claim-token": "opaque-worker-token",
  ":task/claimed-by": [":agent/id", ":agent/release-writer"]
};
```

</div>

Workers can claim and update tasks transactionally. If a claimed task requires
a token for later updates, Datalevin gives you an optimistic ownership check
without a separate lock table.

This keeps coordination boring in the right way. The board is a view of durable
work, not another source of truth that can drift from the task table. A
scheduler can create or wake tasks by changing task state. A worker can claim a
task by adding claim fields. A UI can render lanes by querying task metadata.
All of those views use the same facts.

Task boundaries should also be durable. When a task pauses, completes, or hands
off to another agent, write a small boundary document:

<div class="multi-lang">

```clojure
{:task/id       #uuid "00000000-0000-0000-0000-000000000501"
 :task/boundary {:summary "Data was collected; approval is pending."
                 :resume-hint "Resume at :approve."
                 :next-step :approve
                 :open-questions ["Should this go to the public changelog?"]}}
```

```java
import java.util.List;
import java.util.Map;
import java.util.UUID;

Map<?, ?> taskBoundary = Map.of(
    ":task/id", UUID.fromString("00000000-0000-0000-0000-000000000501"),
    ":task/boundary", Map.of(
        ":summary", "Data was collected; approval is pending.",
        ":resume-hint", "Resume at :approve.",
        ":next-step", ":approve",
        ":open-questions",
        List.of("Should this go to the public changelog?")));
```

```python
from uuid import UUID

task_boundary = {
    ":task/id": UUID("00000000-0000-0000-0000-000000000501"),
    ":task/boundary": {
        ":summary": "Data was collected; approval is pending.",
        ":resume-hint": "Resume at :approve.",
        ":next-step": ":approve",
        ":open-questions": ["Should this go to the public changelog?"],
    },
}
```

```javascript
import { readEdn } from "datalevin-node";

const taskBoundary = {
  ":task/id": await readEdn(
    '#uuid "00000000-0000-0000-0000-000000000501"'
  ),
  ":task/boundary": {
    ":summary": "Data was collected; approval is pending.",
    ":resume-hint": "Resume at :approve.",
    ":next-step": ":approve",
    ":open-questions": ["Should this go to the public changelog?"]
  }
};
```

</div>

This makes continuation safer than relying on chat history alone.

Boundary documents are small on purpose. They are not a full transcript; they
are the minimum state needed for another turn, worker, or human to continue
without guessing. A good boundary states what was done, why the task stopped,
what should happen next, and which questions remain open.


## 6. Pattern: Tool Authority, Audit, and Usage Ledgers

Autonomous systems should not rely on the model to remember which tools are
allowed. Store tool authority and audit records as data.

The permission check should run before the tool handler sees sensitive
arguments or credentials. For example, an email tool can receive a draft body
from the model, but the system should decide whether sending is allowed, whether
approval is required, and which credential proxy may be used. The audit record
then captures the decision and the actor without exposing secrets to the model.

<div class="multi-lang">

```clojure
{:tool.call/id       #uuid "00000000-0000-0000-0000-000000000503"
 :tool.call/task     [:task/id #uuid "00000000-0000-0000-0000-000000000501"]
 :tool.call/tool     :email-send
 :tool.call/decision :permission/approval-required
 :tool.call/args     {:to "team@example.com"
                      :subject "Release report"}}

{:audit.event/id      #uuid "00000000-0000-0000-0000-000000000504"
 :audit.event/type    :audit.type/tool-approved
 :audit.event/task    [:task/id #uuid "00000000-0000-0000-0000-000000000501"]
 :audit.event/actor   :actor/user
 :audit.event/message "User approved email-send for this task."}
```

```java
import java.util.List;
import java.util.Map;
import java.util.UUID;

Map<?, ?> toolCall = Map.of(
    ":tool.call/id",
    UUID.fromString("00000000-0000-0000-0000-000000000503"),
    ":tool.call/task",
    List.of(":task/id",
            UUID.fromString("00000000-0000-0000-0000-000000000501")),
    ":tool.call/tool", ":email-send",
    ":tool.call/decision", ":permission/approval-required",
    ":tool.call/args", Map.of(
        ":to", "team@example.com",
        ":subject", "Release report"));

Map<?, ?> auditEvent = Map.of(
    ":audit.event/id",
    UUID.fromString("00000000-0000-0000-0000-000000000504"),
    ":audit.event/type", ":audit.type/tool-approved",
    ":audit.event/task",
    List.of(":task/id",
            UUID.fromString("00000000-0000-0000-0000-000000000501")),
    ":audit.event/actor", ":actor/user",
    ":audit.event/message",
    "User approved email-send for this task.");
```

```python
from uuid import UUID

tool_call = {
    ":tool.call/id": UUID("00000000-0000-0000-0000-000000000503"),
    ":tool.call/task": [
        ":task/id",
        UUID("00000000-0000-0000-0000-000000000501"),
    ],
    ":tool.call/tool": ":email-send",
    ":tool.call/decision": ":permission/approval-required",
    ":tool.call/args": {
        ":to": "team@example.com",
        ":subject": "Release report",
    },
}

audit_event = {
    ":audit.event/id": UUID("00000000-0000-0000-0000-000000000504"),
    ":audit.event/type": ":audit.type/tool-approved",
    ":audit.event/task": [
        ":task/id",
        UUID("00000000-0000-0000-0000-000000000501"),
    ],
    ":audit.event/actor": ":actor/user",
    ":audit.event/message": "User approved email-send for this task.",
}
```

```javascript
import { readEdn } from "datalevin-node";

const toolCall = {
  ":tool.call/id": await readEdn(
    '#uuid "00000000-0000-0000-0000-000000000503"'
  ),
  ":tool.call/task": [
    ":task/id",
    await readEdn('#uuid "00000000-0000-0000-0000-000000000501"')
  ],
  ":tool.call/tool": ":email-send",
  ":tool.call/decision": ":permission/approval-required",
  ":tool.call/args": {
    ":to": "team@example.com",
    ":subject": "Release report"
  }
};

const auditEvent = {
  ":audit.event/id": await readEdn(
    '#uuid "00000000-0000-0000-0000-000000000504"'
  ),
  ":audit.event/type": ":audit.type/tool-approved",
  ":audit.event/task": [
    ":task/id",
    await readEdn('#uuid "00000000-0000-0000-0000-000000000501"')
  ],
  ":audit.event/actor": ":actor/user",
  ":audit.event/message": "User approved email-send for this task."
};
```

</div>

The same principle applies to LLM budgets. Record sanitized usage entries by
scope so goals, tasks, sessions, and schedules can share enforcement:

<div class="multi-lang">

```clojure
{:limit.usage/id                #uuid "00000000-0000-0000-0000-000000000505"
 :limit.usage/scope             :limit.scope/goal
 :limit.usage/goal              [:goal/id #uuid "00000000-0000-0000-0000-000000000506"]
 :limit.usage/provider          :provider/openai
 :limit.usage/model             "general-reasoner"
 :limit.usage/prompt-tokens     3200
 :limit.usage/completion-tokens 680
 :limit.usage/cost-micros       1450}
```

```java
import java.util.List;
import java.util.Map;
import java.util.UUID;

Map<?, ?> usage = Map.of(
    ":limit.usage/id",
    UUID.fromString("00000000-0000-0000-0000-000000000505"),
    ":limit.usage/scope", ":limit.scope/goal",
    ":limit.usage/goal",
    List.of(":goal/id",
            UUID.fromString("00000000-0000-0000-0000-000000000506")),
    ":limit.usage/provider", ":provider/openai",
    ":limit.usage/model", "general-reasoner",
    ":limit.usage/prompt-tokens", 3200,
    ":limit.usage/completion-tokens", 680,
    ":limit.usage/cost-micros", 1450);
```

```python
from uuid import UUID

usage = {
    ":limit.usage/id": UUID("00000000-0000-0000-0000-000000000505"),
    ":limit.usage/scope": ":limit.scope/goal",
    ":limit.usage/goal": [
        ":goal/id",
        UUID("00000000-0000-0000-0000-000000000506"),
    ],
    ":limit.usage/provider": ":provider/openai",
    ":limit.usage/model": "general-reasoner",
    ":limit.usage/prompt-tokens": 3200,
    ":limit.usage/completion-tokens": 680,
    ":limit.usage/cost-micros": 1450,
}
```

```javascript
import { readEdn } from "datalevin-node";

const usage = {
  ":limit.usage/id": await readEdn(
    '#uuid "00000000-0000-0000-0000-000000000505"'
  ),
  ":limit.usage/scope": ":limit.scope/goal",
  ":limit.usage/goal": [
    ":goal/id",
    await readEdn('#uuid "00000000-0000-0000-0000-000000000506"')
  ],
  ":limit.usage/provider": ":provider/openai",
  ":limit.usage/model": "general-reasoner",
  ":limit.usage/prompt-tokens": 3200,
  ":limit.usage/completion-tokens": 680,
  ":limit.usage/cost-micros": 1450
};
```

</div>

With these records in Datalevin, policy enforcement becomes queryable state
rather than scattered application logic.

Budgets become more useful when they are scoped. A session-level ceiling protects
interactive use, a goal-level ceiling protects long-running work, and a
schedule-level ceiling prevents recurring jobs from quietly consuming unlimited
tokens. Because the ledger is sanitized, it can be used for policy and reporting
without storing prompt text or secret-bearing tool payloads.


## 7. Capstone: A Documentation Feedback Memory Loop

The patterns above come together in one small, runnable program. This is not
production code for a documentation site. Instead, it models the same
application shape in miniature: readers search the docs, leave feedback, a
background job detects a documentation gap, and the system creates a reviewable
task with working memory. The memory loop is illustrated in Figure 27.1.

![Capstone feedback memory loop: not-helpful search-feedback episodes are grouped into a candidate gap once they meet a threshold; the app accepts a current docs-gap fact (with evidence and supersession), opens a docs-gap-review task against it, and projects the fact and its evidence into working memory; resolving the task improves the docs and closes the loop with fewer failed searches. Boxes are stored in Datalevin; the dashed candidate-gap box is application-computed; transitions are application policy](/images/diagrams/capstone-memory-loop.svg)

The important boundary remains the same as in Chapter 23. Datalevin stores the
episodes, facts, task state, and working-memory projection. The application
implements the extraction policy, thresholds, review workflow, and publication
rules.

The full capstone is written as one Clojure program so it can be read and run as
a single listing. The transaction data, queries, and task records are the same
shapes shown above in Java, Python, and JavaScript. In Java and Python, the
serialized read-modify-write portions map to `withTransaction` /
`with_transaction`; in JavaScript, put those serialized commands behind a
Datalevin-hosting service or transaction function and call that boundary from
the client.

The program below implements this loop end to end.

<!-- pdf-listing: Documentation feedback memory loop capstone -->

```clojure
(require '[clojure.string :as str])
(require '[datalevin.core :as d])

(def capstone-schema
  {:user/id          {:db/valueType :db.type/string
                     :db/unique    :db.unique/identity}
   :session/id       {:db/valueType :db.type/string
                     :db/unique    :db.unique/identity}
   :session/user     {:db/valueType :db.type/ref}

   :episode/id       {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :episode/user     {:db/valueType :db.type/ref}
   :episode/session  {:db/valueType :db.type/ref}
   :episode/type     {:db/valueType :db.type/keyword}
   :episode/query    {:db/valueType :db.type/string
                     :db/fulltext  true}
   :episode/page     {:db/valueType :db.type/string}
   :episode/helpful? {:db/valueType :db.type/boolean}
   :episode/summary  {:db/valueType :db.type/string
                     :db/fulltext  true}
   :episode/timestamp {:db/valueType :db.type/instant}

   :fact/id          {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :fact/kind        {:db/valueType :db.type/keyword}
   :fact/subject     {:db/valueType :db.type/string}
   :fact/content     {:db/valueType :db.type/string
                     :db/fulltext  true}
   :fact/status      {:db/valueType :db.type/keyword}
   :fact/confidence  {:db/valueType :db.type/double}
   :fact/evidence    {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :fact/supersedes  {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :fact/created-at  {:db/valueType :db.type/instant}

   :task/id          {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :task/type        {:db/valueType :db.type/keyword}
   :task/state       {:db/valueType :db.type/keyword}
   :task/title       {:db/valueType :db.type/string}
   :task/fact        {:db/valueType :db.type/ref}
   :task/contract    {:db/valueType :db.type/idoc}
   :task/created-at  {:db/valueType :db.type/instant}

   :wm/id            {:db/valueType :db.type/uuid
                     :db/unique    :db.unique/identity}
   :wm/task          {:db/valueType :db.type/ref}
   :wm.slot/wm       {:db/valueType :db.type/ref}
   :wm.slot/entity   {:db/valueType :db.type/ref}
   :wm.slot/relevance {:db/valueType :db.type/double}
   :wm.slot/reason   {:db/valueType :db.type/string}})

(def conn
  (d/create-conn nil capstone-schema {:kv-opts {:inmemory? true}}))

(d/transact! conn
  [{:user/id "reader-1"}
   {:session/id "docs-session-1"
    :session/user [:user/id "reader-1"]}])

(defn record-feedback!
  [conn {:keys [user-id session-id query page helpful?]}]
  (let [episode-id (random-uuid)
        summary    (format "Search for '%s' opened %s and was marked %s."
                           query
                           page
                           (if helpful? "helpful" "not helpful"))]
    (d/transact! conn
      [{:episode/id        episode-id
        :episode/user      [:user/id user-id]
        :episode/session   [:session/id session-id]
        :episode/type      :episode.type/search-feedback
        :episode/query     query
        :episode/page      page
        :episode/helpful?  helpful?
        :episode/summary   summary
        :episode/timestamp (java.util.Date.)}])
    episode-id))

(def feedback-events
  [{:query "datalevin backup restore"
    :page "/docs/19-storage-tuning-durability"
    :helpful? false}
   {:query "backup verification"
    :page "/docs/22-production-operations"
    :helpful? false}
   {:query "restore backup wal"
    :page "/docs/19-storage-tuning-durability"
    :helpful? false}
   {:query "datalog recursion"
    :page "/docs/09-rules-recursion"
    :helpful? true}])

(doseq [event feedback-events]
  (record-feedback!
    conn
    (assoc event
           :user-id "reader-1"
           :session-id "docs-session-1")))

(defn failed-searches
  [db]
  (d/q '[:find ?episode-id ?query ?page
         :where [?e :episode/type :episode.type/search-feedback]
                [?e :episode/helpful? false]
                [?e :episode/id ?episode-id]
                [?e :episode/query ?query]
                [?e :episode/page ?page]]
       db))

(defn gap-topic
  [query]
  (let [q (str/lower-case query)]
    (cond
      (re-find #"backup|restore|wal" q) "backup and restore"
      :else q)))

(defn candidate-gaps
  [db]
  (->> (failed-searches db)
       (map (fn [[episode-id query page]]
              {:topic (gap-topic query)
               :episode-id episode-id
               :query query
               :page page}))
       (group-by :topic)
       (keep (fn [[topic rows]]
               (when (>= (count rows) 2)
                 {:topic topic
                  :count (count rows)
                  :evidence (mapv :episode-id rows)
                  :pages (vec (distinct (map :page rows)))})))
       vec))

(defn current-gap-fact-ids
  [db topic]
  (d/q '[:find [?fact-id ...]
         :in $ ?topic
         :where [?f :fact/kind :fact.kind/docs-gap]
                [?f :fact/subject ?topic]
                [?f :fact/status :fact.status/current]
                [?f :fact/id ?fact-id]]
       db topic))

(defn accept-gap-fact!
  [conn {:keys [topic count evidence pages]}]
  (let [fact-id    (random-uuid)
        confidence (min 0.95 (+ 0.55 (* 0.1 count)))
        content    (format "Readers are struggling to find documentation about %s."
                           topic)]
    (d/with-transaction [tx conn]
      (let [old-ids (current-gap-fact-ids @tx topic)
            retired (mapv (fn [old-id]
                             {:fact/id old-id
                              :fact/status :fact.status/superseded})
                           old-ids)
            current (cond-> {:fact/id fact-id
                              :fact/kind :fact.kind/docs-gap
                              :fact/subject topic
                              :fact/content content
                              :fact/status :fact.status/current
                              :fact/confidence confidence
                              :fact/evidence (mapv (fn [episode-id]
                                                     [:episode/id episode-id])
                                                   evidence)
                              :fact/created-at (java.util.Date.)}
                      (seq old-ids)
                      (assoc :fact/supersedes
                             (mapv (fn [old-id] [:fact/id old-id])
                                   old-ids)))]
        (d/transact! tx (conj retired current))))
    fact-id))

(defn recall-gap-context
  [db topic]
  (d/q '[:find ?query ?page ?summary
         :in $ ?topic
         :where [?f :fact/kind :fact.kind/docs-gap]
                [?f :fact/subject ?topic]
                [?f :fact/status :fact.status/current]
                [?f :fact/evidence ?episode]
                [?episode :episode/query ?query]
                [?episode :episode/page ?page]
                [?episode :episode/summary ?summary]]
       db topic))

(defn create-doc-review-task!
  [conn fact-id]
  (let [db           (d/db conn)
        topic        (d/q '[:find ?topic .
                            :in $ ?fact-id
                            :where [?f :fact/id ?fact-id]
                                   [?f :fact/subject ?topic]]
                          db fact-id)
        evidence-ids (d/q '[:find [?episode-id ...]
                            :in $ ?fact-id
                            :where [?fact :fact/id ?fact-id]
                                   [?fact :fact/evidence ?episode]
                                   [?episode :episode/id ?episode-id]]
                          db fact-id)
        task-id      (random-uuid)
        wm-id        (random-uuid)
        task         {:task/id task-id
                      :task/type :task.type/docs-gap-review
                      :task/state :task.state/ready
                      :task/title (str "Review documentation gap: " topic)
                      :task/fact [:fact/id fact-id]
                      :task/created-at (java.util.Date.)
                      :task/contract {:kind :docs-gap-review
                                      :topic topic
                                      :publish? false
                                      :steps [{:id :inspect-evidence
                                               :kind :human-review}
                                              {:id :draft-change
                                               :kind :llm-assisted
                                               :depends-on :inspect-evidence}
                                              {:id :approve
                                               :kind :human-approval
                                               :depends-on :draft-change}]}}
        wm           {:wm/id wm-id
                      :wm/task [:task/id task-id]}
        fact-slot    {:wm.slot/wm [:wm/id wm-id]
                      :wm.slot/entity [:fact/id fact-id]
                      :wm.slot/relevance 1.0
                      :wm.slot/reason "Current documentation-gap fact."}
        evidence-slots
        (map-indexed
          (fn [idx episode-id]
            {:wm.slot/wm [:wm/id wm-id]
             :wm.slot/entity [:episode/id episode-id]
             :wm.slot/relevance (- 0.9 (* idx 0.05))
             :wm.slot/reason "Failed-search evidence for the review task."})
          evidence-ids)]
    (d/transact! conn (into [task wm fact-slot] evidence-slots))
    {:task/id task-id
     :wm/id wm-id}))

(def candidate (first (candidate-gaps (d/db conn))))
(def fact-id (accept-gap-fact! conn candidate))
(def task-ref (create-doc-review-task! conn fact-id))

(def results
  {;; Semantic memory: current documentation-gap fact.
   :semantic-memory
   (d/q '[:find ?status ?content ?confidence
          :where [?f :fact/kind :fact.kind/docs-gap]
                 [?f :fact/status ?status]
                 [?f :fact/content ?content]
                 [?f :fact/confidence ?confidence]]
        (d/db conn))

   ;; Recall: evidence that explains why the fact exists.
   :recall-context
   (recall-gap-context (d/db conn) "backup and restore")

   ;; Task state: reviewable work item, not an automatic publication.
   :task-state
   (d/q '[:find ?title ?state ?contract
          :where [?task :task/type :task.type/docs-gap-review]
                 [?task :task/title ?title]
                 [?task :task/state ?state]
                 [?task :task/contract ?contract]]
        (d/db conn))

   ;; Working memory: bounded context selected for this task.
   :working-memory
   (d/q '[:find ?reason ?summary
          :where [?wm :wm/id _]
                 [?slot :wm.slot/wm ?wm]
                 [?slot :wm.slot/reason ?reason]
                 [?slot :wm.slot/entity ?entity]
                 [?entity :episode/summary ?summary]]
        (d/db conn))})

(d/close conn)

results
```

The example covers the whole Part VI arc in one place:

- **Episodes** preserve the raw feedback events.
- **Consolidation** groups failed searches into a candidate documentation-gap
  fact.
- **Truth maintenance** marks any older current fact for the same topic as
  superseded before accepting a new current fact.
- **Recall** follows the evidence links from the current fact back to the
  episodes that justify it.
- **Working memory** records a bounded context for the review task.
- **Task state** creates a contract that requires review and approval before
  publication.

Replacing the pure `gap-topic` function with an LLM extractor would not change
the database model. It would only change the application policy that decides
which candidate facts deserve review.


## Summary: The Path to Machine Intelligence

Building stateful AI is about building systems that **accrue value over time**.

- **Memory architecture** (Chapter 23) provides the durable substrate and scope
  boundaries.
- **Episodic, semantic, and working memory** (Chapter 24) provide the records
  that history and knowledge live in.
- **Recall and context assembly** (Chapter 25) provide the model with a bounded,
  organized view of relevant state.
- **Apperception and truth maintenance** (Chapter 26) keep long-term knowledge
  coherent as new evidence arrives.
- **Datalog** (Chapter 8) provides the logic.
- **MCP and tool records** provide a controlled tool surface for AI clients.
- **Durable task contracts** keep long-running work resumable, auditable, and
  safe to hand off.

By choosing Datalevin, you are choosing a database substrate for stateful AI
applications: exact logical reasoning, fuzzy semantic retrieval, durable task
state, controlled tool use, and long-term knowledge maintenance. The agent
policies and control loops remain application code built on top of those
database capabilities.

## References

[1] Stuart Russell and Peter Norvig, *Artificial Intelligence: A Modern
Approach*, 4th US ed., Pearson, 2020. URL: <https://aima.cs.berkeley.edu/>.

[2] Juji Inc. URL: <https://juji.io/>.
