/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.config.DriverInfo;
import java.util.Locale;
import java.util.Objects;
import lombok.experimental.UtilityClass;

/**
 * Factory for creating database-specific SQL dialect implementations in the DBSeed plugin ecosystem.
 * <p>
 * This utility class provides a centralized mechanism for resolving and instantiating the
 * appropriate SQL dialect implementation based on driver information. It analyzes driver
 * class names and connection URL templates to determine the most suitable dialect for
 * a given database system. The factory ensures that the correct SQL generation patterns
 * are used for each supported database vendor, optimizing for their specific syntax
 * and behavioral requirements.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Identifying database vendors from driver class names and URL patterns</li>
 *   <li>Instantiating appropriate dialect implementations for each database system</li>
 *   <li>Providing fallback to standard dialect for unrecognized database systems</li>
 *   <li>Centralizing dialect selection logic for consistent behavior across the application</li>
 *   <li>Supporting multiple database vendors including MySQL, PostgreSQL, Oracle, SQL Server, and SQLite</li>
 *   <li>Handling edge cases and providing sensible defaults for unknown databases</li>
 * </ul>
 * </p>
 * <p>
 * The implementation uses string pattern matching to identify database vendors from
 * driver information and follows a priority-based matching approach. It handles special
 * cases where certain databases share similarities with others (e.g., H2 with PostgreSQL)
 * and provides appropriate fallback mechanisms for unrecognized database systems.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 */
@UtilityClass
public class DialectFactory {

  public static DatabaseDialect resolve(DriverInfo driver) {
    if (driver == null) {
      return new StandardDialect();
    }

    final String cls =
        Objects.requireNonNullElse(driver.driverClass(), "").toLowerCase(Locale.ROOT);
    final String url =
        Objects.requireNonNullElse(driver.urlTemplate(), "").toLowerCase(Locale.ROOT);

    if (cls.contains("mysql")
        || cls.contains("mariadb")
        || url.contains("mysql")
        || url.contains("mariadb")) {
      return new MySQLDialect();
    }
    if (cls.contains("sqlserver") || url.contains("sqlserver") || url.contains("jtds:sqlserver")) {
      return new SqlServerDialect();
    }
    if (cls.contains("oracle") || url.contains("oracle")) {
      return new OracleDialect();
    }
    if (cls.contains("sqlite") || url.contains("sqlite")) {
      return new SqliteDialect();
    }
    if (cls.contains("postgresql")
        || url.contains("postgresql")
        || url.contains("redshift")
        || url.contains("cockroach")) {
      return new PostgreSqlDialect();
    }
    if (cls.contains("h2") || url.contains("h2")) {
      return new PostgreSqlDialect(); // H2 is very close to PostgreSQL/Standard
    }
    if (cls.contains("db2") || url.contains("db2")) {
      return new StandardDialect();
    }

    return new StandardDialect();
  }
}
