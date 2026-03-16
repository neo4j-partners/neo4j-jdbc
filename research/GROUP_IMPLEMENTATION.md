# GROUP BY and HAVING Implementation Summary

The SQL-to-Cypher translator in `neo4j-jdbc-translator/impl` now supports GROUP BY semantics and HAVING clause translation through a six-phase implementation effort. The core challenge is that Cypher has no `GROUP BY` clause — grouping is implicit based on non-aggregated expressions in `RETURN`. For the common case (GROUP BY columns match SELECT columns), the translator continues to emit simple `MATCH ... RETURN` statements because Cypher's implicit grouping produces the correct result. When the GROUP BY contains columns absent from SELECT, or when a HAVING clause is present, the translator introduces a Cypher `WITH` clause to make grouping explicit and to support post-aggregation filtering.

**What was implemented:**

- **Phase 1:** 35 diagnostic tests documenting jOOQ's Query Object Model (QOM) behavior for GROUP BY, HAVING, ORDER BY, and DISTINCT — establishing ground truth before writing production code
- **Phase 2:** `FieldMatcher` — a structural field equivalence matcher that compares jOOQ Field objects by type and content rather than string representation
- **Phase 3:** `AliasRegistry` — a two-mode expression-to-alias registry supporting both structural matching (for aggregate expressions) and name-based lookup (for unresolved alias references)
- **Phase 4:** WITH clause generation when GROUP BY columns differ from SELECT columns, replacing previously incorrect output with semantically correct Cypher
- **Phase 5:** Unified alias resolution in `expression(Field<?>)` for ORDER BY after a WITH clause, plus error detection for de-scoped variables
- **Phase 6:** HAVING condition translation with hidden WITH columns for HAVING-only aggregates, `collectAggregates()` utility for condition tree walking, and end-to-end test coverage for all HAVING patterns

**Key files:**

| File | Purpose |
|------|---------|
| `SqlToCypher.java` | Core translator — `statement(Select<?>)`, `buildWithClause()`, `havingCondition()`, registry interception in `expression(Field<?>)` |
| `FieldMatcher.java` | Structural field equivalence, `isAggregate()` type guard, `collectAggregates()` condition walker |
| `AliasRegistry.java` | Two-mode alias registry (structural + name-based lookup) |
| `JooqQomDiagnosticTests.java` | 35 diagnostic tests documenting jOOQ QOM behavior |
| `FieldMatcherTests.java` | 23 matcher tests + collectAggregates tests |
| `AliasRegistryTests.java` | 15 registry tests covering structural and name-based lookup |
| `SqlToCypherTests.java` | End-to-end translator tests for aggregates, WITH generation, and HAVING |

**Test count:** 445 tests, 0 failures, 0 errors (425 after Phase 6, +20 in Phase 7).

---

## Example Translations

This section shows representative SQL queries and the Cypher the translator now produces. These are drawn from the actual test suite and cover the major categories the implementation handles.

### Simple GROUP BY (implicit grouping — no WITH needed)

When every GROUP BY column also appears in SELECT, Cypher's implicit grouping produces the correct result and the translator emits a straightforward `MATCH ... RETURN`:

```sql
SELECT name, count(*) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) RETURN p.name AS name, count(*)
```

```sql
SELECT name, max(age) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) RETURN p.name AS name, max(p.age)
```

### GROUP BY column not in SELECT (WITH clause needed)

When the GROUP BY lists a column that is absent from SELECT, the translator must make the grouping explicit via a WITH clause. Without it, Cypher would not know to group by that column:

```sql
SELECT sum(age) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0
```

Previously, this query incorrectly produced `RETURN sum(p.age)` — a single total sum across all rows. The corrected output groups by name and returns one sum per name.

### JOIN with GROUP BY

Grouping works across relationships. When the GROUP BY column is in SELECT, no WITH is needed:

