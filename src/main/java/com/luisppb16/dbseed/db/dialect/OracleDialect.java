/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import java.util.Locale;

/**
 * Oracle-specific SQL dialect implementation for the DBSeed plugin ecosystem.
 * <p>
 * This class provides Oracle-specific SQL generation and formatting capabilities,
 * addressing the unique characteristics and requirements of Oracle database systems.
 * It handles Oracle's specific syntax requirements, identifier casing conventions,
 * date/time formatting, and behavioral differences compared to other database systems.
 * The implementation optimizes SQL generation for Oracle's features and ensures
 * compatibility with its specific constraint and transaction management patterns.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Implementing Oracle-specific identifier quoting and casing conventions</li>
 *   <li>Handling Oracle's date and timestamp formatting requirements</li>
 *   <li>Managing Oracle-specific transaction and constraint management</li>
 *   <li>Providing Oracle-appropriate batch insertion optimizations</li>
 *   <li>Addressing Oracle-specific data type conversions and literal representations</li>
 *   <li>Optimizing for Oracle's sequence and auto-increment handling</li>
 * </ul>
 * </p>
 * <p>
 * The implementation extends the AbstractDialect class and loads its configuration from
 * the oracle.properties resource file. It overrides specific formatting methods to
 * accommodate Oracle's unique requirements, such as uppercase identifier handling
 * and TO_DATE/TO_TIMESTAMP function usage for temporal values.
 * </p>
 */
public final class OracleDialect extends AbstractDialect {
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
