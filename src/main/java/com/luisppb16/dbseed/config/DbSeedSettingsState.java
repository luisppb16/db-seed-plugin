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

/**
 * Persistent state component for managing global configuration settings of the DBSeed plugin.
 * <p>
 * This class implements IntelliJ's PersistentStateComponent interface to provide persistent
 * storage of user preferences across IDE sessions. It manages various configuration aspects
 * including dictionary selection, output directory paths, and soft-delete column settings.
 * The state is serialized to an XML file named "DbSeedPlugin.xml" and follows IntelliJ's
 * recommended practices for plugin state persistence.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Maintaining user preferences for dictionary languages (Latin, English, Spanish)</li>
 *   <li>Storing configurable output directory paths for generated seed files</li>
 *   <li>Managing soft-delete column configurations with default values and validation</li>
 *   <li>Providing singleton access pattern through getInstance() method</li>
 *   <li>Ensuring data integrity during state serialization/deserialization</li>
 * </ul>
 * </p>
 * <p>
 * The class implements proper null-safety mechanisms and defensive programming practices
 * to prevent configuration corruption during state transitions. Default values are enforced
 * during the loadState operation to maintain backward compatibility with older configurations.
 * </p>
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

  private boolean useLatinDictionary = true;
  private boolean useEnglishDictionary = false;
  private boolean useSpanishDictionary = false;
  private int columnSpinnerStep = DEFAULT_COLUMN_SPINNER_STEP;
  private String defaultOutputDirectory = DEFAULT_OUTPUT_DIRECTORY;

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
  }
}
