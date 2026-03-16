# GDS Virtual Table: Implementation Details

This document covers the internal architecture of the Neo4j JDBC translator and how the virtual table approach integrates with it. For the proposal itself (problem statement, SQL design, and approach summary), see [sql_gds.md](proposal_gds_virtual_tables.md).

## How the Translator Works Today

The SQL-to-Cypher translator (`SqlToCypher.java`, 2,676 lines) uses jOOQ to parse SQL into a Query Object Model (QOM) AST, then walks the AST node by node and converts each node to its Cypher-DSL equivalent. It has no query planner, cost model, or optimizer. Two methods do the bulk of the work:

**`condition()`** (line 2052) receives a jOOQ `Condition` (a WHERE predicate) and dispatches on its type through an `instanceof` chain: `QOM.And` becomes `condition(left).and(condition(right))`, `QOM.Eq` becomes `expression(left).eq(expression(right))`, `QOM.InList` becomes `expression(field).in(listOf(items))`, and so on for Or, Not, Between, Like, IsNull, etc.

**`expression()`** (line 1661) receives a jOOQ `Field` (a column reference, literal, function call, arithmetic) and dispatches similarly: `Param` becomes `Cypher.parameter()` or `Cypher.literalOf()`, `TableField` resolves the table name to a Cypher node and produces `node.property(fieldName)`, aggregates like `Count` and `Sum` map to their Cypher equivalents.

Each jOOQ AST node maps 1:1 to a Cypher-DSL expression. The translator processes each expression in isolation based on its type. It never examines the full query plan to decide what to do with a given node.

## The Dispatch Point in `statement(Select<?>)`

The `statement(Select<?>)` method (line 450) is the entry point for all SELECT translation. After parsing the FROM clause, it hits a two-way branch at line 483:

```java
var reading = cbvs.isEmpty()
    ? createOngoingReadingFromSources(selectStatement)   // regular tables → MATCH
    : createOngoingReadingFromViews(selectStatement, cbvs);  // CBVs → CALL {}
```

Both branches return an `OngoingReading`, which the downstream code then chains with WHERE, RETURN, ORDER BY, and LIMIT. The GDS virtual table adds a third branch at this same dispatch point, also returning an `OngoingReading` that flows into the same downstream pipeline:

```java
var gdsTable = detectGdsVirtualTable(this.tables);
var reading = (gdsTable != null)
    ? createOngoingReadingFromGds(gdsTable, selectStatement)  // GDS → CALL gds.xxx
    : cbvs.isEmpty()
        ? createOngoingReadingFromSources(selectStatement)
        : createOngoingReadingFromViews(selectStatement, cbvs);
```

The GDS branch produces an `OngoingReading` that the existing downstream code (RETURN columns, ORDER BY, LIMIT) consumes. The GDS-specific work happens inside `createOngoingReadingFromGds`; everything after the branch point is shared.

## Why Not a Pure Early Return

For Levels 1-2 (global PageRank, no residual WHERE, no ORDER BY), a pure early return would work: build the entire `CALL ... YIELD` Cypher and return it as a `ResultStatement`. But Level 3 adds `WHERE score > 0 ORDER BY score DESC LIMIT 25`, and Level 4 adds source node MATCH + collect before the CALL. If the GDS branch returned early, it would have to re-implement ORDER BY translation, LIMIT handling, and SELECT column resolution — duplicating logic that already exists in the downstream pipeline.

The more honest design is to follow the CBV pattern. `createOngoingReadingFromViews` (line 685) already solves a similar problem: it builds a CALL subquery, projects column aliases, applies residual WHERE predicates, and returns an `OngoingReading` that the downstream code chains with RETURN, ORDER BY, and LIMIT. The GDS branch would do the same: build the CALL + YIELD, set up column aliases for the YIELD columns, apply residual WHERE, and hand off to the shared pipeline.

## The Field Resolution Problem and How CBVs Already Solve It

When the downstream code processes `ORDER BY score DESC` or the residual `WHERE score > 0`, the `expression()` method (line 1661) needs to resolve `score` from the jOOQ AST into a Cypher expression. For regular tables, `score` would resolve as `node.score` (a node property access). For a GDS virtual table, `score` is a YIELD column, not a node property. If `expression()` tries to resolve it as `gds_pagerank_stream.score`, it would generate a property access on a nonexistent node.

CBVs face this exact same problem, and the translator already handles it. `createOngoingReadingFromViews` projects the view's columns into a map aliased as the view name:

