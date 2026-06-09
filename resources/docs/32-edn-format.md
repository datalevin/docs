---
title: "EDN Format"
chapter: 32
part: "VII — Appendices"
---

# Chapter 32: EDN Format

EDN, short for Extensible Data Notation, is the data notation used throughout
this book. Datalevin is written in Clojure, so its native examples use EDN
directly: schemas are maps, attributes are keywords, transactions are maps or
vectors, Datalog queries are vectors, rules are vectors of lists and vectors,
and many examples use tagged literals such as `#inst` and `#uuid`.

Readers coming from JSON, SQL, Java, Python, or JavaScript do not need to learn
Clojure as a programming language in order to read the examples. EDN is data,
not code. It is closer to JSON with richer scalar types and symbolic names than
to a programming syntax. This appendix gives the EDN subset and Datalevin
conventions needed to read and write the examples in the rest of the book. The
official EDN specification is maintained at the [edn-format
repository](https://github.com/edn-format/edn).

---

## 1. EDN at a Glance

An EDN value is one readable value. It may be a scalar:

```clojure
nil
true
false
42
3.14
"hello"
:user/email
?name
#inst "2026-06-09T12:00:00Z"
#uuid "00000000-0000-0000-0000-000000000001"
```

Or it may be a collection:

```clojure
[:find ?name :where [?e :user/name ?name]]

{:user/email "ada@example.com"
 :user/name  "Ada"}

#{:admin :editor}

[(parent ?child ?parent)
 [?child :person/parent ?parent]]
```

Commas are optional whitespace in EDN. The following maps are equivalent:

```clojure
{:user/name "Ada" :user/age 37}

{:user/name "Ada", :user/age 37}
```

Datalevin examples usually omit commas because aligned whitespace makes larger
schemas and transactions easier to scan.

---

## 2. Common Literal Forms

| EDN form | Meaning | Datalevin use |
| :--- | :--- | :--- |
| `nil` | Absence of a value | Query results, optional arguments, API options. |
| `true`, `false` | Booleans | Schema flags such as `:db/fulltext true`. |
| `42`, `-7` | Integers | Entity ids, counters, ranks, limits. |
| `3.14` | Floating-point number | Scores, measurements, vector coordinates. |
| `"Ada"` | String | Text fields and full-text content. |
| `:user/email` | Keyword | Attribute names, enum values, options. |
| `?name` | Symbol | Datalog variables. |
| `[a b c]` | Vector | Queries, transaction datoms, lookup refs, paths. |
| `(f x y)` | List | Rule heads and predicate/function clauses in queries. |
| `{:a 1}` | Map | Schemas, entity maps, idoc documents, options. |
| `#{:a :b}` | Set | Unordered collections and set-valued data. |
| `#inst "..."` | Instant | Time values. |
| `#uuid "..."` | UUID | Stable external identifiers. |

EDN has no object syntax and no assignment syntax. A map is just a collection of
key-value pairs. A vector is just an ordered collection. A list is just another
ordered collection, though Datalevin often gives lists a special meaning inside
query and rule forms.

---

## 3. Keywords

Keywords are symbolic values that begin with `:`. In Datalevin, they are used
for attribute names, schema properties, enum-like values, index names, options,
and many API flags:

```clojure
:user/email
:db/valueType
:db.type/string
:order.status/paid
:eav
:closed
```

A keyword may be unqualified, such as `:eav`, or qualified with a slash, such as
`:user/email`. The part before the slash is a namespace-like prefix. It does not
have to name a Clojure namespace, file, table, or class. It is simply a naming
convention that keeps related names together and avoids collisions.

Use qualified keywords for application attributes:

```clojure
:account/email
:account/created-at
:invoice/total
:invoice/customer
```

Use unqualified keywords for small local options only when the surrounding API
expects them:

```clojure
[:find ?e :where [?e :user/email]]
[:closed "a" "m"]
```

Do not quote keywords as strings in Clojure examples. These are different
values:

```clojure
:user/email     ;; keyword
"user/email"    ;; string
```

Datalevin's JSON and non-Clojure APIs sometimes encode keywords as strings at
the language boundary, but the logical value is still a keyword.

---

## 4. Symbols and Datalog Variables

Symbols are names without a leading colon:

```clojure
?e
?name
parent
fulltext
```

In Datalog queries, symbols beginning with `?` are variables. Datalevin binds
them by matching clauses:

```clojure
[:find ?name
 :where [?e :user/email "ada@example.com"]
        [?e :user/name ?name]]
```

The same symbol means the same logical variable within a query. In the example
above, both clauses use `?e`, so they must match the same entity.

Symbols without `?` often name rules or functions:

```clojure
[(active? ?e)]
[(> ?age 18)]
[(fulltext $ "notes" "error budget") [[?e ?a ?v]]]
```

In Clojure source code, query forms are commonly quoted:

```clojure
(d/q '[:find ?name
       :where [?e :user/name ?name]]
     db)
```

The leading quote tells Clojure to pass the vector as data instead of trying to
evaluate symbols such as `?name`. In Java, Python, JavaScript, and other client
languages, the same query is commonly passed as an EDN string or constructed
with an interop helper.

---

## 5. Vectors, Lists, Maps, and Sets

Datalevin uses EDN collection forms consistently.

### 5.1 Vectors

Vectors preserve order and are written with square brackets. Datalevin uses
vectors for Datalog queries, datom transactions, lookup refs, idoc paths, tuple
values, and range specifications:

```clojure
[:find ?e :where [?e :user/email]]

[:db/add 42 :user/name "Ada"]

[:user/email "ada@example.com"]

[:profile :address :city]

["acct-42" #inst "2026-06-09T00:00:00Z"]

[:closed 10 20]
```

When a vector appears inside a Datalog query, position matters. In a data pattern
such as `[?e :user/name ?name]`, the positions are entity, attribute, and value.

### 5.2 Lists

Lists are written with parentheses. In Datalevin query and rule syntax, lists
usually represent rule heads, rule calls, or function/predicate calls:

```clojure
(parent ?child ?parent)

[(parent ?child ?parent)
 [?child :person/parent ?parent]]

[(> ?age 18)]
```

Outside quoted data, a Clojure list means a function call. Inside quoted query
or rule data, it is only EDN data until Datalevin interprets it.

### 5.3 Maps

Maps are written with curly braces and contain alternating keys and values.
Datalevin uses maps for schema definitions, entity transactions, option maps,
and idoc documents:

```clojure
{:user/email {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}
 :user/name  {:db/valueType :db.type/string}}

{:user/email "ada@example.com"
 :user/name  "Ada Lovelace"}

{:limit 10
 :offset 20}
```

Map order is not part of the meaning. Examples often align map values vertically
to make related fields easier to compare.

### 5.4 Sets

Sets are written with `#{...}` and represent unordered unique values:

```clojure
#{:admin :editor}
```

A plain `{...}` form is a map, not a set. Use `#{...}` when the value is a
collection of unique elements without associated values.

---

## 6. Tagged Literals

EDN can attach a tag to the next value. Datalevin examples most often use the
built-in `#inst` and `#uuid` tags.

`#inst` represents an instant in time:

```clojure
#inst "2026-06-09T12:30:00Z"
```

`#uuid` represents a UUID:

```clojure
#uuid "00000000-0000-0000-0000-000000000001"
```

These are not strings, even though their textual form contains quoted strings.
The tag tells the EDN reader to produce a time or UUID value. This matters for
typed schema attributes:

```clojure
{:event/id         {:db/valueType :db.type/uuid
                   :db/unique    :db.unique/identity}
 :event/created-at {:db/valueType :db.type/instant}}
```

When using a non-Clojure client, prefer the client library's EDN helper or typed
wrapper for keywords, UUIDs, and instants. If you send raw JSON strings where the
database expects typed values, Datalevin may store strings instead of UUID,
instant, or keyword values.

---

## 7. EDN in Datalevin

The same EDN forms recur across Datalevin's API surface.

### 7.1 Schemas

A schema is a map from attribute keywords to property maps:

```clojure
{:user/email {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}
 :user/age   {:db/valueType :db.type/long}
 :user/tags  {:db/valueType   :db.type/keyword
              :db/cardinality :db.cardinality/many}}
```

The outer keys are application attributes. The inner keys are Datalevin schema
properties. Most schema property values are also keywords.

### 7.2 Entity Map Transactions

Entity transactions are maps whose keys are attributes:

```clojure
{:user/email "ada@example.com"
 :user/name  "Ada"
 :user/tags  #{:admin :researcher}}
```

With a unique identity attribute, the same map shape can insert a new entity or
update the existing entity with that identity.

### 7.3 Datom Transactions

Datom transactions use vectors:

```clojure
[:db/add 42 :user/name "Ada"]
[:db/retract 42 :user/old-email "ada@old.example"]
[:db/retractEntity 42]
```

The vector shape is compact and positional, so it is useful when you need to be
explicit about the entity id and transaction operation.

### 7.4 Lookup Refs

A lookup ref is a two-element vector containing a unique attribute and a value:

```clojure
[:user/email "ada@example.com"]
[:task/id #uuid "00000000-0000-0000-0000-000000000501"]
```

Lookup refs let transactions and queries refer to entities by domain identity
instead of by numeric entity id.

### 7.5 Datalog Queries

A Datalog query is an EDN vector containing clauses and keywords such as
`:find`, `:where`, `:in`, `:keys`, and `:limit`:

```clojure
[:find ?e ?name
 :where [?e :user/email ?email]
        [?e :user/name ?name]
 :in $ ?email]
```

The query is data. Datalevin interprets that data as a logical query.

### 7.6 Rules

Rules are EDN data too. A rule set is a vector of rule definitions:

```clojure
[[(active? ?e)
  [?e :user/status :user.status/active]]

 [(can-login? ?e)
  (active? ?e)
  [?e :user/email]]]
```

Because rule syntax contains lists and symbols, Clojure code usually quotes the
rule set, while non-Clojure clients usually pass it through an EDN reader helper.

### 7.7 Indexed Documents

For `:db.type/idoc`, EDN can be the document format:

```clojure
{:doc/id      "profile-1"
 :doc/content {:profile {:name "Ada"
                         :age  37
                         :tags #{:math :computing}}}}
```

When `:db/idocFormat` is `:edn`, string payloads are read as EDN and must
produce a map. EDN idocs can use keyword keys, string keys, vectors, sets,
numbers, strings, booleans, instants, UUIDs, and nested maps.

### 7.8 Dumps and Configuration Files

Datalevin command-line dump/load workflows can use EDN files. EDN is useful for
this because it preserves Datalevin-specific values such as keywords, UUIDs,
instants, and sets without inventing JSON conventions for each type.

---

## 8. EDN and JSON

EDN and JSON are both textual data formats, but they make different trade-offs.

| Feature | EDN | JSON |
| :--- | :--- | :--- |
| Map keys | Any EDN value, commonly keywords | Strings only |
| Symbolic names | Keywords and symbols | Usually strings |
| Sets | Built in with `#{...}` | Usually arrays by convention |
| Instants and UUIDs | Tagged literals | Usually strings by convention |
| Comments | Semicolon line comments | Not part of JSON |
| Commas | Optional whitespace | Required separators |
| Top-level value | Any single EDN value | Any JSON value |

This is why the book's Clojure examples can express Datalevin data directly:

```clojure
{:task/id       #uuid "00000000-0000-0000-0000-000000000501"
 :task/status   :task.status/running
 :task/tags     #{:agent :memory}
 :task/started  #inst "2026-06-09T12:00:00Z"}
```

The equivalent JSON representation needs conventions for keyword, set, UUID, and
instant values. Datalevin's non-Clojure clients provide helpers for those cases.

---

## 9. Reading and Printing EDN Safely

In Clojure, use `clojure.edn/read-string` to read EDN text:

```clojure
(require '[clojure.edn :as edn])

(edn/read-string "{:user/name \"Ada\"}")
```

Do not use the general Clojure code reader for untrusted input. EDN is designed
as a data reader; the general Clojure reader reads a larger language.

Use `pr-str` to print ordinary Clojure values in a readable form:

```clojure
(pr-str {:user/name "Ada" :user/active? true})
;; => "{:user/name \"Ada\", :user/active? true}"
```

For Datalevin APIs, prefer passing native values when you are already in
Clojure. Use EDN strings mainly at language boundaries, in configuration files,
or when an API explicitly asks for an EDN form.

---

## 10. Common Mistakes

Do not write JSON object syntax when the example expects EDN:

```clojure
;; EDN
{:user/email "ada@example.com"}

;; JSON, not EDN
{"user/email": "ada@example.com"}
```

Do not use strings when Datalevin expects keywords:

```clojure
;; Keyword attribute
:user/email

;; String
"user/email"
```

Do not forget `#` when writing a set:

```clojure
#{:read :write}  ;; set
{:read :write}   ;; map from :read to :write
```

Do not forget to quote Datalog queries in Clojure source code:

```clojure
;; Correct: pass query data.
(d/q '[:find ?e :where [?e :user/email]] db)

;; Incorrect: Clojure tries to evaluate ?e and other symbols.
(d/q [:find ?e :where [?e :user/email]] db)
```

Do not use `null`; EDN uses `nil`:

```clojure
nil
```

Do not treat tagged literals as plain strings:

```clojure
#uuid "00000000-0000-0000-0000-000000000001"  ;; UUID value
"00000000-0000-0000-0000-000000000001"        ;; string value
```

---

## 11. Quick Reference

| Need | Use |
| :--- | :--- |
| Attribute name | `:user/email` |
| Enum value | `:order.status/paid` |
| Datalog variable | `?e`, `?name`, `?score` |
| Entity transaction | `{:user/email "ada@example.com"}` |
| Datom transaction | `[:db/add 42 :user/name "Ada"]` |
| Lookup ref | `[:user/email "ada@example.com"]` |
| Query | `[:find ?e :where [?e :user/email]]` |
| Rule set | `[[(rule ?x) [?x :attr/value]]]` |
| Set | `#{:a :b :c}` |
| Instant | `#inst "2026-06-09T12:00:00Z"` |
| UUID | `#uuid "00000000-0000-0000-0000-000000000001"` |
| Missing value | `nil` |

EDN's main advantage in Datalevin is that the examples show the actual data
structures the database consumes. Once the literal forms are familiar, schemas,
transactions, queries, rules, and indexed documents all read as variations on
the same small notation.

---

## References

[1] Rich Hickey and contributors, [Extensible Data
Notation](https://github.com/edn-format/edn), official edn-format
specification.
