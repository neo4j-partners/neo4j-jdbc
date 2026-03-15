# GDS Procedure Calls Through Pure SQL: A Hint-Based Escape Hatch

## The Problem

The Neo4j JDBC driver's SQL-to-Cypher translator handles relational access patterns well: reads, writes, joins, aggregates. But GDS procedure calls have no SQL equivalent. The `CALL procedure(args) YIELD columns` pattern is graph-specific syntax with no relational analog. Today, applications that need both SQL translation and GDS procedures must choose between two code-level escape hatches: the `FORCE_CYPHER` hint (which bypasses translation entirely, requiring the application to embed raw Cypher) or Cypher-backed views (which wrap static Cypher behind a table name, but cannot accept parameters).

Neither mechanism solves the POC requirement from [GDS_SHORT.md](GDS_SHORT.md): "I have one or more pharmacies of interest. Run PageRank with those pharmacies as source nodes." The source pharmacies change per request. FORCE_CYPHER requires the calling application to construct Cypher strings. CBVs run the algorithm with fixed inputs and filter afterward, producing semantically wrong results. A SQL-only client cannot express a parameterized GDS call through the current translation layer.

The question is whether structured SQL comments or optimizer hints could bridge this gap without requiring clients to write Cypher.

## How FORCE_CYPHER Works Today

The existing hint mechanism establishes the architectural pattern any extension would follow. Understanding its exact behavior reveals both what's possible and what constrains the design space.

The driver defines a single regex pattern in [`ConnectionImpl`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L103-L107):

```java
private static final Pattern PATTERN_ENFORCE_CYPHER = Pattern
    .compile("(['`\"])?[^'`\"]*/\\*\\+ NEO4J FORCE_CYPHER \\*/[^'`\"]*(['`\"])?");
