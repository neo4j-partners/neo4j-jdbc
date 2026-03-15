# Production Testing Strategy for GROUP BY / HAVING Implementation

## Purpose

This document defines the additional tests needed to safely implement GROUP BY and HAVING support (Phases 2-7 of `group phase 4.md`) without breaking any existing behavior in this production JDBC driver.

Phase 1 (diagnostic tests in `JooqQomDiagnosticTests.java`, 32 tests) is complete. The tests below cover everything from Phase 2 onward, organized by risk tier.

---

## Current Test Baseline

| Area | File | Tests | Notes |
|------|------|-------|-------|
| SQL-to-Cypher unit | `SqlToCypherTests.java` | ~265 | Aggregates, JOINs, DML, DISTINCT, CASE, ORDER BY, LIMIT |
| jOOQ QOM diagnostics | `JooqQomDiagnosticTests.java` | 32 | Phase 1 complete — QOM structure validation only |
| Generated queries | `GeneratedQueriesTests.java` | ~6 | Tableau-style MIN/MAX/STRPOS patterns |
| Config | `SqlToCypherConfigTests.java` | ~10 | Dialect selection, enum handling |
| Integration (translation) | `TranslationIT.java` | ~40 | Real Neo4j via Testcontainers |
| Integration (CBV) | `CypherBackedViewsIT.java` | ~10 | Cypher-backed views with GROUP BY |
| Integration (statement) | `StatementIT.java` | ~30 | Includes supportsGroupByUnrelated, supportsGroupByBeyondSelect |

**Known pre-existing failures**: 6 aggregate tests ([4]-[9] in `SqlToCypherTests.aggregates`) where GROUP BY column is not in SELECT. These are expected — current output is semantically wrong and will be fixed in Phase 4.

---

## Existing Infrastructure

### Testcontainers Setup (Already Wired)

The integration test infrastructure is fully operational. No new container setup is needed.

**Base class**: `IntegrationTestBase` in `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/`

- Uses `@Testcontainers(disabledWithoutDocker = true)` and `@TestInstance(PER_CLASS)`
- Container reuse enabled (`withReuse(true)`) for fast test runs
- `@BeforeEach` auto-cleans the database: `MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 1000 ROWS`
- Connections created via `getConnection(translate, rewriteBatchedStatements)` — pass `true` for first argument to enable SQL translation
- Translator-impl module is already a test dependency in the IT-CP pom

**Adding a new integration test is trivial:**

```java
class GroupByHavingIT extends IntegrationTestBase {
    @Test
    void myTest() throws SQLException {
        // Seed data via Cypher
        try (var c = getConnection(); var s = c.createStatement()) {
            s.execute("CREATE (:Person {name: 'Alice', age: 30})");
        }
        // Query via SQL translation
        try (var c = getConnection(true, false); var s = c.createStatement()) {
            var rs = s.executeQuery("SELECT name, count(*) FROM Person GROUP BY name");
            assertThat(rs.next()).isTrue();
            // ...
        }
    }
}
```

**Available test data**: Movies graph (38 movies, 100+ people, ACTED_IN/DIRECTED/PRODUCED/WROTE relationships, properties: `name`, `born`, `title`, `released`, `tagline`, `roles`). Loaded via `TestUtils.createMovieGraph(connection)`.

---

## Tier 1: Regression Safety Net (Run Before ANY Code Change)

These tests lock down existing behavior that must NOT change. They should be written and green before Phase 2 begins. If any of these break during implementation, stop and investigate.

### 1.1 How the Snapshot Works

The snapshot uses the same `@ParameterizedTest @CsvSource` pattern already established in `SqlToCypherTests.java` (see lines 551-568 for the existing aggregate tests). Each entry is a `SQL|expected Cypher` pair. The expected Cypher string IS the snapshot — it's a character-for-character record of what the translator produces today.

**Mechanism**:

1. **Capture**: Before any code changes, run each SQL through `SqlToCypher.defaultTranslator().translate(sql)` and record the exact output string as the expected value in the CsvSource.
2. **Lock**: The test asserts `assertThat(translator.translate(sql)).isEqualTo(expectedCypher)`. Any behavioral change — even whitespace — fails the test.
3. **Document**: Each CsvSource entry is self-documenting. The SQL input and expected Cypher output are both visible in the test source. No external files, no generated snapshots, no tooling to maintain.

**Where it lives**: New `@ParameterizedTest` methods in `SqlToCypherTests.java`, one per category below. Each method uses `delimiterString = "|"` and `textBlock` for readability, exactly like the existing `aggregates()` test.

**What to do if a snapshot test breaks during implementation**: Stop. Read the diff between old and new output. If the change is intentional (e.g., Phase 4 fixes the wrong GROUP BY output), update the expected value and add a comment explaining why. If the change is unintentional, you've caught a regression — fix it before proceeding.

### 1.2 Snapshot Categories

Each category becomes its own `@ParameterizedTest` method:

```
Category: snapshotSimpleSelects (no GROUP BY, no aggregates)
- SELECT * FROM People p
- SELECT name FROM People p
- SELECT name, age FROM People p WHERE age > 25
- SELECT name FROM People p ORDER BY name
- SELECT name FROM People p ORDER BY name DESC LIMIT 10
- SELECT DISTINCT name FROM People p
- SELECT name FROM People p WHERE name = 'Alice' ORDER BY age LIMIT 5
```

