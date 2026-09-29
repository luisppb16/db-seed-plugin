/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;

import com.intellij.notification.Notification;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.db.AiBatchProgress;
import com.luisppb16.dbseed.db.GenerationProgressListener;
import com.luisppb16.dbseed.util.NotificationHelper;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractAction;
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

  /** Horizontal scale applied to the natural width of the bars. */
  private static final int WIDTH_FACTOR = 2;

  private final AtomicReference<ProgressIndicator> indicatorRef;
  private final GenerationProgressModel model;
  private final Project project;

  /** State that hands the status bar entry the way back to a window hidden in the background. */
  private final ProgressReopenService reopenService;

  /** Detail slot of the header: the fixed-slot AI batch line, or the plain text line. */
  private final AiBatchDetailLine detailLine = new AiBatchDetailLine();

  /** Balloon offering to bring the dialog back; {@code null} while the dialog is on screen. */
  private Notification backgroundNotification;

  private JPanel centerPanel;
  private JBLabel phaseLabel;
  private JProgressBar generalBar;
  private JProgressBar tablesBar;
  private JProgressBar aiColumnsBar;
  private JProgressBar aiValuesBar;
  private JBLabel tablesCountLabel;
  private JBLabel aiColumnsCountLabel;
  private JBLabel aiValuesCountLabel;
  private JPanel aiColumnsRow;
  private JPanel aiValuesRow;

  /** Visibility last applied to the AI rows; the rows are built visible, hence the {@code true}. */
  private boolean lastAiVisible = true;

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
    this.project = project;
    this.reopenService = ProgressReopenService.getInstance(project);
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

  /** Closes the dialog once (guarded for double-close), retiring the balloon it may have left. */
  public void closeSafely() {
    if (isDisposed()) {
      return;
    }
    expireBackgroundNotification();
    reopenService.show();
    updateStatusBarWidget();
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
    return new Action[] {new BackgroundAction(), getCancelAction()};
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
    // The first line names the phase only; the AI bars below carry the column being generated.
    phaseLabel.setText(Objects.requireNonNullElse(model.getPhaseText(), ""));

    // While a batch is in flight the detail slot renders into fixed slots (only digits change);
    // every other phase uses the plain text line of the same slot. The model hands the record out
    // every 2 seconds at most, so the digits never move faster than the eye can read.
    final AiBatchProgress batchProgress = model.batchLineForRender();
    if (Objects.nonNull(batchProgress)) {
      detailLine.render(batchProgress);
    } else {
      detailLine.showText(model.getDetailText());
    }
    // Both AI bars name the column of the batch in flight; with no batch running they show counts.
    final String aiColumnLabel =
        Objects.nonNull(batchProgress) ? batchProgress.columnLabel() : null;

    generalBar.setValue(percentageOf(model.getGeneralFraction()));

    tablesBar.setValue(percentageOf(ratioOf(model.getTablesDone(), model.getTablesTotal())));
    tablesCountLabel.setText(
        rowLabel(model.getTablesDone(), model.getTablesTotal(), model.getLastTableName()));

    final boolean showAi = model.isAiPhaseVisible();
    if (lastAiVisible != showAi) {
      lastAiVisible = showAi;
      aiColumnsRow.setVisible(showAi);
      aiValuesRow.setVisible(showAi);
      centerPanel.revalidate();
      centerPanel.repaint();
    }
    if (!showAi) {
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
        rowLabel(model.getAiColumnsDone(), model.getAiColumnsTotal(), aiColumnLabel));

    aiValuesBar.setIndeterminate(false);
    aiValuesBar.setValue(percentageOf(ratioOf(model.getAiValuesDone(), model.getAiValuesTotal())));
    aiValuesCountLabel.setText(
        rowLabel(model.getAiValuesDone(), model.getAiValuesTotal(), aiColumnLabel));
  }

  /**
   * EDT-only: hides the window without cancelling or disposing it ({@code close()} would dispose
   * the dialog for good) and leaves a balloon and a status bar entry to bring it back. The
   * generation keeps running and the IDE progress widget keeps showing its progress.
   */
  private void hideToBackground() {
    getWindow().setVisible(false);
    reopenService.hide(this::reopen);
    updateStatusBarWidget();
    backgroundNotification =
        NotificationHelper.notifyWithAction(
            project,
            APP_NAME.getValue(),
            "Seed generation is still running in the background.",
            "Show progress",
            this::reopen);
  }

  /** EDT-only: brings the hidden window back to the front and retires its balloon. */
  private void reopen() {
    if (isDisposed()) {
      return;
    }
    expireBackgroundNotification();
    reopenService.show();
    updateStatusBarWidget();
    final Window window = getWindow();
    window.setVisible(true);
    window.toFront();
  }

  /** Retires the balloon offering to reopen the dialog, when one is still up. */
  private void expireBackgroundNotification() {
    if (Objects.nonNull(backgroundNotification)) {
      backgroundNotification.expire();
      backgroundNotification = null;
    }
  }

  /**
   * Asks the platform to re-evaluate whether the status bar entry must be there: it exists only
   * while the window is hidden in the background, so the same call adds it and retires it.
   */
  private void updateStatusBarWidget() {
    final StatusBarWidgetsManager widgetManager = project.getService(StatusBarWidgetsManager.class);
    if (Objects.nonNull(widgetManager)) {
      widgetManager.updateWidget(ProgressStatusBarWidgetFactory.class);
    }
  }

  private JComponent buildCenterPanel() {
    centerPanel = new JPanel(new BorderLayout(0, 10));
    final JPanel header = new JPanel(new BorderLayout(0, 2));
    phaseLabel = new JBLabel();
    // A single child in the SOUTH slot: the detail slot stacks both renderings as cards, since a
    // BorderLayout only ever lays out the last child added to a region.
    header.add(phaseLabel, BorderLayout.NORTH);
    header.add(detailLine.component(), BorderLayout.SOUTH);
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
    // Only the bars are widened: their tracks are the part that uses the room. The detail line,
    // with
    // every slot of its widest stage reserved, is taken at its own width — doubling it made the
    // dialog twice as wide as before.
    final Dimension natural = centerPanel.getPreferredSize();
    final Dimension bars = barsPanel.getPreferredSize();
    centerPanel.setPreferredSize(
        new Dimension(Math.max(bars.width * WIDTH_FACTOR, natural.width), natural.height));
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

  /** Sends the dialog to the background, leaving a balloon that brings it back. */
  private final class BackgroundAction extends AbstractAction {
    private BackgroundAction() {
      super("Background");
      putValue(MNEMONIC_KEY, KeyEvent.VK_B);
    }

    @Override
    public void actionPerformed(final ActionEvent event) {
      hideToBackground();
    }
  }
}
