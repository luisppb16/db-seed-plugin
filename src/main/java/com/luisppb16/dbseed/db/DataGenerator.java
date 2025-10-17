/*
 *
 *  * Copyright (c) 2025 Luis Pepe.
 *  * All rights reserved.
 *
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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

@Slf4j
@UtilityClass
public class DataGenerator {

  private static final Pattern SINGLE_WORD_PATTERN =
      Pattern.compile("^(?<word>[\\p{L}][\\p{L}\\p{N}]*)$");

  public static GenerationResult generate(
      List<Table> tables,
      int rowsPerTable,
      boolean deferred,
      Map<String, Set<String>> pkUuidOverrides) {

    Map<String, Table> overridden = new LinkedHashMap<>();
    tables.forEach(
        t -> {
          Set<String> set = pkUuidOverrides != null ? pkUuidOverrides.get(t.name()) : null;
          if (set == null || set.isEmpty()) {
            overridden.put(t.name(), t);
            return;
          }
          List<Column> newCols = new ArrayList<>();
          t.columns()
              .forEach(
                  c -> {
                    boolean forceUuid = set.contains(c.name());
                    if (forceUuid && !c.uuid()) {
                      newCols.add(
                          new Column(
                              c.name(),
                              c.jdbcType(),
                              c.nullable(),
                              c.primaryKey(),
                              true,
                              c.length(),
                              c.minValue(),
                              c.maxValue(),
                              c.allowedValues()));
                    } else {
                      newCols.add(c);
                    }
                  });
          overridden.put(t.name(), new Table(t.name(), newCols, t.primaryKey(), t.foreignKeys()));
        });

    List<Table> list = new ArrayList<>(overridden.values());
    return generateInternal(list, rowsPerTable, deferred);
  }

  private static GenerationResult generateInternal(
      List<Table> tables, int rowsPerTable, boolean deferred) {

    Instant start = Instant.now();

    List<Table> orderedTables = orderByWordAndFk(tables);
    Map<String, Table> tableMap =
        orderedTables.stream().collect(Collectors.toUnmodifiableMap(Table::name, t -> t));

    Faker faker = new Faker();
    Map<String, List<Row>> data = new LinkedHashMap<>();
    Set<UUID> usedUuids = new HashSet<>();

    // Generate rows
    orderedTables.forEach(
        table -> {
          List<Row> rows = new ArrayList<>();
          Predicate<Column> isFkColumn = column -> table.fkColumnNames().contains(column.name());
          Set<String> seenPrimaryKeys = new HashSet<>();

          IntStream.range(0, rowsPerTable)
              .forEach(
                  i -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    table
                        .columns()
                        .forEach(
                            column -> {
                              if (isFkColumn.test(column)) {
                                values.put(column.name(), null);
                              } else {
                                values.put(
                                    column.name(), generateValue(faker, column, i, usedUuids));
                              }
                            });

                    String pkKey =
                        table.primaryKey().stream()
                            .map(pkCol -> Objects.toString(values.get(pkCol), "NULL"))
                            .collect(Collectors.joining("|"));

                    if (pkKey.isEmpty() || seenPrimaryKeys.add(pkKey)) {
                      rows.add(new Row(values));
                    }
                  });

          data.put(table.name(), rows);
          log.debug("Generated {} rows for table {}.", rows.size(), table.name());
        });

    Map<String, Deque<Row>> uniqueFkParentQueues = new HashMap<>();
    List<PendingUpdate> updates = new ArrayList<>();
    Set<String> inserted = new HashSet<>();

    // Resolve foreign keys
    orderedTables.forEach(
        table -> {
          List<Row> rows = Objects.requireNonNull(data.get(table.name()));

          Function<ForeignKey, Boolean> fkIsNullable =
              fk ->
                  fk.columnMapping().keySet().stream()
                      .map(col -> table.column(col).nullable())
                      .reduce(true, Boolean::logicalAnd);

          rows.forEach(
              row ->
                  table
                      .foreignKeys()
                      .forEach(
                          fk -> {
                            Table parent = tableMap.get(fk.pkTable());
                            if (parent == null) {
                              log.warn(
                                  "Skipping FK {}.{} -> {}: table not found",
                                  table.name(),
                                  fk.name(),
                                  fk.pkTable());
                              fk.columnMapping()
                                  .keySet()
                                  .forEach(col -> row.values().put(col, null));
                              return;
                            }

                            List<Row> parentRows = data.get(parent.name());
                            boolean parentInserted = inserted.contains(parent.name());
                            boolean fkNullable = fkIsNullable.apply(fk);

                            Row parentRow;
                            if (fk.uniqueOnFk()) {
                              String key = table.name() + "|" + fk.name();
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
                                          + parent.name()
                                          + " for non-nullable 1:1 FK from "
                                          + table.name());
                                }
                                fk.columnMapping()
                                    .keySet()
                                    .forEach(col -> row.values().put(col, null));
                                return;
                              }
                              parentRow = queue.pollFirst();
                            } else {
                              parentRow =
                                  parentRows.get(
                                      ThreadLocalRandom.current().nextInt(parentRows.size()));
                            }

                            if (parentInserted || deferred) {
                              fk.columnMapping()
                                  .forEach(
                                      (fkCol, pkCol) ->
                                          row.values().put(fkCol, parentRow.values().get(pkCol)));
                            } else {
                              if (!fkNullable) {
                                throw new IllegalStateException(
                                    "Cycle with non-nullable FK: "
                                        + table.name()
                                        + " -> "
                                        + parent.name());
                              }
                              Map<String, Object> fkVals = new LinkedHashMap<>();
                              fk.columnMapping()
                                  .forEach(
                                      (fkCol, pkCol) -> {
                                        fkVals.put(fkCol, parentRow.values().get(pkCol));
                                        row.values().put(fkCol, null);
                                      });
                              Map<String, Object> pkVals = new LinkedHashMap<>();
                              table
                                  .primaryKey()
                                  .forEach(pkCol -> pkVals.put(pkCol, row.values().get(pkCol)));
                              updates.add(new PendingUpdate(table.name(), fkVals, pkVals));
                            }
                          }));

          inserted.add(table.name());
        });

    Instant end = Instant.now();
    Duration duration = Duration.between(start, end);
    Double seconds = duration.toMillis() / 1000.0;

    log.info(
        "Generation completed in {} seconds. Tables: {}, deferred updates: {}",
        String.format(Locale.ROOT, "%.3f", seconds),
        orderedTables.size(),
        updates.size());

    return new GenerationResult(data, updates);
  }

  private static Object generateValue(Faker faker, Column column, int index, Set<UUID> usedUuids) {
    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      List<String> vals = new ArrayList<>(column.allowedValues());
      return vals.get(ThreadLocalRandom.current().nextInt(vals.size()));
    }
    if (column.uuid()) {
      for (int i = 0; i < 10; i++) {
        UUID u = UUID.randomUUID();
        if (usedUuids.add(u)) return u;
      }
      UUID u = UUID.randomUUID();
      usedUuids.add(u);
      return u;
    }

    return switch (column.jdbcType()) {
      case Types.CHAR,
          Types.VARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGVARCHAR,
          Types.LONGNVARCHAR -> {
        int len = Math.max(column.length(), 0);

        if (len == 2) {
          yield faker.country().countryCode2();
        } else if (len == 3) {
          yield faker.country().countryCode3();
        } else if (len == 24) {
          String iban = "ES" + faker.number().digits(22);
          yield normalizeToLength(iban, len, column.jdbcType());
        }

        int numWords = ThreadLocalRandom.current().nextInt(3, 11);
        String phrase = String.join(" ", faker.lorem().words(numWords));
        yield normalizeToLength(phrase, len, column.jdbcType());
      }

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

  private static String normalizeToLength(String value, int length, int jdbcType) {
    if (length <= 0) return value;
    if (value.length() > length) {
      return value.substring(0, length);
    }
    if (jdbcType == Types.CHAR && value.length() < length) {
      return String.format("%-" + length + "s", value);
    }
    return value;
  }

  private static Integer boundedInt(Column column) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int min = (column.minValue() != 0) ? column.minValue() : 1;
    int max = (column.maxValue() != 0) ? column.maxValue() : 10_000;
    if (min > max) {
      int t = min;
      min = max;
      max = t;
    }
    long v = r.nextLong(min, max + 1);
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
  }

  private static Long boundedLong(Column column) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int min = (column.minValue() != 0) ? column.minValue() : 1;
    int max = (column.maxValue() != 0) ? column.maxValue() : 1_000_000;
    if (min > max) {
      int t = min;
      min = max;
      max = t;
    }
    return r.nextLong(min, Math.addExact(max, 1L));
  }

  private static Double boundedDecimal(Column column) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int min = (column.minValue() != 0) ? column.minValue() : 1;
    int max = (column.maxValue() != 0) ? column.maxValue() : 1_000;
    if (min > max) {
      int t = min;
      min = max;
      max = t;
    }
    return min + (max - min) * r.nextDouble();
  }

  private static boolean isSingleWord(String tableName) {
    return SINGLE_WORD_PATTERN.matcher(tableName).matches();
  }

  private static List<Table> orderByWordAndFk(List<Table> tables) {
    List<Table> singleWord = new ArrayList<>();
    List<Table> multiWord = new ArrayList<>();
    tables.forEach(table -> (isSingleWord(table.name()) ? singleWord : multiWord).add(table));
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

  public record GenerationResult(Map<String, List<Row>> rows, List<PendingUpdate> updates) {}
}
