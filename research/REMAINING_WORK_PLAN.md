# Remaining Work Plan: GROUP BY / HAVING Testing

## Current Baseline

- **Unit tests:** 425, 0 failures, 0 errors
- **Integration tests:** 56, all passing (non-HAVING only)
- **Production code:** Phases 1–6 complete, no outstanding code changes
- **Target:** ~515 total tests across all categories

---

## Phase 7: Hardening & Edge Cases

**Goal:** Cover untested code paths in the WITH clause, verify combinations, apply formatting.
**Estimated new tests:** 8–10
**Production code changes:** None (comment only)

### 7.1 DISTINCT + WITH Tests (3 tests)

Verify DISTINCT applies only to final RETURN, never to WITH:
- `SELECT DISTINCT name, count(*) FROM People GROUP BY name HAVING count(*) > 1`
- `SELECT DISTINCT dept, sum(age) FROM People GROUP BY dept ORDER BY dept`
- `SELECT DISTINCT name FROM People GROUP BY name HAVING count(*) > 2`

### 7.2 LIMIT/OFFSET + WITH Tests (3 tests)

Verify LIMIT and OFFSET placement after RETURN when WITH is present:
- `SELECT name, count(*) FROM People GROUP BY name HAVING count(*) > 1 LIMIT 5`
- `SELECT name, count(*) FROM People GROUP BY name HAVING count(*) > 1 LIMIT 5 OFFSET 2`
- OFFSET-only with WITH clause (if supported by jOOQ)

### 7.3 Full Combination "Kitchen Sink" Test (1–2 tests)

Exercise all clauses together in a single query:
```sql
SELECT DISTINCT dept, count(*), max(age)
FROM People
WHERE age > 18
GROUP BY dept
HAVING count(*) > 1 AND max(age) > 25
ORDER BY count(*) DESC
LIMIT 10 OFFSET 2
```

### 7.4 GROUP BY Validation Comment

