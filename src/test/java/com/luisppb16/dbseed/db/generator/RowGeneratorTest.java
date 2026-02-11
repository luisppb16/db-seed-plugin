/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.*;

import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.*;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class RowGeneratorTest {

  private static Column intCol(String name) {
    return Column.builder().name(name).jdbcType(Types.INTEGER).build();
  }

  private static Column intPk(String name) {
    return Column.builder().name(name).jdbcType(Types.INTEGER).primaryKey(true).build();
  }

  private static Column varcharCol(String name) {
    return Column.builder().name(name).jdbcType(Types.VARCHAR).length(100).build();
  }

  private RowGenerator generator(Table table, int rowCount) {
    return new RowGenerator(
        table, rowCount, Set.of(), List.of(),
        new Faker(), new HashSet<>(), List.of(), false,
        Set.of(), false, null, 2);
  }

  private RowGenerator generator(Table table, int rowCount, Set<String> excluded) {
    return new RowGenerator(
        table, rowCount, excluded, List.of(),
        new Faker(), new HashSet<>(), List.of(), false,
        Set.of(), false, null, 2);
  }

  // ── Basic ──

  @Test
  void correctRowCount() {
    Table t = new Table("t", List.of(intCol("id"), varcharCol("name")),
        List.of(), List.of(), List.of(), List.of());
    List<Row> rows = generator(t, 10).generate();
    assertThat(rows).hasSize(10);
  }

  @Test
  void emptyColumnsTable() {
    Table t = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of());
    List<Row> rows = generator(t, 5).generate();
    assertThat(rows).isEmpty();
  }

  @Test
  void allColumnsPresent() {
    Table t = new Table("t", List.of(intCol("a"), intCol("b"), varcharCol("c")),
        List.of(), List.of(), List.of(), List.of());
    List<Row> rows = generator(t, 5).generate();
    for (Row row : rows) {
      assertThat(row.values()).containsKeys("a", "b", "c");
    }
  }

  // ── PK uniqueness ──

  @Test
  void integerPk_unique() {
    Table t = new Table("t", List.of(intPk("id")),
        List.of("id"), List.of(), List.of(), List.of());
    List<Row> rows = generator(t, 20).generate();
    Set<Object> pkValues = new HashSet<>();
    for (Row r : rows) {
      pkValues.add(r.values().get("id"));
    }
    assertThat(pkValues).hasSize(rows.size());
  }

  @Test
  void compositePk_unique() {
    Table t = new Table("t", List.of(intPk("a"), intPk("b")),
        List.of("a", "b"), List.of(), List.of(), List.of());
    List<Row> rows = generator(t, 20).generate();
    Set<String> combos = new HashSet<>();
    for (Row r : rows) {
      combos.add(r.values().get("a") + "|" + r.values().get("b"));
    }
    assertThat(combos).hasSize(rows.size());
  }

  // ── Unique keys ──

  @Test
  void noDuplicateUniqueCombinations() {
    Table t = new Table("t",
        List.of(intCol("id"), varcharCol("code")),
        List.of(), List.of(), List.of(), List.of(List.of("code")));
    List<Row> rows = generator(t, 10).generate();
    Set<Object> seen = new HashSet<>();
    for (Row r : rows) {
      seen.add(r.values().get("code"));
    }
    assertThat(seen).hasSize(rows.size());
  }

  // ── Excluded columns ──

  @Test
  void excludedColumn_valueIsNull() {
    Table t = new Table("t", List.of(intCol("id"), intCol("excluded_col")),
        List.of(), List.of(), List.of(), List.of());
    List<Row> rows = generator(t, 5, Set.of("excluded_col")).generate();
    for (Row r : rows) {
      assertThat(r.values().get("excluded_col")).isNull();
    }
  }

  // ── FK columns ──

  @Test
  void fkColumn_nonPk_getsNull() {
    ForeignKey fk = new ForeignKey(null, "parent", Map.of("parent_id", "id"), false);
    Table t = new Table("t", List.of(intCol("id"), intCol("parent_id")),
        List.of("id"), List.of(fk), List.of(), List.of());
    List<Row> rows = generator(t, 5).generate();
    // FK columns that are not PKs should be null (resolved later by ForeignKeyResolver)
    for (Row r : rows) {
      assertThat(r.values().get("parent_id")).isNull();
    }
  }

  // ── Soft delete ──

  @Test
  void softDelete_schemaDefault() {
    Table t = new Table("t", List.of(intCol("id"), intCol("deleted")),
        List.of(), List.of(), List.of(), List.of());
    RowGenerator gen = new RowGenerator(
        t, 5, Set.of(), List.of(),
        new Faker(), new HashSet<>(), List.of(), false,
        Set.of("deleted"), true, null, 2);
    List<Row> rows = gen.generate();
    for (Row r : rows) {
      assertThat(r.values().get("deleted")).isEqualTo(SqlKeyword.DEFAULT);
    }
  }

  @Test
  void softDelete_customValue() {
    Table t = new Table("t", List.of(intCol("id"), intCol("is_active")),
        List.of(), List.of(), List.of(), List.of());
    RowGenerator gen = new RowGenerator(
        t, 5, Set.of(), List.of(),
        new Faker(), new HashSet<>(), List.of(), false,
        Set.of("is_active"), false, "1", 2);
    List<Row> rows = gen.generate();
    for (Row r : rows) {
      assertThat(r.values().get("is_active")).isEqualTo(1);
    }
  }

  // ── Repetition rules ──

  @Test
  void repetitionRules_fixedValuesApplied() {
    Table t = new Table("t", List.of(intCol("id"), varcharCol("type")),
        List.of(), List.of(), List.of(), List.of());
    RepetitionRule rule = new RepetitionRule(3, Map.of("type", "fixed"), Set.of());
    RowGenerator gen = new RowGenerator(
        t, 10, Set.of(), List.of(rule),
        new Faker(), new HashSet<>(), List.of(), false,
        Set.of(), false, null, 2);
    List<Row> rows = gen.generate();
    // First 3 rows should have type=fixed
    long fixedCount = rows.stream()
        .filter(r -> "fixed".equals(r.values().get("type")))
        .count();
    assertThat(fixedCount).isGreaterThanOrEqualTo(3);
  }

  @Test
  void repetitionRules_totalRowsCorrect() {
    Table t = new Table("t", List.of(intCol("id"), varcharCol("name")),
        List.of(), List.of(), List.of(), List.of());
    RepetitionRule rule = new RepetitionRule(3, Map.of(), Set.of());
    RowGenerator gen = new RowGenerator(
        t, 10, Set.of(), List.of(rule),
        new Faker(), new HashSet<>(), List.of(), false,
        Set.of(), false, null, 2);
    List<Row> rows = gen.generate();
    assertThat(rows).hasSize(10);
  }

  // ── getConstraints ──

  @Test
  void getConstraints_returnsMap() {
    Table t = new Table("t", List.of(intCol("val")),
        List.of(), List.of(), List.of("val BETWEEN 1 AND 10"), List.of());
    RowGenerator gen = generator(t, 1);
    gen.generate();
    assertThat(gen.getConstraints()).containsKey("val");
    assertThat(gen.getConstraints().get("val").min()).isEqualTo(1.0);
    assertThat(gen.getConstraints().get("val").max()).isEqualTo(10.0);
  }
}
