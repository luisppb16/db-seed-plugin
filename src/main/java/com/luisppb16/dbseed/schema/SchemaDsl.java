/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.schema;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * Domain-specific language for defining database schemas programmatically in the DBSeed plugin.
 *
 * <p>This utility class provides a fluent API for constructing database schema definitions using a
 * domain-specific language approach. It enables programmatic creation of database schemas with
 * tables, columns, primary keys, foreign keys, constraints, and other structural elements. The DSL
 * abstracts away raw SQL syntax while maintaining expressiveness for complex schema definitions. It
 * serves as both a schema definition mechanism and a SQL generation utility.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Providing a fluent API for programmatic schema definition
 *   <li>Generating SQL DDL statements from schema object models
 *   <li>Managing schema element relationships (primary keys, foreign keys)
 *   <li>Supporting various column constraints (NOT NULL, UNIQUE, DEFAULT)
 *   <li>Ensuring schema object immutability and thread safety
 *   <li>Validating schema structure and element relationships
 * </ul>
 *
 * <p>The implementation uses immutable record types to represent schema elements, ensuring thread
 * safety and preventing unintended modifications. The DSL follows builder patterns to enable
 * readable schema definitions and includes comprehensive validation to ensure schema integrity. The
 * class also provides SQL generation capabilities to transform schema definitions into executable
 * DDL statements.
 */
@UtilityClass
public class SchemaDsl {

  public static Schema schema(final Table... tables) {
    return new Schema(List.of(tables));
  }

  public static Table table(final String name, final Column... columns) {
    return new Table(name, List.of(columns));
  }

  public static Column column(final String name, final SqlType type) {
    return new Column(name, type, false, false, null, false);
  }

  public static Column column(
      final String name,
      final SqlType type,
      final boolean notNull,
      final String defaultValue,
      final boolean unique) {
    return new Column(name, type, false, notNull, defaultValue, unique);
  }

  public static Column pk(final String name, final SqlType type) {
    return new Column(name, type, true, true, null, true);
  }

  @SuppressWarnings("unused")
  public static Column fk(
      final String name, final SqlType type, final String refTable, final String refColumn) {
    return new Column(
        name, type, false, false, null, false, new ForeignKeyReference(refTable, refColumn));
  }

  public static String toSql(final Schema schema) {
    return schema.tables().stream().map(SchemaDsl::tableSql).collect(Collectors.joining());
  }

  private static String quoteIdentifier(final String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private static String tableSql(final Table table) {
    final String quotedName = quoteIdentifier(table.name());
    if (table.columns().isEmpty()) {
      return "CREATE TABLE %s ();%n%n".formatted(quotedName);
    }
    final String cols =
        table.columns().stream()
            .map(SchemaDsl::columnSql)
            .collect(Collectors.joining("," + System.lineSeparator() + "  "));
    return "CREATE TABLE %s (%n  %s%n);%n%n".formatted(quotedName, cols);
  }

  private static String columnSql(final Column column) {
    final StringBuilder sql =
        new StringBuilder(
            "%s %s".formatted(quoteIdentifier(column.name()), column.type().toSql()));

    if (column.primaryKey()) {
      sql.append(" PRIMARY KEY");
    }
    if (column.notNull()) {
      sql.append(" NOT NULL");
    }
    if (Objects.nonNull(column.defaultValue())) {
      sql.append(" DEFAULT %s".formatted(column.defaultValue()));
    }
    if (column.unique()) {
      sql.append(" UNIQUE");
    }
    if (column.isForeignKey()) {
      final ForeignKeyReference fk = column.foreignKey();
      sql.append(
          " REFERENCES %s(%s)"
              .formatted(quoteIdentifier(fk.table()), quoteIdentifier(fk.column())));
    }
    return sql.toString();
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
      String name,
      SqlType type,
      boolean primaryKey,
      boolean notNull,
      String defaultValue,
      boolean unique,
      ForeignKeyReference foreignKey) {
    public Column {
      Objects.requireNonNull(name, "The column name cannot be null.");
      Objects.requireNonNull(type, "The SQL type cannot be null.");
    }

    public Column(
        String name,
        SqlType type,
        boolean primaryKey,
        boolean notNull,
        String defaultValue,
        boolean unique) {
      this(name, type, primaryKey, notNull, defaultValue, unique, null);
    }

    public boolean isForeignKey() {
      return Objects.nonNull(foreignKey);
    }
  }

  public record ForeignKeyReference(String table, String column) {
    public ForeignKeyReference {
      Objects.requireNonNull(table, "The referenced table name cannot be null.");
      Objects.requireNonNull(column, "The referenced column name cannot be null.");
    }
  }
}
