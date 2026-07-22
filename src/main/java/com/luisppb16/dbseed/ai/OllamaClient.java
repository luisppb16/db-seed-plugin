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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Advanced REST client for interacting with the Ollama API in the DBSeed plugin ecosystem.
 *
 * <p>This class provides a comprehensive interface for integrating AI-powered content generation
 * into the database seeding process. It implements sophisticated communication protocols with
 * external Ollama LLM servers, handling connection management, request/response processing, and
 * intelligent content sanitization. The client supports both single-value and batch generation
 * operations, with robust error handling and response validation mechanisms.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Establishing and managing HTTP connections to external Ollama servers
 *   <li>Providing health check functionality through server ping operations
 *   <li>Listing available AI models on the target Ollama server
 *   <li>Generating single and batch database column values using AI models
 *   <li>Implementing intelligent prompt engineering for database context
 *   <li>Sanitizing AI responses to remove unwanted prefixes and formatting
 *   <li>Handling application context injection for domain-specific generation
 *   <li>Managing request timeouts and connection pooling for performance
 *   <li>Surfacing Ollama server error responses in exceptions for actionable diagnostics
 *   <li>Processing and validating AI-generated content for database compatibility
 *   <li>Filtering out AI preambles, refusals, and irrelevant content
 *   <li>Managing word count limitations and content length controls
 * </ul>
 *
 * <p>The class implements advanced content sanitization algorithms to clean AI responses, removing
 * common AI artifacts such as introductory phrases, numbering, and explanations. It includes
 * sophisticated pattern matching to identify and strip column name prefixes, surrounding quotes,
 * and numbered list formats. The implementation handles various AI refusal patterns and preamble
 * indicators to ensure only relevant content is returned.
 *
 * <p>Thread safety is maintained through asynchronous request handling with CompletableFuture and
 * dedicated executor services. The class implements efficient JSON serialization and
 * deserialization with proper escaping and unescaping of special characters. Memory efficiency is
 * achieved through streaming response processing and minimal intermediate object allocation.
 *
 * <p>Security considerations include careful handling of application context information, ensuring
 * that only relevant column and table names are sent to the AI model without exposing sensitive
 * data. The class implements robust input validation and sanitization to prevent injection attacks
 * and malformed requests. Response validation ensures that only properly formatted content is
 * accepted from the AI service.
 *
 * <p>Performance optimizations include connection pooling, configurable timeouts, and batch
 * processing capabilities for efficient generation of multiple values. The class implements
 * adaptive request sizing based on word count requirements and handles server-side rate limiting
 * gracefully through appropriate error handling.
 *
 * @see java.net.http.HttpClient
 * @see java.net.http.HttpRequest
 * @see java.util.concurrent.CompletableFuture
 * @see java.util.regex.Pattern
 * @see OllamaException
 */
@Slf4j
public class OllamaClient {

  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final int MIN_REQUEST_TIMEOUT_SECONDS = 10;
  private static final int PING_TIMEOUT_SECONDS = 5;
  private static final int LIST_MODELS_TIMEOUT_SECONDS = 15;

  /**
   * Poll interval for cancel-aware awaits. Bounds how long a blocked generation thread takes to
   * notice a cancellation request: instead of waiting up to the full HTTP request timeout (which
   * can be 120s), the thread re-checks cancellation at least every {@code AI_AWAIT_POLL_MILLIS}.
   */
  private static final long AI_AWAIT_POLL_MILLIS = 50L;

  private static final double DEFAULT_TEMPERATURE = 0.5;
  private static final double BATCH_TEMPERATURE = DEFAULT_TEMPERATURE;

  private static final int BATCH_NUM_PREDICT_FACTOR = 15;
  private static final int WORD_COUNT_PREDICT_MULTIPLIER = 3;
  private static final int MIN_WORD_COUNT = 1;
  private static final int ARRAY_ELEMENT_COUNT = 3;

  /**
   * Upper bound on the generated token budget per request. Models normally stop early by EOS long
   * before reaching this, so it rarely bites; it only guards the pathological case of array columns
   * with a high word count, where {@code count * ARRAY_ELEMENT_COUNT *
   * max(BATCH_NUM_PREDICT_FACTOR, wordCount * WORD_COUNT_PREDICT_MULTIPLIER)} can otherwise reach
   * tens of thousands of tokens and, if a model ever fails to emit EOS, blow past the HTTP request
   * timeout.
   */
  private static final int MAX_NUM_PREDICT = 8192;

