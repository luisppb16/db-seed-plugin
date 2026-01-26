/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SqlGenerator {

  private static final Pattern UNQUOTED = Pattern.compile("[A-Za-z_]\\w*");
  private static final int BATCH_SIZE = 1000;
  private static final String COMMIT_STMT = "COMMIT;\n";
  private static final String NULL_STR = "NULL";

  private static final Set<String> RESERVED_KEYWORDS =
      Set.of(
          "select", "from", "where", "group", "order", "limit", "offset", "insert", "update",
          "delete", "user", "table", "level", "option", "public", "session", "size", "start");

  public static String generate(
      Map<Table, List<Row>> data, List<PendingUpdate> updates, boolean deferred) {
    return generate(data, updates, deferred, null);
  }

  public static String generate(
      Map<Table, List<Row>> data,
      List<PendingUpdate> updates,
      boolean deferred,
      DriverInfo driverInfo) {
    final StringBuilder sb = new StringBuilder();
    final SqlDialect dialect = SqlDialect.resolve(driverInfo);
    final SqlOptions opts = new SqlOptions(true);

    if (deferred) {
      sb.append(dialect.beginTransaction());
      sb.append(dialect.disableConstraints());
    }

    generateInsertStatements(sb, data, opts, dialect);
    generateUpdateStatements(sb, updates, opts, dialect);

    if (deferred) {
      sb.append(dialect.enableConstraints());
      sb.append(dialect.commitTransaction());
    }

    return sb.toString();
  }

  private static void generateInsertStatements(
      final StringBuilder sb, final Map<Table, List<Row>> data, final SqlOptions opts, final SqlDialect dialect) {
    data.forEach(
        (table, rows) -> {
          if (rows == null || rows.isEmpty()) {
            return;
          }

          final List<String> columnOrder = table.columns().stream().map(Column::name).toList();
          final String tableName = qualified(opts, table.name(), dialect);
          final String columnList =
              columnOrder.stream()
                  .map(col -> qualified(opts, col, dialect))
                  .collect(Collectors.joining(", "));

          for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            appendBatch(sb, tableName, columnList, rows, i, columnOrder, dialect);
          }
        });
  }

  private static void appendBatch(
      final StringBuilder sb,
      final String tableName,
      final String columnList,
      final List<Row> rows,
      final int startIndex,
      final List<String> columnOrder,
      final SqlDialect dialect) {
    sb.append("INSERT INTO ")
        .append(tableName)
        .append(" (")
        .append(columnList)
        .append(") VALUES\n");

    final List<Row> batch = rows.subList(startIndex, Math.min(startIndex + BATCH_SIZE, rows.size()));
    for (int j = 0; j < batch.size(); j++) {
      final Row row = batch.get(j);
      sb.append('(');
      for (int k = 0; k < columnOrder.size(); k++) {
        formatValue(row.values().get(columnOrder.get(k)), sb, dialect);
        if (k < columnOrder.size() - 1) {
          sb.append(", ");
        }
      }
      sb.append(')');
      if (j < batch.size() - 1) {
        sb.append(",\n");
      }
    }
    sb.append(";\n");
  }

  private static void generateUpdateStatements(
      final StringBuilder sb, final List<PendingUpdate> updates, final SqlOptions opts, final SqlDialect dialect) {
    if (updates != null && !updates.isEmpty()) {
      for (final PendingUpdate update : updates) {
        final String tableName = qualified(opts, update.table(), dialect);

        final String setPart =
            update.fkValues().entrySet().stream()
                .map(
                    e ->
                        qualified(opts, e.getKey(), dialect)
                            .concat("=")
                            .concat(formatValue(e.getValue(), dialect)))
                .collect(Collectors.joining(", "));

        final String wherePart =
            update.pkValues().entrySet().stream()
                .map(
                    e ->
                        qualified(opts, e.getKey(), dialect)
                            .concat("=")
                            .concat(formatValue(e.getValue(), dialect)))
                .collect(Collectors.joining(" AND "));

        sb.append("UPDATE ")
            .append(tableName)
            .append(" SET ")
            .append(setPart)
            .append(" WHERE ")
            .append(wherePart)
            .append(";\n");
      }
    }
  }

  private static String qualified(final SqlOptions opts, final String identifier, final SqlDialect dialect) {
    if (Objects.isNull(identifier)) {
      throw new IllegalArgumentException("Identifier cannot be null.");
    }

    final boolean needed =
        !UNQUOTED.matcher(identifier).matches()
            || RESERVED_KEYWORDS.contains(identifier.toLowerCase(Locale.ROOT));
    final String quoted = dialect.quote(identifier);

    return (opts.quoteIdentifiers() || needed) ? quoted : identifier;
  }

  private static String formatValue(final Object value, final SqlDialect dialect) {
    final StringBuilder sb = new StringBuilder();
    formatValue(value, sb, dialect);
    return sb.toString();
  }

  private static void formatValue(final Object value, final StringBuilder sb, final SqlDialect dialect) {
    if (value == null) {
      sb.append(NULL_STR);
    } else {
      switch (value) {
        case SqlKeyword k -> sb.append(k.name());
        case String s -> sb.append("'").append(escapeSql(s)).append("'");
        case Character c -> sb.append("'").append(escapeSql(c.toString())).append("'");
        case UUID u -> sb.append("'").append(u).append("'");
        case Date d -> sb.append("'").append(d).append("'");
        case Timestamp t -> sb.append("'").append(t).append("'");
        case Boolean b -> sb.append(dialect.formatBoolean(b));
        case BigDecimal bd -> sb.append(bd.toPlainString());
        case Double d -> sb.append(formatDouble(d));
        case Float f -> sb.append(formatDouble(f.doubleValue()));
        default -> sb.append(Objects.toString(value, NULL_STR));
      }
    }
  }

  private static String formatDouble(final double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return NULL_STR;
    }
    return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
  }

  private static String escapeSql(final String s) {
    return s.replace("'", "''");
  }

  private record SqlOptions(boolean quoteIdentifiers) {}

  private enum SqlDialect {
    STANDARD,
    MYSQL {
      @Override String quote(String id) { return "`" + id.replace("`", "``") + "`"; }
      @Override String formatBoolean(boolean b) { return b ? "1" : "0"; }
      @Override String beginTransaction() { return "START TRANSACTION;\n"; }
      @Override String disableConstraints() { return "SET FOREIGN_KEY_CHECKS = 0;\n"; }
      @Override String enableConstraints() { return "SET FOREIGN_KEY_CHECKS = 1;\n"; }
    },
    SQL_SERVER {
      @Override String quote(String id) { return "[" + id.replace("]", "]]") + "]"; }
      @Override String formatBoolean(boolean b) { return b ? "1" : "0"; }
      @Override String beginTransaction() { return "BEGIN TRANSACTION;\n"; }
      @Override String commitTransaction() { return "COMMIT TRANSACTION;\n"; }
      @Override String disableConstraints() { return "EXEC sp_msforeachtable 'ALTER TABLE ? NOCHECK CONSTRAINT ALL';\n"; }
      @Override String enableConstraints() { return "EXEC sp_msforeachtable 'ALTER TABLE ? WITH CHECK CHECK CONSTRAINT ALL';\n"; }
    },
    POSTGRESQL,
    ORACLE {
      @Override String quote(String id) { return "\"" + id.replace("\"", "\"\"").toUpperCase(Locale.ROOT) + "\""; }
      @Override String formatBoolean(boolean b) { return b ? "1" : "0"; }
      @Override String beginTransaction() { return "SET TRANSACTION READ WRITE;\n"; }
      @Override String disableConstraints() { 
          return """
              BEGIN
                FOR c IN (SELECT table_name, constraint_name FROM user_constraints WHERE constraint_type = 'R')
                LOOP
                  EXECUTE IMMEDIATE 'ALTER TABLE ' || c.table_name || ' DISABLE CONSTRAINT ' || c.constraint_name;
                END LOOP;
              END;
              /
              """;
      }
      @Override String enableConstraints() { 
          return """
              BEGIN
                FOR c IN (SELECT table_name, constraint_name FROM user_constraints WHERE constraint_type = 'R')
                LOOP
                  EXECUTE IMMEDIATE 'ALTER TABLE ' || c.table_name || ' ENABLE CONSTRAINT ' || c.constraint_name;
                END LOOP;
              END;
              /
              """;
      }
    },
    SQLITE {
      @Override String formatBoolean(boolean b) { return b ? "1" : "0"; }
      @Override String beginTransaction() { return "BEGIN TRANSACTION;\n"; }
      @Override String disableConstraints() { return "PRAGMA foreign_keys = OFF;\n"; }
      @Override String enableConstraints() { return "PRAGMA foreign_keys = ON;\n"; }
    };

    String quote(String id) { return "\"" + id.replace("\"", "\"\"") + "\""; }
    String formatBoolean(boolean b) { return b ? "TRUE" : "FALSE"; }
    String beginTransaction() { return "BEGIN;\n"; }
    String commitTransaction() { return COMMIT_STMT; }
    String disableConstraints() { return "SET CONSTRAINTS ALL DEFERRED;\n"; }
    String enableConstraints() { return ""; }

    static SqlDialect resolve(final DriverInfo driver) {
      if (driver == null) return STANDARD;
      
      final String cls = Objects.requireNonNullElse(driver.driverClass(), "").toLowerCase(Locale.ROOT);
      final String url = Objects.requireNonNullElse(driver.urlTemplate(), "").toLowerCase(Locale.ROOT);

      if (cls.contains("mysql") || cls.contains("mariadb") || url.contains("mysql") || url.contains("mariadb")) {
          return MYSQL;
      }
      if (cls.contains("sqlserver") || url.contains("sqlserver") || url.contains("jtds:sqlserver")) {
          return SQL_SERVER;
      }
      if (cls.contains("oracle") || url.contains("oracle")) {
          return ORACLE;
      }
      if (cls.contains("sqlite") || url.contains("sqlite")) {
          return SQLITE;
      }
      if (cls.contains("postgresql") || url.contains("postgresql") || url.contains("redshift") || url.contains("cockroach")) {
          return POSTGRESQL;
      }

      return STANDARD;
    }
  }
}
