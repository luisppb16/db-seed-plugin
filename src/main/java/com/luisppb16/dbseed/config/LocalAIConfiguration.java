/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import lombok.Builder;

/**
 * Local AI Configuration record for DeepSeek integration.
 */
@Builder
public record LocalAIConfiguration(
    boolean enableAiDialect,
    boolean enableContextAwareGeneration,
    String aiLocalEndpoint,
    String aiModelName
) {}
