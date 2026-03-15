# Remaining Work Plan — GROUP BY / HAVING Implementation

**Date**: 2026-03-15
**Current state**: 407 tests, 0 failures, 0 errors

This plan covers all remaining work from the current state through final validation. It supersedes the original parallelization strategy in `prodTestingPlan.md` (Parallel Group 2 is eliminated — all remaining phases are sequential).

---

## Current Status Summary

| Phase | Status | Tests Added | Production Code |
|-------|--------|-------------|-----------------|
| 1: Tier 1 Snapshots | COMPLETE | 45 | None |
| 2: Field Matcher | COMPLETE | 23 | `FieldMatcher.java` |
| 3: Alias Registry | COMPLETE | 15 | `AliasRegistry.java` |
| 4: WITH Clause | COMPLETE | 5 + 6 fixed | `SqlToCypher.java` — `buildWithClause()`, `requiresWithForGroupBy()` |
| 5: ORDER BY + Interception | COMPLETE | 7 + 2 updated | `SqlToCypher.java` — unified interception, registry timing, `finally`, error detection |
| 5-parallel: collectAggregates | COMPLETE | 9 | `FieldMatcher.java` — `collectAggregates()` utility |
| 5-parallel: ORDER BY snapshots | COMPLETE | 7 | None (test-only) |
| 6: HAVING | **IN PROGRESS** | 0 remaining | `SqlToCypher.java` — `buildWithClause()` integration pending |
| 7: Hardening | NOT STARTED | 0 | Verification + cleanup |
| 8: Integration | NOT STARTED | 0 | Test-only |

**Test count**: 407 (265 original + 35 diagnostic + 107 new)

---

## Phase 6: HAVING Hidden Columns — Production Code + Tests

**Prerequisites**: All complete. Phase 5 unified interception is in place. `collectAggregates()` is built and tested (9 cases). Registry timing is set before `reading.with()` (Q3 gap fixed).

**What Phase 5 already handles**: Simple HAVING where the aggregate IS in SELECT (e.g., `HAVING count(*) > 5` when `count(*)` is in SELECT). The unified interception resolves the aggregate to its WITH alias automatically.

**What Phase 6 uniquely delivers**: HAVING with aggregates NOT in SELECT — these need hidden WITH columns.

### Production code changes (3 items)

**6.1 Verify HAVING forces WITH path**

Confirm `boolean needsWithClause = havingCondition != null || requiresWithForGroupBy(selectStatement)` at line 488 still works correctly after Phase 5 changes. No code change expected — verify by running tests with HAVING-only queries.

**6.4 Integrate `collectAggregates()` into `buildWithClause()`**

Insert between the GROUP BY loop end and `reading.with(withExpressions)` (currently lines 590-597):

```java
// After GROUP BY loop, before reading.with():
this.aliasRegistry = registry;  // ← already here from Phase 5

// Phase 6.4: Add hidden HAVING columns
if (havingCondition != null) {
    var havingAliasCounter = new AtomicInteger(0);
    for (var agg : FieldMatcher.collectAggregates(havingCondition)) {
        if (registry.resolve(agg) != null) {
            continue;  // Already in SELECT — skip
        }
        var expr = expression(agg);  // MUST call BEFORE registering
        var alias = "__having_col_" + havingAliasCounter.getAndIncrement();
        withExpressions.add((IdentifiableElement) expr.as(alias));
        registry.register(agg, alias);
        // NOT added to returnExpressions — hidden column
    }
}

var withStep = reading.with(withExpressions);  // ← already here
```

**Critical ordering constraint**: Call `expression(agg)` BEFORE `registry.register(agg, alias)`. Otherwise the registry interception in `expression(Field<?>)` would short-circuit the translation (returning `Cypher.name(alias)` instead of the actual aggregate expression like `count(*)`).

**6.5 Remove unused `havingCondition()` parameter**

Change `havingCondition(org.jooq.Condition c, List<IdentifiableElement> withExpressions)` to `havingCondition(org.jooq.Condition c)`. Update call site. (Q6 recommendation — dead parameter from Phase 4.)

### Tests (~15-20 new cases)

