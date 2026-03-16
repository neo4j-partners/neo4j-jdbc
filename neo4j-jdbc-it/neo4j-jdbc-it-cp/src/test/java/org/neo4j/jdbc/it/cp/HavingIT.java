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
package org.neo4j.jdbc.it.cp;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for HAVING clause translation and execution against a real Neo4j
 * instance.
 * <p>
 * Uses two data sets:
 * <ul>
 * <li>Movies graph (loaded from movies.cypher) — for JOIN + HAVING tests</li>
 * <li>People nodes (5 controlled rows) — for precise assertion on known values</li>
 * </ul>
 * <p>
 * People data (for reference): <pre>
 * | name    | age | department  |
 * |---------|-----|-------------|
 * | Alice   | 30  | Engineering |
 * | Bob     | 25  | Engineering |
 * | Charlie | 35  | Sales       |
 * | Diana   | 28  | Sales       |
 * | Eve     | 32  | Marketing   |
 * </pre>
 *
 * @author Ryan Knight
 */
class HavingIT extends IntegrationTestBase {

	HavingIT() {
		super.doClean = false;
	}

	@BeforeAll
	void loadData() throws SQLException, IOException {
		try (var connection = getConnection(false, false)) {
			TestUtils.createMovieGraph(connection);
		}
		try (var connection = getConnection(false, false); var stmt = connection.createStatement()) {
			stmt.execute("CREATE (:People {name: 'Alice', age: 30, department: 'Engineering'})");
			stmt.execute("CREATE (:People {name: 'Bob', age: 25, department: 'Engineering'})");
			stmt.execute("CREATE (:People {name: 'Charlie', age: 35, department: 'Sales'})");
			stmt.execute("CREATE (:People {name: 'Diana', age: 28, department: 'Sales'})");
			stmt.execute("CREATE (:People {name: 'Eve', age: 32, department: 'Marketing'})");
		}
	}

	// -- 8.1 Basic HAVING Filtering -------------------------------------------

	@Nested
	class BasicHavingFiltering {

