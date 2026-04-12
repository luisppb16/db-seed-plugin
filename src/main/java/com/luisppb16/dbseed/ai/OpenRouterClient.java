/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
public class OpenRouterClient implements AiClient {

  private static final String BASE_URL = "https://openrouter.ai/api/v1";
  private static final String MODELS_ENDPOINT = BASE_URL + "/models";
  private static final String CHAT_ENDPOINT = BASE_URL + "/chat/completions";

  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final int MIN_REQUEST_TIMEOUT_SECONDS = 10;
  private static final int PING_TIMEOUT_SECONDS = 5;
  private static final int LIST_MODELS_TIMEOUT_SECONDS = 10;

  private static final double DEFAULT_TEMPERATURE = 0.5;

  private static final Gson GSON = new Gson();

  private static final ExecutorService HTTP_EXECUTOR =
      Executors.newFixedThreadPool(
          Runtime.getRuntime().availableProcessors(),
          r -> {
            Thread t = new Thread(r, "openrouter-http");
            t.setDaemon(true);
            return t;
          });

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
          .executor(HTTP_EXECUTOR)
          .build();

  private final String apiKey;
  private final String modelName;
  private final int requestTimeoutSeconds;

  public OpenRouterClient(
      @NotNull final String apiKey,
      @NotNull final String modelName,
      final int requestTimeoutSeconds) {
    this.apiKey = apiKey;
    this.modelName = modelName;
    this.requestTimeoutSeconds = Math.max(MIN_REQUEST_TIMEOUT_SECONDS, requestTimeoutSeconds);
  }

  @Override
  public CompletableFuture<Void> ping() {
    try {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(MODELS_ENDPOINT))
              .timeout(Duration.ofSeconds(PING_TIMEOUT_SECONDS))
              .header("Authorization", "Bearer " + apiKey)
              .GET()
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .thenAccept(
              response -> {
                if (response.statusCode() != 200) {
                  throw new AiClientException(
                      "OpenRouter returned status code: " + response.statusCode());
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
              .uri(URI.create(MODELS_ENDPOINT))
              .timeout(Duration.ofSeconds(LIST_MODELS_TIMEOUT_SECONDS))
              .header("Authorization", "Bearer " + apiKey)
              .GET()
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(
              response -> {
                if (response.statusCode() != 200) {
                  throw new AiClientException(
                      "OpenRouter returned status code: " + response.statusCode());
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
      if (json.has("data") && json.get("data").isJsonArray()) {
        models.addAll(
            json.getAsJsonArray("data").asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(obj -> obj.has("id"))
                .map(obj -> obj.get("id").getAsString())
                .sorted()
                .toList());
      }
    } catch (final Exception e) {
      log.warn("Failed to parse OpenRouter models response", e);
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

    final String prompt = AiClient.buildPrompt(applicationContext, tableName, columnName, sqlType, wordCount, count);
    final int numPredict = AiClient.computeNumPredict(count, wordCount, sqlType);

    try {
      final String requestBody = buildChatRequestBody(prompt, DEFAULT_TEMPERATURE, numPredict);

      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(CHAT_ENDPOINT))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + apiKey)
              .header("HTTP-Referer", "https://github.com/luisppb16/db-seed-plugin")
              .header("X-Title", "DBSeed4SQL")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(
              response -> {
                if (response.statusCode() != 200) {
                  log.warn("OpenRouter error {}: {}", response.statusCode(), response.body());
                  throw new AiClientException("OpenRouter error: " + response.statusCode());
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
      final String raw = extractContentFromChatResponse(responseBody);
      return raw.lines()
          .map(line -> AiClient.sanitizeAiOutput(line, columnName))
          .filter(Objects::nonNull)
          .filter(s -> !s.isBlank())
          .distinct()
          .toList();
    } catch (Exception e) {
      log.warn("Failed to parse OpenRouter batch response", e);
      return List.of();
    }
  }

  private String extractContentFromChatResponse(final String responseBody) {
    final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
    if (json.has("choices") && json.get("choices").isJsonArray()) {
      final JsonArray choices = json.getAsJsonArray("choices");
      if (!choices.isEmpty()) {
        final JsonObject firstChoice = choices.get(0).getAsJsonObject();
        if (firstChoice.has("message") && firstChoice.getAsJsonObject("message").has("content")) {
          return firstChoice.getAsJsonObject("message").get("content").getAsString();
        }
      }
    }
    throw new RuntimeException("Invalid response from OpenRouter: missing 'choices[0].message.content'");
  }

  private String buildChatRequestBody(
      final String prompt, final double temperature, final int maxTokens) {
    final JsonObject systemMessage = new JsonObject();
    systemMessage.addProperty("role", "system");
    systemMessage.addProperty("content", SYSTEM_ROLE);

    final JsonObject userMessage = new JsonObject();
    userMessage.addProperty("role", "user");
    userMessage.addProperty("content", prompt);

    final JsonArray messages = new JsonArray();
    messages.add(systemMessage);
    messages.add(userMessage);

    final JsonObject body = new JsonObject();
    body.addProperty("model", modelName);
    body.add("messages", messages);
    body.addProperty("temperature", temperature);
    body.addProperty("max_tokens", maxTokens);
    body.addProperty("stream", false);

    return GSON.toJson(body);
  }

  @Override
  public CompletableFuture<Void> warmModel() {
    try {
      final JsonObject systemMessage = new JsonObject();
      systemMessage.addProperty("role", "system");
      systemMessage.addProperty("content", SYSTEM_ROLE);

      final JsonObject userMessage = new JsonObject();
      userMessage.addProperty("role", "user");
      userMessage.addProperty("content", "Respond with exactly one word: ready");

      final JsonArray messages = new JsonArray();
      messages.add(systemMessage);
      messages.add(userMessage);

      final JsonObject body = new JsonObject();
      body.addProperty("model", modelName);
      body.add("messages", messages);
      body.addProperty("max_tokens", 5);
      body.addProperty("stream", false);

      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(CHAT_ENDPOINT))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + apiKey)
              .header("HTTP-Referer", "https://github.com/luisppb16/db-seed-plugin")
              .header("X-Title", "DBSeed4SQL")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .thenAccept(
              response -> {
                if (response.statusCode() == 200) {
                  log.info("OpenRouter model '{}' warmed up successfully", modelName);
                } else {
                  log.warn("OpenRouter model warm-up returned status {}", response.statusCode());
                }
              });
    } catch (Exception e) {
      log.warn("Failed to warm OpenRouter model: {}", e.getMessage());
      return CompletableFuture.completedFuture(null);
    }
  }

  public static class AiClientException extends RuntimeException {
    public AiClientException(final String message) {
      super(message);
    }
  }
}