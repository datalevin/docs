---
title: "Hybrid Queries"
chapter: 18
part: "IV — Indexes as Capabilities"
---

# Chapter 18: Hybrid Queries

The true power of Datalevin lies not in any single index, but in how they all
work together. Because Datalog, full-text search, vector similarity, text
embedding search, document indexes, and the KV store can all live in the same
storage system, you can combine them in a single query.

By default, full-text, vector, embedding, and idoc indexes are updated
synchronously with source datoms. Full-text, vector, and embedding domains can
opt into `:indexing-mode :async`; in that case source datoms commit with durable
index jobs and index queries are eventually consistent until the worker catches
up.

In Datalevin, **indexes are programmable capabilities**. This chapter
demonstrates how to build hybrid queries that bridge structured logic and
unstructured search.


## 1. Unified Retrieval: The Single-Engine Advantage

In a multi-database architecture (e.g., Postgres + Elasticsearch + Pinecone), a
hybrid query requires fan-out to multiple services, followed by manual result
merging in application code. This is slow, complex, and leads to consistency
issues.

In Datalevin, a hybrid query is a single Datalog expression. The engine manages
joins between different index types automatically.

This single-engine model has consequences beyond convenience:

- **Consistency.** Every index reads from the same transactional snapshot. In
  the default synchronous mode, a hybrid query can never match a document that
  full-text search knows about but the vector index has not yet learned, because
  both were updated inside the transaction that created the datom. There is no
  cross-store version skew to reason about.
- **No dual writes.** A single transaction updates the datoms and every
  secondary index together. You do not run change-data-capture pipelines or
  reconciliation jobs to keep a separate search cluster and vector store in step
  with the system of record.
- **No fan-out.** Results are joined in process over memory-mapped data, not
  serialized across the network to several services and merged afterward. The
  join is a set intersection on entity ids rather than an application-level merge.
- **One operational surface.** One file to back up and restore, one
  access-control boundary, and one failure domain, instead of three systems that
  can fail or drift independently.

Clojure snippets below use an explicit `db` value. Java, Python, and JavaScript
snippets assume an open connection named `conn`, whose `query` method supplies
the current database as `$`.


## 2. Index Return Formats

All specialized index functions return datoms for consistent destructuring. By
default, they all return `[e a v]`. Different options return additional values.

| Function | Score/Distance Option |
|------------------|----------------|
| `fulltext` | `:refs+scores` returns `[e a v score]` |
| `vec-neighbors` | `:refs+dists` returns `[e a v dist]` |
| `embedding-neighbors` | `:refs+dists` returns `[e a v dist]` |
| `idoc-match` | — |


The shared entity id (`?e`) enables seamless joins across all index types, as
depicted in Figure 18.1.

![Hybrid retrieval: full-text, vector, document, and Datalog lenses each return datoms keyed by ?e, which the engine joins by intersecting entity-id sets](/images/diagrams/hybrid-retrieval.svg)

The implication is that the entity id is a *universal join key*. In a multi-store
architecture, each system returns identifiers in its own space — Lucene document
ids, vector-store ids, SQL primary keys — and the application maintains
translation tables to line them up. In Datalevin, every index returns datoms
over the same entity-id space, so joining a full-text hit to a vector hit to a
graph fact is just unification on `?e`. A practical consequence is that adding a
new index type to an existing model, such as embeddings over text you already
store, immediately composes with existing facts; there is no id-mapping layer to
build first.

Full-text scores and vector distances are different quantities. A full-text
score is a relevance score where higher is better. A vector distance is a
metric distance where lower is usually closer. They are not on the same scale,
so production code should not combine the raw numbers directly. Prefer a
rank-based method such as reciprocal rank fusion (RRF), discussed next, or keep
one index as the candidate generator and use the other as a filter.


## 3. Evidence Fusion

When a query combines full-text search and vector similarity, there are two
different problems you might be solving.

The first problem is **intersection**: find entities that satisfy every
retrieval condition. A single Datalog query with shared `?e` variables does
exactly that. If a full-text clause and a vector clause both bind `?e`, the
result contains entities that appear in both candidate sets.

The second problem is **fusion**: accept candidates from several ranked lists
and combine their ranks into one final ranking. This is useful when either
keyword evidence or semantic evidence can be enough to make an item relevant.
Reciprocal rank fusion (RRF) is a simple, robust way to do this without
pretending that full-text scores and vector distances share a numeric scale.

