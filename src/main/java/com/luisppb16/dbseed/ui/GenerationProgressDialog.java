/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.db.GenerationProgressListener;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import org.jetbrains.annotations.NotNull;

/**
 * Non-modal dialog with four live progress bars for the seed generation: overall (work-unit
 * fraction), tables, AI columns and streamed AI values. Subscribes through {@link
 * GenerationProgressModel}; Cancel cancels the IntelliJ progress indicator (the generation polls
 * {@code indicator.isCanceled()}), keeping the dialog open until the task unwinds.
 */
public final class GenerationProgressDialog extends DialogWrapper {

  private final AtomicReference<ProgressIndicator> indicatorRef;
  private final GenerationProgressModel model;
  private JPanel centerPanel;
  private JBLabel phaseLabel;
  private JBLabel detailLabel;
  private JProgressBar generalBar;
  private JProgressBar tablesBar;
  private JProgressBar aiColumnsBar;
  private JProgressBar aiValuesBar;
  private JBLabel tablesCountLabel;
  private JBLabel aiColumnsCountLabel;
  private JBLabel aiValuesCountLabel;
  private JPanel aiColumnsRow;
  private JPanel aiValuesRow;

  /**
   * @param project project used as dialog parent/scope
   * @param indicatorRef reference to the task indicator, set on the task's first line; Cancel
   *     clicks before it is set are safely ignored
   * @param aiExpected whether an AI phase is expected, so the AI bars pre-arm (indeterminate)
   *     during warm-up
   */
  public GenerationProgressDialog(
      final Project project,
      final AtomicReference<ProgressIndicator> indicatorRef,
      final boolean aiExpected) {
    super(project, false);
    Objects.requireNonNull(indicatorRef, "indicatorRef cannot be null");
    this.indicatorRef = indicatorRef;
    setModal(false);
    setTitle(APP_NAME.getValue() + " - Seed generation progress");
    this.model = new GenerationProgressModel(this::syncFromModel);
    if (aiExpected) {
      model.markAiPhaseExpected();
    }
    init();
  }

  private static double ratioOf(final long done, final long total) {
    return total > 0 ? (double) done / total : 0.0;
  }

  private static int percentageOf(final double fraction) {
    return (int) Math.min(100, Math.max(0, Math.round(fraction * 100)));
  }

  private static String rowLabel(final long done, final long total, final String current) {
    final String counts = done + "/" + total;
    return Objects.isNull(current) ? counts : counts + " - " + current;
  }

  /** The listener the generation pipeline pushes events through. */
  public GenerationProgressListener progressListener() {
    return model;
  }

  /** Closes the dialog once (guarded for double-close). */
  public void closeSafely() {
    if (isDisposed()) {
      return;
    }
    close(DialogWrapper.CANCEL_EXIT_CODE);
  }

  /**
   * Cancel routes to the indicator (poll-based cancellation picks it up) but keeps the dialog open
   * until the task unwinds; Esc and the window close button land here too.
   */
  @Override
  public void doCancelAction() {
    if (isDisposed()) {
      return;
    }
    final ProgressIndicator indicator = indicatorRef.get();
    if (Objects.nonNull(indicator)) {
      indicator.cancel();
    }
    setCancelButtonText("Cancelling...");
    final JButton button = getButton(getCancelAction());
    if (Objects.nonNull(button)) {
      button.setEnabled(false);
    }
    // Deliberately NOT calling super.doCancelAction(): the dialog stays open until the task
    // unwinds.
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {getCancelAction()};
  }

  @Override
  protected JComponent createCenterPanel() {
    return buildCenterPanel();
  }

  /** EDT-only: pushes the model state into the Swing components. */
  private void syncFromModel() {
    if (isDisposed()) {
      return;
    }
    phaseLabel.setText(Objects.requireNonNullElse(model.getPhaseText(), ""));
    detailLabel.setText(Objects.requireNonNullElse(model.getDetailText(), ""));

    generalBar.setValue(percentageOf(model.getGeneralFraction()));

    tablesBar.setValue(percentageOf(ratioOf(model.getTablesDone(), model.getTablesTotal())));
    tablesCountLabel.setText(
        rowLabel(model.getTablesDone(), model.getTablesTotal(), model.getLastTableName()));

    final boolean showAi = model.isAiPhaseVisible();
    aiColumnsRow.setVisible(showAi);
    aiValuesRow.setVisible(showAi);
    if (!showAi) {
      centerPanel.revalidate();
      centerPanel.repaint();
      return;
    }
    if (model.isAiPhaseIndeterminate()) {
      aiColumnsBar.setIndeterminate(true);
      aiValuesBar.setIndeterminate(true);
      aiColumnsCountLabel.setText("...");
      aiValuesCountLabel.setText("...");
      return;
    }
    aiColumnsBar.setIndeterminate(false);
    aiColumnsBar.setValue(
        percentageOf(ratioOf(model.getAiColumnsDone(), model.getAiColumnsTotal())));
    aiColumnsCountLabel.setText(
        rowLabel(model.getAiColumnsDone(), model.getAiColumnsTotal(), model.getLastAiColumnName()));

    aiValuesBar.setIndeterminate(false);
    aiValuesBar.setValue(percentageOf(ratioOf(model.getAiValuesDone(), model.getAiValuesTotal())));
    aiValuesCountLabel.setText(
        rowLabel(model.getAiValuesDone(), model.getAiValuesTotal(), model.getLastAiColumnName()));

    centerPanel.revalidate();
    centerPanel.repaint();
  }

  private JComponent buildCenterPanel() {
    centerPanel = new JPanel(new BorderLayout(0, 10));
    final JPanel header = new JPanel(new BorderLayout(0, 2));
    phaseLabel = new JBLabel();
    detailLabel = new JBLabel();
    header.add(phaseLabel, BorderLayout.NORTH);
    header.add(detailLabel, BorderLayout.SOUTH);
    centerPanel.add(header, BorderLayout.NORTH);

    final JPanel barsPanel = new JPanel(new GridLayout(0, 1, 0, 6));
    generalBar = new JProgressBar();
    barsPanel.add(barRow("Overall", generalBar, null));

    tablesBar = new JProgressBar();
    tablesCountLabel = new JBLabel();
    barsPanel.add(barRow("Tables", tablesBar, tablesCountLabel));

    aiColumnsBar = new JProgressBar();
    aiColumnsCountLabel = new JBLabel();
    aiColumnsRow = barRow("AI columns", aiColumnsBar, aiColumnsCountLabel);
    barsPanel.add(aiColumnsRow);

    aiValuesBar = new JProgressBar();
    aiValuesCountLabel = new JBLabel();
    aiValuesRow = barRow("AI values", aiValuesBar, aiValuesCountLabel);
    barsPanel.add(aiValuesRow);

    centerPanel.add(barsPanel, BorderLayout.CENTER);
    return centerPanel;
  }

  private JPanel barRow(final String caption, final JProgressBar bar, final JBLabel countLabel) {
    final JPanel row = new JPanel(new BorderLayout(8, 0));
    final JBLabel captionLabel = new JBLabel(caption);
    captionLabel.setPreferredSize(JBUI.size(120, captionLabel.getPreferredSize().height));
    row.add(captionLabel, BorderLayout.WEST);
    row.add(bar, BorderLayout.CENTER);
    if (Objects.nonNull(countLabel)) {
      row.add(countLabel, BorderLayout.EAST);
    }
    return row;
  }
}
