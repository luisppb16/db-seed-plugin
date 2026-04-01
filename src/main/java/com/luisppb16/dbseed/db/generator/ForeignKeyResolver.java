/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import com.luisppb16.dbseed.db.PendingUpdate;
import com.luisppb16.dbseed.db.ProgressTracker;
import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Sophisticated foreign key resolution engine for database seeding operations in the DBSeed plugin.
 */
public final class ForeignKeyResolver {

  private static final int MAX_UNIQUE_FK_ATTEMPTS_FACTOR = 100;
  private static final String COMBINATION_DELIMITER = "|";
  private static final String NULL_VALUE = "NULL";

  private final Map<String, Table> tableMap;
  private final Map<Table, List<Row>> data;
  private final boolean deferred;
  private final Map<String, Map<String, Integer>> circularReferences;
  private final Set<String> inserted = new HashSet<>();
  private final Set<String> resolving = new HashSet<>();
  private final List<PendingUpdate> updates = new ArrayList<>();
  private final Map<String, Deque<Row>> uniqueFkParentQueues = new HashMap<>();

  public ForeignKeyResolver(
      final Map<String, Table> tableMap,
      final Map<Table, List<Row>> data,
      final boolean deferred,
      final Map<String, Map<String, Integer>> circularReferences) {
    this.tableMap = Objects.requireNonNull(tableMap, "Table map cannot be null");
    this.data = Objects.requireNonNull(data, "Data map cannot be null");
    this.deferred = deferred;
    this.circularReferences = circularReferences == null ? new HashMap<>() : circularReferences;
  }

  public List<PendingUpdate> resolve() {
    tableMap.values().forEach(this::resolveForeignKeysForTable);
    return updates;
  }

  public List<PendingUpdate> resolve(final ProgressTracker tracker) {
    tableMap
        .values()
        .forEach(
            table -> {
              resolveForeignKeysForTable(table);
              tracker.advance(); // 1 unit per table
            });
    return updates;
  }

  private void resolveForeignKeysForTable(final Table table) {
    final List<Row> rows =
        Objects.requireNonNull(data.get(table), "No data for table: " + table.name());

    final Map<ForeignKey, Boolean> fkNullableCache =
        table.foreignKeys().stream()
            .collect(
                Collectors.toMap(
                    fk -> fk,
                    fk ->
                        fk.columnMapping().keySet().stream()
                            .map(table::column)
                            .allMatch(Column::nullable)));

    final List<List<String>> uniqueKeysOnFks = extractUniqueKeysOnForeignKeys(table);

    // First, pre-process circular reference FKs
    final List<ForeignKey> circularFks = new ArrayList<>();
    if (circularReferences.containsKey(table.name())) {
      final Map<String, Integer> fksConfig = circularReferences.get(table.name());
      table.foreignKeys().stream()
          .filter(fk -> fksConfig.containsKey(fk.name()) && fk.pkTable().equals(table.name()))
          .forEach(circularFks::add);
    }

    if (!circularFks.isEmpty()) {
      handleCircularReferences(table, rows, circularFks, circularReferences.get(table.name()));
    }

    if (!uniqueKeysOnFks.isEmpty()) {
      handleUniqueFkResolution(table, rows, uniqueKeysOnFks);
    } else {
      rows.forEach(
          row ->
              table
                  .foreignKeys()
                  .forEach(
                      fk -> {
                        if (!circularFks.contains(fk)) {
                          resolveSingleForeignKey(
                              fk, table, row, fkNullableCache.getOrDefault(fk, false));
                        }
                      }));
    }

    inserted.add(table.name());
  }

  private void handleCircularReferences(
      final Table table,
      final List<Row> rows,
      final List<ForeignKey> circularFks,
      final Map<String, Integer> depths) {

    for (ForeignKey fk : circularFks) {
      int maxDepth = depths.getOrDefault(fk.name(), 3);
      if (rows.isEmpty()) continue;

      for (int i = 0; i < rows.size(); i++) {
        Row row = rows.get(i);
        int chainStart = (i / maxDepth) * maxDepth;
        int chainEnd = Math.min(chainStart + maxDepth, rows.size()) - 1;

        Row parentRow = (i == chainEnd) ? rows.get(chainStart) : rows.get(i + 1);

        if (deferred) {
          fk.columnMapping()
              .forEach((fkCol, pkCol) -> row.values().put(fkCol, parentRow.values().get(pkCol)));
        } else {
          final Map<String, Object> fkVals = new LinkedHashMap<>();
          fk.columnMapping()
              .forEach(
                  (fkCol, pkCol) -> {
                    fkVals.put(fkCol, parentRow.values().get(pkCol));
                    row.values().put(fkCol, null);
                  });
          final Map<String, Object> pkVals = new LinkedHashMap<>();
          table.primaryKey().forEach(pkCol -> pkVals.put(pkCol, row.values().get(pkCol)));
          updates.add(new PendingUpdate(table.name(), fkVals, pkVals));
        }
      }
    }
  }

