/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static com.luisppb16.dbseed.model.Constant.APP_NAME;
import static com.luisppb16.dbseed.model.Constant.PROGRESS_WIDGET_ID;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;

/**
 * Factory of the status bar entry that brings the progress window back: the entry exists only while
 * the window is hidden in the background, and it cannot be switched off from the IDE settings.
 */
public final class ProgressStatusBarWidgetFactory implements StatusBarWidgetFactory {

  @Override
  public String getId() {
    return PROGRESS_WIDGET_ID.getValue();
  }

  @Override
  public String getDisplayName() {
    return APP_NAME.getValue() + " progress";
  }

  @Override
  public boolean isAvailable(final Project project) {
    return ProgressReopenService.getInstance(project).isHidden();
  }

  @Override
  public StatusBarWidget createWidget(final Project project) {
    return new ProgressStatusBarWidget(ProgressReopenService.getInstance(project));
  }

  @Override
  public boolean isConfigurable() {
    return false;
  }
}
