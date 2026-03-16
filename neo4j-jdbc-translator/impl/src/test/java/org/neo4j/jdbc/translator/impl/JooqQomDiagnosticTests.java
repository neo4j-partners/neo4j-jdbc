/*
 * Copyright (c) 2023-2026 "Neo4j,"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.jdbc.translator.impl;

import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.QOM;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic tests for jOOQ Query Object Model (QOM) behavior. These tests do not test
 * the SQL-to-Cypher translator itself. They document how jOOQ represents GROUP BY,
 * HAVING, ORDER BY, and DISTINCT in its AST, so that the translator implementation can
 * rely on verified assumptions rather than guesses.
 * <p>
 * These tests also serve as regression guards: if a future jOOQ upgrade changes any of
 * these QOM behaviors, these tests will catch it.
 *
 * @author Ryan Knight
 */
class JooqQomDiagnosticTests extends QomTestSupport {

	// -------------------------------------------------------------------------
	// HAVING: QOM structure
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("HAVING clause QOM representation")
	class HavingTests {

		@Test
		@DisplayName("HAVING count(*) > 5 produces QOM.Gt with QOM.Count on the left")
		void havingByFunctionForm() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5");

			var having = select.$having();
			assertThat(having).isNotNull();
			assertThat(having).isInstanceOf(QOM.Gt.class);

			var gt = (QOM.Gt<?>) having;
			assertThat(gt.$arg1()).isInstanceOf(QOM.Count.class);

			// FINDING: jOOQ 3.19.x represents the asterisk in count(*) as an
			// SQLField (internal type), not as org.jooq.Asterisk. The structural
			// matcher must check for "*".equals(field.toString()) rather than
			// instanceof Asterisk.
			var count = (QOM.Count) gt.$arg1();
			assertThat(count.$field().toString()).isEqualTo("*");
		}

		@Test
		@DisplayName("HAVING by alias — document exactly what jOOQ produces for HAVING cnt > 5")
		void havingByAlias() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING cnt > 5");

			var having = select.$having();
			assertThat(having).isNotNull();
			assertThat(having).isInstanceOf(QOM.Gt.class);

			var gt = (QOM.Gt<?>) having;
			var lhs = gt.$arg1();
			assertThat(lhs).isInstanceOf(Field.class);

			// DEFINITIVE TEST: Assert that jOOQ does NOT resolve alias to aggregate.
			// If this fails, jOOQ changed behavior and we should update the matcher.
			assertThat(lhs).isNotInstanceOf(QOM.Count.class);