  private List<List<String>> extractUniqueKeysOnForeignKeys(final Table table) {
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
    return uniqueKeysOnFks;
  }

  private void handleUniqueFkResolution(
      final Table table, final List<Row> rows, final List<List<String>> uniqueKeysOnFks) {
    final Set<String> usedCombinations = new HashSet<>();
    final int maxAttempts = MAX_UNIQUE_FK_ATTEMPTS_FACTOR * rows.size();

    rows.forEach(
        row ->
            findUniqueFkCombination(table, uniqueKeysOnFks, usedCombinations, maxAttempts)
                .ifPresent(fkValues -> row.values().putAll(fkValues)));
  }

  private Optional<Map<String, Object>> findUniqueFkCombination(
      final Table table,
      final List<List<String>> uniqueKeysOnFks,
      final Set<String> usedCombinations,
      final int maxAttempts) {

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      final Map<String, Object> potentialFkValues = generatePotentialFkValues(table);

      if (!potentialFkValues.isEmpty()
          && !isUniqueFkCollision(uniqueKeysOnFks, potentialFkValues, usedCombinations)) {
        addUniqueCombinationsToSet(uniqueKeysOnFks, potentialFkValues, usedCombinations);
        return Optional.of(potentialFkValues);
      }
    }
    return Optional.empty();
  }

  private Map<String, Object> generatePotentialFkValues(final Table table) {
    return table.foreignKeys().stream()
        .map(
            fk -> {
              final Table parent = tableMap.get(fk.pkTable());
              final List<Row> parentRows = Objects.nonNull(parent) ? data.get(parent) : null;

              if (Objects.isNull(parent) || Objects.isNull(parentRows) || parentRows.isEmpty()) {
                return Map.<String, Object>of();
              }

              final Row parentRow =
                  getParentRowForForeignKey(fk, parentRows, table.name(), parent.name(), false);
              if (Objects.isNull(parentRow)) {
                return Map.<String, Object>of();
              }

              Map<String, Object> values = new HashMap<>();
              fk.columnMapping()
                  .forEach((fkCol, pkCol) -> values.put(fkCol, parentRow.values().get(pkCol)));
              return values;
            })
        .filter(m -> !m.isEmpty())
        .flatMap(m -> m.entrySet().stream())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
  }

  private boolean isUniqueFkCollision(
      final List<List<String>> uniqueKeysOnFks,
      final Map<String, Object> potentialFkValues,
      final Set<String> usedCombinations) {
    return uniqueKeysOnFks.stream()
        .map(
            ukColumns ->
                ukColumns.stream()
                    .map(c -> Objects.toString(potentialFkValues.get(c), NULL_VALUE))
                    .collect(Collectors.joining(COMBINATION_DELIMITER)))
        .anyMatch(usedCombinations::contains);
  }

  private void addUniqueCombinationsToSet(
      final List<List<String>> uniqueKeysOnFks,
      final Map<String, Object> potentialFkValues,
      final Set<String> usedCombinations) {
    uniqueKeysOnFks.stream()
        .map(
            ukColumns ->
                ukColumns.stream()
                    .map(c -> Objects.toString(potentialFkValues.get(c), NULL_VALUE))
                    .collect(Collectors.joining(COMBINATION_DELIMITER)))
        .forEach(usedCombinations::add);
  }

  private void resolveSingleForeignKey(
      final ForeignKey fk, final Table table, final Row row, final boolean fkNullable) {

    final Table parent = tableMap.get(fk.pkTable());
    if (Objects.isNull(parent)) {
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }

    final List<Row> parentRows = data.get(parent);
    final boolean parentInserted = inserted.contains(parent.name());

    final Row parentRow =
        getParentRowForForeignKey(fk, parentRows, table.name(), parent.name(), fkNullable);

    if (Objects.isNull(parentRow)) {
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }

    if (parentInserted || deferred) {
      fk.columnMapping()
          .forEach((fkCol, pkCol) -> row.values().put(fkCol, parentRow.values().get(pkCol)));
    } else {
      if (!fkNullable) {
        throw new IllegalStateException(
            "Cycle with non-nullable FK: " + table.name() + " -> " + parent.name());
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
      updates.add(new PendingUpdate(table.name(), fkVals, pkVals));
    }
  }

  @SuppressWarnings("java:S2245")
  private Row getParentRowForForeignKey(
      final ForeignKey fk,
      final List<Row> parentRows,
      final String tableName,
      final String parentTableName,
      final boolean fkNullable) {

    if (fk.uniqueOnFk()) {
      final String key = tableName + COMBINATION_DELIMITER + fk.name();
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
                  + parentTableName
                  + " for non-nullable 1:1 FK from "
                  + tableName);
        }
        return null;
      }
      return queue.pollFirst();
    } else {
      return parentRows.get(ThreadLocalRandom.current().nextInt(parentRows.size()));
    }
  }
}
