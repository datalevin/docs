---
title: "Why Datalevin"
chapter: 1
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 1: Why Datalevin

Splitting application state across many specialized stores carries a cost. This
chapter is about where that cost actually lands: not only in extra
infrastructure, but in the application code itself.

When several data capabilities — transactional updates, relational joins, graph
traversal, document-style flexibility, full-text search, and vector search —
are assembled from multiple products, business logic starts to know which data
lives in the relational database, which data was copied into the search index,
which fields were duplicated into a document store, and which vectors need to be
refreshed after an update. This knowledge is not essential to the business
domain; it is incidental complexity. As more incidental complexity accumulates,
the application becomes harder to change, mired in the software engineering
"tar pit" that Frederick P. Brooks described [1].

Datalevin is designed as a single durable substrate for these mixed workloads.
It combines a high-performance key-value storage layer with a Datalog query
engine and integrated indexing and search capabilities, so you can model and
query data through one system instead of stitching several engines together.

The central idea is simple: keep facts, indexes, documents, search entries, and
vector metadata close enough that one transaction can keep them consistent and
one query model can combine them.

That model also gives application code a clean place to observe change. When a
write commits, Datalevin can report what changed and notify application
listeners, so an embedded application does not have to poll or scatter change
detection across search, graph, document, and cache subsystems. Chapter 6 covers
that transaction-observation pattern after introducing transaction reports.

This chapter explains the problem Datalevin addresses, how the architecture
unifies multiple access patterns, and where it fits in a modern stack.

## 1. A Unifying Data Model

At the Datalog layer, Datalevin represents facts as EAV triples: Entity,
Attribute, and Value. This follows the same atom-of-statement idea used by RDF:
an RDF graph is defined as a set of subject-predicate-object triples, and
asserting one triple states one fact: that a relationship holds between its
subject and object [6]. Datalevin uses entity-attribute-value rather than
subject-predicate-object because it is an operational database and chooses
database-friendly terminology. An entity is a database identity around which
facts collect, not necessarily a concrete subject in the RDF sense. An attribute
is a named database property that can have value types, cardinality, uniqueness,
indexing behavior, documentation, and transaction-time validation. The third
position is called a value because it may be a scalar, a reference to another
entity, a tuple, a document value, or another supported Datalevin value type.
Despite the terminology differences, the modeling point is the same: each
assertion is stored as one small, independently queryable fact, called a
**datom** (data atom).

In this chapter, the words **fact**, **assertion**, **EAV triple**, and
**datom** all point to the same basic unit: one statement that an entity has an
attribute with a value. The emphasis differs slightly. "Fact" and "assertion"
describe the logical meaning. "EAV triple" describes the three positions in the
model. "Datom" is Datalevin's concrete name for the stored fact.

Modeling data as datoms yields a model that is compact, sparse, and
composable:

- **Entity (E):** the identity being described. In a Datalevin datom, this is
  represented as a system-assigned internal numeric entity id (eid), valid
  inside that database. Applications can still store stable domain identifiers
  such as user IDs, order IDs, UUIDs, and slugs as ordinary attributes, but
  internal relationships do not have to depend on those identifiers.
- **Attribute (A):** the named property being asserted. Attributes are usually
  namespaced keywords such as `:user/email`, `:order/customer`, or
  `:doc/body`. The relationship between entity and attribute is arbitrary. There
  is no requirement that certain entities must have certain attributes, and vice
  versa. Therefore, the data model can be sparse: only asserted datoms are
  present in the database, and missing facts are represented by the absence of a
  datom rather than by placeholder values. This does not prevent storing a
  boolean `false` as an actual value; in that case, the datom is present and its
  value is `false`.
- **Value (V):** the value of the property. A value may be a string, number,
  keyword, boolean, reference to another entity, timestamp, document value,
  vector, or another supported Datalevin value type.

When surfaced in query functions, datoms are commonly represented as `[e a v]`.
For example, the fact that entity `101` has Alice's email address is the datom
`[101 :user/email "alice@example.com"]`.

This tiny shape is the first new concept to internalize. A datom is smaller
than a row and flatter than a document, but it can express both. A group of
datoms that share the same entity id can look like a row. A graph edge can be a
datom whose value is another entity id. A document-like aggregate can be modeled
as ordinary facts when its fields should join, validate, reference other
entities, or change independently. Chapter 14 introduces the complementary
choice: storing a nested map or JSON-like payload as one document value while
Datalevin indexes its internal paths. The uniform shape makes these choices
composable instead of isolated.

Entities also deserve a precise definition. An entity is not a table, class, or
JSON object. It is an identity around which facts collect. If entity `101` has
`:user/email`, `:customer/tier`, and `:employee/manager`, Datalevin does not
need three separate containers to hold those facts. They are simply assertions
about the same entity. This gives the model room to evolve as the application
learns more about the thing being represented.

As database-local handles, entity ids should not be confused with stable
application identifiers; two databases containing the same application data may
assign different sets of eids. Application domain identifiers such as user IDs,
order IDs, document IDs, UUIDs, and slugs should be stored as ordinary attribute
values, often with the `:db.unique/identity` property (see Chapter 3 for
details).

