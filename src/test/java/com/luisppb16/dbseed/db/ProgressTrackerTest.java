/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProgressTrackerTest {

  @Test
  void nullIndicator_behavesAsNoOp() {
    ProgressTracker tracker = new ProgressTracker(null, 10);

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
    ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    ProgressTracker tracker = new ProgressTracker(indicator, 2);

    tracker.advance();
    tracker.advance(5);

    verify(indicator).setFraction(0.5d);
    verify(indicator).setFraction(1.0d);
    assertThat(tracker.getCompleted()).isEqualTo(6);
    assertThat(tracker.getTotalWork()).isEqualTo(2);
  }

  @Test
  void delegatesTextAndCancellationToIndicator() {
    ProgressIndicator indicator = Mockito.mock(ProgressIndicator.class);
    when(indicator.isCanceled()).thenReturn(true);
    when(indicator.getFraction()).thenReturn(0.42d);

    ProgressTracker tracker = new ProgressTracker(indicator, 10);
    tracker.setText("step 1");
    tracker.setText2("detail");

    verify(indicator).setText("step 1");
    verify(indicator).setText2("detail");
    assertThat(tracker.isCanceled()).isTrue();
    assertThat(tracker.getFraction()).isEqualTo(0.42d);
  }
}
