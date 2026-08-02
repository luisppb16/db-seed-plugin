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
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
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
          .hasRootCauseMessage("Ollama returned status code: 500");
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
          .hasRootCauseMessage("Ollama returned status code: 500");
    }
  }

  @Nested
  class GenerateBatchValues {

    @Test
    void ok_returnsAllValues() throws Exception {
      respondWith(200, "{\"response\":\"valor1\\nvalor2\\nvalor3\"}");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2", "valor3");
    }

    @Test
    void duplicatedLines_returnsDistinctValues() throws Exception {
      respondWith(200, "{\"response\":\"valor1\\nvalor1\\nvalor2\"}");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2");
    }

    @Test
    void emptyResponse_failsWithOllamaException() {
      respondWith(200, "{\"response\":\"\"}");

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
          .hasRootCauseMessage("Ollama error: 500");
    }

    @Test
    void requestBody_containsModelAndDisablesStreaming() throws Exception {
      final AtomicReference<String> capturedPath = new AtomicReference<>();
      final AtomicReference<String> capturedBody = new AtomicReference<>();
      HANDLER.set(
          exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedBody.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"response\":\"valor1\"}");
          });

      newClient()
          .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(capturedPath.get()).isEqualTo("/api/generate");
      assertThat(capturedBody.get()).contains("\"model\"").contains("\"stream\":false");
    }
  }

  @Nested
  class WarmModel {

    @Test
    void ok_completesWithoutException() {
      respondWith(200, "{\"response\":\"\"}");

      assertThatCode(() -> newClient().warmModel().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();
    }

    @Test
    void serverError_stillCompletesWithoutException() {
      respondWith(500, "{\"error\":\"boom\"}");

      assertThatCode(() -> newClient().warmModel().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();
    }
  }
}
