---
title: "Why Datalevin"
chapter: 1
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 1: Why Datalevin

Modern applications often need several data capabilities at once: transactional
updates, relational joins, graph traversal, document-style flexibility, and
semantic retrieval. Teams frequently assemble these from multiple products, then
pay the operational cost in data pipelines, synchronization lag, and glue code.

The result is not just more infrastructure. It also changes how applications
are written: business logic starts to know which data lives in the relational
database, which data was copied into the search index, which fields were
duplicated into a document store, and which vectors need to be refreshed after
an update. This knowledge is not essential to the business domain; it is
incidental complexity. As more incidental complexity accumulates, the
application becomes harder to change, mired in the software engineering "tar
pit" that Frederick P. Brooks described [1].

Datalevin is designed as a single durable substrate for these mixed workloads.
It combines a high-performance key-value storage layer with a Datalog query
engine and integrated indexing and search capabilities, so you can model and
query data through one system instead of stitching several engines together.

The central idea is simple: keep facts, indexes, documents, search entries, and
retrieval metadata close enough that they can participate in the same
transactional and logical query model.

That model also gives application code a clean place to observe change.
Datalevin transaction reports and `listen!` callbacks let an embedded
application react to committed writes without polling or scattering change
detection across search, graph, document, and cache subsystems. Chapter 6 covers
that transaction-observation pattern after introducing transaction reports.

This chapter explains the problem Datalevin addresses, how the architecture
unifies multiple access patterns, and where it fits in a modern stack.

## 1. A Unifying Data Model

At the Datalog layer, Datalevin represents facts as EAV triples: Entity,
Attribute, and Value. This follows the same atom-of-statement idea used by RDF:
an RDF graph is defined as a set of subject-predicate-object triples, and
asserting one triple states that a relationship holds between its subject and
object, a single fact [6]. Datalevin uses entity-attribute-value rather than
subject-predicate-object, because Datalevin is an operational database and
chooses database-friendly terminology: entity is a fluid concept
(see below), not a concrete subject; attribute can have value types, cardinality,
uniqueness, indexing behavior, and transaction-time validation, while predicate
already means a boolean decision function in databases; the third position is
called a value, because it may be a scalar, a reference to another entity, a
tuple, a document value, or another supported Datalevin value type. Despite the
terminology differences, the modeling point is the same: each assertion is
stored as one small, independently queryable fact, hence called a **datom**
(data atom).

Modeling data in terms of datoms results in a data model that is compact,
sparse, and composable:

- **Entity (E):** the subject being described. In a Datalevin datom, this is
  represented as a system-assigned internal numeric entity id (eid), valid
  inside that database. With automatic eid, users no longer need to invent an ID
  field for each domain object.
- **Attribute (A):** the named property being asserted. Attributes are usually
  namespaced keywords such as `:user/email`, `:order/customer`, or
  `:doc/body`. The relationship between entity and attribute is arbitrary. There
  is no requirement that certain entities must have certain attributes, and vice
  versa. Therefore, the data model can be sparse: only true datoms are
  asserted in the database, and the false ones are simply missing.
- **Value (V):** the value of the property. A value may be a string, number,
  keyword, boolean, reference to another entity, timestamp, document value,
  vector, or another supported Datalevin value type.

When surfaced in query functions, datoms are commonly represented as `[e a v]`.
For example, the fact that entity `101` has Alice's email address is the datom
`[101 :user/email "alice@example.com"]`.

This tiny shape is the first new concept to internalize. A datom is smaller
than a row and flatter than a document, but it can express both. A group of
datoms that share the same entity id can look like a row. A graph edge can be a
datom whose value is another entity id. A document-like aggregate can be built
from ordinary facts, or stored as an indexed document value when that is the
better fit. The uniform shape makes these choices composable instead of
isolated.

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
values, often with `:db.unique/identity` property (see Chapter 3 for detailed
explanations).

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

