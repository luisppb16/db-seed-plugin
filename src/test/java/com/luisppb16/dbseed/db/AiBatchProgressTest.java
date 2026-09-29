/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AiBatchProgressTest {

  /** Every shape the AI batch line takes, with the plain text it must render. */
  private static Stream<Arguments> batchTexts() {
    return Stream.of(
        // Batch just started, nothing timed yet.
        Arguments.of(
            new AiBatchProgress(
                1, 50, 61, AiBatchProgress.Stage.TIMING, 0, 0, -1L, -1L, 12L, "users", "email"),
            "rows 1-50/61"),
        // Batch just started, with the previous batch average.
        Arguments.of(
            new AiBatchProgress(
                51, 61, 61, AiBatchProgress.Stage.TIMING, 0, 0, -1L, 3L, 4L, "users", "email"),
            "rows 51-61/61 · last batch 3s · ETA ~4s"),
        // Values arriving from the stream.
        Arguments.of(
            new AiBatchProgress(
                1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L, "users", "email"),
            "rows 1-50/61 · values 7/50 · waiting 12s"),
        // Values arriving with the whole seconds not yet elapsed.
        Arguments.of(
            new AiBatchProgress(
                1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 0L, -1L, -1L, "users", "email"),
            "rows 1-50/61 · values 7/50 · waiting 0s"),
        // Retrying a failed attempt.
        Arguments.of(
            new AiBatchProgress(
                1, 50, 61, AiBatchProgress.Stage.RETRY, 4, 5, 24L, -1L, -1L, "users", "email"),
            "rows 1-50/61 · retry 4/5 · waiting 24s"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("batchTexts")
  void format_rendersThePlainDetailLine(final AiBatchProgress progress, final String expected) {
    // When
    final String formatted = progress.format();

    // Then
    assertThat(formatted).isEqualTo(expected);
  }

  @Test
  void constructor_nullStage_failsFast() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new AiBatchProgress(1, 50, 61, null, 0, 0, -1L, -1L, -1L, "users", "email"))
        .withMessage("Stage cannot be null");
  }

  @Test
  void columnLabel_qualifiesTheColumnOfTheBatch() {
    // Given
    final AiBatchProgress progress =
        new AiBatchProgress(
            1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L, "users", "email");

    // When
    final String label = progress.columnLabel();

    // Then
    assertThat(label).isEqualTo("users.email");
  }

  @Test
  void format_ignoresTheColumnTheBatchCarries() {
    // Given
    final AiBatchProgress email =
        new AiBatchProgress(
            1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L, "users", "email");
    final AiBatchProgress note =
        new AiBatchProgress(
            1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L, "orders", "note");

    // Then
    assertThat(email.format()).isEqualTo(note.format());
    assertThat(email.format()).isEqualTo("rows 1-50/61 · values 7/50 · waiting 12s");
  }

  @Test
  void constructor_nullTableName_failsFast() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new AiBatchProgress(
                    1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L, null, "email"))
        .withMessage("Table name cannot be null");
  }

  @Test
  void constructor_nullColumnName_failsFast() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new AiBatchProgress(
                    1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L, "users", null))
        .withMessage("Column name cannot be null");
  }
}
