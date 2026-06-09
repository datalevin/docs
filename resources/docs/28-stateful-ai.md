---
title: "Building Stateful AI Applications"
chapter: 28
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 28: Building Stateful AI Applications

The true potential of AI is not found in isolated chat sessions, but in applications that maintain a persistent state. A "Stateful AI" application is one where the AI's knowledge, goals, and behavior evolve alongside its users.

This chapter synthesizes the Part VI arc into practical application examples:
memory architecture, concrete memory records, recall and context assembly,
truth maintenance, and application-level task control.

---

## 1. Pattern: The "Database-as-Environment"

In most applications, the database is a passive storage layer. In a Stateful AI application, the database is an **Active Environment**.
- The agent "lives" in the database.
- Every interaction updates its world model.
- Its "personality" is not a static prompt, but a collection of entities and relationships in Datalevin.

An agent runtime also needs a control loop. Perceptors convert external input
into structured observations. Actuators call tools or services. A central
controller decides which task, agenda item, or exception should run next. Memory
keeps the loop from starting over every turn, and plugins extend the loop with
domain-specific skills.

Datalevin is a good fit for this control layer because the agenda can be data.
The agent can have a main stack for the active task, an agenda queue for planned
work, an exception queue for urgent interruptions, and an ad-lib queue for
opportunistic suggestions. These do not need to be separate systems. They can be
task entities with different state, priority, and scheduling attributes.

This is what turns a chatbot into an agent. A chatbot responds to the latest
message. An agent can be interrupted, handle the interruption, and then resume
the original agenda because the agenda and boundary state were durable.

---

## 2. Pattern: The Memory Loop

A typical persistent agent memory loop follows these steps:

1. **Perceive**: The agent receives input and records enough metadata to replay
   the turn later.
2. **Refresh Working Memory**: The agent retrieves relevant entities, facts,
   episodes, and documents into a bounded projection.
3. **Recall**: The agent performs a hybrid query (Chapter 26) to find relevant
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

---

## 3. Example 1: The Adaptive Learning Assistant

Imagine an AI tutor that helps a student learn Clojure over six months.

- **Episodic Memory**: Every question the student asks and every mistake they make is stored as an episode.
- **Semantic State**: A "Student Knowledge Graph" tracks which concepts (e.g., "Recursion", "Macros", "Transducers") the student has mastered.
- **Stateful Logic**:
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
    import datalevin.core.*;

    // Find concepts the student hasn't mastered but has prerequisite knowledge for
    var results = Datalevin.q(
        "[:find ?concept " +
        " :where [?student :student/level ?level]" +
        "        [?concept :concept/difficulty ?d]" +
        "        [(< ?d ?level)]" +
        "        (not [?student :student/mastered ?concept])" +
        "        [?concept :concept/prereq ?pre]" +
        "        [?student :student/mastered ?pre]]",
        db);
    ```

    ```python
    # Find concepts the student hasn't mastered but has prerequisite knowledge for
    results = d.q(
        """[:find ?concept
            :where [?student :student/level ?level]
                   [?concept :concept/difficulty ?d]
                   [(< ?d ?level)]
                   (not [?student :student/mastered ?concept])
                   [?concept :concept/prereq ?pre]
                   [?student :student/mastered ?pre]]""",
        db)
    ```

    ```javascript
    // Find concepts the student hasn't mastered but has prerequisite knowledge for
    const results = d.q(
        `[:find ?concept
          :where [?student :student/level ?level]
                 [?concept :concept/difficulty ?d]
                 [(< ?d ?level)]
                 (not [?student :student/mastered ?concept])
                 [?concept :concept/prereq ?pre]
                 [?student :student/mastered ?pre]]`,
        db);
    ```

    </div>
The tutor's advice is always **grounded** in the student's actual history.

---

## 4. Example 2: Multi-Agent Collaboration Workspace

In a complex project, multiple agents might work together—one for coding, one for testing, and one for documentation.

- **Common Workspace**: All agents share a single Datalevin database.
- **Coordination via Graph**: Agents post tasks as entities and link them to each other.
- **Security**: Use **Datalevin Server RBAC** (Chapter 23) to ensure the "Code Agent" can modify source entities, but the "Documentation Agent" can only view them.

By sharing a transactionally consistent environment, agents can coordinate their efforts without complex message-passing protocols.

---

## 5. Example 3: The Self-Updating Knowledge Base

A documentation site (like this one!) that "learns" from user feedback.

- **Interaction Log**: Store every search query and every "Is this helpful?" click.
- **Inference**: A background agent uses **Full-Text Search** to find areas where users frequently search but fail to find answers.
- **Synthesis**: The agent uses an LLM to draft a new `idoc` document addressing the gap and transacts it into the database for human review.

---

## 6. Example 4: MCP-Backed Agent Tools

For AI applications that use MCP, `dtlv mcp` provides a local `stdio` tool server over Datalevin databases.

- **Read-first safety**: `dtlv mcp` starts with write tools disabled.
- **Explicit writes**: `dtlv --allow-writes mcp` is required before tools can mutate a database.
- **Structured payloads**: Tool calls return machine-readable `structuredContent`, with response limits and truncation metadata for large results.
- **Local and remote targets**: The MCP server can open local database paths or remote `dtlv://` URIs behind the same local process.

