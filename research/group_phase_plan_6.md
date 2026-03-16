# Phase 6: HAVING Condition Translation — Implementation Plan

**Goal**: Support HAVING clauses that reference aggregates not in SELECT (hidden WITH columns), simplify the `havingCondition()` method, and complete `collectAggregates()` coverage for all condition types.

**Baseline**: 407 tests, 0 failures, 0 errors.

**Primary file**: `neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/SqlToCypher.java`
**Secondary files**: `FieldMatcher.java` (extend `collectAggregates()`), `FieldMatcherTests.java`, `SqlToCypherTests.java`

---

## Pre-Implementation Review

### What Already Works (from Phases 4–5)

1. **HAVING with aggregates that ARE in SELECT**: The test at `SqlToCypherTests.java:985` passes today:
   ```
   SELECT sum(age) FROM People p GROUP BY name HAVING sum(age) > 100
   → MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 WHERE __with_col_0 > 100 RETURN __with_col_0
   ```
   This works because `sum(age)` is already registered from the SELECT loop, and the unified interception in `expression(Field<?>)` resolves it to `__with_col_0` during `condition()` translation.

2. **HAVING forces WITH path**: Line 488 already does `havingCondition != null || requiresWithForGroupBy(selectStatement)`. A query with HAVING but without GROUP BY will still take the WITH path.

3. **Registry lifecycle**: Set inside `buildWithClause()` at line 595 (before HAVING translation). Cleared in the `finally` block at line 512. Exception-safe.

4. **Unified interception**: `expression(Field<?> f, boolean)` lines 1621–1628 resolve `TableField`, aggregates, and `FieldAlias` types through the registry when it's non-null.

5. **`collectAggregates()`**: Implemented in `FieldMatcher.java` lines 134–210 with 9 passing tests.

6. **`isAggregate()`**: Already package-private (line 119) since Phase 5.

### What Does NOT Work Yet (the Phase 6 deliverables)

1. **HAVING-only aggregates produce invalid Cypher**. Example:
   ```sql
   SELECT name FROM People GROUP BY name HAVING count(*) > 5
   ```
   Currently produces:
   ```cypher
   MATCH (p:People) WITH p.name AS name WHERE count(*) > 5 RETURN name
   ```
   This is **invalid Cypher** — `count(*)` is not projected in the WITH clause, so it cannot appear in the WHERE after WITH. Neo4j will reject this with error 42N44.

   Correct output should be:
   ```cypher
   MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name
   ```

2. **`havingCondition()` has a dead parameter**: The `withExpressions` parameter at line 617 is never used.

3. **`collectAggregates()` is incomplete**: The spec (Phase 6 item 6.2 in `group_phase_4_plan.md`) lists `InList`, `IsNull`, and `IsNotNull` as condition types to handle. The implementation does not handle them. While rare in HAVING clauses, production-quality code should handle all condition types that the translator's `condition()` method supports.

---

## Outstanding Questions

### Q1: Should HAVING-only aggregate injection happen before or after `this.aliasRegistry = registry`?

**Context**: In `buildWithClause()`, the current flow is:
```
Lines 561–578: SELECT loop → populates registry + withExpressions
Lines 581–590: GROUP BY loop → populates registry + withExpressions
Line 595:      this.aliasRegistry = registry
Line 597:      reading.with(withExpressions)
Line 601:      havingCondition translation
```

The HAVING-only aggregate injection must happen before line 597 (so the hidden columns are included in the WITH). The question is whether it should go before or after line 595 (the registry assignment).

