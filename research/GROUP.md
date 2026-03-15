# Proposal: HAVING Support and GROUP BY Validation in the SQL-to-Cypher Translator

## Background: How GROUP BY Maps to Cypher

Cypher has no `GROUP BY` clause. Grouping is implicit: whenever an aggregation function like `count()`, `sum()`, or `avg()` appears in a `RETURN` clause, Cypher automatically groups the result set by every non-aggregated expression in that same `RETURN`. For example:

```
SQL:    SELECT name, count(*) FROM People GROUP BY name
Cypher: MATCH (p:People) RETURN p.name, count(*)
```

Both produce the same result. The SQL version explicitly declares `name` as the grouping key; the Cypher version infers it because `p.name` is the only non-aggregated return column. This extends naturally to multiple grouping columns and multiple aggregates:

```
SQL:    SELECT department, location, count(*), avg(salary), max(salary)
        FROM Employees
        GROUP BY department, location

Cypher: MATCH (e:Employee)
        RETURN e.department, e.location, count(*), avg(e.salary), max(e.salary)
```

Cypher sees two non-aggregated expressions (`e.department`, `e.location`) and three aggregates, and groups accordingly — exactly what the SQL GROUP BY declared.

The same holds for joins. A query that groups across a relationship:

```
SQL:    SELECT c.country, p.category, sum(o.amount), count(o.id)
        FROM Customers c
        JOIN Orders o ON c.id = o.customer_id
        JOIN Products p ON o.product_id = p.id
        GROUP BY c.country, p.category

Cypher: MATCH (c:Customer)-[:PLACED]->(o:Order)-[:CONTAINS]->(p:Product)
        RETURN c.country, p.category, sum(o.amount), count(o.id)
```

Again, the GROUP BY is redundant from Cypher's perspective. The non-aggregated return columns define the grouping.

This means that in most real-world GROUP BY queries, the SQL GROUP BY clause is effectively redundant information. The grouping columns appear in SELECT, the aggregates appear in SELECT, and Cypher determines the grouping from that structure alone.

## What the Translator Currently Does

The translator parses SQL using jOOQ's Query Object Model. It reads `$select()`, `$from()`, `$where()`, `$orderBy()`, `$limit()`, and `$distinct()` from the AST. It does not read `$groupBy()` or `$having()`. The GROUP BY clause is not extracted from the AST.

Aggregate functions themselves are fully supported — `count()`, `sum()`, `min()`, `max()`, `avg()`, `stDev()`, and `stDevP()` all have explicit handlers that produce the corresponding Cypher function calls.

Because Cypher's implicit grouping semantics align with what GROUP BY declares, the translator already produces correct results for the standard case: queries where the SELECT list contains some plain columns and some aggregate functions, and the GROUP BY lists those same plain columns. This is not an accident or a workaround — it is a natural consequence of how Cypher was designed. The existing test suite confirms correct behavior for queries like these:

```
SQL:    SELECT name, count(*) FROM People GROUP BY name
Cypher: MATCH (p:People) RETURN p.name AS name, count(*)

SQL:    SELECT name, max(age) FROM People GROUP BY name
Cypher: MATCH (p:People) RETURN p.name AS name, max(p.age)

SQL:    SELECT c.City, COUNT(*)
        FROM Orders o
        INNER JOIN Customers c ON o.CustomerID = c.CustomerID
        GROUP BY c.City
Cypher: MATCH (o:Order)<-[purchased:PURCHASED]-(c:Customer)
        RETURN c.City, count(*)
```

In short, GROUP BY support largely works today because Cypher makes it unnecessary to translate the clause explicitly. The translator does not need to do anything with GROUP BY for the common case because the language handles it.

## What Is Actually Missing

The gaps are specific and narrow:

### 1. HAVING Clauses Are Completely Ignored

This is the primary gap. HAVING filters are silently dropped, so post-aggregation conditions never make it into the translated Cypher. Every row group is returned regardless of whether it satisfies the HAVING predicate.

Simple case — filter on a single aggregate:

```
SQL:    SELECT department, count(*) AS cnt
        FROM Employees
        GROUP BY department
        HAVING count(*) > 10
```

Today this translates as if the HAVING were not there. The correct Cypher would use a WITH clause to perform the aggregation, filter with WHERE, then project the final result:

```
Cypher: MATCH (e:Employee)
        WITH e.department AS department, count(*) AS cnt
        WHERE cnt > 10
        RETURN department, cnt
```

More complex case — multiple HAVING conditions with different aggregates:

