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

## Phase 5: ORDER BY Alias Resolution

**Goal**: When a WITH clause is present, ORDER BY fields resolve through the `AliasRegistry` instead of against the original table scope.

**Spec reference**: [group phase 4.md §Phase 5](group%20phase%204.md#phase-5-order-by-alias-resolution-with-with)

- [ ] **5.1** Add registry check to `expression(SortField<?>)` (lines 1415-1440): after extracting `theField` from `s.$field()`, if `this.aliasRegistry != null`, call `aliasRegistry.resolve(theField)`. If non-null alias returned, emit `Cypher.sort(Cypher.name(alias), direction)` and return — skip existing expression translation
- [ ] **5.2** Preserve sort direction: use same direction extraction (line 1419) for both registry and non-registry paths; map `"DEFAULT"` to `SortItem.Direction.UNDEFINED`
- [ ] **5.3** Null-safe registry access: guard with `this.aliasRegistry != null` so all non-GROUP-BY queries behave identically to before

**Tests**: See [prodTestingPlan.md §Phase 5](prodTestingPlan.md#phase-5-order-by-alias-resolution) (items 5.1-5.3, ~10 test cases)

**Quality gate**: All Tier 1-4 tests pass. ORDER BY with GROUP BY references WITH aliases. ORDER BY without GROUP BY unchanged. Sort direction preserved.

---

## Phase 6: HAVING Condition Translation

**Goal**: Replace `havingCondition()` stub with registry-aware implementation. Support HAVING-only aggregates as hidden WITH columns.

**Spec reference**: [group phase 4.md §Phase 6](group%20phase%204.md#phase-6-having-condition-translation)

- [ ] **6.1** Verify HAVING forces WITH path: line 480 already does `havingCondition != null || requiresWithForGroupBy(...)` — confirm this remains correct after Phase 4 changes
- [ ] **6.2** Change `havingCondition()` signature (line 615): accept `AliasRegistry registry` instead of `List<IdentifiableElement> withExpressions`; update call site in `buildWithClause()` (line 599)
- [ ] **6.3** Scan HAVING for aggregates not in SELECT: before `reading.with(...)` (line 595), walk the `havingCondition` jOOQ tree recursively (through `QOM.And`, `QOM.Or`, comparison operators, arithmetic nodes) collecting aggregate `Field<?>` nodes. For each aggregate not already in the registry (`registry.resolve(agg) == null`), translate it, assign `__having_col_N` alias, add to `withExpressions`, register in registry. These hidden columns are NOT added to `returnExpressions`
- [ ] **6.4** Make `FieldMatcher.isAggregate()` package-private (line 115 of `FieldMatcher.java`): change from `private` to default visibility so `SqlToCypher` can use it in the HAVING aggregate scan
- [ ] **6.5** Add registry interception to `expression(Field<?>)`: at the top of `expression(Field<?> f, boolean turnUnknownIntoNames)`, add: if `this.aliasRegistry != null` and in HAVING context, call `aliasRegistry.resolve(f)` — if non-null, return `Cypher.name(alias)`. Use a context flag (`private boolean inHavingContext`) set before/after the `condition(havingCondition)` call in `havingCondition()`, reset in a `finally` block. **IMPORTANT timing note**: `havingCondition()` is called inside `buildWithClause()` (line 587) BEFORE `this.aliasRegistry` is set (line 490). The `havingCondition()` method must set `this.aliasRegistry = registry` (from its parameter) before calling `condition(c)`, and the `inHavingContext` flag is still needed to prevent the registry from interfering with non-HAVING expression translation within the same `buildWithClause()` call
- [ ] **6.6** Implement `havingCondition()` body: set `this.aliasRegistry = registry` and `inHavingContext = true`, call `condition(c)` (the existing condition translator handles And/Or/Gt/etc.), reset both in a `finally` block. The interception in 6.5 resolves each aggregate leaf independently — compound conditions and arithmetic work automatically. Note: `this.aliasRegistry` will be set again at line 490 after `buildWithClause()` returns, so the early assignment is safe
- [ ] **6.7** Verify HAVING-by-alias works: `AliasRegistry.resolve()` name-based fallback handles `HAVING cnt > 5` where `cnt` is an unresolved field reference — no additional code needed, just end-to-end verification

**Tests**: See [prodTestingPlan.md §Phase 6](prodTestingPlan.md#phase-6-having-translation) (items 6.1-6.7, ~20 test cases)

**Quality gate**: All Tier 1-5 tests pass. HAVING produces correct WHERE after WITH. Hidden columns in WITH excluded from RETURN. Both function-form and alias-form HAVING produce identical output.

---

## Phase 7: DISTINCT, LIMIT, and Hardening

**Goal**: Verify correctness of DISTINCT and LIMIT placement with WITH, clean up code quality, review all code paths.

**Spec reference**: [group phase 4.md §Phase 7](group%20phase%204.md#phase-7-distinct-validation-and-hardening)

- [ ] **7.1** Verify DISTINCT placement (lines 494-496): DISTINCT applies to `effectiveReading.returningDistinct()` which is the post-WITH scope — produces `RETURN DISTINCT`, not `WITH DISTINCT`. No production code change expected — verify with tests
- [ ] **7.2** Verify LIMIT placement (lines 621+): `addLimit()` receives the RETURN projection — LIMIT attaches after RETURN, not after WITH. No production code change expected — verify with tests
- [ ] **7.3** GROUP BY validation decision: decide whether to reject, warn, or silently translate queries with non-aggregated SELECT columns missing from GROUP BY. Recommended: silently translate (see [group phase 4.md §Remaining Open Questions](group%20phase%204.md#remaining-open-questions)). Document the decision
- [ ] **7.4** Clean up HAVING context state: ensure `inHavingContext` flag (from 6.5) is reset in `finally` block to prevent state leaks on exceptions
- [ ] **7.5** Code quality: run `./mvnw spring-javaformat:apply`, `./mvnw license:format`, verify checkstyle clean
- [ ] **7.6** End-to-end code path review: trace every path through `statement(Select<?>)`: (a) no GROUP BY/HAVING, (b) GROUP BY matches SELECT, (c) GROUP BY differs from SELECT, (d) GROUP BY + HAVING, (e) HAVING only, (f) each of the above + DISTINCT, (g) + ORDER BY, (h) + LIMIT. Confirm each has a corresponding test

**Tests**: See [prodTestingPlan.md §Phase 7](prodTestingPlan.md#phase-7-distinct-limit-combinations-and-hardening) (items 7.1-7.4, ~12 test cases)

**Quality gate**: Full unit test suite green. Checkstyle clean. License headers present. All formatting applied. Every `statement(Select<?>)` code path covered by at least one test.

---

## Dependencies

```
Phase 4 ──> Phase 5 ──> Phase 6 ──> Phase 7
  |              |            |
  |              |            └── uses registry + interception from 4
  |              └── uses registry from 4
  └── uses FieldMatcher (Phase 2) + AliasRegistry (Phase 3)
```

Phase 4 must complete first (establishes registry threading). Phases 5 and 6 consume the registry. Phase 7 is hardening after all prior phases.

## Files Modified

| File | Phases | Changes |
|---|---|---|
| `SqlToCypher.java` | 4, 5, 6, 7 | `requiresWithForGroupBy()`, `buildWithClause()`, `WithClauseResult`, `havingCondition()`, `expression(SortField<?>)`, `expression(Field<?>)`, instance field `aliasRegistry` |
| `FieldMatcher.java` | 6 | Make `isAggregate()` package-private |
| `AliasRegistry.java` | — | No changes (consumed as-is) |
