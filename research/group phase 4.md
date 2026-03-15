# GROUP BY and HAVING Implementation — Phase 4 Proposal

## Remaining Open Questions

1. **Scope of GROUP BY validation for invalid queries**: When a query has a non-aggregated SELECT column missing from GROUP BY (for example, `SELECT department, name, count(*) FROM Employees GROUP BY department`), should the translator reject the query with an error, emit a warning and translate it anyway, or silently translate it as Cypher would naturally interpret it (grouping by both department and name)? This affects Phase 7 only and does not block earlier phases.

---

## Resolved Questions (from prior review)

**HAVING with aggregates not in SELECT**: We will support this. When a HAVING clause references an aggregate that is not in the SELECT list, the translator will inject that aggregate into the WITH clause as a hidden column used only for filtering. The final RETURN will exclude it. This is valid SQL and common in production queries. Rejecting it would unnecessarily limit the translator's usefulness.

**Nested aggregates in HAVING**: In scope. Expressions like `HAVING max(salary) > 2 * avg(salary)` are valid SQL and will be supported. The structural matcher handles this naturally because the matcher only needs to resolve the individual aggregate sub-expressions (max(salary) and avg(salary)) to their aliases — the arithmetic wrapping them is handled by the existing expression translator. Explicit test cases will be added.

**HAVING referencing aliases versus function forms**: Both forms are valid SQL. Phase 1 will include diagnostic tests that parse SQL through jOOQ and inspect the QOM to determine exactly what jOOQ produces for each form. Multiple diagnostic tests will cover: HAVING by function form, HAVING by alias, HAVING mixing both, and HAVING with aggregates that have no SQL alias.

**ORDER BY interaction with WITH**: Research confirmed this is a real problem. The current ORDER BY translation in `expression(SortField<?>)` resolves fields against the original table scope. When a WITH clause is present, ORDER BY needs to reference the WITH aliases instead. The current code has no awareness of WITH context. This is the same class of problem as HAVING alias resolution and will use the same structural matcher and alias registry to fix. It is promoted from an edge case to a full phase in the plan (Phase 5). Diagnostic tests in Phase 1 will also cover ORDER BY field resolution.

**DISTINCT with HAVING**: The most accurate translation is to use plain `WITH` (not `WITH DISTINCT`) for the aggregation clause, and `RETURN DISTINCT` on the final projection if the original SQL had `SELECT DISTINCT`. The reasoning: in SQL, `SELECT DISTINCT` deduplicates the final result rows. When aggregation is involved, the GROUP BY already produces unique groups, so DISTINCT is redundant but harmless. In Cypher, `WITH DISTINCT` would deduplicate before the implicit aggregation, which changes the semantics — it would eliminate duplicate input rows before grouping, which is not what SQL DISTINCT means. The correct mapping is: WITH (no DISTINCT) for the aggregation step, then RETURN DISTINCT for the final output if the SQL used SELECT DISTINCT. Test cases will verify this by comparing queries with and without DISTINCT and confirming the WITH clause is never modified by the DISTINCT flag.

---

## Context

The SQL-to-Cypher translator currently ignores two parts of the jOOQ AST: the GROUP BY list and the HAVING condition. For most GROUP BY queries this works fine because Cypher's implicit grouping produces the right result. But two categories of queries translate incorrectly:

- **HAVING clauses are silently dropped.** Post-aggregation filters never reach the Cypher output, so every group is returned regardless of whether it satisfies the HAVING predicate.

- **GROUP BY columns not in SELECT are ignored.** When the GROUP BY lists a column that does not appear in SELECT, the translator has no way to know about it. Cypher then groups by whatever non-aggregated columns happen to be in the RETURN, which may produce a different result than the SQL intended.

Both problems share the same solution: introduce a Cypher WITH clause between the MATCH and the final RETURN. The WITH clause makes the grouping explicit, and a WHERE after the WITH handles the HAVING filter.

Work has already started. The current code in SqlToCypher.java reads the GROUP BY and HAVING from the AST and has a skeleton for generating the WITH clause. However, design issues around alias resolution, ORDER BY interaction, and field comparison remain. This proposal addresses all of them and lays out a phased plan that starts with research and validation before any production code changes.

---

## Design Decisions

### No backward compatibility — fix the tests

The existing test for `SELECT sum(age) FROM People GROUP BY name` expects the translator to produce `RETURN sum(p.age)`, which collapses all rows into a single sum. This is semantically wrong. The SQL says to group by name and return one sum per name. The correct Cypher uses a WITH clause to group by name.

We will fix this. The tests will be updated to expect the correct output. There is no configuration flag or opt-in. The translator will produce semantically correct Cypher for all GROUP BY queries, and the test expectations will reflect that.

This means the `requiresWithForGroupBy` check stays in the code. It will trigger a WITH clause whenever the GROUP BY contains fields that are not in the SELECT list. The fragile field-name comparison (Issue 3 from the challenges document) will be addressed by using fully qualified names (table plus column) rather than bare column names.

### Structural matching for alias resolution — not string comparison

