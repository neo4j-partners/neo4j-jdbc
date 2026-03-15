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
import java.util.List;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.QOM;

/**
 * Structural field equivalence matcher for jOOQ QOM fields. Determines whether two jOOQ
 * Field objects represent the same expression, ignoring alias wrappers. Used by the alias
 * registry for GROUP BY, HAVING, and ORDER BY alias resolution.
 *
 * @author Ryan Knight
 */
final class FieldMatcher {

	private FieldMatcher() {
	}

	/**
	 * Determines whether two jOOQ {@link Field} objects represent the same expression.
	 * Alias wrappers are stripped before comparison. Supports aggregate functions
	 * ({@code count}, {@code sum}, {@code min}, {@code max}, {@code avg},
	 * {@code stddev_samp}, {@code stddev_pop}), simple column references (with or without
	 * table qualifiers), and combinations thereof.
	 * @param a the first field
	 * @param b the second field
	 * @return {@code true} if the fields are structurally equivalent
	 */
	static boolean fieldsMatch(Field<?> a, Field<?> b) {
		if (a == null || b == null) {
			return false;
		}

		// Case 1: Alias unwrapping — aliases are presentation, not identity
		if (a instanceof QOM.FieldAlias<?> fa) {
			return fieldsMatch(fa.$field(), b);
		}
		if (b instanceof QOM.FieldAlias<?> fb) {
			return fieldsMatch(a, fb.$field());
		}

		// Case 2: Aggregate function matching
		if (a instanceof QOM.Count ca && b instanceof QOM.Count cb) {
			if (ca.$distinct() != cb.$distinct()) {
				return false;
			}
			return fieldsMatch(ca.$field(), cb.$field());
		}
		if (a instanceof QOM.Sum sa && b instanceof QOM.Sum sb) {
			return fieldsMatch(sa.$field(), sb.$field());
		}
		if (a instanceof QOM.Min<?> ma && b instanceof QOM.Min<?> mb) {
			return fieldsMatch(ma.$field(), mb.$field());
		}
		if (a instanceof QOM.Max<?> ma && b instanceof QOM.Max<?> mb) {
			return fieldsMatch(ma.$field(), mb.$field());
		}
		if (a instanceof QOM.Avg aa && b instanceof QOM.Avg ab) {
			return fieldsMatch(aa.$field(), ab.$field());
		}
		if (a instanceof QOM.StddevSamp sa && b instanceof QOM.StddevSamp sb) {
			return fieldsMatch(sa.$field(), sb.$field());
		}
		if (a instanceof QOM.StddevPop sa && b instanceof QOM.StddevPop sb) {
			return fieldsMatch(sa.$field(), sb.$field());
		}

		// If one is an aggregate and the other is not (or different aggregate types),
		// they do not match
		if (isAggregate(a) || isAggregate(b)) {
			return false;
		}

		// Case 3: Simple column references
		// Handle the count(*) asterisk case — jOOQ represents this as an SQLField
		// with toString() = "*", not as org.jooq.Asterisk
		if ("*".equals(a.toString()) && "*".equals(b.toString())) {
			return true;
		}

		// jOOQ's parser produces TableFieldImpl for all column references.
		// When a column is table-qualified (e.g. c.name), getTable() returns
		// the table/alias; when unqualified (e.g. name), getTable() is null.
		var tableA = (a instanceof TableField<?, ?> tfa) ? tfa.getTable() : null;
		var tableB = (b instanceof TableField<?, ?> tfb) ? tfb.getTable() : null;

		if (tableA != null && tableB != null) {
			// Both have table qualifiers — compare table name AND column name
			return tableA.getName().equalsIgnoreCase(tableB.getName()) && a.getName().equalsIgnoreCase(b.getName());
		}

		// At least one has no table qualifier — match on column name alone
		// (pragmatic for single-table queries or mixed qualified/unqualified)
		return a.getName().equalsIgnoreCase(b.getName());
	}

