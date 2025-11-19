/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.config;

import lombok.Builder;

@Builder(toBuilder = true)
public record GenerationConfig(
    String url, String user, String password, String schema, int rowsPerTable, boolean deferred) {}
