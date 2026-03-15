# GDS Procedures Through Pure SQL: What Works and What Doesn't

## The Question

The POC scenario is: a client sends arbitrary SQL to the Neo4j JDBC driver with `enableSQLTranslation=true`. The driver translates SQL to Cypher and returns results. Can GDS procedures — specifically parameterized PageRank and Louvain calls — be executed this way, without the client ever writing Cypher?

## The Short Answer

**No.** Parameterized GDS procedure calls cannot be executed through pure SQL. The driver provides Cypher-backed views (CBVs) to wrap arbitrary Cypher behind SQL table names, but CBVs do not support parameterization. A SQL WHERE clause on a CBV filters the results after the GDS algorithm runs — it cannot change the algorithm's inputs.

## What Works Through Pure SQL

| Operation | SQL | Works? |
|---|---|---|
| Node counts | `SELECT count(*) FROM ResolvedPharmacy` | Yes — direct translation |
| Node property reads | `SELECT names, npis FROM ResolvedPharmacy WHERE ...` | Yes — direct translation |
| Simple traversals | `SELECT * FROM ResolvedPharmacy NATURAL JOIN ResolvedPrescriber` | Yes — translates to `MATCH (p)-->(pr)` |
| Pre-computed GDS results (fixed inputs) | `SELECT * FROM pagerank_all_nodes ORDER BY score DESC LIMIT 25` | Yes — via CBV with static Cypher |
| Filtering pre-computed results | `SELECT * FROM louvain_communities WHERE pharmacy_count > 2` | Yes — WHERE filters after CALL {} |

## What Does Not Work Through Pure SQL

| Operation | Why |
|---|---|
| Parameterized PageRank (specific source pharmacies) | CBV query is static; WHERE clause applied after algorithm runs |
| Parameterized Louvain (custom iterations, tolerance) | Same — algorithm parameters cannot be injected from SQL |
| Projection management (create, drop, exists check) | No SQL equivalent for GDS procedures; CBVs are read-only |

## Why CBV Parameterization Fails

The critical limitation is in how the translator embeds CBV queries. The [`createOngoingReadingFromViews()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L505-L524) method generates Cypher in this order:

```
CALL { <static view query> }
WITH <column aliases>
WHERE <sql where clause>        ← applied here, AFTER the CALL block
```

The SQL WHERE clause becomes a Cypher WHERE applied after the `CALL {}` subquery returns. By Cypher scoping rules, this WHERE cannot pass values into the CALL block. The view's query is a [static string](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/spi/src/main/java/org/neo4j/jdbc/translator/spi/View.java#L38-L48) loaded from JSON at connection time with no templating mechanism.

A query like:

```sql
SELECT * FROM pagerank_results WHERE source_npis IN ('1234567890', '0987654321')
```

generates:

```cypher
CALL {
  MATCH (source:ResolvedPharmacy)
  WITH collect(source) AS sourceNodes          ← runs on ALL pharmacies
  CALL gds.pageRank.stream('projection', {sourceNodes: sourceNodes})
  YIELD nodeId, score
  RETURN ...
}
WITH ...
WHERE source_npis IN ['1234567890', '0987654321']   ← filters AFTER PageRank ran on everything
```

The PageRank algorithm runs on all nodes, then the results are filtered. This is both semantically wrong (personalized PageRank from specific sources is a different computation than PageRank from all sources, filtered) and wasteful.

## What This Means for the POC

The POC requirement from [FRAUD_DEMO_POC.md](FRAUD_DEMO_POC.md) is: "I have one or more pharmacies of interest. Run PageRank with the pharmacies of interest as the source nodes."

This is inherently a parameterized GDS call. The source pharmacies change per request. Pure SQL cannot express this through the JDBC driver's translation layer.

### What pure SQL can do

For the "graph only changes once a month, keep the projection up" scenario, a **pre-computed, all-nodes PageRank** could be stored as a CBV. The client would query:

```sql
SELECT * FROM pagerank_all ORDER BY score DESC LIMIT 25
```

This answers "what are the highest PageRank nodes in the graph?" — a valid analytical question, but not the POC's question of "what nodes are most connected to *these specific* pharmacies?"

### What requires Cypher

Personalized PageRank with dynamic source nodes requires Cypher. The JDBC driver provides two code-level mechanisms for this:

1. **`FORCE_CYPHER` hint** — `/*+ NEO4J FORCE_CYPHER */ MATCH (source:ResolvedPharmacy) WHERE ... CALL gds.pageRank.stream(...)` bypasses translation. The [driver documents this](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/docs/src/main/asciidoc/modules/ROOT/pages/sql2cypher.adoc#L68-L74) for exactly this use case. But this is a code-level escape hatch — the application embeds Cypher in the statement string. An external SQL-only client would not send this.

2. **`CallableStatementImpl`** — the driver's [`CallableStatementImpl`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/CallableStatementImpl.java#L64-L122) supports JDBC callable statement escape syntax. But this is also a code-level mechanism, not pure SQL.

### The honest boundary

The JDBC driver's SQL translation layer is designed for standard relational access patterns: reads, writes, joins, aggregates. GDS procedures are graph-specific computations with no SQL semantics. The driver handles both through the same connection, but the translation boundary is real:

- **SQL works for**: reading the graph as if it were relational tables
- **SQL does not work for**: invoking graph algorithms with dynamic parameters
- **CBVs bridge the gap for**: fixed, pre-computed analytics exposed as virtual tables
- **CBVs do not bridge the gap for**: parameterized analytics where inputs change per request

## Source Code References

All references are to the [6.11.0 tag](https://github.com/neo4j/neo4j-jdbc/tree/6.11.0) of https://github.com/neo4j/neo4j-jdbc.

- **CBV query embedding**: [`createOngoingReadingFromViews()`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L505-L524) — WHERE applied after CALL {} at [line 522-523](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java#L522-L523)
- **View record (static query)**: [`View.java`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/spi/src/main/java/org/neo4j/jdbc/translator/spi/View.java#L38-L48)
- **View loading (no templating)**: [`ViewDefinitionReader.java`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/ViewDefinitionReader.java#L47-L113)
- **FORCE_CYPHER hint**: [driver documentation](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/docs/src/main/asciidoc/modules/ROOT/pages/sql2cypher.adoc#L68-L74), [source implementation](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc/src/main/java/org/neo4j/jdbc/ConnectionImpl.java#L1074-L1083)
- **CBV restrictions**: [documentation](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/docs/src/main/asciidoc/modules/ROOT/pages/cypher_backed_views.adoc#L135-L144)
- **CBV integration tests** (static WHERE only): [`CypherBackedViewsIT.java`](https://github.com/neo4j/neo4j-jdbc/blob/6.11.0/neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/CypherBackedViewsIT.java)
