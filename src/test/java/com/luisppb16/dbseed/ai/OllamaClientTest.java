/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
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
      // The value equals the column name (an echo/placeholder), so it is discarded.
      assertThat(OllamaClient.sanitizeAiOutput("name", "name")).isNull();
    }

    @Test
    void codeFenceLine_returnsEmpty() {
      assertThat(OllamaClient.sanitizeAiOutput("```", "col")).isEmpty();
    }

    @Test
    void codeFenceWithLanguage_returnsEmpty() {
      assertThat(OllamaClient.sanitizeAiOutput("```json", "col")).isEmpty();
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
    void spanishAquiEstan_isPreamble() {
      assertThat(OllamaClient.isAiPreamble("Aquí están los valores")).isTrue();
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
    void spanishNoPuedo_isRefusal() {
      assertThat(OllamaClient.isAiRefusal("No puedo generar eso")).isTrue();
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
    void partialPrefixMatch_notCorrupted() {
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
  class AwaitCancellable {

    @Test
    void alreadyCanceled_supplierCancelsFutureAndThrows() {
      // A never-completing future would otherwise block up to the HTTP request timeout; with a
      // canceled supplier, awaitCancellable must cancel the future and throw immediately.
      final CompletableFuture<String> never = new CompletableFuture<>();

      assertThatThrownBy(() -> OllamaClient.awaitCancellable(never, () -> true))
          .isInstanceOf(CancellationException.class);
      assertThat(never).isCancelled();
    }

    @Test
    void completedFuture_returnsValueWithoutBlocking() {
      final CompletableFuture<String> done = CompletableFuture.completedFuture("valor");

      assertThat(OllamaClient.awaitCancellable(done, () -> false)).isEqualTo("valor");
    }
  }

  @Nested
  class PerValuePredictTokens {

    @Test
    void scalarSingleWord_isBatchFactor() {
      assertThat(OllamaClient.perValuePredictTokens(false, 1)).isEqualTo(15);
    }

    @Test
    void scalarLowWordCount_flooredAtBatchFactor() {
      // wordCount=2 → max(15, 2*3) = 15
      assertThat(OllamaClient.perValuePredictTokens(false, 2)).isEqualTo(15);
    }

    @Test
    void scalarHighWordCount_scalesWithWordCount() {
      // wordCount=10 → max(15, 10*3) = 30
      assertThat(OllamaClient.perValuePredictTokens(false, 10)).isEqualTo(30);
    }

    @Test
    void scalarZeroWordCount_clampedToOne() {
      assertThat(OllamaClient.perValuePredictTokens(false, 0)).isEqualTo(15);
    }

    @Test
    void arrayMultipliesByElementCount() {
      // array, wordCount=1 → elementCount(3) * 15 = 45
      assertThat(OllamaClient.perValuePredictTokens(true, 1)).isEqualTo(45);
    }
  }
}
