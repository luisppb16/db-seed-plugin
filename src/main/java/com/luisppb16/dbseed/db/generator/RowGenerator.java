/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.db.generator.ConstraintParser.CheckExpression;
import com.luisppb16.dbseed.db.generator.ConstraintParser.MultiColumnConstraint;
import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.datafaker.Faker;

public final class RowGenerator {

  private static final int MAX_GENERATE_ATTEMPTS = 100;

  private final Table table;
  private final int rowsPerTable;
  private final Set<String> excludedColumns;
  private final List<RepetitionRule> repetitionRules;
  private final Faker faker;
  private final Set<java.util.UUID> usedUuids;
  private final List<String> dictionaryWords;
  private final boolean useLatinDictionary;
  private final Set<String> softDeleteCols;
  private final boolean softDeleteUseSchemaDefault;
  private final String softDeleteValue;
  private final int numericScale;
  private final ValueGenerator valueGenerator;

  private final Map<String, ParsedConstraint> constraints;
  private final Predicate<Column> isFkColumn;
  private final List<List<String>> relevantUniqueKeys;
  private final List<MultiColumnConstraint> multiColumnConstraints;

  private final List<Row> rows = new ArrayList<>();
  private final Set<String> seenPrimaryKeys = new HashSet<>();
  private final Map<String, Set<String>> seenUniqueKeyCombinations = new HashMap<>();
  private final AtomicInteger generatedCount = new AtomicInteger(0);

  public RowGenerator(
      final Table table,
      final int rowsPerTable,
      final Set<String> excludedColumns,
      final List<RepetitionRule> repetitionRules,
      final Faker faker,
      final Set<java.util.UUID> usedUuids,
      final List<String> dictionaryWords,
      final boolean useLatinDictionary,
      final Set<String> softDeleteCols,
      final boolean softDeleteUseSchemaDefault,
      final String softDeleteValue,
      final int numericScale) {
    
    this.table = Objects.requireNonNull(table, "Table cannot be null");
    this.rowsPerTable = rowsPerTable;
    this.excludedColumns = excludedColumns != null ? excludedColumns : Set.of();
    this.repetitionRules = repetitionRules != null ? repetitionRules : Collections.emptyList();
    this.faker = Objects.requireNonNull(faker, "Faker cannot be null");
    this.usedUuids = Objects.requireNonNull(usedUuids, "Used UUIDs set cannot be null");
    this.dictionaryWords = dictionaryWords != null ? dictionaryWords : Collections.emptyList();
    this.useLatinDictionary = useLatinDictionary;
    this.softDeleteCols = softDeleteCols != null ? softDeleteCols : Set.of();
    this.softDeleteUseSchemaDefault = softDeleteUseSchemaDefault;
    this.softDeleteValue = softDeleteValue;
    this.numericScale = numericScale;
    this.valueGenerator = new ValueGenerator(faker, dictionaryWords, useLatinDictionary, usedUuids, numericScale);

    final List<CheckExpression> checkExpressions =
        table.checks().stream()
            .filter(c -> c != null && !c.isBlank())
            .map(
                c -> {
                  final String noParens = c.replaceAll("[()]+", " ");
                  return new CheckExpression(c, noParens, noParens.toLowerCase(java.util.Locale.ROOT));
                })
            .toList();

    this.constraints =
        table.columns().stream()
            .collect(
                Collectors.toMap(
                    Column::name,
                    col -> new ConstraintParser(col.name()).parse(checkExpressions, col.length())));

    final Set<String> fkColumnNames = table.fkColumnNames();
    this.isFkColumn = column -> fkColumnNames.contains(column.name());

    this.relevantUniqueKeys =
        table.uniqueKeys().stream()
            .filter(uk -> !fkColumnNames.containsAll(uk))
            .filter(uk -> table.primaryKey().isEmpty() || !table.primaryKey().equals(uk))
            .toList();

    this.multiColumnConstraints = ConstraintParser.parseMultiColumnConstraints(table.checks());
  }

  public List<Row> generate() {
    if (table.columns().isEmpty()) {
      return Collections.emptyList();
    }

    processRepetitionRules();
    fillRemainingRows();

    return rows;
  }

  public Map<String, ParsedConstraint> getConstraints() {
    return constraints;
  }

  private void processRepetitionRules() {
    for (final RepetitionRule rule : repetitionRules) {
      final Map<String, Object> baseValues = new HashMap<>(rule.fixedValues());

      rule.randomConstantColumns()
          .forEach(
              colName -> {
                final Column col = table.column(colName);
                if (col != null) {
                  final Object val =
                      valueGenerator.generateValue(col, constraints.get(colName), generatedCount.get());
                  baseValues.put(colName, val);
                }
              });

      for (int i = 0; i < rule.count(); i++) {
        int attempts = 0;
        while (attempts < MAX_GENERATE_ATTEMPTS) {
          final Optional<Row> generatedRow = generateAndValidateRowWithBase(baseValues);

          if (generatedRow.isPresent()) {
            rows.add(generatedRow.get());
            generatedCount.incrementAndGet();
            break;
          }
          attempts++;
        }
      }
    }
  }

