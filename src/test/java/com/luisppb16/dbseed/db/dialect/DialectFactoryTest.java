/*
 * *****************************************************************************
 *  * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 *  * All rights reserved.
 *  *****************************************************************************
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

  private static DriverInfo driverWithMeta(String driverClass, String urlTemplate) {
    return DriverInfo.builder().driverClass(driverClass).urlTemplate(urlTemplate).build();
  }

  // ── Auto-detection from driverClass / urlTemplate ──

  static Stream<Arguments> dialectCases() {
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
  void resolve_loadsCorrectProperties(
      String dialect, String expectedQuoteStart, String expectedTrue, String expectedFalse) {
    DatabaseDialect d = DialectFactory.resolve(driver(dialect));
    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("col")).startsWith(expectedQuoteStart);
    assertThat(d.formatBoolean(true)).isEqualTo(expectedTrue);
    assertThat(d.formatBoolean(false)).isEqualTo(expectedFalse);
  }

  // ── Explicit dialect field takes priority ──

  @Test
  void resolve_explicitDialect_overridesAutoDetection() {
    DriverInfo info =
        DriverInfo.builder().driverClass("com.mysql.cj.jdbc.Driver").dialect("oracle").build();
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

  @Test
  void resolve_detectsDialectFromDriverClassWhenDialectMissing() {
    DriverInfo info = driverWithMeta("org.postgresql.Driver", null);

    DatabaseDialect d = DialectFactory.resolve(info);

    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.formatBoolean(true)).isEqualTo("TRUE");
  }

  @Test
  void resolve_detectsDialectFromUrlWhenDriverClassIsNull() {
    DriverInfo info = driverWithMeta(null, "jdbc:sqlserver://localhost:1433");

    DatabaseDialect d = DialectFactory.resolve(info);

    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("col")).startsWith("[");
  }

  @Test
  void resolve_unknownDriverAndUrl_fallsBackToStandard() {
    DriverInfo info = driverWithMeta("com.acme.CustomDriver", "jdbc:custom://host/db");

    DatabaseDialect d = DialectFactory.resolve(info);

    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("t")).isEqualTo("\"t\"");
  }
}
