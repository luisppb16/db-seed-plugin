/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

/**
 * Microsoft SQL Server-specific SQL dialect implementation for the DBSeed plugin ecosystem.
 * <p>
 * This class provides SQL Server-specific SQL generation and formatting capabilities,
 * addressing the unique characteristics and requirements of Microsoft SQL Server.
 * It handles SQL Server's specific syntax requirements, data type peculiarities,
 * and behavioral differences compared to other database systems. The implementation
 * optimizes SQL generation for SQL Server's features and ensures compatibility with
 * its specific constraint and transaction management patterns.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Implementing SQL Server-specific SQL formatting and quoting mechanisms</li>
 *   <li>Handling SQL Server's data type system and conversion patterns</li>
 *   <li>Managing SQL Server-specific transaction and constraint management</li>
 *   <li>Providing SQL Server-appropriate batch insertion optimizations</li>
 *   <li>Addressing SQL Server-specific boolean and literal value representations</li>
 *   <li>Optimizing for SQL Server's identity column and sequence handling</li>
 * </ul>
 * </p>
 * <p>
 * The implementation extends the AbstractDialect class and loads its configuration from
 * the sqlserver.properties resource file. It provides SQL Server-specific implementations
 * for features like identity columns, specific date/time functions, and T-SQL extensions
 * that differentiate it from standard ANSI SQL implementations.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 */
public class SqlServerDialect extends AbstractDialect {
  public SqlServerDialect() {
    super("sqlserver.properties");
  }
}
