/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.action;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.testFramework.LightVirtualFile;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.db.TopologicalSorter;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.ui.ColumnCustomizationDialog;
import com.luisppb16.dbseed.ui.SeedDialog;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;
import static com.luisppb16.dbseed.model.Constant.NOTIFICATION_ID;

@Slf4j
public final class GenerateSeedAction extends AnAction {

  private static final String FILE_NAME = "query_batch.sql";
  private static final String JDBC_DRIVER = "org.postgresql.Driver";

  private static boolean requiresDeferredDueToNonNullableCycles(
      TopologicalSorter.SortResult sort, Map<String, Table> tableMap) {
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
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      log.debug("Action canceled: no active project.");
      return;
    }

    SeedDialog dialog = new SeedDialog();
    if (!dialog.showAndGet()) {
      log.info("User canceled the seed generation operation.");
      return;
    }

    GenerationConfig config = dialog.getConfiguration();

    ProgressManager.getInstance()
        .run(
            new Task.Backgroundable(project, APP_NAME.getValue(), false) {
              @Override
              public void run(@NotNull ProgressIndicator indicator) {
                try {
                  generateSeed(project, config, indicator);
                } catch (ProcessCanceledException ex) {
                  log.warn("Seed generation task was canceled by the user.", ex);
                  throw ex;
                } catch (ClassNotFoundException ex) {
                  // Error is already logged and notified in loadJdbcDriver
                } catch (SQLException ex) {
                  log.error("A database error occurred during seed generation.", ex);
                  notifyError(project, "Database error: " + ex.getMessage());
                } catch (Exception ex) {
                  log.error("An unexpected error occurred during seed generation.", ex);
                  notifyError(project, "An unexpected error occurred: " + ex.getMessage());
                }
              }
            });
  }

  private void generateSeed(
      @NotNull Project project,
      @NotNull GenerationConfig config,
      @NotNull ProgressIndicator indicator)
      throws SQLException, ClassNotFoundException {
    loadJdbcDriver(project);

    try (Connection conn =
        DriverManager.getConnection(config.url(), config.user(), config.password())) {
      indicator.setText("Introspecting schema...");
      List<Table> tables = SchemaIntrospector.introspect(conn, config.schema());
      log.debug("Schema introspected with {} tables.", tables.size());

      CustomizationResult customization = showCustomizationDialog(tables);

      indicator.setText("Sorting tables...");
      TopologicalSorter.SortResult sort = TopologicalSorter.sort(tables);
      Map<String, Table> tableMap =
          tables.stream().collect(Collectors.toMap(Table::name, Function.identity()));

      List<Table> orderedTables =
          sort.ordered().stream().map(tableMap::get).filter(Objects::nonNull).toList();
      log.debug("Sorted {} tables.", orderedTables.size());

      boolean mustForceDeferred = requiresDeferredDueToNonNullableCycles(sort, tableMap);
      boolean effectiveDeferred = config.deferred() || mustForceDeferred;
      log.debug("Effective deferred: {}", effectiveDeferred);

      indicator.setText("Generating data...");
      DataGenerator.GenerationResult gen =
          DataGenerator.generate(
              orderedTables,
              config.rowsPerTable(),
              effectiveDeferred,
              customization.pkUuidOverrides(),
              customization.excludedColumns());

      indicator.setText("Building SQL...");
      String sql =
          SqlGenerator.generate(orderedTables, gen.rows(), gen.updates(), effectiveDeferred);

      log.info("Seed SQL generated successfully.");
      ApplicationManager.getApplication().invokeLater(() -> openEditor(project, sql));
    }
  }

  private CustomizationResult showCustomizationDialog(@NotNull List<Table> tables) {
    Computable<CustomizationResult> computable =
        () -> {
          ColumnCustomizationDialog dialog = new ColumnCustomizationDialog(tables);
          if (dialog.showAndGet()) {
            return new CustomizationResult(
                dialog.getExcludedColumns(), dialog.getSelectionByTable());
          }
          return new CustomizationResult(Collections.emptyMap(), Collections.emptyMap());
        };
    ApplicationManager.getApplication()
        .invokeAndWait(computable::compute, ModalityState.defaultModalityState());
    return computable.compute();
  }

  private void loadJdbcDriver(@NotNull Project project) throws ClassNotFoundException {
    try {
      Class.forName(JDBC_DRIVER);
    } catch (ClassNotFoundException ex) {
      log.error("PostgreSQL JDBC driver not found.", ex);
      notifyError(project, "PostgreSQL JDBC driver not found in the classpath.");
      throw ex;
    }
  }

  private void openEditor(@NotNull Project project, @NotNull String sql) {
    FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(FILE_NAME);
    LightVirtualFile file = new LightVirtualFile(FILE_NAME, fileType, sql);
    FileEditorManager.getInstance(project).openFile(file, true);
    log.info("File {} opened in the editor.", FILE_NAME);
  }

  private void notifyError(@NotNull Project project, @NotNull String message) {
    Notifications.Bus.notify(
        new Notification(NOTIFICATION_ID.getValue(), "Error", message, NotificationType.ERROR),
        project);
  }

  private record CustomizationResult(
      Map<String, Set<String>> excludedColumns, Map<String, Set<String>> pkUuidOverrides) {}
}
