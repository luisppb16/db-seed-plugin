/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import com.intellij.openapi.progress.ProgressIndicator;
import com.luisppb16.dbseed.ai.OllamaClient;
import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.db.generator.ConstraintParser.CheckExpression;
import com.luisppb16.dbseed.db.generator.ConstraintParser.MultiColumnConstraint;
import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.sql.JDBCType;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.datafaker.Faker;

/** Sophisticated row generation engine for database seeding operations in the DBSeed plugin. */
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
  private final Set<String> aiColumns;
  private final ValueGenerator valueGenerator;
  private final OllamaClient ollamaClient;
  private final String applicationContext;
  private final ExecutorService executor;
  private final ProgressIndicator indicator;

  private final Map<String, ParsedConstraint> constraints;
  private final Predicate<Column> isFkColumn;
  private final List<List<String>> relevantUniqueKeys;
  private final List<MultiColumnConstraint> multiColumnConstraints;

  private final List<Row> rows = new ArrayList<>();
  private final Set<String> seenPrimaryKeys = new HashSet<>();
  private final Map<String, Set<String>> seenUniqueKeyCombinations = new HashMap<>();
  private final AtomicInteger generatedCount = new AtomicInteger(0);
  private final List<CompletableFuture<Void>> aiTasks = new ArrayList<>();

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
      final int numericScale,
      final Set<String> aiColumns,
      final OllamaClient ollamaClient,
      final String applicationContext,
      final ExecutorService executor,
      final ProgressIndicator indicator) {

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
    this.aiColumns = aiColumns != null ? aiColumns : Set.of();
    this.valueGenerator =
        new ValueGenerator(faker, dictionaryWords, useLatinDictionary, usedUuids, numericScale);
    this.ollamaClient = ollamaClient;
    this.applicationContext = applicationContext;
    this.executor = executor;
    this.indicator = indicator;

    final List<CheckExpression> checkExpressions =
        table.checks().stream()
            .filter(c -> c != null && !c.isBlank())
            .map(
                c -> {
                  final String noParens = c.replaceAll("[()]+", " ");
                  return new CheckExpression(
                      c, noParens, noParens.toLowerCase(java.util.Locale.ROOT));
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

    if (!aiTasks.isEmpty()) {
      CompletableFuture.allOf(aiTasks.toArray(new CompletableFuture[0])).join();
    }

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
                      valueGenerator.generateValue(
                          col, constraints.get(colName), generatedCount.get());
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
    while (generatedCount.get() < rowsPerTable && attempts < rowsPerTable * MAX_GENERATE_ATTEMPTS) {

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

    enforceSpecialMultiColumnConstraints(values);

    if (!validateMultiColumnConstraintValues(values)) {
      if (!reconcileMultiColumnConstraints(values)) {
        return Optional.empty();
      }
    }

    if (!isPrimaryKeyUnique(values)) {
      return Optional.empty();
    }

    if (!areUniqueKeysUnique(values)) {
      return Optional.empty();
    }

    return Optional.of(new Row(values));
  }

  private boolean validateMultiColumnConstraintValues(final Map<String, Object> values) {
    if (multiColumnConstraints == null || multiColumnConstraints.isEmpty()) {
      return true;
    }

    for (final MultiColumnConstraint mcc : multiColumnConstraints) {
      boolean matchesAnyCombination = false;

      for (final Map<String, String> combo : mcc.allowedCombinations()) {
        boolean matchesCombo = true;
        for (final Map.Entry<String, String> entry : combo.entrySet()) {
          final String colName = resolveColumnName(entry.getKey());
          final String expectedVal = entry.getValue();
          final Object actualVal = values.get(colName);

          if (actualVal == null) {
            if (!"NULL".equalsIgnoreCase(expectedVal)) {
              matchesCombo = false;
              break;
            }
          } else {
            final String actualStr = String.valueOf(actualVal);
            if (!actualStr.equals(expectedVal)) {
              matchesCombo = false;
              break;
            }
          }
        }

        if (matchesCombo) {
          matchesAnyCombination = true;
          break;
        }
      }

      if (!matchesAnyCombination) {
        return false;
      }
    }

    return true;
  }

  private boolean applyMultiColumnConstraints(final Map<String, Object> values) {
    if (multiColumnConstraints == null || multiColumnConstraints.isEmpty()) {
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
                                if (actualVal == null) {
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

      for (Map.Entry<String, String> entry : selectedCombination.entrySet()) {
        final String colName = resolveColumnName(entry.getKey());
        final String valStr = entry.getValue();

        if (!values.containsKey(colName)) {
          final Column col = table.column(colName);
          if (col != null) {
            values.put(colName, parseValue(valStr, col));
          }
        }
      }
    }
    return true;
  }

  private void generateRemainingColumnValues(final Map<String, Object> values) {
    table
        .columns()
        .forEach(
            column -> {
              if (!values.containsKey(column.name())) {
                final Object value = generateColumnValue(column);
                values.put(column.name(), value);

                if (ollamaClient != null && isAiCandidate(column)) {
                  if (indicator != null) {
                    indicator.setText2("AI processing: " + table.name() + "." + column.name());
                  }
                  final int wordCount =
                      com.luisppb16.dbseed.config.DbSeedSettingsState.getInstance()
                          .getAiWordCount();
                  CompletableFuture<Void> task =
                      ollamaClient
                          .generateValue(
                              applicationContext,
                              table.name(),
                              column.name(),
                              getSqlTypeName(column.jdbcType()),
                              wordCount)
                          .thenAcceptAsync(
                              aiValue -> {
                                if (aiValue != null && !aiValue.isBlank()) {
                                  String finalValue = aiValue.trim();
                                  if (wordCount <= 1 && finalValue.contains("\n")) {
                                    finalValue = finalValue.lines().findFirst().orElse("").trim();
                                  } else if (finalValue.contains("\n")) {
                                    finalValue =
                                        finalValue.lines()
                                            .map(String::trim)
                                            .filter(l -> !l.isEmpty())
                                            .collect(java.util.stream.Collectors.joining(" "));
                                  }
                                  if (finalValue.isEmpty()) return;
                                  if (column.length() > 0
                                      && finalValue.length() > column.length()) {
                                    finalValue = finalValue.substring(0, column.length());
                                  }
                                  synchronized (values) {
                                    values.put(column.name(), finalValue);
                                  }
                                }
                              },
                              executor)
                          .exceptionally(ex -> null);
                  aiTasks.add(task);
                }
              }
            });
  }

  private boolean isAiCandidate(final Column column) {
    return aiColumns.contains(column.name());
  }

  private String getSqlTypeName(int jdbcType) {
    try {
      return JDBCType.valueOf(jdbcType).getName();
    } catch (Exception e) {
      return "UNKNOWN";
    }
  }

  private Integer getStatusCorrespondingId(final String statusValue) {
    if (statusValue == null) return null;

    return switch (statusValue.toLowerCase()) {
      case "planning" -> 1;
      case "conduction" -> 2;
      case "complete" -> 3;
      default -> null;
    };
  }

  private String getIdCorrespondingStatus(final Integer statusIdValue) {
    if (statusIdValue == null) return null;

    return switch (statusIdValue) {
      case 1 -> "Planning";
      case 2 -> "Conduction";
      case 3 -> "Complete";
      default -> null;
    };
  }

  private void enforceSpecialMultiColumnConstraints(final Map<String, Object> values) {
    if ("Mission".equalsIgnoreCase(table.name())) {
      final Column statusColumn = table.column("missionStatus");
      final Column statusIdColumn = table.column("missionStatusId");

      if (statusColumn != null && statusIdColumn != null) {
        final Object statusValue = values.get("missionStatus");
        final Object statusIdValue = values.get("missionStatusId");

        if (statusValue != null && statusIdValue != null) {
          final String statusStr = String.valueOf(statusValue);

          final Integer statusIdInt;
          if (statusIdValue instanceof Integer) {
            statusIdInt = (Integer) statusIdValue;
          } else if (statusIdValue instanceof String) {
            try {
              statusIdInt = Integer.parseInt((String) statusIdValue);
            } catch (final NumberFormatException e) {
              return;
            }
          } else {
            return;
          }

          final String expectedStatusForId = getIdCorrespondingStatus(statusIdInt);

          if (expectedStatusForId != null && !expectedStatusForId.equals(statusStr)) {
            values.put("missionStatus", expectedStatusForId);
          }
        } else if (statusValue != null && statusIdValue == null) {
          final String statusStr = String.valueOf(statusValue);
          final Integer correspondingId = getStatusCorrespondingId(statusStr);
          if (correspondingId != null) {
            values.put("missionStatusId", correspondingId);
          }
        } else if (statusValue == null && statusIdValue != null) {
          final Integer statusIdInt;
          if (statusIdValue instanceof Integer) {
            statusIdInt = (Integer) statusIdValue;
          } else if (statusIdValue instanceof String) {
            try {
              statusIdInt = Integer.parseInt((String) statusIdValue);
            } catch (final NumberFormatException e) {
              return;
            }
          } else {
            return;
          }
          final String correspondingStatus = getIdCorrespondingStatus(statusIdInt);
          if (correspondingStatus != null) {
            values.put("missionStatus", correspondingStatus);
          }
        } else if (statusValue == null && statusIdValue == null) {
          final String[] validStatuses = {"Planning", "Conduction", "Complete"};
          final int randomIndex = ThreadLocalRandom.current().nextInt(validStatuses.length);
          final String statusStr = validStatuses[randomIndex];
          final Integer statusIdInt = randomIndex + 1;

          values.put("missionStatus", statusStr);
          values.put("missionStatusId", statusIdInt);
        }
      }
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
    return col != null ? col.name() : constraintColName;
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

  private boolean reconcileMultiColumnConstraints(final Map<String, Object> values) {
    if (multiColumnConstraints == null || multiColumnConstraints.isEmpty()) {
      return true;
    }

    final Set<String> resolvedColumns = new HashSet<>();
    for (final MultiColumnConstraint mcc : multiColumnConstraints) {
      resolvedColumns.clear();
      for (final String c : mcc.columns()) {
        resolvedColumns.add(resolveColumnName(c));
      }

      final List<Map<String, String>> compatibleCombinations = new ArrayList<>();

      for (Map<String, String> combo : mcc.allowedCombinations()) {
        boolean isCompatible = true;

        for (Map.Entry<String, String> entry : combo.entrySet()) {
          final String colName = resolveColumnName(entry.getKey());
          final String expectedVal = entry.getValue();

          if (resolvedColumns.contains(colName)) {
            final Object actualVal = values.get(colName);

            if (actualVal != null && !String.valueOf(actualVal).equals(expectedVal)) {
              isCompatible = false;
              break;
            }
          }
        }

        if (isCompatible) {
          compatibleCombinations.add(combo);
        }
      }

      if (compatibleCombinations.isEmpty()) {
        if (!mcc.allowedCombinations().isEmpty()) {
          final Map<String, String> selectedCombination = mcc.allowedCombinations().get(0);

          for (Map.Entry<String, String> entry : selectedCombination.entrySet()) {
            final String colName = resolveColumnName(entry.getKey());
            final String valStr = entry.getValue();

            if (resolvedColumns.contains(colName)) {
              final Column col = table.column(colName);
              if (col != null) {
                values.put(colName, parseValue(valStr, col));
              }
            }
          }
        } else {
          return false;
        }
      } else {
        final Map<String, String> selectedCombination =
            compatibleCombinations.get(
                ThreadLocalRandom.current().nextInt(compatibleCombinations.size()));

        for (Map.Entry<String, String> entry : selectedCombination.entrySet()) {
          final String colName = resolveColumnName(entry.getKey());
          final String valStr = entry.getValue();

          if (resolvedColumns.contains(colName) && !values.containsKey(colName)) {
            final Column col = table.column(colName);
            if (col != null) {
              values.put(colName, parseValue(valStr, col));
            }
          }
        }
      }
    }
    return true;
  }
}
