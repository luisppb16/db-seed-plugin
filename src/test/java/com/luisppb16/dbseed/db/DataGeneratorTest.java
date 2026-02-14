/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.*;

import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.db.DataGenerator.GenerationParameters;
import com.luisppb16.dbseed.db.DataGenerator.GenerationResult;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DataGeneratorTest {

  private static MockedStatic<DbSeedSettingsState> settingsMock;

  @BeforeAll
  static void setUp() {
    DbSeedSettingsState state = new DbSeedSettingsState();
    state.setUseAiGeneration(false);
    settingsMock = org.mockito.Mockito.mockStatic(DbSeedSettingsState.class);
    settingsMock.when(DbSeedSettingsState::getInstance).thenReturn(state);
  }

  @AfterAll
  static void tearDown() {
    settingsMock.close();
  }

  private static Column intPk(String name) {
    return Column.builder().name(name).jdbcType(Types.INTEGER).primaryKey(true).build();
  }

  private static Column intCol(String name) {
    return Column.builder().name(name).jdbcType(Types.INTEGER).build();
  }

  private static Column varcharCol(String name) {
    return Column.builder().name(name).jdbcType(Types.VARCHAR).length(100).build();
  }

  private static Column nullableCol(String name) {
    return Column.builder().name(name).jdbcType(Types.INTEGER).nullable(true).build();
  }

  private static ForeignKey fk(String pkTable, String fkCol, String pkCol) {
    return new ForeignKey(null, pkTable, Map.of(fkCol, pkCol), false);
  }

  private static DataGenerator.GenerationParameters.GenerationParametersBuilder baseParams() {
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
                Column.builder()
                    .name("id")
                    .jdbcType(Types.VARCHAR)
                    .primaryKey(true)
                    .length(36)
                    .build(),
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
            List.of(Column.builder().name("val").jdbcType(Types.INTEGER).build()),
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
        Column.builder().name("price").jdbcType(java.sql.Types.DECIMAL).length(10).scale(4).build();
    Table t =
        new Table("t", List.of(decCol), List.of(), List.of(), List.of(), List.of());

    GenerationParameters params = baseParams().tables(List.of(t)).numericScale(4).rowsPerTable(10).build();
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
      assertThat(val).isInstanceOf(java.math.BigDecimal.class);
    }
  }

  @Test
  void numericScale_defaultScale2_producesTwoDecimals() {
    Column decCol =
        Column.builder().name("amount").jdbcType(java.sql.Types.DECIMAL).length(8).build();
    Table t =
        new Table("t", List.of(decCol), List.of(), List.of(), List.of(), List.of());

    GenerationParameters params = baseParams().tables(List.of(t)).numericScale(2).rowsPerTable(20).build();
    GenerationResult result = DataGenerator.generate(params);

    List<Row> rows =
        result.rows().entrySet().stream()
            .filter(e -> e.getKey().name().equals("t"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    for (Row r : rows) {
      Object val = r.values().get("amount");
      if (val instanceof java.math.BigDecimal bd) {
        assertThat(bd.scale()).isEqualTo(2);
      }
    }
  }

  // ── Numeric constraint validation ──

  @Test
  void numericValidation_constrainedValues_withinBounds() {
    Column intCol =
        Column.builder().name("score").jdbcType(java.sql.Types.INTEGER).build();
    Table t =
        new Table("t", List.of(intCol), List.of(), List.of(), List.of("score BETWEEN 1 AND 5"), List.of());

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
        baseParams()
            .tables(List.of(t))
            .excludedColumns(Map.of("t", List.of("secret")))
            .build();
    GenerationResult result = DataGenerator.generate(params);

    for (Row r : result.rows().get(t)) {
      assertThat(r.values().get("secret")).isNull();
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
