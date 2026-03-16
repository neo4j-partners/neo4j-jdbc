# SQL Surface for GDS PageRank: A JDBC Translator Proposal

## Summary

The Neo4j JDBC driver's existing Cypher-backed view (CBV) implementation demonstrates a pattern that can be extended to support GDS algorithm calls through pure SQL. CBVs wrap arbitrary Cypher in a `CALL {}` subquery, project result columns into a map alias, and let the downstream translator pipeline handle WHERE, ORDER BY, and LIMIT unchanged. A GDS virtual table would follow the same pattern: detect a reserved table name in the FROM clause, partition the WHERE clause into algorithm configuration and result filters, build the GDS procedure call as a raw Cypher string, wrap it in `callRawCypher` with a map projection, and return an `OngoingReading` that the existing pipeline consumes. The CBV implementation validates that this integration pattern works: field resolution, residual WHERE, ORDER BY, and LIMIT all flow through unchanged. See [sql_gds_in_depth.md](proposal_sql_gds_in_depth.md) for the full implementation analysis, reference implementations from PostgreSQL FDW / Calcite / Trino / Spark, and risk assessment.

## Problem Statement

The Neo4j JDBC driver's SQL-to-Cypher translator handles reads, joins, and aggregation, but GDS procedures are unreachable from SQL. A BI tool or data analyst connected via JDBC with `enableSQLTranslation=true` can count nodes and traverse relationships, but cannot run PageRank. The Cypher endpoints work, and the `FORCE_CYPHER` hint works, but both require the client to write or embed Cypher. SQL-only consumers are locked out of graph analytics entirely.

The POC requirement is specific: given one or more pharmacies of interest, run personalized PageRank seeded from those pharmacies and return the highest-ranked entities. The source pharmacies change per request. This is a parameterized algorithm call, not a static query, and it is the exact case where Cypher-backed views fail because the WHERE clause filters results after the algorithm runs rather than configuring the algorithm's inputs.

## Proposed Approach: GDS Algorithms as Virtual Tables

The translator already maps SQL table names to Neo4j node labels. The same mechanism could map reserved table names to GDS procedure calls, where equality predicates in the WHERE clause become algorithm configuration parameters rather than result filters.

A query against `gds_pagerank_stream` would not scan a table. The translator would recognize the name, extract configuration predicates from the WHERE clause, build the corresponding `CALL gds.pageRank.stream(...)` Cypher, and leave any remaining predicates as post-algorithm filters.

**Configuration predicates** are WHERE clause conditions that the translator intercepts and removes from the SQL before translation, using their values to build the GDS procedure call rather than to filter the result set. A normal SQL predicate like `score > 0` filters rows after the query runs. A configuration predicate like `graph_name = 'pharma-graph'` never reaches the result-filtering stage at all; the translator consumes it during translation and uses the value `'pharma-graph'` as the first argument to `gds.pageRank.stream()`. The translator distinguishes between the two by maintaining a registry of known configuration predicate names for each virtual table. Any WHERE predicate whose column name matches a registered key is configuration; everything else is a result filter.

In concrete terms, given this WHERE clause:

```sql
WHERE graph_name = 'pharma-graph'        -- configuration: becomes procedure argument
  AND damping_factor = 0.85              -- configuration: goes into the config map
  AND score > 0                          -- result filter: stays as Cypher WHERE after YIELD
```

The translator partitions the three predicates into two groups. `graph_name` and `damping_factor` are consumed and used to construct `CALL gds.pageRank.stream('pharma-graph', {dampingFactor: 0.85})`. `score > 0` passes through as `WHERE score > 0` after the YIELD clause.

This predicate-partitioning pattern is a standard approach in the database industry, used by PostgreSQL Foreign Data Wrappers, Apache Calcite adapters, Trino connectors, and Spark DataSource V2. Each system presents WHERE predicates to the data source, lets it claim the ones it can handle natively, removes the claimed predicates from the query plan, and evaluates the unclaimed predicates after rows return. See the [in-depth document](proposal_sql_gds_in_depth.md) for a detailed comparison.

## SQL Design: Basic to Complex

### Level 1: Global PageRank with Defaults

The simplest case runs PageRank across the entire projection with default parameters.

**SQL:**
```sql
SELECT node_id, score
FROM gds_pagerank_stream
WHERE graph_name = 'pharma-graph'
```

**Translated Cypher:**
```cypher
CALL gds.pageRank.stream('pharma-graph')
YIELD nodeId AS node_id, score
```

`graph_name` is the only required configuration predicate. The translator consumes it and passes it as the first positional argument to the procedure. The virtual table exposes two columns: `node_id` (Long) and `score` (Double), matching the `YIELD` clause.