  /** Keep the model loaded in VRAM for 10 minutes between requests to avoid cold-start penalty. */
  private static final String DEFAULT_KEEP_ALIVE = "10m";

  /**
   * Fraction of the configured request timeout used as the streaming inactivity window. While
   * streaming, the client tracks the elapsed time since the last token was received; if no token
   * arrives within {@code requestTimeoutSeconds * STREAMING_INACTIVITY_FRACTION}, the request is
   * aborted. This turns the "stuck after some tokens" failure mode (a model that stalls mid-way
   * through a large {@code num_predict} budget) from a full-timeout wait into a fast, detectable
   * failure that the caller can retry on a smaller batch.
   */
  private static final double STREAMING_INACTIVITY_FRACTION = 0.5;

  /** Polling cadence (ms) for the streaming inactivity watchdog. */
  private static final long STREAMING_WATCHDOG_POLL_MILLIS = 250L;

  private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)\\-]\\s*");

  private static final String SYSTEM_ROLE =
      "You are a database seed data generator. You output raw data values only. "
          + "Never add introductions, headers, numbering, bullet points, quotes, labels, or explanations. "
          + "Start immediately with the first value.";

  private static final Gson GSON = new Gson();

  /**
   * Shared executor using daemon threads for HTTP operations. Daemon threads do not prevent JVM
   * shutdown, so no explicit shutdown is required — the lifecycle is tied to the plugin/JVM.
   */
  private static final ExecutorService HTTP_EXECUTOR =
      Executors.newFixedThreadPool(
          Runtime.getRuntime().availableProcessors(),
          r -> {
            Thread t = new Thread(r, "ollama-http");
            t.setDaemon(true);
            return t;
          });

  /**
   * Dedicated single-thread scheduler for the streaming inactivity watchdog. Daemon thread, so it
   * never blocks JVM shutdown. Each streaming request registers a one-shot watchdog task that
   * aborts the request when no token has been received within the inactivity window; the watchdog
   * is cancelled when the stream completes normally.
   */
  private static final ScheduledExecutorService STREAMING_WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final Thread thread = new Thread(r, "ollama-stream-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * Shared HTTP client for all Ollama API operations. Uses {@link #HTTP_EXECUTOR} with daemon
   * threads, so it does not require explicit close — its lifecycle is tied to the plugin/JVM.
   */
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_2)
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

  /** Returns the configured request timeout in seconds. */
  public int requestTimeoutSeconds() {
    return requestTimeoutSeconds;
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

  static String sanitizeAiOutput(final String value, @Nullable final String columnName) {
    if (Objects.isNull(value)) return null;
    String cleaned = value.lines().findFirst().orElse("").trim();

    cleaned = stripCodeFences(cleaned);
    cleaned = stripSurroundingQuotes(cleaned);
    cleaned = NUMBERED_PREFIX.matcher(cleaned).replaceFirst("").trim();

    if (cleaned.startsWith("- ") || cleaned.startsWith("* ")) {
      cleaned = cleaned.substring(2).trim();
    }

    if (isAiPreamble(cleaned) || isAiRefusal(cleaned)) {
      return null;
    }

    if (Objects.nonNull(columnName) && !columnName.isEmpty()) {
      cleaned = stripColumnPrefix(cleaned, columnName);
      if (cleaned.equalsIgnoreCase(columnName)) {
        return null;
      }
    }

    return cleaned;
  }

  /**
   * Removes Markdown code fences that some models wrap around list output. A bare opening/closing
   * fence line ({@code ```} or {@code ```json}) carries no value and is collapsed to empty so the
   * caller can discard it; fences glued to actual content are stripped from the edges.
   */
  static String stripCodeFences(final String text) {
    String result = text;
    if (result.startsWith("```")) {
      final String afterFence = result.substring(3).trim();
      if (afterFence.isEmpty() || afterFence.matches("[a-zA-Z]{1,12}")) {
        return "";
      }
      result = afterFence;
    }
    if (result.endsWith("```")) {
      result = result.substring(0, result.length() - 3).trim();
    }
    return result;
  }

  static String stripSurroundingQuotes(final String text) {
    if (text.length() >= 2
        && ((text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("'") && text.endsWith("'")))) {
      return text.substring(1, text.length() - 1).trim();
    }
    return text;
  }

  static boolean isAiPreamble(final String text) {
    final String lower = text.toLowerCase(Locale.ROOT);
    return lower.startsWith("here are")
        || lower.startsWith("here is")
        || lower.startsWith("sure,")
        || lower.startsWith("sure!")
        || lower.startsWith("certainly")
        || lower.startsWith("of course")
        || lower.startsWith("below are")
        || lower.startsWith("the following")
        || lower.contains("unique and realistic")
        || lower.contains("values for the")
        || lower.contains("values for column")
        || lower.startsWith("aquí están")
        || lower.startsWith("aquí tienes")
        || lower.startsWith("aqui están")
        || lower.startsWith("aqui tienes")
        || lower.startsWith("por supuesto")
        || lower.startsWith("claro,")
        || lower.startsWith("claro.")
        || lower.startsWith("los siguientes")
        || lower.startsWith("las siguientes")
        || lower.startsWith("estos son")
        || lower.startsWith("estas son");
  }

  static boolean isAiRefusal(final String text) {
    final String lower = text.toLowerCase(Locale.ROOT);
    return lower.startsWith("i cannot")
        || lower.startsWith("i can't")
        || lower.startsWith("i'm sorry")
        || lower.startsWith("i am sorry")
        || lower.startsWith("sorry,")
        || lower.startsWith("as an ai")
        || lower.startsWith("i'm not able")
        || lower.startsWith("i am not able")
        || lower.startsWith("no puedo")
        || lower.startsWith("no puedo generar")
        || lower.startsWith("lo siento")
        || lower.startsWith("como ia")
        || lower.startsWith("como modelo")
        || lower.startsWith("como inteligencia artificial");
  }

  static String stripColumnPrefix(final String text, final String columnName) {
    final String lower = text.toLowerCase(Locale.ROOT);
    final String colLower = columnName.toLowerCase(Locale.ROOT);
    if (lower.startsWith(colLower)) {
      final String rest = text.substring(columnName.length()).trim();
      if (rest.startsWith(":") || rest.startsWith("=")) {
        return stripSurroundingQuotes(rest.substring(1).trim());
      }
      // No separator follows the column name, so the prefix match is a partial word
      // (e.g. column "name" vs value "named: John") — keep the original value intact.
      return text;
    }
    return text;
  }

  static boolean isArrayType(final String sqlType) {
    if (Objects.isNull(sqlType) || sqlType.isBlank()) {
      return false;
    }
    final String lower = sqlType.toLowerCase(Locale.ROOT);
    // PostgreSQL array types: TEXT[], _text, INTEGER[], etc.
    // Also handles ARRAY keyword
    return lower.endsWith("[]") || lower.startsWith("_") || lower.contains("array");
  }

  /**
   * Estimated output tokens the model needs to produce a single value for the given column shape.
   * Centralizes the predict sizing so callers (e.g. {@code RowGenerator} batch sizing) derive the
   * batch size from the same formula used to set {@code num_predict}, avoiding drift between the
   * two.
   *
   * @param isArray whether the column is an array type (each value holds {@value
   *     #ARRAY_ELEMENT_COUNT} elements)
   * @param wordCount the max words per value (clamped to {@link #MIN_WORD_COUNT})
   * @return estimated tokens per generated value
   */
  public static int perValuePredictTokens(final boolean isArray, final int wordCount) {
    final int effectiveWordCount = Math.max(MIN_WORD_COUNT, wordCount);
    final int scalarFactor =
        Math.max(BATCH_NUM_PREDICT_FACTOR, effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER);
    return isArray ? ARRAY_ELEMENT_COUNT * scalarFactor : scalarFactor;
  }

  /** Returns the upper bound on generated tokens per AI request ({@link #MAX_NUM_PREDICT}). */
  public static int maxNumPredict() {
    return MAX_NUM_PREDICT;
  }

  /** Shuts down the shared HTTP executor. Called when the plugin is being unloaded. */
  public static void shutdown() {
    HTTP_EXECUTOR.shutdownNow();
    STREAMING_WATCHDOG.shutdownNow();
  }

  /**
   * Awaits a {@link CompletableFuture} while periodically polling a cancellation flag. Unlike
   * {@link CompletableFuture#join()}, control returns within at most {@link #AI_AWAIT_POLL_MILLIS}
   * when the caller is canceled: the underlying future is canceled and a {@link
   * CancellationException} is thrown, so blocked AI-generation threads are released promptly
   * instead of waiting for the full HTTP request timeout (which can reach 120s). This makes the
   * generation cancel responsive to the progress indicator's cancel button.
   *
   * @param future the future to await
   * @param isCanceled cancellation flag supplier, polled every {@link #AI_AWAIT_POLL_MILLIS}
   * @return the future's result
   * @throws CancellationException if the caller is canceled or the waiting thread is interrupted
   * @throws CompletionException if the future completed exceptionally
   */
  public static <T> T awaitCancellable(
      final CompletableFuture<T> future, final BooleanSupplier isCanceled) {
    while (true) {
      if (isCanceled.getAsBoolean()) {
        future.cancel(true);
        throw new CancellationException();
      }
      try {
        return future.get(AI_AWAIT_POLL_MILLIS, TimeUnit.MILLISECONDS);
      } catch (final TimeoutException timeout) {
        // Re-check cancellation on the next iteration.
      } catch (final ExecutionException execution) {
        throw new CompletionException(execution.getCause());
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        throw new CancellationException();
      }
    }
  }

  /**
   * Builds an actionable error message that surfaces the Ollama server's own explanation. Ollama
   * returns errors as {@code {"error":"..."}} JSON; when that field is present it is included so
   * the caller sees exactly why the request failed (e.g. "model not found") instead of a bare
   * status code. Non-JSON bodies are surfaced as a trimmed snippet.
   */
  private static String extractErrorMessage(final int statusCode, final String responseBody) {
    if (Objects.isNull(responseBody) || responseBody.isBlank()) {
      return "Ollama returned status code: " + statusCode;
    }
    try {
      final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
      if (json.has("error") && !json.get("error").isJsonNull()) {
        return "Ollama returned status code: "
            + statusCode
            + " — "
            + json.get("error").getAsString();
      }
    } catch (final Exception ignored) {
      // Fall through and surface the raw body when it is not valid JSON.
    }
    final String trimmed = responseBody.trim();
    final String snippet = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    return "Ollama returned status code: " + statusCode + " — " + snippet;
  }

  /**
   * Builds the system prompt, appending the application context when present. The result is
   * identical across every request of a generation (the context is constant), so Ollama caches it
   * and the cost of evaluating it is paid only once — this realizes passing the context "a single
   * time at the start" via prompt caching.
   */
  private static String buildSystemRole(@Nullable final String applicationContext) {
    if (Objects.isNull(applicationContext) || applicationContext.isBlank()) {
      return SYSTEM_ROLE;
    }
    return SYSTEM_ROLE + "\nApplication context: " + applicationContext;
  }

  /**
   * Pings the Ollama server to check connectivity.
   *
   * @return A CompletableFuture that completes when the ping is successful.
   */
  public CompletableFuture<Void> ping() {
    try {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedUrl))
              .timeout(Duration.ofSeconds(PING_TIMEOUT_SECONDS))
              .GET()
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(
              response -> {
                if (response.statusCode() != 200) {
                  throw new OllamaException(
                      extractErrorMessage(response.statusCode(), response.body()));
                }
              });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Lists the models available on the Ollama server.
   *
   * @return A CompletableFuture containing a list of model names.
   */
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
                      extractErrorMessage(response.statusCode(), response.body()));
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

  public CompletableFuture<List<String>> generateBatchValues(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount,
      final int count) {

    final int effectiveWordCount = Math.max(MIN_WORD_COUNT, wordCount);
    final boolean isArrayType = isArrayType(sqlType);
    // The application context goes into the system prompt (identical across every request of the
    // generation) so Ollama caches it once and the per-request user prompt stays minimal.
    final String system = buildSystemRole(applicationContext);

    try {
      final String prompt;
      if (isArrayType) {
        if (effectiveWordCount == 1) {
          prompt =
              "Generate %d array values for column \"%s\" (table: %s, type: %s). Format: {el1,el2,el3} with %d elements. Single word each. PostgreSQL array syntax. One per line. Raw values only."
                  .formatted(count, columnName, tableName, sqlType, ARRAY_ELEMENT_COUNT);
        } else {
          prompt =
              "Generate %d array values for column \"%s\" (table: %s, type: %s). Format: {el1,el2,el3} with %d elements, up to %d words each. PostgreSQL array syntax. One per line. Raw values only."
                  .formatted(
                      count,
                      columnName,
                      tableName,
                      sqlType,
                      ARRAY_ELEMENT_COUNT,
                      effectiveWordCount);
        }
      } else {
        if (effectiveWordCount == 1) {
          prompt =
              "Generate %d values for column \"%s\" (table: %s, type: %s). One per line. Raw values only."
                  .formatted(count, columnName, tableName, sqlType);
        } else {
          prompt =
              "Generate %d values for column \"%s\" (table: %s, type: %s). Up to %d words each. One per line. Raw values only."
                  .formatted(count, columnName, tableName, sqlType, effectiveWordCount);
        }
      }

      final int numPredict =
          Math.min(count * perValuePredictTokens(isArrayType, wordCount), MAX_NUM_PREDICT);
      final String requestBody =
          buildGenerateRequestBody(prompt, system, BATCH_TEMPERATURE, numPredict);

      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedUrl + "/api/generate"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return streamGenerateValues(request, columnName);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Streams an Ollama {@code /api/generate} response (NDJSON, one JSON object per line per emitted
   * token chunk) and accumulates sanitized, distinct values as soon as each line boundary is
   * observed in the {@code response} field.
   *
   * <p>Streaming (instead of {@code stream:false}) eliminates the "stuck after a certain number of
   * tokens" failure mode: with the buffered request the client blocks until the full {@code
   * num_predict} budget is produced or the request timeout fires, so a model that stalls mid-way
   * (common on slow CPU/GPU hardware when the batch was sized against an optimistic throughput
   * assumption) holds the thread for up to the whole timeout — and {@code RowGenerator} then retries
   * up to {@code AI_MAX_RETRIES} times, each waiting the full timeout, compounding into minutes of
   * apparent hang.
   *
   * <p>Here, each token chunk updates an inactivity timestamp; a watchdog scheduled at {@link
   * #STREAMING_WATCHDOG_POLL_MILLIS} aborts the request if no token arrives within {@code
   * requestTimeoutSeconds * STREAMING_INACTIVITY_FRACTION}. A stall therefore surfaces as a fast,
   * retryable failure instead of a full-timeout wait, and the caller can split the batch and retry
   * on a smaller size.
   *
   * <p>Ollama emits the {@code response} field incrementally: each JSON line carries a token (or
   * token chunk) of the generated text, and a line may carry a newline character that completes a
   * value line. Completed value lines are sanitized through {@link #sanitizeAiOutput} as soon as
   * they appear, so progress is observable and memory bounded.
   *
   * @param request the prepared POST to {@code /api/generate} with {@code stream:true}
   * @param columnName the column being generated, for sanitization context
   * @return a future completing with the list of distinct, sanitized values
   */
  private CompletableFuture<List<String>> streamGenerateValues(
      final HttpRequest request, @NotNull final String columnName) {
    final long inactivityTimeoutMillis =
        Math.max(STREAMING_WATCHDOG_POLL_MILLIS * 2, (long) (requestTimeoutSeconds * 1000L * STREAMING_INACTIVITY_FRACTION));

    final CompletableFuture<List<String>> result = new CompletableFuture<>();
    final List<String> values = new ArrayList<>();
    final StringBuilder lineBuffer = new StringBuilder();
    // Last instant at which a token was received. Initialized to now so the watchdog doesn't fire
    // before the first token (prompt evaluation can take a while before generation starts).
    final AtomicReference<Long> lastTokenNanos = new AtomicReference<>(System.nanoTime());
    final AtomicReference<ScheduledFuture<?>> watchdog = new AtomicReference<>();
    final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> pending = new AtomicReference<>();

    // Watchdog: abort the request if no token has arrived within the inactivity window.
    final Runnable watchdogTask =
        () -> {
          final long idleNanos = System.nanoTime() - lastTokenNanos.get();
          if (idleNanos < TimeUnit.MILLISECONDS.toNanos(inactivityTimeoutMillis)) {
            return;
          }
          log.warn(
              "Ollama stream for column '{}' stalled (no tokens for {}ms), aborting request",
              columnName,
              inactivityTimeoutMillis);
          final CompletableFuture<HttpResponse<InputStream>> inFlight = pending.get();
          if (Objects.nonNull(inFlight)) {
            inFlight.cancel(true);
          }
          result.completeExceptionally(
              new OllamaException(
                  "AI generation stalled for column '"
                      + columnName
                      + "' (no tokens received within "
                      + inactivityTimeoutMillis
                      + "ms)"));
        };
    watchdog.set(
        STREAMING_WATCHDOG.scheduleAtFixedRate(
            watchdogTask,
            STREAMING_WATCHDOG_POLL_MILLIS,
            STREAMING_WATCHDOG_POLL_MILLIS,
            TimeUnit.MILLISECONDS));

    pending.set(HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()));

    pending.get()
        .handle(
            (response, ex) -> {
              if (Objects.nonNull(ex)) {
                cancelWatchdog(watchdog);
                if (result.isDone()) {
                  // Already failed by the watchdog or cancellation.
                  return null;
                }
                result.completeExceptionally(
                    new OllamaException("Ollama streaming request failed: " + ex.getMessage(), ex));
                return null;
              }
              if (response.statusCode() != 200) {
                // Non-200: consume the body to extract the server error message. The stream
                // handler returns an InputStream; read it fully for error reporting.
                try (final BufferedReader errorReader =
                    new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                  final StringBuilder errorBody = new StringBuilder();
                  String errorLine;
                  while (Objects.nonNull(errorLine = errorReader.readLine())) {
                    errorBody.append(errorLine);
                  }
                  result.completeExceptionally(
                      new OllamaException(
                          extractErrorMessage(response.statusCode(), errorBody.toString())));
                } catch (final IOException ioException) {
                  result.completeExceptionally(
                      new OllamaException(
                          "Ollama returned status code: " + response.statusCode(), ioException));
                }
                cancelWatchdog(watchdog);
                return null;
              }
              // Stream the NDJSON body line by line.
              try (final BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String ndjsonLine;
                while (Objects.nonNull(ndjsonLine = reader.readLine())) {
                  if (result.isDone()) {
                    break;
                  }
                  if (ndjsonLine.isBlank()) {
                    continue;
                  }
                  // Mark activity: a line arrived from the server.
                  lastTokenNanos.set(System.nanoTime());
                  processStreamLine(ndjsonLine, columnName, lineBuffer, values);
                }
                // Flush any trailing text that the model emitted without a final newline.
                flushLineBuffer(lineBuffer, columnName, values);
              } catch (final IOException ioException) {
                cancelWatchdog(watchdog);
                if (!result.isDone()) {
                  result.completeExceptionally(
                      new OllamaException(
                          "Failed reading Ollama stream for column '"
                              + columnName
                              + "': "
                              + ioException.getMessage(),
                          ioException));
                }
                return null;
              }
              cancelWatchdog(watchdog);
              if (!result.isDone()) {
                if (values.isEmpty()) {
                  result.completeExceptionally(
                      new OllamaException(
                          "AI response contained no valid values for column '" + columnName + "'"));
                } else {
                  result.complete(values);
                }
              }
              return null;
            });

    // Propagate external cancellation to the in-flight request.
    result.whenComplete(
        (ignored, throwable) -> {
          cancelWatchdog(watchdog);
          if (throwable instanceof CancellationException) {
            final CompletableFuture<HttpResponse<InputStream>> inFlight = pending.get();
            if (Objects.nonNull(inFlight)) {
              inFlight.cancel(true);
            }
          }
        });

    return result;
  }

  private static void cancelWatchdog(final AtomicReference<ScheduledFuture<?>> watchdog) {
    final ScheduledFuture<?> scheduled = watchdog.get();
    if (Objects.nonNull(scheduled)) {
      scheduled.cancel(false);
    }
  }

  /**
   * Processes one NDJSON line from the Ollama stream. Each line is a JSON object carrying a
   * token chunk in its {@code response} field. The chunk may contain partial value lines and
   * newline characters; complete value lines (terminated by {@code \n}) are sanitized and added
   * to {@code values} as soon as they appear, keeping memory usage bounded regardless of {@code
   * num_predict}.
   */
  private void processStreamLine(
      final String ndjsonLine,
      final String columnName,
      final StringBuilder lineBuffer,
      final List<String> values) {
    try {
      final JsonObject json = JsonParser.parseString(ndjsonLine).getAsJsonObject();
      final String chunk = json.has("response") && !json.get("response").isJsonNull()
          ? json.get("response").getAsString()
          : "";
      lineBuffer.append(chunk);
      int newlineIndex;
      while ((newlineIndex = lineBuffer.indexOf("\n")) >= 0) {
        final String completeLine = lineBuffer.substring(0, newlineIndex);
        lineBuffer.delete(0, newlineIndex + 1);
        addSanitizedValue(completeLine, columnName, values);
      }
    } catch (final Exception e) {
      log.debug("Skipping malformed Ollama stream line: {}", e.getMessage());
    }
  }

  private void flushLineBuffer(
      final StringBuilder lineBuffer, final String columnName, final List<String> values) {
    if (lineBuffer.length() > 0) {
      addSanitizedValue(lineBuffer.toString(), columnName, values);
      lineBuffer.setLength(0);
    }
  }

  private void addSanitizedValue(
      final String rawLine, final String columnName, final List<String> values) {
    final String sanitized = sanitizeAiOutput(rawLine, columnName);
    if (Objects.nonNull(sanitized) && !sanitized.isBlank() && !values.contains(sanitized)) {
      values.add(sanitized);
    }
  }

  @SuppressWarnings("unused")
  private List<String> parseBatchResponse(final String responseBody, final String columnName)
      throws OllamaException {
    try {
      final String raw = extractRawResponse(responseBody);
      final List<String> values =
          raw.lines()
              .map(line -> sanitizeAiOutput(line, columnName))
              .filter(Objects::nonNull)
              .filter(s -> !s.isBlank())
              .distinct()
              .toList();
      if (values.isEmpty()) {
        throw new OllamaException(
            "AI response contained no valid values for column '" + columnName + "'");
      }
      return values;
    } catch (OllamaException e) {
      throw e;
    } catch (Exception e) {
      throw new OllamaException("Failed to parse Ollama batch response: " + e.getMessage(), e);
    }
  }

  private String extractRawResponse(final String responseBody) throws IOException {
    try {
      final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
      // /api/generate format: { "response": "..." }
      if (json.has("response") && !json.get("response").isJsonNull()) {
        return json.get("response").getAsString();
      }
      // Fallback for /api/chat format
      if (json.has("message")
          && json.getAsJsonObject("message").has("content")
          && !json.getAsJsonObject("message").get("content").isJsonNull()) {
        return json.getAsJsonObject("message").get("content").getAsString();
      }
      throw new IOException("Invalid response from Ollama: missing 'response' field");
    } catch (final Exception e) {
      if (e instanceof IOException) throw e;
      throw new IOException("Failed to parse Ollama response: " + e.getMessage(), e);
    }
  }

  private String buildGenerateRequestBody(
      final String prompt, final String system, final double temperature, final int numPredict) {
    final JsonObject options = new JsonObject();
    options.addProperty("temperature", temperature);
    options.addProperty("num_predict", numPredict);

    final JsonObject body = new JsonObject();
    body.addProperty("model", modelName);
    body.addProperty("prompt", prompt);
    body.addProperty("system", system);
    body.addProperty("stream", true);
    body.addProperty("keep_alive", DEFAULT_KEEP_ALIVE);
    body.add("options", options);

    return GSON.toJson(body);
  }

  /**
   * Pre-warms the model by sending a minimal generate request. This forces Ollama to load the model
   * into VRAM before the actual batch generation starts, avoiding the cold-start latency penalty on
   * the first real request. The response is discarded.
   *
   * <p>The {@code applicationContext} is embedded in the system prompt so the warm-up primes the
   * same system prompt the real batch requests will use, letting Ollama cache it once and reuse it
   * across every subsequent request.
   *
   * @param applicationContext the application context to bake into the primed system prompt (may be
   *     null or blank to skip it)
   * @return A CompletableFuture that completes when the model is loaded.
   */
  public CompletableFuture<Void> warmModel(@Nullable final String applicationContext) {
    try {
      final JsonObject options = new JsonObject();
      options.addProperty("num_predict", 1);

      final JsonObject body = new JsonObject();
      body.addProperty("model", modelName);
      body.addProperty("prompt", "");
      body.addProperty("system", buildSystemRole(applicationContext));
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

    public OllamaException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
