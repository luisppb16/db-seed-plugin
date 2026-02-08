/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luisppb16.dbseed.ai.OllamaService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

@Slf4j
@UtilityClass
public class DataGenerator {

  private static final int MAX_GENERATE_ATTEMPTS = 5;
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

  private static final OllamaService AI_SERVICE = new OllamaService();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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
                  t.name(), newCols, t.primaryKey(), t.foreignKeys(), t.checks(), t.uniqueKeys(), t.ddl()));
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
    final Set<UUID> usedUuids = Collections.synchronizedSet(new HashSet<>());

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
    generateTableRows(context);
    resolveForeignKeys(context);
  }

  private static void generateTableRows(final GenerationContext context) {
    final Set<String> softDeleteCols = new HashSet<>();
    if (context.softDeleteColumns() != null) {
      Arrays.stream(context.softDeleteColumns().split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .forEach(softDeleteCols::add);
    }

    context.orderedTables().forEach(table -> {
      if (table.columns().isEmpty()) {
        context.data().put(table, Collections.emptyList());
        return;
      }

      final List<Row> rows = Collections.synchronizedList(new ArrayList<>());
      final Set<String> fkColumnNames = table.fkColumnNames();
      final Predicate<Column> isFkColumn = column -> fkColumnNames.contains(column.name());
      final Set<String> seenPrimaryKeys = Collections.synchronizedSet(new HashSet<>());
      final Map<String, Set<String>> seenUniqueKeyCombinations = new HashMap<>();
      final Set<String> excluded = context.excludedColumns().getOrDefault(table.name(), Set.of());
      final AtomicInteger generatedCount = new AtomicInteger(0);

      final TableGenerationContext tableContext = TableGenerationContext.builder()
          .table(table)
          .generatedCount(generatedCount)
          .rows(rows)
          .isFkColumn(isFkColumn)
          .excluded(excluded)
          .seenPrimaryKeys(seenPrimaryKeys)
          .seenUniqueKeyCombinations(seenUniqueKeyCombinations)
          .softDeleteCols(softDeleteCols)
          .build();

      processRepetitionRules(context, tableContext);
      fillRemainingRows(context, tableContext);
      context.data().put(table, new ArrayList<>(rows));
    });
  }

  private static void processRepetitionRules(final GenerationContext context, final TableGenerationContext tableContext) {
    List<RepetitionRule> rules = context.repetitionRules().getOrDefault(tableContext.table().name(), Collections.emptyList());
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (RepetitionRule rule : rules) {
      Map<String, Object> baseValues = new HashMap<>(rule.fixedValues());
      rule.randomConstantColumns().forEach(colName -> {
        Column col = tableContext.table().column(colName);
        if (col != null) {
          baseValues.put(colName, generateInitialValue(context, tableContext, col));
        }
      });

      for (int i = 0; i < rule.count(); i++) {
        futures.add(CompletableFuture.runAsync(() -> {
            generateAndValidateRow(context, tableContext, baseValues).ifPresent(r -> {
              tableContext.rows().add(r);
              tableContext.generatedCount().incrementAndGet();
            });
        }, VIRTUAL_EXECUTOR));
      }
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
  }

  private static void fillRemainingRows(final GenerationContext context, final TableGenerationContext tableContext) {
    int target = context.rowsPerTable();
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    while (tableContext.generatedCount().get() < target) {
      int remaining = target - tableContext.generatedCount().get();
      for (int i = 0; i < remaining; i++) {
        futures.add(CompletableFuture.runAsync(() -> {
            generateAndValidateRow(context, tableContext, Collections.emptyMap()).ifPresent(r -> {
              tableContext.rows().add(r);
              tableContext.generatedCount().incrementAndGet();
            });
        }, VIRTUAL_EXECUTOR));
      }
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      futures.clear();
      if (tableContext.generatedCount().get() < target) {
          try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
    }
  }

  private static Optional<Row> generateAndValidateRow(final GenerationContext context, final TableGenerationContext tableContext, Map<String, Object> baseValues) {
    Map<String, Object> values = new LinkedHashMap<>(baseValues);
    for (Column col : tableContext.table().columns()) {
        if (!values.containsKey(col.name())) {
            values.put(col.name(), generateInitialValue(context, tableContext, col));
        }
    }
    return validateRowWithAi(tableContext.table(), values, tableContext.seenPrimaryKeys());
  }

  private static Object generateInitialValue(GenerationContext context, TableGenerationContext tableContext, Column col) {
    if (tableContext.excluded().contains(col.name())) return null;
    if (tableContext.isFkColumn().test(col) && !col.primaryKey()) return null;
    if (tableContext.softDeleteCols().contains(col.name())) {
      return context.softDeleteUseSchemaDefault() ? SqlKeyword.DEFAULT : convertStringValue(context.softDeleteValue(), col);
    }
    if (col.nullable() && ThreadLocalRandom.current().nextDouble() < 0.2) return null;
    if (col.uuid()) return generateUuid(context.usedUuids());
    return generateDefaultValue(context.faker(), col, tableContext.generatedCount().get(),
                               col.length(), context.dictionaryWords(), context.useLatinDictionary(), context.numericScale());
  }

  private static Optional<Row> validateRowWithAi(Table table, Map<String, Object> values, Set<String> seenPks) {
    String ddl = table.ddl();
    if (ddl == null || ddl.isBlank()) {
        return Optional.of(new Row(values));
    }

    try {
      String jsonValues = OBJECT_MAPPER.writeValueAsString(values);
      String prompt = String.format(
          "Analyze this SQL DDL:\n%s\n\n" +
          "And these generated values for one row (in JSON):\n%s\n\n" +
          "Ensure all domain constraints, checks, and data types are respected. " +
          "If a value is invalid, fix it. Return ONLY the corrected row in JSON format.",
          ddl, jsonValues);

      String response = AI_SERVICE.ask(prompt);
      if (response.contains("```json")) {
        response = response.substring(response.indexOf("```json") + 7);
        response = response.substring(0, response.indexOf("```"));
      }
      Map<String, Object> correctedValues = OBJECT_MAPPER.readValue(response, new TypeReference<Map<String, Object>>() {});

      if (!table.primaryKey().isEmpty()) {
        String pkKey = table.primaryKey().stream()
            .map(pk -> Objects.toString(correctedValues.get(pk), "NULL"))
            .collect(Collectors.joining("|"));
        if (!seenPks.add(pkKey)) return Optional.empty();
      }
      return Optional.of(new Row(correctedValues));
    } catch (Exception e) {
      log.warn("AI validation failed for table {}, using original values: {}", table.name(), e.getMessage());
      return Optional.of(new Row(values));
    }
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
      return null;
    }
  }

  private static void resolveForeignKeys(final GenerationContext context) {
    final ForeignKeyResolutionContext fkContext = new ForeignKeyResolutionContext(
            context.tableMap(), context.data(), context.deferred(), context.updates(), context.inserted(), context.uniqueFkParentQueues());
    context.orderedTables().forEach(table -> resolveForeignKeysForTable(table, fkContext));
  }

  private static void resolveForeignKeysForTable(final Table table, final ForeignKeyResolutionContext context) {
    final List<Row> rows = Objects.requireNonNull(context.data().get(table));
    final Map<ForeignKey, Boolean> fkNullableCache = table.foreignKeys().stream()
            .collect(Collectors.toMap(fk -> fk, fk -> fk.columnMapping().keySet().stream().map(table::column).allMatch(Column::nullable)));
    rows.forEach(row -> table.foreignKeys().forEach(fk ->
        resolveSingleForeignKey(fk, table, row, fkNullableCache.getOrDefault(fk, false), context)));
    context.inserted().add(table.name());
  }

  private static void resolveSingleForeignKey(final ForeignKey fk, final Table table, final Row row, final boolean fkNullable, final ForeignKeyResolutionContext context) {
    final Table parent = context.tableMap().get(fk.pkTable());
    if (parent == null) {
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }
    final List<Row> parentRows = context.data().get(parent);
    final boolean parentInserted = context.inserted().contains(parent.name());
    final Row parentRow = getParentRowForForeignKey(fk, parentRows, context.uniqueFkParentQueues(), table.name(), parent.name(), fkNullable);
    if (parentRow == null) {
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }
    if (parentInserted || context.deferred()) {
      fk.columnMapping().forEach((fkCol, pkCol) -> row.values().put(fkCol, parentRow.values().get(pkCol)));
    } else {
      if (!fkNullable) throw new IllegalStateException("Cycle with non-nullable FK: " + table.name() + " -> " + parent.name());
      final Map<String, Object> fkVals = new LinkedHashMap<>();
      fk.columnMapping().forEach((fkCol, pkCol) -> {
                fkVals.put(fkCol, parentRow.values().get(pkCol));
                row.values().put(fkCol, null);
              });
      final Map<String, Object> pkVals = new LinkedHashMap<>();
      table.primaryKey().forEach(pkCol -> pkVals.put(pkCol, row.values().get(pkCol)));
      context.updates().add(new PendingUpdate(table.name(), fkVals, pkVals));
    }
  }

  private static Row getParentRowForForeignKey(final ForeignKey fk, final List<Row> parentRows, final Map<String, Deque<Row>> uniqueFkParentQueues, final String tableName, final String parentTableName, final boolean fkNullable) {
    if (parentRows == null || parentRows.isEmpty()) return null;
    if (fk.uniqueOnFk()) {
      final String key = tableName + "|" + fk.name();
      final Deque<Row> queue = uniqueFkParentQueues.computeIfAbsent(key, k -> {
                final List<Row> shuffled = new ArrayList<>(parentRows);
                Collections.shuffle(shuffled, ThreadLocalRandom.current());
                return new ArrayDeque<>(shuffled);
              });
      if (queue.isEmpty()) {
        if (!fkNullable) throw new IllegalStateException("Not enough rows in " + parentTableName + " for non-nullable 1:1 FK from " + tableName);
        return null;
      }
      return queue.pollFirst();
    } else {
      return parentRows.get(ThreadLocalRandom.current().nextInt(parentRows.size()));
    }
  }

  private static Object generateDefaultValue(final Faker faker, final Column column, final int index, final Integer maxLen, final List<String> dictionaryWords, final boolean useLatinDictionary, final int numericScale) {
    return switch (column.jdbcType()) {
      case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR ->
          generateString(faker, maxLen, column.jdbcType(), dictionaryWords, useLatinDictionary);
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> boundedInt(column);
      case Types.BIGINT -> boundedLong(column);
      case Types.BOOLEAN, Types.BIT -> faker.bool().bool();
      case Types.DATE -> Date.valueOf(LocalDate.now().minusDays(faker.number().numberBetween(0, 3650)));
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Timestamp.from(Instant.now().minusSeconds(faker.number().numberBetween(0, 31_536_000)));
      case Types.DECIMAL, Types.NUMERIC -> boundedBigDecimal(column, numericScale);
      case Types.FLOAT, Types.DOUBLE, Types.REAL -> boundedDouble(column, numericScale);
      default -> index;
    };
  }

  private static UUID generateUuid(final Set<UUID> usedUuids) {
    for (int i = 0; i < UUID_GENERATION_LIMIT; i++) {
      final UUID u = UUID.randomUUID();
      if (usedUuids.add(u)) return u;
    }
    throw new IllegalStateException("Unable to generate a unique UUID");
  }

  private static Integer boundedInt(final Column column) {
    int min = column.minValue() != 0 ? column.minValue() : 1;
    int max = column.maxValue() != 0 ? column.maxValue() : DEFAULT_INT_MAX;
    return ThreadLocalRandom.current().nextInt(Math.min(min, max), Math.max(min, max) + 1);
  }

  private static Long boundedLong(final Column column) {
    long min = column.minValue() != 0 ? column.minValue() : 1L;
    long max = column.maxValue() != 0 ? column.maxValue() : DEFAULT_LONG_MAX;
    return ThreadLocalRandom.current().nextLong(Math.min(min, max), Math.max(min, max) + 1);
  }

  private static BigDecimal boundedBigDecimal(final Column column, final int numericScale) {
    double val = 1.0 + (DEFAULT_DECIMAL_MAX - 1.0) * ThreadLocalRandom.current().nextDouble();
    return BigDecimal.valueOf(val).setScale(column.scale() > 0 ? column.scale() : numericScale, RoundingMode.HALF_UP);
  }

  private static Double boundedDouble(final Column column, final int numericScale) {
    return boundedBigDecimal(column, numericScale).doubleValue();
  }

  private static String generateString(final Faker faker, final Integer maxLen, final int jdbcType, final List<String> dictionaryWords, final boolean useLatinDictionary) {
    final int len = (maxLen != null && maxLen > 0) ? maxLen : 255;
    if (len <= 5) return faker.lorem().characters(len);
    if (!dictionaryWords.isEmpty()) return dictionaryWords.get(ThreadLocalRandom.current().nextInt(dictionaryWords.size()));
    return faker.lorem().word();
  }

  private static List<String> loadDictionaryWords(final boolean useEnglishDictionary, final boolean useSpanishDictionary) {
    final List<String> words = new ArrayList<>();
    if (useEnglishDictionary) words.addAll(getOrLoad(englishDictionaryCache, ENGLISH_DICTIONARY_PATH));
    if (useSpanishDictionary) words.addAll(getOrLoad(spanishDictionaryCache, SPANISH_DICTIONARY_PATH));
    return words;
  }

  private static List<String> getOrLoad(AtomicReference<List<String>> cache, String path) {
    if (cache.get() == null) {
      synchronized (DICTIONARY_LOCK) {
        if (cache.get() == null) cache.set(readWordsFromFile(path));
      }
    }
    return cache.get();
  }

  private static List<String> readWordsFromFile(final String filePath) {
    try (final InputStream is = DataGenerator.class.getResourceAsStream(filePath)) {
      if (is == null) return Collections.emptyList();
      return Arrays.stream(new String(is.readAllBytes(), StandardCharsets.UTF_8).split("\\s+"))
          .map(String::trim).filter(s -> !s.isEmpty()).toList();
    } catch (final IOException e) {
      return Collections.emptyList();
    }
  }

  @Builder
  public record GenerationParameters(
      List<Table> tables, int rowsPerTable, boolean deferred,
      Map<String, Map<String, String>> pkUuidOverrides,
      Map<String, List<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      boolean useLatinDictionary, boolean useEnglishDictionary, boolean useSpanishDictionary,
      String softDeleteColumns, boolean softDeleteUseSchemaDefault, String softDeleteValue,
      int numericScale) {}

  @Builder
  private record GenerationInternalParameters(
      List<Table> tables, int rowsPerTable, boolean deferred,
      Map<String, Set<String>> excludedColumns, Map<String, List<RepetitionRule>> repetitionRules,
      boolean useLatinDictionary, boolean useEnglishDictionary, boolean useSpanishDictionary,
      String softDeleteColumns, boolean softDeleteUseSchemaDefault, String softDeleteValue,
      int numericScale) {}

  private record GenerationContext(
      List<Table> orderedTables, int rowsPerTable, Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules, Faker faker, Set<UUID> usedUuids,
      Map<Table, List<Row>> data, List<String> dictionaryWords, Map<String, Table> tableMap,
      boolean deferred, boolean useLatinDictionary, List<PendingUpdate> updates,
      Set<String> inserted, Map<String, Deque<Row>> uniqueFkParentQueues,
      String softDeleteColumns, boolean softDeleteUseSchemaDefault, String softDeleteValue,
      int numericScale) {
    GenerationContext(List<Table> orderedTables, int rowsPerTable, Map<String, Set<String>> excludedColumns, Map<String, List<RepetitionRule>> repetitionRules, Faker faker, Set<UUID> usedUuids, Map<Table, List<Row>> data, List<String> dictionaryWords, Map<String, Table> tableMap, boolean deferred, boolean useLatinDictionary, String softDeleteColumns, boolean softDeleteUseSchemaDefault, String softDeleteValue, int numericScale) {
      this(orderedTables, rowsPerTable, excludedColumns, repetitionRules, faker, usedUuids, data, dictionaryWords, tableMap, deferred, useLatinDictionary, new ArrayList<>(), new HashSet<>(), new HashMap<>(), softDeleteColumns, softDeleteUseSchemaDefault, softDeleteValue, numericScale);
    }
  }

  @Builder
  private record TableGenerationContext(
      Table table, AtomicInteger generatedCount, List<Row> rows,
      Predicate<Column> isFkColumn, Set<String> excluded, Set<String> seenPrimaryKeys,
      Map<String, Set<String>> seenUniqueKeyCombinations, Set<String> softDeleteCols) {}

  private record ForeignKeyResolutionContext(
      Map<String, Table> tableMap, Map<Table, List<Row>> data, boolean deferred,
      List<PendingUpdate> updates, Set<String> inserted, Map<String, Deque<Row>> uniqueFkParentQueues) {}

  public record GenerationResult(Map<Table, List<Row>> rows, List<PendingUpdate> updates) {}
}
