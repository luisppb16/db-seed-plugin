/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.db.AiBatchProgress;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Detail slot of the dialog header: either the fixed-slot AI batch line or a plain text line.
 *
 * <p>The line ({@code rows 1-50/61 · retry 4/5 · waiting 24s}) is refreshed once per streamed value
 * and once every poll while waiting on the server. Rendering it as a single replaced label made the
 * whole sentence jump on every refresh. Here every word is an immutable label and every number
 * lives in its own right-aligned slot whose width is frozen at construction: a refresh can only
 * change the digits inside a slot, never the position of any element.
 *
 * <p>Both renderings are stacked in a {@link CardLayout} — the layout meant for showing one child
 * at a time — so the swap is a card change and the slot keeps the height of the tallest card.
 *
 * <p>Not thread-safe: render on the EDT.
 */
final class AiBatchDetailLine {

  /** Card holding the fixed-slot AI batch line. */
  private static final String AI_CARD = "ai";

  /** Card holding the plain text line used by every other phase. */
  private static final String TEXT_CARD = "text";

  /**
   * Fewest digits ever reserved for a number: the elapsed counters have no known bound up front.
   */
  private static final int MIN_NUMERIC_DIGITS = 2;

  /**
   * Blank space kept on both sides of every number, so digits never touch the words around them.
   */
  private static final int DIGIT_GAP = 5;

  /** Caption of the values stage. */
  private static final String CAPTION_VALUES = " · values ";

  /** Caption of the retry stage, which shows the same counters as the values stage. */
  private static final String CAPTION_RETRY = " · retry ";

  /** Caption of the timing stage. */
  private static final String CAPTION_TIMING = " · last batch ";

  /** Separator between the two numbers of the streaming stages. */
  private static final String SEPARATOR_STREAMING = "/";

  /** Words separating the two numbers of the timing stage. */
  private static final String SEPARATOR_TIMING = "s · ETA ~";

  /** Caption of the waiting segment, the longest it can ever be (it is blank when not waiting). */
  private static final String WAITING_CAPTION = " · waiting ";

  /** Unit of the seconds segments. */
  private static final String UNIT_SECONDS = "s";

  private final JPanel cardPanel = new JPanel(new CardLayout());
  private final JPanel slots = new JPanel();
  private final JBLabel textLabel = new JBLabel();
  private final JBLabel rowsFrom = numericLabel();
  private final JBLabel rowsTo = numericLabel();
  private final JBLabel rowsTotal = numericLabel();
  private final JBLabel stageCaption = stageLabel();
  private final JBLabel stageDone = numericLabel();
  private final JBLabel stageSeparator = stageLabel();
  private final JBLabel stageTotal = numericLabel();
  private final JBLabel stageUnit = stageLabel();
  private final JBLabel waitingCaption = wordLabel(WAITING_CAPTION);
  private final JBLabel waitingSeconds = numericLabel();
  private final JBLabel waitingUnit = wordLabel(UNIT_SECONDS);

  /** The number-holding slots, in display order. */
  private final List<JBLabel> numericSlots =
      List.of(rowsFrom, rowsTo, rowsTotal, stageDone, stageTotal, waitingSeconds);

  private String shownCard = TEXT_CARD;

  AiBatchDetailLine() {
    slots.setLayout(new BoxLayout(slots, BoxLayout.X_AXIS));
    slots.add(fixedWord("rows "));
    slots.add(rowsFrom);
    slots.add(fixedWord("-"));
    slots.add(rowsTo);
    slots.add(fixedWord("/"));
    slots.add(rowsTotal);
    slots.add(stageCaption);
    slots.add(stageDone);
    slots.add(stageSeparator);
    slots.add(stageTotal);
    slots.add(stageUnit);
    slots.add(waitingCaption);
    slots.add(waitingSeconds);
    slots.add(waitingUnit);
    cardPanel.add(slots, AI_CARD);
    cardPanel.add(textLabel, TEXT_CARD);
    // CardLayout shows the first card added, so the initial card is selected explicitly.
    ((CardLayout) cardPanel.getLayout()).show(cardPanel, TEXT_CARD);
  }

