/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;
import static com.luisppb16.dbseed.model.Constant.NOTIFICATION_ID;

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
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.db.TopologicalSorter;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.schema.SchemaDsl;
import com.luisppb16.dbseed.ui.SeedDialog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public final class GenerateSeedAction extends AnAction {

  private static final String FILE_NAME = "query_batch.sql";

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
                  Class.forName("org.postgresql.Driver");
                } catch (ClassNotFoundException ex) {
                  log.error("PostgreSQL JDBC driver not found.", ex);
                  notifyError(project, "PostgreSQL JDBC driver not found in the classpath.");
                  return;
                }

                try (Connection conn =
                    DriverManager.getConnection(config.url(), config.user(), config.password())) {

                  indicator.setText("Introspecting schema...");
                  SchemaDsl.Schema schema = SchemaIntrospector.introspect(conn, config.schema());
                  List<Table> tables = SchemaDsl.toTableList(schema);
                  log.debug("Schema introspected with {} tables.", tables.size());

                  indicator.setText("Sorting tables...");
                  TopologicalSorter.SortResult sort = TopologicalSorter.sort(tables);

                  Map<String, Table> tableMap =
                      tables.stream().collect(Collectors.toMap(Table::name, t -> t));

                  List<Table> ordered =
                      sort.ordered().stream().map(tableMap::get).filter(Objects::nonNull).toList();
                  log.debug("Sorted {} tables.", ordered.size());

                  boolean mustForceDeferred =
                      requiresDeferredDueToNonNullableCycles(sort, tableMap);
                  boolean effectiveDeferred = config.deferred() || mustForceDeferred;
                  log.debug("Effective deferred: {}", effectiveDeferred);

                  indicator.setText("Generating data...");
                  DataGenerator.GenerationResult gen =
                      DataGenerator.generate(
                          ordered,
                          config.rowsPerTable(),
                          effectiveDeferred,
                          Collections.emptyMap());

                  indicator.setText("Building SQL...");
                  String sql =
                      SqlGenerator.generate(ordered, gen.rows(), gen.updates(), effectiveDeferred);

                  log.info("Seed SQL generated successfully.");
                  ApplicationManager.getApplication().invokeLater(() -> openEditor(project, sql));

                } catch (Exception ex) {
                  log.error("Error during seed SQL generation.", ex);
                  Notifications.Bus.notify(
                      new Notification(
                          NOTIFICATION_ID.getValue(),
                          "Error",
                          ex.getMessage(),
                          NotificationType.ERROR),
                      project);
                }
              }
            });
  }

  private void openEditor(Project project, String sql) {
    FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(FILE_NAME);
    LightVirtualFile file = new LightVirtualFile(FILE_NAME, fileType, sql);
    FileEditorManager.getInstance(project).openFile(file, true);
    log.info("File {} opened in the editor.", FILE_NAME);
  }

  private void notifyError(Project project, String message) {
    Notifications.Bus.notify(
        new Notification(NOTIFICATION_ID.getValue(), "Error", message, NotificationType.ERROR),
        project);
  }
}