	static boolean isAggregate(Field<?> f) {
		return f instanceof QOM.Count || f instanceof QOM.Sum || f instanceof QOM.Min || f instanceof QOM.Max
				|| f instanceof QOM.Avg || f instanceof QOM.StddevSamp || f instanceof QOM.StddevPop;
	}

	/**
	 * Walks a jOOQ {@link Condition} tree and collects all aggregate sub-expressions
	 * found within it. Handles logical connectives ({@code AND}, {@code OR}, {@code XOR},
	 * {@code NOT}), comparison operators ({@code >}, {@code >=}, {@code <}, {@code <=},
	 * {@code =}, {@code <>}), {@code BETWEEN}, and arithmetic expressions ({@code +},
	 * {@code -}, {@code *}, {@code /}) that may contain nested aggregates.
	 * @param condition the condition tree to walk
	 * @return a list of all aggregate {@link Field} instances found (may contain
	 * duplicates)
	 */
	static List<Field<?>> collectAggregates(Condition condition) {
		var result = new ArrayList<Field<?>>();
		collectAggregatesFromCondition(condition, result);
		return result;
	}

	private static void collectAggregatesFromCondition(Condition condition, List<Field<?>> result) {
		if (condition instanceof QOM.And and) {
			collectAggregatesFromCondition(and.$arg1(), result);
			collectAggregatesFromCondition(and.$arg2(), result);
		}
		else if (condition instanceof QOM.Or or) {
			collectAggregatesFromCondition(or.$arg1(), result);
			collectAggregatesFromCondition(or.$arg2(), result);
		}
		else if (condition instanceof QOM.Xor xor) {
			collectAggregatesFromCondition(xor.$arg1(), result);
			collectAggregatesFromCondition(xor.$arg2(), result);
		}
		else if (condition instanceof QOM.Not not) {
			collectAggregatesFromCondition(not.$arg1(), result);
		}
		else if (condition instanceof QOM.Gt<?> gt) {
			collectAggregatesFromField(gt.$arg1(), result);
			collectAggregatesFromField(gt.$arg2(), result);
		}
		else if (condition instanceof QOM.Ge<?> ge) {
			collectAggregatesFromField(ge.$arg1(), result);
			collectAggregatesFromField(ge.$arg2(), result);
		}
		else if (condition instanceof QOM.Lt<?> lt) {
			collectAggregatesFromField(lt.$arg1(), result);
			collectAggregatesFromField(lt.$arg2(), result);
		}
		else if (condition instanceof QOM.Le<?> le) {
			collectAggregatesFromField(le.$arg1(), result);
			collectAggregatesFromField(le.$arg2(), result);
		}
		else if (condition instanceof QOM.Eq<?> eq) {
			collectAggregatesFromField(eq.$arg1(), result);
			collectAggregatesFromField(eq.$arg2(), result);
		}
		else if (condition instanceof QOM.Ne<?> ne) {
			collectAggregatesFromField(ne.$arg1(), result);
			collectAggregatesFromField(ne.$arg2(), result);
		}
		else if (condition instanceof QOM.Between<?> between) {
			collectAggregatesFromField(between.$arg1(), result);
			collectAggregatesFromField(between.$arg2(), result);
			collectAggregatesFromField(between.$arg3(), result);
		}
	}

	private static void collectAggregatesFromField(Field<?> field, List<Field<?>> result) {
		if (field == null) {
			return;
		}
		if (isAggregate(field)) {
			result.add(field);
		}
		else if (field instanceof QOM.Add<?> add) {
			collectAggregatesFromField(add.$arg1(), result);
			collectAggregatesFromField(add.$arg2(), result);
		}
		else if (field instanceof QOM.Sub<?> sub) {
			collectAggregatesFromField(sub.$arg1(), result);
			collectAggregatesFromField(sub.$arg2(), result);
		}
		else if (field instanceof QOM.Mul<?> mul) {
			collectAggregatesFromField(mul.$arg1(), result);
			collectAggregatesFromField(mul.$arg2(), result);
		}
		else if (field instanceof QOM.Div<?> div) {
			collectAggregatesFromField(div.$arg1(), result);
			collectAggregatesFromField(div.$arg2(), result);
		}
	}

}