			// FINDING: jOOQ 3.19.x keeps 'cnt' as an unresolved field reference
			// (not resolved to QOM.Count). The alias registry must support
			// name-based lookup in addition to structural aggregate matching.
			assertThat(lhs.getName().toUpperCase()).isEqualTo("CNT");
		}

		@Test
		@DisplayName("Compound HAVING with AND produces QOM.And with two comparison children")
		void compoundHaving() {
			var select = parseSelect(
					"SELECT name, count(*) AS cnt, max(age) AS max_age FROM People GROUP BY name HAVING count(*) > 5 AND max(age) > 50");

			var having = select.$having();
			assertThat(having).isNotNull();
			assertThat(having).isInstanceOf(QOM.And.class);

			var and = (QOM.And) having;
			assertThat(and.$arg1()).isInstanceOf(QOM.Gt.class);
			assertThat(and.$arg2()).isInstanceOf(QOM.Gt.class);

			// Left branch: count(*) > 5
			var left = (QOM.Gt<?>) and.$arg1();
			assertThat(left.$arg1()).isInstanceOf(QOM.Count.class);

			// Right branch: max(age) > 50
			var right = (QOM.Gt<?>) and.$arg2();
			assertThat(right.$arg1()).isInstanceOf(QOM.Max.class);
		}

		@Test
		@DisplayName("HAVING with arithmetic: max(salary) > 2 * avg(salary)")
		void havingWithArithmetic() {
			var select = parseSelect(
					"SELECT department, max(salary) AS max_sal, avg(salary) AS avg_sal FROM Employees GROUP BY department HAVING max(salary) > 2 * avg(salary)");

			var having = select.$having();
			assertThat(having).isNotNull();
			assertThat(having).isInstanceOf(QOM.Gt.class);

			var gt = (QOM.Gt<?>) having;
			// Left side: max(salary)
			assertThat(gt.$arg1()).isInstanceOf(QOM.Max.class);

			// Right side: 2 * avg(salary) — should be QOM.Mul
			assertThat(gt.$arg2()).isInstanceOf(QOM.Mul.class);

			var mul = (QOM.Mul<?>) gt.$arg2();
			// One operand should be avg(salary)
			boolean hasAvg = mul.$arg1() instanceof QOM.Avg || mul.$arg2() instanceof QOM.Avg;
			assertThat(hasAvg).as("Multiplication should contain avg(salary)").isTrue();
		}

		@Test
		@DisplayName("HAVING aggregate not in SELECT: SELECT name FROM People GROUP BY name HAVING count(*) > 5")
		void havingAggregateNotInSelect() {
			var select = parseSelect("SELECT name FROM People GROUP BY name HAVING count(*) > 5");

			// $having() should still contain the count(*) even though it's not in
			// $select()
			var having = select.$having();
			assertThat(having).isNotNull();
			assertThat(having).isInstanceOf(QOM.Gt.class);

			var gt = (QOM.Gt<?>) having;
			assertThat(gt.$arg1()).isInstanceOf(QOM.Count.class);

			// Confirm count(*) is absent from $select()
			var selectFields = select.$select();
			boolean hasCountInSelect = selectFields.stream()
				.anyMatch(f -> f instanceof QOM.Count
						|| (f instanceof QOM.FieldAlias<?> fa && fa.$field() instanceof QOM.Count));
			assertThat(hasCountInSelect).as("count(*) should NOT be in SELECT list").isFalse();
		}

		@Test
		@DisplayName("HAVING with OR: count(*) > 10 OR sum(amount) > 1000")
		void havingWithOr() {
			var select = parseSelect(
					"SELECT category, count(*) AS cnt, sum(amount) AS total FROM Products GROUP BY category HAVING count(*) > 10 OR sum(amount) > 1000");

			var having = select.$having();
			assertThat(having).isNotNull();
			assertThat(having).isInstanceOf(QOM.Or.class);

			var or = (QOM.Or) having;
			assertThat(or.$arg1()).isInstanceOf(QOM.Gt.class);
			assertThat(or.$arg2()).isInstanceOf(QOM.Gt.class);
		}

		@Test
		@DisplayName("HAVING with >= operator — confirm QOM.Ge structure matches QOM.Gt pattern")
		void havingWithGreaterThanOrEqual() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) >= 10");

			var having = select.$having();
			assertThat(having).isNotNull();

			// DEFINITIVE TEST: >= produces QOM.Ge, not QOM.Gt. The condition
			// translator dispatches on each comparison type, so we verify the
			// pattern holds for Ge the same way it does for Gt.
			assertThat(having).isInstanceOf(QOM.Ge.class);

			var ge = (QOM.Ge<?>) having;
			assertThat(ge.$arg1()).isInstanceOf(QOM.Count.class);
		}

		@Test
		@DisplayName("HAVING with BETWEEN — verify QOM.Between structure")
		void havingWithBetween() {
			var select = parseSelect(
					"SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) BETWEEN 5 AND 20");

			var having = select.$having();
			assertThat(having).isNotNull();

			// FINDING: HAVING with BETWEEN produces QOM.Between, which is a
			// different condition type than the simple comparisons (Gt, Ge, etc.).
			// The condition translator already handles Between, but we document
			// its structure here for completeness.
			assertThat(having).isInstanceOf(QOM.Between.class);

			var between = (QOM.Between<?>) having;
			assertThat(between.$arg1()).isInstanceOf(QOM.Count.class);
		}

		@Test
		@DisplayName("HAVING with IN list — verify QOM.InList structure")
		void havingWithInList() {
			var select = parseSelect(
					"SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) IN (1, 2, 3)");

			var having = select.$having();
			assertThat(having).isNotNull();

			// FINDING: HAVING with IN produces QOM.InList. The aggregate
			// appears as the first argument. The condition translator already
			// handles InList, but we verify the aggregate is accessible for
			// alias resolution.
			assertThat(having).isInstanceOf(QOM.InList.class);

			var inList = (QOM.InList<?>) having;
			assertThat(inList.$arg1()).isInstanceOf(QOM.Count.class);
		}

		@Test
		@DisplayName("HAVING with multiple aggregate types: HAVING min(x) > 0 AND avg(x) < 100 AND sum(x) > 50")
		void havingMultipleAggregateTypes() {
			var select = parseSelect(
					"SELECT name, min(score) AS mn, avg(score) AS av, sum(score) AS sm FROM Results GROUP BY name HAVING min(score) > 0 AND avg(score) < 100 AND sum(score) > 50");

			var having = select.$having();
			assertThat(having).isNotNull();

			// Walk the condition tree and collect all aggregate types found
			var aggregateTypes = new java.util.ArrayList<Class<?>>();
			collectAggregateTypes(having, aggregateTypes);

			assertThat(aggregateTypes).as("Should find min, avg, and sum aggregates in HAVING").hasSize(3);
			assertThat(aggregateTypes).anyMatch(QOM.Min.class::equals);
			assertThat(aggregateTypes).anyMatch(QOM.Avg.class::equals);
			assertThat(aggregateTypes).anyMatch(QOM.Sum.class::equals);
		}

		private void collectAggregateTypes(org.jooq.Condition condition, java.util.List<Class<?>> types) {
			if (condition instanceof QOM.And and) {
				collectAggregateTypes(and.$arg1(), types);
				collectAggregateTypes(and.$arg2(), types);
			}
			else if (condition instanceof QOM.CombinedCondition<?> combined) {
				collectAggregateTypes(combined.$arg1(), types);
				collectAggregateTypes(combined.$arg2(), types);
			}
			else if (condition instanceof QOM.CompareCondition<?, ?> cmp) {
				collectFieldAggregateType(cmp.$arg1(), types);
				collectFieldAggregateType(cmp.$arg2(), types);
			}
		}

		private void collectFieldAggregateType(Field<?> field, java.util.List<Class<?>> types) {
			if (field instanceof QOM.Count) {
				types.add(QOM.Count.class);
			}
			else if (field instanceof QOM.Sum) {
				types.add(QOM.Sum.class);
			}
			else if (field instanceof QOM.Min) {
				types.add(QOM.Min.class);
			}
			else if (field instanceof QOM.Max) {
				types.add(QOM.Max.class);
			}
			else if (field instanceof QOM.Avg) {
				types.add(QOM.Avg.class);
			}
		}

	}

	// -------------------------------------------------------------------------
	// GROUP BY: QOM structure
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("GROUP BY clause QOM representation")
	class GroupByTests {

		@Test
		@DisplayName("GROUP BY column present in SELECT — field appears in both lists")
		void groupByColumnInSelect() {
			var select = parseSelect("SELECT name, count(*) FROM People GROUP BY name");

			var groupBy = select.$groupBy();
			assertThat(groupBy).hasSize(1);
			assertThat(groupBy.get(0)).isInstanceOf(Field.class);

			var groupField = (Field<?>) groupBy.get(0);
			assertThat(groupField.getName().toUpperCase()).isEqualTo("NAME");
		}

		@Test
		@DisplayName("GROUP BY column NOT in SELECT — field is in $groupBy() but not $select()")
		void groupByColumnNotInSelect() {
			var select = parseSelect("SELECT sum(age) FROM People GROUP BY name");

			var groupBy = select.$groupBy();
			assertThat(groupBy).hasSize(1);

			var groupField = (Field<?>) groupBy.get(0);
			assertThat(groupField.getName().toUpperCase()).isEqualTo("NAME");

			// Verify SELECT does not contain 'name'
			var selectFieldNames = select.$select()
				.stream()
				.filter(f -> f instanceof Field<?>)
				.map(f -> ((Field<?>) f).getName().toUpperCase())
				.toList();
			assertThat(selectFieldNames).doesNotContain("NAME");
		}

		@Test
		@DisplayName("GROUP BY on a join query — verify table qualifiers are accessible")
		void groupByOnJoinQuery() {
			var select = parseSelect(
					"SELECT c.name, count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name");

			var groupBy = select.$groupBy();
			assertThat(groupBy).hasSize(1);

			var groupField = (Field<?>) groupBy.get(0);
			assertThat(groupField.getName().toUpperCase()).isEqualTo("NAME");

			// DEFINITIVE TEST: Assert that jOOQ preserves table qualifier on
			// GROUP BY fields in join queries. If this fails, jOOQ changed
			// behavior and the field matcher needs to handle unqualified fields
			// in join contexts.
			assertThat(groupField).isInstanceOf(TableField.class);
			var tf = (TableField<?, ?>) groupField;
			assertThat(tf.getTable()).isNotNull();
			assertThat(tf.getTable().getName().toUpperCase()).isEqualTo("C");
		}

		@Test
		@DisplayName("Multiple GROUP BY columns — all appear in $groupBy() list")
		void multipleGroupByColumns() {
			var select = parseSelect(
					"SELECT department, location, count(*) FROM Employees GROUP BY department, location");

			var groupBy = select.$groupBy();
			assertThat(groupBy).hasSize(2);

			var names = groupBy.stream()
				.filter(gf -> gf instanceof Field<?>)
				.map(gf -> ((Field<?>) gf).getName().toUpperCase())
				.toList();
			assertThat(names).containsExactly("DEPARTMENT", "LOCATION");
		}

		@Test
		@DisplayName("No GROUP BY — $groupBy() returns empty list")
		void noGroupBy() {
			var select = parseSelect("SELECT name FROM People");

			var groupBy = select.$groupBy();
			assertThat(groupBy).isEmpty();
		}

		@Test
		@DisplayName("GROUP BY ordinal — jOOQ does NOT resolve ordinal to actual column")
		void groupByOrdinal() {
			var select = parseSelect("SELECT name, count(*) FROM People GROUP BY 1");

			var groupBy = select.$groupBy();
			assertThat(groupBy).hasSize(1);

			// FINDING: Contrary to earlier assumption, jOOQ 3.19.x does NOT resolve
			// ordinal GROUP BY to the actual column. It keeps the literal "1".
			// The translator must handle ordinal GROUP BY explicitly by resolving
			// the ordinal against the SELECT list.
			var groupField = (Field<?>) groupBy.get(0);
			assertThat(groupField.getName()).isEqualTo("1");
		}

	}

	// -------------------------------------------------------------------------
	// ORDER BY: QOM structure and interaction with aggregates
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("ORDER BY clause QOM representation")
	class OrderByTests {

		@Test
		@DisplayName("ORDER BY aggregate function — verify QOM type is QOM.Count or field ref")
		void orderByAggregateFunction() {
			var select = parseSelect("SELECT name, count(*) FROM People GROUP BY name ORDER BY count(*)");

			var orderBy = select.$orderBy();
			assertThat(orderBy).hasSize(1);

			var sortField = orderBy.get(0);
			var field = sortField.$field();
			assertThat(field).isInstanceOf(Field.class);

			// DEFINITIVE TEST: Assert that jOOQ DOES preserve the aggregate
			// structure when ORDER BY uses the function form (not an alias).
			assertThat(field).isInstanceOf(QOM.Count.class);

			// FINDING: jOOQ 3.19.x preserves QOM.Count in ORDER BY when the
			// function form is used directly (ORDER BY count(*)). The structural
			// matcher can handle this case via aggregate matching.
			var count = (QOM.Count) field;
			assertThat(count.$field().toString()).isEqualTo("*");
		}

		@Test
		@DisplayName("ORDER BY alias — document exactly what jOOQ produces for ORDER BY cnt")
		void orderByAlias() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name ORDER BY cnt");

			var orderBy = select.$orderBy();
			assertThat(orderBy).hasSize(1);

			var sortField = orderBy.get(0);
			var field = sortField.$field();
			assertThat(field).isInstanceOf(Field.class);

			// DEFINITIVE TEST: Assert that jOOQ does NOT resolve alias to aggregate.
			assertThat(field).isNotInstanceOf(QOM.Count.class);

			// FINDING: jOOQ 3.19.x keeps 'cnt' as an unresolved field reference
			// in ORDER BY, same as HAVING. The alias registry must support
			// name-based lookup for ORDER BY fields too.
			assertThat(field.getName().toUpperCase()).isEqualTo("CNT");
		}

		@Test
		@DisplayName("ORDER BY DESC preserves sort direction")
		void orderByDesc() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name ORDER BY cnt DESC");

			var orderBy = select.$orderBy();
			assertThat(orderBy).hasSize(1);

			var sortField = orderBy.get(0);
			assertThat(sortField.$sortOrder()).isEqualTo(org.jooq.SortOrder.DESC);
		}

		@Test
		@DisplayName("ORDER BY column not in GROUP BY or aggregate context — simple column")
		void orderBySimpleColumn() {
			var select = parseSelect("SELECT name FROM People ORDER BY name");

			var orderBy = select.$orderBy();
			assertThat(orderBy).hasSize(1);

			var field = orderBy.get(0).$field();
			assertThat(field.getName().toUpperCase()).isEqualTo("NAME");
		}

		@Test
		@DisplayName("ORDER BY multiple columns with mixed directions")
		void orderByMultipleMixed() {
			var select = parseSelect(
					"SELECT department, count(*) AS cnt, avg(salary) AS avg_sal FROM Employees GROUP BY department ORDER BY cnt DESC, avg_sal ASC");

			var orderBy = select.$orderBy();
			assertThat(orderBy).hasSize(2);

			assertThat(orderBy.get(0).$sortOrder()).isEqualTo(org.jooq.SortOrder.DESC);
			assertThat(orderBy.get(1).$sortOrder()).isEqualTo(org.jooq.SortOrder.ASC);
		}

	}

	// -------------------------------------------------------------------------
	// DISTINCT: interaction with GROUP BY
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("DISTINCT flag behavior")
	class DistinctTests {

		@Test
		@DisplayName("SELECT DISTINCT with GROUP BY — $distinct() returns true")
		void distinctWithGroupBy() {
			var select = parseSelect("SELECT DISTINCT name, count(*) FROM People GROUP BY name");

			assertThat(select.$distinct()).isTrue();

			// GROUP BY and SELECT should be unaffected by DISTINCT flag
			var groupBy = select.$groupBy();
			assertThat(groupBy).hasSize(1);
			assertThat(select.$select()).hasSize(2);
		}

		@Test
		@DisplayName("SELECT without DISTINCT — $distinct() returns false")
		void nonDistinct() {
			var select = parseSelect("SELECT name, count(*) FROM People GROUP BY name");

			assertThat(select.$distinct()).isFalse();
		}

		@Test
		@DisplayName("SELECT DISTINCT with HAVING — both flags accessible independently")
		void distinctWithHaving() {
			var select = parseSelect(
					"SELECT DISTINCT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5");

			assertThat(select.$distinct()).isTrue();
			assertThat(select.$having()).isNotNull();

			// Both should be independently accessible
			assertThat(select.$having()).isInstanceOf(QOM.Gt.class);
		}

	}

	// -------------------------------------------------------------------------
	// Structural comparison: SELECT aggregates vs HAVING aggregates
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Structural comparison between SELECT and HAVING aggregates")
	class StructuralComparisonTests {

		@Test
		@DisplayName("count(*) in SELECT and count(*) in HAVING — same QOM type and structure")
		void countStarSelectVsHaving() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5");

			// Extract count(*) from SELECT
			var selectFields = select.$select();
			Field<?> selectCount = null;
			for (var sf : selectFields) {
				if (sf instanceof QOM.FieldAlias<?> fa && fa.$field() instanceof QOM.Count) {
					selectCount = fa.$field();
				}
				else if (sf instanceof QOM.Count) {
					selectCount = (Field<?>) sf;
				}
			}
			assertThat(selectCount).as("count(*) should be found in SELECT").isNotNull();
			assertThat(selectCount).isInstanceOf(QOM.Count.class);

			// Extract count(*) from HAVING
			var gt = (QOM.Gt<?>) select.$having();
			var havingCount = gt.$arg1();
			assertThat(havingCount).isInstanceOf(QOM.Count.class);

			// Both should be structurally identical: same QOM type, same inner field
			var selectCountObj = (QOM.Count) selectCount;
			var havingCountObj = (QOM.Count) havingCount;
			assertThat(selectCountObj.$field().getClass()).isEqualTo(havingCountObj.$field().getClass());
		}

		@Test
		@DisplayName("sum(age) in SELECT and sum(age) in HAVING — same inner field")
		void sumSelectVsHaving() {
			var select = parseSelect("SELECT name, sum(age) AS total FROM People GROUP BY name HAVING sum(age) > 100");

			// Extract sum from SELECT
			Field<?> selectSum = null;
			for (var sf : select.$select()) {
				if (sf instanceof QOM.FieldAlias<?> fa && fa.$field() instanceof QOM.Sum) {
					selectSum = fa.$field();
				}
			}
			assertThat(selectSum).as("sum(age) should be found in SELECT").isNotNull();

			// Extract sum from HAVING
			var gt = (QOM.Gt<?>) select.$having();
			assertThat(gt.$arg1()).isInstanceOf(QOM.Sum.class);

			// Both should reference the same inner field name
			var selectSumField = ((QOM.Sum) selectSum).$field();
			var havingSumField = ((QOM.Sum) gt.$arg1()).$field();
			assertThat(selectSumField.getName()).isEqualTo(havingSumField.getName());
		}

		@Test
		@DisplayName("Different aggregates in SELECT vs HAVING — should not match structurally")
		void differentAggregatesDoNotMatch() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING sum(age) > 100");

			// SELECT has count(*), HAVING has sum(age) — structurally different
			var gt = (QOM.Gt<?>) select.$having();
			assertThat(gt.$arg1()).isInstanceOf(QOM.Sum.class);

			// Verify SELECT does not contain sum
			boolean selectHasSum = select.$select()
				.stream()
				.anyMatch(f -> f instanceof QOM.Sum
						|| (f instanceof QOM.FieldAlias<?> fa && fa.$field() instanceof QOM.Sum));
			assertThat(selectHasSum).as("SELECT should not contain sum()").isFalse();
		}

		@Test
		@DisplayName("count(*) vs count(column) — should be structurally different")
		void countStarVsCountColumn() {
			var select = parseSelect("SELECT count(*) AS cnt1, count(name) AS cnt2 FROM People GROUP BY age");

			var selectFields = select.$select();
			QOM.Count countStar = null;
			QOM.Count countColumn = null;

			for (var sf : selectFields) {
				Field<?> underlying = (sf instanceof QOM.FieldAlias<?> fa) ? fa.$field() : (Field<?>) sf;
				if (underlying instanceof QOM.Count c) {
					// FINDING: jOOQ uses SQLField (toString = "*") for count(*),
					// not org.jooq.Asterisk. Detect via toString().
					if ("*".equals(c.$field().toString())) {
						countStar = c;
					}
					else {
						countColumn = c;
					}
				}
			}

			assertThat(countStar).as("count(*) should be found").isNotNull();
			assertThat(countColumn).as("count(name) should be found").isNotNull();

			// They are both QOM.Count but their inner fields differ
			assertThat(countStar.$field().toString()).isEqualTo("*");
			assertThat(countColumn.$field().getName().toUpperCase()).isEqualTo("NAME");
		}

		@Test
		@DisplayName("Aliased vs unaliased — underlying field structure is identical")
		void aliasedVsUnaliased() {
			var select = parseSelect("SELECT count(*) AS cnt, count(*) FROM People GROUP BY name");

			var selectFields = select.$select();
			assertThat(selectFields).hasSize(2);

			// First field should be aliased
			assertThat(selectFields.get(0)).isInstanceOf(QOM.FieldAlias.class);

			// Both should contain a QOM.Count with "*" field (SQLField, not
			// Asterisk)
			for (var sf : selectFields) {
				Field<?> underlying;
				if (sf instanceof QOM.FieldAlias<?> fa) {
					underlying = fa.$field();
				}
				else {
					underlying = (Field<?>) sf;
				}
				assertThat(underlying).isInstanceOf(QOM.Count.class);
				assertThat(((QOM.Count) underlying).$field().toString()).isEqualTo("*");
			}
		}

		@Test
		@DisplayName("count(DISTINCT x) vs count(x) — $distinct() flag differs")
		void countDistinctVsCountAll() {
			var select = parseSelect("SELECT count(DISTINCT name) AS cd, count(name) AS ca FROM People GROUP BY age");

			QOM.Count countDistinct = null;
			QOM.Count countAll = null;

			for (var sf : select.$select()) {
				Field<?> underlying = (sf instanceof QOM.FieldAlias<?> fa) ? fa.$field() : (Field<?>) sf;
				if (underlying instanceof QOM.Count c) {
					if (c.$distinct()) {
						countDistinct = c;
					}
					else {
						countAll = c;
					}
				}
			}

			assertThat(countDistinct).as("count(DISTINCT name) should be found").isNotNull();
			assertThat(countAll).as("count(name) should be found").isNotNull();

			// Same inner field name
			assertThat(countDistinct.$field().getName()).isEqualTo(countAll.$field().getName());
			// But $distinct() flag differs
			assertThat(countDistinct.$distinct()).isTrue();
			assertThat(countAll.$distinct()).isFalse();
		}

	}

	// -------------------------------------------------------------------------
	// Full query combinations
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Full query QOM — GROUP BY + HAVING + ORDER BY + DISTINCT + LIMIT")
	class FullQueryTests {

		@Test
		@DisplayName("All clauses present — each is independently accessible")
		void allClausesPresent() {
			var select = parseSelect(
					"SELECT DISTINCT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5 ORDER BY cnt DESC LIMIT 10");

			assertThat(select.$distinct()).isTrue();
			assertThat(select.$groupBy()).hasSize(1);
			assertThat(select.$having()).isNotNull();
			assertThat(select.$orderBy()).hasSize(1);
			assertThat(select.$limit()).isNotNull();
		}

		@Test
		@DisplayName("GROUP BY + HAVING + ORDER BY — no DISTINCT, no LIMIT")
		void groupByHavingOrderBy() {
			var select = parseSelect(
					"SELECT department, count(*) AS cnt, avg(salary) AS avg_sal FROM Employees GROUP BY department HAVING count(*) > 5 ORDER BY avg_sal DESC");

			assertThat(select.$distinct()).isFalse();
			assertThat(select.$groupBy()).hasSize(1);
			assertThat(select.$having()).isNotNull();
			assertThat(select.$orderBy()).hasSize(1);
			assertThat(select.$limit()).isNull();

			// ORDER BY field — document what jOOQ gives us
			var orderField = select.$orderBy().get(0).$field();
			assertThat(orderField).isNotNull();
		}

		@Test
		@DisplayName("No GROUP BY, no HAVING — baseline query remains unchanged")
		void baselineNoGroupByNoHaving() {
			var select = parseSelect("SELECT name, age FROM People WHERE age > 21");

			assertThat(select.$groupBy()).isEmpty();
			assertThat(select.$having()).isNull();
			assertThat(select.$orderBy()).isEmpty();
			assertThat(select.$distinct()).isFalse();
		}

		@Test
		@DisplayName("Aggregates without GROUP BY — single-row aggregation")
		void aggregatesWithoutGroupBy() {
			var select = parseSelect("SELECT count(*), sum(age) FROM People");

			assertThat(select.$groupBy()).isEmpty();
			assertThat(select.$having()).isNull();
			assertThat(select.$select()).hasSize(2);
		}

		@Test
		@DisplayName("GROUP BY with LIMIT but no HAVING — LIMIT is accessible")
		void groupByWithLimit() {
			var select = parseSelect("SELECT name, count(*) FROM People GROUP BY name LIMIT 10");

			assertThat(select.$groupBy()).hasSize(1);
			assertThat(select.$having()).isNull();
			assertThat(select.$limit()).isNotNull();
		}

	}

}
