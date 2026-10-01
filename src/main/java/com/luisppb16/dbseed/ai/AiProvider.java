/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

/**
 * AI engines the plugin can be pointed at.
 *
 * <p>Every provider except {@link #OLLAMA} speaks the OpenAI-compatible chat completions API, so
 * they all share {@link OpenAiCompatibleClient} and differ only in their label, their default URL
 * and whether they need an API key. Adding an engine of that family is a new constant here plus, at
 * most, a body tweak in the client.
 */
public enum AiProvider {

  /** Native Ollama API, no API key. */
  OLLAMA("Ollama", "http://localhost:11434", false),

  /** Unsloth Studio, which serves an OpenAI-compatible API and authenticates with a Bearer key. */
  UNSLOTH_STUDIO("Unsloth Studio", "http://localhost:8888", true),

  /**
   * Any other OpenAI-compatible server (LM Studio, vLLM, llama.cpp, OpenRouter…). It has no default
   * URL on purpose: those servers listen on different ports, so the user must type their own.
   */
  OPENAI_COMPATIBLE("OpenAI-compatible server", "", false);

  private final String displayName;
  private final String defaultUrl;
  private final boolean apiKeyRequired;

  AiProvider(final String displayName, final String defaultUrl, final boolean apiKeyRequired) {
    this.displayName = displayName;
    this.defaultUrl = defaultUrl;
    this.apiKeyRequired = apiKeyRequired;
  }

  /** Label shown on the settings radio button and in user-facing messages. */
  public String getDisplayName() {
    return displayName;
  }

  /** URL pre-filled when this provider is selected; blank when there is no sensible default. */
  public String getDefaultUrl() {
    return defaultUrl;
  }

  /** Whether the engine refuses requests that carry no API key. */
  public boolean isApiKeyRequired() {
    return apiKeyRequired;
  }

  @Override
  public String toString() {
    return displayName;
  }
}
