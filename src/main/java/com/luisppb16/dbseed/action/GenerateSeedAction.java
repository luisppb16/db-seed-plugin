/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.action;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.testFramework.LightVirtualFile;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.db.TopologicalSorter;
import com.luisppb16.dbseed.db.dialect.DialectFactory;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
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

/** Alternative seed generation workflow action for the DBSeed plugin ecosystem. */
@Slf4j
public final class GenerateSeedAction extends AnAction {

  private static final String FILE_NAME = "query_batch.sql";

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    final Project project = e.getProject();
    if (Objects.isNull(project)) {
      log.debug("Action canceled: no active project.");
      return;
    }

    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();

    try {
      final Optional<DriverInfo> chosenDriverOpt = DriverLoader.selectAndLoadDriver(project);
      if (chosenDriverOpt.isEmpty()) {
        return;
      }
      final DriverInfo chosenDriver = chosenDriverOpt.get();

      final SeedDialog seedDialog = new SeedDialog(project, chosenDriver);
      if (!seedDialog.showAndGet()) {
        log.info("User canceled the seed generation operation.");
        return;
      }

      final GenerationConfig config = seedDialog.getConfiguration();

      final AtomicReference<List<Table>> tablesRef = new AtomicReference<>();
      final AtomicReference<Exception> errorRef = new AtomicReference<>();

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
                    final List<Table> tables =
                        SchemaIntrospector.introspect(
                            conn, config.schema(), DialectFactory.resolve(chosenDriver));
                    tablesRef.set(tables);
                  } catch (Exception ex) {
                    errorRef.set(ex);
                  }
                }
              });

      if (Objects.nonNull(errorRef.get())) {
        handleException(project, errorRef.get());
        return;
      }

      final List<Table> tables = tablesRef.get();
      if (Objects.isNull(tables) || tables.isEmpty()) {
        Messages.showErrorDialog(
            project, "No tables found in schema: " + config.schema(), "DBSeed Error");
        return;
      }

      final PkUuidSelectionDialog pkDialog = new PkUuidSelectionDialog(tables, config);
      if (!pkDialog.showAndGet()) {
        return;
      }

      final DialogSelections selections =
          new DialogSelections(
              pkDialog.getSelectionByTable(),
              pkDialog.getExcludedColumnsByTable(),
              pkDialog.getRepetitionRules(),
              pkDialog.getExcludedTables(),
              pkDialog.getAiColumnsByTable(),
              pkDialog.getCircularReferences(),
              pkDialog.getCircularReferenceTerminationModes());

      final GenerationConfig finalConfig =
          config.withSoftDeleteSettings(
              pkDialog.getSoftDeleteColumns(),
              pkDialog.getSoftDeleteUseSchemaDefault(),
              pkDialog.getSoftDeleteValue(),
              pkDialog.getNumericScale());

      ProgressManager.getInstance()
          .run(
              new Task.Backgroundable(project, APP_NAME.getValue(), true) {
                @Override
                public void run(@NotNull final ProgressIndicator indicator) {
                  try {
                    indicator.setFraction(0.0);
                    indicator.setIndeterminate(false);
                    indicator.setText("Preparing generation...");
                    indicator.setText2(
                        tables.size()
                            + " tables, "
                            + finalConfig.rowsPerTable()
                            + " rows per table");
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
      final DbSeedSettingsState settings,
      final DriverInfo driverInfo) {

    final Map<String, Map<String, String>> pkUuidOverridesAdapted =
        selections.pkUuidOverrides().entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().stream().collect(Collectors.toMap(c -> c, c -> ""))));

    final Map<String, List<String>> excludedColumnsList =
        selections.excludedColumns().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));

    indicator.setText("Sorting tables...");
    indicator.setText2("Filtering " + tables.size() + " tables");
    final List<Table> filteredTables =
        tables.stream().filter(t -> !selections.excludedTables().contains(t.name())).toList();

    indicator.setText2("Resolving dependency order for " + filteredTables.size() + " tables");
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

    // DataGenerator drives the indicator fraction via ProgressTracker
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
                .aiColumns(selections.aiColumns())
                .circularReferences(selections.circularReferences())
                .circularReferenceTerminationModes(selections.circularReferenceTerminationModes())
                .applicationContext(
                    settings.isUseAiGeneration() ? settings.getAiApplicationContext() : null)
                .indicator(indicator)
                .build());

    indicator.setText("Building SQL...");
    indicator.setText2("Generating INSERT statements for " + gen.rows().size() + " tables");
    final String sql =
        SqlGenerator.generate(gen.rows(), gen.updates(), effectiveDeferred, driverInfo);

    log.info("Seed SQL generated successfully.");
    indicator.setFraction(1.0);
    indicator.setText("Done!");
    indicator.setText2("");
    return sql;
  }

  private void openEditor(final Project project, final String sql) {
    final FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(FILE_NAME);
    final LightVirtualFile file = new LightVirtualFile(FILE_NAME, fileType, sql);
    FileEditorManager.getInstance(project).openFile(file, true);
    log.info("File {} opened in the editor.", FILE_NAME);
  }

  private void handleException(final Project project, final Exception ex) {
    final String message =
        switch (ex) {
          case SQLException sqlEx -> "Database Error: " + sqlEx.getMessage();
          default -> "An unexpected error occurred: " + ex.getMessage();
        };
    log.error("Error during seed SQL generation.", ex);

    ApplicationManager.getApplication()
        .invokeLater(
            () -> Messages.showErrorDialog(project, message, "DBSeed Error"),
            ModalityState.defaultModalityState());
  }

  private record DialogSelections(
      Map<String, Set<String>> pkUuidOverrides,
      Map<String, Set<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      Set<String> excludedTables,
      Map<String, Set<String>> aiColumns,
      Map<String, Map<String, Integer>> circularReferences,
      Map<String, Map<String, String>> circularReferenceTerminationModes) {}
}
