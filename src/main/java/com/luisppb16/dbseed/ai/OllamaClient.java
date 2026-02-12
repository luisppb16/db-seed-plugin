/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ai;

import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * REST client for interacting with the Ollama API.
 */
public class OllamaClient {

  private static final Logger LOG = Logger.getInstance(OllamaClient.class);
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
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
    this.requestTimeoutSeconds = Math.max(10, requestTimeoutSeconds);
  }

  private static String normalizeUrl(String url) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      url = "http://" + url;
    }
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  public CompletableFuture<Void> ping() {
    String url = normalizeUrl(ollamaUrl);
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .thenAccept(response -> {
            if (response.statusCode() != 200) {
              throw new RuntimeException(
                  "Ollama returned status code: " + response.statusCode());
            }
          });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public CompletableFuture<List<String>> listModels() {
    String url = normalizeUrl(ollamaUrl);

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "/api/tags"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(response -> {
            if (response.statusCode() != 200) {
              throw new RuntimeException("Ollama returned status code: " + response.statusCode());
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
      LOG.warn("Failed to parse Ollama models response", e);
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
    final int effectiveWordCount = Math.max(1, wordCount);
    final String contextLine =
        (applicationContext != null && !applicationContext.isBlank())
            ? "Application context: " + applicationContext + "\n"
            : "";

    try {
      String prompt;
      int numPredict;

      if (effectiveWordCount == 1) {
        prompt =
            String.format(
                "%sTable: %s | Column: %s (Type: %s)\n"
                    + "Generate exactly ONE short, realistic value for this database column.\n"
                    + "Rules:\n"
                    + "- Return ONLY the raw value, nothing else\n"
                    + "- No quotes, no explanations, no lists, no bullet points\n"
                    + "- Maximum 50 characters\n"
                    + "- Single line only\n"
                    + "Value:",
                contextLine, tableName, columnName, sqlType);
        numPredict = 30;
      } else {
        prompt =
            String.format(
                "%sTable: %s | Column: %s (Type: %s)\n"
                    + "Generate a realistic value of approximately %d words for this database column.\n"
                    + "Rules:\n"
                    + "- Return ONLY the raw value, nothing else\n"
                    + "- No quotes, no explanations, no lists, no bullet points\n"
                    + "- Approximately %d words\n"
                    + "Value:",
                contextLine, tableName, columnName, sqlType,
                effectiveWordCount, effectiveWordCount);
        numPredict = Math.max(30, effectiveWordCount * 3);
      }

      String requestBody =
          String.format(
              "{\"model\": \"%s\", \"prompt\": \"%s\", \"stream\": false, \"options\": {\"temperature\": 0.8, \"num_predict\": %d}}",
              modelName, escapeJson(prompt), numPredict);

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
              throw new RuntimeException("Ollama error: " + response.statusCode());
            }
            return response.body();
          })
          .thenApply(this::parseResponse);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private String parseResponse(String responseBody) {
    try {
      int responseKeyIndex = responseBody.indexOf("\"response\":\"");
      if (responseKeyIndex == -1) {
        throw new IOException("Invalid response from Ollama: " + responseBody);
      }
      int startIndex = responseKeyIndex + 12;
      int endIndex = findClosingQuote(responseBody, startIndex);
      if (endIndex == -1) {
        throw new IOException("Invalid response from Ollama: " + responseBody);
      }
      String raw = unescapeJson(responseBody.substring(startIndex, endIndex));
      return sanitizeAiOutput(raw);
    } catch (Exception e) {
      LOG.warn("Failed to parse Ollama response", e);
      return null;
    }
  }

  private static int findClosingQuote(String json, int startIndex) {
    for (int i = startIndex; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        return i;
      }
    }
    return -1;
  }

  private static String sanitizeAiOutput(String value) {
    if (value == null) return null;
    String cleaned = value.lines().findFirst().orElse("").trim();
    if (cleaned.length() >= 2
        && ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
            || (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
      cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
    }
    if (cleaned.startsWith("- ")) {
      cleaned = cleaned.substring(2).trim();
    }
    return cleaned;
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
    return text.replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\t", "\t")
        .replace("\\r", "\r")
        .replace("\\b", "\b")
        .replace("\\f", "\f")
        .replace("\\\\", "\\");
  }
}
