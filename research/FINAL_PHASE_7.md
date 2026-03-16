# Phase 7: DISTINCT, LIMIT, and Hardening — Pre-Implementation Review and Plan

## Pre-Phase 7 Status

**Baseline:** 432 tests, 0 failures, 0 errors.

**Phases 1-6 are complete.** The core GROUP BY/HAVING implementation is functional. The translator correctly:
- Emits simple `MATCH ... RETURN` when GROUP BY columns match SELECT columns
- Generates `WITH` clauses when GROUP BY columns differ from SELECT
- Translates HAVING conditions to `WHERE` after `WITH`, with proper alias resolution
- Injects hidden WITH columns for HAVING-only aggregates
- Resolves ORDER BY through the alias registry when a WITH clause is present
- Detects and throws errors for ORDER BY on de-scoped variables after WITH
- Translates SQL `OFFSET` to Cypher `SKIP`, correctly ordered before `LIMIT`
- Cleans up the alias registry via try-finally

---

## Issues to Fix Before Phase 7

### No blocking issues found.

A thorough code review of `SqlToCypher.java`, `FieldMatcher.java`, and `AliasRegistry.java` found:

- **No TODO, FIXME, HACK, or XXX markers** in the GROUP BY/HAVING code paths
- **No `System.out.println` or debugging artifacts**
- **`havingCondition()` signature is clean** — unused `withExpressions` parameter was already removed in Phase 6
- **`finally` block properly clears `this.aliasRegistry = null`** (line 527)
- **DISTINCT placement is correct** — `returningDistinct()` is called on the post-WITH reading (lines 503-505), producing `RETURN DISTINCT`, not `WITH DISTINCT`
- **LIMIT/OFFSET placement is correct** — `addLimit()` receives the ordered projection (post-RETURN, post-ORDER-BY); OFFSET is applied via `.skip()` before LIMIT, and LIMIT attaches after the final RETURN (lines 654-683)

The code is in a clean state for Phase 7 work.

---

## Outstanding Questions

### Q1: Should Phase 7 add GROUP BY validation?

**Context:** The plan calls for optionally checking that every non-aggregated SELECT expression appears in the GROUP BY list. Queries like `SELECT department, name, count(*) FROM Employees GROUP BY department` are invalid in strict SQL — `name` is not aggregated and not in GROUP BY.

**Resolution (already decided in Phase 4 plan):** Silently translate. Cypher's implicit grouping handles this by grouping on all non-aggregated columns. Adding validation would mean rejecting queries that Cypher can actually execute. The decision was finalized in the design spec and confirmed during Phase 4. Phase 7 should document this decision with a code comment but not implement validation logic.

### Q2: Should DISTINCT + HAVING produce `RETURN DISTINCT` or is it redundant?

**Context:** When a query has both `SELECT DISTINCT` and `GROUP BY ... HAVING`, the GROUP BY already produces unique groups. DISTINCT is technically redundant but valid SQL.

**Resolution (already decided in Phase 4 plan):** Apply DISTINCT only to the final RETURN, never to the WITH. The current code at lines 503-505 already does this correctly — `returningDistinct()` is called on the post-WITH reading. Phase 7 only needs to add test coverage to verify this behavior.

### Q3: Does the translator handle OFFSET?

**Context:** SQL `OFFSET` (or `LIMIT x OFFSET y`) after GROUP BY + HAVING.

**Resolution: Implemented.** The `addLimit()` method (lines 654-667) reads `selectStatement.$offset()` from jOOQ's Select QOM and calls `projection.skip(expression(offset))` via Cypher-DSL's `TerminalExposesSkip` interface. SKIP is correctly applied before LIMIT, matching Cypher's required clause ordering. Three unit tests cover this (lines 1029-1031 of `SqlToCypherTests.java`), and one integration test (`GroupByIT.limitWithOffset()`) validates end-to-end. Phase 7 should add one test for OFFSET combined with a WITH clause (HAVING path), since the existing tests only exercise the non-WITH path.

### Q4: Are there any queries where the registry cleanup in `finally` could cause issues?

**Context:** The `finally` block at line 527 clears `this.aliasRegistry = null`. If an exception occurs during WITH construction, the registry is partially populated and then cleared. The next query translation starts fresh.