```sql
SELECT c.name, count(*)
FROM Customers c JOIN Orders o ON c.id = o.customer_id
GROUP BY c.name
```
```cypher
MATCH (c:Customers)<-[customer_id:CUSTOMER_ID]-(o:Orders) RETURN c.name, count(*)
```

When the GROUP BY column is not in SELECT, a WITH clause is generated:

```sql
SELECT count(*)
FROM Customers c JOIN Orders o ON c.id = o.customer_id
GROUP BY c.name
```
```cypher
MATCH (c:Customers)<-[customer_id:CUSTOMER_ID]-(o:Orders) WITH count(*) AS __with_col_0, c.name AS __group_col_1 RETURN __with_col_0
```

### HAVING — filtering aggregated results

HAVING conditions translate to a WHERE clause after the WITH. The aggregate is resolved to its WITH alias rather than re-invoked:

```sql
SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS cnt WHERE cnt > 5 RETURN name, cnt
```

### HAVING with aggregate not in SELECT (hidden column)

When the HAVING references an aggregate that is not in the SELECT, it is injected as a hidden WITH column used only for filtering and excluded from the final RETURN:

```sql
SELECT name FROM People p GROUP BY name HAVING count(*) > 5
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name
```

### Compound HAVING with multiple aggregates

Multiple HAVING-only aggregates each get their own hidden column:

```sql
SELECT name FROM People p GROUP BY name HAVING count(*) > 5 AND max(age) > 50
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0, max(p.age) AS __having_col_1
WHERE (__having_col_0 > 5 AND __having_col_1 > 50) RETURN name
```

### Mixed: some aggregates in SELECT, some only in HAVING

The translator deduplicates — aggregates already in SELECT are not injected again:

```sql
SELECT name, sum(age) FROM People p GROUP BY name HAVING sum(age) > 100 AND count(*) > 2
```
```cypher
MATCH (p:People) WITH p.name AS name, sum(p.age) AS __with_col_0, count(*) AS __having_col_1
WHERE (__with_col_0 > 100 AND __having_col_1 > 2) RETURN name, __with_col_0
```

### HAVING without GROUP BY

SQL allows HAVING without GROUP BY, treating the entire table as one implicit group:

```sql
SELECT count(*) FROM People p HAVING count(*) > 5
```
```cypher
MATCH (p:People) WITH count(*) AS __with_col_0 WHERE __with_col_0 > 5 RETURN __with_col_0
```

### ORDER BY with WITH clause

When a WITH clause is present, ORDER BY references the WITH alias rather than the original expression:

```sql
SELECT sum(age) FROM People p GROUP BY name ORDER BY sum(age)
```
```cypher
MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0 ORDER BY __with_col_0
```

### HAVING + ORDER BY together

```sql
SELECT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name ORDER BY name
```

### HAVING with COUNT(DISTINCT)

The DISTINCT flag is preserved through the entire pipeline:

```sql
SELECT name FROM People p GROUP BY name HAVING count(DISTINCT age) > 3
```
```cypher
MATCH (p:People) WITH p.name AS name, count(DISTINCT p.age) AS __having_col_0 WHERE __having_col_0 > 3 RETURN name
```

### HAVING on a non-aggregate GROUP BY column

```sql
SELECT count(*) FROM People p GROUP BY name HAVING name = 'Alice'
```
```cypher
MATCH (p:People) WITH count(*) AS __with_col_0, p.name AS __group_col_1 WHERE __group_col_1 = 'Alice' RETURN __with_col_0
```

---

## Phase 1: jOOQ QOM Diagnostic Research

**Goal:** Understand exactly how jOOQ represents GROUP BY, HAVING, ORDER BY, and DISTINCT in its internal Query Object Model before writing any production code.

**Test file:** `JooqQomDiagnosticTests.java` (35 tests)

### Why this was needed

