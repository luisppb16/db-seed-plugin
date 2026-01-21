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
import lombok.Builder;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SqlGenerator {

  private static final Pattern UNQUOTED = Pattern.compile("[A-Za-z_]\\w*");
  private static final int BATCH_SIZE = 1000; // Optimal batch size for most DBs
  private static final String COMMIT_STMT = "COMMIT;\n";

  private static final Set<String> RESERVED_KEYWORDS =
      Set.of(
          "select", "from", "where", "group", "order", "limit", "offset", "insert", "update",
          "delete", "user", "table");

  public static String generate(
      Map<Table, List<Row>> data, List<PendingUpdate> updates, boolean deferred) {
    return generate(data, updates, deferred, null);
  }

  public static String generate(
      Map<Table, List<Row>> data,
      List<PendingUpdate> updates,
      boolean deferred,
      DriverInfo driverInfo) {
    StringBuilder sb = new StringBuilder();
    SqlDialect dialect = SqlDialect.resolve(driverInfo);
    SqlOptions opts = SqlOptions.builder().quoteIdentifiers(true).build();

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
      StringBuilder sb, Map<Table, List<Row>> data, SqlOptions opts, SqlDialect dialect) {
    data.forEach(
        (table, rows) -> {
          if (rows == null || rows.isEmpty()) {
            return;
          }

          List<String> columnOrder = table.columns().stream().map(Column::name).toList();
          String tableName = qualified(opts, table.name(), dialect);
          String columnList =
              columnOrder.stream()
                  .map(col -> qualified(opts, col, dialect))
                  .collect(Collectors.joining(", "));

          for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            appendBatch(sb, tableName, columnList, rows, i, columnOrder, dialect);
          }
        });
  }

  private static void appendBatch(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      int startIndex,
      List<String> columnOrder,
      SqlDialect dialect) {
    sb.append("INSERT INTO ")
        .append(tableName)
        .append(" (")
        .append(columnList)
        .append(") VALUES\n");

    List<Row> batch = rows.subList(startIndex, Math.min(startIndex + BATCH_SIZE, rows.size()));
    for (int j = 0; j < batch.size(); j++) {
      Row row = batch.get(j);
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
      StringBuilder sb, List<PendingUpdate> updates, SqlOptions opts, SqlDialect dialect) {
    // Apply deferred updates (FKs in cycles).
    if (updates != null && !updates.isEmpty()) {
      for (PendingUpdate update : updates) {
        String tableName = qualified(opts, update.table(), dialect);

        String setPart =
            update.fkValues().entrySet().stream()
                .map(
                    e ->
                        qualified(opts, e.getKey(), dialect)
                            .concat("=")
                            .concat(formatValue(e.getValue(), dialect)))
                .collect(Collectors.joining(", "));

        String wherePart =
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

  private static String qualified(SqlOptions opts, String identifier, SqlDialect dialect) {
    if (Objects.isNull(identifier)) {
      throw new IllegalArgumentException("Identifier cannot be null.");
    }

    boolean needed =
        !UNQUOTED.matcher(identifier).matches()
            || RESERVED_KEYWORDS.contains(identifier.toLowerCase(Locale.ROOT));
    String quoted = dialect.quote(identifier);

    return (opts.quoteIdentifiers() || needed) ? quoted : identifier;
  }

  private static String formatValue(Object value, SqlDialect dialect) {
    StringBuilder sb = new StringBuilder();
    formatValue(value, sb, dialect);
    return sb.toString();
  }

  private static void formatValue(Object value, StringBuilder sb, SqlDialect dialect) {
    if (value == null) {
      sb.append("NULL");
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
        default -> sb.append(Objects.toString(value, "NULL"));
      }
    }
  }

  private static String formatDouble(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return "NULL";
    }
    return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
  }

  private static String escapeSql(String s) {
    return s.replace("'", "''");
  }

  @Builder(toBuilder = true)
  private record SqlOptions(boolean quoteIdentifiers) {}

  private enum SqlDialect {
    STANDARD {
      @Override
      String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
      }

      @Override
      String formatBoolean(boolean b) {
        return b ? "TRUE" : "FALSE";
      }

      @Override
      String beginTransaction() {
        return "BEGIN;\n";
      }

      @Override
      String commitTransaction() {
        return COMMIT_STMT;
      }

      @Override
      String disableConstraints() {
        return "SET CONSTRAINTS ALL DEFERRED;\n";
      }

      @Override
      String enableConstraints() {
        return "";
      }
    },
    MYSQL {
      @Override
      String quote(String id) {
        return "`" + id.replace("`", "``") + "`";
      }

      @Override
      String formatBoolean(boolean b) {
        return b ? "1" : "0";
      }

      @Override
      String beginTransaction() {
        return "START TRANSACTION;\n";
      }

      @Override
      String commitTransaction() {
        return COMMIT_STMT;
      }

      @Override
      String disableConstraints() {
        return "SET FOREIGN_KEY_CHECKS = 0;\n";
      }

      @Override
      String enableConstraints() {
        return "SET FOREIGN_KEY_CHECKS = 1;\n";
      }
    },
    SQL_SERVER {
      @Override
      String quote(String id) {
        return "[" + id.replace("]", "]]") + "]";
      }

      @Override
      String formatBoolean(boolean b) {
        return b ? "1" : "0";
      }

      @Override
      String beginTransaction() {
        return "BEGIN TRANSACTION;\n";
      }

      @Override
      String commitTransaction() {
        return "COMMIT TRANSACTION;\n";
      }

      @Override
      String disableConstraints() {
        return "EXEC sp_msforeachtable 'ALTER TABLE ? NOCHECK CONSTRAINT all';\n";
      }

      @Override
      String enableConstraints() {
        return "EXEC sp_msforeachtable 'ALTER TABLE ? WITH CHECK CHECK CONSTRAINT all';\n";
      }
    },
    POSTGRESQL {
      @Override
      String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
      }

      @Override
      String formatBoolean(boolean b) {
        return b ? "TRUE" : "FALSE";
      }

      @Override
      String beginTransaction() {
        return "BEGIN;\n";
      }

      @Override
      String commitTransaction() {
        return COMMIT_STMT;
      }

      @Override
      String disableConstraints() {
        return "SET CONSTRAINTS ALL DEFERRED;\n";
      }

      @Override
      String enableConstraints() {
        return "";
      }
    };

    abstract String quote(String id);

    abstract String formatBoolean(boolean b);

    abstract String beginTransaction();

    abstract String commitTransaction();

    abstract String disableConstraints();

    abstract String enableConstraints();

    static SqlDialect resolve(DriverInfo driver) {
      if (driver == null) return STANDARD;
      String cls = driver.driverClass();
      if (cls == null) return STANDARD;

      String lowerCls = cls.toLowerCase(Locale.ROOT);
      if (lowerCls.contains("mysql") || lowerCls.contains("mariadb")) return MYSQL;
      if (lowerCls.contains("sqlserver")) return SQL_SERVER;
      if (lowerCls.contains("postgresql")
          || lowerCls.contains("redshift")
          || lowerCls.contains("cockroach")) return POSTGRESQL;

      return STANDARD;
    }
  }
}
