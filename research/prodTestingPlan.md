# Production Testing Implementation Plan

Phased checklist for implementing the testing strategy defined in [prodTesting.md](prodTesting.md). Each phase is a quality gate — all items must be checked before moving to the next phase. Phases align with the implementation phases in [group phase 4.md](group%20phase%204.md).

Baseline reference (captured in `research_logs/init_baseline/`):
- Translator unit tests: 300 tests, 6 expected failures (aggregates[4-9])
- All-module unit tests: 5603+ tests, 0 non-translator failures
- Integration tests: all IT modules pass (2 known issues: timezone flake in PreparedStatementIT[36], FieldProxy error in TranslationIT.innerJoinColumnsWrongDirection from in-progress code)

---

## Parallelization Strategy

Phases are organized into parallel groups to maximize throughput. Each group runs as separate agents in isolated git worktrees, then merges before the next group starts.

```
Time →

           ┌─ Agent A: Phase 1 (snapshot tests)        ─┐
Parallel   ├─ Agent B: Phase 2 (field matcher)          ─┤── merge ──┐
Group 1    └─ Agent C: Phase 3 (alias registry)         ─┘           │
                                                                     ▼
Sequential ──────────── Phase 4 (WITH clause wiring) ────────────────┤
                                                                     │
           ┌─ Agent D: Phase 5 (ORDER BY alias)         ─┐          │
Parallel   │                                              ├─ merge ──┤
Group 2    └─ Agent E: Phase 6 (HAVING translation)     ─┘          │
                                                                     ▼
Sequential ──────────── Phase 7 (DISTINCT, LIMIT, hardening) ────────┘
```

### Group 1 — Fully parallelizable (zero file conflicts)

| Agent | Phase | Files Created/Modified | Conflict Risk |
|-------|-------|----------------------|---------------|
| A | Phase 1 (Tier 1 Snapshot) | Adds methods to `SqlToCypherTests.java` | None — new methods only |
| B | Phase 2 (Field Matcher) | New `FieldMatcher.java` + `FieldMatcherTests.java` | None — new files only |
| C | Phase 3 (Alias Registry) | New `AliasRegistry.java` + `AliasRegistryTests.java` | None — new files only |

### Group 2 — Parallelizable with merge risk

| Agent | Phase | Files Modified | Conflict Risk |
|-------|-------|---------------|---------------|
| D | Phase 5 (ORDER BY) | `SqlToCypher.java`, `SqlToCypherTests.java` | Medium — different methods but same files |
| E | Phase 6 (HAVING) | `SqlToCypher.java`, `SqlToCypherTests.java` | Medium — different methods but same files |

### Sequential phases

- **Phase 4**: Wires matcher + registry into translator. Must wait for Group 1 merge.
- **Phase 7**: Hardening + combinations. Must wait for Group 2 merge.
- **Phase 8**: Integration tests (excluded from parallelization — requires Docker, run manually).

### Execution Log

| Timestamp | Action | Status | Notes |
|-----------|--------|--------|-------|
| 2026-03-15 | Group 1 launched (Agents A, B, C) | COMPLETE | 3 parallel worktree agents |
| 2026-03-15 | Group 1 merged to main working directory | COMPLETE | All 83 new tests pass, 6 pre-existing failures unchanged |
| | Agent A (Phase 1): 45 snapshot tests | PASS | 9 methods in SqlToCypherTests.java |
| | Agent B (Phase 2): 23 field matcher tests | PASS | FieldMatcher.java + FieldMatcherTests.java |
| | Agent C (Phase 3): 15 alias registry tests | PASS | AliasRegistry.java + AliasRegistryTests.java |
| | Merge conflict resolution | CLEAN | Used Phase 2 FieldMatcher.java (authoritative), discarded Phase 3 duplicate |
| | Post-merge test run: 286 tests, 6 failures (pre-existing), 0 errors | PASS | All new tests green |
| 2026-03-15 | Phase 4: WITH Clause Generation | COMPLETE | Production code + 5 new tests |
| | Replaced `resolveFieldName` with `FieldMatcher.fieldsMatch` | DONE | Structural matching for JOIN correctness |
| | Updated aggregates[4-9] expectations | DONE | 6 tests now expect WITH-based output |
| | Post-Phase 4 test run: 390 tests, 0 failures, 0 errors | PASS | All 6 aggregate failures resolved |

