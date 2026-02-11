/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.db.Row;

import java.util.List;

/**
 * SQLite-specific SQL dialect implementation for the DBSeed plugin ecosystem.
 * <p>
 * This class provides SQLite-specific SQL generation and formatting capabilities,
 * addressing the unique characteristics and limitations of the SQLite database
 * management system. It handles SQLite's specific syntax requirements, parameter
 * limitations, and behavioral differences compared to other database systems.
 * The implementation optimizes batch operations for SQLite's constraints and
 * ensures compatibility with SQLite's type affinity system.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Implementing SQLite-specific SQL formatting and quoting mechanisms</li>
 *   <li>Managing SQLite's parameter limit constraints through optimized batching</li>
 *   <li>Handling SQLite's type affinity and data type peculiarities</li>
 *   <li>Providing SQLite-appropriate transaction and constraint management</li>
 *   <li>Optimizing batch insertion operations for SQLite's performance characteristics</li>
 *   <li>Addressing SQLite-specific boolean and literal value representations</li>
 * </ul>
 * </p>
 * <p>
 * The implementation takes into account SQLite's limitations on the number of
 * parameters in a single statement, implementing smaller batch sizes to avoid
 * exceeding these limits. It also handles SQLite's flexible type system and
 * ensures proper value formatting for reliable data insertion.
 * </p>
 */
public final class SqliteDialect extends AbstractDialect {
  private static final int SQLITE_BATCH_SIZE = 100;

  public SqliteDialect() {
    super("sqlite.properties");
  }

  @Override
  public void appendBatch(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      List<String> columnOrder) {
    
    String header = props.getProperty("batchHeader", "INSERT INTO ${table} (${columns}) VALUES\n");
    String prefix = props.getProperty("batchRowPrefix", "(");
    String suffix = props.getProperty("batchRowSuffix", ")");
    String separator = props.getProperty("batchRowSeparator", ",\n");
    String footer = props.getProperty("batchFooter", ";\n");

    for (int i = 0; i < rows.size(); i += SQLITE_BATCH_SIZE) {
      List<Row> batch = rows.subList(i, Math.min(i + SQLITE_BATCH_SIZE, rows.size()));
      
      sb.append(header.replace("${table}", tableName).replace("${columns}", columnList));

      for (int j = 0; j < batch.size(); j++) {
        sb.append(prefix.replace("${table}", tableName).replace("${columns}", columnList));
        appendRowValues(sb, batch.get(j), columnOrder);
        sb.append(suffix);
        if (j < batch.size() - 1) {
          sb.append(separator);
        }
      }
      sb.append(footer);
    }
  }
}
