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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.jdbc.translator.spi.Translator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Living documentation tests for GROUP BY and HAVING translation. Each test translates
 * SQL through the actual translator and verifies the Cypher output. Running
 * {@link #generateMarkdown()} prints every translation in the markdown format used by
 * {@code research/SQL_GROUP_BY_HAVING_TO_CYPHER.md}, so the document can be regenerated from
 * actual translator output.
 *
 * @author Ryan Knight
 */
class GroupByDocumentationTests {

	private static final Translator TRANSLATOR = SqlToCypher.defaultTranslator();

	// -------------------------------------------------------------------
	// Test data: each entry is (category, description, sql, expectedCypher)
	// -------------------------------------------------------------------

	private record DocExample(String category, String description, String sql, String expectedCypher) {
	}

	private static final List<DocExample> ALL_EXAMPLES = new ArrayList<>();

	static {
		// Simple GROUP BY (implicit grouping — no WITH needed)
		ALL_EXAMPLES.add(new DocExample("Simple GROUP BY (implicit grouping)", "count(*) grouped by name",
				"SELECT name, count(*) FROM People p GROUP BY name",
				"MATCH (p:People) RETURN p.name AS name, count(*)"));

		ALL_EXAMPLES.add(new DocExample("Simple GROUP BY (implicit grouping)", "max(age) grouped by name",
				"SELECT name, max(age) FROM People p GROUP BY name",
				"MATCH (p:People) RETURN p.name AS name, max(p.age)"));

		// GROUP BY column not in SELECT (WITH clause needed)
		ALL_EXAMPLES.add(new DocExample("GROUP BY column not in SELECT (WITH clause needed)",
				"sum(age) grouped by name, name not in SELECT", "SELECT sum(age) FROM People p GROUP BY name",
				"MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0"));

		// JOIN with GROUP BY
		ALL_EXAMPLES.add(new DocExample("JOIN with GROUP BY", "JOIN with GROUP BY column in SELECT",
				"SELECT c.name, count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name",
				"MATCH (c:Customers)<-[customer_id:CUSTOMER_ID]-(o:Orders) RETURN c.name, count(*)"));

		ALL_EXAMPLES.add(new DocExample("JOIN with GROUP BY", "JOIN with GROUP BY column not in SELECT",
				"SELECT count(*) FROM Customers c JOIN Orders o ON c.id = o.customer_id GROUP BY c.name",
				"MATCH (c:Customers)<-[customer_id:CUSTOMER_ID]-(o:Orders) WITH count(*) AS __with_col_0, c.name AS __group_col_1 RETURN __with_col_0"));

		// HAVING — filtering aggregated results
		ALL_EXAMPLES
			.add(new DocExample("HAVING (filtering aggregated results)", "HAVING with alias reference (cnt > 5)",
					"SELECT name, count(*) AS cnt FROM People p GROUP BY name HAVING cnt > 5",
					"MATCH (p:People) WITH p.name AS name, count(*) AS cnt WHERE cnt > 5 RETURN name, cnt"));

		// HAVING with aggregate not in SELECT (hidden column)
		ALL_EXAMPLES.add(new DocExample("HAVING with aggregate not in SELECT",
				"count(*) not in SELECT, injected as hidden column",
				"SELECT name FROM People p GROUP BY name HAVING count(*) > 5",
				"MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name"));

		// Compound HAVING with multiple aggregates
		ALL_EXAMPLES.add(new DocExample("Compound HAVING with multiple aggregates",
				"count(*) > 5 AND max(age) > 50, both hidden",
				"SELECT name FROM People p GROUP BY name HAVING count(*) > 5 AND max(age) > 50",
				"MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0, max(p.age) AS __having_col_1 WHERE (__having_col_0 > 5 AND __having_col_1 > 50) RETURN name"));

		// Mixed: some aggregates in SELECT, some only in HAVING
		ALL_EXAMPLES.add(new DocExample("Mixed aggregates (SELECT + HAVING)",
				"sum(age) in SELECT, count(*) only in HAVING",
				"SELECT name, sum(age) FROM People p GROUP BY name HAVING sum(age) > 100 AND count(*) > 2",
				"MATCH (p:People) WITH p.name AS name, sum(p.age) AS __with_col_0, count(*) AS __having_col_1 WHERE (__with_col_0 > 100 AND __having_col_1 > 2) RETURN name, __with_col_0"));

		// HAVING without GROUP BY
		ALL_EXAMPLES.add(new DocExample("HAVING without GROUP BY", "entire table as one implicit group",
				"SELECT count(*) FROM People p HAVING count(*) > 5",
				"MATCH (p:People) WITH count(*) AS __with_col_0 WHERE __with_col_0 > 5 RETURN __with_col_0"));

		// ORDER BY with WITH clause
		ALL_EXAMPLES.add(new DocExample("ORDER BY with WITH clause", "ORDER BY references WITH alias",
				"SELECT sum(age) FROM People p GROUP BY name ORDER BY sum(age)",
				"MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 RETURN __with_col_0 ORDER BY __with_col_0"));

		// HAVING + ORDER BY together
		ALL_EXAMPLES.add(new DocExample("HAVING + ORDER BY together", "HAVING filters, ORDER BY sorts result",
				"SELECT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name",
				"MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN name ORDER BY name"));

		// HAVING with COUNT(DISTINCT)
		ALL_EXAMPLES.add(new DocExample("HAVING with COUNT(DISTINCT)", "DISTINCT flag preserved in count()",
				"SELECT name FROM People p GROUP BY name HAVING count(DISTINCT age) > 3",
				"MATCH (p:People) WITH p.name AS name, count(DISTINCT p.age) AS __having_col_0 WHERE __having_col_0 > 3 RETURN name"));

		// HAVING on a non-aggregate GROUP BY column
		ALL_EXAMPLES.add(new DocExample("HAVING on non-aggregate GROUP BY column",
				"HAVING references GROUP BY column, not an aggregate",
				"SELECT count(*) FROM People p GROUP BY name HAVING name = 'Alice'",
				"MATCH (p:People) WITH count(*) AS __with_col_0, p.name AS __group_col_1 WHERE __group_col_1 = 'Alice' RETURN __with_col_0"));

		// HAVING on aggregate already in SELECT
		ALL_EXAMPLES.add(new DocExample("HAVING on aggregate already in SELECT",
				"sum(age) in both SELECT and HAVING, no hidden column needed",
				"SELECT sum(age) FROM People p GROUP BY name HAVING sum(age) > 100",
				"MATCH (p:People) WITH sum(p.age) AS __with_col_0, p.name AS __group_col_1 WHERE __with_col_0 > 100 RETURN __with_col_0"));

		// Full combination: all clauses
		ALL_EXAMPLES.add(new DocExample("Full combination", "GROUP BY + HAVING + DISTINCT + ORDER BY + LIMIT + OFFSET",
				"SELECT DISTINCT name FROM People p GROUP BY name HAVING count(*) > 5 ORDER BY name LIMIT 10 OFFSET 5",
				"MATCH (p:People) WITH p.name AS name, count(*) AS __having_col_0 WHERE __having_col_0 > 5 RETURN DISTINCT name ORDER BY name SKIP 5 LIMIT 10"));

		ALL_EXAMPLES.add(new DocExample("Full combination",
				"WHERE + multiple HAVING aggregates + DISTINCT + ORDER BY + LIMIT + OFFSET",
				"SELECT DISTINCT department, count(*) AS cnt, max(age) AS max_age FROM People p WHERE age > 18 GROUP BY department HAVING count(*) > 1 AND max(age) > 25 ORDER BY cnt DESC LIMIT 10 OFFSET 2",
				"MATCH (p:People) WHERE p.age > 18 WITH p.department AS department, count(*) AS cnt, max(p.age) AS max_age WHERE (cnt > 1 AND max_age > 25) RETURN DISTINCT department, cnt, max_age ORDER BY cnt DESC SKIP 2 LIMIT 10"));
	}

	static Stream<Arguments> allExamples() {
		return ALL_EXAMPLES.stream()
			.map(ex -> Arguments.of(ex.category() + ": " + ex.description(), ex.sql(), ex.expectedCypher()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allExamples")
	void verifyTranslation(String label, String sql, String expectedCypher) {
		assertThat(TRANSLATOR.translate(sql)).isEqualTo(expectedCypher);
	}

	/**
	 * Generates the Example Translations section in markdown format by running every SQL
	 * example through the actual translator. Run this test and check stdout to regenerate
	 * the documentation from real translator output.
	 */
	@Test
	void generateMarkdown() {
		// Group examples by category, preserving insertion order
		Map<String, List<DocExample>> byCategory = new LinkedHashMap<>();
		for (DocExample ex : ALL_EXAMPLES) {
			byCategory.computeIfAbsent(ex.category(), k -> new ArrayList<>()).add(ex);
		}

		var sb = new StringBuilder();
		sb.append("## Example Translations\n\n");
		sb.append(
				"This section shows representative SQL queries and the Cypher the translator now produces. These are drawn from the actual test suite and cover the major categories the implementation handles.\n\n");

		for (var entry : byCategory.entrySet()) {
			sb.append("### ").append(entry.getKey()).append("\n\n");
			for (DocExample ex : entry.getValue()) {
				String actualCypher = TRANSLATOR.translate(ex.sql());
				sb.append("```sql\n").append(ex.sql()).append("\n```\n");
				sb.append("```cypher\n").append(actualCypher).append("\n```\n\n");
			}
		}

		System.out.println(sb);
	}

}