This makes Datalevin's notion of entity close to the metaphysical idea known as
bundle theory: an object can be understood as the bundle of properties that
belong together, rather than as a hidden substance underneath those properties
[10]. The analogy should not be pushed too far. A Datalevin eid is only a
system-assigned, database-local handle; it is not the object itself and not a
domain identifier. The entity is the bundle of datoms anchored by that handle:
the currently asserted properties and relationships that share the same eid.

Attributes carry much of the modeling meaning. In a table database, schema is
often attached to a table: the `users` table has these columns, the `orders`
table has those columns. In Datalevin, schema is primarily attached to
attributes: `:user/email` has a value type, may be unique, may be indexed for
full-text search, and may have cardinality constraints. Naming attributes well
therefore matters. Namespaces such as `:user/email`, `:doc/body`, and
`:invoice/total` make the domain visible in the data itself.

Datalevin does not require a complete schema up front. When transaction data
mentions an attribute that is not yet in the schema, Datalevin creates a schema
entry for that attribute automatically. You can start with a small model and add
declared structure as the application becomes clearer. Declared attribute
properties still matter: types, uniqueness, references, indexing flags, and
other schema properties control how attributes are stored, constrained, indexed,
and queried.

### Aggregate Models vs Triple Model

Relational and document databases are typically aggregate-first: a document or
row is the main unit of mutation and retrieval, and nested fields or columns are
scoped under that aggregate.

That model is familiar and often convenient. A user row contains user columns.
An order document contains line items. A JSON payload contains nested metadata.
The aggregate gives the application an obvious shape to read and write.

Triple stores are fact-first: each E-A-V assertion is atomic and independently
queryable, which makes cross-aggregate joins, inference, and graph traversal a
natural part of the model.

Instead of asking "which table owns this field?" you ask "what fact am I
asserting?" Instead of asking "which document should duplicate this data?" you
ask "which entity does this fact describe, and which other entities does it
refer to?"

Datalevin keeps the triple model at the core and supports document workflows
through `idoc` indexing (Chapter 14). In other words, documents are supported,
but facts are foundational. You can still return an entity as a map, pull a
nested shape for an API response, or index a JSON-like document for path
queries. The difference is that these aggregate shapes are views over durable
facts, not the only way to see the data. The same facts can also be joined
relationally, traversed as a graph, searched by text, or combined with search
constraints.

Compared with rigid table layouts, EAV handles sparse and evolving data
naturally because missing values are simply omitted, not stored as placeholder
nulls.

If only some users have a `:user/timezone`, only those users need that datom.
If a later feature adds `:user/preferred-model`, existing entities do not need
to be rewritten. The model grows by adding attributes and facts.

## 2. One Engine, Multiple Access Patterns

Datalevin exposes several complementary capabilities in one runtime. Each
capability can be useful by itself, but the more important strength is that they
compose over the same database state. As illustrated in Figure 1.1, Datalevin
uses a single unified data model to support multiple database capabilities. This
section gives an overview, while the rest of the book fills in the details.

![Datalevin unified data model](/images/diagrams/unified-data-model.svg)

### Relational and Graph Queries with Datalog

Datalog provides declarative joins, recursive rules, and reusable query
composition. In practice, this lets Datalevin support both relational workloads
and graph traversal workloads through one query model.

The same query language can ask for "all orders whose customer is in France"
and "all dependencies reachable from this package." The first feels relational:
facts are joined by shared variables. The second feels graph-oriented: entity
references are followed as edges. In Datalevin both are expressed as logical
patterns over datoms.

Datalevin uses the EDN Datalog format pioneered by Datomic (see Appendix B).
This is friendlier to application developers and easier for programs to
construct than the older Prolog-like notation because queries are ordinary data
structures: vectors, keywords, symbols, and lists. The underlying idea is still
classic Datalog: describe the facts that must be true, and let the engine find
bindings for the variables [7].

A small comparison makes the difference concrete:

Classic Datalog literature writes Datalog in Prolog syntax:

```prolog
friend_of_friend(X, Z) :- follows(X, Y), follows(Y, Z).
```

The logical expression is concise and elegant, but less familiar to application
developers who are not versed in first-order predicate logic. For example, it
would be easy to miss the precise meaning of `:-`, `,`, or `.`. The functors and
arguments can seem arbitrary, and the whole form can feel abstract.

Datalevin writes the same Datalog as EDN data:

```clojure
[:find ?z
 :in $ ?x
 :where
 [?x :person/follows ?y]
 [?y :person/follows ?z]]
```

`:find`, `:in`, and `:where` are meaningful words for developers who are
familiar with database queries. Variables consistently start with `?`, `$`
names the database input, and attributes are part of the schema. Everything is
concrete.

Both examples say: find `Z` such that `X` follows `Y` and `Y` follows `Z`. The
Datalevin form is not a string in a mathematical language. It is an
ordinary piece of EDN data: a vector containing keywords in English, symbols
starting with `?` for variables, and nested vectors. That makes queries easier
for programs to construct, validate, transform, and compose before handing them
to the database.

For larger applications, it is important that queries remain close to the
application domain. A clause such as `[?e :doc/lang "en"]` says that the same
entity `?e` must have an English language fact. A clause such as `[?e
:order/customer ?customer]` says that the order entity relates to a customer
entity. Joins emerge from repeated variables, e.g. `?y` in the example above,
not from manually naming join algorithms, as you may need to do in SQL.

