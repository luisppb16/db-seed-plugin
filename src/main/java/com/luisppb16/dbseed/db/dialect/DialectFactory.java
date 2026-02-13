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
 * Factory that resolves the appropriate SQL dialect from driver information.
 *
 * <p>Uses the {@code dialect} field from {@link DriverInfo} to load the corresponding {@code
 * .properties} file. When no explicit dialect is set, auto-detects from the driver class name or
 * URL template. Falls back to {@code standard.properties} when no dialect can be determined.
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
