/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Advanced SQL constraint parser for database schema introspection in the DBSeed plugin.
 *
 * <p>This class implements sophisticated parsing algorithms to extract and interpret database
 * constraints from SQL CHECK expressions and multi-column constraint definitions. It handles
 * complex constraint patterns including range validations, equality constraints, IN lists, length
 * restrictions, and multi-column dependencies expressed in disjunctive normal form. The parser
 * accommodates various SQL dialects and handles complex nested expressions with proper operator
 * precedence and parenthesis grouping.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Parsing single-column constraints from CHECK expressions (ranges, equality, lists)
 *   <li>Extracting multi-column constraint relationships from complex logical expressions
 *   <li>Handling different SQL syntax patterns and dialect variations
 *   <li>Processing disjunctive normal form (DNF) expressions with AND/OR combinations
 *   <li>Resolving column references in schema.table.column format
 *   <li>Normalizing constraint expressions for consistent interpretation
 * </ul>
 *
 * <p>The implementation uses efficient regular expression patterns with caching mechanisms for
 * improved performance when processing repetitive constraint patterns. The parser correctly handles
 * SQL literals with various quoting mechanisms, cast operators, and complex nested parentheses
 * structures. Multi-column constraint parsing employs advanced tokenization techniques to properly
 * separate AND/OR logical operators while respecting expression grouping.
 */
public final class ConstraintParser {

  private static final String CAST_REGEX = "(?:\\s*::[\\w\\[\\]]+(?:\\s+[\\w\\[\\]]+)*)*";
  private static final Pattern COL_EQ_VAL_PATTERN =
      Pattern.compile(
          "(?i)"
              + "^"
              + "[\\s()]*"
              + "(?:(?:\"?[\\w\\.$]+\"?)\\.)*"
              + "\"?([\\w\\.$]+)\"?"
              + "[\\s()]*"
              + CAST_REGEX
              + "\\s*=\\s*"
              + "("
              + "'(?:[^']|'')*'"
              + "|"
              + "\"(?:[^\"]|\"\")*\""
              + "|"
              + "[\\w+-]+(?:\\.\\d+)?"
              + ")"
              + CAST_REGEX
              + "[\\s()]*"
              + "$");

  private static final Pattern COL_EQ_VAL_PATTERN_RELAXED =
      Pattern.compile(
          "(?i)"
              + "[\\s()]*"
              + "\"?([\\w\\.$]+)\"?"
              + "[\\s()]*"
              + "(?:::[\\w\\[\\]]+(?:\\s+[\\w\\[\\]]+)*)?"
              + "\\s*=\\s*"
              + "("
              + "'[^']*'"
              + "|"
              + "\"[^\"]*\""
              + "|"
              + "\\d+"
              + ")"
              + "[\\s()]*");

  private final String columnName;
  private final ColumnPatterns patterns;

  public ConstraintParser(final String columnName) {
    this.columnName = Objects.requireNonNull(columnName, "Column name cannot be null");
    this.patterns = ColumnPatterns.forColumn(columnName);
  }

