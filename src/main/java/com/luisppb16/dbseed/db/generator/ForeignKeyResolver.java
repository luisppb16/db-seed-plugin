/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import com.luisppb16.dbseed.db.PendingUpdate;
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

public final class ForeignKeyResolver {

  private final Map<String, Table> tableMap;
  private final Map<Table, List<Row>> data;
  private final boolean deferred;
  private final List<PendingUpdate> updates;
  private final Set<String> inserted;
  private final Map<String, Deque<Row>> uniqueFkParentQueues;

  public ForeignKeyResolver(
      final Map<String, Table> tableMap,
      final Map<Table, List<Row>> data,
      final boolean deferred) {
    this.tableMap = Objects.requireNonNull(tableMap, "Table map cannot be null");
    this.data = Objects.requireNonNull(data, "Data map cannot be null");
    this.deferred = deferred;
    this.updates = new ArrayList<>();
    this.inserted = new HashSet<>();
    this.uniqueFkParentQueues = new HashMap<>();
  }

  public List<PendingUpdate> resolve() {
    tableMap.values().forEach(this::resolveForeignKeysForTable);
    return updates;
  }

  private void resolveForeignKeysForTable(final Table table) {
    final List<Row> rows = Objects.requireNonNull(data.get(table), "No data for table: " + table.name());

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

    if (!uniqueKeysOnFks.isEmpty()) {
      handleUniqueFkResolution(table, rows, uniqueKeysOnFks);
    } else {
      rows.forEach(
          row ->
              table
                  .foreignKeys()
                  .forEach(
                      fk ->
                          resolveSingleForeignKey(
                              fk, table, row, fkNullableCache.getOrDefault(fk, false))));
    }

    inserted.add(table.name());
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
    final int maxAttempts = 100 * rows.size();

    for (final Row row : rows) {
      final Optional<Map<String, Object>> resolvedFkValues =
          findUniqueFkCombination(table, uniqueKeysOnFks, usedCombinations, maxAttempts);

      if (resolvedFkValues.isPresent()) {
        row.values().putAll(resolvedFkValues.get());
      }
    }
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
    final Map<String, Object> potentialFkValues = new HashMap<>();
    for (final ForeignKey fk : table.foreignKeys()) {
      final Table parent = tableMap.get(fk.pkTable());
      final List<Row> parentRows = (parent != null) ? data.get(parent) : null;

      if (parent != null && parentRows != null && !parentRows.isEmpty()) {
        final Row parentRow =
            getParentRowForForeignKey(
                fk, parentRows, table.name(), parent.name(), false);
        if (parentRow != null) {
          fk.columnMapping()
              .forEach(
                  (fkCol, pkCol) -> potentialFkValues.put(fkCol, parentRow.values().get(pkCol)));
        }
      }
    }
    return potentialFkValues;
  }

  private boolean isUniqueFkCollision(
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

  private void addUniqueCombinationsToSet(
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

  private void resolveSingleForeignKey(
      final ForeignKey fk,
      final Table table,
      final Row row,
      final boolean fkNullable) {

    final Table parent = tableMap.get(fk.pkTable());
    if (parent == null) {
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
      return;
    }

    final List<Row> parentRows = data.get(parent);
    final boolean parentInserted = inserted.contains(parent.name());

    final Row parentRow =
        getParentRowForForeignKey(
            fk, parentRows, table.name(), parent.name(), fkNullable);

    if (parentRow == null) {
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
      final String key = tableName + "|" + fk.name();
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
