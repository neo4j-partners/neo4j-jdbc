# GROUP BY / HAVING Implementation Overview

## Summary

The Neo4j JDBC SQL-to-Cypher translator has been extended with full GROUP BY and HAVING support across six implementation phases, plus a comprehensive integration test suite. The core challenge: Cypher has no `GROUP BY` clause, so grouping is implicit based on non-aggregated expressions in `RETURN`. When GROUP BY columns differ from SELECT or HAVING is present, a Cypher `WITH` clause bridges the semantic gap.

**Current state:** 425+ unit tests (0 failures), 56 integration tests (all passing), all production code complete through Phase 6.

---

## Architecture

### Translation Strategy

Two paths depending on query structure:

**Simple path:** GROUP BY columns match SELECT non-aggregates:
```
SQL:    SELECT name, count(*) FROM People GROUP BY name
Cypher: MATCH (p:People) RETURN p.name AS name, count(*) AS `count(*)`
```

**WITH path:** GROUP BY columns differ from SELECT, or HAVING is present:
```
SQL:    SELECT name FROM People GROUP BY name HAVING count(*) > 5
Cypher: MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0
        WHERE __having_col_0 > 5 RETURN name
```

### Alias Naming Conventions

| Prefix | Purpose | Visibility |
|--------|---------|------------|
| `__with_col_N` | SELECT expressions without a user alias | Visible in RETURN |
| `__group_col_N` | GROUP BY columns not in SELECT | Hidden (WITH only) |
| `__having_col_N` | HAVING-only aggregates not in SELECT | Hidden (WITH only) |

### New Files

| File | Lines | Purpose |
|------|-------|---------|
| `FieldMatcher.java` | 258 | Structural field equivalence matching, aggregate detection, condition tree walking |
| `AliasRegistry.java` | 89 | Expression-to-alias registry with structural and name-based lookup modes |

### Modified Files

| File | Change |
|------|--------|
| `SqlToCypher.java` | GROUP BY/HAVING translation logic: `requiresWithForGroupBy()`, `buildWithClause()`, `havingCondition()`, unified alias resolution in `expression()` |

---

## Implementation Phases

### Phase 1: Diagnostic Tests (35 tests)

Characterized jOOQ QOM (Query Object Model) behavior to inform the implementation:
- `count(*)` asterisk is `SQLField`, not `Asterisk`
- jOOQ does NOT resolve HAVING alias references to aggregates
- jOOQ does NOT resolve ORDER BY alias references
- jOOQ does NOT resolve GROUP BY ordinals
- Documented all QOM accessor behavior (`$select()`, `$from()`, `$where()`, `$orderBy()`, `$limit()`, `$distinct()`, `$groupBy()`, `$having()`)

### Phase 2: FieldMatcher (23 tests)

Structural field equivalence matcher handling:
- Alias unwrapping (comparing underlying expressions)
- Aggregate function matching (count, sum, min, max, avg, stddev_samp, stddev_pop)
- Column reference matching across different parse contexts
- `isAggregate()` for identifying aggregate functions
- `collectAggregates()` for walking HAVING condition trees

### Phase 3: AliasRegistry (16 tests)

Two-mode expression-to-alias registry:
- Structural matching for aggregates (exact expression comparison)
- Name-based fallback for unresolved alias references
- First-registration-wins deduplication
- Round-trip tested from parsed SQL through registration and lookup

### Phase 4: WITH Clause Generation

Core GROUP BY translation producing Cypher `WITH` clauses:
- SELECT field aliasing with internal naming
- GROUP BY-only hidden columns
- Multiple aggregate support
- Fixed semantically wrong output (e.g., `SELECT sum(age) FROM People GROUP BY name` was producing a single total instead of per-group sums)

### Phase 5: ORDER BY Alias Resolution

Unified alias resolution in `expression(Field<?>)`:
- ORDER BY after WITH clause resolves through alias registry
- Error detection for de-scoped variables (references to columns not in WITH)
- Try-finally cleanup for registry lifecycle
- Fixed simple HAVING as a side effect of unified interception

