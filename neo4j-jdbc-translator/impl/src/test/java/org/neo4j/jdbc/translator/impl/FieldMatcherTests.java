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

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Parser;
import org.jooq.Select;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the structural field equivalence matcher. All inputs are parsed through
 * jOOQ's SQL parser to ensure we test against the exact types the production code will
 * encounter.
 *
 * @author Ryan Knight
 */
class FieldMatcherTests {

	private static DSLContext dsl;

	private static Parser parser;

	@BeforeAll
	static void initParser() {
		dsl = DSL.using(org.jooq.SQLDialect.DEFAULT);
		parser = dsl.parser();
	}

	private Select<?> parseSelect(String sql) {
		var query = parser.parseQuery(sql);
		assertThat(query).isInstanceOf(Select.class);
		return (Select<?>) query;
	}

	/**
	 * Extracts a {@link Field} from a {@link SelectFieldOrAsterisk}, unwrapping any alias
	 * wrapper. The returned field is the underlying expression (not the alias).
	 */
	private static Field<?> unwrapAlias(SelectFieldOrAsterisk sfa) {
		if (sfa instanceof QOM.FieldAlias<?> fa) {
			return fa.$field();
		}
		return (Field<?>) sfa;
	}

	/**
	 * Casts a {@link SelectFieldOrAsterisk} to {@link Field} without unwrapping aliases.
	 */
	private static Field<?> asField(SelectFieldOrAsterisk sfa) {
		return (Field<?>) sfa;
	}

	// -------------------------------------------------------------------------
	// Column reference matching
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Column reference matching")
	class ColumnReferenceTests {

		@Test
		@DisplayName("Same table, same column -> true")
		void sameTableSameColumn() {
			var select = parseSelect(
					"SELECT c.name FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name");

			// Extract c.name from SELECT and GROUP BY
			var selectField = unwrapAlias(select.$select().get(0));
			var groupField = (Field<?>) select.$groupBy().get(0);

			assertThat(FieldMatcher.fieldsMatch(selectField, groupField)).isTrue();
		}

		@Test
		@DisplayName("Same table, different column -> false")
		void sameTableDifferentColumn() {
			var select = parseSelect("SELECT c.name, c.age FROM Customers c JOIN Orders o ON c.id = o.customer_id");

			var field1 = unwrapAlias(select.$select().get(0));
			var field2 = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(field1, field2)).isFalse();
		}

