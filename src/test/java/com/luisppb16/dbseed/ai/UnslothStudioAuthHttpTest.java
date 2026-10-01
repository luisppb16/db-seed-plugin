/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
 * HTTP-level tests for the Unsloth Studio sign-in: a password is not a bearer, so it is exchanged
 * at {@code /api/auth/login} for a session token, while an API key travels as it is.
 *
 * <p>The stub mirrors the real server: {@code /api/auth/status} answers without credentials, {@code
 * /api/auth/login} takes {@code {"username","password"}} and returns the token document, and {@code
 * /v1/*} is the OpenAI-compatible surface the client calls.
 */
class UnslothStudioAuthHttpTest {

  private static final String MODEL_NAME = "unsloth-model";
  private static final String PASSWORD = "s3cret-password";
  private static final String API_KEY = "sk-unsloth-test-key";
  private static final int REQUEST_TIMEOUT_SECONDS = 10;
  private static final long AWAIT_SECONDS = 5;

  private static final AtomicInteger LOGIN_CALLS = new AtomicInteger();
  private static final AtomicInteger STATUS_CALLS = new AtomicInteger();
  private static final AtomicReference<Integer> LOGIN_STATUS = new AtomicReference<>(200);
  private static final AtomicReference<String> LOGIN_BODY = new AtomicReference<>();
  private static final AtomicReference<String> STATUS_BODY = new AtomicReference<>();
  private static final AtomicReference<String> LAST_LOGIN_REQUEST = new AtomicReference<>();
  private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();
  private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();

  private static HttpServer server;
  private static String baseUrl;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", UnslothStudioAuthHttpTest::route);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static OpenAiCompatibleClient newClient(final String credential) {
    return new OpenAiCompatibleClient(
        AiProvider.UNSLOTH_STUDIO, baseUrl, MODEL_NAME, credential, REQUEST_TIMEOUT_SECONDS);
  }

  /** What the real server answers: the token document, straight from its own login route. */
  private static String tokenBody(final String accessToken) {
    return "{\"access_token\":\""
        + accessToken
        + "\",\"refresh_token\":\"opaque\",\"token_type\":\"bearer\","
        + "\"must_change_password\":false,\"account_id\":1}";
  }

  /** A syntactically valid JWT: the sign-in only reads the {@code exp} claim out of its payload. */
  private static String jwt(final long expiresAtEpochSeconds) {
    final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    final String header =
        encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    final String payload =
        encoder.encodeToString(
            ("{\"sub\":\"1\",\"exp\":" + expiresAtEpochSeconds + "}")
                .getBytes(StandardCharsets.UTF_8));
    return header + "." + payload + ".signature";
  }

  private static long nowSeconds() {
    return System.currentTimeMillis() / 1000L;
  }

  private static void route(final HttpExchange exchange) throws IOException {
    final String path = exchange.getRequestURI().getPath();
    if ("/api/auth/status".equals(path)) {
      STATUS_CALLS.incrementAndGet();
      respond(exchange, 200, STATUS_BODY.get());
      return;
    }
    if ("/api/auth/login".equals(path)) {
      LOGIN_CALLS.incrementAndGet();
      LAST_LOGIN_REQUEST.set(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, LOGIN_STATUS.get(), LOGIN_BODY.get());
      return;
    }
    LAST_PATH.set(path);
    LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
    if ("/v1/chat/completions".equals(path)) {
      respondSse(exchange);
      return;
    }
    respond(exchange, 200, "{\"data\":[{\"id\":\"" + MODEL_NAME + "\"}]}");
  }

  private static void respondSse(final HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(
          (deltaFrame("{\"values\":[\"Madrid\"]}") + "data: [DONE]\n\n")
              .getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
    exchange.close();
  }

  /**
   * One {@code data: {...}} frame carrying a content delta, serialised so the escaping is right.
   */
  private static String deltaFrame(final String content) {
    final JsonObject delta = new JsonObject();
    delta.addProperty("content", content);
    final JsonObject choice = new JsonObject();
    choice.add("delta", delta);
    final JsonArray choices = new JsonArray();
    choices.add(choice);
    final JsonObject frame = new JsonObject();
    frame.add("choices", choices);
    return "data: " + frame + "\n\n";
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
  }

  private static JsonObject lastSignIn() {
    return JsonParser.parseString(LAST_LOGIN_REQUEST.get()).getAsJsonObject();
  }

  @BeforeEach
  void resetStub() {
    LOGIN_CALLS.set(0);
    STATUS_CALLS.set(0);
    LOGIN_STATUS.set(200);
    LOGIN_BODY.set(tokenBody(jwt(nowSeconds() + 600)));
    STATUS_BODY.set("{\"initialized\":true,\"default_username\":\"unsloth\"}");
    LAST_LOGIN_REQUEST.set(null);
    LAST_PATH.set(null);
    LAST_AUTHORIZATION.set(null);
  }

  @Nested
  class PasswordSignIn {

    @Test
    void password_isExchangedForASessionToken_andTheTokenTravelsAsBearer() {
      final String token = jwt(nowSeconds() + 600);
      LOGIN_BODY.set(tokenBody(token));

      newClient(PASSWORD).ping().join();

      assertThat(LOGIN_CALLS.get()).isEqualTo(1);
      assertThat(lastSignIn().get("username").getAsString()).isEqualTo("unsloth");
      assertThat(lastSignIn().get("password").getAsString()).isEqualTo(PASSWORD);
      assertThat(LAST_PATH.get()).isEqualTo("/v1/models");
      assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + token);
    }

    @Test
    void username_isReadFromTheStatusRoute() {
      STATUS_BODY.set("{\"initialized\":true,\"default_username\":\"ana\"}");

      newClient(PASSWORD).ping().join();

      assertThat(STATUS_CALLS.get()).isEqualTo(1);
      assertThat(lastSignIn().get("username").getAsString()).isEqualTo("ana");
    }

    @Test
    void username_fallsBackToTheDocumentedDefault_whenStatusDoesNotNameOne() {
      STATUS_BODY.set("{\"initialized\":true}");

      newClient(PASSWORD).ping().join();

      assertThat(lastSignIn().get("username").getAsString()).isEqualTo("unsloth");
    }

    @Test
    void sameClient_reusesTheTokenAcrossRequests() {
      final OpenAiCompatibleClient client = newClient(PASSWORD);

      client.ping().join();
      client.listModels().join();

      assertThat(LOGIN_CALLS.get()).isEqualTo(1);
      assertThat(STATUS_CALLS.get()).isEqualTo(1);
    }

    @Test
    void expiredToken_isRenewedOnTheNextRequest() {
      LOGIN_BODY.set(tokenBody(jwt(nowSeconds() - 10)));
      final OpenAiCompatibleClient client = newClient(PASSWORD);

      client.ping().join();
      client.ping().join();

      assertThat(LOGIN_CALLS.get()).isEqualTo(2);
    }

    @Test
    void rejectedPassword_failsWithTheServerMessage_andNeverWithThePassword() {
      LOGIN_STATUS.set(401);
      LOGIN_BODY.set(
          "{\"detail\":\"Incorrect password. Reset it with unsloth studio reset-password\"}");

      final Throwable failure =
          catchThrowable(() -> newClient(PASSWORD).ping().get(AWAIT_SECONDS, TimeUnit.SECONDS));

      assertThat(failure).isInstanceOf(ExecutionException.class);
      assertThat(failure.getCause())
          .isInstanceOf(AiClientException.class)
          .hasMessageContaining("Incorrect password")
          .hasMessageContaining("Settings \u2192 API")
          .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void passwordChangeRequired_isExplained() {
      LOGIN_BODY.set(
          "{\"access_token\":\"" + jwt(nowSeconds() + 600) + "\",\"must_change_password\":true}");

      final Throwable failure =
          catchThrowable(() -> newClient(PASSWORD).ping().get(AWAIT_SECONDS, TimeUnit.SECONDS));

      assertThat(failure.getCause())
          .isInstanceOf(AiClientException.class)
          .hasMessageContaining("changed before it can be used");
    }

    @Test
    void serverDown_isReportedAsAUnslothStudioProblem() throws IOException {
      final HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      final int deadPort = dead.getAddress().getPort();
      dead.start();
      dead.stop(0);
      final OpenAiCompatibleClient client =
          new OpenAiCompatibleClient(
              AiProvider.UNSLOTH_STUDIO,
              "http://127.0.0.1:" + deadPort,
              MODEL_NAME,
              PASSWORD,
              REQUEST_TIMEOUT_SECONDS);

      final Throwable failure =
          catchThrowable(() -> client.ping().get(AWAIT_SECONDS, TimeUnit.SECONDS));

      assertThat(failure.getCause())
          .isInstanceOf(AiClientException.class)
          .hasMessageContaining("Could not reach Unsloth Studio");
    }

    @Test
    void generation_streamsWithTheSessionToken() throws Exception {
      final String token = jwt(nowSeconds() + 600);
      LOGIN_BODY.set(tokenBody(token));

      final List<String> values =
          newClient(PASSWORD)
              .generateBatchValues("tienda online", "users", "city", "varchar", 1, 1)
              .get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(values).containsExactly("Madrid");
      assertThat(LOGIN_CALLS.get()).isEqualTo(1);
      assertThat(LAST_PATH.get()).isEqualTo("/v1/chat/completions");
      assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + token);
    }
  }

  @Nested
  class ApiKey {

    @Test
    void apiKey_travelsAsBearer_andNoSignInIsAttempted() {
      newClient(API_KEY).ping().join();

      assertThat(LOGIN_CALLS.get()).isZero();
      assertThat(STATUS_CALLS.get()).isZero();
      assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + API_KEY);
    }
  }
}