Add a new `@ParameterizedTest` method `havingTranslation` in `SqlToCypherTests.java` using `@CsvSource` with `|` delimiter. Each test translates SQL and asserts exact Cypher output.

**Category 1: HAVING with aggregate NOT in SELECT (hidden columns)**

```
SELECT name FROM People p GROUP BY name HAVING count(*) > 5
→ WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name

SELECT name, sum(age) AS total FROM People p GROUP BY name HAVING avg(age) > 25
→ WITH ... avg(p.age) AS __having_col_0 WHERE __having_col_0 > 25 RETURN name, __with_col_1
```

**Category 2: Compound HAVING**

```
SELECT name, count(*) AS cnt, max(age) AS mx FROM People p GROUP BY name HAVING count(*) > 5 AND max(age) > 50
→ WHERE cnt > 5 AND mx > 50 (both in SELECT — no hidden columns)

SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING count(*) > 2 OR min(age) < 18
→ min(age) hidden in WITH, count(*) resolved to alias
```

**Category 3: Arithmetic in HAVING**

```
SELECT department, max(salary) AS mx, avg(salary) AS av FROM Employees e GROUP BY department HAVING max(salary) > 2 * avg(salary)
→ WHERE mx > 2 * av (both in SELECT)
```

**Category 4: HAVING by alias**

```
SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5
→ Same output as function form (jOOQ resolves alias to unresolved field, registry matches by name)
```

**Category 5: HAVING + ORDER BY combined**

```
SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5 ORDER BY cnt DESC
→ Full chain: WITH → WHERE → RETURN → ORDER BY

SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5 ORDER BY cnt DESC LIMIT 10
→ Full chain with LIMIT
```

**Category 6: HAVING on JOIN queries**

```
SELECT c.name, count(*) AS order_count FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name HAVING count(*) > 10
```

**Category 7: HAVING without GROUP BY**

```
SELECT count(*) FROM People p HAVING count(*) > 5
→ WITH count(p) AS __with_col_0 WHERE __with_col_0 > 5 RETURN __with_col_0
```

**Category 8: HAVING with non-aggregate condition on GROUP BY column**

```
SELECT count(*) FROM People p GROUP BY name HAVING name = 'Alice'
→ WHERE __group_col_N = 'Alice' (non-aggregate resolved via GROUP BY registry entry)
```

### Execution

1. Implement 6.4 production code
2. Implement 6.5 parameter cleanup
3. Run `./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test` — should still be 407 passing
4. Write HAVING test cases (capture actual output for expected values)
5. Run tests — target ~425 tests, 0 failures
6. Apply formatting: `./mvnw -pl neo4j-jdbc-translator/impl spring-javaformat:apply`

**Gate**: Hidden columns appear in WITH but not RETURN. Function-form and alias-form HAVING produce identical output. HAVING without GROUP BY works. All Tier 1-5 tests unchanged.

---

## Phase 7: DISTINCT, LIMIT, Combinations, and Hardening

**Prerequisites**: Phase 6 complete.

### Tests (~15 new cases)

**7.1 DISTINCT with WITH clause**

```
SELECT DISTINCT name, count(*) FROM People p GROUP BY name
→ WITH ... RETURN DISTINCT ... (not WITH DISTINCT)

SELECT DISTINCT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5
→ WITH ... WHERE ... RETURN DISTINCT ...
```

**7.2 Kitchen sink — full combination**

```
SELECT DISTINCT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5 ORDER BY cnt DESC LIMIT 10
→ WITH → WHERE → RETURN DISTINCT → ORDER BY → LIMIT

Join + GROUP BY + HAVING + ORDER BY + LIMIT
```

**7.3 LIMIT/OFFSET placement**

```
SELECT sum(age) FROM People p GROUP BY name LIMIT 5
→ LIMIT attaches to RETURN, not WITH

SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5 LIMIT 10 OFFSET 20
```

**7.4 No-GROUP-BY full regression**

```
SELECT * FROM People
SELECT count(*) FROM People
INSERT/UPDATE/DELETE (unchanged)
```

