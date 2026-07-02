---
title: "Rules and Recursion"
chapter: 9
part: "II — Core APIs: Datalog First, KV When Needed"
---

# Chapter 9: Rules and Recursion

While simple Datalog queries are powerful for finding patterns, real-world
applications often require complex, reusable logic. You might find yourself
repeating the same set of `:where` clauses across many queries, or needing to
traverse hierarchical data of unknown depth (like a file system or an
organizational chart).

This is where **Rules** come in. Rules allow you to define named logic that can
be reused, composed, and even called recursively. They are the "functions" or
"views" of the Datalog world, enabling you to derive new knowledge from your raw
facts.

That derivation is query-time derivation. A rule does not transact new datoms
into the database, and it does not create a stored table. It defines a
**virtual relation**: a named set of tuples that exists while a query is being
evaluated. If you query `(is-active? ?e)`, Datalevin computes the entities that
satisfy that rule for this query. If you need the result to become durable data,
your application should explicitly transact the derived facts.


## 1. What are Rules?

At its simplest, a rule is a named collection of Datalog clauses. You define a
rule once and then invoke it by name within your queries.

Another way to understand rules is to look back at an ordinary query. The
entire `:where` clause can be considered the body of an unnamed rule, and the
`:find` variables are like the rule head: together they define a temporary
relation containing the tuples that satisfy those clauses. Named rules give a
piece of that logic a name, parameter list, and reusable call site.

### The Anatomy of a Rule

A rule consists of a **Head** and a **Body**:

- **Head**: The rule name and its parameters, e.g., `(is-manager? ?e)`.
  The name plus the number of parameters is the rule's shape.
- **Body**: One or more Datalog clauses that must be true for the rule to match.

Read a rule as a definition of a relation. The head names the relation and its
columns. The body says which tuples belong to that relation. Variables in the
head are the values that callers can use; variables that appear only in the body
are local intermediate variables, similar to helper bindings inside a query.

Rules are typically defined as a vector of vectors (a "rule set"):

<div class="multi-lang">

```clojure
(def user-rules
  '[[(is-active? ?e)
     [?e :user/status :active]
     [?e :user/verified? true]]

    [(is-admin? ?e)
     (is-active? ?e) ; Rules can call other rules!
     [?e :user/role :admin]]])
```

```java
Object userRules = Datalevin.edn("[[(is-active? ?e) " +
    "[?e :user/status :active] " +
    "[?e :user/verified? true]] " +
    "[(is-admin? ?e) " +
    "(is-active? ?e) " +   // Rules can call other rules!
    "[?e :user/role :admin]]]");
```

```python
user_rules = interop().read_edn('[[(is-active? ?e) '
    '[?e :user/status :active] '
    '[?e :user/verified? true]] '
    '[(is-admin? ?e) '
    '(is-active? ?e) '   # Rules can call other rules!
    '[?e :user/role :admin]]]')
```

```javascript
const userRules = await interop().readEdn('[[(is-active? ?e) ' +
    '[?e :user/status :active] ' +
    '[?e :user/verified? true]] ' +
    '[(is-admin? ?e) ' +
    '(is-active? ?e) ' +   // Rules can call other rules!
    '[?e :user/role :admin]]]');
```

</div>

The first rule says: an entity belongs to the one-column virtual relation
`is-active?` when it has status `:active` and `:user/verified?` is true. The
second rule says: an entity belongs to `is-admin?` when it belongs to
`is-active?` and has role `:admin`.

> **Key Takeaway**: Rules are your primary tool for **abstraction** and
> **composition**. Use them to name common patterns and build more complex logic
> out of simpler, reusable components.


## 2. Using Rules in Queries

To use rules in a query, you must:

1. Include the `%` symbol in the `:in` clause to represent the rule set.
2. Pass the rule set as the `%` input (`db rules` in Clojure, `rules` after the
   query string in `conn.query`).
3. Invoke the rule in the `:where` clause using its head syntax.

<div class="multi-lang">

```clojure
(let [rules '[[(is-active? ?e)
               [?e :user/status :active]
               [?e :user/verified? true]]]]
  (d/q '[:find ?name
         :in $ %
         :where [?e :user/name ?name]
                (is-active? ?e)]
       db rules))
```

```java
Object rules = Datalevin.edn("[[(is-active? ?e) " +
    "[?e :user/status :active] " +
    "[?e :user/verified? true]]]");

Object result = conn.query("[:find ?name " +
    ":in $ % " +
    ":where [?e :user/name ?name] " +
    "       (is-active? ?e)]",
    rules);
```

```python
rules = interop().read_edn('[[(is-active? ?e) '
    '[?e :user/status :active] '
    '[?e :user/verified? true]]]')

result = conn.query('[:find ?name '
    ':in $ % '
    ':where [?e :user/name ?name] '
    '       (is-active? ?e)]',
    rules)
```

```javascript
const rules = await interop().readEdn('[[(is-active? ?e) ' +
    '[?e :user/status :active] ' +
    '[?e :user/verified? true]]]');

const result = await conn.query('[:find ?name ' +
    ':in $ % ' +
    ':where [?e :user/name ?name] ' +
    '       (is-active? ?e)]',
    rules);
```

</div>

Notice that unlike function calls or triple patterns, rule invocations are not
wrapped in a vector.

In this example, `(is-active? ?e)` acts as a filter. For every entity `?e` that
has a name, the engine checks if that entity also satisfies the clauses defined
in the `is-active?` rule.

The `%` input is just the rule set for this query. Different queries can pass
different rule sets, which makes rules useful for application-specific policies,
tenant-specific logic, tests, and experiments. Rules are data, so you can load
them from EDN, compose them in ordinary code, or generate them from a small
domain-specific configuration when that is appropriate.

