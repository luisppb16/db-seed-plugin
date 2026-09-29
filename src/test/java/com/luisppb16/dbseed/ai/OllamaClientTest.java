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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OllamaClientTest {

  @Nested
  class NormalizeUrl {

    @Test
    void noProtocol_addsHttp() {
      assertThat(OllamaClient.normalizeUrl("localhost:11434")).isEqualTo("http://localhost:11434");
    }

    @Test
    void httpProtocol_preserved() {
      assertThat(OllamaClient.normalizeUrl("http://myserver:1234"))
          .isEqualTo("http://myserver:1234");
    }

    @Test
    void httpsProtocol_preserved() {
      assertThat(OllamaClient.normalizeUrl("https://myserver:1234"))
          .isEqualTo("https://myserver:1234");
    }

    @Test
    void trailingSlash_removed() {
      assertThat(OllamaClient.normalizeUrl("http://localhost:11434/"))
          .isEqualTo("http://localhost:11434");
    }

    @Test
    void alreadyNormalized_unchanged() {
      assertThat(OllamaClient.normalizeUrl("http://localhost:11434"))
          .isEqualTo("http://localhost:11434");
    }
  }

  @Nested
  class SanitizeAiOutput {

    @Test
    void nullInput_returnsNull() {
      assertThat(OllamaClient.sanitizeAiOutput(null, "col")).isNull();
    }

    @Test
    void emptyInput_returnsEmpty() {
      assertThat(OllamaClient.sanitizeAiOutput("", "col")).isEmpty();
    }

    @Test
    void trailingNewlines_firstLineOnly() {
      assertThat(OllamaClient.sanitizeAiOutput("hello\nworld", "col")).isEqualTo("hello");
    }

    @Test
    void quotedValue_stripsQuotes() {
      assertThat(OllamaClient.sanitizeAiOutput("'hello'", "col")).isEqualTo("hello");
    }

    @Test
    void doubleQuotedValue_stripsQuotes() {
      assertThat(OllamaClient.sanitizeAiOutput("\"hello\"", "col")).isEqualTo("hello");
    }

    @Test
    void numberedPrefix_removed() {
      assertThat(OllamaClient.sanitizeAiOutput("1. hello", "col")).isEqualTo("hello");
    }

    @Test
    void dashBullet_prefixRemoved() {
      assertThat(OllamaClient.sanitizeAiOutput("- hello", "col")).isEqualTo("hello");
    }

    @Test
    void starBullet_prefixRemoved() {
      assertThat(OllamaClient.sanitizeAiOutput("* hello", "col")).isEqualTo("hello");
    }

    @Test
    void aiPreamble_returnsNull() {
      assertThat(OllamaClient.sanitizeAiOutput("Here are the values:", "col")).isNull();
    }

    @Test
    void aiRefusal_returnsNull() {
      assertThat(OllamaClient.sanitizeAiOutput("I cannot generate this content.", "col")).isNull();
    }

    @Test
    void validValue_notPreamble() {
      assertThat(OllamaClient.sanitizeAiOutput("John Doe", "name")).isEqualTo("John Doe");
    }

    @Test
    void columnPrefix_stripped() {
      assertThat(OllamaClient.sanitizeAiOutput("name: John", "name")).isEqualTo("John");
    }

    @Test
    void columnPrefixWithEquals_stripped() {
      assertThat(OllamaClient.sanitizeAiOutput("name=John", "name")).isEqualTo("John");
    }

    @Test
    void noPrefix_unmodified() {
      assertThat(OllamaClient.sanitizeAiOutput("John Doe", "name")).isEqualTo("John Doe");
    }

    @Test
    void columnPrefixCaseInsensitive_stripped() {
      assertThat(OllamaClient.sanitizeAiOutput("Name: John", "name")).isEqualTo("John");
    }

    @Test
    void valueEqualsColumnName_returnsNull() {
      // "name" matches the column prefix, no separator follows, and the remaining value
      // equals the column name — an echoed column name is dropped entirely.
      assertThat(OllamaClient.sanitizeAiOutput("name", "name")).isNull();
    }

    @Test
    void codeFenceLines_collapsedToEmpty() {
      assertThat(OllamaClient.sanitizeAiOutput("```", "col")).isEmpty();
      assertThat(OllamaClient.sanitizeAiOutput("```json", "col")).isEmpty();
    }

    @Test
    void spanishPreamble_returnsNull() {
      assertThat(OllamaClient.sanitizeAiOutput("Aquí están los valores:", "col")).isNull();
      assertThat(OllamaClient.sanitizeAiOutput("Por supuesto:", "col")).isNull();
      assertThat(OllamaClient.sanitizeAiOutput("Claro, aquí tienes:", "col")).isNull();
      assertThat(OllamaClient.sanitizeAiOutput("Los siguientes valores:", "col")).isNull();
    }

    @Test
    void spanishRefusal_returnsNull() {
      assertThat(OllamaClient.sanitizeAiOutput("No puedo generar eso", "col")).isNull();
      assertThat(OllamaClient.sanitizeAiOutput("Lo siento, no puedo", "col")).isNull();
      assertThat(OllamaClient.sanitizeAiOutput("Como modelo de lenguaje...", "col")).isNull();
    }

    @Test
    void reasoningRestatingTheInstruction_returnsNull() {
      assertThat(
              OllamaClient.sanitizeAiOutput(
                  "The user wants 12 unique values for a \"content\" column in an articles table.",
                  "content"))
          .isNull();
      assertThat(
              OllamaClient.sanitizeAiOutput(
                  "The user wants 12 unique array values for a \"tags\" column, 3 elements each.",
                  "tags"))
          .isNull();
      assertThat(
              OllamaClient.sanitizeAiOutput(
                  "Necesito generar títulos únicos para la columna title.", "title"))
          .isNull();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "This seems like a benign request for military simulation data.",
          "Let me generate 12 unique text values, one per line.",
          "Let me create 12 unique tag strings for the column.",
          "So each line should be like {tag1, tag2, tag3} where each tag is up to 10 words.",
          "The context is \"Military simulations on foreign territory\", so tags should relate.",
          "Raw values only, no numbering and no quotes.",
          "Unique values from a curated keyword list."
        })
    void leakedReasoningPhrases_returnNull(final String phrase) {
      assertThat(OllamaClient.sanitizeAiOutput(phrase, "tags")).isNull();
    }

    @Test
    void valueStartingWithOrdinaryWords_notDropped() {
      // Guard against an over-eager reasoning filter: only instruction-restating text is dropped.
      assertThat(OllamaClient.sanitizeAiOutput("Let me know your thoughts", "content"))
          .isEqualTo("Let me know your thoughts");
      assertThat(OllamaClient.sanitizeAiOutput("Under the same sky", "title"))
          .isEqualTo("Under the same sky");
      assertThat(OllamaClient.sanitizeAiOutput("Each tag was reviewed by hand", "tags"))
          .isEqualTo("Each tag was reviewed by hand");
    }
  }

  @Nested
  class StripCodeFences {

    @Test
    void bareFence_collapsedToEmpty() {
      assertThat(OllamaClient.stripCodeFences("```")).isEmpty();
    }

    @Test
    void fenceWithLanguage_collapsedToEmpty() {
      assertThat(OllamaClient.stripCodeFences("```json")).isEmpty();
    }

    @Test
    void closingFenceStripped_fromEnd() {
      assertThat(OllamaClient.stripCodeFences("valor1```")).isEqualTo("valor1");
    }

    @Test
    void fenceGluedToContent_stripsFenceOnly() {
      // "```valor1" has a digit after the fence, so it is treated as content, not a language tag.
      assertThat(OllamaClient.stripCodeFences("```valor1")).isEqualTo("valor1");
    }

    @Test
    void noFence_unmodified() {
      assertThat(OllamaClient.stripCodeFences("valor1")).isEqualTo("valor1");
    }
  }

  @Nested
  class StripSurroundingQuotes {

    @Test
    void doubleQuotes_stripped() {
      assertThat(OllamaClient.stripSurroundingQuotes("\"hello\"")).isEqualTo("hello");
    }

    @Test
    void singleQuotes_stripped() {
      assertThat(OllamaClient.stripSurroundingQuotes("'hello'")).isEqualTo("hello");
    }

    @Test
    void mismatchedQuotes_notStripped() {
      assertThat(OllamaClient.stripSurroundingQuotes("\"hello'")).isEqualTo("\"hello'");
    }

    @Test
    void noQuotes_unmodified() {
      assertThat(OllamaClient.stripSurroundingQuotes("hello")).isEqualTo("hello");
    }

    @Test
    void emptyQuotes_returnsEmpty() {
      assertThat(OllamaClient.stripSurroundingQuotes("\"\"")).isEmpty();
    }

    @Test
    void singleChar_quotesStripped() {
      assertThat(OllamaClient.stripSurroundingQuotes("\"a\"")).isEqualTo("a");
    }
  }

  @Nested
  class IsAiPreamble {

    @Test
    void hereAre_isPreamble() {
      assertThat(OllamaClient.isAiPreamble("Here are some values")).isTrue();
    }

    @Test
    void hereIs_isPreamble() {
      assertThat(OllamaClient.isAiPreamble("Here is the data")).isTrue();
    }

    @Test
    void sureComma_isPreamble() {
      assertThat(OllamaClient.isAiPreamble("Sure, here you go")).isTrue();
    }

    @Test
    void certainly_isPreamble() {
      assertThat(OllamaClient.isAiPreamble("Certainly! Here are the results")).isTrue();
    }

    @Test
    void validData_isNotPreamble() {
      assertThat(OllamaClient.isAiPreamble("John Doe")).isFalse();
    }

    @Test
    void blank_isNotPreamble() {
      assertThat(OllamaClient.isAiPreamble("")).isFalse();
    }
  }

  @Nested
  class IsAiRefusal {

    @Test
    void iCannot_isRefusal() {
      assertThat(OllamaClient.isAiRefusal("I cannot do that")).isTrue();
    }

    @Test
    void imSorry_isRefusal() {
      assertThat(OllamaClient.isAiRefusal("I'm sorry, I can't help with that")).isTrue();
    }

    @Test
    void asAnAi_isRefusal() {
      assertThat(OllamaClient.isAiRefusal("As an AI language model")).isTrue();
    }

    @Test
    void validData_isNotRefusal() {
      assertThat(OllamaClient.isAiRefusal("John Doe")).isFalse();
    }
  }

  @Nested
  class StripColumnPrefix {

    @Test
    void colonPrefix_stripped() {
      assertThat(OllamaClient.stripColumnPrefix("name: John", "name")).isEqualTo("John");
    }

    @Test
    void equalsPrefix_stripped() {
      assertThat(OllamaClient.stripColumnPrefix("name=John", "name")).isEqualTo("John");
    }

    @Test
    void noPrefix_unmodified() {
      assertThat(OllamaClient.stripColumnPrefix("John Doe", "name")).isEqualTo("John Doe");
    }

    @Test
    void caseInsensitivePrefix_stripped() {
      assertThat(OllamaClient.stripColumnPrefix("Name: John", "name")).isEqualTo("John");
    }

    @Test
    void partialPrefixMatch_valuePreservedIntact() {
      // "named" starts with "name" but no separator follows, so the value is preserved intact.
      assertThat(OllamaClient.stripColumnPrefix("named: John", "name")).isEqualTo("named: John");
    }
  }

  @Nested
  class IsArrayType {

    @Test
    void textArray_isArrayType() {
      assertThat(OllamaClient.isArrayType("text[]")).isTrue();
    }

    @Test
    void integerArray_isArrayType() {
      assertThat(OllamaClient.isArrayType("integer[]")).isTrue();
    }

    @Test
    void underscorePrefix_isArrayType() {
      assertThat(OllamaClient.isArrayType("_text")).isTrue();
    }

    @Test
    void varchar_isNotArrayType() {
      assertThat(OllamaClient.isArrayType("varchar")).isFalse();
    }

    @Test
    void nullType_isNotArrayType() {
      assertThat(OllamaClient.isArrayType(null)).isFalse();
    }

    @Test
    void emptyType_isNotArrayType() {
      assertThat(OllamaClient.isArrayType("")).isFalse();
    }

    @Test
    void arrayKeyword_isArrayType() {
      assertThat(OllamaClient.isArrayType("ARRAY")).isTrue();
    }
  }
}
