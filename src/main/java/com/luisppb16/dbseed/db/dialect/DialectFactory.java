/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.config.DriverInfo;
import lombok.experimental.UtilityClass;

/**
 * Factory that resolves the appropriate SQL dialect from driver information.
 *
 * <p>Uses the {@code dialect} field from {@link DriverInfo} to load the corresponding {@code
 * .properties} file. Falls back to {@code standard.properties} when no dialect is specified.
 */
@UtilityClass
public class DialectFactory {

  public static DatabaseDialect resolve(DriverInfo driver) {
    if (driver == null || driver.dialect() == null || driver.dialect().isBlank()) {
      return new StandardDialect();
    }
    return new StandardDialect(driver.dialect() + ".properties");
  }
}
