---
title: Preface
chapter: 0
---

# Preface

Modern applications rarely fit inside one traditional database model.

An application may need transactional updates, graph-shaped relationships,
document-shaped payloads, fast key-value access, full-text search, vector
similarity, analytics over recent activity, and long-term state that can be
explained later. In many systems, each requirement becomes another service:
PostgreSQL for records, Redis for low-latency state, Elasticsearch for search,
a vector database for embeddings, a document store for flexible payloads, and a
queue or workflow engine for long-running tasks.

That architecture can work. It also creates friction. Data has to be copied
between systems. Consistency becomes an application concern. Search results and
transactional state can drift apart. Operationally, the system becomes a
collection of moving parts that must be secured, backed up, observed, upgraded,
and understood together.

One response is to keep everything in a capable SQL database and add extensions
as requirements grow. That is often reasonable, but this book's argument is not
only about reducing the number of services. SQL is a poor query language for
programs, and table-shaped storage makes cardinality estimation hard for the
optimizer precisely when queries become complex. Datalevin takes a different
route: facts are the storage and query substrate, and Datalog is the language
for composing those facts.

Datalevin starts from a different premise: many of these needs can share one
small, composable data model.

At its core, Datalevin stores facts. A fact says that an entity has an attribute
with a value. From that simple shape, Datalevin builds relational queries,
graph traversal, document modeling, key-value access, full-text search, vector
search, and logical rules. The important idea is not that every workload looks
the same. The important idea is that these workloads can cooperate inside one
transactional engine instead of being bolted together after the fact.

![Datalevin unified data model](/images/diagrams/unified-data-model.svg)

Datalevin is built on LMDB, a fast memory-mapped key-value store,
but it is not only a key-value wrapper. It exposes KV APIs when direct sorted
access is the right tool, and it provides a Datalog database when relationships,
constraints, identity, and logical queries are the right tool. It also integrates
full-text indexes, vector and embedding indexes, and path-indexed documents so
that search and reasoning can meet in the same query.

Here is the flavor of the model across the main application APIs. The examples
in this book use one convention throughout:

- Clojure examples use EDN directly.
- Java examples use the high-level `datalevin` package, especially typed schema
  and transaction builders. Attribute names are written as strings such as
  `"person/name"`; the Java wrapper turns them into Datalevin keywords.
- Python Datalog examples use `from datalevin import connect` and connection
  methods such as `conn.transact`, `conn.query`, and `conn.pull`. Schema and
  transaction maps use EDN keyword strings such as `":person/name"`. KV,
  administration, and interop examples import the specific Python helpers they
  use, such as `open_kv`, `new_client`, `exec_json`, or `interop`.
- JavaScript examples use `connect` from `datalevin-node`, `await conn.*`
  methods, and the same colon-prefixed keyword strings as Python.

When Java examples must pass a raw option map or UDF descriptor, keyword values
are still written as colon-prefixed strings such as `":cosine"` or
`":tx-fn"`, because those maps are normalized by the Java wrapper at runtime.
Unless a snippet is explicitly labeled as an API sketch, non-Clojure examples
show the public high-level binding style rather than pseudocode.

<div class="multi-lang">

```clojure
(require '[datalevin.core :as d])

(def schema
  {:person/name    {:db/unique :db.unique/identity}
   :person/follows {:db/valueType   :db.type/ref
                    :db/cardinality :db.cardinality/many}})

(def conn (d/get-conn "/tmp/preface-demo" schema))

(d/transact! conn
  [{:person/name "Alice"
    :person/follows [{:person/name "Bob"}]}
   {:person/name "Bob"
    :person/follows [{:person/name "Carol"}]}])

(d/q '[:find ?friend ?friend-of-friend
       :where [?alice :person/name "Alice"]
              [?alice :person/follows ?f]
              [?f :person/name ?friend]
              [?f :person/follows ?ff]
              [?ff :person/name ?friend-of-friend]]
     (d/db conn))
;; => #{["Bob" "Carol"]}
```

```java
import datalevin.*;

Schema schema = Datalevin.schema()
    .attr("person/name",
          Schema.attribute().unique(Schema.Unique.IDENTITY))
    .attr("person/follows",
          Schema.attribute()
              .valueType(Schema.ValueType.REF)
              .cardinality(Schema.Cardinality.MANY));

Connection conn = Datalevin.getConn("/tmp/preface-demo", schema);

conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("person/name", "Alice")
        .put("person/follows", Datalevin.listOf(-2)))
    .entity(Tx.entity(-2)
        .put("person/name", "Bob")
        .put("person/follows", Datalevin.listOf(-3)))
    .entity(Tx.entity(-3)
        .put("person/name", "Carol")));

Object results = conn.query(
    "[:find ?friend ?friend-of-friend " +
    " :where [?alice :person/name \"Alice\"] " +
    "        [?alice :person/follows ?f] " +
    "        [?f :person/name ?friend] " +
    "        [?f :person/follows ?ff] " +
    "        [?ff :person/name ?friend-of-friend]]");
// => #{["Bob" "Carol"]}
```