This makes Datalevin useful as a durable tool substrate for agents: memory stays in a real database, while the tool boundary remains explicit.

---

## 7. Pattern: Durable Tasks and Board Projections

For long-running work, store the plan as data. A task contract should be stable
and inspectable, while runtime state changes independently as the task runs.

The contract is what the system intends to do. Runtime state is what has
happened so far. Keeping them separate makes resumption and review much easier:
the agent can retry a failed step without rewriting the plan, and a human can
inspect whether the task is still following the original objective. Stable step
ids are important because later outputs, approvals, and boundary summaries need
to point back to the same step even after display text changes.

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

A Kanban board, scheduler, or branch-worker queue should be a projection over
tasks, not a separate store. For example:

```clojure
{:task/id           #uuid "00000000-0000-0000-0000-000000000502"
 :task/board        {:visible? true
                     :lane :board.lane/open
                     :priority :normal}
 :task/claim-token  "opaque-worker-token"
 :task/claimed-by   [:agent/id :agent/release-writer]}
```

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

```clojure
{:task/id       #uuid "00000000-0000-0000-0000-000000000501"
 :task/boundary {:summary "Data was collected; approval is pending."
                 :resume-hint "Resume at :approve."
                 :next-step :approve
                 :open-questions ["Should this go to the public changelog?"]}}
```

This makes continuation safer than relying on chat history alone.

Boundary documents are small on purpose. They are not a full transcript; they
are the minimum state needed for another turn, worker, or human to continue
without guessing. A good boundary states what was done, why the task stopped,
what should happen next, and which questions remain open.

---

## 8. Pattern: Tool Authority, Audit, and Usage Ledgers

Autonomous systems should not rely on the model to remember which tools are
allowed. Store tool authority and audit records as data.

The permission check should run before the tool handler sees sensitive
arguments or credentials. For example, an email tool can receive a draft body
from the model, but the system should decide whether sending is allowed, whether
approval is required, and which credential proxy may be used. The audit record
then captures the decision and the actor without exposing secrets to the model.

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

The same principle applies to LLM budgets. Record sanitized usage entries by
scope so goals, tasks, sessions, and schedules can share enforcement:

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

With these records in Datalevin, policy enforcement becomes queryable state
rather than scattered application logic.

Budgets become more useful when they are scoped. A session-level ceiling protects
interactive use, a goal-level ceiling protects long-running work, and a
schedule-level ceiling prevents recurring jobs from quietly consuming unlimited
tokens. Because the ledger is sanitized, it can be used for policy and reporting
without storing prompt text or secret-bearing tool payloads.

---

## 9. Summary: The Path to Machine Intelligence

Building stateful AI is about building systems that **accrue value over time**.

- **Memory architecture** (Chapter 24) provides the durable substrate and scope
  boundaries.
- **Episodic, semantic, and working memory** (Chapter 25) provide the records
  that history and knowledge live in.
- **Recall and context assembly** (Chapter 26) provide the model with a bounded,
  organized view of relevant state.
- **Apperception and truth maintenance** (Chapter 27) keep long-term knowledge
  coherent as new evidence arrives.
- **Datalog** (Chapter 9) provides the logic.
- **MCP and tool records** provide a controlled tool surface for AI clients.
- **Durable task contracts** keep long-running work resumable, auditable, and
  safe to hand off.

By choosing Datalevin, you are not just choosing a place to store data. You are
choosing a substrate that supports the full spectrum of stateful AI: exact
logical reasoning, fuzzy semantic understanding, durable task state, controlled
tool use, and long-term knowledge maintenance.
