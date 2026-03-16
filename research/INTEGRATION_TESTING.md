# Integration Testing — Findings Report & Implementation Plan

**Date**: 2026-03-15
**Scope**: Non-HAVING integration tests only (~50 tests). HAVING tests deferred until HAVING hidden-column support is complete.

---

## Progress Tracker

| Step | Status | Details |
|------|--------|---------|
| Part 1: Infrastructure research | COMPLETE | Findings documented below |
| Part 2: Test plan | COMPLETE | 56 tests across 11 categories |
| GroupByIT.java written | COMPLETE | 56 @Test methods, compiles cleanly, formatting applied, code reviewed |
| Integration test run | **PENDING — USER** | Run: `./test.sh --step 3 --output phase8_non_having` or `./mvnw -DskipUTs -Dcheckstyle.skip=true -Dlicense.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp verify` |
| Assertion tuning | NOT STARTED | Adjust expected values based on actual output |
| HAVING tests | DEFERRED | ~23 additional tests after HAVING hidden-column support |

**Test file**: `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/GroupByIT.java`

**Test breakdown by @Nested class**:

| Nested Class | Tests | Description |
|-------------|-------|-------------|
| BasicGroupBy | 7 | COUNT, SUM, AVG, MIN/MAX, multiple aggregates, unique column, global |
| GroupByNotInSelect | 4 | Hidden GROUP BY column forces WITH clause |
| MultipleGroupByColumns | 6 | Two columns, Movies graph by release year |
| OrderByWithGroupBy | 6 | ORDER BY on GROUP BY column, on aggregate, multiple columns |
| LimitOffsetWithGroupBy | 5 | LIMIT, ORDER BY+LIMIT, OFFSET |
| DistinctWithGroupBy | 3 | DISTINCT with GROUP BY |
| GroupByWithWhere | 5 | WHERE filtering before GROUP BY |
| GlobalAggregationRegression | 5 | Non-GROUP-BY aggregations still work |
| EdgeCases | 6 | Empty results, single result, large cardinality, non-GROUP-BY regression |
| MultiAggregate | 4 | All 5 aggregate functions combined |
| RegressionTests | 5 | SELECT *, LIKE, INSERT+COUNT, literals, zero-match count |
| **Total** | **56** | |

---

## Part 1: Infrastructure Findings

### 1.1 Test Base Class

**File**: `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/IntegrationTestBase.java`

The abstract base class provides:

| Feature | Details |
|---------|---------|
| Container lifecycle | `@Testcontainers(disabledWithoutDocker = true)` + `@TestInstance(PER_CLASS)` — container starts once per test class |
| Database cleanup | `@BeforeEach clearDatabase()` — batched `DETACH DELETE` unless `doClean = false` |
| Connection factory | `getConnection(boolean translate, boolean rewriteBatchedStatements, String... additionalProperties)` |
| Translation mode | Sets `enableSQLTranslation=true`, `s2c.alwaysEscapeNames=false`, `s2c.prettyPrint=false` |
| Resource copying | `resources` list → files copied to `/var/lib/neo4j/import/` in container |

**Connection method signatures**:
```java
getConnection()                          // No translation, no batching
getConnection(true, false)               // Translation ON, batching OFF
getConnection(true, false, "key", "val") // Translation ON + custom properties
```

### 1.2 TestUtils Helper

**File**: `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/TestUtils.java`

Key methods:
- `getNeo4jContainer()` — uses system property `neo4j-jdbc.default-neo4j-image`
- `createMovieGraph(Connection)` — loads `/movies.cypher` from classpath, splits on `;`, executes each statement with `/*+ NEO4J FORCE_CYPHER */` prefix
- `getConnection(Neo4jContainer, boolean translate)` — standalone connection factory

### 1.3 Movies Graph Dataset

**File**: `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/resources/movies.cypher` (507 lines)

**Schema**:
- **Nodes**: `Movie` (title, released, tagline), `Person` (name, born)
- **Relationships**: `ACTED_IN` (roles[]), `DIRECTED`, `PRODUCED`, `WROTE`, `REVIEWED` (summary, rating), `FOLLOWS`
- **Scale**: ~38 Movies, ~133 Persons, ~250 relationships total

