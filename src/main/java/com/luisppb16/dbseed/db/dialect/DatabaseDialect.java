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
 * This sealed interface establishes the contract for database-specific SQL dialect implementations
 * used throughout the DBSeed plugin. It defines the essential operations required to generate
 * database-appropriate SQL statements, taking into account the unique characteristics and
 * requirements of different database management systems. The interface ensures consistent
 * behavior across different database platforms while allowing for specific customizations
 * required by individual database systems.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Providing database-specific identifier quoting mechanisms</li>
 *   <li>Implementing appropriate value formatting for different data types</li>
 *   <li>Defining transaction management patterns for different databases</li>
 *   <li>Handling constraint management for referential integrity</li>
 *   <li>Generating batch insertion statements with database-specific syntax</li>
 *   <li>Managing boolean representation variations across database systems</li>
 * </ul>
 * </p>
 * <p>
 * The interface uses the sealed interface pattern to restrict implementation to known
 * dialect classes, ensuring type safety and maintainability. Each implementing class
 * provides database-specific behavior for SQL generation, taking into account the
 * unique syntax, reserved words, and operational characteristics of the target database.
 * </p>
 */
public sealed interface DatabaseDialect permits 
    AbstractDialect, 
    SqlServerDialect, 
    MySQLDialect, 
    StandardDialect, 
    SqliteDialect, 
    PostgreSqlDialect, 
    OracleDialect {

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
