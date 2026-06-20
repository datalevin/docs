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

In a graph model, as depicted in Figure 13.1, your domain is a network of
interconnected entities.

- **Nodes**: Entities with their properties (attributes).
- **Edges**: Reference attributes (`:db.type/ref`) that point from one entity to another.

![Refs as directed edges: alice and cara follow bob, and bob follows dave, via :user/follows. Forward navigation from a known subject follows the edge using the EAV index; reverse navigation from a known object finds the subjects pointing to it using the AVE index](/images/diagrams/refs-directed-edges.svg)

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
this is through an **Associative Entity** (or a "Join Entity"), as shown in
Figure 13.2.

![Reifying an edge into an associative entity: a simple user A :user/follows user B edge becomes a Follow entity that references user A as :follow/follower and user B as :follow/following, and carries properties such as created-at and strength on the relationship itself](/images/diagrams/associative-entity.svg)

### Example: Weighted Social Connections
Instead of a simple `:user/follows` ref, create a `Follow` entity:

<div class="multi-lang">

```clojure
{:follow/follower  user-a-id
 :follow/following user-b-id
 :follow/created-at #inst "2024-01-01T00:00:00Z"
 :follow/strength   0.85}
```

```java
Tx.entity()
    .put("follow/follower", userAId)
    .put("follow/following", userBId)
    .put("follow/created-at", Instant.parse("2024-01-01T00:00:00Z"))
    .put("follow/strength", 0.85);
```

```python
from datetime import datetime, timezone

follow = {":follow/follower": user_a_id,
          ":follow/following": user_b_id,
          ":follow/created-at": datetime(2024, 1, 1, tzinfo=timezone.utc),
          ":follow/strength": 0.85}
```

```javascript
const follow = {":follow/follower": userAId,
                ":follow/following": userBId,
                ":follow/created-at": new Date("2024-01-01T00:00:00Z"),
                ":follow/strength": 0.85};
```

</div>

This transforms a simple edge into a node that can hold any number of
attributes, such as `created-at`, `strength`, and so on. Using an associative
entity effectively gives you property-graph-style modeling [1] while keeping
Datalevin's normalized fact representation.

---

## 3. Graph Traversal with Recursive Rules

The most powerful feature of a graph database is the ability to traverse paths
of arbitrary length. In Datalevin, this is achieved using **Recursive Rules**
(see Chapter 9).

Fixed-depth traversals are ordinary joins. To find people followed by the
people you follow:

<div class="multi-lang">

```clojure
(d/q '[:find ?suggestion
       :in $ ?user
       :where
       [?user :user/follows ?intermediate]
       [?intermediate :user/follows ?suggestion]
       [(not= ?user ?suggestion)]]
     db user-id)
```

```java
Object suggestions = conn.query(
    "[:find ?suggestion " +
    " :in $ ?user " +
    " :where [?user :user/follows ?intermediate] " +
    "        [?intermediate :user/follows ?suggestion] " +
    "        [(not= ?user ?suggestion)]]",
    userId);
```

```python
suggestions = conn.query(
    '[:find ?suggestion '
    ' :in $ ?user '
    ' :where [?user :user/follows ?intermediate] '
    '        [?intermediate :user/follows ?suggestion] '
    '        [(not= ?user ?suggestion)]]',
    user_id)
```

```javascript
const suggestions = await conn.query(
  "[:find ?suggestion " +
  " :in $ ?user " +
  " :where [?user :user/follows ?intermediate] " +
  "        [?intermediate :user/follows ?suggestion] " +
  "        [(not= ?user ?suggestion)]]",
  userId);
```

</div>

### Deep Traversal: Transitive Closure

Transitive closure means the full set of nodes reachable by following the same
edge relation zero or more times. In practical terms, it is how you find all
connections at *any* distance, such as all reachable nodes in a network:

<div class="multi-lang">

```clojure
(def reachability-rules
  '[[(reachable ?start ?end)
     [?start :link/to ?end]]

    [(reachable ?start ?end)
     [?start :link/to ?mid]
     (reachable ?mid ?end)]])
```

```java
Object reachabilityRules = Datalevin.edn(
    "[[(reachable ?start ?end) " +
    "  [?start :link/to ?end]] " +
    " [(reachable ?start ?end) " +
    "  [?start :link/to ?mid] " +
    "  (reachable ?mid ?end)]]");
```

```python
reachability_rules = interop().read_edn(
    '[[(reachable ?start ?end) '
    '  [?start :link/to ?end]] '
    ' [(reachable ?start ?end) '
    '  [?start :link/to ?mid] '
    '  (reachable ?mid ?end)]]')
```

```javascript
const reachabilityRules = await interop().readEdn(
  "[[(reachable ?start ?end) " +
  "  [?start :link/to ?end]] " +
  " [(reachable ?start ?end) " +
  "  [?start :link/to ?mid] " +
  "  (reachable ?mid ?end)]]");
```

</div>

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

The base case uses `[(ground ?place) ?country]` as an identity binding: after
`?place` is known to be a country, the rule copies that entity id into
`?country`. In other words, a country is its own country for the purpose of this
rule, while non-country places recurse through `:place/isPartOf`.

This rule works for both direct and indirect containment. If `Paris` is part of
`France`, and `France` is part of `Europe`, the rule stops at the first ancestor
whose `:place/type` is `"Country"`.

### Bounded Neighborhoods with Distance

Some graph queries should not traverse arbitrarily far. They ask for neighbors
within a fixed radius, while preserving the distance so the final result can be
ordered or aggregated.

<div class="multi-lang">

<!-- pdf-listing: Bounded graph neighborhood rules with distance -->

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

<div class="multi-lang">

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

```java
Object alternatingRules = Datalevin.edn(
    "[[(path-a ?from ?to) " +
    "  [?from :edge/a ?to]] " +
    " [(path-a ?from ?to) " +
    "  [?mid :edge/a ?to] " +
    "  (path-b ?from ?mid)] " +
    " [(path-b ?from ?to) " +
    "  [?from :edge/b ?to]] " +
    " [(path-b ?from ?to) " +
    "  [?mid :edge/b ?to] " +
    "  (path-a ?from ?mid)]]");

Object result = conn.query(
    "[:find ?to " +
    " :in $ % ?from " +
    " :where (path-a ?from ?to)]",
    alternatingRules,
    startId);
```

```python
alternating_rules = interop().read_edn(
    '[[(path-a ?from ?to) '
    '  [?from :edge/a ?to]] '
    ' [(path-a ?from ?to) '
    '  [?mid :edge/a ?to] '
    '  (path-b ?from ?mid)] '
    ' [(path-b ?from ?to) '
    '  [?from :edge/b ?to]] '
    ' [(path-b ?from ?to) '
    '  [?mid :edge/b ?to] '
    '  (path-a ?from ?mid)]]')

result = conn.query(
    '[:find ?to '
    ' :in $ % ?from '
    ' :where (path-a ?from ?to)]',
    alternating_rules,
    start_id)
```

