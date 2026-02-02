Preface

Modern applications increasingly demand more from their data systems than any
single traditional paradigm can comfortably provide. Transactional integrity,
rich relationships, flexible documents, full-text search, semantic retrieval,
and long-lived application state are now expected to coexist within the same
system. Historically, these needs have been addressed by composing multiple
specialized databases: relational systems such as PostgreSQL for structured
data, document stores like MongoDB for schema flexibility, graph databases such
as Neo4j for relationships, and separate search or vector engines for retrieval.
While powerful, this approach introduces architectural complexity, impedance
mismatches between models, and significant operational overhead.

In response, a newer class of systems has emerged that markets itself as
multi-paradigm, aiming to unify several data models under one roof. Platforms
such as ArangoDB exemplify this direction by supporting key–value, document, and
graph access within a single engine. These systems represent an important step
forward: they reduce the number of moving parts and make it easier to combine
different access patterns. Yet most multi-paradigm databases still center on
data models as their primary abstraction. They offer multiple ways to store and
retrieve data, but reasoning, inference, and long-term state evolution typically
remain outside the core of the system.

Datalevin takes a different approach. Rather than starting from tables,
documents, or graphs, Datalevin is built around logic and persistence. At its
foundation lies a high-performance key–value store, exposed directly for
low-level use. On top of this substrate, Datalevin provides Datalog for
relational and graph queries, rules for deriving new knowledge, and a family of
auxiliary indexes—including full-text search, vector similarity, and automatic
document indexing—that can be composed within a single query workflow. These
capabilities are not bolted on as external services; they are integrated into a
unified execution and storage model. The result is not merely a database that
supports multiple paradigms, but a system that treats knowledge, relationships,
and search as first-class citizens.

This architectural choice becomes especially important in the era of intelligent
applications. Large language models and autonomous agents require more than fast
lookups: they require persistent memory, evolving context, and the ability to
integrate new observations into an existing internal model over time. Datalevin
is designed to serve as this substrate. By combining key–value storage, logical
querying, graph traversal, full-text and vector search, and flexible deployment
modes (embedded, client/server, and lightweight pods), Datalevin supports a new
class of systems in which databases are not just passive repositories, but
active participants in reasoning and collaboration.

Datalevin: The Definitive Guide to Logical and Intelligent Databases is written
for practitioners who want to go beyond isolated data models and toward unified,
long-lived systems. You will learn how Datalevin can be used as a fast embedded
store, a networked database, or an application-integrated component; how to
model data relationally, graphically, and document-style within the same engine;
how to leverage full-text and vector indexes alongside Datalog; and how these
pieces come together to enable persistent agent memory and human–agent
collaboration. Our goal is not only to teach you how to use Datalevin, but to
introduce a different way of thinking about databases: as logical, composable,
and intelligent foundations for modern software.
