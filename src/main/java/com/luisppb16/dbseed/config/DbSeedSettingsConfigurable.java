/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.luisppb16.dbseed.ai.OllamaClient;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

/** IntelliJ settings configurable implementation for the DBSeed plugin configuration interface. */
public class DbSeedSettingsConfigurable implements Configurable {

  private DbSeedSettingsComponent mySettingsComponent;

  @Nls(capitalization = Nls.Capitalization.Title)
  @Override
  public String getDisplayName() {
    return "DBSeed4SQL";
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
        || !Objects.equals(mySettingsComponent.getSoftDeleteValue(), settings.getSoftDeleteValue())
        || mySettingsComponent.getUseAiGeneration() != settings.isUseAiGeneration()
        || !Objects.equals(
            mySettingsComponent.getAiApplicationContext(), settings.getAiApplicationContext())
        || !Objects.equals(mySettingsComponent.getOllamaUrl(), settings.getOllamaUrl())
        || !Objects.equals(mySettingsComponent.getOllamaModel(), settings.getOllamaModel())
        || mySettingsComponent.getAiWordCount() != settings.getAiWordCount()
        || mySettingsComponent.getAiRequestTimeout() != settings.getAiRequestTimeoutSeconds();
  }

  @Override
  public void apply() throws ConfigurationException {
    if (mySettingsComponent.getUseAiGeneration()) {
      String url = mySettingsComponent.getOllamaUrl();
      if (Objects.isNull(url) || url.trim().isEmpty()) {
        throw new ConfigurationException(
            "Please enter a valid Ollama URL when AI generation is enabled.",
            "Invalid Ollama Configuration");
      }

      AtomicReference<Exception> pingError = new AtomicReference<>();
      ProgressManager.getInstance()
          .runProcessWithProgressSynchronously(
              () -> {
                try {
                  new OllamaClient(url.trim(), "", 10).ping().get(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                  pingError.set(e);
                }
              },
              "Checking Ollama Server...",
              false,
              null);

      if (Objects.nonNull(pingError.get())) {
        Throwable cause =
            Objects.nonNull(pingError.get().getCause())
                ? pingError.get().getCause()
                : pingError.get();
        throw new ConfigurationException(
            "No Ollama server found at "
                + url.trim()
                + ".\n"
                + "Ensure Ollama is running and the URL is correct.\n\n"
                + "Error: "
                + cause.getMessage(),
            "Server Not Reachable");
      }
    }

    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();

    settings.setColumnSpinnerStep(mySettingsComponent.getColumnSpinnerStep());
    settings.setDefaultOutputDirectory(mySettingsComponent.getDefaultOutputDirectory());
    settings.setUseLatinDictionary(mySettingsComponent.getUseLatinDictionary());
    settings.setUseEnglishDictionary(mySettingsComponent.getUseEnglishDictionary());
    settings.setUseSpanishDictionary(mySettingsComponent.getUseSpanishDictionary());

    settings.setSoftDeleteColumns(mySettingsComponent.getSoftDeleteColumns());
    settings.setSoftDeleteUseSchemaDefault(mySettingsComponent.getSoftDeleteUseSchemaDefault());
    settings.setSoftDeleteValue(mySettingsComponent.getSoftDeleteValue());

    settings.setUseAiGeneration(mySettingsComponent.getUseAiGeneration());
    settings.setAiApplicationContext(mySettingsComponent.getAiApplicationContext());
    settings.setOllamaUrl(mySettingsComponent.getOllamaUrl());
    settings.setOllamaModel(mySettingsComponent.getOllamaModel());
    settings.setAiWordCount(mySettingsComponent.getAiWordCount());
    settings.setAiRequestTimeoutSeconds(mySettingsComponent.getAiRequestTimeout());
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

    mySettingsComponent.setUseAiGeneration(settings.isUseAiGeneration());
    mySettingsComponent.setAiApplicationContext(settings.getAiApplicationContext());
    mySettingsComponent.setOllamaUrl(settings.getOllamaUrl());
    mySettingsComponent.setOllamaModel(settings.getOllamaModel());
    mySettingsComponent.setAiWordCount(settings.getAiWordCount());
    mySettingsComponent.setAiRequestTimeout(settings.getAiRequestTimeoutSeconds());
  }

  @Override
  public void disposeUIResources() {
    if (Objects.nonNull(mySettingsComponent)) {
      mySettingsComponent.dispose();
    }
    mySettingsComponent = null;
  }
}
