/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

/**
 * Immutable configuration record for database generation parameters in the DBSeed plugin ecosystem.
 */
public record GenerationConfig(
    String url,
    String user,
    String password,
    String schema,
    int rowsPerTable,
    boolean deferred,
    String softDeleteColumns,
    boolean softDeleteUseSchemaDefault,
    String softDeleteValue,
    int numericScale) {

  public GenerationConfig {
    if (rowsPerTable <= 0) {
      rowsPerTable = 1;
    }
    if (numericScale < 0) {
      numericScale = 2;
    }
  }

  public GenerationConfig withSoftDeleteSettings(
      final String softDeleteColumns,
      final boolean softDeleteUseSchemaDefault,
      final String softDeleteValue,
      final int numericScale) {
    return new GenerationConfig(
        url,
        user,
        password,
        schema,
        rowsPerTable,
        deferred,
        softDeleteColumns,
        softDeleteUseSchemaDefault,
        softDeleteValue,
        numericScale);
  }
}
