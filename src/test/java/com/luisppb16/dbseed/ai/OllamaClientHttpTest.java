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

  /** Etiquetas de chain-of-thought tal como las emiten los modelos de razonamiento. */
  private static final String THINK_OPEN = "<" + "think" + ">";

  private static final String THINK_CLOSE = "<" + "/" + "think" + ">";

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

  /**
   * Streams a chunked NDJSON body with one line per chunk, flushed per line — simulating how Ollama
   * emits tokens incrementally. A trailing {@code {"response":"","done":true}} line is appended
   * automatically.
   */
  private static void respondNdjson(final HttpExchange exchange, final String... chunks)
      throws IOException {
    exchange.getRequestBody().readAllBytes();
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    // Length 0 means chunked transfer encoding: each flushed line travels as its own chunk.
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream os = exchange.getResponseBody()) {
      for (final String chunk : chunks) {
        os.write((chunkLine(chunk) + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
      }
      os.write("{\"response\":\"\",\"done\":true}\n".getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
    exchange.close();
  }

  /** Builds one NDJSON line for a token chunk, with minimal JSON string escaping. */
  private static String chunkLine(final String chunk) {
    return "{\"response\":\""
        + chunk.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        + "\",\"done\":false}";
  }

  /** Delegates the stub to {@link #respondNdjson} with the given chunks. */
  private static void respondWithNdjson(final String... chunks) {
    HANDLER.set(exchange -> respondNdjson(exchange, chunks));
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
          .hasRootCauseMessage("Ollama returned status code: 500 — boom");
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
          .hasRootCauseMessage("Ollama returned status code: 500 — {}");
    }
  }

  @Nested
  class GenerateBatchValues {

    @Test
    void ok_returnsAllValues() throws Exception {
      respondWithNdjson("valor1\n", "valor2\n", "valor3");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2", "valor3");
    }

    @Test
    void duplicatedLines_returnsDistinctValues() throws Exception {
      respondWithNdjson("valor1\n", "valor1\n", "valor2");

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
          .hasRootCauseMessage(
              "AI response contained no valid values for column 'city'. Model output: <empty>");
    }

    @Test
    void onlyPreambleResponse_errorIncludesModelOutputSnippet() {
      respondWith(200, "{\"response\":\"Aquí están los valores:\"}");

      assertThatThrownBy(
              () ->
                  newClient()
                      .generateBatchValues("online store", "users", "tags", "text[]", 1, 3)
                      .get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .hasRootCauseMessage(
              "AI response contained no valid values for column 'tags'. Model output:"
                  + " Aquí están los valores:");
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
          .hasRootCauseMessage("Ollama returned status code: 500 — model not found");
    }

    @Test
    void modelNotFound_includesServerErrorMessage() {
      respondWith(404, "{\"error\":\"model \\\"test-model\\\" not found, try pulling it first\"}");

      assertThatThrownBy(
              () ->
                  newClient()
                      .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
                      .get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .hasRootCauseMessage(
              "Ollama returned status code: 404 — model \"test-model\" not found, try pulling it first");
    }

    @Test
    void codeFencedResponse_valuesStillExtracted() throws Exception {
      respondWithNdjson("```json\n", "valor1\n", "valor2\n", "```");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2");
    }

    @Test
    void inlineThinkingBeforeValues_neverBecomesAValue() throws Exception {
      // The reasoning spans several newlines of its own, which must not be handed out as values.
      respondWithNdjson(
          THINK_OPEN + "\nThe user wants 3 unique city names.\nLet me think about it. ",
          THINK_CLOSE + "\n",
          "valor1\n",
          "valor2\n",
          "valor3");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2", "valor3");
    }

    @Test
    void leakedClosingTag_keepsValueAfterIt() throws Exception {
      // Reported shape: the runtime swallows the opening tag and the reasoning travels glued to the
      // first real value, which must survive the whole block being dropped.
      respondWithNdjson(
          "The user wants 12 article titles.\nJust start with the first value."
              + THINK_CLOSE
              + "Understanding Machine Learning\n",
          "Quantum Computing Basics\n");

      final List<String> values =
          newClient()
              .generateBatchValues("blog", "articles", "title", "varchar", 10, 2)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values)
          .containsExactly("Understanding Machine Learning", "Quantum Computing Basics");
    }

    @Test
    void taglessReasoningLine_dropped() throws Exception {
      respondWithNdjson(
          "The user wants 3 unique array values for a \"tags\" column, 3 elements each.\n",
          "{python,cli,linux}\n");

      final List<String> values =
          newClient()
              .generateBatchValues("blog", "articles", "tags", "text[]", 3, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("{python,cli,linux}");
    }

    @Test
    void thinkingTruncatedAtStreamEnd_failsWithoutLeakingIt() {
      respondWithNdjson(
          THINK_OPEN + "The user wants twelve city names but the token budget ran out");

      assertThatThrownBy(
              () ->
                  newClient()
                      .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
                      .get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .hasRootCauseMessage(
              "AI response contained no valid values for column 'city'. Model output: "
                  + THINK_OPEN
                  + "The user wants twelve city names but the token budget ran out");
    }

    @Test
    void requestBody_containsModelAndEnablesStreaming() throws Exception {
      final AtomicReference<String> capturedPath = new AtomicReference<>();
      final AtomicReference<String> capturedBody = new AtomicReference<>();
      HANDLER.set(
          exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedBody.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondNdjson(exchange, "valor1\n");
          });

      newClient()
          .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
          .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(capturedPath.get()).isEqualTo("/api/generate");
      assertThat(capturedBody.get())
          .contains("\"model\"")
          .contains("\"stream\":true")
          .contains("\"think\":false")
          // Structured output: the sampler is constrained to the value schema, so reasoning prose
          // cannot be emitted at all.
          .contains("\"format\"")
          .contains("\"values\"")
          .contains("\"array\"")
          // A prompt example with numbered placeholders is copied verbatim by small models, so the
          // prompt carries none.
          .doesNotContain("value1")
          .doesNotContain("...");
    }

    @Test
    void promptPlaceholders_neverBecomeValues() throws Exception {
      // Reported on a small local model: it answered with the template names of the prompt instead
      // of generating data. Those must never reach the script.
      respondWithNdjson("{\"values\": [\"value1\", \"full_name_2\", \"...\", \"Marta Ruiz\"]}");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "full_name", "varchar", 2, 4)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("Marta Ruiz");
    }

    @Test
    void arrayTemplatePlaceholders_dropped() throws Exception {
      respondWithNdjson("{\"values\": [\"{el1,el2,el3}\", \"{red,green,blue}\"]}");

      final List<String> values =
          newClient()
              .generateBatchValues("blog", "articles", "tags", "text[]", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("{red,green,blue}");
    }

    @Test
    void jsonContract_extractsOnlyTheRequestedValues() throws Exception {
      // Prose may surround the object when a model ignores the schema: only the array is data.
      respondWithNdjson(
          "Looking at the request, here is the answer:\n",
          "{\"values\": [\"valor1\", \"valor2\", \"valor3\"]}\n");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2", "valor3");
    }

    @Test
    void jsonContract_progressCallbackFiresPerString() throws Exception {
      final AtomicInteger addedCalls = new AtomicInteger();
      respondWithNdjson("{\"values\": [\"valor1\", ", "\"valor2\", \"valor3\"]}");

      final List<String> values =
          newClient()
              .generateBatchValues(
                  "online store", "users", "city", "varchar", 1, 3, addedCalls::incrementAndGet)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2", "valor3");
      assertThat(addedCalls.get()).isEqualTo(3);
    }

    @Test
    void jsonContract_escapedStringsDecoded() throws Exception {
      respondWithNdjson("{\"values\": [\"caf\\u00e9\", \"say \\\"hi\\\"\"]}");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 2)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("café", "say \"hi\"");
    }

    @Test
    void truncatedJsonContract_keepsClosedValues() throws Exception {
      respondWithNdjson("{\"values\": [\"valor1\", \"valor2\"");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2");
    }

    @Test
    void separateThinkingField_ignored() throws Exception {
      // Ollama may split reasoning into its own `thinking` field: it is never a seed value.
      HANDLER.set(
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(
                  ("{\"thinking\":\"The user wants three city names.\",\"response\":\"\"}\n"
                          + "{\"response\":\"{\\\"values\\\": [\\\"valor1\\\"]}\","
                          + "\"done\":false}\n"
                          + "{\"response\":\"\",\"done\":true}\n")
                      .getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
            exchange.close();
          });

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1");
    }

    @Test
    void jsonContract_arrayColumn_keepsThreeElementValues() throws Exception {
      respondWithNdjson("{\"values\": [\"{python,cli,linux}\", \"{java,gradle,idea}\"]}");

      final List<String> values =
          newClient()
              .generateBatchValues("blog", "articles", "tags", "text[]", 3, 2)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("{python,cli,linux}", "{java,gradle,idea}");
    }

    @Test
    void arrayColumn_valueWithWrongElementCount_dropped() throws Exception {
      // Reported leak shape: reasoning about the requested array is chopped into pieces that look
      // like values. Only the requested element count is accepted.
      respondWithNdjson("{tag1,tag2}\n", "{python,cli,linux}\n");

      final List<String> values =
          newClient()
              .generateBatchValues("blog", "articles", "tags", "text[]", 3, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("{python,cli,linux}");
    }

    @Test
    void ok_splitAcrossMultipleChunks_assemblesLine() throws Exception {
      respondWithNdjson("va", "lor", "1\n", "va", "lor", "2");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2");
    }

    @Test
    void ok_trailingLineWithoutNewline_flushed() throws Exception {
      respondWithNdjson("valor1");

      final List<String> values =
          newClient()
              .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1");
    }

    @Test
    void callback_firesOncePerAddedValue() throws Exception {
      final AtomicInteger addedCalls = new AtomicInteger();
      respondWithNdjson("valor1\n", "valor2\n", "valor3");

      final List<String> values =
          newClient()
              .generateBatchValues(
                  "online store", "users", "city", "varchar", 1, 3, addedCalls::incrementAndGet)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("valor1", "valor2", "valor3");
      assertThat(addedCalls.get()).isEqualTo(3);
    }

    @Test
    void stall_midStream_watchdogAborts_withStalledMessage() {
      HANDLER.set(
          exchange -> {
            try {
              exchange.getRequestBody().readAllBytes();
              exchange.sendResponseHeaders(200, 0);
              final OutputStream os = exchange.getResponseBody();
              os.write((chunkLine("valor1\n") + "\n").getBytes(StandardCharsets.UTF_8));
              os.flush();
              // Stall: keep the exchange open, never send another token, never close.
              Thread.sleep(4000);
            } catch (final IOException | InterruptedException ignored) {
              // The client aborted or the test finished; nothing to recover.
            }
          });

      assertThatThrownBy(
              () ->
                  new OllamaClient(baseUrl, MODEL_NAME, REQUEST_TIMEOUT_SECONDS, 500L)
                      .generateBatchValues("online store", "users", "city", "varchar", 1, 3)
                      .get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .hasRootCauseMessage(
              "AI generation stalled for column 'city' (no tokens received within 500ms)");
    }

    @Test
    void cancel_midStream_cancellationException() throws Exception {
      HANDLER.set(
          exchange -> {
            try {
              exchange.getRequestBody().readAllBytes();
              exchange.sendResponseHeaders(200, 0);
              final OutputStream os = exchange.getResponseBody();
              os.write((chunkLine("valor1\n") + "\n").getBytes(StandardCharsets.UTF_8));
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
                  "online store", "users", "city", "varchar", 1, 3, addedCalls::incrementAndGet);

      // Wait until the first value arrives, then cancel mid-stream.
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
      respondWith(200, "{\"response\":\"\"}");

      assertThatCode(() -> newClient().warmModel().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .doesNotThrowAnyException();
    }

    @Test
    void serverError_failsWithOllamaException() {
      respondWith(500, "{\"error\":\"boom\"}");

      assertThatThrownBy(() -> newClient().warmModel().get(AWAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OllamaClient.OllamaException.class)
          .hasRootCauseMessage("Ollama returned status code: 500 — boom");
    }
  }
}