```javascript
const alternatingRules = await interop().readEdn(
  "[[(path-a ?from ?to) " +
  "  [?from :edge/a ?to]] " +
  " [(path-a ?from ?to) " +
  "  [?mid :edge/a ?to] " +
  "  (path-b ?from ?mid)] " +
  " [(path-b ?from ?to) " +
  "  [?from :edge/b ?to]] " +
  " [(path-b ?from ?to) " +
  "  [?mid :edge/b ?to] " +
  "  (path-a ?from ?mid)]]");

const result = await conn.query(
  "[:find ?to " +
  " :in $ % ?from " +
  " :where (path-a ?from ?to)]",
  alternatingRules,
  startId);
```

</div>

`path-a` depends on `path-b`, and `path-b` depends on `path-a`. Datalevin
evaluates these rules together, rather than expanding one call stack
top-down. That is what allows mutually recursive graph definitions to remain
practical.

### Branching Traversals with `or-join`

Complex queries often need to combine several graph shapes into one logical
binding. For graph neighborhoods such as "people within two hops" from a start
person, where a person can be reached either directly or through one
intermediate friend, `or-join` can be used to cover the alternatives:

<div class="multi-lang">

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

```java
Object result = conn.query(
    "[:find ?person " +
    " :in $ ?person-id " +
    " :where [?start :person/id ?person-id] " +
    "        (or-join [?start ?person] " +
    "          (and [?k :knows/person1 ?start] " +
    "               [?k :knows/person2 ?person]) " +
    "          (and [?k1 :knows/person1 ?start] " +
    "               [?k1 :knows/person2 ?mid] " +
    "               [?k2 :knows/person1 ?mid] " +
    "               [?k2 :knows/person2 ?person] " +
    "               [(not= ?person ?start)]))]",
    personId);
```

```python
result = conn.query(
    '[:find ?person '
    ' :in $ ?person-id '
    ' :where [?start :person/id ?person-id] '
    '        (or-join [?start ?person] '
    '          (and [?k :knows/person1 ?start] '
    '               [?k :knows/person2 ?person]) '
    '          (and [?k1 :knows/person1 ?start] '
    '               [?k1 :knows/person2 ?mid] '
    '               [?k2 :knows/person1 ?mid] '
    '               [?k2 :knows/person2 ?person] '
    '               [(not= ?person ?start)]))]',
    person_id)
```

```javascript
const result = await conn.query(
  "[:find ?person " +
  " :in $ ?person-id " +
  " :where [?start :person/id ?person-id] " +
  "        (or-join [?start ?person] " +
  "          (and [?k :knows/person1 ?start] " +
  "               [?k :knows/person2 ?person]) " +
  "          (and [?k1 :knows/person1 ?start] " +
  "               [?k1 :knows/person2 ?mid] " +
  "               [?k2 :knows/person1 ?mid] " +
  "               [?k2 :knows/person2 ?person] " +
  "               [(not= ?person ?start)]))]",
  personId);
```

</div>

Use `or-join` when the alternatives bind the same logical result through
different graph patterns. The join variables, here `?start` and `?person`, tell
the query engine which values must be shared between the branch and the rest of
the query. Each `or-join` branch that contains more than one clause is wrapped
in `(and ...)`, because the branch itself must be one syntactic alternative.
This is especially useful before aggregating over messages, forums, tags, or
memberships reached through several allowed path shapes.

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

## 4. More Graph Problem Shapes

The previous examples are social-network and hierarchy shaped, but the same
modeling tools apply to many graph problems. The important step is to identify
what the nodes are, what the edges mean, and whether the edge needs its own
facts.

### 4.1 Co-Appearance and Degrees of Separation

The "six degrees of Kevin Bacon" problem, depicted in Figure 13.3, is a useful
graph example because it is not a tree. It is a dense bipartite graph: two kinds
of nodes, people and movies, with many connections between the two sets. People
connect to movies through credits, and two people are adjacent if they worked on
the same movie. A Datomic article by Andrew Dennis used this problem to show
both Datalog joins and application-level breadth-first search over database
facts [4]. The same idea applies naturally to Datalevin.

![Co-appearance as a bipartite graph: people (Kevin Bacon, John Belushi, Tom Hanks) and movies (Animal House, Apollo 13) connected through Credit entities, projected to a person-to-person acted-with graph where Bacon links to Belushi via Animal House and to Hanks via Apollo 13, while Belushi and Hanks share no movie](/images/diagrams/coappearance-bipartite.svg)

Model the credit as an entity, not as redundant lists on both actors and
movies:

<div class="multi-lang">

```clojure
(def movie-schema
  {:person/name   {:db/valueType :db.type/string
                   :db/unique    :db.unique/identity}
   :movie/title   {:db/valueType :db.type/string
                   :db/unique    :db.unique/identity}
   :movie/year    {:db/valueType :db.type/long}
   :credit/person {:db/valueType :db.type/ref}
   :credit/movie  {:db/valueType :db.type/ref}
   :credit/role   {:db/valueType :db.type/keyword}})
```

```java
Schema movieSchema = Datalevin.schema()
    .attr("person/name", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("movie/title", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("movie/year", Schema.attribute()
        .valueType(Schema.ValueType.LONG))
    .attr("credit/person", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("credit/movie", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("credit/role", Schema.attribute()
        .valueType(Schema.ValueType.KEYWORD));
```

```python
movie_schema = {
    ":person/name": {":db/valueType": ":db.type/string",
                     ":db/unique": ":db.unique/identity"},
    ":movie/title": {":db/valueType": ":db.type/string",
                     ":db/unique": ":db.unique/identity"},
    ":movie/year": {":db/valueType": ":db.type/long"},
    ":credit/person": {":db/valueType": ":db.type/ref"},
    ":credit/movie": {":db/valueType": ":db.type/ref"},
    ":credit/role": {":db/valueType": ":db.type/keyword"}}
```

```javascript
const movieSchema = {
  ":person/name": {":db/valueType": ":db.type/string",
                   ":db/unique": ":db.unique/identity"},
  ":movie/title": {":db/valueType": ":db.type/string",
                   ":db/unique": ":db.unique/identity"},
  ":movie/year": {":db/valueType": ":db.type/long"},
  ":credit/person": {":db/valueType": ":db.type/ref"},
  ":credit/movie": {":db/valueType": ":db.type/ref"},
  ":credit/role": {":db/valueType": ":db.type/keyword"}
};
```

