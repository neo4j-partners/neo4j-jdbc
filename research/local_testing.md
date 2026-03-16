# Building and Testing the GROUP BY / HAVING Changes Locally

## Test Summary

| Category | Test Class | Tests | Status |
|----------|-----------|-------|--------|
| Unit: SQL-to-Cypher translation | `SqlToCypherTests` | ~400+ | Passing |
| Unit: Field matching | `FieldMatcherTests` | varies | Passing |
| Unit: Alias registry | `AliasRegistryTests` | varies | Passing |
| Unit: jOOQ QOM diagnostics | `JooqQomDiagnosticTests` | varies | Passing |
| Unit: Living documentation | `GroupByDocumentationTests` | 17 (parameterized) | Passing |
| Integration: GROUP BY | `GroupByIT` | 56 | Passing |
| Integration: HAVING | `HavingIT` | 23 | Passing |
| **Total** | | **~515+** | **All passing** |

---

## Build a Local JAR

From the repository root:

```bash
# Fast build — skips tests, checkstyle, and other validation
./mvnw -Dfast package
```

This installs `6.11.1-SNAPSHOT` artifacts into your local Maven repository (`~/.m2/repository/org/neo4j/`) and produces JARs in each module's `target/` directory.

### Which JAR to use

| Use case | JAR location |
|----------|-------------|
| Translator module only | `neo4j-jdbc-translator/impl/target/neo4j-jdbc-translator-impl-6.11.1-SNAPSHOT.jar` |
| Core JDBC driver (no translator) | `bundles/neo4j-jdbc-bundle/target/neo4j-jdbc-bundle-6.11.1-SNAPSHOT.jar` |
| Full bundle (driver + translator) | `bundles/neo4j-jdbc-full-bundle/target/neo4j-jdbc-full-bundle-6.11.1-SNAPSHOT.jar` |

The **full bundle** is the easiest option — it is a shaded JAR containing the core driver and the SQL-to-Cypher translator, so you only need one JAR on the classpath.

## Use from a Maven Project

Add the snapshot dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.neo4j</groupId>
    <artifactId>neo4j-jdbc-full-bundle</artifactId>
    <version>6.11.1-SNAPSHOT</version>
</dependency>
```

No special repository configuration is needed — `./mvnw -Dfast package` installs to your local `~/.m2`, which Maven checks by default.

## Use from a Gradle Project

```kotlin
dependencies {
    implementation("org.neo4j:neo4j-jdbc-full-bundle:6.11.1-SNAPSHOT")
}
```

Ensure `mavenLocal()` is in your repositories block:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
```

## Use as a Standalone JAR (e.g., DBeaver, DataGrip)

Copy the full bundle JAR directly:

```bash
cp bundles/neo4j-jdbc-full-bundle/target/neo4j-jdbc-full-bundle-6.11.1-SNAPSHOT.jar /path/to/your/drivers/
```

Then configure your SQL tool to use that JAR as the JDBC driver. The driver class is `org.neo4j.jdbc.Neo4jDriver` and the JDBC URL format is `jdbc:neo4j://host:port`.

## Run the Unit Tests

To verify the GROUP BY / HAVING changes pass:

```bash
# All translator tests (445 tests)
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test

# Just the SQL-to-Cypher translation tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=SqlToCypherTests

# Just the field matcher tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=FieldMatcherTests

# Just the alias registry tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=AliasRegistryTests

# Just the jOOQ QOM diagnostic tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=JooqQomDiagnosticTests

# Living documentation tests (17 parameterized: verifies all SQL→Cypher examples)
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=GroupByDocumentationTests
```

### Unit Test Classes

| Class | Location | What it tests |
|-------|----------|---------------|
| `SqlToCypherTests` | `neo4j-jdbc-translator/impl` | Core SQL-to-Cypher translation (GROUP BY, HAVING, WHERE, ORDER BY, LIMIT, OFFSET, DISTINCT, JOINs) |
| `FieldMatcherTests` | `neo4j-jdbc-translator/impl` | Field-level matching logic for alias resolution |
| `AliasRegistryTests` | `neo4j-jdbc-translator/impl` | Alias registration and lookup (structural + name-based fallback) |
| `JooqQomDiagnosticTests` | `neo4j-jdbc-translator/impl` | jOOQ Query Object Model introspection for GROUP BY detection |
| `GroupByDocumentationTests` | `neo4j-jdbc-translator/impl` | 17 parameterized tests covering all documented SQL-to-Cypher translations; also generates markdown documentation via `generateMarkdown()` |

---

## Run Integration Tests (Requires Docker)

Integration tests use Testcontainers to spin up a real Neo4j instance:

