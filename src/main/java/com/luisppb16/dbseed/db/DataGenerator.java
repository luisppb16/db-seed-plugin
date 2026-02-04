/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.config.LocalAIConfiguration;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.service.LocalAIService;
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
import java.util.Comparator;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

@Slf4j
@UtilityClass
public class DataGenerator {

  private static final Pattern COL_EQ_VAL_PATTERN =
      Pattern.compile(
          "(?i)^\\s*\\(*\\s*" +
          "(?:(?:\"?[A-Za-z0-9_]+\"?)\\.)*" +
          "(?:\\(*\\s*)?" +
          "\"?([A-Za-z0-9_]+)\"?" +
          "(?:\\s*\\)*)?" +
          "(?:\\s*::[a-zA-Z0-9_ \\[\\]]+)*" +
          "\\s*=\\s*" +
          "((?:'.*?')|(?:\\\".*?\\\")|[0-9A-Za-z_+-]+(?:\\.[0-9]+)?)" +
          "(?:\\s*::[a-zA-Z0-9_ \\[\\]]+)*" +
          "\\s*\\)*\\s*$");
  private static final int MAX_GENERATE_ATTEMPTS = 100;
  private static final int DEFAULT_INT_MAX = 10_000;
  private static final int DEFAULT_LONG_MAX = 1_000_000;
  private static final int DEFAULT_DECIMAL_MAX = 1_000;
  private static final int UUID_GENERATION_LIMIT = 1_000_000;
  private static final String ENGLISH_DICTIONARY_PATH = "/dictionaries/english-words.txt";
  private static final String SPANISH_DICTIONARY_PATH = "/dictionaries/spanish-words.txt";

  private static final AtomicReference<List<String>> englishDictionaryCache = new AtomicReference<>();
  private static final AtomicReference<List<String>> spanishDictionaryCache = new AtomicReference<>();
  private static final Object DICTIONARY_LOCK = new Object();
  private static final Map<String, ColumnPatterns> COLUMN_PATTERNS_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  public static GenerationResult generate(final GenerationParameters params) {

    final Map<String, Table> overridden = applyPkUuidOverrides(params.tables(), params.pkUuidOverrides());

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
            .aiConfig(params.aiConfig())
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
        loadDictionaryWords(
            params.useLatinDictionary(),
            params.useEnglishDictionary(),
            params.useSpanishDictionary());
    final Faker faker = new Faker();
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    final Set<UUID> usedUuids = new HashSet<>();

    final Map<String, Map<String, ParsedConstraint>> tableConstraints = new HashMap<>();

    final LocalAIService aiService = (params.aiConfig() != null && params.aiConfig().enableContextAwareGeneration())
        ? new LocalAIService(params.aiConfig().aiLocalEndpoint(), params.aiConfig().aiModelName())
        : null;

    final GenerationContext context =
        new GenerationContext(
            orderedTables,
            params.rowsPerTable(),
            params.excludedColumns(),
            params.repetitionRules(),
            faker,
            usedUuids,
            tableConstraints,
            data,
            dictionaryWords,
            tableMap,
            params.deferred(),
            params.useLatinDictionary(),
            params.softDeleteColumns(),
            params.softDeleteUseSchemaDefault(),
            params.softDeleteValue(),
            params.numericScale(),
            aiService);

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
            .tableConstraints(context.tableConstraints())
            .data(context.data())
            .dictionaryWords(context.dictionaryWords())
            .useLatinDictionary(context.useLatinDictionary())
            .softDeleteColumns(context.softDeleteColumns())
            .softDeleteUseSchemaDefault(context.softDeleteUseSchemaDefault())
            .softDeleteValue(context.softDeleteValue())
            .numericScale(context.numericScale())
            .build());

    validateNumericConstraints(context.orderedTables(), context.tableConstraints(), context.data(), context.numericScale());
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

              final List<CheckExpression> checkExpressions = table.checks().stream()
                  .filter(c -> c != null && !c.isBlank())
                  .map(
                      c -> {
                        final String noParens = c.replaceAll("[()]+", " ");
                        return new CheckExpression(
                            c, noParens, noParens.toLowerCase(Locale.ROOT));
                      })
                  .toList();

              final Map<String, ParsedConstraint> constraints =
                  table.columns().stream()
                      .collect(
                          Collectors.toMap(
                              Column::name,
                              col ->
                                  parseConstraintsForColumn(
                                      checkExpressions, col.name(), col.length())));
              params.tableConstraints().put(table.name(), constraints);
              final List<Row> rows = new ArrayList<>();
              final Set<String> fkColumnNames = table.fkColumnNames();
              final Predicate<Column> isFkColumn =
                  column -> fkColumnNames.contains(column.name());
              final List<List<String>> relevantUniqueKeys = table.uniqueKeys().stream()
                  .filter(uk -> !fkColumnNames.containsAll(uk))
                  .filter(uk -> table.primaryKey().isEmpty() || !table.primaryKey().equals(uk))
                  .toList();
              final Set<String> seenPrimaryKeys = new HashSet<>();
              final Map<String, Set<String>> seenUniqueKeyCombinations = new HashMap<>();
              final Set<String> excluded = params.excludedColumns().getOrDefault(table.name(), Set.of());