**Analysis**:
- If **before** line 595 (`this.aliasRegistry` is null during injection): `expression(aggField)` falls through the registry interception (null check fails) and translates the aggregate normally (e.g., `count(*)`). The aggregate is then registered in the local `registry`. This is correct.
- If **after** line 595 (`this.aliasRegistry` is set during injection): `expression(aggField)` hits the registry interception. For a HAVING-only aggregate, `resolve()` returns null (it wasn't registered yet), so it falls through to normal translation. Same result.

Both positions produce identical output. However, injecting **before** line 595 is cleaner:
- It groups all registry population together (SELECT loop → GROUP BY loop → HAVING injection → assign registry)
- It avoids any theoretical edge case where the interception interferes with fresh translation
- It communicates intent: "populate the registry completely, then activate it"

**Decision: Inject between lines 590 and 595, before the registry assignment.**

### Q2: Should `havingCondition()` be inlined into `buildWithClause()` or kept as a named method?

**Context**: After simplification, `havingCondition()` becomes:
```java
private Condition havingCondition(org.jooq.Condition c) {
    return condition(c);
}
```

**Analysis**: This is a trivially thin wrapper. However:
- The method name documents intent: "this is HAVING translation, not WHERE translation"
- The Javadoc on the method explains the mechanism (registry-based alias resolution)
- Removing it saves one method but makes the call site less self-documenting
- Keeping it costs nothing and provides a named extension point if HAVING-specific logic is ever needed

**Decision: Keep the named method. Remove only the unused `withExpressions` parameter.**

### Q3: What condition types should `collectAggregates()` support?

**Context**: The `condition()` method in `SqlToCypher.java` (lines 2010–2128) handles these condition types:
- Logical: `And`, `Or`, `Xor`, `Not`
- Comparison: `Eq`, `Gt`, `Ge`, `Lt`, `Le`, `Ne`
- Range: `Between`
- Null checks: `IsNull`, `IsNotNull`
- Membership: `InList`
- Row comparisons: `RowEq`, `RowNe`, `RowGt`, `RowGe`, `RowLt`, `RowLe`, `RowIsNull`, `RowIsNotNull`
- Pattern: `Like`
- Boolean: `FieldCondition`

The current `collectAggregates()` handles: `And`, `Or`, `Xor`, `Not`, `Gt`, `Ge`, `Lt`, `Le`, `Eq`, `Ne`, `Between`.

Missing: `IsNull`, `IsNotNull`, `InList`. The row-comparison types, `Like`, and `FieldCondition` are extremely unlikely in HAVING but could theoretically appear.

**Analysis** (verified against jOOQ 3.19.30 source at `/Users/ryanknight/projects/neo4j-labs/jOOQ`):
- `HAVING count(*) IS NULL` — rare but valid SQL. Easy to support.
- `HAVING count(*) IN (1, 2, 3)` — uncommon but valid. The `$list()` method returns field elements that could contain aggregates.
- Row comparisons in HAVING — practically impossible. Skip.
- `Like` in HAVING — e.g., `HAVING max(name) LIKE 'A%'` — possible with string aggregates. `$arg1()` is a Field, `$arg2()` is a Field<String>. Also has `$escape()` (Character, not Field — no aggregate concern).
- `NotLike` — separate type from `Like` with identical structure. `HAVING max(name) NOT LIKE 'A%'` is valid.
- `LikeIgnoreCase` — jOOQ's representation of SQL `ILIKE`. **There is no `QOM.ILike`** — jOOQ uses `LikeIgnoreCase` instead. Same `$arg1()`/`$arg2()` structure. jOOQ's parser produces this from `ILIKE` syntax.
- `NotLikeIgnoreCase` — `NOT ILIKE` variant. Same structure.
- `NotInList` — `HAVING count(*) NOT IN (1, 2, 3)`. Same structure as `InList`: `$field()` + `$list()`.
- `IsDistinctFrom` / `IsNotDistinctFrom` — NULL-safe comparison operators. Take two `Field<T>` args via `$arg1()` / `$arg2()`. Could appear in HAVING.
- `FieldCondition` — boolean params. No aggregate content. Skip.

**Decision: Add `IsNull`, `IsNotNull`, `InList`, `NotInList`, `Like`, `NotLike`, `LikeIgnoreCase`, `NotLikeIgnoreCase`, `IsDistinctFrom`, and `IsNotDistinctFrom` to `collectAggregates()`. Skip row comparisons and `FieldCondition`. This covers all field-bearing condition types that could appear in HAVING clauses.**

### Q3a: Does `COUNT(DISTINCT ...)` need special handling?

**Context**: `HAVING count(DISTINCT name) > 5` — does `isAggregate()` recognize `count(DISTINCT name)`?

**Analysis**: jOOQ uses a single `QOM.Count` class with a `$distinct()` boolean flag. There is no separate `QOM.CountDistinct` type. Therefore `isAggregate()` (which checks `instanceof QOM.Count`) already handles `COUNT(DISTINCT x)` correctly. Structural matching in `AliasRegistry` also works: existing tests at `FieldMatcherTests.java:196` confirm `count(name)` vs `count(DISTINCT name)` returns `false` (correctly distinguishes them), and line 207 confirms `count(DISTINCT name)` vs `count(DISTINCT name)` returns `true` (correctly matches them).

**Decision: No production code change needed. Add a `count(DISTINCT ...)` test case to 6.9 to prove the end-to-end pipeline handles distinct aggregates in HAVING.**

### Q4: How should duplicate HAVING-only aggregates be handled?

**Context**: `HAVING count(*) > 5 AND count(*) < 100` — `collectAggregates()` returns `[count(*), count(*)]`. When injecting into the WITH clause, should we add one hidden column or two?

**Analysis**: The plan says "check `registry.resolve(agg)` — if non-null, it's already registered (from SELECT), skip." This handles SELECT-duplicate aggregates. For HAVING-internal duplicates, after registering the first `count(*)` as `__having_col_0`, the second `count(*)` will resolve as `__having_col_0` (structural match). So the deduplication happens naturally through the registry.

**Decision: No special handling needed. The registry's structural matching deduplicates automatically. The injection loop checks `registry.resolve(agg)` before each add, which catches both SELECT duplicates and HAVING-internal duplicates after the first registration.**

### Q5: Should the `expression(Field<?>)` interception translate HAVING-only aggregate inner fields through the registry?

**Context**: When translating `count(p.age)` for the hidden WITH column, `expression()` processes the Count node. Its inner field `p.age` is a `TableField`. If the registry is active, the interception would try to resolve `p.age`. If `age` happens to be a GROUP BY column registered as `__group_col_N`, the interception would replace `p.age` with `__group_col_N`, producing `count(__group_col_N)` instead of `count(p.age)`.

**Analysis**: This cannot happen because of Q1's decision. The HAVING injection runs **before** `this.aliasRegistry = registry` (line 595). During injection, the registry is null, so the interception is inactive. Inner fields translate normally. After injection completes and the registry is activated, the hidden columns are already built.

**Decision: No issue. Q1's decision (inject before registry assignment) eliminates this concern entirely.**

### Q6: What happens with `HAVING max(salary) > 2 * avg(salary)` where neither aggregate is in SELECT?

**Context**: `collectAggregates()` returns `[max(salary), avg(salary)]`. Both need hidden columns. The arithmetic `2 * avg(salary)` is in the condition, and the `condition()` method will call `expression()` on the `Gt` operands. The left operand `max(salary)` will resolve to its hidden alias. The right operand `2 * avg(salary)` is `Mul(Param(2), Avg(salary))`.

**Analysis**: When `condition()` processes `Gt(max(salary), Mul(Param(2), Avg(salary)))`:
- `expression(max(salary))` → registry interception fires, resolves to `__having_col_0` ✓
- `expression(Mul(Param(2), Avg(salary)))` → Mul is not an aggregate, not a TableField, not a FieldAlias → interception skipped → enters the `QOM.Mul` branch at line 1709 → calls `expression(Param(2))` (returns literal 2) and `expression(Avg(salary))` (registry interception fires, resolves to `__having_col_1`) → produces `2 * __having_col_1` ✓

**Decision: Works correctly. Arithmetic wrappers fall through the type guard, and their aggregate children are resolved individually. No code change needed.**

### Q7: What about `HAVING sum(age) + sum(salary) > 100` where the entire left side is arithmetic?

**Context**: The condition is `Gt(Add(Sum(age), Sum(salary)), Param(100))`. `collectAggregates()` walks the arithmetic and returns `[sum(age), sum(salary)]`. Both get hidden columns. When `condition()` translates:
- `expression(Add(Sum(age), Sum(salary)))` → Add is not matched by type guard → enters `QOM.Add` branch → calls `expression(Sum(age))` (resolves to `__having_col_0`) + `expression(Sum(salary))` (resolves to `__having_col_1`) → produces `__having_col_0 + __having_col_1`
- `expression(Param(100))` → literal 100

Result: `__having_col_0 + __having_col_1 > 100` ✓

**Decision: Works correctly. Same mechanism as Q6.**

---

## Implementation Checklist

### 6.1 — Verify HAVING forces WITH path

**What**: Confirm that the existing condition at `SqlToCypher.java:488` correctly triggers the WITH path for all HAVING scenarios.

**Verification points**:
- `havingCondition != null` is checked before `requiresWithForGroupBy()` — short-circuit guarantees HAVING always takes the WITH path regardless of GROUP BY content
- HAVING without GROUP BY: `$having()` is non-null → `needsWithClause = true` → WITH path taken. The SELECT loop processes aggregates, GROUP BY loop is empty (no `groupByFields`). Result: aggregates appear in WITH, no group columns.
- HAVING with GROUP BY where all GROUP BY columns are in SELECT: `havingCondition != null` triggers WITH path even though `requiresWithForGroupBy()` would return false.

**Action**: No production code change. Add end-to-end tests in 6.9 to verify all three scenarios.

**Test command**: `./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dlicense.skip=true -Dcheckstyle.skip=true`

---

### 6.2 — Extend `collectAggregates()` with missing condition types

**What**: Add `IsNull`, `IsNotNull`, `InList`, `NotInList`, `Like`, `NotLike`, `LikeIgnoreCase`, `NotLikeIgnoreCase`, `IsDistinctFrom`, and `IsNotDistinctFrom` support to the `collectAggregatesFromCondition()` method in `FieldMatcher.java`.

**Why**: These are valid SQL condition types that can appear in HAVING clauses. The current implementation silently ignores aggregates inside these conditions, which would cause the hidden-column injection (6.4) to miss them — producing invalid Cypher.

**File**: `FieldMatcher.java`, method `collectAggregatesFromCondition()` (lines 140–185)

**Changes**: Add ten new `else if` branches after the `Between` handler (line 184):

```java
else if (condition instanceof QOM.IsNull isNull) {
    collectAggregatesFromField(isNull.$arg1(), result);
}
else if (condition instanceof QOM.IsNotNull isNotNull) {
    collectAggregatesFromField(isNotNull.$arg1(), result);
}
else if (condition instanceof QOM.InList<?> inList) {
    collectAggregatesFromField(inList.$field(), result);
    for (var element : inList.$list()) {
        collectAggregatesFromField(element, result);
    }
}
else if (condition instanceof QOM.Like like) {
    collectAggregatesFromField(like.$arg1(), result);
    collectAggregatesFromField(like.$arg2(), result);
}
else if (condition instanceof QOM.NotLike notLike) {
    collectAggregatesFromField(notLike.$arg1(), result);
    collectAggregatesFromField(notLike.$arg2(), result);
}
else if (condition instanceof QOM.LikeIgnoreCase likeIc) {
    collectAggregatesFromField(likeIc.$arg1(), result);
    collectAggregatesFromField(likeIc.$arg2(), result);
}
else if (condition instanceof QOM.NotLikeIgnoreCase notLikeIc) {
    collectAggregatesFromField(notLikeIc.$arg1(), result);
    collectAggregatesFromField(notLikeIc.$arg2(), result);
}
else if (condition instanceof QOM.NotInList<?> notInList) {
    collectAggregatesFromField(notInList.$field(), result);
    for (var element : notInList.$list()) {
        collectAggregatesFromField(element, result);
    }
}
else if (condition instanceof QOM.IsDistinctFrom<?> isDistinctFrom) {
    collectAggregatesFromField(isDistinctFrom.$arg1(), result);
    collectAggregatesFromField(isDistinctFrom.$arg2(), result);
}
else if (condition instanceof QOM.IsNotDistinctFrom<?> isNotDistinctFrom) {
    collectAggregatesFromField(isNotDistinctFrom.$arg1(), result);
    collectAggregatesFromField(isNotDistinctFrom.$arg2(), result);
}
```

**Notes** (verified against jOOQ 3.19.30 source):
- `QOM.IsNull.$arg1()` returns a `Field<?>`
- `QOM.InList.$field()` returns the search field, `$list()` returns the value list
- `QOM.NotInList` has identical structure to `InList`
- `QOM.Like.$arg1()` is the expression, `$arg2()` is the pattern. `$escape()` is `Character` (not a Field — no aggregate concern)
- `QOM.NotLike`, `QOM.LikeIgnoreCase`, `QOM.NotLikeIgnoreCase` have identical structure to `Like`
- `QOM.ILike` does not exist — jOOQ uses `QOM.LikeIgnoreCase` instead
- `QOM.IsDistinctFrom` / `QOM.IsNotDistinctFrom` have same two-`Field<T>` structure as comparison operators
- `collectAggregatesFromField()` handles null safely (line 188)

**After**: Run `./mvnw -pl neo4j-jdbc-translator/impl spring-javaformat:apply` then run tests.

---

### 6.3 — Add tests for new `collectAggregates()` condition types

**What**: Add test cases to `FieldMatcherTests.CollectAggregatesTests` for the new condition types.

**File**: `FieldMatcherTests.java`, nested class `CollectAggregatesTests` (after line 424)

**New test cases**:

1. **IsNull**: `HAVING count(*) IS NULL` → returns `[count(*)]`
   ```java
   @Test
   @DisplayName("IsNull: HAVING count(*) IS NULL returns 1 aggregate")
   void isNull() {
       var select = parseSelect("SELECT name, count(*) FROM People GROUP BY name HAVING count(*) IS NULL");
       var aggregates = FieldMatcher.collectAggregates(select.$having());
       assertThat(aggregates).hasSize(1);
       assertThat(aggregates.get(0)).isInstanceOf(QOM.Count.class);
   }
   ```

2. **IsNotNull**: `HAVING max(age) IS NOT NULL` → returns `[max(age)]`
   ```java
   @Test
   @DisplayName("IsNotNull: HAVING max(age) IS NOT NULL returns 1 aggregate")
   void isNotNull() {
       var select = parseSelect("SELECT name, max(age) FROM People GROUP BY name HAVING max(age) IS NOT NULL");
       var aggregates = FieldMatcher.collectAggregates(select.$having());
       assertThat(aggregates).hasSize(1);
       assertThat(aggregates.get(0)).isInstanceOf(QOM.Max.class);
   }
   ```

3. **InList**: `HAVING count(*) IN (1, 2, 3)` → returns `[count(*)]`
   ```java
   @Test
   @DisplayName("InList: HAVING count(*) IN (1, 2, 3) returns 1 aggregate")
   void inList() {
       var select = parseSelect("SELECT name, count(*) FROM People GROUP BY name HAVING count(*) IN (1, 2, 3)");
       var aggregates = FieldMatcher.collectAggregates(select.$having());
       assertThat(aggregates).hasSize(1);
       assertThat(aggregates.get(0)).isInstanceOf(QOM.Count.class);
   }
   ```

4. **Like** (with string aggregate): `HAVING max(name) LIKE 'A%'` → returns `[max(name)]`
   ```java
   @Test
   @DisplayName("Like: HAVING max(name) LIKE 'A%' returns 1 aggregate")
   void like() {
       var select = parseSelect("SELECT department, max(name) FROM Employees GROUP BY department HAVING max(name) LIKE 'A%'");
       var aggregates = FieldMatcher.collectAggregates(select.$having());
       assertThat(aggregates).hasSize(1);
       assertThat(aggregates.get(0)).isInstanceOf(QOM.Max.class);
   }
   ```

**After**: Run formatter then full test suite. Expect 417+ tests (407 + 10 new), 0 failures.

---

### 6.4 — Inject HAVING-only aggregates as hidden WITH columns

**What**: In `buildWithClause()`, after the GROUP BY loop and BEFORE `this.aliasRegistry = registry`, call `collectAggregates()` on the HAVING condition and add unregistered aggregates as hidden WITH columns.

**File**: `SqlToCypher.java`, method `buildWithClause()`, between lines 590 and 595.

**Changes**: Insert the following block between the GROUP BY loop (ends at line 590) and the registry assignment (line 595):

```java
// Inject HAVING-only aggregates as hidden WITH columns.
// These are aggregates referenced in HAVING but not in SELECT — they need
// to be projected in the WITH so the post-WITH WHERE can reference them,
// but they are NOT added to returnExpressions (excluded from final RETURN).
if (havingCondition != null) {
    for (var agg : FieldMatcher.collectAggregates(havingCondition)) {
        if (registry.resolve(agg) == null) {
            var expr = expression(agg);
            var alias = "__having_col_" + aliasCounter.getAndIncrement();
            withExpressions.add((IdentifiableElement) expr.as(alias));
            registry.register(agg, alias);
        }
    }
}
```

**Note on counter**: This reuses the shared `aliasCounter` (an `AtomicInteger` declared earlier in `buildWithClause()`) that is also used by the SELECT loop (`__with_col_N`) and GROUP BY loop (`__group_col_N`). Each prefix is distinct, so there is no collision risk. Using the shared counter is consistent with the existing pattern — no separate counter is needed.

**Why this position**:
- After the SELECT and GROUP BY loops: the registry contains all SELECT and GROUP BY-only aliases. `registry.resolve(agg)` can correctly identify aggregates that are already registered.
- Before `this.aliasRegistry = registry` (line 595): `expression(agg)` translates the aggregate using normal (non-registry) translation. This is correct — we want `count(*)` or `sum(p.age)`, not an alias reference. See Q1 and Q5.
- Before `reading.with(withExpressions)` (line 597): the hidden columns are included in the WITH clause.

**Notes on the `expression(agg)` call**:
- `this.aliasRegistry` is null at this point (not yet assigned), so the unified interception is inactive.
- The aggregate's inner fields (e.g., `p.age` inside `sum(age)`) are translated normally against `this.tables`, which is populated (line 464–465 in `statement()`).
- The returned expression is a Cypher aggregate function call (e.g., `sum(p.age)`) which is then aliased and added to `withExpressions`.

**Deduplication**: `registry.resolve(agg)` handles three cases:
1. Aggregate already in SELECT (e.g., `sum(age)` in both SELECT and HAVING) → resolve returns the SELECT alias → skip
2. First occurrence of a HAVING-only aggregate → resolve returns null → add and register
3. Subsequent occurrence of the same HAVING-only aggregate → resolve returns the just-registered alias → skip (automatic deduplication via registry, see Q4)

**After**: Run formatter then test suite. Existing HAVING test (line 985) should still pass. New tests (6.9) will validate the hidden column behavior.

---

### 6.5 — Simplify `havingCondition()` signature

**What**: Remove the unused `withExpressions` parameter from `havingCondition()`.

**File**: `SqlToCypher.java`

**Changes**:

1. Update the method signature (line 617):
   ```java
   // Before:
   private Condition havingCondition(org.jooq.Condition c, List<IdentifiableElement> withExpressions) {
   // After:
   private Condition havingCondition(org.jooq.Condition c) {
   ```

2. Update the call site in `buildWithClause()` (line 601):
   ```java
   // Before:
   afterWith = withStep.where(havingCondition(havingCondition, withExpressions));
   // After:
   afterWith = withStep.where(havingCondition(havingCondition));
   ```

3. Update the Javadoc to reflect the simplified mechanism.

**Why**: The `withExpressions` parameter was a placeholder from Phase 4 for potential HAVING-specific injection. Phase 6 item 6.4 handles injection in `buildWithClause()` directly (before the `havingCondition()` call), and the unified interception in `expression(Field<?>)` handles alias resolution. The parameter is dead code.

**After**: Run formatter then test suite. No behavioral change expected — all tests should pass unchanged.

---

### 6.6 — Verify HAVING-by-alias works

**What**: Verify that `HAVING cnt > 5` (where `cnt` is a SQL alias for `count(*)`) resolves correctly.

**Analysis**: jOOQ keeps alias references as unresolved `TableField` nodes with name `"cnt"`. The unified interception at `expression(Field<?>)` checks `f instanceof TableField<?, ?>` → passes type guard → calls `registry.resolve(f)`. `AliasRegistry.resolve()` unwraps and tries structural matching (fails — `cnt` is a plain TableField, not a Count node), then falls back to name-based lookup: `"CNT"` matches the registered alias `"cnt"` (case-insensitive) → returns the alias.

**Precondition**: The SELECT must include `count(*) AS cnt` so that `cnt` is in the name-based map.

**Action**: No production code change. Add end-to-end test in 6.9:
```
SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5
→ MATCH (p:People) RETURN p.name AS name, count(*) AS cnt  [if GROUP BY matches SELECT]
```
Wait — this query has GROUP BY `name` which IS in SELECT. So `requiresWithForGroupBy()` returns false. But `havingCondition != null` forces the WITH path. Expected output:
```
MATCH (p:People) WITH p.name AS name, count(*) AS cnt WHERE cnt > 5 RETURN name, cnt
```

**Note**: The `cnt` alias comes from the SELECT expression processing. When `expression(SelectFieldOrAsterisk)` (line 1275) processes `count(*) AS cnt`, it detects the `FieldAlias`, translates the inner `count(*)`, and applies `.as("cnt")`. In `buildWithClause()`, the SELECT loop at lines 565–566 detects the `AliasedExpression` and uses `"cnt"` as the alias. The registry maps `count(*)` → `"cnt"`. When HAVING processes `cnt > 5`, the unresolved `cnt` TableField resolves via name-based lookup to `"cnt"`.

**After**: Confirm with test.

---

### 6.7 — Verify HAVING with non-aggregate conditions

**What**: Verify `HAVING name = 'Alice'` where `name` is a GROUP BY-only column not in SELECT.

**Analysis**: For `SELECT count(*) FROM People p GROUP BY name HAVING name = 'Alice'`:
- GROUP BY loop registers `name` as `__group_col_N`
- HAVING condition: `Eq(TableField(name), Param('Alice'))`
- `expression(TableField(name))` → registry interception → structural match against GROUP BY entry → resolves to `__group_col_N`
- `expression(Param('Alice'))` → literal `'Alice'`
- WHERE becomes: `__group_col_N = 'Alice'`

Expected output:
```cypher
MATCH (p:People) WITH count(*) AS __with_col_0, p.name AS __group_col_1 WHERE __group_col_1 = 'Alice' RETURN __with_col_0
```

**Action**: No production code change. Add end-to-end test in 6.9.

---

### 6.8 — Verify HAVING without GROUP BY

**What**: Verify `SELECT count(*) FROM People HAVING count(*) > 5` (no GROUP BY — entire table is one implicit group).

**Analysis**:
- `havingCondition != null` → WITH path forced
- `groupByFields` is empty → GROUP BY loop adds nothing
- SELECT loop: `count(*)` → `count(p)` aliased as `__with_col_0` (Note: jOOQ's `count(*)` translates to `count(p)` when a single node `p` is in scope — this is existing behavior)
- `collectAggregates(havingCondition)`: returns `[count(*)]`. `registry.resolve(count(*))` → returns `__with_col_0` (already registered from SELECT). Injection skips it.
- HAVING condition: `count(*) > 5` → `__with_col_0 > 5`

Expected output:
```cypher
MATCH (p:People) WITH count(p) AS __with_col_0 WHERE __with_col_0 > 5 RETURN __with_col_0
```

**Action**: No production code change. Add end-to-end test in 6.9.

---

### 6.9 — End-to-end tests

**What**: Add parameterized end-to-end tests to `SqlToCypherTests.java` covering all HAVING scenarios.

**File**: `SqlToCypherTests.java`

**Test cases** (add as a new parameterized test method or extend `withClauseGeneration`):

| # | SQL | Expected Cypher | Scenario |
|---|-----|-----------------|----------|
| 1 | `SELECT name FROM People p GROUP BY name HAVING count(*) > 5` | *(capture actual output)* | HAVING-only aggregate (core Phase 6 feature) |
| 2 | `SELECT count(*) FROM People p HAVING count(*) > 5` | *(capture actual output)* | HAVING without GROUP BY |
| 3 | `SELECT count(*) FROM People p GROUP BY name HAVING name = 'Alice'` | *(capture actual output)* | HAVING with non-aggregate condition on GROUP BY-only column |
| 4 | `SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5` | *(capture actual output — see note below)* | HAVING by alias |
| 5 | `SELECT name FROM People p GROUP BY name HAVING count(*) > 5 AND max(age) > 50` | *(capture actual output)* | Multiple HAVING-only aggregates |
| 6 | `SELECT name, sum(age) FROM People p GROUP BY name HAVING sum(age) > 100 AND count(*) > 2` | *(capture actual output)* | Mixed: one aggregate in SELECT, one HAVING-only |
| 7 | `SELECT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name` | *(capture actual output)* | HAVING + ORDER BY combination |
| 8 | `SELECT name FROM People p GROUP BY name HAVING count(DISTINCT age) > 3` | *(capture actual output)* | HAVING-only DISTINCT aggregate (validates Q3a) |

**Structural expectations** (what to verify in the captured output, even though exact formatting may vary):
- Test 1: WITH clause must contain `count(*)` (or `count(p)`) aliased as `__having_col_N`, and WHERE must reference that alias. The aggregate must NOT appear in RETURN.
- Test 2: `count(*)` is in both SELECT and HAVING — injection should skip it (already registered from SELECT). Only one `__with_col_0` in WITH.
- Test 3: GROUP BY-only column `name` appears as `__group_col_N` in WITH, referenced in WHERE.
- Test 4: `cnt` alias from SELECT must resolve in HAVING. No hidden columns needed.
- Test 5: Two hidden columns (`__having_col_N`, `__having_col_M`) for the two HAVING-only aggregates.
- Test 6: `sum(age)` from SELECT is NOT duplicated. Only `count(*)` gets a hidden column.
- Test 7: ORDER BY `name` must reference the same alias used in WITH/RETURN.
- Test 8: `count(DISTINCT age)` must appear as a hidden column. jOOQ uses `QOM.Count` with `$distinct()=true` — `isAggregate()` handles this (see Q3a).

**Important implementation notes**:

- Test case 1 is the **critical** test — it validates the core hidden-column injection. If this fails, the feature doesn't work.
- Test case 2 validates HAVING without GROUP BY (Q10 from `group_phase_4_plan.md`).
- Test case 4 validates alias-form HAVING. This may need diagnostic investigation — jOOQ may resolve `cnt` differently depending on context. Parse the SQL first and inspect `$having()` to confirm what jOOQ produces before writing the expected output.
- Test case 6 is important: it confirms that `collectAggregates()` deduplication works (sum(age) is in SELECT and HAVING, count(*) is HAVING-only).
- Test case 8 validates that `COUNT(DISTINCT ...)` flows through the entire pipeline: `isAggregate()` → `collectAggregates()` → hidden column injection → registry resolution.
- **Procedure: Run each SQL through the translator after implementing 6.4 and capture the actual output before writing assertions.** Do not guess exact Cypher strings. Verify the structural expectations above hold, then use the actual output as the expected value.

**Why capture-then-assert**: Several formatting details are unpredictable from static analysis:
- Whether `count(*)` translates to `count(*)` or `count(p)` depends on context (single node in scope) and jOOQ version.
- Whether parentheses appear around AND conditions depends on the Cypher builder library.
- Column ordering in WITH depends on SELECT field order and GROUP BY field order.
- The `__having_col_N` suffix depends on the shared `aliasCounter` value, which varies with the number of SELECT and GROUP BY columns processed first.

**After**: Run formatter then full test suite after each test addition. Verify total count increases appropriately.

---

### 6.10 — Formatting and quality gate

**What**: Apply all formatting, verify all tests pass.

**Commands**:
```bash
./mvnw -pl neo4j-jdbc-translator/impl spring-javaformat:apply
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dlicense.skip=true -Dcheckstyle.skip=true
```

**Quality gate criteria**:
- [ ] All existing tests pass (zero regressions)
- [ ] New `collectAggregates()` tests pass (10 new cases for IsNull, IsNotNull, InList, NotInList, Like, NotLike, LikeIgnoreCase, NotLikeIgnoreCase, IsDistinctFrom, IsNotDistinctFrom)
- [ ] New end-to-end tests pass (8 new test cases from 6.9)
- [ ] `havingCondition()` has simplified signature (no `withExpressions` parameter)
- [ ] Hidden columns appear in WITH but not in RETURN
- [ ] Spring formatting applied cleanly
- [ ] HAVING-only aggregates produce valid Cypher with `__having_col_N` aliases
- [ ] `COUNT(DISTINCT ...)` in HAVING produces correct hidden column (test case 8)

---

## Implementation Order

The items above are ordered by dependency:

```
6.1 (verify — no code change)
  ↓
6.2 (extend collectAggregates)  →  6.3 (test collectAggregates)
  ↓
6.4 (inject hidden HAVING columns)  →  6.5 (simplify havingCondition)
  ↓
6.6, 6.7, 6.8 (verification — no code change, covered by 6.9 tests)
  ↓
6.9 (end-to-end tests)
  ↓
6.10 (formatting and quality gate)
```

**Critical path**: 6.2 → 6.4 → 6.9. Items 6.3 and 6.5 can be done in parallel with their successors. Items 6.6–6.8 are verification only — they have no code changes and are validated by 6.9's tests.

**Expected final test count**: 407 (baseline) + 10 (collectAggregates unit tests) + 8 (end-to-end HAVING tests) = 425 tests.

**Actual final test count**: 425 tests, 0 failures, 0 errors. All quality gate criteria met.

**Run tests after every step** (BP-7 from `group_phase_4_plan.md`). Apply formatting after every code change (BP-8).

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `expression(agg)` call during injection interacts with tables/scope unexpectedly | Low | High | Q1 decision (inject before registry assignment) isolates the call. Tables are populated at this point (line 464–465). |
| jOOQ resolves HAVING alias-form differently than expected | Medium | Medium | Write a diagnostic test (parse SQL, inspect `$having()`) before writing the end-to-end assertion. |
| Cypher builder library adds unexpected parentheses or formatting | Medium | Low | Use actual translator output to set expectations, not hand-written strings. |
| `collectAggregates()` misses an aggregate in a complex expression tree | Low | High | The 19 unit tests (9 existing + 10 new) cover all realistic HAVING patterns. The recursive walker is straightforward. |
| Hidden column alias collides with user-defined alias | Very Low | Medium | The `__having_col_` prefix is synthetic and extremely unlikely to collide with real SQL aliases. |

---

## Files Modified

| File | Changes |
|------|---------|
| `FieldMatcher.java` | Add `IsNull`, `IsNotNull`, `InList`, `NotInList`, `Like`, `NotLike`, `LikeIgnoreCase`, `NotLikeIgnoreCase`, `IsDistinctFrom`, `IsNotDistinctFrom` to `collectAggregatesFromCondition()` |
| `FieldMatcherTests.java` | Add 10 test cases for new condition types |
| `SqlToCypher.java` | Insert HAVING injection block in `buildWithClause()` (6.4), simplify `havingCondition()` signature (6.5) |
| `SqlToCypherTests.java` | Add 8 end-to-end HAVING test cases (6.9) |

**No new files created.** All changes are modifications to existing files.
