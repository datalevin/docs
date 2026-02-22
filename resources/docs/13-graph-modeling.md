---
title: "Graph Modeling and Relationship Design"
chapter: 13
part: "III — Modeling Across Paradigms"
---

# Chapter 13: Graph Modeling and Relationship Design

In many databases, "graph" is a specialized mode or a separate engine. In Datalevin, the graph is the **native state**. Every `:db.type/ref` you define is a directed edge between two nodes (entities). Because every attribute is indexed, traversing these edges in either direction is equally fast.

This chapter explores how to leverage Datalevin as a high-performance graph database, focusing on navigation, recursive patterns, and modeling complex relationships like those found in social networks.

---

## 1. Entities as Nodes, Refs as Edges

In a graph model, your domain is a network of interconnected entities.
- **Nodes**: Entities with their properties (attributes).
- **Edges**: Reference attributes (`:db.type/ref`) that point from one entity to another.

### 1.1 Forward and Reverse Navigation
Datalevin allows you to traverse relationships in both directions without any additional indexing.

- **Forward**: Following a reference from subject to object.
  - Pattern: `[?e :user/follows ?other]`
- **Reverse**: Following a reference from object to subject using the `_` prefix.
  - Pattern: `[?other :_user/follows ?e]`

In a Datalog query, both are performed via the AVE (Attribute-Value-Entity) index, making them extremely efficient.

---

## 2. Modeling Complex Edges (Associative Entities)

In simple graphs, an edge is just a line between two nodes. But often, you need to store data *on* the edge itself (e.g., "When did these two people become friends?" or "What is the weight of this connection?").

As discussed in the Relational Modeling chapter (Ch 12), the best way to model this is through an **Associative Entity** (or a "Join Entity").

### Example: Weighted Social Connections
Instead of a simple `:user/follows` ref, create a `Follow` entity:

```clojure
{:follow/follower  user-a-id
 :follow/following user-b-id
 :follow/created-at #inst "2024-01-01T00:00:00Z"
 :follow/strength   0.85}
```

This transforms a simple edge into a node that can hold any number of attributes, effectively creating a "Property Graph" model.

---

## 3. Graph Traversal with Recursive Rules

The most powerful feature of a graph database is the ability to traverse paths of arbitrary length. In Datalevin, this is achieved using **Recursive Rules** (see Chapter 10).

### Example: "Friend of a Friend" (2-degree connection)
To find people you might know through your friends:

```clojure
(def social-rules
  '[[(friend ?u1 ?u2)
     [?u1 :user/follows ?u2]
     [?u2 :user/follows ?u1]] ; Mutual follow = friendship

    [(suggested-friend ?user ?suggestion)
     (friend ?user ?intermediate)
     (friend ?intermediate ?suggestion)
     (not [?user :user/follows ?suggestion])
     [(not= ?user ?suggestion)]]])
```

### Deep Traversal: Transitive Closure
Finding all connections at *any* distance (e.g., finding all reachable nodes in a network):

```clojure
(def reachability-rules
  '[[(reachable ?start ?end)
     [?start :link/to ?end]]

    [(reachable ?start ?end)
     [?start :link/to ?mid]
     (reachable ?mid ?end)]])
```

Datalevin's query engine is designed to handle these recursive expansions efficiently, preventing cycles from causing infinite loops.

---

## 4. Example: Finding the Forum for a Message (LDBC-SNB IS6)

The LDBC Social Network Benchmark (SNB) is an industry standard for evaluating graph databases. Let's examine a real query from this benchmark to see how Datalevin handles graph traversal in practice.

**Query IS6**: Given a Message, find the Forum that contains it and the Person who moderates that Forum. Since Comments are not directly contained in Forums, for Comments we must traverse to the original Post in the thread.

First, define a recursive rule to find the root post of any message:

```clojure
(def root-post-rule
  '[;; base case
    [(root-post ?m ?post)
     [?m :message/containerOf _]
     [(ground ?m) ?post]] ; ground=identity

    ;; recursive case
    [(root-post ?m ?post)
     [?m :message/replyOf ?parent]
     (root-post ?parent ?post)]])
```

The rule has two branches:
1. If the message has `:message/containerOf`, it is itself a Post (the root)
2. Otherwise, follow `:message/replyOf` recursively until we reach the root

Now the query to find the forum and moderator:

```clojure
(d/q '[:find ?forum-id ?forum-title ?moderator-id
              ?moderator-first-name ?moderator-last-name
       :in $ % ?message-id            ; $=db, %=rules, ?message-id=input
       :where
       ;; locate the message
       [?message :message/id ?message-id]

       ;; traverse to root post (recursive rule)
       (root-post ?message ?post)

       ;; get forum containing the post
       [?post :message/containerOf ?forum]
       [?forum :forum/id ?forum-id]
       [?forum :forum/title ?forum-title]

       ;; get forum moderator
       [?forum :forum/hasModerator ?moderator]
       [?moderator :person/id ?moderator-id]
       [?moderator :person/firstName ?moderator-first-name]
       [?moderator :person/lastName ?moderator-last-name]]
     db root-post-rule message-id)
```

This query demonstrates several Datalevin strengths:
- **Recursive traversal**: The `root-post` rule follows reply chains of arbitrary depth
- **Graph navigation**: Multiple hops from message → post → forum → moderator
- **Rule composition**: The `%` parameter passes rules into the query
- **Unified schema**: All entities use the same attribute-based model

On the LDBC-SNB workload, Datalevin outperforms dedicated graph databases like
Neo4j by **27x to 620x** on short interactive queries (e.g. IS6 latency: Datalevin
took 1.9ms, Neo4j took 1494.5ms), thanks to:
- **Index locality**: Following refs is a simple B+Tree lookup
- **Query optimizer**: Creating efficient plan to minimizing wasted computation
- **Semi-naive evaluation**: Recursive rules don't re-process facts
- **Magic-set rewrites**: Constraints push deep into recursive expansion

---

## 5. Summary: Graph Design Principles

1.  **Edges are Refs**: Use `:db.type/ref` for all relationships.
2.  **Navigate Freely**: Don't be afraid of reverse navigation; it's free.
3.  **Use Rules for Paths**: Encapsulate traversal logic in recursive rules.
4.  **Nodes for Metadata**: If an edge needs properties, make the edge an entity.
5.  **Normalize for Depth**: Deep graphs perform best when the nodes are kept small and the relationships are explicit.

By treating your data as a graph from the beginning, you unlock powerful analytical capabilities that are difficult or impossible to achieve with traditional relational models.
