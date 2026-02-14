/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import com.intellij.openapi.progress.ProgressIndicator;
import com.luisppb16.dbseed.ai.OllamaClient;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.db.generator.ConstraintParser.CheckExpression;
import com.luisppb16.dbseed.db.generator.ConstraintParser.MultiColumnConstraint;
import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.math.BigDecimal;
import java.sql.JDBCType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

/** Sophisticated row generation engine for database seeding operations in the DBSeed plugin. */
@Slf4j
public final class RowGenerator {

  private static final int MAX_GENERATE_ATTEMPTS = 100;
  private static final int AI_BATCH_SIZE = 20;
  private static final int AI_MAX_RETRIES = 5;

  private final Table table;
  private final int rowsPerTable;
  private final Set<String> excludedColumns;
  private final List<RepetitionRule> repetitionRules;
  private final Set<String> softDeleteCols;
  private final boolean softDeleteUseSchemaDefault;
  private final String softDeleteValue;
  private final Set<String> aiColumns;
  private final ValueGenerator valueGenerator;
  private final OllamaClient ollamaClient;
  private final String applicationContext;
  private final ProgressIndicator indicator;

  @Getter private final Map<String, ParsedConstraint> constraints;
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
      final Set<UUID> usedUuids,
      final List<String> dictionaryWords,
      final boolean useLatinDictionary,
      final Set<String> softDeleteCols,
      final boolean softDeleteUseSchemaDefault,
      final String softDeleteValue,
      final int numericScale,
      final Set<String> aiColumns,
      final OllamaClient ollamaClient,
      final String applicationContext,
      final ProgressIndicator indicator) {

    this.table = Objects.requireNonNull(table, "Table cannot be null");
    this.rowsPerTable = rowsPerTable;
    this.excludedColumns = Objects.requireNonNullElse(excludedColumns, Set.of());
    this.repetitionRules = Objects.requireNonNullElse(repetitionRules, List.of());
    Objects.requireNonNull(faker, "Faker cannot be null");
    Objects.requireNonNull(usedUuids, "Used UUIDs set cannot be null");
    this.softDeleteCols = Objects.requireNonNullElse(softDeleteCols, Set.of());
    this.softDeleteUseSchemaDefault = softDeleteUseSchemaDefault;
    this.softDeleteValue = softDeleteValue;
    this.aiColumns = Objects.requireNonNullElse(aiColumns, Set.of());
    final List<String> resolvedDictionaryWords =
        Objects.requireNonNullElse(dictionaryWords, List.of());
    this.valueGenerator =
        new ValueGenerator(
            faker, resolvedDictionaryWords, useLatinDictionary, usedUuids, numericScale);
    this.ollamaClient = ollamaClient;
    this.applicationContext = applicationContext;
    this.indicator = indicator;

    final List<CheckExpression> checkExpressions =
        table.checks().stream()
            .filter(c -> Objects.nonNull(c) && !c.isBlank())
            .map(
                c -> {
                  final String noParens = c.replaceAll("[()]+", " ");
                  return new CheckExpression(c, noParens, noParens.toLowerCase(Locale.ROOT));
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
      return List.of();
    }

    processRepetitionRules();
    fillRemainingRows();
    batchGenerateAiValues();

    return rows;
  }

  private void processRepetitionRules() {
    repetitionRules.forEach(rule -> {
      final Map<String, Object> baseValues = new HashMap<>(rule.fixedValues());

      baseValues.entrySet().forEach(entry -> {
        if (entry.getValue() instanceof String strValue) {
          final Column col = table.column(entry.getKey());
          entry.setValue(parseValue(strValue, col));
        }
      });

      rule.randomConstantColumns().forEach(colName -> {
        final Column col = table.column(colName);
        final Object val =
            valueGenerator.generateValue(col, constraints.get(colName), generatedCount.get());
        baseValues.put(colName, val);
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
    });
  }

  private void fillRemainingRows() {
    int attempts = 0;
    while (generatedCount.get() < rowsPerTable && attempts < rowsPerTable * MAX_GENERATE_ATTEMPTS) {
      generateAndValidateRow().ifPresent(row -> {
        rows.add(row);
        generatedCount.incrementAndGet();
      });
      attempts++;
    }
  }

  private Optional<Row> generateAndValidateRow() {
    return generateAndValidateRowWithBase(Map.of());
  }

  private Optional<Row> generateAndValidateRowWithBase(final Map<String, Object> baseValues) {
    final Map<String, Object> values = new LinkedHashMap<>();

    if (Objects.nonNull(baseValues)) {
      values.putAll(baseValues);
    }

    if (!applyMultiColumnConstraints(values)) {
      return Optional.empty();
    }

    generateRemainingColumnValues(values);

    if (!validateMultiColumnConstraintValues(values)
        && !reconcileMultiColumnConstraints(values)) {
      return Optional.empty();
    }

    if (!isPrimaryKeyUnique(values) || !areUniqueKeysUnique(values)) {
      return Optional.empty();
    }

    return Optional.of(new Row(values));
  }

  private boolean validateMultiColumnConstraintValues(final Map<String, Object> values) {
    if (Objects.isNull(multiColumnConstraints) || multiColumnConstraints.isEmpty()) {
      return true;
    }

    return multiColumnConstraints.stream()
        .allMatch(
            mcc ->
                mcc.allowedCombinations().stream()
                    .anyMatch(
                        combo ->
                            combo.entrySet().stream()
                                .allMatch(entry -> matchesExpectedValue(values, entry))));
  }

  private boolean matchesExpectedValue(
      final Map<String, Object> values, final Map.Entry<String, String> entry) {
    final String colName = resolveColumnName(entry.getKey());
    final String expectedVal = entry.getValue();
    final Object actualVal = values.get(colName);
    if (Objects.isNull(actualVal)) {
      return "NULL".equalsIgnoreCase(expectedVal);
    }
    return String.valueOf(actualVal).equals(expectedVal);
  }

  private boolean applyMultiColumnConstraints(final Map<String, Object> values) {
    if (Objects.isNull(multiColumnConstraints) || multiColumnConstraints.isEmpty()) {
      return true;
    }

    for (final MultiColumnConstraint mcc : multiColumnConstraints) {
      final List<Map<String, String>> compatibleCombinations =
          mcc.allowedCombinations().stream()
              .filter(
                  combo ->
                      combo.entrySet().stream()
                          .allMatch(
                              entry -> {
                                final String colName = resolveColumnName(entry.getKey());
                                final String expectedVal = entry.getValue();
                                if (!values.containsKey(colName)) {
                                  return true;
                                }
                                final Object actualVal = values.get(colName);
                                if (Objects.isNull(actualVal)) {
                                  return "NULL".equalsIgnoreCase(expectedVal);
                                }
                                return String.valueOf(actualVal).equals(expectedVal);
                              }))
              .toList();

      if (compatibleCombinations.isEmpty()) {
        return false;
      }

      final Map<String, String> selectedCombination =
          compatibleCombinations.get(
              ThreadLocalRandom.current().nextInt(compatibleCombinations.size()));

      selectedCombination.forEach((key, valStr) -> {
        final String colName = resolveColumnName(key);
        if (!values.containsKey(colName)) {
          final Column col = table.column(colName);
          values.put(colName, parseValue(valStr, col));
        }
      });
    }
    return true;
  }

  private void generateRemainingColumnValues(final Map<String, Object> values) {
    table.columns().forEach(column -> {
      if (!values.containsKey(column.name())) {
        values.put(column.name(), generateColumnValue(column));
      }
    });
  }

  private void batchGenerateAiValues() {
    if (Objects.isNull(ollamaClient) || aiColumns.isEmpty() || rows.isEmpty()) {
      return;
    }

    final int wordCount = DbSeedSettingsState.getInstance().getAiWordCount();

    aiColumns.forEach(colName -> {
      if (Objects.nonNull(indicator) && indicator.isCanceled()) return;

      final Column col = table.column(colName);
      if (Objects.isNull(col) || excludedColumns.contains(colName)) return;

      final String sqlType = getSqlTypeName(col.jdbcType());
      final int totalRows = rows.size();
      final Set<String> seenAiValues = new HashSet<>();

      for (int batchStart = 0; batchStart < totalRows; batchStart += AI_BATCH_SIZE) {
        if (Objects.nonNull(indicator) && indicator.isCanceled()) return;

        final int batchEnd = Math.min(batchStart + AI_BATCH_SIZE, totalRows);
        final int batchCount = batchEnd - batchStart;

        if (Objects.nonNull(indicator)) {
          indicator.setText2(
              String.format(
                  "AI generating %s.%s (%d-%d/%d)",
                  table.name(), colName, batchStart + 1, batchEnd, totalRows));
        }

        final List<String> allValues = new ArrayList<>();
        int retries = 0;

        while (allValues.size() < batchCount && retries < AI_MAX_RETRIES) {
          if (Objects.nonNull(indicator) && indicator.isCanceled()) return;

          final int remaining = batchCount - allValues.size();
          try {
            final List<String> batchValues =
                ollamaClient
                    .generateBatchValues(
                        applicationContext, table.name(), colName, sqlType, wordCount, remaining)
                    .join();

            if (batchValues.isEmpty()) {
              retries++;
              continue;
            }
            boolean addedAny = false;
            for (final String v : batchValues) {
              if (seenAiValues.add(v)) {
                allValues.add(v);
                addedAny = true;
              }
            }
            if (!addedAny) {
              retries++;
            }
          } catch (final Exception ex) {
            log.warn(
                "Batch AI generation failed for {}.{}: {}",
                table.name(),
                colName,
                ex.getMessage());
            retries++;
          }
        }

        for (int i = 0; i < Math.min(allValues.size(), batchCount); i++) {
          final String val = allValues.get(i);
          if (Objects.nonNull(val) && !val.isBlank()) {
            String finalValue = val.trim();
            if (col.length() > 0 && finalValue.length() > col.length()) {
              finalValue = finalValue.substring(0, col.length());
            }
            rows.get(batchStart + i).values().put(colName, finalValue);
          }
        }
      }
    });
  }

  private String getSqlTypeName(final int jdbcType) {
    try {
      return JDBCType.valueOf(jdbcType).getName();
    } catch (final Exception ignored) {
      return "UNKNOWN";
    }
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
    return valueGenerator.generateValue(
        column, constraints.get(column.name()), generatedCount.get());
  }

  private String resolveColumnName(final String constraintColName) {
    final Column col = table.column(constraintColName);
    return Objects.nonNull(col) ? col.name() : constraintColName;
  }

  private Object parseValue(final String value, final Column column) {
    if (Objects.isNull(value) || "NULL".equalsIgnoreCase(value)) return null;
    try {
      return switch (column.jdbcType()) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(value);
        case Types.BIGINT -> Long.parseLong(value);
        case Types.BOOLEAN, Types.BIT -> Boolean.parseBoolean(value);
        case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(value);
        case Types.FLOAT, Types.DOUBLE, Types.REAL -> Double.parseDouble(value);
        default -> value;
      };
    } catch (final Exception ignored) {
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
      final String combination =
          uniqueKeyColumns.stream()
              .map(ukCol -> Objects.toString(values.get(ukCol), "NULL"))
              .collect(Collectors.joining("|"));
      final Set<String> seenCombinations =
          seenUniqueKeyCombinations.computeIfAbsent(
              String.join("__", uniqueKeyColumns), k -> new HashSet<>());
      if (!seenCombinations.add(combination)) {
        return false;
      }
    }
    return true;
  }

  private boolean reconcileMultiColumnConstraints(final Map<String, Object> values) {
    if (Objects.isNull(multiColumnConstraints) || multiColumnConstraints.isEmpty()) {
      return true;
    }

    final Set<String> resolvedColumns = new HashSet<>();

    for (final MultiColumnConstraint mcc : multiColumnConstraints) {
      resolvedColumns.clear();
      mcc.columns().forEach(c -> resolvedColumns.add(resolveColumnName(c)));

      final List<Map<String, String>> compatibleCombinations =
          mcc.allowedCombinations().stream()
              .filter(
                  combo ->
                      combo.entrySet().stream()
                          .allMatch(
                              entry -> {
                                final String colName = resolveColumnName(entry.getKey());
                                if (!resolvedColumns.contains(colName)) return true;
                                final Object actualVal = values.get(colName);
                                return Objects.isNull(actualVal)
                                    || String.valueOf(actualVal).equals(entry.getValue());
                              }))
              .toList();

      if (compatibleCombinations.isEmpty()) {
        if (mcc.allowedCombinations().isEmpty()) {
          return false;
        }
        applyCombinationValues(mcc.allowedCombinations().getFirst(), resolvedColumns, values);
      } else {
        final Map<String, String> selectedCombination =
            compatibleCombinations.get(
                ThreadLocalRandom.current().nextInt(compatibleCombinations.size()));
        selectedCombination.forEach((key, valStr) -> {
          final String colName = resolveColumnName(key);
          if (resolvedColumns.contains(colName) && !values.containsKey(colName)) {
            final Column col = table.column(colName);
            values.put(colName, parseValue(valStr, col));
          }
        });
      }
    }
    return true;
  }

  private void applyCombinationValues(
      final Map<String, String> combination,
      final Set<String> resolvedColumns,
      final Map<String, Object> values) {
    combination.forEach((key, valStr) -> {
      final String colName = resolveColumnName(key);
      if (resolvedColumns.contains(colName)) {
        final Column col = table.column(colName);
        values.put(colName, parseValue(valStr, col));
      }
    });
  }
}
