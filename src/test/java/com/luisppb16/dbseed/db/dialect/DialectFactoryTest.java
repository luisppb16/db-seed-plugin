/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.config.DriverInfo;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DialectFactoryTest {

  private static DriverInfo driver(final String dialect) {
    return DriverInfo.forDialect(dialect);
  }

  private static DriverInfo driverWithMeta(final String driverClass, final String urlTemplate) {
    return DriverInfo.withDriverMeta(driverClass, urlTemplate);
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

  static Stream<Arguments> driverClassDetectionCases() {
    return Stream.of(
        Arguments.of("com.mysql.cj.jdbc.Driver", "mysql"),
        Arguments.of("org.mariadb.jdbc.Driver", "mysql"),
        Arguments.of("org.postgresql.Driver", "postgresql"),
        Arguments.of("oracle.jdbc.OracleDriver", "oracle"),
        Arguments.of("org.sqlite.JDBC", "sqlite"),
        Arguments.of("org.h2.Driver", "h2"),
        Arguments.of("com.microsoft.sqlserver.jdbc.SQLServerDriver", "sqlserver"),
        Arguments.of("com.ibm.db2.jcc.DB2Driver", "db2"),
        Arguments.of("org.apache.derby.jdbc.EmbeddedDriver", "derby"),
        Arguments.of("org.apache.hive.jdbc.HiveDriver", "hive"),
        Arguments.of("org.hsqldb.jdbc.JDBCDriver", "hsqldb"),
        Arguments.of("com.simba.googlebigquery.jdbc.Driver", "bigquery"));
  }

  // ── Explicit dialect field takes priority ──

  static Stream<Arguments> urlDetectionCases() {
    return Stream.of(
        Arguments.of("jdbc:mysql://localhost:3306/db", "mysql"),
        Arguments.of("jdbc:mariadb://localhost:3306/db", "mysql"),
        Arguments.of("jdbc:postgresql://localhost:5432/db", "postgresql"),
        Arguments.of("jdbc:redshift://cluster.example.com:5439/db", "postgresql"),
        Arguments.of("jdbc:cockroach://localhost:26257/db", "postgresql"),
        Arguments.of("jdbc:sqlserver://localhost:1433;databaseName=db", "sqlserver"),
        Arguments.of("jdbc:oracle:thin:@localhost:1521:xe", "oracle"),
        Arguments.of("jdbc:sqlite:/tmp/test.db", "sqlite"),
        Arguments.of("jdbc:h2:mem:test", "h2"),
        Arguments.of("jdbc:db2://localhost:50000/db", "db2"),
        Arguments.of("jdbc:derby:memory:test;create=true", "derby"),
        Arguments.of("jdbc:hive2://localhost:10000/default", "hive"),
        Arguments.of("jdbc:hsqldb:mem:test", "hsqldb"),
        Arguments.of("jdbc:bigquery://host:443;ProjectId=p", "bigquery"));
  }

  // ── Fallback to standard ──

  @ParameterizedTest
  @MethodSource("dialectCases")
  void resolve_loadsCorrectProperties(
      final String dialect,
      final String expectedQuoteStart,
      final String expectedTrue,
      final String expectedFalse) {
    DatabaseDialect d = DialectFactory.resolve(driver(dialect));
    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("col")).startsWith(expectedQuoteStart);
    assertThat(d.formatBoolean(true)).isEqualTo(expectedTrue);
    assertThat(d.formatBoolean(false)).isEqualTo(expectedFalse);
  }

  @Test
  void resolve_explicitDialect_overridesAutoDetection() {
    final DriverInfo info =
        new DriverInfo(
            null,
            null,
            null,
            null,
            "com.mysql.cj.jdbc.Driver",
            null,
            false,
            false,
            false,
            false,
            "oracle");
    final DatabaseDialect d = DialectFactory.resolve(info);
    assertThat(d.quote("col")).isEqualTo("\"COL\"");
  }

  @Test
  void resolve_nullDriverInfo_returnsStandard() {
    final DatabaseDialect d = DialectFactory.resolve(null);
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
    final DriverInfo info = driverWithMeta("org.postgresql.Driver", null);

    final DatabaseDialect d = DialectFactory.resolve(info);

    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.formatBoolean(true)).isEqualTo("TRUE");
  }

  // ── Detection by driverClass ──

  @Test
  void resolve_detectsDialectFromUrlWhenDriverClassIsNull() {
    final DriverInfo info = driverWithMeta(null, "jdbc:sqlserver://localhost:1433");

    final DatabaseDialect d = DialectFactory.resolve(info);

    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("col")).startsWith("[");
  }

  @Test
  void resolve_unknownDriverAndUrl_fallsBackToStandard() {
    final DriverInfo info = driverWithMeta("com.acme.CustomDriver", "jdbc:custom://host/db");

    final DatabaseDialect d = DialectFactory.resolve(info);

    assertThat(d).isInstanceOf(StandardDialect.class);
    assertThat(d.quote("t")).isEqualTo("\"t\"");
  }

  // ── Detection by urlTemplate ──

  @ParameterizedTest
  @MethodSource("driverClassDetectionCases")
  void resolve_detectsDialectFromDriverClass(final String driverClass, final String expected) {
    final DatabaseDialect detected = DialectFactory.resolve(driverWithMeta(driverClass, null));

    assertThat(detected).isSameAs(DialectFactory.resolve(driver(expected)));
  }

  @ParameterizedTest
  @MethodSource("urlDetectionCases")
  void resolve_detectsDialectFromUrlTemplate(final String urlTemplate, final String expected) {
    final DatabaseDialect detected = DialectFactory.resolve(driverWithMeta(null, urlTemplate));

    assertThat(detected).isSameAs(DialectFactory.resolve(driver(expected)));
  }

  @Test
  void resolve_mysqlDriverClass_loadsMysqlProperties() {
    final DatabaseDialect d =
        DialectFactory.resolve(driverWithMeta("com.mysql.cj.jdbc.Driver", null));

    assertThat(d.quote("col")).isEqualTo("`col`");
    assertThat(d.formatBoolean(true)).isEqualTo("1");
  }

  @Test
  void resolve_redshiftUrl_loadsPostgresqlProperties() {
    final DatabaseDialect d =
        DialectFactory.resolve(driverWithMeta(null, "jdbc:redshift://cluster.example.com:5439/db"));

    assertThat(d.getProperty("checkConstraint.query", "")).contains("pg_constraint");
  }

  // ── Caching ──

  @Test
  void resolve_sameExplicitDialect_returnsCachedInstance() {
    final DatabaseDialect first = DialectFactory.resolve(driver("mysql"));
    final DatabaseDialect second = DialectFactory.resolve(driver("mysql"));

    assertThat(second).isSameAs(first);
  }

  @Test
  void resolve_equalDriverMeta_returnsCachedInstance() {
    final DatabaseDialect first =
        DialectFactory.resolve(driverWithMeta("org.postgresql.Driver", "jdbc:postgresql://h/db"));
    final DatabaseDialect second =
        DialectFactory.resolve(driverWithMeta("org.postgresql.Driver", "jdbc:postgresql://h/db"));

    assertThat(second).isSameAs(first);
  }

  @Test
  void resolve_detectedDialect_sharesCacheWithExplicitDialect() {
    final DatabaseDialect detected =
        DialectFactory.resolve(
            driverWithMeta("com.microsoft.sqlserver.jdbc.SQLServerDriver", null));

    assertThat(detected).isSameAs(DialectFactory.resolve(driver("sqlserver")));
  }

  @Test
  void resolve_nullDriver_returnsCachedStandardInstance() {
    assertThat(DialectFactory.resolve(null)).isSameAs(DialectFactory.resolve(null));
  }

  @Test
  void resolve_noDialectAndNoMetadata_sharesStandardInstance() {
    assertThat(DialectFactory.resolve(driverWithMeta(null, null)))
        .isSameAs(DialectFactory.resolve(null));
  }
}
