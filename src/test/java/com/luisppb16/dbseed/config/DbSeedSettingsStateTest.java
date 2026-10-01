/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luisppb16.dbseed.ai.AiProvider;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DbSeedSettingsStateTest {

  private DbSeedSettingsState state;

  @BeforeEach
  void setUp() {
    state = new DbSeedSettingsState();
  }

  @Test
  void getState_returnsSelf() {
    assertThat(state.getState()).isSameAs(state);
  }

  @Test
  void loadState_nullTextSettings_fallBackToDefaults() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setDefaultOutputDirectory(null);
    incoming.setSoftDeleteColumns(null);
    incoming.setSoftDeleteValue(null);
    incoming.setAiUrl(null);
    incoming.setAiModel(null);
    incoming.setAiApplicationContext(null);

    state.loadState(incoming);

    assertThat(state.getDefaultOutputDirectory()).isEqualTo("src/main/resources/db/seeder");
    assertThat(state.getSoftDeleteColumns()).isEqualTo("deleted_at,is_deleted");
    assertThat(state.getSoftDeleteValue()).isEqualTo("NULL");
    assertThat(state.getAiUrl()).isEqualTo("http://localhost:11434");
    assertThat(state.getAiModel()).isEmpty();
    assertThat(state.getAiApplicationContext()).isEmpty();
  }

  @Test
  void loadState_nonPositiveNumericSettings_fallBackToDefaults() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setColumnSpinnerStep(0);
    incoming.setAiWordCount(-3);
    incoming.setAiRequestTimeoutSeconds(0);

    state.loadState(incoming);

    assertThat(state.getColumnSpinnerStep()).isEqualTo(3);
    assertThat(state.getAiWordCount()).isEqualTo(1);
    assertThat(state.getAiRequestTimeoutSeconds()).isEqualTo(120);
  }

  @Test
  void newState_defaultsToOllama() {
    assertThat(state.getAiProvider()).isEqualTo(AiProvider.OLLAMA);
    assertThat(state.getAiUrl()).isEqualTo(AiProvider.OLLAMA.getDefaultUrl());
    assertThat(state.getAiModel()).isEmpty();
  }

  @Test
  void loadState_nullProvider_fallsBackToOllama() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setAiProvider(null);

    state.loadState(incoming);

    assertThat(state.getAiProvider()).isEqualTo(AiProvider.OLLAMA);
  }

  @Test
  void loadState_copiesProvider() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setAiProvider(AiProvider.UNSLOTH_STUDIO);
    incoming.setAiUrl(AiProvider.UNSLOTH_STUDIO.getDefaultUrl());

    state.loadState(incoming);

    assertThat(state.getAiProvider()).isEqualTo(AiProvider.UNSLOTH_STUDIO);
    assertThat(state.getAiUrl()).isEqualTo("http://localhost:8888");
  }

  @Test
  void loadState_legacyOllamaSettings_areMigratedToTheAiFields() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    // Un XML escrito antes de 1.3.7.5 solo trae estos dos campos.
    incoming.ollamaUrl = "http://ollama:9999";
    incoming.ollamaModel = "llama3";

    state.loadState(incoming);

    assertThat(state.getAiUrl()).isEqualTo("http://ollama:9999");
    assertThat(state.getAiModel()).isEqualTo("llama3");
    assertThat(state.getAiProvider()).isEqualTo(AiProvider.OLLAMA);
    // Los campos antiguos se sueltan para que el archivo no lleve dos nombres de lo mismo.
    assertThat(incoming.ollamaUrl).isNull();
    assertThat(incoming.ollamaModel).isNull();
  }

  @Test
  void loadState_newFieldsWin_overLegacyOnes() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.ollamaUrl = "http://ollama:9999";
    incoming.ollamaModel = "llama3";
    incoming.setAiUrl("http://unsloth:8888");
    incoming.setAiModel("qwen");

    state.loadState(incoming);

    assertThat(state.getAiUrl()).isEqualTo("http://unsloth:8888");
    assertThat(state.getAiModel()).isEqualTo("qwen");
  }

  @Test
  void loadState_validValues_areCopied() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setDefaultOutputDirectory("custom/dir");
    incoming.setColumnSpinnerStep(7);
    incoming.setSoftDeleteColumns("removed_at");
    incoming.setSoftDeleteValue("current_timestamp");
    incoming.setAiUrl("http://ollama:9999");
    incoming.setAiModel("llama3");
    incoming.setAiApplicationContext("banking app");
    incoming.setAiWordCount(5);
    incoming.setAiRequestTimeoutSeconds(30);

    state.loadState(incoming);

    assertThat(state.getDefaultOutputDirectory()).isEqualTo("custom/dir");
    assertThat(state.getColumnSpinnerStep()).isEqualTo(7);
    assertThat(state.getSoftDeleteColumns()).isEqualTo("removed_at");
    assertThat(state.getSoftDeleteValue()).isEqualTo("current_timestamp");
    assertThat(state.getAiUrl()).isEqualTo("http://ollama:9999");
    assertThat(state.getAiModel()).isEqualTo("llama3");
    assertThat(state.getAiApplicationContext()).isEqualTo("banking app");
    assertThat(state.getAiWordCount()).isEqualTo(5);
    assertThat(state.getAiRequestTimeoutSeconds()).isEqualTo(30);
  }

  @Test
  void loadState_deepCopiesCircularReferences() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    final Map<String, Map<String, Integer>> source = new HashMap<>();
    source.put("orders", new HashMap<>(Map.of("customer_id", 5)));
    incoming.setCircularReferences(source);

    state.loadState(incoming);

    // Mutating the original source map after loadState must not affect the destination.
    source.get("orders").put("customer_id", 99);
    source.put("payments", new HashMap<>(Map.of("order_id", 1)));
    // Mutating the inner map held by the incoming state must not affect it either
    // (Collections.unmodifiableMap is shallow, so inner maps stay reachable).
    incoming.getCircularReferences().get("orders").put("customer_id", 77);

    assertThat(state.getCircularReferences()).containsOnlyKeys("orders");
    assertThat(state.getCircularReferences().get("orders")).containsEntry("customer_id", 5);
  }

  @Test
  void loadState_deepCopiesCircularReferenceTerminationModes() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    final Map<String, Map<String, String>> source = new HashMap<>();
    source.put("orders", new HashMap<>(Map.of("customer_id", "NULL")));
    incoming.setCircularReferenceTerminationModes(source);

    state.loadState(incoming);

    source.get("orders").put("customer_id", "SELF");
    source.put("payments", new HashMap<>(Map.of("order_id", "NULL")));
    incoming.getCircularReferenceTerminationModes().get("orders").put("customer_id", "SELF");

    assertThat(state.getCircularReferenceTerminationModes()).containsOnlyKeys("orders");
    assertThat(state.getCircularReferenceTerminationModes().get("orders"))
        .containsEntry("customer_id", "NULL");
  }

  @Test
  void getCircularReferences_returnsUnmodifiableMap() {
    state.setCircularReferences(Map.of("orders", Map.of("customer_id", 1)));
    final Map<String, Map<String, Integer>> view = state.getCircularReferences();
    // El IDE recomienda que la lambda tenga solo una invocación que pueda lanzar excepción
    assertThatThrownBy(
            () -> {
              view.put("payments", Map.of("order_id", 2));
            })
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void getCircularReferenceTerminationModes_returnsUnmodifiableMap() {
    state.setCircularReferenceTerminationModes(Map.of("orders", Map.of("customer_id", "NULL")));
    final Map<String, Map<String, String>> view = state.getCircularReferenceTerminationModes();
    // El IDE recomienda que la lambda tenga solo una invocación que pueda lanzar excepción
    assertThatThrownBy(
            () -> {
              view.put("payments", Map.of("order_id", "NULL"));
            })
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void setCircularReferences_deepCopiesArgument() {
    final Map<String, Integer> inner = new HashMap<>(Map.of("customer_id", 5));
    final Map<String, Map<String, Integer>> argument = new HashMap<>(Map.of("orders", inner));

    state.setCircularReferences(argument);
    inner.put("customer_id", 99);
    inner.put("vendor_id", 1);

    assertThat(state.getCircularReferences().get("orders"))
        .containsOnly(Map.entry("customer_id", 5));
  }

  @Test
  void setCircularReferenceTerminationModes_deepCopiesArgument() {
    final Map<String, String> inner = new HashMap<>(Map.of("customer_id", "NULL"));
    final Map<String, Map<String, String>> argument = new HashMap<>(Map.of("orders", inner));

    state.setCircularReferenceTerminationModes(argument);
    inner.put("customer_id", "SELF");
    inner.put("vendor_id", "NULL");

    assertThat(state.getCircularReferenceTerminationModes().get("orders"))
        .containsOnly(Map.entry("customer_id", "NULL"));
  }

  @ParameterizedTest
  @CsvSource({"false, 4, 1", "true, 1, 1", "true, 4, 4", "true, 99, 16", "true, 0, 1"})
  void effectiveAiThreads_clampsToTheAllowedRange(
      final boolean parallel, final int threads, final int expected) {
    state.setAiParallelGeneration(parallel);
    state.setAiGenerationThreads(threads);

    assertThat(state.effectiveAiThreads()).isEqualTo(expected);
  }

  @Test
  void effectiveAiThreads_parallelGenerationOff_isSequentialByDefault() {
    assertThat(state.isAiParallelGeneration()).isFalse();
    assertThat(state.effectiveAiThreads()).isEqualTo(1);
  }

  @Test
  void effectiveAiThreads_noConfiguration_usesTheConfiguredThreadCount() {
    state.setAiParallelGeneration(true);

    assertThat(state.effectiveAiThreads()).isEqualTo(2);
  }

  @Test
  void loadState_aiThreadsOutOfRange_fallBackToDefault() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setAiGenerationThreads(0);

    state.loadState(incoming);

    assertThat(state.getAiGenerationThreads()).isEqualTo(2);
  }

  @Test
  void loadState_aiThreadsAboveMaximum_fallBackToDefault() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setAiGenerationThreads(99);

    state.loadState(incoming);

    assertThat(state.getAiGenerationThreads()).isEqualTo(2);
  }

  @Test
  void loadState_copiesParallelismFlagAndThreads() {
    final DbSeedSettingsState incoming = new DbSeedSettingsState();
    incoming.setAiParallelGeneration(true);
    incoming.setAiGenerationThreads(8);

    state.loadState(incoming);

    assertThat(state.isAiParallelGeneration()).isTrue();
    assertThat(state.getAiGenerationThreads()).isEqualTo(8);
    assertThat(state.effectiveAiThreads()).isEqualTo(8);
  }
}
