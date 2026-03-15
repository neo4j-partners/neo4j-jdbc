# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Neo4j JDBC Driver — a JDBC 4.3 compliant driver that connects directly to Neo4j via the Bolt protocol. It does not wrap the common Neo4j Java Driver; it implements Bolt connectivity directly. No internal connection pooling (delegates to JDBC pool implementations like HikariCP).

## Build Commands

Uses Maven with the Maven Wrapper (`./mvnw`). Requires JDK 17+.

```bash
# Full build with all tests
./mvnw verify

# Fast build (skip tests, checkstyle, and other validation)
./mvnw -Dfast package

# Unit tests only (core driver module)
./mvnw -DskipITs -am -pl neo4j-jdbc clean package

# Integration tests only (requires Docker for Testcontainers)
./mvnw -DskipUTs -Dcheckstyle.skip=true clean verify

# Skip cluster or reauthentication integration tests
./mvnw -DskipClusterIT verify
./mvnw -DskipReauthenticationIT verify

# Run a single test class
./mvnw -DskipITs -am -pl neo4j-jdbc test -Dtest=StatementImplTests

# Run a single test method
./mvnw -DskipITs -am -pl neo4j-jdbc test -Dtest=StatementImplTests#methodName
```

## Code Style

- **Formatter**: Spring JavaFormat — apply with `./mvnw spring-javaformat:apply`
- **Linter**: Checkstyle (config at `etc/checkstyle/config.xml`)
- **POM ordering**: Keep sorted with `./mvnw sortpom:sort`
- **License headers**: Apply with `./mvnw license:format`
- Public classes require `@author` javadoc tag
- No `System.out` — use proper logging

## Module Structure

| Module | Purpose |
|---|---|
| `neo4j-jdbc` | Core JDBC driver implementation |
| `neo4j-jdbc-authn/spi` | Authentication SPI |
| `neo4j-jdbc-authn/kc` | Keycloak SSO authentication |
| `neo4j-jdbc-translator/spi` | SQL-to-Cypher translator SPI |
| `neo4j-jdbc-translator/impl` | Default SQL-to-Cypher translator |
| `neo4j-jdbc-translator/sparkcleaner` | Spark SQL statement cleaner |
| `neo4j-jdbc-translator/text2cypher` | LLM-based text-to-Cypher |
| `neo4j-jdbc-tracing/micrometer` | Micrometer observability |
| `neo4j-jdbc-it/` | Integration test suites (stub, cp, sso, mp, framework smoke tests) |
| `bundles/` | Distribution bundles (core, full, text2cypher) |

## Architecture

**Core class hierarchy** in `org.neo4j.jdbc`:
- `Neo4jDriver` → `ConnectionImpl` → `StatementImpl` / `PreparedStatementImpl` (sealed) / `CallableStatementImpl` (final)
- `ResultSetImpl` (final) backed by cursor system (`BoltCursor` for protocol results, `LocalCursor` for in-memory)
- `DatabaseMetadataImpl` for schema introspection via Cypher procedures

**Key patterns**:
- **SPI via ServiceLoader**: Translators, authentication providers, and tracers are discovered at runtime through `META-INF/services`
- **Sealed classes**: Statement hierarchy uses sealed/final for controlled extensibility
- **Event listeners**: `ConnectionListener` and `DriverListener` for lifecycle hooks

**Package layout** (core module `neo4j-jdbc/src/main/java/org/neo4j/jdbc/`):
- Root: JDBC interface implementations
- `events/`: Lifecycle event listeners
- `internal/bolt/`: Bolt protocol implementation (internal, not public API)
- `values/`: Neo4j type system (Node, Relationship, Path, etc.)

## Testing

- **Unit tests**: JUnit 5 + Mockito + AssertJ, in each module's `src/test/`
- **Integration tests**: Testcontainers with Neo4j Docker image, in `neo4j-jdbc-it/` submodules
- **Architecture tests**: ArchUnit for enforcing structural rules
- **Naming**: Unit test classes end in `*Tests.java`, integration test classes end in `*IT.java`
- **Framework smoke tests**: Spring Boot, Quarkus, Hibernate, MyBatis modules verify real-world compatibility

## Java Module System

The project uses JPMS (`module-info.java`). Be aware of module boundaries when adding dependencies or cross-module references.

## GraalVM Native Image

Native image configs are maintained in `src/main/resources/META-INF/native-image/`. Update reflection/resource configs when adding classes that need runtime reflection.
