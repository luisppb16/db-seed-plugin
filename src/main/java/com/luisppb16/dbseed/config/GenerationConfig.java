/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import lombok.Builder;

/**
 * Immutable configuration record for database generation parameters in the DBSeed plugin ecosystem.
 */
@Builder(toBuilder = true)
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
    int numericScale) {}
