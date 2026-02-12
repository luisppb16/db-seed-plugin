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
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
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
    if (project == null) {
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

      final SeedDialog seedDialog = new SeedDialog(chosenDriver);
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
                    List<Table> tables = SchemaIntrospector.introspect(conn, config.schema());
                    tablesRef.set(tables);
                  } catch (Exception ex) {
                    errorRef.set(ex);
                  }
                }
              });

      if (errorRef.get() != null) {
        handleException(project, errorRef.get());
        return;
      }

      final List<Table> tables = tablesRef.get();
      if (tables == null || tables.isEmpty()) {
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
              pkDialog.getAiColumnsByTable());

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

      if (settings.isUseAiGeneration()
          && (settings.getAiApplicationContext() == null
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
                .aiColumns(selections.aiColumns())
                .applicationContext(
                    settings.isUseAiGeneration() ? settings.getAiApplicationContext() : null)
                .indicator(indicator)
                .build());

    indicator.setText("Building SQL...");
    indicator.setText2("");
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
      Map<String, Set<String>> aiColumns) {}
}
