# Code Review Cleanup: Phase 1-4 Test Quality

## Background

Phases 1-4 of the GROUP BY/HAVING implementation added ~160 lines to `SqlToCypherTests.java` and created three new test files. A principal-level code review identified the issues below. All findings are scoped to test code in:

```
neo4j-jdbc-translator/impl/src/test/java/org/neo4j/jdbc/translator/impl/
```

### Files involved

| File | Lines | Status |
|------|-------|--------|
| `SqlToCypherTests.java` | 1,076 | Modified (Phase 1 + Phase 4 tests added) |
| `FieldMatcherTests.java` | 420 | New (Phase 2) |
| `AliasRegistryTests.java` | 379 | New (Phase 3) |
| `JooqQomDiagnosticTests.java` | 788 | New (Phase 1) |

### File length verdict

**No split needed yet.** At 1,076 lines, `SqlToCypherTests.java` is the 4th largest test file in the project. The largest is `ResultSetImplTests.java` at 1,645 lines. There is no checkstyle file-length limit. However, Phases 5-7 will add ~40+ more tests. If the file crosses ~1,200 lines after Phase 7, extract everything from line 821 onward into `SqlToCypherGroupByTests.java`.

---

## Issue 1 (P0): Missing comment on `aggregates()` behavior change

**File:** `SqlToCypherTests.java`, lines 551-563

The `aggregates()` `@ParameterizedTest` has 9 CsvSource entries. Entries 1-3 produce simple `RETURN` output (GROUP BY column `name` is in SELECT). Entries 4-9 produce `WITH ... RETURN` output (GROUP BY column `name` is absent from SELECT, triggering WITH clause generation). There is no comment explaining why a single test method has two different output shapes.

### What to do

Add a comment above the `aggregates()` method explaining the split:

```java
// Entries 1-3: GROUP BY column (name) appears in SELECT — Cypher uses simple RETURN.
// Entries 4-9: GROUP BY column (name) is absent from SELECT — translator generates a
// WITH clause to make grouping explicit. Updated in Phase 4 (was previously wrong output).
@ParameterizedTest
@CsvSource(delimiterString = "|",
        textBlock = """
```

---

## Issue 2 (P1): Triplicated helper methods across test files

Three test files duplicate the same jOOQ parser setup and helper methods:

| Method | FieldMatcherTests | AliasRegistryTests | JooqQomDiagnosticTests |
|--------|:-:|:-:|:-:|
| `static DSLContext dsl` | line 44 | line 44 | line 49 |
| `static Parser parser` | line 46 | line 46 | line 51 |
| `@BeforeAll initParser()` | line 49 | line 49 | line 54 |
| `parseSelect(String)` | line 54 | line 54 | line 59 |
| `unwrapAlias()` / `unwrapSelectField()` | line 64 | line 63 | — |
| `asField()` | line 74 | — | — |
| `getAliasName()` | — | line 73 | — |

`parseSelect()` is copied verbatim 3 times. `unwrapAlias()` and `unwrapSelectField()` are functionally identical (differ only in name).

### What to do

Create a shared abstract base class. All three test files extend it.

**Create:** `neo4j-jdbc-translator/impl/src/test/java/org/neo4j/jdbc/translator/impl/QomTestSupport.java`

