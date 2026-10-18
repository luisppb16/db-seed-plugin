/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.generator;

import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.datafaker.Faker;

/**
 * Advanced value generation engine for database seeding operations in the DBSeed plugin.
 *
 * <p>This class implements sophisticated algorithms for generating realistic and
 * constraint-compliant data values across various SQL data types. It combines multiple data sources
 * including Faker library, custom dictionaries, and user-defined allowed values to produce
 * meaningful sample data. The generator handles complex scenarios such as UUID uniqueness, numeric
 * range constraints, string length limitations, and database-specific type requirements.
 */
public record ValueGenerator(
    Faker faker,
    List<String> dictionaryWords,
    boolean useLatinDictionary,
    Set<UUID> usedUuids,
    int numericScale) {

  private static final int UUID_GENERATION_LIMIT = 1_000_000;
  private static final int DEFAULT_INT_MAX = 10_000;
  private static final int DEFAULT_LONG_MAX = 1_000_000;
  private static final int DEFAULT_DECIMAL_MAX = 1_000;
  private static final int DEFAULT_STRING_LENGTH = 255;

  private static final double NULL_PROBABILITY = 0.3;

  private static final int ISO_COUNTRY_CODE_2_LEN = 2;
  private static final int ISO_COUNTRY_CODE_3_LEN = 3;
  private static final int IBAN_ES_LEN = 24;
  private static final String IBAN_ES_PREFIX = "ES";
  private static final int IBAN_ES_DIGITS = 22;

  private static final int MAX_DICTIONARY_WORDS = 5;
  private static final int MIN_FAKER_WORDS = 3;
  private static final int FAKER_WORDS_DIVISOR = 5;
  private static final int MIN_FAKER_WORDS_CLAMP = 4;
  private static final int MAX_FAKER_WORDS_CLAMP = 10;

  private static final int DATE_RANGE_DAYS = 3650;
  private static final int TIMESTAMP_RANGE_SECONDS = 31_536_000;

  public ValueGenerator(
      final Faker faker,
      final List<String> dictionaryWords,
      final boolean useLatinDictionary,
      final Set<UUID> usedUuids,
      final int numericScale) {
    this.faker = Objects.requireNonNull(faker, "Faker cannot be null");
    this.dictionaryWords = Objects.requireNonNullElse(dictionaryWords, Collections.emptyList());
    this.useLatinDictionary = useLatinDictionary;
    this.usedUuids = Objects.requireNonNull(usedUuids, "Used UUIDs set cannot be null");
    this.numericScale = numericScale;
  }

  public static boolean isNumericJdbc(final int jdbcType) {
    return switch (jdbcType) {
      case Types.INTEGER,
          Types.SMALLINT,
          Types.TINYINT,
          Types.BIGINT,
          Types.DECIMAL,
          Types.NUMERIC,
          Types.FLOAT,
          Types.DOUBLE,
          Types.REAL ->
          true;
      default -> false;
    };
  }

  public static boolean isNumericOutsideBounds(final Object value, final ParsedConstraint pc) {
    if (Objects.isNull(value) || Objects.isNull(pc)) return false;
    try {
      final double v;
      if (value instanceof Number n) v = n.doubleValue();
      else v = Double.parseDouble(value.toString());
      return (Objects.nonNull(pc.min()) && v < pc.min())
          || (Objects.nonNull(pc.max()) && v > pc.max());
    } catch (final NumberFormatException e) {
      return true;
    }
  }

  /**
   * Parses a string into the boxed value implied by {@code jdbcType}: the integer, long, boolean,
   * decimal and double types parse into their Java types, any other type keeps the string as-is.
   *
   * @throws NumberFormatException when a numeric type's value fails to parse
   */
  static Object parseTypedValue(final String value, final int jdbcType) {
    return switch (jdbcType) {
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(value);
      case Types.BIGINT -> Long.parseLong(value);
      case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(value);
      case Types.FLOAT, Types.DOUBLE, Types.REAL -> Double.parseDouble(value);
      case Types.BOOLEAN, Types.BIT -> parseBooleanLiteral(value);
      default -> value;
    };
  }

  /** Parses boolean literals where {@code 1} means true and {@code 0} means false. */
  private static boolean parseBooleanLiteral(final String value) {
    return "1".equals(value) || Boolean.parseBoolean(value);
  }

  /** Clamps a numeric value to the nearest bound defined by the constraint. */
  public Object clampToNearestBound(final Object value, final ParsedConstraint pc) {
    if (Objects.isNull(value) || Objects.isNull(pc)) return value;
    double v;
    if (value instanceof Number n) v = n.doubleValue();
    else {
      try {
        v = Double.parseDouble(value.toString());
      } catch (final NumberFormatException e) {
        return pc.min() != null ? pc.min() : pc.max();
      }
    }
    if (pc.min() != null && v < pc.min()) v = pc.min();
    if (pc.max() != null && v > pc.max()) v = pc.max();
    if (value instanceof Integer) return (int) v;
    if (value instanceof Long) return (long) v;
    if (value instanceof Double) return v;
    return v;
  }

  public Object generateValue(
      final Column column, final ParsedConstraint constraint, final int rowIndex) {
    return generateValue(
        column,
        constraint,
        new ArrayList<>(column.allowedValues()),
        Objects.nonNull(constraint) && Objects.nonNull(constraint.allowedValues())
            ? new ArrayList<>(constraint.allowedValues())
            : null,
        rowIndex);
  }

  /**
   * Same as {@link #generateValue(Column, ParsedConstraint, int)} but receives the allowed-values
   * lists pre-resolved once per column, so hot paths never copy the immutable sets per row. A
   * {@code null} list stands for a source without allowed values (e.g. a null constraint).
   */
  Object generateValue(
      final Column column,
      final ParsedConstraint constraint,
      final List<String> columnAllowedValues,
      final List<String> constraintAllowedValues,
      final int rowIndex) {
    // Solo permitir nulos si la columna es nullable
    if (column.nullable() && ThreadLocalRandom.current().nextDouble() < NULL_PROBABILITY) {
      return null;
    }

    // UUID
    if (column.uuid()) {
      Object uuidVal = generateUuidValue(column, constraint);
      if (uuidVal != null) return uuidVal;
      // fallback: generar UUID
      return generateUuid();
    }

    // Valores permitidos en columna
    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      return generateFromAllowedValues(columnAllowedValues, column, rowIndex, "a");
    }

    // Valores permitidos en constraint
    if (Objects.nonNull(constraint)
        && Objects.nonNull(constraint.allowedValues())
        && !constraint.allowedValues().isEmpty()) {
      return generateFromAllowedValues(constraintAllowedValues, column, rowIndex, "x");
    }

    // Restricciones numéricas
    final ParsedConstraint effectivePc = determineEffectiveNumericConstraint(column, constraint);
    if (Objects.nonNull(effectivePc.min()) || Objects.nonNull(effectivePc.max())) {
      final Object bounded = generateNumericWithinBounds(column, effectivePc);
      if (Objects.nonNull(bounded)) return bounded;
    }

    // Longitud máxima
    Integer maxLen = Objects.nonNull(constraint) ? constraint.maxLength() : null;
    if (Objects.isNull(maxLen) || maxLen <= 0)
      maxLen = column.length() > 0 ? column.length() : null;

    // Valor por defecto
    Object def = generateDefaultValue(column, rowIndex, maxLen);
    // Si la columna no es nullable, nunca devolver null
    if (def == null && !column.nullable()) {
      // fallback por tipo
      switch (column.jdbcType()) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
          return 1;
        }
        case Types.BIGINT -> {
          return 1L;
        }
        case Types.DECIMAL, Types.NUMERIC -> {
          return BigDecimal.ONE;
        }
        case Types.FLOAT, Types.DOUBLE, Types.REAL -> {
          return 1.0;
        }
        case Types.BOOLEAN, Types.BIT -> {
          return true;
        }
        case Types.VARCHAR,
            Types.CHAR,
            Types.LONGVARCHAR,
            Types.NVARCHAR,
            Types.NCHAR,
            Types.LONGNVARCHAR -> {
          return "default";
        }
        case Types.ARRAY -> {
          return new String[] {"default"};
        }
        default -> {
          return rowIndex;
        }
      }
    }
    return def;
  }

  /**
   * Shared fallthrough for both allowed-values sources: picks a random allowed value, then falls
   * back through the first non-null value, the type-specific fallback and finally the row index.
   * {@code stringFallback} backs the VARCHAR/CHAR and ARRAY fallbacks ("a" for column-level allowed
   * values, "x" for constraint-level ones).
   */
  private Object generateFromAllowedValues(
      final List<String> values,
      final Column column,
      final int rowIndex,
      final String stringFallback) {
    final Object val = pickRandom(values, column.jdbcType());
    if (val != null) return val;
    // fallback: primer valor no nulo
    final Object first = values.stream().filter(Objects::nonNull).findFirst().orElse(null);
    if (first != null) return first;
    // fallback: para VARCHAR, devolver el fallback de texto
    if (column.jdbcType() == Types.VARCHAR || column.jdbcType() == Types.CHAR)
      return stringFallback;
    // fallback: para tipos numéricos, devolver 1
    if (isNumericJdbc(column.jdbcType())) return 1;
    // fallback: para boolean, devolver true
    if (column.jdbcType() == Types.BOOLEAN || column.jdbcType() == Types.BIT) return true;
    // fallback: para array, devolver array con el fallback de texto
    if (column.jdbcType() == Types.ARRAY) return new String[] {stringFallback};
    // fallback genérico
    return rowIndex;
  }

  public Object generateSoftDeleteValue(
      final Column column, final boolean useSchemaDefault, final String value) {
    if (useSchemaDefault) return SqlKeyword.DEFAULT;
    if (value == null || "NULL".equalsIgnoreCase(value)) return null;
    final Object converted = convertStringValue(value, column);
    return converted != null ? converted : value;
  }

  private Object generateUuidValue(final Column column, final ParsedConstraint constraint) {
    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      final UUID uuid = tryParseUuidFromAllowedValues(column.allowedValues());
      if (Objects.nonNull(uuid)) return uuid;
    }

    if (Objects.nonNull(constraint)
        && Objects.nonNull(constraint.allowedValues())
        && !constraint.allowedValues().isEmpty()) {
      final UUID uuid = tryParseUuidFromAllowedValues(constraint.allowedValues());
      if (Objects.nonNull(uuid)) return uuid;
    }

    return generateUuid();
  }

  private UUID tryParseUuidFromAllowedValues(final Set<String> allowedValues) {
    return allowedValues.stream()
        .map(String::trim)
        .map(
            s -> {
              try {
                return UUID.fromString(s);
              } catch (final IllegalArgumentException e) {
                return null;
              }
            })
        .filter(Objects::nonNull)
        .filter(usedUuids::add)
        .findFirst()
        .orElse(null);
  }

  @SuppressWarnings("java:S2245")
  private UUID generateUuid() {
    for (int i = 0; i < UUID_GENERATION_LIMIT; i++) {
      final UUID candidate = UUID.randomUUID();
      if (usedUuids.add(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Unable to generate a unique UUID after " + UUID_GENERATION_LIMIT + " attempts");
  }

  private ParsedConstraint determineEffectiveNumericConstraint(
      final Column column, final ParsedConstraint pc) {
    final Double pcMin = Objects.nonNull(pc) ? pc.min() : null;
    final Double pcMax = Objects.nonNull(pc) ? pc.max() : null;
    final Double cmin = column.minValue();
    final Double cmax = column.maxValue();
    final Double effectiveMin = Objects.nonNull(pcMin) ? pcMin : cmin;
    final Double effectiveMax = Objects.nonNull(pcMax) ? pcMax : cmax;
    return new ParsedConstraint(
        effectiveMin,
        effectiveMax,
        Objects.nonNull(pc) ? pc.allowedValues() : Collections.emptySet(),
        Objects.nonNull(pc) ? pc.maxLength() : null);
  }

  private Object generateDefaultValue(final Column column, final int index, final Integer maxLen) {
    return switch (column.jdbcType()) {
      case Types.CHAR,
          Types.VARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGVARCHAR,
          Types.LONGNVARCHAR,
          Types.CLOB,
          Types.NCLOB ->
          generateString(column, maxLen, column.jdbcType());
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> boundedInt(column);
      case Types.BIGINT -> boundedLong(column);
      case Types.BOOLEAN, Types.BIT -> faker.bool().bool();
      case Types.DATE ->
          Date.valueOf(LocalDate.now().minusDays(faker.number().numberBetween(0, DATE_RANGE_DAYS)));
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
          Timestamp.from(
              Instant.now().minusSeconds(faker.number().numberBetween(0, TIMESTAMP_RANGE_SECONDS)));
      case Types.DECIMAL, Types.NUMERIC -> boundedBigDecimal(column);
      case Types.FLOAT, Types.DOUBLE, Types.REAL -> boundedDouble(column);
      case Types.ARRAY -> generateArray(column);
      default -> index;
    };
  }

  private Object generateArray(final Column column) {
    final int size = ThreadLocalRandom.current().nextInt(1, 4);
    final int colLength = column.length() > 0 ? column.length() : 20;
    return IntStream.range(0, size)
        .mapToObj(i -> generateString(column, colLength, Types.VARCHAR))
        .toArray(String[]::new);
  }

  @SuppressWarnings("java:S2245")
  private String generateString(final Column column, final Integer maxLen, final int jdbcType) {
    final String colName = column.name().toLowerCase(java.util.Locale.ROOT);
    final int len = Objects.nonNull(maxLen) && maxLen > 0 ? maxLen : DEFAULT_STRING_LENGTH;
    if (colName.contains("foto")
        || colName.contains("avatar")
        || colName.contains("image")
        || colName.contains("picture")
        || colName.contains("profile")) {
      return normalizeToLength(faker.internet().image(), len, jdbcType);
    }

    if (len == ISO_COUNTRY_CODE_2_LEN) return faker.country().countryCode2();
    if (len == ISO_COUNTRY_CODE_3_LEN) return faker.country().countryCode3();
    if (len == IBAN_ES_LEN)
      return normalizeToLength(
          IBAN_ES_PREFIX.concat(faker.number().digits(IBAN_ES_DIGITS)), len, jdbcType);

    boolean useDictionary = !dictionaryWords.isEmpty();
    if (useDictionary && useLatinDictionary) {
      useDictionary = ThreadLocalRandom.current().nextBoolean();
    }

    if (useDictionary) {
      if (dictionaryWords.size() == 1) {
        return normalizeToLength(dictionaryWords.getFirst(), len, jdbcType);
      }
      final int numWords =
          ThreadLocalRandom.current()
              .nextInt(1, Math.min(dictionaryWords.size(), MAX_DICTIONARY_WORDS));
      final String phrase =
          IntStream.range(0, numWords)
              .mapToObj(i -> (String) pickRandom(dictionaryWords, Types.VARCHAR))
              .collect(Collectors.joining(" "));
      return normalizeToLength(phrase, len, jdbcType);
    } else {
      final int numWords =
          ThreadLocalRandom.current()
              .nextInt(
                  MIN_FAKER_WORDS,
                  Math.clamp(
                      len / FAKER_WORDS_DIVISOR, MIN_FAKER_WORDS_CLAMP, MAX_FAKER_WORDS_CLAMP));
      final String phrase = String.join(" ", faker.lorem().words(numWords));
      return normalizeToLength(phrase, len, jdbcType);
    }
  }

  /**
   * Generates a string from a user-provided regex pattern.
   *
   * <p>Returns {@code null} if the pattern is invalid or no value could be produced within the
   * configured max length.
   */
  @SuppressWarnings("java:S2245")
  public String generateStringFromRegex(
      final String regexPattern, final Integer maxLen, final int jdbcType) {
    if (Objects.isNull(regexPattern) || regexPattern.isBlank()) {
      return null;
    }

    try {
      Pattern.compile(regexPattern);
    } catch (final PatternSyntaxException ex) {
      return null;
    }

    final int attempts = 50;
    for (int i = 0; i < attempts; i++) {
      try {
        final String candidate = faker.regexify(regexPattern);
        if (Objects.isNull(candidate)) {
          continue;
        }
        if (Objects.nonNull(maxLen) && maxLen > 0 && candidate.length() > maxLen) {
          continue;
        }
        return Objects.nonNull(maxLen) && maxLen > 0
            ? normalizeToLength(candidate, maxLen, jdbcType)
            : candidate;
      } catch (final RuntimeException ex) {
        return null;
      }
    }

    return null;
  }

  @SuppressWarnings("java:S2245")
  private Object pickRandom(final List<String> vals, final int jdbcType) {
    final String v = vals.get(ThreadLocalRandom.current().nextInt(vals.size()));
    if (Objects.isNull(v)) return null;
    final String trimmed = v.trim();
    if (trimmed.isEmpty()) return "";
    try {
      return parseTypedValue(trimmed, jdbcType);
    } catch (final NumberFormatException e) {
      return trimmed;
    }
  }

  private String normalizeToLength(final String value, final int length, final int jdbcType) {
    if (length <= 0) return value;
    if (value.length() > length) {
      return value.substring(0, length);
    }
    return jdbcType == Types.CHAR && value.length() < length
        ? value.concat(" ".repeat(length - value.length()))
        : value;
  }

  private Object convertStringValue(final String value, final Column column) {
    if (Objects.isNull(value) || "NULL".equalsIgnoreCase(value)) return null;
    try {
      return switch (column.jdbcType()) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(value);
        case Types.BIGINT -> Long.parseLong(value);
        case Types.BOOLEAN, Types.BIT -> parseBooleanLiteral(value);
        case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(value);
        case Types.FLOAT, Types.DOUBLE, Types.REAL -> Double.parseDouble(value);
        default -> value;
      };
    } catch (final Exception e) {
      return null;
    }
  }

  @SuppressWarnings("java:S2245")
  private Integer boundedInt(final Column column) {
    int min = getIntMin(column);
    int max = getIntMax(column);
    if (min > max) {
      final int t = min;
      min = max;
      max = t;
    }
    final long v = ThreadLocalRandom.current().nextLong(min, (long) max + 1);
    return Math.toIntExact(Math.clamp(v, Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  private Long boundedLong(final Column column) {
    return boundedLongWithin(getLongMin(column), getLongMax(column));
  }

  /** Random long within {@code [min, max]}, swapping inverted bounds and without a max overflow. */
  @SuppressWarnings("java:S2245")
  private Long boundedLongWithin(final long min, final long max) {
    long low = min;
    long high = max;
    if (low > high) {
      final long t = low;
      low = high;
      high = t;
    }
    if (high == Long.MAX_VALUE) {
      if (low == Long.MIN_VALUE) {
        return ThreadLocalRandom.current().nextLong();
      }
      return ThreadLocalRandom.current().nextLong(low, Long.MAX_VALUE);
    }
    return ThreadLocalRandom.current().nextLong(low, Math.addExact(high, 1L));
  }

  private BigDecimal boundedBigDecimal(final Column column) {
    return boundedBigDecimal(column, getNumericBounds(column));
  }

  /** Random decimal within {@code bounds} at the column's effective scale. */
  @SuppressWarnings("java:S2245")
  private BigDecimal boundedBigDecimal(final Column column, final double[] bounds) {
    final double val = generateRandomDouble(bounds[0], bounds[1]);
    final int scale = getEffectiveScale(column);
    return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
  }

  private Double boundedDouble(final Column column) {
    return boundedBigDecimal(column).doubleValue();
  }

  @SuppressWarnings("java:S2245")
  public Object generateNumericWithinBounds(final Column column, final ParsedConstraint pc) {
    return switch (column.jdbcType()) {
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
        int min = getIntMinWithConstraint(column, pc);
        int max = getIntMaxWithConstraint(column, pc);
        if (min > max) {
          final int t = min;
          min = max;
          max = t;
        }
        if (max == Integer.MAX_VALUE) {
          yield ThreadLocalRandom.current().nextInt(min, Integer.MAX_VALUE);
        }
        yield ThreadLocalRandom.current().nextInt(min, max + 1);
      }
      case Types.BIGINT -> {
        yield boundedLongWithin(
            getLongMinWithConstraint(column, pc), getLongMaxWithConstraint(column, pc));
      }
      case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.DOUBLE, Types.REAL -> {
        yield boundedBigDecimal(column, getNumericBoundsWithConstraint(column, pc));
      }
      default -> null;
    };
  }

  private int getIntMin(final Column column) {
    return Objects.nonNull(column.minValue()) ? column.minValue().intValue() : 1;
  }

  private int getIntMax(final Column column) {
    return Objects.nonNull(column.maxValue()) ? column.maxValue().intValue() : DEFAULT_INT_MAX;
  }

  private int getIntMinWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMin = Objects.nonNull(pc) && Objects.nonNull(pc.min());
    // Ceil: the smallest integer honouring CHECK (x >= min) even when min is fractional.
    return hasMin ? (int) Math.ceil(pc.min()) : getIntMin(column);
  }

  private int getIntMaxWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMax = Objects.nonNull(pc) && Objects.nonNull(pc.max());
    // Floor: the largest integer honouring CHECK (x <= max) even when max is fractional.
    return hasMax ? (int) Math.floor(pc.max()) : getIntMax(column);
  }

  private long getLongMin(final Column column) {
    return Objects.nonNull(column.minValue()) ? column.minValue().longValue() : 1L;
  }

  private long getLongMax(final Column column) {
    return Objects.nonNull(column.maxValue()) ? column.maxValue().longValue() : DEFAULT_LONG_MAX;
  }

  private long getLongMinWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMin = Objects.nonNull(pc) && Objects.nonNull(pc.min());
    return hasMin ? pc.min().longValue() : getLongMin(column);
  }

  private long getLongMaxWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMax = Objects.nonNull(pc) && Objects.nonNull(pc.max());
    return hasMax ? pc.max().longValue() : getLongMax(column);
  }

  private double getDoubleMin(final Column column) {
    final double colMinValue =
        Objects.nonNull(column.minValue()) ? column.minValue().doubleValue() : 1.0;
    if (Objects.isNull(column.minValue())
        && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)) {
      final double max = getDoubleMax(column);
      if (colMinValue > max) {
        return 0.0;
      }
    }
    return colMinValue;
  }

  private double getDoubleMax(final Column column) {
    final double colMaxValue =
        Objects.nonNull(column.maxValue()) ? column.maxValue().doubleValue() : DEFAULT_DECIMAL_MAX;
    if (Objects.isNull(column.maxValue())
        && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)
        && column.length() > 0) {
      final int precision = column.length();
      final int scale = getEffectiveScale(column);
      return Math.pow(10.0, (double) precision - scale) - Math.pow(10.0, -scale);
    }
    return colMaxValue;
  }

  private double[] getNumericBounds(final Column column) {
    final double min = getDoubleMin(column);
    final double max = getDoubleMax(column);
    return min > max ? new double[] {max, min} : new double[] {min, max};
  }

  private double[] getNumericBoundsWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMin = Objects.nonNull(pc) && Objects.nonNull(pc.min());
    final boolean hasMax = Objects.nonNull(pc) && Objects.nonNull(pc.max());
    final double min = hasMin ? pc.min() : getDoubleMin(column);
    final double max = hasMax ? pc.max() : getDoubleMax(column);
    return min > max ? new double[] {max, min} : new double[] {min, max};
  }

  @SuppressWarnings("java:S2245")
  private double generateRandomDouble(final double min, final double max) {
    return min + (max - min) * ThreadLocalRandom.current().nextDouble();
  }

  private int getEffectiveScale(final Column column) {
    return column.scale() > 0 ? column.scale() : numericScale;
  }
}