The entire implementation depends on reading jOOQ's internal AST (the Query Object Model) and translating its nodes into Cypher-DSL calls. Before writing any production code, we needed to know exactly what types jOOQ produces for each SQL construct. Early assumptions — such as `count(*)` producing an `Asterisk` instance, or `HAVING cnt > 5` resolving the alias to the underlying aggregate — turned out to be wrong. Building the structural matcher or alias registry on incorrect assumptions would have required expensive rework. By writing 35 diagnostic tests first, every design decision in Phases 2-6 was grounded in verified behavior rather than documentation or guesswork. These tests also serve as regression guards: if a future jOOQ upgrade changes how it represents these constructs, the diagnostic tests will catch it immediately.

This phase produced no production code changes. Instead, it established critical findings that shaped all subsequent design decisions:

1. **jOOQ represents `count(*)` asterisk as `SQLField`, not `Asterisk`** — the structural matcher must detect the asterisk via `"*".equals(field.toString())`, not `instanceof Asterisk`.

2. **jOOQ does NOT resolve HAVING alias references to aggregates** — `HAVING cnt > 5` (where `cnt` aliases `count(*)`) keeps `cnt` as an unresolved plain field reference, not a `QOM.Count` node. This drove the requirement for name-based lookup in the alias registry.

3. **jOOQ does NOT resolve ORDER BY alias references to aggregates** — same behavior as HAVING. `ORDER BY cnt` produces a plain field reference.

4. **jOOQ preserves aggregate structure in ORDER BY function form** — `ORDER BY count(*)` produces a `QOM.Count` node, so the structural matcher handles it directly.

5. **jOOQ does NOT resolve GROUP BY ordinals** — `GROUP BY 1` produces a field with name `"1"`, not the resolved first SELECT column. Ordinal GROUP BY support is out of scope.

6. **HAVING arithmetic preserves aggregate nodes** — `HAVING max(salary) > 2 * avg(salary)` produces individually matchable aggregate sub-expressions within the arithmetic tree.

7. **DISTINCT is independent of GROUP BY and HAVING** — the `$distinct()` flag does not affect other clause accessors.

---

## Phase 2: Structural Field Equivalence Matcher

**Goal:** Build a method that determines whether two jOOQ Field objects represent the same expression, handling alias unwrapping, aggregate function comparison, and column reference matching.

**File:** `FieldMatcher.java` (121 lines)
**Tests:** `FieldMatcherTests.java` (23 tests)

### Why this was needed

The translator needs to answer a simple question in multiple contexts: "are these two jOOQ Field objects referring to the same thing?" For example, is the `count(*)` in a HAVING clause the same as the `count(*) AS cnt` in the SELECT? Is the `name` in GROUP BY the same as the `p.name` in SELECT? String comparison (`toString()`) is fragile — it depends on jOOQ's formatting being stable across versions and contexts. Structural matching compares the actual QOM node types and their children recursively, which is deterministic and version-independent. This matcher is the foundation that the alias registry (Phase 3), GROUP BY field comparison (Phase 4), HAVING resolution (Phase 6), and ORDER BY resolution (Phase 5) all depend on. By building and testing it in isolation first, we validated the approach before it could contaminate downstream phases.

The matcher handles four specific cases:

1. **Alias unwrapping** — strips `QOM.FieldAlias` wrappers recursively before comparison
2. **Aggregate function matching** — compares by QOM type and inner field arguments (with `$distinct()` flag check on Count)
3. **Simple column references** — case-insensitive name comparison with table qualifier when both are qualified
4. **Fallback** — returns false for unrecognized types

### Sample Test Scenarios

```
fieldsMatch(count(*) AS cnt, count(*))  → true   (alias stripped, same aggregate)
fieldsMatch(count(name), count(age))     → false  (different inner field)
fieldsMatch(count(name), count(DISTINCT name)) → false  (distinct flag differs)
fieldsMatch(a.name, b.name)              → false  (different table qualifiers)
fieldsMatch(name [unqualified], p.name)  → true   (pragmatic single-table match)
```

---

## Phase 3: Alias Registry

**Goal:** Build a registry mapping jOOQ expressions to their WITH clause aliases, supporting both structural matching (for aggregates) and name-based fallback (for unresolved alias references).

