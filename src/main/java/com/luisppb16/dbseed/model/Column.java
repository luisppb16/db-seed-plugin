/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.model;

import java.sql.Types;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable representation of a database column definition in the DBSeed plugin ecosystem.
 *
 * <p>This record class captures the complete metadata for a database column, including its name,
 * data type, nullability, primary key status, length, scale, value constraints, and allowed values.
 * It serves as a fundamental building block for schema representation and data generation,
 * providing essential information for value generation algorithms and constraint validation during
 * the seeding process.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Representing the complete structural definition of a database column
 *   <li>Storing JDBC type information for proper value generation
 *   <li>Managing column constraints (nullability, primary key, UUID flag)
 *   <li>Tracking physical attributes (length, scale, min/max values)
 *   <li>Defining allowed value sets for constrained columns
 *   <li>Providing convenience methods for constraint checking
 *   <li>Ensuring immutability and thread safety for concurrent access
 * </ul>
 *
 * <p>The implementation includes validation for required fields and defensive copying for mutable
 * collections. The class provides helper methods to check constraint conditions and is designed for
 * efficient use in data generation algorithms that need to respect column-specific constraints and
 * properties.
 *
 * @param name The name of the column
 * @param jdbcType The JDBC type identifier for the column
 * @param typeName The native database type name for the column
 * @param nullable Whether the column allows null values
 * @param primaryKey Whether the column is part of the primary key
 * @param uuid Whether the column should be treated as a UUID
 * @param length The maximum length of the column (for character/decimal types)
 * @param scale The decimal scale of the column (for numeric types)
 * @param minValue The minimum allowed value for the column, or null if not constrained
 * @param maxValue The maximum allowed value for the column, or null if not constrained
 * @param allowedValues A set of specific allowed values for the column
 */
public record Column(
    String name,
    int jdbcType,
    String typeName,
    boolean nullable,
    boolean primaryKey,
    boolean uuid,
    int length,
    int scale,
    Integer minValue,
    Integer maxValue,
    Set<String> allowedValues) {

  public Column {
    Objects.requireNonNull(name, "Column name cannot be null.");
    allowedValues = Objects.nonNull(allowedValues) ? Set.copyOf(allowedValues) : Set.of();
  }

  /** Creates a copy of this column with the uuid flag set to the given value. */
  public Column withUuid(final boolean uuid) {
    return new Column(name, jdbcType, typeName, nullable, primaryKey, uuid, length, scale,
        minValue, maxValue, allowedValues);
  }

  /** Checks if the column's JDBC type represents a string-like or array type suitable for text generation. */
  public static boolean isStringJdbcType(final int jdbcType) {
    return jdbcType == Types.VARCHAR
        || jdbcType == Types.CHAR
        || jdbcType == Types.NCHAR
        || jdbcType == Types.NVARCHAR
        || jdbcType == Types.LONGVARCHAR
        || jdbcType == Types.LONGNVARCHAR
        || jdbcType == Types.CLOB
        || jdbcType == Types.NCLOB
        || jdbcType == Types.ARRAY;
  }

  /** Checks if this column is a string-like type (JDBC string type or array type by name). */
  public boolean isStringType() {
    return isStringJdbcType(jdbcType)
        || (Objects.nonNull(typeName) && typeName.toLowerCase(Locale.ROOT).endsWith("[]"));
  }

  public boolean hasAllowedValues() {
    return !allowedValues.isEmpty();
  }
}