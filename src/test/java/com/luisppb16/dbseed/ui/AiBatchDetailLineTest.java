/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.luisppb16.dbseed.db.AiBatchProgress;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.junit.jupiter.api.Test;

class AiBatchDetailLineTest {

  /** Position of the stage caption inside the fixed-slot row, counted from the left. */
  private static final int STAGE_CAPTION_INDEX = 6;

  /** Position of the stage separator inside the fixed-slot row, counted from the left. */
  private static final int STAGE_SEPARATOR_INDEX = 8;

  /** The fixed-slot row: first card of the panel that stacks both renderings. */
  private static JPanel slotsPanelOf(final AiBatchDetailLine line) {
    return (JPanel) line.component().getComponent(0);
  }

  /** The right-aligned slots: the ones holding a number. */
  private static List<JLabel> numericSlotsOf(final AiBatchDetailLine line) {
    final List<JLabel> digits = new ArrayList<>();
    for (final JLabel slot : slotsOf(line)) {
      if (slot.getHorizontalAlignment() == SwingConstants.RIGHT) {
        digits.add(slot);
      }
    }
    return digits;
  }

  /** Laid-out width of every slot, left to right. */
  private static List<Integer> widthsOf(final JPanel row) {
    return componentsOf(row).stream().map(Component::getWidth).toList();
  }

  /** Laid-out x position of every slot, left to right. */
  private static List<Integer> positionsOf(final JPanel row) {
    return componentsOf(row).stream().map(Component::getX).toList();
  }

  /** Preferred width of every slot, left to right. */
  private static List<Integer> preferredWidthsOf(final JPanel row) {
    return componentsOf(row).stream().map(component -> component.getPreferredSize().width).toList();
  }

  private static List<Component> componentsOf(final JPanel row) {
    return List.of(row.getComponents());
  }

  /** The plain text line: second card of the panel that stacks both renderings. */
  private static JLabel textLabelOf(final AiBatchDetailLine line) {
    return (JLabel) line.component().getComponent(1);
  }

  /** Text of every slot, left to right — what the user actually reads on the line. */
  private static String textOf(final AiBatchDetailLine line) {
    final StringBuilder text = new StringBuilder();
    for (final JLabel slot : slotsOf(line)) {
      text.append(slot.getText());
    }
    return text.toString();
  }

  /** Frozen preferred size of every slot, left to right. */
  private static List<Dimension> slotSizes(final AiBatchDetailLine line) {
    final List<Dimension> sizes = new ArrayList<>();
    for (final JLabel slot : slotsOf(line)) {
      sizes.add(slot.getPreferredSize());
    }
    return sizes;
  }

  /** Frozen preferred size of the word slots, left to right. */
  private static List<Dimension> wordSlotSizes(final AiBatchDetailLine line) {
    final List<Dimension> sizes = new ArrayList<>();
    for (final JLabel slot : slotsOf(line)) {
      if (slot.getHorizontalAlignment() != SwingConstants.RIGHT) {
        sizes.add(slot.getPreferredSize());
      }
    }
    return sizes;
  }

  /** Frozen width of a slot reserving {@code digits} digits: the digits plus its padding. */
  private static int reserveFor(final JLabel slot, final int digits) {
    final Insets insets = slot.getInsets();
    return slot.getFontMetrics(slot.getFont()).stringWidth("0".repeat(digits))
        + insets.left
        + insets.right;
  }

  /** Width a slot needs to show the widest of {@code texts}, padding included. */
  private static int widthOf(final JLabel slot, final String... texts) {
    final Insets insets = slot.getInsets();
    int width = 0;
    for (final String text : texts) {
      width = Math.max(width, slot.getFontMetrics(slot.getFont()).stringWidth(text));
    }
    return width + insets.left + insets.right;
  }

  private static List<JLabel> slotsOf(final AiBatchDetailLine line) {
    final List<JLabel> slots = new ArrayList<>();
    for (final Component component : slotsPanelOf(line).getComponents()) {
      slots.add((JLabel) component);
    }
    return slots;
  }

  /** A single slot of the fixed-slot row, counted from the left. */
  private static JLabel slotAt(final AiBatchDetailLine line, final int index) {
    return slotsOf(line).get(index);
  }

