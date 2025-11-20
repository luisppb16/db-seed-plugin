/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
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

  private static final Pattern UNQUOTED = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private static final Set<String> RESERVED_KEYWORDS =
      Set.of(
          "select", "from", "where", "group", "order", "limit", "offset", "insert", "update",
          "delete", "user", "table");

  public static String generate(
      List<Table> tables,
      Map<String, List<Row>> data,
      List<PendingUpdate> updates,
      boolean deferred) {

    // The insertion order of tables is preserved by LinkedHashMap in DataGenerator.
    List<String> orderedTableNames = new ArrayList<>(data.keySet());

    StringBuilder sb = new StringBuilder();
    SqlOptions opts = SqlOptions.builder().quoteIdentifiers(true).build();

    if (deferred) {
      sb.append("BEGIN;\n");
      sb.append("SET CONSTRAINTS ALL DEFERRED;\n");
    }

    // Insert data for each table in order.
    for (String tableNameOrdered : orderedTableNames) {
      Table table =
          tables.stream()
              .filter(tab -> tab.name().equals(tableNameOrdered))
              .findFirst()
              .orElse(null);
      if (table == null) {
        continue;
      }

      List<Row> rows = data.get(table.name());
      if (rows == null || rows.isEmpty()) {
        continue;
      }

      List<String> columnOrder =
          table.columns().stream().map(Column::name).toList();

      String tableName = qualified(opts, table.name());
      String columnList =
          columnOrder.stream().map(col -> qualified(opts, col)).collect(Collectors.joining(", "));

      for (Row row : rows) {
        String values =
            columnOrder.stream()
                .map(col -> formatValue(row.values().get(col)))
                .collect(Collectors.joining(", "));
        sb.append("INSERT INTO ")
            .append(tableName)
            .append(" (")
            .append(columnList)
            .append(") VALUES (")
            .append(values)
            .append(");\n");
      }
    }

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

    if (deferred) {
      sb.append("COMMIT;\n");
    }

    return sb.toString();
  }

  private static String qualified(SqlOptions opts, String identifier) {
    boolean forceQuote = opts.quoteIdentifiers() || needsQuoting(identifier);
    String safe = identifier.replace("\"", "\"\"");
    return forceQuote ? "\"".concat(safe).concat("\"") : identifier;
  }

  private static boolean needsQuoting(String identifier) {
    if (identifier == null) {
      return false;
    }
    if (!UNQUOTED.matcher(identifier).matches()) {
      return true;
    }
    return RESERVED_KEYWORDS.contains(identifier.toLowerCase(Locale.ROOT));
  }

  private static String formatValue(Object value) {
    if (value == null) {
      return "NULL";
    }

    if (value instanceof String s) return "'".concat(escapeSql(s)).concat("'");
    if (value instanceof UUID u) return "'".concat(u.toString()).concat("'");
    if (value instanceof Date d) return "'".concat(d.toString()).concat("'");
    if (value instanceof Timestamp t) return "'".concat(t.toString()).concat("'");
    if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";

    return Objects.toString(value, "NULL");
  }

  private static String escapeSql(String s) {
    return s.replace("'", "''");
  }

  @Builder(toBuilder = true)
  private record SqlOptions(boolean quoteIdentifiers) {}
}
