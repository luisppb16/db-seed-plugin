/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

public enum AiProvider {
  OLLAMA("Ollama"),
  OPENROUTER("OpenRouter");

  private final String displayName;

  AiProvider(final String displayName) {
    this.displayName = displayName;
  }

  @Override
  public String toString() {
    return displayName;
  }
}