---
title: "Graph Modeling and Relationship Design"
chapter: 13
part: "III — Modeling Across Paradigms"
---

# Chapter 13: Graph Modeling and Relationship Design

In many databases, "graph" is a specialized mode or a separate engine. In
Datalevin, the graph is the **native state**. Every `:db.type/ref` you define is
a directed edge between two nodes (entities). Because every attribute is
indexed, traversing these edges in either direction is equally fast.

This chapter explores how to leverage Datalevin as a high-performance graph
database, focusing on navigation, recursive patterns, and modeling complex
relationships like those found in social networks.

The Java, Python, and JavaScript snippets in this chapter assume an open
connection named `conn`. Calling `conn.query` supplies the connection's current
database as `$`; pass only additional `:in` values after the query string. Rule
sets are EDN data, so non-Clojure snippets use EDN helpers such as
`Datalevin.edn`, `interop().read_edn`, and `await interop().readEdn`.

---

## 1. Entities as Nodes, Refs as Edges

In a graph model, your domain is a network of interconnected entities.

- **Nodes**: Entities with their properties (attributes).
- **Edges**: Reference attributes (`:db.type/ref`) that point from one entity to another.

### 1.1 Forward and Reverse Navigation

Datalevin allows you to traverse relationships in both directions without any
additional indexing.

- **Forward**: Following a reference from subject to object.
  - Pattern: `[?e :user/follows ?other]`
- **Reverse**: Finding subjects that point to a known object by placing the
  known entity in the value position.
  - Pattern: `[?other :user/follows ?e]`

In Pull and entity navigation, Datalevin also supports reverse attributes such
as `:user/_follows`. In Datalog queries, use the normal attribute and let the
query optimizer choose the index from the bound variables.

---

## 2. Modeling Complex Edges (Associative Entities)

