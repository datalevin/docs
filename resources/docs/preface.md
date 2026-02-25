---
title: Preface
chapter: 0
---

# Preface

Modern applications demand more from data systems than any single traditional
paradigm can easily provide. Transactional integrity, rich relationships,
flexible documents, full-text search, and semantic retrieval are now expected
to coexist. Historically, these needs were met by stitching together
specialized databases: relational systems for structured data, document stores
for flexibility, graph databases for relationships, and separate engines for
search and vectors. While powerful, this approach introduces architectural
complexity, impedance mismatches, and significant operational overhead.

In response, a newer class of systems has emerged that markets itself as
multi-paradigm, aiming to unify several data models under one roof. Platforms
such as ArangoDB exemplify this direction with a document-first model that also
supports graph and key-value-style access within a single engine. Relational
databases tackle the challenge by allowing extensions for other access patterns.
For example, PostgreSQL adds JSONB column for document use cases and pg-vec
extension for vector search. These evolutions represent an important step
forward: they reduce the number of moving parts and make it easier to combine
different access patterns.

However, most multi-paradigm databases or databases with extensions still
organize around traditional storage shape as the primary abstraction. They do
not build the database from the ground up for multi-paradigm data modeling. The
multiple data models are provided as add-ons, with reasoning, inference, and
long-term state evolution treated as external concerns.

![Datalevin unified data model](/images/unified.jpg)

Datalevin[^name-origin] starts from a different premise: the database is built
from the ground up to be multi-paradigm friendly. It achieves this flexibility by
modeling data as atomic facts first. In relational and document database systems,
an aggregate shape (a document, a row, or a column, with nested fields, columns, or
rows) is the primary unit of storage, update, and retrieval. In Datalevin's **triple
model**, the atomic unit is a fact of three elements: `entity`, `attribute`, and
`value`.

This fact-first approach is a first-principled choice. It keeps the core model
small and composable, and delays workload-specific optimization decisions until
they are justified by real needs. For example, rows or columns are just
different organizations of the same set of facts. Rows are `entity`-major sorted
facts, and columns are `attribute` and `value` sorted facts.

On top of this conceptual model, Datalevin provides a unified implementation
stack. Facts and indexes are persisted in a high-performance key-value engine,
while the same key-value layer is exposed directly when low-level access is
useful.

The triple model supports a powerful query language called **Datalog**. It can
declaratively express complex relational queries. In addition, query
clauses in Datalog can be grouped into named reusable components called
**rules**, which allow logical derivation of new facts and navigation in graphs.

```clojure
(require '[datalevin.core :as d])

;; Define a schema and open a database
(def schema {:person/name  {:db/unique :db.unique/identity}
             :person/follows {:db/valueType :db.type/ref
                              :db/cardinality :db.cardinality/many}})

(def conn (d/get-conn "/tmp/preface-demo" schema))

;; Transact some facts
(d/transact! conn
  [{:person/name "Alice" :person/follows [{:person/name "Bob"}]}
   {:person/name "Bob"   :person/follows [{:person/name "Carol"}]}
   {:person/name "Carol" :person/follows [{:person/name "Alice"}]}])

;; Query: who does Alice follow, and who do they follow?
(d/q '[:find ?name ?follows-name
       :where
       [?alice :person/name "Alice"]
       [?alice :person/follows ?friend]
       [?friend :person/name ?name]
       [?friend :person/follows ?fof]
       [?fof :person/name ?follows-name]]
     (d/db conn))
;; => #{["Bob" "Carol"]}
```

The triple model also allows rich secondary indices on attributes and their
values. For example, full-text search, vector similarity, and automatic document
indexing are all nicely integrated into the same transactional and query
workflow of Datalevin. Rather than bolting on external services, Datalevin
unifies these capabilities in one execution and storage model.

This architectural choice becomes especially important in the era of intelligent
applications. Large language models and autonomous agents require more than fast
lookups: they require persistent memory, evolving context, and the ability to
integrate new observations into an existing internal model over time. Datalevin
is designed to serve as this substrate. By combining key-value storage, logical
querying, graph traversal, full-text and vector search, and flexible deployment
modes (embedded, client/server, and lightweight pods), Datalevin supports a new
class of systems in which databases are not just passive repositories, but
active participants in reasoning and collaboration.

Datalevin: The Definitive Guide to Logical and Intelligent Databases is written
for practitioners who want to go beyond isolated data models and toward unified,
long-lived systems. You will learn how Datalevin can be used as a fast embedded
store, a networked database, or an application-integrated component; how to
model data relationally, graphically, and document-style within the same engine;
how to leverage full-text and vector indexes in Datalog; and how these
pieces come together to enable persistent agent memory and human-agent
collaboration.

## Who This Book Is For

This book is for software engineers, data engineers, and technical architects
building data-intensive systems. It is written for teams using Datalevin across
Clojure, Java, JavaScript, and Python APIs.

You should be comfortable with basic database concepts, such as transactions,
normalization, and query predicates. No prior Datalog experience is required.

## How to Use This Book

Read sequentially if you are new to Datalevin. If you already have experience,
you can follow a goal-based path:

- Application builders: Part I, Part II, then Part III and Part IV.
- Data modelers: Part I, Part III, then Part IV.
- Operators and platform engineers: Part I, Part II, then Part VI.
- AI system builders: Part I through Part IV, then Part VII.
- Contributors and internals-focused readers: Part I, Part II, then Part VIII.

Our goal is not only to teach you how to use Datalevin, but to introduce a
different way of thinking about databases: as logical, composable, and
intelligent foundations for modern software.

[^name-origin]: Datalevin builds on LMDB, an abbreviation of Lightning Memory-Mapped
    Database, a battle-tested key-value storage with exceptional read
    performance. "levin" is an old English word for lightning.
