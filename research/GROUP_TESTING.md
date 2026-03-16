# Building and Testing the GROUP BY / HAVING Changes Locally

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
# All translator tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test

# Just the SQL-to-Cypher translation tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=SqlToCypherTests

# Just the field matcher tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=FieldMatcherTests

# Just the alias registry tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=AliasRegistryTests

# Just the jOOQ QOM diagnostic tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test -Dtest=JooqQomDiagnosticTests
```

## Run Integration Tests (Requires Docker)

Integration tests use Testcontainers to spin up a real Neo4j instance:

```bash
./mvnw -DskipUTs -Dcheckstyle.skip=true clean verify
```

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