**Key data points for GROUP BY test assertions** (verified from the dataset):

| Query Concept | Known Facts |
|--------------|-------------|
| Total ACTED_IN relationships | 172 (from `TranslationIT.joins()` assertion at line 693) |
| Total Person-Movie natural join | 250 (from `TranslationIT.joins()` line 705) |
| Matrix actors | 5 (Keanu, Carrie-Anne, Laurence, Hugo, Emil) |
| Wachowski-directed movies | 5 unique (Matrix trilogy + Cloud Atlas + Speed Racer) |
| Movies released in 2003 | At least 2 (Matrix Reloaded, Matrix Revolutions) |
| Relationship types | ACTED_IN, DIRECTED, PRODUCED, WROTE, REVIEWED, FOLLOWS |
| REVIEWED ratings | Range: 45-100 (from movies.cypher lines 496-505) |

### 1.4 Existing Test Patterns

**Pattern 1: Translation-only verification** (assert generated Cypher without executing)
```java
var cypher = connection.nativeSQL(sql);
assertThat(cypher).isEqualTo(expectedCypher);
```
Used in: `TranslationIT.innerJoinColumnsWrongDirection()`, `NorthwindIT`

**Pattern 2: Create-then-query** (create data with FORCE_CYPHER, query with translated SQL)
```java
// Create
stmt.execute("/*+ NEO4J FORCE_CYPHER */ CREATE (n:Label {prop: 'value'})");
// Query with SQL translation
var rs = stmt.executeQuery("SELECT prop FROM Label");
assertThat(rs.next()).isTrue();
assertThat(rs.getString("prop")).isEqualTo("value");
```
Used in: `TranslationIT.shouldTranslateAsterisk()`

**Pattern 3: Movies graph bulk load** (load full dataset, query with SQL)
```java
@BeforeAll
void loadMovies() throws Exception {
    try (var connection = getConnection(true, false)) {
        TestUtils.createMovieGraph(connection);
    }
}
```
Used in: `JacksonIT`, `CypherBackedViewsIT`

**Pattern 4: Parameterized tests with CsvSource** (SQL → expected Cypher pairs)
```java
@ParameterizedTest
@CsvSource(delimiterString = "|", textBlock = """
    SQL input | expected Cypher output
    """)
void testTranslation(String sql, String expectedCypher) throws SQLException {
    try (var connection = getConnection(true, false)) {
        assertThat(connection.nativeSQL(sql)).isEqualTo(expectedCypher);
    }
}
```
Used in: `NorthwindIT.insertsShouldUseStableParameters()`

**Pattern 5: Result set iteration with collection**
```java
var results = new ArrayList<Record>();
while (rs.next()) {
    results.add(new Record(rs.getString("col1"), rs.getInt("col2")));
}
assertThat(results).hasSize(N);
assertThat(results).containsExactly(...);
```
Used in: `TranslationIT.joins()`

### 1.5 Assertion Library

**AssertJ** is the standard assertion library. Key patterns:
```java
assertThat(rs.next()).isTrue() / .isFalse()
assertThat(rs.getInt(1)).isEqualTo(42)
assertThat(rs.getString("name")).isEqualTo("Alice")
assertThat(list).hasSize(N)
assertThat(list).containsExactly(...)
assertThat(list).containsExactlyInAnyOrder(...)
assertThat(value).isGreaterThan(0)
```

### 1.6 Existing GROUP BY Coverage in ITs

Current coverage is minimal:

| File | Test | What it covers |
|------|------|---------------|
| `TranslationIT:820` | `innerJoinColumnsWrongDirection` | GROUP BY on JOIN with virtual tables — translation only, no execution |
| `StatementIT` | Two GROUP BY queries | Basic GROUP BY translation in statement context |
| `CypherBackedViewsIT` | `SELECT count(*), a FROM cbv1 GROUP BY a` | GROUP BY on Cypher-backed views |

**Gap**: No integration tests verify GROUP BY correctness against a real Neo4j database with result set assertions. All existing GROUP BY tests are translation-only.

### 1.7 Test Execution

