/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

/**
 * Core data generation engine refactored to use AI-driven constraint validation.
 * Deprecates manual regex parsing in favor of Ollama semantic analysis.
 */
@Slf4j
@UtilityClass
public class DataGenerator {

  private static final int MAX_GENERATE_ATTEMPTS = 10; // Reduced as AI is more precise
  private static final int DEFAULT_INT_MAX = 10_000;
  private static final int DEFAULT_LONG_MAX = 1_000_000;
  private static final int DEFAULT_DECIMAL_MAX = 1_000;
  private static final int UUID_GENERATION_LIMIT = 1_000_000;
  private static final String ENGLISH_DICTIONARY_PATH = "/dictionaries/english-words.txt";
  private static final String SPANISH_DICTIONARY_PATH = "/dictionaries/spanish-words.txt";

  private static final AtomicReference<List<String>> englishDictionaryCache =
      new AtomicReference<>();
  private static final AtomicReference<List<String>> spanishDictionaryCache =
      new AtomicReference<>();
  private static final Object DICTIONARY_LOCK = new Object();
  
  private static final OllamaService OLLAMA_SERVICE = new OllamaService();

  public static GenerationResult generate(final GenerationParameters params) {

    final Map<String, Table> overridden =
        applyPkUuidOverrides(params.tables(), params.pkUuidOverrides());

    final List<Table> list = new ArrayList<>(overridden.values());

    final Map<String, Set<String>> excludedColumnsSet =
        params.excludedColumns().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));

    return generateInternal(
        GenerationInternalParameters.builder()
            .tables(list)
            .rowsPerTable(params.rowsPerTable())
            .deferred(params.deferred())
            .excludedColumns(excludedColumnsSet)
            .repetitionRules(
                params.repetitionRules() != null
                    ? params.repetitionRules()
                    : Collections.emptyMap())
            .useLatinDictionary(params.useLatinDictionary())
            .useEnglishDictionary(params.useEnglishDictionary())
            .useSpanishDictionary(params.useSpanishDictionary())
            .softDeleteColumns(params.softDeleteColumns())
            .softDeleteUseSchemaDefault(params.softDeleteUseSchemaDefault())
            .softDeleteValue(params.softDeleteValue())
            .numericScale(params.numericScale())
            .build());
  }

  private static Map<String, Table> applyPkUuidOverrides(
      final List<Table> tables, final Map<String, Map<String, String>> pkUuidOverrides) {
    final Map<String, Table> overridden = new LinkedHashMap<>();
    tables.forEach(
        t -> {
          final Map<String, String> pkOverridesForTable =
              pkUuidOverrides != null ? pkUuidOverrides.get(t.name()) : null;
          if (pkOverridesForTable == null || pkOverridesForTable.isEmpty()) {
            overridden.put(t.name(), t);
            return;
          }
          final List<Column> newCols = new ArrayList<>();
          t.columns()
              .forEach(
                  c -> {
                    final boolean forceUuid = pkOverridesForTable.containsKey(c.name());
                    if (forceUuid && !c.uuid()) {
                      newCols.add(c.toBuilder().uuid(true).build());
                    } else {
                      newCols.add(c);
                    }
                  });
          overridden.put(
              t.name(),
              new Table(
                  t.name(), newCols, t.primaryKey(), t.foreignKeys(), t.checks(), t.uniqueKeys()));
        });
    return overridden;
  }

  private static GenerationResult generateInternal(final GenerationInternalParameters params) {

    final Instant start = Instant.now();

    final List<Table> orderedTables = List.copyOf(params.tables());
    final Map<String, Table> tableMap =
        orderedTables.stream().collect(Collectors.toUnmodifiableMap(Table::name, t -> t));

    final List<String> dictionaryWords =
        loadDictionaryWords(params.useEnglishDictionary(), params.useSpanishDictionary());
    final Faker faker = new Faker();
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    final Set<UUID> usedUuids = new HashSet<>();

    final GenerationContext context =
        new GenerationContext(
            orderedTables,
            params.rowsPerTable(),
            params.excludedColumns(),
            params.repetitionRules(),
            faker,
            usedUuids,
            data,
            dictionaryWords,
            tableMap,
            params.deferred(),
            params.useLatinDictionary(),
            params.softDeleteColumns(),
            params.softDeleteUseSchemaDefault(),
            params.softDeleteValue(),
            params.numericScale());

    executeGenerationSteps(context);

    final Instant end = Instant.now();
    final Duration duration = Duration.between(start, end);
    final double seconds = duration.toMillis() / 1000.0;

    log.info(
        "Generation completed in {} seconds. Tables: {}, deferred updates: {}",
        String.format(Locale.ROOT, "%.3f", seconds),
        context.orderedTables().size(),
        context.updates().size());

    return new GenerationResult(context.data(), context.updates());
  }

  private static void executeGenerationSteps(final GenerationContext context) {
    generateTableRows(
        GenerateTableRowsParameters.builder()
            .orderedTables(context.orderedTables())
            .rowsPerTable(context.rowsPerTable())
            .excludedColumns(context.excludedColumns())
            .repetitionRules(context.repetitionRules())
            .faker(context.faker())
            .usedUuids(context.usedUuids())
            .data(context.data())
            .dictionaryWords(context.dictionaryWords())
            .useLatinDictionary(context.useLatinDictionary())
            .softDeleteColumns(context.softDeleteColumns())
            .softDeleteUseSchemaDefault(context.softDeleteUseSchemaDefault())
            .softDeleteValue(context.softDeleteValue())
            .numericScale(context.numericScale())
            .build());

    resolveForeignKeys(context);
  }

  private static void generateTableRows(final GenerateTableRowsParameters params) {

    final Set<String> softDeleteCols = new HashSet<>();
    if (params.softDeleteColumns() != null) {
      Arrays.stream(params.softDeleteColumns().split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .forEach(softDeleteCols::add);
    }

    params
        .orderedTables()
        .forEach(
            table -> {
              if (table.columns().isEmpty()) {
                params.data().put(table, Collections.emptyList());
                log.debug(
                    "Skipping row generation for table {} as it has no columns.", table.name());
                return;
              }

              final List<Row> rows = new ArrayList<>();
              final Set<String> fkColumnNames = table.fkColumnNames();
              final Predicate<Column> isFkColumn = column -> fkColumnNames.contains(column.name());
              final List<List<String>> relevantUniqueKeys =
                  table.uniqueKeys().stream()
                      .filter(uk -> !fkColumnNames.containsAll(uk))
                      .filter(uk -> table.primaryKey().isEmpty() || !table.primaryKey().equals(uk))
                      .toList();
              final Set<String> seenPrimaryKeys = new HashSet<>();
              final Map<String, Set<String>> seenUniqueKeyCombinations = new HashMap<>();
              final Set<String> excluded =
                  params.excludedColumns().getOrDefault(table.name(), Set.of());

              final AtomicInteger generatedCount = new AtomicInteger(0);

              List<RepetitionRule> rules = Collections.emptyList();
              if (params.repetitionRules() != null) {
                rules =
                    params.repetitionRules().getOrDefault(table.name(), Collections.emptyList());
              }

              final TableGenerationContext tableContext =
                  TableGenerationContext.builder()
                      .table(table)
                      .generatedCount(generatedCount)
                      .rows(rows)
                      .isFkColumn(isFkColumn)
                      .excluded(excluded)
                      .seenPrimaryKeys(seenPrimaryKeys)
                      .seenUniqueKeyCombinations(seenUniqueKeyCombinations)
                      .softDeleteCols(softDeleteCols)
                      .relevantUniqueKeys(relevantUniqueKeys)
                      .build();

              processRepetitionRules(rules, params, tableContext);
              fillRemainingRows(params, tableContext);

              params.data().put(table, rows);
              log.debug("Generated {} rows for table {}.", rows.size(), table.name());
            });
  }

  private static void processRepetitionRules(
      final List<RepetitionRule> rules,
      final GenerateTableRowsParameters params,
      final TableGenerationContext context) {

    for (final RepetitionRule rule : rules) {
      final Map<String, Object> baseValues = new HashMap<>(rule.fixedValues());

      rule.randomConstantColumns()
          .forEach(
              colName -> {
                final Column col = context.table().column(colName);
                if (col != null) {
                  final Object val =
                      generateColumnValue(
                          GenerateSingleRowParameters.builder()
                              .faker(params.faker())
                              .table(context.table())
                              .index(context.generatedCount().get())
                              .usedUuids(params.usedUuids())
                              .isFkColumn(context.isFkColumn())
                              .excluded(context.excluded())
                              .dictionaryWords(params.dictionaryWords())
                              .useLatinDictionary(params.useLatinDictionary())
                              .softDeleteCols(context.softDeleteCols())
                              .softDeleteUseSchemaDefault(params.softDeleteUseSchemaDefault())
                              .softDeleteValue(params.softDeleteValue())
                              .numericScale(params.numericScale())
                              .build(),
                          col);
                  baseValues.put(colName, val);
                }
              });

      for (int i = 0; i < rule.count(); i++) {
        int attempts = 0;
        while (attempts < MAX_GENERATE_ATTEMPTS) {
          final Optional<Row> generatedRow =
              generateAndValidateRowWithBase(createRowParams(params, context), baseValues);

          if (generatedRow.isPresent()) {
            context.rows().add(generatedRow.get());
            context.generatedCount().incrementAndGet();
            break;
          }
          attempts++;
        }
      }
    }
  }

  private static void fillRemainingRows(
      final GenerateTableRowsParameters params, final TableGenerationContext context) {

    int attempts = 0;
    while (context.generatedCount().get() < params.rowsPerTable()
        && attempts < params.rowsPerTable() * MAX_GENERATE_ATTEMPTS) {

      final Optional<Row> generatedRow = generateAndValidateRow(createRowParams(params, context));

      generatedRow.ifPresent(
          row -> {
            context.rows().add(row);
            context.generatedCount().incrementAndGet();
          });
      attempts++;
    }
  }

  private static GenerateAndValidateRowParameters createRowParams(
      final GenerateTableRowsParameters params, final TableGenerationContext context) {
    return GenerateAndValidateRowParameters.builder()
        .table(context.table())
        .generatedCount(context.generatedCount().get())
        .faker(params.faker())
        .usedUuids(params.usedUuids())
        .isFkColumn(context.isFkColumn())
        .excluded(context.excluded())
        .dictionaryWords(params.dictionaryWords())
        .useLatinDictionary(params.useLatinDictionary())
        .seenPrimaryKeys(context.seenPrimaryKeys())
        .seenUniqueKeyCombinations(context.seenUniqueKeyCombinations())
        .softDeleteCols(context.softDeleteCols())
        .softDeleteUseSchemaDefault(params.softDeleteUseSchemaDefault())
        .softDeleteValue(params.softDeleteValue())
        .numericScale(params.numericScale())
        .relevantUniqueKeys(context.relevantUniqueKeys())
        .build();
  }

  @SuppressWarnings("java:S2245")
  private static Object generateColumnValue(
      final GenerateSingleRowParameters params, final Column column) {
    if (params.excluded().contains(column.name())) {
      return null;
    }
    if (params.isFkColumn().test(column) && !column.primaryKey()) {
      return null;
    }

    if (params.softDeleteCols() != null && params.softDeleteCols().contains(column.name())) {
      if (params.softDeleteUseSchemaDefault()) {
        return SqlKeyword.DEFAULT;
      } else {
        return convertStringValue(params.softDeleteValue(), column);
      }
    }

    if (column.nullable() && ThreadLocalRandom.current().nextDouble() < 0.3) {
      return null;
    }

    return generateValue(params, column);
  }

  private static Object convertStringValue(final String value, final Column column) {
    if (value == null || "NULL".equalsIgnoreCase(value)) return null;
    try {
      return switch (column.jdbcType()) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(value);
        case Types.BIGINT -> Long.parseLong(value);
        case Types.BOOLEAN, Types.BIT -> Boolean.parseBoolean(value);
        case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(value);
        case Types.FLOAT, Types.DOUBLE, Types.REAL -> Double.parseDouble(value);
        default -> value;
      };
    } catch (final Exception e) {
      log.warn("Failed to convert value '{}' for column {}. Using NULL.", value, column.name());
      return null;
    }
  }

  private static Optional<Row> generateAndValidateRow(
      final GenerateAndValidateRowParameters params) {
    return generateAndValidateRowWithBase(params, Collections.emptyMap());
  }

  private static Optional<Row> generateAndValidateRowWithBase(
      final GenerateAndValidateRowParameters params, final Map<String, Object> baseValues) {

    final Map<String, Object> values = new LinkedHashMap<>();

    if (baseValues != null) {
      values.putAll(baseValues);
    }

    generateRemainingColumnValues(params, values);

    if (!isPrimaryKeyUnique(params.table(), values, params.seenPrimaryKeys())) {
      return Optional.empty();
    }

    if (!areUniqueKeysUnique(
        params.relevantUniqueKeys(), values, params.seenUniqueKeyCombinations())) {
      return Optional.empty();
    }
    
    if (!validateWithAi(params.table(), values)) {
        return Optional.empty();
    }

    return Optional.of(new Row(values));
  }

  private static boolean validateWithAi(Table table, Map<String, Object> values) {
      if (table.checks().isEmpty()) return true;
      
      String schemaContext = table.checks().stream().collect(Collectors.joining("; "));
      String rowData = values.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining(", "));
      
      String prompt = """
              Validate if the following data row satisfies the table constraints.
              Constraints: %s
              Row Data: %s
              Return ONLY 'VALID' or 'INVALID'.
              """.formatted(schemaContext, rowData);
      
      try {
          String result = OLLAMA_SERVICE.ask(prompt).trim();
          return "VALID".equalsIgnoreCase(result);
      } catch (Exception e) {
          log.error("AI Validation failed, falling back to true", e);
          return true;
      }
  }

  private static void generateRemainingColumnValues(
      final GenerateAndValidateRowParameters params, final Map<String, Object> values) {
    params
        .table()
        .columns()
        .forEach(
            column -> {
              if (!values.containsKey(column.name())) {
                values.put(
                    column.name(),
                    generateColumnValue(
                        GenerateSingleRowParameters.builder()
                            .faker(params.faker())
                            .table(params.table())
                            .index(params.generatedCount())
                            .usedUuids(params.usedUuids())
                            .isFkColumn(params.isFkColumn())
                            .excluded(params.excluded())
                            .dictionaryWords(params.dictionaryWords())
                            .useLatinDictionary(params.useLatinDictionary())
                            .softDeleteCols(params.softDeleteCols())
                            .softDeleteUseSchemaDefault(params.softDeleteUseSchemaDefault())
                            .softDeleteValue(params.softDeleteValue())
                            .numericScale(params.numericScale())
                            .build(),
                        column));
              }
            });
  }

  private static boolean isPrimaryKeyUnique(
      final Table table, final Map<String, Object> values, final Set<String> seenPrimaryKeys) {
    if (table.primaryKey().isEmpty()) {
      return true;
    }
    final String pkKey =
        table.primaryKey().stream()
            .map(pkCol -> Objects.toString(values.get(pkCol), "NULL"))
            .collect(Collectors.joining("|"));
    return seenPrimaryKeys.add(pkKey);
  }

  private static boolean areUniqueKeysUnique(
      final List<List<String>> relevantUniqueKeys,
      final Map<String, Object> values,
      final Map<String, Set<String>> seenUniqueKeyCombinations) {
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

  private static void resolveForeignKeys(final GenerationContext context) {

    final ForeignKeyResolutionContext fkContext =
        new ForeignKeyResolutionContext(
            context.tableMap(),
            context.data(),
            context.deferred(),
            context.updates(),
            context.inserted(),
            context.uniqueFkParentQueues());

    context.orderedTables().forEach(table -> resolveForeignKeysForTable(table, fkContext));
  }

  private static void resolveForeignKeysForTable(
      final Table table, final ForeignKeyResolutionContext context) {
    final List<Row> rows = Objects.requireNonNull(context.data().get(table));

    final Map<ForeignKey, Boolean> fkNullableCache =
        table.foreignKeys().stream()
            .collect(
                Collectors.toMap(
                    fk -> fk,
                    fk ->
                        fk.columnMapping().keySet().stream()
                            .map(table::column)
                            .allMatch(Column::nullable)));

    final List<List<String>> uniqueKeysOnFks =
        new ArrayList<>(
            table.uniqueKeys().stream()
                .filter(uk -> table.fkColumnNames().containsAll(uk))
                .map(ArrayList::new)
                .toList());
    table.foreignKeys().stream()
        .filter(ForeignKey::uniqueOnFk)
        .forEach(
            fk -> {
              final List<String> fkCols = new ArrayList<>(fk.columnMapping().keySet());
              if (uniqueKeysOnFks.stream()
                  .noneMatch(uk -> new HashSet<>(uk).equals(new HashSet<>(fkCols)))) {
                uniqueKeysOnFks.add(fkCols);
              }
            });

    if (!uniqueKeysOnFks.isEmpty()) {
      handleUniqueFkResolution(table, rows, context, uniqueKeysOnFks);
    } else {
      rows.forEach(
          row ->
              table
                  .foreignKeys()
                  .forEach(
                      fk ->
                          resolveSingleForeignKey(
                              fk, table, row, fkNullableCache.getOrDefault(fk, false), context)));
    }

    context.inserted().add(table.name());
  }

  private static void handleUniqueFkResolution(
      final Table table,
      final List<Row> rows,
      final ForeignKeyResolutionContext context,
      final List<List<String>> uniqueKeysOnFks) {
    final Set<String> usedCombinations = new HashSet<>();
    final int maxAttempts = 100 * rows.size();

    for (final Row row : rows) {
      final Optional<Map<String, Object>> resolvedFkValues =
          findUniqueFkCombination(table, context, uniqueKeysOnFks, usedCombinations, maxAttempts);

      if (resolvedFkValues.isPresent()) {
        row.values().putAll(resolvedFkValues.get());
      } else {
        log.warn("Could not find a unique FK combination for a row in table {}", table.name());
      }
    }
  }

  private static Optional<Map<String, Object>> findUniqueFkCombination(
      final Table table,
      final ForeignKeyResolutionContext context,
      final List<List<String>> uniqueKeysOnFks,
      final Set<String> usedCombinations,
      final int maxAttempts) {

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      final Map<String, Object> potentialFkValues = generatePotentialFkValues(table, context);

      if (!potentialFkValues.isEmpty()
          && !isUniqueFkCollision(uniqueKeysOnFks, potentialFkValues, usedCombinations)) {
        addUniqueCombinationsToSet(uniqueKeysOnFks, potentialFkValues, usedCombinations);
        return Optional.of(potentialFkValues);
      }
    }
    return Optional.empty();
  }

  private static Map<String, Object> generatePotentialFkValues(
      final Table table, final ForeignKeyResolutionContext context) {
    final Map<String, Object> potentialFkValues = new HashMap<>();
    for (final ForeignKey fk : table.foreignKeys()) {
      final Table parent = context.tableMap().get(fk.pkTable());
      final List<Row> parentRows = (parent != null) ? context.data().get(parent) : null;

      if (parent != null && parentRows != null && !parentRows.isEmpty()) {
        final Row parentRow =
            getParentRowForForeignKey(
                fk, parentRows, context.uniqueFkParentQueues(), table.name(), parent.name(), false);
        if (parentRow != null) {
          fk.columnMapping()
              .forEach(
                  (fkCol, pkCol) -> potentialFkValues.put(fkCol, parentRow.values().get(pkCol)));
        }
      }
    }
    return potentialFkValues;
  }

  private static boolean isUniqueFkCollision(
      final List<List<String>> uniqueKeysOnFks,
      final Map<String, Object> potentialFkValues,
      final Set<String> usedCombinations) {
    for (final List<String> ukColumns : uniqueKeysOnFks) {
      final String combination =
          ukColumns.stream()
              .map(c -> Objects.toString(potentialFkValues.get(c), "NULL"))
              .collect(Collectors.joining("|"));
      if (usedCombinations.contains(combination)) {
        return true;
      }
    }
    return false;
  }

  private static void addUniqueCombinationsToSet(
      final List<List<String>> uniqueKeysOnFks,
      final Map<String, Object> potentialFkValues,
      final Set<String> usedCombinations) {
    for (final List<String> ukColumns : uniqueKeysOnFks) {
      final String combination =
          ukColumns.stream()
              .map(c -> Objects.toString(potentialFkValues.get(c), "NULL"))
              .collect(Collectors.joining("|"));
      usedCombinations.add(combination);
    }
  }

  private static void resolveSingleForeignKey(
      final ForeignKey fk,
      final Table table,
      final Row row,
      final boolean fkNullable,
      final ForeignKeyResolutionContext context) {

    final Table parent = context.tableMap().get(fk.pkTable());
    if (parent == null) {
      log.warn("Skipping FK {}.{} -> {}: table not found", table.name(), fk.name(), fk.pkTable());
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }

    final List<Row> parentRows = context.data().get(parent);
    final boolean parentInserted = context.inserted().contains(parent.name());

    final Row parentRow =
        getParentRowForForeignKey(
            fk,
            parentRows,
            context.uniqueFkParentQueues(),
            table.name(),
            parent.name(),
            fkNullable);

    if (parentRow == null) {
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }

    if (parentInserted || context.deferred()) {
      fk.columnMapping()
          .forEach((fkCol, pkCol) -> row.values().put(fkCol, parentRow.values().get(pkCol)));
    } else {
      if (!fkNullable) {
        throw new IllegalStateException(
            "Cycle with non-nullable FK: "
                .concat(table.name())
                .concat(" -> ")
                .concat(parent.name()));
      }
      final Map<String, Object> fkVals = new LinkedHashMap<>();
      fk.columnMapping()
          .forEach(
              (fkCol, pkCol) -> {
                fkVals.put(fkCol, parentRow.values().get(pkCol));
                row.values().put(fkCol, null);
              });
      final Map<String, Object> pkVals = new LinkedHashMap<>();
      table.primaryKey().forEach(pkCol -> pkVals.put(pkCol, row.values().get(pkCol)));
      context.updates().add(new PendingUpdate(table.name(), fkVals, pkVals));
    }
  }

  @SuppressWarnings("java:S2245")
  private static Row getParentRowForForeignKey(
      final ForeignKey fk,
      final List<Row> parentRows,
      final Map<String, Deque<Row>> uniqueFkParentQueues,
      final String tableName,
      final String parentTableName,
      final boolean fkNullable) {

    if (fk.uniqueOnFk()) {
      final String key = tableName.concat("|").concat(fk.name());
      final Deque<Row> queue =
          uniqueFkParentQueues.computeIfAbsent(
              key,
              k -> {
                final List<Row> shuffled = new ArrayList<>(parentRows);
                Collections.shuffle(shuffled, ThreadLocalRandom.current());
                return new ArrayDeque<>(shuffled);
              });
      if (queue.isEmpty()) {
        if (!fkNullable) {
          throw new IllegalStateException(
              "Not enough rows in "
                  .concat(parentTableName)
                  .concat(" for non-nullable 1:1 FK from ")
                  .concat(tableName));
        }
        return null;
      }
      return queue.pollFirst();
    } else {
      return parentRows.get(ThreadLocalRandom.current().nextInt(parentRows.size()));
    }
  }

  private static Object generateValue(
      final GenerateSingleRowParameters params, final Column column) {
    if (column.uuid()) {
      return generateUuidValue(column, params.usedUuids());
    }

    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(column.allowedValues()), column.jdbcType());
    }

    return generateDefaultValue(
        params.faker(),
        column,
        params.index(),
        column.length() > 0 ? column.length() : null,
        params.dictionaryWords(),
        params.useLatinDictionary(),
        params.numericScale());
  }

  private static Object generateUuidValue(
      final Column column, final Set<UUID> usedUuids) {
    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      final UUID uuid = tryParseUuidFromAllowedValues(column.allowedValues(), usedUuids);
      if (uuid != null) return uuid;
    }

    return generateUuid(usedUuids);
  }

  private static UUID tryParseUuidFromAllowedValues(
      final Set<String> allowedValues, final Set<UUID> usedUuids) {
    for (final String s : allowedValues) {
      try {
        final UUID u = UUID.fromString(s.trim());
        if (usedUuids.add(u)) return u;
      } catch (final IllegalArgumentException e) {
        log.debug("Invalid UUID string in allowed values: {}", s, e);
      }
    }
    return null;
  }

  @SuppressWarnings("java:S2245")
  private static Object generateDefaultValue(
      final Faker faker,
      final Column column,
      final int index,
      final Integer maxLen,
      final List<String> dictionaryWords,
      final boolean useLatinDictionary,
      final int numericScale) {
    return switch (column.jdbcType()) {
      case Types.CHAR,
          Types.VARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGVARCHAR,
          Types.LONGNVARCHAR ->
          generateString(faker, maxLen, column.jdbcType(), dictionaryWords, useLatinDictionary);
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> boundedInt(column);
      case Types.BIGINT -> boundedLong(column);
      case Types.BOOLEAN, Types.BIT -> faker.bool().bool();
      case Types.DATE ->
          Date.valueOf(LocalDate.now().minusDays(faker.number().numberBetween(0, 3650)));
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
          Timestamp.from(Instant.now().minusSeconds(faker.number().numberBetween(0, 31_536_000)));
      case Types.DECIMAL, Types.NUMERIC -> boundedBigDecimal(column, numericScale);
      case Types.FLOAT, Types.DOUBLE, Types.REAL -> boundedDouble(column, numericScale);
      default -> index;
    };
  }

  @SuppressWarnings("java:S2245")
  private static Object pickRandom(final List<String> vals, final int jdbcType) {
    String v = vals.get(ThreadLocalRandom.current().nextInt(vals.size()));
    if (v == null) return null;
    v = v.trim();
    if (v.isEmpty()) return "";
    try {
      return switch (jdbcType) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(v);
        case Types.BIGINT -> Long.parseLong(v);
        case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(v);
        case Types.FLOAT, Types.DOUBLE, Types.REAL -> Double.parseDouble(v);
        case Types.BOOLEAN, Types.BIT -> Boolean.parseBoolean(v);
        default -> v;
      };
    } catch (final NumberFormatException e) {
      log.debug("Failed to parse '{}' to numeric type for JDBC type {}", v, jdbcType, e);
      return v;
    }
  }

  @SuppressWarnings("java:S2245")
  private static UUID generateUuid(final Set<UUID> usedUuids) {
    for (int i = 0; i < UUID_GENERATION_LIMIT; i++) {
      final UUID u = UUID.randomUUID();
      if (usedUuids.add(u)) return u;
    }
    throw new IllegalStateException(
        "Unable to generate a unique UUID after "
            .concat(String.valueOf(UUID_GENERATION_LIMIT))
            .concat(" attempts"));
  }

  @SuppressWarnings("java:S2245")
  private static Integer boundedInt(final Column column) {
    int min = column.minValue() != 0 ? column.minValue() : 1;
    int max = column.maxValue() != 0 ? column.maxValue() : DEFAULT_INT_MAX;
    if (min > max) {
      final int t = min;
      min = max;
      max = t;
    }
    final long v = ThreadLocalRandom.current().nextLong(min, (long) max + 1);
    return Math.toIntExact(Math.clamp(v, Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  @SuppressWarnings("java:S2245")
  private static Long boundedLong(final Column column) {
    long min = column.minValue() != 0 ? column.minValue() : 1L;
    long max = column.maxValue() != 0 ? column.maxValue() : DEFAULT_LONG_MAX;
    if (min > max) {
      final long t = min;
      min = max;
      max = t;
    }
    return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
  }

  private static double generateRandomDouble(final double min, final double max) {
    return min + (max - min) * ThreadLocalRandom.current().nextDouble();
  }

  @SuppressWarnings("java:S2245")
  private static BigDecimal boundedBigDecimal(final Column column, final int numericScale) {
    double min = column.minValue() != 0 ? column.minValue() : 1.0;
    double max = column.maxValue() != 0 ? column.maxValue() : DEFAULT_DECIMAL_MAX;
    final double val = generateRandomDouble(min, max);
    final int scale = getEffectiveScale(column, numericScale);
    return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
  }

  @SuppressWarnings("java:S2245")
  private static Double boundedDouble(final Column column, final int numericScale) {
    double min = column.minValue() != 0 ? column.minValue() : 1.0;
    double max = column.maxValue() != 0 ? column.maxValue() : DEFAULT_DECIMAL_MAX;
    final double val = generateRandomDouble(min, max);
    final int scale = getEffectiveScale(column, numericScale);
    return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  @SuppressWarnings("java:S2245")
  private static String generateString(
      final Faker faker,
      final Integer maxLen,
      final int jdbcType,
      final List<String> dictionaryWords,
      final boolean useLatinDictionary) {
    final int len = (maxLen != null && maxLen > 0) ? maxLen : 255;
    if (len == 2) return faker.country().countryCode2();
    if (len == 3) return faker.country().countryCode3();
    if (len == 24) return normalizeToLength("ES".concat(faker.number().digits(22)), len, jdbcType);

    boolean useDictionary = !dictionaryWords.isEmpty();
    if (useDictionary && useLatinDictionary) {
      useDictionary = ThreadLocalRandom.current().nextBoolean();
    }

    if (useDictionary) {
      final int numWords =
          ThreadLocalRandom.current().nextInt(1, Math.min(dictionaryWords.size(), 5));
      final StringBuilder phraseBuilder = new StringBuilder();
      for (int i = 0; i < numWords; i++) {
        phraseBuilder.append(pickRandom(dictionaryWords, Types.VARCHAR)).append(" ");
      }
      return normalizeToLength(phraseBuilder.toString().trim(), len, jdbcType);
    } else {
      final int numWords = ThreadLocalRandom.current().nextInt(3, Math.clamp(len / 5, 4, 10));
      final String phrase = String.join(" ", faker.lorem().words(numWords));
      return normalizeToLength(phrase, len, jdbcType);
    }
  }

  private static List<String> loadDictionaryWords(
      final boolean useEnglishDictionary, final boolean useSpanishDictionary) {
    final List<String> words = new ArrayList<>();
    if (useEnglishDictionary) {
      if (englishDictionaryCache.get() == null) {
        synchronized (DICTIONARY_LOCK) {
          if (englishDictionaryCache.get() == null) {
            englishDictionaryCache.set(readWordsFromFile(ENGLISH_DICTIONARY_PATH));
          }
        }
      }
      words.addAll(englishDictionaryCache.get());
    }
    if (useSpanishDictionary) {
      if (spanishDictionaryCache.get() == null) {
        synchronized (DICTIONARY_LOCK) {
          if (spanishDictionaryCache.get() == null) {
            spanishDictionaryCache.set(readWordsFromFile(SPANISH_DICTIONARY_PATH));
          }
        }
      }
      words.addAll(spanishDictionaryCache.get());
    }
    if (words.isEmpty()) {
      return Collections.emptyList();
    }
    return words;
  }

  private static List<String> readWordsFromFile(final String filePath) {
    try (final InputStream is = DataGenerator.class.getResourceAsStream(filePath)) {
      if (is == null) {
        log.warn("Dictionary file not found: {}", filePath);
        return Collections.emptyList();
      }
      return Arrays.stream(new String(is.readAllBytes(), StandardCharsets.UTF_8).split("\\s+"))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .toList();
    } catch (final IOException e) {
      log.error("Error reading dictionary file: {}", filePath, e);
      return Collections.emptyList();
    }
  }

  private static String normalizeToLength(
      final String value, final int length, final int jdbcType) {
    if (length <= 0) return value;
    if (value.length() > length) {
      return value.substring(0, length);
    }
    if (jdbcType == Types.CHAR && value.length() < length) {
      return value.concat(" ".repeat(length - value.length()));
    }
    return value;
  }

  private static int getEffectiveScale(final Column column, final int numericScale) {
    return column.scale() > 0 ? column.scale() : numericScale;
  }

  @Builder
  public record GenerationParameters(
      List<Table> tables,
      int rowsPerTable,
      boolean deferred,
      Map<String, Map<String, String>> pkUuidOverrides,
      Map<String, List<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      boolean useLatinDictionary,
      boolean useEnglishDictionary,
      boolean useSpanishDictionary,
      String softDeleteColumns,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale) {}

  @Builder
  private record GenerationInternalParameters(
      List<Table> tables,
      int rowsPerTable,
      boolean deferred,
      Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      boolean useLatinDictionary,
      boolean useEnglishDictionary,
      boolean useSpanishDictionary,
      String softDeleteColumns,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale) {}

  private record GenerationContext(
      List<Table> orderedTables,
      int rowsPerTable,
      Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      Faker faker,
      Set<UUID> usedUuids,
      Map<Table, List<Row>> data,
      List<String> dictionaryWords,
      Map<String, Table> tableMap,
      boolean deferred,
      boolean useLatinDictionary,
      List<PendingUpdate> updates,
      Set<String> inserted,
      Map<String, Deque<Row>> uniqueFkParentQueues,
      String softDeleteColumns,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale) {

    GenerationContext(
        final List<Table> orderedTables,
        final int rowsPerTable,
        final Map<String, Set<String>> excludedColumns,
        final Map<String, List<RepetitionRule>> repetitionRules,
        final Faker faker,
        final Set<UUID> usedUuids,
        final Map<Table, List<Row>> data,
        final List<String> dictionaryWords,
        final Map<String, Table> tableMap,
        final boolean deferred,
        boolean useLatinDictionary,
        final String softDeleteColumns,
        final boolean softDeleteUseSchemaDefault,
        final String softDeleteValue,
        final int numericScale) {
      this(
          orderedTables,
          rowsPerTable,
          excludedColumns,
          repetitionRules,
          faker,
          usedUuids,
          data,
          dictionaryWords,
          tableMap,
          deferred,
          useLatinDictionary,
          new ArrayList<>(),
          new HashSet<>(),
          new HashMap<>(),
          softDeleteColumns,
          softDeleteUseSchemaDefault,
          softDeleteValue,
          numericScale);
    }
  }

  @Builder
  private record GenerateTableRowsParameters(
      List<Table> orderedTables,
      int rowsPerTable,
      Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      Faker faker,
      Set<UUID> usedUuids,
      Map<Table, List<Row>> data,
      List<String> dictionaryWords,
      boolean useLatinDictionary,
      String softDeleteColumns,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale) {}

  @Builder
  private record TableGenerationContext(
      Table table,
      AtomicInteger generatedCount,
      List<Row> rows,
      Predicate<Column> isFkColumn,
      Set<String> excluded,
      Set<String> seenPrimaryKeys,
      Map<String, Set<String>> seenUniqueKeyCombinations,
      Set<String> softDeleteCols,
      List<List<String>> relevantUniqueKeys) {}

  @Builder
  private record GenerateSingleRowParameters(
      Faker faker,
      Table table,
      int index,
      Set<UUID> usedUuids,
      Predicate<Column> isFkColumn,
      Set<String> excluded,
      List<String> dictionaryWords,
      boolean useLatinDictionary,
      Set<String> softDeleteCols,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale) {}

  @Builder
  private record GenerateAndValidateRowParameters(
      Table table,
      int generatedCount,
      Faker faker,
      Set<UUID> usedUuids,
      Predicate<Column> isFkColumn,
      Set<String> excluded,
      List<String> dictionaryWords,
      boolean useLatinDictionary,
      Set<String> seenPrimaryKeys,
      Map<String, Set<String>> seenUniqueKeyCombinations,
      Set<String> softDeleteCols,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale,
      List<List<String>> relevantUniqueKeys) {}

  private record ForeignKeyResolutionContext(
      Map<String, Table> tableMap,
      Map<Table, List<Row>> data,
      boolean deferred,
      List<PendingUpdate> updates,
      Set<String> inserted,
      Map<String, Deque<Row>> uniqueFkParentQueues) {}

  public record GenerationResult(Map<Table, List<Row>> rows, List<PendingUpdate> updates) {}
}
