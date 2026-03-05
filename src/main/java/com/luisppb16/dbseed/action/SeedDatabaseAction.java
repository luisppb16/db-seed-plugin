/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.luisppb16.dbseed.config.ConnectionConfigPersistence;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.db.TopologicalSorter;
import com.luisppb16.dbseed.db.dialect.DialectFactory;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.ui.PkUuidSelectionDialog;
import com.luisppb16.dbseed.ui.SeedDialog;
import com.luisppb16.dbseed.util.DriverLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Advanced database seeding workflow orchestrator for the DBSeed plugin ecosystem.
 *
 * <p>This action class serves as the primary entry point for the DBSeed plugin functionality,
 * providing a comprehensive solution for generating synthetic database seed data. It orchestrates
 * the entire seeding process from initial database connection establishment through final SQL
 * script generation and file output. The class integrates seamlessly with the IntelliJ platform
 * action system and provides a sophisticated user interface workflow for configuring seeding
 * parameters.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Initiating the database connection workflow and driver selection process
 *   <li>Managing the multi-stage configuration dialog sequence (connection, table selection,
 *       PK/UUID configuration)
 *   <li>Performing schema introspection to analyze database structure and relationships
 *   <li>Coordinating data generation with advanced features like AI-powered content creation
 *   <li>Handling complex dependency resolution for tables with foreign key relationships
 *   <li>Generating optimized SQL scripts with proper insertion order and constraint management
 *   <li>Managing file output and integration with the IntelliJ editor environment
 *   <li>Providing progress tracking and error handling throughout the workflow
 *   <li>Implementing safeguards for large-scale data generation operations
 * </ul>
 *
 * <p>The class implements a robust error handling mechanism with appropriate user feedback through
 * IntelliJ's notification system. It supports various database systems through dynamic driver
 * loading and dialect-specific SQL generation. The workflow includes intelligent cycle detection
 * and resolution for circular foreign key dependencies, ensuring that data can be generated even in
 * complex schema scenarios.
 *
 * <p>Advanced features include AI-powered data generation using external Ollama LLM servers,
 * configurable dictionary-based content generation, soft-delete column handling, and repetition
 * rule support for consistent test data. The class also provides extensive configuration options
 * for numeric precision, UUID generation, and exclusion rules.
 *
 * <p>Thread safety is maintained through proper use of IntelliJ's application threading model, with
 * background tasks executed through the progress manager and UI updates performed on the EDT as
 * appropriate. The class follows the builder pattern for configuration objects and leverages
 * functional programming concepts for data processing.
 *
 * @author Luis Paolo Pepe Barra (@LuisPPB16)
 * @version 1.3.0
 * @since 2024.1
 * @see AnAction
 * @see SeedDialog
 * @see PkUuidSelectionDialog
 * @see SchemaIntrospector
 * @see DataGenerator
 * @see SqlGenerator
 * @see DriverLoader
 */
@Slf4j
public final class SeedDatabaseAction extends AnAction {

  private static final long INSERT_THRESHOLD = 10000L;
  private static final String PROGRESS_SEPARATOR =
      "═══════════════════════════════════════════════════════════";
  private static final String ERROR_DIALOG_TITLE = "DBSeed Error";
  private static final String SECTION_START =
      "┌─────────────────────────────────────────────────────────────";
  private static final String SECTION_END =
      "└─────────────────────────────────────────────────────────────";
  private static final String SUBSECTION = "├ ";
  private static final String LEAF = "└ ";

  private static String maskUrl(final String url) {
    if (url.length() > 50) {
      return url.substring(0, 47) + "...";
    }
    return url;
  }

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    final Project project = e.getProject();
    if (Objects.isNull(project)) {
      log.debug("Action canceled: no active project.");
      return;
    }

