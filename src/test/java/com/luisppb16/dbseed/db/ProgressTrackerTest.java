/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProgressTrackerTest {

  @Test
  void nullIndicator_behavesAsNoOp() {
    final ProgressTracker tracker = new ProgressTracker(null, 10);

    tracker.advance(3);
    tracker.setText("ignored");
    tracker.setText2("ignored");

    assertThat(tracker.isNoOp()).isTrue();
    assertThat(tracker.isCanceled()).isFalse();
    assertThat(tracker.getFraction()).isZero();
    assertThat(tracker.getCompleted()).isZero();
  }

  @Test
  void advance_updatesFractionAndClampsAtOne() {
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 2);

    tracker.advance();
    tracker.advance(5);

    verify(indicator).setFraction(0.5d);
    verify(indicator).setFraction(1.0d);
    assertThat(tracker.getCompleted()).isEqualTo(6);
    assertThat(tracker.getTotalWork()).isEqualTo(2);
  }

  @Test
  void delegatesTextAndCancellationToIndicator() {
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    when(indicator.isCanceled()).thenReturn(true);
    when(indicator.getFraction()).thenReturn(0.42d);

    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    tracker.setText("step 1");
    tracker.setText2("detail");

    verify(indicator).setText("step 1");
    verify(indicator).setText2("detail");
    assertThat(tracker.isCanceled()).isTrue();
    assertThat(tracker.getFraction()).isEqualTo(0.42d);
  }

  @Test
  void setAiBatchProgress_setsPlainTextOnIndicatorAndPublishesNumbers() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);
    final AiBatchProgress progress =
        new AiBatchProgress(
            1, 50, 61, AiBatchProgress.Stage.RETRY, 4, 5, 24L, -1L, -1L, "users", "email");

    // When
    tracker.setAiBatchProgress(progress);

    // Then
    verify(indicator).setText2("rows 1-50/61 · retry 4/5 · waiting 24s");
    assertThat(listener.aiBatchProgress()).containsExactly(progress);
  }

  @Test
  void setText2_afterBatchProgress_clearsTheStructuredLine() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);
    final AiBatchProgress progress =
        new AiBatchProgress(
            1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 3L, -1L, -1L, "users", "email");
    tracker.setAiBatchProgress(progress);

    // When
    tracker.setText2("AI generation complete");

    // Then
    assertThat(listener.aiBatchProgress()).containsExactly(progress, null);
  }

  @Test
  void setText2_repeatedFreeFormTexts_reachTheIndicatorEveryTime() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);

    // When — the free-form texts of the other phases, which are rare by construction
    tracker.setText2("Preparing tables");
    tracker.setText2("Writing SQL");

    // Then — every free-form text reaches the widget at once
    verify(indicator).setText2("Preparing tables");
    verify(indicator).setText2("Writing SQL");
  }

  @Test
  void advance_publishesGeneralEventWithTexts() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);

    // When
    tracker.advance(2);
    tracker.setText("phase");
    tracker.setText2("detail");

    // Then
    assertThat(listener.generalFractions()).containsExactly(0.2d, 0.2d, 0.2d);
    assertThat(listener.generalPhases()).containsExactly(null, "phase", null);
    assertThat(listener.generalDetails()).containsExactly(null, null, "detail");
  }

  @Test
  void aiPhase_mirrorsAdvancesWithCap() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);

    // When
    tracker.startAiPhase(5);
    tracker.advance(2);
    tracker.advance(4);

    // Then
    assertThat(listener.aiValues()).hasSize(2);
    assertThat(listener.aiValues().get(0)).containsExactly(2, 5);
    assertThat(listener.aiValues().get(1)).containsExactly(5, 5);
  }

  @Test
  void endAiPhase_stopsMirroringAdvances() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);

    // When
    tracker.startAiPhase(5);
    tracker.advance(1);
    tracker.endAiPhase();
    tracker.advance(1);

    // Then
    assertThat(listener.aiValues()).hasSize(1);
    assertThat(listener.aiValues().get(0)).containsExactly(1, 5);
  }

  @Test
  void startAiPhase_zeroTotal_disablesScope() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 5);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);

    // When
    tracker.startAiPhase(0);
    tracker.advance(3);

    // Then
    assertThat(listener.aiValues()).isEmpty();
    assertThat(tracker.getCompleted()).isEqualTo(3);
  }

  @Test
  void nullIndicator_firesNoListenerEvents() {
    // Given
    final ProgressTracker tracker = new ProgressTracker(null, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);

    // When
    tracker.startAiPhase(5);
    tracker.advance(2);
    tracker.setText("x");
    tracker.setText2("y");

    // Then
    assertThat(listener.generalFractions()).isEmpty();
    assertThat(listener.aiValues()).isEmpty();
  }

  @Test
  void adjustTotalWork_positiveDelta_lowersFraction() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);

    // When
    tracker.advance(5);
    tracker.adjustTotalWork(10);
    tracker.advance(5);

    // Then
    assertThat(tracker.getTotalWork()).isEqualTo(20);
    assertThat(listener.generalFractions()).containsExactly(0.5d, 0.5d);
  }

  @Test
  void adjustTotalWork_negativeDelta_raisesFraction() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    final RecordingProgressListener listener = new RecordingProgressListener();
    tracker.setProgressListener(listener);

    // When
    tracker.advance(4);
    tracker.adjustTotalWork(-5);
    tracker.advance(1);

    // Then
    assertThat(tracker.getTotalWork()).isEqualTo(5);
    assertThat(listener.generalFractions()).containsExactly(0.4d, 1.0d);
  }

  @Test
  void adjustTotalWork_neverGoesBelowOne() {
    // Given
    final ProgressTracker tracker = new ProgressTracker(Mockito.mock(ProgressIndicator.class), 3);

    // When
    tracker.adjustTotalWork(-100);

    // Then
    assertThat(tracker.getTotalWork()).isEqualTo(1);
  }

  @Test
  void adjustTotalWork_zeroDelta_keepsTotal() {
    // Given
    final ProgressTracker tracker = new ProgressTracker(Mockito.mock(ProgressIndicator.class), 7);

    // When
    tracker.adjustTotalWork(0);

    // Then
    assertThat(tracker.getTotalWork()).isEqualTo(7);
  }

  @Test
  void setProgressListener_null_fallsBackToNoOp() {
    // Given
    final ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 10);
    // When
    tracker.setProgressListener(null);
    // Then — advance with NO_OP listener must not throw
    assertThatCode(() -> tracker.advance(1)).doesNotThrowAnyException();
  }
}
