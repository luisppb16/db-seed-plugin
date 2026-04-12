/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValueGeneratorTest {

  private ValueGenerator gen;
  private Set<UUID> usedUuids;

  private static Column col(final int jdbcType) {
    return new Column("c", jdbcType, null, false, false, false, 0, 0, null, null, Set.of());
  }

  private static Column col(final int jdbcType, final int length) {
    return new Column("c", jdbcType, null, false, false, false, length, 0, null, null, Set.of());
  }

  private static ParsedConstraint noConstraint() {
    return ParsedConstraint.empty();
  }

  static Stream<Arguments> numericTrueTypes() {
    return Stream.of(
        Arguments.of(Types.INTEGER),
        Arguments.of(Types.SMALLINT),
        Arguments.of(Types.TINYINT),
        Arguments.of(Types.BIGINT),
        Arguments.of(Types.DECIMAL),
        Arguments.of(Types.NUMERIC),
        Arguments.of(Types.FLOAT),
        Arguments.of(Types.DOUBLE),
        Arguments.of(Types.REAL));
  }

  // ── Type generation ──

  @BeforeEach
  void setUp() {
    usedUuids = new HashSet<>();
    gen = new ValueGenerator(new Faker(), List.of(), false, usedUuids, 2);
  }

  @Test
  void varchar_generatesString() {
    assertThat(gen.generateValue(col(Types.VARCHAR, 100), noConstraint(), 0))
        .isInstanceOf(String.class);
  }

  @Test
  void int_generatesInteger() {
    assertThat(gen.generateValue(col(Types.INTEGER), noConstraint(), 0))
        .isInstanceOf(Integer.class);
  }

  @Test
  void bigint_generatesLong() {
    assertThat(gen.generateValue(col(Types.BIGINT), noConstraint(), 0)).isInstanceOf(Long.class);
  }

  @Test
  void boolean_generatesBoolean() {
    assertThat(gen.generateValue(col(Types.BOOLEAN), noConstraint(), 0))
        .isInstanceOf(Boolean.class);
  }

  @Test
  void date_generatesDate() {
    assertThat(gen.generateValue(col(Types.DATE), noConstraint(), 0)).isInstanceOf(Date.class);
  }

  @Test
  void timestamp_generatesTimestamp() {
    assertThat(gen.generateValue(col(Types.TIMESTAMP), noConstraint(), 0))
        .isInstanceOf(Timestamp.class);
  }

  @Test
  void decimal_generatesBigDecimal() {
    final Column c = new Column("c", Types.DECIMAL, null, false, false, false, 10, 2, null, null, Set.of());
    assertThat(gen.generateValue(c, noConstraint(), 0)).isInstanceOf(BigDecimal.class);
  }

  @Test
  void double_generatesDouble() {
    assertThat(gen.generateValue(col(Types.DOUBLE), noConstraint(), 0)).isInstanceOf(Double.class);
  }

  // ── UUID ──

  @Test
  void unknown_generatesIndex() {
    assertThat(gen.generateValue(col(Types.OTHER), noConstraint(), 7)).isEqualTo(7);
  }

  @Test
  void array_generatesArray() {
    assertThat(gen.generateValue(col(Types.ARRAY), noConstraint(), 0)).isInstanceOf(String[].class);
  }

  @Test
  void uuid_uniqueUuid() {
    final Column c = new Column("c", Types.VARCHAR, null, false, false, true, 0, 0, null, null, Set.of());
    final Object v1 = gen.generateValue(c, noConstraint(), 0);
    final Object v2 = gen.generateValue(c, noConstraint(), 1);
    assertThat(v1).isInstanceOf(UUID.class);
    assertThat(v1).isNotEqualTo(v2);
  }

  @Test
  void uuid_allowedValuesUuid() {
    final UUID expected = UUID.randomUUID();
    final Column c =
        new Column(
            "c", Types.VARCHAR, null, false, false, true, 0, 0, null, null, Set.of(expected.toString()));
    final Object val = gen.generateValue(c, noConstraint(), 0);
    assertThat(val).isEqualTo(expected);
  }

  // ── Constraints ──

  @Test
  void uuid_generatesNew() {
    final Column c = new Column("c", Types.VARCHAR, null, false, false, true, 0, 0, null, null, Set.of());
    assertThat(gen.generateValue(c, noConstraint(), 0)).isInstanceOf(UUID.class);
  }

  @Test
  void columnAllowedValues() {
    final Column c =
        new Column("c", Types.VARCHAR, null, false, false, false, 0, 0, null, null, Set.of("a", "b"));
    final Object val = gen.generateValue(c, noConstraint(), 0);
    assertThat(val).isIn("a", "b");
  }

  @Test
  void constraintAllowedValues() {
    final Column c = col(Types.VARCHAR, 50);
    final ParsedConstraint pc = new ParsedConstraint(null, null, Set.of("x", "y"), null);
    final Object val = gen.generateValue(c, pc, 0);
    assertThat(val).isIn("x", "y");
  }

  @Test
  void numericMinMax() {
    final Column c = col(Types.INTEGER);
    final ParsedConstraint pc = new ParsedConstraint(10.0, 20.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      final Object val = gen.generateValue(c, pc, i);
      assertThat(val).isInstanceOf(Integer.class);
      assertThat((Integer) val).isBetween(10, 20);
    }
  }

  @Test
  void stringMaxLength() {
    final Column c = col(Types.VARCHAR, 5);
    for (int i = 0; i < 20; i++) {
      final Object val = gen.generateValue(c, noConstraint(), i);
      if (val instanceof String s) {
        assertThat(s.length()).isLessThanOrEqualTo(5);
      }
    }
  }

  // ── Nullable ──

  @Test
  void charPadding() {
    final Column c = new Column("c", Types.CHAR, null, false, false, false, 10, 0, null, null, Set.of());
    final Object val = gen.generateValue(c, noConstraint(), 0);
    if (val instanceof String s) {
      assertThat(s.length()).isEqualTo(10);
    }
  }

  @Test
  void nullable_sometimesNull() {
    final Column c = new Column("c", Types.VARCHAR, null, true, false, false, 50, 0, null, null, Set.of());
    boolean foundNull = false;
    boolean foundNonNull = false;
    for (int i = 0; i < 100; i++) {
      final Object val = gen.generateValue(c, noConstraint(), i);
      if (val == null) foundNull = true;
      else foundNonNull = true;
    }
    assertThat(foundNull).isTrue();
    assertThat(foundNonNull).isTrue();
  }

  // ── generateNumericWithinBounds ──

  @Test
  void nonNullable_neverNull() {
    final Column c = new Column("c", Types.INTEGER, null, false, false, false, 0, 0, null, null, Set.of());
    for (int i = 0; i < 100; i++) {
      assertThat(gen.generateValue(c, noConstraint(), i)).isNotNull();
    }
  }

  @Test
  void numericBounds_int() {
    final Column c = col(Types.INTEGER);
    final ParsedConstraint pc = new ParsedConstraint(5.0, 15.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      final Object val = gen.generateNumericWithinBounds(c, pc);
      assertThat((Integer) val).isBetween(5, 15);
    }
  }

  @Test
  void numericBounds_bigint() {
    final Column c = col(Types.BIGINT);
    final ParsedConstraint pc = new ParsedConstraint(100.0, 200.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      final Object val = gen.generateNumericWithinBounds(c, pc);
      assertThat((Long) val).isBetween(100L, 200L);
    }
  }

  @Test
  void numericBounds_decimal() {
    final Column c = new Column("c", Types.DECIMAL, null, false, false, false, 10, 2, null, null, Set.of());
    final ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      final Object val = gen.generateNumericWithinBounds(c, pc);
      assertThat(val).isInstanceOf(BigDecimal.class);
      assertThat(((BigDecimal) val).doubleValue()).isBetween(1.0, 10.0);
    }
  }

  @Test
  void numericBounds_invertedSwaps() {
    final Column c = col(Types.INTEGER);
    final ParsedConstraint pc = new ParsedConstraint(50.0, 10.0, Set.of(), null);
    final Object val = gen.generateNumericWithinBounds(c, pc);
    assertThat((Integer) val).isBetween(10, 50);
  }

  // ── Soft delete ──

  @Test
  void numericBounds_unsupportedType_null() {
    final Column c = col(Types.VARCHAR);
    final ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(gen.generateNumericWithinBounds(c, pc)).isNull();
  }

  @Test
  void softDelete_defaultKeyword() {
    final Column c = col(Types.INTEGER);
    assertThat(gen.generateSoftDeleteValue(c, true, "anything")).isEqualTo(SqlKeyword.DEFAULT);
  }

  @Test
  void softDelete_integerConversion() {
    final Column c = col(Types.INTEGER);
    assertThat(gen.generateSoftDeleteValue(c, false, "42")).isEqualTo(42);
  }

  @Test
  void softDelete_boolean() {
    final Column c = col(Types.BOOLEAN);
    assertThat(gen.generateSoftDeleteValue(c, false, "true")).isEqualTo(true);
  }

  @Test
  void softDelete_null_literal() {
    final Column c = col(Types.VARCHAR);
    assertThat(gen.generateSoftDeleteValue(c, false, "NULL")).isNull();
  }

  // ── isNumericJdbc ──

  @Test
  void softDelete_null_value() {
    final Column c = col(Types.VARCHAR);
    assertThat(gen.generateSoftDeleteValue(c, false, null)).isNull();
  }

  @ParameterizedTest
  @MethodSource("numericTrueTypes")
  void isNumericJdbc_true(final int type) {
    assertThat(ValueGenerator.isNumericJdbc(type)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {Types.VARCHAR, Types.BOOLEAN, Types.DATE, Types.TIMESTAMP})
  void isNumericJdbc_false(final int type) {
    assertThat(ValueGenerator.isNumericJdbc(type)).isFalse();
  }

  // ── isNumericOutsideBounds ──

  @Test
  void outsideBounds_withinBounds() {
    final ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(5, pc)).isFalse();
  }

  @Test
  void outsideBounds_belowMin() {
    final ParsedConstraint pc = new ParsedConstraint(10.0, null, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(5, pc)).isTrue();
  }

  @Test
  void outsideBounds_aboveMax() {
    final ParsedConstraint pc = new ParsedConstraint(null, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(15, pc)).isTrue();
  }

  @Test
  void outsideBounds_nullValue() {
    final ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(null, pc)).isFalse();
  }

  @Test
  void outsideBounds_nullConstraint() {
    assertThat(ValueGenerator.isNumericOutsideBounds(5, null)).isFalse();
  }

  @Test
  void outsideBounds_nonNumericString() {
    final ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds("abc", pc)).isTrue();
  }

  @Test
  void regexGeneration_returnsValueMatchingPattern() {
    final String regex = "#[0-9A-F]{6}";
    final String generated = gen.generateStringFromRegex(regex, 7, Types.VARCHAR);
    assertThat(generated).isNotNull();
    assertThat(Pattern.matches(regex, generated)).isTrue();
  }

  @Test
  void regexGeneration_invalidPattern_returnsNull() {
    assertThat(gen.generateStringFromRegex("[", 10, Types.VARCHAR)).isNull();
  }
}