**Resolution:** This is correct. The builder is created fresh per `build()` call. The finally block ensures no stale state leaks. No issue.

---

## Phase 7 Implementation Plan

### 7.1 — Verify DISTINCT Placement with WITH

**What:** Confirm that `SELECT DISTINCT` with a WITH clause (from GROUP BY or HAVING) produces `RETURN DISTINCT`, not `WITH DISTINCT`.

**Code review:** Lines 503-505 of `statement(Select<?>)`:
```java
var projection = selectStatement.$distinct()
    ? effectiveReading.returningDistinct(finalResultColumnsSupplier.get())
    : effectiveReading.returning(finalResultColumnsSupplier.get());
```

When the WITH path is taken, `effectiveReading` is the post-WITH reading. `returningDistinct()` on this produces `RETURN DISTINCT` after the WITH. This is correct — `WITH DISTINCT` would deduplicate before aggregation, changing semantics.

**Production code change:** None expected. Verify with tests.

**Test cases to add:**

```
SELECT DISTINCT name, count(*) FROM People p GROUP BY name
→ MATCH (p:People) RETURN DISTINCT p.name AS name, count(*)
```
Note: This takes the common-case path (GROUP BY matches SELECT, no HAVING), so DISTINCT applies directly to the RETURN. No WITH clause.

```
SELECT DISTINCT count(*) FROM People p GROUP BY name
→ MATCH (p:People) WITH count(*) AS __with_col_0, p.name AS __group_col_1 RETURN DISTINCT __with_col_0
```
GROUP BY column not in SELECT forces WITH. DISTINCT applies to the final RETURN.

```
SELECT DISTINCT name FROM People p GROUP BY name HAVING count(*) > 5
→ MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN DISTINCT name
```
HAVING forces WITH. DISTINCT applies to the final RETURN, not the WITH.

---

### 7.2 — Verify LIMIT and OFFSET Placement with WITH

**What:** Confirm that LIMIT and OFFSET attach to the final RETURN, not the WITH clause.

**Code review:** Lines 521-524 (ordered projection and limit):
```java
var orderedProjection = projection
    .orderBy(selectStatement.$orderBy().stream().map(this::expression).toList());
return addLimit(forceLimit, selectStatement, orderedProjection).build();
```

Lines 654-667 (`addLimit()` handles both OFFSET and LIMIT):
```java
private StatementBuilder.BuildableStatement<ResultStatement> addLimit(boolean force, Select<?> selectStatement,
        StatementBuilder.OngoingMatchAndReturnWithOrder projection) {
    var offset = selectStatement.$offset();
    if (offset != null) {
        var afterSkip = projection.skip(expression(offset));
        return applyLimit(force, selectStatement, afterSkip);
    }
    return applyLimit(force, selectStatement, projection);
}
```

`addLimit()` receives the ordered projection (after RETURN and ORDER BY). OFFSET is applied via `.skip()` first (Cypher requires SKIP before LIMIT), then LIMIT chains after. Both attach to the final RETURN, not the WITH. This is correct.

**Production code change:** None expected. Verify with tests.

**Test cases to add:**

```
SELECT sum(age) FROM People p GROUP BY name LIMIT 5
→ MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0 LIMIT 5
```
GROUP BY not in SELECT forces WITH. LIMIT applies after RETURN.

```
SELECT name FROM People p GROUP BY name HAVING count(*) > 5 LIMIT 10
→ MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name LIMIT 10
```
HAVING forces WITH. LIMIT applies after RETURN.

```
SELECT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name LIMIT 10 OFFSET 2
→ MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name ORDER BY name SKIP 2 LIMIT 10
```
HAVING forces WITH. OFFSET and LIMIT both apply after RETURN. Existing OFFSET tests only cover the non-WITH path; this verifies OFFSET works correctly when a WITH clause is present.

---

### 7.3 — Full Combination Test

**What:** Test the maximum complexity query: GROUP BY + HAVING + ORDER BY + DISTINCT + LIMIT + OFFSET.

**Test case:**

```
SELECT DISTINCT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name LIMIT 10 OFFSET 5
→ MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN DISTINCT name ORDER BY name SKIP 5 LIMIT 10
```