</div>

A direct co-appearance query is just a join through `:credit/movie`:

<div class="multi-lang">

```clojure
(d/q '[:find ?movie-title
       :in $ ?name-1 ?name-2
       :where
       [?p1 :person/name ?name-1]
       [?p2 :person/name ?name-2]
       [?c1 :credit/person ?p1]
       [?c1 :credit/movie ?movie]
       [?c2 :credit/person ?p2]
       [?c2 :credit/movie ?movie]
       [(not= ?p1 ?p2)]
       [?movie :movie/title ?movie-title]]
     db
     "Kevin Bacon"
     "John Belushi")
```

```java
Object movies = conn.query(
    "[:find ?movie-title " +
    " :in $ ?name-1 ?name-2 " +
    " :where [?p1 :person/name ?name-1] " +
    "        [?p2 :person/name ?name-2] " +
    "        [?c1 :credit/person ?p1] " +
    "        [?c1 :credit/movie ?movie] " +
    "        [?c2 :credit/person ?p2] " +
    "        [?c2 :credit/movie ?movie] " +
    "        [(not= ?p1 ?p2)] " +
    "        [?movie :movie/title ?movie-title]]",
    "Kevin Bacon",
    "John Belushi");
```

```python
movies = conn.query(
    '[:find ?movie-title '
    ' :in $ ?name-1 ?name-2 '
    ' :where [?p1 :person/name ?name-1] '
    '        [?p2 :person/name ?name-2] '
    '        [?c1 :credit/person ?p1] '
    '        [?c1 :credit/movie ?movie] '
    '        [?c2 :credit/person ?p2] '
    '        [?c2 :credit/movie ?movie] '
    '        [(not= ?p1 ?p2)] '
    '        [?movie :movie/title ?movie-title]]',
    "Kevin Bacon",
    "John Belushi")
```

```javascript
const movies = await conn.query(
  "[:find ?movie-title " +
  " :in $ ?name-1 ?name-2 " +
  " :where [?p1 :person/name ?name-1] " +
  "        [?p2 :person/name ?name-2] " +
  "        [?c1 :credit/person ?p1] " +
  "        [?c1 :credit/movie ?movie] " +
  "        [?c2 :credit/person ?p2] " +
  "        [?c2 :credit/movie ?movie] " +
  "        [(not= ?p1 ?p2)] " +
  "        [?movie :movie/title ?movie-title]]",
  "Kevin Bacon",
  "John Belushi");
```

</div>

That answers "Bacon number 1" questions. For a fixed small radius, you can add
more joins or generate bounded rules, as shown earlier in this chapter. For an
unknown shortest path, use the database to supply neighbors and run a normal
graph algorithm in the application. Datalevin makes this practical because the
same current DB snapshot can be queried repeatedly and refs are indexed.

<!-- pdf-listing: Shortest-path helper over Datalevin graph facts -->

<div class="multi-lang">

```clojure
(defn acted-with
  "Return [other-person-eid movie-eid] pairs adjacent to actor."
  [db actor]
  (d/q '[:find ?other ?movie
         :in $ ?actor
         :where
         [?c1 :credit/person ?actor]
         [?c1 :credit/movie ?movie]
         [?c2 :credit/movie ?movie]
         [?c2 :credit/person ?other]
         [(not= ?actor ?other)]]
       db actor))

(defn entity-label [db eid]
  (let [m (d/pull db '[:person/name :movie/title] eid)]
    (or (:person/name m)
        (:movie/title m))))

(defn bacon-path
  "Return one shortest alternating [actor movie actor ...] path."
  [db start-name target-name]
  (let [start  (d/q '[:find ?e .
                      :in $ ?name
                      :where [?e :person/name ?name]]
                    db start-name)
        target (d/q '[:find ?e .
                      :in $ ?name
                      :where [?e :person/name ?name]]
                    db target-name)
        edges  (memoize #(acted-with db %))]
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [start])
           seen  #{start}]
      (when-let [path (peek queue)]
        (let [actor (peek path)]
          (if (= actor target)
            (mapv (partial entity-label db) path)
            (let [next-edges (remove #(seen (first %)) (edges actor))]
              (recur
                (into (pop queue)
                      (map (fn [[other movie]]
                             (conj path movie other))
                           next-edges))
                (into seen (map first next-edges))))))))))
```

```java
record CreditEdge(Object other, Object movie) {}

static List<CreditEdge> actedWith(Connection conn, Object actor) {
    List<?> rows = (List<?>) conn.query(
        "[:find ?other ?movie " +
        " :in $ ?actor " +
        " :where [?c1 :credit/person ?actor] " +
        "        [?c1 :credit/movie ?movie] " +
        "        [?c2 :credit/movie ?movie] " +
        "        [?c2 :credit/person ?other] " +
        "        [(not= ?actor ?other)]]",
        actor);
    return rows.stream()
        .map(row -> (List<?>) row)
        .map(row -> new CreditEdge(row.get(0), row.get(1)))
        .toList();
}

static String entityLabel(Connection conn, Object eid) {
    Map<?, ?> entity = conn.pull("[:person/name :movie/title]", eid);
    Object person = entity.get(Datalevin.kw("person/name"));
    Object movie = entity.get(Datalevin.kw("movie/title"));
    return String.valueOf(person != null ? person : movie);
}

static List<String> baconPath(Connection conn,
                              String startName,
                              String targetName) {
    Object start = conn.query(
        "[:find ?e . :in $ ?name :where [?e :person/name ?name]]",
        startName);
    Object target = conn.query(
        "[:find ?e . :in $ ?name :where [?e :person/name ?name]]",
        targetName);

    Queue<List<Object>> queue = new ArrayDeque<>();
    Set<Object> seen = new HashSet<>();
    queue.add(List.of(start));
    seen.add(start);

    while (!queue.isEmpty()) {
        List<Object> path = queue.remove();
        Object actor = path.get(path.size() - 1);
        if (actor.equals(target)) {
            return path.stream()
                .map(eid -> entityLabel(conn, eid))
                .toList();
        }
        for (CreditEdge edge : actedWith(conn, actor)) {
            if (seen.add(edge.other())) {
                ArrayList<Object> next = new ArrayList<>(path);
                next.add(edge.movie());
                next.add(edge.other());
                queue.add(next);
            }
        }
    }
    return List.of();
}
```

