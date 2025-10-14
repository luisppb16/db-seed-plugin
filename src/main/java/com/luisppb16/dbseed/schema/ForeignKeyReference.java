package com.luisppb16.dbseed.schema;

import java.util.Objects;
import lombok.Builder;

@Builder(toBuilder = true)
public record ForeignKeyReference(String table, String column) {

  public ForeignKeyReference {
    Objects.requireNonNull(table, "The referenced table name cannot be null.");
    Objects.requireNonNull(column, "The referenced column name cannot be null.");
  }
}