              final AtomicInteger generatedCount = new AtomicInteger(0);
              
              List<RepetitionRule> rules = Collections.emptyList();
              if (params.repetitionRules() != null) {
                  rules = params.repetitionRules().getOrDefault(table.name(), Collections.emptyList());
              }

              final TableGenerationContext tableContext = TableGenerationContext.builder()
                  .table(table)
                  .generatedCount(generatedCount)
                  .rows(rows)
                  .constraints(constraints)
                  .isFkColumn(isFkColumn)
                  .excluded(excluded)
                  .seenPrimaryKeys(seenPrimaryKeys)
                  .seenUniqueKeyCombinations(seenUniqueKeyCombinations)
                  .softDeleteCols(softDeleteCols)
                  .multiColumnConstraints(parseMultiColumnConstraints(table.checks()))
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
          
          rule.randomConstantColumns().forEach(colName -> {
              final Column col = context.table().column(colName);
              if (col != null) {
                  final Object val = generateColumnValue(
                      GenerateSingleRowParameters.builder()
                          .faker(params.faker())
                          .table(context.table())
                          .index(context.generatedCount().get())
                          .usedUuids(params.usedUuids())
                          .constraints(context.constraints())
                          .isFkColumn(context.isFkColumn())
                          .excluded(context.excluded())
                          .dictionaryWords(params.dictionaryWords())
                          .useLatinDictionary(params.useLatinDictionary())
                          .softDeleteCols(context.softDeleteCols())
                          .softDeleteUseSchemaDefault(params.softDeleteUseSchemaDefault())
                          .softDeleteValue(params.softDeleteValue())
                          .numericScale(params.numericScale())
                          .aiService(params.aiService())
                          .build(),
                      col
                  );
                  baseValues.put(colName, val);
              }
          });