---

## Pre-Implementation: Tier 1 Regression Snapshot

> Write and verify snapshot tests BEFORE any production code changes.
> Reference: prodTesting.md §1.1–1.3

- [x] Add `snapshotSimpleSelects` parameterized test to `SqlToCypherTests.java` (7 SQL|Cypher pairs)
- [x] Add `snapshotGlobalAggregates` parameterized test (6 pairs)
- [x] Add `snapshotGroupByMatchingSelect` parameterized test (3 pairs)
- [x] Add `snapshotJoins` parameterized test (3 pairs — NATURAL JOIN omitted)
- [x] Add `snapshotDml` parameterized test (4 pairs)
- [x] Add `snapshotPredicates` parameterized test (9 pairs)
- [x] Add `snapshotCaseExpressions` parameterized test (1 pair)
- [x] Add `snapshotDistinct` parameterized test (2 pairs)
- [x] Add non-GROUP-BY path isolation test (§1.3) — 10 cases verifying no WITH clause
- [x] Run tests — all 45 snapshot tests green, existing tests unchanged (6 expected failures)

**Gate**: 45 new snapshot tests all pass. Zero regressions. PASSED.

---

## Phase 2: Structural Field Matcher

> Build and test `fieldsMatch()` in isolation, no production wiring.
> Reference: prodTesting.md §Tier 2 (2.1–2.5), group phase 4.md §Phase 2

- [x] Create `FieldMatcherTests.java` in translator-impl test directory
- [x] Create `FieldMatcher.java` in translator-impl main directory
- [x] Implement column reference matching tests (§2.1, 5 cases)
- [x] Implement aggregate function matching tests (§2.2, 9 cases)
- [x] Implement alias transparency tests (§2.3, 3 cases)
- [x] Implement cross-parse matching tests (§2.4, 2 cases)
- [x] Implement negative/false-positive tests (§2.5, 4 cases)
- [x] All tests use parsed SQL via jOOQ parser, not hand-constructed objects
- [x] Run tests — Tier 1 snapshots still green, 23 matcher tests pass

**Gate**: 23 matcher tests pass. Tier 1 still green. No existing production code changed. PASSED.

---

## Phase 3: Alias Registry

> Build and test the alias registry that maps expressions to WITH aliases.
> Reference: prodTesting.md §Tier 3 (3.1–3.4), group phase 4.md §Phase 3

- [x] Create `AliasRegistryTests.java` in translator-impl test directory
- [x] Create `AliasRegistry.java` in translator-impl main directory
- [x] Implement structural lookup tests (§3.1, 5 cases)
- [x] Implement name-based lookup tests (§3.2, 3 cases)
- [x] Implement combined mode tests (§3.3, 5 cases)
- [x] Implement round-trip from parsed SQL test (§3.4, 2 cases)
- [x] Run tests — Tiers 1-2 still green, 15 registry tests pass

**Gate**: 15 registry tests pass. Tiers 1-2 still green. No existing production code changed. PASSED.

---

## Phase 4: WITH Clause Generation

> Wire GROUP BY detection and WITH clause into the translator. First production code change.
> Reference: prodTesting.md §Tier 4 (4.1–4.4), group phase 4.md §Phase 4

