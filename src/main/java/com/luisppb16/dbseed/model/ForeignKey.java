/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
      final String cols =
          columnMapping.keySet().stream()
              .sorted(Comparator.naturalOrder())
              .collect(Collectors.joining("__"));
      name = "fk_%s%s".formatted(pkTable, cols.isEmpty() ? "" : "_" + cols);
    }
  }
}
