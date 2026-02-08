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
    SqlType type,
    boolean nullable,
    boolean primaryKey,
    boolean uuid,
    int minValue,
    int maxValue,
    Set<String> allowedValues) {

  public Column {
    Objects.requireNonNull(name, "Column name cannot be null.");
    Objects.requireNonNull(type, "Column type cannot be null.");
    allowedValues = allowedValues != null ? Set.copyOf(allowedValues) : Set.of();
  }

  public int jdbcType() {
    return type.jdbcType();
  }

  public int length() {
    if (type instanceof SqlType.Text t) return t.length();
    if (type instanceof SqlType.Numeric n) return n.precision();
    return 0;
  }

  public int scale() {
    if (type instanceof SqlType.Numeric n) return n.scale();
    return 0;
  }

  public boolean hasAllowedValues() {
    return !allowedValues.isEmpty();
  }
}