```
SQL:    SELECT c.country, count(o.id) AS order_count, sum(o.amount) AS total
        FROM Customers c
        JOIN Orders o ON c.id = o.customer_id
        GROUP BY c.country
        HAVING count(o.id) >= 100 AND sum(o.amount) > 50000

Cypher: MATCH (c:Customer)-[:PLACED]->(o:Order)
        WITH c.country AS country, count(o.id) AS order_count, sum(o.amount) AS total
        WHERE order_count >= 100 AND total > 50000
        RETURN country, order_count, total
```

HAVING with a subexpression — comparing aggregates to each other:

```
SQL:    SELECT department, avg(salary) AS avg_sal, max(salary) AS max_sal
        FROM Employees
        GROUP BY department
        HAVING max(salary) > 2 * avg(salary)

Cypher: MATCH (e:Employee)
        WITH e.department AS department, avg(e.salary) AS avg_sal, max(e.salary) AS max_sal
        WHERE max_sal > 2 * avg_sal
        RETURN department, avg_sal, max_sal
```

### 2. GROUP BY Expressions That Differ from SELECT Expressions

SQL allows grouping by an expression that does not appear verbatim in the SELECT list, or grouping by ordinal position. These are uncommon but valid SQL patterns, and they can all be translated to Cypher using a WITH clause.

Grouping column not in SELECT — only the aggregate is returned:

```
SQL:    SELECT count(*) FROM Orders GROUP BY customer_id

Cypher: MATCH (o:Order)
        WITH o.customer_id AS cid, count(*) AS cnt
        RETURN cnt
```

The WITH groups by `cid` and computes the count. The final RETURN drops `cid`, matching the SQL SELECT that only asked for the count. Without reading `$groupBy()`, the translator has no way to know that it should group by `customer_id` — it only sees `SELECT count(*)` and would collapse everything into a single total count.

Grouping by a computed expression:

```
SQL:    SELECT YEAR(hire_date) AS yr, count(*), avg(salary)
        FROM Employees
        GROUP BY YEAR(hire_date)

Cypher: MATCH (e:Employee)
        WITH date(e.hire_date).year AS yr, count(*) AS cnt, avg(e.salary) AS avg_sal
        RETURN yr, cnt, avg_sal
```

Grouping by a CASE expression:

```
SQL:    SELECT CASE WHEN age < 30 THEN 'junior'
                    WHEN age < 50 THEN 'mid'
                    ELSE 'senior' END AS bracket,
               count(*), avg(salary)
        FROM Employees
        GROUP BY CASE WHEN age < 30 THEN 'junior'
                      WHEN age < 50 THEN 'mid'
                      ELSE 'senior' END

Cypher: MATCH (e:Employee)
        WITH CASE WHEN e.age < 30 THEN 'junior'
                  WHEN e.age < 50 THEN 'mid'
                  ELSE 'senior' END AS bracket,
             count(*) AS cnt, avg(e.salary) AS avg_sal
        RETURN bracket, cnt, avg_sal
```

Grouping by ordinal position:

```
SQL:    SELECT name, count(*) FROM People GROUP BY 1
```

This is syntactic sugar — `1` refers to the first SELECT column (`name`). Once the jOOQ parser resolves the ordinal to the actual column, it becomes the standard case.

All of these patterns share the same translation strategy: use a WITH clause that includes the grouping expression (even if it is not in the final SELECT), perform aggregation there via Cypher's implicit grouping, then use a final RETURN to project only the columns the SQL SELECT requested. This is the same WITH-based approach needed for HAVING, so both gaps are addressed by the same mechanism.

### 3. No Validation of Grouping Correctness

If a user writes SQL that selects a non-aggregated column without including it in GROUP BY, the translator cannot warn about it. Consider:

```
SQL:    SELECT department, name, count(*)
        FROM Employees
        GROUP BY department
```

This is invalid SQL in strict mode — `name` is not aggregated and not in the GROUP BY. Some databases (MySQL in permissive mode) allow it and return an arbitrary value for `name`. Cypher would group by both `department` and `name`, giving a different (and likely unexpected) result set: one row per unique department-name pair rather than one row per department.

Without reading the GROUP BY list, the translator has no way to detect this mismatch and either reject the query or warn the user.

## Proposed Approach

The work breaks into two phases.

### Phase 1: Extract and Validate GROUP BY

Read the `$groupBy()` list from the jOOQ AST during SELECT translation. Use it to:

- Identify which SELECT expressions are grouping keys and which are aggregates.
- Validate that non-aggregated SELECT expressions appear in the GROUP BY list. If they do not, either reject the query or emit a warning, depending on configuration.
- For the common case where the GROUP BY columns match a subset of the SELECT columns and the rest are aggregates, continue generating the same Cypher as today (a simple MATCH ... RETURN). The implicit grouping is correct and adding an unnecessary WITH clause would complicate the output for no benefit.