```
Category: snapshotGlobalAggregates (aggregates WITHOUT GROUP BY — must still produce simple RETURN)
- SELECT count(*) FROM People p
- SELECT count(name) FROM People p
- SELECT count(DISTINCT name) FROM People p
- SELECT sum(age) FROM People p
- SELECT min(age), max(age) FROM People p
- SELECT avg(age) FROM People p
```

```
Category: snapshotGroupByMatchingSelect (GROUP BY columns all in SELECT — should NOT produce WITH)
- SELECT name, count(*) FROM People p GROUP BY name
- SELECT name, max(age) FROM People p GROUP BY name
- SELECT name, min(age) FROM People p GROUP BY name
```

```
Category: snapshotJoins (must remain unchanged)
- SELECT p.name, m.title FROM People p JOIN Movie m ON p.id = m.person_id
- SELECT * FROM People p NATURAL JOIN Movie m
- SELECT p.name FROM People p JOIN Movie m ON p.id = m.person_id WHERE m.released > 2000
- SELECT p.name FROM People p JOIN Movie m ON p.id = m.person_id ORDER BY m.released LIMIT 5
```

```
Category: snapshotDml (must remain completely unchanged)
- INSERT INTO People (name, age) VALUES ('Alice', 30)
- UPDATE People SET age = 31 WHERE name = 'Alice'
- DELETE FROM People WHERE name = 'Alice'
- INSERT INTO Movie(title) VALUES(?) ON DUPLICATE KEY IGNORE
```

```
Category: snapshotPredicates (WHERE clause expressions, must remain unchanged)
- SELECT name FROM People p WHERE age = 25
- SELECT name FROM People p WHERE age > 25
- SELECT name FROM People p WHERE age >= 25 AND name = 'Alice'
- SELECT name FROM People p WHERE age IN (25, 30, 35)
- SELECT name FROM People p WHERE age IS NULL
- SELECT name FROM People p WHERE age IS NOT NULL
- SELECT name FROM People p WHERE age BETWEEN 20 AND 30
- SELECT name FROM People p WHERE name LIKE 'A%'
- SELECT name FROM People p WHERE NOT (age > 50)
```

```
Category: snapshotCaseExpressions
- SELECT CASE WHEN age > 30 THEN 'old' ELSE 'young' END FROM People p
```

```
Category: snapshotDistinct
- SELECT DISTINCT name FROM People p
- SELECT DISTINCT name, age FROM People p WHERE age > 25
```

### 1.3 Non-GROUP-BY Path Isolation Test

A dedicated test that verifies the code path for queries without GROUP BY is completely unchanged:

- `requiresWithForGroupBy()` returns false for queries with no GROUP BY
- `requiresWithForGroupBy()` returns false for queries with GROUP BY where all columns appear in SELECT
- No WITH clause appears in the generated Cypher for these cases
- The output is character-for-character identical to the output before any changes

---

## Tier 2: Structural Matcher Tests (Phase 2)

Isolated unit tests for the field comparison component. These test the matcher in isolation — no SQL parsing, no Cypher generation.

### 2.1 Column Reference Matching

```
Match:     TableField("People", "name") vs TableField("People", "name")         → true
No match:  TableField("People", "name") vs TableField("People", "age")          → false
No match:  TableField("People", "name") vs TableField("Orders", "name")         → false
Match:     Field("name") vs TableField("People", "name")                        → true (unqualified matches qualified)
Match:     Case-insensitive: Field("NAME") vs Field("name")                     → true
```

### 2.2 Aggregate Function Matching

```
Match:     count(*) vs count(*)                                                 → true
No match:  count(*) vs sum(age)                                                 → false
No match:  count(name) vs count(age)                                            → false
Match:     sum(age) vs sum(age)                                                 → true
Match:     min(salary) vs min(salary)                                           → true
Match:     max(age) vs max(age)                                                 → true
Match:     avg(score) vs avg(score)                                             → true
No match:  count(name) vs count(DISTINCT name)                                  → false
Match:     count(DISTINCT name) vs count(DISTINCT name)                         → true
```

### 2.3 Alias Transparency

```
Match:     count(*) AS cnt vs count(*)                                          → true (alias is presentation, not identity)
Match:     sum(age) AS total vs sum(age)                                        → true
Match:     Field("name") AS n vs Field("name")                                  → true
```

### 2.4 Cross-Parse Matching (Critical for Real-World Use)

These tests parse actual SQL and extract fields from different clauses, then compare them structurally:

```
Parse "SELECT count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5"
- Extract count(*) from $select()[0]
- Extract count(*) from $having() → $arg1() (left side of comparison)
- Structural match: should be true

Parse "SELECT name, sum(age) AS total FROM People GROUP BY name ORDER BY sum(age)"
- Extract sum(age) from $select()[1]
- Extract sum(age) from $orderBy()[0].$field()
- Structural match: should be true
```

### 2.5 Negative Cases (Ensure No False Positives)

```
No match: count(*) vs count(name)         — different argument
No match: sum(age) vs avg(age)            — different function
No match: min(x) vs max(x)               — different function, same argument
No match: count(x) vs count(DISTINCT x)  — DISTINCT changes semantics
```

---

## Tier 3: Alias Registry Tests (Phase 3)

### 3.1 Structural Lookup

```
Register count(*) → "cnt"       | Lookup count(*)       → "cnt"
Register sum(age) → "total"     | Lookup sum(age)       → "total"
Register sum(age) → "total"     | Lookup sum(salary)    → null (different column)
Register count(*), sum(age), max(age) with distinct aliases | Lookup each → correct alias
Lookup unregistered field                                   → null
```