### Phase 6: HAVING Translation

Full HAVING condition support:
- Hidden WITH columns (`__having_col_N`) for aggregates only in HAVING
- `collectAggregates()` integration into `buildWithClause()`
- Deduplication of aggregates already present in SELECT
- Support for all comparison operators (`=`, `<>`, `<`, `<=`, `>`, `>=`, `BETWEEN`, `IN`, `LIKE`, `IS NULL`, etc.)
- Compound conditions (AND, OR, nested)
- Arithmetic expressions in HAVING
- COUNT(DISTINCT ...) in HAVING
- Mixed aggregate and non-aggregate HAVING conditions

---

## Testing Summary

### Unit Tests by Category

| Category | Tests | File |
|----------|-------|------|
| jOOQ QOM Diagnostics | 35 | `JooqQomDiagnosticTests.java` |
| FieldMatcher | 43 | `FieldMatcherTests.java` |
| AliasRegistry | 16 | `AliasRegistryTests.java` |
| SQL-to-Cypher Translation | ~330 | `SqlToCypherTests.java` |
| **Unit Total** | **~425** | |

### Integration Tests (GroupByIT.java, 56 tests)

All tests execute against a real Neo4j instance via Testcontainers with both controlled (`People`, 5 nodes, 3 departments) and realistic (Movies graph, ~38 movies, ~133 persons) datasets.

- **BasicGroupBy** (7 tests): count, sum, avg, min/max, multiple aggregates per group
- **GroupByNotInSelect** (5 tests): GROUP BY columns hidden from result set
- **MultipleGroupByColumns** (7 tests): composite GROUP BY keys
- **OrderByWithGroupBy** (8 tests): ORDER BY on aggregates and GROUP BY columns
- **LimitOffsetWithGroupBy** (5 tests): LIMIT and OFFSET with GROUP BY
- **DistinctWithGroupBy** (3 tests): DISTINCT combined with GROUP BY
- **GroupByWithWhere** (6 tests): WHERE clause filtering before aggregation
- **GlobalAggregationRegression** (5 tests): non-GROUP BY aggregates (regression safety)
- **EdgeCases** (5 tests): empty results, large cardinality, single-row groups
- **MultiAggregate** (3 tests): multiple aggregate functions in one query
- **RegressionTests** (5 tests): non-GROUP BY SQL patterns still work correctly

### Key Bugs Found and Fixed During Testing

1. **ORDER BY alias resolution**: ORDER BY referencing a SELECT alias failed when a WITH clause was present
2. **OFFSET translation**: OFFSET was not being translated in certain code paths
3. **Column labels**: Result set column labels were incorrect for aliased aggregate expressions

---

## Related Research (Completed)

### GDS Through SQL (Not Feasible)

Parameterized GDS procedure calls (e.g., personalized PageRank) cannot be executed through pure SQL. Cypher-backed views don't support parameterization; WHERE clauses filter results after the procedure runs. Three approaches were analyzed (structured hints, parameterized CBVs, SQL CALL); all require driver changes with uncertain upstream adoption. Current recommendation: use `FORCE_CYPHER` hint for direct JDBC, Neo4j Spark Connector for Databricks.

### JDBC Driver Capabilities (Documented)

Comprehensive research on SQL translation boundaries:
- **Works:** SELECT, WHERE, ORDER BY, LIMIT, OFFSET, aggregates, INSERT, UPDATE, DELETE, INNER JOIN, GROUP BY, HAVING
- **Limitations:** LEFT JOIN silently degrades to INNER JOIN, RIGHT/FULL/CROSS JOIN throws, UNION/INTERSECT/EXCEPT throws, window functions unsupported, CTEs unsupported
- **Key finding:** NATURAL JOIN is the only reliable SQL JOIN syntax for graph traversals; relationship properties are inaccessible through SQL JOIN