  private void fillRemainingRows() {
    int attempts = 0;
    while (generatedCount.get() < rowsPerTable
        && attempts < rowsPerTable * MAX_GENERATE_ATTEMPTS) {

      final Optional<Row> generatedRow = generateAndValidateRow();

      generatedRow.ifPresent(
          row -> {
            rows.add(row);
            generatedCount.incrementAndGet();
          });
      attempts++;
    }
  }

  private Optional<Row> generateAndValidateRow() {
    return generateAndValidateRowWithBase(Collections.emptyMap());
  }

  private Optional<Row> generateAndValidateRowWithBase(final Map<String, Object> baseValues) {
    final Map<String, Object> values = new LinkedHashMap<>();

    if (baseValues != null) {
      values.putAll(baseValues);
    }

    if (!applyMultiColumnConstraints(values)) {
      return Optional.empty();
    }

    generateRemainingColumnValues(values);

    if (!isPrimaryKeyUnique(values)) {
      return Optional.empty();
    }

    if (!areUniqueKeysUnique(values)) {
      return Optional.empty();
    }

    return Optional.of(new Row(values));
  }

  private boolean applyMultiColumnConstraints(final Map<String, Object> values) {
    if (multiColumnConstraints == null) {
      return true;
    }

    for (final MultiColumnConstraint mcc : multiColumnConstraints) {
      final List<Map<String, String>> filtered =
          mcc.allowedCombinations().stream()
              .filter(
                  combo ->
                      combo.entrySet().stream()
                          .allMatch(
                              entry ->
                                  !values.containsKey(entry.getKey())
                                      || String.valueOf(values.get(entry.getKey()))
                                          .equals(entry.getValue())))
              .toList();

      if (filtered.isEmpty()) {
        return false;
      }

      final Map<String, String> chosen =
          filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));

      chosen.forEach(
          (colName, valStr) -> {
            final Column col = table.column(colName);
            if (col != null) {
              values.put(colName, parseValue(valStr, col));
            }
          });
    }
    return true;
  }

  private void generateRemainingColumnValues(final Map<String, Object> values) {
    table.columns()
        .forEach(
            column -> {
              if (!values.containsKey(column.name())) {
                final Object value = generateColumnValue(column);
                values.put(column.name(), value);
              }
            });
  }

  private Object generateColumnValue(final Column column) {
    if (excludedColumns.contains(column.name())) {
      return null;
    }
    if (isFkColumn.test(column) && !column.primaryKey()) {
      return null;
    }

    if (softDeleteCols.contains(column.name())) {
      return valueGenerator.generateSoftDeleteValue(
          column, softDeleteUseSchemaDefault, softDeleteValue);
    }

    return valueGenerator.generateValue(column, constraints.get(column.name()), generatedCount.get());
  }

  private Object parseValue(final String value, final Column column) {
    if (value == null || "NULL".equalsIgnoreCase(value)) return null;
    try {
      return switch (column.jdbcType()) {
        case java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT ->
            Integer.parseInt(value);
        case java.sql.Types.BIGINT -> Long.parseLong(value);
        case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> Boolean.parseBoolean(value);
        case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> new java.math.BigDecimal(value);
        case java.sql.Types.FLOAT, java.sql.Types.DOUBLE, java.sql.Types.REAL ->
            Double.parseDouble(value);
        default -> value;
      };
    } catch (final Exception e) {
      return null;
    }
  }

  private boolean isPrimaryKeyUnique(final Map<String, Object> values) {
    if (table.primaryKey().isEmpty()) {
      return true;
    }
    final String pkKey =
        table.primaryKey().stream()
            .map(pkCol -> Objects.toString(values.get(pkCol), "NULL"))
            .collect(Collectors.joining("|"));
    return seenPrimaryKeys.add(pkKey);
  }

  private boolean areUniqueKeysUnique(final Map<String, Object> values) {
    for (final List<String> uniqueKeyColumns : relevantUniqueKeys) {
      final String uniqueKeyCombination =
          uniqueKeyColumns.stream()
              .map(ukCol -> Objects.toString(values.get(ukCol), "NULL"))
              .collect(Collectors.joining("|"));
      final Set<String> seenCombinations =
          seenUniqueKeyCombinations.computeIfAbsent(
              String.join("__", uniqueKeyColumns), k -> new HashSet<>());
      if (!seenCombinations.add(uniqueKeyCombination)) {
        return false;
      }
    }
    return true;
  }
}
