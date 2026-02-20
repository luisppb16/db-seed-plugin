/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import com.luisppb16.dbseed.db.generator.ConstraintParser.ParsedConstraint;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
public final class ValueGenerator {

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

  private final Faker faker;
  private final List<String> dictionaryWords;
  private final boolean useLatinDictionary;
  private final Set<UUID> usedUuids;
  private final int numericScale;

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
      return (Objects.nonNull(pc.min()) && v < pc.min()) || (Objects.nonNull(pc.max()) && v > pc.max());
    } catch (final NumberFormatException e) {
      return true;
    }
  }

  public Object generateValue(
      final Column column, final ParsedConstraint constraint, final int rowIndex) {
    if (column.nullable() && ThreadLocalRandom.current().nextDouble() < NULL_PROBABILITY) {
      return null;
    }

    if (column.uuid()) {
      return generateUuidValue(column, constraint);
    }

    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(column.allowedValues()), column.jdbcType());
    }

    if (Objects.nonNull(constraint)
        && Objects.nonNull(constraint.allowedValues())
        && !constraint.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(constraint.allowedValues()), column.jdbcType());
    }

    final ParsedConstraint effectivePc = determineEffectiveNumericConstraint(column, constraint);
    if (Objects.nonNull(effectivePc.min()) || Objects.nonNull(effectivePc.max())) {
      final Object bounded = generateNumericWithinBounds(column, effectivePc);
      if (Objects.nonNull(bounded)) return bounded;
    }

    Integer maxLen = Objects.nonNull(constraint) ? constraint.maxLength() : null;
    if (Objects.isNull(maxLen) || maxLen <= 0) maxLen = column.length() > 0 ? column.length() : null;

    return generateDefaultValue(column, rowIndex, maxLen);
  }

  public Object generateSoftDeleteValue(
      final Column column, final boolean useSchemaDefault, final String value) {
    if (useSchemaDefault) {
      return SqlKeyword.DEFAULT;
    }
    return convertStringValue(value, column);
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
    for (final String s : allowedValues) {
      try {
        final UUID u = UUID.fromString(s.trim());
        if (usedUuids.add(u)) return u;
      } catch (final IllegalArgumentException ignored) {
      }
    }
    return null;
  }

  @SuppressWarnings("java:S2245")
  private UUID generateUuid() {
    for (int i = 0; i < UUID_GENERATION_LIMIT; i++) {
      final UUID u = UUID.randomUUID();
      if (usedUuids.add(u)) return u;
    }
    throw new IllegalStateException(
        "Unable to generate a unique UUID after " + UUID_GENERATION_LIMIT + " attempts");
  }

  private ParsedConstraint determineEffectiveNumericConstraint(
      final Column column, final ParsedConstraint pc) {
    final Double pcMin = Objects.nonNull(pc) ? pc.min() : null;
    final Double pcMax = Objects.nonNull(pc) ? pc.max() : null;
    final Double cmin = column.minValue() != 0 ? (double) column.minValue() : null;
    final Double cmax = column.maxValue() != 0 ? (double) column.maxValue() : null;
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
          Types.LONGNVARCHAR ->
          generateString(maxLen, column.jdbcType());
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> boundedInt(column);
      case Types.BIGINT -> boundedLong(column);
      case Types.BOOLEAN, Types.BIT -> faker.bool().bool();
      case Types.DATE ->
          Date.valueOf(LocalDate.now().minusDays(faker.number().numberBetween(0, DATE_RANGE_DAYS)));
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
          Timestamp.from(Instant.now().minusSeconds(faker.number().numberBetween(0, TIMESTAMP_RANGE_SECONDS)));
      case Types.DECIMAL, Types.NUMERIC -> boundedBigDecimal(column);
      case Types.FLOAT, Types.DOUBLE, Types.REAL -> boundedDouble(column);
      case Types.ARRAY -> generateArray(column);
      default -> index;
    };
  }

  private Object generateArray(final Column column) {
    final int size = ThreadLocalRandom.current().nextInt(1, 4);
    final String[] array = new String[size];
    for (int i = 0; i < size; i++) {
      array[i] = generateString(column.length() > 0 ? column.length() : 20, Types.VARCHAR);
    }
    return array;
  }

  @SuppressWarnings("java:S2245")
  private String generateString(final Integer maxLen, final int jdbcType) {
    final int len = (Objects.nonNull(maxLen) && maxLen > 0) ? maxLen : DEFAULT_STRING_LENGTH;
    if (len == ISO_COUNTRY_CODE_2_LEN) return faker.country().countryCode2();
    if (len == ISO_COUNTRY_CODE_3_LEN) return faker.country().countryCode3();
    if (len == IBAN_ES_LEN) return normalizeToLength(IBAN_ES_PREFIX + faker.number().digits(IBAN_ES_DIGITS), len, jdbcType);

    boolean useDictionary = !dictionaryWords.isEmpty();
    if (useDictionary && useLatinDictionary) {
      useDictionary = ThreadLocalRandom.current().nextBoolean();
    }

    if (useDictionary) {
      final int numWords =
          ThreadLocalRandom.current().nextInt(1, Math.min(dictionaryWords.size(), MAX_DICTIONARY_WORDS));
      final StringBuilder phraseBuilder = new StringBuilder();
      for (int i = 0; i < numWords; i++) {
        phraseBuilder.append(pickRandom(dictionaryWords, Types.VARCHAR)).append(" ");
      }
      return normalizeToLength(phraseBuilder.toString().trim(), len, jdbcType);
    } else {
      final int numWords = ThreadLocalRandom.current().nextInt(MIN_FAKER_WORDS, Math.clamp(len / FAKER_WORDS_DIVISOR, MIN_FAKER_WORDS_CLAMP, MAX_FAKER_WORDS_CLAMP));
      final String phrase = String.join(" ", faker.lorem().words(numWords));
      return normalizeToLength(phrase, len, jdbcType);
    }
  }

  @SuppressWarnings("java:S2245")
  private Object pickRandom(final List<String> vals, final int jdbcType) {
    String v = vals.get(ThreadLocalRandom.current().nextInt(vals.size()));
    if (Objects.isNull(v)) return null;
    v = v.trim();
    if (v.isEmpty()) return "";
    try {
      return switch (jdbcType) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(v);
        case Types.BIGINT -> Long.parseLong(v);
        case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(v);
        case Types.FLOAT, Types.DOUBLE, Types.REAL -> Double.parseDouble(v);
        case Types.BOOLEAN, Types.BIT -> Boolean.parseBoolean(v);
        default -> v;
      };
    } catch (final NumberFormatException e) {
      return v;
    }
  }

  private String normalizeToLength(final String value, final int length, final int jdbcType) {
    if (length <= 0) return value;
    if (value.length() > length) {
      return value.substring(0, length);
    }
    if (jdbcType == Types.CHAR && value.length() < length) {
      return value + " ".repeat(length - value.length());
    }
    return value;
  }

  private Object convertStringValue(final String value, final Column column) {
    if (Objects.isNull(value) || "NULL".equalsIgnoreCase(value)) return null;
    try {
      return switch (column.jdbcType()) {
        case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(value);
        case Types.BIGINT -> Long.parseLong(value);
        case Types.BOOLEAN, Types.BIT -> Boolean.parseBoolean(value);
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

  @SuppressWarnings("java:S2245")
  private Long boundedLong(final Column column) {
    long min = getLongMin(column);
    long max = getLongMax(column);
    if (min > max) {
      final long t = min;
      min = max;
      max = t;
    }
    return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
  }

  @SuppressWarnings("java:S2245")
  private BigDecimal boundedBigDecimal(final Column column) {
    final double[] bounds = getNumericBounds(column);
    final double val = generateRandomDouble(bounds[0], bounds[1]);
    final int scale = getEffectiveScale(column);
    return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
  }

  @SuppressWarnings("java:S2245")
  private Double boundedDouble(final Column column) {
    final double[] bounds = getNumericBounds(column);
    final double val = generateRandomDouble(bounds[0], bounds[1]);
    final int scale = getEffectiveScale(column);
    return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  @SuppressWarnings("java:S2245")
  public Object generateNumericWithinBounds(final Column column, final ParsedConstraint pc) {
    switch (column.jdbcType()) {
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
        int min = getIntMinWithConstraint(column, pc);
        int max = getIntMaxWithConstraint(column, pc);
        if (min > max) {
          final int t = min;
          min = max;
          max = t;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
      }
      case Types.BIGINT -> {
        long min = getLongMinWithConstraint(column, pc);
        long max = getLongMaxWithConstraint(column, pc);
        if (min > max) {
          final long t = min;
          min = max;
          max = t;
        }
        return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
      }
      case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.DOUBLE, Types.REAL -> {
        final double[] bounds = getNumericBoundsWithConstraint(column, pc);
        final double val = generateRandomDouble(bounds[0], bounds[1]);
        final int scale = getEffectiveScale(column);
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
      }
      default -> {
        return null;
      }
    }
  }

  private int getIntMin(final Column column) {
    return column.minValue() != 0 ? column.minValue() : 1;
  }

  private int getIntMax(final Column column) {
    return column.maxValue() != 0 ? column.maxValue() : DEFAULT_INT_MAX;
  }

  private int getIntMinWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMin = Objects.nonNull(pc) && Objects.nonNull(pc.min());
    return hasMin ? pc.min().intValue() : getIntMin(column);
  }

  private int getIntMaxWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMax = Objects.nonNull(pc) && Objects.nonNull(pc.max());
    return hasMax ? pc.max().intValue() : getIntMax(column);
  }

  private long getLongMin(final Column column) {
    return column.minValue() != 0 ? column.minValue() : 1L;
  }

  private long getLongMax(final Column column) {
    return column.maxValue() != 0 ? column.maxValue() : DEFAULT_LONG_MAX;
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
    final double colMinValue = column.minValue() != 0 ? column.minValue() : 1.0;
    if (column.minValue() == 0
        && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)) {
      final double max = getDoubleMax(column);
      if (colMinValue > max) {
        return 0.0;
      }
    }
    return colMinValue;
  }

  private double getDoubleMax(final Column column) {
    final double colMaxValue = column.maxValue() != 0 ? column.maxValue() : DEFAULT_DECIMAL_MAX;
    if (column.maxValue() == 0
        && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)
        && column.length() > 0) {
      final int precision = column.length();
      final int scale = getEffectiveScale(column);
      return Math.pow(10.0, (double) precision - scale) - Math.pow(10.0, -scale);
    }
    return colMaxValue;
  }

  private double[] getNumericBounds(final Column column) {
    double min = getDoubleMin(column);
    double max = getDoubleMax(column);
    if (min > max) {
      return new double[] {max, min};
    }
    return new double[] {min, max};
  }

  private double[] getNumericBoundsWithConstraint(final Column column, final ParsedConstraint pc) {
    final boolean hasMin = Objects.nonNull(pc) && Objects.nonNull(pc.min());
    final boolean hasMax = Objects.nonNull(pc) && Objects.nonNull(pc.max());
    double min = hasMin ? pc.min() : getDoubleMin(column);
    double max = hasMax ? pc.max() : getDoubleMax(column);
    if (min > max) {
      return new double[] {max, min};
    }
    return new double[] {min, max};
  }

  @SuppressWarnings("java:S2245")
  private double generateRandomDouble(final double min, final double max) {
    return min + (max - min) * ThreadLocalRandom.current().nextDouble();
  }

  private int getEffectiveScale(final Column column) {
    return column.scale() > 0 ? column.scale() : numericScale;
  }
}