```python
from collections import deque

def acted_with(conn, actor):
    return conn.query("""
[:find ?other ?movie
 :in $ ?actor
 :where
 [?c1 :credit/person ?actor]
 [?c1 :credit/movie ?movie]
 [?c2 :credit/movie ?movie]
 [?c2 :credit/person ?other]
 [(not= ?actor ?other)]]
""", actor)

def entity_label(conn, eid):
    entity = conn.pull("[:person/name :movie/title]", eid)
    return entity.get(":person/name") or entity.get(":movie/title")

def bacon_path(conn, start_name, target_name):
    start = conn.query(
        "[:find ?e . :in $ ?name :where [?e :person/name ?name]]",
        start_name)
    target = conn.query(
        "[:find ?e . :in $ ?name :where [?e :person/name ?name]]",
        target_name)

    queue = deque([[start]])
    seen = {start}

    while queue:
        path = queue.popleft()
        actor = path[-1]
        if actor == target:
            return [entity_label(conn, eid) for eid in path]
        for other, movie in acted_with(conn, actor):
            if other not in seen:
                seen.add(other)
                queue.append([*path, movie, other])
    return []
```

```javascript
async function actedWith(conn, actor) {
  return await conn.query(
    `[:find ?other ?movie
      :in $ ?actor
      :where
      [?c1 :credit/person ?actor]
      [?c1 :credit/movie ?movie]
      [?c2 :credit/movie ?movie]
      [?c2 :credit/person ?other]
      [(not= ?actor ?other)]]`,
    actor);
}

async function entityLabel(conn, eid) {
  const entity = await conn.pull("[:person/name :movie/title]", eid);
  return entity[":person/name"] ?? entity[":movie/title"];
}

async function baconPath(conn, startName, targetName) {
  const start = await conn.query(
    "[:find ?e . :in $ ?name :where [?e :person/name ?name]]",
    startName);
  const target = await conn.query(
    "[:find ?e . :in $ ?name :where [?e :person/name ?name]]",
    targetName);

  const queue = [[start]];
  const seen = new Set([start]);

  while (queue.length > 0) {
    const path = queue.shift();
    const actor = path[path.length - 1];
    if (actor === target) {
      return await Promise.all(path.map((eid) => entityLabel(conn, eid)));
    }
    for (const [other, movie] of await actedWith(conn, actor)) {
      if (!seen.has(other)) {
        seen.add(other);
        queue.push([...path, movie, other]);
      }
    }
  }
  return [];
}
```

</div>

Listing 13.2 treats Datalevin as the graph store and keeps shortest-path policy
in ordinary code. That is often the right split: use Datalog for the local
neighborhood relation, and use a graph algorithm when the query needs a global
search strategy.

### 4.2 Dependency and Impact Analysis

Package managers, build systems, data pipelines, and microservice deployments
often need the reverse of a dependency graph question. If `core` changes, what
must be rebuilt? If a table changes, which reports are affected?

Model each dependency as an edge from the dependent item to the thing it uses:

<div class="multi-lang">

```clojure
{:component/name {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
 :component/uses {:db/valueType :db.type/ref}}
```

```java
Schema componentSchema = Datalevin.schema()
    .attr("component/name", Schema.attribute()
        .valueType(Schema.ValueType.STRING)
        .unique(Schema.Unique.IDENTITY))
    .attr("component/uses", Schema.attribute()
        .valueType(Schema.ValueType.REF));
```

```python
component_schema = {
    ":component/name": {":db/valueType": ":db.type/string",
                        ":db/unique": ":db.unique/identity"},
    ":component/uses": {":db/valueType": ":db.type/ref"}}
```

```javascript
const componentSchema = {
  ":component/name": {":db/valueType": ":db.type/string",
                      ":db/unique": ":db.unique/identity"},
  ":component/uses": {":db/valueType": ":db.type/ref"}
};
```

</div>

Then define reachability over `:component/uses`:

<div class="multi-lang">

```clojure
(def dependency-rules
  '[[(depends-on ?component ?dependency)
     [?component :component/uses ?dependency]]

    [(depends-on ?component ?dependency)
     [?component :component/uses ?direct]
     (depends-on ?direct ?dependency)]])
```

```java
Object dependencyRules = Datalevin.edn(
    "[[(depends-on ?component ?dependency) " +
    "  [?component :component/uses ?dependency]] " +
    " [(depends-on ?component ?dependency) " +
    "  [?component :component/uses ?direct] " +
    "  (depends-on ?direct ?dependency)]]");
```

```python
dependency_rules = interop().read_edn(
    '[[(depends-on ?component ?dependency) '
    '  [?component :component/uses ?dependency]] '
    ' [(depends-on ?component ?dependency) '
    '  [?component :component/uses ?direct] '
    '  (depends-on ?direct ?dependency)]]')
```

```javascript
const dependencyRules = await interop().readEdn(
  "[[(depends-on ?component ?dependency) " +
  "  [?component :component/uses ?dependency]] " +
  " [(depends-on ?component ?dependency) " +
  "  [?component :component/uses ?direct] " +
  "  (depends-on ?direct ?dependency)]]");
```

</div>

To find everything impacted by a changed component, bind the dependency side
and ask which components depend on it:

<div class="multi-lang">

```clojure
(d/q '[:find ?impacted-name
       :in $ % ?changed-name
       :where
       [?changed :component/name ?changed-name]
       (depends-on ?impacted ?changed)
       [?impacted :component/name ?impacted-name]]
     db dependency-rules "core")
```

```java
Object impacted = conn.query(
    "[:find ?impacted-name " +
    " :in $ % ?changed-name " +
    " :where [?changed :component/name ?changed-name] " +
    "        (depends-on ?impacted ?changed) " +
    "        [?impacted :component/name ?impacted-name]]",
    dependencyRules,
    "core");
```

```python
impacted = conn.query(
    '[:find ?impacted-name '
    ' :in $ % ?changed-name '
    ' :where [?changed :component/name ?changed-name] '
    '        (depends-on ?impacted ?changed) '
    '        [?impacted :component/name ?impacted-name]]',
    dependency_rules,
    "core")
```

```javascript
const impacted = await conn.query(
  "[:find ?impacted-name " +
  " :in $ % ?changed-name " +
  " :where [?changed :component/name ?changed-name] " +
  "        (depends-on ?impacted ?changed) " +
  "        [?impacted :component/name ?impacted-name]]",
  dependencyRules,
  "core");
```

</div>

The same pattern works for "which policies inherit this rule?", "which
dashboards read from this dataset?", and "which generated files depend on this
source file?"

### 4.3 Weighted Paths and Route Search

Some graph questions are about reachability; others are about optimal paths.
Route planning, network latency, risk propagation, and cost-based dependency
planning all attach weights to edges.

Model a weighted edge as an entity:

<div class="multi-lang">

```clojure
{:route/from {:db/valueType :db.type/ref}
 :route/to   {:db/valueType :db.type/ref}
 :route/km   {:db/valueType :db.type/double}}
```