Datalevin is schema-on-write. Schema is optional, so you can start with a small
model and add structure as the application becomes clearer. When schema is
declared, Datalevin checks and uses it when data is written. This is different
from treating all data as untyped blobs: declared attributes can participate in
type checking, uniqueness, references, full-text indexing, vector search, and
other database behaviors.

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
through `idoc` indexing (Chapter 14). In other words, documents are
supported, but facts are foundational.

Another way to see this: Datalevin treats aggregate shapes as derived views,
not the foundational truth. This preserves modeling flexibility and delays
workload-specific optimization decisions.

You can still return an entity as a map, pull a nested shape for an API
response, or index a JSON-like document for path queries. The difference is that
these shapes are not the only way to see the data. The same facts can also be
joined relationally, traversed as a graph, searched by text, or combined with
retrieval constraints.

Compared with rigid table layouts, EAV handles sparse and evolving data
naturally because missing values are simply omitted, not stored as placeholder
nulls.

If only some users have a `:user/timezone`, only those users need that datom.
If a later feature adds `:user/preferred-model`, existing entities do not need
to be rewritten. The model grows by adding attributes and facts.

The layer boundary is important: triples are the conceptual model; key-value is
the physical storage implementation. Datalevin stores facts and indexes in its
DLMDB key-value substrate, and also exposes that substrate as a low-level API.
DLMDB is Datalevin's extension of LMDB, the memory-mapped key-value store
covered in Chapter 4.

You normally reason about the logical facts. The engine handles how those facts
are encoded into sorted key-value indexes for efficient reads and writes.

## 2. One Engine, Multiple Access Patterns

Datalevin exposes several complementary capabilities in one runtime. Each
capability can be useful by itself, but the more important strength is that they
compose over the same database state. As illustrated in Figure 1.1, Datalevin
uses a single unified data model to handle different database capabilities. This
section gives an overview, while the rest of the book elaborates all the details.

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

Datalevin uses an EDN (see Appendix B) format of Datalog
pioneered by Datomic. This is friendlier to application developers as well as to
programmatic construction than the older Prolog-like notation because queries
are ordinary data structures: vectors, keywords, symbols, and lists. The
underlying idea is still classic Datalog: describe the facts that must be true,
and let the engine find bindings for the variables [7].

A small comparison makes the difference concrete:

Classic Datalog literature writes Datalog in Prolog syntax:

```prolog
friend_of_friend(X, Z) :- follows(X, Y), follows(Y, Z).
```

The logical expression is concise and elegant, but less familiar to application
developers who are not versed in first-order predicate logic. For example, it
would be easy to miss the precise meaning of `:-`, `,`, or `.`. The functors and
arguments seem to be arbitrary, and everything seems to be abstract.

Datalevin writes the same Datalog as EDN data:

```clojure
[:find ?z
 :in $ ?x
 :where
 [?x :person/follows ?y]
 [?y :person/follows ?z]]
```

`:find`, `:in`, and `:where` are meaningful words for developers who are
familiar with the ideas of database queries. Variables all conveniently start
with `?`. `$` can be easily explained as representing the database. Attributes
are part of the schema. Everything is concrete.

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
directly as a durable key-value API for EDN values. EDN is a Clojure data format
similar to JSON but richer: keywords, symbols, sets, tagged literals. This
low-level API is useful for low-latency state access, caches, and simple
lookup-oriented components.

Use the key-value API when the access pattern really is just "given this key, read
or update this value" or "read this key range". Examples include local
application state, checkpoints, registries, counters, queues, or cached
results. In those cases, forcing everything through a logical query layer would
add complexity without adding much value.

Conceptually, the key-value layer is the physical foundation and a low level
direct access API, while the Datalog layer is the logical model for facts,
relationships, joins, and rules. Datalevin gives you both, so a system can keep
simple state simple while still using Datalog for the parts that benefit from
logical querying.

### Path-Indexed Documents with idoc