```java
/*
 * Copyright (c) 2023-2026 "Neo4j,"
 * Neo4j Sweden AB [https://neo4j.com]
 * ... (standard license header — run ./mvnw license:format to generate)
 */
package org.neo4j.jdbc.translator.impl;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Parser;
import org.jooq.Select;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.junit.jupiter.api.BeforeAll;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared setup and helpers for tests that parse SQL through jOOQ and inspect the
 * Query Object Model (QOM). Provides parser initialization, field unwrapping,
 * and alias extraction utilities.
 *
 * @author Ryan Knight
 */
abstract class QomTestSupport {

    static DSLContext dsl;

    static Parser parser;

    @BeforeAll
    static void initParser() {
        dsl = DSL.using(org.jooq.SQLDialect.DEFAULT);
        parser = dsl.parser();
    }

    Select<?> parseSelect(String sql) {
        var query = parser.parseQuery(sql);
        assertThat(query).isInstanceOf(Select.class);
        return (Select<?>) query;
    }

    /**
     * Extracts the underlying field from a {@link SelectFieldOrAsterisk}, unwrapping
     * any alias wrapper. Returns the expression, not the alias.
     */
    static Field<?> unwrapAlias(SelectFieldOrAsterisk sfa) {
        if (sfa instanceof QOM.FieldAlias<?> fa) {
            return fa.$field();
        }
        return (Field<?>) sfa;
    }

    /**
     * Casts a {@link SelectFieldOrAsterisk} to {@link Field} without unwrapping aliases.
     */
    static Field<?> asField(SelectFieldOrAsterisk sfa) {
        return (Field<?>) sfa;
    }

    /**
     * Extracts the alias name from a select field. Returns the field name if not aliased.
     */
    static String getAliasName(SelectFieldOrAsterisk sfa) {
        return ((Field<?>) sfa).getName();
    }

}
```

**Then update each test file:**

1. **`FieldMatcherTests.java`** — Change `class FieldMatcherTests` to `class FieldMatcherTests extends QomTestSupport`. Delete:
   - `private static DSLContext dsl;` (line 44)
   - `private static Parser parser;` (line 46)
   - `@BeforeAll static void initParser()` block (lines 48-52)
   - `private Select<?> parseSelect(String sql)` method (lines 54-58)
   - `private static Field<?> unwrapAlias(SelectFieldOrAsterisk sfa)` method (lines 64-69)
   - `private static Field<?> asField(SelectFieldOrAsterisk sfa)` method (lines 74-76)
   - Remove now-unused imports: `DSLContext`, `Parser`, `DSL`, `BeforeAll`

2. **`AliasRegistryTests.java`** — Change to `extends QomTestSupport`. Delete:
   - `private static DSLContext dsl;` (line 44)
   - `private static Parser parser;` (line 46)
   - `@BeforeAll static void initParser()` block (lines 48-52)
   - `private Select<?> parseSelect(String sql)` method (lines 54-58)
   - `private Field<?> unwrapSelectField(SelectFieldOrAsterisk sfa)` method (lines 63-68) — replaced by inherited `unwrapAlias()`
   - `private String getAliasName(SelectFieldOrAsterisk sfa)` method (lines 73-75)
   - Update all calls from `unwrapSelectField(...)` to `unwrapAlias(...)` (grep for `unwrapSelectField` in the file — approximately 12 occurrences in the test methods)
   - Remove now-unused imports

3. **`JooqQomDiagnosticTests.java`** — Change to `extends QomTestSupport`. Delete:
   - `private static DSLContext dsl;` (line 49)
   - `private static Parser parser;` (line 51)
   - `@BeforeAll static void initParser()` block (lines 53-57)
   - `private Select<?> parseSelect(String sql)` method (lines 59-63)
   - This file has additional helpers (`unwrapAlias` at line 69 and `fieldFromHaving` at line 81) that are specific to diagnostic tests. `unwrapAlias` can be deleted since it duplicates the base class. `fieldFromHaving` stays.
   - Remove now-unused imports

### Important: `@BeforeAll` inheritance

JUnit 5 `@BeforeAll` methods in a superclass are inherited and run before the subclass lifecycle. Since the base class `initParser()` is `static`, it works with `@TestInstance(PER_CLASS)` or default lifecycle. No changes to test lifecycle are needed.

However, `AliasRegistryTests` has `@BeforeEach` methods in its `@Nested` classes that create fresh `AliasRegistry` instances — these are unaffected and stay as-is.

---

## Issue 3 (P1): `withClauseGeneration` test is thin

**File:** `SqlToCypherTests.java`, lines 967-980

The `withClauseGeneration` `@ParameterizedTest` has 5 entries. The plan estimated ~10. Missing scenarios that will serve as regression baselines for Phases 5-6:

1. **HAVING + WITH clause** — e.g., `SELECT sum(age) FROM People p GROUP BY name HAVING sum(age) > 100`
2. **ORDER BY + WITH clause** — e.g., `SELECT sum(age) FROM People p GROUP BY name ORDER BY sum(age)`