### 3.2 Name-Based Lookup (Finding 2 — HAVING/ORDER BY by alias)

```
Register count(*) → "cnt"       | Lookup Field("cnt")        → "cnt"
Register sum(age) → "total"     | Lookup Field("total")      → "total"
Lookup Field("unknown")                                       → null
```

### 3.3 Combined Mode (Both Lookup Paths in One Registry)

```
Register count(*) → "cnt", name → "name"
- Lookup count(*) structurally                → "cnt"
- Lookup Field("cnt") by name                 → "cnt"
- Lookup Field("name") by name                → "name"
- Lookup sum(age) → null (not registered)
- Lookup Field("bogus") → null
```

### 3.4 Round-Trip from Parsed SQL

```
Parse "SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5 ORDER BY cnt"
- Register from $select(): name → alias, count(*) → "cnt"
- Lookup from $having(): count(*) → "cnt" (structural match)
- Lookup from $orderBy(): Field("cnt") → "cnt" (name-based match)
```

---

## Tier 4: WITH Clause Generation Tests (Phase 4)

These test the actual Cypher output when GROUP BY columns differ from SELECT columns.

### 4.1 Core WITH Generation

```sql
-- GROUP BY column not in SELECT → must produce WITH
SELECT sum(age) FROM People p GROUP BY name
-- Expected: MATCH (p:People) WITH p.name AS __group_col_0, sum(p.age) AS __with_col_0 RETURN __with_col_0

-- Multiple GROUP BY columns, one not in SELECT
SELECT name, sum(age) FROM People p GROUP BY name, department
-- Expected: WITH clause includes department as hidden group column, RETURN excludes it

-- All GROUP BY columns in SELECT → should NOT produce WITH
SELECT name, count(*) FROM People p GROUP BY name
-- Expected: MATCH (p:People) RETURN p.name AS name, count(*) (no WITH)
```

### 4.2 JOIN Queries with GROUP BY

```sql
-- Join query with GROUP BY — fully qualified column comparison must avoid false matches
SELECT c.name, count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name
-- Verify: no WITH clause (GROUP BY column is in SELECT)

-- Join query where GROUP BY column is not in SELECT
SELECT count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name
-- Verify: WITH clause with c.name as hidden group column
```

### 4.3 Multiple Aggregates

```sql
SELECT name, count(*), sum(age), avg(age) FROM People p GROUP BY name
-- Verify: no WITH (all GROUP BY in SELECT), all aggregates preserved

SELECT count(*), sum(age) FROM People p GROUP BY name
-- Verify: WITH clause, both aggregates aliased, name as hidden group column
```

### 4.4 Updated Expectations for Existing Tests

Explicitly test that lines 556-561 of `SqlToCypherTests.java` now produce correct output:

```
SELECT sum(age) FROM People p GROUP BY name         → WITH-based output (was wrong: single sum)
SELECT avg(age) FROM People p GROUP BY name         → WITH-based output (was wrong: single avg)
SELECT percentileCont(age) FROM People p GROUP BY name → WITH-based output
SELECT percentileDisc(age) FROM People p GROUP BY name → WITH-based output
SELECT stDev(age) FROM People p GROUP BY name       → WITH-based output
SELECT stDevP(age) FROM People p GROUP BY name      → WITH-based output
```

---

## Tier 5: ORDER BY Alias Resolution Tests (Phase 5)

### 5.1 ORDER BY with WITH Clause Present

```sql
-- ORDER BY by alias, WITH clause present
SELECT name, count(*) AS cnt FROM People p GROUP BY name ORDER BY cnt
-- Verify: ORDER BY references the WITH alias, not re-translated expression

-- ORDER BY by aggregate function form, WITH clause present
SELECT name, count(*) FROM People p GROUP BY name ORDER BY count(*)
-- Verify: ORDER BY resolves to WITH alias via structural match

-- ORDER BY by non-aggregate column, WITH clause present
SELECT name, count(*) AS cnt FROM People p GROUP BY name ORDER BY name
-- Verify: ORDER BY resolves to the name alias from WITH

-- ORDER BY with sort direction preserved
SELECT name, count(*) FROM People p GROUP BY name ORDER BY count(*) DESC
-- Verify: DESC preserved after alias resolution
```

### 5.2 ORDER BY Without WITH (Regression)

```sql
-- ORDER BY without GROUP BY — must be completely unchanged
SELECT name FROM People p ORDER BY name
SELECT name FROM People p ORDER BY name DESC
SELECT name, age FROM People p ORDER BY age LIMIT 10

-- ORDER BY with GROUP BY but no WITH needed (all GROUP BY in SELECT)
SELECT name, count(*) FROM People p GROUP BY name ORDER BY name
-- Verify: no WITH clause, ORDER BY works as before
```

### 5.3 ORDER BY + GROUP BY + LIMIT Chain

```sql
SELECT name, count(*) AS cnt FROM People p GROUP BY name ORDER BY cnt DESC LIMIT 10
-- Verify: full chain WITH → RETURN → ORDER BY → LIMIT
```

---

## Tier 6: HAVING Translation Tests (Phase 6)

### 6.1 Simple HAVING

```sql
-- HAVING with aggregate in function form
SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING count(*) > 5
-- Expected: WITH ... WHERE cnt > 5 RETURN ...

-- HAVING without explicit alias in SELECT
SELECT name, count(*) FROM People p GROUP BY name HAVING count(*) > 5
-- Expected: translator assigns internal alias, WHERE references it

-- HAVING by alias (jOOQ keeps as unresolved field)
SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5
-- Expected: same output as function form
```

