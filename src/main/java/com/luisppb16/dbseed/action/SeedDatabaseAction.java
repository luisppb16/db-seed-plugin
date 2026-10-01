/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.ProgressTracker;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.db.TopologicalSorter;
import com.luisppb16.dbseed.db.dialect.DialectFactory;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.ui.GenerationProgressDialog;
import com.luisppb16.dbseed.ui.PkUuidSelectionDialog;
import com.luisppb16.dbseed.ui.SeedDialog;
import com.luisppb16.dbseed.util.DriverLoader;
import com.luisppb16.dbseed.util.NotificationHelper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * <p>Advanced features include AI-powered data generation using an external AI engine, configurable
 * dictionary-based content generation, soft-delete column handling, and repetition rule support for
 * consistent test data. The class also provides extensive configuration options for numeric
 * precision, UUID generation, and exclusion rules.
 *
 * <p>Thread safety is maintained through proper use of IntelliJ's application threading model, with
 * background tasks executed through the progress manager and UI updates performed on the EDT as
 * appropriate. The class follows the builder pattern for configuration objects and leverages
 * functional programming concepts for data processing.
 *
 * @see AnAction
 * @see SeedDialog
 * @see PkUuidSelectionDialog
 * @see SchemaIntrospector
 * @see DataGenerator
 * @see SqlGenerator
 * @see DriverLoader
 */
@Slf4j
public final class SeedDatabaseAction extends AnAction implements DumbAware {

  private static final long INSERT_THRESHOLD = 10000L;
  private static final DateTimeFormatter FILE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

  /** Returns true when at least one AI column was selected in at least one table. */
  private static boolean hasAnyAiColumn(final Map<String, Set<String>> aiColumns) {
    return Objects.nonNull(aiColumns)
        && aiColumns.values().stream().anyMatch(columns -> !columns.isEmpty());
  }

