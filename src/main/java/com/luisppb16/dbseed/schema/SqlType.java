/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.schema;

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
