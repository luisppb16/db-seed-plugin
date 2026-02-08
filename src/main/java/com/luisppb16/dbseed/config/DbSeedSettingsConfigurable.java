/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
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
    return mySettingsComponent.getColumnSpinnerStep() != settings.getColumnSpinnerStep()
        || !Objects.equals(
            mySettingsComponent.getDefaultOutputDirectory(), settings.getDefaultOutputDirectory())
        || mySettingsComponent.getUseLatinDictionary() != settings.isUseLatinDictionary()
        || mySettingsComponent.getUseEnglishDictionary() != settings.isUseEnglishDictionary()
        || mySettingsComponent.getUseSpanishDictionary() != settings.isUseSpanishDictionary()
        || !Objects.equals(
            mySettingsComponent.getSoftDeleteColumns(), settings.getSoftDeleteColumns())
        || mySettingsComponent.getSoftDeleteUseSchemaDefault()
            != settings.isSoftDeleteUseSchemaDefault()
        || !Objects.equals(mySettingsComponent.getSoftDeleteValue(), settings.getSoftDeleteValue());
  }

  @Override
  public void apply() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    settings.setColumnSpinnerStep(mySettingsComponent.getColumnSpinnerStep());
    settings.setDefaultOutputDirectory(mySettingsComponent.getDefaultOutputDirectory());
    settings.setUseLatinDictionary(mySettingsComponent.getUseLatinDictionary());
    settings.setUseEnglishDictionary(mySettingsComponent.getUseEnglishDictionary());
    settings.setUseSpanishDictionary(mySettingsComponent.getUseSpanishDictionary());

    settings.setSoftDeleteColumns(mySettingsComponent.getSoftDeleteColumns());
    settings.setSoftDeleteUseSchemaDefault(mySettingsComponent.getSoftDeleteUseSchemaDefault());
    settings.setSoftDeleteValue(mySettingsComponent.getSoftDeleteValue());
  }

  @Override
  public void reset() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    mySettingsComponent.setColumnSpinnerStep(settings.getColumnSpinnerStep());
    mySettingsComponent.setDefaultOutputDirectory(settings.getDefaultOutputDirectory());
    mySettingsComponent.setUseLatinDictionary(settings.isUseLatinDictionary());
    mySettingsComponent.setUseEnglishDictionary(settings.isUseEnglishDictionary());
    mySettingsComponent.setUseSpanishDictionary(settings.isUseSpanishDictionary());

    mySettingsComponent.setSoftDeleteColumns(settings.getSoftDeleteColumns());
    mySettingsComponent.setSoftDeleteUseSchemaDefault(settings.isSoftDeleteUseSchemaDefault());
    mySettingsComponent.setSoftDeleteValue(settings.getSoftDeleteValue());
  }

  @Override
  public void disposeUIResources() {
    mySettingsComponent = null;
  }
}