Add a code comment documenting the design decision to silently translate queries with non-aggregated SELECT columns missing from GROUP BY (matching MySQL's permissive mode). No logic change.

### 7.5 Registry Cleanup Test (1 test)

Verify two sequential translations don't leak alias registry state:
```java
translator.translate("SELECT name, count(*) FROM People GROUP BY name HAVING count(*) > 1");
translator.translate("SELECT name FROM People"); // must not use stale registry
```

### 7.6 Formatting & Compliance

- `./mvnw spring-javaformat:apply`
- `./mvnw license:format`
- `./mvnw checkstyle:check` — resolve any violations

### 7.7 Code Path Coverage Audit

Verify tests exist for all WITH-path branches:
- Path i: GROUP BY without WITH (simple path)
- Path k: GROUP BY with WITH (different columns)
- Path k2: GROUP BY with WITH + HAVING
- Path l: ORDER BY alias resolution through registry

**Exit criteria:** All new tests pass, `./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl clean package` green, checkstyle clean.

---

## Phase 8: HAVING Integration Tests

**Goal:** End-to-end validation of HAVING against a real Neo4j instance.
**Estimated new tests:** ~23
**Prerequisites:** Phase 7 complete, Docker available for Testcontainers

These tests extend the existing `GroupByIT.java` file (or add a new `@Nested` class within it).

### 8.1 Basic HAVING Filtering (4 tests)

- `HAVING count(*) > N` — verify rows are filtered
- `HAVING sum(col) > N` — verify aggregate threshold
- `HAVING avg(col) > N` — verify average filtering
- `HAVING min(col) < N` / `HAVING max(col) > N`

### 8.2 HAVING with Aggregate Not in SELECT (3 tests)

Hidden `__having_col_*` columns must not appear in result set:
- `SELECT name FROM People GROUP BY name HAVING count(*) > 1`
- `SELECT dept FROM People GROUP BY dept HAVING avg(age) > 30`
- `SELECT name, sum(age) FROM People GROUP BY name HAVING max(age) > 40`

### 8.3 Compound HAVING (3 tests)

- `HAVING count(*) > 1 AND max(age) > 25`
- `HAVING count(*) > 5 OR min(age) < 20`
- Nested: `HAVING (count(*) > 1 AND max(age) > 25) OR dept = 'Engineering'`

### 8.4 HAVING by Alias (2 tests)

- `SELECT count(*) AS cnt FROM People GROUP BY name HAVING cnt > 1`
- `SELECT sum(age) AS total FROM People GROUP BY dept HAVING total > 50`

### 8.5 ORDER BY + HAVING Combined (3 tests)

- `... HAVING count(*) > 1 ORDER BY count(*) DESC`
- `... HAVING sum(age) > 50 ORDER BY name ASC`
- `... HAVING count(*) > 1 ORDER BY max(age) DESC LIMIT 5`

### 8.6 HAVING + WHERE Interaction (2 tests)

Verify WHERE filters before aggregation and HAVING filters after:
- `WHERE age > 20 ... HAVING count(*) > 1` — WHERE reduces input rows
- `WHERE dept = 'Engineering' ... HAVING sum(age) > 50`

### 8.7 HAVING + JOIN (3 tests)

Using Movies graph with NATURAL JOIN:
- `SELECT m.title, count(*) FROM Movie m NATURAL JOIN Person p GROUP BY m.title HAVING count(*) > 5`
- JOIN with HAVING on aggregate not in SELECT
- JOIN with compound HAVING

### 8.8 HAVING Kitchen Sink (3 tests)

Full combination queries against Movies graph:
- `SELECT DISTINCT ... WHERE ... GROUP BY ... HAVING ... ORDER BY ... LIMIT ... OFFSET ...`
- Verify result correctness against known Movies graph data
- Multiple aggregates with HAVING filtering

**Exit criteria:** All HAVING integration tests pass, no regression in existing 56 tests. Run via `./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp verify`.

---

## Phase 9: Expanded Integration Test Suite (Optional)

**Goal:** Comprehensive coverage matching the full Tier 8 specification from `prodTesting.md`.
**Estimated new tests:** ~50–70 additional
**Priority:** Lower — pursue after Phases 7–8 are merged and stable

### Priority 1: Core Correctness (~15 tests)

- NULL handling in GROUP BY (NULLs as a group, NULL in aggregates)
- Empty result sets (GROUP BY on no matching rows)
- Single-row groups (every group has exactly one member)
- LIMIT 0 edge case
- Large cardinality GROUP BY (all unique values)

### Priority 2: Movies Graph Analytics (~15 tests)

Real-world queries against the Movies dataset:
- Directors by movie count with HAVING
- Actors by decade with HAVING on count
- Genre-based aggregations
- Co-actor counts with HAVING threshold
- Average rating/score by category

### Priority 3: Regression Safety (~10 tests)

Verify non-GROUP BY queries still work after all changes:
- Simple SELECT without GROUP BY
- Global aggregates (no GROUP BY clause)
- DML statements (INSERT, UPDATE, DELETE)
- JOIN queries without aggregation
- DISTINCT without GROUP BY

### Priority 4: Edge Cases (~10 tests)

- GROUP BY on expressions (e.g., `GROUP BY YEAR(released)` — if supported)
- Very long GROUP BY column lists
- GROUP BY with CASE expressions
- Aliased table references in GROUP BY

**Exit criteria:** Full integration suite passes, no regression. Target: ~125+ total integration tests.

---

## Execution Summary

| Phase | New Tests | Effort | Dependencies |
|-------|-----------|--------|--------------|
| **7: Hardening** | 8–10 | Small | None |
| **8: HAVING Integration** | ~23 | Medium | Phase 7, Docker |
| **9: Expanded Suite** | ~50–70 | Large | Phase 8, stable baseline |
| **Total** | ~80–100 | | |

### Recommended Order

1. **Phase 7** first — no Docker required, fast feedback, cleans up code
2. **Phase 8** next — validates HAVING end-to-end, catches translation bugs that unit tests miss
3. **Phase 9** last — optional hardening, pursue if time permits

### Running Tests

```bash
# Phase 7 (unit tests only)
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl clean package

# Phase 8 (integration tests, requires Docker)
./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp verify

# Full regression
./mvnw verify

# Single test class
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=SqlToCypherTests

# Formatting
./mvnw spring-javaformat:apply && ./mvnw license:format && ./mvnw checkstyle:check
```
