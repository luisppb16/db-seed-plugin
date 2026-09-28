/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.luisppb16.dbseed.db.GenerationProgressListener;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.Getter;

/**
 * Thread-safe progress state for the multi-bar progress dialog. Implements {@link
 * GenerationProgressListener} and merges every event into volatile state; a single coalesced flush
 * pushes the state to the Swing renderer via the injected {@code uiSync} runnable on the EDT (at
 * most one queued runnable per event burst).
 *
 * <p>Events carry absolute totals, so dropped intermediate events never corrupt state. {@code null}
 * texts keep the previous value. No Swing types are used here so the class stays unit-testable
 * headlessly (DialogWrapper needs a live IDE).
 */
@Getter
public final class GenerationProgressModel implements GenerationProgressListener {

  private final Runnable uiSync;
  private final Consumer<Runnable> edtDispatcher;
  private final AtomicBoolean flushPending = new AtomicBoolean(false);

  private volatile long aiValuesDone;
  private volatile long aiValuesTotal;
  private volatile int aiColumnsDone;
  private volatile int aiColumnsTotal;
  private volatile int tablesDone;
  private volatile int tablesTotal;
  private volatile double generalFraction;
  private volatile String phaseText;
  private volatile String detailText;
  private volatile String lastTableName;
  private volatile String lastAiColumnName;
  private volatile boolean aiPhaseVisible;
  private volatile boolean aiPhaseIndeterminate;

  /** Production constructor: flushes are marshaled to the EDT via {@link ApplicationManager}. */
  public GenerationProgressModel(final Runnable uiSync) {
    this(uiSync, GenerationProgressModel::defaultEdtDispatch);
  }

  /** Test constructor with an injectable EDT dispatcher. */
  GenerationProgressModel(final Runnable uiSync, final Consumer<Runnable> edtDispatcher) {
    this.uiSync = Objects.requireNonNull(uiSync, "uiSync cannot be null");
    this.edtDispatcher = Objects.requireNonNull(edtDispatcher, "edtDispatcher cannot be null");
  }

  /** Default dispatcher: EDT via invokeLater with any modality; inline when no Application. */
  private static void defaultEdtDispatch(final Runnable runnable) {
    final Application application = ApplicationManager.getApplication();
    if (Objects.isNull(application)) {
      runnable.run();
      return;
    }
    application.invokeLater(runnable, ModalityState.any());
  }

  /**
   * Pre-arms the AI bars (visible + indeterminate) for the warm-up window, before the AI phase
   * reports its totals.
   */
  public void markAiPhaseExpected() {
    aiPhaseVisible = true;
    aiPhaseIndeterminate = true;
    scheduleFlush();
  }

  @Override
  public void onTablesPhaseStarted(final int totalTables) {
    tablesTotal = totalTables;
    tablesDone = 0;
    scheduleFlush();
  }

  @Override
  public void onTableCompleted(
      final String tableName, final int tablesDone, final int tablesTotal) {
    this.tablesDone = tablesDone;
    this.tablesTotal = tablesTotal;
    lastTableName = tableName;
    scheduleFlush();
  }

  @Override
  public void onAiPhaseStarted(final long totalValues, final int totalColumns) {
    aiValuesTotal = totalValues;
    aiColumnsTotal = totalColumns;
    aiValuesDone = 0;
    aiColumnsDone = 0;
    aiPhaseVisible = true;
    aiPhaseIndeterminate = false;
    scheduleFlush();
  }

  @Override
  public void onAiPhaseSkipped() {
    aiPhaseVisible = false;
    scheduleFlush();
  }

  @Override
  public void onAiValue(final long valuesDone, final long valuesTotal) {
    aiValuesDone = Math.min(valuesDone, valuesTotal);
    aiValuesTotal = valuesTotal;
    scheduleFlush();
  }

  @Override
  public void onAiColumnCompleted(
      final String tableName,
      final String columnName,
      final int columnsDone,
      final int columnsTotal) {
    aiColumnsDone = columnsDone;
    aiColumnsTotal = columnsTotal;
    lastAiColumnName = tableName + "." + columnName;
    scheduleFlush();
  }

  @Override
  public void onGeneral(final double fraction, final String phaseText, final String detailText) {
    generalFraction = Math.max(0.0, Math.min(fraction, 1.0));
    if (Objects.nonNull(phaseText)) {
      this.phaseText = phaseText;
    }
    if (Objects.nonNull(detailText)) {
      this.detailText = detailText;
    }
    scheduleFlush();
  }

  private void scheduleFlush() {
    if (flushPending.compareAndSet(false, true)) {
      edtDispatcher.accept(this::flush);
    }
  }

  /**
   * Runs on the EDT (or inline in tests): clears the pending flag BEFORE syncing so events arriving
   * during the sync schedule a fresh flush instead of being lost.
   */
  private void flush() {
    flushPending.set(false);
    uiSync.run();
  }
}
