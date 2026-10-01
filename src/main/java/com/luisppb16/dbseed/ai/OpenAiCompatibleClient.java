/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * AI client for any server that speaks the OpenAI-compatible chat completions API: Unsloth Studio,
 * LM Studio, vLLM, llama.cpp, OpenRouter and similar.
 *
 * <p>It uses {@code GET /v1/models} to check that the server answers and to list models, and {@code
 * POST /v1/chat/completions} to generate a batch. The answer arrives as server-sent events: each
 * {@code data: {...}} frame carries a delta of the generated text and the stream closes with {@code
 * data: [DONE]}. All the shared machinery lives in {@link AbstractAiClient}; this class only
 * declares the protocol.
 *
 * <p>Requests pin the {@code {"values": [...]}} JSON Schema through {@code response_format}, in the
 * strict mode OpenAI defines for it, and ask for {@code stream:true} so values are consumed as they
 * arrive and the progress callback fires per value. The credential travels in the {@code
 * Authorization: Bearer} header — Unsloth Studio refuses every request without one unless keyless
 * API access was turned on in its own settings — and for that engine a password is first exchanged
 * for a session token, since only an API key or a token is accepted there.
 *
 * @see AbstractAiClient
 * @see AiProvider
 * @see AiClientException
 */
@Slf4j
public class OpenAiCompatibleClient extends AbstractAiClient {

  private static final String MODELS_PATH = "/v1/models";
  private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
  private static final String SSE_DATA_PREFIX = "data:";
  private static final String SSE_DONE = "[DONE]";
  private static final String SCHEMA_NAME = "seed_values";

  private final AiProvider provider;
  private final String apiKey;

  /**
   * Set only for Unsloth Studio with a password in the credential field: that value is not a
   * bearer, so it has to be exchanged for a session token before it can authenticate anything. An
   * API key, and every other engine, needs none of this.
   */
  @Nullable private final UnslothStudioAuth auth;

  public OpenAiCompatibleClient(
      @NotNull final AiProvider provider,
      @NotNull final String baseUrl,
      @NotNull final String modelName,
      @Nullable final String apiKey,
      final int requestTimeoutSeconds) {
    this(provider, baseUrl, modelName, apiKey, requestTimeoutSeconds, 0L);
  }

  /**
   * Test-only constructor: {@code inactivityOverrideMillis} shortens the streaming inactivity
   * window so watchdog-stall tests do not need to wait the real window.
   */
  OpenAiCompatibleClient(
      @NotNull final AiProvider provider,
      @NotNull final String baseUrl,
      @NotNull final String modelName,
      @Nullable final String apiKey,
      final int requestTimeoutSeconds,
      final long inactivityOverrideMillis) {
    super(baseUrl, modelName, requestTimeoutSeconds, inactivityOverrideMillis);
    this.provider = provider;
    this.apiKey = Objects.requireNonNullElse(apiKey, "");
    this.auth =
        provider == AiProvider.UNSLOTH_STUDIO
                && !this.apiKey.isBlank()
                && !UnslothStudioAuth.isApiKey(this.apiKey)
            ? new UnslothStudioAuth(normalizedUrl, this.apiKey, requestTimeoutSeconds)
            : null;
  }

  @Nullable
  private static String ssePayload(final String rawLine) {
    final String trimmed = rawLine.strip();
    if (trimmed.isEmpty() || trimmed.startsWith(":")) {
      // Blank line or heartbeat comment: no payload.
      return null;
    }
    if (!trimmed.startsWith(SSE_DATA_PREFIX)) {
      // Another event field such as "event:" or "id:".
      return null;
    }
    return trimmed.substring(SSE_DATA_PREFIX.length()).strip();
  }

  private static String contentOf(final JsonObject carrier, final String field) {
    if (!carrier.has(field) || !carrier.get(field).isJsonObject()) {
      return "";
    }
    final JsonElement content = carrier.getAsJsonObject(field).get("content");
    return Objects.nonNull(content) && content.isJsonPrimitive() ? content.getAsString() : "";
  }

  @Override
  protected String providerName() {
    return provider.getDisplayName();
  }

  @Override
  protected String pingPath() {
    return MODELS_PATH;
  }

  @Override
  protected String listModelsPath() {
    return MODELS_PATH;
  }

  @Override
  protected String generatePath() {
    return CHAT_COMPLETIONS_PATH;
  }

  @Override
  protected HttpRequest.Builder addAuthHeaders(final HttpRequest.Builder builder) {
    if (Objects.nonNull(auth)) {
      return builder.header("Authorization", auth.authorizationHeader());
    }
    return apiKey.isBlank() ? builder : builder.header("Authorization", "Bearer " + apiKey);
  }

