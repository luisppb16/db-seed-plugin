/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Representation of a database row in the DBSeed plugin ecosystem.
 *
 * <p>This record class represents a single row of data in a database table, containing the column
 * values as a map from column names to their corresponding values. It serves as the fundamental
 * unit of data during the seeding process, encapsulating the generated or existing data for a
 * specific table row. The class provides a simple structure for storing and accessing row data
 * throughout the data generation and SQL generation phases.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Storing column values for a database row as key-value pairs
 *   <li>Serving as a data container during data generation and SQL output
 *   <li>Ensuring type safety through generic value storage
 *   <li>Supporting concurrent modifications via synchronized map during AI column generation
 * </ul>
 *
 * <p>The implementation uses a synchronized LinkedHashMap to store column values, allowing for
 * flexible data types, efficient lookups by column name, and safe concurrent modifications during
 * parallel AI value generation. Null values are supported.
 *
 * @param values A mapping of column names to their corresponding values in the row
 */
public record Row(Map<String, Object> values) {
  public Row {
    values = Collections.synchronizedMap(new LinkedHashMap<>(values));
  }
}
