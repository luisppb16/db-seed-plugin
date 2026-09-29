/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

/**
 * SPI for observing the seed-generation progress at every granularity shown in the multi-bar
 * progress dialog: per streamed AI value, per AI column, per table, and the overall work-unit
 * fraction.
 *
 * <p>All events carry absolute running totals (never deltas), so consumers may drop intermediate
 * events (coalescing) without corrupting bar state. Events are fired from background threads (the
 * generation task thread and the AI column executor threads); implementations must be thread-safe
 * and marshal to the EDT themselves. {@code null} text arguments in {@link #onGeneral(double,
 * String, String)} mean "keep the previously displayed text" (merge semantics).
 */
public interface GenerationProgressListener {

  /** Listener that ignores every event. */
  GenerationProgressListener NO_OP =
      new GenerationProgressListener() {
        @Override
        public void onTablesPhaseStarted(final int totalTables) {
          // No-op.
        }

        @Override
        public void onTableCompleted(
            final String tableName, final int tablesDone, final int tablesTotal) {
          // No-op.
        }

        @Override
        public void onAiPhaseStarted(final long totalValues, final int totalColumns) {
          // No-op.
        }

        @Override
        public void onAiPhaseSkipped() {
          // No-op.
        }

        @Override
        public void onAiValue(final long valuesDone, final long valuesTotal) {
          // No-op.
        }

        @Override
        public void onAiColumnCompleted(
            final String tableName,
            final String columnName,
            final int columnsDone,
            final int columnsTotal) {
          // No-op.
        }

        @Override
        public void onGeneral(
            final double fraction, final String phaseText, final String detailText) {
          // No-op.
        }
      };

  /** The rows phase started: {@code totalTables} tables will be processed sequentially. */
  void onTablesPhaseStarted(int totalTables);

  /**
   * A table's rows are fully generated (phase 1), including empty and underproduced tables.
   *
   * @param tableName name of the table whose rows just completed
   * @param tablesDone absolute number of completed tables
   * @param tablesTotal absolute total number of tables
   */
  void onTableCompleted(String tableName, int tablesDone, int tablesTotal);

  /** The AI phase is about to run: {@code totalValues} AI values across {@code totalColumns}. */
  void onAiPhaseStarted(long totalValues, int totalColumns);

  /**
   * The AI phase will not run (no AI client, no valid AI columns or a failed warm-up). Idempotent
   * and fired at most once per generation.
   */
  void onAiPhaseSkipped();

  /**
   * Another AI value arrived from the streaming response; {@code valuesDone} is capped by the
   * producer at {@code valuesTotal}.
   */
  void onAiValue(long valuesDone, long valuesTotal);

  /**
   * An AI column task finished — success, failure and canceled columns all count here.
   *
   * @param tableName name of the table owning the column
   * @param columnName name of the finished column
   * @param columnsDone absolute number of finished AI columns
   * @param columnsTotal absolute total number of AI columns
   */
  void onAiColumnCompleted(String tableName, String columnName, int columnsDone, int columnsTotal);

  /**
   * The overall work-unit fraction changed (and/or the phase/detail texts changed); {@code null}
   * texts keep the previous values.
   */
  void onGeneral(double fraction, String phaseText, String detailText);

  /**
   * The AI batch detail line changed. Published as raw numbers (not as a formatted string) so
   * consumers can keep the static words of the line pinned and update only the digits; a {@code
   * null} value means the line no longer applies.
   */
  default void onAiBatchProgress(final AiBatchProgress progress) {
    // Consumers that only render text may ignore it.
  }
}