### 3.1 Reciprocal Rank Fusion

RRF fuses ranked lists by rank position. A common formula is
`1.0 / (60 + rank)`, where `rank` is 1 for the first item, 2 for the second
item, and so on. The constant `60` dampens the effect of any one list. The same
entity can appear in the full-text list, vector list, and graph list; add its
reciprocal-rank contributions from each list, then sort by the fused score.

The practical pattern is:

1. Run each retrieval lens with enough `:top` headroom: full-text, vector,
   embedding, idoc, or graph expansion.
2. Convert each result list to `(entity-id, rank, source)` records.
3. Sum `1 / (60 + rank)` per entity id.
4. Sort by the fused score.
5. Hydrate or filter the fused entity ids with ordinary Datalog facts:
   permissions, recency, status, ownership, and business rules.

Use the same entity id as the fusion key. This is where Datalevin's return
format matters: every index returns datoms in the same entity-id space, so there
is no external id-mapping table to maintain.

<div class="multi-lang">

```clojure
(defn rrf-score [rank]
  (/ 1.0 (+ 60 rank)))

(defn ranked [source hits]
  (map-indexed
    (fn [idx hit]
      (assoc hit
             :source source
             :rank   (inc idx)
             :rrf    (rrf-score (inc idx))))
    hits))

(defn rrf-fuse [& source->hits]
  (->> source->hits
       (mapcat (fn [[source hits]] (ranked source hits)))
       (group-by :e)
       (map (fn [[e hits]]
              {:e         e
               :rrf-score (reduce + (map :rrf hits))
               :sources   (set (map :source hits))}))
       (sort-by :rrf-score >)))

(rrf-fuse
  [:text   [{:e 10} {:e 20} {:e 30}]]
  [:vector [{:e 20} {:e 40} {:e 10}]])
```

```java
static double rrfScore(int rank) {
    return 1.0 / (60.0 + rank);
}

Map<Object, Double> scores = new HashMap<>();

BiConsumer<String, List<Map<String, Object>>> addList =
    (source, hits) -> {
        for (int i = 0; i < hits.size(); i++) {
            Object e = hits.get(i).get("e");
            scores.merge(e, rrfScore(i + 1), Double::sum);
        }
    };

addList.accept("text", textHits);
addList.accept("vector", vectorHits);

List<Object> fused =
    scores.entrySet().stream()
        .sorted(Map.Entry.<Object, Double>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
```

```python
def rrf_score(rank):
    return 1.0 / (60 + rank)


def add_list(scores, hits):
    for index, hit in enumerate(hits, start=1):
        e = hit["e"]
        scores[e] = scores.get(e, 0.0) + rrf_score(index)


scores = {}
add_list(scores, text_hits)
add_list(scores, vector_hits)

fused = [
    e for e, score in sorted(
        scores.items(),
        key=lambda item: item[1],
        reverse=True,
    )
]
```

```javascript
function rrfScore(rank) {
  return 1.0 / (60 + rank);
}

function addList(scores, hits) {
  hits.forEach((hit, index) => {
    const e = hit.e;
    scores.set(e, (scores.get(e) ?? 0) + rrfScore(index + 1));
  });
}

const scores = new Map();
addList(scores, textHits);
addList(scores, vectorHits);

const fused = [...scores.entries()]
  .sort((a, b) => b[1] - a[1])
  .map(([e]) => e);
```

</div>

### 3.2 Datalog-Native Evidence Fusion

The application-side RRF example above is useful when you want literal
rank-position fusion over lists returned to application code. There is also a
fully Datalog-native alternative: model each retrieval lens as evidence, assign
each kind of evidence a weight, and aggregate those weights inside the query.

This is not exactly RRF unless rank positions are already represented as data.
Datalog relations are unordered, so a query does not derive "the third result in
the vector list" as a rank fact by itself. But for many retrieval pipelines,
weighted evidence fusion is the right in-query solution: keyword evidence,
semantic evidence, tag evidence, permissions, and business rules all remain in
one Datalog query.

<div class="multi-lang">