### 6.2 Compound HAVING

```sql
-- AND
SELECT name, count(*) AS cnt, max(age) AS max_age FROM People p
GROUP BY name HAVING count(*) > 5 AND max(age) > 50
-- Expected: WHERE cnt > 5 AND max_age > 50

-- OR
SELECT name, count(*) AS cnt, min(age) AS min_age FROM People p
GROUP BY name HAVING count(*) > 2 OR min(age) < 18

-- Nested: AND + OR
SELECT name, count(*) AS cnt, sum(age) AS total, avg(age) AS average FROM People p
GROUP BY name HAVING count(*) > 10 AND (sum(age) < 100 OR avg(age) > 50)
```

### 6.3 Arithmetic in HAVING

```sql
SELECT name, max(salary) AS max_sal, avg(salary) AS avg_sal FROM Employees e
GROUP BY name HAVING max(salary) > 2 * avg(salary)
-- Verify: both aggregates resolved to aliases, arithmetic preserved
-- Expected: WHERE max_sal > 2 * avg_sal
```

### 6.4 HAVING with Aggregate NOT in SELECT (Hidden Column)

```sql
SELECT name FROM People p GROUP BY name HAVING count(*) > 5
-- Verify: count(*) appears in WITH as hidden column, excluded from RETURN
-- Expected: WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name

SELECT name, sum(age) AS total FROM People p GROUP BY name HAVING avg(age) > 25
-- Verify: avg(age) is hidden in WITH, total and name in RETURN, avg excluded
```

### 6.5 HAVING with Every Supported Aggregate

```sql
-- One test per aggregate function in HAVING position:
HAVING count(*) > 5
HAVING sum(age) > 100
HAVING min(age) < 18
HAVING max(age) > 65
HAVING avg(age) > 30
```

### 6.6 HAVING + ORDER BY Combined

```sql
SELECT name, count(*) AS cnt FROM People p
GROUP BY name HAVING cnt > 5 ORDER BY cnt DESC
-- Verify: end-to-end — WITH, WHERE, RETURN, ORDER BY all use correct aliases

SELECT name, count(*) AS cnt FROM People p
GROUP BY name HAVING cnt > 5 ORDER BY cnt DESC LIMIT 10
-- Verify: full chain
```

### 6.7 HAVING on JOIN Queries

```sql
SELECT c.city, count(*) AS order_count
FROM Customers c JOIN Orders o ON c.id = o.customer_id
GROUP BY c.city HAVING count(*) > 10
-- Verify: MATCH pattern correct, WITH, WHERE, RETURN all correct
```

---

## Tier 7: DISTINCT, LIMIT, and Combination Tests (Phase 7)

### 7.1 DISTINCT with WITH Clause

```sql
-- DISTINCT + GROUP BY → RETURN DISTINCT, NOT WITH DISTINCT
SELECT DISTINCT name, count(*) FROM People p GROUP BY name
-- Verify: WITH (no DISTINCT) ... RETURN DISTINCT ...

-- DISTINCT + GROUP BY + HAVING
SELECT DISTINCT name, count(*) AS cnt FROM People p
GROUP BY name HAVING cnt > 5
-- Verify: WITH ... WHERE ... RETURN DISTINCT ...
```

### 7.2 Full Combination (The "Kitchen Sink" Tests)

```sql
-- Everything: DISTINCT + GROUP BY + HAVING + ORDER BY + LIMIT
SELECT DISTINCT name, count(*) AS cnt FROM People p
GROUP BY name HAVING cnt > 5 ORDER BY cnt DESC LIMIT 10
-- Verify: WITH → WHERE → RETURN DISTINCT → ORDER BY → LIMIT

-- Join + GROUP BY + HAVING + ORDER BY + LIMIT
SELECT c.city, count(*) AS orders
FROM Customers c JOIN Orders o ON c.id = o.customer_id
GROUP BY c.city HAVING count(*) > 10 ORDER BY orders DESC LIMIT 5
```

### 7.3 LIMIT and OFFSET Placement

```sql
-- LIMIT without GROUP BY (regression)
SELECT name FROM People LIMIT 10
SELECT name FROM People LIMIT 10 OFFSET 20

-- LIMIT with GROUP BY + WITH clause
SELECT sum(age) FROM People p GROUP BY name LIMIT 5
-- Verify: LIMIT attaches to final RETURN, not to WITH

-- LIMIT + OFFSET with GROUP BY + HAVING
SELECT name, count(*) AS cnt FROM People p
GROUP BY name HAVING cnt > 5 LIMIT 10 OFFSET 20
```

### 7.4 Edge Case: No GROUP BY, No HAVING (Full Regression)

```sql
-- These must produce output identical to before any changes:
SELECT * FROM People
SELECT name, age FROM People WHERE age > 25 ORDER BY name LIMIT 10
SELECT count(*) FROM People
INSERT INTO People (name, age) VALUES ('Alice', 30)
UPDATE People SET age = 31 WHERE name = 'Alice'
DELETE FROM People WHERE name = 'Alice'
```

---

## Tier 8: Integration Tests (Run Against Real Neo4j via Testcontainers)

These execute translated SQL against a real Neo4j instance to verify **semantic correctness** — the generated Cypher not only parses but produces the right data. This is the only tier that catches bugs where the Cypher string looks correct but means the wrong thing.

