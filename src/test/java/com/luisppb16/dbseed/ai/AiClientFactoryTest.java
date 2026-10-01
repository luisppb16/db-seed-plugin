/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AiClientFactory}: which implementation each provider maps to. */
class AiClientFactoryTest {

  private static final int REQUEST_TIMEOUT_SECONDS = 120;

  @Nested
  class Create {

    @Test
    void ollama_buildsTheNativeClient() {
      final AiClient client =
          AiClientFactory.create(
              AiProvider.OLLAMA,
              AiProvider.OLLAMA.getDefaultUrl(),
              "llama3",
              "",
              REQUEST_TIMEOUT_SECONDS);

      assertThat(client).isInstanceOf(OllamaClient.class);
    }

    @Test
    void unslothStudio_buildsTheOpenAiCompatibleClient() {
      final AiClient client =
          AiClientFactory.create(
              AiProvider.UNSLOTH_STUDIO,
              AiProvider.UNSLOTH_STUDIO.getDefaultUrl(),
              "qwen",
              "sk-unsloth-test",
              REQUEST_TIMEOUT_SECONDS);

      assertThat(client).isInstanceOf(OpenAiCompatibleClient.class);
    }

    @Test
    void genericOpenAiCompatible_buildsTheOpenAiCompatibleClient() {
      final AiClient client =
          AiClientFactory.create(
              AiProvider.OPENAI_COMPATIBLE,
              "http://localhost:1234",
              "local-model",
              "",
              REQUEST_TIMEOUT_SECONDS);

      assertThat(client).isInstanceOf(OpenAiCompatibleClient.class);
    }

    @Test
    void blankModel_stillBuildsAClient() {
      // Listar los modelos es justo lo que se hace antes de elegir uno, así que el nombre vacío no
      // puede impedir construir el cliente; quien genera valida el modelo por su cuenta.
      final AiClient client =
          AiClientFactory.create(
              AiProvider.UNSLOTH_STUDIO, AiProvider.UNSLOTH_STUDIO.getDefaultUrl(), "", "", 120);

      assertThat(client).isInstanceOf(OpenAiCompatibleClient.class);
    }

    @Test
    void urlWithoutScheme_isAccepted() {
      final AiClient client =
          AiClientFactory.create(AiProvider.OLLAMA, "localhost:11434", "llama3", "", 120);

      assertThat(client).isNotNull();
    }

    @Test
    void nullProvider_returnsNull() {
      assertThat(AiClientFactory.create(null, "http://localhost:11434", "llama3", "", 120))
          .isNull();
    }

    @Test
    void nullUrl_returnsNull() {
      assertThat(AiClientFactory.create(AiProvider.OLLAMA, null, "llama3", "", 120)).isNull();
    }

    @Test
    void blankUrl_returnsNull() {
      assertThat(AiClientFactory.create(AiProvider.OLLAMA, "   ", "llama3", "", 120)).isNull();
    }
  }
}