          for (int i = 0; i < rule.count(); i++) {
              int attempts = 0;
              while (attempts < MAX_GENERATE_ATTEMPTS) {
                  final Optional<Row> generatedRow = generateAndValidateRowWithBase(
                      createRowParams(params, context),
                      baseValues
                  );
                  
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
      final GenerateTableRowsParameters params,
      final TableGenerationContext context) {
      
      int attempts = 0;
      while (context.generatedCount().get() < params.rowsPerTable()
          && attempts < params.rowsPerTable() * MAX_GENERATE_ATTEMPTS) {

        final Optional<Row> generatedRow =
            generateAndValidateRow(
                createRowParams(params, context));

        generatedRow.ifPresent(
            row -> {
              context.rows().add(row);
              context.generatedCount().incrementAndGet();
            });
        attempts++;
      }
  }

  private static GenerateAndValidateRowParameters createRowParams(
      final GenerateTableRowsParameters params,
      final TableGenerationContext context) {
      return GenerateAndValidateRowParameters.builder()
          .table(context.table())
          .generatedCount(context.generatedCount().get())
          .faker(params.faker())
          .usedUuids(params.usedUuids())
          .constraints(context.constraints())
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
          .multiColumnConstraints(context.multiColumnConstraints())
          .relevantUniqueKeys(context.relevantUniqueKeys())
          .aiService(params.aiService())
          .build();
  }

  @SuppressWarnings("java:S2245")
  private static Object generateColumnValue(final GenerateSingleRowParameters params, final Column column) {
    if (params.excluded().contains(column.name())) {
      return null;
    }
    if (params.isFkColumn().test(column) && !column.primaryKey()) {
      return null;
    }

    if (params.aiService() != null && isTextJdbc(column.jdbcType())) {
        final String aiValue = params.aiService().generateSemanticValue(column.name(), column.typeName());
        if (aiValue != null && isValidAiValue(aiValue, column, params.constraints().get(column.name()))) {
            return aiValue;
        }
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

    final ParsedConstraint pc = params.constraints().get(column.name());
    final Object gen =
        generateValue(
            params.faker(),
            column,
            params.index(),
            params.usedUuids(),
            pc,
            params.dictionaryWords(),
            params.useLatinDictionary(),
            params.numericScale());

    return applyNumericConstraints(column, pc, gen, params.numericScale());
  }

  private static boolean isTextJdbc(final int jdbcType) {
      return switch (jdbcType) {
          case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> true;
          default -> false;
      };
  }

  private static boolean isValidAiValue(final String value, final Column column, final ParsedConstraint pc) {
      if (column.length() > 0 && value.length() > column.length()) {
          return false;
      }
      if (pc != null && pc.maxLength() != null && value.length() > pc.maxLength()) {
          return false;
      }
      if (pc != null && pc.allowedValues() != null && !pc.allowedValues().isEmpty()) {
          return pc.allowedValues().contains(value);
      }
      return true;
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

  private static Object applyNumericConstraints(
      final Column column, final ParsedConstraint pc, final Object generatedValue, final int numericScale) {
    if (isNumericJdbc(column.jdbcType()) && pc != null && (pc.min() != null || pc.max() != null)) {
      Object currentGen = generatedValue;
      int attempts = 0;
      while (isNumericOutsideBounds(currentGen, pc) && attempts < MAX_GENERATE_ATTEMPTS) {
        currentGen = generateNumericWithinBounds(column, pc, numericScale);
        attempts++;
      }
      return currentGen;
    }
    return generatedValue;
  }

  private static Optional<Row> generateAndValidateRow(final GenerateAndValidateRowParameters params) {
      return generateAndValidateRowWithBase(params, Collections.emptyMap());
  }

  private static Optional<Row> generateAndValidateRowWithBase(
      final GenerateAndValidateRowParameters params, final Map<String, Object> baseValues) {

    final Map<String, Object> values = new LinkedHashMap<>();
    
    if (baseValues != null) {
        values.putAll(baseValues);
    }

    if (params.multiColumnConstraints() != null) {
      for (final MultiColumnConstraint mcc : params.multiColumnConstraints()) {
        final List<Map<String, String>> candidates = mcc.allowedCombinations();
        final List<Map<String, String>> filtered = new ArrayList<>();

        for (final Map<String, String> combo : candidates) {
          boolean match = true;
          for (final Map.Entry<String, String> entry : combo.entrySet()) {
            final String col = entry.getKey();
            final String valStr = entry.getValue();
            if (values.containsKey(col)) {
              final Object existingVal = values.get(col);
              if (!String.valueOf(existingVal).equals(valStr)) {
                match = false;
                break;
              }
            }
          }
          if (match) filtered.add(combo);
        }

        if (filtered.isEmpty()) {
          return Optional.empty();
        }

        final Map<String, String> chosen =
            filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));

        for (final Map.Entry<String, String> entry : chosen.entrySet()) {
          final String colName = entry.getKey();
          final String valStr = entry.getValue();
          if (!values.containsKey(colName)) {
            final Column col = params.table().column(colName);
            if (col != null) {
              values.put(colName, convertStringValue(valStr, col));
            }
          }
        }
      }
    }

    params.table().columns().forEach(column -> {
        if (!values.containsKey(column.name())) {
             values.put(column.name(), generateColumnValue(
                 GenerateSingleRowParameters.builder()
                    .faker(params.faker())
                    .table(params.table())
                    .index(params.generatedCount())
                    .usedUuids(params.usedUuids())
                    .constraints(params.constraints())
                    .isFkColumn(params.isFkColumn())
                    .excluded(params.excluded())
                    .dictionaryWords(params.dictionaryWords())
                    .useLatinDictionary(params.useLatinDictionary())
                    .softDeleteCols(params.softDeleteCols())
                    .softDeleteUseSchemaDefault(params.softDeleteUseSchemaDefault())
                    .softDeleteValue(params.softDeleteValue())
                    .numericScale(params.numericScale())
                    .aiService(params.aiService())
                    .build(),
                 column
             ));
        }
    });

    if (!isPrimaryKeyUnique(params.table(), values, params.seenPrimaryKeys())) {
      return Optional.empty();
    }

    if (!areUniqueKeysUnique(params.relevantUniqueKeys(), values, params.seenUniqueKeyCombinations())) {
      return Optional.empty();
    }

    return Optional.of(new Row(values));
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

  private static void validateNumericConstraints(
      final List<Table> orderedTables,
      final Map<String, Map<String, ParsedConstraint>> tableConstraints,
      final Map<Table, List<Row>> data,
      final int numericScale) {

    for (final Table table : orderedTables) {
      final Map<String, ParsedConstraint> constraints =
          tableConstraints.getOrDefault(table.name(), Map.of());
      final List<Row> rows = data.get(table);
      if (rows == null) continue;
      for (final Row row : rows) {
        validateRowNumericConstraints(table, row, constraints, numericScale);
      }
    }
  }

  private static void validateRowNumericConstraints(
      final Table table, final Row row, final Map<String, ParsedConstraint> constraints, final int numericScale) {
    for (final Column col : table.columns()) {
      final ParsedConstraint pc = constraints.get(col.name());
      Object val = row.values().get(col.name());
      if (isNumericJdbc(col.jdbcType()) && pc != null && (pc.min() != null || pc.max() != null)) {
        int attempts = 0;
        while (isNumericOutsideBounds(val, pc) && attempts < MAX_GENERATE_ATTEMPTS) {
          val = generateNumericWithinBounds(col, pc, numericScale);
          attempts++;
        }
        row.values().put(col.name(), val);
      }
    }
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

  private static void resolveForeignKeysForTable(final Table table, final ForeignKeyResolutionContext context) {
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
      final Faker faker,
      final Column column,
      final int index,
      final Set<UUID> usedUuids,
      final ParsedConstraint pc,
      final List<String> dictionaryWords,
      final boolean useLatinDictionary,
      final int numericScale) {
    if (column.uuid()) {
      return generateUuidValue(column, usedUuids, pc);
    }

    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(column.allowedValues()), column.jdbcType());
    }

    if (pc != null && pc.allowedValues() != null && !pc.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(pc.allowedValues()), column.jdbcType());
    }

    final ParsedConstraint effectivePc = determineEffectiveNumericConstraint(column, pc);
    if (effectivePc.min() != null || effectivePc.max() != null) {
      final Object bounded = generateNumericWithinBounds(column, effectivePc, numericScale);
      if (bounded != null) return bounded;
    }

    Integer maxLen = pc != null ? pc.maxLength() : null;
    if (maxLen == null || maxLen <= 0) maxLen = column.length() > 0 ? column.length() : null;

    return generateDefaultValue(
        faker, column, index, maxLen, dictionaryWords, useLatinDictionary, numericScale);
  }