### 2.1 Assembling Rule Sets

A rule set is one flat collection of rule definitions. When an application grows,
avoid building one giant literal rule vector in every query namespace. Keep
small, named rule groups close to the domain they describe, assemble them at the
application boundary, and pass the resulting flat rule set as `%`.

The important points are:

- Rule groups are just data. Store them in vars, EDN files, or configuration
  records that your application controls.
- The final value passed as `%` should be a flat vector or sequence of rule
  definitions, not a nested vector of rule groups.
- Rules may call rules from another group as long as the called rule is present
  in the assembled rule set.
- Multiple definitions of the same rule are alternatives, i.e. logical `OR`.
- Keep the public rule names and arities stable. Treat helper rules as internal
  application conventions, just as you would with helper functions.

Here is a typical pattern: one group defines basic user status, another defines
subscription logic, another defines feature access on top of that policy, and an
optional group adds a staff override. The assembled rule set is selected per
query, tenant, feature flag, or test:

```clojure
(def user-status-rules
  '[[(active-user ?u)
     [?u :user/status :user.status/active]
     [?u :user/verified? true]]])

(def subscription-rules
  '[[(paid-user ?u)
     [?sub :subscription/user ?u]
     [?sub :subscription/status :subscription.status/paid]]

    [(premium-user ?u)
     (active-user ?u)
     (paid-user ?u)]])

(def staff-override-rules
  '[[(premium-user ?u)
     (active-user ?u)
     [?u :user/role :user.role/staff]]])

(def feature-access-rules
  '[[(can-use-feature ?u ?feature)
     (premium-user ?u)
     [?feature :feature/min-tier :tier/premium]]

    [(can-use-feature ?u ?feature)
     [?u :user/feature-flag ?feature]]])

(defn assemble-rules
  [& rule-groups]
  (into [] (comp (remove nil?) cat) rule-groups))

(defn user-rules
  [{:keys [staff-override?]}]
  (assemble-rules user-status-rules
                  subscription-rules
                  feature-access-rules
                  (when staff-override?
                    staff-override-rules)))

(d/q '[:find ?feature-name
       :in $ % ?email
       :where [?u :user/email ?email]
              (can-use-feature ?u ?feature)
              [?feature :feature/name ?feature-name]]
     db
     (user-rules {:staff-override? true})
     "alice@example.com")
```

Without `staff-override-rules`, `(premium-user ?u)` means "active user with a
paid subscription." With that group included, the same rule also matches active
staff users. The `can-use-feature` rule does not need to know which policy
module made a user premium; it only depends on the assembled `premium-user`
relation. Nothing about the query changes; only the rule set supplied as `%`
changes. This is the main design advantage of rule assembly: query call sites
can stay stable while policy modules evolve.


## 3. Logical OR: Multiple Rule Definitions

Just like clauses in `:where`, all clauses in a rule body are joined by an
implicit `AND`. To express `OR` in rules, in addition to using `or` or
`or-join`, you can define a rule with the same name and arity multiple times.
The engine treats those definitions as alternatives. If *any* definition
matches, the rule is satisfied.

Keep all definitions of the same rule at the same arity, i.e. the same number
of parameters. In the example below, every `has-access?` definition takes
`?user` and `?resource`.

<div class="multi-lang">

```clojure
(def access-rules
  '[[(has-access? ?user ?resource)
     [?user :user/role :admin]
     [?resource :resource/id]] ; Admins match every resource entity

    [(has-access? ?user ?resource)
     [?user :user/permissions ?resource]] ; Specific permission

    [(has-access? ?user ?resource)
     [?user :user/team ?team]
     [?team :team/permissions ?resource]]]) ; Team-based permission
```

```java
Object accessRules = Datalevin.edn("[[(has-access? ?user ?resource) " +
    "[?user :user/role :admin] " +
    "[?resource :resource/id]] " +              // Admins match every resource entity
    "[(has-access? ?user ?resource) " +
    "[?user :user/permissions ?resource]] " +   // Specific permission
    "[(has-access? ?user ?resource) " +
    "[?user :user/team ?team] " +
    "[?team :team/permissions ?resource]]]");   // Team-based permission
```

```python
access_rules = interop().read_edn('[[(has-access? ?user ?resource) '
    '[?user :user/role :admin] '
    '[?resource :resource/id]] '              # Admins match every resource entity
    '[(has-access? ?user ?resource) '
    '[?user :user/permissions ?resource]] '   # Specific permission
    '[(has-access? ?user ?resource) '
    '[?user :user/team ?team] '
    '[?team :team/permissions ?resource]]]')  # Team-based permission
```

```javascript
const accessRules = await interop().readEdn('[[(has-access? ?user ?resource) ' +
    '[?user :user/role :admin] ' +
    '[?resource :resource/id]] ' +              // Admins match every resource entity
    '[(has-access? ?user ?resource) ' +
    '[?user :user/permissions ?resource]] ' +   // Specific permission
    '[(has-access? ?user ?resource) ' +
    '[?user :user/team ?team] ' +
    '[?team :team/permissions ?resource]]]');   // Team-based permission
```

</div>

When you query `(has-access? ?u ?r)`, Datalevin will return results if the user
is an admin over a resource entity, OR has a direct permission, OR has a team
permission. This is much cleaner and more maintainable than using a complex
`(or ...)` block in every query.

The same mechanism works when one logical value can come from several
attributes. For example, this rule says that `?alias` may come from a person's
name, legal name, or nickname:

