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

/** Comprehensive database seeding workflow orchestrator for the DBSeed plugin ecosystem. */
@Slf4j
public final class SeedDatabaseAction extends AnAction {

  private static final long INSERT_THRESHOLD = 10000L;

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
      final DriverInfo chosenDriver = chosenDriverOpt.get();
      showSeedDialog(project, chosenDriver);
    } catch (final Exception ex) {
      handleException(project, "Error preparing driver: ", ex);
    }
  }

  private void showSeedDialog(final Project project, final DriverInfo chosenDriver) {
    final SeedDialog seedDialog = new SeedDialog(chosenDriver);
    seedDialog.show();

    final int exitCode = seedDialog.getExitCode();
    switch (exitCode) {
      case DialogWrapper.OK_EXIT_CODE ->
          runSeedGeneration(project, seedDialog.getConfiguration(), chosenDriver);
      case SeedDialog.BACK_EXIT_CODE -> {
        try {
          DriverLoader.selectAndLoadDriver(project)
              .ifPresent(driverInfo -> showSeedDialog(project, driverInfo));
        } catch (final Exception ex) {
          handleException(project, "Error re-selecting driver: ", ex);
        }
      }
      default -> log.debug("Seed generation dialog canceled.");
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
                try (final Connection conn =
                    DriverManager.getConnection(
                        config.url(),
                        Objects.requireNonNullElse(config.user(), ""),
                        Objects.requireNonNullElse(config.password(), ""))) {
                  tablesRef.set(SchemaIntrospector.introspect(conn, config.schema()));
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
        log.debug("User canceled large data seeding operation. Returning to previous step.");
        showSeedDialog(project, chosenDriver);
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
            new GenerationConfig(
                config.url(),
                config.user(),
                config.password(),
                config.schema(),
                config.rowsPerTable(),
                config.deferred(),
                pkDialog.getSoftDeleteColumns(),
                pkDialog.getSoftDeleteUseSchemaDefault(),
                pkDialog.getSoftDeleteValue(),
                pkDialog.getNumericScale());

        ConnectionConfigPersistence.save(project, finalConfig);

        ProgressManager.getInstance()
            .run(
                new Task.Backgroundable(project, APP_NAME.getValue(), true) {
                  @Override
                  public void run(@NotNull final ProgressIndicator indicator) {
                    try {
                      indicator.setIndeterminate(false);
                      indicator.setText("Generating data...");

                      final List<Table> filteredTables =
                          ordered.stream().filter(t -> !excludedTables.contains(t.name())).toList();

                      final boolean mustForceDeferred =
                          TopologicalSorter.requiresDeferredDueToNonNullableCycles(
                              sort, tableByName);
                      final boolean effectiveDeferred = finalConfig.deferred() || mustForceDeferred;
                      log.debug("Effective deferred: {}", effectiveDeferred);

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

                      if (indicator.isCanceled()) return;

                      indicator.setText("Building SQL...");
                      indicator.setText2("");
                      final String sql =
                          SqlGenerator.generate(
                              gen.rows(), gen.updates(), effectiveDeferred, chosenDriver);
                      log.info("SQL script built successfully.");

                      ApplicationManager.getApplication()
                          .invokeLater(() -> saveAndOpenSqlFile(project, sql));
                    } catch (final Exception ex) {
                      handleException(project, "Error during SQL generation: ", ex);
                    }
                  }
                });
      }
      case PkUuidSelectionDialog.BACK_EXIT_CODE -> showSeedDialog(project, chosenDriver);
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