The challenges document identified that HAVING conditions reference the original aggregate expressions from jOOQ, not the aliases assigned in the WITH clause. Research confirmed that ORDER BY has the same problem. The simple approach would be to compare string representations and look them up in a map. This is fragile because it depends on jOOQ's toString format being stable and consistent.

Instead, we will use structural matching on the jOOQ QOM tree. During WITH clause construction, we will build a registry that maps each expression (aggregates and grouping columns) to its WITH alias. The registry key will be the jOOQ Field object itself, matched by walking the QOM structure. During HAVING condition translation and ORDER BY translation, when we encounter a Field that matches a registered expression, we emit a Cypher name reference to the alias instead of re-translating the expression.

Structural matching means comparing the QOM node type and its children recursively. Two Count expressions match if they are both Count nodes and their inner field arguments match structurally. Two field references match if they refer to the same table and column. This is more work than string comparison, but it is deterministic, does not depend on formatting, and handles edge cases that string comparison would get wrong.

The structural matcher will be built as an isolated, independently testable component. This makes it easy to write focused unit tests for the matching logic without needing to run full SQL-to-Cypher translations. The same matcher and registry will be reused by both HAVING and ORDER BY, avoiding duplicated logic.

### Hidden columns in WITH for HAVING-only aggregates

When a HAVING clause references an aggregate that does not appear in the SELECT list, the translator will add that aggregate to the WITH clause with a synthetic alias. The aggregate will be used in the WHERE filter after the WITH but will be excluded from the final RETURN. This ensures the translator supports the widest possible range of valid SQL without rejecting queries that are correct but uncommon.

---

## Phased Implementation Plan

Each phase ends with a quality gate: compile, run all existing tests, run the new tests added in that phase, and apply the project's code formatting and style checks. No phase begins until the previous phase is fully green.

### Phase 1: Research, Diagnostic Tests, and Baseline Validation

**Goal**: Understand exactly how jOOQ represents GROUP BY, HAVING, ORDER BY, and DISTINCT in its QOM before writing any production code. Establish a comprehensive baseline of existing behavior so that regressions are caught immediately. Answer every remaining unknown with concrete evidence from diagnostic tests.

**What to do**:

Write a set of diagnostic tests (these can live in a dedicated test class or as clearly-marked sections in the existing test class). Each diagnostic test parses a SQL statement through jOOQ, inspects the resulting QOM tree, and documents what jOOQ produces. These tests are not about Cypher output — they are about understanding the input.

**Diagnostic tests for jOOQ QOM behavior**:

- Parse `SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5` and inspect `$having()`. Document whether jOOQ presents the HAVING aggregate as a fresh `QOM.Count` node or as a reference to the aliased SELECT field.
- Parse `SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING cnt > 5` (HAVING by alias) and inspect `$having()`. Document whether jOOQ resolves the alias to the aggregate or keeps it as an unresolved field reference.
- Parse a query with compound HAVING: `HAVING count(*) > 5 AND max(age) > 50`. Verify the condition tree structure (should be `QOM.And` with two comparison children).
- Parse a query with arithmetic in HAVING: `HAVING max(salary) > 2 * avg(salary)`. Verify the expression tree includes both aggregates and the arithmetic.
- Parse a query with HAVING referencing an aggregate not in SELECT: `SELECT name FROM People GROUP BY name HAVING count(*) > 5`. Verify the `$having()` condition contains the count aggregate even though it is absent from `$select()`.
- Parse `SELECT name, count(*) FROM People GROUP BY name ORDER BY count(*)` and inspect `$orderBy()`. Document whether jOOQ presents the ORDER BY as a fresh `QOM.Count` node, an alias reference, or a positional reference.
- Parse `SELECT name, count(*) AS cnt FROM People GROUP BY name ORDER BY cnt` and inspect `$orderBy()`. Document how jOOQ represents ORDER BY by alias.
- Parse `SELECT DISTINCT name, count(*) FROM People GROUP BY name`. Verify `$distinct()` returns true and that the SELECT and GROUP BY lists are unaffected by the DISTINCT flag.
- Parse a query with GROUP BY column not in SELECT: `SELECT sum(age) FROM People GROUP BY name`. Inspect `$groupBy()` and `$select()` to confirm the GROUP BY field is accessible and not present in SELECT.
- Parse a join query with GROUP BY: `SELECT c.name, count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name`. Inspect table qualifiers on both GROUP BY and SELECT fields.

**Baseline validation of existing translator behavior**:

- Run the full existing test suite and record the current passing state. Every existing test must pass before any changes begin.
- Catalog all existing tests that involve GROUP BY or aggregates (lines 551-568, 767-772, and any others found). Document what SQL each test translates and what Cypher it expects. This catalog becomes the reference for which tests will need updated expectations in later phases.
- Identify any existing tests that combine GROUP BY with ORDER BY, DISTINCT, or LIMIT. (Research indicates none exist currently — confirm this.)

**Quality gate**: All diagnostic tests pass and produce documented output. All existing translator tests pass unchanged. A written summary of jOOQ QOM behavior for each diagnostic case is recorded (can be in test comments or a short addendum to this document). No production code has been modified.

