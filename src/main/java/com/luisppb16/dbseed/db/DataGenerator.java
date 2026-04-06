/*
 * *****************************************************************************
 *  * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 *  * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import com.intellij.openapi.progress.ProgressIndicator;
import com.luisppb16.dbseed.ai.OllamaClient;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.db.generator.ConstraintParser;
import com.luisppb16.dbseed.db.generator.DictionaryLoader;
import com.luisppb16.dbseed.db.generator.ForeignKeyResolver;
import com.luisppb16.dbseed.db.generator.RowGenerator;
import com.luisppb16.dbseed.db.generator.ValueGenerator;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

/**
 * Advanced data generation orchestration engine for the DBSeed plugin ecosystem.
 *
 * <p>This utility class serves as the central hub for synthetic data generation, implementing
 * sophisticated algorithms for creating realistic test data that respects database schema
 * constraints and relationships. It coordinates multiple data generation strategies including
 * dictionary-based content, AI-powered generation, and constraint-aware value creation. The class
 * handles complex scenarios such as foreign key dependencies, check constraints, and numeric bounds
 * validation.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Orchestrating the multi-phase data generation process across all tables
 *   <li>Applying primary key UUID overrides and exclusion rules to table configurations
 *   <li>Managing dictionary loading and selection for realistic string content generation
 *   <li>Coordinating AI-powered content generation through Ollama integration
 *   <li>Validating generated data against check constraints and numeric bounds
 *   <li>Resolving foreign key dependencies through the ForeignKeyResolver component
 *   <li>Handling soft-delete column configurations and values
 *   <li>Managing repetition rules for consistent test data patterns
 *   <li>Tracking and validating UUID uniqueness across the generated dataset
 *   <li>Coordinating with progress indicators for user feedback during generation
 * </ul>
 *
 * <p>The class implements advanced constraint validation algorithms to ensure generated data
 * complies with database check constraints, including numeric bounds and allowed value sets. It
 * features intelligent fallback mechanisms when AI generation fails, gracefully degrading to
 * traditional dictionary-based approaches. The implementation includes sophisticated algorithms for
 * handling numeric constraints, ensuring values fall within specified ranges and meet custom check
 * constraint requirements.
 *
 * <p>Thread safety is maintained through immutable data structures and careful coordination between
 * concurrent AI requests and sequential data validation. The class leverages the builder pattern
 * for configuration parameters and implements efficient caching mechanisms for dictionary words and
 * constraint parsing results. Memory efficiency is achieved through streaming operations and lazy
 * evaluation where possible.
 *
 * <p>The data generation process includes multiple validation phases to ensure referential
 * integrity and constraint compliance. Foreign key relationships are resolved in a post-processing
 * phase using the ForeignKeyResolver, which handles complex scenarios involving deferred constraint
 * processing for circular dependencies. The class also implements retry mechanisms for
 * constraint-bound numeric values, ensuring generated data meets all specified criteria.
 *
 * @author Luis Paolo Pepe Barra (@LuisPPB16)
 * @version 1.3.5
 * @since 2025.1
 * @see RowGenerator
 * @see ValueGenerator
 * @see ForeignKeyResolver
 * @see DictionaryLoader
 * @see OllamaClient
 * @see ConstraintParser
 * @see GenerationParameters
 * @see GenerationResult
 */
@Slf4j
@UtilityClass
public class DataGenerator {

  private static final int MAX_GENERATE_ATTEMPTS = 100;
  private static final String SOFT_DELETE_DELIMITER = ",";
  private static final String EMPTY_CONTEXT = "";

  /**
   * Global executor for AI column generation.
   *
   * <p>The hotfix that serialized AI work column-by-column removed all useful overlap between
   * columns/tables and caused large regressions on machines where Ollama can queue or process more
   * than one request efficiently. We keep a bounded pool to recover throughput without
   * reintroducing the unbounded contention of the old nested parallelism.
   */
  private static final ExecutorService AI_COLUMN_EXECUTOR =
      Executors.newFixedThreadPool(
          Math.clamp(Runtime.getRuntime().availableProcessors(), 2, 4),
          r -> {
            final Thread thread = new Thread(r, "ai-col-gen");
            thread.setDaemon(true);
            return thread;
          });

