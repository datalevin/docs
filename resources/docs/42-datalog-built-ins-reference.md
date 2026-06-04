---
title: "Datalog Built-In Functions Reference"
chapter: 42
part: "IX — Appendices"
---

# Appendix C: Datalog Built-In Functions Reference

This appendix lists the functions and predicates that Datalevin resolves
without namespace qualification inside Datalog query clauses. It also lists the
built-in aggregate functions available in `:find` and `:having`.

Most of these functions mirror `clojure.core`. Datalevin-specific functions are
called out separately because they interact with the database, full-text search,
vector indexes, idoc indexes, or runtime UDFs.

---

## 1. Where Built-Ins Can Appear

Built-ins can be used in predicate clauses:

```clojure
[(> ?age 18)]
[(like ?name "A%")]
```

They can also be used in binding clauses:

```clojure
[(+ ?subtotal ?tax) ?total]
[(get-some $ ?e :profile/email :user/email) [?attr ?email]]
```

Aggregate functions appear in `:find` and `:having`:

```clojure
[:find ?city (count ?e)
 :where [?e :user/city ?city]
 :having [(> (count ?e) 10)]]
```

Fully qualified Clojure functions can also be used when they are available to
the runtime, but they are not part of this built-in table.

---

## 2. Predicates and Comparisons

| Function | Purpose |
| :--- | :--- |
| `=` | Equality predicate. |
| `==` | Numeric equality predicate. |
| `not=` | Inequality predicate. |
| `!=` | Alias for `not=`. |
| `<` | Datalevin typed less-than comparison. |
| `<=` | Datalevin typed less-than-or-equal comparison. |
| `>` | Datalevin typed greater-than comparison. |
| `>=` | Datalevin typed greater-than-or-equal comparison. |
| `compare` | Clojure-style comparison function. |
| `-differ?` | Internal-style helper used to test whether paired argument groups differ. |
| `true?` | True only for the boolean value `true`. |
| `false?` | True only for the boolean value `false`. |
| `nil?` | True for `nil`. |
| `some?` | True for non-`nil` values. |
| `not` | Logical negation. |
| `and` | Logical conjunction over arguments. |
| `or` | Logical disjunction over arguments. |
| `complement` | Return a function that negates another predicate. |
| `identical?` | Reference identity predicate. |
| `zero?` | Numeric zero predicate. |
| `pos?` | Positive-number predicate. |
| `neg?` | Negative-number predicate. |
| `even?` | Even integer predicate. |
| `odd?` | Odd integer predicate. |
| `in` | Membership predicate, e.g. `[(in ?status [:open :ready])]`. |
| `not-in` | Negated membership predicate. |
| `like` | SQL `LIKE`-style string predicate using `%` and `_` wildcards. |
| `not-like` | Negated `like` predicate. |
| `re-find` | Regex search. |
| `re-matches` | Regex full-match test. |

---

## 3. Numeric Functions

| Function | Purpose |
| :--- | :--- |
| `+` | Addition. |
| `-` | Subtraction or numeric negation. |
| `*` | Multiplication. |
| `/` | Division. |
| `quot` | Integer quotient. |
| `rem` | Remainder. |
| `mod` | Modulus. |
| `inc` | Increment. |
| `dec` | Decrement. |
| `min` | Minimum value using Datalevin typed comparison. |
| `max` | Maximum value using Datalevin typed comparison. |
| `rand` | Random floating-point number. |
| `rand-int` | Random integer below a bound. |

---

## 4. General Value and Collection Functions

| Function | Purpose |
| :--- | :--- |
| `identity` | Return its argument unchanged. |
| `ground` | Alias for `identity`; commonly used to bind constants or collections. |
| `quote` | Return its argument unchanged; useful for nested query literals in EDN strings. |
| `keyword` | Create a keyword. |
| `meta` | Return metadata. |
| `name` | Return the name part of a keyword or symbol. |
| `namespace` | Return the namespace part of a keyword or symbol. |
| `type` | Return the Java/Clojure runtime type. |
| `vector` | Construct a vector. |
| `list` | Construct a list. |
| `set` | Construct a set. |
| `hash-map` | Construct a hash map. |
| `array-map` | Construct an array map. |
| `count` | Count items in a collection or characters in a string. |
| `range` | Produce a numeric range. |
| `not-empty` | Return a collection if non-empty, otherwise `nil`. |
| `empty?` | True for empty collections. |
| `contains?` | True when a key/index is present. |
| `get` | Look up a value in a collection or map. |
| `apply` | Apply a built-in or fully qualified function to a sequence of arguments. |
| `tuple` | Construct a tuple value; equivalent to `vector` in query context. |
| `untuple` | Return a tuple value unchanged so it can be destructured by a binding. |