```

The [`forceCypher()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1074-L1083) method scans the raw SQL string for this pattern, skipping matches that appear inside quoted strings (where capture groups 1 and 2 are equal). If found, the [`TranslatorChain.apply()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1099-L1142) method short-circuits and returns the statement unchanged. The entire translation pipeline is bypassed.

This check happens *before* the statement reaches jOOQ's parser. This ordering is critical: jOOQ's lexer strips SQL comments during parsing. A `/* */` comment in the input SQL does not survive into the parsed `Query` AST. Any hint-based approach that depends on comment content reaching `SqlToCypher.java` will fail silently, because by the time `SqlToCypher.translate()` receives the parsed query, the comments are gone.

The FORCE_CYPHER mechanism is therefore a raw-string interception, not a translation feature. It operates at a layer below the translator.

## Three Candidate Approaches

### Approach A: Structured Optimizer Hints (Pre-jOOQ Interception)

The most natural extension of the FORCE_CYPHER pattern. A new hint syntax embeds a procedure call template inside a SQL comment, and the translator intercepts it before jOOQ parsing.

**Syntax example:**

```sql
/*+ NEO4J CALL gds.pageRank.stream($graphName, {sourceNodes: $sourceNodes})
    YIELD nodeId, score */
SELECT nodeId, score
FROM dual
WHERE source_npis IN (?, ?)
ORDER BY score DESC
LIMIT 25
```

**How it would work:**

1. A new regex pattern in `ConnectionImpl` (or early in `SqlToCypher.translate()`) detects the `/*+ NEO4J CALL ... YIELD ... */` block.
2. The procedure name, argument template, and YIELD columns are extracted from the hint.
3. The hint is stripped from the SQL string.
4. The remaining SQL (`SELECT ... FROM dual WHERE ... ORDER BY ... LIMIT ...`) is parsed by jOOQ normally for its WHERE, ORDER BY, and LIMIT clauses.
5. The translator generates Cypher that wraps the procedure call in a `CALL {}` subquery, maps YIELD columns to WITH aliases, and applies the SQL WHERE/ORDER BY/LIMIT as post-call filtering.

**Generated Cypher:**

```cypher
CALL {
  CALL gds.pageRank.stream($graphName, {sourceNodes: $sourceNodes})
  YIELD nodeId, score
  RETURN nodeId, score
}
WITH nodeId, score
WHERE source_npis IN $p1
ORDER BY score DESC
LIMIT 25
```

**What makes this feasible:** The interception point already exists. `ConnectionImpl` already scans raw SQL for comment patterns before jOOQ parsing. Adding a second pattern alongside `PATTERN_ENFORCE_CYPHER` is architecturally consistent. The Cypher generation can reuse existing infrastructure: `Cypher.callRawCypher()` for the CALL block (same as CBVs use), the existing `condition()` method for WHERE translation, and existing ORDER BY and LIMIT handling.

**What makes this hard:** The hint contains a raw Cypher procedure call string. The translator must parse this string to extract the procedure name, arguments, and YIELD columns. This is a mini-parser inside the translator, operating on Cypher syntax embedded in a SQL comment. Argument templates (`$graphName`, `{sourceNodes: $sourceNodes}`) need to be mapped to either JDBC positional parameters or named parameters. The mapping between SQL WHERE predicates and Cypher parameters is not obvious; some WHERE conditions are post-filters (applied after the procedure runs), while others should become procedure arguments. The hint syntax must specify which is which, making the hint increasingly complex.

**Estimated complexity:** ~300-500 lines of new code across `ConnectionImpl` and `SqlToCypher`. A new regex for hint detection, a mini-parser for the CALL/YIELD block, parameter mapping logic, and Cypher generation. Medium difficulty.

### Approach B: Parameterized Cypher-Backed Views

Extend the existing CBV mechanism so that declared parameter columns in the view definition are extracted from the SQL WHERE clause and injected into the view's Cypher query as bound parameters, rather than applied as post-filters.

**View definition (JSON):**

```json
{
  "name": "pagerank_by_source",
  "query": "MATCH (source:ResolvedPharmacy) WHERE ANY(npi IN source.npis WHERE npi IN $source_npis) WITH collect(source) AS sourceNodes CALL gds.pageRank.stream('projection', {sourceNodes: sourceNodes}) YIELD nodeId, score RETURN nodeId, score",
  "columns": [
    {"name": "nodeId", "type": "INTEGER"},
    {"name": "score", "type": "FLOAT"}
  ],
  "parameters": [
    {"name": "source_npis", "sqlPredicate": "source_npis", "type": "LIST"}
  ]
}
```

**SQL:**

```sql
SELECT * FROM pagerank_by_source
WHERE source_npis IN ('1234567890', '0987654321')
ORDER BY score DESC LIMIT 25
```

**How it would work:**

1. The translator detects the CBV reference as it does today.
2. For each declared parameter in the view definition, the translator searches the SQL WHERE clause for a predicate on that column name.
3. The predicate value is extracted and bound as a Cypher parameter (`$source_npis`).
4. The matched predicate is removed from the SQL WHERE; remaining predicates become post-call filters.
5. The view's Cypher query executes with the bound parameters inside the `CALL {}` block.

**What makes this feasible:** The CBV infrastructure already exists. The [`View`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/spi/src/main/java/org/neo4j/jdbc/translator/spi/View.java#L38-L48) record, [`ViewDefinitionReader`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/ViewDefinitionReader.java#L47-L113), and [`createOngoingReadingFromViews()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L505-L524) provide the scaffolding. The `View` record would gain a `parameters` field. The view definition file is controlled by the application, not by end users, so the Cypher template is trusted.

**What makes this hard:** Predicate extraction from a jOOQ `Condition` AST is genuinely complex. The WHERE clause can contain AND/OR trees, nested conditions, IN lists, BETWEEN ranges, function calls, and subqueries. The translator must identify which subtree of the Condition corresponds to a declared parameter, extract its value(s), and remove it from the tree without corrupting the remaining conditions. This is a partial AST rewrite, not a simple string operation.

