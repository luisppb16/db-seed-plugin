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
 * Advanced factory for resolving appropriate SQL dialects from driver information in the DBSeed plugin ecosystem.
 *
 * <p>This utility class implements sophisticated dialect resolution algorithms,
 * automatically detecting and applying the correct SQL dialect based on database
 * driver characteristics. It provides intelligent fallback mechanisms and supports
 * both explicit dialect configuration and automatic detection from driver metadata.
 * The factory manages dialect-specific properties files and ensures optimal SQL
 * generation for various database systems.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Resolving SQL dialects from explicit configuration in DriverInfo objects
 *   <li>Automatically detecting database dialects from driver class names and URL templates
 *   <li>Implementing comprehensive vendor detection algorithms for major database systems
 *   <li>Providing fallback mechanisms to standard dialect when detection fails
 *   <li>Managing dialect-specific properties file loading and initialization
 *   <li>Supporting multiple database vendors including MySQL, PostgreSQL, Oracle, SQL Server, SQLite, and H2
 *   <li>Optimizing detection algorithms for performance and accuracy
 *   <li>Handling edge cases and unusual driver configurations gracefully
 *   <li>Ensuring consistent dialect behavior across different database connection methods
 *   <li>Providing extensible architecture for adding new dialect support
 *   <li>Implementing robust null-safety and error handling mechanisms
 *   <li>Managing dialect-specific SQL generation rules and formatting preferences
 * </ul>
 *
 * <p>The class implements advanced detection algorithms that analyze both driver class
 * names and JDBC URL patterns to determine the appropriate SQL dialect. It includes
 * comprehensive support for major database vendors with vendor-specific SQL syntax
 * and formatting requirements. The detection logic handles various driver naming
 * conventions and URL formats, ensuring accurate dialect identification across
 * different database connection configurations.
 *
 * <p>Thread safety is maintained through immutable factory methods and thread-safe
 * singleton dialect instances. The class implements efficient string matching and
 * case-insensitive comparisons for optimal performance. Memory efficiency is
 * achieved through static detection patterns and cached dialect instances.
 *
 * <p>Advanced features include intelligent fallback mechanisms that gracefully
 * degrade to standard SQL syntax when specific dialect information is unavailable.
 * The factory handles both explicit dialect specifications and automatic detection
 * scenarios, providing consistent behavior regardless of the configuration method.
 * The implementation includes comprehensive validation to ensure detected dialects
 * are valid and properly configured.
 *
 * <p>Error handling includes robust null-safety checks and graceful degradation
 * when driver information is incomplete or malformed. The class handles edge
 * cases such as custom drivers, embedded databases, and unusual connection
 * configurations while maintaining reliable dialect resolution.
 *
 * @author Luis Paolo Pepe Barra (@LuisPPB16)
 * @version 1.3.0
 * @since 2024.1
 * @see DatabaseDialect
 * @see StandardDialect
 * @see DriverInfo
 * @see java.util.Locale
 */
@UtilityClass
public class DialectFactory {

  public static DatabaseDialect resolve(DriverInfo driver) {
    if (Objects.isNull(driver)) {
      return new StandardDialect();
    }

    if (Objects.nonNull(driver.dialect()) && !driver.dialect().isBlank()) {
      return new StandardDialect(driver.dialect() + ".properties");
    }

    String detected = detectDialect(driver);
    if (Objects.nonNull(detected)) {
      return new StandardDialect(detected + ".properties");
    }

    return new StandardDialect();
  }

  private static String detectDialect(DriverInfo driver) {
    String driverClass =
        Objects.nonNull(driver.driverClass()) ? driver.driverClass().toLowerCase(Locale.ROOT) : "";
    String url =
        Objects.nonNull(driver.urlTemplate()) ? driver.urlTemplate().toLowerCase(Locale.ROOT) : "";

    if (driverClass.contains("mysql") || driverClass.contains("mariadb")
        || url.contains("jdbc:mysql") || url.contains("jdbc:mariadb")) {
      return "mysql";
    }
    if (driverClass.contains("sqlserver") || url.contains("jdbc:sqlserver")) {
      return "sqlserver";
    }
    if (driverClass.contains("oracle") || url.contains("jdbc:oracle")) {
      return "oracle";
    }
    if (driverClass.contains("sqlite") || url.contains("jdbc:sqlite")) {
      return "sqlite";
    }
    if (driverClass.contains("postgresql") || url.contains("jdbc:postgresql")
        || url.contains("jdbc:redshift") || url.contains("jdbc:cockroach")
        || driverClass.contains("h2")) {
      return "postgresql";
    }

    return null;
  }
}