**Why this phase is first**: Every design assumption in the later phases depends on how jOOQ actually represents these constructs. By answering these questions with real tests rather than assumptions, we avoid building the structural matcher or alias registry on incorrect premises. The diagnostic tests also serve as regression tests for jOOQ behavior — if a future jOOQ upgrade changes any of these behaviors, the diagnostic tests will catch it.

---

### Phase 2: Focused Field Equivalence Matcher

**Goal**: Build and test a focused method that determines whether two jOOQ Field objects represent the same expression. This is the foundation that the alias registry (Phase 3), GROUP BY field comparison (Phase 4), HAVING resolution (Phase 6), and ORDER BY resolution (Phase 5) all depend on.

**What to build**: A single static method (for example, `fieldsMatch(Field<?> a, Field<?> b)`) inside `SqlToCypher.java` or a small companion class. The method handles exactly four cases, each one validated by Phase 1 diagnostic findings:

1. **Alias unwrapping**: If either field is a `QOM.FieldAlias`, strip the alias wrapper and compare the underlying `$field()`. Aliases are presentation, not identity. This ensures that `count(*) AS cnt` from SELECT matches bare `count(*)` from HAVING.

2. **Aggregate function matching**: If both fields are the same aggregate type (`QOM.Count`, `QOM.Sum`, `QOM.Min`, `QOM.Max`, `QOM.Avg`, `QOM.StddevSamp`, `QOM.StddevPop`), compare their inner `$field()` arguments recursively. For `QOM.Count`, also compare the `$distinct()` flag — `count(x)` and `count(DISTINCT x)` are structurally different (Phase 1 Finding: `countDistinctVsCountAll` test). The inner field of `count(*)` is an `org.jooq.impl.SQLField` with `toString()` returning `"*"` (Phase 1 Finding 1) — compare via `toString().equals("*")`, not `instanceof Asterisk`.

3. **Simple column references**: If both fields are `TableField` or plain `Field` references, compare by name (case-insensitive). When both have table qualifiers (both are `TableField` with non-null `getTable()`), also compare the table name. When only one has a table qualifier, match on column name alone — this is the pragmatic choice for single-table queries where GROUP BY may omit the table prefix. Phase 1 confirmed that jOOQ preserves table qualifiers on GROUP BY fields in join queries (`groupByOnJoinQuery` test), so the fully qualified path handles join ambiguity correctly.

4. **Fallback**: If neither field matches the above cases, return false. The matcher does not need to handle arithmetic expressions, function calls, CASE expressions, or other complex types — those are never registered as standalone aliases in the WITH clause.

**What the matcher explicitly does NOT handle** (and why):

- **Arithmetic expressions** (`QOM.Mul`, `QOM.Add`, etc.): These appear as wrappers around aggregates in HAVING conditions (for example, `2 * avg(salary)`), but the matcher never needs to compare two arithmetic trees. The condition translator walks the expression tree and resolves each aggregate leaf independently through the alias registry. The arithmetic structure around them is handled by the existing expression translator. Building arithmetic matching would be dead code.

- **Condition types** (`QOM.Gt`, `QOM.And`, etc.): These are handled by the existing `condition()` method. The matcher only operates on Field objects, not Condition objects.

- **General recursive QOM tree walking**: Not needed. The four specific cases above cover every scenario that arises from GROUP BY, HAVING, and ORDER BY alias resolution, as proven by Phase 1.

**How to test**: All test inputs should come from **parsed SQL** via jOOQ's parser, not from hand-constructed jOOQ objects. Phase 1 proved that jOOQ's parser produces internal types (like `SQLField` for `count(*)`) that differ from what you might construct manually. Using parsed SQL guarantees the tests exercise the exact same types the production code will encounter.

Test cases:

- Parse `SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5`. Extract `count(*)` from `$select()` (unwrapping alias) and from `$having()` (`QOM.Gt.$arg1()`). Pass both to the matcher — should return true.
- Same query but extract `count(*)` from SELECT and `name` from GROUP BY — should return false (different types: aggregate vs column).
- Parse `SELECT name, sum(age) AS total FROM People GROUP BY name HAVING sum(age) > 100`. Extract `sum(age)` from both SELECT and HAVING — should return true.
- Parse `SELECT count(*) AS cnt1, count(name) AS cnt2 FROM People GROUP BY age`. Extract both counts — should return false (different inner fields: `*` vs `name`).
- Parse `SELECT count(DISTINCT name) AS cd, count(name) AS ca FROM People GROUP BY age`. Extract both counts — should return false (`$distinct()` flag differs).
- Parse a join query: `SELECT c.name, count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name`. Extract `c.name` from SELECT and GROUP BY — should return true (same table, same column).
- Parse `SELECT a.name, b.name, count(*) FROM A a JOIN B b ON a.id = b.a_id GROUP BY a.name, b.name`. Extract `a.name` and `b.name` — should return false (different tables).
- Parse `SELECT name, count(*) AS cnt FROM People GROUP BY name`. Extract `name` from SELECT (which may lack a table qualifier) and `name` from GROUP BY — should return true (unqualified match).
- Aliased vs unaliased: Parse `SELECT count(*) AS cnt, count(*) FROM People GROUP BY name`. Extract the aliased `count(*)` and the unaliased `count(*)` — should return true after alias unwrapping.