  private static String stripQuotes(final String s) {
    if (Objects.isNull(s)) return null;
    final String trimmed = s.trim();
    if ((trimmed.startsWith("'") && trimmed.endsWith("'"))
        || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }

  public static List<MultiColumnConstraint> parseMultiColumnConstraints(final List<String> checks) {
    final List<MultiColumnConstraint> result = new java.util.ArrayList<>();
    for (final String check : checks) {
      if (Objects.isNull(check) || check.isBlank()) {
        continue;
      }
      final String checkUpper = check.toUpperCase(Locale.ROOT);
      if (!checkUpper.contains("=")) {
        continue;
      }
      final boolean hasOr = checkUpper.matches(".*\\bOR\\b.*");
      final boolean hasAnd = checkUpper.matches(".*\\bAND\\b.*");
      if (!hasOr && !hasAnd) {
        continue;
      }
      final MultiColumnConstraint mcc = parseDnfConstraint(check);
      if (Objects.nonNull(mcc) && !mcc.allowedCombinations().isEmpty()) {
        result.add(mcc);
      }
    }
    return result;
  }

  private static MultiColumnConstraint parseDnfConstraint(final String check) {
    String clean = cleanParens(check);

    clean = clean.replaceAll("(?i)^CHECK\\s*", "");
    clean = cleanParens(clean);

    final String[] parts = splitByTopLevelOr(clean);
    final List<java.util.Map<String, String>> combinations =
        Stream.of(parts)
            .map(ConstraintParser::parseAndClause)
            .filter(Objects::nonNull)
            .filter(m -> !m.isEmpty())
            .toList();

    if (combinations.isEmpty()) {
      return null;
    }

    final Set<String> allColumns =
        combinations.stream()
            .flatMap(m -> m.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());

    return new MultiColumnConstraint(allColumns, combinations);
  }

  private static String[] splitByTopLevelOr(String input) {
    List<String> parts = new java.util.ArrayList<>();
    int parenLevel = 0;
    int lastSplit = 0;

    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);

      if (c == '(') {
        parenLevel++;
      } else if (c == ')') {
        parenLevel--;
      } else if (parenLevel == 0 && i + 2 < input.length()) {
        if (Character.toLowerCase(input.charAt(i)) == 'o'
            && Character.toLowerCase(input.charAt(i + 1)) == 'r'
            && (i == 0 || !Character.isLetterOrDigit(input.charAt(i - 1)))
            && (i + 2 >= input.length() || !Character.isLetterOrDigit(input.charAt(i + 2)))) {

          int end = i;
          int start = i + 2;

          while (end > lastSplit && Character.isWhitespace(input.charAt(end - 1))) end--;
          while (start < input.length() && Character.isWhitespace(input.charAt(start))) start++;

          parts.add(input.substring(lastSplit, end).trim());
          lastSplit = start;
          i += 1;
        }
      }
    }

    if (lastSplit < input.length()) {
      parts.add(input.substring(lastSplit).trim());
    }

