/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.db.dialect.DatabaseDialect;
import com.luisppb16.dbseed.db.dialect.DialectFactory;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SqlGenerator {

  private static final Pattern UNQUOTED = Pattern.compile("[A-Za-z_]\\w*");
  private static final int BATCH_SIZE = 1000;

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
    final DatabaseDialect dialect = DialectFactory.resolve(driverInfo);
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
      final StringBuilder sb,
      final Map<Table, List<Row>> data,
      final SqlOptions opts,
      final DatabaseDialect dialect) {
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
            final List<Row> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            dialect.appendBatch(sb, tableName, columnList, batch, columnOrder);
          }
        });
  }

  private static void generateUpdateStatements(
      final StringBuilder sb,
      final List<PendingUpdate> updates,
      final SqlOptions opts,
      final DatabaseDialect dialect) {
    if (updates != null && !updates.isEmpty()) {
      for (final PendingUpdate update : updates) {
        final String tableName = qualified(opts, update.table(), dialect);

        final String setPart =
            update.fkValues().entrySet().stream()
                .map(
                    e -> {
                        StringBuilder valSb = new StringBuilder();
                        dialect.formatValue(e.getValue(), valSb);
                        return qualified(opts, e.getKey(), dialect)
                            .concat("=")
                            .concat(valSb.toString());
                    })
                .collect(Collectors.joining(", "));

        final String wherePart =
            update.pkValues().entrySet().stream()
                .map(
                    e -> {
                        StringBuilder valSb = new StringBuilder();
                        dialect.formatValue(e.getValue(), valSb);
                        return qualified(opts, e.getKey(), dialect)
                            .concat("=")
                            .concat(valSb.toString());
                    })
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

  private static String qualified(
      final SqlOptions opts, final String identifier, final DatabaseDialect dialect) {
    if (Objects.isNull(identifier)) {
      throw new IllegalArgumentException("Identifier cannot be null.");
    }

    final boolean needed =
        !UNQUOTED.matcher(identifier).matches()
            || RESERVED_KEYWORDS.contains(identifier.toLowerCase(Locale.ROOT));
    final String quoted = dialect.quote(identifier);

    return (opts.quoteIdentifiers() || needed) ? quoted : identifier;
  }

  private record SqlOptions(boolean quoteIdentifiers) {}
}