### Direct Key-Value Access

The same key-value substrate that persists triples and indexes is also available
directly as a durable key-value API. That substrate is DLMDB, Datalevin's
extension of LMDB, the memory-mapped key-value store covered in Chapter 4. This
low-level API is useful for low-latency state access, caches, and simple
lookup-oriented components.

Use the key-value API when the access pattern really is just "given this key,
read or update this value" or "read this key range". Examples include local
application state, checkpoints, registries, counters, queues, or cached
results. In those cases, forcing everything through a logical query layer would
add complexity without adding much value.

### Path-Indexed Documents with idoc

Datalevin supports path-indexed access to documents in EDN, JSON, and
Markdown formats using the `:db.type/idoc` data type (Chapter 14). You can query
nested structures with `idoc-match`, including logical combinators and path
predicates, while keeping the documents in the same transactional store.

This is useful because real applications rarely receive perfectly normalized
facts at the boundary. Here, **normalized** means that each durable fact is
stored once and related facts are connected by references, rather than copied or
nested into every larger record that might need them. Applications receive JSON
from APIs, EDN configuration, parsed Markdown metadata, tool outputs, and
model-produced structures whose shape may change over time. An `idoc` lets you
keep such nested data as a document while still indexing paths inside it.

For example, a documentation page might have structured metadata such as
`{:module {:name "search" :status "stable"}}`. You may not want to promote
every nested field to its own top-level schema attribute on day one. With idoc,
Datalevin can still answer questions about paths inside the document and join
those answers with ordinary facts about the same entity.

Idoc does not replace normal datoms. It complements them. Use datoms for core
domain facts with stable meaning and relationships. Use idoc for nested,
semi-structured, imported, or metadata-heavy values where path search is more
important than turning every leaf into a named attribute.

### Integrated Full-Text, Vector, and Embedding Search

Datalevin includes built-in full-text search, similarity search over vectors
supplied by the application, and text embedding search. Because these indexes
live in the same database system, you can combine search results and exact
logical conditions in one Datalog query instead of synchronizing external
search services.

These search modes answer different questions:

- Full-text search is lexical. It is good for matching words, terms, ngrams, and
  phrases. Datalevin supports a variety of text analyzers and boolean search
  expressions. Details are in Chapter 16.
- Vector search is geometric. It is good for finding items whose vector
  embeddings are near a query vector under a similarity metric. See Chapter 17.
- Embedding search converts text to vectors. It is useful when the application
  wants meaning-based search over text fields instead of exact word matching.
  Datalevin provides built-in embedding services so users do not have to roll
  their own. Datalevin can also use third-party embedding services.

In many systems, these capabilities live outside the primary database. The
application first asks a search engine for candidate ids, then asks the database
for structured facts, then performs more filtering in application code.
Datalevin's integrated approach lets word search, meaning-based search,
document-path search, and exact logical constraints meet inside one query.

The practical result is that an application can choose the access pattern that
fits the problem without leaving the same data model. A query can ask for
"documents like this question, containing this phrase, visible to this user,
and marked stable" as one database question rather than as a sequence of
cross-system calls.

## 3. Why Not Just SQL?

A possible objection to a Datalog system is that modern SQL databases keep
adding capabilities: JSON columns, full-text search, graph extensions, vector
indexes, procedural languages, recursive CTEs, and extension ecosystems. For
example, if PostgreSQL can do more and more of these jobs, why not stay with
familiar SQL?

This question matters because many developers are not enthusiastic SQL users so
much as resigned SQL users. They want something more composable, more directly
usable from programs, and less tied to table containers. But the available
alternatives often give up too much: transactions, durability, joins, ad hoc
query, deployment simplicity, or operational maturity. Datalevin
is aimed at that gap. It is a database for developers who want to replace SQL in
application systems, not merely wrap it, hide it behind an ORM, or add another
specialized service beside it.

There are three reasons that go deeper than syntactic cosmetics, and they build
on one another: SQL is awkward as an application interface, table-shaped storage
makes query planning harder, and stacking SQL extensions turns every
cross-cutting question into an integration problem.

### SQL Is Awkward as an Application Interface

The first reason is about programming ergonomics. SQL descends from SEQUEL,
introduced by Chamberlin and Boyce as "a Structured English Query Language"
[14]. That origin still shows in SQL's large English-like surface: tables,
joins, projections, grouping, subqueries, and dialect-specific extensions. SQL
succeeded as a standard, but standardization should not be confused with
language quality. SQL queries are usually strings assembled by a host language.
Complex SQL often forces programmers to think in terms of table aliases, join
syntax, and container-specific mechanics before they can express the relationship
they actually mean.

The ORM and query-builder ecosystem is evidence that raw SQL is awkward at
application boundaries. A whole industry exists to paper over SQL as string
construction, weak host-language composition, dialect differences, type
mismatches between query results and application values, table and row
containers that do not match object graphs, and boilerplate for joins, identity,
transactions, lazy loading, and migrations. Even tools that reject classic ORM
behavior, such as query builders, LINQ-style systems, jOOQ, Ecto, and Prisma,
are still trying to make SQL less like raw SQL and more like host-language data
or typed expressions.