```java
Schema routeSchema = Datalevin.schema()
    .attr("route/from", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("route/to", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("route/km", Schema.attribute()
        .valueType(Schema.ValueType.DOUBLE));
```

```python
route_schema = {
    ":route/from": {":db/valueType": ":db.type/ref"},
    ":route/to": {":db/valueType": ":db.type/ref"},
    ":route/km": {":db/valueType": ":db.type/double"}}
```

```javascript
const routeSchema = {
  ":route/from": {":db/valueType": ":db.type/ref"},
  ":route/to": {":db/valueType": ":db.type/ref"},
  ":route/km": {":db/valueType": ":db.type/double"}
};
```

</div>

Datalog can enumerate bounded path shapes and sum their weights when the bound
is small:

<div class="multi-lang">

```clojure
(d/q '[:find ?mid-name ?km
       :in $ ?from-name ?to-name
       :where
       [?from :place/name ?from-name]
       [?to :place/name ?to-name]
       [?r1 :route/from ?from]
       [?r1 :route/to ?mid]
       [?r1 :route/km ?km1]
       [?r2 :route/from ?mid]
       [?r2 :route/to ?to]
       [?r2 :route/km ?km2]
       [(+ ?km1 ?km2) ?km]
       [?mid :place/name ?mid-name]]
     db "Paris" "Berlin")
```

```java
Object routes = conn.query(
    "[:find ?mid-name ?km " +
    " :in $ ?from-name ?to-name " +
    " :where [?from :place/name ?from-name] " +
    "        [?to :place/name ?to-name] " +
    "        [?r1 :route/from ?from] " +
    "        [?r1 :route/to ?mid] " +
    "        [?r1 :route/km ?km1] " +
    "        [?r2 :route/from ?mid] " +
    "        [?r2 :route/to ?to] " +
    "        [?r2 :route/km ?km2] " +
    "        [(+ ?km1 ?km2) ?km] " +
    "        [?mid :place/name ?mid-name]]",
    "Paris",
    "Berlin");
```

```python
routes = conn.query(
    '[:find ?mid-name ?km '
    ' :in $ ?from-name ?to-name '
    ' :where [?from :place/name ?from-name] '
    '        [?to :place/name ?to-name] '
    '        [?r1 :route/from ?from] '
    '        [?r1 :route/to ?mid] '
    '        [?r1 :route/km ?km1] '
    '        [?r2 :route/from ?mid] '
    '        [?r2 :route/to ?to] '
    '        [?r2 :route/km ?km2] '
    '        [(+ ?km1 ?km2) ?km] '
    '        [?mid :place/name ?mid-name]]',
    "Paris",
    "Berlin")
```

```javascript
const routes = await conn.query(
  "[:find ?mid-name ?km " +
  " :in $ ?from-name ?to-name " +
  " :where [?from :place/name ?from-name] " +
  "        [?to :place/name ?to-name] " +
  "        [?r1 :route/from ?from] " +
  "        [?r1 :route/to ?mid] " +
  "        [?r1 :route/km ?km1] " +
  "        [?r2 :route/from ?mid] " +
  "        [?r2 :route/to ?to] " +
  "        [?r2 :route/km ?km2] " +
  "        [(+ ?km1 ?km2) ?km] " +
  "        [?mid :place/name ?mid-name]]",
  "Paris",
  "Berlin");
```

</div>

For unbounded weighted shortest path, use Dijkstra, A*, or another application
algorithm. Let Datalevin answer the indexed neighbor query, but let the
algorithm own the priority queue and stopping condition. That keeps the
database model clean and avoids pretending that every graph algorithm should be
encoded as one Datalog query.

### 4.4 Lineage Through Relationship Nodes

Some graphs are not stored as one obvious edge. For example, doctoral student
advisor lineage is traced through dissertations: a person advises a
dissertation, and the dissertation points to the student who authored it [5].
The logical edge "advisor advised student" is therefore a derived relation, not
a stored attribute.

<div class="multi-lang">

```clojure
{:person/name        {:db/valueType :db.type/string}
 :person/advised     {:db/valueType   :db.type/ref
                      :db/cardinality :db.cardinality/many}
 :dissertation/cid   {:db/valueType :db.type/ref}
 :dissertation/univ  {:db/valueType :db.type/string}
 :dissertation/area  {:db/valueType :db.type/string}
 :dissertation/title {:db/valueType :db.type/string}}
```

```java
Schema academicSchema = Datalevin.schema()
    .attr("person/name", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("person/advised", Schema.attribute()
        .valueType(Schema.ValueType.REF)
        .cardinality(Schema.Cardinality.MANY))
    .attr("dissertation/cid", Schema.attribute()
        .valueType(Schema.ValueType.REF))
    .attr("dissertation/univ", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("dissertation/area", Schema.attribute()
        .valueType(Schema.ValueType.STRING))
    .attr("dissertation/title", Schema.attribute()
        .valueType(Schema.ValueType.STRING));
```

```python
academic_schema = {
    ":person/name": {":db/valueType": ":db.type/string"},
    ":person/advised": {":db/valueType": ":db.type/ref",
                        ":db/cardinality": ":db.cardinality/many"},
    ":dissertation/cid": {":db/valueType": ":db.type/ref"},
    ":dissertation/univ": {":db/valueType": ":db.type/string"},
    ":dissertation/area": {":db/valueType": ":db.type/string"},
    ":dissertation/title": {":db/valueType": ":db.type/string"}}
```

```javascript
const academicSchema = {
  ":person/name": {":db/valueType": ":db.type/string"},
  ":person/advised": {":db/valueType": ":db.type/ref",
                      ":db/cardinality": ":db.cardinality/many"},
  ":dissertation/cid": {":db/valueType": ":db.type/ref"},
  ":dissertation/univ": {":db/valueType": ":db.type/string"},
  ":dissertation/area": {":db/valueType": ":db.type/string"},
  ":dissertation/title": {":db/valueType": ":db.type/string"}
};
```

</div>

Rules give names to the useful graph relations:

<div class="multi-lang">

```clojure
(def academic-rules
  '[[(author ?dissertation ?person)
     [?dissertation :dissertation/cid ?person]]

    [(advised ?advisor ?student)
     [?advisor :person/advised ?dissertation]
     (author ?dissertation ?student)]

    [(academic-ancestor ?ancestor ?student)
     (advised ?ancestor ?student)]

    [(academic-ancestor ?ancestor ?student)
     (advised ?ancestor ?middle)
     (academic-ancestor ?middle ?student)]])
```