The [`View`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/spi/src/main/java/org/neo4j/jdbc/translator/spi/View.java) record is part of the translator SPI (`neo4j-jdbc-translator/spi`), a public API. Adding a `parameters` field changes the SPI contract. Existing view definition files that lack a `parameters` key would need to remain valid (the field would default to an empty list), but any consumer of the SPI would see a changed record signature.

Parameter type mapping adds another layer. An `IN (?, ?)` predicate in SQL should become a Cypher list parameter `$source_npis: ['1234567890', '0987654321']`. An `= ?` predicate should become a scalar parameter. The view definition must declare the expected predicate shape, and the translator must validate that the SQL predicate matches.

**Estimated complexity:** ~500-800 lines across `View.java` (SPI change), `ViewDefinitionReader.java` (parameter parsing), and `SqlToCypher.java` (predicate extraction and parameter binding). High difficulty, primarily due to the AST manipulation.

### Approach C: SQL CALL Syntax Interception

Map SQL's standard `CALL procedure_name(args)` syntax or table-valued function syntax (`SELECT * FROM TABLE(procedure_name(args))`) to Cypher procedure calls.

**Syntax example:**

```sql
SELECT nodeId, score
FROM TABLE(gds_pageRank_stream('projection', ARRAY['1234567890']))
ORDER BY score DESC LIMIT 25
```

**What makes this feasible in theory:** SQL has a standard CALL statement, and jOOQ does parse it. The `build()` method in `SqlToCypher` currently throws for unrecognized query types; a CALL handler could be added. Table-valued functions exist in several SQL dialects and map conceptually to procedure calls that return result sets.

**What makes this impractical:** jOOQ's CALL parsing produces a `QOM.Call` or similar type, but the YIELD clause has no SQL equivalent. Cypher procedures declare which columns they return via YIELD; SQL has no syntax for this. The translator would need a procedure registry that maps procedure names to their output columns: `gds_pageRank_stream` yields `(nodeId: Long, score: Double)`. This registry must be maintained in sync with the GDS version installed on the server, which the driver has no way to discover at parse time.

Additionally, jOOQ's table-valued function support varies by dialect. The default `SQLDialect.DEFAULT` used by the translator may not parse `TABLE()` syntax. Switching dialects to get TVF support could break existing SQL translation behavior.

The naming convention also creates friction. SQL identifiers cannot contain dots, so `gds.pageRank.stream` must become `gds_pageRank_stream` or require a mapping table. Underscore-based names already conflict with the translator's relationship inference pattern (`Person_ACTED_IN_Movie`).

**Estimated complexity:** ~800-1200 lines. New query type handler in `build()`, procedure registry, YIELD inference, parameter mapping, dialect considerations. High difficulty with significant design surface area.

## Security Analysis

All three approaches share a fundamental security characteristic with the existing FORCE_CYPHER mechanism: the SQL statement string is the trust boundary. If an untrusted user can control the SQL string sent to the driver, they can already inject arbitrary Cypher via `/*+ NEO4J FORCE_CYPHER */`. None of these proposals create a new attack surface; they extend an existing one.

That said, the approaches differ in how they constrain what gets executed.

**Approach A (structured hints)** embeds raw Cypher in the SQL string. The procedure call template is attacker-controlled if the SQL string is attacker-controlled. An injection like `/*+ NEO4J CALL dbms.security.createUser('admin', 'password', false) YIELD value */` would execute a privilege escalation. The hint syntax provides no allowlist or constraint on which procedures can be called. This is the same security model as FORCE_CYPHER, not worse, but no better.

**Approach B (parameterized CBVs)** is structurally safer. The Cypher template lives in a JSON configuration file loaded at connection time, not in the SQL string. The SQL string can only supply parameter values, not change the procedure being called. Parameterized queries use Bolt protocol parameter binding, which prevents injection into the Cypher template. An attacker who controls the SQL string can supply unexpected parameter values but cannot change which procedure runs or alter the Cypher structure. This is a meaningful security improvement over Approaches A and C.

