/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
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
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.registry.DriverRegistry;
import com.luisppb16.dbseed.ui.DriverSelectionDialog;
import com.luisppb16.dbseed.ui.SeedDialog;
import com.luisppb16.dbseed.util.DriverLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

      final SeedDialog dialog = new SeedDialog(chosenDriver.urlTemplate());
      if (!dialog.showAndGet()) {
        log.info("User canceled the seed generation operation.");
        return;
      }

      final GenerationConfig config = dialog.getConfiguration();
      final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();

      ProgressManager.getInstance()
          .run(
              new Task.Backgroundable(project, APP_NAME.getValue(), false) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                  try {
                    final String sql =
                        generateSeedSql(
                            config,
                            dialog,
                            indicator,
                            settings.useLatinDictionary,
                            settings.useEnglishDictionary,
                            settings.useSpanishDictionary);
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
      @NotNull final SeedDialog dialog,
      @NotNull final ProgressIndicator indicator,
      final boolean useLatinDictionary,
      final boolean useEnglishDictionary,
      final boolean useSpanishDictionary)
      throws Exception {

    try (final Connection conn =
        DriverManager.getConnection(config.url(), config.user(), config.password())) {

      indicator.setText("Introspecting schema...");
      final List<Table> tables = SchemaIntrospector.introspect(conn, config.schema());
      log.debug("Schema introspected with {} tables.", tables.size());

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
                  .pkUuidOverrides(dialog.getSelectionByTable())
                  .excludedColumns(dialog.getExcludedColumnsByTable())
                  .useLatinDictionary(useLatinDictionary)
                  .useEnglishDictionary(useEnglishDictionary)
                  .useSpanishDictionary(useSpanishDictionary)
                  .build());

      indicator.setText("Building SQL...");
      final String sql = SqlGenerator.generate(gen.rows(), gen.updates(), effectiveDeferred);

      log.info("Seed SQL generated successfully.");
      return sql;
    }
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
    notifyError(project, message);
  }

  private void notifyError(final Project project, final String message) {
    Notifications.Bus.notify(
        new Notification(NOTIFICATION_ID.getValue(), "Error", message, NotificationType.ERROR),
        project);
  }
}