```java
Object academicRules = Datalevin.edn(
    "[[(author ?dissertation ?person) " +
    "  [?dissertation :dissertation/cid ?person]] " +
    " [(advised ?advisor ?student) " +
    "  [?advisor :person/advised ?dissertation] " +
    "  (author ?dissertation ?student)] " +
    " [(academic-ancestor ?ancestor ?student) " +
    "  (advised ?ancestor ?student)] " +
    " [(academic-ancestor ?ancestor ?student) " +
    "  (advised ?ancestor ?middle) " +
    "  (academic-ancestor ?middle ?student)]]");
```

```python
academic_rules = interop().read_edn(
    '[[(author ?dissertation ?person) '
    '  [?dissertation :dissertation/cid ?person]] '
    ' [(advised ?advisor ?student) '
    '  [?advisor :person/advised ?dissertation] '
    '  (author ?dissertation ?student)] '
    ' [(academic-ancestor ?ancestor ?student) '
    '  (advised ?ancestor ?student)] '
    ' [(academic-ancestor ?ancestor ?student) '
    '  (advised ?ancestor ?middle) '
    '  (academic-ancestor ?middle ?student)]]')
```

```javascript
const academicRules = await interop().readEdn(
  "[[(author ?dissertation ?person) " +
  "  [?dissertation :dissertation/cid ?person]] " +
  " [(advised ?advisor ?student) " +
  "  [?advisor :person/advised ?dissertation] " +
  "  (author ?dissertation ?student)] " +
  " [(academic-ancestor ?ancestor ?student) " +
  "  (advised ?ancestor ?student)] " +
  " [(academic-ancestor ?ancestor ?student) " +
  "  (advised ?ancestor ?middle) " +
  "  (academic-ancestor ?middle ?student)]]");
```

</div>

Now "find all academic ancestors of a person" is a graph traversal over a
relation that was assembled from several attributes:

<div class="multi-lang">

```clojure
(d/q '[:find [?ancestor-name ...]
       :in $ % ?student-name
       :where
       [?student :person/name ?student-name]
       (academic-ancestor ?ancestor ?student)
       [?ancestor :person/name ?ancestor-name]]
     db academic-rules "David Scott Warren")
```

```java
Object ancestors = conn.query(
    "[:find [?ancestor-name ...] " +
    " :in $ % ?student-name " +
    " :where [?student :person/name ?student-name] " +
    "        (academic-ancestor ?ancestor ?student) " +
    "        [?ancestor :person/name ?ancestor-name]]",
    academicRules,
    "David Scott Warren");
```

```python
ancestors = conn.query(
    '[:find [?ancestor-name ...] '
    ' :in $ % ?student-name '
    ' :where [?student :person/name ?student-name] '
    '        (academic-ancestor ?ancestor ?student) '
    '        [?ancestor :person/name ?ancestor-name]]',
    academic_rules,
    "David Scott Warren")
```

```javascript
const ancestors = await conn.query(
  "[:find [?ancestor-name ...] " +
  " :in $ % ?student-name " +
  " :where [?student :person/name ?student-name] " +
  "        (academic-ancestor ?ancestor ?student) " +
  "        [?ancestor :person/name ?ancestor-name]]",
  academicRules,
  "David Scott Warren");
```

</div>

This is a good pattern when the edge has a first-class domain object behind
it. The dissertation is not just a join artifact; it has title, institution,
area, and year facts of its own. Keeping that node visible preserves domain
meaning while rules recover the convenient graph relation.

### 4.5 Type Graphs and Same-Generation Relations

Graph reasoning is not limited to social links or dependency chains. In some
cases, entities have concrete `:type` facts, and rules derive membership in
broader classes such as `:Student`, `:Faculty`, and `:Person` [6].

<div class="multi-lang">

```clojure
(def university-type-rules
  '[[(is-a ?x ?class)
     [?x :type :GraduateStudent]
     [(ground :Student) ?class]]
    [(is-a ?x ?class)
     [?x :type :UndergraduateStudent]
     [(ground :Student) ?class]]
    [(is-a ?x ?class)
     (is-a ?x ?student-class)
     [(= ?student-class :Student)]
     [(ground :Person) ?class]]

    [(is-a ?x ?class)
     [?x :type :FullProfessor]
     [(ground :Professor) ?class]]
    [(is-a ?x ?class)
     [?x :type :AssociateProfessor]
     [(ground :Professor) ?class]]
    [(is-a ?x ?class)
     (is-a ?x ?professor-class)
     [(= ?professor-class :Professor)]
     [(ground :Faculty) ?class]]
    [(is-a ?x ?class)
     (is-a ?x ?faculty-class)
     [(= ?faculty-class :Faculty)]
     [(ground :Person) ?class]]])
```

```java
Object universityTypeRules = Datalevin.edn(
    "[[(is-a ?x ?class) " +
    "  [?x :type :GraduateStudent] " +
    "  [(ground :Student) ?class]] " +
    " [(is-a ?x ?class) " +
    "  [?x :type :UndergraduateStudent] " +
    "  [(ground :Student) ?class]] " +
    " [(is-a ?x ?class) " +
    "  (is-a ?x ?student-class) " +
    "  [(= ?student-class :Student)] " +
    "  [(ground :Person) ?class]] " +
    " [(is-a ?x ?class) " +
    "  [?x :type :FullProfessor] " +
    "  [(ground :Professor) ?class]] " +
    " [(is-a ?x ?class) " +
    "  [?x :type :AssociateProfessor] " +
    "  [(ground :Professor) ?class]] " +
    " [(is-a ?x ?class) " +
    "  (is-a ?x ?professor-class) " +
    "  [(= ?professor-class :Professor)] " +
    "  [(ground :Faculty) ?class]] " +
    " [(is-a ?x ?class) " +
    "  (is-a ?x ?faculty-class) " +
    "  [(= ?faculty-class :Faculty)] " +
    "  [(ground :Person) ?class]]]");
```

```python
university_type_rules = interop().read_edn(
    '[[(is-a ?x ?class) '
    '  [?x :type :GraduateStudent] '
    '  [(ground :Student) ?class]] '
    ' [(is-a ?x ?class) '
    '  [?x :type :UndergraduateStudent] '
    '  [(ground :Student) ?class]] '
    ' [(is-a ?x ?class) '
    '  (is-a ?x ?student-class) '
    '  [(= ?student-class :Student)] '
    '  [(ground :Person) ?class]] '
    ' [(is-a ?x ?class) '
    '  [?x :type :FullProfessor] '
    '  [(ground :Professor) ?class]] '
    ' [(is-a ?x ?class) '
    '  [?x :type :AssociateProfessor] '
    '  [(ground :Professor) ?class]] '
    ' [(is-a ?x ?class) '
    '  (is-a ?x ?professor-class) '
    '  [(= ?professor-class :Professor)] '
    '  [(ground :Faculty) ?class]] '
    ' [(is-a ?x ?class) '
    '  (is-a ?x ?faculty-class) '
    '  [(= ?faculty-class :Faculty)] '
    '  [(ground :Person) ?class]]]')
```

