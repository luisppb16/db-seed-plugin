/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import com.intellij.openapi.progress.ProgressIndicator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;

/**
 * Real-time progress tracker that computes fraction from actual completed work units.
 *
 * <p>Work units are pre-calculated based on the real workload (rows to generate, tables to
 * validate, FK columns to resolve, rows to serialise to SQL, etc.). Every call to {@link
 * #advance(long)} atomically increments the completed count and pushes the current fraction to the
 * underlying {@link ProgressIndicator}.
 *
 * <p>This class is thread-safe — multiple threads (e.g. AI column generators) can call {@code
 * advance} concurrently.
 */
@Getter
public final class ProgressTracker {

  private final ProgressIndicator indicator;
  private final AtomicLong completed = new AtomicLong(0);

  /** AI-phase sub-counter mirrored from advances while the AI scope is active. */
  private final AtomicLong aiCompleted = new AtomicLong(0);

  private volatile long totalWork;

  /** Progress event hub; defaults to the do-nothing listener. */
  private volatile GenerationProgressListener progressListener = GenerationProgressListener.NO_OP;

  /** Total AI work units of the current AI phase (used to cap the AI bar at 100%). */
  private volatile long aiTotalWork = 0L;

  /** Whether advances currently belong to the AI phase (bracketed by startAiPhase/endAiPhase). */
  private volatile boolean aiPhaseActive = false;

  /**
   * @param indicator the IntelliJ progress indicator to update (may be {@code null} — all
   *     operations become no-ops).
   * @param totalWork the total number of work units expected. Must be &gt; 0 when {@code indicator}
   *     is non-null.
   */
  public ProgressTracker(final ProgressIndicator indicator, final long totalWork) {
    this.indicator = indicator;
    this.totalWork = Math.max(totalWork, 1); // avoid division by zero
  }

  /** Returns {@code true} when no indicator is attached (progress is a no-op). */
  public boolean isNoOp() {
    return Objects.isNull(indicator);
  }

  /** Advance the completed counter by {@code units} and update the indicator fraction. */
  public void advance(final long units) {
    if (Objects.isNull(indicator) || units <= 0) return;
    final long now = completed.addAndGet(units);
    if (aiPhaseActive) {
      final long aiDone = Math.min(aiCompleted.addAndGet(units), aiTotalWork);
      progressListener.onAiValue(aiDone, aiTotalWork);
    }
    final double fraction = currentFraction(now);
    indicator.setFraction(fraction);
    progressListener.onGeneral(fraction, null, null);
  }

  /** Convenience shorthand — advance by one unit. */
  public void advance() {
    advance(1);
  }

  /**
   * Recalibrates the grand total when the real workload differs from the up-front estimate (e.g. AI
   * columns dropped by exclusions or behaviour rules) or when work is discovered late. Meant to be
   * called on phase boundaries, never while column threads are advancing concurrently.
   */
  public void adjustTotalWork(final long delta) {
    if (delta == 0L) {
      return;
    }
    totalWork = Math.max(1L, totalWork + delta);
  }

  /** Sets the progress listener (null normalizes to the no-op listener). */
  public void setProgressListener(final GenerationProgressListener progressListener) {
    this.progressListener =
        Objects.requireNonNullElse(progressListener, GenerationProgressListener.NO_OP);
  }

  /**
   * Marks the start of the AI phase: while active, every {@link #advance(long)} also mirrors onto
   * the AI sub-counter and fires {@link GenerationProgressListener#onAiValue(long, long)}. No-op
   * when {@code totalAiWork <= 0}.
   */
  public void startAiPhase(final long totalAiWork) {
    if (totalAiWork <= 0) {
      return;
    }
    aiTotalWork = totalAiWork;
    aiPhaseActive = true;
  }

  /** Ends the AI phase: subsequent advances count toward the general bar only. */
  public void endAiPhase() {
    aiPhaseActive = false;
  }

  /** Set the primary status text and publish it through the listener. */
  public void setText(final String text) {
    if (Objects.isNull(indicator)) {
      return;
    }
    indicator.setText(text);
    progressListener.onGeneral(currentFraction(completed.get()), text, null);
  }

  /** Set the secondary (detail) status text and publish it through the listener. */
  public void setText2(final String text) {
    if (Objects.isNull(indicator)) {
      return;
    }
    indicator.setText2(text);
    progressListener.onGeneral(currentFraction(completed.get()), null, text);
    // A free-form text replaces the structured AI batch line, when one was being shown.
    progressListener.onAiBatchProgress(null);
  }

  /**
   * Set the AI batch detail line from its raw numbers. The IDE widget receives the equivalent plain
   * text, while the listener receives the numbers themselves so that consumers can keep the static
   * words of the line pinned and update only the digits — the line stays readable no matter how
   * fast values stream in.
   *
   * <p>Every event is published: the refresh cadence of the dialog line belongs to the view, which
   * samples the freshest line it has (see {@code GenerationProgressModel}).
   *
   * @param progress the batch numbers to display; must not be {@code null}
   */
  public void setAiBatchProgress(final AiBatchProgress progress) {
    Objects.requireNonNull(progress, "Batch progress cannot be null");
    if (Objects.isNull(indicator)) {
      return;
    }
    indicator.setText2(progress.format());
    progressListener.onGeneral(currentFraction(completed.get()), null, null);
    progressListener.onAiBatchProgress(progress);
  }

  /** Check whether the user has requested cancellation. */
  public boolean isCanceled() {
    return Objects.nonNull(indicator) && indicator.isCanceled();
  }

  /** Return the current fraction (0.0 – 1.0). */
  public double getFraction() {
    return Objects.nonNull(indicator) ? indicator.getFraction() : 0.0;
  }

  /** Return the number of completed work units so far. */
  public long getCompleted() {
    return completed.get();
  }

  /** Return the total number of work units. */
  public long getTotalWork() {
    return totalWork;
  }

  /** Maps completed units to a 0.0–1.0 fraction (clamped). */
  private double currentFraction(final long completedUnits) {
    return Math.max(0.0, Math.min((double) completedUnits / totalWork, 1.0));
  }
}
