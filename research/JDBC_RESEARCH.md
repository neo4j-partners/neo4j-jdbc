# Neo4j JDBC Driver: Research Findings for Pharma 340B Service

## The Question

The Kotlin service needs to execute four operations against Neo4j: check whether a GDS graph projection exists, create or drop that projection, run personalized PageRank with pharmacy source nodes, and return ranked results. The Python prototype does all of this through the native Neo4j Python driver, which speaks Bolt protocol directly and treats Cypher as its native language. The question is whether the Neo4j JDBC driver (version 6.x) can do the same work, and what changes when it does.

A secondary question follows: the JDBC driver includes a SQL-to-Cypher translator. Which of these operations can eventually move from Cypher to SQL, and which must stay as Cypher permanently?

## GDS Procedures Through JDBC

The central finding is straightforward: the Neo4j JDBC driver can execute any Cypher statement, including GDS procedure calls. When SQL translation is not enabled, the driver passes the Cypher string through to the server unchanged. When translation is enabled, Cypher still passes through unchanged if the `/*+ NEO4J FORCE_CYPHER */` hint is present or if the input is already valid Cypher that the translator cannot parse. The driver sends the statement to Neo4j over Bolt and returns results through a standard JDBC ResultSet. It has no awareness of GDS and imposes no restrictions on what Cypher it will execute.

