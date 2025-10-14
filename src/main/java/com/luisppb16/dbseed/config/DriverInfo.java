package com.luisppb16.dbseed.config;

import lombok.Builder;

@Builder
public record DriverInfo(
    String name,
    String mavenGroupId,
    String mavenArtifactId,
    String version,
    String driverClass,
    String urlTemplate) {}
