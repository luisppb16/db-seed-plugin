package com.luisppb16.dbseed.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
    name = "com.luisppb16.dbseed.config.DbSeedSettingsState",
    storages = @Storage("DbSeedPlugin.xml"))
public class DbSeedSettingsState implements PersistentStateComponent<DbSeedSettingsState> {

  private static final String DEFAULT_OUTPUT_DIRECTORY = "src/main/resources/db/seeder";
  private static final int DEFAULT_COLUMN_SPINNER_STEP = 3;

  // New dictionary usage flags
  public boolean useLatinDictionary = true;
  public boolean useEnglishDictionary = false;
  public boolean useSpanishDictionary = false;

  public int columnSpinnerStep = DEFAULT_COLUMN_SPINNER_STEP;
  public String defaultOutputDirectory = DEFAULT_OUTPUT_DIRECTORY;

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
    defaultOutputDirectory =
        Objects.requireNonNullElse(defaultOutputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    if (columnSpinnerStep <= 0) {
      columnSpinnerStep = DEFAULT_COLUMN_SPINNER_STEP;
    }
    // No specific null check needed for booleans as they default to false if not present
  }
}