Examples:

```clojure
;; Bind each vowel from a literal collection.
(d/q '[:find ?vowel
       :where [(ground [:a :e :i :o :u]) [?vowel ...]]])

;; Build transaction data in a query.
(d/q '[:find [?tx-data ...]
       :where [(ground :db/add) ?op]
              [(vector ?op -1 :user/name "Alice") ?tx-data]])

;; Sum a tuple stored as a value.
(d/q '[:find ?e ?sum
       :where [?e :nums ?nums]
              [(apply + ?nums) ?sum]]
     db)
```

---

## 5. String and Regex Functions

| Function | Purpose |
| :--- | :--- |
| `str` | Concatenate values as strings. |
| `pr-str` | Print values readably to a string. |
| `print-str` | Print values to a string using `print` semantics. |
| `println-str` | Print values with a trailing newline. |
| `prn-str` | Print values readably with a trailing newline. |
| `subs` | Return a substring. |
| `re-pattern` | Compile a regex pattern. |
| `re-find` | Find a regex match. |
| `re-matches` | Match an entire string against a regex. |
| `re-seq` | Return a sequence of regex matches. |

---

## 6. Database-Aware Functions

These functions take a database value, usually `$`, or interact with Datalevin
indexes and runtime facilities.

| Function | Purpose |
| :--- | :--- |
| `get-else` | Return an entity attribute value or a non-`nil` default. |
| `get-some` | Return the first present attribute and value from a list of attributes. |
| `missing?` | True when an entity has no value for an attribute. |
| `q` | Run a nested Datalog query. Prefer joins, `not-join`, or rules when possible. |
| `fulltext` | Search full-text indexes and bind matching `[e a v]` tuples. |
| `vec-neighbors` | Search vector indexes with a vector query and bind matching tuples. |
| `embedding-neighbors` | Embed a text query and search embedding indexes. |
| `idoc-match` | Search indexed document attributes and bind matching tuples. |
| `idoc-get` | Extract a nested value from a bound idoc document by path. |
| `udf` | Invoke a registered runtime UDF descriptor in query context. |

### 6.1 Attribute Fallbacks

```clojure
;; Bind either the user's height or a default.
(d/q '[:find ?e ?height
       :where [?e :user/name]
              [(get-else $ ?e :user/height 0) ?height]]
     db)

;; Bind the first present contact attribute.
(d/q '[:find ?e ?attr ?value
       :where [?e :user/name]
              [(get-some $ ?e :user/email :user/phone) [?attr ?value]]]
     db)

;; Filter entities that do not have an attribute.
(d/q '[:find ?e
       :where [?e :user/name]
              [(missing? $ ?e :user/deleted-at)]]
     db)
```

`get-else` does not accept `nil` as its default value. Use a concrete sentinel
or handle the absence with `missing?`.

### 6.2 Nested Queries

```clojure
(d/q '[:find ?e ?age
       :where [(q (quote [:find (min ?age)
                          :where [_ :user/age ?age]])
                 $) [[?age]]]
              [?e :user/age ?age]]
     db)
```

Nested `q` is available, but it can be expensive when executed per candidate
tuple. Prefer expressing the same logic directly with joins, `not-join`, rules,
or aggregates when possible.

### 6.3 Full-Text Search

```clojure
;; Search all full-text domains.
(d/q '[:find ?e ?a ?v
       :in $ ?query
       :where [(fulltext $ ?query) [[?e ?a ?v]]]]
     db "red fox")

;; Search one attribute-specific domain.
(d/q '[:find ?e ?a ?v
       :in $ ?query
       :where [(fulltext $ :article/body ?query) [[?e ?a ?v]]]]
     db "red fox")
```

`fulltext` returns `[e a v]` by default. With `{:display :refs+scores}`, bind
`[e a v score]`; with `:texts`, `:offsets`, or `:texts+offsets`, bind the
corresponding extra values. Attribute-specific search requires
`:db.fulltext/autoDomain true` on the attribute.

### 6.4 Vector and Embedding Search

