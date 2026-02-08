/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import java.util.Locale;

public class OracleDialect extends AbstractDialect {
  public OracleDialect() {
    super("oracle.properties");
  }

  @Override
  public String quote(String identifier) {
    // Oracle usually expects uppercase identifiers when quoted if they were created without quotes
    return super.quote(identifier.toUpperCase(Locale.ROOT));
  }

  @Override
  public void formatValue(Object value, StringBuilder sb) {
    switch (value) {
      case java.sql.Date d -> sb.append("TO_DATE('").append(d).append("', 'YYYY-MM-DD')");
      case java.sql.Timestamp t ->
          // Oracle timestamp format
          sb.append("TO_TIMESTAMP('").append(t).append("', 'YYYY-MM-DD HH24:MI:SS.FF')");
      default -> super.formatValue(value, sb);
    }
  }
}