  /**
   * A batch line for the {@code users.email} column: the layout tests only exercise the numbers,
   * and the column name travels along because the dialog labels its AI bars with it.
   */
  private static AiBatchProgress batch(
      final int rowsFrom,
      final int rowsTo,
      final int rowsTotal,
      final AiBatchProgress.Stage stage,
      final int done,
      final int total,
      final long waitingSeconds,
      final long lastBatchSeconds,
      final long etaSeconds) {
    return new AiBatchProgress(
        rowsFrom,
        rowsTo,
        rowsTotal,
        stage,
        done,
        total,
        waitingSeconds,
        lastBatchSeconds,
        etaSeconds,
        "users",
        "email");
  }

  @Test
  void render_numbersChange_keepsEverySlotAtItsPositionAndSize() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L));
    final List<Dimension> sizesBefore = slotSizes(line);

    // When — the same stage, only the digits move on
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 42, 50, 13L, -1L, -1L));

    // Then
    assertThat(textOf(line)).isEqualTo("rows 1-50/61 · values 42/50 · waiting 13s");
    assertThat(slotSizes(line)).isEqualTo(sizesBefore);
  }

  @Test
  void render_wordsChange_keepsTheNumberOfSlotsIdentical() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L));

    // When — an attempt fails and is retried
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.RETRY, 4, 5, 24L, -1L, -1L));

    // Then
    assertThat(textOf(line)).isEqualTo("rows 1-50/61 · retry 4/5 · waiting 24s");
    assertThat(slotsOf(line)).hasSize(14);
  }

  @Test
  void render_timingWithoutPreviousBatch_omitsTheTimingSegment() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // When
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.TIMING, 0, 0, -1L, -1L, 12L));

    // Then
    assertThat(textOf(line)).isEqualTo("rows 1-50/61");
  }

  @Test
  void render_timingWithPreviousBatch_rendersLastBatchAndEta() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // When
    line.render(batch(51, 61, 61, AiBatchProgress.Stage.TIMING, 0, 0, -1L, 3L, 4L));

    // Then
    assertThat(textOf(line)).isEqualTo("rows 51-61/61 · last batch 3s · ETA ~4s");
  }

  @Test
  void render_columnWithSmallNumbers_reservesOnlyTheDigitsTheyNeed() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // When — a 61-row column whose batches hold 50 values, 12 seconds waited so far
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L));

    // Then — every number reserves two digits, not the four that left a hole in front of it
    assertThat(numericSlotsOf(line))
        .allSatisfy(
            slot -> assertThat(slot.getPreferredSize().width).isEqualTo(reserveFor(slot, 2)));
  }

  @Test
  void render_columnWithWiderNumbers_growsEveryNumberSlotAndNoWordSlot() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 1, 50, 0L, -1L, -1L));
    final List<Dimension> wordSizesBefore = wordSlotSizes(line);

    // When — a column with far more rows than the reserved digits
    line.render(batch(1, 50, 12345678, AiBatchProgress.Stage.VALUES, 1, 50, 0L, -1L, -1L));

    // Then — the numbers' boxes grew and every word kept its exact size
    assertThat(textOf(line)).isEqualTo("rows 1-50/12345678 · values 1/50 · waiting 0s");
    assertThat(wordSlotSizes(line)).isEqualTo(wordSizesBefore);
    assertThat(numericSlotsOf(line))
        .allSatisfy(
            slot -> assertThat(slot.getPreferredSize().width).isGreaterThan(reserveFor(slot, 2)));
  }

  @Test
  void render_retryAfterValues_keepsEveryWordSlotAtItsPositionAndSize() {
    // Given — values streaming for the current attempt
    final AiBatchDetailLine line = new AiBatchDetailLine();
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 1L, -1L, -1L));
    final List<Dimension> sizesBefore = slotSizes(line);

    // When — the attempt fails, is retried, and values stream again
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.RETRY, 4, 5, 24L, -1L, -1L));
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 8, 50, 25L, -1L, -1L));

    // Then — no caption, separator or unit resized, so the line never moves sideways
    assertThat(slotSizes(line)).isEqualTo(sizesBefore);
  }

  @Test
  void render_anyStage_keepsEveryNumberInsideItsOwnPaddedSlot() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // When
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.RETRY, 4, 5, 24L, -1L, -1L));

    // Then — each number has blank space around it, so digits never touch the words
    final List<JLabel> digits = numericSlotsOf(line);
    assertThat(digits).hasSize(6);
    assertThat(digits)
        .allSatisfy(
            slot ->
                assertThat(slot.getPreferredSize().width)
                    .isGreaterThan(
                        slot.getFontMetrics(slot.getFont()).stringWidth(slot.getText())));
    assertThat(digits.stream().map(slot -> slot.getPreferredSize().width).distinct()).hasSize(1);
  }

  @Test
  void render_waitingCounterAppears_keepsEverySlotAtItsWidthAndPosition() {
    // Given — values streaming, laid out in a slot wider than the line itself
    final AiBatchDetailLine line = new AiBatchDetailLine();
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, -1L, -1L, -1L));
    final JPanel row = slotsPanelOf(line);
    row.setSize(1200, row.getPreferredSize().height);
    row.doLayout();
    final List<Integer> widthsBefore = widthsOf(row);
    final List<Integer> positionsBefore = positionsOf(row);

    // When — the stream stalls, the waiting segment shows up and its counter grows
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L));
    row.doLayout();

    // Then — the box has no leftover space to spread, so no slot was stretched and none moved
    assertThat(widthsBefore).isEqualTo(preferredWidthsOf(row));
    assertThat(widthsOf(row)).isEqualTo(widthsBefore);
    assertThat(positionsOf(row)).isEqualTo(positionsBefore);
  }

  @Test
  void render_streamingStage_sizesTheCaptionToTheStreamingWordsOnly() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // When — a values batch, whose caption is one of the two streaming captions
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L));

    // Then — the caption box fits " · values " and " · retry " and nothing more: sharing the width
    // the longer timing caption needs is what left a hole between the word and its numbers
    final JLabel caption = slotAt(line, STAGE_CAPTION_INDEX);
    assertThat(caption.getPreferredSize().width)
        .isEqualTo(widthOf(caption, " · values ", " · retry "))
        .isLessThan(widthOf(caption, " · last batch "));
    final JLabel separator = slotAt(line, STAGE_SEPARATOR_INDEX);
    assertThat(separator.getPreferredSize().width).isEqualTo(widthOf(separator, "/"));
  }

  @Test
  void render_timingStage_sizesTheCaptionAndSeparatorToTheirOwnWords() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // When — a batch that has just started, with the previous batch timed
    line.render(batch(51, 61, 61, AiBatchProgress.Stage.TIMING, 0, 0, -1L, 3L, 4L));

    // Then
    final JLabel caption = slotAt(line, STAGE_CAPTION_INDEX);
    assertThat(caption.getPreferredSize().width).isEqualTo(widthOf(caption, " · last batch "));
    final JLabel separator = slotAt(line, STAGE_SEPARATOR_INDEX);
    assertThat(separator.getPreferredSize().width).isEqualTo(widthOf(separator, "s · ETA ~"));
  }

  @Test
  void constructor_beforeAnyRender_everySlotIsAsTallAsTheFont() {
    // Given / When
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // Then — a height frozen from an empty label is zero, which leaves the whole line on screen but
    // zero pixels tall, that is, invisible
    assertThat(slotsOf(line))
        .allSatisfy(slot -> assertThat(slot.getPreferredSize().height).isPositive());
    assertThat(slotsPanelOf(line).getPreferredSize().height).isPositive();
  }

  @Test
  void render_missingProgress_failsFast() {
    assertThatNullPointerException()
        .isThrownBy(() -> new AiBatchDetailLine().render(null))
        .withMessage("Progress cannot be null");
  }

  @Test
  void showText_withoutProgress_emptyTextIsRenderedInsteadOfFailing() {
    // Given
    final AiBatchDetailLine line = new AiBatchDetailLine();

    // When — a phase without detail text
    line.showText(null);

    // Then
    assertThat(textLabelOf(line).getText()).isEmpty();
    assertThat(textLabelOf(line).isVisible()).isTrue();
  }

  @Test
  void render_afterText_swapsTheCardAndLaysTheSlotsOutInsideTheSharedSlot() {
    // Given — the plain text card is the one showing
    final AiBatchDetailLine line = new AiBatchDetailLine();
    line.showText("Preparing tables");
    final JPanel slot = line.component();
    assertThat(textLabelOf(line).isVisible()).isTrue();
    assertThat(slotsPanelOf(line).isVisible()).isFalse();

    // When — an AI batch takes over the slot
    line.render(batch(1, 50, 61, AiBatchProgress.Stage.VALUES, 7, 50, 12L, -1L, -1L));
    slot.setSize(600, slot.getPreferredSize().height);
    slot.doLayout();

    // Then — only one card is visible and the fixed-slot row is laid out: it used to be left out of
    // the layout entirely, which painted an empty line
    assertThat(slotsPanelOf(line).isVisible()).isTrue();
    assertThat(textLabelOf(line).isVisible()).isFalse();
    assertThat(slotsPanelOf(line).getWidth()).isGreaterThan(0);
    assertThat(slotsPanelOf(line).getHeight()).isGreaterThan(0);
  }
}