**Maven Failsafe Plugin** runs `*IT.java` classes during `verify` phase:
```bash
./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp verify
```

**test.sh step 3**: Full integration test run via `./mvnw verify -DskipUTs`.

### 1.8 Module Dependencies (pom.xml)

`neo4j-jdbc-it-cp` depends on (test scope):
- `org.neo4j:neo4j-jdbc`
- `org.neo4j:neo4j-jdbc-translator-impl` (the SQL-to-Cypher translator we're testing)
- `org.testcontainers:testcontainers-neo4j`
- `org.testcontainers:testcontainers-junit-jupiter`
- AssertJ, JUnit 5, Mockito, SLF4J

---

## Part 2: Integration Test Plan (Non-HAVING)

### 2.1 Test Class Design

**File**: `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/GroupByIT.java`

**Design decisions**:
- Extends `IntegrationTestBase`
- `doClean = false` — load Movies graph once, reuse across all tests
- Movies graph loaded via `TestUtils.createMovieGraph()` in `@BeforeAll`
- Additional simple test data created in `@BeforeAll` for controlled assertions
- SQL translation enabled (`getConnection(true, false)`)
- Organized with `@Nested` classes per test category
- Each test verifies **both** the translated Cypher (via `nativeSQL()`) **and** the actual query results against Neo4j

**Why both translation AND execution assertions**:
- Translation-only tests catch translator regressions
- Execution tests catch cases where the generated Cypher is syntactically valid but semantically wrong
- The Movies graph has known cardinalities we can assert against

### 2.2 Test Data Strategy

**Layer 1: Movies graph** — loaded once, provides rich data for JOIN + GROUP BY tests and realistic cardinalities.

**Layer 2: Simple controlled data** — created in `@BeforeAll` after the Movies graph. Small set with known values for precise assertions:

```sql
-- Created via FORCE_CYPHER in @BeforeAll
CREATE (p:People {name: 'Alice', age: 30, department: 'Engineering'})
CREATE (p:People {name: 'Bob', age: 25, department: 'Engineering'})
CREATE (p:People {name: 'Charlie', age: 35, department: 'Sales'})
CREATE (p:People {name: 'Diana', age: 28, department: 'Sales'})
CREATE (p:People {name: 'Eve', age: 32, department: 'Marketing'})
```

This gives us:
- 3 departments: Engineering (2), Sales (2), Marketing (1)
- Age range: 25-35
- Known aggregation results: count per dept, sum of ages per dept, etc.

**Note**: Uses label `People` (not `Person`) to avoid collision with the Movies graph `Person` nodes.

### 2.3 Test Categories and Cases

#### Basic GROUP BY (7 tests)

Tests fundamental GROUP BY translation and execution with single-table queries.

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department` | Basic GROUP BY + COUNT — 3 rows |
| 2 | `SELECT department, sum(age) AS total FROM People p GROUP BY department` | GROUP BY + SUM — Engineering=55, Sales=63, Marketing=32 |
| 3 | `SELECT department, avg(age) AS average FROM People p GROUP BY department` | GROUP BY + AVG |
| 4 | `SELECT department, min(age) AS youngest, max(age) AS oldest FROM People p GROUP BY department` | GROUP BY + MIN/MAX — multiple aggregates |
| 5 | `SELECT department, count(*) AS cnt, sum(age) AS total, avg(age) AS average FROM People p GROUP BY department` | GROUP BY + multiple aggregate functions |
| 6 | `SELECT name, count(*) AS cnt FROM People p GROUP BY name` | GROUP BY on unique column — each count = 1 |
| 7 | `SELECT count(*) AS total FROM People p` | Global aggregation without GROUP BY — total = 5 |

#### GROUP BY Column Not in SELECT — WITH Required (4 tests)

Tests the WITH clause generation when GROUP BY columns differ from SELECT columns.

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT count(*) AS cnt FROM People p GROUP BY department` | GROUP BY column not in SELECT — 3 rows with counts [2, 2, 1] |
| 2 | `SELECT sum(age) AS total FROM People p GROUP BY department` | SUM with hidden GROUP BY column |
| 3 | `SELECT count(*) AS cnt, avg(age) AS average FROM People p GROUP BY department` | Multiple aggregates, hidden GROUP BY |
| 4 | `SELECT max(age) AS oldest FROM People p GROUP BY department` | MAX with hidden GROUP BY — results [30, 35, 32] |

#### Multiple GROUP BY Columns (6 tests)

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT department, name, count(*) AS cnt FROM People p GROUP BY department, name` | Two GROUP BY columns, both in SELECT — 5 rows, each count = 1 |
| 2 | `SELECT count(*) AS cnt FROM People p GROUP BY department, name` | Two GROUP BY columns, neither in SELECT |
| 3 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department, name` | Two GROUP BY columns, one in SELECT |
| 4 | `SELECT m.released, count(*) AS movie_count FROM Movie m GROUP BY m.released` | GROUP BY on Movie.released — Movies graph |
| 5 | `SELECT m.released, count(*) AS movie_count FROM Movie m GROUP BY m.released ORDER BY m.released` | GROUP BY + ORDER BY on same column — Movies graph, ordered by year |
| 6 | `SELECT m.released, count(*) AS movie_count FROM Movie m GROUP BY m.released ORDER BY movie_count DESC` | GROUP BY + ORDER BY on aggregate — Movies graph |

#### ORDER BY with GROUP BY (6 tests)

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department` | ORDER BY on GROUP BY column |
| 2 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY cnt DESC` | ORDER BY on aggregate alias — descending |
| 3 | `SELECT department, sum(age) AS total FROM People p GROUP BY department ORDER BY total` | ORDER BY on SUM alias — ascending |
| 4 | `SELECT department, count(*) AS cnt, avg(age) AS average FROM People p GROUP BY department ORDER BY cnt DESC, average` | ORDER BY on multiple columns |
| 5 | `SELECT m.released, count(*) AS cnt FROM Movie m GROUP BY m.released ORDER BY cnt DESC, m.released` | Movies graph: ORDER BY aggregate then GROUP BY col |
| 6 | `SELECT department, min(age) AS youngest FROM People p GROUP BY department ORDER BY youngest` | ORDER BY on MIN aggregate |

#### LIMIT/OFFSET with GROUP BY (5 tests)

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department LIMIT 2` | LIMIT on GROUP BY result — only 2 of 3 departments |
| 2 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY cnt DESC LIMIT 2` | ORDER BY + LIMIT — top 2 departments by count |
| 3 | `SELECT m.released, count(*) AS cnt FROM Movie m GROUP BY m.released ORDER BY cnt DESC LIMIT 5` | Movies graph: top 5 release years by movie count |
| 4 | `SELECT department, sum(age) AS total FROM People p GROUP BY department ORDER BY total DESC LIMIT 1` | Top department by age sum |
| 5 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department LIMIT 2 OFFSET 1` | LIMIT + OFFSET — skip first department alphabetically |

#### DISTINCT with GROUP BY (3 tests)

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT DISTINCT department FROM People p GROUP BY department` | DISTINCT on GROUP BY column — same as GROUP BY alone |
| 2 | `SELECT DISTINCT department, count(*) AS cnt FROM People p GROUP BY department` | DISTINCT with GROUP BY + aggregate |
| 3 | `SELECT DISTINCT m.released FROM Movie m GROUP BY m.released ORDER BY m.released` | DISTINCT GROUP BY on Movies data — ordered years |

#### GROUP BY with WHERE (5 tests)

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT department, count(*) AS cnt FROM People p WHERE age > 28 GROUP BY department` | WHERE filter before GROUP BY — Engineering=1 (Alice), Sales=1 (Charlie), Marketing=1 (Eve) |
| 2 | `SELECT department, avg(age) AS average FROM People p WHERE department != 'Marketing' GROUP BY department` | WHERE excludes a department — 2 rows |
| 3 | `SELECT department, sum(age) AS total FROM People p WHERE age BETWEEN 25 AND 30 GROUP BY department` | WHERE with BETWEEN — Engineering=55 (Alice+Bob), Sales=28 (Diana) |
| 4 | `SELECT m.released, count(*) AS cnt FROM Movie m WHERE m.released >= 2000 GROUP BY m.released ORDER BY m.released` | Movies graph: filter by year, then GROUP BY |
| 5 | `SELECT m.released, count(*) AS cnt FROM Movie m WHERE m.released < 2000 GROUP BY m.released ORDER BY cnt DESC` | Movies graph: pre-2000 movies grouped by year |

#### Global Aggregation Regression (5 tests)

Verifies that non-GROUP-BY aggregation queries still work correctly after the WITH clause changes.

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT count(*) FROM People p` | Simple count — 5 |
| 2 | `SELECT count(*) FROM Movie m` | Movies graph count — 38 movies |
| 3 | `SELECT min(age), max(age), avg(age) FROM People p` | Multiple global aggregates — 25, 35, 30.0 |
| 4 | `SELECT count(*) FROM People p WHERE department = 'Engineering'` | Count with WHERE — 2 |
| 5 | `SELECT sum(age) FROM People p` | Global SUM — 150 |

#### Edge Cases (6 tests)

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department` | Deterministic ordering for stable assertions |
| 2 | `SELECT count(*) AS cnt FROM People p GROUP BY department ORDER BY cnt` | Ascending order on count — [1, 2, 2] |
| 3 | `SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department LIMIT 1` | Single result from GROUP BY |
| 4 | Empty result GROUP BY | GROUP BY with WHERE that matches nothing — 0 rows |
| 5 | Large cardinality GROUP BY | Movies graph: GROUP BY released → verify row count matches distinct years |
| 6 | `SELECT * FROM People p` | Non-GROUP-BY query — regression: still works without WITH |

#### Multi-Aggregate (4 tests)

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT department, count(*) AS cnt, sum(age) AS total, min(age) AS youngest, max(age) AS oldest, avg(age) AS average FROM People p GROUP BY department` | All 5 aggregate functions in one query |
| 2 | `SELECT m.released, count(*) AS cnt, min(m.title) AS first_title, max(m.title) AS last_title FROM Movie m GROUP BY m.released ORDER BY m.released` | Multiple aggregates on Movies |
| 3 | `SELECT department, count(*) AS cnt, sum(age) AS total FROM People p GROUP BY department ORDER BY total DESC` | Multi-aggregate with ORDER BY on one of them |
| 4 | `SELECT department, count(*) AS cnt, avg(age) AS average FROM People p WHERE age >= 28 GROUP BY department ORDER BY average DESC` | WHERE + multi-aggregate + ORDER BY |

#### Regression Against Existing IT Patterns (5 tests)

Verifies that existing translator features still work alongside GROUP BY changes.

| # | SQL | Verifies |
|---|-----|----------|
| 1 | `SELECT * FROM Movie m` | Basic SELECT * — unchanged |
| 2 | `SELECT name FROM Person p WHERE p.name LIKE '%Reeves%'` | WHERE with LIKE — unchanged |
| 3 | `SELECT 1` | Literal select — unchanged |
| 4 | `INSERT INTO People (name, age, department) VALUES ('Test', 40, 'HR')` then `SELECT count(*) FROM People p` | INSERT + COUNT — 6 after insert, then cleanup |
| 5 | `SELECT count(*) FROM People p WHERE age > 100` | Count with no matches — 0 |

### 2.4 Test Data Fixtures Summary

**People data** (created in `@BeforeAll`):

| name | age | department |
|------|-----|-----------|
| Alice | 30 | Engineering |
| Bob | 25 | Engineering |
| Charlie | 35 | Sales |
| Diana | 28 | Sales |
| Eve | 32 | Marketing |

**Derived facts for assertions**:

| department | count | sum(age) | min(age) | max(age) | avg(age) |
|-----------|-------|----------|----------|----------|----------|
| Engineering | 2 | 55 | 25 | 30 | 27.5 |
| Sales | 2 | 63 | 28 | 35 | 31.5 |
| Marketing | 1 | 32 | 32 | 32 | 32.0 |
| **Total** | **5** | **150** | **25** | **35** | **30.0** |

### 2.5 Test Structure Template

```java
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupByIT extends IntegrationTestBase {

    GroupByIT() {
        super.doClean = false;
    }

    @BeforeAll
    void loadData() throws Exception {
        try (var connection = getConnection(true, false)) {
            TestUtils.createMovieGraph(connection);
        }
        try (var connection = getConnection(false, false);
             var stmt = connection.createStatement()) {
            // Create People test data
            stmt.execute("CREATE (:People {name: 'Alice', age: 30, department: 'Engineering'})");
            stmt.execute("CREATE (:People {name: 'Bob', age: 25, department: 'Engineering'})");
            stmt.execute("CREATE (:People {name: 'Charlie', age: 35, department: 'Sales'})");
            stmt.execute("CREATE (:People {name: 'Diana', age: 28, department: 'Sales'})");
            stmt.execute("CREATE (:People {name: 'Eve', age: 32, department: 'Marketing'})");
        }
    }

    @Nested
    class BasicGroupBy { ... }

    @Nested
    class GroupByNotInSelect { ... }

    @Nested
    class MultipleGroupByColumns { ... }

    @Nested
    class OrderByWithGroupBy { ... }

    @Nested
    class LimitOffsetWithGroupBy { ... }

    @Nested
    class DistinctWithGroupBy { ... }

    @Nested
    class GroupByWithWhere { ... }

    @Nested
    class GlobalAggregationRegression { ... }

    @Nested
    class EdgeCases { ... }

    @Nested
    class MultiAggregate { ... }

    @Nested
    class RegressionTests { ... }
}
```

### 2.6 Test Implementation Approach

For each test, follow this pattern:

1. **Get translated connection**: `getConnection(true, false)`
2. **Optionally verify translation**: `connection.nativeSQL(sql)` → assert Cypher pattern
3. **Execute the query**: `stmt.executeQuery(sql)`
4. **Assert results**: Iterate ResultSet, collect into list, assert with AssertJ

**Important**: For GROUP BY queries without ORDER BY, results may come in any order. Use `containsExactlyInAnyOrder()` for unordered results, `containsExactly()` only when ORDER BY is specified.

### 2.7 Known Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Movies graph cardinalities may differ from expectations | Run diagnostic queries in first test pass to establish actual counts; update assertions accordingly |
| `count(*)` may translate to `count(p)` vs `count(*)` in Cypher | Use `nativeSQL()` to capture actual translation, don't hardcode Cypher expectations until verified |
| Aggregate return types (int vs long vs double) vary by function | Use `rs.getObject()` with type checking or `rs.getLong()`/`rs.getDouble()` as appropriate |
| `avg()` returns floating point — precision issues | Use `assertThat(value).isCloseTo(expected, within(0.01))` for avg assertions |
| ORDER BY on non-deterministic GROUP BY order | Always include ORDER BY when asserting exact order |
| Container startup time | `withReuse(true)` is already configured — subsequent runs reuse the container |

### 2.8 Execution Plan

1. Claude writes the `GroupByIT.java` test file
2. User runs: `./test.sh --step 3 --output phase8_non_having`
3. User reports results
4. Claude adjusts assertions based on actual output (expected values may need tuning based on Movies graph cardinalities and translator behavior)
5. Iterate until all tests pass

### 2.9 Deferred Tests (Requires HAVING Hidden-Column Support)

The following test categories require the HAVING hidden-column feature and will be added after that work completes:

- HAVING basic filtering (7 tests)
- HAVING not in SELECT — hidden columns (4 tests)
- Compound HAVING (4 tests)
- HAVING by alias (4 tests)
- ORDER BY + HAVING combined (4 tests)
- JOIN + GROUP BY (HAVING cases only)
- Kitchen sink (HAVING cases only)
- Movies graph analytics (HAVING cases only)

---

## Part 3: File Inventory

| File | Action | Purpose |
|------|--------|---------|
| `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/GroupByIT.java` | CREATE | New integration test class |
| `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/IntegrationTestBase.java` | READ ONLY | Base class — no changes |
| `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/TestUtils.java` | READ ONLY | Movies graph loader — no changes |
| `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/resources/movies.cypher` | READ ONLY | Movies dataset — no changes |
