/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;

@Builder(toBuilder = true)
public record Table(
    String name,
    List<Column> columns,
    List<String> primaryKey,
    List<ForeignKey> foreignKeys,
    List<String> checks) {

  public Table {
    Objects.requireNonNull(name, "Table name cannot be null.");
    Objects.requireNonNull(columns, "Column list cannot be null.");
    Objects.requireNonNull(primaryKey, "The list of PK columns cannot be null.");
    Objects.requireNonNull(foreignKeys, "The list of foreign keys cannot be null.");
    Objects.requireNonNull(checks, "The list of checks cannot be null.");

    columns = List.copyOf(columns);
    primaryKey = List.copyOf(primaryKey);
    foreignKeys = List.copyOf(foreignKeys);
    checks = List.copyOf(checks);
  }

  public Column column(String columnName) {
    return columns.stream()
        .filter(c -> c.name().equalsIgnoreCase(columnName))
        .findFirst()
        .orElse(null);
  }

  public Set<String> fkColumnNames() {
    return foreignKeys.stream()
        .flatMap(fk -> fk.columnMapping().keySet().stream())
        .collect(Collectors.toUnmodifiableSet());
  }
}
