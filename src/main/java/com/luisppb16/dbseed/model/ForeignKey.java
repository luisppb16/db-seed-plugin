/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Map;
import java.util.Objects;
import lombok.Builder;

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
      String cols = String.join("__", columnMapping.keySet());
      name = "fk_" + pkTable + (cols.isEmpty() ? "" : "_" + cols);
    }
  }
}
