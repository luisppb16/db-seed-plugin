/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import static org.assertj.core.api.Assertions.*;

import com.luisppb16.dbseed.config.DriverInfo;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DialectFactoryTest {

  private static DriverInfo driver(String dialect) {
    return DriverInfo.builder().dialect(dialect).build();
  }

  // ── Auto-detection from driverClass / urlTemplate ──

  static Stream<Arguments> mysqlCases() {
    return Stream.of(
        Arguments.of("mysql", "`", "1", "0"),
        Arguments.of("mariadb", "`", "1", "0"),
        Arguments.of("postgresql", "\"", "TRUE", "FALSE"),
        Arguments.of("sqlserver", "[", "1", "0"),
        Arguments.of("oracle", "\"", "1", "0"),
        Arguments.of("sqlite", "\"", "1", "0"),
        Arguments.of("h2", "\"", "TRUE", "FALSE"));
  }

  @ParameterizedTest
  @MethodSource("dialectCases")
  void resolve_loadsCorrectProperties(String dialect, String expectedQuoteStart,
      String expectedTrue, String expectedFalse) {
    DatabaseDialect d = DialectFactory.resolve(driver(dialect));
    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("col")).startsWith(expectedQuoteStart);
    assertThat(d.formatBoolean(true)).isEqualTo(expectedTrue);
    assertThat(d.formatBoolean(false)).isEqualTo(expectedFalse);
  }

  static Stream<Arguments> sqlserverCases() {
    return Stream.of(
        Arguments.of("com.microsoft.sqlserver.jdbc.SQLServerDriver", ""),
        Arguments.of("", "jdbc:sqlserver://host"));
  }

  @ParameterizedTest
  @MethodSource("sqlserverCases")
  void resolve_sqlserver_squareBrackets(String driverClass, String url) {
    DatabaseDialect d = DialectFactory.resolve(driver(driverClass, url));
    assertThat(d.quote("t")).startsWith("[");
    assertThat(d.formatBoolean(true)).isEqualTo("1");
    assertThat(d.beginTransaction()).isEqualTo("BEGIN TRANSACTION;\n");
  }

  static Stream<Arguments> oracleCases() {
    return Stream.of(
        Arguments.of("oracle.jdbc.OracleDriver", ""),
        Arguments.of("", "jdbc:oracle:thin:@host"));
  }

  @ParameterizedTest
  @MethodSource("oracleCases")
  void resolve_oracle_uppercase(String driverClass, String url) {
    DatabaseDialect d = DialectFactory.resolve(driver(driverClass, url));
    assertThat(d.quote("myCol")).isEqualTo("\"MYCOL\"");
    assertThat(d.formatBoolean(true)).isEqualTo("1");
  }

  static Stream<Arguments> sqliteCases() {
    return Stream.of(
        Arguments.of("org.sqlite.JDBC", ""),
        Arguments.of("", "jdbc:sqlite:file.db"));
  }

  @ParameterizedTest
  @MethodSource("sqliteCases")
  void resolve_sqlite_detected(String driverClass, String url) {
    DatabaseDialect d = DialectFactory.resolve(driver(driverClass, url));
    assertThat(d.formatBoolean(true)).isEqualTo("1");
    assertThat(d.beginTransaction()).isEqualTo("BEGIN TRANSACTION;\n");
  }

  static Stream<Arguments> postgresqlCases() {
    return Stream.of(
        Arguments.of("org.postgresql.Driver", ""),
        Arguments.of("", "jdbc:postgresql://host/db"),
        Arguments.of("", "jdbc:redshift://host/db"),
        Arguments.of("", "jdbc:cockroach://host/db"),
        Arguments.of("org.h2.Driver", ""));
  }

  @ParameterizedTest
  @MethodSource("postgresqlCases")
  void resolve_postgresql_doubleQuotes(String driverClass, String url) {
    DatabaseDialect d = DialectFactory.resolve(driver(driverClass, url));
    assertThat(d.quote("t")).isEqualTo("\"t\"");
    assertThat(d.formatBoolean(true)).isEqualTo("TRUE");
    assertThat(d.beginTransaction()).isEqualTo("BEGIN;\n");
  }

  // ── Explicit dialect field takes priority ──

  @Test
  void resolve_explicitDialect_overridesAutoDetection() {
    DriverInfo info =
        DriverInfo.builder()
            .driverClass("com.mysql.cj.jdbc.Driver")
            .dialect("oracle")
            .build();
    DatabaseDialect d = DialectFactory.resolve(info);
    assertThat(d.quote("col")).isEqualTo("\"COL\"");
  }

  // ── Fallback to standard ──

  @Test
  void resolve_nullDriverInfo_returnsStandard() {
    DatabaseDialect d = DialectFactory.resolve(null);
    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("t")).isEqualTo("\"t\"");
  }

  @Test
  void resolve_emptyDialect_returnsStandard() {
    assertThat(DialectFactory.resolve(driver(""))).isInstanceOf(StandardDialect.class);
  }

  @Test
  void resolve_nullDialect_returnsStandard() {
    assertThat(DialectFactory.resolve(driver(null))).isInstanceOf(StandardDialect.class);
  }
}
