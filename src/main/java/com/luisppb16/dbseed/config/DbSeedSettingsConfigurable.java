/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.luisppb16.dbseed.ai.AiClient;
import com.luisppb16.dbseed.ai.AiClientFactory;
import com.luisppb16.dbseed.ai.AiProvider;
import com.luisppb16.dbseed.util.NotificationHelper;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

/** IntelliJ settings configurable implementation for the DBSeed plugin configuration interface. */
public class DbSeedSettingsConfigurable implements Configurable {

  private final Project myProject;
  private DbSeedSettingsComponent mySettingsComponent;

  public DbSeedSettingsConfigurable(Project project) {
    this.myProject = project;
  }

  @Nls(capitalization = Nls.Capitalization.Title)
  @Override
  public String getDisplayName() {
    return "DBSeed4SQL";
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return mySettingsComponent != null ? mySettingsComponent.getPreferredFocusedComponent() : null;
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    mySettingsComponent = new DbSeedSettingsComponent(myProject);
    return mySettingsComponent.getPanel();
  }

  @Override
  public boolean isModified() {
    if (mySettingsComponent == null) return false;
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    boolean isProfileModified = mySettingsComponent.isProfileModified();

    return isProfileModified
        || mySettingsComponent.getColumnSpinnerStep() != settings.getColumnSpinnerStep()
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
        || mySettingsComponent.getAiProvider() != settings.getAiProvider()
        || !Objects.equals(mySettingsComponent.getAiUrl(), settings.getAiUrl())
        || !Objects.equals(mySettingsComponent.getAiModel(), settings.getAiModel())
        || !Objects.equals(
            mySettingsComponent.getAiApiKey(),
            AiApiKeyStore.load(mySettingsComponent.getAiProvider()))
        || mySettingsComponent.getAiWordCount() != settings.getAiWordCount()
        || mySettingsComponent.getAiRequestTimeout() != settings.getAiRequestTimeoutSeconds()
        || mySettingsComponent.getAiParallelGeneration() != settings.isAiParallelGeneration()
        || mySettingsComponent.getAiGenerationThreads() != settings.getAiGenerationThreads();
  }

  @Override
  public void apply() throws ConfigurationException {
    final AiProvider provider = mySettingsComponent.getAiProvider();
    if (mySettingsComponent.getUseAiGeneration()) {
      String url = mySettingsComponent.getAiUrl();
      if (Objects.isNull(url) || url.trim().isEmpty()) {
        throw new ConfigurationException(
            "Please enter a valid server URL when AI generation is enabled.",
            "Invalid AI Configuration");
      }
      String model = mySettingsComponent.getAiModel();
      if (Objects.isNull(model) || model.trim().isEmpty()) {
        throw new ConfigurationException(
            "Please select an "
                + provider.getDisplayName()
                + " model when AI generation is enabled, or disable AI generation.",
            "Invalid AI Configuration");
      }
      if (provider.isApiKeyRequired() && mySettingsComponent.getAiApiKey().isBlank()) {
        throw new ConfigurationException(
            provider.getDisplayName()
                + " requires a credential. Paste either the API key Unsloth Studio hands out under"
                + " Settings → API, or the password you sign in to it with, or enable Keyless API"
                + " access there, or choose another engine.",
            "Invalid AI Configuration");
      }
    }

    // Save all settings first, regardless of AI server availability
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
    settings.setAiProvider(provider);
    settings.setAiUrl(mySettingsComponent.getAiUrl());
    settings.setAiModel(mySettingsComponent.getAiModel());
    if (mySettingsComponent.getAiApiKey().isBlank()) {
      AiApiKeyStore.clear(provider);
    } else {
      AiApiKeyStore.save(provider, mySettingsComponent.getAiApiKey());
    }
    settings.setAiWordCount(mySettingsComponent.getAiWordCount());
    settings.setAiRequestTimeoutSeconds(mySettingsComponent.getAiRequestTimeout());
    settings.setAiParallelGeneration(mySettingsComponent.getAiParallelGeneration());
    settings.setAiGenerationThreads(mySettingsComponent.getAiGenerationThreads());

    mySettingsComponent.applyProfileSettings();

    // Validate the AI connection as a non-blocking warning (settings are already saved)
    if (mySettingsComponent.getUseAiGeneration()) {
      String url = mySettingsComponent.getAiUrl();
      if (Objects.nonNull(url) && !url.trim().isEmpty()) {
        final AiClient client =
            AiClientFactory.create(
                provider,
                url.trim(),
                mySettingsComponent.getAiModel(),
                mySettingsComponent.getAiApiKey(),
                10);
        AtomicReference<Exception> pingError = new AtomicReference<>();
        ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                () -> {
                  try {
                    if (Objects.nonNull(client)) {
                      client.ping().get(3, TimeUnit.SECONDS);
                    }
                  } catch (Exception e) {
                    pingError.set(e);
                  }
                },
                "Checking " + provider.getDisplayName() + " server...",
                false,
                myProject);

        if (Objects.nonNull(pingError.get())) {
          Throwable cause =
              Objects.nonNull(pingError.get().getCause())
                  ? pingError.get().getCause()
                  : pingError.get();
          NotificationHelper.notifyWarning(
              myProject,
              provider.getDisplayName() + " server not reachable",
              "Settings saved, but no "
                  + provider.getDisplayName()
                  + " server found at "
                  + url.trim()
                  + ". AI generation may not work until it is available. Error: "
                  + Objects.requireNonNullElse(
                      cause.getMessage(), cause.getClass().getSimpleName()));
        }
      }
    }
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
    mySettingsComponent.setAiProvider(settings.getAiProvider());
    mySettingsComponent.setAiUrl(settings.getAiUrl());
    mySettingsComponent.setAiModel(settings.getAiModel());
    mySettingsComponent.setAiApiKey(AiApiKeyStore.load(settings.getAiProvider()));
    mySettingsComponent.setAiWordCount(settings.getAiWordCount());
    mySettingsComponent.setAiRequestTimeout(settings.getAiRequestTimeoutSeconds());
    mySettingsComponent.setAiParallelGeneration(settings.isAiParallelGeneration());
    mySettingsComponent.setAiGenerationThreads(settings.getAiGenerationThreads());

    mySettingsComponent.resetProfileSettings();
  }

  @Override
  public void disposeUIResources() {
    if (Objects.nonNull(mySettingsComponent)) {
      mySettingsComponent.dispose();
    }
    mySettingsComponent = null;
  }
}
