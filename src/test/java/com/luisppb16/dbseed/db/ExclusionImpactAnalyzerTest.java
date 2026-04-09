/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * ****************************************************************************
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExclusionImpactAnalyzerTest {

  @Test
  void analyze_warnsWhenExcludingPrimaryKeyColumn() {
    final Table users =
        new Table(
            "users",
            List.of(
                Column.builder().name("id").jdbcType(4).primaryKey(true).nullable(false).build(),
                Column.builder().name("name").jdbcType(12).nullable(false).build()),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    final ExclusionImpactAnalyzer.Result result =
        ExclusionImpactAnalyzer.analyze(List.of(users), Map.of("users", Set.of("id")), Set.of());

    assertThat(result.hasWarnings()).isTrue();
    assertThat(result.risks()).anyMatch(risk -> risk.contains("PK users.id"));
  }

  @Test
  void analyze_warnsWhenParentTableExcludedAndChildHasNonNullableFk() {
    final Table parent =
        new Table(
            "company",
            List.of(Column.builder().name("id").jdbcType(4).primaryKey(true).nullable(false).build()),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    final Table child =
        new Table(
            "employee",
            List.of(
                Column.builder().name("id").jdbcType(4).primaryKey(true).nullable(false).build(),
                Column.builder().name("company_id").jdbcType(4).nullable(false).build()),
            List.of("id"),
            List.of(new ForeignKey("fk_employee_company", "company", Map.of("company_id", "id"), false)),
            List.of(),
            List.of());

    final ExclusionImpactAnalyzer.Result result =
        ExclusionImpactAnalyzer.analyze(List.of(parent, child), Map.of(), Set.of("company"));

    assertThat(result.hasWarnings()).isTrue();
    assertThat(result.risks()).anyMatch(risk -> risk.contains("employee") && risk.contains("company"));
    assertThat(result.recommendations())
        .anyMatch(recommendation -> recommendation.contains("excluye tambien employee"));
  }

  @Test
  void analyze_noWarningsWhenNothingExcluded() {
    final Table users =
        new Table(
            "users",
            List.of(
                Column.builder().name("id").jdbcType(4).primaryKey(true).nullable(false).build(),
                Column.builder().name("name").jdbcType(12).nullable(true).build()),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    final ExclusionImpactAnalyzer.Result result =
        ExclusionImpactAnalyzer.analyze(List.of(users), Map.of(), Set.of());

    assertThat(result.hasWarnings()).isFalse();
    assertThat(result.risks()).isEmpty();
  }

  @Test
  void analyze_warnsWhenReferencedParentColumnIsExcluded() {
    final Table parent =
        new Table(
            "palette",
            List.of(
                Column.builder().name("id").jdbcType(4).primaryKey(true).nullable(false).build(),
                Column.builder().name("hex").jdbcType(12).nullable(false).build()),
            List.of("id"),
            List.of(),
            List.of(),
            List.of());

    final Table child =
        new Table(
            "theme",
            List.of(
                Column.builder().name("id").jdbcType(4).primaryKey(true).nullable(false).build(),
                Column.builder().name("palette_hex").jdbcType(12).nullable(false).build()),
            List.of("id"),
            List.of(new ForeignKey("fk_theme_palette", "palette", Map.of("palette_hex", "hex"), false)),
            List.of(),
            List.of());

    final ExclusionImpactAnalyzer.Result result =
        ExclusionImpactAnalyzer.analyze(
            List.of(parent, child), Map.of("palette", Set.of("hex")), Set.of());

    assertThat(result.hasWarnings()).isTrue();
    assertThat(result.risks())
        .anyMatch(risk -> risk.contains("palette.hex") && risk.contains("theme.palette_hex"));
    assertThat(result.recommendations())
        .anyMatch(recommendation -> recommendation.contains("inclu") && recommendation.contains("palette.hex"));
  }
}