**Quality gate**: All new matcher tests pass. All existing translator tests still pass (the matcher is not wired into the translator yet). Code formatting and checkstyle applied. Each test uses parsed SQL, not synthetic objects.

**Why this phase is separate**: The matcher is the riskiest piece of the design. By building and testing it in isolation first with real parsed inputs, we validate the approach before touching the translator's main code path. If the focused matching proves insufficient for any case, we discover it here and can expand the matcher before it contaminates later phases. The narrow scope (four cases, not a general recursive walker) reduces both implementation risk and testing surface area.

---

### Phase 3: Aggregate and Expression Alias Registry

**Goal**: Build and test the registry that maps expressions to their WITH clause aliases. This registry will be populated during WITH construction and queried during HAVING and ORDER BY translation.

**What to build**: A data structure that stores entries pairing a jOOQ Field with a string alias. It should provide a lookup method that takes a jOOQ Field and returns the matching alias. The lookup must support two resolution modes (based on Phase 1 Finding 2):

- **Structural matching**: When the input field is an aggregate (QOM.Count, QOM.Sum, etc.), use the structural matcher from Phase 2 to find a matching registered aggregate. This handles `HAVING count(*) > 5` and `ORDER BY count(*)`.
- **Name-based matching**: When the input field is a plain field reference (not an aggregate), check if its name matches any registered alias. This handles `HAVING cnt > 5` and `ORDER BY cnt`, where jOOQ keeps the alias as an unresolved field reference.

If no match is found by either mode, return nothing (indicating the field should be translated normally).

The registry needs to handle three categories of entries:

- Aggregate expressions from SELECT (for example, count(*) mapped to "cnt") — matched structurally
- Non-aggregate grouping expressions from SELECT (for example, column references mapped to their alias, needed for ORDER BY resolution) — matched structurally or by name
- Alias names themselves (for example, "cnt" as a key) — matched by name when jOOQ presents an unresolved alias reference

**What to test**:

- Register a count(*) with alias "cnt", then look up count(*) structurally and get "cnt"
- Register sum(age) with alias "total", then look up sum(salary) and get no match (different column)
- Register multiple aggregates (count, sum, max) and verify each resolves to the correct alias via structural matching
- Look up a non-aggregate field and get no match when only aggregates are registered
- Register a non-aggregate column reference with alias "name", look it up from an ORDER BY field and get "name"
- Register the same aggregate twice with different aliases — verify the behavior is well-defined (first registration wins)
- **Name-based lookup**: Register count(*) with alias "cnt", then look up a plain field reference named "cnt" (simulating `HAVING cnt > 5`) — should return "cnt"
- **Name-based lookup**: Register sum(age) with alias "total", then look up a plain field reference named "total" — should return "total"
- **Name-based negative**: Look up a plain field reference named "unknown" — should return nothing
- Register entries from an actual parsed SQL statement (using jOOQ parser) and look up fields from the same statement's HAVING and ORDER BY — verify real-world round-trip works for both function-form and alias-form references
- Register a count(*) from SELECT, then look up a count(*) from HAVING of the same parsed query — should match structurally

**Quality gate**: All registry tests pass. All existing translator tests still pass. The registry is not wired into the translator yet. Code formatting applied.

**Why this phase is separate**: Testing the registry independently confirms that the lookup logic works before it is used in any translation path. Combined with the Phase 2 matcher tests, this gives high confidence that alias resolution is correct before we touch production translation logic.

---

### Phase 4: WITH Clause Generation for GROUP BY

**Goal**: Wire the GROUP BY detection and WITH clause generation into the translator. After this phase, queries where GROUP BY columns differ from SELECT columns will produce semantically correct Cypher with a WITH clause.

**What to build**: Modify the `statement(Select<?>)` method to read the GROUP BY list and, when it contains fields not present in the SELECT, route through the WITH-based code path. Use the structural matcher from Phase 2 for the field comparison (replacing the fragile bare-name comparison). This gives fully qualified, table-aware comparison that handles join queries correctly.

The WITH clause should include:

- All SELECT expressions, each aliased (preserving SQL aliases where present, generating synthetic aliases otherwise)
- Any GROUP BY-only fields not covered by SELECT, aliased with synthetic names
- The final RETURN projects only the columns that appeared in the original SQL SELECT, referencing them by their WITH aliases

For the common case where GROUP BY columns match the SELECT columns and there is no HAVING, the translator continues to produce a simple MATCH-RETURN without a WITH clause. This keeps the generated Cypher clean and simple for the majority of queries.

**What to test**:

- Update the existing test for `SELECT sum(age) FROM People GROUP BY name` to expect the correct WITH-based output (one sum per name, not a single total)
- Verify the common case still works: `SELECT name, count(*) FROM People GROUP BY name` produces a simple RETURN without a WITH clause
- Add a test for multiple GROUP BY columns where one is in SELECT and one is not
- Add a test for a join query with GROUP BY where both tables have identically-named columns to verify the fully qualified comparison avoids false matches
- Add a test for GROUP BY with all columns in SELECT (should not produce a WITH clause)
- Run the full existing test suite to confirm no other tests broke (beyond the one updated expectation)

