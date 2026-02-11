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

  private static DriverInfo driver(String driverClass, String urlTemplate) {
    return DriverInfo.builder().driverClass(driverClass).urlTemplate(urlTemplate).build();
  }

  static Stream<Arguments> dialectCases() {
    return Stream.of(
        // MySQL
        Arguments.of("com.mysql.cj.jdbc.Driver", "", MySQLDialect.class),
        Arguments.of("", "jdbc:mysql://host/db", MySQLDialect.class),
        // MariaDB
        Arguments.of("org.mariadb.jdbc.Driver", "", MySQLDialect.class),
        Arguments.of("", "jdbc:mariadb://host/db", MySQLDialect.class),
        // SQL Server
        Arguments.of("com.microsoft.sqlserver.jdbc.SQLServerDriver", "", SqlServerDialect.class),
        Arguments.of("", "jdbc:sqlserver://host", SqlServerDialect.class),
        // Oracle
        Arguments.of("oracle.jdbc.OracleDriver", "", OracleDialect.class),
        Arguments.of("", "jdbc:oracle:thin:@host", OracleDialect.class),
        // SQLite
        Arguments.of("org.sqlite.JDBC", "", SqliteDialect.class),
        Arguments.of("", "jdbc:sqlite:file.db", SqliteDialect.class),
        // PostgreSQL
        Arguments.of("org.postgresql.Driver", "", PostgreSqlDialect.class),
        Arguments.of("", "jdbc:postgresql://host/db", PostgreSqlDialect.class),
        // Redshift / CockroachDB -> PostgreSQL
        Arguments.of("", "jdbc:redshift://host/db", PostgreSqlDialect.class),
        Arguments.of("", "jdbc:cockroach://host/db", PostgreSqlDialect.class),
        // H2 -> PostgreSQL
        Arguments.of("org.h2.Driver", "", PostgreSqlDialect.class),
        // DB2 -> Standard
        Arguments.of("com.ibm.db2.jcc.DB2Driver", "", StandardDialect.class));
  }

  @ParameterizedTest
  @MethodSource("dialectCases")
  void resolve_returnsCorrectDialect(String driverClass, String url, Class<?> expected) {
    assertThat(DialectFactory.resolve(driver(driverClass, url))).isInstanceOf(expected);
  }

  @Test
  void resolve_nullDriverInfo_returnsStandard() {
    assertThat(DialectFactory.resolve(null)).isInstanceOf(StandardDialect.class);
  }

  @Test
  void resolve_emptyDriverInfo_returnsStandard() {
    assertThat(DialectFactory.resolve(driver("", ""))).isInstanceOf(StandardDialect.class);
  }
}
