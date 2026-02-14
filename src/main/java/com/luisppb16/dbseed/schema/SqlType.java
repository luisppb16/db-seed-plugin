/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.schema;

/**
 * Represents a simplified set of SQL data types for use in the schema DSL.
 * This enum provides a clean, type-safe way to specify column types when
 * defining a database schema programmatically.
 */
public enum SqlType {
  INT,
  VARCHAR,
  TEXT,
  DECIMAL,
  TIMESTAMP,
  BOOLEAN;

  public String toSql() {
    return switch (this) {
      case INT -> "INT";
      case VARCHAR -> "VARCHAR(255)";
      case TEXT -> "TEXT";
      case DECIMAL -> "DECIMAL(10, 2)";
      case TIMESTAMP -> "TIMESTAMP";
      case BOOLEAN -> "BOOLEAN";
    };
  }

  @Override
  public String toString() {
    return toSql();
  }
}