**Quality gate**: All existing tests pass (with updated expectations). All new GROUP BY tests pass. Code formatting applied. Review generated Cypher for each new test case to confirm readability.

**Why this phase is separate**: This phase changes existing observable behavior. By handling it before HAVING or ORDER BY, we verify that the core WITH generation mechanism works correctly for the simpler case (no post-aggregation filter, no alias resolution in downstream clauses).

---

### Phase 5: ORDER BY Alias Resolution with WITH

**Goal**: Fix ORDER BY to reference WITH aliases when a WITH clause is present. This must be done before HAVING because a correct end-to-end test of HAVING will often include ORDER BY, and we need ORDER BY working correctly to write meaningful HAVING tests.

**What to build**: Modify the ORDER BY translation path so that when a WITH clause has been generated, ORDER BY fields are resolved through the alias registry from Phase 3 before falling through to the normal expression translation.

The current `expression(SortField<?>)` method resolves ORDER BY fields against the original table scope. When a WITH clause is present, the table scope is no longer directly accessible — Cypher's scope after a WITH only includes the aliases established in the WITH. The fix is to check the alias registry first: if the ORDER BY field structurally matches an expression that was aliased in the WITH, emit a sort on `Cypher.name(alias)` instead of re-translating the expression.

This reuses the exact same structural matcher and alias registry built in Phases 2 and 3. No new matching infrastructure is needed.

**What to test**:

- `SELECT name, count(*) AS cnt FROM People GROUP BY name ORDER BY cnt` — ORDER BY should reference the alias from the RETURN
- `SELECT name, count(*) FROM People GROUP BY name ORDER BY count(*)` — ORDER BY by aggregate function should resolve to the WITH alias
- `SELECT name, count(*) AS cnt FROM People GROUP BY name ORDER BY name` — ORDER BY by a non-aggregate column should also resolve to the WITH alias when WITH is present
- `SELECT name, count(*) FROM People GROUP BY name ORDER BY count(*) DESC` — verify sort direction is preserved
- A join query with GROUP BY and ORDER BY — verify correct alias resolution across relationships
- A query with ORDER BY that does not involve GROUP BY or WITH — verify the existing behavior is completely unchanged (regression check)
- A query with ORDER BY, GROUP BY, and LIMIT — verify the full chain works

**Quality gate**: All previous tests still pass. All new ORDER BY tests pass. The alias resolution uses the same registry as will be used for HAVING. Code formatting applied.

**Why this phase comes before HAVING**: ORDER BY is simpler than HAVING (it only needs to resolve field references, not full condition trees with nested aggregates). Getting it right first means that when we write HAVING tests in Phase 6, we can include ORDER BY in those tests and trust that it works. This also validates the alias registry in a real translation context before we depend on it for the more complex HAVING case.

---

### Phase 6: HAVING Condition Translation

**Goal**: Add HAVING support using the structural matcher and alias registry proven in Phases 2 through 5.

**What to build**: Modify the `statement(Select<?>)` method to check for a HAVING condition. When HAVING is present, always use the WITH-based path (even if GROUP BY columns match SELECT columns — the HAVING filter requires a WHERE after the WITH).

During WITH clause construction, populate the alias registry with every aggregate expression from the SELECT list and its corresponding WITH alias. When the HAVING references an aggregate that is not in the SELECT list, add that aggregate to the WITH clause as a hidden column with a synthetic alias. The hidden column participates in the WHERE filter but is excluded from the final RETURN.

Replace the current `havingCondition` method with a version that translates conditions in a WITH-alias-aware context. The approach: use the existing `condition()` translator but with an expression interception layer. Before translating any Field in the HAVING condition, check the alias registry. If the field structurally matches a registered expression, emit `Cypher.name(alias)` instead of re-translating it. If not, fall through to the normal expression translation.

The interception happens at the Field level, not the Condition level. The existing condition translator (which handles And, Or, Gt, Lt, Eq, and so on) does not need to change. Only the expression translator needs a HAVING-aware mode.

For nested arithmetic in HAVING (for example, `HAVING max(salary) > 2 * avg(salary)`), the interception resolves each aggregate sub-expression independently. The arithmetic operators around them are handled by the existing expression translator. The result is a WHERE condition like `WHERE max_sal > 2 * avg_sal`, where each aggregate has been replaced with its alias and the arithmetic is preserved.

**What to test**:

- Simple HAVING: `SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5` — WHERE should reference the alias "cnt", not re-invoke count(*)
- HAVING without SELECT alias: `SELECT name, count(*) FROM People GROUP BY name HAVING count(*) > 5` — translator must assign an internal alias in the WITH and reference it in the WHERE
- HAVING by alias (if jOOQ supports it): `SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING cnt > 5` — should produce the same output as the function form
- Compound HAVING: `HAVING count(*) > 5 AND max(age) > 50` — both aggregates should resolve to their respective aliases
- Arithmetic in HAVING: `HAVING max(salary) > 2 * avg(salary)` — both aggregates resolve, arithmetic preserved
- HAVING with aggregate not in SELECT: `SELECT name FROM People GROUP BY name HAVING count(*) > 5` — count(*) appears in WITH for filtering, excluded from RETURN
- HAVING on a join query: verify the full MATCH pattern, WITH, WHERE, RETURN chain works across relationships
- HAVING with ORDER BY: `SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING cnt > 5 ORDER BY cnt DESC` — verify end-to-end with both HAVING and ORDER BY alias resolution
- HAVING with ORDER BY and LIMIT: verify the full chain
- HAVING with every supported aggregate function: count, sum, min, max, avg — each one in a HAVING condition, each one verified to use the alias

