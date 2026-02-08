/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.config.DriverInfo;
import java.util.Locale;
import java.util.Objects;
import lombok.experimental.UtilityClass;

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