```clojure
(def alias-rules
  '[[(alias-of ?person ?alias)
     [?person :person/name ?alias]]

    [(alias-of ?person ?alias)
     [?person :person/legal-name ?alias]]

    [(alias-of ?person ?alias)
     [?person :person/nickname ?alias]]])
```

Each definition contributes rows to the derived relation
`(alias-of ?person ?alias)`. If the same alias is found through more than one
definition, normal Datalog set semantics remove the duplicate result. Use
`or-join` for one-off branching inside a single query. Use multiple rule
definitions when the expansion has a name, is reused, or is clearer as part of
the domain model.


## 4. Datalevin as a Reasoner: Bottom-Up Rules

Datalevin evaluates rules bottom-up (see Chapter 21). In this mode, the database
doesn't just store data; it can infer new classifications or higher-level facts
that were never explicitly transacted.

Bottom-up evaluation starts from base facts and repeatedly applies rules to
discover derived tuples until no new tuples can be found. In rule-engine
terminology, this is a forward-chaining style of reasoning: facts drive rule
application, and rule application derives more facts. This still happens inside
a query. Datalevin is not running a background job that stores inferred facts
unless your application chooses to materialize those results.

This is different from Prolog-style backward chaining, where evaluation starts
with a goal and recursively asks what subgoals could prove it. A Datalevin query
does name the rule relations it needs, but recursive rule evaluation itself is
set-oriented and bottom-up. Chapter 21 explains how magic-set rewriting can make
bottom-up evaluation goal-directed without turning it into backward chaining.

An "expert system" is a rule-based classifier: domain experts write conditions,
and the system applies those conditions to facts. In Datalevin, the conditions
are Datalog rules and the classifications are query-time derived tuples.

These terms come from the older expert-system and production-system literature
[1,2,3]. Classic expert systems separated a knowledge base of facts and rules
from an inference engine that derived conclusions. Datalevin's mechanism here is
Datalog rule evaluation, not a production-system shell or a Rete implementation,
but the modeling direction is similar: represent domain knowledge as data and
derive higher-level facts from it.

If you are comparing this style with Clojure production rule engines such as
Clara, the Datalevin repository includes an OpenRuleBench implementation that
exercises recursive workloads such as transitive closure and same-generation
queries [4,5]. In those workloads, Datalevin's bottom-up deductive evaluation
is much more performant than Rete-style production-rule engines [2]. Treat that as
workload-specific benchmark evidence, not as a universal ranking of all
rule-engine use cases.

### Example: Automated Classification (Expert Systems)

Imagine an e-commerce system that needs to determine if a user qualifies for
"VIP Status" based on a complex set of evolving business rules. Instead of
hard-coding these rules in your application, you can define them as a reasoning
set.

<div class="multi-lang">

```clojure
(def reasoning-rules
  '[;; Rule 1: High Spender
    [(is-high-spender? ?u)
     [?u :user/total-spent ?amount]
     [(> ?amount 5000)]]

    ;; Rule 2: Frequent Shopper
    [(is-frequent-shopper? ?u)
     [?u :user/order-count ?count]
     [(> ?count 20)]]

    ;; Rule 3: Reasoner for VIP Status
    [(vip-status ?u ?tier)
     (is-high-spender? ?u)
     (is-frequent-shopper? ?u)
     [(ground :gold) ?tier]]

    [(vip-status ?u ?tier)
     (or (is-high-spender? ?u)
         (is-frequent-shopper? ?u))
     [(ground :silver) ?tier]]])
```

```java
Object reasoningRules = Datalevin.edn("["
    // Rule 1: High Spender
    + "[(is-high-spender? ?u) "
    + "[?u :user/total-spent ?amount] "
    + "[(> ?amount 5000)]] "
    // Rule 2: Frequent Shopper
    + "[(is-frequent-shopper? ?u) "
    + "[?u :user/order-count ?count] "
    + "[(> ?count 20)]] "
    // Rule 3: Reasoner for VIP Status
    + "[(vip-status ?u ?tier) "
    + "(is-high-spender? ?u) "
    + "(is-frequent-shopper? ?u) "
    + "[(ground :gold) ?tier]] "
    + "[(vip-status ?u ?tier) "
    + "(or (is-high-spender? ?u) "
    + "    (is-frequent-shopper? ?u)) "
    + "[(ground :silver) ?tier]]]");
```

```python
reasoning_rules = interop().read_edn('['
    # Rule 1: High Spender
    '[(is-high-spender? ?u) '
    '[?u :user/total-spent ?amount] '
    '[(> ?amount 5000)]] '
    # Rule 2: Frequent Shopper
    '[(is-frequent-shopper? ?u) '
    '[?u :user/order-count ?count] '
    '[(> ?count 20)]] '
    # Rule 3: Reasoner for VIP Status
    '[(vip-status ?u ?tier) '
    '(is-high-spender? ?u) '
    '(is-frequent-shopper? ?u) '
    '[(ground :gold) ?tier]] '
    '[(vip-status ?u ?tier) '
    '(or (is-high-spender? ?u) '
    '    (is-frequent-shopper? ?u)) '
    '[(ground :silver) ?tier]]]')
```

```javascript
const reasoningRules = await interop().readEdn('['
    // Rule 1: High Spender
    + '[(is-high-spender? ?u) '
    + '[?u :user/total-spent ?amount] '
    + '[(> ?amount 5000)]] '
    // Rule 2: Frequent Shopper
    + '[(is-frequent-shopper? ?u) '
    + '[?u :user/order-count ?count] '
    + '[(> ?count 20)]] '
    // Rule 3: Reasoner for VIP Status
    + '[(vip-status ?u ?tier) '
    + '(is-high-spender? ?u) '
    + '(is-frequent-shopper? ?u) '
    + '[(ground :gold) ?tier]] '
    + '[(vip-status ?u ?tier) '
    + '(or (is-high-spender? ?u) '
    + '    (is-frequent-shopper? ?u)) '
    + '[(ground :silver) ?tier]]]');
```

