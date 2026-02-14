/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.*;

import com.luisppb16.dbseed.db.generator.ConstraintParser.CheckExpression;
import com.luisppb16.dbseed.db.generator.ConstraintParser.MultiColumnConstraint;
import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ConstraintParserTest {

  private static CheckExpression ce(String expr) {
    String noParens = expr.replaceAll("[()]+", " ");
    return new CheckExpression(expr, noParens, noParens.toLowerCase(Locale.ROOT));
  }

  private ParsedConstraint parse(String columnName, String check, int colLength) {
    return new ConstraintParser(columnName).parse(List.of(ce(check)), colLength);
  }

  private ParsedConstraint parse(String columnName, String check) {
    return parse(columnName, check, 0);
  }

  // ── BETWEEN ──

  @Test
  void between_basic() {
    ParsedConstraint pc = parse("age", "age BETWEEN 1 AND 100");
    assertThat(pc.min()).isEqualTo(1.0);
    assertThat(pc.max()).isEqualTo(100.0);
  }

  @Test
  void between_decimals() {
    ParsedConstraint pc = parse("price", "price BETWEEN 0.5 AND 99.9");
    assertThat(pc.min()).isEqualTo(0.5);
    assertThat(pc.max()).isEqualTo(99.9);
  }

  @Test
  void between_reversedBounds() {
    ParsedConstraint pc = parse("val", "val BETWEEN 100 AND 1");
    assertThat(pc.min()).isEqualTo(1.0);
    assertThat(pc.max()).isEqualTo(100.0);
  }

  // ── Range operators ──

  @Test
  void range_greaterThanOrEqual() {
    ParsedConstraint pc = parse("x", "x >= 5");
    assertThat(pc.min()).isEqualTo(5.0);
  }

  @Test
  void range_lessThanOrEqual() {
    ParsedConstraint pc = parse("x", "x <= 50");
    assertThat(pc.max()).isEqualTo(50.0);
  }

  @Test
  void range_greaterThan() {
    ParsedConstraint pc = parse("x", "x > 5");
    assertThat(pc.min()).isEqualTo(Math.nextUp(5.0));
  }

  @Test
  void range_lessThan() {
    ParsedConstraint pc = parse("x", "x < 50");
    assertThat(pc.max()).isEqualTo(Math.nextDown(50.0));
  }

  @Test
  void range_equality() {
    ParsedConstraint pc = parse("x", "x = 42");
    assertThat(pc.min()).isEqualTo(42.0);
    assertThat(pc.max()).isEqualTo(42.0);
  }

  @Test
  void range_multipleTighteningBounds() {
    ConstraintParser parser = new ConstraintParser("x");
    ParsedConstraint result = parser.parse(List.of(ce("x >= 10"), ce("x <= 50"), ce("x >= 20")), 0);
    assertThat(result.min()).isEqualTo(20.0);
    assertThat(result.max()).isEqualTo(50.0);
  }

  // ── IN list ──

  @Test
  void inList_strings() {
    ParsedConstraint pc = parse("status", "status IN ('active', 'inactive', 'pending')");
    assertThat(pc.allowedValues()).containsExactlyInAnyOrder("active", "inactive", "pending");
  }

  @Test
  void inList_numbers() {
    ParsedConstraint pc = parse("code", "code IN (1, 2, 3)");
    assertThat(pc.allowedValues()).containsExactlyInAnyOrder("1", "2", "3");
  }

  // ── ANY ARRAY ──

  @Test
  void anyArray_postgresCasts() {
    ParsedConstraint pc = parse("role", "role = ANY ARRAY['admin'::text, 'user'::text]");
    assertThat(pc.allowedValues()).containsExactlyInAnyOrder("admin", "user");
  }

  // ── Equality ──

  @Test
  void equality_stringValue() {
    ParsedConstraint pc = parse("type", "type = 'special'");
    assertThat(pc.allowedValues()).contains("special");
  }

  // ── Length ──

  @Test
  void length_constraint() {
    ParsedConstraint pc = parse("name", "length(name) <= 50");
    assertThat(pc.maxLength()).isEqualTo(50);
  }

  @Test
  void charLength_constraint() {
    ParsedConstraint pc = parse("name", "char_length(name) <= 30");
    assertThat(pc.maxLength()).isEqualTo(30);
  }

  // ── Column length override ──

  @Test
  void columnLength_smallerThanConstraint() {
    ParsedConstraint pc = parse("name", "length(name) <= 50", 30);
    assertThat(pc.maxLength()).isEqualTo(30);
  }

  @Test
  void columnLength_largerThanConstraint() {
    ParsedConstraint pc = parse("name", "length(name) <= 20", 50);
    assertThat(pc.maxLength()).isEqualTo(20);
  }

  // ── Edge cases ──

  @Test
  void nullInput_returnsEmpty() {
    ParsedConstraint pc = new ConstraintParser("x").parse(null, 0);
    assertThat(pc).isEqualTo(ParsedConstraint.empty());
  }

  @Test
  void emptyInput_returnsEmpty() {
    ParsedConstraint pc = new ConstraintParser("x").parse(List.of(), 0);
    assertThat(pc).isEqualTo(ParsedConstraint.empty());
  }

  @Test
  void differentColumnName_ignored() {
    ParsedConstraint pc = parse("age", "height BETWEEN 1 AND 200");
    assertThat(pc.min()).isNull();
    assertThat(pc.max()).isNull();
  }

  @Test
  void qualifiedNames() {
    ParsedConstraint pc = parse("val", "schema.table.val BETWEEN 1 AND 10");
    assertThat(pc.min()).isEqualTo(1.0);
    assertThat(pc.max()).isEqualTo(10.0);
  }

  // ── Length strict less-than vs less-than-or-equal ──

  @Test
  void length_strictLessThan_subtractsOne() {
    ParsedConstraint pc = parse("name", "length(name) < 10");
    assertThat(pc.maxLength()).isEqualTo(9);
  }

  @Test
  void length_lessThanOrEqual_unchanged() {
    ParsedConstraint pc = parse("name", "length(name) <= 10");
    assertThat(pc.maxLength()).isEqualTo(10);
  }

  @Test
  void length_equal_unchanged() {
    ParsedConstraint pc = parse("name", "char_length(name) = 5");
    assertThat(pc.maxLength()).isEqualTo(5);
  }

  @Test
  void length_strictLessThan_multipleConstraints_tightest() {
    ConstraintParser parser = new ConstraintParser("name");
    ParsedConstraint result =
        parser.parse(List.of(ce("length(name) < 20"), ce("length(name) <= 15")), 0);
    assertThat(result.maxLength()).isEqualTo(15);
  }

  @Test
  void length_strictLessThan_tighterThanLessEqual() {
    ConstraintParser parser = new ConstraintParser("name");
    ParsedConstraint result =
        parser.parse(List.of(ce("length(name) < 10"), ce("length(name) <= 20")), 0);
    assertThat(result.maxLength()).isEqualTo(9);
  }

  @Test
  void length_columnLengthOverrides_strictLessThan() {
    ParsedConstraint pc = parse("name", "length(name) < 100", 50);
    assertThat(pc.maxLength()).isEqualTo(50);
  }

  // ── Cache behavior ──

  @Test
  void cache_sameColumn_returnsSamePatterns() {
    ConstraintParser parser1 = new ConstraintParser("cached_col");
    ConstraintParser parser2 = new ConstraintParser("cached_col");
    ParsedConstraint pc1 = parser1.parse(List.of(ce("cached_col >= 1")), 0);
    ParsedConstraint pc2 = parser2.parse(List.of(ce("cached_col >= 1")), 0);
    assertThat(pc1.min()).isEqualTo(pc2.min());
  }

  @Test
  void cache_differentColumns_bothWork() {
    ParsedConstraint pc1 = parse("col_a", "col_a >= 5");
    ParsedConstraint pc2 = parse("col_b", "col_b <= 10");
    assertThat(pc1.min()).isEqualTo(5.0);
    assertThat(pc2.max()).isEqualTo(10.0);
  }

  // ── Multi-column constraints ──

  @Test
  void multiColumn_orConstraint() {
    List<String> checks =
        List.of("(status = 'active' AND code = 1) OR (status = 'inactive' AND code = 2)");
    List<MultiColumnConstraint> result = ConstraintParser.parseMultiColumnConstraints(checks);
    assertThat(result).hasSize(1);
    MultiColumnConstraint mcc = result.get(0);
    assertThat(mcc.columns()).containsExactlyInAnyOrder("status", "code");
    assertThat(mcc.allowedCombinations()).hasSize(2);
  }

  @Test
  void multiColumn_noEqualsNoAndOr_skipped() {
    List<MultiColumnConstraint> result =
        ConstraintParser.parseMultiColumnConstraints(List.of("x > 5"));
    assertThat(result).isEmpty();
  }

  @Test
  void multiColumn_nullAndBlank_skipped() {
    List<MultiColumnConstraint> result =
        ConstraintParser.parseMultiColumnConstraints(java.util.Arrays.asList(null, "", "  "));
    assertThat(result).isEmpty();
  }

  @Test
  void multiColumn_nestedParens() {
    List<String> checks = List.of("((a = 1 AND b = 2) OR (a = 3 AND b = 4))");
    List<MultiColumnConstraint> result = ConstraintParser.parseMultiColumnConstraints(checks);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).allowedCombinations()).hasSize(2);
  }

  @Test
  void multiColumn_unparsableClause() {
    // clause with AND/OR but no valid col=val pattern
    List<MultiColumnConstraint> result =
        ConstraintParser.parseMultiColumnConstraints(
            List.of("x = 1 OR something_unparsable > 5 AND y"));
    // Should either be empty or have partial results - not crash
    assertThatCode(
            () ->
                ConstraintParser.parseMultiColumnConstraints(
                    List.of("x = 1 OR something_unparsable > 5 AND y")))
        .doesNotThrowAnyException();
  }
}
