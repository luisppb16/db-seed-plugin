/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;
import static com.luisppb16.dbseed.model.Constant.NOTIFICATION_ID;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public final class GenerateSeedAction extends AnAction {

  private static final String FILE_NAME = "query_batch.sql";
  private static final String PREF_LAST_DRIVER = "dbseed.last.driver";

  private static boolean requiresDeferredDueToNonNullableCycles(
      final TopologicalSorter.SortResult sort, final Map<String, Table> tableMap) {

    return sort.cycles().stream()
        .anyMatch(
            cycle ->
                cycle.stream()
                    .map(tableMap::get)
                    .filter(Objects::nonNull)
                    .anyMatch(
                        table ->
                            table.foreignKeys().stream()
                                .filter(fk -> cycle.contains(fk.pkTable()))
                                .anyMatch(
                                    fk ->
                                        fk.columnMapping().keySet().stream()
                                            .map(table::column)
                                            .filter(Objects::nonNull)
                                            .anyMatch(c -> !c.nullable()))));
  }

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      log.debug("Action canceled: no active project.");
      return;
    }

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
        log.debug("No driver selected.");
        return;
      }
      final DriverInfo chosenDriver = chosenDriverOpt.get();
      props.setValue(PREF_LAST_DRIVER, chosenDriver.name());

      DriverLoader.ensureDriverPresent(chosenDriver);

      final SeedDialog seedDialog = new SeedDialog(chosenDriver);
      if (!seedDialog.showAndGet()) {
        log.info("User canceled the seed generation operation.");
        return;
      }

      final GenerationConfig config = seedDialog.getConfiguration();
      final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();

      // Step 1: Introspect Schema (Background)
      final AtomicReference<List<Table>> tablesRef = new AtomicReference<>();
      
      ProgressManager.getInstance().run(new Task.Modal(project, "Introspecting Schema", true) {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
              indicator.setIndeterminate(true);
              try (Connection conn = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                  List<Table> tables = SchemaIntrospector.introspect(conn, config.schema());
                  tablesRef.set(tables);
              } catch (Exception ex) {
                  ApplicationManager.getApplication().invokeLater(() -> handleException(project, ex));
              }
          }
      });
      
      final List<Table> tables = tablesRef.get();
      if (tables == null || tables.isEmpty()) {
          if (tables != null) notifyError(project, "No tables found in schema " + config.schema());
          return;
      }

      // Step 2: Show PkUuidSelectionDialog (EDT)
      // Pass the config from Step 2 to Step 3 so it can be updated with Soft Delete settings
      final PkUuidSelectionDialog pkDialog = new PkUuidSelectionDialog(tables, config);
      if (!pkDialog.showAndGet()) {
          return;
      }
      
      final Map<String, Set<String>> pkUuidOverrides = pkDialog.getSelectionByTable();
      final Map<String, Set<String>> excludedColumns = pkDialog.getExcludedColumnsByTable();
      final Map<String, List<RepetitionRule>> repetitionRules = pkDialog.getRepetitionRules();
      
      // Update config with Soft Delete settings from Step 3
      final GenerationConfig finalConfig = config.toBuilder()
          .softDeleteColumns(pkDialog.getSoftDeleteColumns())
          .softDeleteUseSchemaDefault(pkDialog.getSoftDeleteUseSchemaDefault())
          .softDeleteValue(pkDialog.getSoftDeleteValue())
          .numericScale(pkDialog.getNumericScale()) // Get scale from Step 3
          .build();

      // Step 3: Generate Data (Background)
      ProgressManager.getInstance()
          .run(
              new Task.Backgroundable(project, APP_NAME.getValue(), false) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                  try {
                    final String sql =
                        generateSeedSql(
                            finalConfig,
                            tables,
                            pkUuidOverrides,
                            excludedColumns,
                            repetitionRules,
                            indicator,
                            settings,
                            chosenDriver);
                    ApplicationManager.getApplication().invokeLater(() -> openEditor(project, sql));
                  } catch (final Exception ex) {
                    handleException(project, ex);
                  }
                }
              });

    } catch (final Exception ex) {
      handleException(project, ex);
    }
  }

  private String generateSeedSql(
      @NotNull final GenerationConfig config,
      @NotNull final List<Table> tables,
      Map<String, Set<String>> pkUuidOverrides,
      Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      @NotNull final ProgressIndicator indicator,
      DbSeedSettingsState settings,
      DriverInfo driverInfo)
      throws Exception {

      Map<String, Map<String, String>> pkUuidOverridesAdapted = pkUuidOverrides.entrySet().stream()
          .collect(Collectors.toMap(
              Map.Entry::getKey,
              e -> e.getValue().stream().collect(Collectors.toMap(c -> c, c -> ""))
          ));
      
      Map<String, List<String>> excludedColumnsList = excludedColumns.entrySet().stream()
          .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));

      indicator.setText("Sorting tables...");
      final TopologicalSorter.SortResult sort = TopologicalSorter.sort(tables);

      final Map<String, Table> tableMap =
          tables.stream().collect(Collectors.toMap(Table::name, t -> t));

      final List<Table> ordered =
          sort.ordered().stream().map(tableMap::get).filter(Objects::nonNull).toList();
      log.debug("Sorted {} tables.", ordered.size());

      final boolean mustForceDeferred = requiresDeferredDueToNonNullableCycles(sort, tableMap);
      final boolean effectiveDeferred = config.deferred() || mustForceDeferred;
      log.debug("Effective deferred: {}", effectiveDeferred);

      indicator.setText("Generating data...");
      final DataGenerator.GenerationResult gen =
          DataGenerator.generate(
              DataGenerator.GenerationParameters.builder()
                  .tables(ordered)
                  .rowsPerTable(config.rowsPerTable())
                  .deferred(effectiveDeferred)
                  .pkUuidOverrides(pkUuidOverridesAdapted)
                  .excludedColumns(excludedColumnsList)
                  .repetitionRules(repetitionRules)
                  .useLatinDictionary(settings.useLatinDictionary)
                  .useEnglishDictionary(settings.useEnglishDictionary)
                  .useSpanishDictionary(settings.useSpanishDictionary)
                  .softDeleteColumns(config.softDeleteColumns())
                  .softDeleteUseSchemaDefault(config.softDeleteUseSchemaDefault())
                  .softDeleteValue(config.softDeleteValue())
                  .numericScale(config.numericScale()) // Pass scale
                  .build());

      indicator.setText("Building SQL...");
      final String sql = SqlGenerator.generate(gen.rows(), gen.updates(), effectiveDeferred, driverInfo);

      log.info("Seed SQL generated successfully.");
      return sql;
  }

  private void openEditor(final Project project, final String sql) {
    final FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(FILE_NAME);
    final LightVirtualFile file = new LightVirtualFile(FILE_NAME, fileType, sql);
    FileEditorManager.getInstance(project).openFile(file, true);
    log.info("File {} opened in the editor.", FILE_NAME);
  }

  private void handleException(final Project project, final Exception ex) {
    final String message;
    if (ex instanceof SQLException) {
      message = "Database Error: " + ex.getMessage();
    } else {
      message = "An unexpected error occurred: " + ex.getMessage();
    }
    log.error("Error during seed SQL generation.", ex);
    
    // Ensure notification is shown on EDT
    ApplicationManager.getApplication().invokeLater(() -> notifyError(project, message));
  }

  private void notifyError(final Project project, final String message) {
    Notifications.Bus.notify(
        new Notification(NOTIFICATION_ID.getValue(), "Error", message, NotificationType.ERROR),
        project);
  }
}