```bash
# All GROUP BY / HAVING integration tests (79 tests: 56 GroupByIT + 23 HavingIT)
./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp -Dit.test="GroupByIT,HavingIT" verify

# Run only GROUP BY integration tests
./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp -Dit.test="GroupByIT" verify

# Run only HAVING integration tests
./mvnw -DskipUTs -Dcheckstyle.skip=true -pl neo4j-jdbc-it/neo4j-jdbc-it-cp -Dit.test="HavingIT" verify

# Full integration suite (all modules)
./mvnw -DskipUTs -Dcheckstyle.skip=true clean verify
```

### Integration Test: GroupByIT (56 tests)

**File:** `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/GroupByIT.java`

Uses People nodes (5 controlled rows) and the Movies graph for realistic cardinality.

| Nested Class | Tests | What it validates |
|-------------|-------|-------------------|
| `BasicGroupBy` | 8 | count, sum, avg, min/max, multiple aggregates, unique column, global aggregation |
| `GroupByNotInSelect` | 4 | WITH clause generation when GROUP BY column is hidden from SELECT |
| `MultipleGroupByColumns` | 7 | Two GROUP BY columns (both/one/neither in SELECT), Movies released grouping |
| `OrderByWithGroupBy` | 6 | ORDER BY on group column, aggregate DESC/ASC, multiple columns, min aggregate |
| `LimitOffsetWithGroupBy` | 5 | LIMIT, ORDER BY + LIMIT, top-N, LIMIT + OFFSET |
| `DistinctWithGroupBy` | 3 | DISTINCT on group column, with aggregate, Movies released |
| `GroupByWithWhere` | 5 | WHERE before GROUP BY, department exclusion, BETWEEN, Movies released filters |
| `GlobalAggregationRegression` | 5 | count, Movies count, multiple globals, count with WHERE, global sum |
| `EdgeCases` | 7 | Deterministic order, empty results, large cardinality, non-GROUP BY regression |
| `MultiAggregate` | 4 | All five aggregate functions, Movies multi-aggregate, ORDER BY total, WHERE + multi-aggregate |
| `RegressionTests` | 5 | SELECT *, LIKE, literal SELECT, INSERT then count, count with no results |

### Integration Test: HavingIT (23 tests)

**File:** `neo4j-jdbc-it/neo4j-jdbc-it-cp/src/test/java/org/neo4j/jdbc/it/cp/HavingIT.java`

Uses the same People + Movies data sets.

| Nested Class | Tests | What it validates |
|-------------|-------|-------------------|
| `BasicHavingFiltering` | 4 | count, sum, avg, max thresholds |
| `HavingAggregateNotInSelect` | 3 | Hidden `__having_col_*` columns absent from ResultSetMetaData |
| `CompoundHaving` | 3 | AND, OR, and nested `(AND) OR` conditions |
| `HavingByAlias` | 2 | `HAVING cnt > 1` and `HAVING total > 50` via alias resolution |
| `OrderByHavingCombined` | 3 | ORDER BY aggregate DESC, column ASC, ORDER BY + LIMIT with HAVING |
| `HavingWhereInteraction` | 2 | WHERE filters pre-aggregation, HAVING post-aggregation |
| `HavingWithJoin` | 3 | NATURAL JOIN with HAVING count, not-in-SELECT metadata, compound HAVING with min(born) |
| `HavingKitchenSink` | 3 | Full DISTINCT + WHERE + GROUP BY + HAVING + ORDER BY + LIMIT + OFFSET; multiple aggregates; Movies JOIN |

## Quick Smoke Test with Java

A minimal program to verify the translator works end-to-end:

```java
import java.sql.*;

public class GroupByTest {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:neo4j://localhost:7687?enableSQLTranslation=true";
        try (Connection conn = DriverManager.getConnection(url, "neo4j", "password")) {
            // Simple GROUP BY — implicit grouping
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                    "SELECT name, count(*) AS cnt FROM Person GROUP BY name");
                while (rs.next()) {
                    System.out.println(rs.getString("name") + ": " + rs.getInt("cnt"));
                }
            }

            // HAVING — post-aggregation filter
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                    "SELECT name, count(*) AS cnt FROM Person GROUP BY name HAVING cnt > 1");
                while (rs.next()) {
                    System.out.println(rs.getString("name") + ": " + rs.getInt("cnt"));
                }
            }
        }
    }
}
```

Compile and run with the full bundle JAR on the classpath:

```bash
javac -cp bundles/neo4j-jdbc-full-bundle/target/neo4j-jdbc-full-bundle-6.11.1-SNAPSHOT.jar GroupByTest.java
java -cp .:bundles/neo4j-jdbc-full-bundle/target/neo4j-jdbc-full-bundle-6.11.1-SNAPSHOT.jar GroupByTest
```