**7.5 Phase 5 test gaps (from remaining items)**

```
ORDER BY on GROUP BY-only column not in RETURN
ORDER BY error test — de-scoped field (assertThrows)
Simple HAVING validation (3 cases — aggregate IS in SELECT)
```

### Production code

- 7.1-7.2: No code changes expected — verify with tests
- 7.3: Document GROUP BY validation decision (comment in `requiresWithForGroupBy()`)
- 7.4: Verify `finally` cleanup — test consecutive translations
- 7.5: Run `./mvnw spring-javaformat:apply` + `./mvnw license:format`
- 7.6: Verify checkstyle clean

### Execution

1. Write all test cases
2. Run full module test suite — target ~440 tests, 0 failures
3. Apply formatting + license headers
4. Run checkstyle: `./mvnw -pl neo4j-jdbc-translator/impl checkstyle:check`
5. Run all-module unit tests: `test.sh --step 2 --output post_phase7`

**Gate**: Full unit test suite green. Checkstyle clean. Every `statement(Select<?>)` code path covered.

---

## Phase 8: Integration Tests

**Prerequisites**: Phases 6-7 complete. Docker running.

### Structure

New class: `GroupByHavingIT extends IntegrationTestBase` in `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/`

See `prodTesting.md` §Tier 8 (8.1–8.19) for full test specifications. ~100 tests total.

### Execution approach

Claude writes the test code. User runs `test.sh --step 3 --output post_phase8` and reports results. Claude does NOT run integration tests (Docker + Testcontainers required).

### Prioritized test groups

**Priority 1 — Core correctness** (write first, ~30 tests):
- 8.1: Basic GROUP BY (7 tests)
- 8.2: GROUP BY not in SELECT — WITH required (4 tests)
- 8.4: HAVING basic filtering (7 tests)
- 8.5: HAVING not in SELECT — hidden columns (4 tests)
- 8.14: Global aggregation regression (5 tests)

**Priority 2 — Combinations** (~25 tests):
- 8.3: Multiple GROUP BY columns (6 tests)
- 8.8: ORDER BY with GROUP BY (6 tests)
- 8.9: ORDER BY + HAVING combined (4 tests)
- 8.10: LIMIT/OFFSET with GROUP BY (5 tests)
- 8.11: DISTINCT with GROUP BY (3 tests)

**Priority 3 — Edge cases + real-world** (~25 tests):
- 8.6: Compound HAVING (4 tests)
- 8.7: HAVING by alias (4 tests)
- 8.13: GROUP BY with WHERE (5 tests)
- 8.15: Edge cases — empty, single, NULLs (8 tests)
- 8.16: Multi-aggregate (4 tests)

**Priority 4 — Full chain + regression** (~20 tests):
- 8.12: JOIN queries + GROUP BY — Movies graph (6 tests)
- 8.17: Kitchen sink (4 tests)
- 8.18: Regression against existing IT patterns (5 tests)
- 8.19: Movies graph analytics (5 tests)

**Gate**: ~100 integration tests pass. `TranslationIT.innerJoinColumnsWrongDirection` now passes.

---

## Final Validation

- [ ] Run `test.sh --step 1 --output final` — translator unit tests
- [ ] Run `test.sh --step 2 --output final` — all-module unit tests
- [ ] Run `test.sh --step 5 --output final` — checkstyle
- [ ] Compare `final/` against `init_baseline/` — only expected changes
- [ ] Total translator test count target: ~440 unit + ~100 integration
- [ ] No checkstyle violations
- [ ] No license header issues
- [ ] All formatting applied

---

## Estimated Effort Summary

| Phase | Code Changes | New Tests | Estimated Complexity |
|-------|-------------|-----------|---------------------|
| 6: HAVING hidden columns | 3 items in `SqlToCypher.java` | ~18 | Medium — `buildWithClause()` insertion, ordering constraint |
| 7: Hardening | ~0 (comments, formatting) | ~15 | Low — verification + cleanup |
| 8: Integration | 1 new test class | ~100 | Medium — writing test code, but execution is manual |
| **Total remaining** | **~3 production changes** | **~133 tests** | |