- [x] Add core WITH generation tests (§4.1, 1 case: GROUP BY with hidden column)
- [x] Add JOIN + GROUP BY tests (§4.2, 2 cases: matching SELECT + hidden GROUP BY)
- [x] Add multiple aggregates tests (§4.3, 2 cases: no-WITH + WITH paths)
- [x] Update expectations for existing aggregates[4-9] tests (§4.4, 6 cases)
- [x] Verify common case still produces simple RETURN (no WITH) when GROUP BY matches SELECT
- [x] Production code: replaced `resolveFieldName` with `FieldMatcher.fieldsMatch` in `requiresWithForGroupBy` and `buildWithClause` for table-qualified structural comparison
- [x] Run translator tests — 390 tests, 0 failures, 0 errors (was 6 failures before)
- [x] Review generated Cypher output for correctness and readability

**Gate**: 5 new WITH tests pass. The 6 aggregate failures are resolved. All Tier 1 snapshots pass unchanged. Zero regressions. PASSED.

---

## Phase 5: ORDER BY Alias Resolution

> Fix ORDER BY to reference WITH aliases when WITH clause is present.
> Reference: prodTesting.md §Tier 5 (5.1–5.3), group phase 4.md §Phase 5

- [ ] Add ORDER BY with WITH clause tests (§5.1, ~4 cases)
- [ ] Add ORDER BY regression tests — no GROUP BY (§5.2, ~4 cases)
- [ ] Add ORDER BY + GROUP BY + LIMIT chain test (§5.3, ~1 case)
- [ ] Verify sort direction (ASC/DESC) preserved after alias resolution
- [ ] Run `test.sh --step 1` — Tiers 1-4 still green
- [ ] Run `test.sh --step 2` — no new failures

**Gate**: ~10 ORDER BY tests pass. All previous tiers still green.

---

## Phase 6: HAVING Translation

> Add HAVING support using the structural matcher and alias registry.
> Reference: prodTesting.md §Tier 6 (6.1–6.7), group phase 4.md §Phase 6

- [ ] Add simple HAVING tests (§6.1, ~3 cases)
- [ ] Add compound HAVING tests — AND, OR, nested (§6.2, ~3 cases)
- [ ] Add arithmetic in HAVING test (§6.3, ~1 case)
- [ ] Add HAVING with aggregate NOT in SELECT tests (§6.4, ~2 cases)
- [ ] Add HAVING with every supported aggregate test (§6.5, ~5 cases)
- [ ] Add HAVING + ORDER BY combined tests (§6.6, ~2 cases)
- [ ] Add HAVING on JOIN queries test (§6.7, ~1 case)
- [ ] Verify hidden columns appear in WITH but not in RETURN
- [ ] Verify HAVING by alias produces same output as HAVING by function form
- [ ] Run `test.sh --step 1` — Tiers 1-5 still green
- [ ] Run `test.sh --step 2` — no new failures

**Gate**: ~20 HAVING tests pass. All previous tiers still green.

---

## Phase 7: DISTINCT, LIMIT, Combinations, and Hardening

> Handle remaining edge cases and combination coverage.
> Reference: prodTesting.md §Tier 7 (7.1–7.4), group phase 4.md §Phase 7

- [ ] Add DISTINCT with WITH clause tests (§7.1, ~2 cases)
- [ ] Add full combination "kitchen sink" tests (§7.2, ~2 cases)
- [ ] Add LIMIT/OFFSET placement tests (§7.3, ~4 cases)
- [ ] Add no-GROUP-BY full regression tests (§7.4, ~6 cases)
- [ ] Run `test.sh --step 1` — all unit tests green
- [ ] Run `test.sh --step 2` — all modules green
- [ ] Run `test.sh --step 5` — checkstyle clean (resolve all 77 violations)
- [ ] Apply formatting: `./mvnw spring-javaformat:apply`
- [ ] Apply license headers: `./mvnw license:format`

**Gate**: ~12 combination tests pass. Full unit test suite green. Checkstyle clean.

---

## Phase 8: Integration Tests

> Verify semantic correctness against real Neo4j via Testcontainers.
> Reference: prodTesting.md §Tier 8 (8.1–8.19)
>
> **Note**: Integration tests require Docker and take a long time to run. Claude will write the test code, but the user runs `test.sh --step 3` manually and reports results back. Claude does not run step 3.

