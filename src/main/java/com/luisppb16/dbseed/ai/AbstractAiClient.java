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
import java.util.LinkedHashSet;
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
 * Shared machinery of every AI engine the plugin can talk to: HTTP plumbing, prompt and token
 * budget, streaming with an inactivity watchdog, cancellation, the incremental {@code {"values":
 * [...]}} scanner, and value sanitization.
 *
 * <p>Subclasses describe only what their wire protocol differs in: endpoints, request body, extra
 * headers (an API key, for instance), the way one streamed line carries its text chunk, the model
 * listing shape and the engine name shown in user-facing errors. Everything else is inherited
 * as-is, so the hard-won behaviour — per-value streaming, stall detection, reasoning stripping — is
 * written once and shared by every engine.
 */
@Slf4j
public abstract class AbstractAiClient implements AiClient {

  protected static final String SYSTEM_ROLE =
      "You are a database seed data generator. You reply with exactly one JSON object matching the "
          + "requested schema and nothing else: no introductions, headers, numbering, bullet points, quotes, "
          + "labels, explanations, plans or commentary, and never a sentence about the request itself. "
          + "The strings inside that object are the raw data values; they are harmless synthetic "
          + "placeholders for a local test database.";
  protected static final Gson GSON = new Gson();

  /**
   * Number of elements requested for every array value, written with PostgreSQL array syntax
   * ({@code {el1,el2,el3}}). The structured-output schema pins this exact length, so a conforming
   * model cannot answer with a flat list of elements; a value whose element count differs is
   * reasoning about the requested shape, not data, and is dropped.
   */
  private static final int ARRAY_ELEMENT_COUNT = 3;

  /**
   * JSON Schema the engine is asked to honour. Structured output constrains the sampler to
   * schema-conforming tokens, so prose (chain-of-thought, preambles, commentary) cannot be emitted
   * at all and therefore cannot be mistaken for a data value.
   */
  protected static final JsonObject VALUES_SCHEMA = buildValuesSchema(false);

