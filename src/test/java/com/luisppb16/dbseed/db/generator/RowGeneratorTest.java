/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.ai.OllamaClient;
import com.luisppb16.dbseed.db.ProgressTracker;
import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class RowGeneratorTest {

  private static Column intCol(final String name) {
    return new Column(name, Types.INTEGER, null, false, false, false, 0, 0, null, null, Set.of());
  }

  private static Column intPk(final String name) {
    return new Column(name, Types.INTEGER, null, false, true, false, 0, 0, null, null, Set.of());
  }

  private static Column varcharCol(final String name) {
    return new Column(name, Types.VARCHAR, null, false, false, false, 100, 0, null, null, Set.of());
  }

  /**
   * Runs AI generation for a single varchar column against an embedded HTTP server and returns the
   * number of requests made. The server always replies with 500 distinct valid values so the retry
   * loop never triggers, making the request count equal to the number of batches.
   */
  private static int countAiRequests(final int wordCount, final int totalRows) throws Exception {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final AtomicInteger requestCount = new AtomicInteger();
    final String values =
        IntStream.range(0, 500).mapToObj(i -> "val" + i).collect(Collectors.joining("\\n"));
    server.createContext(
        "/",
        exchange -> {
          requestCount.incrementAndGet();
          respond(exchange, 200, "{\"response\":\"" + values + "\"}");
        });
    server.start();
    try {
      final OllamaClient client =
          new OllamaClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-model", 999);
      final Table table =
          new Table(
              "t",
              List.of(intPk("id"), varcharCol("name")),
              List.of("id"),
              List.of(),
              List.of(),
              List.of());
      final RowGenerator gen =
          new RowGenerator(
              table,
              totalRows,
              Set.of(),
              List.of(),
              new Faker(),
              new HashSet<>(),
              List.of(),
              false,
              Set.of(),
              false,
              null,
              2,
              Set.of("name"),
              wordCount,
              client,
              "",
              new ProgressTracker(null, 0));
      gen.generate();
      gen.generateAiValuesForColumn(varcharCol("name"), null);
      return requestCount.get();
    } finally {
      server.stop(0);
    }
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    exchange.getRequestBody().readAllBytes();
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    exchange.close();
  }

  // ── Basic ──

  private RowGenerator generator(final Table table, final int rowCount) {
    return new RowGenerator(
        table,
        rowCount,
        Set.of(),
        List.of(),
        new Faker(),
        new HashSet<>(),
        List.of(),
        false,
        Set.of(),
        false,
        null,
        2,
        Set.of(),
        1,
        null,
        null,
        new ProgressTracker(null, 0));
  }

  private RowGenerator generator(
      final Table table, final int rowCount, final Set<String> excluded) {
    return new RowGenerator(
        table,
        rowCount,
        excluded,
        List.of(),
        new Faker(),
        new HashSet<>(),
        List.of(),
        false,
        Set.of(),
        false,
        null,
        2,
        Set.of(),
        1,
        null,
        null,
        new ProgressTracker(null, 0));
  }

  @Test
  void correctRowCount() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), varcharCol("name")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final List<Row> rows = generator(t, 10).generate();
    assertThat(rows).hasSize(10);
  }

  // ── PK uniqueness ──

  @Test
  void emptyColumnsTable() {
    final Table t = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of());
    final List<Row> rows = generator(t, 5).generate();
    assertThat(rows).isEmpty();
  }

  @Test
  void allColumnsPresent() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("a"), intCol("b"), varcharCol("c")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final List<Row> rows = generator(t, 5).generate();
    for (final Row row : rows) {
      assertThat(row.values()).containsKeys("a", "b", "c");
    }
  }

  // ── Unique keys ──

  @Test
  void integerPk_unique() {
    final Table t =
        new Table("t", List.of(intPk("id")), List.of("id"), List.of(), List.of(), List.of());
    final List<Row> rows = generator(t, 20).generate();
    final Set<Object> pkValues = new HashSet<>();
    for (final Row r : rows) {
      pkValues.add(r.values().get("id"));
    }
    assertThat(pkValues).hasSize(rows.size());
  }

  // ── Excluded columns ──

  @Test
  void compositePk_unique() {
    final Table t =
        new Table(
            "t",
            List.of(intPk("a"), intPk("b")),
            List.of("a", "b"),
            List.of(),
            List.of(),
            List.of());
    final List<Row> rows = generator(t, 20).generate();
    final Set<String> combos = new HashSet<>();
    for (final Row r : rows) {
      combos.add(r.values().get("a") + "|" + r.values().get("b"));
    }
    assertThat(combos).hasSize(rows.size());
  }

  // ── FK columns ──

  @Test
  void noDuplicateUniqueCombinations() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), varcharCol("code")),
            List.of(),
            List.of(),
            List.of(),
            List.of(List.of("code")));
    final List<Row> rows = generator(t, 10).generate();
    final Set<Object> seen = new HashSet<>();
    for (final Row r : rows) {
      seen.add(r.values().get("code"));
    }
    assertThat(seen).hasSize(rows.size());
  }

  // ── Soft delete ──

  @Test
  void excludedColumn_valueIsNull() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), intCol("excluded_col")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final List<Row> rows = generator(t, 5, Set.of("excluded_col")).generate();
    for (final Row r : rows) {
      assertThat(r.values().get("excluded_col")).isNull();
    }
  }

  @Test
  void fkColumn_nonPk_getsNull() {
    final ForeignKey fk = new ForeignKey(null, "parent", Map.of("parent_id", "id"), false);
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(fk),
            List.of(),
            List.of());
    final List<Row> rows = generator(t, 5).generate();
    // FK columns that are not PKs should be null (resolved later by ForeignKeyResolver)
    for (final Row r : rows) {
      assertThat(r.values().get("parent_id")).isNull();
    }
  }

  // ── Repetition rules ──

  @Test
  void softDelete_schemaDefault() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), intCol("deleted")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final RowGenerator gen =
        new RowGenerator(
            t,
            5,
            Set.of(),
            List.of(),
            new Faker(),
            new HashSet<>(),
            List.of(),
            false,
            Set.of("deleted"),
            true,
            null,
            2,
            Set.of(),
            1,
            null,
            null,
            new ProgressTracker(null, 0));
    final List<Row> rows = gen.generate();
    for (final Row r : rows) {
      assertThat(r.values().get("deleted")).isEqualTo(SqlKeyword.DEFAULT);
    }
  }

  @Test
  void softDelete_customValue() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), intCol("is_active")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final RowGenerator gen =
        new RowGenerator(
            t,
            5,
            Set.of(),
            List.of(),
            new Faker(),
            new HashSet<>(),
            List.of(),
            false,
            Set.of("is_active"),
            false,
            "1",
            2,
            Set.of(),
            1,
            null,
            null,
            new ProgressTracker(null, 0));
    final List<Row> rows = gen.generate();
    for (final Row r : rows) {
      assertThat(r.values().get("is_active")).isEqualTo(1);
    }
  }

  @Test
  void repetitionRules_fixedValuesApplied() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), varcharCol("type")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final RepetitionRule rule = new RepetitionRule(3, Map.of("type", "fixed"), Set.of(), Map.of());
    final RowGenerator gen =
        new RowGenerator(
            t,
            10,
            Set.of(),
            List.of(rule),
            new Faker(),
            new HashSet<>(),
            List.of(),
            false,
            Set.of(),
            false,
            null,
            2,
            Set.of(),
            1,
            null,
            null,
            new ProgressTracker(null, 0));
    final List<Row> rows = gen.generate();
    // First 3 rows should have type=fixed
    final long fixedCount =
        rows.stream().filter(r -> "fixed".equals(r.values().get("type"))).count();
    assertThat(fixedCount).isGreaterThanOrEqualTo(3);
  }

  // ── getConstraints ──

  @Test
  void repetitionRules_totalRowsCorrect() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), varcharCol("name")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final RepetitionRule rule = new RepetitionRule(3, Map.of(), Set.of(), Map.of());
    final RowGenerator gen =
        new RowGenerator(
            t,
            10,
            Set.of(),
            List.of(rule),
            new Faker(),
            new HashSet<>(),
            List.of(),
            false,
            Set.of(),
            false,
            null,
            2,
            Set.of(),
            1,
            null,
            null,
            new ProgressTracker(null, 0));
    final List<Row> rows = gen.generate();
    assertThat(rows).hasSize(10);
  }

  // ── AI columns ──

  @Test
  void repetitionRules_regexPatternAppliedOnStringColumns() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("id"), varcharCol("hex_color")),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final RepetitionRule rule =
        new RepetitionRule(
            3,
            Map.of("hex_color", "ignored-when-regex-present"),
            Set.of(),
            Map.of("hex_color", "#[0-9A-F]{6}"));

    final RowGenerator gen =
        new RowGenerator(
            t,
            5,
            Set.of(),
            List.of(rule),
            new Faker(),
            new HashSet<>(),
            List.of(),
            false,
            Set.of(),
            false,
            null,
            2,
            Set.of(),
            1,
            null,
            null,
            new ProgressTracker(null, 0));

    final List<Row> rows = gen.generate();
    assertThat(rows).hasSize(5);
    final Pattern hexColorPattern = Pattern.compile("#[0-9A-F]{6}");
    assertThat(rows)
        .extracting(r -> r.values().get("hex_color"))
        .allSatisfy(v -> assertThat(v).isInstanceOf(String.class));
    final long matchingHexColors =
        rows.stream()
            .map(r -> (String) r.values().get("hex_color"))
            .filter(v -> hexColorPattern.matcher(v).matches())
            .count();
    assertThat(matchingHexColors).isGreaterThanOrEqualTo(3);
  }

  // ── Adaptive AI batch sizing ──

  @Test
  void getConstraints_returnsMap() {
    final Table t =
        new Table(
            "t",
            List.of(intCol("val")),
            List.of(),
            List.of(),
            List.of("val BETWEEN 1 AND 10"),
            List.of());
    final RowGenerator gen = generator(t, 1);
    gen.generate();
    assertThat(gen.getConstraints()).containsKey("val");
    assertThat(gen.getConstraints().get("val").min()).isEqualTo(1.0);
    assertThat(gen.getConstraints().get("val").max()).isEqualTo(10.0);
  }

  @Test
  void getValidAiColumns_excludesFkColumns() {
    final ForeignKey fk = new ForeignKey(null, "parent", Map.of("parent_id", "id"), false);
    final Table t =
        new Table(
            "t",
            List.of(intPk("id"), varcharCol("name"), varcharCol("parent_id")),
            List.of("id"),
            List.of(fk),
            List.of(),
            List.of());
    final RowGenerator gen =
        new RowGenerator(
            t,
            5,
            Set.of(),
            List.of(),
            new Faker(),
            new HashSet<>(),
            List.of(),
            false,
            Set.of(),
            false,
            null,
            2,
            Set.of("name", "parent_id"),
            1,
            new OllamaClient("http://localhost:11434", "test-model", 10),
            null,
            new ProgressTracker(null, 0));
    gen.generate();
    final List<Column> columns = gen.getValidAiColumns();
    assertThat(columns).extracting(Column::name).contains("name").doesNotContain("parent_id");
  }

  @Test
  void aiBatchSize_fitsManyRowsInOneRequest() throws Exception {
    // batch size = 50, so 50 rows fit in a single request.
    assertThat(countAiRequests(1, 50)).isEqualTo(1);
  }

  @Test
  void aiBatchSize_scalesWithRowCount() throws Exception {
    // batch size = 50, so 100 rows need two requests, 50 need one.
    assertThat(countAiRequests(1, 50)).isEqualTo(1);
    assertThat(countAiRequests(10, 100)).isEqualTo(2);
  }
}