Datalog gives the programmer a smaller and more uniform surface. A query is
data, not a string. The query is truly declarative: it says which facts must be
true, not which joins to perform step by step. Reusing the same variable name
connects facts. Rules name reusable logic. Recursive queries use the same
logical form as non-recursive queries. The result is not less relational; it is
a higher-level way to write relational logic. Relational algebra remains the
substrate, but the user does not have to speak in join operators to ask a
relational question [2].

The ergonomic difference is not only visible to database specialists. In
practice, experienced developers often prefer Datalog once they have used it for
real application queries, because the clauses stay close to the facts they are
asking for. The contrast is even sharper for people without years of SQL
habits: interns, domain experts learning to inspect data, and application
developers who do not want to become query-planner whisperers before they can
ask a basic question. A small set of data patterns, shared variables, and named
rules is easier to teach than a large string language with dialects, aliases,
join forms, grouping edge cases, and host-language impedance mismatch. This
developer ergonomics goal was one of the original motivations for Datalevin.

The same simplicity matters for LLM-generated queries. Text-to-SQL is a hard
problem not only because natural language is ambiguous, but also because SQL is
a large, dialect-sensitive target language. A model must choose table aliases,
join syntax, grouping rules, subquery shapes, vendor functions, and sometimes
planner-sensitive rewrites. Recent text-to-SQL results show the gap clearly:
Spider 2.0 was designed around realistic enterprise workflows, and its authors
reported that an `o1-preview`-based code-agent setup solved only 21.3% of its
tasks, compared with much higher scores on older benchmarks such as Spider 1.0
[11]. A later Spider 2.0 state-of-the-art system reported 70.2% execution
accuracy, still far from the reliability one would want from an automatic
application interface [12].

Datalog is a better generation target for the same reason it is a better
programming interface: the surface is smaller and more regular. A Datalevin
query is an EDN data structure. Most of the work is choosing facts,
attributes, variables, predicates, and rules. The syntax is token-efficient
compared with structured English SQL, and there are fewer dialect decisions for
the model to hallucinate. Even though public Datalog training data is much
smaller than SQL training data, the language itself gives both humans and LLMs a
simpler target.

Datalevin's attribute-centered schema also gives an LLM better semantic
handles. An attribute such as `:order/total` can carry its own type,
cardinality, uniqueness, index behavior, and `:db/doc` documentation wherever
that fact appears. SQL systems often support table and column comments, but
they are vendor-specific metadata on table containers. In Datalevin, attribute
documentation is ordinary schema metadata. It can be read, exported, and fed to
a query-writing assistant along with the same attribute names used in Datalog
clauses. That makes the schema more useful as prompt context and reduces the
guesswork involved in choosing the right facts.

### Table Storage Makes Query Planning Harder

The second reason is query planning. SQL databases are table-first systems. Data
is bundled into row or column containers, and the optimizer must infer how many
rows will survive predicates and joins over those containers.
Real application data is usually neither evenly distributed nor independent. A
few customers may account for most orders; a few tags may appear on most
documents; some vendors, regions, products, and refund patterns may strongly
move together. Data distributions are called **skewed** when some values are
much more common than others. They are **correlated** when one predicate being
true changes the odds of another predicate being true. Skewed and correlated data
make a query optimizer's job difficult because formulas based on uniformity or
independence can produce wildly inaccurate estimates for them. Traditional
estimators therefore depend on approximations such as histograms and, in some
systems, learned models. This difficult problem of estimating result sizes,
**cardinality estimation**, has been repeatedly identified as one of the central
problems in query optimization in the database research literature [3] [4].
For container-based storage, there is no cheap general answer: the counts the
planner needs are not directly present in the storage layout, so the optimizer
has to approximate them.

A triplestore changes the shape of the problem. In Datalevin, a datom is an
explicit cell: entity, attribute, value. Missing facts are absent rather than
stored as nullable positions. Attributes are globally scoped. EAV
(Entity-Attribute-Value) and AVE (Attribute-Value-Entity) indexes make many
counts direct, and larger estimates can be sampled under the same query
conditions that execution will use. Datalevin still has to optimize queries,
but it starts from a storage model that exposes the units the optimizer needs to
count [5].

This is why adding more features to a SQL database does not fully answer what
modern applications need. An application database needs more than features:
it must remain conceptually simple, fast, and easy to operate. For
many legacy systems that are already heavily invested in SQL ecosystems, a
mature SQL database remains the right choice. But feature accumulation does not
fix SQL as a query language, and it does not remove the cardinality estimation
burden created by table-shaped containers.

### Stacking Extensions Becomes an Integration Tax

The third reason is integration. SQL extensions are valuable; they are one
reason systems such as PostgreSQL remain useful for so many applications. The
problem is not that extensions exist. The problem is that each extension
often brings its own syntax, operators, indexes, cost model, and limitations.
JSON paths, text search, vector similarity, recursive queries, and procedural
code may all be available in one SQL database. But the application still has to
compose several feature-specific surfaces and understand how the planner handles
their interaction. That flexibility is helpful when a workload needs one
extension in isolation. It becomes a tax when a single application question
needs search, document structure, relationships, similarity, and exact
constraints to work together.

