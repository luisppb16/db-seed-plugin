/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 *  *****************************************************************************
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * HTTP-level tests for {@link OllamaClient} against an embedded JDK {@link HttpServer} bound to an
 * ephemeral port on 127.0.0.1 that simulates an Ollama server.
 *
 * <p>The static sanitizer helpers ({@code normalizeUrl}, {@code sanitizeAiOutput}, ...) are already
 * covered by {@link OllamaClientTest} and are intentionally not duplicated here.
 */
class OllamaClientHttpTest {

  private static final String MODEL_NAME = "test-model";
  private static final int REQUEST_TIMEOUT_SECONDS = 10;
  private static final long AWAIT_SECONDS = 5;

  /** Per-test delegate handler; the server itself lives for the whole test class. */
  private static final AtomicReference<HttpHandler> HANDLER = new AtomicReference<>();

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

  private static OllamaClient newClient() {
    return new OllamaClient(baseUrl, MODEL_NAME, REQUEST_TIMEOUT_SECONDS);
  }

  private static void respondWith(final int status, final String body) {
    HANDLER.set(exchange -> respond(exchange, status, body));
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    exchange.getRequestBody().readAllBytes();
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
  }

  /**
   * Emits an NDJSON stream: one JSON object per line, as Ollama does when {@code stream:true}. Each
   * value in {@code chunks} becomes a {@code {"response":"...","done":false}} line, followed by a
   * final {@code {"response":"","done":true}} terminator. A newline inside a chunk completes a
   * value line on the client side.
   */
  private static void respondNdjson(final HttpExchange exchange, final String... chunks)
      throws IOException {
    exchange.getRequestBody().readAllBytes();
    exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream os = exchange.getResponseBody()) {
      for (final String chunk : chunks) {
        final JsonObject obj = new JsonObject();
        obj.addProperty("response", chunk);
        obj.addProperty("done", false);
        os.write((obj.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
      }
      final JsonObject done = new JsonObject();
      done.addProperty("response", "");
      done.addProperty("done", true);
      os.write((done.toString() + "\n").getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
    exchange.close();
  }

  /** Installs a handler that captures the request body and replies with {@code response}. */
  private static AtomicReference<String> captureBodyHandler(final String response) {
    final AtomicReference<String> capturedBody = new AtomicReference<>();
    HANDLER.set(
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respond(exchange, 200, response);
        });
    return capturedBody;
  }

  /** Installs a handler that captures the request body and replies with an NDJSON stream. */
  private static AtomicReference<String> captureBodyNdjsonHandler(final String... chunks) {
    final AtomicReference<String> capturedBody = new AtomicReference<>();
    HANDLER.set(
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respondNdjson(exchange, chunks);
        });
    return capturedBody;
  }

  @BeforeEach
  void resetHandler() {
    HANDLER.set(null);
  }

  @Nested
  class Ping {

    @Test
    void ok_completesWithoutException() {
      respondWith(200, "{}");

      assertThatCode(() -> newClient().ping().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();
    }

    @Test
    void serverError_failsWithOllamaException() {
      respondWith(500, "{\"error\":\"boom\"}");

      assertThatThrownBy(() -> newClient().ping().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .rootCause()
          .hasMessageContaining("Ollama returned status code: 500")
          .hasMessageContaining("boom");
    }

    @Test
    void serverDown_futureFails() throws IOException {
      final HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      final int deadPort = dead.getAddress().getPort();
      dead.start();
      dead.stop(0);

      final OllamaClient client =
          new OllamaClient("http://127.0.0.1:" + deadPort, MODEL_NAME, REQUEST_TIMEOUT_SECONDS);

      assertThatThrownBy(() -> client.ping().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IOException.class);
    }
  }

  @Nested
  class ListModels {

    @Test
    void ok_returnsModelNamesSorted() throws Exception {
      respondWith(200, "{\"models\":[{\"name\":\"b\"},{\"name\":\"a\"}]}");

      final List<String> models = newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(models).containsExactly("a", "b");
    }

    @Test
    void invalidJson_returnsEmptyList() throws Exception {
      respondWith(200, "{ this is not valid json");

      final List<String> models = newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(models).isEmpty();
    }

    @Test
    void missingModelsField_returnsEmptyList() throws Exception {
      respondWith(200, "{\"other\":123}");

      final List<String> models = newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(models).isEmpty();
    }

    @Test
    void serverError_failsWithOllamaException() {
      respondWith(500, "{}");

      assertThatThrownBy(() -> newClient().listModels().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .rootCause()
          .hasMessageContaining("Ollama returned status code: 500");
    }
  }

  @Nested
  class GenerateBatchValues {

    /** Installs a handler that streams the given value-lines as NDJSON token chunks. */
    private void respondNdjsonWith(final String valuesLine) {
      final String[] chunks =
          java.util.Arrays.stream(valuesLine.split("\n", -1))
              .map(c -> c + "\n")
              .toArray(String[]::new);
      HANDLER.set(exchange -> respondNdjson(exchange, chunks));
    }

    @Test
    void ok_returnsAllValues() throws Exception {
      respondNdjsonWith("valor1\nvalor2\nvalor3");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2", "valor3");
    }

    @Test
    void duplicatedLines_returnsDistinctValues() throws Exception {
      respondNdjsonWith("valor1\nvalor1\nvalor2");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2");
    }

    @Test
    void emptyResponse_failsWithOllamaException() {
      HANDLER.set(exchange -> respondNdjson(exchange, ""));

      assertThatThrownBy(
              () ->
                  newClient()
                      .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
                      .get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .hasRootCauseMessage("AI response contained no valid values for column 'city'");
    }

    @Test
    void serverError_failsWithOllamaException() {
      respondWith(500, "{\"error\":\"model not found\"}");

      assertThatThrownBy(
              () ->
                  newClient()
                      .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
                      .get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .rootCause()
          .hasMessageContaining("Ollama returned status code: 500")
          .hasMessageContaining("model not found");
    }

    @Test
    void requestBody_containsModelAndEnablesStreaming() throws Exception {
      final AtomicReference<String> capturedBody = captureBodyNdjsonHandler("valor1\n");

      newClient()
          .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(capturedBody.get()).contains("\"model\"").contains("\"stream\":true");
    }

    @Test
    void requestBody_contextGoesToSystemNotPrompt() throws Exception {
      final AtomicReference<String> capturedBody = captureBodyNdjsonHandler("valor1\n");

      newClient()
          .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      final JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
      assertThat(body.get("system").getAsString()).contains("Application context: online store");
      assertThat(body.get("prompt").getAsString()).doesNotContain("Application context");
    }

    @Test
    void requestBody_blankContext_systemHasNoContextLine() throws Exception {
      final AtomicReference<String> capturedBody = captureBodyNdjsonHandler("valor1\n");

      newClient()
          .generateBatchValues("", "users", "city", "varchar", 1, 3)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      final JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
      assertThat(body.get("system").getAsString()).doesNotContain("Application context");
      assertThat(body.get("prompt").getAsString()).doesNotContain("Application context");
    }

    @Test
    void requestBody_promptIsConcise() throws Exception {
      final AtomicReference<String> capturedBody = captureBodyNdjsonHandler("valor1\n");

      newClient()
          .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      final JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
      final String prompt = body.get("prompt").getAsString();
      assertThat(prompt).contains("Generate").contains("One per line");
      assertThat(prompt).doesNotContain("Application context");
    }

    @Test
    void ok_splitAcrossMultipleChunks_assemblesLine() throws Exception {
      // Tokens arriving one character at a time should still assemble into complete value lines.
      HANDLER.set(exchange -> respondNdjson(exchange, "va", "lor", "1\n", "valor2\n"));

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2");
    }
  }

  @Nested
  class WarmModel {

    @Test
    void ok_completesWithoutException() {
      respondWith(200, "{\"response\":\"\"}");

      assertThatCode(() -> newClient().warmModel(null).get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();
    }

    @Test
    void serverError_stillCompletesWithoutException() {
      respondWith(500, "{\"error\":\"boom\"}");

      assertThatCode(() -> newClient().warmModel(null).get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();
    }

    @Test
    void warmModelWithContext_systemContainsContext() throws Exception {
      final AtomicReference<String> capturedBody = captureBodyHandler("{\"response\":\"\"}");

      newClient().warmModel("online store").get(AWAIT_SECONDS, TimeUnit.SECONDS);

      final JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
      assertThat(body.get("system").getAsString()).contains("Application context: online store");
    }
  }
}