- [ ] Create `GroupByHavingIT extends IntegrationTestBase` in `neo4j-jdbc-it-cp`
- [ ] 8.1: Basic GROUP BY — single table, single group column (~7 tests)
- [ ] 8.2: GROUP BY column NOT in SELECT — WITH clause required (~4 tests)
- [ ] 8.3: Multiple GROUP BY columns (~6 tests)
- [ ] 8.4: HAVING — basic filtering (~7 tests)
- [ ] 8.5: HAVING with aggregate NOT in SELECT (~4 tests)
- [ ] 8.6: Compound HAVING — AND, OR, arithmetic (~4 tests)
- [ ] 8.7: HAVING by alias (~4 tests)
- [ ] 8.8: ORDER BY with GROUP BY (~6 tests)
- [ ] 8.9: ORDER BY + HAVING combined (~4 tests)
- [ ] 8.10: LIMIT and OFFSET with GROUP BY (~5 tests)
- [ ] 8.11: DISTINCT with GROUP BY (~3 tests)
- [ ] 8.12: JOIN queries with GROUP BY — Movies graph (~6 tests)
- [ ] 8.13: GROUP BY with WHERE — pre-aggregation filtering (~5 tests)
- [ ] 8.14: Global aggregation — no GROUP BY regression (~5 tests)
- [ ] 8.15: Edge cases — empty table, single row, NULLs, LIMIT 0 (~8 tests)
- [ ] 8.16: Multi-aggregate combinations (~4 tests)
- [ ] 8.17: Full chain "kitchen sink" tests (~4 tests)
- [ ] 8.18: Regression against existing IT patterns (~9 tests)
- [ ] 8.19: Movies graph real-world analytic queries (~5 tests)
- [ ] **User action**: Run `test.sh --step 3 --output <phase>` and report results
- [ ] Verify TranslationIT.innerJoinColumnsWrongDirection now passes (FieldProxy fix)

**Gate**: ~100 integration tests pass. Full test suite green across all tiers.

---

## Open Questions — Parallel Group 2 (Phases 5 & 6)

### Q1: Parallel Group 2 contradicts the dependency graph

The parallelization strategy (lines 25-28) places Phase 5 and Phase 6 in **Parallel Group 2** with "Medium" conflict risk. However, the dependency graph in `group_phase_4_plan.md` (line 303) explicitly shows `Phase 5 → Phase 6` as **sequential**, and the Phase 6 checklist depends on four Phase 5 deliverables:

- **5.1** (registry timing restructure) — Phase 6's `havingCondition()` reads `this.aliasRegistry` which Phase 5 moves into `buildWithClause()`
- **5.3** (unified `expression(Field<?>)` interception) — Phase 6's HAVING alias resolution relies on this to translate `count(*)` → `__with_col_0` in the WHERE clause
- **5.4** (`isAggregate()` visibility) — Phase 6's `collectAggregates()` utility calls this
- **5.2** (`finally` cleanup block) — Phase 6 assumes registry lifecycle is managed

**Question**: Should Parallel Group 2 be changed to sequential (Phase 5 → Phase 6), matching the dependency graph? Or is there a way to factor out the shared infrastructure so the phases can truly run in parallel?

**Resolution**: Changed to sequential. Phase 5 → Phase 6. The dependency is real — Phase 6 cannot start until Phase 5's unified interception, registry timing, and `isAggregate()` visibility are in place.

**Impact on parallelization strategy**: Update the diagram in lines 17-30 to remove Parallel Group 2 and show Phases 5-7 as fully sequential after Phase 4.

### Q2: Existing test expectations at lines 968-969 are known-broken

The `withClauseGeneration` tests added in Phase 4 include two cases with intentionally incorrect expectations that reflect the current (pre-Phase 5/6) behavior:

- **Line 968** (HAVING): expects `WHERE sum(p.age) > 100` — but `p` is de-scoped after the WITH clause. Correct output should be `WHERE __with_col_0 > 100`.
- **Line 969** (ORDER BY): expects `ORDER BY sum(p.age)` — same issue. Correct output should be `ORDER BY __with_col_0`.