**File:** `AliasRegistry.java` (86 lines)
**Tests:** `AliasRegistryTests.java` (15 tests)

### Why this was needed

When the translator generates a WITH clause, every expression gets an alias (`count(*) AS cnt` or `sum(p.age) AS __with_col_0`). Later, when translating HAVING conditions or ORDER BY clauses, the translator must emit references to those aliases — not re-translate the original expressions. Re-emitting `count(*)` after a WITH would attempt to re-aggregate already-aggregated rows, producing wrong results. The registry maps each original expression to its WITH alias so downstream translation can look up the correct reference. Phase 1 finding #2 revealed that jOOQ keeps alias references (like `HAVING cnt > 5`) as unresolved plain field names rather than resolving them to the underlying aggregate. This meant the registry needed two lookup modes: structural matching for function-form references (`HAVING count(*) > 5`) and name-based matching for alias-form references (`HAVING cnt > 5`).

Two-mode resolution:
1. **Structural matching** — uses `FieldMatcher.fieldsMatch()` to find a matching registered aggregate (handles `HAVING count(*) > 5`)
2. **Name-based fallback** — checks if the field's name matches any registered alias (handles `HAVING cnt > 5` where jOOQ keeps `cnt` unresolved)

---

## Phase 4: WITH Clause Generation for GROUP BY

**Goal:** Produce semantically correct Cypher when GROUP BY columns differ from SELECT columns by generating an intermediate WITH clause.

### Why this was needed

The translator was producing semantically wrong Cypher for an entire class of queries. When a GROUP BY column does not appear in the SELECT list, Cypher's implicit grouping has no way to know about it — the grouping key is simply absent from the RETURN. For example, `SELECT sum(age) FROM People GROUP BY name` should return one sum per name (multiple rows), but the translator was emitting `RETURN sum(p.age)` which collapses everything into a single total (one row). The only way to make the grouping explicit in Cypher is to introduce a WITH clause that includes the grouping column alongside the aggregates. This phase wired the FieldMatcher and AliasRegistry from Phases 2-3 into the translator's main SELECT path, replacing the fragile bare-name field comparison with structural matching, and generating WITH clauses when needed.

**Key change:** Previously, `SELECT sum(age) FROM People GROUP BY name` incorrectly produced `RETURN sum(p.age)` (a single total sum). After Phase 4, it correctly produces a WITH clause that groups by name and returns one sum per name.

### SQL-to-Cypher Examples: Common Case (No WITH Needed)

When all GROUP BY columns appear in SELECT, Cypher's implicit grouping is sufficient:

```sql
SELECT name, count(*) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) RETURN p.name AS name, count(*)
```

```sql
SELECT name, max(age) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) RETURN p.name AS name, max(p.age)
```

```sql
SELECT name, count(*), sum(age), avg(age) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) RETURN p.name AS name, count(*), sum(p.age), avg(p.age)
```

```sql
-- Join query: GROUP BY column in SELECT
SELECT c.name, count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name
```
```cypher
MATCH (c:Customers)<-[customer_id:CUSTOMER_ID]-(o:Orders) RETURN c.name, count(*)
```

### SQL-to-Cypher Examples: WITH Clause Required

When GROUP BY contains columns not in SELECT, the translator generates a WITH clause to make grouping explicit:

```sql
SELECT sum(age) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0
```

The `__group_col_1` alias makes `name` visible for Cypher's implicit grouping in the WITH scope but excludes it from the final RETURN (matching the SQL SELECT which only requests `sum(age)`).

```sql
SELECT count(*), sum(age) FROM People p GROUP BY name
```
```cypher
MATCH (p:People) WITH count(*) AS __with_col_0, sum(p.age) AS __with_col_1, p.name AS __group_col_2 RETURN __with_col_0, __with_col_1
```

