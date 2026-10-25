/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.util;

import com.intellij.util.ExceptionUtil;

/** Formats the user-facing message of a nested exception. */
public final class Errors {

  private Errors() {}

  public static String messageOf(final Throwable exception) {
    final Throwable cause = ExceptionUtil.getRootCause(exception);
    return ExceptionUtil.getNonEmptyMessage(cause, cause.getClass().getSimpleName());
  }
}
