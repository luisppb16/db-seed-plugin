/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Locale;
import java.util.Objects;

/** Defines how a configured self-referencing FK chain should terminate. */
public enum CircularReferenceTerminationMode {
  CLOSE_CYCLE("Close cycle"),
  NULL_FK("End with NULL");

  private final String displayName;

  CircularReferenceTerminationMode(final String displayName) {
    this.displayName = Objects.requireNonNull(displayName, "Display name cannot be null");
  }

  public static CircularReferenceTerminationMode fromPersistedValue(final String value) {
    if (Objects.isNull(value) || value.isBlank()) {
      return CLOSE_CYCLE;
    }

    try {
      return CircularReferenceTerminationMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException ignored) {
      return CLOSE_CYCLE;
    }
  }

  @Override
  public String toString() {
    return displayName;
  }
}