```python
from datalevin import connect

schema = {
    ":person/name": {":db/unique": ":db.unique/identity"},
    ":person/follows": {":db/valueType": ":db.type/ref",
                        ":db/cardinality": ":db.cardinality/many"}}

with connect("/tmp/preface-demo", schema=schema) as conn:
    conn.transact([
        {":db/id": -1,
         ":person/name": "Alice",
         ":person/follows": [-2]},
        {":db/id": -2,
         ":person/name": "Bob",
         ":person/follows": [-3]},
        {":db/id": -3,
         ":person/name": "Carol"}])

    results = conn.query("""
        [:find ?friend ?friend-of-friend
         :where [?alice :person/name "Alice"]
                [?alice :person/follows ?f]
                [?f :person/name ?friend]
                [?f :person/follows ?ff]
                [?ff :person/name ?friend-of-friend]]""")
    # => [["Bob", "Carol"]]
```

```javascript
import { connect } from "datalevin-node";

const schema = {
  ":person/name": { ":db/unique": ":db.unique/identity" },
  ":person/follows": {
    ":db/valueType": ":db.type/ref",
    ":db/cardinality": ":db.cardinality/many"
  }
};

const conn = await connect("/tmp/preface-demo", { schema });

try {
  await conn.transact([
    { ":db/id": -1, ":person/name": "Alice", ":person/follows": [-2] },
    { ":db/id": -2, ":person/name": "Bob", ":person/follows": [-3] },
    { ":db/id": -3, ":person/name": "Carol" }
  ]);

  const results = await conn.query(
    `[:find ?friend ?friend-of-friend
      :where [?alice :person/name "Alice"]
             [?alice :person/follows ?f]
             [?f :person/name ?friend]
             [?f :person/follows ?ff]
             [?ff :person/name ?friend-of-friend]]`);
  // => [["Bob", "Carol"]]
} finally {
  await conn.close();
}
```

</div>

The example is small, but it shows the shape of the book. Data is represented as
facts. Relationships are ordinary values. Queries are data. The database can
answer questions by joining facts, following references, and applying logic.
Later chapters extend the same foundation to transactions, schema design,
documents, full-text search, vectors, rules, operations, and intelligent
applications.

## Why This Book Exists

Datalevin is a compact production database system. It can be embedded in an
application, run as a server, used from scripts, accessed from multiple language
clients, and deployed as part of production systems. It also supports a style of
modeling that is unfamiliar to many engineers who come from table-first,
document-first, or service-first architectures.

This book exists to make that style practical.

The first goal is to teach Datalevin as a database: how to open it, transact
data, read data, design schemas, model relationships, use the key-value layer,
query with Datalog [1], tune storage, and operate the system.

The second goal is to teach a way of thinking. Datalevin works best when you
learn to see application data as facts, relationships, indexes, and derived
knowledge. A row, a document, a graph edge, a search hit, and a vector neighbor
do not have to live in separate conceptual universes. They can be different
views over the same durable facts.

The third goal is to show why this matters now. Large language models and
agentic applications are pushing databases into a new role. A useful AI system
does not only need prompt text. It needs persistent memory, scoped task state,
retrieval with permissions and provenance, audit trails, and a way to integrate
new observations into a coherent long-term model. Part VI of this book shows how
Datalevin can serve as that memory substrate.

## How the Book Is Organized

Part I builds the foundation. It explains why Datalevin exists, how to get
started, how to think in facts rather than containers, how LMDB shapes the
storage model, and how attributes, entities, and namespaces work.

Part II covers the core APIs. You will transact data atomically, read with
lookup, pull, and entity APIs, learn Datalog fundamentals, rules, recursion, and
derived knowledge, and then drop down to the direct key-value API when sorted
keys or custom storage structures are the right tool.

Part III is about modeling. It shows how relational, graph, and document
patterns map onto Datalevin's fact-first model, and how to make schema choices
that can evolve without losing clarity.

Part IV treats indexes as capabilities. You will learn the core EAV and AVE
indexes, full-text search, vector and embedding search, and hybrid queries that
combine KV access, logic, text, and similarity.

Part V is about performance and operations. It covers batching, ingestion,
durability, storage tuning, query planning, rules-engine execution, deployment,
security, replication, monitoring, and production checklists.

Part VI applies the database to intelligent systems. It develops a line from
persistent agent memory, to episodic, semantic, and working memory, to recall
and context assembly, to apperception and truth maintenance, and finally to
stateful AI application patterns.

Part VII is reference material. It summarizes schema keys, Datalog built-ins,
the key-value API, and EDN, the data notation used throughout the book.

## Who This Book Is For

This book is for engineers who build data-intensive applications and want more
than a single-purpose store. You may be building an embedded application, a
backend service, a knowledge graph, a search-heavy product, a durable workflow
system, or an AI application that needs memory and auditability.

