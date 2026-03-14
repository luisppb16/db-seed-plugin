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
import com.luisppb16.dbseed.model.SelfReferenceConfig;
import com.luisppb16.dbseed.model.SelfReferenceStrategy;
import com.luisppb16.dbseed.model.Table;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
  private final List<PendingUpdate> updates;
  private final Set<String> inserted;
  private final Map<String, Deque<Row>> uniqueFkParentQueues;
  private final Map<String, SelfReferenceConfig> selfRefConfigs;
  /** IdentityHashMap keyed by Row identity so mutable map contents don't affect equality. */
  private final Map<String, Map<Row, Map<String, Object>>> selfRefAssignmentsCache;

  public ForeignKeyResolver(
      final Map<String, Table> tableMap, final Map<Table, List<Row>> data, final boolean deferred) {
    this(tableMap, data, deferred, Map.of());
  }

  public ForeignKeyResolver(
      final Map<String, Table> tableMap,
      final Map<Table, List<Row>> data,
      final boolean deferred,
      final Map<String, SelfReferenceConfig> selfRefConfigs) {
    this.tableMap = Objects.requireNonNull(tableMap, "Table map cannot be null");
    this.data = Objects.requireNonNull(data, "Data map cannot be null");
    this.deferred = deferred;
    this.selfRefConfigs = Objects.requireNonNullElse(selfRefConfigs, Map.of());
    this.updates = new ArrayList<>();
    this.inserted = new HashSet<>();
    this.uniqueFkParentQueues = new HashMap<>();
    this.selfRefAssignmentsCache = new HashMap<>();
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

    // Pre-compute self-referencing FK assignments for CIRCULAR / HIERARCHY strategies
    final SelfReferenceConfig selfRefConfig =
        selfRefConfigs.getOrDefault(table.name(), SelfReferenceConfig.NONE_CONFIG);
    final boolean hasSelfRefFk =
        table.foreignKeys().stream().anyMatch(fk -> fk.pkTable().equals(table.name()));
    if (hasSelfRefFk
        && selfRefConfig.strategy() != SelfReferenceStrategy.NONE
        && !rows.isEmpty()) {
      buildSelfRefAssignments(table, rows, selfRefConfig);
    }

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

    // Self-referencing FK with a pre-computed CIRCULAR / HIERARCHY assignment
    if (fk.pkTable().equals(table.name())) {
      final Map<Row, Map<String, Object>> assignments = selfRefAssignmentsCache.get(table.name());
      if (assignments != null) {
        final Map<String, Object> precomputed = assignments.get(row);
        if (precomputed != null) {
          applySelfRefAssignment(fk, table, row, precomputed);
          return;
        }
      }
      // NONE strategy or cache miss: fall through to existing logic, which correctly handles
      // the self-ref as a not-yet-inserted parent (nullable → PendingUpdate, else throws).
    }

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

  // ── Self-reference strategies ─────────────────────────────────────────────────────────────────

  /**
   * Applies a pre-computed self-ref FK assignment to {@code row}.
   *
   * <p>When {@code precomputed} contains {@code null} values (i.e. HIERARCHY root level), and the
   * FK column is NOT NULL, a self-pointing {@link PendingUpdate} is created so the row can be
   * updated after all rows are inserted — avoiding an unsatisfied constraint on the root record.
   */
  private void applySelfRefAssignment(
      final ForeignKey fk,
      final Table table,
      final Row row,
      final Map<String, Object> precomputed) {

    final boolean hasNull = precomputed.values().stream().anyMatch(Objects::isNull);

    if (hasNull) {
      final boolean allNullable =
          fk.columnMapping().keySet().stream()
              .map(table::column)
              .filter(Objects::nonNull)
              .allMatch(Column::nullable);

      if (!allNullable) {
        // Root of a HIERARCHY with NOT-NULL FK: schedule a self-pointing UPDATE
        final Map<String, Object> pkVals = new LinkedHashMap<>();
        table.primaryKey().forEach(pkCol -> pkVals.put(pkCol, row.values().get(pkCol)));

        final Map<String, Object> selfFkVals = new LinkedHashMap<>();
        fk.columnMapping()
            .forEach((fkCol, pkCol) -> selfFkVals.put(fkCol, row.values().get(pkCol)));

        updates.add(new PendingUpdate(table.name(), selfFkVals, pkVals));
      }
      fk.columnMapping().keySet().forEach(col -> row.values().put(col, null));
    } else {
      row.values().putAll(precomputed);
    }
  }

  /**
   * Pre-computes self-referencing FK value assignments for all rows in {@code table} according to
   * the configured {@link SelfReferenceStrategy}.
   *
   * <p>Results are stored in {@link #selfRefAssignmentsCache} keyed by table name. An
   * {@link IdentityHashMap} is used for the inner map so that mutable {@code Row.values()} maps do
   * not interfere with equality comparisons.
   */
  @SuppressWarnings("java:S2245")
  private void buildSelfRefAssignments(
      final Table table, final List<Row> rows, final SelfReferenceConfig config) {

    final List<ForeignKey> selfFks =
        table.foreignKeys().stream().filter(fk -> fk.pkTable().equals(table.name())).toList();

    final Map<Row, Map<String, Object>> assignments = new IdentityHashMap<>();

    switch (config.strategy()) {
      case CIRCULAR -> {
        if (rows.size() < 2) {
          throw new IllegalStateException(
              "CIRCULAR self-reference strategy requires ≥ 2 rows in table '"
                  + table.name()
                  + "', but got "
                  + rows.size()
                  + ". Increase 'rows per table' or choose a different strategy.");
        }
        // Sattolo's algorithm: builds a single-cycle derangement with no self-loops
        final int[] targets = buildCircularTargets(rows.size());
        for (int i = 0; i < rows.size(); i++) {
          final Row parent = rows.get(targets[i]);
          final Map<String, Object> fkVals = new LinkedHashMap<>();
          selfFks.forEach(
              fk -> fk.columnMapping().forEach((fkCol, pkCol) -> fkVals.put(fkCol, parent.values().get(pkCol))));
          assignments.put(rows.get(i), fkVals);
        }
      }
      case HIERARCHY -> {
        final int effectiveDepth = Math.min(config.hierarchyDepth(), rows.size());
        final List<List<Row>> levels = buildHierarchyLevels(rows, effectiveDepth);
        for (int lvl = 0; lvl < levels.size(); lvl++) {
          for (final Row row : levels.get(lvl)) {
            if (lvl == 0) {
              // Root level: null FK (handled by applySelfRefAssignment)
              final Map<String, Object> nullVals = new LinkedHashMap<>();
              selfFks.forEach(
                  fk -> fk.columnMapping().keySet().forEach(col -> nullVals.put(col, null)));
              assignments.put(row, nullVals);
            } else {
              final List<Row> parentLevel = levels.get(lvl - 1);
              final Row parent =
                  parentLevel.get(ThreadLocalRandom.current().nextInt(parentLevel.size()));
              final Map<String, Object> fkVals = new LinkedHashMap<>();
              selfFks.forEach(
                  fk -> fk.columnMapping().forEach((fkCol, pkCol) -> fkVals.put(fkCol, parent.values().get(pkCol))));
              assignments.put(row, fkVals);
            }
          }
        }
      }
      default -> {
        // NONE — nothing to pre-compute
      }
    }

    selfRefAssignmentsCache.put(table.name(), assignments);
  }

  /**
   * Builds a random derangement (permutation with no fixed points) using <em>Sattolo's
   * algorithm</em>.
   *
   * <p>Sattolo's algorithm guarantees a single-cycle permutation: every element eventually reaches
   * every other element, and no element maps to itself. For {@code n = 2}, the only result is
   * {@code [1, 0]}.
   *
   * @param n Number of rows — must be ≥ 2 (caller is responsible for validation).
   * @return {@code int[]} where {@code result[i]} is the parent index for row {@code i}.
   */
  @SuppressWarnings("java:S2245")
  private static int[] buildCircularTargets(final int n) {
    final int[] perm = IntStream.range(0, n).toArray();
    final ThreadLocalRandom rng = ThreadLocalRandom.current();
    // Sattolo: for each position i, pick j in [0, i-1] — never i
    for (int i = n - 1; i > 0; i--) {
      final int j = rng.nextInt(i); // [0, i-1] exclusive of i
      final int tmp = perm[i];
      perm[i] = perm[j];
      perm[j] = tmp;
    }
    return perm;
  }

  /**
   * Distributes {@code rows} evenly across {@code depth} levels for HIERARCHY strategy.
   *
   * <p>Level 0 contains the roots. Rows are distributed as evenly as possible; any remainder rows
   * are spread across the earlier levels.
   *
   * @param rows  All rows for the table.
   * @param depth Effective depth (already clamped to {@code rows.size()}).
   * @return A list of levels, each containing at least one row.
   */
  private static List<List<Row>> buildHierarchyLevels(final List<Row> rows, final int depth) {
    final int n = rows.size();
    final int baseSize = n / depth;
    final int remainder = n % depth;
    final List<List<Row>> levels = new ArrayList<>(depth);
    int idx = 0;
    for (int lvl = 0; lvl < depth && idx < n; lvl++) {
      final int size = baseSize + (lvl < remainder ? 1 : 0);
      final int end = Math.min(idx + Math.max(size, 1), n);
      levels.add(new ArrayList<>(rows.subList(idx, end)));
      idx = end;
    }
    return levels;
  }
}
