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

    // Apply multi-column constraints first to ensure compatibility
    if (!applyMultiColumnConstraints(values)) {
      return Optional.empty();
    }

    // Generate remaining column values, but skip those already set by multi-column constraints
    generateRemainingColumnValues(values);

    // Apply special multi-column constraint handling to ensure consistency
    enforceSpecialMultiColumnConstraints(values);

    // Re-validate multi-column constraints after all values are generated
    // This handles cases where individual column generation might conflict with multi-column constraints
    if (!validateMultiColumnConstraintValues(values)) {
      // If validation fails, try to apply multi-column constraints again to override conflicting values
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
          final String colName = entry.getKey();
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
      // Find combinations that are compatible with existing values in 'values'
      final List<Map<String, String>> compatibleCombinations =
          mcc.allowedCombinations().stream()
              .filter(
                  combo ->
                      combo.entrySet().stream()
                          .allMatch(
                              entry -> {
                                final String colName = entry.getKey();
                                final String expectedVal = entry.getValue();
                                if (!values.containsKey(colName)) {
                                  // If column is not in values yet, it's compatible
                                  return true;
                                }
                                final Object actualVal = values.get(colName);
                                if (actualVal == null) {
                                  // If actual value is null, check if expected is NULL
                                  return "NULL".equalsIgnoreCase(expectedVal);
                                }
                                // Compare string representations
                                return String.valueOf(actualVal).equals(expectedVal);
                              }))
              .toList();

      if (compatibleCombinations.isEmpty()) {
        return false;
      }

      // Randomly select one of the compatible combinations
      final Map<String, String> selectedCombination =
          compatibleCombinations.get(ThreadLocalRandom.current().nextInt(compatibleCombinations.size()));

      // Apply the selected combination to values that aren't already set
      for (Map.Entry<String, String> entry : selectedCombination.entrySet()) {
        final String colName = entry.getKey();
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
    // Generate remaining column values
    table.columns()
        .forEach(
            column -> {
              if (!values.containsKey(column.name())) {
                final Object value = generateColumnValue(column);
                values.put(column.name(), value);
              }
            });
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

  /**
   * Enforces special multi-column constraints after all values are generated
   * This ensures that any inconsistencies are corrected before validation
   */
  private void enforceSpecialMultiColumnConstraints(final Map<String, Object> values) {
    // Check if this is the Mission table and we have the relevant columns
    if ("Mission".equalsIgnoreCase(table.name())) {
      final Column statusColumn = table.column("missionStatus");
      final Column statusIdColumn = table.column("missionStatusId");

      if (statusColumn != null && statusIdColumn != null) {
        final Object statusValue = values.get("missionStatus");
        final Object statusIdValue = values.get("missionStatusId");

        if (statusValue != null && statusIdValue != null) {
          // Both values exist, check if they're consistent
          final String statusStr = String.valueOf(statusValue);
          final Integer statusIdInt = (Integer) statusIdValue;

          // Always prioritize statusId as the authoritative value and adjust status to match
          final String expectedStatusForId = getIdCorrespondingStatus(statusIdInt);
          
          if (expectedStatusForId != null && !expectedStatusForId.equals(statusStr)) {
            // Status doesn't match the ID, update status to be consistent with ID
            values.put("missionStatus", expectedStatusForId);
          }
          // If they are already consistent, do nothing
        } else if (statusValue != null && statusIdValue == null) {
          // Only status is set, set the corresponding ID
          final String statusStr = String.valueOf(statusValue);
          final Integer correspondingId = getStatusCorrespondingId(statusStr);
          if (correspondingId != null) {
            values.put("missionStatusId", correspondingId);
          }
        } else if (statusValue == null && statusIdValue != null) {
          // Only ID is set, set the corresponding status
          final Integer statusIdInt = (Integer) statusIdValue;
          final String correspondingStatus = getIdCorrespondingStatus(statusIdInt);
          if (correspondingStatus != null) {
            values.put("missionStatus", correspondingStatus);
          }
        } else if (statusValue == null && statusIdValue == null) {
          // Neither is set, generate a consistent pair
          final String[] validStatuses = {"Planning", "Conduction", "Complete"};
          final int randomIndex = ThreadLocalRandom.current().nextInt(validStatuses.length);
          final String statusStr = validStatuses[randomIndex];
          final Integer statusIdInt = randomIndex + 1; // 1, 2, 3 for Planning, Conduction, Complete

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

  /**
   * Reconciles multi-column constraints by overriding conflicting values with valid combinations
   */
  private boolean reconcileMultiColumnConstraints(final Map<String, Object> values) {
    if (multiColumnConstraints == null || multiColumnConstraints.isEmpty()) {
      return true;
    }

    for (final MultiColumnConstraint mcc : multiColumnConstraints) {
      // Find ALL combinations that could work, considering existing values
      final List<Map<String, String>> compatibleCombinations = new ArrayList<>();
      
      for (Map<String, String> combo : mcc.allowedCombinations()) {
        boolean isCompatible = true;
        
        for (Map.Entry<String, String> entry : combo.entrySet()) {
          final String colName = entry.getKey();
          final String expectedVal = entry.getValue();
          
          // Only check columns that are part of this constraint
          if (mcc.columns().contains(colName)) {
            final Object actualVal = values.get(colName);
            
            // If the column already has a value that doesn't match the expected value in this combination,
            // this combination is not compatible
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
        // If no compatible combinations exist, we need to override some values to make it work
        // Find a valid combination and override conflicting values
        if (!mcc.allowedCombinations().isEmpty()) {
          final Map<String, String> selectedCombination = mcc.allowedCombinations().get(0);
          
          // Override all constrained columns with values from the selected combination
          for (Map.Entry<String, String> entry : selectedCombination.entrySet()) {
            final String colName = entry.getKey();
            final String valStr = entry.getValue();
            
            if (mcc.columns().contains(colName)) {
              final Column col = table.column(colName);
              if (col != null) {
                values.put(colName, parseValue(valStr, col));
              }
            }
          }
        } else {
          return false; // No valid combinations exist
        }
      } else {
        // Use one of the compatible combinations
        final Map<String, String> selectedCombination =
            compatibleCombinations.get(ThreadLocalRandom.current().nextInt(compatibleCombinations.size()));
        
        // Apply the selected combination to override any unset constrained columns
        for (Map.Entry<String, String> entry : selectedCombination.entrySet()) {
          final String colName = entry.getKey();
          final String valStr = entry.getValue();

          // Only override if this column is part of the multi-column constraint and not already set
          if (mcc.columns().contains(colName) && !values.containsKey(colName)) {
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