```javascript
const universityTypeRules = await interop().readEdn(
  "[[(is-a ?x ?class) " +
  "  [?x :type :GraduateStudent] " +
  "  [(ground :Student) ?class]] " +
  " [(is-a ?x ?class) " +
  "  [?x :type :UndergraduateStudent] " +
  "  [(ground :Student) ?class]] " +
  " [(is-a ?x ?class) " +
  "  (is-a ?x ?student-class) " +
  "  [(= ?student-class :Student)] " +
  "  [(ground :Person) ?class]] " +
  " [(is-a ?x ?class) " +
  "  [?x :type :FullProfessor] " +
  "  [(ground :Professor) ?class]] " +
  " [(is-a ?x ?class) " +
  "  [?x :type :AssociateProfessor] " +
  "  [(ground :Professor) ?class]] " +
  " [(is-a ?x ?class) " +
  "  (is-a ?x ?professor-class) " +
  "  [(= ?professor-class :Professor)] " +
  "  [(ground :Faculty) ?class]] " +
  " [(is-a ?x ?class) " +
  "  (is-a ?x ?faculty-class) " +
  "  [(= ?faculty-class :Faculty)] " +
  "  [(ground :Person) ?class]]]");
```

</div>

A query can then ask at the semantic level it cares about:

<div class="multi-lang">

```clojure
(d/q '[:find ?student ?advisor
       :in $ %
       :where
       (is-a ?student ?class)
       [(= ?class :Student)]
       [?student :advisor ?advisor]]
     db university-type-rules)
```

```java
Object advisors = conn.query(
    "[:find ?student ?advisor " +
    " :in $ % " +
    " :where (is-a ?student ?class) " +
    "        [(= ?class :Student)] " +
    "        [?student :advisor ?advisor]]",
    universityTypeRules);
```

```python
advisors = conn.query(
    '[:find ?student ?advisor '
    ' :in $ % '
    ' :where (is-a ?student ?class) '
    '        [(= ?class :Student)] '
    '        [?student :advisor ?advisor]]',
    university_type_rules)
```

```javascript
const advisors = await conn.query(
  "[:find ?student ?advisor " +
  " :in $ % " +
  " :where (is-a ?student ?class) " +
  "        [(= ?class :Student)] " +
  "        [?student :advisor ?advisor]]",
  universityTypeRules);
```

</div>

This does not require Datalevin attributes themselves to become an ontology.
The class graph is ordinary application data, and the rules describe how that
application wants to interpret it.

The problem of looking for entities of the "same generation" over a parent graph
is different from reachability: it derives a relation between pairs of nodes
that occupy the same structural level.

<div class="multi-lang">

```clojure
(def same-generation-rules
  '[[(same-generation ?x ?y)
     [?x :parent _]
     [(ground ?x) ?y]]

    [(same-generation ?x ?y)
     [?a :parent ?x]
     [?b :parent ?y]
     (same-generation ?a ?b)]])
```

```java
Object sameGenerationRules = Datalevin.edn(
    "[[(same-generation ?x ?y) " +
    "  [?x :parent _] " +
    "  [(ground ?x) ?y]] " +
    " [(same-generation ?x ?y) " +
    "  [?a :parent ?x] " +
    "  [?b :parent ?y] " +
    "  (same-generation ?a ?b)]]");
```

```python
same_generation_rules = interop().read_edn(
    '[[(same-generation ?x ?y) '
    '  [?x :parent _] '
    '  [(ground ?x) ?y]] '
    ' [(same-generation ?x ?y) '
    '  [?a :parent ?x] '
    '  [?b :parent ?y] '
    '  (same-generation ?a ?b)]]')
```

```javascript
const sameGenerationRules = await interop().readEdn(
  "[[(same-generation ?x ?y) " +
  "  [?x :parent _] " +
  "  [(ground ?x) ?y]] " +
  " [(same-generation ?x ?y) " +
  "  [?a :parent ?x] " +
  "  [?b :parent ?y] " +
  "  (same-generation ?a ?b)]]");
```

</div>

The base case says a node that has a parent is in the same generation as
itself. The recursive case says that parents of same-generation nodes are also
same-generation. This kind of query is useful for structural comparisons,
lineage analysis, and rule-engine tests because the derived relation is about
two moving positions in the graph, not one source-to-target path.

---

## 5. Example: Finding the Forum for a Message (LDBC-SNB IS6)

The LDBC Social Network Benchmark (LDBC-SNB) is an industry standard for
evaluating graph databases. It models a social network with people, friendships,
forums, posts, comments, likes, tags, organizations, and places [2]. Let's examine
one real Interactive Short query from this benchmark to see how Datalevin
handles graph traversal in practice.

**Query IS6**: Given a Message, find the Forum that contains it and the Person
who moderates that Forum. Since Comments are not directly contained in Forums,
we must traverse to the original Post in the thread.

First, define a recursive rule to find the root post of any message:

<div class="multi-lang">

```clojure
(def root-post-rule
  '[;; base case
    [(root-post ?m ?post)
     [?m :message/containerOf _]
     [(ground ?m) ?post]] ; copy the root message id into ?post

    ;; recursive case
    [(root-post ?m ?post)
     [?m :message/replyOf ?parent]
     (root-post ?parent ?post)]])
```

```java
Object rootPostRule = Datalevin.edn(
    "[[(root-post ?m ?post) " +
    "  [?m :message/containerOf _] " +
    "  [(ground ?m) ?post]] " +
    " [(root-post ?m ?post) " +
    "  [?m :message/replyOf ?parent] " +
    "  (root-post ?parent ?post)]]");
```

```python
root_post_rule = interop().read_edn(
    '[[(root-post ?m ?post) '
    '  [?m :message/containerOf _] '
    '  [(ground ?m) ?post]] '
    ' [(root-post ?m ?post) '
    '  [?m :message/replyOf ?parent] '
    '  (root-post ?parent ?post)]]')
```

```javascript
const rootPostRule = await interop().readEdn(
  "[[(root-post ?m ?post) " +
  "  [?m :message/containerOf _] " +
  "  [(ground ?m) ?post]] " +
  " [(root-post ?m ?post) " +
  "  [?m :message/replyOf ?parent] " +
  "  (root-post ?parent ?post)]]");
```

</div>

The rule has two branches:

1. If the message has `:message/containerOf`, it is itself a Post (the root)
2. Otherwise, follow `:message/replyOf` recursively until we reach the root

Now the query to find the forum and moderator:

<!-- pdf-listing: Recursive graph query for LDBC-SNB forum lookup -->

