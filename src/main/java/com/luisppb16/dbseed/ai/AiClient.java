/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface AiClient {

  Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)\\-]\\s*");

  String SYSTEM_ROLE =
      "You are a database seed data generator. You output raw data values only. "
          + "Never add introductions, headers, numbering, bullet points, quotes, labels, or explanations. "
          + "Start immediately with the first value.";

  static String sanitizeAiOutput(final String value, @Nullable final String columnName) {
    if (Objects.isNull(value)) return null;
    String cleaned = value.lines().findFirst().orElse("").trim();

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

  static String stripSurroundingQuotes(final String text) {
    if (text.length() >= 2
        && ((text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("'") && text.endsWith("'")))) {
      return text.substring(1, text.length() - 1).trim();
    }
    return text;
  }

  static boolean isAiPreamble(final String text) {
    final String lower = text.toLowerCase();
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
        || lower.contains("values for column");
  }

  static boolean isAiRefusal(final String text) {
    final String lower = text.toLowerCase();
    return lower.startsWith("i cannot")
        || lower.startsWith("i can't")
        || lower.startsWith("i'm sorry")
        || lower.startsWith("i am sorry")
        || lower.startsWith("sorry,")
        || lower.startsWith("as an ai")
        || lower.startsWith("i'm not able")
        || lower.startsWith("i am not able");
  }

  static String stripColumnPrefix(final String text, final String columnName) {
    final String lower = text.toLowerCase();
    final String colLower = columnName.toLowerCase();
    if (lower.startsWith(colLower)) {
      String rest = text.substring(columnName.length()).trim();
      if (rest.startsWith("=") || rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      return stripSurroundingQuotes(rest);
    }
    return text;
  }

  static boolean isArrayType(final String sqlType) {
    if (Objects.isNull(sqlType) || sqlType.isBlank()) {
      return false;
    }
    final String lower = sqlType.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith("[]") || lower.startsWith("_") || lower.contains("array");
  }

  static String buildPrompt(
      @NotNull String applicationContext,
      @NotNull String tableName,
      @NotNull String columnName,
      @NotNull String sqlType,
      int wordCount,
      int count) {
    final int effectiveWordCount = Math.max(1, wordCount);
    final String contextLine =
        !applicationContext.isBlank() ? "Application context: " + applicationContext + "\n" : "";
    final boolean isArrayType = isArrayType(sqlType);

    if (isArrayType) {
      final int elementCount = 3;
      if (effectiveWordCount == 1) {
        return "%sGenerate exactly %d unique array values for column \"%s\" (table: %s, type: %s). Format: {el1,el2,el3} with %d elements. Single word each. PostgreSQL array syntax. One per line. Raw values only."
            .formatted(contextLine, count, columnName, tableName, sqlType, elementCount);
      } else {
        return "%sGenerate exactly %d unique array values for column \"%s\" (table: %s, type: %s). Format: {el1,el2,el3} with %d elements, up to %d words each. PostgreSQL array syntax. One per line. Raw values only."
            .formatted(
                contextLine,
                count,
                columnName,
                tableName,
                sqlType,
                elementCount,
                effectiveWordCount);
      }
    } else {
      if (effectiveWordCount == 1) {
        return "%sGenerate exactly %d unique values for column \"%s\" (table: %s, type: %s). One per line. Raw values only."
            .formatted(contextLine, count, columnName, tableName, sqlType);
      } else {
        return "%sGenerate exactly %d unique values for column \"%s\" (table: %s, type: %s). Up to %d words each. One per line. Raw values only."
            .formatted(contextLine, count, columnName, tableName, sqlType, effectiveWordCount);
      }
    }
  }

  static int computeNumPredict(int count, int wordCount, @NotNull String sqlType) {
    final int effectiveWordCount = Math.max(1, wordCount);
    final boolean isArrayType = isArrayType(sqlType);
    final int batchNumPredictFactor = 15;
    final int wordCountPredictMultiplier = 3;

    if (isArrayType) {
      final int elementCount = 3;
      return count
          * elementCount
          * Math.max(batchNumPredictFactor, effectiveWordCount * wordCountPredictMultiplier);
    } else if (effectiveWordCount == 1) {
      return count * batchNumPredictFactor;
    } else {
      return count
          * Math.max(batchNumPredictFactor, effectiveWordCount * wordCountPredictMultiplier);
    }
  }

  CompletableFuture<Void> ping();

  CompletableFuture<List<String>> listModels();

  CompletableFuture<List<String>> generateBatchValues(
      @NotNull String applicationContext,
      @NotNull String tableName,
      @NotNull String columnName,
      @NotNull String sqlType,
      int wordCount,
      int count);

  CompletableFuture<Void> warmModel();
}
