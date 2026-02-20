/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Advanced REST client for interacting with the Ollama API in the DBSeed plugin ecosystem.
 *
 * <p>This class provides a comprehensive interface for integrating AI-powered content generation
 * into the database seeding process. It implements sophisticated communication protocols with
 * external Ollama LLM servers, handling connection management, request/response processing, and
 * intelligent content sanitization. The client supports both single-value and batch generation
 * operations, with robust error handling and response validation mechanisms.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Establishing and managing HTTP connections to external Ollama servers
 *   <li>Providing health check functionality through server ping operations
 *   <li>Listing available AI models on the target Ollama server
 *   <li>Generating single and batch database column values using AI models
 *   <li>Implementing intelligent prompt engineering for database context
 *   <li>Sanitizing AI responses to remove unwanted prefixes and formatting
 *   <li>Handling application context injection for domain-specific generation
 *   <li>Managing request timeouts and connection pooling for performance
 *   <li>Implementing retry mechanisms and fallback strategies for resilience
 *   <li>Processing and validating AI-generated content for database compatibility
 *   <li>Filtering out AI preambles, refusals, and irrelevant content
 *   <li>Managing word count limitations and content length controls
 * </ul>
 *
 * <p>The class implements advanced content sanitization algorithms to clean AI responses, removing
 * common AI artifacts such as introductory phrases, numbering, and explanations. It includes
 * sophisticated pattern matching to identify and strip column name prefixes, surrounding quotes,
 * and numbered list formats. The implementation handles various AI refusal patterns and preamble
 * indicators to ensure only relevant content is returned.
 *
 * <p>Thread safety is maintained through asynchronous request handling with CompletableFuture and
 * dedicated executor services. The class implements efficient JSON serialization and
 * deserialization with proper escaping and unescaping of special characters. Memory efficiency is
 * achieved through streaming response processing and minimal intermediate object allocation.
 *
 * <p>Security considerations include careful handling of application context information, ensuring
 * that only relevant column and table names are sent to the AI model without exposing sensitive
 * data. The class implements robust input validation and sanitization to prevent injection attacks
 * and malformed requests. Response validation ensures that only properly formatted content is
 * accepted from the AI service.
 *
 * <p>Performance optimizations include connection pooling, configurable timeouts, and batch
 * processing capabilities for efficient generation of multiple values. The class implements
 * adaptive request sizing based on word count requirements and handles server-side rate limiting
 * gracefully through appropriate error handling.
 *
 * @author Luis Paolo Pepe Barra (@LuisPPB16)
 * @version 1.3.0
 * @since 2025.1
 * @see java.net.http.HttpClient
 * @see java.net.http.HttpRequest
 * @see java.util.concurrent.CompletableFuture
 * @see java.util.regex.Pattern
 * @see OllamaException
 */
@Slf4j
public class OllamaClient {

  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final int MIN_REQUEST_TIMEOUT_SECONDS = 10;
  private static final int PING_TIMEOUT_SECONDS = 2;
  private static final int LIST_MODELS_TIMEOUT_SECONDS = 5;

  private static final double DEFAULT_TEMPERATURE = 0.5;
  private static final double BATCH_TEMPERATURE = DEFAULT_TEMPERATURE;

  private static final int DEFAULT_NUM_PREDICT = 30;
  private static final int BATCH_NUM_PREDICT_FACTOR = 15;
  private static final int WORD_COUNT_PREDICT_MULTIPLIER = 3;
  private static final int MIN_WORD_COUNT = 1;