		@Test
		@DisplayName("Different table, same column name -> false")
		void differentTableSameColumn() {
			var select = parseSelect("SELECT c.id, o.id FROM Customers c JOIN Orders o ON c.id = o.customer_id");

			var field1 = unwrapAlias(select.$select().get(0));
			var field2 = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(field1, field2)).isFalse();
		}

		@Test
		@DisplayName("Unqualified field matches qualified field -> true")
		void unqualifiedMatchesQualified() {
			// Use two separate queries: one with table qualifier, one without
			var qualifiedSelect = parseSelect("SELECT c.name FROM Customers c JOIN Orders o ON c.id = o.customer_id");
			var unqualifiedSelect = parseSelect("SELECT name FROM Customers");

			var qualified = unwrapAlias(qualifiedSelect.$select().get(0));
			var unqualified = unwrapAlias(unqualifiedSelect.$select().get(0));

			assertThat(FieldMatcher.fieldsMatch(qualified, unqualified)).isTrue();
		}

		@Test
		@DisplayName("Case-insensitive matching -> true")
		void caseInsensitiveMatching() {
			var select1 = parseSelect("SELECT name FROM People");
			var select2 = parseSelect("SELECT NAME FROM People");

			var field1 = unwrapAlias(select1.$select().get(0));
			var field2 = unwrapAlias(select2.$select().get(0));

			assertThat(FieldMatcher.fieldsMatch(field1, field2)).isTrue();
		}

	}

	// -------------------------------------------------------------------------
	// Aggregate function matching
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Aggregate function matching")
	class AggregateFunctionTests {

		@Test
		@DisplayName("count(*) vs count(*) -> true")
		void countStarVsCountStar() {
			var select = parseSelect("SELECT count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5");

			var selectCount = unwrapAlias(select.$select().get(0));
			var havingCount = ((QOM.Gt<?>) select.$having()).$arg1();

			assertThat(FieldMatcher.fieldsMatch(selectCount, havingCount)).isTrue();
		}

		@Test
		@DisplayName("count(*) vs sum(age) -> false")
		void countStarVsSumAge() {
			var select1 = parseSelect("SELECT count(*) FROM People");
			var select2 = parseSelect("SELECT sum(age) FROM People");

			var count = unwrapAlias(select1.$select().get(0));
			var sum = unwrapAlias(select2.$select().get(0));

			assertThat(FieldMatcher.fieldsMatch(count, sum)).isFalse();
		}

		@Test
		@DisplayName("count(name) vs count(age) -> false")
		void countNameVsCountAge() {
			var select = parseSelect("SELECT count(name) AS cn, count(age) AS ca FROM People");

			var countName = unwrapAlias(select.$select().get(0));
			var countAge = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(countName, countAge)).isFalse();
		}

		@Test
		@DisplayName("sum(age) vs sum(age) -> true")
		void sumVsSum() {
			var select = parseSelect("SELECT sum(age) AS total FROM People GROUP BY name HAVING sum(age) > 100");

			var selectSum = unwrapAlias(select.$select().get(0));
			var havingSum = ((QOM.Gt<?>) select.$having()).$arg1();

			assertThat(FieldMatcher.fieldsMatch(selectSum, havingSum)).isTrue();
		}

		@Test
		@DisplayName("min(salary) vs min(salary) -> true")
		void minVsMin() {
			var select = parseSelect(
					"SELECT min(salary) AS mn FROM Employees GROUP BY department HAVING min(salary) > 50000");

			var selectMin = unwrapAlias(select.$select().get(0));
			var havingMin = ((QOM.Gt<?>) select.$having()).$arg1();

			assertThat(FieldMatcher.fieldsMatch(selectMin, havingMin)).isTrue();
		}

		@Test
		@DisplayName("max(age) vs max(age) -> true")
		void maxVsMax() {
			var select = parseSelect("SELECT max(age) AS mx FROM People GROUP BY name HAVING max(age) > 60");

			var selectMax = unwrapAlias(select.$select().get(0));
			var havingMax = ((QOM.Gt<?>) select.$having()).$arg1();

			assertThat(FieldMatcher.fieldsMatch(selectMax, havingMax)).isTrue();
		}

		@Test
		@DisplayName("avg(score) vs avg(score) -> true")
		void avgVsAvg() {
			var select = parseSelect("SELECT avg(score) AS av FROM Results GROUP BY name HAVING avg(score) > 75");

			var selectAvg = unwrapAlias(select.$select().get(0));
			var havingAvg = ((QOM.Gt<?>) select.$having()).$arg1();

			assertThat(FieldMatcher.fieldsMatch(selectAvg, havingAvg)).isTrue();
		}

		@Test
		@DisplayName("count(name) vs count(DISTINCT name) -> false")
		void countVsCountDistinct() {
			var select = parseSelect("SELECT count(name) AS cn, count(DISTINCT name) AS cdn FROM People");

			var countAll = unwrapAlias(select.$select().get(0));
			var countDistinct = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(countAll, countDistinct)).isFalse();
		}

		@Test
		@DisplayName("count(DISTINCT name) vs count(DISTINCT name) -> true")
		void countDistinctVsCountDistinct() {
			var select = parseSelect(
					"SELECT count(DISTINCT name) AS cdn FROM People GROUP BY age HAVING count(DISTINCT name) > 3");

			var selectCountDistinct = unwrapAlias(select.$select().get(0));
			var havingCountDistinct = ((QOM.Gt<?>) select.$having()).$arg1();

			assertThat(FieldMatcher.fieldsMatch(selectCountDistinct, havingCountDistinct)).isTrue();
		}

	}

	// -------------------------------------------------------------------------
	// Alias transparency
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Alias transparency")
	class AliasTransparencyTests {

		@Test
		@DisplayName("count(*) AS cnt vs count(*) -> true")
		void aliasedCountVsUnaliasedCount() {
			var select1 = parseSelect("SELECT count(*) AS cnt FROM People");
			var select2 = parseSelect("SELECT count(*) FROM People");

			// The first is aliased, the second is not
			var aliasedCount = asField(select1.$select().get(0));
			assertThat(aliasedCount).isInstanceOf(QOM.FieldAlias.class);

			var unaliasedCount = asField(select2.$select().get(0));

			assertThat(FieldMatcher.fieldsMatch(aliasedCount, unaliasedCount)).isTrue();
		}

		@Test
		@DisplayName("sum(age) AS total vs sum(age) -> true")
		void aliasedSumVsUnaliasedSum() {
			var select1 = parseSelect("SELECT sum(age) AS total FROM People");
			var select2 = parseSelect("SELECT sum(age) FROM People");

			var aliasedSum = asField(select1.$select().get(0));
			assertThat(aliasedSum).isInstanceOf(QOM.FieldAlias.class);

			var unaliasedSum = asField(select2.$select().get(0));

			assertThat(FieldMatcher.fieldsMatch(aliasedSum, unaliasedSum)).isTrue();
		}

		@Test
		@DisplayName("name AS n vs name -> true")
		void aliasedFieldVsUnaliasedField() {
			var select1 = parseSelect("SELECT name AS n FROM People");
			var select2 = parseSelect("SELECT name FROM People");

			var aliasedField = asField(select1.$select().get(0));
			assertThat(aliasedField).isInstanceOf(QOM.FieldAlias.class);

			var unaliasedField = asField(select2.$select().get(0));

			assertThat(FieldMatcher.fieldsMatch(aliasedField, unaliasedField)).isTrue();
		}

	}

	// -------------------------------------------------------------------------
	// Cross-parse matching (within the same parsed query)
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Cross-parse matching")
	class CrossParseTests {

		@Test
		@DisplayName("count(*) from SELECT vs count(*) from HAVING of the same query -> true")
		void countFromSelectVsHaving() {
			var select = parseSelect("SELECT name, count(*) AS cnt FROM People GROUP BY name HAVING count(*) > 5");

			// Extract count(*) from SELECT (aliased)
			var selectCount = asField(select.$select().get(1));
			assertThat(selectCount).isInstanceOf(QOM.FieldAlias.class);

			// Extract count(*) from HAVING
			var havingCount = ((QOM.Gt<?>) select.$having()).$arg1();
			assertThat(havingCount).isInstanceOf(QOM.Count.class);

			assertThat(FieldMatcher.fieldsMatch(selectCount, havingCount)).isTrue();
		}

		@Test
		@DisplayName("sum(age) from SELECT vs sum(age) from ORDER BY of the same query -> true")
		void sumFromSelectVsOrderBy() {
			var select = parseSelect("SELECT name, sum(age) AS total FROM People GROUP BY name ORDER BY sum(age)");

			// Extract sum(age) from SELECT (aliased)
			var selectSum = asField(select.$select().get(1));
			assertThat(selectSum).isInstanceOf(QOM.FieldAlias.class);

			// Extract sum(age) from ORDER BY
			var orderByField = select.$orderBy().get(0).$field();
			assertThat(orderByField).isInstanceOf(QOM.Sum.class);

			assertThat(FieldMatcher.fieldsMatch(selectSum, orderByField)).isTrue();
		}

	}

	// -------------------------------------------------------------------------
	// Negative / false-positive cases
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Negative and false-positive cases")
	class NegativeTests {

		@Test
		@DisplayName("count(*) vs count(name) -> false")
		void countStarVsCountName() {
			var select = parseSelect("SELECT count(*) AS c1, count(name) AS c2 FROM People");

			var countStar = unwrapAlias(select.$select().get(0));
			var countName = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(countStar, countName)).isFalse();
		}

		@Test
		@DisplayName("sum(age) vs avg(age) -> false")
		void sumVsAvg() {
			var select = parseSelect("SELECT sum(age) AS s, avg(age) AS a FROM People");

			var sum = unwrapAlias(select.$select().get(0));
			var avg = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(sum, avg)).isFalse();
		}

		@Test
		@DisplayName("min(x) vs max(x) -> false")
		void minVsMax() {
			var select = parseSelect("SELECT min(score) AS mn, max(score) AS mx FROM Results");

			var min = unwrapAlias(select.$select().get(0));
			var max = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(min, max)).isFalse();
		}

		@Test
		@DisplayName("count(x) vs count(DISTINCT x) -> false")
		void countVsCountDistinctNegative() {
			var select = parseSelect("SELECT count(name) AS cn, count(DISTINCT name) AS cdn FROM People");

			var countAll = unwrapAlias(select.$select().get(0));
			var countDistinct = unwrapAlias(select.$select().get(1));

			assertThat(FieldMatcher.fieldsMatch(countAll, countDistinct)).isFalse();
		}

		@Test
		@DisplayName("null vs field -> false")
		void nullVsField() {
			var select = parseSelect("SELECT name FROM People");
			var field = unwrapAlias(select.$select().get(0));

			assertThat(FieldMatcher.fieldsMatch(null, field)).isFalse();
			assertThat(FieldMatcher.fieldsMatch(field, null)).isFalse();
			assertThat(FieldMatcher.fieldsMatch(null, null)).isFalse();
		}

	}

}
