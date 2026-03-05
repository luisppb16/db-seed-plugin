/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.intellij.openapi.progress.ProgressIndicator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

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
public final class ProgressTracker {

  private final ProgressIndicator indicator;
  private final long totalWork;
  private final AtomicLong completed = new AtomicLong(0);
  private final AtomicLong phaseCompleted = new AtomicLong(0);
  private long phaseTotal = 0;
  private String currentPhase = "";
  private long phaseStartTime = 0;

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

  /**
   * Format time information: elapsed time and estimated remaining time.
   *
   * @param percent completion percentage (0-100)
   * @param elapsedMs elapsed milliseconds
   * @return formatted time string
   */
  private static String formatTimeInfo(final long percent, final long elapsedMs) {
    if (percent == 0) {
      return "| --:-- remaining";
    }
    final long totalEstimatedMs = (elapsedMs * 100) / percent;
    final long remainingMs = totalEstimatedMs - elapsedMs;
    final long elapsedSec = elapsedMs / 1000;
    final long remainingSec = remainingMs / 1000;
    return String.format(
        "| %d:%02d elapsed | %d:%02d remaining",
        elapsedSec / 60, elapsedSec % 60, remainingSec / 60, remainingSec % 60);
  }

  /**
   * Build a visual progress bar.
   *
   * @param percent completion percentage (0-100)
   * @return a visual progress bar string
   */
  private static String buildProgressBar(final long percent) {
    final int barLength = 25;
    final int filledLength = (int) ((percent * barLength) / 100);
    final StringBuilder bar = new StringBuilder("│");
    for (int i = 0; i < barLength; i++) {
      bar.append(i < filledLength ? "█" : "░");
    }
    bar.append("│");
    return bar.toString();
  }

  /** Returns {@code true} when no indicator is attached (progress is a no-op). */
  public boolean isNoOp() {
    return Objects.isNull(indicator);
  }

  /** Advance the completed counter by {@code units} and update the indicator fraction. */
  public void advance(final long units) {
    if (Objects.isNull(indicator)) return;
    final long now = completed.addAndGet(units);
    final long phaseNow = phaseCompleted.addAndGet(units);
    indicator.setFraction(Math.min((double) now / totalWork, 1.0));
    updateDetailedProgress(phaseNow);
  }

  /** Convenience shorthand — advance by one unit. */
  public void advance() {
    advance(1);
  }

  /** Set the primary status text. */
  public void setText(final String text) {
    if (Objects.nonNull(indicator)) indicator.setText(text);
  }

  /** Set the secondary (detail) status text with verbose phase information. */
  public void setText2(final String text) {
    if (Objects.nonNull(indicator)) indicator.setText2(text);
  }

  /**
   * Start a new phase with a total number of work units.
   *
   * @param phaseName the name of the phase (e.g., "Generating rows", "Validating constraints")
   * @param total the total number of work units in this phase
   */
  public void startPhase(final String phaseName, final long total) {
    this.currentPhase = Objects.requireNonNull(phaseName, "Phase name cannot be null");
    this.phaseTotal = Math.max(total, 1);
    this.phaseCompleted.set(0);
    this.phaseStartTime = System.currentTimeMillis();
    updateDetailedProgress(0);
  }

  /** Update the detailed progress display with current phase information. */
  private void updateDetailedProgress(final long phaseNow) {
    if (Objects.isNull(indicator) || currentPhase.isEmpty()) {
      return;
    }
    final long percent = phaseTotal > 0 ? (phaseNow * 100) / phaseTotal : 0;
    final String progressBar = buildProgressBar(percent);
    final double overallPercent = totalWork > 0 ? (completed.get() * 100.0) / totalWork : 0;
    final long elapsedMs = System.currentTimeMillis() - phaseStartTime;
    final String timeInfo = formatTimeInfo(percent, elapsedMs);
    indicator.setText2(
        String.format(
            "├ %s │ %d/%d (%d%%) %s [TOTAL: %.1f%%] %s",
            currentPhase, phaseNow, phaseTotal, percent, progressBar, overallPercent, timeInfo));
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

  /** Return the current phase name. */
  public String getCurrentPhase() {
    return currentPhase;
  }

  /** Return the number of completed units in the current phase. */
  public long getPhaseCompleted() {
    return phaseCompleted.get();
  }

  /** Return the total units for the current phase. */
  public long getPhaseTotal() {
    return phaseTotal;
  }
}
