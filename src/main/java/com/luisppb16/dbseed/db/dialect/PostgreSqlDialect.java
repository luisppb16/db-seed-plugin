/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

/**
 * PostgreSQL-specific SQL dialect implementation for the DBSeed plugin ecosystem.
 * <p>
 * This class provides PostgreSQL-specific SQL generation and formatting capabilities,
 * addressing the unique characteristics and requirements of PostgreSQL database systems.
 * It handles PostgreSQL's specific syntax requirements, data type peculiarities,
 * and behavioral differences compared to other database systems. The implementation
 * optimizes SQL generation for PostgreSQL's advanced features and ensures compatibility
 * with its specific constraint and transaction management patterns.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Implementing PostgreSQL-specific SQL formatting and quoting mechanisms</li>
 *   <li>Handling PostgreSQL's rich data type system and conversion patterns</li>
 *   <li>Managing PostgreSQL-specific transaction and constraint management</li>
 *   <li>Providing PostgreSQL-appropriate batch insertion optimizations</li>
 *   <li>Addressing PostgreSQL-specific boolean and literal value representations</li>
 *   <li>Optimizing for PostgreSQL's sequence and auto-increment handling</li>
 * </ul>
 * </p>
 * <p>
 * The implementation extends the AbstractDialect class and loads its configuration from
 * the postgresql.properties resource file. It provides PostgreSQL-specific implementations
 * for features like sequences, specific date/time functions, and advanced data types
 * that differentiate it from standard ANSI SQL implementations.
 * </p>
 */
public final class PostgreSqlDialect extends AbstractDialect {
  public PostgreSqlDialect() {
    super("postgresql.properties");
  }
}
