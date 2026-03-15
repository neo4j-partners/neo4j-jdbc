# Cypher-DSL Builder Chain Reference

This document captures the Cypher-DSL builder type chain as used in this project's SQL-to-Cypher translator. It serves as a reference for anyone extending or improving the translator's DSL usage.

## Cypher-DSL Version

`2025.2.4` (managed via `org.neo4j:neo4j-cypher-dsl-bom` in the root POM)

## Core Imports

All from `org.neo4j.cypherdsl.core`:

```
StatementBuilder.OngoingReading
StatementBuilder.OngoingReadingWithWhere
StatementBuilder.OngoingReadingWithoutWhere
StatementBuilder.OngoingMatchAndReturnWithOrder
StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere
StatementBuilder.BuildableStatement<ResultStatement>
```

## Builder Chain: MATCH → WHERE → RETURN → ORDER BY → LIMIT

This is the standard chain used for SELECT translation (see `SqlToCypher.java` lines 473-481):

```
Cypher.match(List<PatternElement>)
  → OngoingReadingWithoutWhere

  .where(Condition)
  → OngoingReadingWithWhere

  .returning(List<Expression>)   OR  .returningDistinct(List<Expression>)
  → OngoingMatchAndReturnWithOrder

  .orderBy(List<SortItem>)
  → OngoingMatchAndReturnWithOrder

  .limit(Expression)
  → BuildableStatement<ResultStatement>

  .build()
  → ResultStatement
```

Both `OngoingReadingWithWhere` and `OngoingReadingWithoutWhere` satisfy `OngoingReading`, which exposes `.returning()` and `.returningDistinct()`.

## Builder Chain: MATCH → WHERE → WITH → WHERE → RETURN

This chain is needed for GROUP BY with HAVING or when grouping expressions differ from SELECT expressions. The WITH clause triggers Cypher's implicit aggregation and allows post-aggregation filtering.

The existing codebase uses WITH for Cypher-backed views (lines 505-524):

```java
// with() accepts List<IdentifiableElement> (aliased expressions)
StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere m1 =
    Cypher.callRawCypher(view.query()).with(previousAliases);
```

For aggregation, the pattern is:

```
OngoingReading                           (after MATCH + optional WHERE)
  .with(Expression... / List<IdentifiableElement>)
  → OrderableOngoingReadingAndWithWithoutWhere

  .where(Condition)                      (for HAVING translation)
  → OngoingReadingWithWhere              (or similar With-variant)

  .returning(List<Expression>)
  → OngoingMatchAndReturnWithOrder
  ... (then ORDER BY, LIMIT, build as above)
```

`OrderableOngoingReadingAndWithWithoutWhere` extends `OngoingReading`, so it can chain directly to `.returning()` if no HAVING/WHERE is needed after the WITH, or to `.where()` to add a post-aggregation filter.

## Key Type Relationships

- `OngoingReading` is the shared interface that exposes `.returning()`, `.returningDistinct()`, and `.with()`
- `OngoingReadingWithWhere` extends `OngoingReading` — result of adding `.where()`
- `OngoingReadingWithoutWhere` extends `OngoingReading` and can be cast to `OngoingReadingWithWhere`
- `OrderableOngoingReadingAndWithWithoutWhere` — result of `.with()`, also satisfies `OngoingReading`
- `OngoingMatchAndReturnWithOrder` — result of `.returning()`, exposes `.orderBy()` and `.limit()`

## Expression Aliasing for WITH

Expressions passed to `.with()` should be aliased using `.as()`:

```java
expression.as("alias_name")  // Returns IdentifiableElement / AliasedExpression
```

After the WITH, subsequent clauses reference the alias as a symbolic name:

```java
Cypher.name("alias_name")    // Returns SymbolicName, usable as Expression
```

## Condition Translation

The `condition(org.jooq.Condition c)` method (line 1857) translates jOOQ conditions to Cypher-DSL conditions. It handles: And, Or, Xor, Not, Eq, Gt, Ge, Lt, Le, Ne, Between, IsNull, IsNotNull, Like, InList, and Row variants. This same method can be reused for HAVING condition translation — the HAVING condition from jOOQ is just an `org.jooq.Condition`.

## Aggregate Function Mapping

All handled in the `expression(Field<?> f)` method (lines 1775-1807):

| jOOQ QOM Type | Cypher-DSL Call |
|---|---|
| `QOM.Count` | `Cypher.count(exp)` / `Cypher.countDistinct(exp)` |
| `QOM.Min` | `Cypher.min(exp)` |
| `QOM.Max` | `Cypher.max(exp)` |
| `QOM.Sum` | `Cypher.sum(exp)` |
| `QOM.Avg` | `Cypher.avg(exp)` |
| `QOM.StddevSamp` | `Cypher.stDev(exp)` |
| `QOM.StddevPop` | `Cypher.stDevP(exp)` |
| `QOM.Function` (generic) | `Cypher.call(name).withArgs(...).asFunction()` |

## jOOQ Select QOM Accessors

Methods available on `Select<?>` for query decomposition:

| Accessor | Returns | Currently Used |
|---|---|---|
| `$select()` | `List<SelectFieldOrAsterisk>` | Yes |
| `$from()` | `List<Table<?>>` | Yes |
| `$where()` | `Condition` (nullable) | Yes |
| `$orderBy()` | `List<SortField<?>>` | Yes |
| `$limit()` | `Field<?>` (nullable) | Yes |
| `$distinct()` | `boolean` | Yes |
| `$groupBy()` | `List<? extends GroupField>` | **No — needed for GROUP BY** |
| `$having()` | `Condition` (nullable) | **No — needed for HAVING** |