```sql
SELECT name, sum(age) FROM People p GROUP BY name, department
```
```cypher
MATCH (p:People) WITH p.name AS name, sum(p.age) AS __with_col_0, p.department AS __group_col_1 RETURN name, __with_col_0
```

```sql
-- Join query: GROUP BY column NOT in SELECT
SELECT count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name
```
```cypher
MATCH (c:Customers)<-[customer_id:CUSTOMER_ID]-(o:Orders) WITH count(*) AS __with_col_0, c.name AS __group_col_1 RETURN __with_col_0
```

### Alias Naming Conventions

| Prefix | Source | Appears in RETURN? |
|--------|--------|--------------------|
| `__with_col_N` | SELECT expressions without a user alias | Yes |
| `__group_col_N` | GROUP BY columns not in SELECT | No (hidden) |
| `__having_col_N` | HAVING-only aggregates not in SELECT | No (hidden) |
| User alias (e.g., `cnt`) | SELECT expressions with SQL `AS` | Yes |

---

## Phase 5: ORDER BY Alias Resolution with WITH

**Goal:** Fix ORDER BY to reference WITH aliases when a WITH clause is present, and detect de-scoped variable errors.

### Why this was needed

In Cypher, a WITH clause completely replaces the current scope — variables from the preceding MATCH are no longer accessible unless they are explicitly projected in the WITH. Before Phase 5, the ORDER BY translation resolved fields against the original table scope (the MATCH variables), which worked fine when there was no WITH. But after Phase 4 introduced WITH clauses, ORDER BY would emit references to variables that were no longer in scope (e.g., `ORDER BY p.name` when `p` was replaced by `__group_col_1` in the WITH). This produced Cypher that Neo4j would reject at runtime with error 42N44 (inaccessible variable). Phase 5 added a unified interception point in `expression(Field<?>)` that checks the alias registry before falling through to normal translation. This same interception serves both ORDER BY and HAVING (Phase 6), avoiding duplicated resolution logic. It also added explicit error detection so that ORDER BY on a de-scoped variable throws a clear error at translation time rather than producing invalid Cypher that fails at runtime.

After a WITH clause, the original MATCH scope is gone in Cypher — only WITH aliases remain accessible. Phase 5 added:

1. **Unified registry interception** at the top of `expression(Field<?>)` that resolves fields through the alias registry when active
2. **Error detection** in `expression(SortField<?>)` that throws when ORDER BY references a field not in the WITH scope
3. **Try-finally cleanup** ensuring the registry is always cleared after `statement(Select<?>)` completes

### SQL-to-Cypher Examples: ORDER BY with WITH

```sql
SELECT sum(age) FROM People p GROUP BY name ORDER BY sum(age)
```
```cypher
MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0 ORDER BY __with_col_0
```

The ORDER BY references `__with_col_0` (the WITH alias for `sum(p.age)`) rather than re-emitting `sum(p.age)`, which would be semantically incorrect after the WITH clause.

### SQL-to-Cypher Examples: ORDER BY without WITH (unchanged)

Queries without GROUP BY differences or HAVING continue to use the original translation path:

```sql
SELECT name, count(*) FROM People p GROUP BY name ORDER BY name
```
```cypher
MATCH (p:People) RETURN p.name AS name, count(*) ORDER BY p.name
```

```sql
SELECT name, max(age) FROM People p GROUP BY name ORDER BY name DESC
```
```cypher
MATCH (p:People) RETURN p.name AS name, max(p.age) ORDER BY p.name DESC
```

---

## Phase 6: HAVING Condition Translation

**Goal:** Support HAVING clauses by translating them to Cypher `WHERE` clauses after the `WITH`, with proper alias resolution and hidden column injection for HAVING-only aggregates.

### Why this was needed

