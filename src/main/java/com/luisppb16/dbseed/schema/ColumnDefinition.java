/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.schema;

import java.util.Objects;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Builder(toBuilder = true)
public record ColumnDefinition(
    @NotNull String name, @NotNull SqlType type, boolean primaryKey, @Nullable ForeignKeyReference foreignKey) {

  public ColumnDefinition(@NotNull String name, @NotNull SqlType type, boolean primaryKey, @Nullable ForeignKeyReference foreignKey) {
    this.name = Objects.requireNonNull(name, "The column name cannot be null.");
    this.type = Objects.requireNonNull(type, "The SQL type of the column cannot be null.");
    this.primaryKey = primaryKey;
    this.foreignKey = foreignKey;
  }

  public boolean isForeignKey() {
    return foreignKey != null;
  }
}
