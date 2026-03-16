# GROUP BY / HAVING — Recommended Polish Work

## Current State

- **Unit tests:** 445 passing, 0 failures, 0 errors
- **Production code:** Phases 1-7 complete, all code paths tested
- **No debugging artifacts:** No `System.out.println`, no TODO/FIXME markers, no temporary tests
- **Formatting:** Spring JavaFormat applied; Javadoc `@param`/`@return` tags added to key methods
- **Checkstyle:** 72 pre-existing `InnerTypeLast` violations (structural ordering, not introduced by this work)

---

## Priority 1: Integration Tests (Phase 8)

**Effort: Medium | Value: High**

Unit tests validate Cypher output strings. Integration tests validate that the generated Cypher actually executes correctly against a real Neo4j instance. This is the most impactful remaining work.

The full plan is documented in `REMAINING_WORK_PLAN.md` Phase 8. Key categories:

| Category | Tests | What it validates |
|----------|-------|-------------------|
| Basic HAVING filtering | 4 | count/sum/avg/min/max thresholds |
| HAVING aggregate not in SELECT | 3 | Hidden `__having_col_*` columns don't leak to ResultSet |
| Compound HAVING (AND/OR) | 3 | Multiple conditions in HAVING |
| HAVING by alias | 2 | `HAVING cnt > 1` where `cnt` is a SELECT alias |
| ORDER BY + HAVING | 3 | Sorting after post-aggregation filter |
| HAVING + WHERE interaction | 2 | WHERE filters pre-aggregation, HAVING post |
| HAVING + JOIN | 3 | Multi-label queries with HAVING |
| Kitchen sink | 3 | Full clause combinations end-to-end |

**Prerequisites:** Docker for Testcontainers.

**Run with:**
```bash
./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp verify
```

---

## Priority 2: Code Formatting Compliance

**Effort: Small | Value: Low-Medium**

### 2a. Fix pre-existing checkstyle violations

72 `InnerTypeLast` violations exist across `SqlToCypher.java` and `AliasRegistry.java`. These are structural ordering issues (inner types defined before methods) that predate the GROUP BY work. Fixing them would mean moving record definitions and inner classes to the end of their enclosing classes.

**Risk:** These are mechanical refactors but touch many lines, producing a large diff. Consider fixing in a separate commit to keep GROUP BY changes reviewable.

### 2b. Run full formatting pipeline

```bash
./mvnw spring-javaformat:apply && ./mvnw license:format && ./mvnw sortpom:sort
```

Verify no formatting drift was introduced.

---

## Priority 3: Edge Case Hardening (Phase 9)

**Effort: Large | Value: Low-Medium**

These are documented in `REMAINING_WORK_PLAN.md` Phase 9. Only pursue if time permits after Phase 8 is stable.

### Would-be-nice additions:

1. **NULL handling in GROUP BY** — NULLs as a group key, NULL values in aggregate functions. Cypher handles NULLs differently than SQL in some cases (e.g., `count(null_prop)` returns 0, `sum(null_prop)` returns null).

2. **Empty result sets** — GROUP BY on zero matching rows should return no rows (not one row with null aggregates, which is the SQL behavior for SELECT without GROUP BY).

3. **Large cardinality GROUP BY** — All unique values, verifying performance doesn't degrade.

4. **GROUP BY on expressions** — `GROUP BY YEAR(released)`, `GROUP BY CASE WHEN ...`. These require expression-level translation that the current implementation does not handle. jOOQ parses these into complex QOM nodes that the `requiresWithForGroupBy()` comparison would need to match against.

5. **GROUP BY by ordinal** — `GROUP BY 1, 2`. jOOQ does not resolve ordinals, producing a field named `"1"`. Would require manual resolution from the SELECT list, which is fragile.

---

## Priority 4: Documentation

**Effort: Small | Value: Low**

### 4a. Architecture comment in SqlToCypher.java

The two-phase alias resolution (structural matching then name-based fallback) in `expression(Field<?>)` is the most subtle part of the implementation. A brief inline comment explaining the flow would help future maintainers:

```java
// Alias resolution: when aliasRegistry is active (WITH clause was generated),
// resolve fields through the registry before falling through to normal translation.
// Two modes: (1) structural matching for function-form references like HAVING count(*) > 5,
// (2) name-based fallback for alias references like HAVING cnt > 5 (jOOQ keeps these unresolved).
```

### 4b. Update GROUP_IMPLEMENTATION.md test count

Keep the test count current as new tests are added. Currently: 445.

---

## Not Recommended

These were considered and explicitly deferred:

1. **GROUP BY validation** — Rejecting queries where non-aggregated SELECT columns are missing from GROUP BY. Decision: silently translate, matching MySQL's permissive mode. Cypher handles this correctly. Documented in `requiresWithForGroupBy()` Javadoc.

2. **Configuration flags** — No backward-compatibility flags for the new WITH clause generation. The previous output was wrong; the new output is correct. No opt-out needed.

3. **HAVING without GROUP BY validation** — SQL technically allows `HAVING` without `GROUP BY`. The translator handles this correctly by treating the entire table as one group. No validation needed.