Datalevin supports path-indexed access to documents in EDN, JSON, and
Markdown formats using the `:db.type/idoc` data type (Chapter 14). You can query
nested structures with `idoc-match`, including logical combinators and path
predicates, while keeping the documents in the same transactional store.

This is useful because real applications rarely receive perfectly normalized
facts at the boundary. They receive JSON from APIs, EDN configuration, parsed
Markdown metadata, tool outputs, and model-produced structures whose shape may
change over time. An `idoc` lets you keep such nested data as a document while
still indexing paths inside it.

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

Datalevin includes built-in full-text search, user-supplied vector similarity
search, and text embedding search over datoms that have string values. Because
these indexes live in the same database system, you can combine retrieval and
logic filtering in one Datalog query instead of synchronizing external search
services.

These search modes answer different questions:

- Full-text search is lexical. It is good for matching words, terms, ngrams, and
  phrases. Datalevin supports a variety of text analyzers and boolean search
  expressions. Details are in Chapter 16.
- Vector search is geometric. It is good for finding items whose vector
  embeddings are near a query vector under a similarity metric. See Chapter 17.
- Embedding converts text to vectors. It is useful when the application
  wants semantic retrieval over text fields instead of exact word matching.
  Datalevin provides built-in embedding services so users do not have to roll
  their own. It can also leverage third-party embedding services.

In many systems, these capabilities live outside the primary database. The
application first asks a search engine for candidate ids, then asks the
database for structured facts, then performs more filtering in application
code. Datalevin's integrated approach lets lexical, semantic, document-path,
and logical constraints meet inside one query.

By integrating different capabilities in one Datalog system, users can choose
the access pattern that fits the problem without leaving the same Datalog model.
When a task needs specific retrieval shape and exact constraints, composition is
readily available in Datalog.

## 3. Why Not Just SQL?

A possible objection to a Datalog system is that modern SQL databases keep
adding capabilities: JSON columns, full-text search, graph extensions, vector
indexes, procedural languages, recursive CTEs, and extension ecosystems. For
example, if PostgreSQL can do more and more of these jobs, why not stay with
familiar SQL?

There are two reasons that are deeper than cosmetics in syntax differences. The
first is blunt: SQL is not a good query language for programs. It is a large
structured-English language designed around tables, joins, projections,
grouping, subqueries, and dialect-specific extensions. It succeeded as a
standard, but standardization should not be confused with language quality. SQL
queries are usually strings assembled by a host language, and complex SQL often
forces programmers to think in terms of table aliases, join syntax, and
container-specific mechanics before they can express the domain relationship
they actually mean.

The ORM and query-builder ecosystem is strong evidence that SQL is a bad
application programming interface. A whole industry exists to paper over SQL as
string construction, weak host-language composition, dialect differences, type
mismatches between query results and application values, table and row
containers that do not match object graphs, and boilerplate for joins, identity,
transactions, lazy loading, and migrations. Even tools that reject classic ORM
behavior, such as query builders, LINQ-style systems, jOOQ, Ecto, and Prisma,
are still trying to make SQL less like raw SQL and more like host-language data
or typed expressions.

Datalog gives the programmer a smaller and more uniform surface. A query is
data, not a string. The query is truly declarative: clauses say which facts must
be true. Joins arise from shared variables through unification. Rules name
reusable logic. Recursive queries use the same logical form as non-recursive
queries. The result is not less relational; it is a higher-level way to write
relational logic. Relational algebra remains the substrate, but the user does
not have to speak in join operators to ask a relational question [2].

The second reason is deeper still, as it is about facilities for effective query
implementation, specifically, query planning. SQL databases are table-first
systems: data is bundled into row or column containers, and the optimizer must
infer how many rows will survive predicates and joins over those containers.
Real application data is skewed and correlated. Traditional estimators therefore
depend on approximations such as histograms and assumptions such as uniformity
or independence. The database literature has repeatedly identified cardinality
estimation as one of the central hard problems in query optimization [3] [4].
For container-based storage, there is no cheap general answer: the counts the
planner needs are not directly present in the layout, so the optimizer has to
approximate them.