### What to do

Add these entries to the `withClauseGeneration` CsvSource. To get the expected Cypher output, run each SQL through the translator:

```java
var translator = SqlToCypher.defaultTranslator();
System.out.println(translator.translate("SELECT sum(age) FROM People p GROUP BY name HAVING sum(age) > 100"));
System.out.println(translator.translate("SELECT sum(age) FROM People p GROUP BY name ORDER BY sum(age)"));
```

Or add them with a deliberately wrong expected value (like `TODO`), run the test, and capture the actual output from the failure message. Then update the expected value to match.

These are baseline tests — they capture the *current* behavior (which may be incomplete for HAVING/ORDER BY with WITH). When Phases 5-6 implement the correct handling, these expected values will be updated.

---

## Issue 4 (P2): Missing `@author` tag

**File:** `SqlToCypherTests.java`, lines 72-75

The javadoc currently lists:
```java
/**
 * @author Michael J. Simons
 * @author Michael Hunger
 */
```

Since ~20% of the file (167 lines) was added by Ryan Knight, add:
```java
/**
 * @author Michael J. Simons
 * @author Michael Hunger
 * @author Ryan Knight
 */
```

---

## Issue 5 (P2): Undocumented duplicate test entry

**File:** `SqlToCypherTests.java`

`SELECT DISTINCT name FROM People p` with expected output `MATCH (p:People) RETURN DISTINCT p.name AS name` appears at:
- Line 834 in `snapshotSimpleSelects`
- Line 933 in `snapshotDistinct`

The overlap between `snapshotGroupByMatchingSelect` and `aggregates[1-3]` is documented (comment at line 858), but this overlap is not.

### What to do

Remove the duplicate entry from `snapshotDistinct` (line 933). The `snapshotDistinct` method should keep only its unique entry:

```java
@ParameterizedTest
@CsvSource(delimiterString = "|",
        textBlock = """
                SELECT DISTINCT name, age FROM People p WHERE age > 25|MATCH (p:People) WHERE p.age > 25 RETURN DISTINCT p.name AS name, p.age AS age
                """)
void snapshotDistinct(String sql, String cypher) {
```

Note: A `@ParameterizedTest` with a single CsvSource entry is fine — it still serves as a categorized regression guard.

---

## Issue 6 (P3): Section separator comments diverge from existing style

**File:** `SqlToCypherTests.java`, lines 821-824 and 962-965

```java
// -------------------------------------------------------------------------
// Tier 1 Regression Snapshots — lock down existing translator behavior
// before any production code changes. See prodTesting.md §1.1–1.3.
// -------------------------------------------------------------------------
```

The original `SqlToCypherTests.java` (lines 1-819, authored by Michael J. Simons and Michael Hunger) does not use section separators anywhere. No other test file in the project uses this pattern either. The method names and `@CsvSource` annotations already communicate the categories.

### What to do

Remove both separator blocks (lines 821-824 and 962-965). Keep the separators in `FieldMatcherTests.java` and `AliasRegistryTests.java` where they complement the `@Nested` class structure — that's a different file and a different organizational style.

---

## Verification

After all changes, run:

```bash
# 1. Run all translator unit tests
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test \
  -Dcheckstyle.skip=true -Dspring-javaformat.skip=true -Dlicense.skip=true \
  -Dsurefire.failIfNoSpecifiedTests=false

# 2. Apply formatting (Spring JavaFormat)
./mvnw spring-javaformat:apply -pl neo4j-jdbc-translator/impl

# 3. Apply license headers (for new QomTestSupport.java)
./mvnw license:format -pl neo4j-jdbc-translator/impl

# 4. Re-run tests after formatting to confirm nothing broke
./mvnw -DskipITs -am -pl neo4j-jdbc-translator/impl test \
  -Dcheckstyle.skip=true -Dspring-javaformat.skip=true -Dlicense.skip=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Expected outcome:** All tests pass. Test count should be 390 + however many new `withClauseGeneration` entries were added (expect ~392-393 total). Zero failures.
