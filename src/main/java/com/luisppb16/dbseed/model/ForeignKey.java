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

@Builder(toBuilder = true)
public record ForeignKey(
    String name, String pkTable, Map<String, String> columnMapping, boolean uniqueOnFk) {

  public ForeignKey {
    Objects.requireNonNull(pkTable, "The primary table name (pkTable) cannot be null.");
    Objects.requireNonNull(columnMapping, "The column mapping cannot be null.");


    columnMapping = Map.copyOf(columnMapping);


    if (name == null || name.isBlank()) {
      final String cols =
          columnMapping.keySet().stream()
              .sorted(Comparator.naturalOrder())
              .collect(Collectors.joining("__"));
      name = "fk_%s%s".formatted(pkTable, cols.isEmpty() ? "" : "_" + cols);
    }
  }
}