```clojure
;; Vector query against an attribute-specific vector domain.
(d/q '[:find ?e ?a ?v
       :in $ ?query-vector
       :where [(vec-neighbors $ :item/embedding ?query-vector)
               [[?e ?a ?v]]]]
     db query-vector)

;; Text query against an embedding domain.
(d/q '[:find ?e ?a ?v
       :in $ ?text
       :where [(embedding-neighbors $ :article/body ?text)
               [[?e ?a ?v]]]]
     db "database internals")
```

With `{:display :refs+dists}`, vector and embedding searches can bind
`[e a v distance]`. Attribute-specific embedding search requires
`:db.embedding/autoDomain true` on the attribute.

### 6.5 Indexed Documents

```clojure
;; Search idoc documents by query map.
(d/q '[:find ?e ?a ?doc
       :where [(idoc-match $ {:status "active"}) [[?e ?a ?doc]]]]
     db)

;; Extract a nested field from the matched document.
(d/q '[:find ?e ?email
       :where [(idoc-match $ :person/profile {:status "active"})
               [[?e ?a ?doc]]]
              [(idoc-get ?doc :contact :email) ?email]]
     db)
```

Attribute-specific `idoc-match` requires an attribute with
`:db/valueType :db.type/idoc`.

### 6.6 Runtime UDFs

```clojure
(d/q '[:find ?normalized
       :in $ ?email
       :where [(udf :normalize-email ?email) ?normalized]]
     db
     "ALICE@EXAMPLE.COM")
```

The descriptor must be installed in the database or available from the runtime
UDF registry, and its kind must be usable as a query function or predicate.

---

## 7. Aggregate Functions

Aggregate functions are used in `:find`, aggregate expressions, and `:having`.

| Function | Forms | Purpose |
| :--- | :--- | :--- |
| `sum` | `(sum ?x)` | Sum numeric values. |
| `avg` | `(avg ?x)` | Arithmetic mean. |
| `median` | `(median ?x)` | Median value. |
| `variance` | `(variance ?x)` | Population variance. |
| `stddev` | `(stddev ?x)` | Population standard deviation. |
| `count` | `(count ?x)` | Count values. |
| `count-distinct` | `(count-distinct ?x)` | Count distinct values. |
| `distinct` | `(distinct ?x)` | Return a set of distinct values. |
| `min` | `(min ?x)`, `(min n ?x)` | Smallest value, or vector of the `n` smallest values. |
| `max` | `(max ?x)`, `(max n ?x)` | Largest value, or vector of the `n` largest values. |
| `rand` | `(rand ?x)`, `(rand n ?x)` | Random value, or vector of `n` random draws. |
| `sample` | `(sample n ?x)` | Vector of up to `n` sampled values. |

Aggregate expressions can combine aggregate results:

```clojure
(d/q '[:find ?name (+ (sum ?debits) (sum ?credits))
       :in [[?name ?debits ?credits]]]
     rows)
```

Custom aggregate functions are also supported with the special aggregate form:

```clojure
(d/q '[:find ?group (aggregate ?agg ?x)
       :in [[?group ?x]] ?agg]
     rows
     my-aggregate-fn)
```

---

## 8. Complete Name Index

Query functions and predicates:

`!=`, `*`, `+`, `-`, `-differ?`, `/`, `<`, `<=`, `=`, `==`, `>`, `>=`, `and`,
`apply`, `array-map`, `compare`, `complement`, `contains?`, `count`, `dec`,
`embedding-neighbors`, `empty?`, `even?`, `false?`, `fulltext`, `get`,
`get-else`, `get-some`, `ground`, `hash-map`, `identical?`, `identity`,
`idoc-get`, `idoc-match`, `in`, `inc`, `keyword`, `like`, `list`, `max`, `meta`,
`min`, `missing?`, `mod`, `name`, `namespace`, `neg?`, `nil?`, `not`,
`not-empty`, `not-in`, `not-like`, `not=`, `odd?`, `or`, `pos?`, `pr-str`,
`print-str`, `println-str`, `prn-str`, `q`, `quot`, `quote`, `rand`, `rand-int`,
`range`, `re-find`, `re-matches`, `re-pattern`, `re-seq`, `rem`, `set`,
`some?`, `str`, `subs`, `true?`, `tuple`, `type`, `udf`, `untuple`,
`vec-neighbors`, `vector`, `zero?`.

Aggregate functions:

`avg`, `count`, `count-distinct`, `distinct`, `max`, `median`, `min`, `rand`,
`sample`, `stddev`, `sum`, `variance`.