### Level 2: Algorithm Parameters via WHERE Predicates

Add damping factor, iteration count, and a relationship weight property.

**SQL:**
```sql
SELECT node_id, score
FROM gds_pagerank_stream
WHERE graph_name = 'pharma-graph'
  AND relationship_weight_property = 'claimCount'
  AND max_iterations = 20
  AND damping_factor = 0.85
```

**Translated Cypher:**
```cypher
CALL gds.pageRank.stream('pharma-graph', {
    relationshipWeightProperty: 'claimCount',
    maxIterations: 20,
    dampingFactor: 0.85
})
YIELD nodeId AS node_id, score
```

The translator maintains a registry of known configuration predicates for each virtual table. Predicates matching registered names are extracted from the WHERE clause and placed into the configuration map. The snake_case SQL column names map to camelCase Cypher parameter names (`max_iterations` to `maxIterations`, `damping_factor` to `dampingFactor`).

### Level 3: Result Filtering and Ordering

Mix configuration predicates with result-set predicates. The translator must distinguish between the two.

**SQL:**
```sql
SELECT node_id, score
FROM gds_pagerank_stream
WHERE graph_name = 'pharma-graph'
  AND relationship_weight_property = 'claimCount'
  AND max_iterations = 20
  AND damping_factor = 0.85
  AND score > 0
ORDER BY score DESC
LIMIT 25
```

**Translated Cypher:**
```cypher
CALL gds.pageRank.stream('pharma-graph', {
    relationshipWeightProperty: 'claimCount',
    maxIterations: 20,
    dampingFactor: 0.85
})
YIELD nodeId AS node_id, score
WHERE score > 0
RETURN node_id, score
ORDER BY score DESC
LIMIT 25
```

`score > 0` is not a known configuration predicate, so the translator leaves it as a post-algorithm filter. `ORDER BY` and `LIMIT` pass through to the Cypher RETURN clause as they do for regular SQL translation. The rule is simple: if the predicate name matches a registered configuration key, it configures the algorithm; otherwise, it filters the output.

### Level 4: Personalized PageRank with Source Nodes

This is the critical case. Personalized PageRank requires `sourceNodes`, a list of node references that the algorithm uses as seeds. In Cypher, this requires a MATCH to find the nodes, a collect() to gather them into a list, and then passing that list into the configuration map. There is no direct SQL equivalent for "collect graph nodes by property and pass them to a procedure."

Most configuration predicates are standalone: `damping_factor = 0.85` maps to a single key-value pair in the GDS config map. Source node selection is different. It requires three pieces of information working together: which node label to match (`ResolvedPharmacy`), which property to filter on (`npis`), and which values to match (`'1234567890', '0987654321'`). These three predicates form a composite configuration predicate: the translator must recognize all three, treat them as a unit, and use them together to generate the MATCH + collect preamble. If any one is missing, the translator cannot construct the source node query.

**SQL:**
```sql
SELECT node_id, score
FROM gds_pagerank_stream
WHERE graph_name = 'pharma-graph'
  AND relationship_weight_property = 'claimCount'
  AND max_iterations = 20
  AND damping_factor = 0.85
  AND source_label = 'ResolvedPharmacy'
  AND source_property = 'npis'
  AND source_values IN ('1234567890', '0987654321')
  AND score > 0
ORDER BY score DESC
LIMIT 25
```

**Translated Cypher:**
```cypher
MATCH (source:ResolvedPharmacy)
WHERE ANY(npi IN source.npis WHERE npi IN ['1234567890', '0987654321'])
WITH collect(source) AS sourceNodes
CALL gds.pageRank.stream('pharma-graph', {
    sourceNodes: sourceNodes,
    relationshipWeightProperty: 'claimCount',
    maxIterations: 20,
    dampingFactor: 0.85
})
YIELD nodeId AS node_id, score
WHERE score > 0
RETURN node_id, score
ORDER BY score DESC
LIMIT 25
```

The translator recognizes `source_label`, `source_property`, and `source_values` as a linked triple. It generates the MATCH + collect preamble and injects the collected nodes into the configuration map.

**Tradeoffs:** The SQL reads naturally as "run PageRank on this graph, seeded from these pharmacies." But `source_values IN (...)` is doing something fundamentally different from a normal SQL IN predicate: it is not filtering rows from the result set, it is configuring which nodes seed the algorithm. This semantic overloading is the core tension with the virtual table approach. Any user who reads this SQL without knowing the convention will misunderstand what `source_values IN (...)` does.

