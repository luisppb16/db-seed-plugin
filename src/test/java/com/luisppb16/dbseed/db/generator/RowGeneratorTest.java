/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.db.ProgressTracker;
import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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

  // ── Basic ──

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

  // ── PK uniqueness ──

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

  // ── Unique keys ──

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

  // ── Excluded columns ──

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

  // ── FK columns ──

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

  // ── Soft delete ──

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

  // ── Repetition rules ──

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

  // ── getConstraints ──

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
}
