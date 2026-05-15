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
import java.util.Set;

/**
 * Representation of a database row in the DBSeed plugin ecosystem.
 *
 * <p>This record class represents a single row of data in a database table, containing the column
 * values as a map from column names to their corresponding values. It serves as the fundamental
 * unit of data during the seeding process, encapsulating the generated or existing data for a
 * specific table row.
 *
 * <p>Thread safety: column values are stored in a synchronized map to support concurrent writes
 * during parallel AI value generation (Phase 2). Individual {@code put}/{@code get} operations are
 * atomic. For compound operations (iteration, bulk read), callers must synchronize on the Row
 * instance. SQL generation and FK resolution run sequentially after all parallel work completes,
 * so no additional synchronization is needed in those phases.
 *
 * @param values A mapping of column names to their corresponding values in the row
 * @param explicitColumns The set of columns whose values were explicitly set (not auto-generated)
 */
public record Row(Map<String, Object> values, Set<String> explicitColumns) {
  public Row(Map<String, Object> values) {
    this(values, Set.of());
  }

  public Row {
    values = Collections.synchronizedMap(new LinkedHashMap<>(values));
    explicitColumns = Set.copyOf(explicitColumns);
  }
}
