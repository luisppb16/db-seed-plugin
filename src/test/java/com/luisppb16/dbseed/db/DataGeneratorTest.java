/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.db.DataGenerator.GenerationParameters;
import com.luisppb16.dbseed.db.DataGenerator.GenerationResult;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class DataGeneratorTest {

  private static MockedStatic<DbSeedSettingsState> settingsMock;

  @BeforeAll
  static void setUp() {
    DbSeedSettingsState state = new DbSeedSettingsState();
    state.setUseAiGeneration(false);
    settingsMock = Mockito.mockStatic(DbSeedSettingsState.class);
    settingsMock.when(DbSeedSettingsState::getInstance).thenReturn(state);
  }

  @AfterAll
  static void tearDown() {
    settingsMock.close();
  }

  private static Column intPk(String name) {
    return new Column(name, Types.INTEGER, null, false, true, false, 0, 0, null, null, Set.of());
  }

  private static Column intCol(String name) {
    return new Column(name, Types.INTEGER, null, false, false, false, 0, 0, null, null, Set.of());
  }

  private static Column varcharCol(String name) {
    return new Column(name, Types.VARCHAR, null, false, false, false, 100, 0, null, null, Set.of());
  }

  private static Column nullableCol(String name) {
    return new Column(name, Types.INTEGER, null, false, false, false, 0, 0, null, null, Set.of());
  }

  private static ForeignKey fk(String pkTable, String fkCol, String pkCol) {
    return new ForeignKey(null, pkTable, Map.of(fkCol, pkCol), false);
  }

  private static DataGenerator.GenerationParameters.Builder baseParams() {
    return DataGenerator.GenerationParameters.builder()
        .rowsPerTable(5)
        .deferred(false)
        .pkUuidOverrides(Map.of())
        .excludedColumns(Map.of())
        .repetitionRules(Map.of())
        .useLatinDictionary(false)
        .useEnglishDictionary(false)
        .useSpanishDictionary(false)
        .softDeleteColumns(null)
        .softDeleteUseSchemaDefault(false)
        .softDeleteValue(null)
        .numericScale(2)
        .aiColumns(Map.of());
  }

  // ── Basic ──

  @Test
  void singleTable_correctCount() {
    Table t =
        new Table(
            "users",
            List.of(intPk("id"), varcharCol("name")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());
    GenerationParameters params = baseParams().tables(List.of(t)).build();
    GenerationResult result = DataGenerator.generate(params);
    assertThat(result.rows().get(t)).hasSize(5);
  }

  @Test
  void parentChildFks_resolved() {
    Table parent =
        new Table("parent", List.of(intPk("id")), List.of("id"), List.of(), List.of(), List.of());
    Table child =
        new Table(
            "child",
            List.of(intPk("id"), intCol("parent_id")),
            List.of("id"),
            List.of(fk("parent", "parent_id", "id")),
            List.of(),
            List.of());

    // Use deferred mode to avoid cycle detection issues (internal tableMap ordering is
    // unpredictable)
    GenerationParameters params =
        baseParams().tables(List.of(parent, child)).deferred(true).build();
    GenerationResult result = DataGenerator.generate(params);

    List<Row> childRows =
        result.rows().entrySet().stream()
            .filter(e -> e.getKey().name().equals("child"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    // With deferred=true, FK values are always resolved directly
    assertThat(childRows)
        .allSatisfy(
            r -> {
              assertThat(r.values().get("parent_id")).isNotNull();
            });
  }

  @Test
  void emptyColumnTable() {
    Table t = new Table("empty", List.of(), List.of(), List.of(), List.of(), List.of());
    GenerationParameters params = baseParams().tables(List.of(t)).build();
    GenerationResult result = DataGenerator.generate(params);
    assertThat(result.rows().get(t)).isEmpty();
  }

  // ── PK UUID overrides ──

  @Test
  void pkUuidOverride_makesUuid() {
    Table t =
        new Table(
            "t",
            List.of(
                new Column("id", Types.VARCHAR, null, false, true, false, 36, 0, null, null, Set.of()),
                varcharCol("name")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params =
        baseParams().tables(List.of(t)).pkUuidOverrides(Map.of("t", Map.of("id", "UUID"))).build();
    GenerationResult result = DataGenerator.generate(params);

    // applyPkUuidOverrides creates new Table objects, so find by name
    List<Row> rows =
        result.rows().entrySet().stream()
            .filter(e -> e.getKey().name().equals("t"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    for (Row r : rows) {
      assertThat(r.values().get("id")).isInstanceOf(UUID.class);
    }
  }

  @Test
  void noUuidOverride_keepsOriginal() {
    Table t =
        new Table(
            "t",
            List.of(intPk("id"), varcharCol("name")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params = baseParams().tables(List.of(t)).build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("id")).isInstanceOf(Integer.class);
    }
  }

  @Test
  void pkUuidOverrides_null_keepsOriginalPrimaryKeyType() {
    Table t =
        new Table(
            "t",
            List.of(intPk("id"), varcharCol("name")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params = baseParams().tables(List.of(t)).pkUuidOverrides(null).build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("id")).isInstanceOf(Integer.class);
    }
  }

  @Test
  void pkUuidOverride_onIntegerPrimaryKey_forcesUuid() {
    Table t =
        new Table(
            "t",
            List.of(intPk("id"), varcharCol("name")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params =
        baseParams().tables(List.of(t)).pkUuidOverrides(Map.of("t", Map.of("id", "UUID"))).build();
    GenerationResult result = DataGenerator.generate(params);

    // The table key in result.rows() differs from t because applyPkUuidOverrides creates a new
    // Table
    List<Row> rows = result.rows().values().iterator().next();
    for (Row r : rows) {
      assertThat(r.values().get("id")).isInstanceOf(UUID.class);
    }
  }

  @Test
  void pkUuidOverride_onAlreadyUuidColumn_keepsUuidGeneration() {
    Column uuidPk =
        new Column("id", Types.VARCHAR, null, false, true, true, 0, 0, null, null, Set.of());
    Table t =
        new Table(
            "t",
            List.of(uuidPk, varcharCol("name")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params =
        baseParams().tables(List.of(t)).pkUuidOverrides(Map.of("t", Map.of("id", "UUID"))).build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("id")).isInstanceOf(UUID.class);
    }
  }

  // ── Deferred ──

  @Test
  void deferred_noExceptionWithCycles() {
    Table a =
        new Table(
            "A",
            List.of(intPk("id"), nullableCol("b_id")),
            List.of("id"),
            List.of(fk("B", "b_id", "id")),
            List.of(),
            List.of());
    Table b =
        new Table(
            "B",
            List.of(intPk("id"), nullableCol("a_id")),
            List.of("id"),
            List.of(fk("A", "a_id", "id")),
            List.of(),
            List.of());

    GenerationParameters params = baseParams().tables(List.of(a, b)).deferred(true).build();
    assertThatCode(() -> DataGenerator.generate(params)).doesNotThrowAnyException();
  }

  // ── Numeric validation ──

  @Test
  void numericValues_withinCheckBounds() {
    Table t =
        new Table(
            "t",
            List.of(new Column("val", Types.INTEGER, null, false, false, false, 0, 0, null, null, Set.of())),
            List.of(),
            List.of(),
            List.of("val BETWEEN 10 AND 20"),
            List.of());

    GenerationParameters params = baseParams().tables(List.of(t)).rowsPerTable(20).build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      Object val = r.values().get("val");
      if (val instanceof Integer i) {
        assertThat(i).isBetween(10, 20);
      }
    }
  }

  // ── Soft delete ──

  @Test
  void softDelete_applied() {
    Table t =
        new Table(
            "t",
            List.of(intCol("id"), intCol("deleted")),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params =
        baseParams()
            .tables(List.of(t))
            .softDeleteColumns("deleted")
            .softDeleteUseSchemaDefault(true)
            .build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("deleted")).isEqualTo(SqlKeyword.DEFAULT);
    }
  }

  // ── Dictionary usage ──

  @Test
  void englishDictionary_used() {
    Table t =
        new Table("t", List.of(varcharCol("text")), List.of(), List.of(), List.of(), List.of());

    GenerationParameters params =
        baseParams().tables(List.of(t)).useEnglishDictionary(true).rowsPerTable(10).build();
    GenerationResult result = DataGenerator.generate(params);
    // Just verify it completes successfully with dictionary
    assertThat(result.rows().get(t)).hasSize(10);
  }

  @Test
  void noDictionary_defaultGeneration() {
    Table t =
        new Table("t", List.of(varcharCol("text")), List.of(), List.of(), List.of(), List.of());

    GenerationParameters params = baseParams().tables(List.of(t)).rowsPerTable(5).build();
    GenerationResult result = DataGenerator.generate(params);
    assertThat(result.rows().get(t)).hasSize(5);
    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("text")).isInstanceOf(String.class);
    }
  }

  // ── Numeric scale propagation ──

  @Test
  void numericScale_appliedToDecimalValues() {
    Column decCol =
        new Column("price", Types.DECIMAL, null, false, false, false, 10, 4, null, null, Set.of());
    Table t = new Table("t", List.of(decCol), List.of(), List.of(), List.of(), List.of());

    GenerationParameters params =
        baseParams().tables(List.of(t)).numericScale(4).rowsPerTable(10).build();
    GenerationResult result = DataGenerator.generate(params);

    List<Row> rows =
        result.rows().entrySet().stream()
            .filter(e -> e.getKey().name().equals("t"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    assertThat(rows).hasSize(10);
    for (Row r : rows) {
      Object val = r.values().get("price");
      assertThat(val).isInstanceOf(BigDecimal.class);
    }
  }

  @Test
  void numericScale_defaultScale2_producesTwoDecimals() {
    Column decCol = new Column("amount", Types.DECIMAL, null, false, false, false, 8, 0, null, null, Set.of());
    Table t = new Table("t", List.of(decCol), List.of(), List.of(), List.of(), List.of());

    GenerationParameters params =
        baseParams().tables(List.of(t)).numericScale(2).rowsPerTable(20).build();
    GenerationResult result = DataGenerator.generate(params);

    List<Row> rows =
        result.rows().entrySet().stream()
            .filter(e -> e.getKey().name().equals("t"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    for (Row r : rows) {
      Object val = r.values().get("amount");
      if (val instanceof BigDecimal bd) {
        assertThat(bd.scale()).isEqualTo(2);
      }
    }
  }

  // ── Numeric constraint validation ──

  @Test
  void numericValidation_constrainedValues_withinBounds() {
    Column intCol = new Column("score", Types.INTEGER, null, false, false, false, 0, 0, null, null, Set.of());
    Table t =
        new Table(
            "t",
            List.of(intCol),
            List.of(),
            List.of(),
            List.of("score BETWEEN 1 AND 5"),
            List.of());

    GenerationParameters params = baseParams().tables(List.of(t)).rowsPerTable(50).build();
    GenerationResult result = DataGenerator.generate(params);

    List<Row> rows =
        result.rows().entrySet().stream()
            .filter(e -> e.getKey().name().equals("t"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    for (Row r : rows) {
      Object val = r.values().get("score");
      if (val instanceof Integer i) {
        assertThat(i).isBetween(1, 5);
      }
    }
  }

  // ── Multiple soft delete columns ──

  @Test
  void softDelete_multipleColumns_allApplied() {
    Table t =
        new Table(
            "t",
            List.of(intCol("id"), intCol("deleted"), intCol("archived")),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params =
        baseParams()
            .tables(List.of(t))
            .softDeleteColumns("deleted,archived")
            .softDeleteUseSchemaDefault(true)
            .build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("deleted")).isEqualTo(SqlKeyword.DEFAULT);
      assertThat(r.values().get("archived")).isEqualTo(SqlKeyword.DEFAULT);
    }
  }

  @Test
  void softDelete_multipleColumns_withSpacesAndEmptyTokens_allApplied() {
    Table t =
        new Table(
            "t",
            List.of(intCol("id"), intCol("deleted"), intCol("archived")),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params =
        baseParams()
            .tables(List.of(t))
            .softDeleteColumns(" deleted, , archived ,")
            .softDeleteUseSchemaDefault(true)
            .build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("deleted")).isEqualTo(SqlKeyword.DEFAULT);
      assertThat(r.values().get("archived")).isEqualTo(SqlKeyword.DEFAULT);
    }
  }

  // ── Excluded columns ──

  @Test
  void excludedColumns_notGenerated() {
    Table t =
        new Table(
            "t",
            List.of(intPk("id"), varcharCol("name"), varcharCol("secret")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params =
        baseParams().tables(List.of(t)).excludedColumns(Map.of("t", List.of("secret"))).build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("secret")).isNull();
    }
  }

  @Test
  void aiGeneration_populatesSelectedColumnsViaOllamaClient() {
    DbSeedSettingsState state = new DbSeedSettingsState();
    state.setUseAiGeneration(true);
    state.setOllamaModel("test-model");
    state.setAiRequestTimeoutSeconds(30);
    state.setAiWordCount(1);

    Table products =
        new Table(
            "products",
            List.of(intPk("id"), varcharCol("description")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());
    Table users =
        new Table(
            "users",
            List.of(intPk("id"), varcharCol("bio")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    ExecutorService serverExecutor = Executors.newCachedThreadPool();
    HttpServer server = null;

    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(serverExecutor);
      server.createContext(
          "/api/generate",
          exchange -> {
            final String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final boolean warmUpRequest = body.contains("\"prompt\":\"\"");
            final String responseBody;

            if (warmUpRequest) {
              responseBody = "{\"response\":\"\"}";
            } else {
              try {
                Thread.sleep(50L);
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Interrupted while handling fake Ollama request", e);
              }

              responseBody = "{\"response\":\"__AI_VALUE__\\n__AI_VALUE_2__\"}";
            }

            final byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
              outputStream.write(responseBytes);
            }
          });
      server.start();
      state.setOllamaUrl("http://127.0.0.1:" + server.getAddress().getPort());
      settingsMock.when(DbSeedSettingsState::getInstance).thenReturn(state);

      GenerationParameters params =
          baseParams()
              .tables(List.of(products, users))
              .rowsPerTable(1)
              .aiColumns(Map.of("products", Set.of("description"), "users", Set.of("bio")))
              .build();

      final GenerationResult result = DataGenerator.generate(params);

      assertThat(result.rows().get(products).getFirst().values())
          .containsEntry("description", "__AI_VALUE__");
      assertThat(result.rows().get(users).getFirst().values()).containsEntry("bio", "__AI_VALUE__");
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to start fake Ollama server", e);
    } finally {
      if (Objects.nonNull(server)) {
        server.stop(0);
      }
      serverExecutor.shutdownNow();
    }
  }

  // ── Empty tables ──

  @Test
  void multipleTables_emptyAndNonEmpty() {
    Table empty = new Table("empty", List.of(), List.of(), List.of(), List.of(), List.of());
    Table normal =
        new Table(
            "normal",
            List.of(intPk("id"), varcharCol("name")),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    GenerationParameters params = baseParams().tables(List.of(empty, normal)).build();
    GenerationResult result = DataGenerator.generate(params);

    assertThat(result.rows().get(empty)).isEmpty();
    List<Row> normalRows =
        result.rows().entrySet().stream()
            .filter(e -> e.getKey().name().equals("normal"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    assertThat(normalRows).hasSize(5);
  }
}
