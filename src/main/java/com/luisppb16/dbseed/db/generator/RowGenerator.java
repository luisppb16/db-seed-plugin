/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.generator;

import com.luisppb16.dbseed.ai.AiClient;
import com.luisppb16.dbseed.db.ProgressTracker;
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
import java.util.Arrays;
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
import java.util.stream.IntStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

/**
 * Sophisticated row generation engine for database seeding operations in the DBSeed plugin.
 *
 * <p>This class orchestrates the generation of synthetic data rows for database tables,
 * implementing complex algorithms that respect schema constraints, foreign key relationships, and
 * user-defined rules. It coordinates multiple data generation strategies including dictionary-based
 * content, AI-powered generation, constraint-aware value creation, and repetition rules for
 * consistent data patterns. The engine handles both single-table and multi-table scenarios with
 * proper dependency resolution.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Generating realistic data rows that comply with database schema constraints
 *   <li>Coordinating AI-powered content generation for specified columns
 *   <li>Applying repetition rules for controlled data pattern duplication
 *   <li>Managing soft-delete column configurations and default values
 *   <li>Ensuring uniqueness constraints are respected across generated data
 *   <li>Handling excluded columns and custom value overrides
 *   <li>Integrating with progress tracking for user feedback during generation
 *   <li>Supporting concurrent AI generation with bounded parallelism
 * </ul>
 *
 * <p>The implementation uses advanced data structures to track constraint states, unique value
 * combinations, and generation progress. It employs sophisticated caching mechanisms for
 * multi-column constraints and implements retry logic for constraint-bound value generation. The
 * class supports pluggable value generation strategies and integrates seamlessly with the broader
 * data generation pipeline.
 *
 * <p>Performance optimizations include batched AI requests, lazy constraint parsing, and efficient
 * data structures for large-scale data generation. The engine handles complex scenarios such as
 * circular foreign key dependencies, multi-column unique constraints, and user-defined repetition
 * patterns. Thread safety is maintained through careful coordination of concurrent operations and
 * immutable data structures where possible.
 */
@Slf4j
public final class RowGenerator {

  private static final int MAX_GENERATE_ATTEMPTS = 100;
  private static final int AI_BATCH_SIZE = 50;
  private static final int AI_MAX_RETRIES = 5;
  private static final double AI_OVER_REQUEST_FACTOR = 1.2;
  private static final int AI_RECYCLE_THRESHOLD = 5;