These tests currently PASS because the unified registry interception (Phase 5, item 5.3) hasn't been implemented yet, so aggregates are re-translated rather than resolved to aliases.

**Question**: Which phase is responsible for updating each expectation? Phase 5's unified interception (5.3) will fix BOTH ORDER BY and HAVING resolution in one change — meaning Phase 5 would break line 968 (HAVING) even though HAVING is nominally Phase 6's domain. Should Phase 5 update both expectations, or should line 968 be deferred?

**Recommendation**: Phase 5 updates BOTH expectations. The unified interception (5.3) is a single atomic change — once it lands, both ORDER BY and HAVING resolve through the registry. Leaving line 968 broken would violate the "all tests green after each phase" quality gate (BP-7 in `group_phase_4_plan.md`). This is already aligned with `group_phase_4_plan.md` item 5.8 and its "Existing Test Expectations That Must Change" section, which explicitly lists both lines.

**Note**: This is consistent, not overlapping — `group_phase_4_plan.md` already accounts for this. No change needed to either document beyond this resolution.

### Q3: `collectAggregates()` insertion point in `buildWithClause()`

Phase 6.4 says to integrate `collectAggregates()` "after the SELECT and GROUP BY loops (line 582) and BEFORE `reading.with(withExpressions)` (line 583)." Looking at the current code:

```java
// line 581: }  (end of GROUP BY loop)
// line 582: (blank)
// line 583: var withStep = reading.with(withExpressions);
```

The HAVING aggregate injection must add to `withExpressions` between lines 581 and 583. But `this.aliasRegistry` is currently set at line 490 (after `buildWithClause()` returns), and Phase 5.1 moves it to "just BEFORE the HAVING condition check" at line 586 — which is AFTER `reading.with()` at line 583.

**Question**: Phase 6.4 needs the registry populated (to call `registry.resolve(agg)` for dedup), but it also needs to run before `reading.with()`. Phase 5.1 sets the registry before the HAVING condition check (line 586), not before line 583. Should Phase 5.1's registry assignment be moved earlier — to just after the GROUP BY loop (line 581) — so Phase 6.4 can use it for dedup AND inject hidden columns before `reading.with()`?

**Recommendation**: Yes — move the registry assignment in Phase 5.1 to just after the GROUP BY loop ends (line 581), before `reading.with()` at line 583. The registry is fully populated at that point (both SELECT and GROUP BY loops are done), so this is safe. The new order in `buildWithClause()` becomes:

```
1. SELECT loop (populates registry with select fields)
2. GROUP BY loop (populates registry with group-only fields)
3. this.aliasRegistry = registry;          ← moved here (Phase 5.1)
4. [Phase 6.4 injection point: collectAggregates → dedup → add hidden cols]
5. var withStep = reading.with(withExpressions);
6. havingCondition() call (reads registry via expression(Field<?>))
```

This is a GAP in `group_phase_4_plan.md` — item 5.1 says "just BEFORE the HAVING condition check" but should say "just AFTER the GROUP BY loop, BEFORE `reading.with()`." Update `group_phase_4_plan.md` item 5.1 to reflect this earlier placement. Without this fix, Phase 6.4 cannot deduplicate HAVING aggregates against the registry.

**Impact**: Also affects BP-6 in `group_phase_4_plan.md` — the calling-context trace for `buildWithClause()` SELECT/GROUP BY loops says "registry not yet assigned to `this.aliasRegistry`, so the interception is inactive." With the earlier assignment, the interception WOULD be active during the Phase 6.4 HAVING aggregate collection. But this is safe because `collectAggregates()` walks the jOOQ condition tree directly — it doesn't call `expression(Field<?>)`. The `expression(agg)` call in 6.4 step 3 translates HAVING-only aggregates for the WITH clause, which should NOT resolve via the registry (they're not registered yet). **Clarification needed**: Phase 6.4 step 3 must call `expression(agg)` BEFORE registering the aggregate — otherwise the registry interception would short-circuit the translation. Document this ordering constraint in Phase 6.4.

