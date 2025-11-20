/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

@Slf4j
@UtilityClass
public class DataGenerator {

  private static final Pattern SINGLE_WORD_PATTERN =
      Pattern.compile("^(?<word>[\\p{L}][\\p{L}\\p{N}]*)$");
  private static final int MAX_GENERATE_ATTEMPTS = 10;
  private static final int DEFAULT_INT_MAX = 10_000;
  private static final int DEFAULT_LONG_MAX = 1_000_000;
  private static final int DEFAULT_DECIMAL_MAX = 1_000;
  private static final int UUID_GENERATION_LIMIT = 1_000_000;

  public static GenerationResult generate(
      List<Table> tables,
      int rowsPerTable,
      boolean deferred,
      Map<String, Set<String>> pkUuidOverrides,
      Map<String, Set<String>> excludedColumns) {

    Map<String, Table> overridden = new LinkedHashMap<>();
    tables.forEach(
        t -> {
          Set<String> set = pkUuidOverrides != null ? pkUuidOverrides.get(t.name()) : null;
          if (set == null || set.isEmpty()) {
            overridden.put(t.name(), t);
            return;
          }
          List<Column> newCols = new ArrayList<>();
          t.columns()
              .forEach(
                  c -> {
                    boolean forceUuid = set.contains(c.name());
                    if (forceUuid && !c.uuid()) {
                      newCols.add(c.toBuilder().uuid(true).build());
                    } else {
                      newCols.add(c);
                    }
                  });
          overridden.put(
              t.name(), new Table(t.name(), newCols, t.primaryKey(), t.foreignKeys(), t.checks(), t.uniqueKeys()));
        });

    List<Table> list = new ArrayList<>(overridden.values());
    return generateInternal(list, rowsPerTable, deferred, excludedColumns);
  }

  private static GenerationResult generateInternal(
      List<Table> tables,
      int rowsPerTable,
      boolean deferred,
      Map<String, Set<String>> excludedColumns) {

    Instant start = Instant.now();

    List<Table> orderedTables = orderByWordAndFk(tables);
    Map<String, Table> tableMap =
        orderedTables.stream().collect(Collectors.toUnmodifiableMap(Table::name, t -> t));

    Faker faker = new Faker();
    Map<String, List<Row>> data = new LinkedHashMap<>();
    Set<UUID> usedUuids = new HashSet<>();

    // Generate rows
    Map<String, Map<String, ParsedConstraint>> tableConstraints = new HashMap<>();
    orderedTables.forEach(
        table -> {
          // parse checks for this table into per-column constraints
          Map<String, ParsedConstraint> constraints = new HashMap<>();
          table
              .columns()
              .forEach(
                  col ->
                      constraints.put(
                          col.name(),
                          parseConstraintsForColumn(table.checks(), col.name(), col.length())));
          tableConstraints.put(table.name(), constraints);
          List<Row> rows = new ArrayList<>();
          Predicate<Column> isFkColumn = column -> table.fkColumnNames().contains(column.name());
          Set<String> seenPrimaryKeys = new HashSet<>();
          Set<String> excluded = excludedColumns.getOrDefault(table.name(), Set.of());

          IntStream.range(0, rowsPerTable)
              .forEach(
                  i -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    table
                        .columns()
                        .forEach(
                            column -> {
                              if (isFkColumn.test(column) || excluded.contains(column.name())) {
                                values.put(column.name(), null);
                              } else {
                                ParsedConstraint pc = constraints.get(column.name());
                                Object gen = generateValue(faker, column, i, usedUuids, pc);
                                // if numeric and there are parsed bounds, ensure generated value
                                // respects them
                                if (isNumericJdbc(column.jdbcType())
                                    && pc != null
                                    && (pc.min() != null || pc.max() != null)) {
                                  int attempts = 0;
                                  while (!numericWithin(gen, pc) && attempts < MAX_GENERATE_ATTEMPTS) {
                                    gen = generateNumericWithinBounds(column, pc);
                                    attempts++;
                                  }
                                }
                                values.put(column.name(), gen);
                              }
                            });

                    String pkKey =
                        table.primaryKey().stream()
                            .map(pkCol -> Objects.toString(values.get(pkCol), "NULL"))
                            .collect(Collectors.joining("|"));

                    if (pkKey.isEmpty() || seenPrimaryKeys.add(pkKey)) {
                      rows.add(new Row(values));
                    }
                  });

          data.put(table.name(), rows);
          log.debug("Generated {} rows for table {}.", rows.size(), table.name());
        });