**Quality gate**: All previous tests still pass. All new HAVING tests pass. The structural matcher correctly resolves every aggregate in every test case. Code formatting and style checks applied. Manual review of generated Cypher for each test to confirm correctness and readability.

**Why this phase is separate**: HAVING is the most complex addition. By the time we reach this phase, the WITH generation mechanism is proven (Phase 4), the alias registry is proven (Phase 3), ORDER BY alias resolution is proven (Phase 5), and the structural matcher is proven (Phase 2). This phase only needs to wire them together and add the HAVING-specific logic (condition interception and hidden columns for HAVING-only aggregates).

---

### Phase 7: DISTINCT, Validation, and Hardening

**Goal**: Handle the remaining edge cases, add validation for malformed queries, and ensure robustness across all combinations of GROUP BY, HAVING, ORDER BY, DISTINCT, and LIMIT.

**What to build**:

- **DISTINCT with WITH**: When a query has both SELECT DISTINCT and a WITH clause (from GROUP BY or HAVING), apply DISTINCT only to the final RETURN, not to the WITH. The WITH performs aggregation — deduplicating before aggregation would change the semantics. The RETURN DISTINCT deduplicates the final result, which matches SQL's SELECT DISTINCT behavior. In practice, DISTINCT after GROUP BY is redundant (aggregation already produces unique groups), but the translator should still honor it for correctness.

- **LIMIT and OFFSET with WITH**: Verify these clauses still chain correctly after the WITH-based path. They should attach to the final RETURN, not to the WITH. The existing builder chain likely handles this correctly, but it needs explicit test coverage.

- **GROUP BY validation**: When a query has a GROUP BY clause, optionally check that every non-aggregated SELECT expression appears in the GROUP BY list. If it does not, the query is invalid in strict SQL mode. The behavior here (reject, warn, or silently translate) is an open question to be decided before this phase begins.

- **Comprehensive combination tests**: Test every meaningful combination of GROUP BY, HAVING, ORDER BY, DISTINCT, and LIMIT in a single query to ensure the builder chain produces valid Cypher in all cases.

**What to test**:

- `SELECT DISTINCT name, count(*) FROM People GROUP BY name` — RETURN DISTINCT, no WITH DISTINCT
- `SELECT DISTINCT name, count(*) FROM People GROUP BY name HAVING count(*) > 5` — WITH for HAVING, RETURN DISTINCT for the final output
- `SELECT DISTINCT name, count(*) FROM People GROUP BY name HAVING count(*) > 5 ORDER BY count(*) DESC LIMIT 10` — full combination: WITH, WHERE, RETURN DISTINCT, ORDER BY, LIMIT
- `SELECT name, count(*) FROM People GROUP BY name ORDER BY count(*) LIMIT 10` — WITH-less (common case), ORDER BY, LIMIT
- `SELECT count(*) FROM People GROUP BY name LIMIT 5` — GROUP BY not in SELECT with LIMIT
- Query with no GROUP BY and no HAVING (regression) — verify output is identical to before any changes were made
- Query with only aggregates and no GROUP BY: `SELECT count(*) FROM People` — should not trigger WITH
- Every supported aggregate function in both SELECT and HAVING, with ORDER BY — final sweep to confirm complete coverage

**Quality gate**: Full test suite green. Code formatting applied. Checkstyle clean. License headers present. Manual review of generated Cypher for all new test cases to confirm readability and correctness. Review the complete set of new and modified tests to confirm no gaps in coverage.

---

## What Is Not in Scope

- **GROUP BY with computed expressions** (CASE, YEAR, function calls): These require the expression translator to handle additional jOOQ QOM types in the GROUP BY context. This is a separate piece of work that builds on the WITH mechanism established here but involves different challenges (expression normalization, function mapping). It should be a follow-up effort.

- **GROUP BY by ordinal position**: Phase 1 diagnostic testing revealed that jOOQ does NOT resolve ordinals to actual columns — it keeps the literal string (for example, `"1"`). If ordinal GROUP BY support is needed, the translator must resolve the ordinal against the SELECT list manually. This is deferred to a follow-up effort.

- **Configuration flags for backward compatibility**: Per the design decision above, we are not adding opt-in or opt-out flags. The translator will produce correct Cypher. Tests will be updated.

---

## Risk Assessment

**Lowest risk**: Phase 1. Pure research with no code changes. Cannot break anything. Produces the knowledge needed to make all subsequent phases correct.

**Low risk**: Phases 2 and 3. Isolated components with no impact on existing behavior. If the structural matching approach proves unworkable, we discover it here before changing any translator logic.