  @Override
  protected String buildGenerateRequestBody(
      final String prompt, final double temperature, final int numPredict) {
    final JsonObject systemMessage = new JsonObject();
    systemMessage.addProperty("role", "system");
    systemMessage.addProperty("content", SYSTEM_ROLE);

    final JsonObject userMessage = new JsonObject();
    userMessage.addProperty("role", "user");
    userMessage.addProperty("content", prompt);

    final JsonArray messages = new JsonArray();
    messages.add(systemMessage);
    messages.add(userMessage);

    final JsonObject jsonSchema = new JsonObject();
    jsonSchema.addProperty("name", SCHEMA_NAME);
    jsonSchema.addProperty("strict", true);
    jsonSchema.add("schema", VALUES_SCHEMA);

    final JsonObject responseFormat = new JsonObject();
    responseFormat.addProperty("type", "json_schema");
    responseFormat.add("json_schema", jsonSchema);

    final JsonObject body = new JsonObject();
    body.addProperty("model", modelName);
    // Streaming is what makes per-value progress possible: tokens are consumed as they arrive and
    // the inactivity watchdog can abort a stalled request instead of blocking for the full timeout.
    body.addProperty("stream", true);
    body.addProperty("temperature", temperature);
    body.addProperty("max_tokens", numPredict);
    body.add("messages", messages);
    // Structured output: the sampler is constrained to this schema, so prose (chain-of-thought,
    // preambles, commentary about the request) cannot be emitted and therefore cannot be mistaken
    // for a value. Servers that ignore response_format still get the plain-text extraction path.
    body.add("response_format", responseFormat);
    // Unsloth Studio defines this extension field and its thinking defaults to ON, which would
    // spend
    // the token budget on hidden chain-of-thought that never belongs in a seed value. Only Unsloth
    // documents the field, so it is not sent to another server that might reject an unknown member.
    if (provider == AiProvider.UNSLOTH_STUDIO) {
      body.addProperty("enable_thinking", false);
    }

    return GSON.toJson(body);
  }

  @Override
  protected List<String> parseModelsResponse(final String responseBody) {
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
      log.warn("Failed to parse {} models response", provider.getDisplayName(), e);
    }
    return models;
  }

  /**
   * Reads one server-sent event: heartbeat comments and other event fields carry no text, {@code
   * data: [DONE]} closes the stream and a JSON frame contributes its first choice's delta. The
   * {@code message} content is read as well, because a server may answer in one piece despite
   * {@code stream:true}.
   */
  @Override
  protected ParsedChunk parseStreamLine(final String rawLine) {
    final String payload = ssePayload(rawLine);
    if (Objects.isNull(payload)) {
      return ParsedChunk.ignore();
    }
    if (SSE_DONE.equals(payload)) {
      return ParsedChunk.end();
    }
    try {
      final JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
      if (!json.has("choices") || !json.get("choices").isJsonArray()) {
        return ParsedChunk.ignore();
      }
      final JsonArray choices = json.getAsJsonArray("choices");
      if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
        return ParsedChunk.ignore();
      }
      final JsonObject choice = choices.get(0).getAsJsonObject();
      final String delta = contentOf(choice, "delta");
      final String chunk = delta.isEmpty() ? contentOf(choice, "message") : delta;
      return chunk.isEmpty() ? ParsedChunk.ignore() : ParsedChunk.text(chunk);
    } catch (final Exception e) {
      log.debug("Skipping malformed {} stream line: {}", provider.getDisplayName(), e.getMessage());
      return ParsedChunk.ignore();
    }
  }

  /**
   * Pre-warms the model by sending a minimal chat completion. Engines of this family load the model
   * on their own, so this mainly checks that the endpoint, the model name and the API key are
   * accepted before the first batch pays for the discovery. The response is discarded.
   *
   * @return A CompletableFuture that completes when the model is loaded.
   */
  @Override
  public CompletableFuture<Void> warmModel() {
    try {
      final JsonObject userMessage = new JsonObject();
      userMessage.addProperty("role", "user");
      userMessage.addProperty("content", "");

      final JsonArray messages = new JsonArray();
      messages.add(userMessage);

      final JsonObject body = new JsonObject();
      body.addProperty("model", modelName);
      body.addProperty("stream", false);
      body.addProperty("max_tokens", 1);
      body.add("messages", messages);

      final HttpRequest request =
          addAuthHeaders(
                  HttpRequest.newBuilder()
                      .uri(URI.create(normalizedUrl + generatePath()))
                      .header("Content-Type", "application/json")
                      .timeout(Duration.ofSeconds(requestTimeoutSeconds)))
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
