/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * HTTP-level tests for {@link OpenAiCompatibleClient} against an embedded JDK {@link HttpServer}
 * bound to an ephemeral port on 127.0.0.1 that simulates Unsloth Studio and the other
 * OpenAI-compatible servers.
 */
class OpenAiCompatibleClientHttpTest {

  private static final String MODEL_NAME = "unsloth-model";
  private static final String API_KEY = "sk-unsloth-test";
  private static final int REQUEST_TIMEOUT_SECONDS = 10;
  private static final long AWAIT_SECONDS = 5;

  /** Per-test delegate handler; the server itself lives for the whole test class. */
  private static final AtomicReference<HttpHandler> HANDLER = new AtomicReference<>();

  /** What the stub saw on the last request, so tests can assert the wire contract. */
  private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();

  private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
  private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();

  private static HttpServer server;
  private static String baseUrl;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          final HttpHandler delegate = HANDLER.get();
          if (delegate != null) {
            delegate.handle(exchange);
          } else {
            respond(exchange, 404, "{}");
          }
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static OpenAiCompatibleClient newClient() {
    return new OpenAiCompatibleClient(
        AiProvider.UNSLOTH_STUDIO, baseUrl, MODEL_NAME, API_KEY, REQUEST_TIMEOUT_SECONDS);
  }

  /** Same as {@link #newClient()} but with no key, to check that the header is simply omitted. */
  private static OpenAiCompatibleClient newClientWithoutKey() {
    return new OpenAiCompatibleClient(
        AiProvider.UNSLOTH_STUDIO, baseUrl, MODEL_NAME, "", REQUEST_TIMEOUT_SECONDS);
  }

  /** Same as {@link #newClient()} but for another engine sharing the OpenAI-compatible protocol. */
  private static OpenAiCompatibleClient newClient(final AiProvider provider) {
    return new OpenAiCompatibleClient(
        provider, baseUrl, MODEL_NAME, API_KEY, REQUEST_TIMEOUT_SECONDS);
  }

  private static void respondWith(final int status, final String body) {
    HANDLER.set(exchange -> respond(exchange, status, body));
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    capture(exchange);
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
  }

  /** Records the request line, the API key and the body before answering. */
  private static void capture(final HttpExchange exchange) throws IOException {
    LAST_PATH.set(exchange.getRequestURI().getPath());
    LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
    LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
  }