</div>

The `ground` function is a built-in identity function used in binding clauses.
It can bind a constant into a variable, or copy an already-bound value into a
new variable. The clause `[(ground :gold) ?tier]` means "bind `?tier` to
`:gold`." A clause such as `[(ground ?x) ?y]`, after `?x` is bound, means "bind
`?y` to the same value as `?x`." This is useful in rules because rule heads must
expose variables, not raw constants.

With data like this:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:user/id "u1" :user/total-spent 6000 :user/order-count 25}
   {:user/id "u2" :user/total-spent 6000 :user/order-count 1}
   {:user/id "u3" :user/total-spent 1    :user/order-count 25}])

(d/q '[:find ?id ?tier
       :in $ %
       :where [?u :user/id ?id]
              (vip-status ?u ?tier)]
     db reasoning-rules)
;; => #{["u1" :gold]
;;      ["u1" :silver]
;;      ["u2" :silver]
;;      ["u3" :silver]}
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("user/id", "u1")
        .put("user/total-spent", 6000L)
        .put("user/order-count", 25L))
    .entity(Tx.entity()
        .put("user/id", "u2")
        .put("user/total-spent", 6000L)
        .put("user/order-count", 1L))
    .entity(Tx.entity()
        .put("user/id", "u3")
        .put("user/total-spent", 1L)
        .put("user/order-count", 25L)));

Object result = conn.query("[:find ?id ?tier " +
    ":in $ % " +
    ":where [?u :user/id ?id] " +
    "       (vip-status ?u ?tier)]",
    reasoningRules);
```

```python
conn.transact([
    {":user/id": "u1", ":user/total-spent": 6000, ":user/order-count": 25},
    {":user/id": "u2", ":user/total-spent": 6000, ":user/order-count": 1},
    {":user/id": "u3", ":user/total-spent": 1,    ":user/order-count": 25}])

result = conn.query('[:find ?id ?tier '
    ':in $ % '
    ':where [?u :user/id ?id] '
    '       (vip-status ?u ?tier)]',
    reasoning_rules)
```

```javascript
await conn.transact([
  { ":user/id": "u1", ":user/total-spent": 6000, ":user/order-count": 25 },
  { ":user/id": "u2", ":user/total-spent": 6000, ":user/order-count": 1 },
  { ":user/id": "u3", ":user/total-spent": 1,    ":user/order-count": 25 }
]);

const result = await conn.query('[:find ?id ?tier ' +
    ':in $ % ' +
    ':where [?u :user/id ?id] ' +
    '       (vip-status ?u ?tier)]',
    reasoningRules);
```

</div>

By querying `(vip-status ?u ?tier)`, you are treating Datalevin as an **Expert
System**. The database takes the base facts (amount spent, order count) and
"chains" them forward through your rules to derive matching `vip-status` facts.
This rule set returns every tier a user qualifies for. User `u1` appears twice
because both the gold rule and the silver rule are true. That is normal Datalog
semantics: overlapping alternatives contribute multiple tuples. Add an explicit
precedence rule, aggregate, or filter if your application needs one exclusive
tier.

This approach is useful because:

1. **Composability**: You can build complex reasoning chains out of simple,
   testable rules.
2. **Performance**: Reasoning happens inside the engine, utilizing indexes and
   the optimized semi-naive evaluator.
3. **Consistency**: The "logic" of what defines a VIP is centralized in the
   database schema context, not scattered across microservices or application
   code modules.


## 5. Rule Parameters and Binding

Rules are not limited to single variables. They can take multiple parameters,
and those parameters may be bound or unbound when the rule is called.

In Datalog, "bound" means a variable already has a value from an input or an
earlier clause. "Unbound" means the engine is free to discover values that make
the rule true. Rules are more relational than ordinary functions: a function
call usually has inputs and a return value, but a rule describes a relation.

There is one important practical nuance. Data patterns are naturally relational:
the same attribute can be used to find an entity, find a value, or test that an
entity/value pair exists. Function binding clauses are directional. A clause
such as `[(+ ?x ?y) ?sum]` computes and binds `?sum`; `?sum` should be unbound
when that clause runs. Datalevin does not run arithmetic functions backward:
`[(= ?dist2 100)]` can filter computed distances, but it does not let the engine
infer which `?x` and `?y` values would produce that distance. When writing a
rule that filters on a computed value, express that as a function binding for
the computed variable plus a predicate over that variable. Ordinary data
constraints still determine which candidate tuples the rule has to consider.

<div class="multi-lang">

```clojure
(def distance-rules
  '[[(distance-squared ?p1 ?p2 ?d2)
     [?p1 :point/x ?x1]
     [?p1 :point/y ?y1]
     [?p2 :point/x ?x2]
     [?p2 :point/y ?y2]
     [(- ?x2 ?x1) ?dx]
     [(- ?y2 ?y1) ?dy]
     [(* ?dx ?dx) ?dx2]
     [(* ?dy ?dy) ?dy2]
     [(+ ?dx2 ?dy2) ?d2]]])
```

```java
Object distanceRules = Datalevin.edn("[[(distance-squared ?p1 ?p2 ?d2) " +
    "[?p1 :point/x ?x1] " +
    "[?p1 :point/y ?y1] " +
    "[?p2 :point/x ?x2] " +
    "[?p2 :point/y ?y2] " +
    "[(- ?x2 ?x1) ?dx] " +
    "[(- ?y2 ?y1) ?dy] " +
    "[(* ?dx ?dx) ?dx2] " +
    "[(* ?dy ?dy) ?dy2] " +
    "[(+ ?dx2 ?dy2) ?d2]]]");
