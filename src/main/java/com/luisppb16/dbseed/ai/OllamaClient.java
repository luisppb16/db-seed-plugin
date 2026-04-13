/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class OllamaClient implements AiClient {

  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final int MIN_REQUEST_TIMEOUT_SECONDS = 10;
  private static final int PING_TIMEOUT_SECONDS = 2;
  private static final int LIST_MODELS_TIMEOUT_SECONDS = 5;

  private static final double DEFAULT_TEMPERATURE = 0.5;

  private static final String DEFAULT_KEEP_ALIVE = "10m";

  private static final Gson GSON = new Gson();

  private static final ExecutorService HTTP_EXECUTOR =
      Executors.newFixedThreadPool(
          Runtime.getRuntime().availableProcessors(),
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

  private final String normalizedUrl;
  private final String modelName;
  private final int requestTimeoutSeconds;

  public OllamaClient(
      @NotNull final String ollamaUrl,
      @NotNull final String modelName,
      final int requestTimeoutSeconds) {
    this.normalizedUrl = normalizeUrl(ollamaUrl);
    this.modelName = modelName;
    this.requestTimeoutSeconds = Math.max(MIN_REQUEST_TIMEOUT_SECONDS, requestTimeoutSeconds);
  }

  static String normalizeUrl(final String url) {
    String normalized = url;
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
      normalized = "http://" + normalized;
    }
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  @Override
  public CompletableFuture<Void> ping() {
    try {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedUrl))
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

  @Override
  public CompletableFuture<List<String>> listModels() {
    try {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedUrl + "/api/tags"))
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

  private List<String> parseModelsResponse(final String responseBody) {
    final List<String> models = new ArrayList<>();
    try {
      final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
      if (json.has("models") && json.get("models").isJsonArray()) {
        models.addAll(
            json.getAsJsonArray("models").asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(obj -> obj.has("name"))
                .map(obj -> obj.get("name").getAsString())
                .sorted()
                .toList());
      }
    } catch (final Exception e) {
      log.warn("Failed to parse Ollama models response", e);
    }
    return models;
  }

  @Override
  public CompletableFuture<List<String>> generateBatchValues(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount,
      final int count) {

    final String prompt =
        AiClient.buildPrompt(applicationContext, tableName, columnName, sqlType, wordCount, count);
    final int numPredict = AiClient.computeNumPredict(count, wordCount, sqlType);

    try {
      final String requestBody = buildGenerateRequestBody(prompt, DEFAULT_TEMPERATURE, numPredict);

      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedUrl + "/api/generate"))
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

  private List<String> parseBatchResponse(final String responseBody, final String columnName) {
    try {
      final String raw = extractRawResponse(responseBody);
      return raw.lines()
          .map(line -> AiClient.sanitizeAiOutput(line, columnName))
          .filter(Objects::nonNull)
          .filter(s -> !s.isBlank())
          .distinct()
          .toList();
    } catch (Exception e) {
      log.warn("Failed to parse Ollama batch response", e);
      return List.of();
    }
  }

  private String extractRawResponse(final String responseBody) {
    try {
      final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
      if (json.has("response")) {
        return json.get("response").getAsString();
      }
      if (json.has("message") && json.getAsJsonObject("message").has("content")) {
        return json.getAsJsonObject("message").get("content").getAsString();
      }
      throw new RuntimeException("Invalid response from Ollama: missing 'response' field");
    } catch (final Exception e) {
      if (e instanceof RuntimeException) throw (RuntimeException) e;
      throw new RuntimeException("Failed to parse Ollama response: " + e.getMessage(), e);
    }
  }

  private String buildGenerateRequestBody(
      final String prompt, final double temperature, final int numPredict) {
    final JsonObject options = new JsonObject();
    options.addProperty("temperature", temperature);
    options.addProperty("num_predict", numPredict);

    final JsonObject body = new JsonObject();
    body.addProperty("model", modelName);
    body.addProperty("prompt", prompt);
    body.addProperty("system", SYSTEM_ROLE);
    body.addProperty("stream", false);
    body.addProperty("keep_alive", DEFAULT_KEEP_ALIVE);
    body.add("options", options);

    return GSON.toJson(body);
  }

  @Override
  public CompletableFuture<Void> warmModel() {
    try {
      final JsonObject options = new JsonObject();
      options.addProperty("num_predict", 1);

      final JsonObject body = new JsonObject();
      body.addProperty("model", modelName);
      body.addProperty("prompt", "");
      body.addProperty("system", SYSTEM_ROLE);
      body.addProperty("stream", false);
      body.addProperty("keep_alive", DEFAULT_KEEP_ALIVE);
      body.add("options", options);

      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedUrl + "/api/generate"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .thenAccept(
              response -> {
                if (response.statusCode() == 200) {
                  log.info("Model '{}' warmed up successfully", modelName);
                } else {
                  log.warn("Model warm-up returned status {}", response.statusCode());
                }
              });
    } catch (Exception e) {
      log.warn("Failed to warm model: {}", e.getMessage());
      return CompletableFuture.completedFuture(null);
    }
  }

  public static class OllamaException extends RuntimeException {
    public OllamaException(final String message) {
      super(message);
    }
  }
}