  private static Object generateUuidValue(final Column column, final Set<UUID> usedUuids, final ParsedConstraint pc) {
    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      final UUID uuid = tryParseUuidFromAllowedValues(column.allowedValues(), usedUuids);
      if (uuid != null) return uuid;
    }

    if (pc != null && pc.allowedValues() != null && !pc.allowedValues().isEmpty()) {
      final UUID uuid = tryParseUuidFromAllowedValues(pc.allowedValues(), usedUuids);
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

  private static ParsedConstraint determineEffectiveNumericConstraint(
      final Column column, final ParsedConstraint pc) {
    final Double pcMin = pc != null ? pc.min() : null;
    final Double pcMax = pc != null ? pc.max() : null;
    final Double cmin = column.minValue() != 0 ? (double) column.minValue() : null;
    final Double cmax = column.maxValue() != 0 ? (double) column.maxValue() : null;
    final Double effectiveMin = (pcMin != null) ? pcMin : cmin;
    final Double effectiveMax = (pcMax != null) ? pcMax : cmax;
    return new ParsedConstraint(
        effectiveMin,
        effectiveMax,
        pc != null ? pc.allowedValues() : Collections.emptySet(),
        pc != null ? pc.maxLength() : null);
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

  private static int getIntMin(final Column column, final ParsedConstraint pc) {
    final boolean hasMin = pc != null && pc.min() != null;
    final int colMinValue = column.minValue() != 0 ? column.minValue() : 1;
    return hasMin ? pc.min().intValue() : colMinValue;
  }

  private static int getIntMax(final Column column, final ParsedConstraint pc) {
    final boolean hasMax = pc != null && pc.max() != null;
    final int colMaxValue = column.maxValue() != 0 ? column.maxValue() : DEFAULT_INT_MAX;
    return hasMax ? pc.max().intValue() : colMaxValue;
  }

  @SuppressWarnings("java:S2245")
  private static Integer boundedInt(final Column column) {
    int min = getIntMin(column, null);
    int max = getIntMax(column, null);
    if (min > max) {
      final int t = min;
      min = max;
      max = t;
    }
    final long v = ThreadLocalRandom.current().nextLong(min, (long) max + 1);
    return Math.toIntExact(Math.clamp(v, Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  private static long getLongMin(final Column column, final ParsedConstraint pc) {
    final boolean hasMin = pc != null && pc.min() != null;
    final long colMinValue = column.minValue() != 0 ? column.minValue() : 1L;
    return hasMin ? pc.min().longValue() : colMinValue;
  }

  private static long getLongMax(final Column column, final ParsedConstraint pc) {
    final boolean hasMax = pc != null && pc.max() != null;
    final long colMaxValue = column.maxValue() != 0 ? column.maxValue() : DEFAULT_LONG_MAX;
    return hasMax ? pc.max().longValue() : colMaxValue;
  }

  @SuppressWarnings("java:S2245")
  private static Long boundedLong(final Column column) {
    long min = getLongMin(column, null);
    long max = getLongMax(column, null);
    if (min > max) {
      final long t = min;
      min = max;
      max = t;
    }
    return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
  }

  private static double getDoubleMin(final Column column, final ParsedConstraint pc, final int numericScale) {
    final boolean hasMin = pc != null && pc.min() != null;
    final double colMinValue = column.minValue() != 0 ? column.minValue() : 1.0;
    if (hasMin) return pc.min();

    if (column.minValue() == 0 && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)) {
      final double max = getDoubleMax(column, pc, numericScale);
      if (colMinValue > max) {
        return 0.0;
      }
    }
    return colMinValue;
  }

  private static double getDoubleMax(final Column column, final ParsedConstraint pc, final int numericScale) {
    final boolean hasMax = pc != null && pc.max() != null;
    final double colMaxValue = column.maxValue() != 0 ? column.maxValue() : DEFAULT_DECIMAL_MAX;
    if (hasMax) return pc.max();

    if (column.maxValue() == 0 && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC) && column.length() > 0) {
        final int precision = column.length();
        final int scale = getEffectiveScale(column, numericScale);
        return Math.pow(10.0, (double) precision - scale) - Math.pow(10.0, -scale);
    }
    return colMaxValue;
  }

  private static double[] getNumericBounds(final Column column, final ParsedConstraint pc, final int numericScale) {
      double min = getDoubleMin(column, pc, numericScale);
      double max = getDoubleMax(column, pc, numericScale);
      if (min > max) {
          return new double[]{max, min};
      }
      return new double[]{min, max};
    }

  private static double generateRandomDouble(final double min, final double max) {
      return min + (max - min) * ThreadLocalRandom.current().nextDouble();
  }

  @SuppressWarnings("java:S2245")
  private static BigDecimal boundedBigDecimal(final Column column, final int numericScale) {
    final double[] bounds = getNumericBounds(column, null, numericScale);
    final double val = generateRandomDouble(bounds[0], bounds[1]);
    final int scale = getEffectiveScale(column, numericScale);
    return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
  }

  @SuppressWarnings("java:S2245")
  private static Double boundedDouble(final Column column, final int numericScale) {
    final double[] bounds = getNumericBounds(column, null, numericScale);
    final double val = generateRandomDouble(bounds[0], bounds[1]);
    final int scale = getEffectiveScale(column, numericScale);
    return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  @SuppressWarnings("java:S2245")
  private static Object generateNumericWithinBounds(final Column column, final ParsedConstraint pc, final int numericScale) {
    switch (column.jdbcType()) {
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
        int min = getIntMin(column, pc);
        int max = getIntMax(column, pc);
        if (min > max) {
          final int t = min;
          min = max;
          max = t;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
      }
      case Types.BIGINT -> {
        long min = getLongMin(column, pc);
        long max = getLongMax(column, pc);
        if (min > max) {
          final long t = min;
          min = max;
          max = t;
        }
        return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
      }
      case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.DOUBLE, Types.REAL -> {
        final double[] bounds = getNumericBounds(column, pc, numericScale);
        final double val = generateRandomDouble(bounds[0], bounds[1]);
        final int scale = getEffectiveScale(column, numericScale);
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
      }
      default -> {
        return null;
      }
    }
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
      final boolean useLatinDictionary, final boolean useEnglishDictionary, final boolean useSpanishDictionary) {
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

  private static boolean isNumericJdbc(final int jdbcType) {
    return switch (jdbcType) {
      case Types.INTEGER,
          Types.SMALLINT,
          Types.TINYINT,
          Types.BIGINT,
          Types.DECIMAL,
          Types.NUMERIC,
          Types.FLOAT,
          Types.DOUBLE,
          Types.REAL ->
          true;
      default -> false;
    };
  }

  private static boolean isNumericOutsideBounds(final Object value, final ParsedConstraint pc) {
    if (value == null || pc == null) return false;
    try {
      final double v;
      if (value instanceof Number n) v = n.doubleValue();
      else v = Double.parseDouble(value.toString());
      return (pc.min() != null && v < pc.min()) || (pc.max() != null && v > pc.max());
    } catch (final NumberFormatException e) {
      log.debug("Value '{}' is not a valid number for numeric constraint check.", value, e);
      return true;
    }
  }

  private static ParsedConstraint parseConstraintsForColumn(
      final List<CheckExpression> checkExpressions, final String columnName, final int columnLength) {
    if (checkExpressions == null || checkExpressions.isEmpty())
      return new ParsedConstraint(null, null, Collections.emptySet(), null);

    Double lower = null;
    Double upper = null;
    final Set<String> allowed = new HashSet<>();
    Integer maxLen = null;

    final ColumnPatterns patterns = getColumnPatterns(columnName);
    final String columnLow = columnName.toLowerCase(Locale.ROOT);

    for (final CheckExpression ce : checkExpressions) {
      if (!ce.noParensLow().contains(columnLow)) continue;

      final String check = ce.original();
      final String exprNoParens = ce.noParens();

      final BetweenParseResult betweenResult =
          parseBetweenConstraint(exprNoParens, patterns.between(), check, lower, upper);
      lower = betweenResult.lower();
      upper = betweenResult.upper();

      final RangeParseResult rangeResult =
          parseRangeConstraint(exprNoParens, patterns.range(), check, lower, upper);
      lower = rangeResult.lower();
      upper = rangeResult.upper();

      parseInListConstraint(check, patterns.in(), allowed);
      parseAnyArrayConstraint(exprNoParens, patterns.anyArray(), allowed);
      parseEqualityConstraint(exprNoParens, patterns.eq(), allowed);
      maxLen = parseLengthConstraint(check, patterns.len(), maxLen);
    }

    if (columnLength > 0 && (maxLen == null || columnLength < maxLen)) {
      maxLen = columnLength;
    }

    return new ParsedConstraint(lower, upper, Set.copyOf(allowed), maxLen);
  }

  private static BetweenParseResult parseBetweenConstraint(
      final String exprNoParens,
      final Pattern betweenPattern,
      final String check,
      final Double currentLower,
      final Double currentUpper) {
    final Matcher mb = betweenPattern.matcher(exprNoParens);
    Double newLower = currentLower;
    Double newUpper = currentUpper;
    while (mb.find()) {
      try {
        final double a = Double.parseDouble(mb.group(1));
        final double b = Double.parseDouble(mb.group(2));
        final double lo = Math.min(a, b);
        final double hi = Math.max(a, b);
        newLower = (newLower == null) ? lo : Math.max(newLower, lo);
        newUpper = (newUpper == null) ? hi : Math.min(newUpper, hi);
      } catch (final NumberFormatException e) {
        log.debug("Failed to parse BETWEEN bounds in check: {}", check, e);
      }
    }
    return new BetweenParseResult(newLower, newUpper);
  }

  private static RangeParseResult parseRangeConstraint(
      final String exprNoParens,
      final Pattern rangePattern,
      final String check,
      final Double currentLower,
      final Double currentUpper) {
    final Matcher mr = rangePattern.matcher(exprNoParens);
    Double newLower = currentLower;
    Double newUpper = currentUpper;
    while (mr.find()) {
      final String op = mr.group(1);
      final String num = mr.group(2);
      try {
        final double val = Double.parseDouble(num);
        newLower = updateLowerBound(op, val, newLower);
        newUpper = updateUpperBound(op, val, newUpper);
      } catch (final NumberFormatException e) {
        log.debug("Failed to parse numeric range in check: {}", check, e);
      }
    }
    return new RangeParseResult(newLower, newUpper);
  }

  private static Double updateLowerBound(final String op, final double val, final Double currentLower) {
    return switch (op) {
      case ">" ->
          (currentLower == null) ? Math.nextUp(val) : Math.max(currentLower, Math.nextUp(val));
      case ">=", "=" -> (currentLower == null) ? val : Math.max(currentLower, val);
      default -> currentLower;
    };
  }

  private static Double updateUpperBound(final String op, final double val, final Double currentUpper) {
    return switch (op) {
      case "<" ->
          (currentUpper == null) ? Math.nextDown(val) : Math.min(currentUpper, Math.nextDown(val));
      case "<=", "=" -> (currentUpper == null) ? val : Math.min(currentUpper, val);
      default -> currentUpper;
    };
  }

  private static void parseInListConstraint(final String check, final Pattern inPattern, final Set<String> allowed) {
    final Matcher mi = inPattern.matcher(check);
    while (mi.find()) {
      final String inside = mi.group(1);
      Arrays.stream(inside.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .forEach(
              s -> {
                if ((s.startsWith("'") && s.endsWith("'"))
                    || (s.startsWith("\"") && s.endsWith("\""))) {
                  allowed.add(s.substring(1, s.length() - 1));
                } else {
                  allowed.add(s);
                }
              });
    }
  }

  private static void parseAnyArrayConstraint(
      final String check, final Pattern anyArrayPattern, final Set<String> allowed) {
    final Matcher ma = anyArrayPattern.matcher(check);
    while (ma.find()) {
      final String inside = ma.group(1);
      Arrays.stream(inside.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .map(s -> s.replaceAll("(?i)::[a-z ]+", "")) // Remove Postgres type casts
          .map(
              s -> {
                if ((s.startsWith("'") && s.endsWith("'"))
                    || (s.startsWith("\"") && s.endsWith("\""))) {
                  return s.substring(1, s.length() - 1);
                }
                return s;
              })
          .forEach(allowed::add);
    }
  }

  private static void parseEqualityConstraint(
      final String exprNoParens, final Pattern eqPattern, final Set<String> allowed) {
    final Matcher me = eqPattern.matcher(exprNoParens);
    while (me.find()) {
      String s = me.group(1).trim();
      if (s.startsWith("'") && s.endsWith("'")) s = s.substring(1, s.length() - 1);
      if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
      if (!s.isEmpty()) allowed.add(s);
    }
  }

  private static Integer parseLengthConstraint(
      final String check, final Pattern lenPattern, final Integer currentMaxLen) {
    final Matcher ml = lenPattern.matcher(check);
    Integer newMaxLen = currentMaxLen;
    while (ml.find()) {
      final String op = ml.group(1);
      final String num = ml.group(2);
      try {
        final int v = Integer.parseInt(num);
        if ("<".equals(op) || "<=".equals(op) || "=".equals(op)) {
          newMaxLen = (newMaxLen == null) ? v : Math.min(newMaxLen, v);
        }
      } catch (final NumberFormatException e) {
        log.debug("Failed to parse length constraint in check: {}", check, e);
      }
    }
    return newMaxLen;
  }

  private static String normalizeToLength(final String value, final int length, final int jdbcType) {
    if (length <= 0) return value;
    if (value.length() > length) {
      return value.substring(0, length);
    }
    if (jdbcType == Types.CHAR && value.length() < length) {
      return value.concat(" ".repeat(length - value.length()));
    }
    return value;
  }

  private static ColumnPatterns getColumnPatterns(final String columnName) {
    return COLUMN_PATTERNS_CACHE.computeIfAbsent(
        columnName,
        name -> {
          final String colPattern =
              "(?i)(?:[A-Za-z0-9_]+\\.)*\\s*\"?".concat(Pattern.quote(name)).concat("\"?\\s*");

          final String castPattern = "(?:\\s*::[a-zA-Z0-9 ]+)*";

          return new ColumnPatterns(
              Pattern.compile(
                  colPattern
                      .concat(castPattern)
                      .concat(
                          "\\s+BETWEEN\\s+([-+]?[0-9]+(?:\\.[0-9]+)?)\\s+AND\\s+([-+]?[0-9]+(?:\\.[0-9]+)?)"),
                  Pattern.CASE_INSENSITIVE),
              Pattern.compile(
                  colPattern
                      .concat(castPattern)
                      .concat("\\s*(>=|<=|>|<|=)\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)"),
                  Pattern.CASE_INSENSITIVE),
              Pattern.compile(
                  colPattern.concat(castPattern).concat("\\s+IN\\s*\\(([^)]+)\\)"),
                  Pattern.CASE_INSENSITIVE),
              Pattern.compile(
                  colPattern
                      .concat(castPattern)
                      .concat("\\s*=\\s*ANY\\s+ARRAY\\s*\\[(.*?)\\]"),
                  Pattern.CASE_INSENSITIVE),
              Pattern.compile(
                  colPattern
                      .concat(castPattern)
                      .concat("\\s*=\\s*(?!ANY\\b)('.*?'|\".*?\"|[0-9A-Za-z_+-]+)"),
                  Pattern.CASE_INSENSITIVE),
              Pattern.compile(
                  "(?i)(?:char_length|length)\\s*\\(\\s*"
                      .concat(colPattern)
                      .concat("\\s*\\)\\s*(<=|<|=)\\s*(\\d+)")));
        });
  }


  private static int getEffectiveScale(final Column column, final int numericScale) {
    return column.scale() > 0 ? column.scale() : numericScale;
  }

  private static List<MultiColumnConstraint> parseMultiColumnConstraints(final List<String> checks) {
    final List<MultiColumnConstraint> result = new ArrayList<>();
    for (final String check : checks) {
      if ((check.toUpperCase(Locale.ROOT).contains(" OR ")
          || check.toUpperCase(Locale.ROOT).contains(" AND "))
          && check.contains("=")) {
        final MultiColumnConstraint mcc = parseDnfConstraint(check);
        if (mcc != null) {
          result.add(mcc);
        }
      }
    }
    return result;
  }

  private static MultiColumnConstraint parseDnfConstraint(final String check) {
    String clean = check.trim();
    while (clean.startsWith("(") && clean.endsWith(")")) {
      if (isWrappedInParens(clean)) {
        clean = clean.substring(1, clean.length() - 1).trim();
      } else {
        break;
      }
    }

    final String[] clauses = clean.split("(?i)\\s+OR\\s+");
    final List<Map<String, String>> combinations = new ArrayList<>();
    final Set<String> columns = new HashSet<>();

    for (final String clause : clauses) {
      final Map<String, String> combination = new HashMap<>();
      final String[] conditions = clause.split("(?i)\\s+AND\\s+");
      for (final String cond : conditions) {
        String cleanCond = cond.trim();
        while (cleanCond.startsWith("(") && cleanCond.endsWith(")") && isWrappedInParens(cleanCond)) {
          cleanCond = cleanCond.substring(1, cleanCond.length() - 1).trim();
        }

        final Matcher m = COL_EQ_VAL_PATTERN.matcher(cleanCond);
        if (m.find()) {
          final String col = m.group(1).replaceAll("\"", "");
          String val = m.group(2);
          if ((val.startsWith("'") && val.endsWith("'"))
              || (val.startsWith("\"") && val.endsWith("\""))) {
            val = val.substring(1, val.length() - 1);
          }
          combination.put(col, val);
        }
      }
      if (combination.isEmpty()) return null;
      combinations.add(combination);
      if (columns.isEmpty()) {
        columns.addAll(combination.keySet());
      } else {
        if (!columns.equals(combination.keySet())) {
          return null;
        }
      }
    }

    if (combinations.isEmpty()) return null;
    return new MultiColumnConstraint(columns, combinations);
  }

  private static boolean isWrappedInParens(final String s) {
    if (!s.startsWith("(") || !s.endsWith(")")) return false;
    int balance = 0;
    for (int i = 0; i < s.length() - 1; i++) {
      final char c = s.charAt(i);
      if (c == '(') balance++;
      else if (c == ')') balance--;
      if (balance == 0) return false;
    }
    return true;
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
      int numericScale,
      LocalAIConfiguration aiConfig) {}

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
      int numericScale,
      LocalAIConfiguration aiConfig) {}

  private record GenerationContext(
      List<Table> orderedTables,
      int rowsPerTable,
      Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      Faker faker,
      Set<UUID> usedUuids,
      Map<String, Map<String, ParsedConstraint>> tableConstraints,
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
      int numericScale,
      LocalAIService aiService) {

    GenerationContext(
        final List<Table> orderedTables,
        final int rowsPerTable,
        final Map<String, Set<String>> excludedColumns,
        final Map<String, List<RepetitionRule>> repetitionRules,
        final Faker faker,
        final Set<UUID> usedUuids,
        final Map<String, Map<String, ParsedConstraint>> tableConstraints,
        final Map<Table, List<Row>> data,
        final List<String> dictionaryWords,
        final Map<String, Table> tableMap,
        final boolean deferred,
        boolean useLatinDictionary,
        final String softDeleteColumns,
        final boolean softDeleteUseSchemaDefault,
        final String softDeleteValue,
        final int numericScale,
        final LocalAIService aiService) {
      this(
          orderedTables,
          rowsPerTable,
          excludedColumns,
          repetitionRules,
          faker,
          usedUuids,
          tableConstraints,
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
          numericScale,
          aiService);
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
      Map<String, Map<String, ParsedConstraint>> tableConstraints,
      Map<Table, List<Row>> data,
      List<String> dictionaryWords,
      boolean useLatinDictionary,
      String softDeleteColumns,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale,
      LocalAIService aiService) {}

  @Builder
  private record TableGenerationContext(
      Table table,
      AtomicInteger generatedCount,
      List<Row> rows,
      Map<String, ParsedConstraint> constraints,
      Predicate<Column> isFkColumn,
      Set<String> excluded,
      Set<String> seenPrimaryKeys,
      Map<String, Set<String>> seenUniqueKeyCombinations,
      Set<String> softDeleteCols,
      List<MultiColumnConstraint> multiColumnConstraints,
      List<List<String>> relevantUniqueKeys
  ) {}

  @Builder
  private record GenerateSingleRowParameters(
      Faker faker,
      Table table,
      int index,
      Set<UUID> usedUuids,
      Map<String, ParsedConstraint> constraints,
      Predicate<Column> isFkColumn,
      Set<String> excluded,
      List<String> dictionaryWords,
      boolean useLatinDictionary,
      Set<String> softDeleteCols,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale,
      LocalAIService aiService) {}

  @Builder
  private record GenerateAndValidateRowParameters(
      Table table,
      int generatedCount,
      Faker faker,
      Set<UUID> usedUuids,
      Map<String, ParsedConstraint> constraints,
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
      List<MultiColumnConstraint> multiColumnConstraints,
      List<List<String>> relevantUniqueKeys,
      LocalAIService aiService) {}

  private record ForeignKeyResolutionContext(
      Map<String, Table> tableMap,
      Map<Table, List<Row>> data,
      boolean deferred,
      List<PendingUpdate> updates,
      Set<String> inserted,
      Map<String, Deque<Row>> uniqueFkParentQueues) {}

  private record BetweenParseResult(Double lower, Double upper) {}

  private record RangeParseResult(Double lower, Double upper) {}

  private record ParsedConstraint(
      Double min, Double max, Set<String> allowedValues, Integer maxLength) {}

  private record MultiColumnConstraint(Set<String> columns, List<Map<String, String>> allowedCombinations) {}

  private record ColumnPatterns(
      Pattern between,
      Pattern range,
      Pattern in,
      Pattern anyArray,
      Pattern eq,
      Pattern len) {}

  private record CheckExpression(String original, String noParens, String noParensLow) {}

  public record GenerationResult(Map<Table, List<Row>> rows, List<PendingUpdate> updates) {}
}