You should be comfortable with basic database ideas: transactions, indexes,
schemas, predicates, and query results. You do not need prior Datalog
experience. The book introduces Datalog from first principles, then returns to it
throughout the modeling, search, performance, and intelligent-systems chapters.
Datalevin uses the Datomic-style EDN form of Datalog: friendlier for application
developers than the older Prolog-like notation, but still based on the same
logic-programming ideas of variables, unification, and rules.

Most examples use Clojure because Datalevin's native data model is easiest to
see in EDN and Datalog forms. The ideas are not limited to Clojure. Datalevin
also exposes Java, Python, JavaScript, command-line, server, and MCP surfaces,
and the book calls out those modes where they matter.

If EDN is new to you, read Appendix D early. You do not need to become a
Clojure programmer to understand EDN, but the notation appears everywhere in
schemas, transactions, queries, rules, and examples.

## How to Read This Book

If you are new to Datalevin, read Parts I and II in order. They establish the
mental model and the APIs that later chapters assume.

If you are designing an application schema, continue into Part III before
optimizing anything. Good Datalevin design starts with clear facts, identities,
and relationships.

If you are building search or retrieval features, read Parts III and IV
together. The most useful retrieval systems combine modeling choices with the
right indexes.

If you are operating Datalevin in production, read Part V after the foundations.
The operational chapters make more sense once you know how data, indexes, and
queries are represented.

If you are building AI systems, resist the urge to jump straight to Part VI.
Persistent memory and agent state are only reliable when the underlying data
model, transactions, indexes, and query semantics are clear. Part VI is not a
separate AI appendix; it is the natural consequence of the earlier parts.

## What You Should Take Away

By the end of the book, you should be able to:

- model application data as durable facts without losing relational, graph, or
  document expressiveness;
- use Datalog for declarative queries, joins, rules, and recursive relationships;
- choose when to use Datalevin's Datalog layer and when to use the lower-level
  key-value API directly;
- combine full-text search, vector search, and logical constraints in one
  retrieval pipeline;
- design schemas and indexes that support both correctness and performance;
- operate Datalevin in embedded, server, scripting, and production contexts;
- build persistent-memory systems for agents without treating a chat transcript
  as the only source of truth.

The larger argument of the book is simple: databases can be more than passive
containers. A database can be a logical substrate: a place where facts are
stored, relationships are traversed, knowledge is derived, evidence is preserved,
and intelligent systems can maintain state over time.

Datalevin is one concrete way to build on that idea.

## Acknowledgments

Datalevin stands on a line of ideas and software that made this project
possible. I am grateful to Rich Hickey, whose EDN-based Datalog flavor in
Datomic [2] showed how logic queries could become a practical application
programming interface; to Nikita Prokopov, whose DataScript [3] code was the
starting point for Datalevin; and to Howard Chu, whose LMDB code became the
storage foundation on which Datalevin is built [4].

I also want to thank the Datalevin users in the Clojure community. Their
questions, bug reports, feature requests, examples, and production experience
have shaped the project in ways that are hard to separate from the code itself.
Thanks also to everyone who contributed code, wrote about Datalevin,
or supported the work through GitHub Sponsors: Dennis Heihoff, Anders Murphy,
Aleksandr Bogdanov, Nils Grünwald, Christophe Grand, Garrett Hopper, Daniel
Vingo, Samuel Ludwig, itonomi, Amar Mehta, Ryan Domigan, Lars Rune Nøstdal, Clay
Hopperdietzel, Isaac Ballone, and many others that I have missed.

Datalevin was originally motivated by needs at Juji [5], where intelligent
applications required persistent memory, fast retrieval, and a more flexible
way to represent user and agent state. I am grateful to Michelle Zhou, Wenxi
Chen, and other great people at Juji, whose work made those needs concrete and
whose applications kept the project grounded in real systems.

This book has already benefited from volunteer reviewers who read draft
chapters, ran examples, questioned unclear explanations, found inconsistent
terminology, and pointed out places where the book assumed too much background:
[insert reviewer names here]. I am grateful for the contributions!

## Notes

Datalevin builds on LMDB, an abbreviation of Lightning Memory-Mapped Database, a
battle-tested key-value store with exceptional read performance. "Levin" is an
old English word for lightning.

## References

[1] Stefano Ceri, Georg Gottlob, and Letizia Tanca, ["What You Always Wanted to
Know About Datalog (And Never Dared to
Ask)"](https://hdl.handle.net/11311/665510), *IEEE Transactions on Knowledge and
Data Engineering* 1, no. 1, 1989, pp. 146-166.

[2] Cognitect, [Datomic](https://www.datomic.com/).

[3] Nikita Prokopov, [DataScript](https://github.com/tonsky/datascript).

[4] Howard Chu, [Lightning Memory-Mapped Database
(LMDB)](https://www.lmdb.tech/doc/).

[5] [Juji Inc.](https://juji.io/).
