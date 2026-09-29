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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
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
 *   <li>Implementing retry mechanisms and fallback strategies for resilience
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
  private static final int PING_TIMEOUT_SECONDS = 2;
  private static final int LIST_MODELS_TIMEOUT_SECONDS = 5;

  private static final double DEFAULT_TEMPERATURE = 0.5;
  private static final double BATCH_TEMPERATURE = DEFAULT_TEMPERATURE;

  private static final int BATCH_NUM_PREDICT_FACTOR = 15;
  private static final int WORD_COUNT_PREDICT_MULTIPLIER = 3;
  private static final int MIN_WORD_COUNT = 1;

  /**
   * Number of elements requested for every array value, written with PostgreSQL array syntax
   * ({@code {el1,el2,el3}}). Values whose element count differs are reasoning about the requested
   * shape, not data, so they are dropped.
   */
  private static final int ARRAY_ELEMENT_COUNT = 3;

  /**
   * Minimum token budget for batch requests. {@code num_predict} is a cap, not a target, so a
   * generous floor costs nothing when the model stops early; it only prevents truncation on small
   * batches where a verbose preamble would otherwise consume the whole budget.
   */
  private static final int NUM_PREDICT_FLOOR = 512;

  /** Keep the model loaded in VRAM for 10 minutes between requests to avoid cold-start penalty. */
  private static final String DEFAULT_KEEP_ALIVE = "10m";

  /**
   * Upper bound on the generated token budget per request. Models normally stop early by EOS long
   * before reaching this, so it rarely bites; it only guards the pathological case of array columns
   * with a high word count, where {@code count * max(15, words × 3)} can otherwise reach tens of
   * thousands of tokens and, if a model ever fails to emit EOS, blow past the HTTP request timeout.
   */
  private static final int MAX_NUM_PREDICT = 8192;

  /**
   * Fraction of the configured request timeout used as the streaming inactivity window. While
   * streaming, the client tracks the elapsed time since the last token was received; if no token
   * arrives within {@code requestTimeoutSeconds × STREAMING_INACTIVITY_FRACTION}, the request is
   * aborted. This turns the "stuck after a certain number of tokens" failure mode (a model that
   * stalls mid-way through a large {@code num_predict} budget) from a full-timeout wait into a
   * fast, detectable failure that the caller can retry on a smaller batch.
   */
  private static final double STREAMING_INACTIVITY_FRACTION = 0.5;

  /** Polling cadence (ms) for the streaming inactivity watchdog. */
  private static final long STREAMING_WATCHDOG_POLL_MILLIS = 250L;

  /**
   * Poll interval for cancel-aware awaits. Bounds how long a blocked generation thread takes to
   * notice a cancellation request: instead of waiting up to the full HTTP request timeout (which
   * can reach 120s), the thread re-checks cancellation at least every {@code AI_AWAIT_POLL_MILLIS}.
   */
  private static final long AI_AWAIT_POLL_MILLIS = 50L;

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

  private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)\\-]\\s*");

  /**
   * Matches a chain-of-thought delimiter emitted inline in the {@code response} field. Both the
   * ASCII and the full-width spellings are accepted, together with the {@code think}/{@code
   * thinking} variants, because the exact tag depends on the model.
   */
  private static final Pattern THINKING_TAG = Pattern.compile("[<＜]/?(?:think|thinking)[>＞]");

  private static final String SYSTEM_ROLE =
      "You are a database seed data generator. You reply with exactly one JSON object matching the "
          + "requested schema and nothing else: no introductions, headers, numbering, bullet points, quotes, "
          + "labels, explanations, plans or commentary, and never a sentence about the request itself. "
          + "The strings inside that object are the raw data values; they are harmless synthetic "
          + "placeholders for a local test database.";

  /**
   * JSON Schema sent through the Ollama {@code format} parameter. Structured output constrains the
   * sampler to schema-conforming tokens, so prose (chain-of-thought, preambles, commentary) cannot
   * be emitted at all and therefore cannot be mistaken for a data value.
   */
  private static final JsonObject VALUES_SCHEMA = buildValuesSchema();

  /**
   * Placeholder names a model can copy from a prompt template instead of generating data ({@code
   * value1}, {@code el2}, {@code full_name_1}). They are never seed values, so they are dropped; a
   * batch of nothing but placeholders falls back to DataFaker, which is visible to the user.
   */
  private static final Pattern INDEXED_PLACEHOLDER =
      Pattern.compile("^([a-z][a-z]*(?:[ _-][a-z]+)*)[ _-]?\\d+$");

  /** A value made only of dots, as in the {@code ...} of an example list. */
  private static final Pattern DOTS_ONLY = Pattern.compile("^[.\\u2026]+$");

  private static final Set<String> PLACEHOLDER_WORDS =
      Set.of(
          "value",
          "values",
          "val",
          "el",
          "elem",
          "element",
          "item",
          "tag",
          "tags",
          "text",
          "string",
          "sample",
          "example",
          "placeholder",
          "foo",
          "bar",
          "baz");

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
   * Shared HTTP client for all Ollama API operations. Uses {@link #HTTP_EXECUTOR} with daemon
   * threads, so it does not require explicit close — its lifecycle is tied to the plugin/JVM.
   */
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
          .executor(HTTP_EXECUTOR)
          .build();

  private final String normalizedUrl;
  private final String modelName;
  private final int requestTimeoutSeconds;

  /** Test-only override of the streaming inactivity window (ms); {@code 0} means derive it. */
  private final long inactivityOverrideMillis;

  public OllamaClient(
      @NotNull final String ollamaUrl,
      @NotNull final String modelName,
      final int requestTimeoutSeconds) {
    this(ollamaUrl, modelName, requestTimeoutSeconds, 0L);
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
    this.normalizedUrl = normalizeUrl(ollamaUrl);
    this.modelName = modelName;
    this.requestTimeoutSeconds = Math.max(MIN_REQUEST_TIMEOUT_SECONDS, requestTimeoutSeconds);
    this.inactivityOverrideMillis = inactivityOverrideMillis;
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

  /**
   * Builds the {@code {"values": [string]}} schema requested for every batch: one object holding
   * the strings the caller consumes as seed values.
   */
  private static JsonObject buildValuesSchema() {
    final JsonObject items = new JsonObject();
    items.addProperty("type", "string");

    final JsonObject values = new JsonObject();
    values.addProperty("type", "array");
    values.add("items", items);

    final JsonObject properties = new JsonObject();
    properties.add("values", values);

    final JsonArray required = new JsonArray();
    required.add("values");

    final JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    schema.add("properties", properties);
    schema.add("required", required);
    return schema;
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
        || lower.startsWith("estas son")
        // Chain-of-thought restating the instruction, leaked as a value when the model emits its
        // reasoning untagged (see discardClosedThinking).
        || lower.startsWith("the user ")
        || lower.startsWith("el usuario ")
        || lower.startsWith("the task is")
        || lower.startsWith("thinking:")
        || lower.contains("chain of thought")
        || lower.contains("thinking process")
        || lower.startsWith("we need to generate")
        || lower.startsWith("we need to produce")
        || lower.startsWith("i need to generate")
        || lower.startsWith("i need to produce")
        || lower.startsWith("necesito generar")
        // Reasoning that comments on the request or on the shape of the answer. These phrases
        // cannot be data: they only appear when the model talks about the task instead of doing
        // it. "Let me know your thoughts" is a valid value and stays out of this list.
        || lower.startsWith("let me generate")
        || lower.startsWith("let me create")
        || lower.startsWith("let me produce")
        || lower.startsWith("this seems like")
        || lower.startsWith("this sounds like")
        || lower.startsWith("this is for")
        || lower.contains("the context is")
        || lower.contains("should be like")
        || lower.contains("one per line")
        || lower.contains("raw values only")
        || lower.contains("words each")
        || lower.contains("unique values")
        || lower.contains("unique arrays")
        || lower.contains("unique lines")
        || lower.contains("legitimate request")
        || lower.contains("legitimate use case")
        || lower.contains("benign request");
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
   * Shuts down the shared HTTP and watchdog executors. Called when the plugin is being unloaded.
   */
  public static void shutdown() {
    HTTP_EXECUTOR.shutdownNow();
    STREAMING_WATCHDOG.shutdownNow();
  }

  /**
   * Builds an actionable error message for a non-2xx Ollama response. Extracts the {@code error}
   * field from the JSON body when present (e.g. {@code model 'x' not found}); otherwise falls back
   * to a trimmed snippet of the raw body.
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

  /** Trims and caps a raw model output at 200 characters for error messages. */
  private static String snippetOf(final String raw) {
    final String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      return "<empty>";
    }
    return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
  }

  /** Cancels the scheduled watchdog task, if any. Safe to call more than once. */
  private static void cancelWatchdog(final AtomicReference<ScheduledFuture<?>> watchdog) {
    final ScheduledFuture<?> scheduled = watchdog.get();
    if (Objects.nonNull(scheduled)) {
      scheduled.cancel(false);
    }
  }

  /** Closes a response body stream to abort a blocking read. Null-safe, idempotent. */
  private static void abortBody(@Nullable final InputStream body) {
    if (Objects.nonNull(body)) {
      try {
        body.close();
      } catch (final IOException ignored) {
        // The stream is already closed or the connection is broken; nothing to recover.
      }
    }
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
   * @param onWaitMillis invoked on every poll with the total elapsed wait in ms; may be {@code
   *     null}. Callers use it to keep the progress text alive during long waits.
   * @return the future's result
   * @throws CancellationException if the caller is canceled or the waiting thread is interrupted
   * @throws CompletionException if the future completed exceptionally
   */
  public static <T> T awaitCancellable(
      final CompletableFuture<T> future,
      final BooleanSupplier isCanceled,
      @Nullable final LongConsumer onWaitMillis) {
    final long startNanos = System.nanoTime();
    while (true) {
      if (isCanceled.getAsBoolean()) {
        future.cancel(true);
        throw new CancellationException();
      }
      try {
        return future.get(AI_AWAIT_POLL_MILLIS, TimeUnit.MILLISECONDS);
      } catch (final TimeoutException timeout) {
        // Re-check cancellation and report elapsed wait on the next iteration.
        if (Objects.nonNull(onWaitMillis)) {
          onWaitMillis.accept((System.nanoTime() - startNanos) / 1_000_000L);
        }
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
   * Drops everything up to and including the last thinking closing tag found in the buffer, so a
   * chain-of-thought block never becomes a value.
   */
  private static void discardClosedThinking(final StringBuilder buffer) {
    final Matcher matcher = THINKING_TAG.matcher(buffer);
    int end = -1;
    while (matcher.find()) {
      if (matcher.group().contains("/")) {
        end = matcher.end();
      }
    }
    if (end > 0) {
      buffer.delete(0, end);
    }
  }

  /** True while the buffer holds an opening thinking tag whose closing tag has not arrived yet. */
  private static boolean isInsideThinking(final StringBuilder buffer) {
    final Matcher matcher = THINKING_TAG.matcher(buffer);
    boolean open = false;
    while (matcher.find()) {
      open = !matcher.group().contains("/");
    }
    return open;
  }

  /**
   * Extracts the strings of the {@code values} array from a complete answer. Returns an empty list
   * when the answer is not the requested JSON object (truncated output, or a plain-text answer).
   */
  private static List<String> extractJsonValues(final String rawText) {
    final List<String> extracted = new ArrayList<>();
    final JsonObject object = tryParseJsonObject(rawText);
    if (Objects.isNull(object)) {
      return extracted;
    }
    final JsonArray array = findValuesArray(object);
    if (Objects.isNull(array)) {
      return extracted;
    }
    for (final JsonElement element : array) {
      if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
        extracted.add(element.getAsString());
      }
    }
    return extracted;
  }

  @Nullable
  private static JsonArray findValuesArray(final JsonObject object) {
    final JsonElement declared = object.get("values");
    if (Objects.nonNull(declared)
        && declared.isJsonArray()
        && containsOnlyStrings(declared.getAsJsonArray())) {
      return declared.getAsJsonArray();
    }
    // The model may name the array differently: any string array in the object is taken as the
    // requested value list, which keeps the extraction working across model families.
    for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
      if (entry.getValue().isJsonArray()
          && containsOnlyStrings(entry.getValue().getAsJsonArray())) {
        return entry.getValue().getAsJsonArray();
      }
    }
    return null;
  }

  private static boolean containsOnlyStrings(final JsonArray array) {
    for (final JsonElement element : array) {
      if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Parses the accumulated answer as a JSON object, tolerating the text a model may write around
   * it. Ollama can also prefix the object with a stray {@code {"} when thinking and a format schema
   * are combined (ollama#10929), so that known prefix is repaired before giving up.
   */
  @Nullable
  private static JsonObject tryParseJsonObject(final String rawText) {
    final String trimmed = rawText.trim();
    final JsonObject direct = parseJsonObject(trimmed);
    if (Objects.nonNull(direct)) {
      return direct;
    }
    if (trimmed.startsWith("{\"{")) {
      final JsonObject repaired = parseJsonObject("{" + trimmed.substring(3));
      if (Objects.nonNull(repaired)) {
        return repaired;
      }
    }
    final int firstBrace = trimmed.indexOf('{');
    final int lastBrace = trimmed.lastIndexOf('}');
    if (firstBrace >= 0 && lastBrace > firstBrace) {
      return parseJsonObject(trimmed.substring(firstBrace, lastBrace + 1));
    }
    return null;
  }

  @Nullable
  private static JsonObject parseJsonObject(final String candidate) {
    try {
      final JsonElement parsed = JsonParser.parseString(candidate);
      return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    } catch (final Exception e) {
      return null;
    }
  }

  /**
   * Checks the {@code {el1,el2,el3}} shape requested for array columns. A reasoning line that talks
   * about the requested shape ({@code So each line should be like {tag1, tag2, tag3} where ...})
   * has a different element count and is dropped here; {@code expectedArrayElements} is 0 for
   * scalar columns, which accept any value.
   */
  private static boolean hasExpectedArrayShape(
      final String value, final int expectedArrayElements, final String columnName) {
    if (expectedArrayElements <= 0) {
      return true;
    }
    final String body = value.trim();
    final boolean braced = body.startsWith("{") && body.endsWith("}");
    final boolean bracketed = body.startsWith("[") && body.endsWith("]");
    if (!braced && !bracketed) {
      return false;
    }
    final String[] elements = body.substring(1, body.length() - 1).split(",", -1);
    if (elements.length != expectedArrayElements) {
      return false;
    }
    for (final String element : elements) {
      if (element.isBlank() || isPlaceholderValue(element, columnName)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a value is a placeholder a model copied from the prompt template rather than a
   * generated value: the template words themselves, a template word with an index ({@code value1},
   * {@code el2}), the column name with an index ({@code full_name_1}) or a bare ellipsis.
   */
  private static boolean isPlaceholderValue(final String value, @Nullable final String columnName) {
    final String lower = value.trim().toLowerCase(Locale.ROOT);
    if (DOTS_ONLY.matcher(lower).matches() || PLACEHOLDER_WORDS.contains(lower)) {
      return true;
    }
    final Matcher indexed = INDEXED_PLACEHOLDER.matcher(lower);
    if (!indexed.matches()) {
      return false;
    }
    return PLACEHOLDER_WORDS.contains(indexed.group(1))
        || (Objects.nonNull(columnName)
            && indexed.group(1).equals(columnName.trim().toLowerCase(Locale.ROOT)));
  }

  /**
   * Returns the streaming inactivity window: the test-only override when set, otherwise a fraction
   * of the configured request timeout (bounded below by twice the watchdog poll interval).
   */
  private long inactivityWindowMillis() {
    if (inactivityOverrideMillis > 0) {
      return inactivityOverrideMillis;
    }
    return Math.max(
        STREAMING_WATCHDOG_POLL_MILLIS * 2,
        (long) (requestTimeoutSeconds * 1000L * STREAMING_INACTIVITY_FRACTION));
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

  /**
   * Generates a batch of values for a column, streaming the Ollama response so sanitized values
   * arrive incrementally. Each time a new value survives sanitization and dedup, {@code
   * onValueAdded} is invoked, enabling live per-value progress.
   */
  public CompletableFuture<List<String>> generateBatchValues(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount,
      final int count,
      @Nullable final Runnable onValueAdded) {

    final int effectiveWordCount = Math.max(MIN_WORD_COUNT, wordCount);
    final String contextLine =
        !applicationContext.isBlank() ? "Application context: " + applicationContext + "\n" : "";
    final boolean isArrayType = isArrayType(sqlType);

    try {
      final String prompt;
      final int numPredict;

      if (isArrayType) {
        final int elementCount = ARRAY_ELEMENT_COUNT;
        if (effectiveWordCount == 1) {
          prompt =
              "%sGenerate exactly %d unique values for column \"%s\" (table: %s, type: %s). Answer with a JSON object holding a \"values\" array of exactly %d strings; every string is a PostgreSQL array literal, that is, exactly %d single comma-separated words wrapped in curly braces. Write real words in place of the el1, el2 and el3 of this template: no placeholders, no numbering, no commentary."
                  .formatted(
                      contextLine, count, columnName, tableName, sqlType, count, elementCount);
        } else {
          prompt =
              "%sGenerate exactly %d unique values for column \"%s\" (table: %s, type: %s), up to %d words per element. Answer with a JSON object holding a \"values\" array of exactly %d strings; every string is a PostgreSQL array literal, that is, exactly %d comma-separated values wrapped in curly braces. Write real words in place of the el1, el2 and el3 of this template: no placeholders, no numbering, no commentary."
                  .formatted(
                      contextLine,
                      count,
                      columnName,
                      tableName,
                      sqlType,
                      effectiveWordCount,
                      count,
                      elementCount);
        }
        numPredict =
            Math.min(
                Math.max(
                    count
                        * elementCount
                        * Math.max(
                            BATCH_NUM_PREDICT_FACTOR,
                            effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER),
                    NUM_PREDICT_FLOOR),
                MAX_NUM_PREDICT);
      } else {
        if (effectiveWordCount == 1) {
          prompt =
              "%sGenerate exactly %d unique values for column \"%s\" (table: %s, type: %s), one single word each. Answer with a JSON object holding a \"values\" array of exactly %d strings, every one of them a real value for that column: no placeholders, no numbering, no labels, no commentary."
                  .formatted(contextLine, count, columnName, tableName, sqlType, count);
          numPredict = Math.min(count * BATCH_NUM_PREDICT_FACTOR, MAX_NUM_PREDICT);
        } else {
          prompt =
              "%sGenerate exactly %d unique values for column \"%s\" (table: %s, type: %s), up to %d words each. Answer with a JSON object holding a \"values\" array of exactly %d strings, every one of them a real value for that column: no placeholders, no numbering, no labels, no commentary."
                  .formatted(
                      contextLine,
                      count,
                      columnName,
                      tableName,
                      sqlType,
                      effectiveWordCount,
                      count);
          numPredict =
              Math.min(
                  Math.max(
                      count
                          * Math.max(
                              BATCH_NUM_PREDICT_FACTOR,
                              effectiveWordCount * WORD_COUNT_PREDICT_MULTIPLIER),
                      NUM_PREDICT_FLOOR),
                  MAX_NUM_PREDICT);
        }
      }

      final String requestBody = buildGenerateRequestBody(prompt, BATCH_TEMPERATURE, numPredict);

      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedUrl + "/api/generate"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(requestTimeoutSeconds))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return streamGenerateValues(
          request, columnName, isArrayType ? ARRAY_ELEMENT_COUNT : 0, onValueAdded);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Overload without live progress: values are still streamed internally, but no per-value callback
   * is invoked.
   */
  public CompletableFuture<List<String>> generateBatchValues(
      @NotNull final String applicationContext,
      @NotNull final String tableName,
      @NotNull final String columnName,
      @NotNull final String sqlType,
      final int wordCount,
      final int count) {
    return generateBatchValues(
        applicationContext, tableName, columnName, sqlType, wordCount, count, null);
  }

  /**
   * Streams an Ollama {@code /api/generate} response (NDJSON, one JSON object per line per emitted
   * token chunk) and accumulates sanitized, distinct values as soon as each value line is completed
   * in the stream.
   *
   * <p>Streaming (instead of {@code stream:false}) eliminates the "stuck after a certain number of
   * tokens" failure mode: with the buffered request the client blocks until the full {@code
   * num_predict} budget is produced or the request timeout fires, so a model that stalls mid-way
   * (common on slow CPU/GPU hardware when the batch was sized against an optimistic throughput
   * assumption) holds the thread for up to the whole timeout — and {@code RowGenerator} then
   * retries up to {@code AI_MAX_RETRIES} times, each waiting the full timeout, compounding into
   * minutes of apparent hang.
   *
   * <p>Here, each token chunk updates an inactivity timestamp; a watchdog scheduled at {@link
   * #STREAMING_WATCHDOG_POLL_MILLIS} aborts the request if no token arrives within {@code
   * requestTimeoutSeconds × STREAMING_INACTIVITY_FRACTION}. A stall therefore surfaces as a fast,
   * retryable failure instead of a full-timeout wait, and the caller can split the batch and retry
   * on a smaller size.
   *
   * <p>Ollama emits the response incrementally: each NDJSON line carries a token (or token chunk)
   * of the generated text in its {@code response} field. Because the request pins a JSON schema
   * through the {@code format} parameter, that text is a {@code {"values": [...]}} object whose
   * string literals are extracted as soon as they close ({@link JsonValueScanner}); every new value
   * invokes {@code onValueAdded}, so the caller advances the progress bar per value instead of per
   * batch. Anything outside that array — preambles, stray reasoning, a {@code thinking} field — is
   * never read as a value.
   *
   * <p>Servers that predate structured output ignore {@code format} altogether. When the
   * accumulated text holds no JSON string array, the legacy line-based extraction runs over it as a
   * fallback, so those setups keep producing values.
   *
   * @param expectedArrayElements element count required for array columns ({@code 0} for scalars);
   *     values with a different count describe the requested shape instead of being data, so they
   *     are dropped
   * @param onValueAdded invoked each time a new sanitized, distinct value is added to the batch
   *     result; may be {@code null} to skip live progress
   * @return a future completing with the list of distinct, sanitized values
   */
  private CompletableFuture<List<String>> streamGenerateValues(
      final HttpRequest request,
      @NotNull final String columnName,
      final int expectedArrayElements,
      @Nullable final Runnable onValueAdded) {
    final long inactivityTimeoutMillis = inactivityWindowMillis();

    final CompletableFuture<List<String>> result = new CompletableFuture<>();
    final List<String> values = new ArrayList<>();
    // Accumulates every raw token chunk so that, when the model yields no usable values, the
    // thrown error can include a snippet of what the model actually returned (diagnosability).
    final StringBuilder rawOutput = new StringBuilder();
    // Walks the accumulated answer as it grows, so each value is delivered when its closing quote
    // arrives instead of waiting for the whole object.
    final JsonValueScanner scanner = new JsonValueScanner(rawOutput);
    // Plain-text path, used until (and unless) the JSON contract shows up: complete lines are
    // handed out as they arrive, which keeps the per-value progress working for servers that
    // ignore `format`.
    final StringBuilder lineBuffer = new StringBuilder();
    // Last instant at which a token was received. Initialized to now so the watchdog doesn't fire
    // before the first token (prompt evaluation can take a while before generation starts).
    final AtomicReference<Long> lastTokenNanos = new AtomicReference<>(System.nanoTime());
    final AtomicReference<ScheduledFuture<?>> watchdog = new AtomicReference<>();
    final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> pending =
        new AtomicReference<>();
    // Response body stream once the request reaches its body-reading phase; the watchdog closes
    // it to abort a blocking read that cancelling the (already-completed) future cannot stop.
    final AtomicReference<InputStream> bodyRef = new AtomicReference<>();

    // Watchdog: abort the request if no token has arrived within the inactivity window.
    final Runnable watchdogTask =
        () -> {
          final long idleNanos = System.nanoTime() - lastTokenNanos.get();
          if (idleNanos < TimeUnit.MILLISECONDS.toNanos(inactivityTimeoutMillis)) {
            return;
          }
          log.warn(
              "Ollama stream for column '"
                  + columnName
                  + "' stalled (no tokens for "
                  + inactivityTimeoutMillis
                  + "ms), aborting request");
          // Complete the result BEFORE cancelling the in-flight request: cancelling pending
          // triggers this future's .handle callback, whose error-wrapping path only skips
          // overwriting when the result is already done. Otherwise the stalled diagnostic is
          // lost to a generic "streaming request failed" message.
          if (!result.isDone()) {
            result.completeExceptionally(
                new OllamaException(
                    "AI generation stalled for column '"
                        + columnName
                        + "' (no tokens received within "
                        + inactivityTimeoutMillis
                        + "ms)"));
          }
          final CompletableFuture<HttpResponse<InputStream>> inFlight = pending.get();
          if (Objects.nonNull(inFlight)) {
            inFlight.cancel(true);
          }
          // The request may already be in its body-phase, where cancelling the future is a
          // no-op; closing the body stream aborts the blocking read instead.
          abortBody(bodyRef.get());
        };
    watchdog.set(
        STREAMING_WATCHDOG.scheduleAtFixedRate(
            watchdogTask,
            STREAMING_WATCHDOG_POLL_MILLIS,
            STREAMING_WATCHDOG_POLL_MILLIS,
            TimeUnit.MILLISECONDS));

    pending.set(HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()));

    // handleAsync on the client executor: attaching a plain .handle to an already-completed
    // future (fast server) would run the whole stream-reading loop on the CALLER's thread,
    // where it blocks the thread that must cancel the stream. On the executor, the loop always
    // runs off the caller and stays cancellable via the body-stream close.
    pending
        .get()
        .handleAsync(
            (response, ex) -> {
              if (Objects.nonNull(ex)) {
                cancelWatchdog(watchdog);
                if (!result.isDone()) {
                  result.completeExceptionally(
                      new OllamaException(
                          "Ollama streaming request failed: " + ex.getMessage(), ex));
                }
                return null;
              }
              if (response.statusCode() != 200) {
                // Non-200: consume the stream to surface the server's own error message.
                try (final BufferedReader errorReader =
                    new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                  final StringBuilder errorBody = new StringBuilder();
                  String errorLine;
                  while (Objects.nonNull(errorLine = errorReader.readLine())) {
                    errorBody.append(errorLine);
                  }
                  if (!result.isDone()) {
                    result.completeExceptionally(
                        new OllamaException(
                            extractErrorMessage(response.statusCode(), errorBody.toString())));
                  }
                } catch (final IOException ioException) {
                  if (!result.isDone()) {
                    result.completeExceptionally(
                        new OllamaException(
                            "Ollama returned status code: " + response.statusCode(), ioException));
                  }
                }
                cancelWatchdog(watchdog);
                return null;
              }
              // Stream the NDJSON body line by line.
              bodyRef.set(response.body());
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
                  processStreamLine(
                      ndjsonLine,
                      columnName,
                      scanner,
                      lineBuffer,
                      rawOutput,
                      expectedArrayElements,
                      values,
                      onValueAdded);
                }
                // The stream ended: settle what the scanner could not close and, when the text is
                // not the requested JSON shape, fall back to the legacy line-based extraction.
                collectRemainingValues(
                    scanner, rawOutput, columnName, expectedArrayElements, values, onValueAdded);
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
                          "AI response contained no valid values for column '"
                              + columnName
                              + "'. Model output: "
                              + snippetOf(rawOutput.toString())));
                } else {
                  result.complete(values);
                }
              }
              return null;
            },
            HTTP_EXECUTOR);

    // Propagate external cancellation to the in-flight request.
    result.whenComplete(
        (ignored, throwable) -> {
          cancelWatchdog(watchdog);
          if (throwable instanceof CancellationException) {
            final CompletableFuture<HttpResponse<InputStream>> inFlight = pending.get();
            if (Objects.nonNull(inFlight)) {
              inFlight.cancel(true);
            }
            // Abort a blocking body read that the future cancellation cannot interrupt.
            abortBody(bodyRef.get());
          }
        });

    return result;
  }

  /**
   * Processes one NDJSON line from the Ollama stream. Each line is a JSON object carrying a token
   * chunk in its {@code response} field; a reasoning model may also send its chain-of-thought in a
   * separate {@code thinking} field, which is never a seed value and is ignored here.
   *
   * <p>The chunk is appended to the accumulated answer and handed to the JSON scanner, which
   * delivers every string of the requested {@code values} array the moment its closing quote
   * arrives, so the progress callback keeps firing value by value while the stream is still open.
   * Until that array shows up, the text is read as plain lines instead, so a server or a model that
   * ignores the {@code format} schema keeps the previous behaviour.
   */
  private void processStreamLine(
      final String ndjsonLine,
      final String columnName,
      final JsonValueScanner scanner,
      final StringBuilder lineBuffer,
      final StringBuilder rawOutput,
      final int expectedArrayElements,
      final List<String> values,
      @Nullable final Runnable onValueAdded) {
    try {
      final JsonObject json = JsonParser.parseString(ndjsonLine).getAsJsonObject();
      if (!json.has("response") || json.get("response").isJsonNull()) {
        return;
      }
      final String chunk = json.get("response").getAsString();
      rawOutput.append(chunk);
      if (!scanner.hasSeenValuesKey() && scanner.locateValuesArray()) {
        // The answer is the JSON object after all, so whatever the plain-text path collected
        // before it was prose about the request, never a value.
        values.clear();
        lineBuffer.setLength(0);
      }
      if (scanner.hasSeenValuesKey()) {
        scanner.scan(
            value ->
                addSanitizedValue(value, columnName, expectedArrayElements, values, onValueAdded));
        return;
      }
      appendPlainTextChunk(
          chunk, lineBuffer, columnName, expectedArrayElements, values, onValueAdded);
    } catch (final Exception e) {
      log.debug("Skipping malformed Ollama stream line: " + e.getMessage());
    }
  }

  /**
   * Appends one token chunk to the plain-text buffer and hands out every line it completes, which
   * is how values are streamed when the answer carries no JSON contract.
   */
  private void appendPlainTextChunk(
      final String chunk,
      final StringBuilder lineBuffer,
      final String columnName,
      final int expectedArrayElements,
      final List<String> values,
      @Nullable final Runnable onValueAdded) {
    lineBuffer.append(chunk);
    // Drop a chain-of-thought block as soon as its closing tag shows up, and hold back complete
    // lines while an opening tag is still unmatched: otherwise reasoning lines (which are long and
    // contain newlines) reach the caller as the first values of the column.
    discardClosedThinking(lineBuffer);
    if (isInsideThinking(lineBuffer)) {
      return;
    }
    int newlineIndex;
    while ((newlineIndex = lineBuffer.indexOf("\n")) >= 0) {
      final String completeLine = lineBuffer.substring(0, newlineIndex);
      lineBuffer.delete(0, newlineIndex + 1);
      addSanitizedValue(completeLine, columnName, expectedArrayElements, values, onValueAdded);
    }
  }

  /**
   * Settles the answer once the stream is closed. When the text is the requested JSON contract, the
   * end-of-stream parse is authoritative and adds any string the scanner could not close
   * incrementally; when the answer has no {@code values} array at all (a server or a model that
   * ignored the {@code format} schema), the plain-text extraction takes over, which is exactly the
   * behaviour this client had before structured output.
   */
  private void collectRemainingValues(
      final JsonValueScanner scanner,
      final StringBuilder rawOutput,
      final String columnName,
      final int expectedArrayElements,
      final List<String> values,
      @Nullable final Runnable onValueAdded) {
    final String rawText = rawOutput.toString();
    if (!scanner.hasSeenValuesKey()) {
      addPlainTextValues(rawText, columnName, expectedArrayElements, values, onValueAdded);
      return;
    }
    for (final String jsonValue : extractJsonValues(rawText)) {
      addSanitizedValue(jsonValue, columnName, expectedArrayElements, values, onValueAdded);
    }
  }

  /**
   * Legacy extraction: one value per line. Used when the answer carries no JSON contract, so the
   * previous chain-of-thought handling stays in place as the safety net.
   */
  private void addPlainTextValues(
      final String rawText,
      final String columnName,
      final int expectedArrayElements,
      final List<String> values,
      @Nullable final Runnable onValueAdded) {
    final StringBuilder buffer = new StringBuilder(rawText);
    discardClosedThinking(buffer);
    if (isInsideThinking(buffer)) {
      // Truncated mid-thought (num_predict exhausted): the trailing text is reasoning.
      return;
    }
    int newlineIndex;
    while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
      final String completeLine = buffer.substring(0, newlineIndex);
      buffer.delete(0, newlineIndex + 1);
      addSanitizedValue(completeLine, columnName, expectedArrayElements, values, onValueAdded);
    }
    if (buffer.length() > 0) {
      addSanitizedValue(buffer.toString(), columnName, expectedArrayElements, values, onValueAdded);
    }
  }

  private void addSanitizedValue(
      final String rawLine,
      final String columnName,
      final int expectedArrayElements,
      final List<String> values,
      @Nullable final Runnable onValueAdded) {
    final String sanitized = sanitizeAiOutput(rawLine, columnName);
    if (Objects.isNull(sanitized)
        || sanitized.isBlank()
        || isPlaceholderValue(sanitized, columnName)
        || !hasExpectedArrayShape(sanitized, expectedArrayElements, columnName)
        || values.contains(sanitized)) {
      return;
    }
    values.add(sanitized);
    if (Objects.nonNull(onValueAdded)) {
      onValueAdded.run();
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

  /**
   * Pre-warms the model by sending a minimal generate request. This forces Ollama to load the model
   * into VRAM before the actual batch generation starts, avoiding the cold-start latency penalty on
   * the first real request. The response is discarded.
   *
   * @return A CompletableFuture that completes when the model is loaded.
   */
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
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(
              response -> {
                if (response.statusCode() == 200) {
                  log.info("Model '{}' warmed up successfully", modelName);
                } else {
                  throw new OllamaException(
                      extractErrorMessage(response.statusCode(), response.body()));
                }
              });
    } catch (final Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Incremental scanner for the {@code {"values": [string]}} contract requested through the Ollama
   * {@code format} parameter.
   *
   * <p>It reads the answer as it is appended, finds the {@code "values"} array and walks its
   * elements with a small state machine, so every string literal is decoded and delivered as soon
   * as its closing quote arrives. Nothing outside that array is ever handed out: prose cannot
   * become a value even if a model manages to produce it.
   */
  private static final class JsonValueScanner {

    /** The array key requested by the schema sent through the Ollama {@code format} parameter. */
    private static final String VALUES_KEY = "\"values\"";

    private static final int VALUES_KEY_LENGTH = VALUES_KEY.length();

    /** Characters kept for a re-scan when the key may still be arriving split across chunks. */
    private static final int VALUES_KEY_LOOKBEHIND = VALUES_KEY_LENGTH - 1;

    private final StringBuilder text;

    /** Position from which the {@code "values"} key is searched on the next chunk. */
    private int searchFrom;

    /** Position of the next character to walk inside the array. */
    private int cursor;

    /** Index of the first character of the literal being read, or -1 outside a string. */
    private int stringStart = -1;

    private boolean insideValuesArray;
    private boolean insideString;
    private boolean escaped;
    private boolean valuesKeySeen;

    JsonValueScanner(final StringBuilder text) {
      this.text = text;
    }

    /** Whether the answer carries the requested key, i.e. it tried to honour the JSON contract. */
    boolean hasSeenValuesKey() {
      return valuesKeySeen;
    }

    /**
     * Looks for the {@code values} array without emitting anything, so the caller can decide what
     * to do with whatever it collected before the object showed up.
     */
    boolean locateValuesArray() {
      return insideValuesArray || enterValuesArray();
    }

    /** Scans everything appended since the last call and emits each complete string it closes. */
    void scan(final Consumer<String> onElement) {
      if (!locateValuesArray()) {
        return;
      }
      scanElements(onElement);
    }

    /** Locates the opening bracket of the {@code values} array, waiting for it across chunks. */
    private boolean enterValuesArray() {
      final int keyIndex = text.indexOf(VALUES_KEY, searchFrom);
      if (keyIndex < 0) {
        searchFrom = Math.max(0, text.length() - VALUES_KEY_LOOKBEHIND);
        return false;
      }
      searchFrom = keyIndex;
      final int bracketIndex = text.indexOf("[", keyIndex + VALUES_KEY_LENGTH);
      if (bracketIndex < 0) {
        return false;
      }
      valuesKeySeen = true;
      insideValuesArray = true;
      cursor = bracketIndex + 1;
      searchFrom = cursor;
      return true;
    }

    /** Walks the array elements, emitting the text between each pair of unescaped quotes. */
    private void scanElements(final Consumer<String> onElement) {
      while (cursor < text.length()) {
        final char current = text.charAt(cursor);
        if (escaped) {
          escaped = false;
          cursor++;
          continue;
        }
        if (current == '\\') {
          escaped = true;
          cursor++;
          continue;
        }
        if (current == '"') {
          if (insideString) {
            insideString = false;
            emit(stringStart, cursor, onElement);
            stringStart = -1;
          } else {
            insideString = true;
            stringStart = cursor + 1;
          }
          cursor++;
          continue;
        }
        if (!insideString && current == ']') {
          // The array closed: everything the model adds afterwards is not a value.
          insideValuesArray = false;
          cursor++;
          return;
        }
        cursor++;
      }
    }

    /** Decodes the JSON escapes of a literal and hands the resulting value to the caller. */
    private void emit(final int start, final int end, final Consumer<String> onElement) {
      if (start < 0 || end < start) {
        return;
      }
      try {
        final String decoded =
            GSON.fromJson("\"" + text.substring(start, end) + "\"", String.class);
        if (Objects.nonNull(decoded)) {
          onElement.accept(decoded);
        }
      } catch (final Exception e) {
        log.debug("Skipping malformed JSON string in Ollama response: " + e.getMessage());
      }
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