### Infrastructure

**File**: New class `GroupByHavingIT extends IntegrationTestBase` in `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/`

**Data setup pattern**: Each test section uses `@BeforeEach` (via the inherited database cleanup) and seeds its own data via Cypher through a non-translating connection (`getConnection(false, false)`), then queries via a translating connection (`getConnection(true, false)`). This ensures every test starts with a known state.

**The Movies graph** (`TestUtils.createMovieGraph()`) is available but we also seed focused datasets for precise assertions. The Movies graph is used for higher-level tests where we need rich relational data.

### 8.1 Basic GROUP BY — Single Table, Single Group Column

```
Seed: CREATE (:Person {name: 'Alice', age: 30}),
      (:Person {name: 'Alice', age: 25}),
      (:Person {name: 'Bob', age: 40}),
      (:Person {name: 'Bob', age: 35}),
      (:Person {name: 'Carol', age: 28})

Tests:
 1. SELECT name, count(*) FROM Person GROUP BY name
    → 3 rows: [Alice, 2], [Bob, 2], [Carol, 1]

 2. SELECT name, sum(age) FROM Person GROUP BY name
    → 3 rows: [Alice, 55], [Bob, 75], [Carol, 28]

 3. SELECT name, min(age) FROM Person GROUP BY name
    → 3 rows: [Alice, 25], [Bob, 35], [Carol, 28]

 4. SELECT name, max(age) FROM Person GROUP BY name
    → 3 rows: [Alice, 30], [Bob, 40], [Carol, 28]

 5. SELECT name, avg(age) FROM Person GROUP BY name
    → 3 rows: [Alice, 27.5], [Bob, 37.5], [Carol, 28.0]

 6. SELECT name, count(*), sum(age), min(age), max(age) FROM Person GROUP BY name
    → 3 rows with all aggregates correct per group

 7. SELECT name, count(DISTINCT age) FROM Person GROUP BY name
    → 3 rows: [Alice, 2], [Bob, 2], [Carol, 1]
```

### 8.2 GROUP BY Column NOT in SELECT (WITH Clause Required)

```
Seed: Same as 8.1

Tests:
 8. SELECT sum(age) FROM Person GROUP BY name
    → 3 rows: [55], [75], [28] (NOT a single 158)

 9. SELECT count(*) FROM Person GROUP BY name
    → 3 rows: [2], [2], [1] (NOT a single 5)

10. SELECT avg(age) FROM Person GROUP BY name
    → 3 rows: [27.5], [37.5], [28.0] (NOT a single 31.6)

11. SELECT min(age), max(age) FROM Person GROUP BY name
    → 3 rows: [25, 30], [35, 40], [28, 28]
```

### 8.3 Multiple GROUP BY Columns

```
Seed: CREATE (:Employee {name: 'Alice', dept: 'Eng', city: 'NYC', salary: 100}),
      (:Employee {name: 'Bob',   dept: 'Eng', city: 'NYC', salary: 120}),
      (:Employee {name: 'Carol', dept: 'Eng', city: 'SF',  salary: 110}),
      (:Employee {name: 'Dave',  dept: 'Sales', city: 'NYC', salary: 90}),
      (:Employee {name: 'Eve',   dept: 'Sales', city: 'SF',  salary: 95})

Tests:
12. SELECT dept, city, count(*) FROM Employee GROUP BY dept, city
    → 3 rows: [Eng, NYC, 2], [Eng, SF, 1], [Sales, NYC, 1], [Sales, SF, 1]
    (Wait: actually 4 rows)

13. SELECT dept, count(*) FROM Employee GROUP BY dept
    → 2 rows: [Eng, 3], [Sales, 2]

14. SELECT city, sum(salary) FROM Employee GROUP BY city
    → 2 rows: [NYC, 310], [SF, 205]

15. SELECT dept, city, sum(salary), avg(salary) FROM Employee GROUP BY dept, city
    → 4 rows with correct sums and averages per group

16. SELECT count(*) FROM Employee GROUP BY dept, city
    → 4 rows: [2], [1], [1], [1] (GROUP BY not in SELECT — WITH required)

17. SELECT dept, sum(salary) FROM Employee GROUP BY dept, city
    → 4 rows (city is a hidden group column, changes the grouping but not the output columns)
```

### 8.4 HAVING — Basic Filtering

```
Seed: Same as 8.1 (Alice x2, Bob x2, Carol x1)

Tests:
18. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING count(*) > 1
    → 2 rows: [Alice, 2], [Bob, 2] (Carol filtered out)

19. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING count(*) = 1
    → 1 row: [Carol, 1]

20. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING count(*) >= 2
    → 2 rows: [Alice, 2], [Bob, 2]

21. SELECT name, sum(age) FROM Person GROUP BY name HAVING sum(age) > 50
    → 2 rows: [Alice, 55], [Bob, 75]

22. SELECT name, min(age) FROM Person GROUP BY name HAVING min(age) < 30
    → 2 rows: [Alice, 25], [Carol, 28]

23. SELECT name, max(age) FROM Person GROUP BY name HAVING max(age) >= 40
    → 1 row: [Bob, 40]

24. SELECT name, avg(age) FROM Person GROUP BY name HAVING avg(age) > 30
    → 1 row: [Bob, 37.5]
```

### 8.5 HAVING with Aggregate NOT in SELECT (Hidden Column)

