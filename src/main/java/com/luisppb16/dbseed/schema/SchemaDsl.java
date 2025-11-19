/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.schema;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SchemaDsl {

  public static Schema schema(Table... tables) {
    return new Schema(List.of(tables));
  }

  public static Table table(String name, Column... columns) {
    return new Table(name, List.of(columns));
  }

  public static Column column(String name, SqlType type) {
    return new Column(name, type, false, null);
  }

  public static Column pk(String name, SqlType type) {
    return new Column(name, type, true, null);
  }

  public static Column fk(String name, SqlType type, String refTable, String refColumn) {
    return new Column(name, type, false, new ForeignKeyReference(refTable, refColumn));
  }

  public static String toSql(Schema schema) {
    return schema.tables().stream().map(SchemaDsl::tableSql).collect(Collectors.joining());
  }

  private static String tableSql(Table table) {
    String cols =
        table.columns().stream().map(SchemaDsl::columnSql).collect(Collectors.joining(",\n"));
    return "CREATE TABLE " + table.name() + " (\n" + cols + "\n);\n\n";
  }

  private static String columnSql(Column column) {
    StringBuilder sql =
        new StringBuilder("  ").append(column.name()).append(' ').append(column.type().toSql());

    if (column.primaryKey()) {
      sql.append(" PRIMARY KEY");
    }
    if (column.isForeignKey()) {
      ForeignKeyReference fk = column.foreignKey();
      sql.append(" REFERENCES ").append(fk.table()).append('(').append(fk.column()).append(')');
    }
    return sql.toString();
  }

  public enum SqlType {
    INT,
    VARCHAR,
    TIMESTAMP,
    BOOLEAN;

    public String toSql() {
      return switch (this) {
        case INT -> "INT";
        case VARCHAR -> "VARCHAR(255)";
        case TIMESTAMP -> "TIMESTAMP";
        case BOOLEAN -> "BOOLEAN";
      };
    }
  }

  public record Schema(List<Table> tables) {
    public Schema {
      Objects.requireNonNull(tables, "The list of tables cannot be null.");
      tables = List.copyOf(tables);
    }
  }

  public record Table(String name, List<Column> columns) {
    public Table {
      Objects.requireNonNull(name, "The table name cannot be null.");
      Objects.requireNonNull(columns, "The list of columns cannot be null.");
      columns = List.copyOf(columns);
    }
  }

  public record Column(
      String name, SqlType type, boolean primaryKey, ForeignKeyReference foreignKey) {
    public Column {
      Objects.requireNonNull(name, "The column name cannot be null.");
      Objects.requireNonNull(type, "The SQL type cannot be null.");
    }

    public boolean isForeignKey() {
      return foreignKey != null;
    }
  }

  public record ForeignKeyReference(String table, String column) {
    public ForeignKeyReference {
      Objects.requireNonNull(table, "The referenced table name cannot be null.");
      Objects.requireNonNull(column, "The referenced column name cannot be null.");
    }
  }
}
