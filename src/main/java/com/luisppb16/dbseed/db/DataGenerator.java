/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

@Slf4j
@UtilityClass
public class DataGenerator {

  private static final Pattern SINGLE_WORD_PATTERN =
      Pattern.compile("^\\p{L}[\\p{L}\\p{N}]*$"); // Simplified regex, removed named group
  private static final int MAX_GENERATE_ATTEMPTS = 100; // Increased from 10
  private static final int DEFAULT_INT_MAX = 10_000;
  private static final int DEFAULT_LONG_MAX = 1_000_000;
  private static final int DEFAULT_DECIMAL_MAX = 1_000;
  private static final int UUID_GENERATION_LIMIT = 1_000_000;

  public static GenerationResult generate(
      List<Table> tables,
      int rowsPerTable,
      boolean deferred,
      Map<String, Map<String, String>> pkUuidOverrides,
      Map<String, List<String>> excludedColumns) {

    Map<String, Table> overridden = new LinkedHashMap<>();
    tables.forEach(
        t -> {
          Map<String, String> pkOverridesForTable =
              pkUuidOverrides != null ? pkUuidOverrides.get(t.name()) : null;
          if (pkOverridesForTable == null || pkOverridesForTable.isEmpty()) {
            overridden.put(t.name(), t);
            return;
          }
          List<Column> newCols = new ArrayList<>();
          t.columns()
              .forEach(
                  c -> {
                    boolean forceUuid = pkOverridesForTable.containsKey(c.name());
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

    List<Table> list = new ArrayList<>(overridden.values());

    // Convert excludedColumns from Map<String, List<String>> to Map<String, Set<String>>
    Map<String, Set<String>> excludedColumnsSet =
        excludedColumns.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));

    return generateInternal(list, rowsPerTable, deferred, excludedColumnsSet);
  }

  private static GenerationResult generateInternal(
      List<Table> tables,
      int rowsPerTable,
      boolean deferred,
      Map<String, Set<String>> excludedColumns) {

    Instant start = Instant.now();

    List<Table> orderedTables = orderByWordAndFk(tables);
    Map<String, Table> tableMap =
        orderedTables.stream().collect(Collectors.toUnmodifiableMap(Table::name, t -> t));

    Faker faker = new Faker();
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    Set<UUID> usedUuids = new HashSet<>();

    Map<String, Map<String, ParsedConstraint>> tableConstraints = new HashMap<>();

    // Generate rows for each table
    generateTableRows(
        orderedTables, rowsPerTable, excludedColumns, faker, usedUuids, tableConstraints, data);

    // Validation pass: ensure numeric values satisfy parsed CHECK bounds; if not, replace them.
    validateNumericConstraints(orderedTables, tableConstraints, data);

    // Ensure UUID uniqueness across generated rows (fast check/reparations)
    ensureUuidUniqueness(data, orderedTables, usedUuids);

    List<PendingUpdate> updates = new ArrayList<>();
    Set<String> inserted = new HashSet<>();
    Map<String, Deque<Row>> uniqueFkParentQueues = new HashMap<>();

    // Resolve foreign keys
    resolveForeignKeys(
        orderedTables, tableMap, data, deferred, updates, inserted, uniqueFkParentQueues);

    Instant end = Instant.now();
    Duration duration = Duration.between(start, end);
    double seconds = duration.toMillis() / 1000.0;

    log.info(
        "Generation completed in {} seconds. Tables: {}, deferred updates: {}",
        String.format(Locale.ROOT, "%.3f", seconds),
        orderedTables.size(),
        updates.size());

    return new GenerationResult(data, updates);
  }

  private static void generateTableRows(
      List<Table> orderedTables,
      int rowsPerTable,
      Map<String, Set<String>> excludedColumns,
      Faker faker,
      Set<UUID> usedUuids,
      Map<String, Map<String, ParsedConstraint>> tableConstraints,
      Map<Table, List<Row>> data) {

    orderedTables.forEach(
        table -> {
          if (table.columns().isEmpty()) {
            data.put(table, Collections.emptyList());
            log.debug("Skipping row generation for table {} as it has no columns.", table.name());
            return;
          }

          Map<String, ParsedConstraint> constraints =
              table.columns().stream()
                  .collect(
                      Collectors.toMap(
                          Column::name,
                          col ->
                              parseConstraintsForColumn(table.checks(), col.name(), col.length())));
          tableConstraints.put(table.name(), constraints);
          List<Row> rows = new ArrayList<>();
          Predicate<Column> isFkColumn = column -> table.fkColumnNames().contains(column.name());
          Set<String> seenPrimaryKeys = new HashSet<>();
          // Map to store seen combinations for each unique key (list of column names)
          Map<String, Set<String>> seenUniqueKeyCombinations = new HashMap<>();
          Set<String> excluded = excludedColumns.getOrDefault(table.name(), Set.of());

          int generatedCount = 0;
          int attempts = 0;
          while (generatedCount < rowsPerTable && attempts < rowsPerTable * MAX_GENERATE_ATTEMPTS) {
            Map<String, Object> values =
                generateSingleRow(
                    faker, table, generatedCount, usedUuids, constraints, isFkColumn, excluded);

            boolean isPkUnique;
            if (table.primaryKey().isEmpty()) {
              isPkUnique = true;
            } else {
              String pkKey =
                  table.primaryKey().stream()
                      .map(pkCol -> Objects.toString(values.get(pkCol), "NULL"))
                      .collect(Collectors.joining("|"));
              isPkUnique = seenPrimaryKeys.add(pkKey);
            }

            boolean areUniqueColumnsUnique = true;
            for (List<String> uniqueKeyColumns : table.uniqueKeys()) {
              if (uniqueKeyColumns.stream().allMatch(table.fkColumnNames()::contains)) {
                continue;
              }
              // If the unique key is exactly the primary key, its uniqueness is already covered by
              // isPkUnique.
              // We only need to check other unique keys or if the PK is empty.
              if (!table.primaryKey().equals(uniqueKeyColumns) || table.primaryKey().isEmpty()) {
                String uniqueKeyCombination =
                    uniqueKeyColumns.stream()
                        .map(ukCol -> Objects.toString(values.get(ukCol), "NULL"))
                        .collect(Collectors.joining("|"));
                Set<String> seenCombinations =
                    seenUniqueKeyCombinations.computeIfAbsent(
                        String.join("__", uniqueKeyColumns), k -> new HashSet<>());
                if (!seenCombinations.add(uniqueKeyCombination)) {
                  areUniqueColumnsUnique = false;
                  break;
                }
              }
            }

            if (isPkUnique && areUniqueColumnsUnique) {
              rows.add(new Row(values));
              generatedCount++;
            }
            attempts++;
          }
          data.put(table, rows);
          log.debug("Generated {} rows for table {}.", rows.size(), table.name());
        });
  }

  private static Map<String, Object> generateSingleRow(
      Faker faker,
      Table table,
      int index,
      Set<UUID> usedUuids,
      Map<String, ParsedConstraint> constraints,
      Predicate<Column> isFkColumn,
      Set<String> excluded) { // Removed seenUniqueValues parameter

    Map<String, Object> values = new LinkedHashMap<>();
    table
        .columns()
        .forEach(
            column -> {
              if (isFkColumn.test(column) || excluded.contains(column.name())) {
                values.put(column.name(), null);
              } else {
                if (column.nullable() && ThreadLocalRandom.current().nextDouble() < 0.3) {
                  values.put(column.name(), null);
                } else {
                  ParsedConstraint pc = constraints.get(column.name());
                  Object gen = generateValue(faker, column, index, usedUuids, pc);

                  // Removed unique column handling from here. It will be handled in
                  // generateTableRows.

                  if (isNumericJdbc(column.jdbcType())
                      && pc != null
                      && (pc.min() != null || pc.max() != null)) {
                    int attempts = 0;
                    while (isNumericOutsideBounds(gen, pc) && attempts < MAX_GENERATE_ATTEMPTS) {
                      gen = generateNumericWithinBounds(column, pc);
                      attempts++;
                    }
                  }
                  values.put(column.name(), gen);
                }
              }
            });
    return values;
  }

  private static void validateNumericConstraints(
      List<Table> orderedTables,
      Map<String, Map<String, ParsedConstraint>> tableConstraints,
      Map<Table, List<Row>> data) {

    for (Table table : orderedTables) {
      Map<String, ParsedConstraint> constraints =
          tableConstraints.getOrDefault(table.name(), Map.of());
      List<Row> rows = data.get(table);
      if (rows == null) continue;
      for (Row row : rows) {
        validateRowNumericConstraints(table, row, constraints);
      }
    }
  }

  private static void validateRowNumericConstraints(
      Table table, Row row, Map<String, ParsedConstraint> constraints) {
    for (Column col : table.columns()) {
      ParsedConstraint pc = constraints.get(col.name());
      Object val = row.values().get(col.name());
      if (isNumericJdbc(col.jdbcType()) && pc != null && (pc.min() != null || pc.max() != null)) {
        int attempts = 0;
        while (isNumericOutsideBounds(val, pc) && attempts < MAX_GENERATE_ATTEMPTS) {
          val = generateNumericWithinBounds(col, pc);
          attempts++;
        }
        row.values().put(col.name(), val);
      }
    }
  }

  private static void resolveForeignKeys(
      List<Table> orderedTables,
      Map<String, Table> tableMap,
      Map<Table, List<Row>> data,
      boolean deferred,
      List<PendingUpdate> updates,
      Set<String> inserted,
      Map<String, Deque<Row>> uniqueFkParentQueues) {

    ForeignKeyResolutionContext context =
        new ForeignKeyResolutionContext(
            tableMap, data, deferred, updates, inserted, uniqueFkParentQueues);

    orderedTables.forEach(
        table -> {
          List<Row> rows = Objects.requireNonNull(data.get(table));

          Predicate<ForeignKey> fkIsNullable =
              fk ->
                  fk.columnMapping().keySet().stream()
                      .map(table::column)
                      .allMatch(Column::nullable);

          List<List<String>> uniqueKeysOnFks =
              table.uniqueKeys().stream()
                  .filter(uk -> uk.stream().allMatch(table.fkColumnNames()::contains))
                  .toList();

          if (!uniqueKeysOnFks.isEmpty()) {
            // Special handling for tables with unique keys composed entirely of foreign keys
            handleUniqueFkResolution(table, rows, context, uniqueKeysOnFks);
          } else {
            // Default FK resolution
            rows.forEach(
                row ->
                    table
                        .foreignKeys()
                        .forEach(
                            fk ->
                                resolveSingleForeignKey(
                                    fk, table, row, fkIsNullable.test(fk), context)));
          }

          inserted.add(table.name());
        });
  }

  private static void handleUniqueFkResolution(
      Table table,
      List<Row> rows,
      ForeignKeyResolutionContext context,
      List<List<String>> uniqueKeysOnFks) {
    Set<String> usedCombinations = new HashSet<>();
    int maxAttempts = 100 * rows.size();

    for (Row row : rows) {
      boolean assigned = false;
      for (int attempt = 0; attempt < maxAttempts; attempt++) {
        Map<String, Object> potentialFkValues = new HashMap<>();
        // For each FK in the table, pick a random parent
        for (ForeignKey fk : table.foreignKeys()) {
          Table parent = context.tableMap().get(fk.pkTable());
          if (parent == null) continue;
          List<Row> parentRows = context.data().get(parent);
          if (parentRows == null || parentRows.isEmpty()) continue;

          Row parentRow =
              getParentRowForForeignKey(
                  fk,
                  parentRows,
                  context.uniqueFkParentQueues(),
                  table.name(),
                  parent.name(),
                  false);
          if (parentRow != null) {
            fk.columnMapping()
                .forEach(
                    (fkCol, pkCol) -> potentialFkValues.put(fkCol, parentRow.values().get(pkCol)));
          }
        }

        // Check if this combination of FKs violates any unique constraint
        boolean collision = false;
        List<String> currentCombinations = new ArrayList<>();
        for (List<String> ukColumns : uniqueKeysOnFks) {
          String combination =
              ukColumns.stream()
                  .map(c -> Objects.toString(potentialFkValues.get(c), "NULL"))
                  .collect(Collectors.joining("|"));
          if (usedCombinations.contains(combination)) {
            collision = true;
            break;
          }
          currentCombinations.add(combination);
        }

        if (!collision) {
          // No collision, this is a valid assignment
          row.values().putAll(potentialFkValues);
          usedCombinations.addAll(currentCombinations);
          assigned = true;
          break;
        }
      }
      if (!assigned) {
        log.warn("Could not find a unique FK combination for a row in table {}", table.name());
      }
    }
  }

  private static void resolveSingleForeignKey(
      ForeignKey fk,
      Table table,
      Row row,
      boolean fkNullable,
      ForeignKeyResolutionContext context) {

    Table parent = context.tableMap().get(fk.pkTable());
    if (parent == null) {
      log.warn("Skipping FK {}.{} -> {}: table not found", table.name(), fk.name(), fk.pkTable());
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }

    List<Row> parentRows = context.data().get(parent);
    boolean parentInserted = context.inserted().contains(parent.name());

    Row parentRow =
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
      Map<String, Object> fkVals = new LinkedHashMap<>();
      fk.columnMapping()
          .forEach(
              (fkCol, pkCol) -> {
                fkVals.put(fkCol, parentRow.values().get(pkCol));
                row.values().put(fkCol, null);
              });
      Map<String, Object> pkVals = new LinkedHashMap<>();
      table.primaryKey().forEach(pkCol -> pkVals.put(pkCol, row.values().get(pkCol)));
      context.updates().add(new PendingUpdate(table.name(), fkVals, pkVals));
    }
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static Row getParentRowForForeignKey(
      ForeignKey fk,
      List<Row> parentRows,
      Map<String, Deque<Row>> uniqueFkParentQueues,
      String tableName,
      String parentTableName,
      boolean fkNullable) {

    if (fk.uniqueOnFk()) {
      String key = tableName.concat("|").concat(fk.name());
      Deque<Row> queue =
          uniqueFkParentQueues.computeIfAbsent(
              key,
              k -> {
                List<Row> shuffled = new ArrayList<>(parentRows);
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
      Faker faker, Column column, int index, Set<UUID> usedUuids, ParsedConstraint pc) {
    if (column.uuid()) {
      return generateUuidValue(column, usedUuids, pc);
    }

    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(column.allowedValues()), column.jdbcType());
    }

    if (pc != null && pc.allowedValues() != null && !pc.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(pc.allowedValues()), column.jdbcType());
    }

    ParsedConstraint effectivePc = determineEffectiveNumericConstraint(column, pc);
    if (effectivePc.min() != null || effectivePc.max() != null) {
      Object bounded = generateNumericWithinBounds(column, effectivePc);
      if (bounded != null) return bounded;
    }

    Integer maxLen = pc != null ? pc.maxLength() : null;
    if (maxLen == null || maxLen <= 0) maxLen = column.length() > 0 ? column.length() : null;

    return generateDefaultValue(faker, column, index, maxLen);
  }

  private static Object generateUuidValue(Column column, Set<UUID> usedUuids, ParsedConstraint pc) {
    // Try to parse from column's allowed values
    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      UUID uuid = tryParseUuidFromAllowedValues(column.allowedValues(), usedUuids);
      if (uuid != null) return uuid;
    }

    // Try to parse from parsed constraint's allowed values
    if (pc != null && pc.allowedValues() != null && !pc.allowedValues().isEmpty()) {
      UUID uuid = tryParseUuidFromAllowedValues(pc.allowedValues(), usedUuids);
      if (uuid != null) return uuid;
    }

    // Otherwise generate a new unique UUID
    return generateUuid(usedUuids);
  }

  private static UUID tryParseUuidFromAllowedValues(
      Set<String> allowedValues, Set<UUID> usedUuids) {
    for (String s : allowedValues) {
      try {
        UUID u = UUID.fromString(s.trim());
        if (usedUuids.add(u)) return u;
      } catch (IllegalArgumentException e) {
        log.debug("Invalid UUID string in allowed values: {}", s, e);
      }
    }
    return null;
  }

  private static ParsedConstraint determineEffectiveNumericConstraint(
      Column column, ParsedConstraint pc) {
    Double pcMin = pc != null ? pc.min() : null;
    Double pcMax = pc != null ? pc.max() : null;
    Double cmin = column.minValue() != 0 ? (double) column.minValue() : null;
    Double cmax = column.maxValue() != 0 ? (double) column.maxValue() : null;
    Double effectiveMin = (pcMin != null) ? pcMin : cmin;
    Double effectiveMax = (pcMax != null) ? pcMax : cmax;
    return new ParsedConstraint(
        effectiveMin,
        effectiveMax,
        pc != null ? pc.allowedValues() : Collections.emptySet(),
        pc != null ? pc.maxLength() : null);
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static Object generateDefaultValue(
      Faker faker, Column column, int index, Integer maxLen) {
    return switch (column.jdbcType()) {
      case Types.CHAR,
          Types.VARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGVARCHAR,
          Types.LONGNVARCHAR ->
          generateString(faker, maxLen, column.jdbcType());
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> boundedInt(column);
      case Types.BIGINT -> boundedLong(column);
      case Types.BOOLEAN, Types.BIT -> faker.bool().bool();
      case Types.DATE ->
          Date.valueOf(LocalDate.now().minusDays(faker.number().numberBetween(0, 3650)));
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
          Timestamp.from(Instant.now().minusSeconds(faker.number().numberBetween(0, 31_536_000)));
      case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.DOUBLE, Types.REAL ->
          boundedDecimal(column);
      default -> index;
    };
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static Object pickRandom(List<String> vals, int jdbcType) {
    String v = vals.get(ThreadLocalRandom.current().nextInt(vals.size()));
    if (v == null) return null;
    v = v.trim();
    if (v.isEmpty()) return "";
    try {
      return switch (jdbcType) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(v);
        case Types.BIGINT -> Long.parseLong(v);
        case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.DOUBLE, Types.REAL ->
            Double.parseDouble(v);
        case Types.BOOLEAN, Types.BIT -> Boolean.parseBoolean(v);
        default -> v;
      };
    } catch (NumberFormatException e) {
      log.debug("Failed to parse '{}' to numeric type for JDBC type {}", v, jdbcType, e);
      return v;
    }
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static UUID generateUuid(Set<UUID> usedUuids) {
    for (int i = 0; i < UUID_GENERATION_LIMIT; i++) {
      UUID u = UUID.randomUUID();
      if (usedUuids.add(u)) return u;
    }
    throw new IllegalStateException(
        "Unable to generate a unique UUID after "
            .concat(String.valueOf(UUID_GENERATION_LIMIT))
            .concat(" attempts"));
  }

  private static int getIntMin(Column column, ParsedConstraint pc) {
    boolean hasMin = pc != null && pc.min() != null;
    int colMinValue = column.minValue() != 0 ? column.minValue() : 1;
    return hasMin ? pc.min().intValue() : colMinValue;
  }

  private static int getIntMax(Column column, ParsedConstraint pc) {
    boolean hasMax = pc != null && pc.max() != null;
    int colMaxValue = column.maxValue() != 0 ? column.maxValue() : DEFAULT_INT_MAX;
    return hasMax ? pc.max().intValue() : colMaxValue;
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static Integer boundedInt(Column column) {
    int min =
        getIntMin(column, null); // Pass null for pc as it's handled in generateNumericWithinBounds
    int max =
        getIntMax(column, null); // Pass null for pc as it's handled in generateNumericWithinBounds
    if (min > max) {
      int t = min;
      min = max;
      max = t;
    }
    long v = ThreadLocalRandom.current().nextLong(min, (long) max + 1);
    return Math.toIntExact(Math.clamp(v, Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  private static long getLongMin(Column column, ParsedConstraint pc) {
    boolean hasMin = pc != null && pc.min() != null;
    long colMinValue = column.minValue() != 0 ? column.minValue() : 1L;
    return hasMin ? pc.min().longValue() : colMinValue;
  }

  private static long getLongMax(Column column, ParsedConstraint pc) {
    boolean hasMax = pc != null && pc.max() != null;
    long colMaxValue = column.maxValue() != 0 ? column.maxValue() : DEFAULT_LONG_MAX;
    return hasMax ? pc.max().longValue() : colMaxValue;
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static Long boundedLong(Column column) {
    long min = getLongMin(column, null); // Pass null for pc
    long max = getLongMax(column, null); // Pass null for pc
    if (min > max) {
      long t = min;
      min = max;
      max = t;
    }
    return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
  }

  private static double getDoubleMin(Column column, ParsedConstraint pc) {
    boolean hasMin = pc != null && pc.min() != null;
    double colMinValue = column.minValue() != 0 ? column.minValue() : 1.0;
    return hasMin ? pc.min() : colMinValue;
  }

  private static double getDoubleMax(Column column, ParsedConstraint pc) {
    boolean hasMax = pc != null && pc.max() != null;
    double colMaxValue = column.maxValue() != 0 ? column.maxValue() : DEFAULT_DECIMAL_MAX;
    return hasMax ? pc.max() : colMaxValue;
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static Double boundedDecimal(Column column) {
    double min = getDoubleMin(column, null); // Pass null for pc
    double max = getDoubleMax(column, null); // Pass null for pc
    if (min > max) {
      double t = min;
      min = max;
      max = t;
    }
    return min + (max - min) * ThreadLocalRandom.current().nextDouble();
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static Object generateNumericWithinBounds(Column column, ParsedConstraint pc) {
    switch (column.jdbcType()) {
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
        int min = getIntMin(column, pc);
        int max = getIntMax(column, pc);
        if (min > max) {
          int t = min;
          min = max;
          max = t;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
      }
      case Types.BIGINT -> {
        long min = getLongMin(column, pc);
        long max = getLongMax(column, pc);
        if (min > max) {
          long t = min;
          min = max;
          max = t;
        }
        return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
      }
      case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.DOUBLE, Types.REAL -> {
        double min = getDoubleMin(column, pc);
        double max = getDoubleMax(column, pc);
        if (min > max) {
          double t = min;
          min = max;
          max = t;
        }
        return min + (max - min) * ThreadLocalRandom.current().nextDouble();
      }
      default -> {
        return null;
      }
    }
  }

  @SuppressWarnings("java:S2245") // ThreadLocalRandom is appropriate for data generation
  private static String generateString(Faker faker, Integer maxLen, int jdbcType) {
    int len = (maxLen != null && maxLen > 0) ? maxLen : 255;
    if (len == 2) return faker.country().countryCode2();
    if (len == 3) return faker.country().countryCode3();
    if (len == 24) return normalizeToLength("ES".concat(faker.number().digits(22)), len, jdbcType);
    int numWords = ThreadLocalRandom.current().nextInt(3, Math.clamp(len / 5, 4, 10));
    String phrase = String.join(" ", faker.lorem().words(numWords));
    return normalizeToLength(phrase, len, jdbcType);
  }

  private static boolean isNumericJdbc(int jdbcType) {
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

  private static boolean isNumericOutsideBounds(Object value, ParsedConstraint pc) {
    if (value == null || pc == null) return false;
    try {
      double v;
      if (value instanceof Number n) v = n.doubleValue();
      else v = Double.parseDouble(value.toString());
      return (pc.min() != null && v < pc.min()) || (pc.max() != null && v > pc.max());
    } catch (NumberFormatException e) {
      log.debug("Value '{}' is not a valid number for numeric constraint check.", value, e);
      return true;
    }
  }

  private static ParsedConstraint parseConstraintsForColumn(
      List<String> checks, String columnName, int columnLength) {
    if (checks == null || checks.isEmpty())
      return new ParsedConstraint(null, null, Collections.emptySet(), null);

    Double lower = null;
    Double upper = null;
    Set<String> allowed = new HashSet<>();
    Integer maxLen = null;

    String colPattern =
        "(?i)(?:[A-Za-z0-9_]+\\.)*\"?".concat(Pattern.quote(columnName)).concat("\"?");

    Pattern betweenPattern =
        Pattern.compile(
            colPattern.concat(
                "\\s+BETWEEN\\s+([-+]?[0-9]+(?:\\.[0-9]+)?)\\s+AND\\s+([-+]?[0-9]+(?:\\.[0-9]+)?)"),
            Pattern.CASE_INSENSITIVE);
    Pattern rangePattern =
        Pattern.compile(
            colPattern.concat("\\s*(>=|<=|>|<|=)\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)"),
            Pattern.CASE_INSENSITIVE);
    Pattern inPattern =
        Pattern.compile(colPattern.concat("\\s+IN\\s*\\(([^)]+)\\)"), Pattern.CASE_INSENSITIVE);
    Pattern eqPattern =
        Pattern.compile(
            colPattern.concat("\\s*=\\s*('.*?'|\".*?\"|[0-9A-Za-z_+-]+)"),
            Pattern.CASE_INSENSITIVE);
    Pattern lenPattern =
        Pattern.compile(
            "(?i)(?:char_length|length)\\s*\\(\\s*"
                .concat(colPattern)
                .concat("\\s*\\)\\s*(<=|<|=)\\s*(\\d+)"));

    for (String check : checks) {
      if (check == null || check.isBlank()) continue;
      String exprNoParens = check.replaceAll("[()]+", " ");

      // Parse BETWEEN constraints
      BetweenParseResult betweenResult =
          parseBetweenConstraint(exprNoParens, betweenPattern, check, lower, upper);
      lower = betweenResult.lower();
      upper = betweenResult.upper();

      // Parse range constraints
      RangeParseResult rangeResult =
          parseRangeConstraint(exprNoParens, rangePattern, check, lower, upper);
      lower = rangeResult.lower();
      upper = rangeResult.upper();

      // Parse IN list constraints
      parseInListConstraint(check, inPattern, allowed);
      // Parse equality constraints
      parseEqualityConstraint(exprNoParens, eqPattern, allowed);
      // Parse length constraints
      maxLen = parseLengthConstraint(check, lenPattern, maxLen);
    }

    if (columnLength > 0 && (maxLen == null || columnLength < maxLen)) {
      maxLen = columnLength;
    }

    return new ParsedConstraint(lower, upper, Set.copyOf(allowed), maxLen);
  }

  private static BetweenParseResult parseBetweenConstraint(
      String exprNoParens,
      Pattern betweenPattern,
      String check,
      Double currentLower,
      Double currentUpper) {
    Matcher mb = betweenPattern.matcher(exprNoParens);
    Double newLower = currentLower;
    Double newUpper = currentUpper;
    while (mb.find()) {
      try {
        double a = Double.parseDouble(mb.group(1));
        double b = Double.parseDouble(mb.group(2));
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        newLower = (newLower == null) ? lo : Math.max(newLower, lo);
        newUpper = (newUpper == null) ? hi : Math.min(newUpper, hi);
      } catch (NumberFormatException e) {
        log.debug("Failed to parse BETWEEN bounds in check: {}", check, e);
      }
    }
    return new BetweenParseResult(newLower, newUpper);
  }

  private static RangeParseResult parseRangeConstraint(
      String exprNoParens,
      Pattern rangePattern,
      String check,
      Double currentLower,
      Double currentUpper) {
    Matcher mr = rangePattern.matcher(exprNoParens);
    Double newLower = currentLower;
    Double newUpper = currentUpper;
    while (mr.find()) {
      String op = mr.group(1);
      String num = mr.group(2);
      try {
        double val = Double.parseDouble(num);
        newLower = updateLowerBound(op, val, newLower);
        newUpper = updateUpperBound(op, val, newUpper);
      } catch (NumberFormatException e) {
        log.debug("Failed to parse numeric range in check: {}", check, e);
      }
    }
    return new RangeParseResult(newLower, newUpper);
  }

  private static Double updateLowerBound(String op, double val, Double currentLower) {
    return switch (op) {
      case ">" ->
          (currentLower == null) ? Math.nextUp(val) : Math.max(currentLower, Math.nextUp(val));
      case ">=", "=" -> (currentLower == null) ? val : Math.max(currentLower, val);
      default -> currentLower;
    };
  }

  private static Double updateUpperBound(String op, double val, Double currentUpper) {
    return switch (op) {
      case "<" ->
          (currentUpper == null) ? Math.nextDown(val) : Math.min(currentUpper, Math.nextDown(val));
      case "<=", "=" -> (currentUpper == null) ? val : Math.min(currentUpper, val);
      default -> currentUpper;
    };
  }

  private static void parseInListConstraint(String check, Pattern inPattern, Set<String> allowed) {
    Matcher mi = inPattern.matcher(check);
    while (mi.find()) {
      String inside = mi.group(1);
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

  private static void parseEqualityConstraint(
      String exprNoParens, Pattern eqPattern, Set<String> allowed) {
    Matcher me = eqPattern.matcher(exprNoParens);
    while (me.find()) {
      String s = me.group(1).trim();
      if (s.startsWith("'") && s.endsWith("'")) s = s.substring(1, s.length() - 1);
      if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
      if (!s.isEmpty()) allowed.add(s);
    }
  }

  private static Integer parseLengthConstraint(
      String check, Pattern lenPattern, Integer currentMaxLen) {
    Matcher ml = lenPattern.matcher(check);
    Integer newMaxLen = currentMaxLen;
    while (ml.find()) {
      String op = ml.group(1);
      String num = ml.group(2);
      try {
        int v = Integer.parseInt(num);
        if ("<".equals(op) || "<=".equals(op) || "=".equals(op)) {
          newMaxLen = (newMaxLen == null) ? v : Math.min(newMaxLen, v);
        }
      } catch (NumberFormatException e) {
        log.debug("Failed to parse length constraint in check: {}", check, e);
      }
    }
    return newMaxLen;
  }

  private static String normalizeToLength(String value, int length, int jdbcType) {
    if (length <= 0) return value;
    if (value.length() > length) {
      return value.substring(0, length);
    }
    if (jdbcType == Types.CHAR && value.length() < length) {
      return String.format("%-".concat(String.valueOf(length)).concat("s"), value);
    }
    return value;
  }

  private static boolean isSingleWord(String tableName) {
    return SINGLE_WORD_PATTERN.matcher(tableName).matches();
  }

  private static List<Table> orderByWordAndFk(List<Table> tables) {
    List<Table> singleWord = tables.stream().filter(t -> isSingleWord(t.name())).toList();
    List<Table> multiWord = tables.stream().filter(t -> !isSingleWord(t.name())).toList();

    List<Table> ordered = new ArrayList<>(orderByFk(singleWord));
    ordered.addAll(orderByFk(multiWord));
    return List.copyOf(ordered);
  }

  private static List<Table> orderByFk(List<Table> tables) {
    List<Table> ordered = new ArrayList<>(tables);
    ordered.sort(
        Comparator.comparingInt((Table table) -> table.foreignKeys().size())
            .thenComparing(Table::name, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(ordered);
  }

  private static void ensureUuidUniqueness(
      Map<Table, List<Row>> data, List<Table> orderedTables, Set<UUID> usedUuids) {
    Map<String, Set<UUID>> seenPerColumn = new HashMap<>();
    for (Table table : orderedTables) {
      List<Row> rows = data.get(table);
      if (rows == null) continue;
      for (Column col : table.columns()) {
        if (!col.uuid()) continue;
        processUuidColumn(table, col, rows, usedUuids, seenPerColumn);
      }
    }
  }

  private static void processUuidColumn(
      Table table,
      Column col,
      List<Row> rows,
      Set<UUID> usedUuids,
      Map<String, Set<UUID>> seenPerColumn) {
    String key = table.name().concat(".").concat(col.name());
    Set<UUID> seen = seenPerColumn.computeIfAbsent(key, k -> new HashSet<>());
    for (Row row : rows) {
      Object v = row.values().get(col.name());
      UUID u = null;
      if (v instanceof UUID uuid) {
        u = uuid;
      } else if (v instanceof String s) {
        try {
          u = UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
          log.debug("Invalid UUID string in row for {}.{}: {}", table.name(), col.name(), s, e);
        }
      }
      if (u == null) {
        u = generateUuid(usedUuids);
        row.values().put(col.name(), u);
        seen.add(u);
        continue;
      }
      if (seen.contains(u)) {
        UUID newU = generateUuid(usedUuids);
        row.values().put(col.name(), newU);
        seen.add(newU);
        log.warn("Replaced duplicate UUID for {}.{}: {} -> {}", table.name(), col.name(), u, newU);
      } else {
        seen.add(u);
        usedUuids.add(u);
      }
    }
  }

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

  public record GenerationResult(Map<Table, List<Row>> rows, List<PendingUpdate> updates) {}
}
