---
title: Preface
chapter: 0
---

# Preface

Modern applications rarely fit neatly inside one traditional database model. A
product may need transactional updates, graph-shaped relationships,
document-shaped payloads, fast key-value access, full-text search, vector
similarity, recent-activity analytics, and long-lived state that can be audited
or explained later. In many systems, each requirement becomes another service:
PostgreSQL for records, Redis for low-latency state, Elasticsearch for search, a
vector database for embeddings, a document store for flexible payloads, and a
queue or workflow engine for long-running tasks.

That architecture can work. It also adds complexity and performance
overhead. Data has to be copied between systems. Consistency becomes an
application concern. Search results and transactional state can drift apart.
Operationally, the system becomes a collection of moving parts that must be
secured, backed up, observed, upgraded, and understood together.

One response is to keep everything in a capable SQL database and add extensions
as requirements grow. That is often reasonable. SQL is dominant for good
reasons: it is mature, widely understood, and excellent for many relational
workloads. The limitation this book cares about is different. SQL is often an
awkward application interface: queries are strings embedded in another
language, composition pushes teams toward ORMs or query builders, dialect
differences leak into application code, and table-shaped schemas make graph,
sparse, document, search, and vector workloads feel like additions around the
relational core. Many SQL extensions also bring their own syntax and operator
families rather than one uniform query model. PostgreSQL `jsonb`, for example,
is powerful, but path and containment expressions are a separate surface that
does not look like ordinary relational SQL. When queries span many joins over
uneven, interrelated data, table-based optimizers also face a hard problem:
estimating how much data each step of the query will produce.

Datalevin takes another route. Application state is stored as small facts. A
fact is a statement such as "Alice follows Bob" or "document 42 is in English":
one entity, one attribute, one value. Datalog composes those facts into
relational queries, graph traversals, rules, and recursive logic, while
key-value access, document paths, full-text search, vector similarity, and
embeddings remain transactional capabilities over the same durable store.
Queries are data structures rather than SQL strings. Attributes carry schema and
documentation wherever facts appear.

This is different from adding isolated extension features to a SQL system. In
SQL, document features, search features, similarity search, relationship
traversal, and custom logic often come with separate syntaxes and planning
rules. Application code then has to compose those surfaces carefully and
reconcile their results. In Datalevin, these capabilities share one
transactional engine and one coherent, fact-oriented query model. Search,
similarity, document structure, relationships, and exact data constraints can be
expressed together in one query instead of being stitched together afterward in
application code.

Datalevin's aim is practical: it is for application developers who want a
serious alternative to SQL for day-to-day application state. The goal is not
merely to offer another database feature set, but to make
ordinary application data easier to model, query, search, evolve, and operate
without falling back to SQL strings, ORM ceremony, extension mini-languages, and
cross-system glue code.

## Two Practical Advantages

Two advantages follow from that design:

- **Developer ergonomics.** Datalevin queries are data structures, not strings
  assembled inside another language. Datalog is truly declarative: a query
  describes the facts that must be true, and the engine finds the matching
  values. Joins come from reusing the same variable name rather than writing
  explicit join syntax. Attributes carry names, types, uniqueness, whether they
  hold one value or many, indexes, and documentation in one place. The same
  fact-oriented model covers relationships, documents, search, vectors, and
  rules, which makes queries easier for application developers, new team
  members, and LLMs to write and inspect. The ergonomics extend beyond the query
  language: Datalevin can be embedded directly in application code, moved to
  client/server deployment when sharing or operations require it, and called
  from shell scripts through the `dtlv` command-line tool. It is also
  AI-native: embedding and text-generation providers are built in, and the
  `dtlv mcp` server exposes Datalevin to MCP-compatible AI tools without a
  separate adapter service.