  /**
   * Surfaces the AI generation outcome to the user. The generation always completes with DataFaker
   * data as fallback, but a total failure is reported as an error and partial failures as a warning
   * with the per-column summary — never silently.
   */
  private static void notifyAiReport(
      final Project project,
      final DataGenerator.AiGenerationReport report,
      final Map<String, Set<String>> aiColumns) {
    if (!hasAnyAiColumn(aiColumns)) {
      return;
    }

    if (!report.modelConfigured()) {
      NotificationHelper.notifyWarning(
          project,
          "AI generation skipped",
          "No AI model is selected (Settings → DBSeed4SQL)."
              + " All rows were filled with DataFaker data.");
      return;
    }

    if (Objects.nonNull(report.warmUpError())) {
      NotificationHelper.notifyError(
          project,
          "AI generation failed: "
              + report.warmUpError()
              + ". The SQL file was generated with DataFaker data instead.");
      return;
    }

    if (report.isTotalFailure()) {
      final String cause =
          report.columns().stream()
              .map(DataGenerator.AiColumnStat::lastError)
              .filter(Objects::nonNull)
              .findFirst()
              .orElse("unknown AI engine error");
      NotificationHelper.notifyError(
          project,
          "AI generation failed for all selected columns: "
              + cause
              + ". The SQL file was generated with DataFaker data instead.");
      return;
    }

    if (report.hasFailures()) {
      final String summary =
          report.columns().stream()
              .filter(DataGenerator.AiColumnStat::hasDeficit)
              .map(
                  col ->
                      "• %s.%s: %d/%d AI values%s"
                          .formatted(
                              col.tableName(),
                              col.columnName(),
                              col.appliedAiValues(),
                              col.requestedRows(),
                              Objects.nonNull(col.lastError()) ? " (" + col.lastError() + ")" : ""))
              .collect(Collectors.joining("\n"));
      NotificationHelper.notifyWarning(
          project, "AI generation partially failed", "Rows filled with DataFaker:\n" + summary);
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
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
      final SeedDialog seedDialog = new SeedDialog(project, chosenDriver);
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
            new Task.Backgroundable(project, "Introspecting Schema", true) {
              @Override
              public void run(@NotNull final ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try (final Connection conn =
                    DriverManager.getConnection(
                        config.url(),
                        Objects.requireNonNullElse(config.user(), ""),
                        Objects.requireNonNullElse(config.password(), ""))) {
                  tablesRef.set(
                      SchemaIntrospector.introspect(
                          conn, config.schema(), DialectFactory.resolve(chosenDriver)));
                  log.info("Schema introspection successful for schema: {}", config.schema());
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
                      project, "No tables found in schema: " + config.schema(), "DBSeed Error");
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

        if (settings.isUseAiGeneration()
            && hasAnyAiColumn(aiColumns)
            && (Objects.isNull(settings.getAiModel()) || settings.getAiModel().isBlank())) {
          Messages.showErrorDialog(
              project,
              "AI generation is enabled but no AI model is selected.\n"
                  + "Please select a model in Settings → DBSeed4SQL, or disable AI generation.",
              "Missing AI Model");
          return;
        }

        final GenerationConfig finalConfig =
            config.withSoftDeleteSettings(
                pkDialog.getSoftDeleteColumns(),
                pkDialog.getSoftDeleteUseSchemaDefault(),
                pkDialog.getSoftDeleteValue(),
                pkDialog.getNumericScale());

        final AtomicReference<ProgressIndicator> indicatorRef = new AtomicReference<>();
        final GenerationProgressDialog progressDialog =
            new GenerationProgressDialog(
                project, indicatorRef, settings.isUseAiGeneration() && hasAnyAiColumn(aiColumns));
        progressDialog.show();

        final Task.Backgroundable generationTask =
            new Task.Backgroundable(project, APP_NAME.getValue(), true) {
              @Override
              public void run(@NotNull final ProgressIndicator indicator) {
                indicatorRef.set(indicator);
                try {
                  indicator.setIndeterminate(false);
                  indicator.setFraction(0.0);
                  indicator.setText("Preparing generation...");

                  final List<Table> filteredTables =
                      ordered.stream().filter(t -> !excludedTables.contains(t.name())).toList();

                  indicator.setText("Sorting tables...");
                  indicator.setText2(
                      "Resolving dependency order for " + filteredTables.size() + " tables");

                  final boolean mustForceDeferred =
                      TopologicalSorter.requiresDeferredDueToNonNullableCycles(sort, tableByName);
                  final boolean effectiveDeferred = finalConfig.deferred() || mustForceDeferred;
                  log.debug("Effective deferred: {}", effectiveDeferred);

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
                              .softDeleteUseSchemaDefault(finalConfig.softDeleteUseSchemaDefault())
                              .softDeleteValue(finalConfig.softDeleteValue())
                              .numericScale(finalConfig.numericScale())
                              .aiColumns(aiColumns)
                              .circularReferences(pkDialog.getCircularReferences())
                              .circularReferenceTerminationModes(
                                  pkDialog.getCircularReferenceTerminationModes())
                              .applicationContext(
                                  settings.isUseAiGeneration()
                                      ? settings.getAiApplicationContext()
                                      : null)
                              .indicator(indicator)
                              .progressListener(progressDialog.progressListener())
                              .build());
                  log.info(
                      "Data generation completed for "
                          + finalConfig.rowsPerTable()
                          + " rows per table.");

                  // A voluntary cancellation must not surface as an AI failure: canceled
                  // columns report 0 applied values without any error cause.
                  if (indicator.isCanceled()) return;

                  notifyAiReport(project, gen.aiReport(), aiColumns);

                  // The tracker still holds the SQL phase's per-table units: SqlGenerator
                  // advances them so the overall bar keeps moving until the script is built.
                  final ProgressTracker progress = Objects.requireNonNull(gen.progress());
                  progress.setText("Building SQL...");
                  progress.setText2(
                      "Generating INSERT statements for " + gen.rows().size() + " tables");
                  final String sql =
                      SqlGenerator.generate(
                          gen.rows(), gen.updates(), effectiveDeferred, chosenDriver, progress);
                  indicator.setFraction(1.0);
                  indicator.setText("Done!");
                  progressDialog.progressListener().onGeneral(1.0, "Done!", null);
                  log.info("SQL script built successfully.");

                  final Path filePath = writeSqlFile(project, sql);
                  if (filePath != null) {
                    ApplicationManager.getApplication()
                        .invokeLater(() -> openFileInEditor(project, filePath));
                  } else {
                    ApplicationManager.getApplication()
                        .invokeLater(
                            () ->
                                Messages.showErrorDialog(
                                    project,
                                    "Could not write the generated SQL file. See the IDE log for details.",
                                    "DBSeed Error"),
                            ModalityState.defaultModalityState());
                  }
                } catch (final Exception ex) {
                  handleException(project, "Error during SQL generation: ", ex);
                }
              }

              @Override
              public void onFinished() {
                progressDialog.closeSafely();
              }
            };

        // Start the generation on the next EDT cycle instead of straight after show(): otherwise
        // a fast rows phase can finish (and its coalesced flush paint the table bar at 100%)
        // before the dialog is on screen, so the bar looks full the moment the window opens.
        ApplicationManager.getApplication()
            .invokeLater(
                () -> ProgressManager.getInstance().run(generationTask), ModalityState.any());
      }
      case PkUuidSelectionDialog.BACK_EXIT_CODE -> {
        log.debug("User navigated back from PK UUID selection.");
        openSeedDialogFlow(project, chosenDriver);
      }
      default -> log.debug("PK UUID selection canceled.");
    }
  }

  private Path writeSqlFile(final Project project, final String sql) {
    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    final String outputDir = settings.getDefaultOutputDirectory();
    final String timestamp = FILE_TIMESTAMP.format(LocalDateTime.now());
    final String fileName = String.format("V%s__seed.sql", timestamp);

    final String basePath = project.getBasePath();
    if (Objects.isNull(basePath)) {
      log.error("Could not determine project base path.");
      return null;
    }

    final Path path = Paths.get(basePath, outputDir, fileName);

    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, sql, StandardCharsets.UTF_8);
      log.info("SQL file written to: {}", path);
      return path;
    } catch (final IOException e) {
      log.error("Error writing SQL file: {}", path, e);
      return null;
    }
  }

  private void openFileInEditor(final Project project, final Path path) {
    final VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
    if (Objects.nonNull(virtualFile)) {
      FileEditorManager.getInstance(project).openFile(virtualFile, true);
      log.info("File {} opened in the editor.", path.getFileName());
    } else {
      log.warn("Could not find VirtualFile for path: {}", path);
      Messages.showErrorDialog(project, "Could not open generated SQL file.", "DBSeed Error");
    }
  }

  private void handleException(final Project project, final String message, final Exception ex) {
    log.error(message, ex);
    final String detail = Objects.toString(ex.getMessage(), ex.getClass().getSimpleName());
    final String fullMessage = message + detail;
    ApplicationManager.getApplication()
        .invokeLater(
            () -> Messages.showErrorDialog(project, fullMessage, "DBSeed Error"),
            ModalityState.defaultModalityState());
  }
}