HAVING clauses were the primary gap identified in the original proposal. Before this phase, the translator silently dropped every HAVING condition — post-aggregation filters never reached the Cypher output, so every group was returned regardless of whether it satisfied the HAVING predicate. This is not a subtle edge case; `HAVING count(*) > 5` is one of the most common SQL patterns for filtering aggregated results. Cypher has no HAVING keyword — the equivalent is a WHERE clause placed after a WITH that performs the aggregation. The translation requires three capabilities that didn't exist before: (1) detecting which aggregates in the HAVING are not already in the SELECT and injecting them as hidden WITH columns for filtering, (2) walking the HAVING condition tree to find all aggregate sub-expressions (which may be nested inside arithmetic like `max(salary) > 2 * avg(salary)`), and (3) resolving each aggregate reference to its WITH alias so the WHERE references aliases instead of re-invoking aggregate functions. All of this was made possible by the infrastructure from Phases 2-5 — the structural matcher, the two-mode registry, and the unified interception in `expression(Field<?>)`.

This is the most complex phase, building on all previous infrastructure. Key components:

1. **`collectAggregates(Condition)`** — recursively walks the HAVING condition tree and extracts all aggregate sub-expressions. Handles all jOOQ condition types: `And`, `Or`, `Xor`, `Not`, comparison operators, `Between`, `InList`, `NotInList`, `IsNull`, `IsNotNull`, `Like`, `NotLike`, `LikeIgnoreCase`, `NotLikeIgnoreCase`, `IsDistinctFrom`, `IsNotDistinctFrom`.

2. **Hidden column injection** — when a HAVING clause references an aggregate not in SELECT, that aggregate is added to the WITH clause with a synthetic `__having_col_N` alias. It participates in the WHERE filter but is excluded from the final RETURN.

3. **Alias resolution** — the unified interception in `expression(Field<?>)` resolves HAVING aggregates to their WITH aliases, whether referenced by function form (`HAVING count(*) > 5`) or by SQL alias (`HAVING cnt > 5`).

### SQL-to-Cypher Examples: Simple HAVING

```sql
SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS cnt WHERE cnt > 5 RETURN name, cnt
```

The SQL alias `cnt` is preserved in the WITH clause. The HAVING condition resolves `cnt` via name-based registry lookup.

### SQL-to-Cypher Examples: HAVING with Aggregate Not in SELECT

```sql
SELECT name FROM People p GROUP BY name HAVING count(*) > 5
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name
```

`count(*)` is not in the SELECT, so it becomes a hidden WITH column (`__having_col_0`) used only for the WHERE filter. The final RETURN excludes it.

### SQL-to-Cypher Examples: Compound HAVING

```sql
SELECT name FROM People p GROUP BY name HAVING count(*) > 5 AND max(age) > 50
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0, max(p.age) AS __having_col_1 WHERE (__having_col_0 > 5 AND __having_col_1 > 50) RETURN name
```

Both aggregates are HAVING-only, so both get hidden column aliases. The AND condition references both aliases.

### SQL-to-Cypher Examples: Mixed Aggregates (Some in SELECT, Some in HAVING)

```sql
SELECT name, sum(age) FROM People p GROUP BY name HAVING sum(age) > 100 AND count(*) > 2
```
```cypher
MATCH (p:People) WITH p.name AS name, sum(p.age) AS __with_col_0, count(*) AS __having_col_1 WHERE (__with_col_0 > 100 AND __having_col_1 > 2) RETURN name, __with_col_0
```

`sum(age)` is in SELECT (gets `__with_col_0`, appears in RETURN). `count(*)` is HAVING-only (gets `__having_col_1`, excluded from RETURN). The registry deduplicates `sum(age)` — it is not injected twice.

### SQL-to-Cypher Examples: HAVING with Non-Aggregate Condition

```sql
SELECT count(*) FROM People p GROUP BY name HAVING name = 'Alice'
```
```cypher
MATCH (p:People) WITH count(*) AS __with_col_0, p.name AS __group_col_1 WHERE __group_col_1 = 'Alice' RETURN __with_col_0
```

The HAVING references a GROUP BY-only column (`name`), which was registered in the WITH as `__group_col_1`. The unified interception resolves it.

