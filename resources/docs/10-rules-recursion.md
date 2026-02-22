---
title: "Rules, Recursion, and Derived Knowledge"
chapter: 10
part: "II — Core APIs: From KV to Datalog"
---

# Chapter 10: Rules, Recursion, and Derived Knowledge

While simple Datalog queries are powerful for finding patterns, real-world applications often require complex, reusable logic. You might find yourself repeating the same set of `:where` clauses across many queries, or needing to traverse hierarchical data of unknown depth (like a file system or an organizational chart).

This is where **Rules** come in. Rules allow you to define named logic that can be reused, composed, and even called recursively. They are the "functions" or "views" of the Datalog world, enabling you to derive new knowledge from your raw facts.

---

## 1. What are Rules?

At its simplest, a rule is a named collection of Datalog clauses. You define a rule once and then invoke it by name within your queries.

### The Anatomy of a Rule
A rule consists of a **Head** and a **Body**:
- **Head**: The rule name and its parameters, e.g., `(is-manager? ?e)`.
- **Body**: One or more Datalog clauses that must be true for the rule to match.

Rules are typically defined as a vector of vectors (a "rule set"):

```clojure
(def user-rules
  '[[(is-active? ?e)
     [?e :user/status :active]
     [?e :user/verified? true]]
     
    [(is-admin? ?e)
     (is-active? ?e) ; Rules can call other rules!
     [?e :user/role :admin]]])
```

> **Key Takeaway**: Rules are your primary tool for **abstraction** and **composition**. Use them to name common patterns and build more complex logic out of simpler, reusable components.

---

## 2. Using Rules in Queries

To use rules in a query, you must:
1. Include the `%` symbol in the `:in` clause to represent the rule set.
2. Pass the rule set as an argument to `d/q` (usually after the database).
3. Invoke the rule in the `:where` clause using its head syntax.

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

In this example, `(is-active? ?e)` acts as a filter. For every entity `?e` that has a name, the engine checks if that entity also satisfies the clauses defined in the `is-active?` rule.

---

## 3. Logical OR: Multiple Rule Definitions

In standard Datalog, all clauses in a `:where` block are joined by an implicit `AND`. Rules provide a clean way to express logical `OR`.

If you define a rule with the same name multiple times, the engine treats these definitions as alternatives. If *any* of the definitions match, the rule is satisfied.

```clojure
(def access-rules
  '[[(has-access? ?user ?resource)
     [?user :user/role :admin]] ; Admins have access to everything
     
    [(has-access? ?user ?resource)
     [?user :user/permissions ?resource]] ; Specific permission
     
    [(has-access? ?user ?resource)
     [?user :user/team ?team]
     [?team :team/permissions ?resource]]]) ; Team-based permission
```

When you query `(has-access? ?u ?r)`, Datalevin will return results if the user is an admin, OR has a direct permission, OR has a team permission. This is much cleaner and more maintainable than using a complex `(or ...)` block in every query.

---

## 4. Datalevin as a Reasoner: Forward-Chaining Logic

Because Datalevin's rule engine evaluates rules from the bottom up (see Chapter 25), it can act as a **forward-chaining reasoner**. In this mode, the database doesn't just store data; it "reasons" about it to infer new classifications or higher-level facts that were never explicitly transacted.

### Example: Automated Classification (Expert Systems)
Imagine an e-commerce system that needs to determine if a user qualifies for "VIP Status" based on a complex set of evolving business rules. Instead of hard-coding these rules in your application, you can define them as a reasoning set.

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
    [(vip-status ?u :gold)
     (is-high-spender? ?u)
     (is-frequent-shopper? ?u)]

    [(vip-status ?u :silver)
     (or (is-high-spender? ?u)
         (is-frequent-shopper? ?u))]])