  /** Freezes a word label to the widest of the texts it can ever show in the current stage. */
  private static void freezeWords(final JBLabel label, final String... texts) {
    final FontMetrics metrics = label.getFontMetrics(label.getFont());
    final Insets insets = label.getInsets();
    int width = 0;
    for (final String text : texts) {
      width = Math.max(width, metrics.stringWidth(text));
    }
    final int frozen = width + insets.left + insets.right;
    if (frozen != label.getPreferredSize().width) {
      freeze(label, frozen);
    }
  }

  private static int digitsOf(final long value) {
    return String.valueOf(Math.abs(value)).length();
  }

  /** Sets a numeric slot to the width of {@code digits} digits plus its padding. */
  private static void reserve(final JBLabel label, final int digits) {
    final Insets insets = label.getInsets();
    final int width =
        label.getFontMetrics(label.getFont()).stringWidth("0".repeat(digits))
            + insets.left
            + insets.right;
    if (width != label.getPreferredSize().width) {
      freeze(label, width);
    }
  }

  /**
   * A right-aligned numeric slot, padded on both sides so neither a longer number nor the digits
   * themselves can move the words around it.
   */
  private static JBLabel numericLabel() {
    final JBLabel label = new JBLabel();
    label.setHorizontalAlignment(SwingConstants.RIGHT);
    label.setBorder(JBUI.Borders.empty(0, DIGIT_GAP));
    reserve(label, MIN_NUMERIC_DIGITS);
    return label;
  }

  /**
   * A word label that never changes text, frozen to exactly that text.
   *
   * @param text the only text the label will ever show
   */
  private static JBLabel fixedWord(final String text) {
    final JBLabel label = wordLabel(text);
    label.setText(text);
    return label;
  }

  /**
   * A word label frozen to the longest text it can ever show. Stage captions, separators and units
   * change text mid-run; without a frozen width every such change resized the label and pushed the
   * rest of the line sideways.
   */
  private static JBLabel wordLabel(final String widestText) {
    final JBLabel label = new JBLabel();
    final Insets insets = label.getInsets();
    freeze(
        label,
        label.getFontMetrics(label.getFont()).stringWidth(widestText) + insets.left + insets.right);
    return label;
  }

  /** A stage word, re-frozen on every render by {@link #refreshWords} to the current stage only. */
  private static JBLabel stageLabel() {
    final JBLabel label = new JBLabel();
    freeze(label, 0);
    return label;
  }

  /**
   * Gives a label a fixed width and forbids the layout from stretching or shrinking it: with a
   * maximum size equal to the preferred one, {@link BoxLayout} hands out exactly that width and has
   * no leftover space to spread among its children. Left to the default maximum size, the leftover
   * was shared between the labels and the shares changed whenever a group appeared or disappeared,
   * which is what made the waiting segment slide sideways.
   */
  private static void freeze(final JBLabel label, final int width) {
    final FontMetrics metrics = label.getFontMetrics(label.getFont());
    final Insets insets = label.getInsets();
    // The height comes from the font, never from the label's own preferred size: an empty label
    // reports a zero height, and freezing it left the whole line zero pixels tall — present in the
    // layout but invisible on screen.
    final Dimension size = new Dimension(width, metrics.getHeight() + insets.top + insets.bottom);
    label.setPreferredSize(size);
    label.setMinimumSize(size);
    label.setMaximumSize(size);
  }

  /** The component to place in the dialog header. */
  JPanel component() {
    return cardPanel;
  }

  /**
   * Shows the fixed-slot line with the numbers of the current batch. Only digits ever change: the
   * words are the same labels with the same frozen size, so nothing moves on screen.
   *
   * @param progress the batch numbers to display
   */
  void render(final AiBatchProgress progress) {
    Objects.requireNonNull(progress, "Progress cannot be null");
    showCard(AI_CARD);
    refreshWords(progress.stage());
    refreshReserves(progress);
    setSlot(rowsFrom, progress.rowsFrom());
    setSlot(rowsTo, progress.rowsTo());
    setSlot(rowsTotal, progress.rowsTotal());
    switch (progress.stage()) {
      case TIMING -> renderTiming(progress);
      case VALUES -> renderStage(CAPTION_VALUES, progress);
      case RETRY -> renderStage(CAPTION_RETRY, progress);
    }
    final boolean waiting = progress.waitingSeconds() >= 0;
    waitingCaption.setText(waiting ? WAITING_CAPTION : "");
    waitingUnit.setText(waiting ? UNIT_SECONDS : "");
    if (waiting) {
      setSlot(waitingSeconds, progress.waitingSeconds());
    } else {
      setSlot(waitingSeconds, "");
    }
  }

