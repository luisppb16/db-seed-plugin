/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;

/**
 * Immutable representation of a database table structure in the DBSeed plugin ecosystem.
 *
 * <p>This record class represents the complete structural definition of a database table, including
 * its columns, primary key constraints, foreign key relationships, check constraints, and unique
 * key constraints. It serves as a central data model for schema introspection and data generation
 * operations, providing a normalized view of table metadata that can be used consistently across
 * different database systems and generation phases.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Representing the complete structural definition of a database table
 *   <li>Providing efficient lookup methods for table columns by name
 *   <li>Managing primary key, foreign key, and constraint definitions
 *   <li>Offering convenient access to foreign key column names
 *   <li>Ensuring immutability and thread safety through defensive copying
 *   <li>Validating structural integrity of table definitions
 * </ul>
 *
 * <p>The implementation uses immutable collections and follows defensive programming practices to
 * ensure data integrity. The class provides convenient accessor methods for common table operations
 * such as column lookup and foreign key analysis. It maintains strict validation of required fields
 * and implements proper equals/hashCode semantics for use in collections.
 *
 * @param name The name of the table
 * @param columns The list of columns in the table
 * @param primaryKey The list of primary key column names
 * @param foreignKeys The list of foreign key relationships
 * @param checks The list of check constraints
 * @param uniqueKeys The list of unique key constraints
 */
@Builder(toBuilder = true)
public record Table(
    String name,
    List<Column> columns,
    List<String> primaryKey,
    List<ForeignKey> foreignKeys,
    List<String> checks,
    List<List<String>> uniqueKeys) {

  public Table {
    Objects.requireNonNull(name, "Table name cannot be null.");
    Objects.requireNonNull(columns, "Column list cannot be null.");
    Objects.requireNonNull(primaryKey, "The list of PK columns cannot be null.");
    Objects.requireNonNull(foreignKeys, "The list of foreign keys cannot be null.");
    Objects.requireNonNull(checks, "The list of checks cannot be null.");
    Objects.requireNonNull(uniqueKeys, "The list of unique keys cannot be null.");

    columns = List.copyOf(columns);
    primaryKey = List.copyOf(primaryKey);
    foreignKeys = List.copyOf(foreignKeys);
    checks = List.copyOf(checks);
    uniqueKeys = uniqueKeys.stream().map(List::copyOf).toList();
  }

  public Column column(final String columnName) {
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