This exercises every clause interaction:
- WITH from HAVING
- Hidden `__having_col_0` for filtering (excluded from RETURN)
- `RETURN DISTINCT` (not `WITH DISTINCT`)
- ORDER BY resolves `name` from WITH alias
- SKIP before LIMIT, both after RETURN

Additional combination:

```
SELECT DISTINCT name, sum(age) FROM People p GROUP BY name, department HAVING sum(age) > 100 ORDER BY name LIMIT 5
→ (capture actual output)
```

This adds GROUP BY-only columns (`department`) with HAVING, DISTINCT, ORDER BY, and LIMIT.

---

### 7.4 — Document GROUP BY Validation Decision

**What:** Add a code comment in `requiresWithForGroupBy()` or `statement(Select<?>)` documenting the decision to silently translate queries with non-aggregated SELECT columns missing from GROUP BY.

**Production code change:** Add a comment (no logic change). Example:

```java
// Design decision: silently translate queries where non-aggregated SELECT columns
// are missing from GROUP BY. Cypher's implicit grouping will group by all non-aggregated
// columns in the RETURN, which may differ from the SQL GROUP BY. This matches MySQL's
// permissive mode behavior. Validation/rejection was considered and deferred — see
// group phase 4.md §Remaining Open Questions.
```

---

### 7.5 — Verify Registry Cleanup

**What:** Confirm the `finally` block from Phase 5 resets `this.aliasRegistry = null` on all paths.

**Code review:** Lines 490-528:
```java
try {
    // ... WITH and non-WITH paths ...
    return addLimit(...).build();
}
finally {
    this.aliasRegistry = null;
}
```

This covers: success path, exception during WITH construction, exception during HAVING translation, exception during ORDER BY translation.

**Test case to add:** Translate two queries sequentially — first one with GROUP BY/HAVING (sets registry), second one without. Verify the second produces no WITH clause and no alias references.

```java
@Test
void registryDoesNotLeakBetweenTranslations() {
    var translator = SqlToCypher.defaultTranslator();
    // First: query with HAVING (activates registry)
    translator.translate("SELECT name FROM People p GROUP BY name HAVING count(*) > 5");
    // Second: simple query (should NOT have WITH or alias references)
    var result = translator.translate("SELECT name FROM People p");
    assertThat(result).doesNotContainPattern("\\bWITH\\b");
    assertThat(result).doesNotContain("__with_col_");
    assertThat(result).doesNotContain("__having_col_");
}
```

---

### 7.6 — Code Quality

**What:** Apply all formatting and style checks.

**Commands:**
```bash
./mvnw -pl neo4j-jdbc-translator/impl spring-javaformat:apply
./mvnw -pl neo4j-jdbc-translator/impl license:format
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dlicense.skip=true -Dcheckstyle.skip=true
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl checkstyle:check
```

---

### 7.7 — End-to-End Code Path Review

**What:** Trace every path through `statement(Select<?>)` and confirm each has a corresponding test.

| Path | Description | Test Coverage |
|------|-------------|---------------|
| (a) | No GROUP BY, no HAVING — simple MATCH-RETURN | `nonGroupByPathNeverProducesWithClause` (10 cases) |
| (b) | GROUP BY matches SELECT — simple MATCH-RETURN | `snapshotGroupByMatchingSelect` (3 cases) + `aggregates[1-3]` |
| (c) | GROUP BY differs from SELECT — WITH-based | `aggregates[4-9]` + `withClauseGeneration[1,2,5]` |
| (d) | GROUP BY + HAVING (function form) — WITH + WHERE | `havingConditionTranslation[1,2,5,6]` + `withClauseGeneration[6]` |
| (e) | GROUP BY + HAVING (alias form) — WITH + WHERE | `havingConditionTranslation[4]` |
| (f) | HAVING only (no GROUP BY) — WITH + WHERE | `havingConditionTranslation[2]` |
| (g) | HAVING with aggregate not in SELECT — hidden column | `havingConditionTranslation[1,5,6,8]` |
| (h) | HAVING with non-aggregate condition on GROUP BY column | `havingConditionTranslation[3]` |
| (i) | Any of above + DISTINCT | `distinctWithGroupByAndHaving` (3 cases) |
| (j) | Any of above + ORDER BY | `withClauseGeneration[7]` + `havingConditionTranslation[7]` + `snapshotOrderBy[5,6]` |
| (k) | Any of above + LIMIT | `limitAndOffsetWithWithClause[1,2]` |
| (k2) | Any of above + OFFSET | `limitWithOffset` (3 cases, non-WITH) + `limitAndOffsetWithWithClause[3]` (WITH path) |
| (l) | Full combination: GROUP BY + HAVING + ORDER BY + DISTINCT + LIMIT + OFFSET | `fullGroupByCombination` + `fullGroupByCombinationWithGroupByMismatch` + `fullGroupByCombinationWithWhereAndMultipleAggregates` |

