# GROUP BY / HAVING — Remaining Test Work

## Current State

- **Unit tests:** 445 passing, 0 failures, 0 errors
- **Integration tests:** 79 passing (56 GroupByIT + 23 HavingIT), 0 failures
- **Production code:** Phases 1-8 complete, all code paths tested
- **No debugging artifacts:** No `System.out.println`, no TODO/FIXME markers, no temporary tests
- **Formatting:** Spring JavaFormat applied; Javadoc `@param`/`@return` tags added to key methods
- **Checkstyle:** 72 pre-existing `InnerTypeLast` violations (structural ordering, not introduced by this work)

---

## Completed Phases

| Phase | Description | Tests Added | Status |
|-------|-------------|-------------|--------|
| 1-6 | Core GROUP BY / HAVING implementation | ~400+ unit | Complete |
| 7 | Hardening and edge cases | 12 unit | Complete |
| 8 | HAVING integration tests | 23 integration | Complete |

---

## Phase 9: Expanded Integration Test Suite (Remaining)

**Goal:** Comprehensive coverage matching the full specification.
**Estimated new tests:** ~50-70 additional
**Priority:** Lower — pursue after Phases 7-8 are merged and stable

### Priority 1: Core Correctness (~15 tests)

- NULL handling in GROUP BY (NULLs as a group key, NULL in aggregates). Cypher handles NULLs differently than SQL in some cases (e.g., `count(null_prop)` returns 0, `sum(null_prop)` returns null).
- Empty result sets (GROUP BY on zero matching rows should return no rows, not one row with null aggregates)
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

- GROUP BY on expressions (e.g., `GROUP BY YEAR(released)` — requires expression-level translation that the current implementation does not handle; jOOQ parses these into complex QOM nodes)
- Very long GROUP BY column lists
- GROUP BY with CASE expressions
- Aliased table references in GROUP BY
- GROUP BY by ordinal (`GROUP BY 1, 2` — jOOQ does not resolve ordinals, producing a field named `"1"`; would require manual resolution from the SELECT list)

**Exit criteria:** Full integration suite passes, no regression. Target: ~125+ total integration tests.

---

## Code Formatting Compliance

**Effort: Small | Value: Low-Medium**

### Fix pre-existing checkstyle violations

72 `InnerTypeLast` violations exist across `SqlToCypher.java` and `AliasRegistry.java`. These are structural ordering issues (inner types defined before methods) that predate the GROUP BY work. Fixing them would mean moving record definitions and inner classes to the end of their enclosing classes.

**Risk:** These are mechanical refactors but touch many lines, producing a large diff. Consider fixing in a separate commit to keep GROUP BY changes reviewable.

### Run full formatting pipeline

```bash
./mvnw spring-javaformat:apply && ./mvnw license:format && ./mvnw sortpom:sort
```

---

## Not Recommended

These were considered and explicitly deferred:

1. **GROUP BY validation** — Rejecting queries where non-aggregated SELECT columns are missing from GROUP BY. Decision: silently translate, matching MySQL's permissive mode. Cypher handles this correctly. Documented in `requiresWithForGroupBy()` Javadoc.

2. **Configuration flags** — No backward-compatibility flags for the new WITH clause generation. The previous output was wrong; the new output is correct. No opt-out needed.

3. **HAVING without GROUP BY validation** — SQL technically allows `HAVING` without `GROUP BY`. The translator handles this correctly by treating the entire table as one group. No validation needed.

---

## Running Tests

```bash
# Unit tests only (Phase 7)
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl clean package

# Integration tests (Phase 8, requires Docker)
./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp -Dit.test="GroupByIT,HavingIT" verify

# Full regression
./mvnw verify

# Single test class
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=SqlToCypherTests

# Formatting
./mvnw spring-javaformat:apply && ./mvnw license:format && ./mvnw checkstyle:check
```
