/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.util;

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

/**
 * Parsing helpers for the PostgreSQL array literals the AI engines are asked to produce.
 *
 * <p>Small models rarely honour the requested shape exactly: they answer with flat scalars, with a
 * curly-braced literal, with quoted and spaced elements ({@code { 'a', 'b' }}) or with nested
 * groups whose elements are themselves literals ({@code {{a,b,c},{d,e,f}}}). A naive {@code
 * split(",")} mangles every one of those because the commas inside an element or inside a nested
 * group are indistinguishable from the separators. Every split here is therefore nesting-aware and
 * quote-aware, so the element count and the individual elements stay correct regardless of the
 * shape the model chose.
 *
 * <p>The public surface covers the three operations the AI pipeline needs:
 *
 * <ul>
 *   <li>{@link #isWrapped(String)} tells a braced/bracketed literal apart from a plain scalar.
 *   <li>{@link #topLevelElements(String)} returns the direct elements of the literal, used to
 *       validate the requested element count and to reject placeholders.
 *   <li>{@link #normalize(String)} and {@link #flatten(String)} collapse any nesting into a flat,
 *       deduplicated-by-shape literal the SQL layer can consume.
 * </ul>
 */
@UtilityClass
public class ArrayLiterals {

  /**
   * Whether the value is a braced ({@code {a,b,c}}) or bracketed ({@code [a,b,c]}) literal, as
   * opposed to a plain scalar the model returned outside the requested shape.
   */
  public static boolean isWrapped(@Nullable final String value) {
    if (value == null) {
      return false;
    }
    final String trimmed = value.trim();
    return (trimmed.startsWith("{") && trimmed.endsWith("}"))
        || (trimmed.startsWith("[") && trimmed.endsWith("]"));
  }

  /**
   * Direct (first-level) elements of a literal, with the outer wrapper removed and each element
   * trimmed of whitespace and surrounding quotes. A plain scalar yields a single-element list so
   * callers can treat both shapes uniformly.
   */
  public static List<String> topLevelElements(@Nullable final String value) {
    if (value == null) {
      return List.of();
    }
    final String trimmed = value.trim();
    if (!isWrapped(trimmed)) {
      final String single = stripQuotes(trimmed);
      return single.isBlank() ? List.of() : List.of(single);
    }
    final List<String> tokens = splitTopLevel(trimmed.substring(1, trimmed.length() - 1));
    final List<String> elements = new ArrayList<>(tokens.size());
    for (final String token : tokens) {
      final String cleaned = stripQuotes(token.trim());
      if (!cleaned.isBlank()) {
        elements.add(cleaned);
      }
    }
    return elements;
  }

  /**
   * Every leaf element of the value, flattening nested groups and trimming quotes and whitespace
   * from each one. A scalar yields a single-element list.
   */
  public static List<String> flatten(@Nullable final String value) {
    final List<String> leaves = new ArrayList<>();
    collectLeaves(value, leaves);
    return leaves;
  }

  /**
   * Rewrites any literal into a canonical flat PostgreSQL literal ({@code {a,b,c,d}}), collapsing
   * nested groups and stripping per-element quotes and spacing so the elements cannot be split
   * again by a comma. A plain scalar is returned trimmed, unchanged.
   */
  @Nullable
  public static String normalize(@Nullable final String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    if (!isWrapped(trimmed)) {
      return trimmed;
    }
    final List<String> leaves = flatten(trimmed);
    if (leaves.isEmpty()) {
      return trimmed;
    }
    final StringBuilder literal = new StringBuilder("{");
    for (int i = 0; i < leaves.size(); i++) {
      if (i > 0) {
        literal.append(',');
      }
      literal.append(quoteIfNeeded(leaves.get(i)));
    }
    return literal.append('}').toString();
  }

  private static void collectLeaves(@Nullable final String value, final List<String> leaves) {
    if (value == null) {
      return;
    }
    final String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    if (isWrapped(trimmed)) {
      for (final String token : splitTopLevel(trimmed.substring(1, trimmed.length() - 1))) {
        collectLeaves(token, leaves);
      }
      return;
    }
    final String cleaned = stripQuotes(trimmed);
    if (!cleaned.isBlank()) {
      leaves.add(cleaned);
    }
  }

  /**
   * Splits the body of a literal on the commas that sit outside any nested group and outside any
   * quoted element. Commas inside {@code {…}}, {@code […]} or {@code "…"}/{@code '…'} are kept as
   * part of their element.
   */
  static List<String> splitTopLevel(final String content) {
    final List<String> tokens = new ArrayList<>();
    int depth = 0;
    char quote = 0;
    boolean escaped = false;
    int start = 0;
    for (int i = 0; i < content.length(); i++) {
      final char current = content.charAt(i);
      if (quote != 0) {
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (current == quote) {
          quote = 0;
        }
        continue;
      }
      if (current == '"' || current == '\'') {
        quote = current;
        continue;
      }
      if (current == '{' || current == '[') {
        depth++;
        continue;
      }
      if (current == '}' || current == ']') {
        if (depth > 0) {
          depth--;
        }
        continue;
      }
      if (current == ',' && depth == 0) {
        tokens.add(content.substring(start, i).trim());
        start = i + 1;
      }
    }
    tokens.add(content.substring(start).trim());
    return tokens;
  }

  /** Strips a single pair of matching surrounding quotes, trimming the result. */
  static String stripQuotes(final String token) {
    if (token.length() >= 2) {
      final char first = token.charAt(0);
      final char last = token.charAt(token.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return token.substring(1, token.length() - 1).trim();
      }
    }
    return token;
  }

  /**
   * Re-quotes a flattened leaf that carries a character with structural meaning (a comma or a
   * brace/bracket/quote), so re-joining it does not create the illusion of a new element.
   */
  private static String quoteIfNeeded(final String leaf) {
    for (int i = 0; i < leaf.length(); i++) {
      final char current = leaf.charAt(i);
      if (current == ','
          || current == '{'
          || current == '}'
          || current == '['
          || current == ']'
          || current == '"') {
        return "\"" + leaf.replace("\"", "\\\"") + "\"";
      }
    }
    return leaf;
  }
}