For example, suppose a documentation assistant needs to find pages that mention
"vector", belong to a stable module in nested metadata, are visible to the
current user, and are close to the user's question in embedding space. In a
PostgreSQL-style design, this usually means combining a text-search expression,
a JSON path or containment expression, a vector-similarity expression, and joins
through permission tables. Each part can be indexed, but each part also has its
own operators, index choices, selectivity estimates, and tuning rules. The
engineering cost is not only syntax. The application may need to maintain
generated search columns or embedding columns, create several different index
types, tune a query plan whose estimates cross extension boundaries, or fetch
candidate ids from one subsystem and filter them in another. The runtime cost is
extra memory traffic, intermediate result sets, and CPU spent reconciling
candidates that were found through different access paths.

Datalevin's approach is to make these capabilities participate in one logical
query model over the same facts. The same documentation question can be written
as one Datalog query whose parts say, in one place: this entity matches the
text, this entity has the required nested metadata, this entity is visible to
the user, this entity is close in meaning to the question, and this entity has
the exact attributes required by the application. The cost shifts from
application-level stitching to database-level planning over one fact-oriented
model. Section 6 shows a smaller runnable version of this pattern. Likewise,
SQL schemas are centered on tables, while Datalevin schema is centered on
attributes. That matters for sparse, evolving, cross-domain data: an attribute
can carry its own type, uniqueness, cardinality, reference semantics, full-text
behavior, embedding behavior, and documentation wherever that fact appears.

Datalevin's argument is that a fact-first model, paired with Datalog, is a
better foundation for applications whose data is relational, graph-shaped,
sparse, evolving, searchable by words and meaning, and increasingly used by AI
systems.

### Many-Join Queries in Practice

To make the planning problem concrete, consider a production-style question:

```clojure
[:find ?order-id ?customer-email ?product-name
 :in $ ?vendor ?region ?since
 :where
 [?vendor-e :vendor/id ?vendor]
 [?product :product/vendor ?vendor-e]
 [?product :product/name ?product-name]
 [?line :line-item/product ?product]
 [?line :line-item/order ?order]
 [?order :order/id ?order-id]
 [?order :order/customer ?customer]
 [?customer :customer/email ?customer-email]
 [?customer :customer/address ?address]
 [?address :address/region ?region]
 [?shipment :shipment/order ?order]
 [?shipment :shipment/status :shipment.status/late]
 [?refund :refund/order ?order]
 [?refund :refund/created-at ?refund-time]
 [(>= ?refund-time ?since)]]
```

The logical question is straightforward: find refunded late orders since a
given time, for products from one vendor, shipped to customers in one region.
The actual execution plan in a database engine is not straightforward. The
engine must decide whether to start from the vendor's products, the recent
refunds, the late shipments, the region, or some other selective fact, and then
join outward.

In a SQL engine, this kind of question is usually a many-join query over
table-shaped containers. The optimizer must estimate selectivity, i.e. how many
rows survive each predicate and each join. The fact counts it needs are bundled
inside rows, indexes, histograms, and assumptions about value independence. When the estimates are wrong, the optimizer can start from a broad
table, build huge intermediate results, and only later discover that a different
predicate would have narrowed the search immediately. It is therefore hard to
predict whether a SQL query engine will perform well on complex queries. Because
of this uncertainty, large join queries are widely treated as
optimizer-sensitive. For example, PostgreSQL's own documentation [8] notes
that possible join orders grow exponentially, that exhaustive planning becomes
impractical beyond roughly ten input tables, and that the planner switches to
heuristic search for many-relation queries. It also documents techniques for
constraining join order to reduce planning time. That is the practical pressure
behind advice to denormalize, split a query, materialize intermediate results,
or hand-guide the planner when SQL joins become too large or too sensitive to
cardinality estimation. Cardinality estimation is the work of estimating the
result size of all subplans of a query [9].

Fact-based storage changes that problem. Datalevin does not have to answer every
selectivity question from table containers. Its EAV and AVE indexes expose
facts directly, and DLMDB's counted ranges and samples give the optimizer
evidence about how many datoms match a bound entity, attribute, value, or range.
The optimizer still has real work to do, but it starts from storage that matches
the logical units in the query. That is why an optimizing Datalog engine has
advantages: it can plan the complex question as one query without making the
application hard-code a join order, denormalize the model, or split the question
into many smaller queries.

This kind of query is not exotic. It appears whenever an application keeps data
normalized and asks compositional questions: which documents can this user see,
which orders match a constraint spanning products, vendors, shipments, refunds,
and customers, which clinical cases satisfy rules spanning diagnoses, providers,
prescriptions, and coverage, or which papers connect authors, affiliations,
topics, institutions, and collaborators. If a system never seems to have such
queries, that is often because the complexity has been moved into denormalized
records, application-side filtering, or cache materialization rather than
disappearing.