```java
var projection = Cypher.mapOf(view.columns().stream()
    .flatMap(column -> Stream.of(column.name(), Cypher.raw(column.propertyName())))
    .toArray());
previousAliases.add(projection.as(cbv.alias()));
m1 = Cypher.callRawCypher(view.query()).with(previousAliases);
```

This generates Cypher like:

```cypher
CALL { MATCH (n:Movie)<-[:ACTED_IN]-(:Person) RETURN * }
WITH {a: n.title, c1: n.released} AS cbv1
WHERE cbv1.a = 'The Matrix'
```

When `expression()` resolves `cbv1.a`, it calls `resolveTableOrJoin("cbv1")`, which creates a node-like pattern element with symbolic name `cbv1`. Then `pc.property("a")` produces `cbv1.a` in Cypher. Because `cbv1` is actually a map variable (from the WITH clause), `cbv1.a` is valid Cypher map access, not a node property access. The translator does not know or care about the distinction — the field resolution produces the same syntax for both.

The GDS branch follows the same pattern. After the CALL + YIELD, project the YIELD columns into a map aliased as the virtual table name:

```java
// Inside createOngoingReadingFromGds:
var gdsCall = buildGdsCallCypher(gdsTable, configPredicates);  // raw Cypher string
var projection = Cypher.mapOf(
    "node_id", Cypher.raw("nodeId"),
    "score", Cypher.raw("score")
);
var reading = Cypher.callRawCypher(gdsCall)
    .with(List.of(projection.as("gds_pagerank_stream")));
```

This generates:

```cypher
CALL { CALL gds.pageRank.stream('pharma-graph', {config}) YIELD nodeId, score
       RETURN nodeId, score }
WITH {node_id: nodeId, score: score} AS gds_pagerank_stream
WHERE gds_pagerank_stream.score > 0
```

Now `expression()` resolves `gds_pagerank_stream.score` using the same `resolveTableOrJoin` → `pc.property()` path it uses for CBVs. The downstream ORDER BY and LIMIT code works unchanged. No new field resolution mechanism is needed.

## Level 4: Source Nodes Compose Inside the CALL Block

