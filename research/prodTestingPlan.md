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

## Final Validation

- [ ] Run full baseline capture: `test.sh --step 1 through 5 --output final`
- [ ] Compare `final/` output against `init_baseline/` — only expected changes
- [ ] Total test count target: ~515 tests in translator module (265 existing + 35 diagnostic + ~215 new)
- [ ] No checkstyle violations
- [ ] No license header issues
- [ ] All formatting applied