Join Order Benchmark (JOB) is the benchmark version of this problem [3]. It is
not a benchmark of simple key lookups or single-table scans, nor an academic
exercise with synthetic data. JOB is based on the real IMDb dataset, where value
frequencies and relationships have the same nonuniform shape seen in many
production datasets. It requires joins across many related entities: 113
analytical queries, with query shapes that range from a few joins to more than
a dozen. Its queries have enough joins that a poor query plan can produce orders
of magnitude more intermediate rows than a good one. In Datalevin's published
JOB run [13], the same workload was compared with both PostgreSQL and SQLite.
Datalevin finished the workload in 71 seconds, compared with 171 seconds for
PostgreSQL. SQLite completed the non-timeout portion in 295 seconds and hit
timeout on 9 queries, so the often-quoted "more than 4x faster than SQLite"
summary understates the actual gap.

## 4. Developer Ergonomics and Deployment Modes

The design choices described above are meant to survive contact with real
deployment. A query should still be data when it moves from a local prototype to
a service. Attributes should carry the same meaning in application code, shell
scripts, and AI tools. The application should not have to redesign its data
model merely because the operational topology changes.

Datalevin therefore treats deployment flexibility as part of the programming
model. You can begin with an embedded database in the same process as the
application, move to client/server when sharing or centralized operations are
needed, use replicas or HA when availability requirements grow, and still
script the same database from the command line. The facts, attributes,
transactions, queries, and indexes remain the same across those modes.

Datalevin is designed to run where your application runs:

- **Embedded mode** for local, process-level access. This is the simplest mode:
  the application links to Datalevin and reads or writes a local database
  directly. Embedded access is supported from Clojure, Java, Python, and
  Node.js.
- **Client/server mode** for shared deployment and centralized operations. This
  is useful when multiple processes or machines should access the same service.
  The client can be written in the same four environments.
- **Non-HA async read-only replicas** for simple read scaling on the server.
  Replicas can serve reads where eventual freshness is acceptable and automatic
  failover is not required.
- **Consensus-lease HA** for automatic leader promotion. This mode is intended
  for server deployments that need higher availability without changing the
  application data model.
- **Babashka pod mode** for scriptable workflows, automation, and lightweight
  command-line tools. Babashka is a fast-starting Clojure scripting runtime.
- **MCP server mode** for local AI tool integration. Datalevin can expose
  persistent state and retrieval capabilities to MCP-compatible AI tools without
  requiring a separate adapter service. MCP is the Model Context Protocol, a
  standard interface for connecting tools and data sources to AI clients.

This flexibility lets teams start small and evolve deployment architecture
without changing core data and query concepts. The same schema, datoms, Datalog
queries, idoc values, and search indexes remain identical as you move from a
local prototype to a server deployment or an AI tool integration. Operational
shape can change later; the conceptual model does not have to be redesigned
each time. That continuity is the practical point: Datalevin is meant to feel
like one database across development, production, automation, and AI
integration, rather than a collection of special-case systems connected by glue
code.

## 5. Where Datalevin Fits

Given these design choices, Datalevin fits where teams want a serious
application database but do not want SQL to be the center of the application
model. The goal is not to dismiss SQL databases; they are mature, capable, and
often the right tool. The goal is to offer a practical alternative for systems
that want ACID transactions, durable storage, indexes, ad hoc queries,
embeddability, and operational simplicity, while making facts and Datalog the
core model instead of tables and SQL strings.

Datalevin is especially strong when one operational database system should
support:

- application state with ACID transactions (atomicity, consistency, isolation,
  and durability),
- relational and graph-style query patterns,
- document-style payloads with path indexing,
- search pipelines that combine full-text, vector similarity, and logic
  constraints.

This combination appears in more places than it may first seem. A SaaS product
may need accounts, permissions, audit events, search, and recommendation
features. A developer tool may need package dependency graphs, documentation
search, local state, and structured metadata. An AI application may need
episodic memory, semantic facts, vector recall, source documents, and strict
filters that prevent irrelevant context from entering a prompt.

Datalevin is especially attractive when the boundaries between these needs are
porous. If a search result must be filtered by permissions, joined to ownership
metadata, checked against a lifecycle state, and then ranked with meaning-based
signals, keeping those facts in one engine simplifies the application. In SQL,
that kind of query often crosses ordinary joins, JSON expressions, search
operators, vector extensions, permission tables, and application-side filtering.
In Datalevin, the same application question can stay inside one fact-oriented
query.

Another advantage is the ability to absorb change. Datalevin lets you start
with a small schema, add declared structure where the application has learned
enough to justify it, and keep optional facts absent rather than storing
placeholder nulls. Declared schema is enforced when transactions write data.
Because facts are isolated datoms and attributes are additive, new features can
add new attributes without rewriting old entities or forcing every record
through a table migration. Stable domain identifiers, lookup refs, and unique
attributes let the application keep its own identities while Datalevin manages
database-local entity ids. This makes the model well suited to systems whose
shape changes as the product, data, or AI workflow matures.

This makes Datalevin a good default candidate for new applications whose data is
already relational, graph-shaped, searchable, document-shaped, or AI-facing.
It is also a modernization path for mature systems where SQL has become a
friction point: too many hand-written joins, too much ORM ceremony, too many
side indexes and auxiliary stores, or too much application code compensating for
queries the database should be able to express directly.

If your workload is narrowly specialized, a single-purpose engine may still be
the better choice. Datalevin is not trying to replace every database in every
role. For example, a pure analytical warehouse, a massive
append-only log pipeline, or a dedicated search cluster may be better served by
systems built only for those jobs.