A triplestore changes the shape of the problem. In Datalevin, a datom is an
explicit cell: entity, attribute, value. Missing facts are absent rather than
stored as nullable positions. Attributes are globally scoped. EAV
(Entity-Attribute-Value) and AVE (Attribute-Value-Entity) indexes make many
counts direct, and larger estimates can be sampled under the same query
conditions that execution will use. Datalevin still has to optimize queries,
but it starts from a storage model that exposes the units the optimizer needs to
count [5].

This is why adding more features to a SQL database does not fully answer the
question that a modern database is presented with. That is, a modern database
needs not only to be full of features, but also to be simple in concept, fast in
performance, and easy to operate. For many legacy systems
that are already heavily invested in SQL ecosystems, a mature SQL database
remains the right choice. But feature accumulation does not fix SQL as a query
language, and it does not remove the cardinality estimation burden created by
table-shaped containers.

There is also an integration difference. SQL databases can add search, vector,
document, and graph features, but those features often arrive as separate
extension surfaces with their own indexes, functions, limitations, and planner
interactions. Datalevin makes these capabilities joinable through one logical
query model: a full-text hit, an idoc path match, a vector neighbor, and an
ordinary fact can all bind the same Datalog variable. Likewise, SQL schemas are
centered on tables, while Datalevin schema is centered on attributes. That
matters for sparse, evolving, cross-domain data: an attribute can carry its own
type, uniqueness, cardinality, reference semantics, full-text behavior, or
embedding behavior wherever that fact appears.

Datalevin's argument is that a fact-first model, paired with Datalog, is a
better foundation for applications whose data is relational, graph-shaped,
sparse, evolving, searchable, and increasingly retrieval-driven.

A concrete way to see this argument is through Join Order Benchmark (JOB) [3].
JOB is useful here because it is not a benchmark of simple key lookups or
single-table scans, nor some academic exercise with synthetic data. JOB is based
on the real IMDb dataset, and its queries stress the query optimizer because the
data distribution is highly skewed and correlated, as it is in all real-world
datasets. It requires joins across many related entities: 113 analytical queries, with
query shapes that range from a few joins to more than a dozen. Its queries have
enough joins that a poor query plan can produce orders of magnitude more
intermediate rows than a good one.

It should be noted that this kind of query is not exotic either. It appears
whenever an application keeps data normalized and asks compositional questions:
which documents can this user see, which orders match a constraint spanning
products, vendors, shipments, refunds, and customers, which clinical cases
satisfy rules spanning diagnoses, providers, prescriptions, and coverage, or
which papers connect authors, affiliations, topics, institutions, and
collaborators. If a system never seems to have such queries, that is because the
complexity has been moved into denormalized records, application-side filtering,
or cache materialization rather than disappearing. That is to say, the lack of
complex queries is a symptom of weak database capability, not a lack of demand
for them. Developers have to deal with the complexities in application code
because the database they use cannot handle them effectively.

Let us start with a concrete example. A Datalevin query for a production
question might look like this:

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
rows survive each predicate and each join, because the fact counts it needs are
bundled inside rows, indexes, histograms, and assumptions about value
independence. When the estimates are wrong, the optimizer can start from a broad
table, build huge intermediate results, and only later discover that a different
predicate would have narrowed the search immediately. It is therefore quite
unpredictable if a SQL query engine would perform well or not for complex
queries. Due to this kind of uncertainty, large join queries are widely treated
as optimizer-sensitive. For example, PostgreSQL's own documentation [8] notes
that possible join orders grow exponentially, that exhaustive planning becomes
impractical beyond roughly ten input tables, and that the planner switches to
heuristic search for many-relation queries. It also documents techniques for
constraining join order to reduce planning time. That is the practical pressure
behind advice to denormalize, split a query, materialize intermediate results,
or hand-guide the planner when SQL joins become too large or too sensitive to
cardinality estimation, the work of estimating the result size of all sub-plans
of a query [9].

