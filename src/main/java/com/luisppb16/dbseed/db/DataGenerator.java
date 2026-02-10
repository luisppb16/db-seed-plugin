/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.db.generator.ConstraintParser;
import com.luisppb16.dbseed.db.generator.DictionaryLoader;
import com.luisppb16.dbseed.db.generator.ForeignKeyResolver;
import com.luisppb16.dbseed.db.generator.RowGenerator;
import com.luisppb16.dbseed.db.generator.ValueGenerator;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import net.datafaker.Faker;

/**
 * Centralized data generation orchestration engine for the DBSeed plugin ecosystem.
 * <p>
 * This utility class serves as the primary orchestrator for the entire data generation process,
 * coordinating multiple subsystems including row generation, constraint validation, foreign key
 * resolution, and dictionary-based value generation. It implements sophisticated algorithms
 * for handling complex database relationships, constraint satisfaction, and data consistency
 * across interconnected tables. The class manages the complete lifecycle of data generation
 * from schema analysis to final output preparation.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Coordinating the multi-phase data generation pipeline across all tables</li>
 *   <li>Managing primary key UUID overrides and custom generation patterns</li>
 *   <li>Resolving foreign key dependencies with support for deferred constraint processing</li>
 *   <li>Applying repetition rules and custom data generation configurations</li>
 *   <li>Validating generated data against parsed database constraints</li>
 *   <li>Integrating with dictionary systems for realistic data generation</li>
 * </ul>
 * </p>
 * <p>
 * The implementation follows a layered architecture where each aspect of data generation
 * is handled by specialized components. The class ensures proper ordering of table processing
 * to respect foreign key dependencies and implements retry mechanisms for constraint resolution.
 * It maintains global state for UUID uniqueness and coordinates with the foreign key resolver
 * to handle inter-table dependencies appropriately.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 */
@UtilityClass
public class DataGenerator {

  private static final int MAX_GENERATE_ATTEMPTS = 100;

  public static GenerationResult generate(final GenerationParameters params) {
    final List<Table> orderedTables = applyPkUuidOverrides(params.tables(), params.pkUuidOverrides());
    final Map<String, Table> tableMap =
        orderedTables.stream().collect(Collectors.toUnmodifiableMap(Table::name, t -> t));

    final List<String> dictionaryWords =
        DictionaryLoader.loadWords(params.useEnglishDictionary(), params.useSpanishDictionary());
    final Faker faker = new Faker();
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    final Set<UUID> usedUuids = new HashSet<>();
    final Map<String, Map<String, ConstraintParser.ParsedConstraint>> tableConstraints = new HashMap<>();
    final Set<String> softDeleteCols = parseSoftDeleteColumns(params.softDeleteColumns());

    // Convert excluded columns from List to Set
    final Map<String, Set<String>> excludedColumnsSet = params.excludedColumns().entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));

    generateTableRows(
        orderedTables,
        params.rowsPerTable(),
        excludedColumnsSet,
        params.repetitionRules(),
        faker,
        usedUuids,
        tableConstraints,
        data,
        dictionaryWords,
        params.useLatinDictionary(),
        softDeleteCols,
        params.softDeleteUseSchemaDefault(),
        params.softDeleteValue(),
        params.numericScale());

    validateNumericConstraints(orderedTables, tableConstraints, data, params.numericScale());

    final ForeignKeyResolver fkResolver =
        new ForeignKeyResolver(tableMap, data, params.deferred());
    final List<PendingUpdate> updates = fkResolver.resolve();

    return new GenerationResult(data, updates);
  }

  private static List<Table> applyPkUuidOverrides(
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
                      newCols.add(new Column(
                          c.name(),
                          c.jdbcType(),
                          c.nullable(),
                          c.primaryKey(),
                          true,
                          c.length(),
                          c.scale(),
                          c.minValue(),
                          c.maxValue(),
                          c.allowedValues()));
                    } else {
                      newCols.add(c);
                    }
                  });
          overridden.put(
              t.name(),
              new Table(
                  t.name(), newCols, t.primaryKey(), t.foreignKeys(), t.checks(), t.uniqueKeys()));
        });
    return List.copyOf(overridden.values());
  }

  private static Set<String> parseSoftDeleteColumns(final String softDeleteColumns) {
    final Set<String> softDeleteCols = new HashSet<>();
    if (softDeleteColumns != null) {
      java.util.Arrays.stream(softDeleteColumns.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .forEach(softDeleteCols::add);
    }
    return softDeleteCols;
  }

  private static void generateTableRows(
      final List<Table> orderedTables,
      final int rowsPerTable,
      final Map<String, Set<String>> excludedColumns,
      final Map<String, List<RepetitionRule>> repetitionRules,
      final Faker faker,
      final Set<UUID> usedUuids,
      final Map<String, Map<String, ConstraintParser.ParsedConstraint>> tableConstraints,
      final Map<Table, List<Row>> data,
      final List<String> dictionaryWords,
      final boolean useLatinDictionary,
      final Set<String> softDeleteCols,
      final boolean softDeleteUseSchemaDefault,
      final String softDeleteValue,
      final int numericScale) {

    for (final Table table : orderedTables) {
      if (table.columns().isEmpty()) {
        data.put(table, Collections.emptyList());
        continue;
      }

      final RowGenerator rowGenerator =
          new RowGenerator(
              table,
              rowsPerTable,
              excludedColumns.getOrDefault(table.name(), Set.of()),
              repetitionRules != null ? repetitionRules.getOrDefault(table.name(), Collections.emptyList()) : Collections.emptyList(),
              faker,
              usedUuids,
              dictionaryWords,
              useLatinDictionary,
              softDeleteCols,
              softDeleteUseSchemaDefault,
              softDeleteValue,
              numericScale);

      final List<Row> rows = rowGenerator.generate();
      data.put(table, rows);
      tableConstraints.put(table.name(), rowGenerator.getConstraints());
    }
  }

  private static void validateNumericConstraints(
      final List<Table> orderedTables,
      final Map<String, Map<String, ConstraintParser.ParsedConstraint>> tableConstraints,
      final Map<Table, List<Row>> data,
      final int numericScale) {

    for (final Table table : orderedTables) {
      final Map<String, ConstraintParser.ParsedConstraint> constraints =
          tableConstraints.getOrDefault(table.name(), Map.of());
      final List<Row> rows = data.get(table);
      if (rows == null) continue;
      for (final Row row : rows) {
        validateRowNumericConstraints(table, row, constraints, numericScale);
      }
    }
  }

  private static void validateRowNumericConstraints(
      final Table table,
      final Row row,
      final Map<String, ConstraintParser.ParsedConstraint> constraints,
      final int numericScale) {
    
    for (final Column col : table.columns()) {
      final ConstraintParser.ParsedConstraint pc = constraints.get(col.name());
      Object val = row.values().get(col.name());
      if (ValueGenerator.isNumericJdbc(col.jdbcType()) && pc != null && (pc.min() != null || pc.max() != null)) {
        int attempts = 0;
        while (ValueGenerator.isNumericOutsideBounds(val, pc) && attempts < MAX_GENERATE_ATTEMPTS) {
          final ValueGenerator vg = new ValueGenerator(new Faker(), null, false, new HashSet<>(), numericScale);
          val = vg.generateNumericWithinBounds(col, pc);
          attempts++;
        }
        row.values().put(col.name(), val);
      }
    }
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

  public record GenerationResult(Map<Table, List<Row>> rows, List<PendingUpdate> updates) {}
}
