/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OllamaClientTest {

  @Nested
  class NormalizeUrl {

    @Test
    void noProtocol_addsHttp() {
      assertThat(OllamaClient.normalizeUrl("localhost:11434")).isEqualTo("http://localhost:11434");
    }

    @Test
    void httpProtocol_preserved() {
      assertThat(OllamaClient.normalizeUrl("http://myserver:1234")).isEqualTo("http://myserver:1234");
    }

    @Test
    void httpsProtocol_preserved() {
      assertThat(OllamaClient.normalizeUrl("https://myserver:1234")).isEqualTo("https://myserver:1234");
    }

    @Test
    void trailingSlash_removed() {
      assertThat(OllamaClient.normalizeUrl("http://localhost:11434/")).isEqualTo("http://localhost:11434");
    }

    @Test
    void alreadyNormalized_unchanged() {
      assertThat(OllamaClient.normalizeUrl("http://localhost:11434")).isEqualTo("http://localhost:11434");
    }
  }

  @Nested
  class SanitizeAiOutput {

    @Test
    void nullInput_returnsNull() {
      assertThat(AiClient.sanitizeAiOutput(null, "col")).isNull();
    }

    @Test
    void emptyInput_returnsEmpty() {
      assertThat(AiClient.sanitizeAiOutput("", "col")).isEmpty();
    }

    @Test
    void trailingNewlines_firstLineOnly() {
      assertThat(AiClient.sanitizeAiOutput("hello\nworld", "col")).isEqualTo("hello");
    }

    @Test
    void quotedValue_stripsQuotes() {
      assertThat(AiClient.sanitizeAiOutput("'hello'", "col")).isEqualTo("hello");
    }

    @Test
    void doubleQuotedValue_stripsQuotes() {
      assertThat(AiClient.sanitizeAiOutput("\"hello\"", "col")).isEqualTo("hello");
    }

    @Test
    void numberedPrefix_removed() {
      assertThat(AiClient.sanitizeAiOutput("1. hello", "col")).isEqualTo("hello");
    }

    @Test
    void dashBullet_prefixRemoved() {
      assertThat(AiClient.sanitizeAiOutput("- hello", "col")).isEqualTo("hello");
    }

    @Test
    void starBullet_prefixRemoved() {
      assertThat(AiClient.sanitizeAiOutput("* hello", "col")).isEqualTo("hello");
    }

    @Test
    void aiPreamble_returnsNull() {
      assertThat(AiClient.sanitizeAiOutput("Here are the values:", "col")).isNull();
    }

    @Test
    void aiRefusal_returnsNull() {
      assertThat(AiClient.sanitizeAiOutput("I cannot generate this content.", "col")).isNull();
    }

    @Test
    void validValue_notPreamble() {
      assertThat(AiClient.sanitizeAiOutput("John Doe", "name")).isEqualTo("John Doe");
    }

    @Test
    void columnPrefix_stripped() {
      assertThat(AiClient.sanitizeAiOutput("name: John", "name")).isEqualTo("John");
    }

    @Test
    void columnPrefixWithEquals_stripped() {
      assertThat(AiClient.sanitizeAiOutput("name=John", "name")).isEqualTo("John");
    }

    @Test
    void noPrefix_unmodified() {
      assertThat(AiClient.sanitizeAiOutput("John Doe", "name")).isEqualTo("John Doe");
    }

    @Test
    void columnPrefixCaseInsensitive_stripped() {
      assertThat(AiClient.sanitizeAiOutput("Name: John", "name")).isEqualTo("John");
    }

    @Test
    void valueEqualsColumnName_afterPrefixStrip_returnsEmpty() {
      assertThat(AiClient.sanitizeAiOutput("name", "name")).isEmpty();
    }
  }

  @Nested
  class StripSurroundingQuotes {

    @Test
    void doubleQuotes_stripped() {
      assertThat(AiClient.stripSurroundingQuotes("\"hello\"")).isEqualTo("hello");
    }

    @Test
    void singleQuotes_stripped() {
      assertThat(AiClient.stripSurroundingQuotes("'hello'")).isEqualTo("hello");
    }

    @Test
    void mismatchedQuotes_notStripped() {
      assertThat(AiClient.stripSurroundingQuotes("\"hello'")).isEqualTo("\"hello'");
    }

    @Test
    void noQuotes_unmodified() {
      assertThat(AiClient.stripSurroundingQuotes("hello")).isEqualTo("hello");
    }

    @Test
    void emptyQuotes_returnsEmpty() {
      assertThat(AiClient.stripSurroundingQuotes("\"\"")).isEmpty();
    }

    @Test
    void singleChar_quotesStripped() {
      assertThat(AiClient.stripSurroundingQuotes("\"a\"")).isEqualTo("a");
    }
  }

  @Nested
  class IsAiPreamble {

    @Test
    void hereAre_isPreamble() {
      assertThat(AiClient.isAiPreamble("Here are some values")).isTrue();
    }

    @Test
    void hereIs_isPreamble() {
      assertThat(AiClient.isAiPreamble("Here is the data")).isTrue();
    }

    @Test
    void sureComma_isPreamble() {
      assertThat(AiClient.isAiPreamble("Sure, here you go")).isTrue();
    }

    @Test
    void certainly_isPreamble() {
      assertThat(AiClient.isAiPreamble("Certainly! Here are the results")).isTrue();
    }

    @Test
    void validData_isNotPreamble() {
      assertThat(AiClient.isAiPreamble("John Doe")).isFalse();
    }

    @Test
    void blank_isNotPreamble() {
      assertThat(AiClient.isAiPreamble("")).isFalse();
    }
  }

  @Nested
  class IsAiRefusal {

    @Test
    void iCannot_isRefusal() {
      assertThat(AiClient.isAiRefusal("I cannot do that")).isTrue();
    }

    @Test
    void imSorry_isRefusal() {
      assertThat(AiClient.isAiRefusal("I'm sorry, I can't help with that")).isTrue();
    }

    @Test
    void asAnAi_isRefusal() {
      assertThat(AiClient.isAiRefusal("As an AI language model")).isTrue();
    }

    @Test
    void validData_isNotRefusal() {
      assertThat(AiClient.isAiRefusal("John Doe")).isFalse();
    }
  }

  @Nested
  class StripColumnPrefix {

    @Test
    void colonPrefix_stripped() {
      assertThat(AiClient.stripColumnPrefix("name: John", "name")).isEqualTo("John");
    }

    @Test
    void equalsPrefix_stripped() {
      assertThat(AiClient.stripColumnPrefix("name=John", "name")).isEqualTo("John");
    }

    @Test
    void noPrefix_unmodified() {
      assertThat(AiClient.stripColumnPrefix("John Doe", "name")).isEqualTo("John Doe");
    }

    @Test
    void caseInsensitivePrefix_stripped() {
      assertThat(AiClient.stripColumnPrefix("Name: John", "name")).isEqualTo("John");
    }

    @Test
    void partialPrefixMatch_stripsButMayCorrupt() {
      assertThat(AiClient.stripColumnPrefix("named: John", "name")).isNotEqualTo("named: John");
    }
  }

  @Nested
  class IsArrayType {

    @Test
    void textArray_isArrayType() {
      assertThat(AiClient.isArrayType("text[]")).isTrue();
    }

    @Test
    void integerArray_isArrayType() {
      assertThat(AiClient.isArrayType("integer[]")).isTrue();
    }

    @Test
    void underscorePrefix_isArrayType() {
      assertThat(AiClient.isArrayType("_text")).isTrue();
    }

    @Test
    void varchar_isNotArrayType() {
      assertThat(AiClient.isArrayType("varchar")).isFalse();
    }

    @Test
    void nullType_isNotArrayType() {
      assertThat(AiClient.isArrayType(null)).isFalse();
    }

    @Test
    void emptyType_isNotArrayType() {
      assertThat(AiClient.isArrayType("")).isFalse();
    }

    @Test
    void arrayKeyword_isArrayType() {
      assertThat(AiClient.isArrayType("ARRAY")).isTrue();
    }
  }
}