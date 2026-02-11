/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.*;

import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.*;
import java.util.stream.Stream;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValueGeneratorTest {

  private ValueGenerator gen;
  private Set<UUID> usedUuids;

  @BeforeEach
  void setUp() {
    usedUuids = new HashSet<>();
    gen = new ValueGenerator(new Faker(), List.of(), false, usedUuids, 2);
  }

  private static Column col(int jdbcType) {
    return Column.builder().name("c").jdbcType(jdbcType).build();
  }

  private static Column col(int jdbcType, int length) {
    return Column.builder().name("c").jdbcType(jdbcType).length(length).build();
  }

  private static ParsedConstraint noConstraint() {
    return ParsedConstraint.empty();
  }

  // ── Type generation ──

  @Test void varchar_generatesString() {
    assertThat(gen.generateValue(col(Types.VARCHAR, 100), noConstraint(), 0)).isInstanceOf(String.class);
  }

  @Test void int_generatesInteger() {
    assertThat(gen.generateValue(col(Types.INTEGER), noConstraint(), 0)).isInstanceOf(Integer.class);
  }

  @Test void bigint_generatesLong() {
    assertThat(gen.generateValue(col(Types.BIGINT), noConstraint(), 0)).isInstanceOf(Long.class);
  }

  @Test void boolean_generatesBoolean() {
    assertThat(gen.generateValue(col(Types.BOOLEAN), noConstraint(), 0)).isInstanceOf(Boolean.class);
  }

  @Test void date_generatesDate() {
    assertThat(gen.generateValue(col(Types.DATE), noConstraint(), 0)).isInstanceOf(Date.class);
  }

  @Test void timestamp_generatesTimestamp() {
    assertThat(gen.generateValue(col(Types.TIMESTAMP), noConstraint(), 0)).isInstanceOf(Timestamp.class);
  }

  @Test void decimal_generatesBigDecimal() {
    Column c = Column.builder().name("c").jdbcType(Types.DECIMAL).length(10).scale(2).build();
    assertThat(gen.generateValue(c, noConstraint(), 0)).isInstanceOf(BigDecimal.class);
  }

  @Test void double_generatesDouble() {
    assertThat(gen.generateValue(col(Types.DOUBLE), noConstraint(), 0)).isInstanceOf(Double.class);
  }

  @Test void unknown_generatesIndex() {
    assertThat(gen.generateValue(col(Types.OTHER), noConstraint(), 7)).isEqualTo(7);
  }

  // ── UUID ──

  @Test void uuid_uniqueUuid() {
    Column c = Column.builder().name("c").jdbcType(Types.VARCHAR).uuid(true).build();
    Object v1 = gen.generateValue(c, noConstraint(), 0);
    Object v2 = gen.generateValue(c, noConstraint(), 1);
    assertThat(v1).isInstanceOf(UUID.class);
    assertThat(v1).isNotEqualTo(v2);
  }

  @Test void uuid_allowedValuesUuid() {
    UUID expected = UUID.randomUUID();
    Column c = Column.builder().name("c").jdbcType(Types.VARCHAR).uuid(true)
        .allowedValues(Set.of(expected.toString())).build();
    Object val = gen.generateValue(c, noConstraint(), 0);
    assertThat(val).isEqualTo(expected);
  }

  @Test void uuid_generatesNew() {
    Column c = Column.builder().name("c").jdbcType(Types.VARCHAR).uuid(true).build();
    assertThat(gen.generateValue(c, noConstraint(), 0)).isInstanceOf(UUID.class);
  }

  // ── Constraints ──

  @Test void columnAllowedValues() {
    Column c = Column.builder().name("c").jdbcType(Types.VARCHAR).allowedValues(Set.of("a", "b")).build();
    Object val = gen.generateValue(c, noConstraint(), 0);
    assertThat(val).isIn("a", "b");
  }

  @Test void constraintAllowedValues() {
    Column c = col(Types.VARCHAR, 50);
    ParsedConstraint pc = new ParsedConstraint(null, null, Set.of("x", "y"), null);
    Object val = gen.generateValue(c, pc, 0);
    assertThat(val).isIn("x", "y");
  }

  @Test void numericMinMax() {
    Column c = col(Types.INTEGER);
    ParsedConstraint pc = new ParsedConstraint(10.0, 20.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      Object val = gen.generateValue(c, pc, i);
      assertThat(val).isInstanceOf(Integer.class);
      assertThat((Integer) val).isBetween(10, 20);
    }
  }

  @Test void stringMaxLength() {
    Column c = col(Types.VARCHAR, 5);
    for (int i = 0; i < 20; i++) {
      Object val = gen.generateValue(c, noConstraint(), i);
      if (val instanceof String s) {
        assertThat(s.length()).isLessThanOrEqualTo(5);
      }
    }
  }

  @Test void charPadding() {
    Column c = Column.builder().name("c").jdbcType(Types.CHAR).length(10).build();
    Object val = gen.generateValue(c, noConstraint(), 0);
    if (val instanceof String s) {
      assertThat(s.length()).isEqualTo(10);
    }
  }

  // ── Nullable ──

  @Test void nullable_sometimesNull() {
    Column c = Column.builder().name("c").jdbcType(Types.VARCHAR).nullable(true).length(50).build();
    boolean foundNull = false;
    boolean foundNonNull = false;
    for (int i = 0; i < 100; i++) {
      Object val = gen.generateValue(c, noConstraint(), i);
      if (val == null) foundNull = true;
      else foundNonNull = true;
    }
    assertThat(foundNull).isTrue();
    assertThat(foundNonNull).isTrue();
  }

  @Test void nonNullable_neverNull() {
    Column c = Column.builder().name("c").jdbcType(Types.INTEGER).nullable(false).build();
    for (int i = 0; i < 100; i++) {
      assertThat(gen.generateValue(c, noConstraint(), i)).isNotNull();
    }
  }

  // ── generateNumericWithinBounds ──

  @Test void numericBounds_int() {
    Column c = col(Types.INTEGER);
    ParsedConstraint pc = new ParsedConstraint(5.0, 15.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      Object val = gen.generateNumericWithinBounds(c, pc);
      assertThat((Integer) val).isBetween(5, 15);
    }
  }

  @Test void numericBounds_bigint() {
    Column c = col(Types.BIGINT);
    ParsedConstraint pc = new ParsedConstraint(100.0, 200.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      Object val = gen.generateNumericWithinBounds(c, pc);
      assertThat((Long) val).isBetween(100L, 200L);
    }
  }

  @Test void numericBounds_decimal() {
    Column c = Column.builder().name("c").jdbcType(Types.DECIMAL).length(10).scale(2).build();
    ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    for (int i = 0; i < 50; i++) {
      Object val = gen.generateNumericWithinBounds(c, pc);
      assertThat(val).isInstanceOf(BigDecimal.class);
      assertThat(((BigDecimal) val).doubleValue()).isBetween(1.0, 10.0);
    }
  }

  @Test void numericBounds_invertedSwaps() {
    Column c = col(Types.INTEGER);
    ParsedConstraint pc = new ParsedConstraint(50.0, 10.0, Set.of(), null);
    Object val = gen.generateNumericWithinBounds(c, pc);
    assertThat((Integer) val).isBetween(10, 50);
  }

  @Test void numericBounds_unsupportedType_null() {
    Column c = col(Types.VARCHAR);
    ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(gen.generateNumericWithinBounds(c, pc)).isNull();
  }

  // ── Soft delete ──

  @Test void softDelete_defaultKeyword() {
    Column c = col(Types.INTEGER);
    assertThat(gen.generateSoftDeleteValue(c, true, "anything")).isEqualTo(SqlKeyword.DEFAULT);
  }

  @Test void softDelete_integerConversion() {
    Column c = col(Types.INTEGER);
    assertThat(gen.generateSoftDeleteValue(c, false, "42")).isEqualTo(42);
  }

  @Test void softDelete_boolean() {
    Column c = col(Types.BOOLEAN);
    assertThat(gen.generateSoftDeleteValue(c, false, "true")).isEqualTo(true);
  }

  @Test void softDelete_null_literal() {
    Column c = col(Types.VARCHAR);
    assertThat(gen.generateSoftDeleteValue(c, false, "NULL")).isNull();
  }

  @Test void softDelete_null_value() {
    Column c = col(Types.VARCHAR);
    assertThat(gen.generateSoftDeleteValue(c, false, null)).isNull();
  }

  // ── isNumericJdbc ──

  static Stream<Arguments> numericTrueTypes() {
    return Stream.of(
        Arguments.of(Types.INTEGER), Arguments.of(Types.SMALLINT), Arguments.of(Types.TINYINT),
        Arguments.of(Types.BIGINT), Arguments.of(Types.DECIMAL), Arguments.of(Types.NUMERIC),
        Arguments.of(Types.FLOAT), Arguments.of(Types.DOUBLE), Arguments.of(Types.REAL));
  }

  @ParameterizedTest
  @MethodSource("numericTrueTypes")
  void isNumericJdbc_true(int type) {
    assertThat(ValueGenerator.isNumericJdbc(type)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {Types.VARCHAR, Types.BOOLEAN, Types.DATE, Types.TIMESTAMP})
  void isNumericJdbc_false(int type) {
    assertThat(ValueGenerator.isNumericJdbc(type)).isFalse();
  }

  // ── isNumericOutsideBounds ──

  @Test void outsideBounds_withinBounds() {
    ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(5, pc)).isFalse();
  }

  @Test void outsideBounds_belowMin() {
    ParsedConstraint pc = new ParsedConstraint(10.0, null, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(5, pc)).isTrue();
  }

  @Test void outsideBounds_aboveMax() {
    ParsedConstraint pc = new ParsedConstraint(null, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(15, pc)).isTrue();
  }

  @Test void outsideBounds_nullValue() {
    ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds(null, pc)).isFalse();
  }

  @Test void outsideBounds_nullConstraint() {
    assertThat(ValueGenerator.isNumericOutsideBounds(5, null)).isFalse();
  }

  @Test void outsideBounds_nonNumericString() {
    ParsedConstraint pc = new ParsedConstraint(1.0, 10.0, Set.of(), null);
    assertThat(ValueGenerator.isNumericOutsideBounds("abc", pc)).isTrue();
  }
}
