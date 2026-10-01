/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * AI client for the native Ollama API.
 *
 * <p>It speaks Ollama's own protocol: {@code GET /} to check that the server answers, {@code GET
 * /api/tags} to list the served models and {@code POST /api/generate} to generate a batch. All the
 * shared machinery — connection handling, streaming, the inactivity watchdog, the JSON scanner and
 * the sanitization of the answer — lives in {@link AbstractAiClient}; this class only declares what
 * is specific to Ollama.
 *
 * <p>Requests pin the {@code {"values": [...]}} JSON Schema through the {@code format} parameter,
 * so the sampler cannot emit prose in place of values, and ask for {@code stream:true} so values
 * are consumed as they arrive and the progress callback fires per value. {@code keep_alive} keeps
 * the model resident between batches, and {@code think:false} stops a reasoning model from spending
 * the token budget on hidden chain-of-thought.
 *
 * @see AbstractAiClient
 * @see AiClient
 * @see AiClientException
 */
@Slf4j
public class OllamaClient extends AbstractAiClient {

  /** How long Ollama keeps the model loaded in VRAM after a request. */
  private static final String DEFAULT_KEEP_ALIVE = "10m";

  public OllamaClient(
      @NotNull final String ollamaUrl,
      @NotNull final String modelName,
      final int requestTimeoutSeconds) {
    super(ollamaUrl, modelName, requestTimeoutSeconds);
  }

  /**
   * Test-only constructor: {@code inactivityOverrideMillis} shortens the streaming inactivity
   * window so watchdog-stall tests do not need to wait the real window.
   */
  OllamaClient(
      @NotNull final String ollamaUrl,
      @NotNull final String modelName,
      final int requestTimeoutSeconds,
      final long inactivityOverrideMillis) {
    super(ollamaUrl, modelName, requestTimeoutSeconds, inactivityOverrideMillis);
  }

  @Override
  protected String providerName() {
    return "Ollama";
  }

  @Override
  protected String pingPath() {
    return "";
  }

  @Override
  protected String listModelsPath() {
    return "/api/tags";
  }

  @Override
  protected String generatePath() {
    return "/api/generate";
  }

  @Override
  protected String buildGenerateRequestBody(
      final String prompt, final double temperature, final int numPredict) {
    final JsonObject options = new JsonObject();
    options.addProperty("temperature", temperature);
    options.addProperty("num_predict", numPredict);

    final JsonObject body = new JsonObject();
    body.addProperty("model", modelName);
    body.addProperty("prompt", prompt);
    body.addProperty("system", SYSTEM_ROLE);
    // Streaming (instead of stream:false) is what makes per-value progress possible: tokens are
    // consumed as they arrive, the progress bar advances per value, and the inactivity watchdog
    // can abort stalled requests instead of blocking on a buffered response for the full timeout.
    body.addProperty("stream", true);
    // Reasoning models spend the token budget on hidden thinking, leaving "response"
    // empty; seed generation never needs chain-of-thought, so it stays off.
    body.addProperty("think", false);
    // Structured output: the sampler is constrained to this schema, so prose (chain-of-thought,
    // preambles, commentary about the request) cannot be emitted at all and therefore cannot be
    // mistaken for a value. Servers or models that ignore `format` still get the plain-text
    // extraction path as a fallback.
    body.add("format", VALUES_SCHEMA);
    body.addProperty("keep_alive", DEFAULT_KEEP_ALIVE);
    body.add("options", options);

    return GSON.toJson(body);
  }

  @Override
  protected List<String> parseModelsResponse(final String responseBody) {
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

  /**
   * Reads one NDJSON line: Ollama puts the emitted text in the {@code response} field, marks the
   * terminal line with {@code done:true}, and may also carry a {@code thinking} field that is never
   * a seed value and is therefore ignored.
   */
  @Override
  protected ParsedChunk parseStreamLine(final String rawLine) {
    try {
      final JsonObject json = JsonParser.parseString(rawLine).getAsJsonObject();
      final boolean done =
          json.has("done") && json.get("done").isJsonPrimitive() && json.get("done").getAsBoolean();
      if (!json.has("response") || json.get("response").isJsonNull()) {
        return done ? ParsedChunk.end() : ParsedChunk.ignore();
      }
      final String chunk = json.get("response").getAsString();
      if (chunk.isEmpty()) {
        return done ? ParsedChunk.end() : ParsedChunk.ignore();
      }
      return ParsedChunk.text(chunk);
    } catch (final Exception e) {
      log.debug("Skipping malformed Ollama stream line: " + e.getMessage());
      return ParsedChunk.ignore();
    }
  }

  /**
   * Pre-warms the model by sending a minimal generate request. This forces Ollama to load the model
   * into VRAM before the actual batch generation starts, avoiding the cold-start latency penalty on
   * the first real request. The response is discarded.
   *
   * @return A CompletableFuture that completes when the model is loaded.
   */
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
              .uri(URI.create(normalizedUrl + generatePath()))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(
              response -> {
                if (response.statusCode() == 200) {
                  log.info("Model '{}' warmed up successfully", modelName);
                } else {
                  throw new AiClientException(
                      extractErrorMessage(response.statusCode(), response.body()));
                }
              });
    } catch (final Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