  /** Shows a free-form detail text instead of the AI batch line. */
  void showText(final String text) {
    textLabel.setText(Objects.requireNonNullElse(text, ""));
    showCard(TEXT_CARD);
  }

  /** Selects which of the two stacked renderings is shown; a no-op while it is already the one. */
  private void showCard(final String card) {
    if (card.equals(shownCard)) {
      return;
    }
    shownCard = card;
    ((CardLayout) cardPanel.getLayout()).show(cardPanel, card);
  }

  /** Renders the batch-timing stage; the first batch of a column has no timing to show yet. */
  private void renderTiming(final AiBatchProgress progress) {
    if (progress.lastBatchSeconds() < 0) {
      blankStage();
      return;
    }
    stageCaption.setText(CAPTION_TIMING);
    stageSeparator.setText(SEPARATOR_TIMING);
    stageUnit.setText(UNIT_SECONDS);
    setSlot(stageDone, progress.lastBatchSeconds());
    setSlot(stageTotal, progress.etaSeconds());
  }

  /** Renders the values and retry stages, which share the {@code caption done/total} shape. */
  private void renderStage(final String caption, final AiBatchProgress progress) {
    stageCaption.setText(caption);
    stageSeparator.setText(SEPARATOR_STREAMING);
    stageUnit.setText("");
    setSlot(stageDone, progress.done());
    setSlot(stageTotal, progress.total());
  }

  private void blankStage() {
    stageCaption.setText("");
    stageSeparator.setText("");
    stageUnit.setText("");
    setSlot(stageDone, "");
    setSlot(stageTotal, "");
  }

  /** Writes a slot, widening it only when a longer text does not fit the reserved width. */
  private void setSlot(final JBLabel label, final String text) {
    final Insets insets = label.getInsets();
    final int needed =
        label.getFontMetrics(label.getFont()).stringWidth(text) + insets.left + insets.right;
    if (needed > label.getPreferredSize().width) {
      freeze(label, needed);
    }
    label.setText(text);
  }

  private void setSlot(final JBLabel label, final long value) {
    setSlot(label, String.valueOf(value));
  }

  /**
   * Sizes the words of the current stage to exactly the texts that stage can show. One frozen width
   * shared by every stage made the streaming captions carry the width of the longer timing caption,
   * which left a visible hole between the word and its numbers.
   */
  private void refreshWords(final AiBatchProgress.Stage stage) {
    if (stage == AiBatchProgress.Stage.TIMING) {
      freezeWords(stageCaption, CAPTION_TIMING);
      freezeWords(stageSeparator, SEPARATOR_TIMING);
      freezeWords(stageUnit, UNIT_SECONDS);
      return;
    }
    freezeWords(stageCaption, CAPTION_VALUES, CAPTION_RETRY);
    freezeWords(stageSeparator, SEPARATOR_STREAMING);
    freezeWords(stageUnit, "");
  }

  /**
   * Reserves, for every number of the line, exactly the digits the current batch needs: the widest
   * among its totals and its counters. Reserving a fixed four digits left a hole in front of every
   * short number; reserving nothing let a growing number push its neighbours sideways.
   */
  private void refreshReserves(final AiBatchProgress progress) {
    final int digits =
        IntStream.of(
                MIN_NUMERIC_DIGITS,
                digitsOf(progress.rowsTotal()),
                digitsOf(progress.total()),
                digitsOf(progress.waitingSeconds()),
                digitsOf(progress.lastBatchSeconds()),
                digitsOf(progress.etaSeconds()))
            .max()
            .orElse(MIN_NUMERIC_DIGITS);
    numericSlots.forEach(slot -> reserve(slot, digits));
  }
}
