/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.ai.AiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class AiServerAddressTest {

  @ParameterizedTest
  @CsvSource({
    "http://localhost:11434, http://localhost, 11434",
    "http://localhost:8888, http://localhost, 8888",
    "http://localhost, http://localhost, ''",
    "https://openrouter.ai/api/v1, https://openrouter.ai/api/v1, ''",
    "localhost:11434, localhost, 11434",
    "http://[::1]:8080, http://[::1], 8080",
    "http://user:secret@localhost:9999/v1, http://user:secret@localhost/v1, 9999"
  })
  void parse_splitsThePortFromTheUrl(
      final String rawUrl, final String expectedUrl, final String expectedPort) {
    final AiServerAddress address = AiServerAddress.parse(rawUrl);

    assertThat(address.url()).isEqualTo(expectedUrl);
    assertThat(address.port()).isEqualTo(expectedPort);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void parse_nullOrBlank_yieldsAnEmptyAddress(final String rawUrl) {
    final AiServerAddress address = AiServerAddress.parse(rawUrl);

    assertThat(address.url()).isEmpty();
    assertThat(address.port()).isEmpty();
    assertThat(address.composed()).isEmpty();
  }

  @Test
  void parse_nonNumericPort_keepsItInTheUrl() {
    final AiServerAddress address = AiServerAddress.parse("http://localhost:api");

    assertThat(address.url()).isEqualTo("http://localhost:api");
    assertThat(address.port()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "http://localhost, 11434, http://localhost:11434",
    "http://localhost, '', http://localhost",
    "https://openrouter.ai/api/v1, '', https://openrouter.ai/api/v1",
    "'', 11434, ''"
  })
  void of_composesTheUrlAndThePort(
      final String url, final String port, final String expectedComposed) {
    assertThat(AiServerAddress.of(url, port).composed()).isEqualTo(expectedComposed);
  }

  @Test
  void of_portFieldOverridesThePortEmbeddedInTheUrl() {
    assertThat(AiServerAddress.of("http://localhost:11434", "9999").composed())
        .isEqualTo("http://localhost:9999");
  }

  @Test
  void of_blankPortFieldKeepsThePortEmbeddedInTheUrl() {
    assertThat(AiServerAddress.of("http://localhost:11434", "").composed())
        .isEqualTo("http://localhost:11434");
  }

  @Test
  void of_nullUrl_yieldsAnEmptyAddress() {
    assertThat(AiServerAddress.of(null, "11434").composed()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "OLLAMA, http://localhost, 11434",
    "UNSLOTH_STUDIO, http://localhost, 8888",
    "OPENAI_COMPATIBLE, '', ''"
  })
  void parse_providerDefaults_splitIntoUrlAndPort(
      final AiProvider provider, final String expectedUrl, final String expectedPort) {
    final AiServerAddress address = AiServerAddress.parse(provider.getDefaultUrl());

    assertThat(address.url()).isEqualTo(expectedUrl);
    assertThat(address.port()).isEqualTo(expectedPort);
  }
}