**All code paths now have test coverage.**

---

## Implementation Checklist

- [x] **7.1** Add DISTINCT + WITH tests (3 test cases) — `distinctWithGroupByAndHaving` parameterized test
- [x] **7.2** Add LIMIT + WITH tests (2 test cases) and OFFSET + WITH test (1 test case) — `limitAndOffsetWithWithClause` parameterized test
- [x] **7.3** Add full combination tests (3 test cases) — `fullGroupByCombination`, `fullGroupByCombinationWithGroupByMismatch`, `fullGroupByCombinationWithWhereAndMultipleAggregates`
- [x] **7.4** Add GROUP BY validation decision comment — added to `requiresWithForGroupBy()` Javadoc
- [x] **7.5** Add registry cleanup test (1 test case) — `registryDoesNotLeakBetweenTranslations` test
- [x] **7.6** Apply formatting and run quality checks — formatting applied, 82 pre-existing checkstyle issues (none introduced by Phase 7)
- [x] **7.7** Verify all code paths have test coverage (review table below)

---

## Implementation Order

```
7.1 (DISTINCT tests) ──────────┐
7.2 (LIMIT + OFFSET tests) ───┤── can be done in parallel
7.5 (registry test) ───────────┘
        ↓
7.3 (full combination test incl. OFFSET — depends on 7.1 and 7.2 confirming no issues)
        ↓
7.4 (documentation comment)
        ↓
7.6 (formatting and quality)
        ↓
7.7 (final code path review)
```

---

## Risk Assessment

| Risk | Likelihood | Impact | Status |
|------|-----------|--------|--------|
| DISTINCT + WITH produces `WITH DISTINCT` instead of `RETURN DISTINCT` | Very Low | High | **Verified** — 3 tests confirm `RETURN DISTINCT` placement |
| LIMIT applies to WITH instead of RETURN | Very Low | High | **Verified** — 2 tests confirm LIMIT after RETURN |
| Registry leaks between consecutive translations | Very Low | High | **Verified** — `registryDoesNotLeakBetweenTranslations` test passes |
| OFFSET not applied when WITH clause is present | Very Low | Medium | **Verified** — WITH-path OFFSET test added and passes |
| Edge case in full combination (DISTINCT + HAVING + ORDER BY + LIMIT + OFFSET) | Low | Medium | **Verified** — full combination test passes |

---

## Expected Outcome

Phase 7 is primarily a **hardening and verification phase**. As expected from the code review, no production logic changes were needed — DISTINCT and LIMIT placement were already correct. The deliverables are:

1. **13 new test cases** covering the DISTINCT, LIMIT, OFFSET (WITH path), combination (including GROUP BY mismatch and WHERE+multiple aggregates), WHERE+GROUP BY paths, and registry cleanup gaps
2. **1 code comment** documenting the GROUP BY validation decision in `requiresWithForGroupBy()` Javadoc
3. **Formatting and quality gate** confirmed — 0 new checkstyle violations introduced
4. **Complete code path coverage** verified — all paths (a) through (l) now have tests

**Final test count:** 432 (baseline) + 13 (new) = **445 tests, 0 failures.**

---

## What Is NOT in Scope for Phase 7

- **GROUP BY with computed expressions** (CASE, YEAR, function calls) — requires additional expression translator work
- **GROUP BY by ordinal position** — jOOQ does not resolve ordinals; would require manual resolution
- **OFFSET edge cases beyond WITH path** — basic OFFSET is implemented; only the WITH-path gap is addressed in Phase 7
- **Configuration flags** — no backward compatibility flags; the translator produces correct Cypher
- **Integration tests** — Phase 7 is unit-test-level hardening; integration tests are a separate effort
