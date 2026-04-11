/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import java.util.Map;

/**
 * Immutable representation of a database row in the DBSeed plugin ecosystem.
 *
 * <p>This record class represents a single row of data in a database table, containing the column
 * values as a map from column names to their corresponding values. It serves as the fundamental
 * unit of data during the seeding process, encapsulating the generated or existing data for a
 * specific table row. The class provides a simple, immutable structure for storing and accessing
 * row data throughout the data generation and SQL generation phases.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Storing column values for a database row as key-value pairs
 *   <li>Providing immutable access to row data for thread-safe operations
 *   <li>Serving as a data container during data generation and SQL output
 *   <li>Ensuring type safety through generic value storage
 * </ul>
 *
 * <p>The implementation uses a Map to store column values, allowing for flexible data types and
 * efficient lookups by column name. The record ensures immutability, making it safe for use in
 * concurrent environments and preventing accidental modification of row data.
 *
 * @param values A mapping of column names to their corresponding values in the row
 */
public record Row(Map<String, Object> values) {}