Fact-based storage changes that problem. Datalevin does not have to answer every
selectivity question from table containers. Its EAV and AVE indexes expose
facts directly, and DLMDB's counted ranges and samples give the optimizer
evidence about how many datoms match a bound entity, attribute, value, or range.
The optimizer still has real work to do, but it starts from storage that matches
the logical units in the query. That is why an optimizing Datalog engine has
advantages: it can plan the complex question as one query without making the
application hard-code a join order, denormalize the model, or split the question
into many smaller queries.

## 4. Developer Ergonomics and Deployment Modes

Datalevin is designed to run where your application runs:

- **Embedded mode** for local, process-level access. This is the simplest mode:
  the application links to Datalevin and reads or writes a local database
  directly. Right now, four programming languages, Java, Clojure, Python, and
  Node.js are supported.
- **Client/server mode** for shared deployment and centralized operations. This
  is useful when multiple processes or machines should access the same service.
  The client can be written in the same four programming languages.
- **Non-HA async read-only replicas** for simple read scaling on the server. Replicas can
  serve reads where eventual freshness is acceptable and automatic failover is
  not required.
- **Consensus-lease HA** for automatic leader promotion. This mode is intended
  for server deployments that need higher availability without changing the
  application data model.
- **Babashka pod mode** for scriptable workflows, automation, and lightweight
  command-line tools. Babashka is a fast-starting Clojure scripting runtime.
- **MCP server mode** for local AI tool integration, where Datalevin can expose
  persistent state and retrieval capabilities to agentic applications. MCP is
  the Model Context Protocol, a standard interface for connecting tools and
  data sources to AI clients.

This flexibility lets teams start small and evolve deployment architecture
without changing core data and query concepts.

The same schema, datoms, Datalog queries, idoc values, and search indexes remain
recognizable as you move from a local prototype to a server deployment or an AI
tool integration. Operational shape can change later; the conceptual model does
not have to be redesigned each time.

## 5. Where Datalevin Fits

Datalevin is a strong fit when you want one operational database system to
support:

- application state with ACID transactions (atomicity, consistency, isolation,
  and durability),
- relational and graph-style query patterns,
- document-style payloads with path indexing,
- retrieval pipelines that combine full-text, vector similarity, and logic
  constraints.

This combination appears in more places than it may first seem. A SaaS product
may need accounts, permissions, audit events, search, and recommendation
features. A developer tool may need package dependency graphs, documentation
search, local state, and structured metadata. An AI application may need
episodic memory, semantic facts, vector recall, source documents, and strict
filters that prevent irrelevant context from entering a prompt.

Datalevin is especially attractive when the boundaries between these needs are
porous. If a search result must be filtered by permissions, joined to ownership
metadata, checked against a lifecycle state, and then ranked with semantic
signals, keeping those facts in one engine simplifies the application.

Another advantage is the ability to absorb change. Datalevin is
schema-on-write: you can start with a small schema, add structure where the
application has learned enough to justify it, and keep optional facts absent
rather than storing placeholder nulls. Because facts are isolated datoms and
attributes are additive, new features can add new attributes without rewriting
old entities or forcing every record through a table migration. Stable domain
identifiers, lookup refs, and unique attributes let the application keep its
own identities while Datalevin manages database-local entity ids. This makes the
model well suited to systems whose shape changes as the product, data, or AI
workflow matures.

Due to the advantages in handling complex queries that are common in today's
practical applications, greenfield projects and mature systems looking to
modernize data access layers should seriously consider a Datalog-based system
like Datalevin.

If your workload is narrowly specialized, a single-purpose engine may still be
the better choice. Datalevin is most compelling when you need these
capabilities together. For example, a pure analytical warehouse, a massive
append-only log pipeline, or a dedicated search cluster may be better served by
systems built only for those jobs.

Datalevin's niche is the application database that needs to be
logical, graph-aware, document-friendly, and retrieval-capable, especially as
user requirements grow in complexity and composition while the application
matures.