```clojure
(def fusion-rules
  '[[(evidence ?e ?weight ?source ?query ?query-vec ?query-tags)
     [(fulltext $ :doc/content ?query {:top 50}) [[?e _ _]]]
     [(ground 1.0) ?weight]
     [(ground :text) ?source]
     [(ground ?query-vec) ?query-vec]
     [(ground ?query-tags) ?query-tags]]

    [(evidence ?e ?weight ?source ?query ?query-vec ?query-tags)
     [(vec-neighbors $ :doc/embedding ?query-vec {:top 50}) [[?e _ _]]]
     [(ground 1.0) ?weight]
     [(ground :vector) ?source]
     [(ground ?query) ?query]
     [(ground ?query-tags) ?query-tags]]

    [(evidence ?e ?weight ?source ?query ?query-vec ?query-tags)
     [?e :doc/tag ?tag]
     [(contains? ?query-tags ?tag)]
     [(ground 0.25) ?weight]
     [(ground :tag) ?source]
     [(ground ?query) ?query]
     [(ground ?query-vec) ?query-vec]]])

(d/q '[:find ?e ?title (sum ?weight)
       :with ?source
       :in $ % ?query ?query-vec ?query-tags ?allowed-owner-set
       :where
       (evidence ?e ?weight ?source ?query ?query-vec ?query-tags)
       [?e :doc/title ?title]
       [?e :doc/owner ?owner]
       [(contains? ?allowed-owner-set ?owner)]
       :order-by [2 :desc 1 :asc]]
     db
     fusion-rules
     user-query
     query-embedding
     query-tags
     allowed-owner-set)
```

```java
Object fusionRules = Datalevin.edn("["
    + "[(evidence ?e ?weight ?source ?query ?query-vec ?query-tags) "
    + " [(fulltext $ :doc/content ?query {:top 50}) [[?e _ _]]] "
    + " [(ground 1.0) ?weight] "
    + " [(ground :text) ?source] "
    + " [(ground ?query-vec) ?query-vec] "
    + " [(ground ?query-tags) ?query-tags]] "
    + "[(evidence ?e ?weight ?source ?query ?query-vec ?query-tags) "
    + " [(vec-neighbors $ :doc/embedding ?query-vec {:top 50}) [[?e _ _]]] "
    + " [(ground 1.0) ?weight] "
    + " [(ground :vector) ?source] "
    + " [(ground ?query) ?query] "
    + " [(ground ?query-tags) ?query-tags]] "
    + "[(evidence ?e ?weight ?source ?query ?query-vec ?query-tags) "
    + " [?e :doc/tag ?tag] "
    + " [(contains? ?query-tags ?tag)] "
    + " [(ground 0.25) ?weight] "
    + " [(ground :tag) ?source] "
    + " [(ground ?query) ?query] "
    + " [(ground ?query-vec) ?query-vec]]]");

conn.query("[:find ?e ?title (sum ?weight) " +
           " :with ?source " +
           " :in $ % ?query ?query-vec ?query-tags ?allowed-owner-set " +
           " :where " +
           " (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) " +
           " [?e :doc/title ?title] " +
           " [?e :doc/owner ?owner] " +
           " [(contains? ?allowed-owner-set ?owner)] " +
           " :order-by [2 :desc 1 :asc]]",
           fusionRules,
           userQuery,
           queryEmbedding,
           queryTags,
           allowedOwnerSet);
```

```python
fusion_rules = interop().read_edn(
    '[[ (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) '
    '   [(fulltext $ :doc/content ?query {:top 50}) [[?e _ _]]] '
    '   [(ground 1.0) ?weight] '
    '   [(ground :text) ?source] '
    '   [(ground ?query-vec) ?query-vec] '
    '   [(ground ?query-tags) ?query-tags]] '
    ' [ (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) '
    '   [(vec-neighbors $ :doc/embedding ?query-vec {:top 50}) [[?e _ _]]] '
    '   [(ground 1.0) ?weight] '
    '   [(ground :vector) ?source] '
    '   [(ground ?query) ?query] '
    '   [(ground ?query-tags) ?query-tags]] '
    ' [ (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) '
    '   [?e :doc/tag ?tag] '
    '   [(contains? ?query-tags ?tag)] '
    '   [(ground 0.25) ?weight] '
    '   [(ground :tag) ?source] '
    '   [(ground ?query) ?query] '
    '   [(ground ?query-vec) ?query-vec]]]'
)

conn.query(
    '[:find ?e ?title (sum ?weight) '
    ' :with ?source '
    ' :in $ % ?query ?query-vec ?query-tags ?allowed-owner-set '
    ' :where '
    ' (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) '
    ' [?e :doc/title ?title] '
    ' [?e :doc/owner ?owner] '
    ' [(contains? ?allowed-owner-set ?owner)] '
    ' :order-by [2 :desc 1 :asc]]',
    fusion_rules,
    user_query,
    query_embedding,
    query_tags,
    allowed_owner_set,
)
```

