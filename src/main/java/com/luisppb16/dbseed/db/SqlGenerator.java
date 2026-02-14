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

/**
 * Advanced SQL generation engine for the DBSeed plugin ecosystem.
 *
 * <p>This utility class provides sophisticated SQL script generation capabilities, transforming
 * in-memory data representations into optimized SQL INSERT and UPDATE statements. It implements
 * dialect-aware SQL generation with proper identifier quoting, value formatting, and constraint
 * management. The class handles both immediate and deferred constraint processing modes, supporting
 * complex scenarios with circular foreign key dependencies.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Generating optimized INSERT statements with proper batching for performance
 *   <li>Creating UPDATE statements for deferred foreign key constraint resolution
 *   <li>Applying database dialect-specific formatting and syntax rules
 *   <li>Managing transaction boundaries for deferred constraint processing
 *   <li>Implementing intelligent identifier quoting for reserved keywords
 *   <li>Handling value formatting for different data types and SQL dialects
 *   <li>Generating constraint enable/disable sequences when required
 *   <li>Optimizing SQL output for readability and execution efficiency
 *   <li>Managing batch processing to avoid statement size limitations
 *   <li>Resolving dialect-specific identifier quoting requirements
 *   <li>Generating BEGIN/COMMIT transaction blocks for constraint management
 *   <li>Implementing proper SQL statement termination and formatting
 * </ul>
 *
 * <p>The class implements advanced batching algorithms to optimize SQL generation performance,
 * grouping INSERT statements into manageable chunks to avoid database limitations. It includes
 * sophisticated identifier qualification logic that automatically detects when quoting is required
 * based on reserved keywords and naming conventions. The implementation handles various data types
 * with appropriate formatting and escaping for the target SQL dialect.
 *
 * <p>Thread safety is maintained through StringBuilder-based construction and immutable input
 * parameters. The class leverages the DialectFactory and DatabaseDialect implementations to provide
 * vendor-specific SQL generation capabilities. Memory efficiency is achieved through direct string
 * building without intermediate collections, and the implementation includes optimizations for
 * large datasets through batch processing.
 *
 * <p>Advanced features include automatic constraint management for deferred processing scenarios,
 * where foreign key constraints are temporarily disabled during data insertion and re-enabled
 * afterward. The class handles complex scenarios involving circular dependencies that require
 * UPDATE statements to establish foreign key relationships after initial data insertion.
 *
 * <p>Error handling includes validation of input data and graceful handling of edge cases such as
 * null values, empty datasets, and malformed identifiers. The implementation is resilient to schema
 * changes and adapts to varying table structures dynamically.
 *
 * @author Luis Paolo Pepe Barra (@LuisPPB16)
 * @version 1.3.0
 * @since 2024.1
 * @see DatabaseDialect
 * @see DialectFactory
 * @see PendingUpdate
 * @see Row
 * @see Table
 * @see DriverInfo
 */
@UtilityClass
public class SqlGenerator {

  private static final Pattern UNQUOTED = Pattern.compile("[A-Za-z_]\\w*");
  private static final int BATCH_SIZE = 1000;

  private static final String SQL_UPDATE = "UPDATE ";
  private static final String SQL_SET = " SET ";
  private static final String SQL_WHERE = " WHERE ";
  private static final String SQL_AND = " AND ";
  private static final String SQL_EQUALS = "=";
  private static final String SQL_COMMA_SPACE = ", ";
  private static final String SQL_STATEMENT_END = ";\n";

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
          if (Objects.isNull(rows) || rows.isEmpty()) {
            return;
          }

          final List<String> columnOrder = table.columns().stream().map(Column::name).toList();
          final String tableName = qualified(opts, table.name(), dialect);
          final String columnList =
              columnOrder.stream()
                  .map(col -> qualified(opts, col, dialect))
                  .collect(Collectors.joining(SQL_COMMA_SPACE));

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
    if (Objects.nonNull(updates) && !updates.isEmpty()) {
      for (final PendingUpdate update : updates) {
        final String tableName = qualified(opts, update.table(), dialect);

        final String setPart =
            update.fkValues().entrySet().stream()
                .map(
                    e -> {
                      StringBuilder valSb = new StringBuilder();
                      dialect.formatValue(e.getValue(), valSb);
                      return qualified(opts, e.getKey(), dialect)
                          .concat(SQL_EQUALS)
                          .concat(valSb.toString());
                    })
                .collect(Collectors.joining(SQL_COMMA_SPACE));

        final String wherePart =
            update.pkValues().entrySet().stream()
                .map(
                    e -> {
                      StringBuilder valSb = new StringBuilder();
                      dialect.formatValue(e.getValue(), valSb);
                      return qualified(opts, e.getKey(), dialect)
                          .concat(SQL_EQUALS)
                          .concat(valSb.toString());
                    })
                .collect(Collectors.joining(SQL_AND));

        sb.append(SQL_UPDATE)
            .append(tableName)
            .append(SQL_SET)
            .append(setPart)
            .append(SQL_WHERE)
            .append(wherePart)
            .append(SQL_STATEMENT_END);
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
