# Remaining Work Plan: GROUP BY / HAVING Testing

## Current Baseline

- **Unit tests:** 445, 0 failures, 0 errors
- **Integration tests:** 56, all passing (non-HAVING only)
- **Production code:** Phases 1–6 complete, no outstanding code changes
- **Checkstyle:** 72 remaining errors, all pre-existing `InnerTypeLast` (structural ordering)
- **Target:** ~515 total tests across all categories

---

## Phase 7: Hardening & Edge Cases — COMPLETE

**Status:** Done. 445 unit tests, 0 failures. All modules BUILD SUCCESS.

### Completed Items

- **7.1 DISTINCT + WITH Tests (3 tests)** — Verify DISTINCT applies only to final RETURN, never to WITH
- **7.2 LIMIT/OFFSET + WITH Tests (3 tests)** — Verify LIMIT and OFFSET placement after RETURN when WITH is present
- **7.3 Full Combination "Kitchen Sink" Tests (2 tests)** — All clauses combined; second test adds WHERE + multiple aggregates
- **7.4 GROUP BY Validation Comment** — Design decision documented in `requiresWithForGroupBy()` Javadoc
- **7.5 Registry Cleanup Test (1 test)** — Sequential translations don't leak alias registry state
- **7.6 Formatting & Compliance** — Spring JavaFormat applied; Javadoc `@param`/`@return` added to `requiresWithForGroupBy()`, `buildWithClause()`, `havingCondition()`; unused `Asterisk` import removed from `JooqQomDiagnosticTests`
- **7.7 Code Path Coverage (3 tests)** — WHERE+GROUP BY simple path, WHERE+GROUP BY WITH path, ORDER BY aggregate alias without GROUP BY
- **Test fix:** `fullGroupByCombinationWithGroupByMismatch` expectation corrected for aggregate deduplication behavior

---

## Phase 8: HAVING Integration Tests — COMPLETE

**Status:** Done. 23 new integration tests in `HavingIT.java`, 0 failures. GroupByIT refactored (56 tests, 0 regressions). Total: 79 integration tests passing.

**Refactoring:** Extracted `collectRows` helper from `GroupByIT` to `TestUtils` for shared use. Created `HavingIT.java` as a separate test class extending `IntegrationTestBase`.

### Completed Items

- **8.1 Basic HAVING Filtering (4 tests)** — count, sum, avg, max thresholds verified
- **8.2 HAVING with Aggregate Not in SELECT (3 tests)** — Hidden `__having_col_*` columns confirmed absent from ResultSetMetaData
- **8.3 Compound HAVING (3 tests)** — AND, OR, and nested `(AND) OR` conditions verified
- **8.4 HAVING by Alias (2 tests)** — `HAVING cnt > 1` and `HAVING total > 50` resolve via AliasRegistry name-based fallback
- **8.5 ORDER BY + HAVING Combined (3 tests)** — ORDER BY aggregate DESC, column ASC, and ORDER BY + LIMIT with HAVING
- **8.6 HAVING + WHERE Interaction (2 tests)** — WHERE filters before aggregation, HAVING filters after; verified with precise row counts
- **8.7 HAVING + JOIN (3 tests)** — NATURAL JOIN with HAVING count, HAVING not in SELECT (metadata check), compound HAVING with min(born)
- **8.8 HAVING Kitchen Sink (3 tests)** — Full DISTINCT + WHERE + GROUP BY + HAVING + ORDER BY + LIMIT + OFFSET; multiple aggregates; Movies JOIN kitchen sink

**Run command:** `./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp -Dit.test="GroupByIT,HavingIT" verify`

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
