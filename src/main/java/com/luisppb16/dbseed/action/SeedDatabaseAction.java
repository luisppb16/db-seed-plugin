/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.NOTIFICATION_ID;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.db.TopologicalSorter;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.registry.DriverRegistry;
import com.luisppb16.dbseed.ui.DriverSelectionDialog;
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
import java.sql.SQLException;
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

@Slf4j
public class SeedDatabaseAction extends AnAction {

  private static final String PREF_LAST_DRIVER = "dbseed.last.driver";
  private static final long INSERT_THRESHOLD = 10000L;

  private static boolean hasNonNullableForeignKeyInCycle(
      final Table table, final Set<String> cycle) {
    return table.foreignKeys().stream()
        .filter(
            fk -> cycle.contains(fk.pkTable())) // Foreign key points to a table within the cycle
        .anyMatch(
            fk ->
                fk.columnMapping().keySet().stream()
                    .map(table::column)
                    .filter(Objects::nonNull)
                    .anyMatch(c -> !c.nullable())); // At least one column in the FK is non-nullable
  }

  private static boolean requiresDeferredDueToNonNullableCycles(
      final TopologicalSorter.SortResult sort, final Map<String, Table> tableMap) {

    return sort.cycles().stream()
        .anyMatch(
            cycle ->
                cycle.stream()
                    .map(tableMap::get)
                    .filter(Objects::nonNull)
                    .anyMatch(table -> hasNonNullableForeignKeyInCycle(table, cycle)));
  }

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      log.debug("Action canceled: no active project.");
      return;
    }

    showDriverSelection(project);
  }

  private void showDriverSelection(final Project project) {
    try {
      final List<DriverInfo> drivers = DriverRegistry.getDrivers();
      if (drivers.isEmpty()) {
        notifyError(project, "No drivers found in drivers.json");
        return;
      }

      final PropertiesComponent props = PropertiesComponent.getInstance(project);
      final String lastDriverName = props.getValue(PREF_LAST_DRIVER);

      final DriverSelectionDialog driverDialog =
          new DriverSelectionDialog(project, drivers, lastDriverName);
      if (!driverDialog.showAndGet()) {
        log.debug("Driver selection canceled.");
        return;
      }

      final Optional<DriverInfo> chosenDriverOpt = driverDialog.getSelectedDriver();
      if (chosenDriverOpt.isEmpty()) {
        log.debug("No driver selected or BigQuery ProjectId missing.");
        return;
      }
      final DriverInfo chosenDriver = chosenDriverOpt.get();
      props.setValue(PREF_LAST_DRIVER, chosenDriver.name());

      DriverLoader.ensureDriverPresent(chosenDriver);
      showSeedDialog(project, chosenDriver);
    } catch (final Exception ex) {
      handleException(project, "Error preparing driver: ", ex);
    }
  }

  private void showSeedDialog(final Project project, final DriverInfo chosenDriver) {
    final SeedDialog seedDialog = new SeedDialog(chosenDriver.urlTemplate());
    seedDialog.show();

    final int exitCode = seedDialog.getExitCode();
    if (exitCode == DialogWrapper.OK_EXIT_CODE) {
      runSeedGeneration(project, seedDialog.getConfiguration(), chosenDriver);
    } else if (exitCode == SeedDialog.BACK_EXIT_CODE) {
      showDriverSelection(project);
    } else {
      log.debug("Seed generation dialog canceled.");
    }
  }

  private void runSeedGeneration(
      final Project project, final GenerationConfig config, final DriverInfo chosenDriver) {
    final AtomicReference<List<Table>> tablesRef = new AtomicReference<>();

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
                } catch (final SQLException ex) {
                  handleException(project, "Error introspecting schema: ", ex);
                }
              }

              @Override
              public void onSuccess() {
                Optional.ofNullable(tablesRef.get())
                    .ifPresent(
                        tables ->
                            ApplicationManager.getApplication()
                                .invokeLater(
                                    () ->
                                        continueGeneration(project, config, tables, chosenDriver)));
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
                  + "Do you still want to continue?",
              totalRows);

      final int result =
          Messages.showOkCancelDialog(
              project,
              message,
              "Large Data Seeding Operation",
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
    if (exitCode == DialogWrapper.OK_EXIT_CODE) {
      final Map<String, Set<String>> selectedPkUuidColumns = pkDialog.getSelectionByTable();
      final Map<String, Set<String>> excludedColumnsSet = pkDialog.getExcludedColumnsByTable();
      final Map<String, List<RepetitionRule>> repetitionRules = pkDialog.getRepetitionRules();

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
              .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));

      final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
      
      // Update config with Soft Delete settings from Step 3
      final GenerationConfig finalConfig = config.toBuilder()
          .softDeleteColumns(pkDialog.getSoftDeleteColumns())
          .softDeleteUseSchemaDefault(pkDialog.getSoftDeleteUseSchemaDefault())
          .softDeleteValue(pkDialog.getSoftDeleteValue())
          .build();

      ProgressManager.getInstance()
          .run(
              new Task.Backgroundable(project, "Generating SQL", false) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                  try {
                    indicator.setIndeterminate(false);
                    indicator.setText("Generating data...");
                    indicator.setFraction(0.3);

                    final boolean mustForceDeferred =
                        requiresDeferredDueToNonNullableCycles(sort, tableByName);
                    final boolean effectiveDeferred = finalConfig.deferred() || mustForceDeferred;
                    log.debug("Effective deferred: {}", effectiveDeferred);

                    final DataGenerator.GenerationResult gen =
                        DataGenerator.generate(
                            DataGenerator.GenerationParameters.builder()
                                .tables(ordered)
                                .rowsPerTable(finalConfig.rowsPerTable())
                                .deferred(effectiveDeferred)
                                .pkUuidOverrides(pkUuidOverrides)
                                .excludedColumns(excludedColumns)
                                .repetitionRules(repetitionRules)
                                .useLatinDictionary(settings.useLatinDictionary)
                                .useEnglishDictionary(settings.useEnglishDictionary)
                                .useSpanishDictionary(settings.useSpanishDictionary)
                                .softDeleteColumns(finalConfig.softDeleteColumns())
                                .softDeleteUseSchemaDefault(finalConfig.softDeleteUseSchemaDefault())
                                .softDeleteValue(finalConfig.softDeleteValue())
                                .build());
                    log.info(
                        "Data generation completed for {} rows per table.", finalConfig.rowsPerTable());

                    indicator.setText("Building SQL...");
                    indicator.setFraction(0.8);
                    final String sql =
                        SqlGenerator.generate(gen.rows(), gen.updates(), effectiveDeferred, chosenDriver);
                    log.info("SQL script built successfully.");

                    indicator.setText("Opening editor...");
                    indicator.setFraction(1.0);
                    ApplicationManager.getApplication()
                        .invokeLater(() -> saveAndOpenSqlFile(project, sql));
                  } catch (final Exception ex) {
                    handleException(project, "Error during SQL generation: ", ex);
                  }
                }
              });
    } else if (exitCode == PkUuidSelectionDialog.BACK_EXIT_CODE) {
      showSeedDialog(project, chosenDriver);
    } else {
      log.debug("PK UUID selection canceled.");
    }
  }

  private void saveAndOpenSqlFile(final Project project, final String sql) {
    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    final String outputDir = settings.defaultOutputDirectory;
    final String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    final String fileName = String.format("V%s__seed.sql", timestamp);

    final Path path = Paths.get(Objects.requireNonNull(project.getBasePath()), outputDir, fileName);

    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, sql, StandardCharsets.UTF_8);

      final VirtualFile virtualFile =
          LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
      if (virtualFile != null) {
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
        log.info("File {} saved and opened in the editor.", fileName);
      } else {
        log.warn("Could not find VirtualFile for path: {}", path);
        notifyError(project, "Could not open generated SQL file.");
      }
    } catch (final IOException e) {
      handleException(project, "Error saving SQL file: ", e);
    }
  }

  private void handleException(final Project project, final String message, final Exception ex) {
    log.error(message, ex);
    notifyError(project, message + ex.getMessage());
  }

  private void notifyError(final Project project, final String message) {
    Notifications.Bus.notify(
        new Notification(NOTIFICATION_ID.getValue(), "Error", message, NotificationType.ERROR),
        project);
  }
}