```

By querying `(vip-status ?u ?tier)`, you are treating Datalevin as an **Expert System**. The database takes the base facts (amount spent, order count) and "chains" them forward through your rules to derive the `vip-status`. 

This approach is far superior to standard database views because:
1. **Composability**: You can build complex reasoning chains out of simple, testable rules.
2. **Performance**: Reasoning happens inside the engine, utilizing indexes and the optimized semi-naive evaluator.
3. **Consistency**: The "logic" of what defines a VIP is centralized in the database schema context, not scattered across microservices.

---

## 5. Recursion: Navigating Hierarchies

Recursion is the "superpower" of rules. It allows you to query data structures with arbitrary depth, which is impossible with standard SQL joins without knowing the depth in advance.

A recursive rule follows the standard recursive pattern:
1. **Base Case**: The simplest version of the relationship.
2. **Recursive Case**: A definition that calls itself.

### Example: Organizational Hierarchy
Suppose you have an `:employee/manager` attribute that points to another employee. To find everyone who reports to a manager—either directly or indirectly—you can use a recursive `reports-to` rule.

```clojure
(def org-rules
  '[[(reports-to ?sub ?boss)
     [?sub :employee/manager ?boss]] ; Base case: direct report
     
    [(reports-to ?sub ?boss)
     [?sub :employee/manager ?intermediate] ; Recursive case
     (reports-to ?intermediate ?boss)]])    ; Call itself
```

Now, finding all subordinates of "Alice" is simple:

```clojure
(d/q '[:find ?name
       :in $ % ?boss-name
       :where [?boss :employee/name ?boss-name]
              (reports-to ?sub ?boss)
              [?sub :employee/name ?name]]
     db org-rules "Alice")
```

Datalevin's Datalog engine handles the recursive expansion and ensures that the query terminates (preventing infinite loops even if your data has cycles).

---

## 6. Rule Parameters and Binding

Rules are not limited to single variables. They can take multiple parameters, and those parameters can be either bound (input) or unbound (output).

```clojure
[[(distance ?p1 ?p2 ?d)
  [?p1 :point/x ?x1]
  [?p1 :point/y ?y1]
  [?p2 :point/x ?x2]
  [?p2 :point/y ?y2]
  [(Math/sqrt (+ (Math/pow (- ?x2 ?x1) 2) 
                 (Math/pow (- ?y2 ?y1) 2))) ?d]]]
```

You can use this rule in different ways:
- **Filter**: `(distance ?a ?b 10.0)` — Find pairs exactly 10 units apart.
- **Calculate**: `(distance ?a ?b ?dist)` — Find pairs and *bind* the distance to `?dist`.

---

## 7. Rules as an Abstraction Layer

Beyond their technical capabilities, rules serve as a powerful **abstraction layer** for your domain logic.

Imagine a complex "Product Pricing" engine where the final price depends on user discounts, seasonal sales, and bulk quantities. Instead of putting this logic in your application code (which would require multiple round-trips to the database) or in every single query, you can encode it as a rule set.

This keeps your queries readable:

```clojure
(d/q '[:find ?product ?final-price
       :in $ % ?user
       :where [?product :product/id]
              (calculated-price ?user ?product ?final-price)]
     db pricing-rules user-id)
```

By moving this logic into rules, you gain:
1. **Consistency**: The "price" is calculated the same way across the entire system.
2. **Performance**: The calculation happens inside the database engine, close to the data.
3. **Simplicity**: Your application code stays focused on the *intent* of the query, not the mechanics of the calculation.

---

## Summary

Rules transform Datalog from a simple pattern-matching language into a sophisticated tool for knowledge derivation.

- **Encapsulation**: Wrap complex `:where` clauses in a named rule.
- **Reusability**: Define logic once and use it across many queries.
- **Logical OR**: Use multiple rule definitions for clean branching logic.
- **Recursion**: Traverse graphs and hierarchies of any depth with ease.

By mastering rules, you can build expressive and maintainable data models that reflect the true complexity of your domain logic. For a deep dive into using these patterns for graph data, see **Chapter 13: Graph Modeling and Relationship Design**.
