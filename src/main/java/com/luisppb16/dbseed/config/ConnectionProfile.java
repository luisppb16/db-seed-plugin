/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * ****************************************************************************
 */

package com.luisppb16.dbseed.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class ConnectionProfile {
  private String name = "";
  private String url = "";
  private String user = "";
  private String schema = "";
  private int rowsPerTable = 10;
  private boolean deferred = false;
  private String softDeleteColumns = "";
  private boolean softDeleteUseSchemaDefault = true;
  private String softDeleteValue = "";
  private int numericScale = 2;
  private Map<String, Map<String, String>> stringRegexByTable = new LinkedHashMap<>();

  public static boolean isValidName(String profileName) {
    return profileName != null && !profileName.trim().isEmpty();
  }

  public boolean hasValidName() {
    return isValidName(name);
  }
}