```
Seed: Same as 8.1

Tests:
25. SELECT name FROM Person GROUP BY name HAVING count(*) > 1
    → 2 rows: [Alice], [Bob] — count not in result set, only used for filtering

26. SELECT name FROM Person GROUP BY name HAVING sum(age) > 50
    → 2 rows: [Alice], [Bob]

27. SELECT name FROM Person GROUP BY name HAVING min(age) >= 28
    → 2 rows: [Carol], [Bob]

28. SELECT sum(age) FROM Person GROUP BY name HAVING count(*) > 1
    → 2 rows: [55], [75] — name hidden in GROUP BY, count hidden in HAVING
```

### 8.6 Compound HAVING

```
Seed: Same as 8.3 (Employee with dept/city/salary)

Tests:
29. SELECT dept, count(*) AS cnt, sum(salary) AS total FROM Employee
    GROUP BY dept HAVING count(*) > 2 AND sum(salary) > 200
    → 1 row: [Eng, 3, 330]

30. SELECT dept, count(*) AS cnt FROM Employee
    GROUP BY dept HAVING count(*) > 2 OR sum(salary) < 200
    → 2 rows: [Eng, 3], [Sales, 2] (Eng by count, Sales by sum)

31. SELECT dept, avg(salary) FROM Employee
    GROUP BY dept HAVING avg(salary) > 100 AND count(*) >= 2
    → 1 row: [Eng, 110.0]

32. SELECT dept, min(salary), max(salary) FROM Employee
    GROUP BY dept HAVING max(salary) - min(salary) > 10
    → 1 row: [Eng, 100, 120] (spread of 20 > 10; Sales spread is 5)
```

### 8.7 HAVING by Alias (jOOQ Finding 2 — Alias as Unresolved Field)

```
Seed: Same as 8.1

Tests:
33. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt > 1
    → 2 rows: [Alice, 2], [Bob, 2]

34. SELECT name, sum(age) AS total FROM Person GROUP BY name HAVING total > 50
    → 2 rows: [Alice, 55], [Bob, 75]

35. SELECT name, max(age) AS oldest FROM Person GROUP BY name HAVING oldest >= 35
    → 2 rows: [Bob, 40], ... depends on data

36. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt = 1
    → 1 row: [Carol, 1]
```

### 8.8 ORDER BY with GROUP BY

```
Seed: Same as 8.1

Tests:
37. SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY cnt DESC
    → 3 rows in order: [Alice, 2] or [Bob, 2] first (tied), then [Carol, 1]

38. SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY cnt ASC
    → [Carol, 1] first, then the two with count 2

39. SELECT name, sum(age) AS total FROM Person GROUP BY name ORDER BY total DESC
    → [Bob, 75], [Alice, 55], [Carol, 28]

40. SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY name ASC
    → Alphabetical: [Alice, 2], [Bob, 2], [Carol, 1]

41. SELECT name, count(*) FROM Person GROUP BY name ORDER BY count(*) DESC
    → Same as 37 but using function form instead of alias

42. SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY cnt DESC, name ASC
    → Multi-column sort: tied counts broken by name alphabetically
```

### 8.9 ORDER BY + HAVING Combined

```
Seed: Same as 8.1

Tests:
43. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt > 1 ORDER BY cnt DESC
    → 2 rows: [Alice, 2], [Bob, 2] (or reversed — tied)

44. SELECT name, sum(age) AS total FROM Person GROUP BY name HAVING total > 30 ORDER BY total ASC
    → [Alice, 55], [Bob, 75] in ascending order

45. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt >= 1 ORDER BY name
    → 3 rows alphabetically: Alice, Bob, Carol

46. SELECT name FROM Person GROUP BY name HAVING count(*) > 1 ORDER BY name DESC
    → [Bob], [Alice] (hidden aggregate in HAVING, ORDER BY visible column)
```

### 8.10 LIMIT and OFFSET with GROUP BY

```
Seed: Same as 8.1

Tests:
47. SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY name LIMIT 2
    → 2 rows: [Alice, 2], [Bob, 2]

48. SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY name LIMIT 1 OFFSET 1
    → 1 row: [Bob, 2]

49. SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt > 0 ORDER BY name LIMIT 2
    → 2 rows: [Alice, 2], [Bob, 2]

50. SELECT sum(age) FROM Person GROUP BY name ORDER BY sum(age) DESC LIMIT 1
    → 1 row: [75] (Bob's group, but name not shown)

51. SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY cnt DESC LIMIT 1
    → 1 row: either [Alice, 2] or [Bob, 2]
```

### 8.11 DISTINCT with GROUP BY

```
Seed: Same as 8.1

Tests:
52. SELECT DISTINCT name, count(*) FROM Person GROUP BY name
    → Same as non-DISTINCT (aggregation already produces unique groups) — 3 rows

53. SELECT DISTINCT name FROM Person GROUP BY name
    → 3 rows: Alice, Bob, Carol

54. SELECT DISTINCT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt > 1
    → 2 rows: [Alice, 2], [Bob, 2]
```

### 8.12 JOIN Queries with GROUP BY (Using Movies Graph)