    return parts.toArray(new String[0]);
  }

  private static String cleanParens(final String s) {
    String clean = s.trim();
    while (clean.startsWith("(") && clean.endsWith(")") && isWrappedInParens(clean)) {
      clean = clean.substring(1, clean.length() - 1).trim();
    }
    return clean;
  }

  private static java.util.Map<String, String> parseAndClause(final String clause) {
    final java.util.Map<String, String> combination = new java.util.HashMap<>();
    final String[] conditions =
        clause.split("(?i)\\s+AND\\s+|(?<=\\))\\s*AND\\s*|\\s*AND\\s*(?=\\()");
    for (final String cond : conditions) {
      final String cleanCond = cleanParens(cond);
      Matcher m = COL_EQ_VAL_PATTERN.matcher(cleanCond);
      if (m.find()) {
        final String col = m.group(1).replace("\"", "");
        final String val = stripQuotes(m.group(2));
        combination.put(col, val);
      } else {
        m = COL_EQ_VAL_PATTERN_RELAXED.matcher(cleanCond);
        if (m.find()) {
          final String col = m.group(1).replace("\"", "");
          final String val = stripQuotes(m.group(2));
          combination.put(col, val);
        } else {
          return null;
        }
      }
    }
    return combination;
  }

  private static boolean isWrappedInParens(final String s) {
    if (!s.startsWith("(") || !s.endsWith(")")) return false;
    int balance = 0;
    for (int i = 0; i < s.length() - 1; i++) {
      final char c = s.charAt(i);
      if (c == '(') balance++;
      else if (c == ')') balance--;
      if (balance == 0) return false;
    }
    return true;
  }

  public ParsedConstraint parse(
      final List<CheckExpression> checkExpressions, final int columnLength) {
    if (Objects.isNull(checkExpressions) || checkExpressions.isEmpty()) {
      return ParsedConstraint.empty();
    }

    Double lower = null;
    Double upper = null;
    final Set<String> allowed = new HashSet<>();
    Integer maxLen = null;
    final String columnLow = columnName.toLowerCase(Locale.ROOT);

    for (final CheckExpression ce : checkExpressions) {
      if (!ce.noParensLow().contains(columnLow)) {
        continue;
      }

      final String check = ce.original();
      final String exprNoParens = ce.noParens();

      final BetweenParseResult betweenResult =
          parseBetweenConstraint(exprNoParens, check, lower, upper);
      lower = betweenResult.lower();
      upper = betweenResult.upper();

      final RangeParseResult rangeResult = parseRangeConstraint(exprNoParens, check, lower, upper);
      lower = rangeResult.lower();
      upper = rangeResult.upper();

      parseInListConstraint(check, allowed);
      parseAnyArrayConstraint(check, allowed);
      parseEqualityConstraint(exprNoParens, allowed);
      maxLen = parseLengthConstraint(check, maxLen);
    }

    if (columnLength > 0 && (Objects.isNull(maxLen) || columnLength < maxLen)) {
      maxLen = columnLength;
    }

    return new ParsedConstraint(lower, upper, Set.copyOf(allowed), maxLen);
  }

  private BetweenParseResult parseBetweenConstraint(
      final String exprNoParens,
      final String check,
      final Double currentLower,
      final Double currentUpper) {

    final Matcher mb = patterns.between().matcher(exprNoParens);
    Double newLower = currentLower;
    Double newUpper = currentUpper;

    while (mb.find()) {
      try {
        final double a = Double.parseDouble(mb.group(1));
        final double b = Double.parseDouble(mb.group(2));
        final double lo = Math.min(a, b);
        final double hi = Math.max(a, b);
        newLower = Objects.isNull(newLower) ? lo : Math.max(newLower, lo);
        newUpper = Objects.isNull(newUpper) ? hi : Math.min(newUpper, hi);
      } catch (final NumberFormatException ignored) {
      }
    }
    return new BetweenParseResult(newLower, newUpper);
  }

  private RangeParseResult parseRangeConstraint(
      final String exprNoParens,
      final String check,
      final Double currentLower,
      final Double currentUpper) {

    final Matcher mr = patterns.range().matcher(exprNoParens);
    Double newLower = currentLower;
    Double newUpper = currentUpper;

    while (mr.find()) {
      final String op = mr.group(1);
      final String num = mr.group(2);
      try {
        final double val = Double.parseDouble(num);
        newLower = updateLowerBound(op, val, newLower);
        newUpper = updateUpperBound(op, val, newUpper);
      } catch (final NumberFormatException ignored) {
      }
    }
    return new RangeParseResult(newLower, newUpper);
  }

  private Double updateLowerBound(final String op, final double val, final Double currentLower) {
    return switch (op) {
      case ">" ->
          Objects.isNull(currentLower) ? Math.nextUp(val) : Math.max(currentLower, Math.nextUp(val));
      case ">=", "=" -> Objects.isNull(currentLower) ? val : Math.max(currentLower, val);
      default -> currentLower;
    };
  }

  private Double updateUpperBound(final String op, final double val, final Double currentUpper) {
    return switch (op) {
      case "<" ->
          Objects.isNull(currentUpper) ? Math.nextDown(val) : Math.min(currentUpper, Math.nextDown(val));
      case "<=", "=" -> Objects.isNull(currentUpper) ? val : Math.min(currentUpper, val);
      default -> currentUpper;
    };
  }

  private void parseInListConstraint(final String check, final Set<String> allowed) {
    final Matcher mi = patterns.in().matcher(check);
    while (mi.find()) {
      extractValuesFromList(mi.group(1), allowed, false);
    }
  }

  private void parseAnyArrayConstraint(final String check, final Set<String> allowed) {
    final Matcher ma = patterns.anyArray().matcher(check);
    while (ma.find()) {
      extractValuesFromList(ma.group(1), allowed, true);
    }
  }

  private void parseEqualityConstraint(final String exprNoParens, final Set<String> allowed) {
    final Matcher me = patterns.eq().matcher(exprNoParens);
    while (me.find()) {
      final String s = stripQuotes(me.group(1));
      if (Objects.nonNull(s) && !s.isEmpty()) {
        allowed.add(s);
      }
    }
  }

  private Integer parseLengthConstraint(final String check, final Integer currentMaxLen) {
    final Matcher ml = patterns.len().matcher(check);
    Integer newMaxLen = currentMaxLen;

    while (ml.find()) {
      final String op = ml.group(1);
      final String num = ml.group(2);
      try {
        final int v = Integer.parseInt(num);
        if ("<".equals(op) || "<=".equals(op) || "=".equals(op)) {
          newMaxLen = Objects.isNull(newMaxLen) ? v : Math.min(newMaxLen, v);
        }
      } catch (final NumberFormatException ignored) {
      }
    }
    return newMaxLen;
  }

  private void extractValuesFromList(
      final String list, final Set<String> allowed, final boolean removeCasts) {
    Arrays.stream(list.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> removeCasts ? s.replaceAll("(?i)::[\\w\\[\\]]+(?:\\s+[\\w\\[\\]]+)*", "") : s)
        .map(ConstraintParser::stripQuotes)
        .forEach(allowed::add);
  }

  public record CheckExpression(String original, String noParens, String noParensLow) {
    public CheckExpression {
      Objects.requireNonNull(original, "Original expression cannot be null");
      Objects.requireNonNull(noParens, "NoParens expression cannot be null");
      Objects.requireNonNull(noParensLow, "NoParensLow expression cannot be null");
    }
  }

  public record ParsedConstraint(
      Double min, Double max, Set<String> allowedValues, Integer maxLength) {
    public static ParsedConstraint empty() {
      return new ParsedConstraint(null, null, Collections.emptySet(), null);
    }
  }

  public record MultiColumnConstraint(
      Set<String> columns, List<java.util.Map<String, String>> allowedCombinations) {}

  private record BetweenParseResult(Double lower, Double upper) {}

  private record RangeParseResult(Double lower, Double upper) {}

  private record ColumnPatterns(
      Pattern between, Pattern range, Pattern in, Pattern anyArray, Pattern eq, Pattern len) {
    private static final java.util.Map<String, ColumnPatterns> CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    static ColumnPatterns forColumn(final String columnName) {
      return CACHE.computeIfAbsent(columnName, ColumnPatterns::create);
    }

    private static ColumnPatterns create(final String name) {
      final String colPattern = "(?i)(?:\\w+\\.)*+\\s*\"?" + Pattern.quote(name) + "\"?\\s*";

      return new ColumnPatterns(
          Pattern.compile(
              colPattern
                  + CAST_REGEX
                  + "\\s+BETWEEN\\s+([-+]?\\d+(?:\\.\\d+)?)\\s+AND\\s+([-+]?\\d+(?:\\.\\d+)?)",
              Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              colPattern + CAST_REGEX + "\\s*(>=|<=|>|<|=)\\s*([-+]?\\d+(?:\\.\\d+)?)",
              Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              colPattern + CAST_REGEX + "\\s+IN\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              colPattern + CAST_REGEX + "\\s*=\\s*ANY\\s+ARRAY\\s*\\[(.*?)\\]",
              Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              colPattern + CAST_REGEX + "\\s*=\\s*(?!ANY\\b)('.*?'|\"[^\"]*+\"|[\\w+-]+)",
              Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              "(?i)(?:char_length|length)\\s*\\(\\s*"
                  + colPattern
                  + "\\s*\\)\\s*(<=|<|=)\\s*(\\d+)"));
    }
  }
}