    try {
      final Optional<DriverInfo> chosenDriverOpt = DriverLoader.selectAndLoadDriver(project);
      if (chosenDriverOpt.isEmpty()) {
        return;
      }

      openSeedDialogFlow(project, chosenDriverOpt.get());
    } catch (final Exception ex) {
      handleException(project, "Error preparing driver: ", ex);
    }
  }

  private void openSeedDialogFlow(final Project project, final DriverInfo initialDriver) {
    Optional<DriverInfo> chosenDriverOpt = Optional.of(initialDriver);
    boolean continueLoop = true;

    while (continueLoop) {
      final DriverInfo chosenDriver = chosenDriverOpt.get();
      final SeedDialog seedDialog = new SeedDialog(chosenDriver);
      seedDialog.show();

      final int exitCode = seedDialog.getExitCode();
      switch (exitCode) {
        case DialogWrapper.OK_EXIT_CODE -> {
          runSeedGeneration(project, seedDialog.getConfiguration(), chosenDriver);
          continueLoop = false;
        }
        case SeedDialog.BACK_EXIT_CODE -> {
          try {
            chosenDriverOpt = DriverLoader.selectAndLoadDriver(project);
            if (chosenDriverOpt.isEmpty()) {
              continueLoop = false;
            }
          } catch (final Exception ex) {
            handleException(project, "Error re-selecting driver: ", ex);
            continueLoop = false;
          }
        }
        default -> {
          log.debug("Seed generation dialog canceled.");
          continueLoop = false;
        }
      }
    }
  }

  private void runSeedGeneration(
      final Project project, final GenerationConfig config, final DriverInfo chosenDriver) {
    final AtomicReference<List<Table>> tablesRef = new AtomicReference<>();
    final AtomicReference<Exception> errorRef = new AtomicReference<>();

    ProgressManager.getInstance()
        .run(
            new Task.Backgroundable(project, "Introspecting schema", false) {
              @Override
              public void run(@NotNull final ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText(SECTION_START);
                indicator.setText2(
                    SUBSECTION + "Establishing connection to " + chosenDriver.name() + "...");
                try (final Connection conn =
                    DriverManager.getConnection(
                        config.url(),
                        Objects.requireNonNullElse(config.user(), ""),
                        Objects.requireNonNullElse(config.password(), ""))) {

                  indicator.setText(PROGRESS_SEPARATOR);
                  indicator.setText2(
                      SUBSECTION + "[1/4] Connection established | URL: " + maskUrl(config.url()));
                  Thread.sleep(100);

                  indicator.setText(PROGRESS_SEPARATOR);
                  indicator.setText2(
                      SUBSECTION + "[2/4] Analyzing schema structure: " + config.schema());

                  final long introspectStart = System.currentTimeMillis();
                  final List<Table> tables =
                      SchemaIntrospector.introspect(
                          conn, config.schema(), DialectFactory.resolve(chosenDriver));
                  final long introspectElapsed = System.currentTimeMillis() - introspectStart;

                  indicator.setText(PROGRESS_SEPARATOR);
                  indicator.setText2(
                      SUBSECTION + "[3/4] Schema analysis complete (" + introspectElapsed + "ms)");
                  Thread.sleep(100);

                  indicator.setText(PROGRESS_SEPARATOR);
                  final String tablesSummary =
                      tables.stream()
                          .map(t -> t.name() + " (" + t.columns().size() + " cols)")
                          .limit(3)
                          .collect(Collectors.joining(", "));
                  final String moreInfo = tables.size() > 3 ? ", ..." : "";
                  indicator.setText2(
                      SUBSECTION
                          + "[4/4] Tables found: "
                          + tables.size()
                          + " | "
                          + tablesSummary
                          + moreInfo);
                  Thread.sleep(100);

                  indicator.setText(SECTION_END);
                  final int totalCols = tables.stream().mapToInt(t -> t.columns().size()).sum();
                  indicator.setText2(
                      LEAF
                          + "✓ Introspection successful in "
                          + introspectElapsed
                          + "ms | Total columns: "
                          + totalCols);

                  tablesRef.set(tables);
                  log.info("Schema introspection successful for schema: {}", config.schema());
                } catch (final InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  errorRef.set(ex);
                } catch (final Exception ex) {
                  errorRef.set(ex);
                }
              }

              @Override
              public void onSuccess() {
                if (Objects.nonNull(errorRef.get())) {
                  handleException(project, "Error introspecting schema: ", errorRef.get());
                  return;
                }
                final List<Table> tables = tablesRef.get();
                if (Objects.isNull(tables) || tables.isEmpty()) {
                  Messages.showErrorDialog(
                      project, "No tables found in schema: " + config.schema(), ERROR_DIALOG_TITLE);
                  return;
                }
                continueGeneration(project, config, tables, chosenDriver);
              }
            });
  }

  private void continueGeneration(
      final Project project,
      final GenerationConfig config,
      final List<Table> tables,
      final DriverInfo chosenDriver) {
    final long totalRows = (long) config.rowsPerTable() * tables.size();
    if (totalRows >= INSERT_THRESHOLD) {
      final String message =
          String.format(
              "This operation will generate approximately %,d rows."
                  + " The plugin can handle them, but your database may take some time to insert them.%n%n"
                  + "Do you still wish to continue?",
              totalRows);

      final int result =
          Messages.showOkCancelDialog(
              project,
              message,
              "Massive Data Seeding Operation",
              "Continue",
              "Cancel",
              Messages.getWarningIcon());

      if (result == Messages.CANCEL) {
        log.debug("User canceled large data seeding operation.");
        return;
      }
    }

    final TopologicalSorter.SortResult sort = TopologicalSorter.sort(tables);
    final Map<String, Table> tableByName =
        tables.stream().collect(Collectors.toMap(Table::name, Function.identity()));
    final List<Table> ordered =
        sort.ordered().stream().map(tableByName::get).filter(Objects::nonNull).toList();

    final PkUuidSelectionDialog pkDialog = new PkUuidSelectionDialog(ordered, config);
    pkDialog.show();

    final int exitCode = pkDialog.getExitCode();
    switch (exitCode) {
      case DialogWrapper.OK_EXIT_CODE -> {
        final Map<String, Set<String>> selectedPkUuidColumns = pkDialog.getSelectionByTable();
        final Map<String, Set<String>> excludedColumnsSet = pkDialog.getExcludedColumnsByTable();
        final Map<String, List<RepetitionRule>> repetitionRules = pkDialog.getRepetitionRules();
        final Map<String, Set<String>> aiColumns = pkDialog.getAiColumnsByTable();
        final Set<String> excludedTables = pkDialog.getExcludedTables();

        final Map<String, Map<String, String>> pkUuidOverrides =
            selectedPkUuidColumns.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        entry ->
                            entry.getValue().stream()
                                .collect(Collectors.toMap(Function.identity(), col -> ""))));

        final Map<String, List<String>> excludedColumns =
            excludedColumnsSet.entrySet().stream()
                .collect(
                    Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));

        final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();

        if (settings.isUseAiGeneration()
            && (Objects.isNull(settings.getAiApplicationContext())
                || settings.getAiApplicationContext().isBlank())) {
          final int aiResult =
              Messages.showYesNoDialog(
                  project,
                  "AI generation is enabled but the application context is empty.\n"
                      + "Without context, the AI model may produce less relevant data.\n\n"
                      + "Continue without application context?",
                  "Empty AI Application Context",
                  "Continue",
                  "Cancel",
                  Messages.getWarningIcon());
          if (aiResult != Messages.YES) {
            return;
          }
        }

        final GenerationConfig finalConfig =
            config.toBuilder()
                .softDeleteColumns(pkDialog.getSoftDeleteColumns())
                .softDeleteUseSchemaDefault(pkDialog.getSoftDeleteUseSchemaDefault())
                .softDeleteValue(pkDialog.getSoftDeleteValue())
                .numericScale(pkDialog.getNumericScale())
                .build();

        ConnectionConfigPersistence.save(project, finalConfig);

        ProgressManager.getInstance()
            .run(
                new Task.Backgroundable(project, APP_NAME.getValue(), true) {
                  @Override
                  public void run(@NotNull final ProgressIndicator indicator) {
                    try {
                      indicator.setIndeterminate(false);
                      indicator.setFraction(0.0);

                      // STEP 0: Configuration
                      indicator.setText(SECTION_START);
                      indicator.setText2(SUBSECTION + "STEP 0/5: Loading configuration files...");
                      Thread.sleep(50);
                      indicator.setFraction(0.05);

                      final List<Table> filteredTables =
                          ordered.stream().filter(t -> !excludedTables.contains(t.name())).toList();

                      // STEP 1: Topology
                      indicator.setText(PROGRESS_SEPARATOR);
                      indicator.setText2(
                          SUBSECTION
                              + "STEP 1/5: Table topology analysis ("
                              + filteredTables.size()
                              + " tables, "
                              + excludedTables.size()
                              + " excluded)");
                      Thread.sleep(50);
                      indicator.setFraction(0.10);

                      final boolean mustForceDeferred =
                          TopologicalSorter.requiresDeferredDueToNonNullableCycles(
                              sort, tableByName);
                      final boolean effectiveDeferred = finalConfig.deferred() || mustForceDeferred;

                      indicator.setText(PROGRESS_SEPARATOR);
                      indicator.setText2(
                          SUBSECTION
                              + "STEP 1/5: Deferred constraints mode: "
                              + (effectiveDeferred ? "ENABLED" : "disabled"));
                      Thread.sleep(50);
                      log.debug("Effective deferred: {}", effectiveDeferred);

                      // STEP 2: Data Generation (handled by DataGenerator)
                      indicator.setText(PROGRESS_SEPARATOR);
                      indicator.setText2(
                          SUBSECTION + "STEP 2/5: Generating synthetic data rows...");
                      Thread.sleep(50);
                      indicator.setFraction(0.15);

                      // DataGenerator drives the indicator fraction via ProgressTracker
                      final DataGenerator.GenerationResult gen =
                          DataGenerator.generate(
                              DataGenerator.GenerationParameters.builder()
                                  .tables(filteredTables)
                                  .rowsPerTable(finalConfig.rowsPerTable())
                                  .deferred(effectiveDeferred)
                                  .pkUuidOverrides(pkUuidOverrides)
                                  .excludedColumns(excludedColumns)
                                  .repetitionRules(repetitionRules)
                                  .useLatinDictionary(settings.isUseLatinDictionary())
                                  .useEnglishDictionary(settings.isUseEnglishDictionary())
                                  .useSpanishDictionary(settings.isUseSpanishDictionary())
                                  .softDeleteColumns(finalConfig.softDeleteColumns())
                                  .softDeleteUseSchemaDefault(
                                      finalConfig.softDeleteUseSchemaDefault())
                                  .softDeleteValue(finalConfig.softDeleteValue())
                                  .numericScale(finalConfig.numericScale())
                                  .aiColumns(aiColumns)
                                  .applicationContext(
                                      settings.isUseAiGeneration()
                                          ? settings.getAiApplicationContext()
                                          : null)
                                  .indicator(indicator)
                                  .build());
                      log.info(
                          "Data generation completed for {} rows per table.",
                          finalConfig.rowsPerTable());

                      if (indicator.isCanceled()) {
                        log.info("Data generation cancelled by user.");
                        return;
                      }

                      // STEP 3: SQL Build
                      indicator.setText(PROGRESS_SEPARATOR);
                      indicator.setText2(
                          SUBSECTION + "STEP 3/5: Building SQL INSERT statements...");
                      Thread.sleep(50);
                      indicator.setFraction(0.70);

                      final int totalRows = gen.rows().values().stream().mapToInt(List::size).sum();

                      indicator.setText(PROGRESS_SEPARATOR);
                      indicator.setText2(
                          SUBSECTION + "STEP 3/5: Processing " + totalRows + " data rows...");
                      Thread.sleep(50);

                      final String sql =
                          SqlGenerator.generate(
                              gen.rows(), gen.updates(), effectiveDeferred, chosenDriver);

                      indicator.setText(PROGRESS_SEPARATOR);
                      final long sqlLines = sql.split("\n").length;
                      indicator.setText2(
                          SUBSECTION + "STEP 3/5: Generated " + sqlLines + " SQL lines");
                      Thread.sleep(50);
                      indicator.setFraction(0.85);

                      // STEP 4: File Output
                      indicator.setText(PROGRESS_SEPARATOR);
                      indicator.setText2(SUBSECTION + "STEP 4/5: Preparing output file...");
                      Thread.sleep(50);
                      indicator.setFraction(0.90);

                      // STEP 5: Complete
                      indicator.setText(PROGRESS_SEPARATOR);
                      indicator.setText2(SUBSECTION + "STEP 5/5: Finalizing...");
                      indicator.setFraction(0.95);
                      Thread.sleep(100);

                      indicator.setText(SECTION_END);
                      final long updateCount = gen.updates().size();
                      final long tableCount = gen.rows().size();
                      final long rowsPerTableAvg = tableCount > 0 ? totalRows / tableCount : 0;
                      final long totalColumnsCount =
                          gen.rows().entrySet().stream()
                              .mapToLong(
                                  e -> (long) e.getKey().columns().size() * e.getValue().size())
                              .sum();
                      indicator.setText2(
                          LEAF
                              + "✓ COMPLETED: "
                              + totalRows
                              + " rows across "
                              + tableCount
                              + " tables | "
                              + updateCount
                              + " deferred updates | "
                              + totalColumnsCount
                              + " cell values | Avg: "
                              + rowsPerTableAvg
                              + " rows/table");
                      indicator.setFraction(1.0);

                      log.info("SQL script built successfully.");

                      ApplicationManager.getApplication()
                          .invokeLater(() -> saveAndOpenSqlFile(project, sql));
                    } catch (final InterruptedException ex) {
                      Thread.currentThread().interrupt();
                      log.error("Generation interrupted: {}", ex.getMessage());
                    } catch (final Exception ex) {
                      handleException(project, "Error during SQL generation: ", ex);
                    }
                  }
                });
      }
      case PkUuidSelectionDialog.BACK_EXIT_CODE -> {
        log.debug("User navigated back from PK UUID selection.");
        openSeedDialogFlow(project, chosenDriver);
      }
      default -> log.debug("PK UUID selection canceled.");
    }
  }

  private void saveAndOpenSqlFile(final Project project, final String sql) {
    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    final String outputDir = settings.getDefaultOutputDirectory();
    final String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    final String fileName = String.format("V%s__seed.sql", timestamp);

    final String basePath = project.getBasePath();
    if (Objects.isNull(basePath)) {
      Messages.showErrorDialog(project, "Could not determine project base path.", "DBSeed Error");
      return;
    }

    final Path path = Paths.get(basePath, outputDir, fileName);

    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, sql, StandardCharsets.UTF_8);

      final VirtualFile virtualFile =
          LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
      if (Objects.nonNull(virtualFile)) {
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
        log.info("File {} saved and opened in the editor.", fileName);
      } else {
        log.warn("Could not find VirtualFile for path: {}", path);
        Messages.showErrorDialog(project, "Could not open generated SQL file.", "DBSeed Error");
      }
    } catch (final IOException e) {
      handleException(project, "Error saving SQL file: ", e);
    }
  }

  private void handleException(final Project project, final String message, final Exception ex) {
    log.error(message, ex);
    final String fullMessage = message + ex.getMessage();
    ApplicationManager.getApplication()
        .invokeLater(
            () -> Messages.showErrorDialog(project, fullMessage, "DBSeed Error"),
            ModalityState.defaultModalityState());
  }
}
