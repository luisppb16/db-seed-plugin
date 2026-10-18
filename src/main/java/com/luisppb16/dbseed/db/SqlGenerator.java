/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.db.dialect.AbstractDialect;
import com.luisppb16.dbseed.db.dialect.DialectFactory;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

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
 * parameters. The class leverages the DialectFactory and AbstractDialect implementations to provide
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
 * @see AbstractDialect
 * @see DialectFactory
 * @see PendingUpdate
 * @see Row
 * @see Table
 * @see DriverInfo
 */
@UtilityClass
@Slf4j
public class SqlGenerator {

  private static final String SQL_UPDATE = "UPDATE ";
  private static final String SQL_SET = " SET ";
  private static final String SQL_WHERE = " WHERE ";
  private static final String SQL_AND = " AND ";
  private static final String SQL_EQUALS = "=";
  private static final String SQL_COMMA_SPACE = ", ";
  private static final String SQL_STATEMENT_END = ";\n";

  public static String generate(
      Map<Table, List<Row>> data, List<PendingUpdate> updates, boolean deferred) {
    return generate(data, updates, deferred, null, null);
  }

  public static String generate(
      Map<Table, List<Row>> data,
      List<PendingUpdate> updates,
      boolean deferred,
      DriverInfo driverInfo) {
    return generate(data, updates, deferred, driverInfo, null);
  }

  /**
   * Generates the SQL script while reporting progress through {@code tracker}: one work unit per
   * table and one per deferred update <em>evaluated</em> (empty tables and dropped updates
   * included, so the caller's reservation always matches). The caller owns the total reserved for
   * this phase.
   *
   * @param tracker progress sink, may be {@code null} (no progress reporting)
   */
  public static String generate(
      final Map<Table, List<Row>> data,
      final List<PendingUpdate> updates,
      final boolean deferred,
      final DriverInfo driverInfo,
      @Nullable final ProgressTracker tracker) {
    final boolean hasData = data != null && !data.isEmpty();
    final boolean hasUpdates = updates != null && !updates.isEmpty();
    if (!deferred && !hasData && !hasUpdates) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    final AbstractDialect dialect = DialectFactory.resolve(driverInfo);

    if (deferred) {
      sb.append(dialect.beginTransaction());
      sb.append(dialect.disableConstraints());
    }

    if (hasData) {
      generateInsertStatements(sb, data, dialect, tracker);
    }
    generateUpdateStatements(sb, updates, dialect, tracker);

    if (deferred) {
      sb.append(dialect.enableConstraints());
      sb.append(dialect.commitTransaction());
    }

    return sb.toString();
  }

  private static void generateInsertStatements(
      final StringBuilder sb,
      final Map<Table, List<Row>> data,
      final AbstractDialect dialect,
      @Nullable final ProgressTracker tracker) {
    data.forEach(
        (table, rows) -> {
          if (Objects.nonNull(tracker)) {
            tracker.setText2("Writing INSERTs for ".concat(table.name()));
            tracker.advance();
          }
          if (Objects.isNull(rows) || rows.isEmpty()) {
            return;
          }

          final List<String> columnOrder = table.columns().stream().map(Column::name).toList();
          final String tableName = qualified(table.name(), dialect);
          final String columnList =
              columnOrder.stream()
                  .map(col -> qualified(col, dialect))
                  .collect(Collectors.joining(SQL_COMMA_SPACE));

          dialect.appendBatch(sb, tableName, columnList, rows, columnOrder);
        });
  }

  private static void generateUpdateStatements(
      final StringBuilder sb,
      final List<PendingUpdate> updates,
      final AbstractDialect dialect,
      @Nullable final ProgressTracker tracker) {
    if (updates == null || updates.isEmpty()) {
      return;
    }
    updates.forEach(
        update -> {
          if (Objects.nonNull(tracker)) {
            tracker.setText2("Writing deferred UPDATEs for ".concat(update.table()));
            tracker.advance();
          }
          if (update.fkValues().isEmpty() || update.pkValues().isEmpty()) {
            return;
          }
          final String tableName = qualified(update.table(), dialect);

          final String setPart =
              update.fkValues().entrySet().stream()
                  .map(
                      e -> {
                        StringBuilder valSb = new StringBuilder();
                        dialect.formatValue(e.getValue(), valSb);
                        return qualified(e.getKey(), dialect)
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
                        return qualified(e.getKey(), dialect)
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
        });
  }

  private static String qualified(final String identifier, final AbstractDialect dialect) {
    if (Objects.isNull(identifier)) {
      throw new IllegalArgumentException("Identifier cannot be null.");
    }
    return dialect.quote(identifier);
  }
}
