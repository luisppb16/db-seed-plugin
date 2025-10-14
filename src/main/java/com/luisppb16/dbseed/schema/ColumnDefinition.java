package com.luisppb16.dbseed.schema;

import java.util.Objects;
import lombok.Builder;
import org.jetbrains.annotations.Nullable;

@Builder(toBuilder = true)
public record ColumnDefinition(
    String name, SqlType type, boolean primaryKey, @Nullable ForeignKeyReference foreignKey) {

  public ColumnDefinition {
    Objects.requireNonNull(name, "The column name cannot be null.");
    Objects.requireNonNull(type, "The SQL type of the column cannot be null.");
  }

  public boolean isForeignKey() {
    return foreignKey != null;
  }
}
