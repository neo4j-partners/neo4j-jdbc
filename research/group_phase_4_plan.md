# GROUP BY Implementation Checklist — Production Code (Phases 4-7)

This checklist tracks **production code changes only** for the remaining GROUP BY/HAVING implementation. All test work is tracked separately in [prodTestingPlan.md](prodTestingPlan.md) and is cross-referenced here but not duplicated.

Design spec: [group phase 4.md](group%20phase%204.md)

**Prerequisites** (all COMPLETE):
- Phase 1: Diagnostic tests — 35 tests documenting jOOQ QOM behavior
- Phase 2: `FieldMatcher.java` — structural field equivalence matching (23 tests)
- Phase 3: `AliasRegistry.java` — expression-to-alias mapping with two-mode lookup (15 tests)
- Tier 1 snapshot tests — 45 regression baselines in `SqlToCypherTests.java`

**Primary file**: `neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java`

---

## Phase 4: WITH Clause Generation for GROUP BY — COMPLETE

**Goal**: Replace fragile name-based field comparison with `FieldMatcher`, wire `AliasRegistry` into `buildWithClause()`, and produce correct WITH-based Cypher when GROUP BY columns differ from SELECT columns.

**Status**: COMPLETE. 390 tests, 0 failures, 0 errors.

**Spec reference**: [group phase 4.md §Phase 4](group%20phase%204.md#phase-4-with-clause-generation-for-group-by)

- [x] **4.1** Replace `requiresWithForGroupBy()` internals: now uses `FieldMatcher.fieldsMatch()` via `groupByFieldMatchesAnySelectField()` helper. *(Done prior — already in codebase before this phase started)*
- [x] **4.2** Replace `buildWithClause()` GROUP BY detection: now uses `groupByFieldMatchesAnySelectField()` with `FieldMatcher`. *(Done prior — already in codebase)*
- [x] **4.3** Create `AliasRegistry` inside `buildWithClause()`: instantiated at top of method; SELECT loop registers each `(selectField, alias)` pair; GROUP BY-only loop registers each `(groupField, alias)` pair
- [x] **4.4** Add `AliasRegistry` to `WithClauseResult` record: changed to `WithClauseResult(OngoingReading reading, Supplier<List<Expression>> returnExpressionsSupplier, AliasRegistry registry)`
- [x] **4.5** Thread registry through `statement(Select<?>)`: added `private AliasRegistry aliasRegistry` instance field to `ContextAwareStatementBuilder`; set to `withResult.registry()` in the WITH path, remains `null` in the non-WITH path
- [x] **4.6** Delete `resolveFieldName()` helper: already removed in prior work, no callers remain
- [x] **4.7** Verify common case bypass: confirmed — `snapshotGroupByMatchingSelect` (3 tests) and all Tier 1 snapshots (45 tests) pass unchanged

**Tests**: See [prodTestingPlan.md §Phase 4](prodTestingPlan.md#phase-4-with-clause-generation) (items 4.1-4.4, ~10 test cases)

**Test results**:
- **Fixed**: 6 pre-existing aggregate test failures (aggregates[4-9]) — now pass with correct WITH-based output
- **Fixed**: 4 Tier 4 `withClauseGeneration` tests — now pass
- **Unchanged**: 45 Tier 1 snapshot tests — all pass, no regressions
- **Unchanged**: 23 FieldMatcher + 15 AliasRegistry tests — all pass
- **Unchanged**: 3 `snapshotGroupByMatchingSelect` tests — common-case bypass works correctly
- **Total**: 390 tests, 0 failures, 0 errors

**Quality gate**: PASSED. All tests green. Spring formatting applied.

---

## Phase 5: ORDER BY Alias Resolution + Unified Registry Interception

**Goal**: Add unified alias resolution in `expression(Field<?>)` so both ORDER BY and HAVING (Phase 6) resolve through the `AliasRegistry`. Restructure `buildWithClause()` to set the registry before HAVING translation. Add error detection for ORDER BY on de-scoped variables.

**Spec reference**: [group phase 4.md §Phase 5](group%20phase%204.md#phase-5-order-by-alias-resolution-with-with)

**Design decisions incorporated** (from Outstanding Questions):
- **Unified interception** (Q5, Q11): Registry check lives in `expression(Field<?>)`, not in `expression(SortField<?>)` or behind a context flag. Both ORDER BY and HAVING use the same path.
- **Type guard** (Q12): Only resolve `TableField`, aggregates, and `FieldAlias` — skip `Param<?>` and arithmetic to prevent collisions.
- **Restructured timing** (Q4): Set `this.aliasRegistry` inside `buildWithClause()` before HAVING, not after it returns.
- **Defensive null** (Q1): Explicitly clear registry in non-WITH path.
- **Error on de-scoped ORDER BY** (Q3): Throw when ORDER BY references a variable not in the WITH scope.

### Review Notes and Clarifications

The following issues were identified during review of the Phase 5 checklist against the existing codebase. Each is annotated with the checklist item it affects and the resolution.

**RN-1 (affects 5.6): Error check placement relative to `expression(SortField<?>)` catch block.**
The current `expression(SortField<?>)` (line 1403) has a catch block at line 1414 that handles unresolvable `TableField` by falling back to `findTableFieldInTables()`. After a WITH clause, `findTableFieldInTables()` will still find the table in `this.tables` and produce a `node.property("col")` reference — which is syntactically valid Cypher but semantically wrong (the variable is out of scope after WITH). The error check in 5.6 must fire **before** the catch block's `findTableFieldInTables()` fallback. Resolution: the interception at 5.3 handles the success path (field IS in registry → emit alias). For the error path, the check should go at the top of the catch block: if `this.aliasRegistry != null` and the field is a `TableField` that the registry didn't resolve, throw immediately rather than falling through to `findTableFieldInTables()`. Updated in 5.6 below.

**RN-2 (affects 5.3): `FieldAlias` in type guard — redundancy with registry unwrapping.**
`AliasRegistry.resolve()` already calls `unwrap()` which strips `FieldAlias`. Including `QOM.FieldAlias<?>` in the type guard is therefore safe but redundant for the structural match path — the unwrapping happens inside `resolve()`. However, it IS needed for correctness: without it, a `FieldAlias` wrapping a `TableField` would fail the type guard (it's not itself a `TableField`). The type guard checks the outer type, not the inner. Phase 1 diagnostic test `aliasedVsUnaliased` confirms jOOQ produces `FieldAlias` wrappers in SELECT. Keep `FieldAlias` in the type guard. No change needed.

**RN-3 (affects 5.1): Dual assignment — registry in `WithClauseResult` becomes redundant.**
After 5.1 moves the assignment inside `buildWithClause()`, the `registry` field in `WithClauseResult` is no longer consumed by `statement(Select<?>)`. Options: (a) remove it from the record, (b) keep it for testability. Resolution: **remove it from the record.** The record is private and has no external consumers. Keeping dead fields creates confusion about who owns the registry lifecycle. The registry is now an instance field set as a side effect of `buildWithClause()`. Update 5.1 to include removing `registry` from `WithClauseResult` and the corresponding `withResult.registry()` call at line 490.

**RN-4 (affects 5.2): `finally` block scope — early returns in `statement(Select<?>)`.**
`statement(Select<?>)` has an early return at line 474 (empty `$from()`). The `finally` block should wrap the entire method body starting from the `needsWithClause` check (line 482), not from the top of the method. The early return paths (lines 468-475) execute before the registry could ever be set, so wrapping them is harmless but unnecessary. Resolution: wrap from the `needsWithClause` check through the final `build()` return. This keeps the `finally` scope tight and communicates intent clearly.

**RN-5 (general): Line numbers in this checklist are reference points, not implementation coordinates.**
Line numbers reference the codebase as of Phase 4 completion. After the first edit in Phase 5, they will shift. Use the logical descriptions (method names, variable names, surrounding context) to locate edit points, not line numbers. This is especially important if items are implemented out of order.

**RN-6 (affects 5.3): Registry interception must be a standalone block, not part of the if-else chain.**
The existing `expression(Field<?>)` method is a single long if-else-if chain (`Param` → `TableField` → `QOM.Add` → ... → `unsupported()`). Inserting the registry interception as an `else if` would prevent fall-through when `resolve()` returns null — the field would skip the `TableField` branch and eventually hit `unsupported()`. Resolution: the interception is a **standalone `if` block at the very top of the method**, before the existing chain. It returns early on success (`resolve()` non-null) and is invisible on failure (`resolve()` null — execution falls through to the unchanged chain). The type guard excludes `Param<?>` so placing it before the Param check is functionally identical to placing it after. The entire existing if-else chain remains untouched — zero structural changes to existing code.

### Existing Test Expectations That Must Change

Two test cases in `SqlToCypherTests.withClauseGeneration()` (lines 968-969) currently expect **incorrect** output that will be fixed by this phase:

1. `SELECT sum(age) FROM People p GROUP BY name HAVING sum(age) > 100` currently expects:
   `... WHERE sum(p.age) > 100 RETURN __with_col_0`
   After Phase 5: `... WHERE __with_col_0 > 100 RETURN __with_col_0` (HAVING resolves via registry)

2. `SELECT sum(age) FROM People p GROUP BY name ORDER BY sum(age)` currently expects:
   `... RETURN __with_col_0 ORDER BY sum(p.age)`
   After Phase 5: `... RETURN __with_col_0 ORDER BY __with_col_0` (ORDER BY resolves via registry)

These test expectations were added during Phase 4 as "current behavior" snapshots. They document the pre-Phase-5 state. Update them as part of this phase. The corrected output uses WITH aliases instead of re-emitting raw expressions, which is the entire point of Phase 5.

### Checklist

- [ ] **5.0** Defensive null in non-WITH path: in `statement(Select<?>)`, add `this.aliasRegistry = null` in the else branch (currently line 492). Ensures the registry is explicitly cleared when no WITH clause is needed.

- [ ] **5.1** Restructure `buildWithClause()` registry timing and simplify `WithClauseResult`:
    - Move `this.aliasRegistry = registry` inside `buildWithClause()`, placed just BEFORE the HAVING condition check (currently line 586), after the SELECT and GROUP BY loops have fully populated the registry.
    - Remove the `registry` field from the `WithClauseResult` record — it becomes `WithClauseResult(OngoingReading reading, Supplier<List<Expression>> returnExpressionsSupplier)`.
    - Remove the `this.aliasRegistry = withResult.registry()` assignment at line 490 in `statement()` — it is now redundant.
    - (See RN-3 for rationale.)

- [ ] **5.2** Add `finally` block for registry cleanup: wrap the body of `statement(Select<?>)` starting from the `needsWithClause` check (line 482) through the final `build()` return in a try-finally that resets `this.aliasRegistry = null`. This prevents state leaks on exceptions and ensures the registry is scoped to exactly one query translation. Early returns before line 482 (empty `$from()`, limit-top-N) are outside the try-finally scope since the registry cannot be set on those paths. (See RN-4 for rationale.)

- [ ] **5.3** Add unified registry interception to `expression(Field<?>)`: add a **standalone `if` block at the very top** of `expression(Field<?> f, boolean turnUnknownIntoNames)`, BEFORE the existing if-else chain (before the `Param<?>` check at line 1601). This is a standalone block, not an `else if` in the chain:
    ```java
    private Expression expression(Field<?> f, boolean turnUnknownIntoNames) {
        // Registry interception — resolve fields to WITH aliases when in WITH scope
        if (this.aliasRegistry != null
                && (f instanceof TableField<?, ?> || FieldMatcher.isAggregate(f) || f instanceof QOM.FieldAlias<?>)) {
            String alias = this.aliasRegistry.resolve(f);
            if (alias != null) {
                return Cypher.name(alias);
            }
        }
        // existing if-else chain starts here, completely unchanged
        if (f instanceof Param<?> p) {
            // ...
        }
        else if (f instanceof TableField<?, ?> tf) {
            // ...
        }
        // ... rest of chain ...
    }
    ```
    **Why standalone at the top, not inside the else-if chain**: The interception must fall through silently when `resolve()` returns null (the field is not in the registry). An `else if` in the chain would prevent fall-through to the `TableField` branch — the field would skip to the end and hit `unsupported()`. A standalone block returns early on success and is invisible on failure. Placing it before the `Param<?>` check is safe because the type guard excludes `Param<?>` (it's not a `TableField`, aggregate, or `FieldAlias`). The entire existing if-else chain remains untouched.

    The type guard ensures literal values and arithmetic operators (`QOM.Add`, `QOM.Mul`, etc.) skip the resolve entirely — they fall through to normal translation. `FieldMatcher.isAggregate()` must be package-private for this (see 5.4). The `FieldAlias` inclusion is needed because a `FieldAlias` wrapping a `TableField` would otherwise fail the guard — see RN-2.

- [ ] **5.4** Make `FieldMatcher.isAggregate()` package-private: change from `private` to default visibility (line 115 of `FieldMatcher.java`). Required by the type guard in 5.3. *(Promoted from original 6.4.)*

- [ ] **5.5** Preserve sort direction: the existing direction extraction in `expression(SortField<?>)` (line 1407) continues to work unchanged. The SortField unwraps to a Field via `s.$field()`, calls `expression(theField)` which now hits the registry interception at 5.3. The sort direction mapping (`"DEFAULT"` → `SortItem.Direction.UNDEFINED`) remains in `expression(SortField<?>)`. No change needed to the SortField method itself — verify with tests.

- [ ] **5.6** Error on unresolvable ORDER BY after WITH: modify the catch block in `expression(SortField<?>)` (line 1414). Currently, when `expression(theField)` throws `IllegalArgumentException`, the catch block falls back to `findTableFieldInTables()`. After a WITH clause, this fallback produces syntactically valid but semantically broken Cypher (the variable is out of scope). Add a guard at the **top** of the catch block:
    ```java
    catch (IllegalArgumentException ex) {
        if (this.aliasRegistry != null) {
            throw new IllegalArgumentException(
                String.format("ORDER BY references field '%s' which is not in scope after WITH clause",
                    theField.getName()), ex);
        }
        // ... existing findTableFieldInTables() fallback for non-WITH queries ...
    }
    ```
    This ensures the fallback only fires when there is no WITH clause (i.e., the original table scope is still accessible). When a WITH clause is present, the error surfaces immediately with a clear message rather than emitting invalid Cypher that would fail at runtime with Neo4j error 42N44. (See RN-1 for the full analysis.)

- [ ] **5.7** Verify non-WITH ORDER BY unchanged: all existing ORDER BY tests (Tier 1 snapshots, non-GROUP-BY queries) must produce identical output. The `aliasRegistry == null` guard ensures the new code path is never entered for queries without a WITH clause.

- [ ] **5.8** Update test expectations: update the two `withClauseGeneration` test cases (see "Existing Test Expectations That Must Change" above) to expect alias-resolved output instead of re-emitted raw expressions.

**Tests**: See [prodTestingPlan.md §Phase 5](prodTestingPlan.md#phase-5-order-by-alias-resolution) (items 5.1-5.3, ~10 test cases). Additional tests needed for:
- ORDER BY on GROUP BY-only column not in RETURN (valid — WITH alias resolves)
- ORDER BY on expression not in registry after WITH (should throw error)
- ORDER BY with sort direction preservation (ASC, DESC, DEFAULT)

**Quality gate**: All Tier 1-4 tests pass (with updated expectations for 5.8). ORDER BY with GROUP BY references WITH aliases. ORDER BY without GROUP BY unchanged. Sort direction preserved. Unresolvable ORDER BY after WITH throws clear error.

### Implementation Best Practices

These practices were identified by reviewing the Phase 1-4 implementation (FieldMatcher, AliasRegistry, QomTestSupport, diagnostic tests, and SqlToCypher changes). They should be followed for Phase 5-7 work.

**BP-1: All test inputs must use parsed SQL, never hand-constructed jOOQ objects.**
Phase 1 Finding 1 proved that jOOQ's parser produces internal types (e.g., `SQLField` for `count(*)`) that differ from what you'd get constructing objects manually. Every test in Phases 2 and 3 correctly uses `parseSelect()` from `QomTestSupport`. Continue this pattern. If a test needs a field from HAVING, parse a query with HAVING and extract the field — do not use `DSL.count()` or similar builders.

**BP-2: New test classes should extend `QomTestSupport`.**
The shared base class provides `parseSelect()`, `unwrapAlias()`, `asField()`, and `getAliasName()`. All three test classes (JooqQomDiagnosticTests, FieldMatcherTests, AliasRegistryTests) already use it. Any new test class for `collectAggregates()` or integration tests should extend it too.

**BP-3: Use `@Nested` + `@DisplayName` for test organization.**
All three existing test classes use nested inner classes with descriptive display names grouped by spec section (e.g., `§3.1 Structural lookup`). This makes test output readable and traces back to the design spec. Continue this pattern — use the spec section number in the display name.

**BP-4: Test components in isolation before wiring them into `SqlToCypher`.**
Phases 2 and 3 built `FieldMatcher` and `AliasRegistry` with full test suites before any `SqlToCypher` changes. Phase 6 should do the same for `collectAggregates()` — build it, test it independently with ~8-10 cases, then wire it in. This pattern catches design flaws early and produces components that are easy to debug in isolation.

**BP-5: End-to-end translator tests use `SqlToCypher.defaultTranslator()` and assert full Cypher string equality.**
The `withClauseGeneration` parameterized test (line 971) and the `aggregates` test (line 568) both use `assertThat(translator.translate(sql)).isEqualTo(cypher)`. This is the right pattern for end-to-end validation. Use `@CsvSource` with `|` delimiter for clean SQL|Cypher pairs. Avoid regex-based assertions unless testing a specific substring — full string equality catches unexpected changes in any clause.

**BP-6: When changing `expression(Field<?>)`, be aware of the method's calling contexts.**
`expression(Field<?>)` is called from multiple sites: `expression(SortField<?>)` for ORDER BY, `condition()` for WHERE/HAVING, the SELECT expression loop, and `buildWithClause()` itself. Any change to `expression(Field<?>)` affects all of these paths. The registry interception in 5.3 is deliberately placed early in the method so it intercepts regardless of which caller triggered it. After implementing 5.3, manually trace through each calling context to verify no unintended side effects:
- `buildWithClause()` SELECT loop (lines 552-568): calls `expression(selectField)` — registry is being populated but not yet assigned to `this.aliasRegistry`, so the interception is inactive. Safe.
- `buildWithClause()` GROUP BY loop (lines 573-580): same — registry not yet assigned. Safe.
- `havingCondition()` → `condition()` → `expression()`: registry IS set (after 5.1 moves the assignment). Interception fires. This is the intended behavior for Phase 6.
- `expression(SortField<?>)` → `expression(theField)`: registry IS set if WITH path was taken. Interception fires. This is the intended behavior for Phase 5.
- SELECT result column supplier (line 464): called lazily via `.returning(finalResultColumnsSupplier.get())` at line 498-499. In the WITH path, `finalResultColumnsSupplier` comes from `WithClauseResult` and returns pre-built `Cypher.name(alias)` expressions — it does NOT call `expression(Field<?>)`. Safe.

**BP-7: Run the full module test suite after each checklist item, not just at the end.**
Phase 4 reported 390 tests, 0 failures. After each item in Phase 5, run `./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test` and confirm the count. If a change breaks something unexpected, the small diff makes it easy to diagnose. Do not batch multiple items and then try to fix a cascade of failures.

**BP-8: Apply formatting after each change, not just at the quality gate.**
Run `./mvnw -pl neo4j-jdbc-translator/impl spring-javaformat:apply` after each edit. The project uses Spring JavaFormat which enforces specific indentation and brace styles. Applying formatting incrementally avoids large formatting-only diffs that obscure functional changes.

---

## Phase 6: HAVING Condition Translation

**Goal**: Build the `collectAggregates()` utility, simplify `havingCondition()` to use the already-set registry, and support HAVING-only aggregates as hidden WITH columns.

**Spec reference**: [group phase 4.md §Phase 6](group%20phase%204.md#phase-6-having-condition-translation)

**Design decisions incorporated** (from Outstanding Questions):
- **No `inHavingContext` flag** (Q5): The unified interception from Phase 5 handles HAVING automatically — the registry is set before `havingCondition()` is called.
- **No dual registry assignment** (Q4): Registry is set in `buildWithClause()` (Phase 5 item 5.1), so `havingCondition()` reads it directly.
- **Standalone aggregate collector** (Q6): New `collectAggregates()` utility, tested independently.
- **Separate counter** (Q7): Dedicated `havingAliasCounter` with `__having_col_` prefix.
- **Non-aggregate HAVING conditions** (Q8): Work automatically via registry — GROUP BY-only columns are registered and resolvable.

### Checklist

- [ ] **6.1** Verify HAVING forces WITH path: line 482 already does `havingCondition != null || requiresWithForGroupBy(...)` — confirm this remains correct after Phase 4 and Phase 5 changes. Also verify HAVING without GROUP BY takes the WITH path (Q10: current behavior is correct — the entire table is one implicit group).

- [ ] **6.2** Build `collectAggregates(org.jooq.Condition)` utility method: a standalone package-private static method (in `SqlToCypher.java` or a companion class like `FieldMatcher.java`) that recursively walks a jOOQ condition tree and returns `List<Field<?>>` of all aggregate sub-expressions found. Algorithm:
    - `QOM.And` / `QOM.Or` / `QOM.Xor` → recurse into `$arg1()` and `$arg2()` (both are `Condition`)
    - `QOM.Not` → recurse into `$arg1()` (`Condition`)
    - Comparison operators (`QOM.Gt`, `QOM.Ge`, `QOM.Lt`, `QOM.Le`, `QOM.Eq`, `QOM.Ne`) → extract `Field<?>` from `$arg1()` and `$arg2()`, then walk each field
    - `QOM.Between` → extract from `$arg1()`, `$arg2()`, `$arg3()`
    - `QOM.InList` → extract from `$field()` and each element of `$list()`
    - `QOM.IsNull` / `QOM.IsNotNull` → extract from `$arg1()`
    - For each `Field<?>`: if `FieldMatcher.isAggregate(f)` → add to results; if arithmetic (`QOM.Add`, `QOM.Sub`, `QOM.Mul`, `QOM.Div`) → recurse into `$arg1()` and `$arg2()` to find nested aggregates
    - Uses `FieldMatcher.isAggregate()` (already package-private from 5.4)

- [ ] **6.3** Test `collectAggregates()` independently: ~8-10 test cases using parsed SQL, following the same pattern as `FieldMatcher` and `AliasRegistry` tests. Test cases:
    - Simple: `HAVING count(*) > 5` → returns `[count(*)]`
    - Compound: `HAVING count(*) > 5 AND max(age) > 50` → returns `[count(*), max(age)]`
    - Arithmetic: `HAVING max(salary) > 2 * avg(salary)` → returns `[max(salary), avg(salary)]`
    - Nested: `HAVING sum(a) + sum(b) > 100` → returns `[sum(a), sum(b)]`
    - No aggregates: `HAVING name = 'Alice'` → returns `[]`
    - Mixed: `HAVING count(*) > 5 AND name = 'Alice'` → returns `[count(*)]`
    - Duplicate aggregates: `HAVING count(*) > 5 OR count(*) < 100` → returns `[count(*), count(*)]` (caller deduplicates via registry)
    - HAVING with BETWEEN: `HAVING count(*) BETWEEN 5 AND 10` → returns `[count(*)]`

- [ ] **6.4** Integrate `collectAggregates()` into `buildWithClause()`: after the SELECT and GROUP BY loops (line 582) and BEFORE `reading.with(withExpressions)` (line 583), if `havingCondition != null`:
    1. Call `collectAggregates(havingCondition)` to get all aggregate fields
    2. For each aggregate, check `registry.resolve(agg)` — if non-null, it's already registered (from SELECT), skip
    3. If null (HAVING-only aggregate): translate with `expression(agg)`, assign alias `"__having_col_" + havingAliasCounter.getAndIncrement()`, add to `withExpressions` as `expr.as(alias)`, register in registry via `registry.register(agg, alias)`
    4. Do NOT add to `returnExpressions` — these are hidden columns used only for filtering
    5. Use a separate `AtomicInteger havingAliasCounter = new AtomicInteger(0)` — distinct from the existing `aliasCounter`

- [ ] **6.5** Simplify `havingCondition()`: remove the `List<IdentifiableElement> withExpressions` parameter. The method becomes:
    ```java
    private Condition havingCondition(org.jooq.Condition c) {
        return condition(c);
    }
    ```
    The registry is already set (Phase 5 item 5.1 moved the assignment to before this call). The unified interception in `expression(Field<?>)` (Phase 5 item 5.3) resolves aggregate and column references to their WITH aliases automatically. No context flag needed. Update the call site in `buildWithClause()` to pass only the condition.

- [ ] **6.6** Verify HAVING-by-alias works: `AliasRegistry.resolve()` name-based fallback handles `HAVING cnt > 5` where `cnt` is an unresolved field reference (jOOQ keeps it as a plain `TableField` with name `"cnt"`). The unified interception catches this because `TableField` passes the type guard. No additional code needed — verify with end-to-end test.

- [ ] **6.7** Verify HAVING with non-aggregate conditions: `HAVING name = 'Alice'` where `name` is a GROUP BY-only column. The registry resolves it to its `__group_col_N` alias via structural matching. Verify with end-to-end test.

- [ ] **6.8** Verify HAVING without GROUP BY: `SELECT count(*) FROM People HAVING count(*) > 5`. The WITH path is triggered by `havingCondition != null`. With empty `groupByFields`, only SELECT aggregates appear in WITH. The HAVING condition resolves `count(*)` to its WITH alias. Produces: `MATCH (p:People) WITH count(p) AS __with_col_0 WHERE __with_col_0 > 5 RETURN __with_col_0`. Verify with end-to-end test.

**Tests**: See [prodTestingPlan.md §Phase 6](prodTestingPlan.md#phase-6-having-translation) (items 6.1-6.7, ~20 test cases). Additional tests needed for:
- `collectAggregates()` unit tests (~8-10 cases, see 6.3)
- HAVING without GROUP BY (Q10)
- HAVING with non-aggregate GROUP BY-only column condition (Q8)

**Quality gate**: All Tier 1-5 tests pass. `collectAggregates()` independently tested. HAVING produces correct WHERE after WITH. Hidden columns in WITH excluded from RETURN. Both function-form and alias-form HAVING produce identical output. HAVING without GROUP BY works. Non-aggregate HAVING conditions resolve GROUP BY-only columns.

---

## Phase 7: DISTINCT, LIMIT, and Hardening

**Goal**: Verify correctness of DISTINCT and LIMIT placement with WITH, finalize GROUP BY validation decision, clean up code quality, review all code paths.

**Spec reference**: [group phase 4.md §Phase 7](group%20phase%204.md#phase-7-distinct-validation-and-hardening)

**Design decisions incorporated** (from Outstanding Questions):
- **GROUP BY validation** (Q9): Silently translate. Decision is final — documented in design spec, confirmed by research.
- **No `inHavingContext` cleanup** (original 7.4): The `inHavingContext` flag no longer exists (replaced by unified registry interception in Phase 5). Registry cleanup is handled by the `finally` block added in Phase 5 item 5.2.

### Checklist

- [ ] **7.1** Verify DISTINCT placement: DISTINCT applies to `effectiveReading.returningDistinct()` which is the post-WITH scope — produces `RETURN DISTINCT`, not `WITH DISTINCT`. No production code change expected — verify with tests

- [ ] **7.2** Verify LIMIT placement: `addLimit()` receives the RETURN projection — LIMIT attaches after RETURN, not after WITH. No production code change expected — verify with tests

- [ ] **7.3** Document GROUP BY validation decision: silently translate queries with non-aggregated SELECT columns missing from GROUP BY. Cypher's implicit grouping handles this naturally. Add a code comment in `requiresWithForGroupBy()` or `statement(Select<?>)` documenting this decision and referencing the design spec.

- [ ] **7.4** Verify registry cleanup: confirm the `finally` block from Phase 5 item 5.2 resets `this.aliasRegistry = null` on all paths (success, exception, early return). Write a test that verifies no state leaks between consecutive translations if the builder were ever reused.

- [ ] **7.5** Code quality: run `./mvnw spring-javaformat:apply`, `./mvnw license:format`, verify checkstyle clean

- [ ] **7.6** End-to-end code path review: trace every path through `statement(Select<?>)` and confirm each has a corresponding test:
    - (a) no GROUP BY, no HAVING — simple MATCH-RETURN
    - (b) GROUP BY matches SELECT — simple MATCH-RETURN (common case bypass)
    - (c) GROUP BY differs from SELECT — WITH-based
    - (d) GROUP BY + HAVING (function form) — WITH + WHERE
    - (e) GROUP BY + HAVING (alias form) — WITH + WHERE
    - (f) HAVING only (no GROUP BY) — WITH + WHERE (implicit single group)
    - (g) HAVING with aggregate not in SELECT — hidden WITH column
    - (h) HAVING with non-aggregate condition on GROUP BY-only column
    - (i) each of (a)-(h) + DISTINCT
    - (j) each of (a)-(h) + ORDER BY
    - (k) each of (a)-(h) + LIMIT
    - (l) full combination: GROUP BY + HAVING + ORDER BY + DISTINCT + LIMIT

**Tests**: See [prodTestingPlan.md §Phase 7](prodTestingPlan.md#phase-7-distinct-limit-combinations-and-hardening) (items 7.1-7.4, ~12 test cases)

**Quality gate**: Full unit test suite green. Checkstyle clean. License headers present. All formatting applied. Every `statement(Select<?>)` code path covered by at least one test. No mutable context flags remain in the codebase for HAVING/ORDER BY translation.

---

## Outstanding Questions — Resolved

### Phase 5: ORDER BY

1. **Non-WITH ORDER BY with aliasRegistry null**

**Decision: Add explicit `this.aliasRegistry = null` in the else branch at line 492.** Defensive coding. The builder is currently created fresh per `build()` call so it defaults to null, but explicitly clearing it guards against future lifecycle changes. Add to Phase 5 checklist as item 5.0.

2. **ORDER BY on GROUP BY-only columns**

**Decision: Valid Cypher — no issue.** Research confirmed that Cypher allows ORDER BY on any variable projected in the WITH clause, even if it's not in the RETURN. The Neo4j docs state: "ORDER BY can sort by values that are not included in the result set." However, when WITH uses aggregation, **only explicitly projected WITH variables remain in scope** — unprojected variables are discarded. Since GROUP BY-only columns ARE explicitly projected in the WITH (with `__group_col_N` aliases), ORDER BY can reference them. The registry resolves the alias correctly. No code change needed — the existing plan handles this.

3. **ORDER BY expression not in registry**

**Decision: Throw an error.** After a WITH clause, the original match scope is completely gone in Cypher. The Neo4j docs are explicit: "If a variable is not explicitly referenced in a WITH clause, it is dropped from the scope of the query and cannot be referenced by subsequent clauses." Attempting to ORDER BY an expression not in the WITH would produce Cypher error 42N44 (inaccessible variable). The translator should detect this and throw an `IllegalArgumentException` with a clear message rather than emitting invalid Cypher. Add to Phase 5 checklist: in `expression(SortField<?>)`, when `this.aliasRegistry != null` and `aliasRegistry.resolve(theField)` returns null AND normal expression translation would reference a de-scoped variable, throw an error.

### Phase 6: HAVING

4. **Timing of `aliasRegistry` assignment**

**Decision: Restructure `buildWithClause()` to set the registry BEFORE calling `havingCondition()`.** Research confirmed this is feasible. The registry is fully populated by line 582 (after both SELECT and GROUP BY loops). Move `this.aliasRegistry = registry` to just before the HAVING condition check (line 586). Remove the second assignment at line 490 in `statement()` — it becomes redundant. This eliminates dual assignment entirely. The `havingCondition()` method no longer needs a registry parameter; it reads `this.aliasRegistry` which is already set. Add cleanup: reset `this.aliasRegistry = null` in a `finally` block at the end of `statement(Select<?>)`.

5. **`inHavingContext` flag vs parameter-based approach**

**Decision: Eliminate the `inHavingContext` flag entirely — use unified registry interception in `expression(Field<?>)`.** The research revealed that both HAVING and ORDER BY need the same alias resolution behavior. The cleanest approach: when `this.aliasRegistry != null`, always check it at the top of `expression(Field<?> f, boolean)` for aggregate and column-reference fields. This is safe because:
- The registry is only non-null during WITH-based translation
- `resolve()` returns null for unregistered fields (literals, parameters, arithmetic wrappers), so they fall through naturally
- No mutable context flag needed — the registry's presence IS the context

This replaces the `inHavingContext` flag from item 6.5 AND the separate SortField-level interception from Phase 5 item 5.1. Both HAVING and ORDER BY get alias resolution through the same unified path. Update Phase 5 and Phase 6 checklists to reflect this unified approach.

6. **HAVING aggregate tree walking (6.3)**

**Decision: Build a standalone `collectAggregates(org.jooq.Condition)` utility method. Test independently.** No existing utility exists for this. The jOOQ QOM tree is well-structured for recursive walking (Phase 1 diagnostic tests confirmed the tree shapes). The method should:
- Walk `QOM.And`/`QOM.Or` → recurse into both `$arg1()` and `$arg2()`
- Walk comparison operators (`QOM.Gt`, `QOM.Lt`, etc.) → extract `Field<?>` from `$arg1()` and `$arg2()`
- For each `Field<?>` → if `FieldMatcher.isAggregate()` returns true, collect it; if it's arithmetic (`QOM.Add`, `QOM.Mul`, etc.), recurse into operands
- Return `List<Field<?>>` of all aggregate fields found

**Add to plan:** Insert a new Phase 5.5 (or extend Phase 6 with a sub-step) for building and testing `collectAggregates()`. It should be tested independently with parsed SQL, following the same pattern as `FieldMatcher` and `AliasRegistry`. Requires `FieldMatcher.isAggregate()` to be package-private (already planned in 6.4). Estimated: ~8-10 test cases covering simple HAVING, compound AND/OR, arithmetic wrapping, and nested aggregates.

Integration into `buildWithClause()`: call `collectAggregates(havingCondition)` after the SELECT/GROUP BY loops, filter out aggregates already in the registry via `registry.resolve(agg) != null`, add remaining ones as hidden WITH columns with `__having_col_N` aliases.

7. **Hidden column naming collisions**

**Decision: Use a separate counter with distinct prefix.** Use a dedicated `AtomicInteger havingAliasCounter` (starting at 0) with the `__having_col_` prefix. Although sharing the existing `aliasCounter` would work because the prefixes differ (`__with_col_`, `__group_col_`, `__having_col_`), a separate counter is clearer and avoids coupling the HAVING numbering to the SELECT/GROUP BY numbering. If a GROUP BY column is added or removed, the HAVING aliases remain stable.

8. **HAVING with non-aggregate conditions**

**Decision: Works correctly with the current registry design — no change needed.** For `SELECT count(*) FROM People GROUP BY name HAVING name = 'Alice'`: the GROUP BY-only loop in `buildWithClause()` (lines 573-581) registers `name` with alias `__group_col_0`. When the HAVING condition `name = 'Alice'` is translated, `resolve(name)` finds the GROUP BY-only alias via structural matching. The WHERE becomes `WHERE __group_col_0 = 'Alice'`. The `__group_col_0` column is in the WITH but NOT in the RETURN, which is correct — it's used only for filtering. Verify with an end-to-end test in Phase 6.

### Phase 7: Hardening

9. **GROUP BY validation decision (7.3)**

**Decision: Already resolved — silently translate.** The design spec (`group phase 4.md` §Remaining Open Questions) identifies this and `group_phase_4_plan.md` item 7.3 recommends silent translation. The prior research documents (`GROUP.md`, `GROUP_BY_CHALLENGES.md`) discuss MySQL's permissive behavior vs PostgreSQL's strict rejection. Cypher's implicit grouping handles this naturally by grouping on all non-aggregated columns in the RETURN. The Phase 4 WITH clause generation already makes this explicit. No further decision needed — document "silently translate" as final in Phase 7 and move on. This does not block Phase 5.

10. **HAVING without GROUP BY**

**Decision: Current code is correct — no change needed.** SQL allows `HAVING` without `GROUP BY`, treating the entire table as one implicit group. The current code at line 482 triggers the WITH path when `havingCondition != null`, and `buildWithClause()` with empty `groupByFields` processes only SELECT fields. For `SELECT count(*) FROM People HAVING count(*) > 5`, this produces:
```cypher
MATCH (p:People)
WITH count(p) AS __with_col_0
WHERE __with_col_0 > 5
RETURN __with_col_0
```
This is semantically correct: the WITH without grouping columns creates one implicit group, the aggregate runs over all matched rows, and the WHERE filters the single result. Add an end-to-end test in Phase 6 or 7 to verify.

### Cross-cutting

11. **`expression(Field<?>)` interception scope**

**Decision: Unified interception — check registry in `expression(Field<?>)` when non-null, no context flags.** (Merged with question 5.) The Phase 5 approach of intercepting at the `SortField` level is insufficient — `expression(SortField<?>)` calls `expression(Field<?>)` for the inner field anyway. A unified approach: at the top of `expression(Field<?> f, boolean turnUnknownIntoNames)`, add:
```java
if (this.aliasRegistry != null) {
    String alias = this.aliasRegistry.resolve(f);
    if (alias != null) {
        return Cypher.name(alias);
    }
}
```
This handles ORDER BY (the SortField unwraps to a Field, which hits the registry), HAVING (the condition unwraps to Fields, which hit the registry), and any future context that needs alias resolution. No flags needed. Parameters and literals pass through because `resolve()` returns null for them (they don't match structurally and their `getName()` doesn't match any registered alias in normal use — see question 12 for edge cases).

12. **Expression types the registry won't resolve — collision edge cases**

**Decision: Mitigate with a type guard, not an error.** Research found one real collision risk: jOOQ represents `GROUP BY 1` (ordinal) as a Field with `getName() = "1"`. If an alias named `"1"` existed, the name-based lookup would incorrectly resolve it. However:
- GROUP BY ordinals are already out of scope (Phase 1 Finding 5 / design spec §What Is Not in Scope)
- Parameters (`Param<?>`) are intercepted at line 1601 before reaching the registry check, so they never collide
- Inline literals are also intercepted as `Param<?>` instances

**Defensive measure:** Add a type guard in the unified interception (question 11): only call `aliasRegistry.resolve(f)` when `f` is a `TableField`, an aggregate (`FieldMatcher.isAggregate(f)`), or a `QOM.FieldAlias`. Skip the resolve for `Param<?>`, literal types, and arithmetic operators. This eliminates collision risk by construction rather than error-throwing. Add this to the Phase 5 checklist

---

## Dependencies

```
Phase 4 ──> Phase 5 ──────────────> Phase 6 ──> Phase 7
  |              |                       |
  |              ├── unified interception│
  |              ├── registry timing     ├── collectAggregates() utility
  |              ├── isAggregate() visibility
  |              ├── finally cleanup     ├── hidden HAVING columns
  |              └── ORDER BY error      └── havingCondition() simplification
  |
  └── uses FieldMatcher (Phase 2) + AliasRegistry (Phase 3)
```

Phase 4 must complete first (establishes registry threading). Phase 5 builds the unified interception infrastructure (registry timing, type-guarded `expression(Field<?>)` check, cleanup). Phase 6 builds `collectAggregates()` and wires HAVING through the infrastructure Phase 5 established. Phase 7 is hardening after all prior phases.

**Key change from original plan**: Phase 5 now carries more infrastructure work (items 5.0-5.4) that the original plan had split between Phases 5 and 6. This front-loads the registry interception so Phase 6 only needs to add HAVING-specific logic (aggregate collection and hidden columns), not wire up the interception mechanism.

## Files Modified

| File | Phases | Changes |
|---|---|---|
| `SqlToCypher.java` | 4, 5, 6, 7 | `buildWithClause()` (registry timing, HAVING aggregate injection), `havingCondition()` (simplified), `expression(Field<?>)` (unified type-guarded registry interception), `expression(SortField<?>)` (error on de-scoped ORDER BY), `statement(Select<?>)` (defensive null, finally cleanup), `collectAggregates()` (new utility) |
| `FieldMatcher.java` | 5 | Make `isAggregate()` package-private *(promoted from Phase 6 to Phase 5 — needed by type guard)* |
| `AliasRegistry.java` | — | No changes (consumed as-is) |
