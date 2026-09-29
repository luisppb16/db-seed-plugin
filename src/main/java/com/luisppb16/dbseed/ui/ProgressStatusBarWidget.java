/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;
import static com.luisppb16.dbseed.model.Constant.PROGRESS_WIDGET_ID;

import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * Status bar entry that brings the seed generation progress window back while it is hidden in the
 * background. It carries no progress figures: the window and the IDE progress widget own those.
 */
public final class ProgressStatusBarWidget
    implements StatusBarWidget, StatusBarWidget.TextPresentation {

  private final ProgressReopenService reopenService;

  ProgressStatusBarWidget(final ProgressReopenService reopenService) {
    this.reopenService = Objects.requireNonNull(reopenService, "Reopen service cannot be null");
  }

  @Override
  public String ID() {
    return PROGRESS_WIDGET_ID.getValue();
  }

  @Override
  public WidgetPresentation getPresentation() {
    return this;
  }

  @Override
  public String getText() {
    return APP_NAME.getValue();
  }

  @Override
  public float getAlignment() {
    return Component.RIGHT_ALIGNMENT;
  }

  @Override
  public String getTooltipText() {
    return "Click to bring the seed generation progress window back.";
  }

  @Override
  public Consumer<MouseEvent> getClickConsumer() {
    return event -> reopenService.reopen();
  }
}
