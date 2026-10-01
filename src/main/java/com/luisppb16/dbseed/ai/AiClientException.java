/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

/**
 * Raised when a call to an AI engine fails: the server is unreachable, it answered with an error
 * status, the stream stalled or the answer carried no usable value.
 *
 * <p>It is engine-agnostic on purpose: the message names the engine through {@code providerName()},
 * so the user still reads "Ollama" or "Unsloth Studio" in the notification while the thrown type
 * stays the same whichever engine produced it.
 */
public class AiClientException extends RuntimeException {

  public AiClientException(final String message) {
    super(message);
  }

  public AiClientException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