```

```python
distance_rules = interop().read_edn('[[(distance-squared ?p1 ?p2 ?d2) '
    '[?p1 :point/x ?x1] '
    '[?p1 :point/y ?y1] '
    '[?p2 :point/x ?x2] '
    '[?p2 :point/y ?y2] '
    '[(- ?x2 ?x1) ?dx] '
    '[(- ?y2 ?y1) ?dy] '
    '[(* ?dx ?dx) ?dx2] '
    '[(* ?dy ?dy) ?dy2] '
    '[(+ ?dx2 ?dy2) ?d2]]]')
```

```javascript
const distanceRules = await interop().readEdn('[[(distance-squared ?p1 ?p2 ?d2) ' +
    '[?p1 :point/x ?x1] ' +
    '[?p1 :point/y ?y1] ' +
    '[?p2 :point/x ?x2] ' +
    '[?p2 :point/y ?y2] ' +
    '[(- ?x2 ?x1) ?dx] ' +
    '[(- ?y2 ?y1) ?dy] ' +
    '[(* ?dx ?dx) ?dx2] ' +
    '[(* ?dy ?dy) ?dy2] ' +
    '[(+ ?dx2 ?dy2) ?d2]]]');
```

</div>

This rule computes the distance between two points. You can use this rule in
different ways:

- **Filter**: compute `?dist2`, then constrain it with a predicate such as
  `[(= ?dist2 100)]`.
- **Calculate**: `(distance-squared ?a ?b ?dist2)` — Find pairs and *bind* the
  squared distance to `?dist2`.

For filtering, let the rule compute the squared distance, then test it:

<div class="multi-lang">

```clojure
(d/q '[:find ?name-a ?name-b
       :in $ %
       :where [?a :point/name ?name-a]
              [?b :point/name ?name-b]
              (distance-squared ?a ?b ?dist2)
              [(= ?dist2 100)]]
     db distance-rules)
```

```java
Object result = conn.query("[:find ?name-a ?name-b " +
    ":in $ % " +
    ":where [?a :point/name ?name-a] " +
    "       [?b :point/name ?name-b] " +
    "       (distance-squared ?a ?b ?dist2) " +
    "       [(= ?dist2 100)]]",
    distanceRules);
```

```python
result = conn.query('[:find ?name-a ?name-b '
    ':in $ % '
    ':where [?a :point/name ?name-a] '
    '       [?b :point/name ?name-b] '
    '       (distance-squared ?a ?b ?dist2) '
    '       [(= ?dist2 100)]]',
    distance_rules)
```

```javascript
const result = await conn.query('[:find ?name-a ?name-b ' +
    ':in $ % ' +
    ':where [?a :point/name ?name-a] ' +
    '       [?b :point/name ?name-b] ' +
    '       (distance-squared ?a ?b ?dist2) ' +
    '       [(= ?dist2 100)]]',
    distanceRules);
```

</div>

For calculation, leave the distance variable unbound and include it in
`:find`:

<div class="multi-lang">

```clojure
(d/q '[:find ?name ?dist2
       :in $ %
       :where [?origin :point/name "origin"]
              [?p :point/name ?name]
              (distance-squared ?origin ?p ?dist2)]
     db distance-rules)
```

```java
Object result = conn.query("[:find ?name ?dist2 " +
    ":in $ % " +
    ":where [?origin :point/name \"origin\"] " +
    "       [?p :point/name ?name] " +
    "       (distance-squared ?origin ?p ?dist2)]",
    distanceRules);
```

```python
result = conn.query('[:find ?name ?dist2 '
    ':in $ % '
    ':where [?origin :point/name "origin"] '
    '       [?p :point/name ?name] '
    '       (distance-squared ?origin ?p ?dist2)]',
    distance_rules)
```

```javascript
const result = await conn.query('[:find ?name ?dist2 ' +
    ':in $ % ' +
    ':where [?origin :point/name "origin"] ' +
    '       [?p :point/name ?name] ' +
    '       (distance-squared ?origin ?p ?dist2)]',
    distanceRules);
```

</div>

The first query uses the computed third column as a filter. The second query
returns it as data. The rule body is the same in both cases.


## 6. Recursion: Navigating Hierarchies

Recursion is the "superpower" of rules. It allows you to query data structures
with arbitrary depth, which is impossible with standard SQL joins without
knowing the depth in advance.

A recursive rule follows the standard recursive pattern:

1. **Base Case**: The simplest version of the relationship.
2. **Recursive Case**: A definition that calls itself.

### Example: Organizational Hierarchy

Suppose you have an `:employee/manager` attribute that points to another
employee. To find everyone who reports to a manager—either directly or
indirectly—you can use a recursive `reports-to` rule.

<div class="multi-lang">

```clojure
(def org-rules
  '[[(reports-to ?sub ?boss)
     [?sub :employee/manager ?boss]] ; Base case: direct report

    [(reports-to ?sub ?boss)
     [?sub :employee/manager ?intermediate] ; Recursive case
     (reports-to ?intermediate ?boss)]])    ; Call itself

;; Finding all subordinates of "Alice"
(d/q '[:find ?name
       :in $ % ?boss-name
       :where [?boss :employee/name ?boss-name]
              (reports-to ?sub ?boss)
              [?sub :employee/name ?name]]
     db org-rules "Alice")