    // Validation pass: ensure numeric values satisfy parsed CHECK bounds; if not, replace them.
    for (Table table : orderedTables) {
      Map<String, ParsedConstraint> constraints =
          tableConstraints.getOrDefault(table.name(), Map.of());
      List<Row> rows = data.get(table.name());
      if (rows == null) continue;
      for (Row row : rows) {
        for (Column col : table.columns()) {
          ParsedConstraint pc = constraints.get(col.name());
          Object val = row.values().get(col.name());
          if (isNumericJdbc(col.jdbcType())
              && pc != null
              && (pc.min() != null || pc.max() != null)) {
            int attempts = 0;
            while (!numericWithin(val, pc) && attempts < MAX_GENERATE_ATTEMPTS) {
              val = generateNumericWithinBounds(col, pc);
              attempts++;
            }
            row.values().put(col.name(), val);
          }
        }
      }
    }

    // Ensure UUID uniqueness across generated rows (fast check/reparations)
    ensureUuidUniqueness(data, orderedTables, usedUuids);

    Map<String, Deque<Row>> uniqueFkParentQueues = new HashMap<>();
    List<PendingUpdate> updates = new ArrayList<>();
    Set<String> inserted = new HashSet<>();

    // Resolve foreign keys
    orderedTables.forEach(
        table -> {
          List<Row> rows = Objects.requireNonNull(data.get(table.name()));

          Predicate<ForeignKey> fkIsNullable =
              fk ->
                  fk.columnMapping().keySet().stream()
                      .map(col -> table.column(col).nullable())
                      .reduce(true, Boolean::logicalAnd);

          rows.forEach(
              row ->
                  table
                      .foreignKeys()
                      .forEach(
                          fk -> {
                            Table parent = tableMap.get(fk.pkTable());
                            if (parent == null) {
                              log.warn(
                                  "Skipping FK {}.{} -> {}: table not found",
                                  table.name(),
                                  fk.name(),
                                  fk.pkTable());
                              fk.columnMapping()
                                  .keySet()
                                  .forEach(col -> row.values().put(col, null));
                              return;
                            }

                            List<Row> parentRows = data.get(parent.name());
                            boolean parentInserted = inserted.contains(parent.name());
                            boolean fkNullable = fkIsNullable.test(fk);

                            Row parentRow;
                            if (fk.uniqueOnFk()) {
                              String key = table.name().concat("|").concat(fk.name());
                              Deque<Row> queue =
                                  uniqueFkParentQueues.computeIfAbsent(
                                      key,
                                      k -> {
                                        List<Row> shuffled = new ArrayList<>(parentRows);
                                        Collections.shuffle(shuffled, ThreadLocalRandom.current());
                                        return new ArrayDeque<>(shuffled);
                                      });
                              if (queue.isEmpty()) {
                                if (!fkNullable) {
                                  throw new IllegalStateException(
                                      "Not enough rows in "
                                          .concat(parent.name())
                                          .concat(" for non-nullable 1:1 FK from ")
                                          .concat(table.name()));
                                }
                                fk.columnMapping()
                                    .keySet()
                                    .forEach(col -> row.values().put(col, null));
                                return;
                              }
                              parentRow = queue.pollFirst();
                            } else {
                              parentRow =
                                  parentRows.get(
                                      ThreadLocalRandom.current().nextInt(parentRows.size()));
                            }

                            if (parentInserted || deferred) {
                              fk.columnMapping()
                                  .forEach(
                                      (fkCol, pkCol) ->
                                          row.values().put(fkCol, parentRow.values().get(pkCol)));
                            } else {
                              if (!fkNullable) {
                                throw new IllegalStateException(
                                    "Cycle with non-nullable FK: "
                                        .concat(table.name())
                                        .concat(" -> ")
                                        .concat(parent.name()));
                              }
                              Map<String, Object> fkVals = new LinkedHashMap<>();
                              fk.columnMapping()
                                  .forEach(
                                      (fkCol, pkCol) -> {
                                        fkVals.put(fkCol, parentRow.values().get(pkCol));
                                        row.values().put(fkCol, null);
                                      });
                              Map<String, Object> pkVals = new LinkedHashMap<>();
                              table
                                  .primaryKey()
                                  .forEach(pkCol -> pkVals.put(pkCol, row.values().get(pkCol)));
                              updates.add(new PendingUpdate(table.name(), fkVals, pkVals));
                            }
                          }));

          inserted.add(table.name());
        });