		@Test
		void havingCountGreaterThan() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department HAVING count(*) > 1 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt");
				// Marketing(1) filtered out; Engineering(2) and Sales(2) remain
				assertThat(rows).containsExactly(List.of("Engineering", 2L), List.of("Sales", 2L));
			}
		}

		@Test
		void havingSumGreaterThan() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p GROUP BY department HAVING sum(age) > 50 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "total");
				// Marketing(32) filtered out; Engineering(55) and Sales(63) remain
				assertThat(rows).containsExactly(List.of("Engineering", 55L), List.of("Sales", 63L));
			}
		}

		@Test
		void havingAvgGreaterThan() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, avg(age) AS average FROM People p GROUP BY department HAVING avg(age) > 30 ORDER BY department")) {
				// Engineering(27.5) filtered out; Marketing(32.0) and Sales(31.5) remain
				var rows = new ArrayList<Object[]>();
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("department"), rs.getDouble("average") });
				}
				assertThat(rows).hasSize(2);
				assertThat((String) rows.get(0)[0]).isEqualTo("Marketing");
				assertThat((double) rows.get(0)[1]).isCloseTo(32.0, within(0.1));
				assertThat((String) rows.get(1)[0]).isEqualTo("Sales");
				assertThat((double) rows.get(1)[1]).isCloseTo(31.5, within(0.1));
			}
		}

		@Test
		void havingMaxGreaterThan() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, max(age) AS oldest FROM People p GROUP BY department HAVING max(age) > 32 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "oldest");
				// Engineering(max=30) and Marketing(max=32) filtered; Sales(max=35)
				// remains
				assertThat(rows).containsExactly(List.of("Sales", 35L));
			}
		}

	}

	// -- 8.2 HAVING with Aggregate Not in SELECT ------------------------------

	@Nested
	class HavingAggregateNotInSelect {

		@Test
		void countNotInSelect() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department FROM People p GROUP BY department HAVING count(*) > 1 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department");
				// count(*) not in SELECT; Marketing(1) filtered out
				assertThat(rows).containsExactly(List.of("Engineering"), List.of("Sales"));
				// Verify hidden __having_col columns do NOT appear in result metadata
				var meta = rs.getMetaData();
				for (int i = 1; i <= meta.getColumnCount(); i++) {
					assertThat(meta.getColumnLabel(i)).doesNotStartWith("__having");
				}
			}
		}

		@Test
		void avgNotInSelect() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department FROM People p GROUP BY department HAVING avg(age) > 30 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department");
				// Engineering(27.5) filtered out; Marketing(32.0) and Sales(31.5) remain
				assertThat(rows).containsExactly(List.of("Marketing"), List.of("Sales"));
			}
		}

		@Test
		void maxNotInSelectWithOtherAggregate() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p GROUP BY department HAVING max(age) > 30 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "total");
				// max(age) not in SELECT: Eng(max=30, not >30), Sales(35), Marketing(32)
				assertThat(rows).containsExactly(List.of("Marketing", 32L), List.of("Sales", 63L));
			}
		}

	}

	// -- 8.3 Compound HAVING --------------------------------------------------

	@Nested
	class CompoundHaving {

		@Test
		void havingWithAnd() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department HAVING count(*) > 1 AND max(age) > 25 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt");
				// count>1: Eng(2), Sales(2). max>25: all. Intersection: Eng, Sales
				assertThat(rows).containsExactly(List.of("Engineering", 2L), List.of("Sales", 2L));
			}
		}

		@Test
		void havingWithOr() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department HAVING count(*) > 1 OR avg(age) > 31 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt");
				// count>1: Eng(2), Sales(2). avg>31: Sales(31.5), Mkt(32). Union: all 3
				assertThat(rows).containsExactly(List.of("Engineering", 2L), List.of("Marketing", 1L),
						List.of("Sales", 2L));
			}
		}

		@Test
		void havingWithNestedCondition() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department HAVING (count(*) > 1 AND avg(age) > 30) OR count(*) = 1 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt");
				// (count>1 AND avg>30): Sales(2,31.5). count=1: Marketing. Result: Mkt,
				// Sales
				assertThat(rows).containsExactly(List.of("Marketing", 1L), List.of("Sales", 2L));
			}
		}

	}

	// -- 8.4 HAVING by Alias --------------------------------------------------

	@Nested
	class HavingByAlias {

		@Test
		void havingByCountAlias() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department HAVING cnt > 1 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt");
				assertThat(rows).containsExactly(List.of("Engineering", 2L), List.of("Sales", 2L));
			}
		}

		@Test
		void havingBySumAlias() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p GROUP BY department HAVING total > 50 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "total");
				assertThat(rows).containsExactly(List.of("Engineering", 55L), List.of("Sales", 63L));
			}
		}

	}

	// -- 8.5 ORDER BY + HAVING Combined ---------------------------------------

	@Nested
	class OrderByHavingCombined {

		@Test
		void havingWithOrderByAggregateDesc() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department HAVING count(*) >= 1 ORDER BY cnt DESC")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt");
				assertThat(rows).hasSize(3);
				// Marketing(1) should be last
				assertThat(rows.get(2)).isEqualTo(List.of("Marketing", 1L));
			}
		}

		@Test
		void havingWithOrderByColumnAsc() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p GROUP BY department HAVING sum(age) > 50 ORDER BY department ASC")) {
				var rows = TestUtils.collectRows(rs, "department", "total");
				assertThat(rows).containsExactly(List.of("Engineering", 55L), List.of("Sales", 63L));
			}
		}

		@Test
		void havingWithOrderByAndLimit() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt, max(age) AS oldest FROM People p GROUP BY department HAVING count(*) >= 1 ORDER BY oldest DESC LIMIT 2")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt", "oldest");
				assertThat(rows).hasSize(2);
				// Sales(max=35) first, then Marketing(max=32) or Engineering(max=30)
				assertThat(rows.get(0)).isEqualTo(List.of("Sales", 2L, 35L));
			}
		}

	}

	// -- 8.6 HAVING + WHERE Interaction ---------------------------------------

	@Nested
	class HavingWhereInteraction {

		@Test
		void whereReducesInputRowsBeforeHaving() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p WHERE age >= 28 GROUP BY department HAVING count(*) > 1 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt");
				// WHERE age>=28: Alice(30), Charlie(35), Diana(28), Eve(32). Bob
				// excluded.
				// GROUP BY: Eng(1), Sales(2), Mkt(1). HAVING count>1: Sales(2) only.
				assertThat(rows).containsExactly(List.of("Sales", 2L));
			}
		}

		@Test
		void whereOnDepartmentThenHavingOnSum() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p WHERE department != 'Marketing' GROUP BY department HAVING sum(age) > 60 ORDER BY department")) {
				var rows = TestUtils.collectRows(rs, "department", "total");
				// WHERE excludes Marketing. Eng(sum=55), Sales(sum=63).
				// HAVING sum>60: Sales(63) only.
				assertThat(rows).containsExactly(List.of("Sales", 63L));
			}
		}

	}

	// -- 8.7 HAVING + JOIN ----------------------------------------------------

	@Nested
	class HavingWithJoin {

		@Test
		void naturalJoinWithHavingCount() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.title AS title, count(*) AS cnt FROM Person p NATURAL JOIN ACTED_IN a NATURAL JOIN Movie m GROUP BY m.title HAVING count(*) > 3 ORDER BY cnt DESC")) {
				var rows = TestUtils.collectRows(rs, "title", "cnt");
				assertThat(rows).isNotEmpty();
				// All returned movies should have more than 3 actors
				for (var row : rows) {
					assertThat((long) row.get(1)).isGreaterThan(3L);
				}
			}
		}

		@Test
		void naturalJoinWithHavingNotInSelect() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.title AS title FROM Person p NATURAL JOIN ACTED_IN a NATURAL JOIN Movie m GROUP BY m.title HAVING count(*) > 3 ORDER BY m.title")) {
				var titles = new ArrayList<String>();
				while (rs.next()) {
					titles.add(rs.getString("title"));
				}
				assertThat(titles).isNotEmpty();
				// Verify count(*) does not leak into result set columns
				var meta = rs.getMetaData();
				assertThat(meta.getColumnCount()).isEqualTo(1);
			}
		}

		@Test
		void naturalJoinWithCompoundHaving() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.title AS title, count(*) AS cnt, min(p.born) AS earliest FROM Person p NATURAL JOIN ACTED_IN a NATURAL JOIN Movie m GROUP BY m.title HAVING count(*) > 2 AND min(p.born) < 1970 ORDER BY cnt DESC")) {
				var rows = TestUtils.collectRows(rs, "title", "cnt", "earliest");
				assertThat(rows).isNotEmpty();
				for (var row : rows) {
					assertThat((long) row.get(1)).isGreaterThan(2L);
					assertThat((long) row.get(2)).isLessThan(1970L);
				}
			}
		}

	}

	// -- 8.8 HAVING Kitchen Sink ----------------------------------------------

	@Nested
	class HavingKitchenSink {

		@Test
		void fullCombinationWithPeople() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT DISTINCT department, count(*) AS cnt, sum(age) AS total FROM People p WHERE age >= 25 GROUP BY department HAVING count(*) >= 1 ORDER BY total DESC LIMIT 3 OFFSET 0")) {
				var rows = TestUtils.collectRows(rs, "department", "cnt", "total");
				assertThat(rows).isNotEmpty();
				assertThat(rows.size()).isLessThanOrEqualTo(3);
				// Verify ordering by total DESC
				for (int i = 1; i < rows.size(); i++) {
					assertThat((long) rows.get(i).get(2)).isLessThanOrEqualTo((long) rows.get(i - 1).get(2));
				}
			}
		}

		@Test
		void multipleAggregatesWithHavingFiltering() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt, sum(age) AS total, avg(age) AS average FROM People p WHERE age >= 25 GROUP BY department HAVING count(*) > 1 AND sum(age) > 50 ORDER BY total DESC")) {
				// All 5 pass WHERE. GROUP BY: Eng(2,55), Sales(2,63), Mkt(1,32).
				// HAVING count>1 AND sum>50: Eng(55), Sales(63).
				var rows = new ArrayList<Object[]>();
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("department"), rs.getLong("cnt"), rs.getLong("total"),
							rs.getDouble("average") });
				}
				assertThat(rows).hasSize(2);
				// ORDER BY total DESC: Sales(63) first, Engineering(55) second
				assertThat((String) rows.get(0)[0]).isEqualTo("Sales");
				assertThat((long) rows.get(0)[1]).isEqualTo(2L);
				assertThat((long) rows.get(0)[2]).isEqualTo(63L);
				assertThat((double) rows.get(0)[3]).isCloseTo(31.5, within(0.1));
				assertThat((String) rows.get(1)[0]).isEqualTo("Engineering");
				assertThat((long) rows.get(1)[1]).isEqualTo(2L);
				assertThat((long) rows.get(1)[2]).isEqualTo(55L);
				assertThat((double) rows.get(1)[3]).isCloseTo(27.5, within(0.1));
			}
		}

		@Test
		void moviesKitchenSinkWithJoin() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.title AS title, count(*) AS cnt FROM Person p NATURAL JOIN ACTED_IN a NATURAL JOIN Movie m WHERE p.born > 1960 GROUP BY m.title HAVING count(*) > 2 ORDER BY cnt DESC LIMIT 5")) {
				var rows = TestUtils.collectRows(rs, "title", "cnt");
				assertThat(rows.size()).isLessThanOrEqualTo(5);
				// All returned movies have >2 actors born after 1960
				for (var row : rows) {
					assertThat((long) row.get(1)).isGreaterThan(2L);
				}
				// Verify descending order
				for (int i = 1; i < rows.size(); i++) {
					assertThat((long) rows.get(i).get(1)).isLessThanOrEqualTo((long) rows.get(i - 1).get(1));
				}
			}
		}

	}

}
