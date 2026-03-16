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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for GROUP BY translation and execution against a real Neo4j instance.
 * <p>
 * Uses two data sets:
 * <ul>
 * <li>Movies graph (loaded from movies.cypher) — for realistic cardinality tests</li>
 * <li>People nodes (5 controlled rows) — for precise assertion on known values</li>
 * </ul>
 *
 * @author Ryan Knight
 */
class GroupByIT extends IntegrationTestBase {

	GroupByIT() {
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

	// -- Basic GROUP BY -------------------------------------------------------

	@Nested
	class BasicGroupBy {

		@Test
		void groupByWithCount() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT department, count(*) AS cnt FROM People p GROUP BY department")) {
				var rows = collectRows(rs, "department", "cnt");
				assertThat(rows).hasSize(3);
				assertThat(rows).containsExactlyInAnyOrder(List.of("Engineering", 2L), List.of("Sales", 2L),
						List.of("Marketing", 1L));
			}
		}

		@Test
		void groupByWithSum() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT department, sum(age) AS total FROM People p GROUP BY department")) {
				var rows = collectRows(rs, "department", "total");
				assertThat(rows).hasSize(3);
				assertThat(rows).containsExactlyInAnyOrder(List.of("Engineering", 55L), List.of("Sales", 63L),
						List.of("Marketing", 32L));
			}
		}

		@Test
		void groupByWithAvg() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT department, avg(age) AS average FROM People p GROUP BY department")) {
				var rows = new ArrayList<Object[]>();
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("department"), rs.getDouble("average") });
				}
				assertThat(rows).hasSize(3);
				for (var row : rows) {
					var dept = (String) row[0];
					var avg = (double) row[1];
					switch (dept) {
						case "Engineering" -> assertThat(avg).isCloseTo(27.5, within(0.1));
						case "Sales" -> assertThat(avg).isCloseTo(31.5, within(0.1));
						case "Marketing" -> assertThat(avg).isCloseTo(32.0, within(0.1));
						default -> fail("Unexpected department: " + dept);
					}
				}
			}
		}

		@Test
		void groupByWithMinMax() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, min(age) AS youngest, max(age) AS oldest FROM People p GROUP BY department")) {
				var rows = collectRows(rs, "department", "youngest", "oldest");
				assertThat(rows).hasSize(3);
				assertThat(rows).containsExactlyInAnyOrder(List.of("Engineering", 25L, 30L), List.of("Sales", 28L, 35L),
						List.of("Marketing", 32L, 32L));
			}
		}

		@Test
		void groupByWithMultipleAggregates() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt, sum(age) AS total, avg(age) AS average FROM People p GROUP BY department")) {
				var rows = new ArrayList<Object[]>();
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("department"), rs.getLong("cnt"), rs.getLong("total"),
							rs.getDouble("average") });
				}
				assertThat(rows).hasSize(3);
				for (var row : rows) {
					var dept = (String) row[0];
					switch (dept) {
						case "Engineering" -> {
							assertThat((long) row[1]).isEqualTo(2L);
							assertThat((long) row[2]).isEqualTo(55L);
							assertThat((double) row[3]).isCloseTo(27.5, within(0.1));
						}
						case "Sales" -> {
							assertThat((long) row[1]).isEqualTo(2L);
							assertThat((long) row[2]).isEqualTo(63L);
							assertThat((double) row[3]).isCloseTo(31.5, within(0.1));
						}
						case "Marketing" -> {
							assertThat((long) row[1]).isEqualTo(1L);
							assertThat((long) row[2]).isEqualTo(32L);
							assertThat((double) row[3]).isCloseTo(32.0, within(0.1));
						}
						default -> fail("Unexpected department: " + dept);
					}
				}
			}
		}

		@Test
		void groupByOnUniqueColumn() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT name, count(*) AS cnt FROM People p GROUP BY name ORDER BY name")) {
				var rows = collectRows(rs, "name", "cnt");
				assertThat(rows).hasSize(5);
				for (var row : rows) {
					assertThat(row.get(1)).isEqualTo(1L);
				}
			}
		}

		@Test
		void globalAggregationWithoutGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT count(*) AS total FROM People p")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong(1)).isEqualTo(5L);
				assertThat(rs.next()).isFalse();
			}
		}

	}

	// -- GROUP BY column not in SELECT (WITH required) -----------------------

	@Nested
	class GroupByNotInSelect {

		@Test
		void countWithHiddenGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT count(*) AS cnt FROM People p GROUP BY department")) {
				var counts = new ArrayList<Long>();
				while (rs.next()) {
					counts.add(rs.getLong("cnt"));
				}
				assertThat(counts).hasSize(3);
				assertThat(counts).containsExactlyInAnyOrder(2L, 2L, 1L);
			}
		}

		@Test
		void sumWithHiddenGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT sum(age) AS total FROM People p GROUP BY department")) {
				var totals = new ArrayList<Long>();
				while (rs.next()) {
					totals.add(rs.getLong("total"));
				}
				assertThat(totals).hasSize(3);
				assertThat(totals).containsExactlyInAnyOrder(55L, 63L, 32L);
			}
		}

		@Test
		void multipleAggregatesWithHiddenGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT count(*) AS cnt, avg(age) AS average FROM People p GROUP BY department")) {
				var counts = new ArrayList<Long>();
				while (rs.next()) {
					counts.add(rs.getLong("cnt"));
					// Just verify avg is a reasonable number
					assertThat(rs.getDouble("average")).isBetween(25.0, 35.0);
				}
				assertThat(counts).hasSize(3);
				assertThat(counts).containsExactlyInAnyOrder(2L, 2L, 1L);
			}
		}

		@Test
		void maxWithHiddenGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT max(age) AS oldest FROM People p GROUP BY department")) {
				var maxAges = new ArrayList<Long>();
				while (rs.next()) {
					maxAges.add(rs.getLong("oldest"));
				}
				assertThat(maxAges).hasSize(3);
				assertThat(maxAges).containsExactlyInAnyOrder(30L, 35L, 32L);
			}
		}

	}

	// -- Multiple GROUP BY columns --------------------------------------------

	@Nested
	class MultipleGroupByColumns {

		@Test
		void twoGroupByColumnsBothInSelect() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, name, count(*) AS cnt FROM People p GROUP BY department, name")) {
				var rows = collectRows(rs, "department", "name", "cnt");
				assertThat(rows).hasSize(5);
				for (var row : rows) {
					assertThat(row.get(2)).isEqualTo(1L);
				}
			}
		}

		@Test
		void twoGroupByColumnsNeitherInSelect() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT count(*) AS cnt FROM People p GROUP BY department, name")) {
				var counts = new ArrayList<Long>();
				while (rs.next()) {
					counts.add(rs.getLong("cnt"));
				}
				assertThat(counts).hasSize(5);
				for (var count : counts) {
					assertThat(count).isEqualTo(1L);
				}
			}
		}

		@Test
		void twoGroupByColumnsOneInSelect() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT department, count(*) AS cnt FROM People p GROUP BY department, name")) {
				var rows = collectRows(rs, "department", "cnt");
				assertThat(rows).hasSize(5);
			}
		}

		@Test
		void moviesGroupByReleased() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT m.released, count(*) AS movie_count FROM Movie m GROUP BY m.released")) {
				var rows = new ArrayList<List<Object>>();
				while (rs.next()) {
					rows.add(List.of(rs.getLong("released"), rs.getLong("movie_count")));
				}
				assertThat(rows).isNotEmpty();
				var totalMovies = rows.stream().mapToLong(r -> (long) r.get(1)).sum();
				assertThat(totalMovies).isEqualTo(38L);
			}
		}

		@Test
		void moviesGroupByReleasedOrderByReleased() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.released, count(*) AS movie_count FROM Movie m GROUP BY m.released ORDER BY m.released")) {
				var years = new ArrayList<Long>();
				while (rs.next()) {
					years.add(rs.getLong("released"));
				}
				assertThat(years).isNotEmpty();
				assertThat(years).isSorted();
			}
		}

		@Test
		void moviesGroupByReleasedOrderByCount() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.released, count(*) AS movie_count FROM Movie m GROUP BY m.released ORDER BY movie_count DESC")) {
				var counts = new ArrayList<Long>();
				while (rs.next()) {
					counts.add(rs.getLong("movie_count"));
				}
				assertThat(counts).isNotEmpty();
				assertThat(counts).isSortedAccordingTo((a, b) -> Long.compare(b, a));
			}
		}

	}

	// -- ORDER BY with GROUP BY -----------------------------------------------

	@Nested
	class OrderByWithGroupBy {

		@Test
		void orderByGroupByColumn() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department")) {
				var depts = new ArrayList<String>();
				while (rs.next()) {
					depts.add(rs.getString("department"));
				}
				assertThat(depts).containsExactly("Engineering", "Marketing", "Sales");
			}
		}

		@Test
		void orderByAggregateDesc() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY cnt DESC")) {
				var rows = collectRows(rs, "department", "cnt");
				assertThat(rows).hasSize(3);
				// Marketing (1) should be last; Engineering (2) and Sales (2) first in
				// some
				// order
				assertThat(rows.get(2).get(1)).isEqualTo(1L);
			}
		}

		@Test
		void orderBySumAsc() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p GROUP BY department ORDER BY total")) {
				var totals = new ArrayList<Long>();
				while (rs.next()) {
					totals.add(rs.getLong("total"));
				}
				assertThat(totals).containsExactly(32L, 55L, 63L);
			}
		}

		@Test
		void orderByMultipleColumns() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt, avg(age) AS average FROM People p GROUP BY department ORDER BY cnt DESC, average")) {
				var depts = new ArrayList<String>();
				while (rs.next()) {
					depts.add(rs.getString("department"));
				}
				assertThat(depts).hasSize(3);
				// Marketing (cnt=1) is last
				assertThat(depts.get(2)).isEqualTo("Marketing");
			}
		}

		@Test
		void moviesOrderByAggregateThenColumn() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.released, count(*) AS cnt FROM Movie m GROUP BY m.released ORDER BY cnt DESC, m.released")) {
				var prevCount = Long.MAX_VALUE;
				var prevYear = Long.MIN_VALUE;
				while (rs.next()) {
					var count = rs.getLong("cnt");
					var year = rs.getLong("released");
					if (count == prevCount) {
						assertThat(year).isGreaterThanOrEqualTo(prevYear);
					}
					else {
						assertThat(count).isLessThanOrEqualTo(prevCount);
					}
					prevCount = count;
					prevYear = year;
				}
			}
		}

		@Test
		void orderByMinAggregate() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, min(age) AS youngest FROM People p GROUP BY department ORDER BY youngest")) {
				var rows = collectRows(rs, "department", "youngest");
				assertThat(rows).containsExactly(List.of("Engineering", 25L), List.of("Sales", 28L),
						List.of("Marketing", 32L));
			}
		}

	}

	// -- LIMIT/OFFSET with GROUP BY -------------------------------------------

	@Nested
	class LimitOffsetWithGroupBy {

		@Test
		void limitOnGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT department, count(*) AS cnt FROM People p GROUP BY department LIMIT 2")) {
				var rows = collectRows(rs, "department", "cnt");
				assertThat(rows).hasSize(2);
			}
		}

		@Test
		void orderByThenLimit() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY cnt DESC LIMIT 2")) {
				var rows = collectRows(rs, "department", "cnt");
				assertThat(rows).hasSize(2);
				// Both rows should have count 2 (Engineering and Sales)
				for (var row : rows) {
					assertThat(row.get(1)).isEqualTo(2L);
				}
			}
		}

		@Test
		void moviesTopReleaseYears() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.released, count(*) AS cnt FROM Movie m GROUP BY m.released ORDER BY cnt DESC LIMIT 5")) {
				var counts = new ArrayList<Long>();
				while (rs.next()) {
					counts.add(rs.getLong("cnt"));
				}
				assertThat(counts).hasSize(5);
				assertThat(counts).isSortedAccordingTo((a, b) -> Long.compare(b, a));
			}
		}

		@Test
		void topDepartmentByAgeSum() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p GROUP BY department ORDER BY total DESC LIMIT 1")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("department")).isEqualTo("Sales");
				assertThat(rs.getLong("total")).isEqualTo(63L);
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void limitWithOffset() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department LIMIT 2 OFFSET 1")) {
				var depts = new ArrayList<String>();
				while (rs.next()) {
					depts.add(rs.getString("department"));
				}
				assertThat(depts).containsExactly("Marketing", "Sales");
			}
		}

	}

	// -- DISTINCT with GROUP BY -----------------------------------------------

	@Nested
	class DistinctWithGroupBy {

		@Test
		void distinctOnGroupByColumn() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT DISTINCT department FROM People p GROUP BY department ORDER BY department")) {
				var depts = new ArrayList<String>();
				while (rs.next()) {
					depts.add(rs.getString("department"));
				}
				assertThat(depts).containsExactly("Engineering", "Marketing", "Sales");
			}
		}

		@Test
		void distinctWithGroupByAndAggregate() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT DISTINCT department, count(*) AS cnt FROM People p GROUP BY department")) {
				var rows = collectRows(rs, "department", "cnt");
				assertThat(rows).hasSize(3);
			}
		}

		@Test
		void distinctMoviesGroupByReleased() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT DISTINCT m.released FROM Movie m GROUP BY m.released ORDER BY m.released")) {
				var years = new ArrayList<Long>();
				while (rs.next()) {
					years.add(rs.getLong("released"));
				}
				assertThat(years).isNotEmpty();
				assertThat(years).isSorted();
				assertThat(years).doesNotHaveDuplicates();
			}
		}

	}

	// -- GROUP BY with WHERE --------------------------------------------------

	@Nested
	class GroupByWithWhere {

		@Test
		void whereBeforeGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p WHERE age > 28 GROUP BY department ORDER BY department")) {
				var rows = collectRows(rs, "department", "cnt");
				// Alice(30), Charlie(35), Eve(32) pass the filter
				assertThat(rows).containsExactly(List.of("Engineering", 1L), List.of("Marketing", 1L),
						List.of("Sales", 1L));
			}
		}

		@Test
		void whereExcludesDepartment() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, avg(age) AS average FROM People p WHERE department != 'Marketing' GROUP BY department ORDER BY department")) {
				var depts = new ArrayList<String>();
				while (rs.next()) {
					depts.add(rs.getString("department"));
				}
				assertThat(depts).containsExactly("Engineering", "Sales");
			}
		}

		@Test
		void whereWithBetween() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, sum(age) AS total FROM People p WHERE age BETWEEN 25 AND 30 GROUP BY department ORDER BY department")) {
				var rows = collectRows(rs, "department", "total");
				// Alice(30) + Bob(25) = Engineering 55, Diana(28) = Sales 28
				assertThat(rows).containsExactly(List.of("Engineering", 55L), List.of("Sales", 28L));
			}
		}

		@Test
		void moviesWhereReleasedAfter2000() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.released, count(*) AS cnt FROM Movie m WHERE m.released >= 2000 GROUP BY m.released ORDER BY m.released")) {
				var years = new ArrayList<Long>();
				while (rs.next()) {
					years.add(rs.getLong("released"));
				}
				assertThat(years).isNotEmpty();
				assertThat(years).isSorted();
				assertThat(years).allMatch(y -> y >= 2000);
			}
		}

		@Test
		void moviesWhereBefore2000OrderByCount() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.released, count(*) AS cnt FROM Movie m WHERE m.released < 2000 GROUP BY m.released ORDER BY cnt DESC")) {
				var counts = new ArrayList<Long>();
				while (rs.next()) {
					counts.add(rs.getLong("cnt"));
				}
				assertThat(counts).isNotEmpty();
				assertThat(counts).isSortedAccordingTo((a, b) -> Long.compare(b, a));
			}
		}

	}

	// -- Global Aggregation Regression ----------------------------------------

	@Nested
	class GlobalAggregationRegression {

		@Test
		void simpleCount() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT count(*) FROM People p")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong(1)).isEqualTo(5L);
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void moviesCount() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT count(*) FROM Movie m")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong(1)).isEqualTo(38L);
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void multipleGlobalAggregates() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT min(age), max(age), avg(age) FROM People p")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong(1)).isEqualTo(25L);
				assertThat(rs.getLong(2)).isEqualTo(35L);
				assertThat(rs.getDouble(3)).isCloseTo(30.0, within(0.1));
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void countWithWhere() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT count(*) FROM People p WHERE department = 'Engineering'")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong(1)).isEqualTo(2L);
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void globalSum() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT sum(age) FROM People p")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong(1)).isEqualTo(150L);
				assertThat(rs.next()).isFalse();
			}
		}

	}

	// -- Edge Cases -----------------------------------------------------------

	@Nested
	class EdgeCases {

		@Test
		void deterministicOrderForAssertions() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department")) {
				var rows = collectRows(rs, "department", "cnt");
				assertThat(rows).containsExactly(List.of("Engineering", 2L), List.of("Marketing", 1L),
						List.of("Sales", 2L));
			}
		}

		@Test
		void ascendingOrderOnCount() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt
						.executeQuery("SELECT count(*) AS cnt FROM People p GROUP BY department ORDER BY cnt")) {
				var counts = new ArrayList<Long>();
				while (rs.next()) {
					counts.add(rs.getLong("cnt"));
				}
				assertThat(counts).containsExactly(1L, 2L, 2L);
			}
		}

		@Test
		void singleResultFromGroupBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p GROUP BY department ORDER BY department LIMIT 1")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("department")).isEqualTo("Engineering");
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void groupByOnEmptyResult() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt FROM People p WHERE age > 1000 GROUP BY department")) {
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void largeCardinalityGroupBy() throws SQLException {
			int yearCount;
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT m.released, count(*) AS cnt FROM Movie m GROUP BY m.released")) {
				yearCount = 0;
				var totalMovies = 0L;
				while (rs.next()) {
					yearCount++;
					totalMovies += rs.getLong("cnt");
				}
				assertThat(yearCount).isGreaterThan(10);
				assertThat(totalMovies).isEqualTo(38L);
			}
			// Verify against distinct count using direct Cypher
			try (var connection = getConnection(false, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("MATCH (m:Movie) RETURN count(DISTINCT m.released) AS years")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getInt("years")).isEqualTo(yearCount);
			}
		}

		@Test
		void nonGroupByQueryStillWorks() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT * FROM People p ORDER BY name")) {
				var names = new ArrayList<String>();
				while (rs.next()) {
					names.add(rs.getString("name"));
				}
				assertThat(names).containsExactly("Alice", "Bob", "Charlie", "Diana", "Eve");
			}
		}

	}

	// -- Multi-Aggregate ------------------------------------------------------

	@Nested
	class MultiAggregate {

		@Test
		void allFiveAggregateFunctions() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt, sum(age) AS total, min(age) AS youngest, max(age) AS oldest, avg(age) AS average FROM People p GROUP BY department ORDER BY department")) {
				var rows = new ArrayList<Object[]>();
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("department"), rs.getLong("cnt"), rs.getLong("total"),
							rs.getLong("youngest"), rs.getLong("oldest"), rs.getDouble("average") });
				}
				assertThat(rows).hasSize(3);
				// Engineering
				assertThat((String) rows.get(0)[0]).isEqualTo("Engineering");
				assertThat((long) rows.get(0)[1]).isEqualTo(2L);
				assertThat((long) rows.get(0)[2]).isEqualTo(55L);
				assertThat((long) rows.get(0)[3]).isEqualTo(25L);
				assertThat((long) rows.get(0)[4]).isEqualTo(30L);
				assertThat((double) rows.get(0)[5]).isCloseTo(27.5, within(0.1));
				// Marketing
				assertThat((String) rows.get(1)[0]).isEqualTo("Marketing");
				assertThat((long) rows.get(1)[1]).isEqualTo(1L);
				assertThat((long) rows.get(1)[2]).isEqualTo(32L);
				assertThat((long) rows.get(1)[3]).isEqualTo(32L);
				assertThat((long) rows.get(1)[4]).isEqualTo(32L);
				assertThat((double) rows.get(1)[5]).isCloseTo(32.0, within(0.1));
				// Sales
				assertThat((String) rows.get(2)[0]).isEqualTo("Sales");
				assertThat((long) rows.get(2)[1]).isEqualTo(2L);
				assertThat((long) rows.get(2)[2]).isEqualTo(63L);
				assertThat((long) rows.get(2)[3]).isEqualTo(28L);
				assertThat((long) rows.get(2)[4]).isEqualTo(35L);
				assertThat((double) rows.get(2)[5]).isCloseTo(31.5, within(0.1));
			}
		}

		@Test
		void moviesMultipleAggregates() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT m.released, count(*) AS cnt, min(m.title) AS first_title, max(m.title) AS last_title FROM Movie m GROUP BY m.released ORDER BY m.released")) {
				var rowCount = 0;
				while (rs.next()) {
					rowCount++;
					assertThat(rs.getLong("cnt")).isGreaterThan(0);
					assertThat(rs.getString("first_title")).isNotNull();
					assertThat(rs.getString("last_title")).isNotNull();
					assertThat(rs.getString("first_title").compareTo(rs.getString("last_title")))
						.isLessThanOrEqualTo(0);
				}
				assertThat(rowCount).isGreaterThan(10);
			}
		}

		@Test
		void multiAggregateWithOrderBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt, sum(age) AS total FROM People p GROUP BY department ORDER BY total DESC")) {
				var rows = collectRows(rs, "department", "cnt", "total");
				assertThat(rows).containsExactly(List.of("Sales", 2L, 63L), List.of("Engineering", 2L, 55L),
						List.of("Marketing", 1L, 32L));
			}
		}

		@Test
		void whereAndMultiAggregateAndOrderBy() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery(
							"SELECT department, count(*) AS cnt, avg(age) AS average FROM People p WHERE age >= 28 GROUP BY department ORDER BY average DESC")) {
				// Alice(30) = Engineering, Charlie(35)+Diana(28) = Sales, Eve(32) =
				// Marketing
				var rows = new ArrayList<Object[]>();
				while (rs.next()) {
					rows.add(new Object[] { rs.getString("department"), rs.getLong("cnt"), rs.getDouble("average") });
				}
				assertThat(rows).hasSize(3);
				// Sales: avg(35,28) = 31.5, Marketing: avg(32) = 32.0, Engineering:
				// avg(30) = 30.0
				// ORDER BY average DESC → Marketing(32.0), Sales(31.5),
				// Engineering(30.0)
				assertThat((String) rows.get(0)[0]).isEqualTo("Marketing");
				assertThat((long) rows.get(0)[1]).isEqualTo(1L);
				assertThat((double) rows.get(0)[2]).isCloseTo(32.0, within(0.1));
				assertThat((String) rows.get(1)[0]).isEqualTo("Sales");
				assertThat((long) rows.get(1)[1]).isEqualTo(2L);
				assertThat((double) rows.get(1)[2]).isCloseTo(31.5, within(0.1));
				assertThat((String) rows.get(2)[0]).isEqualTo("Engineering");
				assertThat((long) rows.get(2)[1]).isEqualTo(1L);
				assertThat((double) rows.get(2)[2]).isCloseTo(30.0, within(0.1));
			}
		}

	}

	// -- Regression Against Existing IT Patterns ------------------------------

	@Nested
	class RegressionTests {

		@Test
		void selectStarUnchanged() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT * FROM Movie m")) {
				var count = 0;
				while (rs.next()) {
					assertThat(rs.getString("title")).isNotNull();
					count++;
				}
				assertThat(count).isEqualTo(38);
			}
		}

		@Test
		void whereWithLikeUnchanged() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT name FROM Person p WHERE p.name LIKE '%Reeves%'")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString("name")).isEqualTo("Keanu Reeves");
				assertThat(rs.next()).isFalse();
			}
		}

		@Test
		void literalSelectUnchanged() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT 1")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getInt(1)).isOne();
			}
		}

		@Test
		void insertThenCount() throws SQLException {
			try (var connection = getConnection(true, false)) {
				try (var stmt = connection.createStatement()) {
					stmt.executeUpdate("INSERT INTO People (name, age, department) VALUES ('TestUser', 40, 'HR')");
				}
				try (var stmt = connection.createStatement();
						var rs = stmt.executeQuery("SELECT count(*) FROM People p")) {
					assertThat(rs.next()).isTrue();
					// 5 original + 1 inserted
					assertThat(rs.getLong(1)).isEqualTo(6L);
				}
			}
			// Clean up to avoid polluting other tests (doClean = false)
			try (var connection = getConnection(false, false); var stmt = connection.createStatement()) {
				stmt.execute("MATCH (p:People {name: 'TestUser'}) DELETE p");
			}
		}

		@Test
		void countWithNoResults() throws SQLException {
			try (var connection = getConnection(true, false);
					var stmt = connection.createStatement();
					var rs = stmt.executeQuery("SELECT count(*) FROM People p WHERE age > 100")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong(1)).isZero();
			}
		}

	}

	// -- Helper methods -------------------------------------------------------

	/**
	 * Collects ResultSet rows into a list of lists. Each inner list contains the values
	 * for the specified columns. String columns are read as getString, numeric columns as
	 * getLong.
	 */
	private static List<List<Object>> collectRows(ResultSet rs, String... columns) throws SQLException {
		var rows = new ArrayList<List<Object>>();
		while (rs.next()) {
			var row = new ArrayList<Object>();
			for (var col : columns) {
				var value = rs.getObject(col);
				if (value instanceof Number n) {
					row.add(n.longValue());
				}
				else {
					row.add(value);
				}
			}
			rows.add(row);
		}
		return rows;
	}

}