This phase adds no new Cypher constructs. It makes the existing behavior explicit and validated rather than unverified.

### Phase 2: WITH-Based Translation for HAVING and Expression GROUP BY

Both HAVING support and expression-based GROUP BY require the same translation mechanism: a WITH clause that sits between the MATCH and the final RETURN. This phase introduces that mechanism and uses it for both cases.

Read the `$having()` condition and the `$groupBy()` expressions from the jOOQ AST. When a query needs a WITH clause — because it has a HAVING condition, or because the GROUP BY contains expressions not in the SELECT list — generate Cypher in two stages:

- The WITH clause includes all grouping expressions and all aggregate expressions, triggering Cypher's implicit grouping.
- If there is a HAVING condition, a WHERE clause after the WITH filters on the aggregated values.
- The final RETURN projects only the columns that appeared in the original SQL SELECT.

When neither HAVING nor expression-based grouping is present, the translator continues to produce a simple MATCH ... RETURN as it does today.

For example, a query that combines a computed grouping expression with a HAVING filter:

```
SQL:    SELECT YEAR(order_date) AS yr, count(*) AS cnt, sum(amount) AS total
        FROM Orders
        GROUP BY YEAR(order_date)
        HAVING sum(amount) > 10000

Cypher: MATCH (o:Order)
        WITH date(o.order_date).year AS yr, count(*) AS cnt, sum(o.amount) AS total
        WHERE total > 10000
        RETURN yr, cnt, total
```

The main complexity is in the expression mapping: HAVING conditions can reference aggregates by their function call form (`HAVING count(*) > 5`) or by alias (`HAVING cnt > 5`), and the translator needs to resolve these to the aliases established in the WITH clause.

## Estimated Difficulty

**Phase 1 (GROUP BY extraction and validation): Low.** The jOOQ AST already exposes `$groupBy()`. The main work is wiring it into the existing SELECT translation path and adding validation logic. The Cypher output does not change for well-formed queries. This is primarily plumbing and test coverage.

**Phase 2 (WITH-based translation for HAVING and expression GROUP BY): Moderate.** Generating WITH clauses is a new pattern for the SELECT path. The translator needs to decide when a WITH is necessary (HAVING present, or grouping expressions differ from SELECT), build the intermediate projection, alias aggregates, resolve HAVING references, and then project the final RETURN. The Cypher-DSL library supports all of these constructs, so the rendering side is straightforward — the complexity is in the decision logic and expression rewriting. Edge cases around nested aggregates, aliases referencing other aliases, and mixed HAVING conditions will need careful handling and thorough test coverage.

**Overall: Low to moderate.** Neither phase requires architectural changes to the translator. The core expression-handling infrastructure already translates all the relevant aggregate functions and expressions. The work is additive — extending the SELECT translation to read two additional AST nodes and, for HAVING, introducing WITH-based two-stage output.

---

## Implementation Progress

### Status: In Progress — Core WITH-clause logic written, needs compilation and test validation

### What Has Been Implemented

Changes are in `SqlToCypher.java` (`neo4j-jdbc-translator/impl`):

**New imports added:**
- `org.jooq.GroupField` — the type returned by `$groupBy()` list entries
- `org.neo4j.cypherdsl.core.AliasedExpression` — needed to extract alias names from expressions in the WITH clause

**Modified method — `statement(Select<?>)`:**
The core SELECT translation method now reads `$groupBy()` and `$having()` from the jOOQ AST. It decides whether a WITH clause is needed via two checks:
1. Is `$having()` non-null?
2. Does `$groupBy()` contain fields not present in `$select()`?

If neither applies, the method follows the original code path — a direct `MATCH ... RETURN`. If either applies, it delegates to `buildWithClause()` to produce a `MATCH ... WITH ... WHERE ... RETURN` chain.

**New method — `requiresWithForGroupBy(Select<?>)`:**
Compares the GROUP BY field names against the SELECT field names (case-insensitive). Returns `true` when any GROUP BY field is absent from SELECT, which means Cypher's implicit grouping would not produce the right result without an intermediate WITH.

**New method — `resolveFieldName(Object)`:**
Extracts the canonical uppercase field name from a jOOQ field or alias. Used by `requiresWithForGroupBy` to compare GROUP BY and SELECT lists.

