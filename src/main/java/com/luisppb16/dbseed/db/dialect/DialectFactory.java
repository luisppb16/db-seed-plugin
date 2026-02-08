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
    return new AiDatabaseDialect(driver);
  }
}