  /**
   * Streams a server-sent events body with one frame per chunk, flushed per frame — the way an
   * OpenAI-compatible server emits tokens. The {@code data: [DONE]} terminator is appended
   * automatically.
   */
  private static void respondSse(final HttpExchange exchange, final String... contents)
      throws IOException {
    capture(exchange);
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    // Length 0 means chunked transfer encoding: each flushed frame travels as its own chunk.
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream os = exchange.getResponseBody()) {
      for (final String content : contents) {
        os.write((deltaFrame(content) + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
      }
      os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
    exchange.close();
  }

  /** Builds one {@code data: {...}} frame carrying a content delta, with minimal JSON escaping. */
  private static String deltaFrame(final String content) {
    return "data: {\"choices\":[{\"delta\":{\"content\":\""
        + content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        + "\"}}]}";
  }

  /** Delegates the stub to {@link #respondSse} with the given content chunks. */
  private static void respondWithSse(final String... contents) {
    HANDLER.set(exchange -> respondSse(exchange, contents));
  }

  /** Serves raw SSE lines verbatim, for heartbeat comments and out-of-contract frames. */
  private static void respondWithRawSse(final String... frames) {
    HANDLER.set(
        exchange -> {
          capture(exchange);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            for (final String frame : frames) {
              os.write((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          }
          exchange.close();
        });
  }

  private static JsonObject lastRequestBody() {
    return JsonParser.parseString(LAST_BODY.get()).getAsJsonObject();
  }

  @BeforeEach
  void resetHandler() {
    HANDLER.set(null);
    LAST_PATH.set(null);
    LAST_BODY.set(null);
    LAST_AUTHORIZATION.set(null);
  }

  @Nested
  class Ping {

    @Test
    void ok_completesWithoutException() {
      respondWith(200, "{\"data\":[]}");

      assertThatCode(() -> newClient().ping().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();
      assertThat(LAST_PATH.get()).isEqualTo("/v1/models");
    }

    @Test
    void apiKeySent_asBearerAuthorization() {
      respondWith(200, "{\"data\":[]}");

      newClient().ping().join();

      assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    void noApiKey_sendsNoAuthorizationHeader() {
      respondWith(200, "{\"data\":[]}");

      newClientWithoutKey().ping().join();

      assertThat(LAST_AUTHORIZATION.get()).isNull();
    }

    @Test
    void serverError_failsWithAiClientException() {
      respondWith(500, "{\"error\":\"boom\"}");

      assertThatThrownBy(() -> newClient().ping().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AiClientException.class)
          .hasRootCauseMessage("Unsloth Studio returned status code: 500 — boom");
    }

    @Test
    void serverDown_futureFails() throws IOException {
      final HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      final int deadPort = dead.getAddress().getPort();
      dead.start();
      dead.stop(0);

      final OpenAiCompatibleClient client =
          new OpenAiCompatibleClient(
              AiProvider.UNSLOTH_STUDIO,
              "http://127.0.0.1:" + deadPort,
              MODEL_NAME,
              API_KEY,
              REQUEST_TIMEOUT_SECONDS);

      assertThatThrownBy(() -> client.ping().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IOException.class);
    }
  }

  @Nested
  class ListModels {

    @Test
    void ok_returnsModelIdsSorted() throws Exception {
      respondWith(200, "{\"data\":[{\"id\":\"qwen\"},{\"id\":\"llama\"},{\"id\":\"gemma\"}]}");

      final List<String> models = newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(models).containsExactly("gemma", "llama", "qwen");
      assertThat(LAST_PATH.get()).isEqualTo("/v1/models");
    }

    @Test
    void invalidJson_returnsEmptyList() throws Exception {
      respondWith(200, "{ this is not valid json");

      final List<String> models = newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(models).isEmpty();
    }

    @Test
    void missingDataField_returnsEmptyList() throws Exception {
      respondWith(200, "{\"models\":[{\"id\":\"qwen\"}]}");

      final List<String> models = newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(models).isEmpty();
    }

    @Test
    void serverError_failsWithAiClientException() {
      respondWith(401, "{\"error\":\"invalid api key\"}");

      assertThatThrownBy(() -> newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AiClientException.class)
          .hasRootCauseMessage("Unsloth Studio returned status code: 401 — invalid api key");
    }

    @Test
    void notAuthenticatedEnvelope_surfacesTheMessageInsteadOfTheRawBody() {
      // Envelope real de la superficie /v1/* de Unsloth Studio: el motivo viene dentro de un
      // objeto.
      respondWith(
          401,
          "{\"error\":{\"message\":\"Not authenticated\",\"type\":\"authentication_error\","
              + "\"param\":null,\"code\":null}}");

      assertThatThrownBy(() -> newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AiClientException.class)
          .hasRootCauseMessage("Unsloth Studio returned status code: 401 — Not authenticated");
    }
  }

  @Nested
  class StreamFrames {

    @Test
    void deltaContent_becomesText() {
      final AbstractAiClient.ParsedChunk parsed =
          newClient().parseStreamLine("data: {\"choices\":[{\"delta\":{\"content\":\"Madrid\"}}]}");

      assertThat(parsed.kind()).isEqualTo(AbstractAiClient.ChunkKind.TEXT);
      assertThat(parsed.text()).isEqualTo("Madrid");
    }

    @Test
    void doneSentinel_endsTheStream() {
      assertThat(newClient().parseStreamLine("data: [DONE]").kind())
          .isEqualTo(AbstractAiClient.ChunkKind.END);
    }

    @Test
    void heartbeatBlankAndOtherEventFields_areIgnored() {
      final OpenAiCompatibleClient client = newClient();

      assertThat(client.parseStreamLine(": keep-alive").kind())
          .isEqualTo(AbstractAiClient.ChunkKind.IGNORE);
      assertThat(client.parseStreamLine("").kind()).isEqualTo(AbstractAiClient.ChunkKind.IGNORE);
      assertThat(client.parseStreamLine("event: ping").kind())
          .isEqualTo(AbstractAiClient.ChunkKind.IGNORE);
      assertThat(client.parseStreamLine("data: not json").kind())
          .isEqualTo(AbstractAiClient.ChunkKind.IGNORE);
    }

    @Test
    void roleOnlyAndEmptyDeltas_emitNoText() {
      final OpenAiCompatibleClient client = newClient();

      assertThat(
              client
                  .parseStreamLine("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}")
                  .kind())
          .isEqualTo(AbstractAiClient.ChunkKind.IGNORE);
      assertThat(
              client.parseStreamLine("data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}").kind())
          .isEqualTo(AbstractAiClient.ChunkKind.IGNORE);
      assertThat(client.parseStreamLine("data: {\"choices\":[]}").kind())
          .isEqualTo(AbstractAiClient.ChunkKind.IGNORE);
    }

    @Test
    void messageContent_usedWhenTheServerAnswersInOnePiece() {
      final AbstractAiClient.ParsedChunk parsed =
          newClient()
              .parseStreamLine("data: {\"choices\":[{\"message\":{\"content\":\"Madrid\"}}]}");

      assertThat(parsed.kind()).isEqualTo(AbstractAiClient.ChunkKind.TEXT);
      assertThat(parsed.text()).isEqualTo("Madrid");
    }
  }

  @Nested
  class GenerateBatchValues {

    private CompletableFuture<List<String>> generate(final int count) {
      return newClient().generateBatchValues("tienda online", "users", "city", "varchar", 1, count);
    }

    @Test
    void ok_returnsAllValues() throws Exception {
      respondWithSse("{\"values\":[\"Madrid\",\"Bilbao\"]}");

      final List<String> values = generate(2).get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("Madrid", "Bilbao");
      assertThat(LAST_PATH.get()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void jsonContract_splitAcrossFrames_assemblesValues() throws Exception {
      respondWithSse("{\"values\":[", "\"Madrid\",", "\"Bilbao\"]}");

      final List<String> values = generate(2).get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("Madrid", "Bilbao");
    }

    @Test
    void progressCallback_firesOncePerValue() throws Exception {
      respondWithSse("{\"values\":[\"Madrid\",\"Bilbao\"]}");
      final AtomicInteger addedCalls = new AtomicInteger();

      final List<String> values =
          newClient()
              .generateBatchValues(
                  "tienda online", "users", "city", "varchar", 1, 2, addedCalls::incrementAndGet)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).hasSize(2);
      assertThat(addedCalls.get()).isEqualTo(2);
    }

    @Test
    void requestBody_containsMessagesAndStructuredOutput() throws Exception {
      respondWithSse("{\"values\":[\"Madrid\"]}");

      generate(1).get(AWAIT_SECONDS, TimeUnit.SECONDS);

      final JsonObject body = lastRequestBody();
      assertThat(body.get("model").getAsString()).isEqualTo(MODEL_NAME);
      assertThat(body.get("stream").getAsBoolean()).isTrue();
      assertThat(body.get("max_tokens").getAsInt()).isPositive();

      assertThat(body.getAsJsonArray("messages")).hasSize(2);
      assertThat(body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString())
          .isEqualTo("system");
      assertThat(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("role").getAsString())
          .isEqualTo("user");

      final JsonObject responseFormat = body.getAsJsonObject("response_format");
      assertThat(responseFormat.get("type").getAsString()).isEqualTo("json_schema");
      final JsonObject jsonSchema = responseFormat.getAsJsonObject("json_schema");
      assertThat(jsonSchema.get("name").getAsString()).isEqualTo("seed_values");
      assertThat(jsonSchema.get("strict").getAsBoolean()).isTrue();
      final JsonObject schema = jsonSchema.getAsJsonObject("schema");
      assertThat(schema.get("type").getAsString()).isEqualTo("object");
      assertThat(schema.get("additionalProperties").getAsBoolean()).isFalse();
      assertThat(schema.getAsJsonArray("required")).hasSize(1);
      // Unsloth define este campo y su pensamiento viene activado por defecto, así que se apaga: la
      // cadena de pensamiento no es un valor sembrable y gasta el presupuesto de tokens.
      assertThat(body.get("enable_thinking").getAsBoolean()).isFalse();
    }

    @Test
    void requestBody_omitsTheUnslothThinkingField_forAnotherEngine() throws Exception {
      respondWithSse("{\"values\":[\"Madrid\"]}");

      newClient(AiProvider.OPENAI_COMPATIBLE)
          .generateBatchValues("tienda online", "users", "city", "varchar", 1, 1)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      // Solo Unsloth documenta el campo: enviarlo a otro servidor arriesga un 400 por miembro
      // extra.
      assertThat(lastRequestBody().has("enable_thinking")).isFalse();
    }

    @Test
    void apiKeySent_asBearerAuthorization() throws Exception {
      respondWithSse("{\"values\":[\"Madrid\"]}");

      generate(1).get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    void noApiKey_sendsNoAuthorizationHeader() throws Exception {
      respondWithSse("{\"values\":[\"Madrid\"]}");

      newClientWithoutKey()
          .generateBatchValues("tienda online", "users", "city", "varchar", 1, 1)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(LAST_AUTHORIZATION.get()).isNull();
    }

    @Test
    void plainTextResponse_fallsBackToLineExtraction() throws Exception {
      respondWithSse("Madrid\n", "Bilbao\n");

      final List<String> values = generate(2).get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("Madrid", "Bilbao");
    }

    @Test
    void noValuesAtAll_failsWithAiClientException() {
      respondWithSse();

      assertThatThrownBy(() -> generate(2).get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .rootCause()
          .isInstanceOf(AiClientException.class)
          .hasMessageContaining("contained no valid values for column 'city'");
    }

    @Test
    void serverError_failsWithAiClientException() {
      respondWith(500, "{\"error\":\"boom\"}");

      assertThatThrownBy(() -> generate(2).get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AiClientException.class)
          .hasRootCauseMessage("Unsloth Studio returned status code: 500 — boom");
    }

    @Test
    void stall_midStream_watchdogAborts_withStalledMessage() {
      HANDLER.set(
          exchange -> {
            try {
              capture(exchange);
              exchange.sendResponseHeaders(200, 0);
              final OutputStream os = exchange.getResponseBody();
              os.write(
                  (deltaFrame("{\"values\":[\"Madrid\"]}") + "\n\n")
                      .getBytes(StandardCharsets.UTF_8));
              os.flush();
              // Stall: keep the exchange open, never send another token, never close.
              Thread.sleep(4000);
            } catch (final IOException | InterruptedException ignored) {
              // The client aborted or the test finished; nothing to recover.
            }
          });

      assertThatThrownBy(
              () ->
                  new OpenAiCompatibleClient(
                          AiProvider.UNSLOTH_STUDIO,
                          baseUrl,
                          MODEL_NAME,
                          API_KEY,
                          REQUEST_TIMEOUT_SECONDS,
                          500L)
                      .generateBatchValues("tienda online", "users", "city", "varchar", 1, 2)
                      .get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AiClientException.class)
          .hasRootCauseMessage(
              "AI generation stalled for column 'city' (no tokens received within 500ms)");
    }

    @Test
    void cancel_midStream_cancellationException() throws Exception {
      HANDLER.set(
          exchange -> {
            try {
              capture(exchange);
              exchange.sendResponseHeaders(200, 0);
              final OutputStream os = exchange.getResponseBody();
              os.write(
                  (deltaFrame("{\"values\":[\"Madrid\"]}") + "\n\n")
                      .getBytes(StandardCharsets.UTF_8));
              os.flush();
              // Stall with the exchange open so the test can cancel mid-stream.
              Thread.sleep(2000);
            } catch (final IOException | InterruptedException ignored) {
              // The client aborted or the test finished; nothing to recover.
            }
          });

      final AtomicInteger addedCalls = new AtomicInteger();
      final CompletableFuture<List<String>> future =
          newClient()
              .generateBatchValues(
                  "tienda online", "users", "city", "varchar", 1, 2, addedCalls::incrementAndGet);

      final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
      while (addedCalls.get() == 0 && System.nanoTime() < deadlineNanos) {
        Thread.sleep(20);
      }

      future.cancel(true);

      assertThatThrownBy(() -> future.get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
      assertThat(addedCalls.get()).isEqualTo(1);
    }
  }

  @Nested
  class WarmModel {

    @Test
    void ok_completesWithoutException() {
      respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}");

      assertThatCode(() -> newClient().warmModel().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();

      assertThat(LAST_PATH.get()).isEqualTo("/v1/chat/completions");
      final JsonObject body = lastRequestBody();
      assertThat(body.get("stream").getAsBoolean()).isFalse();
      assertThat(body.get("max_tokens").getAsInt()).isEqualTo(1);
    }

    @Test
    void serverError_failsWithAiClientException() {
      respondWith(503, "{\"error\":\"model loading\"}");

      assertThatThrownBy(() -> newClient().warmModel().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AiClientException.class)
          .hasRootCauseMessage("Unsloth Studio returned status code: 503 — model loading");
    }
  }
}
