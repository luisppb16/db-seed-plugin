/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AbstractDialectTest {

  private static String fmt(final DatabaseDialect d, final Object value) {
    final StringBuilder sb = new StringBuilder();
    d.formatValue(value, sb);
    return sb.toString();
  }

  private static List<Row> rows(final int count) {
    final List<Row> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      result.add(new Row(Map.of("id", i)));
    }
    return result;
  }

  private static String batch(final DatabaseDialect d, final int rowCount) {
    final StringBuilder sb = new StringBuilder();
    d.appendBatch(sb, "\"t\"", "\"id\"", rows(rowCount), List.of("id"));
    return sb.toString();
  }

  private static long count(final String sql, final String token) {
    return sql.split(Pattern.quote(token), -1).length - 1L;
  }

  // ── formatValue ──

  @Nested
  class FormatValueTests {
    final DatabaseDialect d = new StandardDialect();

    @Test
    void nullValue_formatsAsNull() {
      assertThat(fmt(d, null)).isEqualTo("NULL");
    }

    @Test
    void string_wrappedInSingleQuotes() {
      assertThat(fmt(d, "hello")).isEqualTo("'hello'");
    }

    @Test
    void string_singleQuotes_escapedByDoubling() {
      assertThat(fmt(d, "O'Brien's")).isEqualTo("'O''Brien''s'");
    }

    @Test
    void string_newlines_replacedWithSpace() {
      assertThat(fmt(d, "line1\nline2")).isEqualTo("'line1 line2'");
      assertThat(fmt(d, "line1\r\nline2")).isEqualTo("'line1 line2'");
      assertThat(fmt(d, "line1\rline2")).isEqualTo("'line1 line2'");
    }

    @Test
    void string_nulCharacter_removed() {
      assertThat(fmt(d, "he\0llo")).isEqualTo("'hello'");
    }

    @Test
    void booleanValues_useDialectLiterals() {
      assertThat(fmt(d, Boolean.TRUE)).isEqualTo("TRUE");
      assertThat(fmt(d, Boolean.FALSE)).isEqualTo("FALSE");
    }

    @Test
    void sqlDate_formattedWithDialectTemplate() {
      assertThat(fmt(d, Date.valueOf("2024-01-15"))).isEqualTo("'2024-01-15'");
    }

    @Test
    void sqlTimestamp_formattedWithDialectTemplate() {
      final String result = fmt(d, Timestamp.valueOf("2024-01-15 10:30:45"));
      assertThat(result).startsWith("'2024-01-15 10:30:45").endsWith("'");
    }

    @Test
    void uuid_formattedWithDialectTemplate() {
      final UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
      assertThat(fmt(d, uuid)).isEqualTo("'550e8400-e29b-41d4-a716-446655440000'");
    }

    @Test
    void bigDecimal_usesPlainString() {
      assertThat(fmt(d, new BigDecimal("123.45"))).isEqualTo("123.45");
      assertThat(fmt(d, new BigDecimal("1.23E+4"))).isEqualTo("12300");
    }

    @Test
    void double_normalValue() {
      assertThat(fmt(d, 3.14d)).isEqualTo("3.14");
    }

    @Test
    void double_largeValue_neverUsesScientificNotation() {
      assertThat(fmt(d, 1.0E10d)).isEqualTo("10000000000");
    }

    @Test
    void double_nan_formatsAsNull() {
      assertThat(fmt(d, Double.NaN)).isEqualTo("NULL");
    }

    @Test
    void double_infinity_formatsAsNull() {
      assertThat(fmt(d, Double.POSITIVE_INFINITY)).isEqualTo("NULL");
      assertThat(fmt(d, Double.NEGATIVE_INFINITY)).isEqualTo("NULL");
    }

    @Test
    void float_formattedAsDouble() {
      assertThat(fmt(d, 2.5f)).isEqualTo("2.5");
    }

    @Test
    void float_nan_formatsAsNull() {
      assertThat(fmt(d, Float.NaN)).isEqualTo("NULL");
    }

    @Test
    void character_quotedAndEscaped() {
      assertThat(fmt(d, 'A')).isEqualTo("'A'");
      assertThat(fmt(d, '\'')).isEqualTo("''''");
    }

    @Test
    void objectArray_usesDialectArrayFormat() {
      assertThat(fmt(d, new Object[] {1, "a"})).isEqualTo("ARRAY[1, 'a']");
    }

    @Test
    void list_usesDialectArrayFormat() {
      assertThat(fmt(d, List.of(1, 2, 3))).isEqualTo("ARRAY[1, 2, 3]");
    }

    @Test
    void array_withNullElement_formatsNull() {
      assertThat(fmt(d, new Object[] {1, null})).isEqualTo("ARRAY[1, NULL]");
    }

    @Test
    void sqlKeywordDefault_emittedVerbatim() {
      assertThat(fmt(d, SqlKeyword.DEFAULT)).isEqualTo("DEFAULT");
    }

    @Test
    void otherTypes_fallBackToToString() {
      assertThat(fmt(d, 42)).isEqualTo("42");
      assertThat(fmt(d, 9_999_999_999L)).isEqualTo("9999999999");
    }
  }

  // ── quote ──

  @Nested
  class QuoteTests {

    @Test
    void standard_wrapsInDoubleQuotes() {
      final DatabaseDialect d = new StandardDialect();
      assertThat(d.quote("users")).isEqualTo("\"users\"");
    }

    @Test
    void standard_embeddedQuoteChar_escapedByDoubling() {
      final DatabaseDialect d = new StandardDialect();
      assertThat(d.quote("my\"col")).isEqualTo("\"my\"\"col\"");
    }

    @Test
    void mysql_wrapsInBackticks() {
      final DatabaseDialect d = new StandardDialect("mysql.properties");
      assertThat(d.quote("users")).isEqualTo("`users`");
    }

    @Test
    void mysql_embeddedBacktick_escapedByDoubling() {
      final DatabaseDialect d = new StandardDialect("mysql.properties");
      assertThat(d.quote("a`b")).isEqualTo("`a``b`");
    }

    @Test
    void sqlserver_usesAsymmetricBrackets() {
      final DatabaseDialect d = new StandardDialect("sqlserver.properties");
      assertThat(d.quote("order")).isEqualTo("[order]");
    }

    @Test
    void sqlserver_embeddedClosingBracket_escapedByDoubling() {
      final DatabaseDialect d = new StandardDialect("sqlserver.properties");
      assertThat(d.quote("a]b")).isEqualTo("[a]]b]");
    }

    @Test
    void sqlserver_openingBracket_notEscaped() {
      final DatabaseDialect d = new StandardDialect("sqlserver.properties");
      assertThat(d.quote("a[b")).isEqualTo("[a[b]");
    }

    @Test
    void oracle_uppercasesIdentifiers() {
      final DatabaseDialect d = new StandardDialect("oracle.properties");
      assertThat(d.quote("myCol")).isEqualTo("\"MYCOL\"");
    }
  }

  // ── appendBatch ──

  @Nested
  class AppendBatchTests {

    @Test
    void multiRow_singleStatement_withHeaderRowsAndFooter() {
      final String sql = batch(new StandardDialect(), 3);
      assertThat(sql).isEqualTo("INSERT INTO \"t\" (\"id\") VALUES\n(0),\n(1),\n(2);\n");
    }

    @Test
    void multiRow_rowsWithinMaxBatchSize_singleInsert() {
      final DatabaseDialect sqlite = new StandardDialect("sqlite.properties");
      final String sql = batch(sqlite, 100);
      assertThat(count(sql, "INSERT INTO")).isEqualTo(1);
    }

    @Test
    void multiRow_splitsIntoMultipleInserts_whenRowsExceedMaxBatchSize() {
      // sqlite.properties defines maxBatchSize=100 → 250 rows = 100 + 100 + 50
      final DatabaseDialect sqlite = new StandardDialect("sqlite.properties");
      final String sql = batch(sqlite, 250);
      assertThat(count(sql, "INSERT INTO")).isEqualTo(3);
      assertThat(sql).contains("(249)").endsWith(";\n");
    }

    @Test
    void multiRow_exactMultipleOfMaxBatchSize_noEmptyTrailingBatch() {
      final DatabaseDialect sqlite = new StandardDialect("sqlite.properties");
      final String sql = batch(sqlite, 200);
      assertThat(count(sql, "INSERT INTO")).isEqualTo(2);
    }

    @Test
    void singleRow_whenMultiRowInsertNotSupported_oneInsertPerRow() {
      // derby.properties defines supportsMultiRowInsert=false
      final DatabaseDialect derby = new StandardDialect("derby.properties");
      final String sql = batch(derby, 2);
      assertThat(sql)
          .isEqualTo(
              "INSERT INTO \"t\" (\"id\") VALUES\n(0);\nINSERT INTO \"t\" (\"id\") VALUES\n(1);\n");
    }

    @Test
    void singleRow_oracle_emitsInsertAllPerRow() {
      // oracle.properties defines supportsMultiRowInsert=false with INSERT ALL templates
      final DatabaseDialect oracle = new StandardDialect("oracle.properties");
      final String sql = batch(oracle, 2);
      assertThat(count(sql, "INSERT ALL")).isEqualTo(2);
      assertThat(count(sql, "SELECT * FROM dual")).isEqualTo(2);
      assertThat(sql).contains("INTO \"t\" (\"id\") VALUES (0)");
      assertThat(sql).contains("INTO \"t\" (\"id\") VALUES (1)");
    }
  }

  // ── getProperty / resource loading ──

  @Nested
  class PropertyLoadingTests {

    @Test
    void getProperty_returnsConfiguredValue() {
      final DatabaseDialect d = new StandardDialect();
      assertThat(d.getProperty("booleanTrue", "X")).isEqualTo("TRUE");
    }

    @Test
    void getProperty_returnsDefault_whenKeyMissing() {
      final DatabaseDialect d = new StandardDialect();
      assertThat(d.getProperty("no.such.key", "fallback")).isEqualTo("fallback");
    }

    @Test
    void missingResource_doesNotThrow() {
      assertThatCode(() -> new StandardDialect("noexiste.properties")).doesNotThrowAnyException();
    }

    @Test
    void missingResource_fallsBackToBuiltInDefaults() {
      final DatabaseDialect d = new StandardDialect("noexiste.properties");
      assertThat(d.getProperty("quoteChar", "?")).isEqualTo("?");
      assertThat(d.quote("t")).isEqualTo("\"t\"");
      assertThat(d.formatBoolean(true)).isEqualTo("TRUE");
      assertThat(d.formatBoolean(false)).isEqualTo("FALSE");
      assertThat(d.beginTransaction()).isEqualTo("BEGIN;\n");
      assertThat(d.commitTransaction()).isEqualTo("COMMIT;\n");
      assertThat(d.disableConstraints()).isEmpty();
      assertThat(d.enableConstraints()).isEmpty();
    }

    @Test
    void missingResource_appendBatch_usesDefaultTemplates() {
      final String sql = batch(new StandardDialect("noexiste.properties"), 2);
      assertThat(sql).isEqualTo("INSERT INTO \"t\" (\"id\") VALUES\n(0),\n(1);\n");
    }
  }
}
