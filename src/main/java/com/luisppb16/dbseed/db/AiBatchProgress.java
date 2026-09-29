/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import java.util.Objects;

/**
 * Immutable record carrying the raw numbers of the AI batch detail line of the progress dialog,
 * together with the column the batch belongs to.
 *
 * <p>The line ({@code rows 1-50/61 · retry 4/5 · waiting 24s}) is refreshed dozens of times per
 * second — once per streamed value and once every {@code AI_AWAIT_POLL_MILLIS} while waiting on the
 * server. Sending it as a pre-formatted string forced the dialog to replace and re-layout the whole
 * sentence on every refresh. This record instead publishes only the numbers, so the dialog can keep
 * every static word ({@code rows}, {@code values}, {@code retry}, {@code waiting}, separators and
 * units) pinned to a fixed position and update nothing but the digits.
 *
 * <p>{@link #format()} renders the equivalent plain text and is used for the IDE progress widget,
 * which takes a single string. Both renderings carry exactly the same information.
 *
 * @param rowsFrom first row of the current batch (1-based, inclusive)
 * @param rowsTo last row of the current batch (1-based, inclusive)
 * @param rowsTotal total rows of the column being generated
 * @param stage which middle segment the numbers belong to
 * @param done completed items of the middle segment (values streamed, or retries used)
 * @param total total items of the middle segment (values requested, or max retries)
 * @param waitingSeconds seconds spent waiting on the server; negative when not waiting
 * @param lastBatchSeconds duration of the previous batch in seconds; negative when unknown
 * @param etaSeconds estimated remaining seconds from the rolling batch average
 * @param tableName name of the table owning the column of this batch
 * @param columnName name of the column of this batch
 */
public record AiBatchProgress(
    int rowsFrom,
    int rowsTo,
    int rowsTotal,
    Stage stage,
    int done,
    int total,
    long waitingSeconds,
    long lastBatchSeconds,
    long etaSeconds,
    String tableName,
    String columnName) {

  public AiBatchProgress {
    Objects.requireNonNull(stage, "Stage cannot be null");
    Objects.requireNonNull(tableName, "Table name cannot be null");
    Objects.requireNonNull(columnName, "Column name cannot be null");
  }

  /**
   * Renders the column this batch belongs to, as {@code table.column}. The dialog labels its AI
   * bars with it, so every name on screen refers to the batch that is actually being generated.
   *
   * @return the qualified name of the column of this batch
   */
  public String columnLabel() {
    return tableName + "." + columnName;
  }

  /**
   * Renders the plain-text detail line, byte-compatible with the strings published before this
   * record existed, so the IDE progress widget shows exactly the same text as always.
   *
   * @return the formatted detail line
   */
  public String format() {
    final StringBuilder text =
        new StringBuilder("rows ")
            .append(rowsFrom)
            .append("-")
            .append(rowsTo)
            .append("/")
            .append(rowsTotal);
    switch (stage) {
      case TIMING -> appendTiming(text);
      case VALUES -> text.append(" · values ").append(done).append("/").append(total);
      case RETRY -> text.append(" · retry ").append(done).append("/").append(total);
    }
    if (waitingSeconds >= 0) {
      text.append(" · waiting ").append(waitingSeconds).append("s");
    }
    return text.toString();
  }

  /** Appends the batch-timing segment, which is omitted while no batch has finished yet. */
  private void appendTiming(final StringBuilder text) {
    if (lastBatchSeconds >= 0) {
      text.append(" · last batch ")
          .append(lastBatchSeconds)
          .append("s · ETA ~")
          .append(etaSeconds)
          .append("s");
    }
  }

  /** Middle segment of the detail line: batch timing, streamed values, or retry attempts. */
  public enum Stage {
    /** Batch just started: {@code last batch Ns · ETA ~Ms}. */
    TIMING,
    /** Values arriving from the stream: {@code values x/N}. */
    VALUES,
    /** Attempt failed and is being retried: {@code retry r/5}. */
    RETRY
  }
}
