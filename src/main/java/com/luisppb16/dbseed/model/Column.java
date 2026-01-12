/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Objects;
import java.util.Set;
import lombok.Builder;

@Builder(toBuilder = true)
public record Column(
    String name,
    int jdbcType,
    boolean nullable,
    boolean primaryKey,
    boolean uuid,
    int length,
    int minValue,
    int maxValue,
    Set<String> allowedValues) {

  public Column {
    Objects.requireNonNull(name, "Column name cannot be null.");
  }

  public boolean hasAllowedValues() {
    return allowedValues != null && !allowedValues.isEmpty();
  }
}
