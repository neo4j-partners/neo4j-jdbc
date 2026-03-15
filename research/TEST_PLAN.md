# TEST_PLAN.md — Baseline Capture & Regression Testing

## Quick Start

Use `research_logs/test.sh` for all test runs. Output is organized into named directories under `research_logs/`.

```bash
# Initial baseline (run before any code changes)
research_logs/test.sh --step all --output init_baseline

# After completing a phase, run against a new output dir and compare
research_logs/test.sh --step 1 --output post_phase2
research_logs/test.sh --step 1 --output post_phase4

# Run a single step
research_logs/test.sh --step 1 --output my_test
research_logs/test.sh --step 3 --output integration_check
```

Each run creates a directory like `research_logs/post_phase2/` containing the log files for that run. Previous runs are never overwritten.

## Prerequisites

- JDK 17+
- Docker running (required for Step 3 — integration tests via Testcontainers)

## What the Script Handles

The script automatically skips three Maven plugins that would otherwise fail the build before tests run:

1. **Checkstyle**: The in-progress GROUP BY code has 77 violations (missing javadoc, `InnerTypeLast` ordering). Expected — code hasn't been styled yet.
2. **Spring JavaFormat**: Same in-progress code triggers formatting violations.
3. **License headers**: `research_logs/` contains files without Apache 2.0 headers.

It also uses `-fae` (fail-at-end) for Steps 2 and 3 so Maven continues through all modules even when the translator's 6 known failures occur.

## Steps

| Step | What | Time | Requires |
|------|------|------|----------|
| 1 | Translator module unit tests | ~12s | JDK 17+ |
| 2 | All modules unit tests | ~2-5min | JDK 17+ |
| 3 | Integration tests (Testcontainers) | ~5-15min | JDK 17+ and Docker |
| 4 | Cypher output capture (SqlToCypherTests) | ~12s | JDK 17+ |
| 5 | Checkstyle state recording | ~5s | JDK 17+ |
| all | Steps 1-5 sequentially | ~8-20min | JDK 17+ and Docker |

## Output Structure

```
research_logs/
├── test.sh                              ← The test runner script
├── init_baseline/                       ← Initial baseline (run once before any changes)
│   ├── translator-unit.log
│   ├── translator-summary.txt
│   ├── all-unit.log
│   ├── all-unit-summary.txt
│   ├── integration.log
│   ├── integration-summary.txt
│   ├── cypher-output.log
│   └── checkstyle.log
├── post_phase2/                         ← After Phase 2 (structural matcher)
│   ├── translator-unit.log
│   └── translator-summary.txt
├── post_phase4/                         ← After Phase 4 (WITH clause generation)
│   └── ...
└── ...
```

## Confirmed Baseline State

**Step 1** (captured 2026-03-15): 300 tests run, 6 failures, 0 errors.

The 6 expected failures are all in `SqlToCypherTests.aggregates` — GROUP BY queries where the grouping column is NOT in SELECT. The test expectations are stale: they expect the old wrong output, but the in-progress `buildWithClause()` code is already producing the correct WITH-based output.

| Test | SQL | Test expects (old, wrong) | Translator produces (new, correct) |
|------|-----|--------------------------|-----------------------------------|
| aggregates[4] | `SELECT sum(age) FROM People p GROUP BY name` | `RETURN sum(p.age)` | `WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0` |
| aggregates[5] | `SELECT avg(age) FROM People p GROUP BY name` | `RETURN avg(p.age)` | same pattern with `avg` |
| aggregates[6] | `SELECT percentileCont(age) FROM People p GROUP BY name` | `RETURN percentileCont(p.age)` | same pattern |
| aggregates[7] | `SELECT percentileDisc(age) FROM People p GROUP BY name` | `RETURN percentileDisc(p.age)` | same pattern |
| aggregates[8] | `SELECT stDev(age) FROM People p GROUP BY name` | `RETURN stDev(p.age)` | same pattern |
| aggregates[9] | `SELECT stDevP(age) FROM People p GROUP BY name` | `RETURN stDevP(p.age)` | same pattern |

Root cause: when GROUP BY lists a column not in SELECT, the old code ignored it. The new code correctly adds a WITH clause to make the grouping explicit in Cypher. The test expectations will be updated in Phase 4 to match the new correct output.

## Regression Checking

After each phase, compare failures against the baseline:

```bash
# Run step 1 against a new output dir
research_logs/test.sh --step 1 --output post_phase4

# Compare failures
diff research_logs/init_baseline/translator-summary.txt \
     research_logs/post_phase4/translator-summary.txt
```

- **New failures** not in the baseline = regression. Stop and investigate.
- **Fewer failures** (e.g., Phase 4 fixes the 6 aggregate tests) = expected progress.
- **Identical** = no change to this step's scope, which is correct for phases that don't touch the translator output.

## Quick Reference — Run a Single Test Manually

```bash
# Single test class
./mvnw -DskipITs -Dcheckstyle.skip=true -Dspring-javaformat.skip=true \
  -am -pl neo4j-jdbc-translator/impl test -Dtest=SqlToCypherTests

# Single test method
./mvnw -DskipITs -Dcheckstyle.skip=true -Dspring-javaformat.skip=true \
  -am -pl neo4j-jdbc-translator/impl test -Dtest=SqlToCypherTests#aggregates

# Diagnostic tests only (Phase 1)
./mvnw -DskipITs -Dcheckstyle.skip=true -Dspring-javaformat.skip=true \
  -am -pl neo4j-jdbc-translator/impl test -Dtest=JooqQomDiagnosticTests

# Integration tests only (requires Docker)
./mvnw -fae -DskipUTs -Dcheckstyle.skip=true -Dspring-javaformat.skip=true -Dlicense.skip=true \
  -pl neo4j-jdbc-it/neo4j-jdbc-it-cp verify
```