  public static GenerationResult generate(final GenerationParameters params) {
    final List<Table> orderedTables =
        applyPkUuidOverrides(params.tables(), params.pkUuidOverrides());
    final Map<String, Table> tableMap =
        orderedTables.stream().collect(Collectors.toUnmodifiableMap(Table::name, t -> t));

    final List<String> dictionaryWords =
        DictionaryLoader.loadWords(params.useEnglishDictionary(), params.useSpanishDictionary());
    final Faker faker = new Faker();
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    final Set<UUID> usedUuids = new HashSet<>();
    final Map<String, Map<String, ConstraintParser.ParsedConstraint>> tableConstraints =
        new HashMap<>();
    final Set<String> softDeleteCols = parseSoftDeleteColumns(params.softDeleteColumns());

    final Map<String, Set<String>> excludedColumnsSet =
        params.excludedColumns().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));

    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    final OllamaClient ollamaClient =
        settings.isUseAiGeneration()
                && Objects.nonNull(settings.getOllamaUrl())
                && !settings.getOllamaUrl().isBlank()
            ? new OllamaClient(
                settings.getOllamaUrl(),
                settings.getOllamaModel(),
                settings.getAiRequestTimeoutSeconds())
            : null;

    final Map<String, Set<String>> aiColumns =
        Objects.requireNonNullElse(params.aiColumns(), Map.of());
    final int aiWordCount = settings.getAiWordCount();

    // ── Calculate TOTAL real work units up-front ──────────────────────────────
    // 1 unit per row to generate (rows phase)
    final long rowWork = (long) orderedTables.size() * params.rowsPerTable();
    // 1 unit per AI-column×row (AI phase) — 0 when no AI
    final long aiWork =
        Objects.nonNull(ollamaClient)
            ? orderedTables.stream()
                .mapToLong(
                    t ->
                        (long) aiColumns.getOrDefault(t.name(), Set.of()).size()
                            * params.rowsPerTable())
                .sum()
            : 0;
    // 1 unit per table for constraint validation
    final long validateWork = orderedTables.size();
    // 1 unit per table for FK resolution
    final long fkWork = orderedTables.size();
    // Grand total
    final long totalWork = rowWork + aiWork + validateWork + fkWork;

    final ProgressTracker tracker = new ProgressTracker(params.indicator(), totalWork);

    // Pre-warm the AI model so it's loaded in VRAM before the first batch request.
    // This eliminates cold-start latency on the first real generation call.
    if (Objects.nonNull(ollamaClient) && !aiColumns.isEmpty()) {
      try {
        tracker.setText("Warming up AI model...");
        tracker.setText2("Loading " + settings.getOllamaModel() + " into memory");
        ollamaClient.warmModel().join();
      } catch (final Exception e) {
        log.warn("Model warm-up failed, proceeding anyway: {}", e.getMessage());
      }
    }

    final List<RowGenerator> generators =
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
            params.numericScale(),
            aiColumns,
            aiWordCount,
            ollamaClient,
            Objects.requireNonNullElse(params.applicationContext(), EMPTY_CONTEXT),
            tracker);

    // Phase 2: Run AI generation with bounded parallelism across AI columns
    generateAiValues(generators, tracker);

    tracker.setText("Validating constraints...");
    tracker.setText2("Checking numeric bounds for " + orderedTables.size() + " tables");
    validateNumericConstraints(
        orderedTables, tableConstraints, data, params.numericScale(), tracker);

    tracker.setText("Resolving foreign keys...");
    tracker.setText2("Processing deferred FK dependencies");
    final ForeignKeyResolver fkResolver =
        new ForeignKeyResolver(
            tableMap,
            data,
            params.deferred(),
            params.circularReferences(),
            params.circularReferenceTerminationModes());
    final List<PendingUpdate> updates = fkResolver.resolve(tracker);

    tracker.setText2(updates.size() + " deferred updates created");

    return new GenerationResult(data, updates);
  }

  private static List<Table> applyPkUuidOverrides(
      final List<Table> tables, final Map<String, Map<String, String>> pkUuidOverrides) {
    final Map<String, Table> overridden = new LinkedHashMap<>();
    tables.forEach(
        t -> {
          final Map<String, String> pkOverridesForTable =
              Objects.nonNull(pkUuidOverrides) ? pkUuidOverrides.get(t.name()) : null;
          if (Objects.isNull(pkOverridesForTable) || pkOverridesForTable.isEmpty()) {
            overridden.put(t.name(), t);
            return;
          }
          final List<Column> newCols =
              t.columns().stream()
                  .map(
                      c -> {
                        final boolean forceUuid = pkOverridesForTable.containsKey(c.name());
                        final boolean isIntegerType =
                            c.jdbcType() == Types.INTEGER
                                || c.jdbcType() == Types.BIGINT
                                || c.jdbcType() == Types.SMALLINT
                                || c.jdbcType() == Types.TINYINT;
                        return forceUuid && !c.uuid() && !isIntegerType
                            ? c.toBuilder().uuid(true).build()
                            : c;
                      })
                  .toList();
          overridden.put(t.name(), t.toBuilder().columns(newCols).build());
        });
    return List.copyOf(overridden.values());
  }

  private static Set<String> parseSoftDeleteColumns(final String softDeleteColumns) {
    final Set<String> softDeleteCols = new HashSet<>();
    if (Objects.nonNull(softDeleteColumns)) {
      Arrays.stream(softDeleteColumns.split(SOFT_DELETE_DELIMITER))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .forEach(softDeleteCols::add);
    }
    return softDeleteCols;
  }

  private static List<RowGenerator> generateTableRows(
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
      final int numericScale,
      final Map<String, Set<String>> aiColumns,
      final int aiWordCount,
      final OllamaClient ollamaClient,
      final String applicationContext,
      final ProgressTracker tracker) {

    final int totalTables = orderedTables.size();
    final long startTime = System.currentTimeMillis();
    final List<RowGenerator> generators = new ArrayList<>();

    IntStream.range(0, totalTables)
        .forEach(
            i -> {
              final Table table = orderedTables.get(i);
              tracker.setText(
                  "Generating table "
                      .concat(String.valueOf(i + 1))
                      .concat("/")
                      .concat(String.valueOf(totalTables))
                      .concat(": ")
                      .concat(table.name()));
              tracker.setText2(
                  table.columns().size() + " columns, " + rowsPerTable + " rows to generate");

              if (table.columns().isEmpty()) {
                data.put(table, Collections.emptyList());
                // count the rows-worth of work as done even for empty tables
                tracker.advance(rowsPerTable);
                return;
              }

              final RowGenerator rowGenerator =
                  new RowGenerator(
                      table,
                      rowsPerTable,
                      excludedColumns.getOrDefault(table.name(), Set.of()),
                      Objects.nonNull(repetitionRules)
                          ? repetitionRules.getOrDefault(table.name(), Collections.emptyList())
                          : Collections.emptyList(),
                      faker,
                      usedUuids,
                      dictionaryWords,
                      useLatinDictionary,
                      softDeleteCols,
                      softDeleteUseSchemaDefault,
                      softDeleteValue,
                      numericScale,
                      aiColumns.getOrDefault(table.name(), Set.of()),
                      aiWordCount,
                      ollamaClient,
                      applicationContext,
                      tracker);

              final List<Row> rows = rowGenerator.generate();
              data.put(table, rows);
              tableConstraints.put(table.name(), rowGenerator.getConstraints());
              generators.add(rowGenerator);

              // Advance by however many rows were actually generated (the RowGenerator
              // already advanced per-row; reconcile any gap if fewer rows were produced).
              // RowGenerator advances inside fillRemainingRows, so nothing extra here.

              final long elapsed = System.currentTimeMillis() - startTime;
              tracker.setText2(rows.size() + " rows generated — " + (elapsed / 1000) + "s elapsed");
            });

    return generators;
  }

  /** Phase 2: Runs AI value generation with bounded parallelism across all selected AI columns. */
  private static void generateAiValues(
      final List<RowGenerator> generators, final ProgressTracker tracker) {
    final List<RowGenerator> aiGenerators =
        generators.stream().filter(RowGenerator::hasAiColumns).toList();

    if (aiGenerators.isEmpty()) return;

    final long totalAiColumns =
        aiGenerators.stream().mapToLong(g -> g.getValidAiColumns().size()).sum();
    final AtomicInteger completedColumns = new AtomicInteger(0);
    final List<CompletableFuture<Void>> futures = new ArrayList<>();

    tracker.setText("AI column 0/" + totalAiColumns);
    tracker.setText2(totalAiColumns + " AI columns across " + aiGenerators.size() + " tables");

    for (final RowGenerator gen : aiGenerators) {
      for (final Column col : gen.getValidAiColumns()) {
        futures.add(
            CompletableFuture.runAsync(
                () -> {
                  if (tracker.isCanceled()) return;

                  try {
                    gen.generateAiValuesForColumn(col);
                  } catch (final Exception ex) {
                    log.warn("AI generation failed for column {}: {}", col.name(), ex.getMessage());
                  } finally {
                    final int completed = completedColumns.incrementAndGet();
                    tracker.setText(
                        "AI column "
                            .concat(String.valueOf(completed))
                            .concat("/")
                            .concat(String.valueOf(totalAiColumns)));
                  }
                },
                AI_COLUMN_EXECUTOR));
      }
    }

    try {
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    } catch (final Exception ex) {
      log.warn("Some AI column generations failed: {}", ex.getMessage());
    }

    tracker.setText2("AI generation complete");
  }

  private static void validateNumericConstraints(
      final List<Table> orderedTables,
      final Map<String, Map<String, ConstraintParser.ParsedConstraint>> tableConstraints,
      final Map<Table, List<Row>> data,
      final int numericScale,
      final ProgressTracker tracker) {

    final Faker faker = new Faker();
    final ValueGenerator vg = new ValueGenerator(faker, null, false, new HashSet<>(), numericScale);

    orderedTables.forEach(
        table -> {
          final Map<String, ConstraintParser.ParsedConstraint> constraints =
              tableConstraints.getOrDefault(table.name(), Map.of());
          final List<Row> rows = data.get(table);
          if (Objects.nonNull(rows)) {
            rows.forEach(row -> validateRowNumericConstraints(table, row, constraints, vg));
          }
          tracker.advance(); // 1 unit per table
        });
  }

  private static void validateRowNumericConstraints(
      final Table table,
      final Row row,
      final Map<String, ConstraintParser.ParsedConstraint> constraints,
      final ValueGenerator vg) {

    table
        .columns()
        .forEach(
            col -> {
              final ConstraintParser.ParsedConstraint pc = constraints.get(col.name());
              Object val = row.values().get(col.name());
              if (ValueGenerator.isNumericJdbc(col.jdbcType())
                  && Objects.nonNull(pc)
                  && (Objects.nonNull(pc.min()) || Objects.nonNull(pc.max()))) {
                if (Objects.nonNull(val)
                    && Objects.nonNull(pc.allowedValues())
                    && !pc.allowedValues().isEmpty()
                    && pc.allowedValues().contains(String.valueOf(val))) {
                  return;
                }
                int attempts = 0;
                while (ValueGenerator.isNumericOutsideBounds(val, pc)
                    && attempts < MAX_GENERATE_ATTEMPTS) {
                  val = vg.generateNumericWithinBounds(col, pc);
                  attempts++;
                }
                row.values().put(col.name(), val);
              }
            });
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
      Map<String, Set<String>> aiColumns,
      String applicationContext,
      ProgressIndicator indicator,
      Map<String, Map<String, Integer>> circularReferences,
      Map<String, Map<String, String>> circularReferenceTerminationModes) {}

  public record GenerationResult(Map<Table, List<Row>> rows, List<PendingUpdate> updates) {}
}
