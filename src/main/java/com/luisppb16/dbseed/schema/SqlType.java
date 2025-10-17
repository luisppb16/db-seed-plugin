/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.schema;

public enum SqlType {
  INT,
  VARCHAR,
  TIMESTAMP,
  BOOLEAN;

  public String toSql() {
    return switch (this) {
      case INT -> "INT";
      case VARCHAR -> "VARCHAR(255)";
      case TIMESTAMP -> "TIMESTAMP";
      case BOOLEAN -> "BOOLEAN";
    };
  }

  @Override
  public String toString() {
    return toSql();
  }
}
