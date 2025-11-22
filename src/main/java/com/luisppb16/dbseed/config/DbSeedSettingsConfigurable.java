/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.options.Configurable;
import java.util.Objects;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class DbSeedSettingsConfigurable implements Configurable {

  private DbSeedSettingsComponent mySettingsComponent;

  @Nls(capitalization = Nls.Capitalization.Title)
  @Override
  public String getDisplayName() {
    return "DB Seed Plugin";
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return mySettingsComponent.getPreferredFocusedComponent();
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    mySettingsComponent = new DbSeedSettingsComponent();
    return mySettingsComponent.getPanel();
  }

  @Override
  public boolean isModified() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    return mySettingsComponent.getColumnSpinnerStep() != settings.columnSpinnerStep
        || !Objects.equals(
            mySettingsComponent.getDefaultOutputDirectory(), settings.defaultOutputDirectory)
        || mySettingsComponent.getUuidStrategy() != settings.uuidStrategy;
  }

  @Override
  public void apply() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    settings.columnSpinnerStep = mySettingsComponent.getColumnSpinnerStep();
    settings.defaultOutputDirectory = mySettingsComponent.getDefaultOutputDirectory();
    settings.uuidStrategy = mySettingsComponent.getUuidStrategy();
  }

  @Override
  public void reset() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    mySettingsComponent.setColumnSpinnerStep(settings.columnSpinnerStep);
    mySettingsComponent.setDefaultOutputDirectory(settings.defaultOutputDirectory);
    mySettingsComponent.setUuidStrategy(settings.uuidStrategy);
  }

  @Override
  public void disposeUIResources() {
    mySettingsComponent = null;
  }
}