In simple graphs, an edge is just a line between two nodes. But often, you need
to store data *on* the edge itself (e.g., "When did these two people become
friends?" or "What is the weight of this connection?").

As discussed in the Relational Modeling chapter (Ch 12), the best way to model
this is through an **Associative Entity** (or a "Join Entity").

### Example: Weighted Social Connections
Instead of a simple `:user/follows` ref, create a `Follow` entity:

```clojure
{:follow/follower  user-a-id
 :follow/following user-b-id
 :follow/created-at #inst "2024-01-01T00:00:00Z"
 :follow/strength   0.85}
```

This transforms a simple edge into a node that can hold any number of
attributes, effectively giving you property-graph-style modeling [1]
while keeping Datalevin's normalized fact representation.

---

## 3. Graph Traversal with Recursive Rules

The most powerful feature of a graph database is the ability to traverse paths
of arbitrary length. In Datalevin, this is achieved using **Recursive Rules**
(see Chapter 10).

Fixed-depth traversals are ordinary joins. To find people followed by the
people you follow:

```clojure
(d/q '[:find ?suggestion
       :in $ ?user
       :where
       [?user :user/follows ?intermediate]
       [?intermediate :user/follows ?suggestion]
       [(not= ?user ?suggestion)]]
     db user-id)
```

### Deep Traversal: Transitive Closure

Finding all connections at *any* distance (e.g., finding all reachable nodes in
a network):

```clojure
(def reachability-rules
  '[[(reachable ?start ?end)
     [?start :link/to ?end]]

    [(reachable ?start ?end)
     [?start :link/to ?mid]
     (reachable ?mid ?end)]])
```

Datalevin's query engine is designed to handle these recursive expansions
efficiently, preventing cycles from causing infinite loops.

### Hierarchies and Taxonomies

Recursive rules are also a natural fit for tree-like graph structures. The
same pattern works for place hierarchies, product categories, organization
charts, and tag taxonomies. A city belongs to a country through
`:place/isPartOf`, and a tag class belongs to a broader class through
`:tagclass/isSubclassOf`.

<div class="multi-lang">

```clojure
(def place-country-rules
  '[[(place-country ?place ?country)
     [?place :place/type "Country"]
     [(ground ?place) ?country]]

    [(place-country ?place ?country)
     [?place :place/isPartOf ?parent]
     (place-country ?parent ?country)]])

(d/q '[:find ?country-name
       :in $ % ?place-name
       :where
       [?place :place/name ?place-name]
       (place-country ?place ?country)
       [?country :place/name ?country-name]]
     db place-country-rules "Paris")
```

```java
Object placeCountryRules = Datalevin.edn(
    "[[(place-country ?place ?country) " +
    "  [?place :place/type \"Country\"] " +
    "  [(ground ?place) ?country]] " +
    " [(place-country ?place ?country) " +
    "  [?place :place/isPartOf ?parent] " +
    "  (place-country ?parent ?country)]]");

Object result = conn.query("[:find ?country-name " +
    ":in $ % ?place-name " +
    ":where [?place :place/name ?place-name] " +
    "       (place-country ?place ?country) " +
    "       [?country :place/name ?country-name]]",
    placeCountryRules, "Paris");
```

```python
place_country_rules = interop().read_edn(
    '[[(place-country ?place ?country) '
    '  [?place :place/type "Country"] '
    '  [(ground ?place) ?country]] '
    ' [(place-country ?place ?country) '
    '  [?place :place/isPartOf ?parent] '
    '  (place-country ?parent ?country)]]')

result = conn.query('[:find ?country-name '
    ':in $ % ?place-name '
    ':where [?place :place/name ?place-name] '
    '       (place-country ?place ?country) '
    '       [?country :place/name ?country-name]]',
    place_country_rules, 'Paris')
```

```javascript
const placeCountryRules = await interop().readEdn(
  '[[(place-country ?place ?country) ' +
  '  [?place :place/type "Country"] ' +
  '  [(ground ?place) ?country]] ' +
  ' [(place-country ?place ?country) ' +
  '  [?place :place/isPartOf ?parent] ' +
  '  (place-country ?parent ?country)]]');

const result = await conn.query('[:find ?country-name ' +
  ':in $ % ?place-name ' +
  ':where [?place :place/name ?place-name] ' +
  '       (place-country ?place ?country) ' +
  '       [?country :place/name ?country-name]]',
  placeCountryRules, 'Paris');
```

</div>

This rule works for both direct and indirect containment. If `Paris` is part of
`France`, and `France` is part of `Europe`, the rule stops at the first ancestor
whose `:place/type` is `"Country"`.

### Bounded Neighborhoods with Distance

Some graph queries should not traverse arbitrarily far. They ask for neighbors
within a fixed radius, while preserving the distance so the final result can be
ordered or aggregated.

<div class="multi-lang">

```clojure
(def friends-3-rules
  '[[(friends-3 ?start ?friend ?dist)
     [?k :knows/person1 ?start]
     [?k :knows/person2 ?friend]
     [(ground 1) ?dist]]

    [(friends-3 ?start ?friend ?dist)
     [?k1 :knows/person1 ?start]
     [?k1 :knows/person2 ?mid]
     [?k2 :knows/person1 ?mid]
     [?k2 :knows/person2 ?friend]
     [(not= ?friend ?start)]
     [(ground 2) ?dist]]

    [(friends-3 ?start ?friend ?dist)
     [?k1 :knows/person1 ?start]
     [?k1 :knows/person2 ?mid1]
     [?k2 :knows/person1 ?mid1]
     [?k2 :knows/person2 ?mid2]
     [(not= ?mid2 ?start)]
     [?k3 :knows/person1 ?mid2]
     [?k3 :knows/person2 ?friend]
     [(not= ?friend ?start)]
     [(not= ?friend ?mid1)]
     [(ground 3) ?dist]]])

(d/q '[:find (min ?dist) ?friend-id
       :in $ % ?person-id
       :where
       [?start :person/id ?person-id]
       (friends-3 ?start ?friend ?dist)
       [?friend :person/id ?friend-id]]
     db friends-3-rules person-id)
```

```java
Object friends3Rules = Datalevin.edn(
    "[[(friends-3 ?start ?friend ?dist) " +
    "  [?k :knows/person1 ?start] " +
    "  [?k :knows/person2 ?friend] " +
    "  [(ground 1) ?dist]] " +
    " [(friends-3 ?start ?friend ?dist) " +
    "  [?k1 :knows/person1 ?start] " +
    "  [?k1 :knows/person2 ?mid] " +
    "  [?k2 :knows/person1 ?mid] " +
    "  [?k2 :knows/person2 ?friend] " +
    "  [(not= ?friend ?start)] " +
    "  [(ground 2) ?dist]] " +
    " [(friends-3 ?start ?friend ?dist) " +
    "  [?k1 :knows/person1 ?start] " +
    "  [?k1 :knows/person2 ?mid1] " +
    "  [?k2 :knows/person1 ?mid1] " +
    "  [?k2 :knows/person2 ?mid2] " +
    "  [(not= ?mid2 ?start)] " +
    "  [?k3 :knows/person1 ?mid2] " +
    "  [?k3 :knows/person2 ?friend] " +
    "  [(not= ?friend ?start)] " +
    "  [(not= ?friend ?mid1)] " +
    "  [(ground 3) ?dist]]]");

Object result = conn.query("[:find (min ?dist) ?friend-id " +
    ":in $ % ?person-id " +
    ":where [?start :person/id ?person-id] " +
    "       (friends-3 ?start ?friend ?dist) " +
    "       [?friend :person/id ?friend-id]]",
    friends3Rules, personId);
```

```python
friends_3_rules = interop().read_edn(
    '[[(friends-3 ?start ?friend ?dist) '
    '  [?k :knows/person1 ?start] '
    '  [?k :knows/person2 ?friend] '
    '  [(ground 1) ?dist]] '
    ' [(friends-3 ?start ?friend ?dist) '
    '  [?k1 :knows/person1 ?start] '
    '  [?k1 :knows/person2 ?mid] '
    '  [?k2 :knows/person1 ?mid] '
    '  [?k2 :knows/person2 ?friend] '
    '  [(not= ?friend ?start)] '
    '  [(ground 2) ?dist]] '
    ' [(friends-3 ?start ?friend ?dist) '
    '  [?k1 :knows/person1 ?start] '
    '  [?k1 :knows/person2 ?mid1] '
    '  [?k2 :knows/person1 ?mid1] '
    '  [?k2 :knows/person2 ?mid2] '
    '  [(not= ?mid2 ?start)] '
    '  [?k3 :knows/person1 ?mid2] '
    '  [?k3 :knows/person2 ?friend] '
    '  [(not= ?friend ?start)] '
    '  [(not= ?friend ?mid1)] '
    '  [(ground 3) ?dist]]]')

result = conn.query('[:find (min ?dist) ?friend-id '
    ':in $ % ?person-id '
    ':where [?start :person/id ?person-id] '
    '       (friends-3 ?start ?friend ?dist) '
    '       [?friend :person/id ?friend-id]]',
    friends_3_rules, person_id)
```

```javascript
const friends3Rules = await interop().readEdn(
  '[[(friends-3 ?start ?friend ?dist) ' +
  '  [?k :knows/person1 ?start] ' +
  '  [?k :knows/person2 ?friend] ' +
  '  [(ground 1) ?dist]] ' +
  ' [(friends-3 ?start ?friend ?dist) ' +
  '  [?k1 :knows/person1 ?start] ' +
  '  [?k1 :knows/person2 ?mid] ' +
  '  [?k2 :knows/person1 ?mid] ' +
  '  [?k2 :knows/person2 ?friend] ' +
  '  [(not= ?friend ?start)] ' +
  '  [(ground 2) ?dist]] ' +
  ' [(friends-3 ?start ?friend ?dist) ' +
  '  [?k1 :knows/person1 ?start] ' +
  '  [?k1 :knows/person2 ?mid1] ' +
  '  [?k2 :knows/person1 ?mid1] ' +
  '  [?k2 :knows/person2 ?mid2] ' +
  '  [(not= ?mid2 ?start)] ' +
  '  [?k3 :knows/person1 ?mid2] ' +
  '  [?k3 :knows/person2 ?friend] ' +
  '  [(not= ?friend ?start)] ' +
  '  [(not= ?friend ?mid1)] ' +
  '  [(ground 3) ?dist]]]');

const result = await conn.query('[:find (min ?dist) ?friend-id ' +
  ':in $ % ?person-id ' +
  ':where [?start :person/id ?person-id] ' +
  '       (friends-3 ?start ?friend ?dist) ' +
  '       [?friend :person/id ?friend-id]]',
  friends3Rules, personId);
```

</div>

The rule emits one row for each path shape. The query uses `(min ?dist)` to keep
the shortest distance for each reachable person.

### Mutual Recursion: Alternating Edge Types

Some graph traversals are not a single repeated edge. Datalevin's rule engine
also supports mutually recursive rules, where two or more rules call each other
until the result reaches a fixpoint. This pattern is useful for alternating path
semantics.

For example, suppose a graph has two edge types, `:edge/a` and `:edge/b`, and
you want to find paths that end with an `:edge/a` step after alternating between
the two edge types:

```clojure
(def alternating-rules
  '[[(path-a ?from ?to)
     [?from :edge/a ?to]]

    [(path-a ?from ?to)
     [?mid :edge/a ?to]
     (path-b ?from ?mid)]

    [(path-b ?from ?to)
     [?from :edge/b ?to]]

    [(path-b ?from ?to)
     [?mid :edge/b ?to]
     (path-a ?from ?mid)]])

(d/q '[:find ?to
       :in $ % ?from
       :where
       (path-a ?from ?to)]
     db alternating-rules start-id)
```

`path-a` depends on `path-b`, and `path-b` depends on `path-a`. Datalevin
evaluates these rules together, rather than expanding one call stack
top-down. That is what allows mutually recursive graph definitions to remain
practical.

### Branching Traversals with `or-join`

Complex queries often need to combine several graph shapes into one logical
binding. For graph neighborhoods such as "people within two hops" from a start
person, where a person can be reached either directly or through one
intermediate friend, `or-join` can be used to cover the alternatives:

```clojure
(d/q '[:find ?person
       :in $ ?person-id
       :where
       [?start :person/id ?person-id]
       (or-join [?start ?person]
                ;; one hop
                (and [?k :knows/person1 ?start]
                     [?k :knows/person2 ?person])

                ;; two hops
                (and [?k1 :knows/person1 ?start]
                     [?k1 :knows/person2 ?mid]
                     [?k2 :knows/person1 ?mid]
                     [?k2 :knows/person2 ?person]
                     [(not= ?person ?start)]))]
     db person-id)
```

Use `or-join` when the alternatives bind the same logical result through
different graph patterns. The join variables, here `?start` and `?person`, tell
the query engine which values must be shared between the branch and the rest of
the query. This is especially useful before aggregating over messages, forums,
tags, or memberships reached through several allowed path shapes.

### Excluding Graph Shapes with `not-join`

Recommendation queries often need an anti-join: find the nodes reachable by one
path, but exclude nodes reachable by another path. A common example is
friends-of-friends who are not already direct friends.

<div class="multi-lang">

```clojure
(d/q '[:find ?person-id
       :in $ ?start-person-id
       :where
       [?start :person/id ?start-person-id]

       ;; exactly two hops
       [?k1 :knows/person1 ?start]
       [?k1 :knows/person2 ?mid]
       [?k2 :knows/person1 ?mid]
       [?k2 :knows/person2 ?person]
       [(not= ?person ?start)]

       ;; exclude direct friends
       (not-join [?start ?person]
                 [?k :knows/person1 ?start]
                 [?k :knows/person2 ?person])

       [?person :person/id ?person-id]]
     db start-person-id)
```

```java
Object result = conn.query("[:find ?person-id " +
    ":in $ ?start-person-id " +
    ":where [?start :person/id ?start-person-id] " +
    "       [?k1 :knows/person1 ?start] " +
    "       [?k1 :knows/person2 ?mid] " +
    "       [?k2 :knows/person1 ?mid] " +
    "       [?k2 :knows/person2 ?person] " +
    "       [(not= ?person ?start)] " +
    "       (not-join [?start ?person] " +
    "                 [?k :knows/person1 ?start] " +
    "                 [?k :knows/person2 ?person]) " +
    "       [?person :person/id ?person-id]]",
    startPersonId);
```

```python
result = conn.query('[:find ?person-id '
    ':in $ ?start-person-id '
    ':where [?start :person/id ?start-person-id] '
    '       [?k1 :knows/person1 ?start] '
    '       [?k1 :knows/person2 ?mid] '
    '       [?k2 :knows/person1 ?mid] '
    '       [?k2 :knows/person2 ?person] '
    '       [(not= ?person ?start)] '
    '       (not-join [?start ?person] '
    '                 [?k :knows/person1 ?start] '
    '                 [?k :knows/person2 ?person]) '
    '       [?person :person/id ?person-id]]',
    start_person_id)
```

```javascript
const result = await conn.query('[:find ?person-id ' +
  ':in $ ?start-person-id ' +
  ':where [?start :person/id ?start-person-id] ' +
  '       [?k1 :knows/person1 ?start] ' +
  '       [?k1 :knows/person2 ?mid] ' +
  '       [?k2 :knows/person1 ?mid] ' +
  '       [?k2 :knows/person2 ?person] ' +
  '       [(not= ?person ?start)] ' +
  '       (not-join [?start ?person] ' +
  '                 [?k :knows/person1 ?start] ' +
  '                 [?k :knows/person2 ?person]) ' +
  '       [?person :person/id ?person-id]]',
  startPersonId);
```

</div>

Use `not-join` when the negated graph pattern depends on a specific set of
outer variables. Here the exclusion is about the pair `?start` and `?person`;
it should not depend on unrelated variables that happen to appear elsewhere in
the query.

---

## 4. Example: Finding the Forum for a Message (LDBC-SNB IS6)

The LDBC Social Network Benchmark (SNB) is an industry standard for evaluating
graph databases. Let's examine a real query from this benchmark to see how
Datalevin handles graph traversal in practice.

**Query IS6**: Given a Message, find the Forum that contains it and the Person
who moderates that Forum. Since Comments are not directly contained in Forums,
for Comments we must traverse to the original Post in the thread.

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

In the Datalevin LDBC-SNB SF1 benchmark report, Datalevin is faster than Neo4j
on all seven Interactive Short queries. The reported average latency is 12.6 ms
for Datalevin versus 1908.3 ms for Neo4j, about 151x faster. For the IS6 query
shown above, the reported latency is 1.9 ms for Datalevin versus 1494.5 ms for
Neo4j, about 787x faster [2]. The main reasons are:
- **Index locality**: Following refs is a simple B+Tree lookup
- **Query optimizer**: Creating efficient plan to minimizing wasted computation
- **Semi-naive evaluation**: Recursive rules don't re-process facts
- **Magic-set rewrites**: Constraints push deep into recursive expansion

---

## 5. Summary: Graph Design Principles

1.  **Edges are Refs**: Use `:db.type/ref` for all relationships.
2.  **Navigate Freely**: Don't be afraid of reverse navigation; it's free.
3.  **Use Rules for Paths**: Encapsulate traversal logic in recursive rules.
4.  **Preserve Distance When Needed**: Bounded rules can return path length as
    data for ranking, grouping, or filtering.
5.  **Use `or-join` for Branch Shapes**: Combine alternative graph patterns
    when several paths bind the same result.
6.  **Use `not-join` for Exclusion**: Express "reachable this way but not that
    way" with explicit anti-joins.
7.  **Nodes for Metadata**: If an edge needs properties, make the edge an entity.
8.  **Normalize for Depth**: Deep graphs perform best when the nodes are kept
    small and the relationships are explicit.

By treating your data as a graph from the beginning, you unlock powerful
analytical capabilities that are difficult or impossible to achieve with
traditional relational models.

## References

[1] Renzo Angles, ["The Property Graph Database
Model"](https://ceur-ws.org/Vol-2100/paper26.pdf), *Proceedings of the 12th
Alberto Mendelzon International Workshop on Foundations of Data Management*,
CEUR Workshop Proceedings 2100, 2018.

[2] Datalevin project, ["LDBC SNB
Benchmark"](https://github.com/juji-io/datalevin/tree/master/benchmarks/LDBC-SNB-bench),
benchmark writeup and implementation.