```

```java
Object orgRules = Datalevin.edn("[[(reports-to ?sub ?boss) " +
    "[?sub :employee/manager ?boss]] " +      // Base case: direct report
    "[(reports-to ?sub ?boss) " +
    "[?sub :employee/manager ?intermediate] " + // Recursive case
    "(reports-to ?intermediate ?boss)]]");      // Call itself

// Finding all subordinates of "Alice"
Object result = conn.query("[:find ?name " +
    ":in $ % ?boss-name " +
    ":where [?boss :employee/name ?boss-name] " +
    "       (reports-to ?sub ?boss) " +
    "       [?sub :employee/name ?name]]",
    orgRules, "Alice");
```

```python
org_rules = interop().read_edn('[[(reports-to ?sub ?boss) '
    '[?sub :employee/manager ?boss]] '       # Base case: direct report
    '[(reports-to ?sub ?boss) '
    '[?sub :employee/manager ?intermediate] ' # Recursive case
    '(reports-to ?intermediate ?boss)]]')     # Call itself

# Finding all subordinates of "Alice"
result = conn.query('[:find ?name '
    ':in $ % ?boss-name '
    ':where [?boss :employee/name ?boss-name] '
    '       (reports-to ?sub ?boss) '
    '       [?sub :employee/name ?name]]',
    org_rules, 'Alice')
```

```javascript
const orgRules = await interop().readEdn('[[(reports-to ?sub ?boss) ' +
    '[?sub :employee/manager ?boss]] ' +       // Base case: direct report
    '[(reports-to ?sub ?boss) ' +
    '[?sub :employee/manager ?intermediate] ' + // Recursive case
    '(reports-to ?intermediate ?boss)]]');      // Call itself

// Finding all subordinates of "Alice"
const result = await conn.query('[:find ?name ' +
    ':in $ % ?boss-name ' +
    ':where [?boss :employee/name ?boss-name] ' +
    '       (reports-to ?sub ?boss) ' +
    '       [?sub :employee/name ?name]]',
    orgRules, 'Alice');
```

</div>

With a small hierarchy:

<div class="multi-lang">

```clojure
(d/transact! conn
  [{:db/id -1 :employee/name "Alice"}
   {:db/id -2 :employee/name "Bob"  :employee/manager -1}
   {:db/id -3 :employee/name "Cara" :employee/manager -2}
   {:db/id -4 :employee/name "Dee"  :employee/manager -1}
   {:db/id -5 :employee/name "Eli"  :employee/manager -3}])

(d/q '[:find ?name
       :in $ % ?boss-name
       :where [?boss :employee/name ?boss-name]
              (reports-to ?sub ?boss)
              [?sub :employee/name ?name]]
     db org-rules "Alice")
;; => #{["Bob"] ["Cara"] ["Dee"] ["Eli"]}
```

```java
conn.transact(Datalevin.tx()
    .entity(Tx.entity()
        .put("db/id", -1L)
        .put("employee/name", "Alice"))
    .entity(Tx.entity()
        .put("db/id", -2L)
        .put("employee/name", "Bob")
        .put("employee/manager", -1L))
    .entity(Tx.entity()
        .put("db/id", -3L)
        .put("employee/name", "Cara")
        .put("employee/manager", -2L))
    .entity(Tx.entity()
        .put("db/id", -4L)
        .put("employee/name", "Dee")
        .put("employee/manager", -1L))
    .entity(Tx.entity()
        .put("db/id", -5L)
        .put("employee/name", "Eli")
        .put("employee/manager", -3L)));

Object result = conn.query("[:find ?name " +
    ":in $ % ?boss-name " +
    ":where [?boss :employee/name ?boss-name] " +
    "       (reports-to ?sub ?boss) " +
    "       [?sub :employee/name ?name]]",
    orgRules, "Alice");
```

```python
conn.transact([
    {":db/id": -1, ":employee/name": "Alice"},
    {":db/id": -2, ":employee/name": "Bob",  ":employee/manager": -1},
    {":db/id": -3, ":employee/name": "Cara", ":employee/manager": -2},
    {":db/id": -4, ":employee/name": "Dee",  ":employee/manager": -1},
    {":db/id": -5, ":employee/name": "Eli",  ":employee/manager": -3}])

result = conn.query('[:find ?name '
    ':in $ % ?boss-name '
    ':where [?boss :employee/name ?boss-name] '
    '       (reports-to ?sub ?boss) '
    '       [?sub :employee/name ?name]]',
    org_rules, 'Alice')
```

```javascript
await conn.transact([
  { ":db/id": -1, ":employee/name": "Alice" },
  { ":db/id": -2, ":employee/name": "Bob",  ":employee/manager": -1 },
  { ":db/id": -3, ":employee/name": "Cara", ":employee/manager": -2 },
  { ":db/id": -4, ":employee/name": "Dee",  ":employee/manager": -1 },
  { ":db/id": -5, ":employee/name": "Eli",  ":employee/manager": -3 }
]);

const result = await conn.query('[:find ?name ' +
    ':in $ % ?boss-name ' +
    ':where [?boss :employee/name ?boss-name] ' +
    '       (reports-to ?sub ?boss) ' +
    '       [?sub :employee/name ?name]]',
    orgRules, 'Alice');
