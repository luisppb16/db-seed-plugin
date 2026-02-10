/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.db.Row;

import java.util.List;

public class SqliteDialect extends AbstractDialect {
  private static final int SQLITE_BATCH_SIZE = 100; // Smaller batch size for SQLite

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
    
    // Use smaller batch size for SQLite to avoid parameter limits
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