### Q4: Phase 5 unified interception also fixes HAVING — scope overlap

Phase 5.3 adds the type-guarded registry check to `expression(Field<?>)`. This check runs for ALL field resolution when the registry is non-null — including fields inside HAVING conditions (since `havingCondition()` at line 587 calls `condition(c)` which eventually calls `expression(Field<?>)`).

This means Phase 5 effectively fixes simple HAVING alias resolution as a side effect, even though HAVING is Phase 6's responsibility. The only thing Phase 6 uniquely adds is:
- `collectAggregates()` for HAVING-only hidden columns
- `havingCondition()` signature cleanup

**Question**: Should the testing plan acknowledge that Phase 5's unified interception is the mechanism that makes HAVING work, and Phase 6 is really about the "HAVING aggregate NOT in SELECT" edge case? This affects which tests belong where — simple HAVING tests (§6.1) might be more naturally validated in Phase 5.

**Recommendation**: Yes — acknowledge the scope overlap and sharpen the phase boundaries. After Phase 5, simple HAVING (where the HAVING aggregate is already in SELECT) works automatically. The testing plan should reflect this:

- **Phase 5 tests should include**: simple HAVING where the aggregate IS in SELECT (e.g., `SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING count(*) > 5`). This validates the unified interception end-to-end for both ORDER BY and HAVING.
- **Phase 6 tests focus exclusively on**: HAVING with aggregates NOT in SELECT (hidden columns), compound HAVING, arithmetic in HAVING, HAVING-by-alias edge cases, and `collectAggregates()` unit tests.

This reframing matches reality: Phase 5 delivers the interception mechanism, Phase 6 delivers the "hidden aggregate" feature. Update `prodTestingPlan.md` Phase 5 test section to add ~3 simple HAVING validation cases, and narrow Phase 6 §6.1 to only cover the NOT-in-SELECT case. This also reduces Phase 6's scope and potential for conflicts.

### Q5: `expression(SortField<?>)` existing catch block interaction with 5.6

The `expression(SortField<?>)` method (lines 1403-1428) already has a try-catch for `IllegalArgumentException` (lines 1414-1417) that handles unresolved `TableField` lookups. Phase 5.6 proposes throwing `IllegalArgumentException` for de-scoped ORDER BY fields.

**Question**: Will the existing catch block at line 1414 inadvertently swallow the new Phase 5.6 error? The catch filters on `theField instanceof TableField<?, ?> tf && tf.getTable() == null`, which would NOT match a table-qualified field like `p.age` — but it's worth confirming in the test plan that de-scoped error detection works correctly given this existing exception handling.

**Resolution**: Already addressed by RN-1 in `group_phase_4_plan.md`. The implementation plan places the `aliasRegistry != null` guard at the TOP of the catch block, before the `findTableFieldInTables()` fallback. The catch block won't swallow the error because the guard re-throws immediately. The existing `tf.getTable() == null` filter only fires for non-WITH queries (when `aliasRegistry` is null). Add a specific test case for this in Phase 5 tests: ORDER BY on a table-qualified field (e.g., `p.age`) not in the registry, WITH clause present — should throw, not silently produce broken Cypher.

### Q6: `havingCondition()` unused parameter cleanup timing

The `withExpressions` parameter in `havingCondition(org.jooq.Condition c, List<IdentifiableElement> withExpressions)` at line 603 is already unused (the body is just `return condition(c)`). Phase 6.5 removes it.

**Question**: Since Phase 5 restructures the `buildWithClause()` internals (items 5.1-5.2) and touches the call site area around line 587, should Phase 5 preemptively remove this dead parameter to reduce Phase 6's merge surface? Or leave it to keep Phase 5 strictly scoped to ORDER BY?