```javascript
const fusionRules = await interop().readEdn(
  '[[ (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) ' +
  '   [(fulltext $ :doc/content ?query {:top 50}) [[?e _ _]]] ' +
  '   [(ground 1.0) ?weight] ' +
  '   [(ground :text) ?source] ' +
  '   [(ground ?query-vec) ?query-vec] ' +
  '   [(ground ?query-tags) ?query-tags]] ' +
  ' [ (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) ' +
  '   [(vec-neighbors $ :doc/embedding ?query-vec {:top 50}) [[?e _ _]]] ' +
  '   [(ground 1.0) ?weight] ' +
  '   [(ground :vector) ?source] ' +
  '   [(ground ?query) ?query] ' +
  '   [(ground ?query-tags) ?query-tags]] ' +
  ' [ (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) ' +
  '   [?e :doc/tag ?tag] ' +
  '   [(contains? ?query-tags ?tag)] ' +
  '   [(ground 0.25) ?weight] ' +
  '   [(ground :tag) ?source] ' +
  '   [(ground ?query) ?query] ' +
  '   [(ground ?query-vec) ?query-vec]]]'
);

await conn.query(
  '[:find ?e ?title (sum ?weight) ' +
  ' :with ?source ' +
  ' :in $ % ?query ?query-vec ?query-tags ?allowed-owner-set ' +
  ' :where ' +
  ' (evidence ?e ?weight ?source ?query ?query-vec ?query-tags) ' +
  ' [?e :doc/title ?title] ' +
  ' [?e :doc/owner ?owner] ' +
  ' [(contains? ?allowed-owner-set ?owner)] ' +
  ' :order-by [2 :desc 1 :asc]]',
  fusionRules,
  userQuery,
  queryEmbedding,
  queryTags,
  allowedOwnerSet
);
```

</div>

The `:with ?source` clause is important. It keeps one row per evidence source
before aggregation, so text evidence and vector evidence for the same entity are
both counted even when they have the same numeric weight. The `:order-by` clause
uses result column `2`, the aggregate score, to rank entities by total evidence.

The repeated `ground` clauses in the rules are just pass-through bindings for
query inputs that a particular evidence source does not use. They keep all
`evidence` rule heads at the same arity, which makes the three rule definitions
act like one named union relation.

Literal rank-position RRF is usually an application-side fusion step unless
ranks have already been represented as data. Datalog-native weighted fusion is
the better fit when the fusion policy itself should be queryable data: rules
produce evidence, aggregation sums it, and strict filters remain ordinary
Datalog clauses. If a condition must be mandatory, keep it as a Datalog clause
rather than treating it as just another evidence source.


## 4. Order of Execution: How the Optimizer Thinks

When you mix different index types, the query optimizer uses cost-based analysis
for ordinary Datalog clauses and joins the result tuples produced by specialized
index functions.

A **candidate set** is the intermediate set of entities produced by one clause
or index function. Specialized functions such as `fulltext`, `vec-neighbors`,
`embedding-neighbors`, and `idoc-match` create candidate sets from their own
indexes. Ordinary Datalog clauses then join, filter, or extend those candidates.

- **Selective Retrieval First**: Keep `:top`, domains, and index-specific
  filters tight so specialized functions produce useful candidate sets.
- **Join Order**: All index results are sets of entity ids. The join is an
  intersection of these ids — an extremely fast operation in a memory-mapped
  architecture.

You usually do not need to reorder clauses manually. State constraints clearly,
then tune the candidate-producing options when a search/vector function returns
too much or too little.

The deeper point is to think in terms of selectivity. Let the specialized
function that produces the smallest, most relevant candidate set drive the
query, and let cheap Datalog clauses prune what remains. Because the join is an
id-set intersection, the cost is dominated by the size of those candidate sets,
not by the number of clauses. That also makes `:top` a recall-versus-cost knob:
set it too low and a genuine match can be dropped before the structured filters
ever see it; set it too high and downstream joins do needless work. When in
doubt, widen `:top` enough that the real answer is reliably in the candidate set,
then rely on the structured clauses to enforce correctness.