- **Performance for complex application queries.** Datalevin's sorted fact
  indexes keep data close to the shapes that Datalog queries ask for, which
  gives the query planner better information and reduces unnecessary work.
  Integrated full-text, vector, embedding, document, and key-value indexes also
  avoid shuttling candidate ids between separate systems before applying
  logical constraints. This design performs better on benchmark workloads that
  stress these shapes: Datalevin's published Join Order Benchmark run compares
  complex IMDb-derived join queries with PostgreSQL and SQLite [6], and its
  LDBC Social Network Benchmark run compares graph-oriented social-network
  queries with Neo4j [7]. In the JOB run, SQLite was the weaker SQL comparison:
  its reported non-timeout query time was already more than 4x slower than
  Datalevin, and that understates the gap because several SQLite queries timed
  out. These are not claims that one database wins every workload; they are
  evidence for a structural advantage in applications whose queries combine
  joins, graph traversal, full-text search, vector search, and evolving sparse
  data.

## Built on LMDB

Datalevin is built on LMDB, a fast memory-mapped key-value store, but it is not
only a key-value wrapper. LMDB provides the durable sorted storage foundation;
Datalevin exposes that foundation directly when sorted key-value access is the
right tool, and provides a Datalog database when relationships, constraints,
identity, and logical queries are the right tool. Full-text indexes, vector and
embedding indexes, and path-indexed documents are maintained beside the facts so
that search, vector similarity, and logical constraints stay transactionally
aligned.

The implementation is layered. At the bottom is a native storage layer derived
from LMDB. A JVM/Java layer exposes the native operations to managed code. The
Clojure layer implements the main database semantics: schema, Datalog queries,
planning, secondary indexes, and high-level APIs, while leveraging LMDB's
transaction machinery underneath. The Java, Python, and JavaScript packages call
into the same Datalevin runtime through language bindings. In other words,
Clojure is the most compact language for showing the model in this book, but
Datalevin itself is not merely a Clojure wrapper around a file format. This
layering is pragmatic rather than ideological: each layer uses technology well
suited to that job, from LMDB's native storage and transaction discipline to the
JVM's portability and Clojure's strength at data-oriented programming and
Datalog representation.

## A First Look in Code

Here is a small first look at the model in code. It declares two attributes,
opens a connection, writes three people and two follow relationships, and asks
for Alice's friend-of-a-friend.

For the printed book, we show Clojure examples for their conciseness. You do
not need to know Clojure to understand them; most are just data and Datalevin
function calls. The Web version at
https://datalevin.org shows the same examples in four languages side by side:
Clojure, Java, JavaScript, and Python.

To grasp the full nuances of some examples, you may want to be familiar with the
data notation we use, called EDN (extensible data notation), which is similar to
JSON, but with slightly richer data types. Appendix B is the EDN reference for
this book.

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

If you do not understand every line yet, do not worry. Later chapters explain
the syntax and APIs in detail. The point here is the basic shape of the book:
data is represented as facts, relationships are ordinary values, and queries are
data. The database answers questions by joining facts, following references, and
applying logic. Later chapters extend the same foundation to transactions,
schema design, documents, full-text search, vectors, rules, operations, and
intelligent applications.

A related theme runs throughout the book: data-oriented programming for
application design. Model durable application state as plain, inspectable data;
make relationships explicit; and let small functions and declarative queries
transform that data instead of hiding essential state inside opaque objects.