**New method — `buildWithClause(OngoingReading, Select<?>, List<GroupField>, Condition)`:**
Builds the WITH projection:
1. Translates each SELECT field and aliases it. If the field already has an alias (from SQL `AS`), that alias is preserved. Otherwise a synthetic alias (`__with_col_N`) is generated.
2. Adds any GROUP BY fields not already covered by the SELECT list (with synthetic aliases `__group_col_N`). These are included in the WITH to trigger correct implicit grouping but are excluded from the final RETURN.
3. Calls `reading.with(withExpressions)` to build the WITH clause.
4. If HAVING is present, applies `.where()` after the WITH.
5. Returns a `WithClauseResult` containing the new `OngoingReading` and a supplier for the final RETURN expressions (which reference the WITH aliases via `Cypher.name(alias)`).

**New record — `WithClauseResult`:**
Simple holder for the reading state after the WITH clause and the return expression supplier.

**New method — `havingCondition(Condition, List<IdentifiableElement>)`:**
Currently delegates directly to the existing `condition()` method. The existing condition translator already handles all the QOM condition types (`Gt`, `Lt`, `And`, `Or`, etc.) and the aggregate expression handlers (`Count`, `Sum`, etc.) that appear in HAVING clauses.

### Learnings and Challenges

**jOOQ `$groupBy()` API confirmed:**
- Returns `List<? extends GroupField>` where each entry is a `TableFieldImpl` (which is a `Field<?>`)
- Empty list when no GROUP BY is present
- jOOQ resolves ordinal GROUP BY (`GROUP BY 1`) to the actual column before exposing it through the QOM — the translator does not need to handle ordinals

**jOOQ `$having()` API confirmed:**
- Returns a standard `org.jooq.Condition` (nullable), using the same QOM types as WHERE (`QOM.Gt`, `QOM.And`, etc.)
- Aggregate functions inside HAVING appear as the same QOM types used in SELECT (`QOM.Count`, `QOM.Sum`, etc.)
- This means the existing `condition()` and `expression()` methods can handle HAVING translation without modification

**Cypher-DSL WITH builder chain confirmed:**
- `OngoingReading` extends `ExposesWith`, which provides `.with(IdentifiableElement...)` and `.with(Collection<IdentifiableElement>)`
- `.with()` returns `OrderableOngoingReadingAndWithWithoutWhere`, which has `.where(Condition)` for the HAVING filter
- Both `OrderableOngoingReadingAndWithWithoutWhere` and `OrderableOngoingReadingAndWithWithWhere` extend `OngoingReadingAndWith` → `OngoingReading`, so `.returning()` is available at either point
- `AliasedExpression` (returned by `expression.as("alias")`) implements both `Expression` and `IdentifiableElement`, so it can be passed directly to `.with()`

**Key design decision — when to emit WITH:**
The translator avoids emitting unnecessary WITH clauses. For the common case (GROUP BY columns match SELECT columns, no HAVING), the original `MATCH ... RETURN` output is preserved. WITH is only introduced when it is semantically necessary. This keeps the generated Cypher simple and readable for the majority of queries.

**Challenge — HAVING condition alias resolution:**
The HAVING condition in jOOQ references the original aggregate expressions (e.g., `count(*)`) rather than the aliases assigned in the WITH clause. The existing `condition()` method translates these to Cypher aggregate function calls (e.g., `Cypher.count(Cypher.asterisk())`). This works because Cypher allows referencing aggregate results by their expression form in a WHERE after WITH — but it would be cleaner and more reliable to reference them by alias. This is an area for potential refinement.

**Challenge — project code quality standards:**
- No `System.out.println` allowed (Checkstyle enforces `Regexp` rule against it)
- Spring JavaFormat must be applied (`./mvnw spring-javaformat:apply`)
- License headers required on all files
- `@author` javadoc required on public classes
- Tests must follow naming convention: `*Tests.java` for unit tests

### Current State

The core implementation has been written in `SqlToCypher.java` but has not yet been compiled or tested. The next step is to compile, run existing tests to confirm no regressions, and then add new test cases.

### Next Steps

1. **Compile and verify** — Run the existing test suite (`SqlToCypherTests`) to confirm the changes do not break any existing GROUP BY or aggregate translations. The design preserves the original code path for the common case, so existing tests should pass unchanged.

2. **Add HAVING test cases** — Add parameterized tests for:
   - Simple HAVING with single aggregate condition
   - Compound HAVING with AND/OR
   - HAVING referencing aliased aggregates

3. **Add GROUP BY expression tests** — Add parameterized tests for:
   - GROUP BY column not in SELECT
   - Multiple GROUP BY with partial SELECT overlap

4. **Apply code quality** — Run `spring-javaformat:apply`, `checkstyle`, `license:format`, and `sortpom:sort` to ensure compliance.

5. **Validate HAVING condition alias resolution** — Test whether the current approach (re-translating aggregate expressions in HAVING) produces correct Cypher, or whether alias-based references are needed. Adjust `havingCondition()` if necessary.