For personalized PageRank, the source node preamble (MATCH + collect) lives inside the `callRawCypher` string, not outside it. The raw Cypher string passed to `callRawCypher` would be:

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
YIELD nodeId, score
RETURN nodeId, score
```

The translator wraps this in a `CALL {}` subquery, projects the YIELD columns into a map, and hands off to the downstream pipeline. The source node logic is entirely within the raw Cypher string — constructed by `createOngoingReadingFromGds` from the configuration predicates — and does not interact with the jOOQ AST or the existing translation methods.

This means the complexity of Level 4 is in **string construction**, not in **AST manipulation**. Building the MATCH + collect + CALL Cypher string from extracted configuration values is straightforward string templating. The integration with the rest of the translator uses the same `callRawCypher` → map projection → `OngoingReading` pattern that CBVs already validate.

## The Predicate Pre-Pass Does Not Modify the jOOQ AST

jOOQ's QOM types are Java records. They are immutable. The predicate partitioning step reads the `Condition` tree and produces two outputs without mutating anything:

1. **A configuration map** (`Map<String, Object>`): extracted from `QOM.Eq` and `QOM.InList` nodes whose field name matches a registered configuration key. For example, `graph_name = 'pharma-graph'` yields `{"graphName": "pharma-graph"}`.

2. **A residual `Condition`**: the remaining predicates (like `score > 0`), re-ANDed into a new jOOQ `Condition`. This residual is passed to the existing `condition()` method for normal Cypher translation.

The AND tree is a binary tree of `QOM.And` nodes. Partitioning it is list manipulation: flatten the tree into leaf predicates, check each leaf's field name against the registry, split into two lists, reconstruct. The existing `condition()` method receives only normal predicates it already handles. It does not know or care that some predicates were removed upstream.

## Comparison to GROUP BY/HAVING: Less Invasive

The GROUP BY/HAVING implementation (Phases 1-6) modified the translator's core flow in several ways:

- **`buildWithClause()`** inserted a WITH clause into the Cypher-DSL builder chain, changing how the `reading` variable is consumed downstream
- **`AliasRegistry`** added global mutable state (`this.aliasRegistry`) that the `expression()` method checks on every invocation, altering behavior for all field translations while active
- **`FieldMatcher`** added structural equivalence logic that `requiresWithForGroupBy` and `buildWithClause` depend on
- **`needsWithClause`** branching at line 488 altered whether the RETURN clause uses the original result columns or the WITH-aliased versions

These changes touched the main translation flow. The `aliasRegistry` interception in `expression()` (line 1663-1670) runs for every field translation when active, meaning a bug in the registry could affect any query that happens to trigger the GROUP BY path.

The GDS virtual table approach avoids this pattern. It does not add global state or intercept `expression()`. It follows the CBV pattern: build a raw Cypher string, wrap it in `callRawCypher`, project columns via map alias, and return an `OngoingReading`. The downstream pipeline handles the rest using its existing field resolution path, which already works for map-aliased columns.

## What Could Go Wrong

The realistic failure modes are:

1. **Table name collision.** If someone has a node label called `gds_pagerank_stream` in their graph, the translator would misroute a legitimate `SELECT * FROM gds_pagerank_stream` query into the GDS branch instead of generating a MATCH. Mitigation: use a reserved prefix (e.g., `__gds_`) or require explicit registration in a configuration file rather than hardcoding names.

2. **Predicate misclassification.** If the registry lists `score` as a configuration predicate when it should be a result filter, the translator would try to pass `score > 0` into the GDS configuration map, which would either error or silently produce wrong results. Mitigation: the registry is a static mapping defined once per algorithm; each algorithm's configuration keys are well-documented in the GDS manual and do not overlap with YIELD column names (`nodeId`, `score`, `communityId`).

3. **Residual predicate reconstruction.** If the pre-pass incorrectly reconstructs the remaining AND tree (dropping a predicate or duplicating one), the result filter would be wrong. Mitigation: the flatten-partition-reconstruct logic is simple list manipulation with straightforward unit tests. The GROUP BY `FieldMatcher` is more complex and was tested with 425+ cases; this would need fewer.

4. **Raw Cypher string construction.** The MATCH + collect + CALL Cypher for Level 4 is built by string templating inside `createOngoingReadingFromGds`. A bug here (wrong parameter injection, missing collect(), malformed ANY() predicate) would produce invalid Cypher or wrong PageRank results. But this is isolated to the GDS branch and testable independently — the generated Cypher can be compared against the known-working hand-written Cypher in `Neo4jRepository.kt`.

None of these failure modes affect existing queries. A regression in the GDS branch produces wrong results for GDS virtual table queries only. Regular SQL translation, GROUP BY, HAVING, CBVs, and JOINs flow through separate code paths that the GDS branch does not modify.

## Implementation Difficulty

**Difficulty: Medium (2-4 weeks)**

The implementation hooks into two existing extension points. First, the `extractQueriedTables` method (line 763) already checks each table name against the CBV registry (`CbvPointer.of(table)` + `ownsView()`). A parallel check against a GDS virtual table registry would follow the same pattern. Second, the `condition()` method (line 2052) already recursively walks the jOOQ `Condition` AST to translate WHERE predicates. The new code would walk the same AST, but partition predicates into configuration and result sets before translation.

The new components would be:

- A `GdsVirtualTable` record (analogous to `View`) defining the procedure name, configuration predicate names/types/defaults, and YIELD columns
- A predicate partitioning method that walks the `Condition` tree and extracts configuration predicates by name match
- A `createOngoingReadingFromGds` method that builds the raw Cypher string (with or without source node preamble), wraps it in `callRawCypher`, projects YIELD columns via map alias, applies residual WHERE, and returns an `OngoingReading`

The source node preamble (MATCH + collect for `source_npis`) adds complexity to the raw Cypher string construction but does not add complexity to the translator integration. The `callRawCypher` → map projection → `OngoingReading` pattern is the same regardless of whether the raw Cypher contains a simple CALL or a MATCH + collect + CALL.

**Result accuracy: Correct.** The translated Cypher would be structurally identical to the hand-written Cypher in `Neo4jRepository.kt`. The configuration predicates map directly to GDS configuration map entries, and the result predicates map directly to post-YIELD WHERE clauses. There is no semantic gap between what the SQL expresses and what the Cypher executes, provided the translator correctly classifies every predicate. Misclassification (treating a configuration predicate as a result filter or vice versa) would produce wrong results: a `damping_factor = 0.85` left as a result filter would try to filter the score column by 0.85, returning zero rows.

## Reference Implementations: Predicate Pushdown in Other Systems

The idea of intercepting WHERE predicates and routing them as configuration to a non-relational backend is well-established. Four systems implement variations of it, each with architectural lessons for the Neo4j JDBC translator.

### PostgreSQL Foreign Data Wrappers

PostgreSQL's FDW framework is the closest analog to what the virtual table approach proposes. A foreign table looks like a regular table to SQL clients, but its data comes from an external system. The FDW author implements callback functions that the query planner calls during planning and execution.

The predicate pushdown mechanism works through `baserel->baserestrictinfo`, which contains the restriction quals (WHERE clauses) that apply to the foreign table. During the `GetForeignPaths` callback, the FDW examines each restriction clause and decides whether the remote system can evaluate it. Clauses the FDW handles remotely are removed from `scan_clauses` in `GetForeignPlan`; any remaining clauses are evaluated locally by the PostgreSQL executor after rows return from the remote source.

The key architectural pattern is that the FDW does not need to handle every predicate. It examines each clause, pushes down what it can, and leaves the rest for the executor. The `oracle_fdw` wrapper, for example, pushes simple equality and range predicates to Oracle but leaves complex expressions for local evaluation. This partial pushdown model is exactly what the virtual table approach needs: `graph_name = 'pharma-graph'` and `damping_factor = 0.85` are pushed down (consumed as GDS configuration), while `score > 0` is left for post-YIELD filtering.

The difference is scope. A PostgreSQL FDW is a C extension with access to the full planner API, path costing, and executor hooks. The Neo4j JDBC translator is a jOOQ-to-Cypher-DSL string transformer with no planner integration. It cannot cost alternative plans or partially push down predicates through a formal API. It would need to hardcode the predicate classification rules in the translator itself.

Sources: [FDW Callback Routines](https://www.postgresql.org/docs/current/fdw-callbacks.html), [FDW Query Planning](https://www.postgresql.org/docs/current/fdw-planning.html)

### Apache Calcite Adapters

Calcite's adapter framework provides a higher-level abstraction than PostgreSQL's FDW. A data source implements one of several interfaces depending on how much optimization it can accept. `FilterableTable` receives an array of `RexNode` filter expressions and returns a list of filters it could not handle (the residual). Calcite applies the residual filters after the scan. `TranslatableTable` goes further, converting the entire relational algebra subtree into the adapter's native query language through planner rules.

The `FilterableTable` interface is the closest match to the virtual table concept. The table receives filters, consumes the ones it understands (configuration predicates), and returns the ones it does not (result predicates). Calcite handles the returned filters locally. This is the same partition-and-consume pattern the proposal describes, but formalized as an interface contract rather than a hardcoded predicate registry.

Source: [Calcite Adapter Documentation](https://calcite.apache.org/docs/adapter.html)

### Trino Connectors

Trino's connector framework supports seven types of pushdown: predicate, projection, aggregation, join, limit, top-N, and dereference. When the query planner encounters a `ScanFilterProject` node, it calls the connector's `applyFilter` method, passing a `TupleDomain` that maps columns to their constraint domains (ranges, discrete values, nullability). The connector returns a `ConstraintApplicationResult` indicating which constraints it handled and which must be evaluated by Trino.

The partial pushdown model is explicit: if a connector for a database that does not support range matching receives `WHERE x = 2 AND y > 5`, it pushes down `x = 2` and returns `y > 5` as a residual for Trino to evaluate. This is the same predicate partitioning the virtual table approach needs, and Trino's `TupleDomain` representation (mapping column names to constraint objects) is structurally similar to the proposed configuration predicate registry.

Trino's `PredicatePushdownController` interface, used in JDBC-compliant connectors, determines per-column whether a domain can be pushed down. This is a useful design reference: the virtual table registry could implement a similar per-predicate controller that classifies each WHERE predicate as "configuration" or "result filter."

Source: [Trino Pushdown Documentation](https://trino.io/docs/current/optimizer/pushdown.html)

### Spark DataSource V2

Spark's `SupportsPushDownFilters` interface requires two methods: `pushFilters(Filter[] filters)` returns the array of filters the data source could not handle (post-scan filters), and `pushedFilters()` returns the array of filters that were pushed down. The query planner calls `PushDownUtils.pushFilters()` to partition the original filter set into pushed and residual groups, then builds the physical plan with only the residual filters applied after the scan.

The pattern is identical to the others: the data source receives all filters, claims the ones it can handle, and returns the rest. Spark's implementation is the simplest of the four because it operates at the filter-object level rather than through a full relational algebra transformation.

### Common Pattern

All four systems implement the same core mechanism:

1. The query engine presents WHERE predicates to the data source
2. The data source examines each predicate and claims the ones it can handle natively
3. Claimed predicates are removed from the query plan and executed by the data source
4. Unclaimed predicates remain in the query plan and are evaluated by the engine after rows return

The virtual table approach for the Neo4j JDBC translator follows this pattern, but without the formal interface contract. The translator would hardcode the predicate classification (configuration vs. result filter) in a registry rather than implementing a callback that the planner invokes. This is a pragmatic simplification given that jOOQ's parser has no FDW-style callback mechanism, but it means the classification logic lives in the translator's source code rather than being extensible by third parties.
