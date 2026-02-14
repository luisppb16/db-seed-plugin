/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ai;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class OllamaClientTest {

  private OllamaClient client;

  @BeforeEach
  void setUp() {
    client = new OllamaClient("http://localhost:11434", "test-model", 30);
  }

  private String invokeEscapeJson(String text) throws Exception {
    Method method = OllamaClient.class.getDeclaredMethod("escapeJson", String.class);
    method.setAccessible(true);
    return (String) method.invoke(client, text);
  }

  private String invokeUnescapeJson(String text) throws Exception {
    Method method = OllamaClient.class.getDeclaredMethod("unescapeJson", String.class);
    method.setAccessible(true);
    return (String) method.invoke(client, text);
  }

  private static String invokeSanitizeAiOutput(String value, String columnName) throws Exception {
    Method method =
        OllamaClient.class.getDeclaredMethod("sanitizeAiOutput", String.class, String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, value, columnName);
  }

  private static String invokeNormalizeUrl(String url) throws Exception {
    Method method = OllamaClient.class.getDeclaredMethod("normalizeUrl", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, url);
  }

  private static int invokeFindClosingQuote(String json, int startIndex) throws Exception {
    Method method =
        OllamaClient.class.getDeclaredMethod("findClosingQuote", String.class, int.class);
    method.setAccessible(true);
    return (int) method.invoke(null, json, startIndex);
  }

  private String invokeExtractRawResponse(String responseBody) throws Exception {
    Method method = OllamaClient.class.getDeclaredMethod("extractRawResponse", String.class);
    method.setAccessible(true);
    return (String) method.invoke(client, responseBody);
  }

  // ── escapeJson / unescapeJson roundtrip ──

  @Nested
  class JsonEscaping {

    @Test
    void roundtrip_plainText() throws Exception {
      String original = "Hello World";
      assertThat(invokeUnescapeJson(invokeEscapeJson(original))).isEqualTo(original);
    }

    @Test
    void roundtrip_backslashes() throws Exception {
      String original = "path\\to\\file";
      assertThat(invokeUnescapeJson(invokeEscapeJson(original))).isEqualTo(original);
    }

    @Test
    void roundtrip_newlinesAndTabs() throws Exception {
      String original = "line1\nline2\ttabbed";
      assertThat(invokeUnescapeJson(invokeEscapeJson(original))).isEqualTo(original);
    }

    @Test
    void roundtrip_doubleQuotes() throws Exception {
      String original = "He said \"hello\"";
      assertThat(invokeUnescapeJson(invokeEscapeJson(original))).isEqualTo(original);
    }

    @Test
    void roundtrip_mixedEscapes() throws Exception {
      String original = "C:\\Users\\test\n\"quoted\"\ttab";
      assertThat(invokeUnescapeJson(invokeEscapeJson(original))).isEqualTo(original);
    }

    @Test
    void roundtrip_backslashN_literal() throws Exception {
      String original = "literal \\n in text";
      String escaped = invokeEscapeJson(original);
      String restored = invokeUnescapeJson(escaped);
      assertThat(restored).isEqualTo(original);
    }

    @Test
    void unescape_literalBackslash_preserved() throws Exception {
      assertThat(invokeUnescapeJson("file\\\\path")).isEqualTo("file\\path");
    }

    @Test
    void unescape_newline() throws Exception {
      assertThat(invokeUnescapeJson("line1\\nline2")).isEqualTo("line1\nline2");
    }

    @Test
    void unescape_tab() throws Exception {
      assertThat(invokeUnescapeJson("col1\\tcol2")).isEqualTo("col1\tcol2");
    }

    @Test
    void unescape_quote() throws Exception {
      assertThat(invokeUnescapeJson("say \\\"hi\\\"")).isEqualTo("say \"hi\"");
    }

    @Test
    void unescape_backslashFollowedByN_notConfused() throws Exception {
      assertThat(invokeUnescapeJson("\\\\n")).isEqualTo("\\n");
    }

    @Test
    void escape_backslash() throws Exception {
      assertThat(invokeEscapeJson("a\\b")).isEqualTo("a\\\\b");
    }

    @Test
    void escape_doubleQuote() throws Exception {
      assertThat(invokeEscapeJson("a\"b")).isEqualTo("a\\\"b");
    }

    @Test
    void escape_controlChars() throws Exception {
      assertThat(invokeEscapeJson("\b\f\r")).isEqualTo("\\b\\f\\r");
    }
  }

  // ── sanitizeAiOutput ──

  @Nested
  class SanitizeAiOutput {

    @Test
    void null_returnsNull() throws Exception {
      assertThat(invokeSanitizeAiOutput(null, "col")).isNull();
    }

    @Test
    void plainValue_unchanged() throws Exception {
      assertThat(invokeSanitizeAiOutput("John Doe", "name")).isEqualTo("John Doe");
    }

    @Test
    void stripsDoubleQuotes() throws Exception {
      assertThat(invokeSanitizeAiOutput("\"hello\"", "col")).isEqualTo("hello");
    }

    @Test
    void stripsSingleQuotes() throws Exception {
      assertThat(invokeSanitizeAiOutput("'world'", "col")).isEqualTo("world");
    }

    @Test
    void stripsNumberedPrefix_dot() throws Exception {
      assertThat(invokeSanitizeAiOutput("1. Value here", "col")).isEqualTo("Value here");
    }

    @Test
    void stripsNumberedPrefix_paren() throws Exception {
      assertThat(invokeSanitizeAiOutput("3) Another value", "col")).isEqualTo("Another value");
    }

    @Test
    void stripsBulletDash() throws Exception {
      assertThat(invokeSanitizeAiOutput("- bullet item", "col")).isEqualTo("bullet item");
    }

    @Test
    void stripsBulletAsterisk() throws Exception {
      assertThat(invokeSanitizeAiOutput("* star item", "col")).isEqualTo("star item");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "Here are some values",
          "Here is a value",
          "Sure, here you go",
          "Sure! Let me help",
          "Certainly, the value is",
          "Of course!",
          "Below are the results",
          "The following values",
          "unique and realistic values",
          "values for the column",
          "values for column name"
        })
    void preamble_returnsNull(String preamble) throws Exception {
      assertThat(invokeSanitizeAiOutput(preamble, "col")).isNull();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "I cannot generate that",
          "I can't do that",
          "I'm sorry, but",
          "I am sorry",
          "Sorry, I cannot",
          "As an AI, I",
          "I'm not able to",
          "I am not able to"
        })
    void refusal_returnsNull(String refusal) throws Exception {
      assertThat(invokeSanitizeAiOutput(refusal, "col")).isNull();
    }

    @Test
    void columnPrefix_stripped() throws Exception {
      assertThat(invokeSanitizeAiOutput("name: John", "name")).isEqualTo("John");
    }

    @Test
    void columnPrefix_equals_stripped() throws Exception {
      assertThat(invokeSanitizeAiOutput("name= Doe", "name")).isEqualTo("Doe");
    }

    @Test
    void columnNameOnly_returnsEmpty() throws Exception {
      String result = invokeSanitizeAiOutput("name", "name");
      assertThat(result == null || result.isEmpty()).isTrue();
    }

    @Test
    void multiline_takesFirstLine() throws Exception {
      assertThat(invokeSanitizeAiOutput("first\nsecond\nthird", "col")).isEqualTo("first");
    }

    @Test
    void nullColumnName_noStripping() throws Exception {
      assertThat(invokeSanitizeAiOutput("some value", null)).isEqualTo("some value");
    }
  }

  // ── normalizeUrl ──

  @Nested
  class NormalizeUrl {

    @Test
    void addsHttpPrefix() throws Exception {
      assertThat(invokeNormalizeUrl("localhost:11434")).isEqualTo("http://localhost:11434");
    }

    @Test
    void preservesHttps() throws Exception {
      assertThat(invokeNormalizeUrl("https://example.com")).isEqualTo("https://example.com");
    }

    @Test
    void stripsTrailingSlash() throws Exception {
      assertThat(invokeNormalizeUrl("http://localhost:11434/"))
          .isEqualTo("http://localhost:11434");
    }

    @Test
    void noChangeIfCorrect() throws Exception {
      assertThat(invokeNormalizeUrl("http://localhost:11434"))
          .isEqualTo("http://localhost:11434");
    }
  }

  // ── findClosingQuote ──

  @Nested
  class FindClosingQuote {

    @Test
    void simpleString() throws Exception {
      assertThat(invokeFindClosingQuote("hello\"", 0)).isEqualTo(5);
    }

    @Test
    void escapedQuoteSkipped() throws Exception {
      assertThat(invokeFindClosingQuote("he\\\"llo\"", 0)).isEqualTo(7);
    }

    @Test
    void noClosingQuote() throws Exception {
      assertThat(invokeFindClosingQuote("no closing quote", 0)).isEqualTo(-1);
    }

    @Test
    void escapedBackslashBeforeQuote() throws Exception {
      assertThat(invokeFindClosingQuote("path\\\\\"", 0)).isEqualTo(6);
    }
  }

  // ── extractRawResponse ──

  @Nested
  class ExtractRawResponse {

    @Test
    void validResponse() throws Exception {
      String body = "{\"response\":\"Hello World\",\"done\":true}";
      assertThat(invokeExtractRawResponse(body)).isEqualTo("Hello World");
    }

    @Test
    void responseWithEscapes() throws Exception {
      String body = "{\"response\":\"line1\\nline2\",\"done\":true}";
      assertThat(invokeExtractRawResponse(body)).isEqualTo("line1\nline2");
    }

    @Test
    void responseWithBackslash() throws Exception {
      String body = "{\"response\":\"C:\\\\Users\\\\test\",\"done\":true}";
      assertThat(invokeExtractRawResponse(body)).isEqualTo("C:\\Users\\test");
    }

    @Test
    void missingResponseKey_throws() {
      assertThatThrownBy(() -> invokeExtractRawResponse("{\"error\":\"bad\"}"))
          .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void missingClosingQuote_throws() {
      assertThatThrownBy(() -> invokeExtractRawResponse("{\"response\":\"no end"))
          .hasCauseInstanceOf(IOException.class);
    }
  }

  // ── Constructor ──

  @Test
  void timeout_clamped_toMinimum() {
    OllamaClient lowTimeout = new OllamaClient("http://localhost", "model", 1);
    assertThatCode(() -> lowTimeout.ping()).doesNotThrowAnyException();
  }
}
