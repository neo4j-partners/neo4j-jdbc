# GDS Procedures Through the SQL-to-Cypher Translator: Limitations and Approaches Considered

## The Core Limitation

The Neo4j JDBC driver's SQL-to-Cypher translator converts standard SQL (SELECT, WHERE, JOIN, aggregates) into Cypher. GDS procedure calls cannot go through this translation. The `CALL procedure(args) YIELD columns` pattern is graph-specific syntax with no SQL equivalent. The translator has no concept of CALL mapped to SQL, and the YIELD clause has no SQL analog.

For static, pre-computed analytics (run PageRank on everything, expose the results), Cypher-backed views wrap the procedure call behind a SQL table name. For parameterized analytics (run PageRank from *these specific* source nodes), no pure SQL mechanism exists. The source nodes change per request, and CBV queries are static strings loaded at connection time with no templating.

## Approaches Considered

### Structured Optimizer Hints

Embed a Cypher procedure call inside a SQL comment (`/*+ NEO4J CALL gds.pageRank.stream(...) YIELD nodeId, score */`) and intercept it before jOOQ parsing, similar to how `FORCE_CYPHER` works today. Architecturally consistent with existing code, but the client must embed Cypher in the SQL string. Not pure SQL. Does not work through Databricks UC because Spark's query planner strips optimizer hints during JDBC pushdown.

### Parameterized Cypher-Backed Views

Extend the CBV mechanism so declared parameters in the view definition are extracted from the SQL WHERE clause and injected into the Cypher query as bound parameters. The client writes pure SQL (`SELECT * FROM pagerank_by_source WHERE source_npis IN (...)`), and the translator maps WHERE predicates to Cypher parameters inside the CALL block. Strongest security model (Cypher template is config, not user input). However, predicate extraction from jOOQ's Condition AST is complex, the View record is part of the translator SPI (public API), and the implementation effort is significant for a feature that may not be approved upstream.

### SQL CALL / Table-Valued Function Syntax

Map SQL's standard CALL statement or `SELECT * FROM TABLE(procedure(...))` to Cypher procedure calls. Requires a procedure registry mapping SQL function names to Cypher procedures and their YIELD columns, uncertain jOOQ dialect compatibility, and the highest implementation effort. Not recommended.

## Why None of These Were Pursued

All three approaches require changes to the Neo4j JDBC driver, which is maintained by Neo4j. The parameterized CBV approach additionally changes the translator SPI. Even if implemented and working, the changes would need to be accepted upstream by the Neo4j team. For the Databricks UC path specifically, the SafeSpark sandbox adds constraints (whether view definition files can be loaded from UC Volumes is untested) that could block the feature regardless of driver-level support.

The investment required to implement, test, and upstream any of these approaches is substantial relative to the likelihood of adoption. The simpler path for applications that need parameterized GDS calls is to use the `FORCE_CYPHER` hint (for direct JDBC) or the Neo4j Spark Connector / Python driver (for Databricks), both of which work today without driver modifications.

## What Works Today

| Operation | Mechanism | Pure SQL? |
|---|---|---|
| Basic graph reads (node properties, counts, traversals) | SQL-to-Cypher translation | Yes |
| Static GDS results (fixed inputs, pre-computed) | Cypher-backed views | Yes |
| Parameterized GDS calls | `FORCE_CYPHER` hint | No (Cypher in comment) |
| Parameterized GDS calls from Databricks | Neo4j Spark Connector or Python driver | No (Cypher directly) |

## Source Material

- [GDS_SHORT.md](GDS_SHORT.md) — CBV parameterization failure analysis with source code references
- [JDBC_RESEARCH.md](JDBC_RESEARCH.md) — Full driver research including SQL translation gaps and CBV constraints
- [DSL.md](DSL.md) — Cypher-DSL builder chain reference for translator internals