```

</div>

Here is the same example worked through as Datalog evaluation. Figure 9.1 shows
the steps visually.

![Recursive rule fixpoint: from the employee/manager facts, round 1 derives the direct reports (bob→alice, cara→bob, dee→alice, eli→cara), round 2 adds the indirect reports cara→alice and eli→bob, round 3 adds eli→alice, and round 4 produces no new tuples, reaching a fixpoint; the reports-to relation is the union of all rounds](/images/diagrams/recursive-fixpoint.svg)

The stored facts are the **EDB** (extensional database): facts that are directly
present in Datalevin. Written as `[e a v]` datoms with names instead of entity
ids, the `:employee/manager` facts are:

```text pdf-keep
[bob  :employee/manager alice]
[cara :employee/manager bob]
[dee  :employee/manager alice]
[eli  :employee/manager cara]
```

The rule predicate `reports-to` is the **IDB** (intensional database): tuples
derived by applying rules to EDB facts and to previously derived IDB tuples.
Before evaluation starts, there are no derived `reports-to` tuples:
`IDB round 0 = {}`.

Round 1 applies the base rule:

```clojure
[(reports-to ?sub ?boss)
 [?sub :employee/manager ?boss]]
```

Every direct manager fact becomes a direct `reports-to` tuple:

```text pdf-keep
(reports-to bob  alice)
(reports-to cara bob)
(reports-to dee  alice)
(reports-to eli  cara)
```

Round 2 applies the recursive rule using the tuples discovered in round 1:

```clojure
[(reports-to ?sub ?boss)
 [?sub :employee/manager ?intermediate]
 (reports-to ?intermediate ?boss)]
```

This produces the next layer of indirect reports:

```text pdf-keep
(reports-to cara alice)  ; [cara :employee/manager bob] + (reports-to bob alice)
(reports-to eli  bob)    ; [eli :employee/manager cara] + (reports-to cara bob)
```

Round 3 repeats the recursive step with the new round-2 tuples:

```text pdf-keep
(reports-to eli alice)   ; [eli :employee/manager cara] + (reports-to cara alice)
```

Round 4 produces no new tuples, so evaluation has reached a **fixpoint**. The
complete derived `reports-to` relation is the union of all IDB rounds:

```text pdf-keep
(reports-to bob  alice)
(reports-to cara bob)
(reports-to dee  alice)
(reports-to eli  cara)
(reports-to cara alice)
(reports-to eli  bob)
(reports-to eli  alice)
```

The query asks only for tuples whose boss is Alice, so it returns Bob, Cara,
Dee, and Eli. Chapter 21 explains how Datalevin evaluates recursive rules
efficiently; the step-by-step illustration here is the conceptual model.

Datalevin's Datalog engine handles the recursive expansion and ensures that the
query terminates (preventing infinite loops even if your data has cycles).
Termination is not the same as data validity: if your domain requires a strict
tree, your application or transaction functions should still prevent illegal
management cycles.


## 7. Rules as an Abstraction Layer

Beyond their technical capabilities, rules serve as a powerful **abstraction
layer** for your domain logic.

Imagine a complex "Product Pricing" engine where the final price depends on user
discounts, seasonal sales, and bulk quantities. Instead of putting this logic in
your application code (which would require multiple round-trips to the database)
or in every single query, you can encode it as a rule set.

Here is a simplified rule set. It first derives candidate prices: the base
price, plus one candidate for each applicable discount. The final
`calculated-price` rule keeps only a candidate for which no better candidate
exists.

<!-- pdf-listing: Rule-based product pricing abstraction -->

<div class="multi-lang">

```clojure
(def pricing-rules
  '[[(price-candidate ?user ?product ?price)
     [?product :product/base-price ?price]]

    [(discount-rate ?user ?product ?rate)
     [?user :user/tier :gold]
     [(ground 0.20) ?rate]]

    [(discount-rate ?user ?product ?rate)
     [?product :product/category :clearance]
     [(ground 0.15) ?rate]]

    [(discount-rate ?user ?product ?rate)
     [?user :user/bulk-eligible? true]
     [?product :product/bulk? true]
     [(ground 0.10) ?rate]]

    [(price-candidate ?user ?product ?price)
     [?product :product/base-price ?base]
     (discount-rate ?user ?product ?rate)
     [(- 1.0 ?rate) ?multiplier]
     [(* ?base ?multiplier) ?price]]

    [(calculated-price ?user ?product ?price)
     (price-candidate ?user ?product ?price)
     (not-join [?user ?product ?price]
       (price-candidate ?user ?product ?better)
       [(< ?better ?price)])]])
```

```java
Object pricingRules = Datalevin.edn("["
    + "[(price-candidate ?user ?product ?price) "
    + "[?product :product/base-price ?price]] "
    + "[(discount-rate ?user ?product ?rate) "
    + "[?user :user/tier :gold] "
    + "[(ground 0.20) ?rate]] "
    + "[(discount-rate ?user ?product ?rate) "
    + "[?product :product/category :clearance] "
    + "[(ground 0.15) ?rate]] "
    + "[(discount-rate ?user ?product ?rate) "
    + "[?user :user/bulk-eligible? true] "
    + "[?product :product/bulk? true] "
    + "[(ground 0.10) ?rate]] "
    + "[(price-candidate ?user ?product ?price) "
    + "[?product :product/base-price ?base] "
    + "(discount-rate ?user ?product ?rate) "
    + "[(- 1.0 ?rate) ?multiplier] "
    + "[(* ?base ?multiplier) ?price]] "
    + "[(calculated-price ?user ?product ?price) "
    + "(price-candidate ?user ?product ?price) "
    + "(not-join [?user ?product ?price] "
    + "  (price-candidate ?user ?product ?better) "
    + "  [(< ?better ?price)])]]");