The target is broad but concrete: the application database whose state must be
modeled, queried, searched, evolved, and reasoned over in one coherent system.
For developers who have been waiting for a database that keeps SQL's practical
seriousness while offering a simpler, more composable query model, Datalevin is
designed to be that alternative.

## 6. A Minimal Unified Query Example

Suppose you are building a documentation assistant. You want to find English
documents that mention a term in free text, while also requiring a structured
module status inside nested document metadata.

The example below combines word search, document-path filtering, and exact
metadata constraints in one query:

Do not worry if every detail of the syntax is not familiar yet. The main thing
to notice is that the same variable, `?e`, flows through all three constraints.
The full-text index proposes entities, the idoc index keeps entities with a
matching nested document shape, and the Datalog clause checks an exact language
fact.

<div class="multi-lang">

<!-- pdf-listing: Minimal unified query across full-text, idoc, and Datalog -->

```clojure
(require '[datalevin.core :as d])

;; Schema is optional, but this example declares it for type checking and indexes.
(def schema
  {:doc/body {:db/valueType :db.type/string
              :db/fulltext  true}
   :doc/lang {:db/valueType :db.type/string}
   :doc/idoc {:db/valueType :db.type/idoc}})

;; Obtain a DB connection. The database will be in the data/ch1 directory.
(def conn (d/get-conn "data/ch1" schema))

;; Transact one entity.
(d/transact! conn
  [{:db/id    -1
    :doc/lang "en"
    :doc/body "Datalevin adds idoc indexing and vector search."
    :doc/idoc {:module {:name "search" :status "stable"}}}])

;; Run the query.
(d/q '[:find ?e
       :in $ ?term
       :where
       ;; fulltext/idoc-match return datoms as [e a v].
       ;; [[?e _ _]] destructures the triple: keep entity id, ignore attribute/value.
       [(fulltext $ :doc/body ?term) [[?e _ _]]]
       [(idoc-match $ :doc/idoc {:module {:status "stable"}}) [[?e _ _]]]
       [?e :doc/lang "en"]]
     (d/db conn) "vector")
```

```java
import datalevin.*;

// Schema is optional, but this example declares it for type checking and indexes.
Schema schema = Datalevin.schema()
    .attr("doc/body",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true))
    .attr("doc/lang",
          Schema.attribute().valueType(Schema.ValueType.STRING))
    .attr("doc/idoc",
          Schema.attribute().valueType(Schema.ValueType.IDOC));

// Obtain a DB connection. The database will be in the data/ch1 directory.
Connection conn = Datalevin.getConn("data/ch1", schema);

// Transact one entity.
conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("doc/lang", "en")
        .put("doc/body", "Datalevin adds idoc indexing and vector search.")
        .put("doc/idoc",
             Datalevin.mapOf("module",
                 Datalevin.mapOf("name", "search", "status", "stable")))));

// Run the query.
Object results = conn.query(
    "[:find ?e " +
    " :in $ ?term " +
    " :where " +
    " [(fulltext $ :doc/body ?term) [[?e _ _]]] " +
    " [(idoc-match $ :doc/idoc {:module {:status \"stable\"}}) [[?e _ _]]] " +
    " [?e :doc/lang \"en\"]]",
    "vector");
```

```python
from datalevin import connect

# Schema is optional, but this example declares it for type checking and indexes.
schema = {
    ":doc/body": {":db/valueType": ":db.type/string",
                  ":db/fulltext": True},
    ":doc/lang": {":db/valueType": ":db.type/string"},
    ":doc/idoc": {":db/valueType": ":db.type/idoc"}}

# Obtain a DB connection. The database will be in the data/ch1 directory.
with connect("data/ch1", schema=schema) as conn:
    # Transact an entity.
    conn.transact([
        {":db/id": -1,
         ":doc/lang": "en",
         ":doc/body": "Datalevin adds idoc indexing and vector search.",
         ":doc/idoc": {"module": {"name": "search", "status": "stable"}}}])

    # Query.
    results = conn.query("""
        [:find ?e
         :in $ ?term
         :where
         [(fulltext $ :doc/body ?term) [[?e _ _]]]
         [(idoc-match $ :doc/idoc {:module {:status "stable"}}) [[?e _ _]]]
         [?e :doc/lang "en"]]""",
        "vector")
```

```javascript
import { connect } from "datalevin-node";

// Schema is optional, but this example declares it for type checking and indexes.
const schema = {
  ":doc/body": { ":db/valueType": ":db.type/string",
                 ":db/fulltext": true },
  ":doc/lang": { ":db/valueType": ":db.type/string" },
  ":doc/idoc": { ":db/valueType": ":db.type/idoc" }
};

// Obtain a DB connection. The database will be in the data/ch1 directory.
const conn = await connect("data/ch1", { schema });

try {
  // Transact an entity.
  await conn.transact([
    { ":db/id": -1,
      ":doc/lang": "en",
      ":doc/body": "Datalevin adds idoc indexing and vector search.",
      ":doc/idoc": { "module": { "name": "search", "status": "stable" } } }
  ]);

  // Query.
  const results = await conn.query(
    `[:find ?e
      :in $ ?term
      :where
      [(fulltext $ :doc/body ?term) [[?e _ _]]]
      [(idoc-match $ :doc/idoc {:module {:status "stable"}}) [[?e _ _]]]
      [?e :doc/lang "en"]]`,
    "vector");
} finally {
  await conn.close();
}
```