The execution path is: [`StatementImpl.executeQuery()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/StatementImpl.java#L148-L150) calls `executeQuery0()`, which applies [`processSQL()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/StatementImpl.java#L737-L748) — a `UnaryOperator<String>` that is either identity (no translator configured) or a [`TranslatorChain`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1085-L1142) that checks the `FORCE_CYPHER` hint before attempting translation.

This means `gds.pageRank.stream`, `gds.graph.project`, `gds.graph.drop`, and `gds.graph.exists` all work through `Statement.executeQuery()`. The driver also provides [`CallableStatementImpl`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/CallableStatementImpl.java#L64-L122) which supports JDBC callable statement escape syntax and parses it into Cypher procedure calls, but plain `Statement` is simpler and avoids any translation logic that might interfere with complex GDS queries.

### Compound Queries

The personalized PageRank query from the Python prototype is a compound statement: it MATCHes pharmacy nodes, collects them into a list, passes that list as the `sourceNodes` parameter to `gds.pageRank.stream`, filters results, and returns ranked entities. This entire query executes as a single Cypher string. Because the JDBC driver passes Cypher through unchanged, the compound structure works exactly as it does through the Python driver.

The one Cypher constraint worth noting: when `CALL` appears inline with other clauses (MATCH, WITH, RETURN), the `YIELD` columns must be named explicitly. `YIELD *` is only valid in standalone `CALL` statements. The prototype's queries already follow this rule.

### Result Set Mapping

GDS procedure results map to JDBC types predictably. The `gds.pageRank.stream` procedure yields `nodeId` (Neo4j Integer, which is 64-bit) and `score` (Neo4j Float, which is 64-bit double-precision). These map to `ResultSet.getLong("nodeId")` and `ResultSet.getDouble("score")` respectively. String columns use `getString()`, and boolean columns (like the `exists` field from `gds.graph.exists`) use `getBoolean()`.

One subtlety: Neo4j's documentation labels these as "Integer" and "Float," but both are 64-bit types. Java's `int` and `float` are 32-bit. Using `getLong()` and `getDouble()` is correct; using `getInt()` and `getFloat()` would risk truncation.

### Projection Must Precede Algorithm

Graph projection and algorithm execution must happen in separate statements. This is a GDS constraint, not a JDBC one: `gds.graph.project` must complete and return before `gds.pageRank.stream` can reference the projection by name. The service handles this by calling `ensure_projection` (check existence, create if missing) as a prerequisite step before any PageRank call.

### Historical Concern

An issue on the older neo4j-contrib JDBC driver repository (Issue #232) reported failures when calling `gds.graph.create.cypher` through JDBC, with relationship loading errors that did not occur through the native driver. That issue was on the community-contributed driver, not the current official Neo4j JDBC driver (org.neo4j:neo4j-jdbc, version 6.x). The current driver has a completely different Bolt implementation. Testing should confirm this is resolved, but there's no indication the issue carries forward.

## List Properties and Parameter Binding

### Reading Lists from Results

Neo4j stores the `npis` and `names` fields on ResolvedPharmacy and ResolvedPrescriber nodes as list properties (arrays of strings). The JDBC driver surfaces these through `ResultSet.getObject()`, which returns a `java.util.List`. There is no `getArray()` support for general list properties; `java.sql.Array` is reserved for Neo4j's specialized `Vector` type (fixed-size numeric arrays used for embeddings). The pattern is `getObject("npis")` cast to `List<String>`.

### Passing Lists as Parameters

The PageRank query needs to pass a list of pharmacy NPIs as a parameter: `WHERE ANY(npi IN source.npis WHERE npi IN $pharmacy_npis)`. The JDBC driver supports `PreparedStatement.setObject()` with `java.util.List` values, since the underlying Bolt protocol handles list serialization natively. Named parameters are also available through `Neo4jPreparedStatement`, a driver-specific extension that accepts parameter names instead of positional indices.

This capability is not prominently documented but is consistent with how the Bolt protocol handles parameterized queries. The Python prototype passes lists as parameters without issue, and the Bolt serialization is the same regardless of which driver sends it.

## Spring Boot Integration

### No Auto-Configuration

The Neo4j JDBC driver does not provide a Spring Boot starter or auto-configuration module. This is distinct from Spring Data Neo4j, which has its own starter (`spring-boot-starter-data-neo4j`) and uses the native Java driver with `spring.neo4j.*` properties. The JDBC driver requires manual DataSource bean configuration.

The setup involves creating a `Neo4jDataSource` bean, setting URL, username, and password from application properties, then wrapping it in a HikariCP `HikariDataSource` for connection pooling. From there, Spring Boot's `JdbcTemplate` and `NamedParameterJdbcTemplate` work normally.

### Connection Pooling

The driver performs no internal connection pooling by design. It delegates pooling entirely to external solutions, and HikariCP (Spring Boot's default pool) is the recommended choice. Standard `spring.datasource.hikari.*` properties control pool size, idle timeout, and connection lifetime.

### Timeouts

The driver's `timeout` property (default 1000ms) controls only connection acquisition, not query execution. Query execution timeouts are controlled server-side by Neo4j's `db.transaction.timeout` setting. For GDS operations on larger graphs, projection creation can take longer than default transaction timeouts allow. On the ~2,600-node graph in this demo, this is unlikely to be an issue, but production-scale graphs would need the server-side timeout adjusted.

### Transaction Management

Standard Spring `@Transactional` works through `DataSourceTransactionManager`. The driver follows JDBC spec: one concurrent transaction per connection, auto-commit by default. GDS procedure calls (which are read operations against an in-memory projection) work fine in auto-commit mode and don't require explicit transaction management.

### Observability

The driver automatically publishes Micrometer metrics when Spring Boot Actuator is on the classpath, covering connection counts, statement counts, query counts, and cached translation counts. OpenTelemetry tracing is available through a bridge class.

## SQL-to-Cypher Translation

### How It Works

The driver includes an optional SQL-to-Cypher translator that parses SQL using jOOQ's generic SQL parser, builds an AST, and generates Cypher through Neo4j's Java Cypher DSL. The core translation logic is in [`SqlToCypher.java`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java). It activates by adding `enableSQLTranslation=true` to the JDBC URL or by calling `connection.nativeSQL()` on individual statements. Raw Cypher can bypass the translator with a `/*+ NEO4J FORCE_CYPHER */` hint, which is detected by a [regex pattern in `ConnectionImpl`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L103-L107) and causes the [`TranslatorChain`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1106-L1112) to return the statement unchanged.

### What Translates

The translator handles a useful subset of SQL for read and write operations. SELECT with WHERE clauses (comparisons, BETWEEN, LIKE, IN, IS NULL), ORDER BY, LIMIT, and basic aggregates (COUNT, SUM, AVG, MIN, MAX) all translate. DML operations including INSERT, UPDATE, DELETE, and TRUNCATE also work, with INSERT supporting multi-row batches via UNWIND.

JOINs translate to relationship traversals through an inference system. The translator infers relationship types from join column names (a column named `directed` in an ON clause becomes a `DIRECTED` relationship), from intersection/bridge tables (a `movie_actors` table becomes an `ACTED_IN` relationship with explicit mapping), or from table naming patterns (`Person_ACTED_IN_Movie` decomposes into a typed relationship). Table names map to node labels, configurable through `s2c.tableToLabelMappings`.

NATURAL JOINs create anonymous relationship traversals, which is interesting for simple graph queries where the relationship type doesn't matter for filtering.

### What Does Not Translate

The gaps are significant for analytical workloads, though the details are more nuanced than a simple "not supported" for each feature.

**GROUP BY and HAVING** are parsed by jOOQ but silently dropped during Cypher generation. The [`statement(Select<?>)`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L440-L481) method extracts `$distinct()`, `$orderBy()`, and `$limit()` from the jOOQ AST but never calls `$groupBy()` or `$having()`. The query executes without error and may appear correct because Cypher's aggregation functions in RETURN perform implicit grouping on non-aggregated columns. However, the grouping semantics are not identical to SQL GROUP BY, so results can be silently wrong. This is confirmed by the [test suite](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/test/java/org/neo4j/jdbc/translator/impl/SqlToCypherTests.java#L551-L568), which shows `SELECT name, count(*) FROM People p GROUP BY name` translating to `MATCH (p:People) RETURN p.name AS name, count(*)` — the GROUP BY clause simply disappears.

**LEFT JOIN** is accepted by the translator — the [`JoinDetails.of()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L296-L306) method handles both `QOM.Join` and `QOM.LeftJoin` at [line 299](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L299) — but empirical testing (see addendum) shows it produces the same Cypher as an INNER JOIN rather than generating `OPTIONAL MATCH`. The [translator documentation](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/test/resources/joins.adoc#L13-L16) warns that "Outer joins are not supported." **RIGHT JOIN, FULL JOIN, and CROSS JOIN** are not handled by `JoinDetails.of()` and throw errors.

**Subqueries in the FROM clause** (derived tables) are partially supported — the translator detects [`QOM.DerivedTable`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L444-L447) and unwraps it. Correlated subqueries and scalar subqueries in WHERE clauses are not supported.

**UNION, INTERSECT, and EXCEPT** are not implemented. The [`build()` method](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L345-L369) dispatches on query type (Select, Delete, Truncate, Insert, InsertReturning, Update) and throws [`unsupported(query)`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L366-L368) for anything else.

**Window functions and CTEs** have no handling in the translator and will either error or produce incorrect output.

**OFFSET** is not implemented. The [`addLimit()` method](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L485-L503) accesses `$limit()` but never accesses `$offset()`.

For this service specifically, the most consequential gap is GROUP BY. A query like "count claims per pharmacy grouped by 340B provider" would need to stay in Cypher. The silent-drop behavior makes this more dangerous than an outright error — the query runs, returns a result, but the result may be wrong.

### GDS Procedures Cannot Use SQL Translation

GDS procedure calls have no SQL equivalent. The translator has no concept of CALL mapped to SQL syntax, and the procedure-specific YIELD clause has no SQL analog. GDS operations must either use the `FORCE_CYPHER` hint to bypass translation entirely, or use Cypher-backed views.

### Cypher-Backed Views

The most interesting SQL translation feature for this project is Cypher-backed views (CBVs). A CBV defines a virtual "table" backed by an arbitrary Cypher query, including GDS procedure calls. The [`View` record](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/spi/src/main/java/org/neo4j/jdbc/translator/spi/View.java#L38-L48) in the translator SPI consists of a name, a Cypher query, and a list of column definitions. Views are defined in a JSON configuration file loaded at connection time by [`ViewDefinitionReader`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/ViewDefinitionReader.java#L47-L113), which supports `file://`, `http://`, `https://`, and `resource://` schemes, and accepts both an array-of-views JSON format and the Simba connector format. Once defined, the view is queryable with standard SQL SELECT statements.

A CBV could wrap the PageRank query: the Cypher that calls `gds.pageRank.stream`, filters out pharmacy nodes, and returns entity types with scores becomes a virtual table. The API layer then queries it with `SELECT * FROM pagerank_results ORDER BY score DESC LIMIT 25`. The GDS complexity is encapsulated in the view definition; the application code sees a flat result set.

CBVs have constraints, all enforced in the translator source code:

- **Read-only.** INSERT ([line 623](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L623)), UPDATE ([line 1077](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L1077)), DELETE ([line 411](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L411)), and TRUNCATE ([line 425](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L425)) all throw `IllegalArgumentException` via [`assertCypherBackedViewUsage()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L1128-L1134).
- **Cannot be mixed with label-based tables.** [`assertEitherCypherBackedViewsOrTables()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L569-L573) throws if a query references both CBVs and regular tables.
- **Cannot be used in JOIN clauses.** The [`extractQueriedTables()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L583-L595) method throws if a CBV appears as part of a join (line 590). Multiple CBVs can be queried together by enumerating them in the FROM clause and joining via WHERE predicates.
- **The underlying Cypher must be valid inside a CALL subquery.** The translator wraps each view's query using [`Cypher.callRawCypher(view.query())`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L516-L520), embedding it as a `CALL {}` block. This is what enables composing multiple CBVs in one query, but it requires that each view's Cypher is independently valid in a CALL context.

For wrapping a self-contained analytical query like PageRank, these constraints don't apply.

### What Phase 3 Looks Like

Given these findings, the SQL phase of the service has a clear shape. The basic read queries (graph summary counts, pharmacy neighborhood lookups, chargeback concentration) can move to SQL. They use SELECT, WHERE, ORDER BY, and LIMIT, all of which translate. Relationship traversals map through JOIN inference or NATURAL JOINs.

GDS operations stay in Cypher, either through the FORCE_CYPHER hint or wrapped in CBVs. The CBV approach is cleaner: the application code uses SQL uniformly, and the Cypher is isolated in a configuration file rather than scattered through the codebase.

The absence of GROUP BY limits how far SQL mode can go for analytical queries. Queries that aggregate across relationship patterns (shared prescriber counts between pharmacy pairs, total chargeback amounts per provider) need Cypher.

## Summary of Findings

| Capability | Status | Notes |
|---|---|---|
| GDS procedure execution | Works | Via Statement.executeQuery() with Cypher |
| Compound PageRank query | Works | MATCH + collect + CALL + YIELD + RETURN passes through unchanged |
| Result type mapping | Works | getLong() for nodeId, getDouble() for score |
| List property reads | Works | getObject() cast to List |
| List parameter binding | Works | setObject() with java.util.List |
| Spring Boot integration | Manual setup | No starter; create Neo4jDataSource + HikariCP manually |
| Connection pooling | Works | HikariCP, no internal pooling |
| SQL for basic reads | Works | SELECT, WHERE, ORDER BY, LIMIT, INNER JOIN |
| SQL for GDS procedures | Does not translate | Must use FORCE_CYPHER hint or Cypher-backed views |
| SQL GROUP BY | Silently dropped | Parsed but not extracted; may produce wrong results without error ([source](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L440-L481)) |
| SQL LEFT JOIN | Accepted, degrades to INNER JOIN | Code handles `QOM.LeftJoin` but does not generate `OPTIONAL MATCH` ([source](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L299)) |
| SQL RIGHT/FULL JOIN | Not supported | Throws error |
| SQL FROM-clause subqueries | Supported | Via `DerivedTable` handling ([source](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L444-L447)) |
| SQL UNION/INTERSECT/EXCEPT | Not supported | Throws `IllegalArgumentException` ([source](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L366-L368)) |
| SQL OFFSET | Not supported | `$offset()` never accessed ([source](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L485-L503)) |
| Cypher-backed views for GDS | Verified | Read-only, no JOIN, wraps Cypher in `CALL {}` subquery ([source](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L516-L520)) |

## Implications for the Service

The Kotlin service can proceed with confidence on the Cypher phase. Every operation the Python prototype performs will work through the JDBC driver without modification to the query logic. Spring Boot integration requires a manual DataSource configuration class rather than property-based auto-configuration, but this is a one-time setup.

The SQL phase is viable but bounded. Simple graph reads translate well. GDS operations and grouped aggregations stay in Cypher, either explicitly or behind CBVs. The demo value is in showing this boundary clearly: SQL covers the common access patterns, Cypher handles the graph-specific analytics, and the JDBC driver supports both through the same connection.

## Addendum: Empirical SQL Translation Testing (Phase 3b)

Date: 2026-03-14. Driver version 6.11.0, tested against the live AuraDB instance with `enableSQLTranslation=true`.

This section records the results of running actual SQL queries against the pharma graph schema. The theoretical analysis above was largely confirmed, with several corrections and new findings.

### Corrections to Theoretical Analysis

Three findings from empirical testing and source code review differ from the original theoretical analysis above (which has since been corrected inline):

1. **`getArray()` works for list properties.** The original analysis stated "There is no `getArray()` support for general list properties." Testing shows `getArray()` returns `org.neo4j.jdbc.ArrayImpl` with `baseType=12` (VARCHAR) for all list properties. Both `getObject()` (returns `java.util.List`) and `getArray()` (returns `java.sql.Array`) work. The `getObject()` cast to `List<*>` remains the simpler pattern for Kotlin code.

2. **LEFT JOIN and GROUP BY silently degrade rather than throwing errors.** The translator code accepts `LEFT JOIN` — [`JoinDetails.of()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L299) handles `QOM.LeftJoin` — but it generates the same `MATCH` pattern as an INNER JOIN rather than producing `OPTIONAL MATCH`. GROUP BY is parsed by jOOQ but the translator's [`statement(Select<?>)`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L475-L478) method never extracts it from the AST. Both execute without error but return incorrect results. This is more dangerous than throwing an error because there is no indication of failure.

3. **FROM-clause subqueries are partially supported.** The original analysis stated "subqueries are missing." The translator handles [`QOM.DerivedTable`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L444-L447) (subqueries in the FROM clause), though correlated and scalar subqueries remain unsupported.

### Basic SQL Reads

All basic SQL reads translate correctly.

| SQL | Translated Cypher | Result |
|-----|-------------------|--------|
| `SELECT * FROM ResolvedPharmacy LIMIT 10` | `MATCH (n:ResolvedPharmacy) RETURN elementId(n) AS v$id, n.npis, n.enterpriseIds, n.names LIMIT 10` | 10 rows |
| `SELECT count(*) FROM ResolvedPharmacy` | `MATCH (n:ResolvedPharmacy) RETURN count(*)` | 5,000 |
| `SELECT count(*) FROM ResolvedPrescriber` | `MATCH (n:ResolvedPrescriber) RETURN count(*)` | 60,000 |
| `SELECT count(*) FROM Provider340b` | `MATCH (n:Provider340b) RETURN count(*)` | 800 |

`SELECT *` adds a synthetic `v$id` column containing `elementId()` as VARCHAR. This column does not appear when specific columns are selected.

Labels with digits (`Provider340b`) translate without issue.

### Property Access and List Behavior

List properties (`names`, `npis`, `enterpriseIds`) behave identically in SQL mode and Cypher mode. No differences observed.

| Access Method | List Columns | Scalar Columns |
|---------------|-------------|----------------|
| `getObject()` | `java.util.List` (`SingletonList` or `ListN`) | `java.lang.String` |
| `getString()` | JSON array string (`["value1", "value2"]`) | Plain string |
| `getArray()` | `org.neo4j.jdbc.ArrayImpl` (baseType=VARCHAR) | Throws `Neo4jException` |
| JDBC type code | 2003 (ARRAY) | 12 (VARCHAR) |

Recommended Kotlin access pattern: `rs.getObject("names") as List<*>`.

`WHERE ... IS NOT NULL` works on list properties. Other list predicates (`WHERE 'X' IN names`) were not tested.

### JOIN Behavior

The JOIN behavior is the most complex finding. The translator has two distinct direction rules:

| SQL Syntax | Cypher Direction | Practical Implication |
|------------|-----------------|----------------------|
| `A NATURAL JOIN B` | `(A)-->(B)` | Table order matches relationship direction |
| `A JOIN B ON a.REL = b.REL` | `(A)<-[r:REL]-(B)` | Table order is REVERSED from relationship direction |

**NATURAL JOIN is the only reliable SQL JOIN syntax.** `ResolvedPharmacy NATURAL JOIN ResolvedPrescriber` translates to `MATCH (p:ResolvedPharmacy)-->(pr:ResolvedPrescriber)` and returns data correctly. It works across all node type pairs.

Explicit JOIN (`ON a.SHARES_CLAIMS_WITH = b.SHARES_CLAIMS_WITH`) generates a reversed arrow. Writing `Pharmacy JOIN Prescriber` produces `(Pharmacy)<--(Prescriber)`, which matches no data because relationships go `Pharmacy-->Prescriber`. To get results, the table order must be counterintuitively reversed: `Prescriber JOIN Pharmacy`.

**Relationship properties are inaccessible through any SQL JOIN syntax.** Neither NATURAL JOIN nor explicit JOIN expose `claimCount`, `totalPapCopayAmount`, or other relationship properties. Queries needing relationship data must use `FORCE_CYPHER`.

**Relationship names with underscores are mishandled as table names.** `SELECT * FROM SHARES_CLAIMS_WITH` splits the name into three tokens (`SHARES`, `CLAIMS`, `WITH`) and generates nonsensical Cypher.

### FORCE_CYPHER on SQL-Enabled Connections

The `/*+ NEO4J FORCE_CYPHER */` hint works correctly on SQL-enabled connections. All tested operations succeeded:

- `RETURN gds.graph.exists('pharma-claims-projection') AS exists` — returned `true`
- `CALL gds.pageRank.stream(...)` — returned ranked results with correct JDBC type mapping
- Relationship traversal with properties — returned `claimCount` values
- Array indexing (`names[0]`) — returned scalar strings
- Schema introspection (`db.schema.visualization()`) — confirmed 3 node types, 2 relationship types

### Silent Failure Modes

| Feature | Expected Behavior | Actual Behavior |
|---------|-------------------|-----------------|
| `LEFT JOIN` | Error or outer join semantics | Silently converted to INNER JOIN |
| `GROUP BY` | Error or grouped results | Silently dropped; returns ungrouped total |

These silent failures mean all SQL queries should be validated by checking translated Cypher via `connection.nativeSQL()` before trusting results.

### Query Routing Decision Table for Phase 3c

| Query Type | Use SQL? | Mechanism |
|------------|----------|-----------|
| Node counts per label | Yes | `SELECT count(*) FROM <Label>` |
| Node property reads | Yes | `SELECT cols FROM <Label> WHERE ...` |
| Simple traversals (node data only) | Yes | `NATURAL JOIN` |
| Traversals needing relationship properties | No | `FORCE_CYPHER` with Cypher |
| GDS procedures (PageRank, Louvain, projection) | No | `FORCE_CYPHER` with Cypher |
| Aggregation with GROUP BY | No | `FORCE_CYPHER` with Cypher |
| Outer join semantics | No | `FORCE_CYPHER` with `OPTIONAL MATCH` |

### Research Artifacts

The test harnesses and detailed per-query findings are in:
- `pharma-service/src/main/kotlin/com/pharma/frauddetection/research/` — Kotlin research classes
- `pharma-service/research-findings/` — Detailed findings per query category

## Sources

### Documentation
- Neo4j JDBC Driver Manual: neo4j.com/docs/jdbc-manual/current/
- Neo4j JDBC SQL-to-Cypher Documentation: neo4j.com/docs/jdbc-manual/current/sql2cypher/
- Neo4j JDBC Data Types: neo4j.com/docs/jdbc-manual/current/datatypes/
- Neo4j JDBC Configuration: neo4j.com/docs/jdbc-manual/current/configuration/
- Neo4j JDBC Syntax (CallableStatement): neo4j.com/docs/jdbc-manual/current/syntax/
- GDS PageRank Documentation: neo4j.com/docs/graph-data-science/current/algorithms/page-rank/
- Cypher CALL Procedure Manual: neo4j.com/docs/cypher-manual/current/clauses/call/
- Movies Java Spring Boot JDBC Example: github.com/neo4j-examples/movies-java-spring-boot-jdbc
- Neo4j-contrib JDBC Issue #232: github.com/neo4j-contrib/neo4j-jdbc/issues/232

### Driver Source Code (version 6.11.0)

All source references link to the [6.11.0 tag](https://github.com/neo4j/neo4j-jdbc/tree/6.11.0) of https://github.com/neo4j/neo4j-jdbc.

- **Statement execution path**: [`StatementImpl.executeQuery()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/StatementImpl.java#L148-L171) → [`processSQL()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/StatementImpl.java#L737-L748)
- **FORCE_CYPHER hint**: [Pattern definition](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L103-L107), [`forceCypher()` method](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1074-L1083), [`TranslatorChain.apply()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1099-L1142)
- **CallableStatement**: [`CallableStatementImpl`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/CallableStatementImpl.java#L64-L122)
- **SQL-to-Cypher translator**: [`SqlToCypher.java`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java) — [`build()` dispatcher](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L345-L369), [`statement(Select<?>)`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L440-L503)
- **JOIN handling**: [`JoinDetails.of()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L296-L306), [joins documentation](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/test/resources/joins.adoc)
- **Cypher-backed views**: [`View` record (SPI)](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/spi/src/main/java/org/neo4j/jdbc/translator/spi/View.java#L38-L48), [`ViewDefinitionReader`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/ViewDefinitionReader.java#L47-L113), [`callRawCypher()` embedding](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L516-L520), [CBV restrictions](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/docs/src/main/asciidoc/modules/ROOT/pages/cypher_backed_views.adoc#L135-L144)
- **Test coverage for GROUP BY silent drop**: [`SqlToCypherTests.aggregates()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/test/java/org/neo4j/jdbc/translator/impl/SqlToCypherTests.java#L551-L568)
