/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
  private static final String DEFAULT_AI_MODEL = "prem-1b-sql";

  private String ollamaUrl = DEFAULT_OLLAMA_URL;
  private String aiModel = DEFAULT_AI_MODEL;
  private boolean useLatinDictionary = true;
  private boolean useEnglishDictionary = false;
  private boolean useSpanishDictionary = false;
  private int columnSpinnerStep = DEFAULT_COLUMN_SPINNER_STEP;
  private String defaultOutputDirectory = DEFAULT_OUTPUT_DIRECTORY;

  // Soft Delete Configuration
  private String softDeleteColumns = DEFAULT_SOFT_DELETE_COLUMNS;
  private boolean softDeleteUseSchemaDefault = DEFAULT_SOFT_DELETE_USE_SCHEMA_DEFAULT;
  private String softDeleteValue = DEFAULT_SOFT_DELETE_VALUE;

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

    // Ensure resilience against null or invalid values from a deserialized state
    this.defaultOutputDirectory =
        Objects.requireNonNullElse(this.defaultOutputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    if (this.columnSpinnerStep <= 0) {
      this.columnSpinnerStep = DEFAULT_COLUMN_SPINNER_STEP;
    }
    // Restore explicit assignment for language settings
    this.useLatinDictionary = state.useLatinDictionary;
    this.useEnglishDictionary = state.useEnglishDictionary;
    this.useSpanishDictionary = state.useSpanishDictionary;

    // Soft Delete defaults
    this.softDeleteColumns =
        Objects.requireNonNullElse(state.softDeleteColumns, DEFAULT_SOFT_DELETE_COLUMNS);
    this.softDeleteValue =
        Objects.requireNonNullElse(state.softDeleteValue, DEFAULT_SOFT_DELETE_VALUE);
    this.softDeleteUseSchemaDefault = state.softDeleteUseSchemaDefault;

    this.ollamaUrl = Objects.requireNonNullElse(state.ollamaUrl, DEFAULT_OLLAMA_URL);
    this.aiModel = Objects.requireNonNullElse(state.aiModel, DEFAULT_AI_MODEL);
  }
}
