/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import java.util.Objects;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the {@link AiClient} the current configuration describes. It is the only place that knows
 * which provider maps to which implementation, so callers never name a concrete client.
 *
 * <p>The key is passed in rather than read here: it lives in the IDE credential store, and keeping
 * that lookup at the call site leaves this package free of any dependency on the settings package.
 */
@UtilityClass
public class AiClientFactory {

  /**
   * The model name may be empty: listing the models a server offers is precisely what the settings
   * dialog does before the user has picked one. Callers that generate values must validate it first
   * ({@code DataGenerator} does, and skips the whole AI phase when it is missing).
   *
   * @return a client for the given provider, or {@code null} when there is no provider or no URL,
   *     which callers read as "AI generation is off"
   */
  @Nullable
  public static AiClient create(
      @Nullable final AiProvider provider,
      @Nullable final String url,
      @Nullable final String modelName,
      @Nullable final String apiKey,
      final int requestTimeoutSeconds) {
    if (Objects.isNull(provider) || Objects.isNull(url) || url.isBlank()) {
      return null;
    }
    return switch (provider) {
      case OLLAMA -> new OllamaClient(url, modelName, requestTimeoutSeconds);
      case UNSLOTH_STUDIO, OPENAI_COMPATIBLE ->
          new OpenAiCompatibleClient(provider, url, modelName, apiKey, requestTimeoutSeconds);
    };
  }
}
