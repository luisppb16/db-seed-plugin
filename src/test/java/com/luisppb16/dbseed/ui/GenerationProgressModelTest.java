/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GenerationProgressModelTest {

  private static Consumer<Runnable> manualDispatcher(final List<Runnable> into) {
    return into::add;
  }

  @Test
  void eventsCoalesceIntoSingleEdtFlush() {
    final List<Runnable> dispatched = new ArrayList<>();
    final GenerationProgressModel model =
        new GenerationProgressModel(() -> {}, manualDispatcher(dispatched));
    model.onAiValue(1, 10);
    model.onAiValue(2, 10);
    assertThat(dispatched).hasSize(1);
    dispatched.getFirst().run();
    assertThat(model.getAiValuesDone()).isEqualTo(2);
    assertThat(model.getAiValuesTotal()).isEqualTo(10);
    model.onAiValue(3, 10);
    assertThat(dispatched).hasSize(2);
  }

  @Test
  void onGeneral_nullTextsKeepPrevious() {
    final List<Runnable> dispatched = new ArrayList<>();
    final GenerationProgressModel model =
        new GenerationProgressModel(() -> {}, manualDispatcher(dispatched));
    model.onGeneral(0.1, "A", null);
    model.onGeneral(0.2, null, "B");
    assertThat(model.getPhaseText()).isEqualTo("A");
    assertThat(model.getDetailText()).isEqualTo("B");
    assertThat(model.getGeneralFraction()).isEqualTo(0.2);
  }

  @Test
  void capsValuesAndClampsFraction() {
    final List<Runnable> dispatched = new ArrayList<>();
    final GenerationProgressModel model =
        new GenerationProgressModel(() -> {}, manualDispatcher(dispatched));
    model.onAiValue(150, 100);
    model.onGeneral(1.5, null, null);
    model.onGeneral(-0.5, null, null);
    assertThat(model.getAiValuesDone()).isEqualTo(100);
    assertThat(model.getGeneralFraction()).isZero();
  }

  @Test
  void aiPhaseVisibility_transitions() {
    final List<Runnable> dispatched = new ArrayList<>();
    final GenerationProgressModel model =
        new GenerationProgressModel(() -> {}, manualDispatcher(dispatched));
    model.markAiPhaseExpected();
    assertThat(model.isAiPhaseVisible()).isTrue();
    assertThat(model.isAiPhaseIndeterminate()).isTrue();
    model.onAiPhaseStarted(10, 2);
    assertThat(model.isAiPhaseVisible()).isTrue();
    assertThat(model.isAiPhaseIndeterminate()).isFalse();
    assertThat(model.getAiValuesTotal()).isEqualTo(10);
    assertThat(model.getAiColumnsTotal()).isEqualTo(2);
    model.onAiPhaseSkipped();
    assertThat(model.isAiPhaseVisible()).isFalse();
  }

  @Test
  void onAiColumnCompleted_setsLabelAndCounts() {
    final List<Runnable> dispatched = new ArrayList<>();
    final GenerationProgressModel model =
        new GenerationProgressModel(() -> {}, manualDispatcher(dispatched));
    model.onAiColumnCompleted("products", "description", 1, 3);
    assertThat(model.getLastAiColumnName()).isEqualTo("products.description");
    assertThat(model.getAiColumnsDone()).isEqualTo(1);
    assertThat(model.getAiColumnsTotal()).isEqualTo(3);
  }
}
