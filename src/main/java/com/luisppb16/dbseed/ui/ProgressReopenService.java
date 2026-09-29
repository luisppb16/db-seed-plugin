/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.project.Project;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Project-level state of the progress dialog while it is hidden in the background: it keeps the
 * action that brings the window back, so the status bar entry can offer it without holding the
 * dialog itself. The state is empty whenever the window is on screen or the generation is over.
 */
public final class ProgressReopenService {

  private final AtomicReference<Runnable> reopenAction = new AtomicReference<>();

  /**
   * @param project project the state belongs to
   */
  public ProgressReopenService(final Project project) {
    Objects.requireNonNull(project, "Project cannot be null");
  }

  public static ProgressReopenService getInstance(final Project project) {
    Objects.requireNonNull(project, "Project cannot be null");
    return Objects.requireNonNull(
        project.getService(ProgressReopenService.class),
        "Progress reopen service is not registered");
  }

  /**
   * @return whether the progress window is hidden in the background
   */
  public boolean isHidden() {
    return Objects.nonNull(reopenAction.get());
  }

  /** Remembers how to bring the hidden window back. */
  public void hide(final Runnable action) {
    reopenAction.set(Objects.requireNonNull(action, "Reopen action cannot be null"));
  }

  /** Brings the hidden window back, at most once. */
  public void reopen() {
    final Runnable action = reopenAction.getAndSet(null);
    if (Objects.nonNull(action)) {
      action.run();
    }
  }

  /** Forgets the reapertura: the window is on screen again, or the generation ended. */
  public void show() {
    reopenAction.set(null);
  }
}