### SQL-to-Cypher Examples: HAVING without GROUP BY

```sql
SELECT count(*) FROM People p HAVING count(*) > 5
```
```cypher
MATCH (p:People) WITH count(*) AS __with_col_0 WHERE __with_col_0 > 5 RETURN __with_col_0
```

SQL allows HAVING without GROUP BY, treating the entire table as one implicit group. The translator forces the WITH path and produces correct Cypher.

### SQL-to-Cypher Examples: HAVING with ORDER BY

```sql
SELECT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name ORDER BY name
```

Both HAVING and ORDER BY work together — HAVING uses the hidden column for filtering, ORDER BY references the RETURN alias.

### SQL-to-Cypher Examples: HAVING with COUNT(DISTINCT)

```sql
SELECT name FROM People p GROUP BY name HAVING count(DISTINCT age) > 3
```
```cypher
MATCH (p:People) WITH p.name AS name, count(DISTINCT p.age) AS __having_col_0 WHERE __having_col_0 > 3 RETURN name
```

The `DISTINCT` flag on `count()` is preserved through the entire pipeline: `isAggregate()` detection, `collectAggregates()` extraction, hidden column injection, and Cypher output.

### SQL-to-Cypher Examples: HAVING on Aggregate Already in SELECT

```sql
SELECT sum(age) FROM People p GROUP BY name HAVING sum(age) > 100
```
```cypher
MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 WHERE __with_col_0 > 100 RETURN __with_col_0
```

When the HAVING aggregate is already in SELECT, no hidden column is needed — the HAVING condition references the existing WITH alias.

---

## Phase 7: DISTINCT, LIMIT, OFFSET Hardening & Full Combination Tests

**Goal:** Verify that DISTINCT, LIMIT, and OFFSET interact correctly with WITH clauses from GROUP BY/HAVING, add full combination tests, and harden the implementation.

### Why this was needed

Phases 1-6 built the core GROUP BY/HAVING machinery but didn't explicitly verify how DISTINCT, LIMIT, and OFFSET behave when a WITH clause is present. These clauses must attach to the final `RETURN`, never to the `WITH`. A subtle bug could produce `WITH DISTINCT` (deduplicating before aggregation) instead of `RETURN DISTINCT` (deduplicating after), or apply `LIMIT` to the WITH instead of the RETURN. Phase 7 was a verification and hardening pass — no production logic changes were needed, confirming that the Phase 4-6 implementation was already correct.

### What was implemented

**Production code changes (documentation only):**
- Added `@param` and `@return` Javadoc tags to `requiresWithForGroupBy()`, `buildWithClause()`, and `havingCondition()` methods in `SqlToCypher.java`
- Removed unused `Asterisk` import from `JooqQomDiagnosticTests.java`

**Test additions (13 new test cases):**

1. **DISTINCT + WITH path tests (3 tests)** — `distinctWithGroupByAndHaving` parameterized test verifying `RETURN DISTINCT` placement:
   - `SELECT DISTINCT name, count(*) ... GROUP BY name` → `RETURN DISTINCT` (no WITH needed)
   - `SELECT DISTINCT count(*) ... GROUP BY name` → WITH path, `RETURN DISTINCT`
   - `SELECT DISTINCT name ... GROUP BY name HAVING count(*) > 5` → HAVING path, `RETURN DISTINCT`

2. **LIMIT/OFFSET + WITH path tests (3 tests)** — `limitAndOffsetWithWithClause` parameterized test:
   - `GROUP BY ... LIMIT` with WITH → LIMIT after RETURN
   - `GROUP BY ... HAVING ... LIMIT` → LIMIT after RETURN
   - `GROUP BY ... HAVING ... ORDER BY ... LIMIT ... OFFSET` → SKIP before LIMIT, both after RETURN