**Approach C (SQL CALL syntax)** depends on the procedure registry design. An open registry (any procedure name maps to a Cypher CALL) has the same problem as Approach A. An allowlist registry (only declared procedures can be called) provides the same constraint as Approach B, at the cost of requiring configuration.

### Parameter Injection Risks

Approaches A and C must handle parameter binding carefully. If procedure arguments are constructed by string concatenation rather than Bolt parameter binding, they are vulnerable to Cypher injection. The existing CBV mechanism avoids this because the view query is a static string passed to `Cypher.callRawCypher()`. Parameterized CBVs (Approach B) maintain this property as long as parameters are bound through the Bolt protocol, not interpolated into the query string.

## Feasibility Assessment

| Criterion | Approach A (Structured Hints) | Approach B (Parameterized CBVs) | Approach C (SQL CALL) |
|---|---|---|---|
| Architectural consistency | High (extends FORCE_CYPHER pattern) | High (extends CBV pattern) | Low (new query type handler) |
| jOOQ compatibility | N/A (pre-jOOQ interception) | Compatible (WHERE parsing post-jOOQ) | Uncertain (dialect-dependent) |
| SPI impact | None (ConnectionImpl only) | Yes (View record change) | Yes (new registry interface) |
| Security model | Same as FORCE_CYPHER | Stronger (template is config, not SQL) | Depends on registry design |
| "Pure SQL" from client | No (Cypher in comment) | Yes | Mostly (naming convention friction) |
| Parameterization | Complex mapping | Natural (WHERE to parameter) | Natural (function args) |
| Implementation effort | ~300-500 lines, medium | ~500-800 lines, high | ~800-1200 lines, high |
| GDS version coupling | None | None (template in config) | High (procedure registry) |

## Recommendation

**Approach B (Parameterized CBVs) is the strongest candidate**, despite its higher implementation complexity.

It is the only approach that delivers genuinely pure SQL from the client's perspective. The client writes `SELECT * FROM pagerank_by_source WHERE source_npis IN (?, ?) ORDER BY score DESC LIMIT 25`. No Cypher appears in the SQL string. No special comment syntax is required. An external BI tool or SQL client that can issue parameterized SELECT statements can call GDS procedures without modification.

It is also the only approach with a stronger security model than FORCE_CYPHER. The Cypher template is defined in a configuration file controlled by the application operator, not in the SQL string controlled by the client. Parameter values are bound through Bolt protocol parameterization. The attack surface is limited to unexpected parameter values, not arbitrary Cypher execution.

The primary cost is implementation complexity. Predicate extraction from jOOQ's Condition AST is a non-trivial tree manipulation. The SPI change to the `View` record requires careful versioning. But both costs are one-time, and the resulting mechanism is reusable for any procedure that accepts parameters, not just GDS.

### Approach A as an interim step

If the full CBV parameterization is too costly for an initial implementation, Approach A (structured hints) is a viable interim step. It extends an existing pattern, requires no SPI changes, and can be implemented in ~300-500 lines. The tradeoff is that it does not achieve "pure SQL" (the client must embed Cypher in a comment) and it does not improve the security model. It is a power-user escape hatch, not a SQL abstraction.

The two approaches are not mutually exclusive. Approach A could ship first as a low-cost improvement, with Approach B following as the more complete solution.

### Approach C is not recommended

The SQL CALL / TABLE() function approach requires the most implementation effort, has uncertain jOOQ dialect compatibility, creates a maintenance burden through the procedure registry, and delivers a less natural SQL experience than parameterized CBVs. The naming convention friction (`gds_pageRank_stream` vs. `gds.pageRank.stream`) adds cognitive overhead without adding capability.

## What This Does Not Solve

Even with parameterized CBVs, certain GDS operations remain outside the SQL boundary:

