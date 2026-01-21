/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import lombok.Builder;

@Builder
public record DriverInfo(
    String name,
    String mavenGroupId,
    String mavenArtifactId,
    String version,
    String driverClass,
    String urlTemplate,
    boolean requiresDatabaseName,
    boolean requiresUser,
    boolean requiresPassword,
    boolean requiresSchema) {}