    Instant end = Instant.now();
    Duration duration = Duration.between(start, end);
    Double seconds = duration.toMillis() / 1000.0;

    log.info(
        "Generation completed in {} seconds. Tables: {}, deferred updates: {}",
        String.format(Locale.ROOT, "%.3f", seconds),
        orderedTables.size(),
        updates.size());

    return new GenerationResult(data, updates);
  }

  private static Object generateValue(
      Faker faker, Column column, int index, Set<UUID> usedUuids, ParsedConstraint pc) {
    // 1) If the column is a UUID column, treat it specially to guarantee uniqueness.
    if (column.uuid()) {
      // If allowed values exist on the column use them (parse as UUID)
      if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
        List<String> vals = new ArrayList<>(column.allowedValues());
        for (int i = 0; i < vals.size(); i++) {
          String s = vals.get(ThreadLocalRandom.current().nextInt(vals.size())).trim();
          try {
            UUID u = UUID.fromString(s);
            if (usedUuids.add(u)) return u;
          } catch (IllegalArgumentException ignored) {
            // ignore invalid UUID string
          }
        }
        // fallback to random UUID if none of the allowed values are usable
        return generateUuid(usedUuids);
      }

      // If CHECK provided allowed values, try to parse them as UUIDs
      if (pc != null && pc.allowedValues() != null && !pc.allowedValues().isEmpty()) {
        List<String> vals = new ArrayList<>(pc.allowedValues());
        for (int i = 0; i < vals.size(); i++) {
          String s = vals.get(ThreadLocalRandom.current().nextInt(vals.size())).trim();
          try {
            UUID u = UUID.fromString(s);
            if (usedUuids.add(u)) return u;
          } catch (IllegalArgumentException ignored) {
          }
        }
        return generateUuid(usedUuids);
      }

      // Otherwise generate a new unique UUID
      return generateUuid(usedUuids);
    }

    // 2) explicit allowed values on Column take precedence (non-UUID columns)
    if (column.hasAllowedValues() && !column.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(column.allowedValues()), column.jdbcType());
    }

    // 3) allowed values from CHECK
    if (pc != null && pc.allowedValues() != null && !pc.allowedValues().isEmpty()) {
      return pickRandom(new ArrayList<>(pc.allowedValues()), column.jdbcType());
    }

    // 4) numeric bounds: combine parsed constraint with Column min/max as fallback
    Double pcMin = pc != null ? pc.min() : null;
    Double pcMax = pc != null ? pc.max() : null;
    Double cmin = column.minValue() != 0 ? Double.valueOf(column.minValue()) : null;
    Double cmax = column.maxValue() != 0 ? Double.valueOf(column.maxValue()) : null;
    Double effectiveMin = (pcMin != null) ? pcMin : cmin;
    Double effectiveMax = (pcMax != null) ? pcMax : cmax;
    ParsedConstraint effectivePc =
        new ParsedConstraint(
            effectiveMin,
            effectiveMax,
            pc != null ? pc.allowedValues() : Collections.emptySet(),
            pc != null ? pc.maxLength() : null);
    if (effectivePc.min() != null || effectivePc.max() != null) {
      Object bounded = generateNumericWithinBounds(column, effectivePc);
      if (bounded != null) return bounded;
    }

    // 5) string length constraints
    Integer maxLen = pc != null ? pc.maxLength() : null;
    if (maxLen == null || maxLen <= 0) maxLen = column.length() > 0 ? column.length() : null;

    // 6) type-specific default generation
    switch (column.jdbcType()) {
      case Types.CHAR,
      Types.VARCHAR,
      Types.NCHAR,
      Types.NVARCHAR,
      Types.LONGVARCHAR,
      Types.LONGNVARCHAR:
        return generateString(faker, maxLen, column.jdbcType());
      case Types.INTEGER:
      case Types.SMALLINT:
      case Types.TINYINT:
        return boundedInt(column);
      case Types.BIGINT:
        return boundedLong(column);
      case Types.BOOLEAN:
      case Types.BIT:
        return faker.bool().bool();
      case Types.DATE:
        return Date.valueOf(LocalDate.now().minusDays(faker.number().numberBetween(0, 3650)));
      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return Timestamp.from(
            Instant.now().minusSeconds(faker.number().numberBetween(0, 31_536_000)));
      case Types.DECIMAL:
      case Types.NUMERIC:
      case Types.FLOAT:
      case Types.DOUBLE:
      case Types.REAL:
        return boundedDecimal(column);
      default:
        return index;
    }
  }

  private static Object pickRandom(List<String> vals, int jdbcType) {
    String v = vals.get(ThreadLocalRandom.current().nextInt(vals.size()));
    if (v == null) return null;
    v = v.trim();
    if (v.isEmpty()) return "";
    try {
      switch (jdbcType) {
        case Types.INTEGER:
        case Types.SMALLINT:
        case Types.TINYINT:
          return Integer.parseInt(v);
        case Types.BIGINT:
          return Long.parseLong(v);
        case Types.DECIMAL:
        case Types.NUMERIC:
        case Types.FLOAT:
        case Types.DOUBLE:
        case Types.REAL:
          return Double.parseDouble(v);
        case Types.BOOLEAN:
        case Types.BIT:
          return Boolean.parseBoolean(v);
        default:
          return v;
      }
    } catch (NumberFormatException e) {
      return v;
    }
  }

  private static UUID generateUuid(Set<UUID> usedUuids) {
    // Loop until a unique UUID is found (with a generous safety limit). This prevents accidental duplicates.
    for (int i = 0; i < UUID_GENERATION_LIMIT; i++) {
      UUID u = UUID.randomUUID();
      if (usedUuids.add(u)) return u;
    }
    throw new IllegalStateException("Unable to generate a unique UUID after ".concat(String.valueOf(UUID_GENERATION_LIMIT)).concat(" attempts"));
  }

  private static Object generateNumericWithinBounds(Column column, ParsedConstraint pc) {
    boolean hasMin = pc.min() != null;
    boolean hasMax = pc.max() != null;
    switch (column.jdbcType()) {
      case Types.INTEGER:
      case Types.SMALLINT:
      case Types.TINYINT:
        {
          int min = hasMin ? pc.min().intValue() : (column.minValue() != 0 ? column.minValue() : 1);
          int max =
              hasMax ? pc.max().intValue() : (column.maxValue() != 0 ? column.maxValue() : 10_000);
          if (min > max) {
            int t = min;
            min = max;
            max = t;
          }
          return ThreadLocalRandom.current().nextInt(min, max + 1);
        }
      case Types.BIGINT:
        {
          long min =
              hasMin ? pc.min().longValue() : (column.minValue() != 0 ? column.minValue() : 1);
          long max =
              hasMax
                  ? pc.max().longValue()
                  : (column.maxValue() != 0 ? column.maxValue() : 1_000_000);
          if (min > max) {
            long t = min;
            min = max;
            max = t;
          }
          return ThreadLocalRandom.current().nextLong(min, Math.addExact(max, 1L));
        }
      case Types.DECIMAL:
      case Types.NUMERIC:
      case Types.FLOAT:
      case Types.DOUBLE:
      case Types.REAL:
        {
          double min = hasMin ? pc.min() : (column.minValue() != 0 ? column.minValue() : 1);
          double max = hasMax ? pc.max() : (column.maxValue() != 0 ? column.maxValue() : 1_000);
          if (min > max) {
            double t = min;
            min = max;
            max = t;
          }
          return min + (max - min) * ThreadLocalRandom.current().nextDouble();
        }
      default:
        return null;
    }
  }

  private static String generateString(Faker faker, Integer maxLen, int jdbcType) {
    int len = (maxLen != null && maxLen > 0) ? maxLen : 255;
    if (len == 2) return faker.country().countryCode2();
    if (len == 3) return faker.country().countryCode3();
    if (len == 24) return normalizeToLength("ES".concat(faker.number().digits(22)), len, jdbcType);
    int numWords = ThreadLocalRandom.current().nextInt(1, Math.max(2, Math.min(10, len / 5)));
    String phrase = String.join(" ", faker.lorem().words(numWords));
    return normalizeToLength(phrase, len, jdbcType);
  }

  private static boolean isNumericJdbc(int jdbcType) {
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

  private static boolean numericWithin(Object value, ParsedConstraint pc) {
    if (value == null || pc == null) return true;
    try {
      double v;
      if (value instanceof Number n) v = n.doubleValue();
      else v = Double.parseDouble(value.toString());
      if (pc.min() != null && v < pc.min()) return false;
      if (pc.max() != null && v > pc.max()) return false;
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  // Parse checks for a given column: supports BETWEEN, numeric ranges (>=, <=, >, <), IN lists,
  // equality checks and simple length checks (char_length/length).
  private static ParsedConstraint parseConstraintsForColumn(
      List<String> checks, String columnName, int columnLength) {
    if (checks == null || checks.isEmpty())
      return new ParsedConstraint(null, null, Collections.emptySet(), null);

    Double lower = null; // inclusive lower bound
    Double upper = null; // inclusive upper bound
    Set<String> allowed = new HashSet<>();
    Integer maxLen = null;

    // allow optional qualifiers and optional quotes around column name
    String colPattern = "(?i)(?:[A-Za-z0-9_]+\\.)*\"?".concat(Pattern.quote(columnName)).concat("\"?");

    Pattern betweenPattern =
        Pattern.compile(
            colPattern.concat("\\s+BETWEEN\\s+([-+]?[0-9]+(?:\\.[0-9]+)?)\\s+AND\\s+([-+]?[0-9]+(?:\\.[0-9]+)?)"),
            Pattern.CASE_INSENSITIVE);
    Pattern rangePattern =
        Pattern.compile(
            colPattern.concat("\\s*(>=|<=|>|<|=)\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)"),
            Pattern.CASE_INSENSITIVE);
    Pattern inPattern =
        Pattern.compile(colPattern.concat("\\s+IN\\s*\\(([^)]+)\\)"), Pattern.CASE_INSENSITIVE);
    Pattern eqPattern =
        Pattern.compile(
            colPattern.concat("\\s*=\\s*('.*?'|\".*?\"|[0-9A-ZaZ_+-]+)"), Pattern.CASE_INSENSITIVE);
    Pattern lenPattern =
        Pattern.compile(
            "(?i)(?:char_length|length)\\s*\\(\\s*".concat(colPattern).concat("\\s*\\)\\s*(<=|<|=)\\s*(\\d+)"));

    for (String check : checks) {
      if (check == null || check.isBlank()) continue;
      String expr = check;
      // remove parentheses for numeric/range matching (many DBs wrap subexpressions in parentheses)
      String exprNoParens = expr.replaceAll("[()]+", " ");

      // BETWEEN: set lower = max(lower, min(a,b)), upper = min(upper, max(a,b))
      Matcher mb = betweenPattern.matcher(exprNoParens);
      while (mb.find()) {
        try {
          double a = Double.parseDouble(mb.group(1));
          double b = Double.parseDouble(mb.group(2));
          double lo = Math.min(a, b);
          double hi = Math.max(a, b);
          lower = (lower == null) ? lo : Math.max(lower, lo);
          upper = (upper == null) ? hi : Math.min(upper, hi);
        } catch (NumberFormatException ignored) {
        }
      }

      // ranges and equality
      Matcher mr = rangePattern.matcher(exprNoParens);
      while (mr.find()) {
        String op = mr.group(1);
        String num = mr.group(2);
        try {
          double val = Double.parseDouble(num);
          switch (op) {
            case ">": // exclusive lower -> next representable
              val = Math.nextUp(val);
              lower = (lower == null) ? val : Math.max(lower, val);
              break;
            case ">=":
              lower = (lower == null) ? val : Math.max(lower, val);
              break;
            case "<": // exclusive upper
              val = Math.nextDown(val);
              upper = (upper == null) ? val : Math.min(upper, val);
              break;
            case "<=":
              upper = (upper == null) ? val : Math.min(upper, val);
              break;
            case "=":
              lower = (lower == null) ? val : Math.max(lower, val);
              upper = (upper == null) ? val : Math.min(upper, val);
              break;
            default:
              break;
          }
        } catch (NumberFormatException ignored) {
        }
      }

      // IN lists (must use original expr because IN(...) needs parentheses)
      Matcher mi = inPattern.matcher(expr);
      while (mi.find()) {
        String inside = mi.group(1);
        String[] parts = inside.split("\\s*,\\s*");
        for (String p : parts) {
          String s = p.trim();
          if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            s = s.substring(1, s.length() - 1);
          }
          if (!s.isEmpty()) allowed.add(s);
        }
      }

      // equality patterns (use expression without parens to match variants)
      Matcher me = eqPattern.matcher(exprNoParens);
      while (me.find()) {
        String raw = me.group(1).trim();
        String s = raw;
        if (s.startsWith("'") && s.endsWith("'")) s = s.substring(1, s.length() - 1);
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        if (!s.isEmpty()) allowed.add(s);
      }

      // length constraints
      Matcher ml = lenPattern.matcher(expr);
      while (ml.find()) {
        String op = ml.group(1);
        String num = ml.group(2);
        try {
          int v = Integer.parseInt(num);
          if ("<".equals(op) || "<=".equals(op) || "=".equals(op)) {
            maxLen = (maxLen == null) ? v : Math.min(maxLen, v);
          }
        } catch (NumberFormatException ignored) {
        }
      }
    }

    // prefer column declared length if smaller
    if (columnLength > 0) {
      if (maxLen == null || columnLength < maxLen) maxLen = columnLength;
    }

    return new ParsedConstraint(lower, upper, Set.copyOf(allowed), maxLen);
  }

  private static String normalizeToLength(String value, int length, int jdbcType) {
    if (length <= 0) return value;
    if (value.length() > length) {
      return value.substring(0, length);
    }
    if (jdbcType == Types.CHAR && value.length() < length) {
      return String.format("%-".concat(String.valueOf(length)).concat("s"), value);
    }
    return value;
  }

  private static Integer boundedInt(Column column) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int min = (column.minValue() != 0) ? column.minValue() : 1;
    int max = (column.maxValue() != 0) ? column.maxValue() : DEFAULT_INT_MAX;
    if (min > max) {
      int t = min;
      min = max;
      max = t;
    }
    long v = r.nextLong(min, max + 1);
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
  }

  private static Long boundedLong(Column column) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int min = (column.minValue() != 0) ? column.minValue() : 1;
    int max = (column.maxValue() != 0) ? column.maxValue() : DEFAULT_LONG_MAX;
    if (min > max) {
      int t = min;
      min = max;
      max = t;
    }
    return r.nextLong(min, Math.addExact(max, 1L));
  }

  private static Double boundedDecimal(Column column) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int min = (column.minValue() != 0) ? column.minValue() : 1;
    int max = (column.maxValue() != 0) ? column.maxValue() : DEFAULT_DECIMAL_MAX;
    if (min > max) {
      int t = min;
      min = max;
      max = t;
    }
    return min + (max - min) * r.nextDouble();
  }

  private static boolean isSingleWord(String tableName) {
    return SINGLE_WORD_PATTERN.matcher(tableName).matches();
  }

  private static List<Table> orderByWordAndFk(List<Table> tables) {
    List<Table> singleWord = new ArrayList<>();
    List<Table> multiWord = new ArrayList<>();
    tables.forEach(table -> (isSingleWord(table.name()) ? singleWord : multiWord).add(table));
    List<Table> ordered = new ArrayList<>(orderByFk(singleWord));
    ordered.addAll(orderByFk(multiWord));
    return List.copyOf(ordered);
  }

  private static List<Table> orderByFk(List<Table> tables) {
    List<Table> ordered = new ArrayList<>(tables);
    ordered.sort(
        Comparator.comparingInt((Table table) -> table.foreignKeys().size())
            .thenComparing(Table::name, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(ordered);
  }

  // Small container for parsed constraints for a single column.
  private record ParsedConstraint(
      Double min, Double max, Set<String> allowedValues, Integer maxLength) {}

  public record GenerationResult(Map<String, List<Row>> rows, List<PendingUpdate> updates) {}

  private static void ensureUuidUniqueness(
      Map<String, List<Row>> data, List<Table> orderedTables, Set<UUID> usedUuids) {
    // Ensure UUID columns contain unique UUIDs (per column and globally). If duplicates are found,
    // replace them with new unique UUIDs and record them in usedUuids.
    Map<String, Set<UUID>> seenPerColumn = new HashMap<>();
    for (Table table : orderedTables) {
      List<Row> rows = data.get(table.name());
      if (rows == null) continue;
      for (Column col : table.columns()) {
        if (!col.uuid()) continue;
        String key = table.name().concat(".").concat(col.name());
        Set<UUID> seen = seenPerColumn.computeIfAbsent(key, k -> new HashSet<>());
        for (Row row : rows) {
          Object v = row.values().get(col.name());
          UUID u = null;
          if (v instanceof UUID) u = (UUID) v;
          else if (v instanceof String) {
            try {
              u = UUID.fromString(((String) v).trim());
            } catch (IllegalArgumentException ignored) {
              // ignore invalid UUID string
            }
          }
          if (u == null) {
            // generate a new unique UUID and set it
            u = generateUuid(usedUuids);
            row.values().put(col.name(), u);
            seen.add(u);
            continue;
          }
          if (seen.contains(u)) {
            // duplicate detected for this column -> replace
            UUID newU = generateUuid(usedUuids);
            row.values().put(col.name(), newU);
            seen.add(newU);
            log.warn(
                "Replaced duplicate UUID for {}.{}: {} -> {}",
                table.name(),
                col.name(),
                u,
                newU);
          } else {
            // record and also add to global used set in case it wasn't present
            seen.add(u);
            usedUuids.add(u);
          }
        }
      }
    }
  }
}