```
Seed: TestUtils.createMovieGraph(connection) — loads full Movies dataset

Tests:
55. SELECT p.name, count(*) AS movie_count FROM Person p
    JOIN Movie m ON ... GROUP BY p.name ORDER BY movie_count DESC LIMIT 5
    → Top 5 actors by number of movies (verify count is correct)
    NOTE: Exact join syntax depends on how the translator maps joins.
    Use the pattern from existing TranslationIT tests.

56. SELECT m.released, count(*) AS num_movies FROM Movie m GROUP BY m.released ORDER BY m.released
    → One row per release year, count of movies in that year

57. SELECT m.released, count(*) FROM Movie m GROUP BY m.released HAVING count(*) > 1
    → Only years with multiple movies

58. SELECT m.released, count(*) AS cnt FROM Movie m
    GROUP BY m.released HAVING cnt > 1 ORDER BY cnt DESC LIMIT 3
    → Top 3 years by movie count

59. SELECT m.released, min(p.born), max(p.born) FROM Person p
    JOIN Movie m ON ... GROUP BY m.released
    → Birth range of actors per release year

60. SELECT m.released, count(*) FROM Movie m
    GROUP BY m.released HAVING count(*) >= 2 ORDER BY m.released DESC
    → Years with 2+ movies, newest first
```

### 8.13 GROUP BY with WHERE (Filtering Before Aggregation)

```
Seed: Same as 8.3 (Employee)

Tests:
61. SELECT dept, count(*) FROM Employee WHERE salary > 95 GROUP BY dept
    → Only employees with salary > 95 counted: [Eng, 2], [Sales, 0 or absent]

62. SELECT dept, sum(salary) FROM Employee WHERE city = 'NYC' GROUP BY dept
    → Only NYC employees: [Eng, 220], [Sales, 90]

63. SELECT dept, count(*) AS cnt FROM Employee WHERE city = 'NYC' GROUP BY dept HAVING cnt > 1
    → [Eng, 2] (Sales has only 1 NYC employee)

64. SELECT dept, avg(salary) FROM Employee WHERE salary >= 100 GROUP BY dept ORDER BY avg(salary) DESC
    → Only high-salary employees averaged per dept

65. SELECT city, count(*) FROM Employee WHERE dept = 'Eng' GROUP BY city
    → [NYC, 2], [SF, 1]
```

### 8.14 Global Aggregation (No GROUP BY — Regression)

```
Seed: Same as 8.1

Tests:
66. SELECT count(*) FROM Person
    → 1 row: [5]

67. SELECT sum(age) FROM Person
    → 1 row: [158]

68. SELECT min(age), max(age) FROM Person
    → 1 row: [25, 40]

69. SELECT avg(age) FROM Person
    → 1 row: [31.6]

70. SELECT count(DISTINCT name) FROM Person
    → 1 row: [3]
```

### 8.15 Edge Cases and Boundary Conditions

```
Seed varies per test

Tests:
71. Empty table: SELECT name, count(*) FROM Person GROUP BY name
    → 0 rows (no data, no groups)

72. Single row: CREATE (:Person {name: 'Alice', age: 30})
    SELECT name, count(*) FROM Person GROUP BY name
    → 1 row: [Alice, 1]

73. All same group: CREATE (:Person {name: 'Alice', age: 30}), (:Person {name: 'Alice', age: 25})
    SELECT name, count(*) FROM Person GROUP BY name
    → 1 row: [Alice, 2]

74. HAVING filters everything: Same seed as 8.1
    SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt > 100
    → 0 rows

75. HAVING keeps everything: Same seed
    SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt >= 1
    → 3 rows (all groups pass)

76. NULL handling: CREATE (:Person {name: 'Alice', age: 30}), (:Person {age: 25})
    SELECT name, count(*) FROM Person GROUP BY name
    → Verify: NULL name group exists and is counted correctly

77. Large group count: Create 20 persons across 10 names
    SELECT name, count(*) AS cnt FROM Person GROUP BY name ORDER BY cnt DESC
    → Verify ordering and counts are correct across many groups

78. GROUP BY + LIMIT 0: SELECT name, count(*) FROM Person GROUP BY name LIMIT 0
    → 0 rows (LIMIT 0 should return nothing)
```

### 8.16 Multi-Aggregate Combinations

```
Seed: Same as 8.3 (Employee)

Tests:
79. SELECT dept, count(*), sum(salary), avg(salary), min(salary), max(salary) FROM Employee GROUP BY dept
    → 2 rows with all 5 aggregates correct per department

80. SELECT dept, count(*) AS c, sum(salary) AS s FROM Employee
    GROUP BY dept HAVING c > 2 AND s > 300
    → 1 row: [Eng, 3, 330]

81. SELECT dept, count(*) AS c, avg(salary) AS a FROM Employee
    GROUP BY dept ORDER BY a DESC
    → Ordered by average salary descending

82. SELECT city, count(*) AS c, sum(salary) AS s, min(salary) AS lo, max(salary) AS hi FROM Employee
    GROUP BY city HAVING c > 1 ORDER BY s DESC
    → NYC first (higher sum), only cities with 2+ employees
```

### 8.17 Full Chain Tests (The "Kitchen Sink")

```
Seed: Same as 8.3 (Employee)

Tests:
83. SELECT dept, count(*) AS cnt FROM Employee
    WHERE salary > 90
    GROUP BY dept
    HAVING cnt >= 2
    ORDER BY cnt DESC
    LIMIT 5
    → Full chain: WHERE → GROUP BY → HAVING → ORDER BY → LIMIT

84. SELECT DISTINCT dept, count(*) AS cnt FROM Employee
    GROUP BY dept
    HAVING cnt > 1
    ORDER BY dept
    → DISTINCT + GROUP BY + HAVING + ORDER BY

85. SELECT city, dept, count(*) AS cnt, sum(salary) AS total FROM Employee
    WHERE salary >= 90
    GROUP BY city, dept
    HAVING count(*) >= 1
    ORDER BY total DESC
    LIMIT 10
    → Two GROUP BY columns + WHERE + HAVING + ORDER BY + LIMIT

86. SELECT dept, sum(salary) AS total FROM Employee
    GROUP BY dept
    HAVING sum(salary) > 200
    ORDER BY total ASC
    LIMIT 1 OFFSET 0
    → HAVING + ORDER BY + LIMIT + OFFSET
```

