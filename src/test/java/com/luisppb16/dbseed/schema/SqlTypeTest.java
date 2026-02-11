/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlTypeTest {

  static Stream<Arguments> toSqlCases() {
    return Stream.of(
        Arguments.of(SqlType.INT, "INT"),
        Arguments.of(SqlType.VARCHAR, "VARCHAR(255)"),
        Arguments.of(SqlType.TEXT, "TEXT"),
        Arguments.of(SqlType.DECIMAL, "DECIMAL(10, 2)"),
        Arguments.of(SqlType.TIMESTAMP, "TIMESTAMP"),
        Arguments.of(SqlType.BOOLEAN, "BOOLEAN"));
  }

  @ParameterizedTest
  @MethodSource("toSqlCases")
  void toSql_returnsExpected(SqlType type, String expected) {
    assertThat(type.toSql()).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("toSqlCases")
  void toString_sameAsToSql(SqlType type, String expected) {
    assertThat(type.toString()).isEqualTo(expected);
  }
}
