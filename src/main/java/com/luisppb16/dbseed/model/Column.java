/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.model;

import com.luisppb16.dbseed.schema.SchemaDsl;
import com.luisppb16.dbseed.util.SqlStandardizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;

@Builder(toBuilder = true)
public record Column(
    String name,
    int type,
    boolean nullable,
    boolean primaryKey,
    boolean isGuid,
    int length,
    int minValue,
    int maxValue,
    Set<String> allowedValues,
    List<String> checkConstraints) {

  public Column {
    Objects.requireNonNull(name, "Column name cannot be null.");
  }

  public SchemaDsl.Column toDsl() {
    List<SchemaDsl.CheckConstraint> checks =
        checkConstraints.stream()
            .map(SqlStandardizer::parseCheckConstraint)
            .collect(Collectors.toList());
    return new SchemaDsl.Column(name, toSqlType(type), primaryKey, null, checks);
  }

  private SchemaDsl.SqlType toSqlType(int sqlType) {
    return switch (sqlType) {
      case java.sql.Types.INTEGER -> SchemaDsl.SqlType.INT;
      case java.sql.Types.VARCHAR -> SchemaDsl.SqlType.VARCHAR;
      case java.sql.Types.TIMESTAMP -> SchemaDsl.SqlType.TIMESTAMP;
      case java.sql.Types.BOOLEAN -> SchemaDsl.SqlType.BOOLEAN;
      default -> throw new IllegalArgumentException("Unsupported SQL type: " + sqlType);
    };
  }

  public boolean hasAllowedValues() {
    return allowedValues != null && !allowedValues.isEmpty();
  }
}