</div>

What happens in this query:

1. `fulltext` retrieves candidate entities by searching `:doc/body` for the
   term `"vector"`.
2. `idoc-match` narrows candidates to documents whose nested idoc metadata
   contains `{:module {:status "stable"}}`.
3. The Datalog clause `[?e :doc/lang "en"]` applies an exact metadata constraint.

The key point here is composition: word search, structure-aware filtering, and
exact logical predicates are executed in one query context over one database
state. In a split architecture, this often requires multiple systems and
intermediate joins. Here it is a single declarative query over shared facts.

## Summary

Datalevin is built for workloads that cross boundaries between key-value
storage, logical querying, graph relationships, document modeling, full-text
search, and vector or embedding search. Its core move is to treat facts as the
shared substrate and indexes as composable capabilities over that substrate.

The rest of the book develops these concepts in layers. The next chapters build
the basic mental model, storage model, and entity model. Later chapters cover
the core APIs, schema design, Datalog, documents, full-text search, vector
search, performance, deployment, and finally intelligent systems that use
Datalevin as persistent memory.

## References

[1] Frederick P. Brooks, Jr., *The Mythical Man-Month: Essays on Software
Engineering*, Anniversary Edition, Addison-Wesley, 1995.

[2] Huahai Yang,
   [Competing for the JOB with a Triplestore](https://yyhh.org/blog/2024/09/competing-for-the-job-with-a-triplestore/),
   yyhh.org, 2024.

[3] Viktor Leis, Andrey Gubichev, Atanas Mirchev, Peter Boncz,
   Alfons Kemper, and Thomas Neumann,
   [How Good Are Query Optimizers, Really?](https://www.vldb.org/pvldb/vol9/p204-leis.pdf),
   Proceedings of the VLDB Endowment, vol. 9, no. 3, 2015, pp. 204-215.

[4] Viktor Leis, Bernhard Radke, Andrey Gubichev, Alfons Kemper,
   and Thomas Neumann,
   [Cardinality Estimation Done Right: Index-Based Join Sampling](https://www.cidrdb.org/cidr2017/papers/p9-leis-cidr17.pdf),
   CIDR 2017.

[5] Huahai Yang,
   [SQLite in Production? Not So Fast for Complex Queries](https://yyhh.org/blog/2026/01/sqlite-in-production-not-so-fast-for-complex-queries/),
   yyhh.org, January 27, 2026.

[6] Richard Cyganiak, David Wood, and Markus Lanthaler,
   [RDF 1.1 Concepts and Abstract Syntax](https://www.w3.org/TR/rdf11-concepts/),
   W3C Recommendation, February 25, 2014.

[7] Stefano Ceri, Georg Gottlob, and Letizia Tanca,
   [What You Always Wanted to Know About Datalog (And Never Dared to Ask)](https://hdl.handle.net/11311/665510),
   *IEEE Transactions on Knowledge and Data Engineering*, 1(1):146-166, 1989.

[8] PostgreSQL Global Development Group,
    [Controlling the Planner with Explicit JOIN Clauses](https://www.postgresql.org/docs/current/explicit-joins.html), PostgreSQL Documentation.

[9] Han, Yuxing, et al. Cardinality estimation in DBMS: A comprehensive
benchmark evaluation. VLDB, 15:4, (2022).

[10] Howard Robinson and Ralph Weir,
   [Substance](https://plato.stanford.edu/entries/substance/),
   *The Stanford Encyclopedia of Philosophy*, substantive revision May 6, 2024,
   especially Section 3.2, "Bundle theories versus substrata and thin
   particulars."

[11] Fangyu Lei, Jixuan Chen, Yuxiao Ye, Ruisheng Cao, Dongchan Shin,
   Hongjin Su, Zhaoqing Suo, Hongcheng Gao, Wenjing Hu, Pengcheng Yin,
   Victor Zhong, Caiming Xiong, Ruoxi Sun, Qian Liu, Sida Wang, and Tao Yu,
   [Spider 2.0: Evaluating Language Models on Real-World Enterprise Text-to-SQL Workflows](https://arxiv.org/abs/2411.07763),
   arXiv:2411.07763, 2024.

[12] Tanmay Parekh, Ella Hofmann-Coyle, Shuyi Wang, Sachith Sri Ram Kothur,
   Srivas Prasad, and Yunmo Chen,
   [PExA: Parallel Exploration Agent for Complex Text-to-SQL](https://arxiv.org/abs/2604.22934),
   arXiv:2604.22934, 2026.

[13] Datalevin project,
   [Join Order Benchmark](https://github.com/datalevin/datalevin/tree/master/benchmarks/JOB-bench),
   benchmark writeup and implementation.

[14] Donald D. Chamberlin and Raymond F. Boyce,
   [SEQUEL: A Structured English Query Language](https://doi.org/10.1145/800296.811515),
   *Proceedings of the 1974 ACM SIGFIDET Workshop on Data Description,
   Access and Control*, 1974, pp. 249-264.
