/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.db.Row;
import java.util.List;

/**
 * Contract defining database-specific SQL dialect implementations for the DBSeed plugin.
 * <p>
 * Each dialect provides database-appropriate SQL statement generation, taking into account
 * the unique syntax and requirements of different database management systems.
 * </p>
 */
public interface DatabaseDialect {

  /** Quotes an identifier (table or column name). */
  String quote(String identifier);

  /** Formats a boolean value for this dialect. */
  String formatBoolean(boolean b);

  /** Returns the SQL statement to begin a transaction. */
  String beginTransaction();

  /** Returns the SQL statement to commit a transaction. */
  String commitTransaction();

  /** Returns the SQL statement to disable foreign key constraints. */
  String disableConstraints();

  /** Returns the SQL statement to enable foreign key constraints. */
  String enableConstraints();

  /** Formats a value for inclusion in an SQL statement. */
  void formatValue(Object value, StringBuilder sb);

  /** Appends a batch of INSERT statements to the StringBuilder. */
  void appendBatch(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      List<String> columnOrder);
}
