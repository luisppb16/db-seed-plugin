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
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * REST client for interacting with the Ollama API.
 * Provides methods to ping the server, list available models, and generate seed data values.
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
          + "Never add introductions, headers, numbering, bullet points, quotes, labels, or explanations. "
          + "Start immediately with the first value.";

  private static final ExecutorService HTTP_EXECUTOR =
      Executors.newCachedThreadPool(r -> {
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

  /**
   * Constructs a new OllamaClient.
   *
   * @param ollamaUrl             The base URL of the Ollama server.
   * @param modelName             The name of the model to use for generation.
   * @param requestTimeoutSeconds The timeout for generation requests in seconds.
   */
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
          .thenAccept(response -> {
            if (response.statusCode() != 200) {
              throw new OllamaException("Ollama returned status code: " + response.statusCode());
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
          .thenApply(response -> {
            if (response.statusCode() != 200) {
              throw new OllamaException("Ollama returned status code: " + response.statusCode());
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

  /**
   * Generates a single value for a database column using the AI model.
   *
   * @param applicationContext Contextual information about the application.
   * @param tableName          The name of the table.
   * @param columnName         The name of the column.
   * @param sqlType            The SQL type of the column.
   * @param wordCount          The maximum number of words to generate.
   * @return A CompletableFuture containing the generated value.
   */
  public CompletableFuture<String> generateValue(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount) {

    String url = normalizeUrl(ollamaUrl);
    final int effectiveWordCount = Math.max(MIN_WORD_COUNT, wordCount);
    final String contextLine = !applicationContext.isBlank()
            ? "Application context: " + applicationContext + "\n"
            : "";

    try {
      String prompt;
      int numPredict;

      if (effectiveWordCount == 1) {
        prompt = """
                %sGenerate one realistic value for column "%s" (table: %s, type: %s). Single line only.
                Value:""".formatted(contextLine, columnName, tableName, sqlType);
        numPredict = DEFAULT_NUM_PREDICT;
      } else {
        prompt = """
                %sGenerate one realistic value for column "%s" (table: %s, type: %s). Up to %d words. Single line only.
                Value:""".formatted(contextLine, columnName, tableName, sqlType, effectiveWordCount);
        numPredict = Math.max(DEFAULT_NUM_PREDICT, effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER);
      }

      String requestBody =
          String.format(Locale.ROOT,
              "{\"model\": \"%s\", \"prompt\": \"%s\", \"system\": \"%s\", \"stream\": false, \"options\": {\"temperature\": %.1f, \"num_predict\": %d}}",
              modelName, escapeJson(prompt), escapeJson(SYSTEM_ROLE), DEFAULT_TEMPERATURE, numPredict);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "/api/generate"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(response -> {
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

  /**
   * Generates a batch of values for a database column using the AI model.
   *
   * @param applicationContext Contextual information about the application.
   * @param tableName          The name of the table.
   * @param columnName         The name of the column.
   * @param sqlType            The SQL type of the column.
   * @param wordCount          The maximum number of words per value.
   * @param count              The number of values to generate.
   * @return A CompletableFuture containing a list of generated values.
   */
  public CompletableFuture<List<String>> generateBatchValues(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount,
      final int count) {

    String url = normalizeUrl(ollamaUrl);
    final int effectiveWordCount = Math.max(MIN_WORD_COUNT, wordCount);
    final String contextLine = !applicationContext.isBlank()
            ? "Application context: " + applicationContext + "\n"
            : "";

    try {
      String prompt;
      int numPredict;

      if (effectiveWordCount == 1) {
        prompt = """
                %sGenerate exactly %d unique and different values for column "%s" (table: %s, type: %s). \
                One value per line. No duplicates. No numbering. No explanations. Raw values only."""
                .formatted(contextLine, count, columnName, tableName, sqlType);
        numPredict = count * BATCH_NUM_PREDICT_FACTOR;
      } else {
        prompt = """
                %sGenerate exactly %d unique and different values for column "%s" (table: %s, type: %s). \
                Each value up to %d words. One value per line. No duplicates. No numbering. No explanations. Raw values only."""
                .formatted(contextLine, count, columnName, tableName, sqlType, effectiveWordCount);
        numPredict = count * Math.max(BATCH_NUM_PREDICT_FACTOR, effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER);
      }

      String requestBody =
          String.format(Locale.ROOT,
              "{\"model\": \"%s\", \"prompt\": \"%s\", \"system\": \"%s\", \"stream\": false, \"options\": {\"temperature\": %.1f, \"num_predict\": %d}}",
              modelName, escapeJson(prompt), escapeJson(SYSTEM_ROLE), BATCH_TEMPERATURE, numPredict);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "/api/generate"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(response -> {
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

  /**
   * Custom exception for Ollama API errors.
   */
  public static class OllamaException extends RuntimeException {
    public OllamaException(String message) {
      super(message);
    }
  }
}