3. **Full combination tests (3 tests):**
   - `fullGroupByCombination` — all clauses: GROUP BY + HAVING + DISTINCT + ORDER BY + LIMIT + OFFSET
   - `fullGroupByCombinationWithGroupByMismatch` — GROUP BY-only column with HAVING, DISTINCT, ORDER BY, LIMIT
   - `fullGroupByCombinationWithWhereAndMultipleAggregates` — WHERE + multiple HAVING aggregates + DISTINCT + ORDER BY + LIMIT + OFFSET

4. **WHERE + GROUP BY path tests (3 tests)** — `whereWithGroupByAndOrderByPaths` parameterized test:
   - WHERE with GROUP BY (simple path, no WITH)
   - WHERE with GROUP BY (WITH path)
   - ORDER BY aggregate alias without GROUP BY

5. **Registry cleanup test (1 test)** — `registryDoesNotLeakBetweenTranslations`: translates a HAVING query then a simple query, verifying no WITH or alias artifacts leak to the second translation.

### SQL-to-Cypher Examples: Full Combination

```sql
SELECT DISTINCT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name LIMIT 10 OFFSET 5
```
```cypher
MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN DISTINCT name ORDER BY name SKIP 5 LIMIT 10
```

Every clause interaction verified: WITH from HAVING, hidden `__having_col_0` excluded from RETURN, `RETURN DISTINCT` (not `WITH DISTINCT`), ORDER BY resolves alias, SKIP before LIMIT.

```sql
SELECT DISTINCT department, count(*) AS cnt, max(age) AS max_age FROM People p WHERE age > 18 GROUP BY department HAVING count(*) > 1 AND max(age) > 25 ORDER BY cnt DESC LIMIT 10 OFFSET 2
```
```cypher
MATCH (p:People) WHERE p.age > 18 WITH p.department AS department, count(*) AS cnt, max(p.age) AS max_age WHERE (cnt > 1 AND max_age > 25) RETURN DISTINCT department, cnt, max_age ORDER BY cnt DESC SKIP 2 LIMIT 10
```

WHERE filters before aggregation, HAVING filters after, DISTINCT on final RETURN, SKIP + LIMIT after ORDER BY.

---

## Architecture: How the Pieces Fit Together

### Decision Flow in `statement(Select<?>)`

```
Parse SELECT statement
    ↓
Extract tables, WHERE, ORDER BY, LIMIT, DISTINCT
    ↓
Does query have HAVING or GROUP BY fields not in SELECT?
    ├── NO → Simple MATCH ... RETURN (original path, unchanged)
    └── YES → buildWithClause()
                ├── Phase 1: Translate SELECT fields → register in AliasRegistry
                ├── Phase 2: Add GROUP BY-only fields → register (hidden)
                ├── Phase 3: Inject HAVING-only aggregates → register (hidden)
                ├── Activate aliasRegistry on instance
                ├── Build WITH clause
                ├── Translate HAVING → WHERE (aliases resolved via registry)
                └── Return supplier for final RETURN expressions
    ↓
Apply DISTINCT, ORDER BY, LIMIT to final projection
    ↓
finally: clear aliasRegistry
```

### Unified Alias Resolution in `expression(Field<?>)`

```
expression(Field<?> f) called (from ORDER BY, HAVING condition, or other context)
    ↓
Is aliasRegistry active?
    ├── NO → Normal translation (original path)
    └── YES → Is f a TableField, aggregate, or FieldAlias?
                ├── NO → Normal translation (Param, arithmetic, etc. fall through)
                └── YES → aliasRegistry.resolve(f)
                            ├── Structural match found → return Cypher.name(alias)
                            ├── Name-based match found → return Cypher.name(alias)
                            └── No match → Fall through to normal translation
```

### Three Categories of WITH Expressions

```
WITH clause contains:
  ├── SELECT expressions (aliased) ──────────→ Included in RETURN
  ├── GROUP BY-only columns (__group_col_N) ─→ Excluded from RETURN
  └── HAVING-only aggregates (__having_col_N) → Excluded from RETURN

Only the first category appears in the final RETURN statement.
The other two participate in grouping/filtering but are invisible in results.
```