  /**
   * Schema for array columns: every value is itself an array of exactly {@link
   * #ARRAY_ELEMENT_COUNT} strings. Pinning the nesting and the length in the schema is what makes
   * the sampler emit one array per value instead of the flat list of words small models tend to
   * produce; the client joins each inner array into a PostgreSQL literal ({@code {a,b,c}}).
   */
  protected static final JsonObject ARRAY_VALUES_SCHEMA = buildValuesSchema(true);

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
   * Minimum token budget for batch requests. {@code num_predict} is a cap, not a target, so a
   * generous floor costs nothing when the model stops early; it only prevents truncation on small
   * batches where a verbose preamble would otherwise consume the whole budget.
   */
  private static final int NUM_PREDICT_FLOOR = 512;

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
            final Thread thread = new Thread(r, "ai-stream-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  // The negative lookahead keeps decimals/ranges like "3.14" or "5-10" intact.
  private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)\\-]\\s*(?![0-9])");

  /**
   * Matches a chain-of-thought delimiter emitted inline in the {@code response} field. Both the
   * ASCII and the full-width spellings are accepted, together with the {@code think}/{@code
   * thinking} variants, because the exact tag depends on the model.
   */
  private static final Pattern THINKING_TAG = Pattern.compile("[<＜]/?(?:think|thinking)[>＞]");

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

  /**
   * Shared executor using daemon threads for HTTP operations. Daemon threads do not prevent JVM
   * shutdown, so no explicit shutdown is required — the lifecycle is tied to the plugin/JVM.
   */
  private static final ExecutorService HTTP_EXECUTOR =
      Executors.newFixedThreadPool(
          Runtime.getRuntime().availableProcessors(),
          r -> {
            Thread t = new Thread(r, "ai-http");
            t.setDaemon(true);
            return t;
          });

  /**
   * Shared HTTP client for all AI engine operations. Uses {@link #HTTP_EXECUTOR} with daemon
   * threads, so it does not require explicit close — its lifecycle is tied to the plugin/JVM.
   */
  protected static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
          .executor(HTTP_EXECUTOR)
          .build();

  protected final String normalizedUrl;
  protected final String modelName;
  protected final int requestTimeoutSeconds;

  /** Test-only override of the streaming inactivity window (ms); {@code 0} means derive it. */
  private final long inactivityOverrideMillis;

  protected AbstractAiClient(
      @NotNull final String baseUrl,
      @NotNull final String modelName,
      final int requestTimeoutSeconds) {
    this(baseUrl, modelName, requestTimeoutSeconds, 0L);
  }

  /**
   * Test-only constructor: {@code inactivityOverrideMillis} shortens the streaming inactivity
   * window so watchdog-stall tests do not need to wait the real window.
   */
  protected AbstractAiClient(
      @NotNull final String baseUrl,
      @NotNull final String modelName,
      final int requestTimeoutSeconds,
      final long inactivityOverrideMillis) {
    this.normalizedUrl = normalizeUrl(baseUrl);
    this.modelName = modelName;
    this.requestTimeoutSeconds = Math.max(MIN_REQUEST_TIMEOUT_SECONDS, requestTimeoutSeconds);
    this.inactivityOverrideMillis = inactivityOverrideMillis;
  }

  // ── Protocol hooks ──────────────────────────────────────────────────────────

  static String normalizeUrl(final String url) {
    String normalized = url;
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
      normalized = "http://" + normalized;
    }
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    // OpenAI-compatible servers document their base URL with the version segment already in it
    // ("http://localhost:8888/v1"), but every path this client appends carries its own version, so
    // a URL copied from that documentation would otherwise produce /v1/v1/chat/completions.
    if (normalized.endsWith("/v1")) {
      normalized = normalized.substring(0, normalized.length() - "/v1".length());
    }
    return normalized;
  }

  /**
   * Builds the {@code {"values": [...]}} schema requested for every batch. Scalar columns ask for
   * an array of strings; array columns ask for an array of arrays, which constrains the sampler to
   * emit one nested array per value instead of a flat list of elements.
   *
   * @param nested when {@code true} each value is itself an array of exactly {@link
   *     #ARRAY_ELEMENT_COUNT} strings (array columns)
   */
  private static JsonObject buildValuesSchema(final boolean nested) {
    final JsonObject stringItem = new JsonObject();
    stringItem.addProperty("type", "string");

    final JsonObject item = new JsonObject();
    item.addProperty("type", nested ? "array" : "string");
    if (nested) {
      item.add("items", stringItem);
      item.addProperty("minItems", ARRAY_ELEMENT_COUNT);
      item.addProperty("maxItems", ARRAY_ELEMENT_COUNT);
    }

    final JsonObject values = new JsonObject();
    values.addProperty("type", "array");
    values.add("items", item);

    final JsonObject properties = new JsonObject();
    properties.add("values", values);

    final JsonArray required = new JsonArray();
    required.add("values");

    final JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    schema.add("properties", properties);
    schema.add("required", required);
    // Required by the OpenAI strict-schema mode; Ollama ignores the extra keyword.
    schema.addProperty("additionalProperties", false);
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
   * Pulls the human-readable reason out of an error body. Engines differ in how they wrap it: some
   * send {@code {"error": "boom"}}, the OpenAI- and Anthropic-compatible surfaces (Unsloth Studio
   * among them) send {@code {"error": {"message": "boom", "type": ...}}}, whose {@code error}
   * member is an object, and the rest of a FastAPI server — its authentication routes included —
   * send {@code {"detail": "boom"}}. Anything else falls back to a capped snippet of the body.
   */
  static String errorDetail(final String responseBody) {
    if (Objects.isNull(responseBody) || responseBody.isBlank()) {
      return "";
    }
    try {
      final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
      if (json.has("error") && !json.get("error").isJsonNull()) {
        final JsonElement error = json.get("error");
        if (error.isJsonPrimitive()) {
          return error.getAsString();
        }
        if (error.isJsonObject()) {
          final JsonElement message = error.getAsJsonObject().get("message");
          if (Objects.nonNull(message) && message.isJsonPrimitive()) {
            return message.getAsString();
          }
        }
      }
      final JsonElement detail = json.get("detail");
      if (Objects.nonNull(detail) && detail.isJsonPrimitive()) {
        return detail.getAsString();
      }
    } catch (final Exception ignored) {
      // Fall through and surface the raw body when it is not valid JSON.
    }
    final String trimmed = responseBody.trim();
    return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
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
   * Extracts the values of the {@code values} array from a complete answer. A string element is
   * taken as-is (scalar columns and flat answers for array columns); an inner array is joined into
   * a PostgreSQL literal ({@code {a,b,c}}) so array columns whose sampler honoured the nested
   * schema are handed out in the shape the caller parses. Returns an empty list when the answer is
   * not the requested JSON object (truncated output, or a plain-text answer).
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
      } else if (element.isJsonArray()) {
        final String literal = joinInnerArray(element.getAsJsonArray());
        if (Objects.nonNull(literal)) {
          extracted.add(literal);
        }
      }
    }
    return extracted;
  }

  @Nullable
  private static JsonArray findValuesArray(final JsonObject object) {
    final JsonElement declared = object.get("values");
    if (Objects.nonNull(declared)
        && declared.isJsonArray()
        && containsOnlyStringValues(declared.getAsJsonArray())) {
      return declared.getAsJsonArray();
    }
    // The model may name the array differently: any string array in the object is taken as the
    // requested value list, which keeps the extraction working across model families.
    for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
      if (entry.getValue().isJsonArray()
          && containsOnlyStringValues(entry.getValue().getAsJsonArray())) {
        return entry.getValue().getAsJsonArray();
      }
    }
    return null;
  }

  /**
   * True when every element is a string, or an inner array of strings (the nested payload the array
   * schema requests for array columns).
   */
  private static boolean containsOnlyStringValues(final JsonArray array) {
    for (final JsonElement element : array) {
      if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
        continue;
      }
      if (element.isJsonArray() && containsOnlyStringValues(element.getAsJsonArray())) {
        continue;
      }
      return false;
    }
    return true;
  }

  /**
   * Joins an inner array of strings into the PostgreSQL literal the caller expects, or {@code
   * null}.
   */
  @Nullable
  private static String joinInnerArray(final JsonArray array) {
    final StringBuilder literal = new StringBuilder("{");
    boolean first = true;
    for (final JsonElement element : array) {
      if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
        return null;
      }
      if (!first) {
        literal.append(",");
      }
      literal.append(element.getAsString());
      first = false;
    }
    if (first) {
      return null;
    }
    return literal.append("}").toString();
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
   * Checks the {@code {el1,el2,el3}} shape requested for array columns. A braced/bracketed value
   * with a different element count is reasoning about the requested shape, so it is dropped here;
   * {@code expectedArrayElements} is 0 for scalar columns, which accept any value.
   *
   * <p>A plain scalar (no braces or brackets) is accepted even for array columns: it is not the
   * requested literal shape, but it is still usable array data, and the caller wraps it as a
   * single-element array. Small models routinely ignore the "one array literal per string"
   * instruction and answer with one element per string, and dropping every such value would leave
   * the whole column to DataFaker.
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
      return true;
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

  /** Engine name as it appears in user-facing error messages ("Ollama", "Unsloth Studio"). */
  protected abstract String providerName();

  /** Path appended to the normalized base URL to check that the server answers. */
  protected abstract String pingPath();

  /** Path appended to the normalized base URL to list the served models. */
  protected abstract String listModelsPath();

  /** Path appended to the normalized base URL to generate one batch. */
  protected abstract String generatePath();

  /**
   * Builds the engine-specific body of one batch generation request.
   *
   * @param valuesSchema the structured-output schema to pin ({@link #VALUES_SCHEMA} for scalar
   *     columns, {@link #ARRAY_VALUES_SCHEMA} for array columns)
   */
  protected abstract String buildGenerateRequestBody(
      String prompt, double temperature, int numPredict, JsonObject valuesSchema);

  /** Parses the engine's model listing into sorted model names. */
  protected abstract List<String> parseModelsResponse(String responseBody);

  /**
   * Maps one line of the streamed answer to the text it emits.
   *
   * @return {@link ParsedChunk#text} with the emitted chunk, {@link ParsedChunk#progress} for a
   *     frame that proves the model is still working but carries no value (for example a reasoning
   *     delta), {@link ParsedChunk#ignore} for a line that carries no text (heartbeat, role-only
   *     delta, malformed JSON), {@link ParsedChunk#error} for a fatal error payload the engine sent
   *     mid-stream, or {@link ParsedChunk#end} for the engine's end-of-stream sentinel
   */
  protected abstract ParsedChunk parseStreamLine(String rawLine);

  /** Adds engine-specific headers (an API key, for instance) to a request under construction. */
  protected HttpRequest.Builder addAuthHeaders(final HttpRequest.Builder builder) {
    return builder;
  }

  /**
   * Builds an actionable error message for a non-2xx engine response. Extracts the {@code error}
   * field from the JSON body when present (e.g. {@code model 'x' not found}); otherwise falls back
   * to a trimmed snippet of the raw body.
   */
  protected String extractErrorMessage(final int statusCode, final String responseBody) {
    final String detail = errorDetail(responseBody);
    return detail.isEmpty()
        ? providerName() + " returned status code: " + statusCode
        : providerName() + " returned status code: " + statusCode + " — " + detail;
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
   * Pings the configured AI server to check connectivity.
   *
   * @return A CompletableFuture that completes when the ping is successful.
   */
  @Override
  public final CompletableFuture<Void> ping() {
    try {
      final HttpRequest request =
          addAuthHeaders(
                  HttpRequest.newBuilder()
                      .uri(URI.create(normalizedUrl + pingPath()))
                      .timeout(Duration.ofSeconds(PING_TIMEOUT_SECONDS))
                      .GET())
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(
              response -> {
                if (response.statusCode() != 200) {
                  throw new AiClientException(
                      extractErrorMessage(response.statusCode(), response.body()));
                }
              });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Lists the models available on the configured AI server.
   *
   * @return A CompletableFuture containing a list of model names.
   */
  @Override
  public final CompletableFuture<List<String>> listModels() {
    try {
      final HttpRequest request =
          addAuthHeaders(
                  HttpRequest.newBuilder()
                      .uri(URI.create(normalizedUrl + listModelsPath()))
                      .timeout(Duration.ofSeconds(LIST_MODELS_TIMEOUT_SECONDS))
                      .GET())
              .build();

      return HTTP_CLIENT
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenApply(
              response -> {
                if (response.statusCode() != 200) {
                  throw new AiClientException(
                      extractErrorMessage(response.statusCode(), response.body()));
                }
                return response.body();
              })
          .thenApply(this::parseModelsResponse);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Generates a batch of values for a column, streaming the engine response so sanitized values
   * arrive incrementally. Each time a new value survives sanitization and dedup, {@code
   * onValueAdded} is invoked, enabling live per-value progress.
   */
  @Override
  public final CompletableFuture<List<String>> generateBatchValues(
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
      final boolean singleWord = effectiveWordCount == MIN_WORD_COUNT;

      // One prompt assembled from a shared base plus the fragments the column shape and word count
      // change: the words clause right after the column's parenthetical, and the clause that
      // describes each string of the "values" array.
      final String wordsClause;
      if (singleWord) {
        wordsClause = isArrayType ? "" : ", one single word each";
      } else {
        wordsClause =
            ", up to " + effectiveWordCount + " words " + (isArrayType ? "per element" : "each");
      }

      final String valueClause;
      if (isArrayType) {
        valueClause =
            "; every string is a PostgreSQL array literal, that is, exactly "
                + ARRAY_ELEMENT_COUNT
                + (singleWord ? " single comma-separated words" : " comma-separated values")
                + " wrapped in curly braces. Write real words in place of the el1, el2 and el3 of"
                + " this template: no placeholders, no numbering, no commentary.";
      } else {
        valueClause =
            ", every one of them a real value for that column: no placeholders, no numbering, no"
                + " labels, no commentary.";
      }

      final String prompt =
          "%sGenerate exactly %d unique values for column \"%s\" (table: %s, type: %s)"
                  .formatted(contextLine, count, columnName, tableName, sqlType)
              + wordsClause
              + ". Answer with a JSON object holding a \"values\" array of exactly "
              + count
              + " strings"
              + valueClause;

      // Scalar values are not shaped into array literals, so their element count counts as one.
      final int elementCount = isArrayType ? ARRAY_ELEMENT_COUNT : 1;
      final int numPredict;
      if (isArrayType || !singleWord) {
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
        numPredict = Math.min(count * elementCount * BATCH_NUM_PREDICT_FACTOR, MAX_NUM_PREDICT);
      }

      final String requestBody =
          buildGenerateRequestBody(
              prompt,
              BATCH_TEMPERATURE,
              numPredict,
              isArrayType ? ARRAY_VALUES_SCHEMA : VALUES_SCHEMA);

      final HttpRequest request =
          addAuthHeaders(
                  HttpRequest.newBuilder()
                      .uri(URI.create(normalizedUrl + generatePath()))
                      .header("Content-Type", "application/json")
                      .timeout(Duration.ofSeconds(requestTimeoutSeconds)))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      return streamGenerateValues(
          request, columnName, isArrayType ? ARRAY_ELEMENT_COUNT : 0, onValueAdded);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Streams the engine response line by line and accumulates sanitized, distinct values as soon as
   * each value is completed in the stream. The protocol is supplied by {@link
   * #parseStreamLine(String)}, which turns every line the server writes into either a text chunk or
   * a control marker.
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
   * <p>The server emits the response incrementally, one token (or token chunk) per line. Because
   * the request pins a JSON schema, that text is a {@code {"values": [...]}} object whose string
   * literals are extracted as soon as they close ({@link JsonValueScanner}); every new value
   * invokes {@code onValueAdded}, so the caller advances the progress bar per value instead of per
   * batch. Anything outside that array — preambles, stray reasoning, a thinking field — is never
   * read as a value.
   *
   * <p>Servers that ignore the structured-output request produce no JSON contract. When the
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
    // LinkedHashSet: O(1) duplicate rejection per streamed value and first-seen insertion order, so
    // the delivered list holds exactly the values the ArrayList accumulated before, in the same
    // order.
    final Set<String> values = new LinkedHashSet<>();
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
              providerName()
                  + " stream for column '"
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
                new AiClientException(
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
                      new AiClientException(
                          providerName() + " streaming request failed: " + ex.getMessage(), ex));
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
                        new AiClientException(
                            extractErrorMessage(response.statusCode(), errorBody.toString())));
                  }
                } catch (final IOException ioException) {
                  if (!result.isDone()) {
                    result.completeExceptionally(
                        new AiClientException(
                            extractErrorMessage(response.statusCode(), ""), ioException));
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
                  final ParsedChunk parsed = parseStreamLine(ndjsonLine);
                  if (parsed.kind() == ChunkKind.END) {
                    break;
                  }
                  if (parsed.kind() == ChunkKind.ERROR) {
                    // The engine reported a fatal error mid-stream: record the payload for
                    // diagnostics and fail the batch with the server's own message. Return right
                    // away so the already-failed batch is not settled again after the loop.
                    rawOutput.append(parsed.text());
                    cancelWatchdog(watchdog);
                    if (!result.isDone()) {
                      result.completeExceptionally(new AiClientException(parsed.text()));
                    }
                    return null;
                  }
                  if (parsed.kind() == ChunkKind.IGNORE) {
                    // Heartbeat comments, event lines and empty deltas carry no progress; they must
                    // not reset the inactivity timer, or the watchdog never fires on a wedged
                    // stream that only keeps the connection alive.
                    continue;
                  }
                  // TEXT or PROGRESS: the server produced output, so the stream is alive. PROGRESS
                  // covers progress frames with no consumable value (for example reasoning deltas),
                  // which still prove the model is working and must not trip the watchdog.
                  lastTokenNanos.set(System.nanoTime());
                  if (parsed.kind() != ChunkKind.TEXT) {
                    continue;
                  }
                  processChunk(
                      parsed.text(),
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
                      new AiClientException(
                          "Failed reading "
                              + providerName()
                              + " stream for column '"
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
                      new AiClientException(
                          "AI response contained no valid values for column '"
                              + columnName
                              + "'. Model output: "
                              + snippetOf(rawOutput.toString())));
                } else {
                  result.complete(new ArrayList<>(values));
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
   * Feeds one text chunk emitted by the engine into the accumulated answer. The chunk is handed to
   * the JSON scanner, which delivers every string of the requested {@code values} array the moment
   * its closing quote arrives, so the progress callback keeps firing value by value while the
   * stream is still open. Until that array shows up, the text is read as plain lines instead, so a
   * server or a model that ignores the structured-output contract keeps the previous behaviour.
   */
  private void processChunk(
      final String chunk,
      final String columnName,
      final JsonValueScanner scanner,
      final StringBuilder lineBuffer,
      final StringBuilder rawOutput,
      final int expectedArrayElements,
      final Set<String> values,
      @Nullable final Runnable onValueAdded) {
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
      final Set<String> values,
      @Nullable final Runnable onValueAdded) {
    lineBuffer.append(chunk);
    // Drop a chain-of-thought block as soon as its closing tag shows up, and hold back complete
    // lines while an opening tag is still unmatched: otherwise reasoning lines (which are long and
    // contain newlines) reach the caller as the first values of the column.
    drainLineBuffer(lineBuffer, columnName, expectedArrayElements, values, onValueAdded);
  }

  /**
   * Drains a plain-text buffer: drops a closed chain-of-thought block, delivers every complete line
   * to {@link #addSanitizedValue} and leaves the trailing remainder in the buffer. Both the
   * streamed plain-text path and the legacy full-text extraction drain through here, so they stay
   * under the same chain-of-thought handling.
   *
   * @return {@code true} when the buffer was drained, {@code false} while it holds an unmatched
   *     opening thinking tag, in which case nothing was delivered
   */
  private boolean drainLineBuffer(
      final StringBuilder buffer,
      final String columnName,
      final int expectedArrayElements,
      final Set<String> values,
      @Nullable final Runnable onValueAdded) {
    discardClosedThinking(buffer);
    if (isInsideThinking(buffer)) {
      return false;
    }
    int newlineIndex;
    while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
      final String completeLine = buffer.substring(0, newlineIndex);
      buffer.delete(0, newlineIndex + 1);
      addSanitizedValue(completeLine, columnName, expectedArrayElements, values, onValueAdded);
    }
    return true;
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
      final Set<String> values,
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
      final Set<String> values,
      @Nullable final Runnable onValueAdded) {
    final StringBuilder buffer = new StringBuilder(rawText);
    if (!drainLineBuffer(buffer, columnName, expectedArrayElements, values, onValueAdded)) {
      // Truncated mid-thought (num_predict exhausted): the trailing text is reasoning.
      return;
    }
    if (buffer.length() > 0) {
      addSanitizedValue(buffer.toString(), columnName, expectedArrayElements, values, onValueAdded);
    }
  }

  private void addSanitizedValue(
      final String rawLine,
      final String columnName,
      final int expectedArrayElements,
      final Set<String> values,
      @Nullable final Runnable onValueAdded) {
    final String sanitized = sanitizeAiOutput(rawLine, columnName);
    if (Objects.isNull(sanitized)
        || sanitized.isBlank()
        || isPlaceholderValue(sanitized, columnName)
        || !hasExpectedArrayShape(sanitized, expectedArrayElements, columnName)) {
      return;
    }
    if (!values.add(sanitized)) {
      // Already collected: a duplicate is neither delivered twice nor made to advance the progress.
      return;
    }
    if (Objects.nonNull(onValueAdded)) {
      onValueAdded.run();
    }
  }

  /**
   * What one streamed line means: text to consume, progress without a value, nothing to consume, a
   * fatal error, or end of stream.
   */
  protected enum ChunkKind {
    TEXT,
    PROGRESS,
    IGNORE,
    END,
    ERROR
  }

  /**
   * One parsed line of a streamed answer. {@code TEXT} carries a consumable chunk; {@code PROGRESS}
   * marks a frame that proves the model is still working but yields no value (for example a
   * reasoning delta), so it only refreshes the inactivity timer. {@code END} is what lets an engine
   * stop reading on its own sentinel (the SSE {@code data: [DONE]} line) instead of waiting for the
   * socket to close. {@code ERROR} carries the server's error message: the stream loop records it
   * in the raw output and fails the batch with it.
   */
  protected record ParsedChunk(ChunkKind kind, String text) {

    static ParsedChunk text(final String value) {
      return new ParsedChunk(ChunkKind.TEXT, value);
    }

    static ParsedChunk progress() {
      return new ParsedChunk(ChunkKind.PROGRESS, null);
    }

    static ParsedChunk ignore() {
      return new ParsedChunk(ChunkKind.IGNORE, null);
    }

    static ParsedChunk error(final String errorMessage) {
      return new ParsedChunk(ChunkKind.ERROR, errorMessage);
    }

    static ParsedChunk end() {
      return new ParsedChunk(ChunkKind.END, null);
    }
  }

  /**
   * Incremental scanner for the {@code {"values": [string]}} contract the engine is asked to honour
   * through its structured-output parameter.
   *
   * <p>It reads the answer as it is appended, finds the {@code "values"} array and walks its
   * elements with a small state machine, so every string literal is decoded and delivered as soon
   * as its closing quote arrives. Nothing outside that array is ever handed out: prose cannot
   * become a value even if a model manages to produce it.
   */
  private static final class JsonValueScanner {

    /** The array key requested by the schema sent through the structured-output parameter. */
    private static final String VALUES_KEY = "\"values\"";

    private static final int VALUES_KEY_LENGTH = VALUES_KEY.length();

    /** Characters kept for a re-scan when the key may still be arriving split across chunks. */
    private static final int VALUES_KEY_LOOKBEHIND = VALUES_KEY_LENGTH - 1;

    private final StringBuilder text;

    /** Decoded elements of the inner array being read; joined into a literal when it closes. */
    private final StringBuilder innerElements = new StringBuilder();

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

    /**
     * Nesting depth of the value being read: {@code 0} when the {@code values} array holds strings
     * directly, {@code 1} while reading an inner array (array columns ask the sampler for an array
     * of arrays). Deeper nesting is flattened into depth 1 so nothing is lost.
     */
    private int arrayDepth;

    /** Whether {@link #innerElements} already holds an element (separator handling). */
    private boolean innerHasElement;

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

    /**
     * Walks the array elements, emitting each value once its JSON literal closes. A string element
     * is handed out as-is (scalar columns, and flat answers for array columns that ignore the
     * nested schema); the elements of an inner array are joined into a PostgreSQL literal ({@code
     * {a,b,c}}) and handed out as one value when the inner array closes.
     */
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
            final String decoded = decode(stringStart, cursor);
            if (Objects.nonNull(decoded)) {
              if (arrayDepth > 0) {
                appendInnerElement(decoded);
              } else {
                onElement.accept(decoded);
              }
            }
            stringStart = -1;
          } else {
            insideString = true;
            stringStart = cursor + 1;
          }
          cursor++;
          continue;
        }
        if (!insideString && current == '[') {
          if (arrayDepth == 0) {
            // Nested payload: the value being read is itself an array of elements.
            arrayDepth = 1;
            resetInnerElements();
          } else {
            // Deeper nesting is outside the requested contract; flatten it into the inner array.
            arrayDepth++;
          }
          cursor++;
          continue;
        }
        if (!insideString && current == ']') {
          if (arrayDepth > 1) {
            arrayDepth--;
            cursor++;
            continue;
          }
          if (arrayDepth == 1) {
            // Inner array closed: hand out the joined literal and keep scanning values.
            emitInnerArray(onElement);
            arrayDepth = 0;
            cursor++;
            continue;
          }
          // The values array closed: everything the model adds afterwards is not a value.
          insideValuesArray = false;
          cursor++;
          return;
        }
        cursor++;
      }
    }

    /** Decodes the JSON escapes of a literal, or {@code null} when it is not a valid string. */
    @Nullable
    private String decode(final int start, final int end) {
      if (start < 0 || end < start) {
        return null;
      }
      try {
        return GSON.fromJson("\"" + text.substring(start, end) + "\"", String.class);
      } catch (final Exception e) {
        log.debug("Skipping malformed JSON string in engine response: " + e.getMessage());
        return null;
      }
    }

    private void resetInnerElements() {
      innerElements.setLength(0);
      innerHasElement = false;
    }

    private void appendInnerElement(final String element) {
      if (innerHasElement) {
        innerElements.append(",");
      }
      innerElements.append(element);
      innerHasElement = true;
    }

    /** Emits the elements gathered for the inner array as one PostgreSQL literal. */
    private void emitInnerArray(final Consumer<String> onElement) {
      if (innerHasElement) {
        onElement.accept("{" + innerElements + "}");
      }
      resetInnerElements();
    }
  }
}
