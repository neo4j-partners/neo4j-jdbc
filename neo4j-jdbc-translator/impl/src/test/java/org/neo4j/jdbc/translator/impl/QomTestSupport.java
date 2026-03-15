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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared setup and helpers for tests that parse SQL through jOOQ and inspect the Query
 * Object Model (QOM). Provides parser initialization, field unwrapping, and alias
 * extraction utilities.
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
	 * Extracts the underlying field from a {@link SelectFieldOrAsterisk}, unwrapping any
	 * alias wrapper. Returns the expression, not the alias.
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
