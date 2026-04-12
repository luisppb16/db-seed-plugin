/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.luisppb16.dbseed.model.RepetitionRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent state component for managing global configuration settings of the DBSeed plugin.
 *
 * <p>This class serves as the central configuration repository for the DBSeed plugin, implementing
 * IntelliJ's PersistentStateComponent interface to automatically save and restore user preferences
 * across IDE sessions. It manages a comprehensive set of configuration options including dictionary
 * preferences, AI generation settings, soft-delete configurations, and various behavioral flags
 * that control the plugin's data generation capabilities.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Managing persistent storage of user configuration settings
 *   <li>Providing default values for all configuration options
 *   <li>Handling migration and validation of configuration data
 *   <li>Supporting dynamic reconfiguration during runtime
 *   <li>Integrating with IntelliJ's settings infrastructure
 *   <li>Maintaining backward compatibility with older configuration formats
 * </ul>
 *
 * <p>The implementation uses IntelliJ's XmlSerializerUtil for automatic serialization and
 * deserialization of configuration data. It includes comprehensive validation and fallback
 * mechanisms to ensure robust operation even with corrupted or missing configuration files. The
 * class follows the singleton pattern through ApplicationManager service registration.
 *
 * <p>Configuration options include dictionary language preferences, AI model settings, output
 * directory specifications, soft-delete column handling, and various behavioral flags. All settings
 * are designed to be user-configurable through the plugin's settings UI and are automatically
 * persisted to maintain user preferences across IDE restarts.
 */
@Getter
@Setter
@State(
    name = "com.luisppb16.dbseed.config.DbSeedSettingsState",
    storages = @Storage("DbSeedPlugin.xml"))
public class DbSeedSettingsState implements PersistentStateComponent<DbSeedSettingsState> {

  private static final String DEFAULT_OUTPUT_DIRECTORY = "src/main/resources/db/seeder";
  private static final int DEFAULT_COLUMN_SPINNER_STEP = 3;
  private static final String DEFAULT_SOFT_DELETE_VALUE = "NULL";
  private static final boolean DEFAULT_SOFT_DELETE_USE_SCHEMA_DEFAULT = true;
  private static final String DEFAULT_SOFT_DELETE_COLUMNS = "deleted_at,is_deleted";
  private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
  private static final String DEFAULT_OLLAMA_MODEL = "";

  private boolean useLatinDictionary = true;
  private boolean useEnglishDictionary = false;
  private boolean useSpanishDictionary = false;
  private int columnSpinnerStep = DEFAULT_COLUMN_SPINNER_STEP;
  private String defaultOutputDirectory = DEFAULT_OUTPUT_DIRECTORY;

  private String softDeleteColumns = DEFAULT_SOFT_DELETE_COLUMNS;
  private boolean softDeleteUseSchemaDefault = DEFAULT_SOFT_DELETE_USE_SCHEMA_DEFAULT;
  private String softDeleteValue = DEFAULT_SOFT_DELETE_VALUE;

  private String ollamaUrl = DEFAULT_OLLAMA_URL;
  private String ollamaModel = DEFAULT_OLLAMA_MODEL;

  private boolean useAiGeneration = false;
  private String aiApplicationContext = "";
  private int aiWordCount = 1;
  private int aiRequestTimeoutSeconds = 120;

  private Map<String, List<RepetitionRule>> repetitionRules = new HashMap<>();

  private Map<String, Map<String, Integer>> circularReferences = new HashMap<>();

  private Map<String, Map<String, String>> circularReferenceTerminationModes = new HashMap<>();

  public static DbSeedSettingsState getInstance() {
    return Objects.requireNonNull(
        ApplicationManager.getApplication().getService(DbSeedSettingsState.class),
        "DbSeedSettingsState service not found. Plugin might be improperly installed.");
  }

  @Nullable
  @Override
  public DbSeedSettingsState getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull final DbSeedSettingsState state) {
    XmlSerializerUtil.copyBean(state, this);

    this.defaultOutputDirectory =
        Objects.requireNonNullElse(this.defaultOutputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    if (this.columnSpinnerStep <= 0) {
      this.columnSpinnerStep = DEFAULT_COLUMN_SPINNER_STEP;
    }
    this.useLatinDictionary = state.useLatinDictionary;
    this.useEnglishDictionary = state.useEnglishDictionary;
    this.useSpanishDictionary = state.useSpanishDictionary;

    this.softDeleteColumns =
        Objects.requireNonNullElse(state.softDeleteColumns, DEFAULT_SOFT_DELETE_COLUMNS);
    this.softDeleteValue =
        Objects.requireNonNullElse(state.softDeleteValue, DEFAULT_SOFT_DELETE_VALUE);
    this.softDeleteUseSchemaDefault = state.softDeleteUseSchemaDefault;

    this.ollamaUrl = Objects.requireNonNullElse(state.ollamaUrl, DEFAULT_OLLAMA_URL);
    this.ollamaModel = Objects.requireNonNullElse(state.ollamaModel, DEFAULT_OLLAMA_MODEL);

    this.useAiGeneration = state.useAiGeneration;
    this.aiApplicationContext = Objects.requireNonNullElse(state.aiApplicationContext, "");
    this.aiWordCount = state.aiWordCount > 0 ? state.aiWordCount : 1;
    this.aiRequestTimeoutSeconds =
        state.aiRequestTimeoutSeconds > 0 ? state.aiRequestTimeoutSeconds : 120;

    this.repetitionRules =
        Objects.nonNull(state.repetitionRules) ? state.repetitionRules : new HashMap<>();
    this.circularReferences =
        Objects.nonNull(state.circularReferences) ? state.circularReferences : new HashMap<>();
    this.circularReferenceTerminationModes =
        Objects.nonNull(state.circularReferenceTerminationModes)
            ? state.circularReferenceTerminationModes
            : new HashMap<>();
  }

  public Map<String, List<RepetitionRule>> getRepetitionRules() {
    return repetitionRules;
  }

  public void setRepetitionRules(final Map<String, List<RepetitionRule>> repetitionRules) {
    this.repetitionRules = repetitionRules;
  }

  public Map<String, Map<String, Integer>> getCircularReferences() {
    return circularReferences;
  }

  public void setCircularReferences(final Map<String, Map<String, Integer>> circularReferences) {
    this.circularReferences = circularReferences;
  }

  public Map<String, Map<String, String>> getCircularReferenceTerminationModes() {
    return circularReferenceTerminationModes;
  }

  public void setCircularReferenceTerminationModes(
      final Map<String, Map<String, String>> circularReferenceTerminationModes) {
    this.circularReferenceTerminationModes = circularReferenceTerminationModes;
  }
}