- **Projection management** (create, drop, exists check) requires imperative procedure calls with side effects. These are not read operations and cannot be wrapped in a SELECT-based CBV. They would still require FORCE_CYPHER or application-level Cypher.
- **Algorithm configuration beyond parameters** (custom graph projections, algorithm-specific tuning maps) would require either very complex view definitions or multiple views for different configurations.
- **Streaming vs. mutate vs. write modes** (GDS procedures have multiple execution modes) would each need separate view definitions. A single `pagerank` view cannot serve both `gds.pageRank.stream` and `gds.pageRank.mutate`.

The honest boundary remains: SQL covers relational access patterns and pre-defined analytical queries. Cypher handles graph-specific operations that require imperative control flow. Parameterized CBVs push the boundary further into GDS territory, but they do not eliminate it.

## Databricks Unity Catalog Integration

The [neo4j-uc-integration](https://github.com/neo4j-partners/neo4j-uc-integration) project connects Neo4j to Databricks through a Unity Catalog JDBC connection. The [neo4j-unity-catalog-connector](https://github.com/neo4j-labs/neo4j-unity-catalog-connector) provides a single shaded JAR published to Maven Central that bundles the Neo4j JDBC driver, the SQL-to-Cypher translator, and a Spark subquery cleaner. Users download this JAR and upload it to a UC Volume. They do not rebuild it. The question is whether CBVs can work through this pipeline when view definitions are managed separately from the connector JAR.

### How the UC Query Pipeline Works

The query path is: Databricks notebook sends SQL via `spark.read.format("jdbc")` or `remote_query()` → Spark wraps it in a subquery for schema probing (`SELECT * FROM (<query>) SPARK_GEN_SUBQ_0 WHERE 1=0`) → the SafeSpark sandbox forwards it to the Neo4j JDBC driver → the Spark cleaner strips the wrapping → the SQL-to-Cypher translator converts it to Cypher → the driver sends Cypher to Neo4j over Bolt.

The JDBC URL is set once when creating the UC connection:

```sql
CREATE CONNECTION neo4j_connection TYPE JDBC
OPTIONS (
  url 'jdbc:neo4j+s://host:7687/neo4j?enableSQLTranslation=true',
  ...
)
```

The `viewDefinitions` property can be added to this URL. The driver's [`SqlToCypherConfig`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypherConfig.java#L75) maps `viewDefinitions` from the JDBC URL to `s2c.viewDefinitions` internally. The [`ViewDefinitionReader`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/ViewDefinitionReader.java#L47-L113) supports four URI schemes: `file://`, `http://`, `https://`, and `resource://`. The `resource://` scheme loads from the JAR's classpath, but the connector JAR is a published artifact on Maven Central — users cannot add files to it. The view definitions must come from outside the JAR.

### Where View Definitions Can Live

The `ViewDefinitionReader` resolves the URI via `java.net.URI.toURL()` and opens it with standard Java I/O. The practical options for UC:

**Option 1: UC Volume path via `file://`**

UC Volumes are FUSE-mounted on cluster nodes at `/Volumes/catalog/schema/volume_name/`. Users already upload the connector JAR to a Volume. A `views.json` file uploaded to the same Volume would be referenced as:

```
viewDefinitions=file:///Volumes/main/jdbc_drivers/views/views.json
```

The full JDBC URL becomes:

```
jdbc:neo4j+s://host:7687/neo4j?enableSQLTranslation=true&viewDefinitions=file:///Volumes/main/jdbc_drivers/views/views.json
```

The open question is whether the SafeSpark sandbox can access Volume FUSE mounts via `java.io.File`. The sandbox loads the connector JAR from a Volume path (via the `java_dependencies` option), but Databricks infrastructure may copy the JAR into the sandbox rather than reading it through FUSE at runtime. If the FUSE mount is not accessible from within the sandbox, `file://` will fail with a `FileNotFoundException`.

This is the simplest option for users (upload a JSON file next to the JAR, reference it in the URL) and should be tested first.

**Option 2: HTTPS endpoint**

The `ViewDefinitionReader` supports `https://` URIs. The view definitions could be hosted at any HTTPS-accessible endpoint:

```
viewDefinitions=https://raw.githubusercontent.com/org/repo/main/views.json
```

This works if the SafeSpark sandbox allows outbound HTTPS connections, which it does (the driver connects to Neo4j Aura over TLS). A GitHub raw URL, an S3 pre-signed URL, or a simple HTTPS file server all work. The downside is an external dependency: if the endpoint is unavailable at connection time, every JDBC connection fails.

For prototyping, a GitHub raw URL pointing to a views.json in the neo4j-uc-integration repo is the fastest path. For production, the endpoint needs to be reliable and version-controlled.

**Option 3: DBFS path (not supported)**

DBFS paths (`/dbfs/...` or `dbfs://...`) are not a supported URI scheme in `ViewDefinitionReader`. The supported set is `file`, `http`, `https`, and `resource`. Adding DBFS support would require a driver change.

### Can Static CBVs Work Through UC Today?

Yes, with high confidence, provided the view definitions file is accessible from the SafeSpark sandbox. The mechanism requires no driver changes. Users create a JSON file defining their views, make it accessible via one of the supported URI schemes, and add the `viewDefinitions` parameter to the JDBC URL.

A static CBV for all-nodes PageRank would look like:

```json
[
  {
    "name": "pagerank_all",
    "query": "CALL gds.pageRank.stream('projection') YIELD nodeId, score MATCH (n) WHERE id(n) = nodeId RETURN n.name AS name, labels(n)[0] AS type, score",
    "columns": [
      {"name": "name", "type": "STRING"},
      {"name": "type", "type": "STRING"},
      {"name": "score", "type": "FLOAT"}
    ]
  }
]
```

The Databricks query would be:

```python
df = spark.read.format("jdbc") \
    .option("databricks.connection", "neo4j_connection") \
    .option("query", "SELECT * FROM pagerank_all") \
    .option("customSchema", "name STRING, type STRING, score DOUBLE") \
    .load()
```

The translator wraps the view's Cypher in a `CALL {}` subquery, applies any SQL WHERE as a post-filter, and the result flows back through Spark as a DataFrame.

### Spark Subquery Wrapping and CBVs

Spark wraps JDBC queries in a subquery for schema probing: `SELECT * FROM (<query>) SPARK_GEN_SUBQ_0 WHERE 1=0`. The Spark cleaner in the connector JAR detects the `SPARK_GEN_SUBQ_0` marker and strips the outer wrapping before the SQL reaches the translator. The inner query (`SELECT * FROM pagerank_all`) then goes through normal translation, where the translator detects `pagerank_all` as a CBV and generates the appropriate Cypher.

The risk is that the schema probe query (`WHERE 1=0`) still executes the full GDS procedure call just to discover column types. For expensive algorithms on large graphs, this is wasteful. The `customSchema` option in Spark bypasses this schema probe entirely by telling Spark the column types upfront. Every UC JDBC query in the integration guide already uses `customSchema` because the Neo4j JDBC driver returns `NullType()` during Spark's schema inference. This means the schema probe issue is already mitigated by existing best practice.

The current [validated query patterns](https://github.com/neo4j-partners/neo4j-uc-integration/blob/main/docs/neo4j_uc_jdbc_guide.md) show that aggregate queries work through UC JDBC while non-aggregate SELECT, ORDER BY, and LIMIT fail because of Spark's subquery wrapping. CBV queries would need to be tested to determine which category they fall into. The CBV's Cypher runs inside a `CALL {}` subquery, which may or may not survive the Spark wrapping depending on how the translator handles the nested structure. This is the primary unknown that the prototype must answer.

### What the UC Context Changes About the Recommendation

The UC integration strengthens the case for static CBVs as the first step, and shifts the parameterized CBV proposal from a driver-level concern to a prototyping opportunity that can be validated entirely through configuration.

In the UC context, the client is Spark. It sends SQL queries that the user writes in a Databricks notebook. The user has no way to embed Cypher hints or magic comments — Spark's JDBC connector sends exactly the SQL string from the `query` option. This eliminates Approach A (structured hints) from consideration for UC. The client cannot write `/*+ NEO4J CALL ... */` because the SQL passes through Spark's query planner, which does not preserve optimizer hints in JDBC pushdown.

Static CBVs work because Spark sends `SELECT * FROM view_name WHERE ...`, which is standard SQL that passes through Spark's planner unchanged. The translator sees the view name, looks up the Cypher, and generates the `CALL {}` subquery. The user never writes Cypher. The Cypher lives in a JSON file that the user uploads alongside the connector JAR.

Parameterized CBVs would extend this: `SELECT * FROM pagerank_by_source WHERE source_npis IN ('1234', '5678')` would extract the IN-list values, bind them as Cypher parameters, and inject them into the procedure call. But this requires driver-level changes (the `View` SPI, predicate extraction in `SqlToCypher`). The prototype should validate static CBVs first, then use the results to justify the driver investment.

### Prototype Plan

The prototype validates two things: (1) whether view definitions can be loaded from outside the connector JAR in the SafeSpark sandbox, and (2) whether CBV-backed queries work end-to-end through the UC/Spark pipeline. No driver changes are required.

**Step 1: Create view definitions file**

Create a `views.json` file with one or two static CBV definitions. Start with a simple Cypher query (not GDS) to isolate CBV behavior from GDS-specific concerns:

```json
[
  {
    "name": "movie_actors",
    "query": "MATCH (m:Movie)<-[:ACTED_IN]-(p:Person) RETURN m.title AS title, m.released AS released, collect(p.name) AS actors",
    "columns": [
      {"name": "title", "type": "STRING"},
      {"name": "released", "type": "INTEGER"},
      {"name": "actors", "type": "LIST"}
    ]
  }
]
```

**Step 2: Make the file accessible and update the JDBC URL**

Try both file delivery mechanisms in order. The first one that works determines the recommended pattern for documentation.

*Option A — Volume path:*

Upload `views.json` to a UC Volume (e.g., `/Volumes/main/jdbc_drivers/views/views.json`). Update the connection:

```sql
CREATE CONNECTION neo4j_connection TYPE JDBC
OPTIONS (
  url 'jdbc:neo4j+s://host:7687/neo4j?enableSQLTranslation=true&viewDefinitions=file:///Volumes/main/jdbc_drivers/views/views.json',
  ...
)
```

If this fails because the SafeSpark sandbox cannot access Volume FUSE mounts, fall back to Option B.

*Option B — HTTPS URL:*

Commit `views.json` to the neo4j-uc-integration repository (or any HTTPS-accessible location). Reference it via raw URL:

```sql
CREATE CONNECTION neo4j_connection TYPE JDBC
OPTIONS (
  url 'jdbc:neo4j+s://host:7687/neo4j?enableSQLTranslation=true&viewDefinitions=https://raw.githubusercontent.com/neo4j-partners/neo4j-uc-integration/main/views.json',
  ...
)
```

This has an external dependency but is guaranteed to work if outbound HTTPS is allowed (which it must be, since the driver connects to Aura over TLS).

**Step 3: Test through Databricks**

Run these queries in a Databricks notebook, increasing complexity:

```python
# Test 1: Basic CBV — does the view resolve?
df = spark.read.format("jdbc") \
    .option("databricks.connection", "neo4j_connection") \
    .option("query", "SELECT * FROM movie_actors") \
    .option("customSchema", "title STRING, released INT, actors STRING") \
    .load()
df.show()

# Test 2: CBV with WHERE — does post-filtering work?
df = spark.read.format("jdbc") \
    .option("databricks.connection", "neo4j_connection") \
    .option("query", "SELECT * FROM movie_actors WHERE title = 'The Matrix'") \
    .option("customSchema", "title STRING, released INT, actors STRING") \
    .load()
df.show()

# Test 3: CBV with aggregate — does it survive Spark wrapping?
df = spark.read.format("jdbc") \
    .option("databricks.connection", "neo4j_connection") \
    .option("query", "SELECT COUNT(*) AS cnt FROM movie_actors") \
    .option("customSchema", "cnt LONG") \
    .load()
df.show()
```

**Step 4: Test with GDS (if the graph has GDS)**

If the Neo4j instance has GDS and a graph projection, add a GDS-backed CBV to the JSON and test:

```json
{
  "name": "pagerank_all",
  "query": "CALL gds.pageRank.stream('projection') YIELD nodeId, score RETURN nodeId, score",
  "columns": [
    {"name": "nodeId", "type": "INTEGER"},
    {"name": "score", "type": "FLOAT"}
  ]
}
```

```python
df = spark.read.format("jdbc") \
    .option("databricks.connection", "neo4j_connection") \
    .option("query", "SELECT * FROM pagerank_all") \
    .option("customSchema", "nodeId LONG, score DOUBLE") \
    .load()
df.orderBy("score", ascending=False).show(25)
```

### What the Prototype Answers

| Question | How the prototype answers it |
|---|---|
| Can the SafeSpark sandbox read view definitions from a Volume path? | Step 2A: if `file:///Volumes/...` works, Volume-based CBVs are viable |
| Can the sandbox fetch view definitions over HTTPS? | Step 2B: fallback if Volume path fails |
| Do CBVs work through UC JDBC at all? | Test 1: basic SELECT from a CBV |
| Does Spark's subquery wrapping break CBV resolution? | Test 1 and 3: if the Spark cleaner passes the inner query through, the CBV resolves; if not, we'll see a "table not found" or translation error |
| Do non-aggregate CBV queries work through UC? | Test 1: `SELECT *` from a CBV — this is the pattern that currently fails for regular tables |
| Does WHERE filtering work on CBV results? | Test 2: post-call WHERE filtering |
| Can GDS procedure calls run inside CBVs through UC? | Test 4: end-to-end GDS through SQL |
| Is `customSchema` sufficient to avoid double-execution? | All tests: if results return without timeout, the schema probe is not causing full GDS re-runs |

If tests 1-3 pass, static CBVs are viable for UC and can be documented as a supported pattern. Users would upload a `views.json` alongside their connector JAR and reference it in the JDBC URL. If test 4 passes, GDS-through-SQL-via-CBV is validated. Either outcome provides the data needed to decide whether parameterized CBVs (Approach B) justify the driver-level investment.

If test 1 fails due to Spark subquery wrapping, the Spark cleaner may need a small extension to handle CBV-backed queries. This is a change in the connector project, not the driver, and is lower risk.

## Source Code References

All references are to the [6.11.0 tag](https://github.com/neo4j/neo4j-jdbc/tree/6.11.0) of https://github.com/neo4j/neo4j-jdbc.

- **FORCE_CYPHER pattern**: [`ConnectionImpl.java` L103-107](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L103-L107)
- **forceCypher() method**: [`ConnectionImpl.java` L1074-1083](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1074-L1083)
- **TranslatorChain.apply()**: [`ConnectionImpl.java` L1099-1142](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1099-L1142)
- **SqlToCypher.translate()**: [`SqlToCypher.java` L230-250](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L230-L250)
- **View record (SPI)**: [`View.java` L38-48](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/spi/src/main/java/org/neo4j/jdbc/translator/spi/View.java#L38-L48)
- **CBV query embedding**: [`SqlToCypher.java` L505-524](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L505-L524)
- **build() dispatcher**: [`SqlToCypher.java` L345-369](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L345-L369)
- **ViewDefinitionReader**: [`ViewDefinitionReader.java` L47-113](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/ViewDefinitionReader.java#L47-L113)
