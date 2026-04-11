/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Defines how a configured self-referencing foreign key chain should terminate in the DBSeed
 * plugin.
 *
 * <p>This enum specifies the termination strategies for handling circular references in database
 * schemas where foreign keys create loops (e.g., a table referencing itself or mutual references
 * between tables). It provides options for either closing the cycle by linking back to an existing
 * record or terminating with null values to break the referential chain. The choice of termination
 * mode affects how data generation handles these complex relationship scenarios.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Defining termination strategies for circular foreign key references
 *   <li>Supporting both cyclic completion and null termination approaches
 *   <li>Providing user-friendly display names for UI components
 *   <li>Enabling configuration persistence and restoration
 *   <li>Supporting case-insensitive parsing from persisted values
 * </ul>
 *
 * <p>The implementation includes robust parsing methods to handle configuration loading from
 * various sources, with fallback to default behavior for invalid or missing values. Each enum value
 * includes a human-readable display name suitable for user interface presentation.
 */
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
