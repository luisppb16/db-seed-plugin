/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
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

  private static final Set<String> RESERVED_KEYWORDS =
      Set.of(
          "select", "from", "where", "group", "order", "limit", "offset", "insert", "update",
          "delete", "user", "table");

  public static String generate(
      Map<Table, List<Row>> data, List<PendingUpdate> updates, boolean deferred) {
    StringBuilder sb = new StringBuilder();
    SqlOptions opts = SqlOptions.builder().quoteIdentifiers(true).build();

    if (deferred) {
      sb.append("BEGIN;\n");
    }

    generateInsertStatements(sb, data, opts);

    if (deferred) {
      sb.append("SET CONSTRAINTS ALL DEFERRED;\n");
    }

    generateUpdateStatements(sb, updates, opts);

    if (deferred) {
      sb.append("COMMIT;\n");
    }

    return sb.toString();
  }

  private static void generateInsertStatements(
      StringBuilder sb, Map<Table, List<Row>> data, SqlOptions opts) {
    data.forEach(
        (table, rows) -> {
          if (rows == null || rows.isEmpty()) {
            return;
          }

          List<String> columnOrder = table.columns().stream().map(Column::name).toList();
          String tableName = qualified(opts, table.name());
          String columnList =
              columnOrder.stream()
                  .map(col -> qualified(opts, col))
                  .collect(Collectors.joining(", "));

          for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            sb.append("INSERT INTO ")
                .append(tableName)
                .append(" (")
                .append(columnList)
                .append(") VALUES\n");

            List<Row> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            for (int j = 0; j < batch.size(); j++) {
              Row row = batch.get(j);
              sb.append('(');
              for (int k = 0; k < columnOrder.size(); k++) {
                sb.append(formatValue(row.values().get(columnOrder.get(k))));
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
        });
  }

  private static void generateUpdateStatements(
      StringBuilder sb, List<PendingUpdate> updates, SqlOptions opts) {
    // Apply deferred updates (FKs in cycles).
    if (updates != null && !updates.isEmpty()) {
      for (PendingUpdate update : updates) {
        String tableName = qualified(opts, update.table());

        String setPart =
            update.fkValues().entrySet().stream()
                .map(e -> qualified(opts, e.getKey()).concat("=").concat(formatValue(e.getValue())))
                .collect(Collectors.joining(", "));

        String wherePart =
            update.pkValues().entrySet().stream()
                .map(e -> qualified(opts, e.getKey()).concat("=").concat(formatValue(e.getValue())))
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

  private static String qualified(SqlOptions opts, String identifier) {
    if (Objects.isNull(identifier)) {
      throw new IllegalArgumentException("Identifier cannot be null.");
    }
    boolean forceQuote = opts.quoteIdentifiers() || needsQuoting(identifier);
    String safe = identifier.replace("\"", "\"\"");
    return forceQuote ? "\"".concat(safe).concat("\"") : identifier;
  }

  private static boolean needsQuoting(String identifier) {
    if (Objects.isNull(identifier)) {
      return false;
    }
    if (!UNQUOTED.matcher(identifier).matches()) {
      return true;
    }
    return RESERVED_KEYWORDS.contains(identifier.toLowerCase(Locale.ROOT));
  }

  private static String formatValue(Object value) {
    return switch (value) {
      case null -> "NULL";
      case String s -> "'".concat(escapeSql(s)).concat("'");
      case Character c -> "'".concat(escapeSql(c.toString())).concat("'");
      case UUID u -> "'".concat(u.toString()).concat("'");
      case Date d -> "'".concat(d.toString()).concat("'");
      case Timestamp t -> "'".concat(t.toString()).concat("'");
      case Boolean b -> String.valueOf(b).toUpperCase(Locale.ROOT);
      default -> Objects.toString(value, "NULL");
    };
  }

  private static String escapeSql(String s) {
    return s.replace("'", "''");
  }

  @Builder(toBuilder = true)
  private record SqlOptions(boolean quoteIdentifiers) {}
}