## 5. Mixing KV and Datalog

KV and Datalog storage do not have to live in separate files or separate
services. A Datalevin Datalog store is built on the same DLMDB substrate as the
key-value APIs, so application-owned KV data can live beside Datalog data in the
same database file. Use separate DBIs for those KV datasets when they need their
own key/value encoding, range access pattern, or lifecycle, while Datalog keeps
managing its own internal DBIs.

For absolute performance, combine raw key-value layer access with Datalog using
function bindings or descriptor-backed runtime UDFs. The example below assumes
the connection was opened with a UDF registry that resolves
`:kv/check-blacklist` to a small, deterministic function that reads the relevant
same-store KV DBI.

<div class="multi-lang">

```clojure
(def blacklist-fn
  {:udf/lang :app
   :udf/kind :query-fn
   :udf/id   :kv/check-blacklist})

(d/q '[:find ?e
       :in $ ?blacklist-fn
       :where [?e :user/id ?id]
              [(udf ?blacklist-fn ?id) ?blocked?]
              [(false? ?blocked?)]]
     db blacklist-fn)
```

```java
var blacklistFn = Datalevin.queryUdf("kv/check-blacklist")
    .lang("app")
    .build();

conn.query("[:find ?e " +
           " :in $ ?blacklist-fn " +
           " :where [?e :user/id ?id] " +
           "        [(udf ?blacklist-fn ?id) ?blocked?] " +
           "        [(false? ?blocked?)]]",
           blacklistFn);
```

```python
from datalevin import udf_descriptor

blacklist_fn = udf_descriptor(":kv/check-blacklist", lang=":app")

conn.query(
    '[:find ?e '
    ' :in $ ?blacklist-fn '
    ' :where [?e :user/id ?id] '
    '        [(udf ?blacklist-fn ?id) ?blocked?] '
    '        [(false? ?blocked?)]]',
    blacklist_fn,
)
```

```javascript
import { udfDescriptor } from "datalevin-node";

const blacklistFn = udfDescriptor(":kv/check-blacklist", { lang: ":app" });

await conn.query(
  '[:find ?e ' +
  ' :in $ ?blacklist-fn ' +
  ' :where [?e :user/id ?id] ' +
  '        [(udf ?blacklist-fn ?id) ?blocked?] ' +
  '        [(false? ?blocked?)]]',
  blacklistFn
);
```

</div>

The underlying KV layer is a first-class capability, allowing custom logic and
specialized indexes.

The example above uses a Datalog function binding through the portable `udf`
form: the function receives a value from the query and returns another value
that the query can bind. Clojure can also call ordinary Clojure functions
directly in embedded queries, but descriptor-backed UDFs are the portable shape
for Java, Python, JavaScript, server deployments, and code that should be
configured through an application registry. Register implementations with
`datalevin.udf/register!`, Java `UdfRegistry.queryFn`, Python
`registry.query_udf`, or JavaScript `registry.queryUdf`.

Keep query UDFs deterministic and cheap. If a function performs network calls,
writes state, or scans a large KV range, it can make query behavior surprising
and hard to optimize.

When a write must update Datalog datoms and application-owned KV DBIs atomically,
use the `with-transaction` pattern from Chapter 6. Function bindings are for
read-time lookup and filtering, not for writing during query evaluation.


## 6. Practical Pattern: Hybrid Retrieval with Structured Filters

The database pattern behind Retrieval-Augmented Generation (RAG) is the same
pattern this chapter has been building toward: use full-text and vector indexes
to produce candidates, then join those candidates with ordinary Datalog facts
for permission, recency, ownership, status, and business rules.

The important database point is that the filter should run in the same query as
retrieval. There is no window in which the system fetches candidates and then
forgets to filter them before handing context to another component. In a fan-out
architecture, filters often run after results are merged from several services,
which is exactly where access-control leaks and stale data slip in. In
Datalevin, an entity that fails a permission or policy clause is never part of
the result, no matter which index surfaced it.

Chapter 25 returns to this shape in the agent-memory setting. There, the problem
is no longer just candidate retrieval; it is recall and context assembly:
choosing the right lenses, ranking evidence, preserving provenance, and
formatting a small packet for an LLM. This chapter stays at the database level:
how to make heterogeneous indexes participate in one planned query.


## 7. End-to-End Example: The "Smart" Product Search

