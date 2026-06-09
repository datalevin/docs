---
title: "Building Stateful AI Applications"
chapter: 28
part: "VI — Datalevin for Intelligent Systems"
---

# Chapter 28: Building Stateful AI Applications

The true potential of AI is not found in isolated chat sessions, but in applications that maintain a persistent state. A "Stateful AI" application is one where the AI's knowledge, goals, and behavior evolve alongside its users.

This chapter synthesizes the patterns from Part VI into practical application
examples and implementation patterns, showing how Datalevin serves as the
central nervous system for stateful AI.

---

## 1. Pattern: The "Database-as-Environment"

In most applications, the database is a passive storage layer. In a Stateful AI application, the database is an **Active Environment**.
- The agent "lives" in the database.
- Every interaction updates its world model.
- Its "personality" is not a static prompt, but a collection of entities and relationships in Datalevin.

---

## 2. Example 1: The Adaptive Learning Assistant

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

## 3. Example 2: Multi-Agent Collaboration Workspace

In a complex project, multiple agents might work together—one for coding, one for testing, and one for documentation.

- **Common Workspace**: All agents share a single Datalevin database.
- **Coordination via Graph**: Agents post tasks as entities and link them to each other.
- **Security**: Use **Datalevin Server RBAC** (Chapter 23) to ensure the "Code Agent" can modify source entities, but the "Documentation Agent" can only view them.

By sharing a transactionally consistent environment, agents can coordinate their efforts without complex message-passing protocols.

---

## 4. Example 3: The Self-Updating Knowledge Base

A documentation site (like this one!) that "learns" from user feedback.

- **Interaction Log**: Store every search query and every "Is this helpful?" click.
- **Inference**: A background agent uses **Full-Text Search** to find areas where users frequently search but fail to find answers.
- **Synthesis**: The agent uses an LLM to draft a new `idoc` document addressing the gap and transacts it into the database for human review.

---

## 5. Example 4: MCP-Backed Agent Tools

For AI applications that use MCP, `dtlv mcp` provides a local `stdio` tool server over Datalevin databases.

- **Read-first safety**: `dtlv mcp` starts with write tools disabled.
- **Explicit writes**: `dtlv --allow-writes mcp` is required before tools can mutate a database.
- **Structured payloads**: Tool calls return machine-readable `structuredContent`, with response limits and truncation metadata for large results.
- **Local and remote targets**: The MCP server can open local database paths or remote `dtlv://` URIs behind the same local process.

This makes Datalevin useful as a durable tool substrate for agents: memory stays in a real database, while the tool boundary remains explicit.

---

## 6. Pattern: Durable Tasks and Board Projections

For long-running work, store the plan as data. A task contract should be stable
and inspectable, while runtime state changes independently as the task runs.

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

---

## 7. Pattern: Tool Authority, Audit, and Usage Ledgers

Autonomous systems should not rely on the model to remember which tools are
allowed. Store tool authority and audit records as data.

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

---

## 8. Summary: The Path to Machine Intelligence

Building stateful AI is about building systems that **accrue value over time**.

- **Memory** (Chapters 24 and 26) provides the history.
- **Apperception** (Chapter 25) provides the coherence.
- **Hybrid Retrieval** (Chapter 27) provides the context.
- **Datalog** (Chapter 9) provides the logic.
- **MCP and tool records** provide a controlled tool surface for AI clients.
- **Durable task contracts** keep long-running work resumable, auditable, and
  safe to hand off.

By choosing Datalevin, you are not just choosing a place to store data; you are choosing a substrate that supports the full spectrum of machine intelligence—from exact logical reasoning to fuzzy semantic understanding.