<div class="multi-lang">

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

```java
Object forum = conn.query(
    "[:find ?forum-id ?forum-title ?moderator-id " +
    "       ?moderator-first-name ?moderator-last-name " +
    " :in $ % ?message-id " +
    " :where [?message :message/id ?message-id] " +
    "        (root-post ?message ?post) " +
    "        [?post :message/containerOf ?forum] " +
    "        [?forum :forum/id ?forum-id] " +
    "        [?forum :forum/title ?forum-title] " +
    "        [?forum :forum/hasModerator ?moderator] " +
    "        [?moderator :person/id ?moderator-id] " +
    "        [?moderator :person/firstName ?moderator-first-name] " +
    "        [?moderator :person/lastName ?moderator-last-name]]",
    rootPostRule,
    messageId);
```

```python
forum = conn.query(
    '[:find ?forum-id ?forum-title ?moderator-id '
    '       ?moderator-first-name ?moderator-last-name '
    ' :in $ % ?message-id '
    ' :where [?message :message/id ?message-id] '
    '        (root-post ?message ?post) '
    '        [?post :message/containerOf ?forum] '
    '        [?forum :forum/id ?forum-id] '
    '        [?forum :forum/title ?forum-title] '
    '        [?forum :forum/hasModerator ?moderator] '
    '        [?moderator :person/id ?moderator-id] '
    '        [?moderator :person/firstName ?moderator-first-name] '
    '        [?moderator :person/lastName ?moderator-last-name]]',
    root_post_rule,
    message_id)
```

```javascript
const forum = await conn.query(
  "[:find ?forum-id ?forum-title ?moderator-id " +
  "       ?moderator-first-name ?moderator-last-name " +
  " :in $ % ?message-id " +
  " :where [?message :message/id ?message-id] " +
  "        (root-post ?message ?post) " +
  "        [?post :message/containerOf ?forum] " +
  "        [?forum :forum/id ?forum-id] " +
  "        [?forum :forum/title ?forum-title] " +
  "        [?forum :forum/hasModerator ?moderator] " +
  "        [?moderator :person/id ?moderator-id] " +
  "        [?moderator :person/firstName ?moderator-first-name] " +
  "        [?moderator :person/lastName ?moderator-last-name]]",
  rootPostRule,
  messageId);
```

</div>

This query demonstrates several Datalevin strengths:

- **Recursive traversal**: The `root-post` rule follows reply chains of
  arbitrary depth
- **Graph navigation**: Multiple hops from message → post → forum → moderator
- **Rule composition**: The `%` parameter passes rules into the query
- **Unified schema**: All entities use the same attribute-based model

The Datalevin project includes an unofficial LDBC-SNB SF1 benchmark
implementation for both Datalevin and Neo4j [3]. Treat the numbers below as a
reproducible, self-published workload observation, not as a general graph
database guarantee. The reported run used LDBC-SNB scale factor 1
(approximately 3.2M entities and 17.3M edges) on one 2023 Apple M2 Max machine
with 12 cores, 32GB RAM, a 1TB SSD, macOS 15.2, OpenJDK 21, and Clojure
1.12.4. Queries were run twice, with the second run reported. Different Neo4j
versions, memory settings, indexes, query parameters, hardware, or larger scale
factors can change the result.

Under that setup, the benchmark reports that Datalevin was faster than Neo4j on
all seven Interactive Short queries. The reported average latency is 12.6 ms for
Datalevin versus 1908.3 ms for Neo4j, about 151x faster. For the IS6 query shown
above, the reported latency is 1.9 ms for Datalevin versus 1494.5 ms for Neo4j,
about 787x faster [3]. Likely contributing factors are:

- **Index locality**: Following refs is a simple B+Tree lookup
- **Query optimizer**: Efficient plans minimize wasted computation
- **Semi-naive evaluation**: Recursive rules don't re-process facts; see
  Chapter 21.
- **Magic-set rewrites**: Constraints push deep into recursive expansion; see
  Chapter 21.

### 5.1 Other LDBC-SNB Graph Questions

LDBC-SNB contains many graph problems beyond plain traversal:

- **Temporal neighborhoods**: find friends or friends-of-friends who produced
  activity inside a time window, then exclude activity before that window.
- **Co-occurrence**: find tags, topics, or forums that co-occur in posts
  written by a social neighborhood.
- **Recommendation**: find friends-of-friends who are not direct friends, then
  rank them by shared interests or interaction evidence.
- **Referral and expert search**: combine social paths, organizations, places,
  replies, and tag-class descendants into one query.
- **Trusted paths**: enumerate bounded social paths and score them by message
  interactions along each edge.

These examples all use the same Datalevin building blocks: refs for graph
edges, join entities when an edge has facts, rules for reusable derived
relations, and `not-join` when one graph shape must exclude another. The
important modeling lesson is that "graph query" often means a mix of topology,
time, text, taxonomy, and ranking, and these are all readily represented in
Datalevin's native data model.

---

## Summary: Graph Design Principles

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
9.  **Name Derived Graphs**: Use rules when the useful graph relation is
    assembled from several lower-level facts.
10. **Use Algorithms When Needed**: For arbitrary shortest paths, weighted
    routing, or other global graph algorithms, use Datalevin for indexed
    neighbor lookup and keep the search policy in application code.

By treating your data as a graph from the beginning, you unlock powerful
analytical capabilities that are difficult or impossible to achieve with
traditional relational models.

## References

[1] Renzo Angles, ["The Property Graph Database
Model"](https://ceur-ws.org/Vol-2100/paper26.pdf), *Proceedings of the 12th
Alberto Mendelzon International Workshop on Foundations of Data Management*,
CEUR Workshop Proceedings 2100, 2018.

[2] Graph Data Council, ["LDBC Social Network Benchmark (LDBC
SNB)"](https://ldbcouncil.org/benchmarks/snb/), official benchmark overview and
links to the SNB workloads, datasets, and specification.

[3] Datalevin project, ["LDBC SNB
Benchmark"](https://github.com/datalevin/datalevin/tree/master/benchmarks/LDBC-SNB-bench),
benchmark writeup and implementation.

[4] Andrew Dennis, ["Using Datomic as a Graph
Database"](https://hashrocket.com/blog/posts/using-datomic-as-a-graph-database),
Hashrocket, April 10, 2014.

[5] Datalevin project, ["Math
Bench"](https://github.com/datalevin/datalevin/tree/master/benchmarks/math-bench),
benchmark implementation based on the Mathematics Genealogy Project.

[6] Datalevin project,
["OpenRuleBench"](https://github.com/datalevin/datalevin/tree/master/benchmarks/openrulebench), benchmark implementation including transitive closure, same-generation, and
LUBM-style type inference rules.