### 8.18 Regression Against Existing Patterns

```
These verify that existing working integration test patterns still pass unchanged.

Tests:
87. Reproduce StatementIT.orderOrGroupByUnrelated: GROUP BY unrelated column
    → SELECT m.title FROM Movie m GROUP BY m.year — returns correct row count

88. Reproduce StatementIT.orderOrGroupByUnrelated: GROUP BY beyond SELECT
    → SELECT m.title FROM Movie m GROUP BY m.title, m.year — returns correct row count

89. Reproduce TranslationIT GROUP BY with VID join pattern (line 827)
    → Verify the GROUP BY with elementId column on joined relationship still works

90. Reproduce CypherBackedViewsIT GROUP BY on views (line 91)
    → SELECT count(*), a FROM cbv1 GROUP BY a — returns 38 rows (one per movie)

91. Simple non-GROUP-BY regression: SELECT * FROM Movie — still works
92. Simple non-GROUP-BY regression: SELECT m.title FROM Movie m WHERE m.released > 2000 — still works
93. Simple non-GROUP-BY regression: INSERT, UPDATE, DELETE on Movie — still works
94. ORDER BY without GROUP BY regression: SELECT m.title FROM Movie m ORDER BY m.released DESC LIMIT 5
95. COUNT without GROUP BY regression: SELECT count(*) FROM Movie — single count of all movies
```

### 8.19 Movies Graph — Real-World Analytic Queries

```
Seed: TestUtils.createMovieGraph(connection)

These test realistic analytic SQL that a BI tool or application would generate.

Tests:
96. "Movies per decade":
    SELECT m.released, count(*) FROM Movie m GROUP BY m.released ORDER BY m.released
    → Verify row count matches number of distinct release years in the dataset

97. "Prolific directors" (if join pattern works):
    SELECT p.name, count(*) AS directed FROM Person p
    JOIN ... GROUP BY p.name HAVING count(*) > 1 ORDER BY directed DESC
    → People who directed multiple movies

98. "Years with multiple movies":
    SELECT m.released, count(*) AS cnt FROM Movie m GROUP BY m.released HAVING cnt > 2
    → Only years with 3+ movies

99. "Average birth year of actors per movie release year":
    Complex join + GROUP BY + aggregate

100. "Actor count per movie, only movies with 3+ actors":
     GROUP BY movie title, HAVING count of actors > 3, ORDER BY count DESC
```

---

## Test Execution Order

Run these in this order during implementation. Each tier is a quality gate — do not proceed to the next tier if any test in the current tier fails.

| Step | When | What | Pass Criteria |
|------|------|------|---------------|
| 1 | Before any code change | Tier 1 (regression snapshot) | All existing tests pass, snapshot captured |
| 2 | After Phase 2 (matcher) | Tier 2 (matcher unit tests) | All matcher tests pass, Tier 1 still green |
| 3 | After Phase 3 (registry) | Tier 3 (registry unit tests) | All registry tests pass, Tiers 1-2 still green |
| 4 | After Phase 4 (WITH clause) | Tier 4 (WITH generation) + re-run Tier 1 | WITH tests pass, updated expectations correct, no regressions |
| 5 | After Phase 5 (ORDER BY) | Tier 5 (ORDER BY alias) + re-run Tiers 1-4 | ORDER BY tests pass, no regressions |
| 6 | After Phase 6 (HAVING) | Tier 6 (HAVING) + re-run Tiers 1-5 | HAVING tests pass, no regressions |
| 7 | After Phase 7 (hardening) | Tier 7 (combinations) + re-run all | All combination tests pass, full suite green |
| 8 | Final validation | Tier 8 (integration) | Real Neo4j produces correct results |

---

## Test Placement

| Tests | File | Rationale |
|-------|------|-----------|
| Tier 1 snapshots | `SqlToCypherTests.java` (add new `@ParameterizedTest` methods) | Extends existing test patterns, same test infrastructure |
| Tiers 2-3 | New class: `FieldMatcherTests.java` or `AliasRegistryTests.java` | Isolated component tests, separate concern |
| Tiers 4-7 | `SqlToCypherTests.java` (new `@ParameterizedTest` methods per tier) | These test translation output, same as existing tests |
| Tier 8 | New class: `GroupByHavingIT extends IntegrationTestBase` in `neo4j-jdbc-it-cp` | Dedicated IT class, inherits all container/connection infrastructure |

---

## Test Count Estimate

| Tier | Estimated Tests |
|------|-----------------|
| 1. Regression snapshot | ~35 |
| 2. Structural matcher | ~15 |
| 3. Alias registry | ~15 |
| 4. WITH generation | ~10 |
| 5. ORDER BY alias | ~10 |
| 6. HAVING translation | ~20 |
| 7. Combinations | ~12 |
| 8. Integration | ~100 |
| **Total** | **~217** |

Combined with the 32 Phase 1 diagnostic tests and ~265 existing tests, the module will have ~515 tests providing comprehensive coverage of the GROUP BY / HAVING feature.