Datalevin is open source. Its source code, issues, release history, and project
documentation live in the
[Datalevin GitHub repository](https://github.com/datalevin/datalevin).

## Why This Book Exists

Datalevin is a compact database system. It can be embedded in an application,
run as a server, used from scripts, accessed from multiple language clients, and
deployed as part of large production systems. It also supports a style of modeling
that is unfamiliar to many engineers who come from table-first, document-first,
or service-first architectures.

This book exists to make that style practical.

The first goal is to teach Datalevin as a database: how to open it, transact
data, read data, design schemas, model relationships, use the key-value layer,
query with Datalog [1], tune storage, and operate the system.

The second goal is to teach a way of thinking. Datalevin works best when you
learn to see application data as facts, relationships, indexes, and derived
knowledge. A row, a document, a relationship, a search result, and a similarity
result do not have to live in separate conceptual universes. They can be different
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

Part V is about performance and operations. It covers durability, storage
tuning, batching, ingestion, query planning, rules-engine execution, deployment,
security, replication, monitoring, and production checklists.

Part VI applies the database to intelligent systems. It develops a line from
persistent agent memory, to episodic, semantic, and working memory, to recall
and context assembly, to apperception and truth maintenance, and finally to
stateful AI application patterns.

Part VII is reference material. It covers installation, runtimes, and deployment
modes; EDN, the data notation used throughout the book; schema keys; Datalog
built-ins; and the core, key-value, and client APIs.

## Who This Book Is For

This book is for engineers who build data-intensive applications and want more
than a single-purpose store. You may be building an embedded application, a
backend service, a knowledge graph, a search-heavy product, a durable workflow
system, or an AI application that needs memory and auditability.

You should be comfortable with basic database ideas: transactions, indexes,
schemas, filters, and query results. You do not need prior Datalog
experience. The book introduces Datalog from first principles, then returns to it
throughout the modeling, search, performance, and intelligent-systems chapters.
Datalevin uses the Datomic-style EDN form of Datalog: friendlier for application
developers than the older Prolog-like notation, but still based on the same
logic-programming ideas of variables and rules.

Most examples use Clojure because Datalevin's native data model is easiest to
see in EDN and Datalog forms, but the ideas are not limited to Clojure. As noted
earlier, the web version at https://datalevin.org shows each example in all four
languages, following one set of per-language conventions:

- Clojure examples use EDN directly.
- Java examples use the high-level `datalevin` package, especially typed schema
  and transaction builders. Attribute names are written as strings such as
  `"person/name"`. In the few Java examples that pass options or custom
  function definitions as maps, keyword values are still written as
  colon-prefixed strings such as `":cosine"` or `":tx-fn"`, because those maps
  are normalized by the Java wrapper at runtime.
- Python Datalog examples use `from datalevin import connect` and connection
  methods such as `conn.transact`, `conn.query`, and `conn.pull`. Schema and
  transaction maps use EDN keyword strings such as `":person/name"`. KV,
  administration, and interop examples import the specific Python helpers they
  use, such as `open_kv`, `new_client`, `exec_json`, or `interop`.
- JavaScript examples use `connect` from `datalevin-node`, `await conn.*`
  methods, and the same colon-prefixed keyword strings as Python.

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
model, transactions, indexes, and query semantics are clear. You should not
consider Part VI to be a separate AI appendix; it is the natural consequence of
the earlier parts.

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
way to represent user and agent state. I am grateful to Michelle Zhou, Amar
Mehta, Wenxi Chen, Ransom Williams, Taejun Song, and other great people at Juji,
whose work made those needs concrete and whose applications kept the project
grounded in real systems.

This book has already benefited from volunteer reviewers in the Datalevin
community who read draft chapters, ran examples, questioned unclear
explanations, and improved phrasings: Estevo U. C. Castro, Max Rothman, Weidong
Cai, Amar Mehta, and Norbert Wójtowicz. I am grateful for their contributions.

<div class="preface-signature" style="text-align: right; margin-top: 2rem;">
Huahai Yang<br>
June 2026
</div>

## Notes

Datalevin builds on LMDB, an abbreviation of Lightning Memory-Mapped Database, a
battle-tested key-value store with exceptional read performance. "Levin" is an
old English word for lightning.

## References

[1] Stefano Ceri, Georg Gottlob, and Letizia Tanca, ["What You Always Wanted to
Know About Datalog (And Never Dared to
Ask)"](https://hdl.handle.net/11311/665510), *IEEE Transactions on Knowledge and
Data Engineering* 1, no. 1, 1989, pp. 146-166.

[2] Rich Hickey, [Datomic](https://www.datomic.com/).

[3] Nikita Prokopov, [DataScript](https://github.com/tonsky/datascript).

[4] Howard Chu, [Lightning Memory-Mapped Database
(LMDB)](https://www.lmdb.tech/doc/).

[5] [Juji Inc.](https://juji.io/).

[6] Datalevin project,
    [Join Order Benchmark](https://github.com/datalevin/datalevin/tree/master/benchmarks/JOB-bench).

[7] Datalevin project,
    [LDBC SNB Benchmark](https://github.com/datalevin/datalevin/tree/master/benchmarks/LDBC-SNB-bench).
