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

/**
 * IntelliJ settings configurable implementation for the DBSeed plugin configuration interface.
 * <p>
 * This class provides the integration point between the DBSeed plugin's configuration
 * component and IntelliJ's settings framework. It implements the Configurable interface
 * to provide a standardized way for users to access and modify the plugin's global
 * settings through IntelliJ's settings dialog. The class manages the lifecycle of
 * the settings UI component and handles the application and validation of user changes.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Integrating the DBSeed settings component with IntelliJ's settings framework</li>
 *   <li>Managing the lifecycle of the settings UI component</li>
 *   <li>Handling the application of user changes to persistent settings</li>
 *   <li>Providing validation to determine if settings have been modified</li>
 *   <li>Implementing reset functionality to restore current settings</li>
 *   <li>Properly disposing of UI resources when the settings dialog is closed</li>
 * </ul>
 * </p>
 * <p>
 * The implementation follows IntelliJ's conventions for settings configuration,
 * providing proper integration with the IDE's settings validation and persistence
 * mechanisms. It ensures that changes made in the settings dialog are properly
 * synchronized with the persistent settings state and provides appropriate user
 * feedback when changes are applied or reverted.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 */
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
