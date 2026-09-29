/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.db.AiBatchProgress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GenerationProgressModelTest {

  private static Consumer<Runnable> manualDispatcher(final List<Runnable> into) {
    return into::add;
  }

  /** A model whose batch-line cadence is driven by an injectable nanosecond clock. */
  private static GenerationProgressModel modelWithClock(
      final List<Runnable> dispatched, final AtomicLong clock) {
    return new GenerationProgressModel(() -> {}, manualDispatcher(dispatched), clock::get);
  }

  /** A batch line of the given stage, holding the given number of streamed values. */
  private static AiBatchProgress batchLine(final AiBatchProgress.Stage stage, final int done) {
    return new AiBatchProgress(1, 50, 61, stage, done, 50, -1L, -1L, -1L, "users", "email");
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
  void onAiColumnCompleted_setsCounts() {
    final List<Runnable> dispatched = new ArrayList<>();
    final GenerationProgressModel model =
        new GenerationProgressModel(() -> {}, manualDispatcher(dispatched));
    model.onAiColumnCompleted("products", "description", 1, 3);
    assertThat(model.getAiColumnsDone()).isEqualTo(1);
    assertThat(model.getAiColumnsTotal()).isEqualTo(3);
  }

  @Test
  void batchLine_beforeAnyBatch_isNull() {
    // Given / When
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), new AtomicLong());

    // Then
    assertThat(model.batchLineForRender()).isNull();
  }

  @Test
  void batchLine_firstBatch_isShownImmediately() {
    // Given
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), new AtomicLong());
    final AiBatchProgress first = batchLine(AiBatchProgress.Stage.TIMING, 0);

    // When
    model.onAiBatchProgress(first);

    // Then — nothing is painted yet, so there is nothing for the cadence to hold back
    assertThat(model.batchLineForRender()).isSameAs(first);
  }

  @Test
  void batchLine_sameStageWithinTheInterval_keepsTheShownLine() {
    // Given
    final AtomicLong clock = new AtomicLong();
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), clock);
    final AiBatchProgress first = batchLine(AiBatchProgress.Stage.VALUES, 1);
    model.onAiBatchProgress(first);
    model.batchLineForRender();

    // When — the values of the batch keep streaming
    clock.set(Duration.ofMillis(1999).toNanos());
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 25));

    // Then
    assertThat(model.batchLineForRender()).isSameAs(first);
  }

  @Test
  void batchLine_afterTheInterval_showsTheLatestLine() {
    // Given
    final AtomicLong clock = new AtomicLong();
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), clock);
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 1));
    model.batchLineForRender();

    // When — two seconds pass while the values stream in
    clock.set(Duration.ofSeconds(2).toNanos());
    final AiBatchProgress latest = batchLine(AiBatchProgress.Stage.VALUES, 25);
    model.onAiBatchProgress(latest);

    // Then — the line advances to the newest numbers, not to the first ones of the interval
    assertThat(model.batchLineForRender()).isSameAs(latest);
  }

  @Test
  void batchLine_stageChangeWithinTheInterval_isHeldBack() {
    // Given — a batch that has just started, timed, and is the line being painted
    final AtomicLong clock = new AtomicLong();
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), clock);
    final AiBatchProgress timing = batchLine(AiBatchProgress.Stage.TIMING, 0);
    model.onAiBatchProgress(timing);
    model.batchLineForRender();

    // When — the values of that batch start streaming right away
    clock.set(Duration.ofMillis(100).toNanos());
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 1));

    // Then — the cadence is time-only. With several AI columns streaming at once the stages
    // interleave constantly, so letting a stage change through put the line back in real time.
    assertThat(model.batchLineForRender()).isSameAs(timing);
  }

  @Test
  void batchLine_freeFormText_supersedesTheLineImmediately() {
    // Given
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), new AtomicLong());
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 1));
    model.batchLineForRender();

    // When — another phase writes its own detail text
    model.onGeneral(0.2, null, "Writing SQL");

    // Then
    assertThat(model.batchLineForRender()).isNull();
  }

  @Test
  void batchLine_batchAfterFreeFormText_isShownImmediately() {
    // Given — a free-form text replaced the batch line
    final AtomicLong clock = new AtomicLong();
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), clock);
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 1));
    model.batchLineForRender();
    model.onGeneral(0.2, null, "Writing SQL");
    model.batchLineForRender();

    // When — the next batch line arrives inside the interval
    clock.set(Duration.ofMillis(100).toNanos());
    final AiBatchProgress next = batchLine(AiBatchProgress.Stage.TIMING, 0);
    model.onAiBatchProgress(next);

    // Then — with nothing painted there is nothing to hold back
    assertThat(model.batchLineForRender()).isSameAs(next);
  }

  @Test
  void batchLine_nullProgress_clearsTheLine() {
    // Given
    final GenerationProgressModel model = modelWithClock(new ArrayList<>(), new AtomicLong());
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 1));
    model.batchLineForRender();

    // When
    model.onAiBatchProgress(null);

    // Then
    assertThat(model.batchLineForRender()).isNull();
  }

  @Test
  void onAiBatchProgress_burst_dispatchesASingleCoalescedFlush() {
    // Given
    final List<Runnable> dispatched = new ArrayList<>();
    final GenerationProgressModel model = modelWithClock(dispatched, new AtomicLong());

    // When — several AI columns stream values at once
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 1));
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 2));
    model.onAiBatchProgress(batchLine(AiBatchProgress.Stage.VALUES, 3));

    // Then
    assertThat(dispatched).hasSize(1);
  }
}
