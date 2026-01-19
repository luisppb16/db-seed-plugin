/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import java.util.Objects;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class DbSeedSettingsConfigurable implements Configurable {

  private final Project project;
  private DbSeedSettingsComponent mySettingsComponent;

  public DbSeedSettingsConfigurable(Project project) {
    this.project = project;
  }

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
    mySettingsComponent = new DbSeedSettingsComponent(project);
    return mySettingsComponent.getPanel();
  }

  @Override
  public boolean isModified() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    return mySettingsComponent.getColumnSpinnerStep() != settings.columnSpinnerStep
        || !Objects.equals(
            mySettingsComponent.getDefaultOutputDirectory(), settings.defaultOutputDirectory)
        || mySettingsComponent.getUseLatinDictionary() != settings.useLatinDictionary
        || mySettingsComponent.getUseEnglishDictionary() != settings.useEnglishDictionary
        || mySettingsComponent.getUseSpanishDictionary() != settings.useSpanishDictionary
        || !Objects.equals(mySettingsComponent.getSoftDeleteColumns(), settings.softDeleteColumns)
        || mySettingsComponent.getSoftDeleteUseSchemaDefault() != settings.softDeleteUseSchemaDefault
        || !Objects.equals(mySettingsComponent.getSoftDeleteValue(), settings.softDeleteValue);
  }

  @Override
  public void apply() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    settings.columnSpinnerStep = mySettingsComponent.getColumnSpinnerStep();
    settings.defaultOutputDirectory = mySettingsComponent.getDefaultOutputDirectory();
    settings.useLatinDictionary = mySettingsComponent.getUseLatinDictionary();
    settings.useEnglishDictionary = mySettingsComponent.getUseEnglishDictionary();
    settings.useSpanishDictionary = mySettingsComponent.getUseSpanishDictionary();
    
    settings.softDeleteColumns = mySettingsComponent.getSoftDeleteColumns();
    settings.softDeleteUseSchemaDefault = mySettingsComponent.getSoftDeleteUseSchemaDefault();
    settings.softDeleteValue = mySettingsComponent.getSoftDeleteValue();
  }

  @Override
  public void reset() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    mySettingsComponent.setColumnSpinnerStep(settings.columnSpinnerStep);
    mySettingsComponent.setDefaultOutputDirectory(settings.defaultOutputDirectory);
    mySettingsComponent.setUseLatinDictionary(settings.useLatinDictionary);
    mySettingsComponent.setUseEnglishDictionary(settings.useEnglishDictionary);
    mySettingsComponent.setUseSpanishDictionary(settings.useSpanishDictionary);
    
    mySettingsComponent.setSoftDeleteColumns(settings.softDeleteColumns);
    mySettingsComponent.setSoftDeleteUseSchemaDefault(settings.softDeleteUseSchemaDefault);
    mySettingsComponent.setSoftDeleteValue(settings.softDeleteValue);
  }

  @Override
  public void disposeUIResources() {
    mySettingsComponent = null;
  }
}
