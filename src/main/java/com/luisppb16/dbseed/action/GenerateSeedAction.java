/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;

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
import com.luisppb16.dbseed.util.NotificationHelper;
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

/**
 * Alternative seed generation workflow action for the DBSeed plugin ecosystem.
 * <p>
 * This IntelliJ action provides an alternative pathway for generating database seed scripts,
 * implementing a streamlined workflow that combines schema introspection, data generation,
 * and SQL output in a cohesive process. Unlike the primary seeding action, this implementation
 * focuses on a more direct approach to seed generation with integrated configuration management
 * and optimized progress tracking. The action manages database connectivity, handles complex
 * schema dependencies, and generates comprehensive SQL scripts with proper foreign key ordering.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Initiating the alternative seed generation workflow with driver selection</li>
 *   <li>Performing schema introspection in background threads for responsive UI</li>
 *   <li>Coordinating user configuration through multiple dialog interfaces</li>
 *   <li>Generating data with respect to user-defined constraints and preferences</li>
 *   <li>Producing properly ordered SQL scripts accounting for foreign key dependencies</li>
 *   <li>Managing configuration persistence and user preferences</li>
 * </ul>
 * </p>
 * <p>
 * The implementation follows IntelliJ's threading model by performing long-running operations
 * on background threads while updating UI elements on the Event Dispatch Thread. It implements
 * sophisticated error handling with appropriate user notifications and ensures proper resource
 * cleanup during database operations. The action also handles complex schema scenarios including
 * circular foreign key dependencies and deferred constraint processing.
 * </p>
 */
@Slf4j
public final class GenerateSeedAction extends AnAction {

  private static final String FILE_NAME = "query_batch.sql";

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      log.debug("Action canceled: no active project.");
      return;
    }

    try {
      final Optional<DriverInfo> chosenDriverOpt = DriverLoader.selectAndLoadDriver(project);
      if (chosenDriverOpt.isEmpty()) {
        return;
      }
      final DriverInfo chosenDriver = chosenDriverOpt.get();

      final SeedDialog seedDialog = new SeedDialog(chosenDriver);
      if (!seedDialog.showAndGet()) {
        log.info("User canceled the seed generation operation.");
        return;
      }

      final GenerationConfig config = seedDialog.getConfiguration();
      final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();

      // Step 1: Introspect Schema (Background)
      final AtomicReference<List<Table>> tablesRef = new AtomicReference<>();

      ProgressManager.getInstance()
          .run(
              new Task.Modal(project, "Introspecting Schema", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                  indicator.setIndeterminate(true);
                  try (Connection conn =
                      DriverManager.getConnection(
                          config.url(),
                          Objects.requireNonNullElse(config.user(), ""),
                          Objects.requireNonNullElse(config.password(), ""))) {
                    List<Table> tables = SchemaIntrospector.introspect(conn, config.schema());
                    tablesRef.set(tables);
                  } catch (Exception ex) {
                    ApplicationManager.getApplication()
                        .invokeLater(() -> handleException(project, ex));
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

      final DialogSelections selections =
          new DialogSelections(
              pkDialog.getSelectionByTable(),
              pkDialog.getExcludedColumnsByTable(),
              pkDialog.getRepetitionRules(),
              pkDialog.getExcludedTables());

      // Update config with Soft Delete settings from Step 3
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

      // Persist the updated configuration including Soft Delete settings
      ConnectionConfigPersistence.save(project, finalConfig);

      // Step 3: Generate Data (Background)
      ProgressManager.getInstance()
          .run(
              new Task.Backgroundable(project, APP_NAME.getValue(), false) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                  try {
                    final String sql =
                        generateSeedSql(
                            finalConfig, tables, selections, indicator, settings, chosenDriver);
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
      @NotNull final DialogSelections selections,
      @NotNull final ProgressIndicator indicator,
      DbSeedSettingsState settings,
      DriverInfo driverInfo) {

    Map<String, Map<String, String>> pkUuidOverridesAdapted =
        selections.pkUuidOverrides().entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().stream().collect(Collectors.toMap(c -> c, c -> ""))));

    Map<String, List<String>> excludedColumnsList =
        selections.excludedColumns().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));

    indicator.setText("Sorting tables...");
    final List<Table> filteredTables =
        tables.stream().filter(t -> !selections.excludedTables().contains(t.name())).toList();

    final TopologicalSorter.SortResult sort = TopologicalSorter.sort(filteredTables);

    final Map<String, Table> tableMap =
        filteredTables.stream().collect(Collectors.toMap(Table::name, t -> t));

    final List<Table> ordered =
        sort.ordered().stream().map(tableMap::get).filter(Objects::nonNull).toList();
    log.debug("Sorted {} tables.", ordered.size());

    final boolean mustForceDeferred =
        TopologicalSorter.requiresDeferredDueToNonNullableCycles(sort, tableMap);
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
                .repetitionRules(selections.repetitionRules())
                .useLatinDictionary(settings.isUseLatinDictionary())
                .useEnglishDictionary(settings.isUseEnglishDictionary())
                .useSpanishDictionary(settings.isUseSpanishDictionary())
                .softDeleteColumns(config.softDeleteColumns())
                .softDeleteUseSchemaDefault(config.softDeleteUseSchemaDefault())
                .softDeleteValue(config.softDeleteValue())
                .numericScale(config.numericScale())
                .build());

    indicator.setText("Building SQL...");
    final String sql =
        SqlGenerator.generate(gen.rows(), gen.updates(), effectiveDeferred, driverInfo);

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
    NotificationHelper.notifyError(project, message);
  }

  private record DialogSelections(
      Map<String, Set<String>> pkUuidOverrides,
      Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      Set<String> excludedTables) {}
}