```

```python
pricing_rules = interop().read_edn('['
    '[(price-candidate ?user ?product ?price) '
    '[?product :product/base-price ?price]] '
    '[(discount-rate ?user ?product ?rate) '
    '[?user :user/tier :gold] '
    '[(ground 0.20) ?rate]] '
    '[(discount-rate ?user ?product ?rate) '
    '[?product :product/category :clearance] '
    '[(ground 0.15) ?rate]] '
    '[(discount-rate ?user ?product ?rate) '
    '[?user :user/bulk-eligible? true] '
    '[?product :product/bulk? true] '
    '[(ground 0.10) ?rate]] '
    '[(price-candidate ?user ?product ?price) '
    '[?product :product/base-price ?base] '
    '(discount-rate ?user ?product ?rate) '
    '[(- 1.0 ?rate) ?multiplier] '
    '[(* ?base ?multiplier) ?price]] '
    '[(calculated-price ?user ?product ?price) '
    '(price-candidate ?user ?product ?price) '
    '(not-join [?user ?product ?price] '
    '  (price-candidate ?user ?product ?better) '
    '  [(< ?better ?price)])]]')
```

```javascript
const pricingRules = await interop().readEdn('['
    + '[(price-candidate ?user ?product ?price) '
    + '[?product :product/base-price ?price]] '
    + '[(discount-rate ?user ?product ?rate) '
    + '[?user :user/tier :gold] '
    + '[(ground 0.20) ?rate]] '
    + '[(discount-rate ?user ?product ?rate) '
    + '[?product :product/category :clearance] '
    + '[(ground 0.15) ?rate]] '
    + '[(discount-rate ?user ?product ?rate) '
    + '[?user :user/bulk-eligible? true] '
    + '[?product :product/bulk? true] '
    + '[(ground 0.10) ?rate]] '
    + '[(price-candidate ?user ?product ?price) '
    + '[?product :product/base-price ?base] '
    + '(discount-rate ?user ?product ?rate) '
    + '[(- 1.0 ?rate) ?multiplier] '
    + '[(* ?base ?multiplier) ?price]] '
    + '[(calculated-price ?user ?product ?price) '
    + '(price-candidate ?user ?product ?price) '
    + '(not-join [?user ?product ?price] '
    + '  (price-candidate ?user ?product ?better) '
    + '  [(< ?better ?price)])]]');
```

</div>

This example uses floating-point prices to keep the arithmetic readable. In a
production money model, prefer integer cents or a decimal representation and
write the rule arithmetic accordingly.

Now the application query stays focused on intent:

<div class="multi-lang">

```clojure
(d/q '[:find ?product-id ?final-price
       :in $ % ?user-id
       :where [?user :user/id ?user-id]
              [?product :product/id ?product-id]
              (calculated-price ?user ?product ?final-price)]
     db pricing-rules "u-123")
```

```java
Object result = conn.query("[:find ?product-id ?final-price " +
    ":in $ % ?user-id " +
    ":where [?user :user/id ?user-id] " +
    "       [?product :product/id ?product-id] " +
    "       (calculated-price ?user ?product ?final-price)]",
    pricingRules, "u-123");
```

```python
result = conn.query('[:find ?product-id ?final-price '
    ':in $ % ?user-id '
    ':where [?user :user/id ?user-id] '
    '       [?product :product/id ?product-id] '
    '       (calculated-price ?user ?product ?final-price)]',
    pricing_rules, 'u-123')
```

```javascript
const result = await conn.query('[:find ?product-id ?final-price ' +
    ':in $ % ?user-id ' +
    ':where [?user :user/id ?user-id] ' +
    '       [?product :product/id ?product-id] ' +
    '       (calculated-price ?user ?product ?final-price)]',
    pricingRules, 'u-123');
```

</div>

The query does not need to know whether the price came from a gold-tier
discount, a clearance discount, a bulk discount, or no discount at all. If the
business adds a new discount rule, you add another `discount-rate` definition
and keep the query shape stable.

By moving this logic into rules, you gain:

1. **Consistency**: The "price" is calculated the same way across the entire
   system.
2. **Performance**: The calculation happens inside the database engine, close to
   the data.
3. **Simplicity**: Your application code stays focused on the *intent* of the
   query, not the mechanics of the calculation.


## Summary

Rules transform Datalog from a simple pattern-matching language into a
sophisticated tool for knowledge derivation.

- **Encapsulation**: Wrap complex `:where` clauses in a named rule.
- **Reusability**: Define logic once and use it across many queries.
- **Named alternatives**: Express branching domain logic once instead of
  repeating `or` blocks across queries.
- **Parameters and bindings**: Use rules as relations whose arguments may be
  inputs, outputs, or both.
- **Recursion**: Traverse graphs and hierarchies of any depth with ease.

By mastering rules, you can build expressive and maintainable data models that
reflect the true complexity of your domain logic. For a deep dive into using
these patterns for graph data, see **Chapter 13: Graph Modeling**.

## References

[1] Stuart Russell and Peter Norvig, *Artificial Intelligence: A Modern
Approach*, 4th US ed., Pearson, 2020. URL: <https://aima.cs.berkeley.edu/>.

[2] Charles L. Forgy, "Rete: A Fast Algorithm for the Many Pattern/Many Object
Pattern Match Problem," *Artificial Intelligence* 19(1):17-37, 1982. DOI:
<https://doi.org/10.1016/0004-3702(82)90020-0>.

[3] Peter Jackson, *Introduction to Expert Systems*, 3rd ed., Addison-Wesley,
1998.

[4] Michael Kifer, Georg Lausen, and James Wu, *OpenRuleBench: An Analysis of
the Performance of Rule Engines*, 2009. URL:
<https://www3.cs.stonybrook.edu/~kifer/TechReports/OpenRuleBench09.pdf>.

[5] Datalevin project, "OpenRuleBench," benchmark implementation. URL:
<https://github.com/datalevin/datalevin/tree/master/benchmarks/openrulebench>.
