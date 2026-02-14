/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import static org.assertj.core.api.Assertions.*;

import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DialectTest {

  private static String fmt(DatabaseDialect d, Object value) {
    StringBuilder sb = new StringBuilder();
    d.formatValue(value, sb);
    return sb.toString();
  }

  // ── AbstractDialect formatValue (via StandardDialect) ──

  @Nested
  class FormatValueTests {
    final DatabaseDialect d = new StandardDialect();

    @Test
    void null_formatsAsNULL() {
      assertThat(fmt(d, null)).isEqualTo("NULL");
    }

    @Test
    void string_quoted() {
      assertThat(fmt(d, "hello")).isEqualTo("'hello'");
    }

    @Test
    void string_quoteEscaping() {
      assertThat(fmt(d, "it's")).isEqualTo("'it''s'");
    }

    @Test
    void uuid_quoted() {
      UUID u = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
      assertThat(fmt(d, u)).isEqualTo("'550e8400-e29b-41d4-a716-446655440000'");
    }

    @Test
    void date_quoted() {
      Date date = Date.valueOf("2024-01-15");
      assertThat(fmt(d, date)).isEqualTo("'2024-01-15'");
    }

    @Test
    void timestamp_quoted() {
      Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");
      assertThat(fmt(d, ts)).startsWith("'2024-01-15 10:30:00");
    }

    @Test
    void booleanTrue() {
      assertThat(fmt(d, true)).isEqualTo("TRUE");
    }

    @Test
    void booleanFalse() {
      assertThat(fmt(d, false)).isEqualTo("FALSE");
    }

    @Test
    void bigDecimal() {
      assertThat(fmt(d, new BigDecimal("123.45"))).isEqualTo("123.45");
    }

    @Test
    void doubleValue() {
      assertThat(fmt(d, 3.14)).isEqualTo("3.14");
    }

    @Test
    void doubleNaN() {
      assertThat(fmt(d, Double.NaN)).isEqualTo("NULL");
    }

    @Test
    void doubleInfinity() {
      assertThat(fmt(d, Double.POSITIVE_INFINITY)).isEqualTo("NULL");
    }

    @Test
    void integer() {
      assertThat(fmt(d, 42)).isEqualTo("42");
    }

    @Test
    void sqlKeyword() {
      assertThat(fmt(d, SqlKeyword.DEFAULT)).isEqualTo("DEFAULT");
    }

    @Test
    void character() {
      assertThat(fmt(d, 'A')).isEqualTo("'A'");
    }
  }

  // ── escapeSql / Backslash injection ──

  @Nested
  class EscapeSqlTests {
    final DatabaseDialect d = new StandardDialect();

    @Test
    void singleQuote_escaped() {
      assertThat(fmt(d, "it's")).isEqualTo("'it''s'");
    }

    @Test
    void backslash_notEscaped_standardDialect() {
      assertThat(fmt(d, "C:\\path")).isEqualTo("'C:\\path'");
    }

    @Test
    void backslashBeforeQuote_onlyQuoteEscaped_standardDialect() {
      assertThat(fmt(d, "a\\'b")).isEqualTo("'a\\''b'");
    }

    @Test
    void noSpecialChars_unchanged() {
      assertThat(fmt(d, "plain text")).isEqualTo("'plain text'");
    }

    @Test
    void mysqlBackslash_escaped() {
      DatabaseDialect mysql = new StandardDialect("mysql.properties");
      assertThat(fmt(mysql, "C:\\path")).isEqualTo("'C:\\\\path'");
    }

    @Test
    void mysqlBackslashBeforeQuote_bothEscaped() {
      DatabaseDialect mysql = new StandardDialect("mysql.properties");
      assertThat(fmt(mysql, "a\\'b")).isEqualTo("'a\\\\''b'");
    }

    @Test
    void mysqlMultipleBackslashes_allEscaped() {
      DatabaseDialect mysql = new StandardDialect("mysql.properties");
      assertThat(fmt(mysql, "a\\\\b")).isEqualTo("'a\\\\\\\\b'");
    }

    @Test
    void mysqlBackslashInjection_prevented() {
      DatabaseDialect mysql = new StandardDialect("mysql.properties");
      String malicious = "\\'; DROP TABLE users; --";
      String result = fmt(mysql, malicious);
      assertThat(result).contains("\\\\");
      assertThat(result).contains("''");
      assertThat(result).startsWith("'").endsWith("'");
      assertThat(result).isEqualTo("'\\\\''; DROP TABLE users; --'");
    }

    @Test
    void emptyString_escaped() {
      assertThat(fmt(d, "")).isEqualTo("''");
    }

    @Test
    void characterBackslash_notEscaped_standardDialect() {
      assertThat(fmt(d, '\\')).isEqualTo("'\\'");
    }

    @Test
    void mysqlCharacterBackslash_escaped() {
      DatabaseDialect mysql = new StandardDialect("mysql.properties");
      assertThat(fmt(mysql, '\\')).isEqualTo("'\\\\'");
    }
  }

  // ── StandardDialect ──

  @Nested
  class StandardDialectTests {
    final StandardDialect d = new StandardDialect();

    @Test
    void quote_doubleQuotes() {
      assertThat(d.quote("table")).isEqualTo("\"table\"");
    }

    @Test
    void formatBoolean_true() {
      assertThat(d.formatBoolean(true)).isEqualTo("TRUE");
    }

    @Test
    void beginTransaction() {
      assertThat(d.beginTransaction()).isEqualTo("BEGIN;\n");
    }

    @Test
    void commitTransaction() {
      assertThat(d.commitTransaction()).isEqualTo("COMMIT;\n");
    }
  }

  // ── MySQLDialect ──

  @Nested
  class MySQLDialectTests {
    final DatabaseDialect d = new StandardDialect("mysql.properties");

    @Test
    void quote_backtick() {
      assertThat(d.quote("user")).isEqualTo("`user`");
    }

    @Test
    void boolean_1_0() {
      assertThat(d.formatBoolean(true)).isEqualTo("1");
      assertThat(d.formatBoolean(false)).isEqualTo("0");
    }

    @Test
    void beginTransaction() {
      assertThat(d.beginTransaction()).isEqualTo("START TRANSACTION;\n");
    }

    @Test
    void fkChecks() {
      assertThat(d.disableConstraints()).contains("FOREIGN_KEY_CHECKS = 0");
      assertThat(d.enableConstraints()).contains("FOREIGN_KEY_CHECKS = 1");
    }
  }

  // ── PostgreSqlDialect ──

  @Nested
  class PostgreSqlDialectTests {
    final DatabaseDialect d = new StandardDialect("postgresql.properties");

    @Test
    void quote_doubleQuote() {
      assertThat(d.quote("col")).isEqualTo("\"col\"");
    }

    @Test
    void beginTransaction() {
      assertThat(d.beginTransaction()).isEqualTo("BEGIN;\n");
    }
  }

  // ── SqlServerDialect ──

  @Nested
  class SqlServerDialectTests {
    final DatabaseDialect d = new StandardDialect("sqlserver.properties");

    @Test
    void quote_squareBrackets() {
      assertThat(d.quote("order")).startsWith("[").contains("order");
    }

    @Test
    void boolean_1_0() {
      assertThat(d.formatBoolean(true)).isEqualTo("1");
      assertThat(d.formatBoolean(false)).isEqualTo("0");
    }

    @Test
    void beginTransaction() {
      assertThat(d.beginTransaction()).isEqualTo("BEGIN TRANSACTION;\n");
    }

    @Test
    void commitTransaction() {
      assertThat(d.commitTransaction()).isEqualTo("COMMIT TRANSACTION;\n");
    }
  }

  // ── OracleDialect ──

  @Nested
  class OracleDialectTests {
    final DatabaseDialect d = new StandardDialect("oracle.properties");

    @Test
    void quote_uppercase() {
      assertThat(d.quote("myCol")).isEqualTo("\"MYCOL\"");
    }

    @Test
    void formatValue_date_toDate() {
      Date date = Date.valueOf("2024-01-15");
      assertThat(fmt(d, date)).isEqualTo("TO_DATE('2024-01-15', 'YYYY-MM-DD')");
    }

    @Test
    void formatValue_timestamp_toTimestamp() {
      Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");
      assertThat(fmt(d, ts)).startsWith("TO_TIMESTAMP('2024-01-15 10:30:00");
    }

    @Test
    void boolean_1_0() {
      assertThat(d.formatBoolean(true)).isEqualTo("1");
      assertThat(d.formatBoolean(false)).isEqualTo("0");
    }
  }

  // ── SqliteDialect ──

  @Nested
  class SqliteDialectTests {
    final DatabaseDialect d = new StandardDialect("sqlite.properties");

    @Test
    void batchSplittingAt100Rows() {
      List<Row> rows = new ArrayList<>();
      for (int i = 0; i < 150; i++) {
        rows.add(new Row(Map.of("id", i)));
      }
      StringBuilder sb = new StringBuilder();
      d.appendBatch(sb, "t", "\"id\"", rows, List.of("id"));
      String sql = sb.toString();
      // Should have 2 INSERT statements (100 + 50)
      long insertCount = sql.chars().filter(ch -> ch == ';').count();
      assertThat(insertCount).isEqualTo(2);
    }
  }

  // ── appendBatch ──

  @Nested
  class AppendBatchTests {
    final StandardDialect d = new StandardDialect();

    @Test
    void singleRow() {
      Row row = new Row(Map.of("id", 1, "name", "test"));
      StringBuilder sb = new StringBuilder();
      d.appendBatch(sb, "\"t\"", "\"id\", \"name\"", List.of(row), List.of("id", "name"));
      String sql = sb.toString();
      assertThat(sql).contains("INSERT INTO");
      assertThat(sql).contains("VALUES");
    }

    @Test
    void multipleRows() {
      List<Row> rows = List.of(new Row(Map.of("id", 1)), new Row(Map.of("id", 2)));
      StringBuilder sb = new StringBuilder();
      d.appendBatch(sb, "\"t\"", "\"id\"", rows, List.of("id"));
      String sql = sb.toString();
      assertThat(sql).contains(",\n");
    }
  }
}
