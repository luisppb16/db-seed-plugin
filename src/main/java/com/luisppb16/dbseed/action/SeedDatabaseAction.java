/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.NOTIFICATION_ID;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
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
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.registry.DriverRegistry;
import com.luisppb16.dbseed.ui.DriverSelectionDialog;
import com.luisppb16.dbseed.ui.PkUuidSelectionDialog;
import com.luisppb16.dbseed.ui.SeedDialog;
import com.luisppb16.dbseed.util.DriverLoader;
import java.io.IOException;
import java.net.URISyntaxException;
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
  private static final String NOTIFICATION_TITLE = "DB Seed Generator";
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

    try {
      DriverLoader.ensureDriverPresent(chosenDriver);
      final SeedDialog seedDialog = new SeedDialog();
      if (seedDialog.showAndGet()) {
        runSeedGeneration(project, seedDialog.getConfiguration());
      } else {
        log.debug("Seed generation dialog canceled.");
      }
    } catch (final IOException
        | ReflectiveOperationException
        | URISyntaxException
        | SQLException ex) {
      log.error("Error preparing driver: {}", chosenDriver.name(), ex);
      notifyError(project, "Error preparing driver: " + ex.getMessage());
    }
  }

  private void runSeedGeneration(final Project project, final GenerationConfig config) {
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
                  log.warn("Error introspecting schema for URL: {}", config.url(), ex);
                  notifyError(project, "Error introspecting schema: " + ex.getMessage());
                }
              }

              @Override
              public void onSuccess() {
                Optional.ofNullable(tablesRef.get())
                    .ifPresent(
                        tables ->
                            ApplicationManager.getApplication()
                                .invokeLater(() -> continueGeneration(project, config, tables)));
              }
            });
  }

  private void continueGeneration(
      final Project project, final GenerationConfig config, final List<Table> tables) {
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
        final SeedDialog seedDialog = new SeedDialog();
        if (seedDialog.showAndGet()) {
          runSeedGeneration(project, seedDialog.getConfiguration());
        } else {
          log.debug("Seed generation dialog canceled after warning.");
        }
        return;
      }
    }

    final TopologicalSorter.SortResult sort = TopologicalSorter.sort(tables);
    final Map<String, Table> tableByName =
        tables.stream().collect(Collectors.toMap(Table::name, Function.identity()));
    final List<Table> ordered =
        sort.ordered().stream().map(tableByName::get).filter(Objects::nonNull).toList();

    final PkUuidSelectionDialog pkDialog = new PkUuidSelectionDialog(ordered);
    if (!pkDialog.showAndGet()) {
      log.debug("PK UUID selection canceled.");
      return;
    }
    final Map<String, Set<String>> selectedPkUuidColumns = pkDialog.getSelectionByTable();
    final Map<String, Set<String>> excludedColumnsSet = pkDialog.getExcludedColumnsByTable();

    // Convert Map<String, Set<String>> to Map<String, Map<String, String>> for pkUuidOverrides
    final Map<String, Map<String, String>> pkUuidOverrides =
        selectedPkUuidColumns.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry ->
                        entry.getValue().stream()
                            .collect(Collectors.toMap(Function.identity(), col -> ""))));

    // Convert Map<String, Set<String>> to Map<String, List<String>> for excludedColumns
    final Map<String, List<String>> excludedColumns =
        excludedColumnsSet.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));

    ProgressManager.getInstance()
        .run(
            new Task.Backgroundable(project, "Generating SQL", false) {
              @Override
              public void run(@NotNull final ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("Generating data...");
                indicator.setFraction(0.3);

                final boolean mustForceDeferred =
                    requiresDeferredDueToNonNullableCycles(sort, tableByName);
                final boolean effectiveDeferred = config.deferred() || mustForceDeferred;
                log.debug("Effective deferred: {}", effectiveDeferred);

                final DataGenerator.GenerationResult gen =
                    DataGenerator.generate(
                        ordered,
                        config.rowsPerTable(),
                        effectiveDeferred,
                        pkUuidOverrides,
                        excludedColumns);
                log.info("Data generation completed for {} rows per table.", config.rowsPerTable());

                indicator.setText("Building SQL...");
                indicator.setFraction(0.8);
                final String sql =
                    SqlGenerator.generate(gen.rows(), gen.updates(), effectiveDeferred);
                log.info("SQL script built successfully.");

                indicator.setText("Opening editor...");
                indicator.setFraction(1.0);
                ApplicationManager.getApplication()
                    .invokeLater(() -> saveAndOpenSqlFile(project, sql));
              }

              @Override
              public void onThrowable(@NotNull final Throwable error) {
                log.error("Error during SQL generation.", error);
                notifyError(project, "Error during SQL generation: " + error.getMessage());
              }
            });
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
      log.error("Error saving SQL file: {}", path, e);
      notifyError(project, "Error saving SQL file: " + e.getMessage());
    }
  }

  private void notifyError(final Project project, final String message) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_ID.getValue())
        .createNotification(NOTIFICATION_TITLE, message, NotificationType.ERROR)
        .notify(project);
  }
}