## 6. A Minimal Unified Query Example

Suppose you are building a documentation assistant. You want to find English
documents that mention a term in free text, while also requiring a structured
module status inside nested document metadata.

The example below combines full-text retrieval, idoc filtering, and metadata
constraints in one query:

Do not worry if every detail of the syntax is not familiar yet. The main thing
to notice is that the same variable, `?e`, flows through all three constraints.
The full-text index proposes entities, the idoc index keeps entities with a
matching nested document shape, and the Datalog clause checks an exact language
fact.

<div class="multi-lang">

<!-- pdf-listing: Minimal unified query across full-text, idoc, and Datalog -->

```clojure
(require '[datalevin.core :as d])

;; Datalevin is schema-on-write, so schema is optional, but strongly recommended
(def schema
  {:doc/body {:db/valueType :db.type/string
              :db/fulltext  true}
   :doc/lang {:db/valueType :db.type/string}
   :doc/idoc {:db/valueType :db.type/idoc}})

;; Obtain a DB connection. DB will be at data/ch1 directory
(def conn (d/get-conn "data/ch1" schema))

;; Transact an entity
(d/transact! conn
  [{:db/id    -1
    :doc/lang "en"
    :doc/body "Datalevin adds idoc indexing and vector search."
    :doc/idoc {:module {:name "search" :status "stable"}}}])

;; Query
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

// Datalevin is schema-on-write, so schema is optional, but strongly recommended
Schema schema = Datalevin.schema()
    .attr("doc/body",
          Schema.attribute()
              .valueType(Schema.ValueType.STRING)
              .fulltext(true))
    .attr("doc/lang",
          Schema.attribute().valueType(Schema.ValueType.STRING))
    .attr("doc/idoc",
          Schema.attribute().valueType(Schema.ValueType.IDOC));

// Obtain a DB connection. DB will be at data/ch1 directory
Connection conn = Datalevin.getConn("data/ch1", schema);

// Transact an entity
conn.transact(Datalevin.tx()
    .entity(Tx.entity(-1)
        .put("doc/lang", "en")
        .put("doc/body", "Datalevin adds idoc indexing and vector search.")
        .put("doc/idoc",
             Datalevin.mapOf("module",
                 Datalevin.mapOf("name", "search", "status", "stable")))));

// Query
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

# Datalevin is schema-on-write, so schema is optional, but strongly recommended
schema = {
    ":doc/body": {":db/valueType": ":db.type/string",
                  ":db/fulltext": True},
    ":doc/lang": {":db/valueType": ":db.type/string"},
    ":doc/idoc": {":db/valueType": ":db.type/idoc"}}

# Obtain a DB connection. DB will be at data/ch1 directory.
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

// Datalevin is schema-on-write, so schema is optional, but strongly recommended
const schema = {
  ":doc/body": { ":db/valueType": ":db.type/string",
                 ":db/fulltext": true },
  ":doc/lang": { ":db/valueType": ":db.type/string" },
  ":doc/idoc": { ":db/valueType": ":db.type/idoc" }
};

// Obtain a DB connection. DB will be at data/ch1 directory.
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
2. `idoc-match` narrows candidates to documents whose nested idoc metadata has
   `{:module {:status "stable"}}`.
3. The Datalog clause `[?e :doc/lang "en"]` applies an exact metadata constraint.

The key point here is composition: lexical retrieval,
structure-aware filtering, and exact logical predicates are executed in one
query context, over one database state.

This example also shows the architectural core: the triple model links all
capabilities together, so full-text matches, idoc constraints, and relational
facts can be joined in one logical query.

In a split architecture, this often requires multiple systems and intermediate
joins. Here it is a single declarative query.

## Summary

Datalevin is built for workloads that cross boundaries between key-value
storage, logical querying, graph relationships, document modeling, and semantic
retrieval. Its core move is to treat facts as the shared substrate and indexes
as composable capabilities over that substrate.

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
