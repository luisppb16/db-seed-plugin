/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Builder;

/**
 * Immutable representation of a foreign key relationship in the DBSeed plugin ecosystem.
 * <p>
 * This record class represents a foreign key constraint between two database tables,
 * capturing the relationship between referencing (child) columns and referenced (parent)
 * primary key columns. It provides essential metadata for foreign key resolution during
 * data generation, including the mapping between columns, the referenced table, and
 * special flags for unique foreign key constraints. The class plays a critical role
 * in maintaining referential integrity during the seeding process.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Representing the complete definition of a foreign key relationship</li>
 *   <li>Mapping referencing columns to referenced primary key columns</li>
 *   <li>Identifying the referenced primary key table</li>
 *   <li>Indicating whether the foreign key enforces uniqueness</li>
 *   <li>Generating default names when none are provided</li>
 *   <li>Ensuring immutability and thread safety for concurrent access</li>
 * </ul>
 * </p>
 * <p>
 * The implementation includes automatic name generation when no explicit name is provided,
 * using a consistent naming convention based on the referenced table and columns involved.
 * It maintains strict validation of required fields and implements defensive copying to
 * ensure data integrity. The class is designed for efficient use in foreign key resolution
 * algorithms during the data generation process.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 * @param name The name of the foreign key constraint
 * @param pkTable The name of the referenced primary key table
 * @param columnMapping A mapping of referencing columns (in the child table) to referenced columns (in the parent table)
 * @param uniqueOnFk Flag indicating whether this foreign key enforces uniqueness (1:1 relationship)
 */
@Builder(toBuilder = true)
public record ForeignKey(
    String name, String pkTable, Map<String, String> columnMapping, boolean uniqueOnFk) {

  public ForeignKey {
    Objects.requireNonNull(pkTable, "The primary table name (pkTable) cannot be null.");
    Objects.requireNonNull(columnMapping, "The column mapping cannot be null.");

    // Immutable copy of the mapping.
    columnMapping = Map.copyOf(columnMapping);

    // If the name was not provided, one is generated based on the PK and child columns.
    if (name == null || name.isBlank()) {
      final String cols =
          columnMapping.keySet().stream()
              .sorted(Comparator.naturalOrder())
              .collect(Collectors.joining("__"));
      name = "fk_%s%s".formatted(pkTable, cols.isEmpty() ? "" : "_" + cols);
    }
  }
}
