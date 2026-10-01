/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Contract for the AI engines the plugin can talk to.
 *
 * <p>Implementations differ only in the wire protocol they speak: the native Ollama API ({@link
 * OllamaClient}) or the OpenAI-compatible chat completions API ({@link OpenAiCompatibleClient})
 * served by Unsloth Studio, LM Studio, vLLM, llama.cpp and similar. Callers — the settings UI,
 * {@code DataGenerator} and {@code RowGenerator} — depend on this interface alone, so adding an
 * engine means adding an implementation and an {@link AiProvider} constant, nothing else.
 */
public interface AiClient {

  /**
   * Checks that the configured server answers.
   *
   * @return a future completing when the server is reachable
   */
  CompletableFuture<Void> ping();

  /**
   * Lists the models the server can serve.
   *
   * @return a future completing with the sorted model names
   */
  CompletableFuture<List<String>> listModels();

  /**
   * Generates a batch of values for one column, streaming the response so sanitized values arrive
   * incrementally. Each time a new value survives sanitization and dedup, {@code onValueAdded} is
   * invoked, which is what keeps the per-value progress live.
   *
   * @param applicationContext user-provided domain description, may be blank
   * @param tableName table the column belongs to
   * @param columnName column being seeded
   * @param sqlType SQL type of the column, used to detect array types
   * @param wordCount words requested per value
   * @param count how many values the batch needs
   * @param onValueAdded invoked per new value; may be {@code null} to skip live progress
   * @return a future completing with the distinct, sanitized values
   */
  CompletableFuture<List<String>> generateBatchValues(
      @NotNull String applicationContext,
      @NotNull String tableName,
      @NotNull String columnName,
      @NotNull String sqlType,
      int wordCount,
      int count,
      @Nullable Runnable onValueAdded);

  /**
   * Overload without live progress: values are still streamed internally, but no per-value callback
   * is invoked.
   */
  default CompletableFuture<List<String>> generateBatchValues(
      final String applicationContext,
      final String tableName,
      final String columnName,
      final String sqlType,
      final int wordCount,
      final int count) {
    return generateBatchValues(
        applicationContext, tableName, columnName, sqlType, wordCount, count, null);
  }

  /**
   * Pre-loads the model so the first batch does not pay the cold-start cost. Engines that load the
   * model on their own may complete immediately.
   *
   * @return a future completing when the model is ready
   */
  CompletableFuture<Void> warmModel();
}
