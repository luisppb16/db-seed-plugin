/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

/**
 * Standard SQL dialect implementation for the DBSeed plugin ecosystem.
 * <p>
 * This class provides a baseline SQL dialect implementation that serves as a fallback for
 * database systems that don't have specific dialect implementations. It implements common
 * SQL patterns and behaviors that are widely supported across different database management
 * systems. The standard dialect provides basic functionality for SQL generation while
 * maintaining compatibility with ANSI SQL standards and common database implementations.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Providing baseline SQL formatting and generation capabilities</li>
 *   <li>Implementing common SQL patterns for unsupported database systems</li>
 *   <li>Offering standard transaction and constraint management patterns</li>
 *   <li>Serving as a fallback dialect when specific database support is unavailable</li>
 *   <li>Ensuring basic SQL generation functionality across all supported operations</li>
 * </ul>
 * </p>
 * <p>
 * The implementation extends the AbstractDialect class and loads its configuration from
 * the standard.properties resource file. It provides sensible defaults for SQL formatting
 * that work across multiple database systems, though specific dialects may offer better
 * optimization for particular database vendors.
 * </p>
 */
public class StandardDialect extends AbstractDialect {
  public StandardDialect() {
    super("standard.properties");
  }
}