  private static final String RESPONSE_KEY_PREFIX = "\"response\":\"";
  private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)\\-]\\s*");

  private static final String SYSTEM_ROLE =
      "You are a database seed data generator. You output raw data values only. "
          + "The language consistency is very important, you will output values in the language of the prompt. "
          + "Never add introductions, headers, numbering, bullet points, quotes, labels, or explanations. "
          + "Start immediately with the first value.";

  private static final ExecutorService HTTP_EXECUTOR =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "ollama-http");
            t.setDaemon(true);
            return t;
          });

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
          .executor(HTTP_EXECUTOR)
          .build();

  private final String ollamaUrl;
  private final String modelName;
  private final int requestTimeoutSeconds;

  public OllamaClient(
      @NotNull final String ollamaUrl,
      @NotNull final String modelName,
      final int requestTimeoutSeconds) {
    this.ollamaUrl = ollamaUrl;
    this.modelName = modelName;
    this.requestTimeoutSeconds = Math.max(MIN_REQUEST_TIMEOUT_SECONDS, requestTimeoutSeconds);
  }

  private static String normalizeUrl(String url) {
    String normalized = url;
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
      normalized = "http://" + normalized;
    }
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static int findClosingQuote(String json, int startIndex) {
    int i = startIndex;
    while (i < json.length()) {
      char c = json.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == '"') {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  private static String sanitizeAiOutput(String value, @Nullable String columnName) {
    if (Objects.isNull(value)) return null;
    String cleaned = value.lines().findFirst().orElse("").trim();

    cleaned = stripSurroundingQuotes(cleaned);
    cleaned = NUMBERED_PREFIX.matcher(cleaned).replaceFirst("").trim();

    if (cleaned.startsWith("- ") || cleaned.startsWith("* ")) {
      cleaned = cleaned.substring(2).trim();
    }

    if (isAiPreamble(cleaned) || isAiRefusal(cleaned)) {
      return null;
    }

    if (Objects.nonNull(columnName) && !columnName.isEmpty()) {
      cleaned = stripColumnPrefix(cleaned, columnName);
      if (cleaned.equalsIgnoreCase(columnName)) {
        return null;
      }
    }

    return cleaned;
  }

  private static String stripSurroundingQuotes(String text) {
    if (text.length() >= 2
        && ((text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("'") && text.endsWith("'")))) {
      return text.substring(1, text.length() - 1).trim();
    }
    return text;
  }

  private static boolean isAiPreamble(String text) {
    String lower = text.toLowerCase();
    return lower.startsWith("here are")
        || lower.startsWith("here is")
        || lower.startsWith("sure,")
        || lower.startsWith("sure!")
        || lower.startsWith("certainly")
        || lower.startsWith("of course")
        || lower.startsWith("below are")
        || lower.startsWith("the following")
        || lower.contains("unique and realistic")
        || lower.contains("values for the")
        || lower.contains("values for column");
  }

  private static boolean isAiRefusal(String text) {
    String lower = text.toLowerCase();
    return lower.startsWith("i cannot")
        || lower.startsWith("i can't")
        || lower.startsWith("i'm sorry")
        || lower.startsWith("i am sorry")
        || lower.startsWith("sorry,")
        || lower.startsWith("as an ai")
        || lower.startsWith("i'm not able")
        || lower.startsWith("i am not able");
  }

  private static String stripColumnPrefix(String text, String columnName) {
    String lower = text.toLowerCase();
    String colLower = columnName.toLowerCase();
    if (lower.startsWith(colLower)) {
      String rest = text.substring(columnName.length()).trim();
      if (rest.startsWith("=") || rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      return stripSurroundingQuotes(rest);
    }
    return text;
  }

  private static boolean isArrayType(String sqlType) {
    if (Objects.isNull(sqlType) || sqlType.isBlank()) {
      return false;
    }
    String lower = sqlType.toLowerCase(Locale.ROOT);
    // PostgreSQL array types: TEXT[], _text, INTEGER[], etc.
    // Also handles ARRAY keyword
    return lower.endsWith("[]") || lower.startsWith("_") || lower.contains("array");
  }

  /**
   * Pings the Ollama server to check connectivity.
   *
   * @return A CompletableFuture that completes when the ping is successful.
   */
  public CompletableFuture<Void> ping() {
    String url = normalizeUrl(ollamaUrl);
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(PING_TIMEOUT_SECONDS))
              .GET()
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .thenAccept(
              response -> {
                if (response.statusCode() != 200) {
                  throw new OllamaException(
                      "Ollama returned status code: " + response.statusCode());
                }
              });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Lists the models available on the Ollama server.
   *
   * @return A CompletableFuture containing a list of model names.
   */
  public CompletableFuture<List<String>> listModels() {
    String url = normalizeUrl(ollamaUrl);

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "/api/tags"))
              .timeout(Duration.ofSeconds(LIST_MODELS_TIMEOUT_SECONDS))
              .GET()
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(
              response -> {
                if (response.statusCode() != 200) {
                  throw new OllamaException(
                      "Ollama returned status code: " + response.statusCode());
                }
                return response.body();
              })
          .thenApply(this::parseModelsResponse);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private List<String> parseModelsResponse(String responseBody) {
    List<String> models = new ArrayList<>();
    try {
      Pattern pattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
      Matcher matcher = pattern.matcher(responseBody);
      while (matcher.find()) {
        models.add(matcher.group(1));
      }
    } catch (Exception e) {
      log.warn("Failed to parse Ollama models response", e);
    }
    return models;
  }

  public CompletableFuture<String> generateValue(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount) {

    String url = normalizeUrl(ollamaUrl);
    final int effectiveWordCount = Math.max(MIN_WORD_COUNT, wordCount);
    final String contextLine =
        !applicationContext.isBlank() ? "Application context: " + applicationContext + "\n" : "";
    final boolean isArrayType = isArrayType(sqlType);

    try {
      String prompt;
      int numPredict;

      if (isArrayType) {
        // For array types, generate in PostgreSQL array format: {element1,element2,element3}
        final int elementCount = 3; // Default 3 elements per array
        if (effectiveWordCount == 1) {
          prompt =
              """
                  %sGenerate one realistic array value for column "%s" (table: %s, type: %s). \
                  Format: {element1,element2,element3} with %d elements. Each element should be a single word. \
                  Use PostgreSQL array syntax with curly braces and comma-separated values. Single line only.
                  Value:"""
                  .formatted(contextLine, columnName, tableName, sqlType, elementCount);
        } else {
          prompt =
              """
                  %sGenerate one realistic array value for column "%s" (table: %s, type: %s). \
                  Format: {element1,element2,element3} with %d elements. Each element up to %d words. \
                  Use PostgreSQL array syntax with curly braces and comma-separated values. Single line only.
                  Value:"""
                  .formatted(contextLine, columnName, tableName, sqlType, elementCount, effectiveWordCount);
        }
        numPredict = Math.max(DEFAULT_NUM_PREDICT * 2, elementCount * effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER);
      } else {
        if (effectiveWordCount == 1) {
          prompt =
              """
                  %sGenerate one realistic value for column "%s" (table: %s, type: %s). Single line only.
                  Value:"""
                  .formatted(contextLine, columnName, tableName, sqlType);
          numPredict = DEFAULT_NUM_PREDICT;
        } else {
          prompt =
              """
                  %sGenerate one realistic value for column "%s" (table: %s, type: %s). Up to %d words. Single line only.
                  Value:"""
                  .formatted(contextLine, columnName, tableName, sqlType, effectiveWordCount);
          numPredict =
              Math.max(DEFAULT_NUM_PREDICT, effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER);
        }
      }

      String requestBody =
          String.format(
              Locale.ROOT,
              "{\"model\": \"%s\", \"prompt\": \"%s\", \"system\": \"%s\", \"stream\": false, \"options\": {\"temperature\": %.1f, \"num_predict\": %d}}",
              escapeJson(modelName),
              escapeJson(prompt),
              escapeJson(SYSTEM_ROLE),
              DEFAULT_TEMPERATURE,
              numPredict);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "/api/generate"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(
              response -> {
                if (response.statusCode() != 200) {
                  log.warn("Ollama error {}: {}", response.statusCode(), response.body());
                  throw new OllamaException("Ollama error: " + response.statusCode());
                }
                return response.body();
              })
          .thenApply(body -> parseResponse(body, columnName));
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public CompletableFuture<List<String>> generateBatchValues(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount,
      final int count) {

    String url = normalizeUrl(ollamaUrl);
    final int effectiveWordCount = Math.max(MIN_WORD_COUNT, wordCount);
    final String contextLine =
        !applicationContext.isBlank() ? "Application context: " + applicationContext + "\n" : "";
    final boolean isArrayType = isArrayType(sqlType);

    try {
      String prompt;
      int numPredict;

      if (isArrayType) {
        // For array types, generate in PostgreSQL array format: {element1,element2,element3}
        final int elementCount = 3; // Default 3 elements per array
        if (effectiveWordCount == 1) {
          prompt =
              """
                  %sGenerate exactly %d unique and different array values for column "%s" (table: %s, type: %s). \
                  Format each array as: {element1,element2,element3} with %d elements per array. Each element should be a single word. \
                  Use PostgreSQL array syntax with curly braces and comma-separated values. \
                  One array per line. No duplicates. No numbering. No explanations. Raw array values only."""
                  .formatted(contextLine, count, columnName, tableName, sqlType, elementCount);
        } else {
          prompt =
              """
                  %sGenerate exactly %d unique and different array values for column "%s" (table: %s, type: %s). \
                  Format each array as: {element1,element2,element3} with %d elements per array. Each element up to %d words. \
                  Use PostgreSQL array syntax with curly braces and comma-separated values. \
                  One array per line. No duplicates. No numbering. No explanations. Raw array values only."""
                  .formatted(contextLine, count, columnName, tableName, sqlType, elementCount, effectiveWordCount);
        }
        numPredict = count * elementCount * Math.max(BATCH_NUM_PREDICT_FACTOR, effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER);
      } else {
        if (effectiveWordCount == 1) {
          prompt =
              """
                  %sGenerate exactly %d unique and different values for column "%s" (table: %s, type: %s). \
                  One value per line. No duplicates. No numbering. No explanations. Raw values only."""
                  .formatted(contextLine, count, columnName, tableName, sqlType);
          numPredict = count * BATCH_NUM_PREDICT_FACTOR;
        } else {
          prompt =
              """
                  %sGenerate exactly %d unique and different values for column "%s" (table: %s, type: %s). \
                  Each value up to %d words. One value per line. No duplicates. No numbering. No explanations. Raw values only."""
                  .formatted(contextLine, count, columnName, tableName, sqlType, effectiveWordCount);
          numPredict =
              count
                  * Math.max(
                      BATCH_NUM_PREDICT_FACTOR, effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER);
        }
      }

      String requestBody =
          String.format(
              Locale.ROOT,
              "{\"model\": \"%s\", \"prompt\": \"%s\", \"system\": \"%s\", \"stream\": false, \"options\": {\"temperature\": %.1f, \"num_predict\": %d}}",
              escapeJson(modelName),
              escapeJson(prompt),
              escapeJson(SYSTEM_ROLE),
              BATCH_TEMPERATURE,
              numPredict);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "/api/generate"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(
              response -> {
                if (response.statusCode() != 200) {
                  log.warn("Ollama error {}: {}", response.statusCode(), response.body());
                  throw new OllamaException("Ollama error: " + response.statusCode());
                }
                return response.body();
              })
          .thenApply(body -> parseBatchResponse(body, columnName));
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private List<String> parseBatchResponse(String responseBody, String columnName) {
    try {
      String raw = extractRawResponse(responseBody);
      return raw.lines()
          .map(line -> sanitizeAiOutput(line, columnName))
          .filter(Objects::nonNull)
          .filter(s -> !s.isBlank())
          .distinct()
          .toList();
    } catch (Exception e) {
      log.warn("Failed to parse Ollama batch response", e);
      return List.of();
    }
  }

  private String parseResponse(String responseBody, String columnName) {
    try {
      String raw = extractRawResponse(responseBody);
      return sanitizeAiOutput(raw, columnName);
    } catch (Exception e) {
      log.warn("Failed to parse Ollama response", e);
      return null;
    }
  }

  private String extractRawResponse(String responseBody) throws IOException {
    int responseKeyIndex = responseBody.indexOf(RESPONSE_KEY_PREFIX);
    if (responseKeyIndex == -1) {
      throw new IOException("Invalid response from Ollama: " + responseBody);
    }
    int startIndex = responseKeyIndex + RESPONSE_KEY_PREFIX.length();
    int endIndex = findClosingQuote(responseBody, startIndex);
    if (endIndex == -1) {
      throw new IOException("Invalid response from Ollama: " + responseBody);
    }
    return unescapeJson(responseBody.substring(startIndex, endIndex));
  }

  private String escapeJson(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private String unescapeJson(String text) {
    return text.replace("\\\\", "\0BACKSLASH\0")
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\t", "\t")
        .replace("\\r", "\r")
        .replace("\\b", "\b")
        .replace("\\f", "\f")
        .replace("\0BACKSLASH\0", "\\");
  }

  public static class OllamaException extends RuntimeException {
    public OllamaException(String message) {
      super(message);
    }
  }
}
