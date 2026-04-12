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
  private final long totalWork;
  private final AtomicLong completed = new AtomicLong(0);

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
    indicator.setFraction(Math.min((double) now / totalWork, 1.0));
  }

  /** Convenience shorthand — advance by one unit. */
  public void advance() {
    advance(1);
  }

  /** Set the primary status text. */
  public void setText(final String text) {
    if (Objects.nonNull(indicator)) indicator.setText(text);
  }

  /** Set the secondary (detail) status text. */
  public void setText2(final String text) {
    if (Objects.nonNull(indicator)) indicator.setText2(text);
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
}
