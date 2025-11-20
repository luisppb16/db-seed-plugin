/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.NOTIFICATION_ID;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class SeedDatabaseAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Optional.ofNullable(e.getProject())
        .ifPresentOrElse(
            project -> {
              List<DriverInfo> drivers = DriverRegistry.getDrivers();
              if (drivers.isEmpty()) {
                notifyError(project, "No drivers found in drivers.json");
                return;
              }

              DriverSelectionDialog driverDialog = new DriverSelectionDialog(project, drivers);
              if (!driverDialog.showAndGet()) {
                return;
              }

              Optional<DriverInfo> chosenOpt = driverDialog.getSelectedDriver();
              if (chosenOpt.isEmpty()) {
                return;
              }
              DriverInfo chosen = chosenOpt.get();
              try {
                ensureDriverPresent(chosen);
                SeedDialog dialog = new SeedDialog();
                if (dialog.showAndGet()) {
                  runSeedGeneration(project, dialog.getConfiguration());
                }

              } catch (Exception ex) {
                notifyError(project, "Error preparing driver: " + ex.getMessage());
              }
            },
            () -> log.debug("Action canceled: no active project."));
  }

  private void runSeedGeneration(Project project, GenerationConfig config) {
    final AtomicReference<List<Table>> tablesRef = new AtomicReference<>();

    ProgressManager.getInstance()
        .run(
            new Task.Backgroundable(project, "Introspecting schema", false) {
              @Override
              public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try (Connection conn =
                    DriverManager.getConnection(
                        config.url(),
                        Objects.requireNonNullElse(config.user(), ""),
                        Objects.requireNonNullElse(config.password(), ""))) {
                  tablesRef.set(SchemaIntrospector.introspect(conn, config.schema()));
                } catch (SQLException ex) {
                  log.warn("Error introspecting schema.", ex);
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
      Project project, GenerationConfig config, List<Table> tables) {
    TopologicalSorter.SortResult sort = TopologicalSorter.sort(tables);
    Map<String, Table> tableByName =
        tables.stream().collect(Collectors.toMap(Table::name, Function.identity()));
    List<Table> ordered =
        sort.ordered().stream().map(tableByName::get).filter(Objects::nonNull).toList();

    final PkUuidSelectionDialog pkDialog = new PkUuidSelectionDialog(ordered);
    if (!pkDialog.showAndGet()) {
      return;
    }
    Map<String, Set<String>> overrides = pkDialog.getSelectionByTable();
    Map<String, Set<String>> excludedColumns = pkDialog.getExcludedColumnsByTable();

    ProgressManager.getInstance()
        .run(
            new Task.Backgroundable(project, "Generating SQL", false) {
              @Override
              public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("Generating data...");
                indicator.setFraction(0.3);
                DataGenerator.GenerationResult gen =
                    DataGenerator.generate(
                        ordered,
                        config.rowsPerTable(),
                        config.deferred(),
                        overrides,
                        excludedColumns);

                indicator.setText("Building SQL...");
                indicator.setFraction(0.8);
                String sql =
                    SqlGenerator.generate(ordered, gen.rows(), gen.updates(), config.deferred());

                indicator.setText("Opening editor...");
                indicator.setFraction(1.0);
                ApplicationManager.getApplication().invokeLater(() -> openEditor(project, sql));
              }
            });
  }

  private void ensureDriverPresent(DriverInfo info) throws Exception {
    Path libDir = Path.of(System.getProperty("user.home"), ".dbseed-drivers");
    Files.createDirectories(libDir);

    String jarName = info.mavenArtifactId() + "-" + info.version() + ".jar";
    Path jarPath = libDir.resolve(jarName);

    if (!Files.exists(jarPath)) {
      String url =
          String.format(
              "https://repo1.maven.org/maven2/%s/%s/%s/%s",
              info.mavenGroupId().replace('.', '/'),
              info.mavenArtifactId(),
              info.version(),
              jarName);
      log.info("Downloading driver {} from {}", info.name(), url);
      try (var in = new URI(url).toURL().openStream()) {
        Files.copy(in, jarPath);
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }

    URLClassLoader loader =
        new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, this.getClass().getClassLoader());
    Driver driver =
        (Driver)
            Class.forName(info.driverClass(), true, loader).getDeclaredConstructor().newInstance();
    DriverManager.registerDriver(new DriverShim(driver));
  }

  private void openEditor(Project project, String sql) {
    FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName("seed.sql");
    LightVirtualFile file = new LightVirtualFile("seed.sql", fileType, sql);
    FileEditorManager.getInstance(project).openFile(file, true);
  }

  private void notifyError(Project project, String message) {
    Optional.ofNullable(
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_ID.getValue()))
        .ifPresentOrElse(
            group ->
                group
                    .createNotification(NOTIFICATION_ID.getValue(), message, NotificationType.ERROR)
                    .notify(project),
            () -> {
              Notification notification =
                  new Notification(
                      NOTIFICATION_ID.getValue(),
                      NOTIFICATION_ID.getValue(),
                      message,
                      NotificationType.ERROR);
              Notifications.Bus.notify(notification, project);
            });
  }

  private record DriverShim(Driver driver) implements Driver {

    @Override
    public boolean acceptsURL(String u) throws SQLException {
      return driver.acceptsURL(u);
    }

    @Override
    public Connection connect(String u, Properties p) throws SQLException {
      return driver.connect(u, p);
    }

    @Override
    public int getMajorVersion() {
      return driver.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
      return driver.getMinorVersion();
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException {
      return driver.getPropertyInfo(u, p);
    }

    @Override
    public boolean jdbcCompliant() {
      return driver.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() {
      try {
        return driver.getParentLogger();
      } catch (Exception e) {
        return Logger.getGlobal();
      }
    }
  }
}