  private final Table table;
  private final int rowsPerTable;
  private final Set<String> excludedColumns;
  private final List<RepetitionRule> repetitionRules;
  private final Set<String> softDeleteCols;
  private final boolean softDeleteUseSchemaDefault;
  private final String softDeleteValue;
  private final Set<String> aiColumns;
  private final int aiWordCount;
  private final ValueGenerator valueGenerator;
  private final AiClient aiClient;
  private final String applicationContext;
  private final ProgressTracker tracker;
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
      final int aiWordCount,
      final AiClient aiClient,
      final String applicationContext,
      final ProgressTracker tracker) {

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
    this.aiWordCount = Math.max(aiWordCount, 1);
    final List<String> resolvedDictionaryWords =
        Objects.requireNonNullElse(dictionaryWords, List.of());
    this.valueGenerator =
        new ValueGenerator(
            faker, resolvedDictionaryWords, useLatinDictionary, usedUuids, numericScale);
    this.aiClient = aiClient;
    this.applicationContext = applicationContext;
    this.tracker = tracker;

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
    // AI values are generated in a separate phase by DataGenerator
    // to allow cross-table parallelism.

    return rows;
  }

  /** Returns true if this generator has AI columns that require generation. */
  public boolean hasAiColumns() {
    return Objects.nonNull(aiClient) && !aiColumns.isEmpty() && !rows.isEmpty();
  }

  /**
   * Returns the filtered list of AI columns valid for generation (non-null, non-excluded). Used by
   * DataGenerator to flatten (table, column) pairs for the single AI executor.
   */
  public List<Column> getValidAiColumns() {
    if (Objects.isNull(aiClient) || aiColumns.isEmpty() || rows.isEmpty()) {
      return List.of();
    }

    final Set<String> behaviorColumns = new HashSet<>();
    for (final RepetitionRule rule : repetitionRules) {
      if (rule.fixedValues() != null) behaviorColumns.addAll(rule.fixedValues().keySet());
      if (rule.randomConstantColumns() != null)
        behaviorColumns.addAll(rule.randomConstantColumns());
      if (rule.regexPatterns() != null) behaviorColumns.addAll(rule.regexPatterns().keySet());
    }

    return aiColumns.stream()
        .map(table::column)
        .filter(Objects::nonNull)
        .filter(col -> !excludedColumns.contains(col.name()))
        .filter(col -> !behaviorColumns.contains(col.name()))
        .toList();
  }

  /**
   * Generates AI values for a single column across all rows. Called externally by DataGenerator
   * from the flat AI executor pool.
   */
  public void generateAiValuesForColumn(final Column col) {
    if (Objects.isNull(aiClient) || rows.isEmpty()) return;
    final int totalRows = rows.size();
    generateAiValuesForColumnInternal(col, aiWordCount, totalRows);
  }

  private void processRepetitionRules() {
    repetitionRules.forEach(
        rule -> {
          final Map<String, Object> baseValues = new HashMap<>(rule.fixedValues());

          baseValues.putAll(buildRegexBasedFixedValues(rule.regexPatterns()));

          baseValues
              .entrySet()
              .forEach(
                  entry -> {
                    if (entry.getValue() instanceof String strValue) {
                      final Column col = table.column(entry.getKey());
                      entry.setValue(parseValue(strValue, col));
                    }
                  });

          rule.randomConstantColumns()
              .forEach(
                  colName -> {
                    final Column col = table.column(colName);
                    final Object val =
                        valueGenerator.generateValue(
                            col, constraints.get(colName), generatedCount.get());
                    baseValues.put(colName, val);
                  });

          IntStream.range(0, rule.count())
              .forEach(
                  i -> {
                    int attempts = 0;
                    while (attempts < MAX_GENERATE_ATTEMPTS) {
                      final Optional<Row> generatedRow = generateAndValidateRowWithBase(baseValues);
                      if (generatedRow.isPresent()) {
                        rows.add(generatedRow.get());
                        final int count = generatedCount.incrementAndGet();
                        tracker.advance(); // 1 work unit per row generated
                        if (count % 50 == 0) {
                          tracker.setText2(
                              "Row " + count + "/" + rowsPerTable + " for " + table.name());
                        }
                        break;
                      }
                      attempts++;
                    }
                  });
        });
  }

  private Map<String, Object> buildRegexBasedFixedValues(final Map<String, String> regexPatterns) {
    if (Objects.isNull(regexPatterns) || regexPatterns.isEmpty()) {
      return Map.of();
    }

    final Map<String, Object> generatedValues = new HashMap<>();
    regexPatterns.forEach(
        (columnName, regexPattern) -> {
          final Column column = table.column(columnName);
          if (Objects.isNull(column)
              || Objects.isNull(regexPattern)
              || regexPattern.isBlank()
              || !supportsRegexGeneration(column)) {
            return;
          }

          final String generated =
              valueGenerator.generateStringFromRegex(
                  regexPattern, column.length() > 0 ? column.length() : null, column.jdbcType());
          if (Objects.nonNull(generated)) {
            generatedValues.put(columnName, generated);
          }
        });

    return generatedValues;
  }

  private boolean supportsRegexGeneration(final Column column) {
    return switch (column.jdbcType()) {
      case Types.CHAR,
          Types.VARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGVARCHAR,
          Types.LONGNVARCHAR,
          Types.CLOB,
          Types.NCLOB ->
          true;
      default -> false;
    };
  }

  private void fillRemainingRows() {
    final int maxAttempts = rowsPerTable * MAX_GENERATE_ATTEMPTS;
    IntStream.range(0, maxAttempts)
        .takeWhile(i -> generatedCount.get() < rowsPerTable)
        .forEach(
            i -> {
              generateAndValidateRow()
                  .ifPresent(
                      row -> {
                        rows.add(row);
                        final int count = generatedCount.incrementAndGet();
                        tracker.advance(); // 1 work unit per row generated
                        if (count % 50 == 0) {
                          tracker.setText2(
                              "Row " + count + "/" + rowsPerTable + " for " + table.name());
                        }
                      });
            });
    if (generatedCount.get() < rowsPerTable) {
      log.warn(
          "Could only generate {}/{} rows for table '{}' due to constraint restrictions",
          generatedCount.get(),
          rowsPerTable,
          table.name());
    }
  }

  private Optional<Row> generateAndValidateRow() {
    return generateAndValidateRowWithBase(Map.of());
  }

  private Optional<Row> generateAndValidateRowWithBase(final Map<String, Object> baseValues) {
    final Map<String, Object> values = new LinkedHashMap<>();
    final Set<String> explicitColumns = new HashSet<>();

    if (Objects.nonNull(baseValues)) {
      values.putAll(baseValues);
      explicitColumns.addAll(baseValues.keySet());
    }

    if (!applyMultiColumnConstraints(values)) {
      return Optional.empty();
    }

    generateRemainingColumnValues(values);

    if (!validateMultiColumnConstraintValues(values) && !reconcileMultiColumnConstraints(values)) {
      return Optional.empty();
    }

    if (!isPrimaryKeyUnique(values) || !areUniqueKeysUnique(values)) {
      return Optional.empty();
    }

    return Optional.of(new Row(values, explicitColumns));
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
    return Objects.isNull(actualVal)
        ? "NULL".equalsIgnoreCase(expectedVal)
        : String.valueOf(actualVal).equals(expectedVal);
  }

  private boolean applyMultiColumnConstraints(final Map<String, Object> values) {
    if (Objects.isNull(multiColumnConstraints) || multiColumnConstraints.isEmpty()) {
      return true;
    }

    multiColumnConstraints.stream()
        .forEach(
            mcc -> {
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
                                        return Objects.isNull(actualVal)
                                            ? "NULL".equalsIgnoreCase(expectedVal)
                                            : String.valueOf(actualVal).equals(expectedVal);
                                      }))
                      .toList();

              if (compatibleCombinations.isEmpty()) {
                return;
              }

              final Map<String, String> selectedCombination =
                  compatibleCombinations.get(
                      ThreadLocalRandom.current().nextInt(compatibleCombinations.size()));

              selectedCombination.entrySet().stream()
                  .forEach(
                      entry -> {
                        final String colName = resolveColumnName(entry.getKey());
                        if (!values.containsKey(colName)) {
                          final Column col = table.column(colName);
                          values.put(colName, parseValue(entry.getValue(), col));
                        }
                      });
            });
    return true;
  }

  private void generateRemainingColumnValues(final Map<String, Object> values) {
    table
        .columns()
        .forEach(
            column -> {
              if (!values.containsKey(column.name())) {
                values.put(column.name(), generateColumnValue(column));
              }
            });
  }

  private void generateAiValuesForColumnInternal(
      final Column col, final int wordCount, final int totalRows) {
    final String colName = col.name();
    final String sqlType = getSqlTypeName(col);
    final boolean isArray = isArrayType(col);
    final boolean columnUnique = isColumnUnique(col);

    // For UNIQUE columns, accumulate seen values across batches to avoid duplicates.
    // For non-UNIQUE columns, only dedup intra-batch (cleared per iteration).
    final Set<String> seenAiValues = new HashSet<>();

    final int totalBatches = (totalRows + AI_BATCH_SIZE - 1) / AI_BATCH_SIZE;

    IntStream.range(0, totalBatches)
        .forEach(
            b -> {
              if (tracker.isCanceled()) return;

              // Improvement #3: relax dedup for non-UNIQUE columns — clear per batch
              if (!columnUnique) {
                seenAiValues.clear();
              }

              final int batchStart = b * AI_BATCH_SIZE;
              final int batchEnd = Math.min(batchStart + AI_BATCH_SIZE, totalRows);
              final int batchCount = batchEnd - batchStart;

              tracker.setText2(
                  "AI generating "
                      .concat(table.name())
                      .concat(".")
                      .concat(colName)
                      .concat(" (")
                      .concat(String.valueOf(batchStart + 1))
                      .concat("-")
                      .concat(String.valueOf(batchEnd))
                      .concat("/")
                      .concat(String.valueOf(totalRows))
                      .concat(")"));

              // Improvement #2: over-request to absorb dedup losses
              final int requestCount = (int) Math.ceil(batchCount * AI_OVER_REQUEST_FACTOR);

              final List<String> allValues = new ArrayList<>();
              int retries = 0;

              try {
                final List<String> batchValues =
                    aiClient
                        .generateBatchValues(
                            applicationContext,
                            table.name(),
                            colName,
                            sqlType,
                            wordCount,
                            requestCount)
                        .join();
                batchValues.stream().filter(v -> seenAiValues.add(v)).forEach(allValues::add);
              } catch (final Exception ex) {
                log.warn(
                    "Batch AI generation failed for {}.{}: {}",
                    table.name(),
                    colName,
                    ex.getMessage());
                retries++;
              }

              // Improvement #2: recycle existing values if deficit is small and column is not
              // UNIQUE
              final int deficit = batchCount - allValues.size();
              if (deficit > 0
                  && deficit <= AI_RECYCLE_THRESHOLD
                  && !columnUnique
                  && !allValues.isEmpty()) {
                IntStream.range(0, deficit)
                    .forEach(i -> allValues.add(allValues.get(i % allValues.size())));
              } else {
                // Full retry loop only when deficit is large or column requires uniqueness
                while (allValues.size() < batchCount && retries < AI_MAX_RETRIES) {
                  if (tracker.isCanceled()) return;

                  final int remaining = batchCount - allValues.size();
                  try {
                    final List<String> batchValues =
                        aiClient
                            .generateBatchValues(
                                applicationContext,
                                table.name(),
                                colName,
                                sqlType,
                                wordCount,
                                remaining)
                            .join();

                    if (batchValues.isEmpty()) {
                      retries++;
                      continue;
                    }
                    boolean addedAny =
                        batchValues.stream()
                            .filter(v -> seenAiValues.add(v))
                            .peek(allValues::add)
                            .findAny()
                            .isPresent();
                    if (!addedAny) {
                      retries++;
                    }
                  } catch (final Exception ex) {
                    log.warn(
                        "Batch AI retry failed for {}.{}: {}",
                        table.name(),
                        colName,
                        ex.getMessage());
                    retries++;
                  }
                }
              }

              applyAiValuesToRows(allValues, batchStart, batchCount, colName, col, isArray);
              tracker.advance(batchCount);
            });
  }

  /**
   * Checks whether a column requires globally unique AI values — true if it is a PK column or
   * participates in a single-column unique key constraint.
   */
  private boolean isColumnUnique(final Column col) {
    if (col.primaryKey()) return true;
    return table.uniqueKeys().stream()
        .anyMatch(uk -> uk.size() == 1 && uk.getFirst().equalsIgnoreCase(col.name()));
  }

  private void applyAiValuesToRows(
      final List<String> allValues,
      final int batchStart,
      final int batchCount,
      final String colName,
      final Column col,
      final boolean isArray) {
    IntStream.range(0, Math.min(allValues.size(), batchCount))
        .forEach(
            i -> {
              final Row row = rows.get(batchStart + i);
              if (row.explicitColumns().contains(colName)) {
                return;
              }

              final String val = allValues.get(i);
              if (Objects.nonNull(val) && !val.isBlank()) {
                String finalTrimmed = val.trim();
                final Object finalValue;

                if (isArray) {
                  finalValue = parseAiArray(finalTrimmed);
                } else {
                  if (col.length() > 0 && finalTrimmed.length() > col.length()) {
                    finalTrimmed = finalTrimmed.substring(0, col.length());
                  }
                  final Object parsed = parseValue(finalTrimmed, col);
                  finalValue = parsed != null ? parsed : finalTrimmed;
                }
                synchronized (row) {
                  row.values().put(colName, finalValue);
                }
              }
            });
  }

  private boolean isArrayType(final Column col) {
    return col.jdbcType() == Types.ARRAY
        || (Objects.nonNull(col.typeName())
            && col.typeName().toLowerCase(Locale.ROOT).endsWith("[]"));
  }

  private Object[] parseAiArray(final String val) {
    String cleaned = val.trim();
    if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
      cleaned = cleaned.substring(1, cleaned.length() - 1);
    } else if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
      cleaned = cleaned.substring(1, cleaned.length() - 1);
    }

    if (cleaned.isEmpty()) {
      return new Object[0];
    }

    return Arrays.stream(cleaned.split(","))
        .map(String::trim)
        .map(
            s -> {
              if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
              if (s.startsWith("'") && s.endsWith("'")) return s.substring(1, s.length() - 1);
              return s;
            })
        .toArray(String[]::new);
  }

  private String getSqlTypeName(final Column column) {
    if (Objects.nonNull(column.typeName()) && !column.typeName().isBlank()) {
      return column.typeName();
    }
    try {
      return JDBCType.valueOf(column.jdbcType()).getName();
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
      if (isArrayType(column)) {
        return parseAiArray(value);
      }
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
    return relevantUniqueKeys.stream()
        .allMatch(
            uniqueKeyColumns -> {
              final String combination =
                  uniqueKeyColumns.stream()
                      .map(ukCol -> Objects.toString(values.get(ukCol), "NULL"))
                      .collect(Collectors.joining("|"));
              final Set<String> seenCombinations =
                  seenUniqueKeyCombinations.computeIfAbsent(
                      String.join("__", uniqueKeyColumns), k -> new HashSet<>());
              return seenCombinations.add(combination);
            });
  }

  private boolean reconcileMultiColumnConstraints(final Map<String, Object> values) {
    if (Objects.isNull(multiColumnConstraints) || multiColumnConstraints.isEmpty()) {
      return true;
    }

    final Set<String> resolvedColumns = new HashSet<>();
    boolean allResolved = true;

    for (MultiColumnConstraint mcc : multiColumnConstraints) {
      resolvedColumns.clear();
      mcc.columns().stream().forEach(c -> resolvedColumns.add(resolveColumnName(c)));

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
          allResolved = false;
          continue;
        }
        applyCombinationValues(mcc.allowedCombinations().getFirst(), resolvedColumns, values);
      } else {
        final Map<String, String> selectedCombination =
            compatibleCombinations.get(
                ThreadLocalRandom.current().nextInt(compatibleCombinations.size()));
        selectedCombination.entrySet().stream()
            .forEach(
                entry -> {
                  final String colName = resolveColumnName(entry.getKey());
                  if (resolvedColumns.contains(colName) && !values.containsKey(colName)) {
                    final Column col = table.column(colName);
                    values.put(colName, parseValue(entry.getValue(), col));
                  }
                });
      }
    }
    return allResolved;
  }

  private void applyCombinationValues(
      final Map<String, String> combination,
      final Set<String> resolvedColumns,
      final Map<String, Object> values) {
    combination.entrySet().stream()
        .forEach(
            entry -> {
              final String colName = resolveColumnName(entry.getKey());
              if (resolvedColumns.contains(colName)) {
                final Column col = table.column(colName);
                values.put(colName, parseValue(entry.getValue(), col));
              }
            });
  }
}