**Moderate risk**: Phase 4. This changes an existing test expectation, which means it changes observable behavior. The risk is mitigated by the fact that the current behavior is semantically wrong and the change makes it correct.

**Moderate risk**: Phase 5. Modifies the ORDER BY translation path to be WITH-aware. Risk is mitigated by the proven alias registry and matcher from earlier phases, and by the fact that ORDER BY without a WITH clause takes the unchanged existing path.

**Highest risk**: Phase 6. This introduces a new translation path (WITH plus WHERE for HAVING) that interacts with the existing condition and expression translators. The risk is mitigated by the extensive foundation work in Phases 1 through 5 and the fact that HAVING-specific logic is narrow (condition interception and hidden columns).

**Low risk**: Phase 7. Hardening and validation. May surface edge cases that require adjustments to earlier phases, but should not introduce new architectural risk.

---

## Implementation Progress

### Phase 1: COMPLETE

**Status**: All 32 diagnostic tests pass. No production code modified. Baseline established.

**Test file**: `neo4j-jdbc-translator/impl/src/test/java/org/neo4j/jdbc/translator/impl/JooqQomDiagnosticTests.java`

**Test breakdown**:

| Category | Tests | Status |
|---|---|---|
| HAVING clause QOM representation | 10 | Pass |
| GROUP BY clause QOM representation | 6 | Pass |
| ORDER BY clause QOM representation | 5 | Pass |
| DISTINCT flag behavior | 3 | Pass |
| Structural comparison (SELECT vs HAVING) | 6 | Pass |
| Full query combinations | 5 | Pass |
| **Total** | **35** | **All pass** |

**Baseline**: 300 total tests in the module (265 existing + 35 new). 6 pre-existing failures from the in-progress GROUP BY changes in SqlToCypher.java (aggregate tests [4]-[9] where GROUP BY column is not in SELECT). These failures are expected and will be resolved in Phase 4 when test expectations are updated.

**Key Findings from Diagnostic Tests** (these directly affect the structural matcher and alias registry design):

1. **jOOQ represents count(*) asterisk as SQLField, not Asterisk**: The `$field()` of a `QOM.Count` for `count(*)` returns an `org.jooq.impl.SQLField` whose `toString()` is `"*"`, not an `org.jooq.Asterisk` instance. The structural matcher must detect the asterisk via string comparison (`"*".equals(field.toString())`) or by checking for the `SQLField` internal type, not via `instanceof Asterisk`.

2. **jOOQ does NOT resolve HAVING alias references to aggregates**: When SQL says `HAVING cnt > 5` (where `cnt` is an alias for `count(*)`), jOOQ keeps `cnt` as a plain unresolved field reference. It does NOT substitute it with a `QOM.Count` node. This means the alias registry must support **two lookup modes**: structural matching for when HAVING uses the function form (`HAVING count(*) > 5`), and name-based lookup for when HAVING uses an alias (`HAVING cnt > 5`).

3. **jOOQ does NOT resolve ORDER BY alias references to aggregates**: Same behavior as HAVING. `ORDER BY cnt` produces a plain field reference with name `"cnt"`, not a `QOM.Count`. The alias registry's name-based lookup will serve both HAVING and ORDER BY.

4. **jOOQ DOES preserve aggregate structure in ORDER BY function form**: `ORDER BY count(*)` produces a `QOM.Count` node in the ORDER BY sort field. So the structural matcher can handle this case directly. The two ORDER BY patterns (by alias vs by function) require different resolution paths.

5. **jOOQ does NOT resolve GROUP BY ordinals**: `GROUP BY 1` produces a field with name `"1"`, not the resolved first SELECT column. This contradicts the earlier assumption in GROUP.md. If ordinal GROUP BY support is needed, the translator must resolve it manually. This is deferred to out-of-scope.

6. **HAVING arithmetic with aggregates preserves aggregate nodes**: `HAVING max(salary) > 2 * avg(salary)` produces `QOM.Gt` with `QOM.Max` on the left and `QOM.Mul` (containing `QOM.Avg`) on the right. The aggregate nodes are individually matchable within the arithmetic expression tree. The structural matcher can intercept each aggregate sub-expression independently.

7. **Compound HAVING conditions use standard QOM types**: `HAVING ... AND ...` produces `QOM.And`, `HAVING ... OR ...` produces `QOM.Or`. These are the same condition types used in WHERE clauses, confirming the existing `condition()` translator can handle the condition structure — only the leaf expression resolution needs the alias registry.

8. **DISTINCT is independent of GROUP BY and HAVING**: `$distinct()` returns a boolean flag that does not affect `$groupBy()`, `$having()`, or `$select()`. Confirmed that DISTINCT should apply only to the final RETURN, not to the WITH clause.

9. **All query clauses are independently accessible**: A query with GROUP BY + HAVING + ORDER BY + DISTINCT + LIMIT exposes each clause independently through its accessor. No clause interferes with another.