After the retrieval pieces are clear, a larger query can combine all of them.
This example finds products that:

1. Match the term "clojure" in the description (full-text)
2. Are semantically similar to a user's interest vector or query text
3. Are in stock and in a specific category (Datalog)
4. Have specific nested metadata (idoc)
5. Pass a fast operational policy check stored in a KV DBI (KV)

This example assumes `:product/desc` has `:db/fulltext true` and
`:db.fulltext/autoDomain true`, `:product/embedding` is a vector attribute, and
`:product/metadata` is an idoc attribute.

Chapter 14 introduced `idoc-match`: it searches inside a path-indexed document
value. In this example it filters products whose nested metadata says
`{:discounted true}` without requiring that nested field to become a separate
Datalog attribute.

<div class="multi-lang">

<!-- pdf-listing: Hybrid product search with text, vector, logic, KV, and idoc -->

```clojure
(d/q '[:find ?title ?dist
       :in $ ?search-term ?target-vec ?category
       :where
       ;; full-text search
       [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]]
       ;; vector search with distance
       [(vec-neighbors $ :product/embedding ?target-vec
                       {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]]
       ;; structured filters
       [?e :product/status :status/in-stock]
       [?e :product/category ?category]
       [?e :product/sku ?sku]
       [?e :product/title ?title]
       ;; KV-backed policy check
       [(my-app.kv/sellable-sku? ?sku) ?sellable?]
       [(true? ?sellable?)]
       ;; idoc metadata filter
       [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]
     db "clojure" user-embedding :books)
```

```java
conn.query("[:find ?title ?dist " +
           " :in $ ?search-term ?target-vec ?category " +
           " :where " +
           " [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] " +
           " [(vec-neighbors $ :product/embedding ?target-vec " +
           "                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] " +
           " [?e :product/status :status/in-stock] " +
           " [?e :product/category ?category] " +
           " [?e :product/sku ?sku] " +
           " [?e :product/title ?title] " +
           " [(my-app.kv/sellable-sku? ?sku) ?sellable?] " +
           " [(true? ?sellable?)] " +
           " [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]",
           "clojure", userEmbedding, Datalevin.kw("books"));
```

```python
conn.query(
    '[:find ?title ?dist '
    ' :in $ ?search-term ?target-vec ?category '
    ' :where '
    ' [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] '
    ' [(vec-neighbors $ :product/embedding ?target-vec '
    '                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] '
    ' [?e :product/status :status/in-stock] '
    ' [?e :product/category ?category] '
    ' [?e :product/sku ?sku] '
    ' [?e :product/title ?title] '
    ' [(my-app.kv/sellable-sku? ?sku) ?sellable?] '
    ' [(true? ?sellable?)] '
    ' [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]',
    "clojure",
    user_embedding,
    ":books",
)
```

```javascript
await conn.query(
  '[:find ?title ?dist ' +
  ' :in $ ?search-term ?target-vec ?category ' +
  ' :where ' +
  ' [(fulltext $ :product/desc ?search-term) [[?e ?a ?v]]] ' +
  ' [(vec-neighbors $ :product/embedding ?target-vec ' +
  '                 {:top 20 :display :refs+dists}) [[?e _ _ ?dist]]] ' +
  ' [?e :product/status :status/in-stock] ' +
  ' [?e :product/category ?category] ' +
  ' [?e :product/sku ?sku] ' +
  ' [?e :product/title ?title] ' +
  ' [(my-app.kv/sellable-sku? ?sku) ?sellable?] ' +
  ' [(true? ?sellable?)] ' +
  ' [(idoc-match $ :product/metadata {:discounted true}) [[?e _ _]]]]',
  'clojure',
  userEmbedding,
  ':books'
);
```

</div>

The shared `?e` variable joins all results together. The
`my-app.kv/sellable-sku?` predicate is ordinary application code that can read a
small KV DBI, for example a same-file operational table of suppressed SKUs,
real-time availability flags, or rollout policy.


## Summary

The multi-paradigm nature of Datalevin is about **composition**:

- **Consistency**: Indexes are synchronous by default, with explicit async modes for expensive secondary indexing
- **Performance**: Specialized indexes produce bounded candidate sets that
  Datalog can join with ordinary facts in the same engine
- **Simplicity**: One database, one query language, one operational model

By treating every index as a programmable capability, you can answer complex questions that traditional single-paradigm databases cannot express.