**The `npis` list-property problem:** The Cypher uses `ANY(npi IN source.npis WHERE npi IN $1)` because `npis` is a list property on each pharmacy node, not a scalar. A single pharmacy may have multiple NPIs, and the source values need to match against any element in that list. The SQL `source_values IN (...)` predicate hides this list-contains-element semantics. The translator would need to know that `source_property` references a list-typed property and generate the `ANY()` pattern rather than a simple equality check. This could be handled by convention (always use `ANY` for source property matching) or by metadata inspection (check the property type at translation time).

**Simplified form:** A pragmatic simplification consolidates the composite predicate into a single `source_npis` configuration key that accepts a list and always generates the `ANY()` pattern against the label's list-typed identifier property:

```sql
SELECT node_id, score
FROM gds_pagerank_stream
WHERE graph_name = 'pharma-graph'
  AND source_npis IN ('1234567890', '0987654321')
  AND relationship_weight_property = 'claimCount'
  AND max_iterations = 20
  AND damping_factor = 0.85
  AND score > 0
ORDER BY score DESC
LIMIT 25
```

This reads as: "From the PageRank results on pharma-graph, seeded from these NPIs, weighted by claim count, with these tuning parameters, give me the top 25 results with scores above zero." The SQL communicates intent even if the execution model differs from a table scan.

## Implementation Scope

The virtual table approach requires three changes to the JDBC driver's translator:

1. **Virtual table registry.** A mapping from reserved table names (`gds_pagerank_stream`, `gds_louvain_stream`, etc.) to procedure signatures, including which WHERE predicate names are configuration keys, their types, and their defaults.

2. **Predicate partitioning.** When the translator encounters a registered virtual table name, it splits the WHERE clause into configuration predicates (consumed and removed) and result predicates (preserved as Cypher WHERE after YIELD).

3. **`createOngoingReadingFromGds` method.** Following the CBV pattern: build the GDS procedure call as a raw Cypher string (including source node MATCH + collect when `source_npis` is present), wrap it in `callRawCypher`, project YIELD columns via map alias, apply residual WHERE, and return an `OngoingReading` that the existing downstream pipeline (RETURN, ORDER BY, LIMIT) consumes unchanged.

The virtual table registry could start with just `gds_pagerank_stream` and expand to `gds_louvain_stream`, `gds_wcc_stream`, and others as demand warrants. Each algorithm's configuration keys are well-documented in the GDS manual and map cleanly to a predicate registry.

**Estimated difficulty: Medium (2-4 weeks).** The CBV implementation validates the integration pattern. The new code is a third branch at the existing dispatch point, producing an `OngoingReading` through the same `callRawCypher` → map projection mechanism. No existing methods are modified. See the [in-depth document](proposal_sql_gds_in_depth.md) for the full analysis.

## What This Does Not Solve

**Projection management.** Creating, dropping, and checking projections are administrative operations, not analytical queries. They have no natural SQL SELECT representation. These would remain Cypher-only or use the CALL/callable statement path.

**Algorithm chaining.** Running Louvain in mutate mode, then PageRank on the same projection, requires two sequential statements with shared state in the projection. SQL has no mechanism for this. Multi-algorithm pipelines stay in Cypher.

**Node resolution in results.** The Cypher query uses `gds.util.asNode(nodeId)` to resolve raw node IDs back to node properties (names, NPIs, labels). The virtual table would return raw `node_id` values. Resolving those to human-readable properties would require a second query or a JOIN against the node labels, which the SQL translator can handle independently.

```sql
-- Two-step: run PageRank, then resolve node details
SELECT rp.names, rp.npis, pr.score
FROM gds_pagerank_stream pr
JOIN ResolvedPrescriber rp ON rp.v$id = pr.node_id
WHERE pr.graph_name = 'pharma-graph'
  AND pr.source_npis IN ('1234567890', '0987654321')
ORDER BY pr.score DESC
LIMIT 25
```

Whether this JOIN would translate correctly depends on the translator's ability to handle a JOIN between a virtual table result set and a label-based table. This would likely require the PageRank to execute first, materialize its results, and then join against the node label. The current translator has no mechanism for this kind of staged execution.

## Open Questions

1. **Should the driver own this, or should it be a GDS plugin?** The virtual table registry is GDS-specific knowledge embedded in the JDBC translator. If GDS algorithm signatures change, the registry needs updating. A plugin architecture where GDS ships its own virtual table definitions would keep the coupling clean.

2. **How should the translator signal misuse?** If a user writes `WHERE graph_name = 'pharma-graph' AND graph_name = 'other-graph'`, should it error or take the last value? If `graph_name` is missing, should it fail at translation time or let Neo4j return the error?

3. **Can the result schema be discovered?** JDBC `ResultSetMetaData` requires knowing the column names and types before execution. For virtual tables, the translator would need to declare the YIELD columns statically. This is feasible since each algorithm's stream output is well-defined, but it adds a metadata contract the translator does not currently maintain.
