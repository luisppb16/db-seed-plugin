/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Enumeration of constant values used throughout the DBSeed plugin ecosystem.
 *
 * <p>This enum class defines application-wide constants that are used for configuration,
 * identification, and consistent naming across different components of the DBSeed plugin. These
 * constants help maintain consistency in naming conventions, notification groups, and other
 * application-specific identifiers that need to be referenced in multiple places.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Providing centralized definition of application constants
 *   <li>Ensuring consistent naming across the plugin components
 *   <li>Supporting internationalization and configuration management
 *   <li>Preventing hard-coded strings scattered throughout the codebase
 * </ul>
 *
 * <p>The implementation uses Lombok annotations for automatic getter generation and follows
 * standard enum patterns for type-safe constant definitions.
 */
@Getter
@RequiredArgsConstructor
public enum Constant {
  APP_NAME("DBSeed4SQL"),
  NOTIFICATION_ID("DBSeed4SQL");

  private final String value;
}