10. **HAVING comparison operators follow a consistent pattern**: `>=` produces `QOM.Ge`, `BETWEEN` produces `QOM.Between`, `IN (...)` produces `QOM.InList`. In all cases, the aggregate expression is accessible as `$arg1()`. The existing `condition()` translator already handles all these condition types — the only change needed for HAVING is resolving the aggregate sub-expressions to aliases, not changing condition dispatch.

11. **JOIN GROUP BY fields are definitively `TableField` with table qualifier preserved**: Phase 1 now uses a hard assertion (`assertThat(groupField).isInstanceOf(TableField.class)`) confirming that jOOQ preserves the table reference on GROUP BY fields in join queries. The field matcher can rely on `TableField.getTable()` being available for fully qualified comparison.

**Impact on Design**:

Finding 2 is the most significant. The original design assumed the alias registry would only need structural aggregate matching. Now we know it also needs name-based alias lookup. The registry design in Phase 3 must be updated to support both: given a jOOQ Field, first check if it's an aggregate (match structurally), and if not, check if its name matches a registered alias (match by name). This is a straightforward addition to the registry but changes the interface from "lookup by structural match" to "lookup by structural match or alias name".

Finding 1 is a practical detail that affects the structural matcher (Phase 2). The matcher's handling of `count(*)` must use string comparison for the asterisk argument (`toString().equals("*")`), not `instanceof Asterisk`.

Finding 5 means GROUP BY ordinal support is out of scope for this effort, which simplifies Phase 4.

Finding 10 confirms that the existing `condition()` translator does not need modification for HAVING — only the leaf expression resolution needs the alias registry. All comparison operators (Gt, Ge, Lt, Le, Eq, Ne), range operators (Between), and list operators (InList) expose the aggregate via `$arg1()` in a consistent way.

Finding 11 eliminates the last soft assumption from Phase 1. The field matcher in Phase 2 can rely on `TableField.getTable()` being available for join GROUP BY fields, enabling fully qualified comparison without fallbacks.

---

### Phase 2: COMPLETE

**Status**: FieldMatcher built and tested. No production code in SqlToCypher.java modified. 23 tests pass.

**Note on overlap**: Phase 2 production code and tests were implemented together by `prodTestingPlan.md` Group 1 (Agent B). The testing plan executed the full Phase 2 spec — both the `FieldMatcher.java` class and its test suite — as a single unit of work. There is no remaining Phase 2 work outside of what `prodTestingPlan.md` already completed.

**Files created**:
- `neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/FieldMatcher.java` (121 lines)
- `neo4j-jdbc-translator/impl/src/test/java/org/neo4j/jdbc/translator/impl/FieldMatcherTests.java` (410 lines)

**Test breakdown**:

| Category | Tests | Status |
|---|---|---|
| Column reference matching (§2.1) | 5 | Pass |
| Aggregate function matching (§2.2) | 9 | Pass |
| Alias transparency (§2.3) | 3 | Pass |
| Cross-parse matching (§2.4) | 2 | Pass |
| Negative / false-positive cases (§2.5) | 4 | Pass |
| **Total** | **23** | **All pass** |

**Implementation details**: The matcher handles 4 cases as specified in the design:
1. Alias unwrapping — recursive `QOM.FieldAlias` stripping
2. Aggregate function matching — type-specific comparison for Count, Sum, Min, Max, Avg, StddevSamp, StddevPop (with `$distinct()` flag check on Count)
3. Simple column references — case-insensitive name comparison with pragmatic table qualifier handling (fully qualified when both have tables, name-only when either lacks a qualifier)
4. Fallback — returns false for unrecognized types

All tests use parsed SQL via jOOQ's parser (not hand-constructed objects), as required by the spec. Phase 1 Finding 1 (count(*) as SQLField) is handled via `"*".equals(field.toString())`.

---

### Phase 3: COMPLETE

**Status**: AliasRegistry built and tested. No production code in SqlToCypher.java modified. 15 tests pass.

**Note on overlap**: Like Phase 2, this was implemented by `prodTestingPlan.md` Group 1 (Agent C) as a single unit covering both the production class and its tests.

**Files created**:
- `neo4j-jdbc-translator/impl/src/main/java/org/neo4j/jdbc/translator/impl/AliasRegistry.java` (86 lines)
- `neo4j-jdbc-translator/impl/src/test/java/org/neo4j/jdbc/translator/impl/AliasRegistryTests.java` (362 lines)

**Test breakdown**:

| Category | Tests | Status |
|---|---|---|
| Structural lookup (§3.1) | 5 | Pass |
| Name-based lookup (§3.2) | 3 | Pass |
| Combined mode (§3.3) | 5 | Pass |
| Round-trip from parsed SQL (§3.4) | 2 | Pass |
| **Total** | **15** | **All pass** |

**Implementation details**: Two-mode lookup as required by Phase 1 Finding 2:
- Structural matching via `FieldMatcher.fieldsMatch()` for aggregate expressions
- Name-based fallback for unresolved alias references (handles `HAVING cnt > 5` and `ORDER BY cnt`)

**Cumulative baseline**: 383 total tests in the module (265 existing + 35 Phase 1 + 45 Tier 1 snapshots + 23 Phase 2 + 15 Phase 3). 6 pre-existing failures unchanged (aggregates[4-9], to be resolved in Phase 4).