**Recommendation**: Phase 5 should remove it. Rationale:
1. Phase 5 already touches the call site (line 587) when restructuring registry timing
2. The parameter is dead code NOW (Phase 4 left it unused) — removing dead code in the area you're already modifying is clean practice
3. It removes one item from Phase 6's checklist, reducing Phase 6's scope
4. Since Phases 5 and 6 are now sequential (Q1), there's no merge risk — Phase 6 simply starts with the cleaner signature

Add this to `group_phase_4_plan.md` Phase 5 as a new item 5.9, and remove item 6.5 from Phase 6.

---

### Cross-Document Overlap Analysis: `prodTestingPlan.md` vs `group_phase_4_plan.md`

The two documents serve different purposes but have significant overlap in Phase 5-6 that could cause confusion or conflicting instructions for an implementing agent:

**What overlaps:**

| Topic | `prodTestingPlan.md` | `group_phase_4_plan.md` |
|-------|---------------------|------------------------|
| Phase 5/6 checklist items | §Phase 5/6 test checklists | §Phase 5/6 implementation checklists |
| Test expectations to update | Q2 (lines 968-969) | "Existing Test Expectations That Must Change" section |
| Registry timing | Q3 (insertion point) | Item 5.1 + RN-3 |
| Catch block interaction | Q5 | RN-1 |
| Scope of Phase 5 vs 6 | Q4 (which tests where) | Phase 5 goal statement + dependency graph |

**What doesn't overlap (each doc is authoritative):**

- `prodTestingPlan.md` is authoritative for: test case specifications (SQL/Cypher pairs), test placement (which file), tier structure, integration test specs (§8.1-8.19), quality gates for test counts
- `group_phase_4_plan.md` is authoritative for: production code changes (method edits, new methods), code-level implementation details (type guards, catch block placement), implementation best practices (BP-1 through BP-8), review notes (RN-1 through RN-5)

**Recommendation to minimize conflict:**

1. **Single source of truth per concern.** Don't duplicate implementation details in `prodTestingPlan.md` — reference `group_phase_4_plan.md` instead. The testing plan should specify WHAT to test (SQL input, expected Cypher output, edge cases), not HOW the production code works.

2. **Consolidate the Q/A resolutions.** The questions in this section that are already addressed in `group_phase_4_plan.md` (Q2, Q5) should note "already resolved in `group_phase_4_plan.md`" rather than providing independent analysis that could drift.

3. **Flag the GAP.** Q3 (registry timing for Phase 6.4) is a real gap — `group_phase_4_plan.md` item 5.1 says "before HAVING condition check" but needs to say "after GROUP BY loop, before `reading.with()`." This must be fixed in `group_phase_4_plan.md` before Phase 5 implementation starts.

4. **Update the parallelization diagram.** Remove Parallel Group 2 from `prodTestingPlan.md` lines 17-30. Replace with:
   ```
   Phase 4 (complete) → Phase 5 → Phase 6 → Phase 7
   ```
   All sequential. No parallel group for Phases 5-7.

5. **Scope minimization for Phase 6.** With Q4's recommendation (move simple HAVING tests to Phase 5) and Q6's recommendation (move `havingCondition()` cleanup to Phase 5), Phase 6 reduces to:
   - `collectAggregates()` utility + tests (6.2, 6.3)
   - Hidden HAVING column integration (6.4)
   - Verification tests for HAVING-only aggregates, compound HAVING, HAVING-by-alias (6.6-6.8)

   This is a cleaner, smaller scope that touches fewer lines in `SqlToCypher.java` — just `buildWithClause()` for the aggregate injection, plus the new utility method.

---

## Final Validation

- [ ] Run full baseline capture: `test.sh --step 1 through 5 --output final`
- [ ] Compare `final/` output against `init_baseline/` — only expected changes
- [ ] Total test count target: ~515 tests in translator module (265 existing + 35 diagnostic + ~215 new)
- [ ] No checkstyle violations
- [ ] No license header issues
- [ ] All formatting applied
