/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ModelRecordsTest {

  // ── Table ──

  @Test
  void table_nullName_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Table(null, List.of(), List.of(), List.of(), List.of(), List.of()));
  }

  @Test
  void table_nullColumns_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Table("t", null, List.of(), List.of(), List.of(), List.of()));
  }

  @Test
  void table_nullPk_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Table("t", List.of(), null, List.of(), List.of(), List.of()));
  }

  @Test
  void table_nullFks_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Table("t", List.of(), List.of(), null, List.of(), List.of()));
  }

  @Test
  void table_nullChecks_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Table("t", List.of(), List.of(), List.of(), null, List.of()));
  }

  @Test
  void table_nullUniqueKeys_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Table("t", List.of(), List.of(), List.of(), List.of(), null));
  }

  @Test
  void table_defensiveCopy_columnsImmutable() {
    List<Column> mutable = new ArrayList<>();
    mutable.add(Column.builder().name("a").jdbcType(4).build());
    Table t = new Table("t", mutable, List.of(), List.of(), List.of(), List.of());
    assertThatThrownBy(() -> t.columns().add(Column.builder().name("b").jdbcType(4).build()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void table_columnLookupByName() {
    Column c = Column.builder().name("age").jdbcType(4).build();
    Table t = new Table("t", List.of(c), List.of(), List.of(), List.of(), List.of());
    assertThat(t.column("AGE")).isSameAs(c);
  }

  @Test
  void table_columnLookup_nonExistentReturnsNull() {
    Table t = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of());
    assertThat(t.column("missing")).isNull();
  }

  @Test
  void table_fkColumnNames() {
    ForeignKey fk = new ForeignKey(null, "parent", Map.of("parent_id", "id"), false);
    Table t = new Table("t", List.of(), List.of(), List.of(fk), List.of(), List.of());
    assertThat(t.fkColumnNames()).containsExactly("parent_id");
  }

  @Test
  void table_fkColumnNames_empty() {
    Table t = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of());
    assertThat(t.fkColumnNames()).isEmpty();
  }

  // ── Column ──

  @Test
  void column_nullName_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> Column.builder().name(null).jdbcType(4).build());
  }

  @Test
  void column_hasAllowedValues_true() {
    Column c = Column.builder().name("x").jdbcType(4).allowedValues(Set.of("a")).build();
    assertThat(c.hasAllowedValues()).isTrue();
  }

  @Test
  void column_hasAllowedValues_false() {
    Column c = Column.builder().name("x").jdbcType(4).build();
    assertThat(c.hasAllowedValues()).isFalse();
  }

  // ── ForeignKey ──

  @Test
  void fk_nullPkTable_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ForeignKey("fk1", null, Map.of("a", "b"), false));
  }

  @Test
  void fk_nullColumnMapping_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ForeignKey("fk1", "parent", null, false));
  }

  @Test
  void fk_blankName_generatesName() {
    ForeignKey fk = new ForeignKey("", "parent", Map.of("col1", "id"), false);
    assertThat(fk.name()).startsWith("fk_parent");
  }

  @Test
  void fk_providedName_kept() {
    ForeignKey fk = new ForeignKey("my_fk", "parent", Map.of("col1", "id"), false);
    assertThat(fk.name()).isEqualTo("my_fk");
  }

  @Test
  void fk_immutableMapping() {
    Map<String, String> mutable = new HashMap<>(Map.of("a", "b"));
    ForeignKey fk = new ForeignKey("fk", "p", mutable, false);
    assertThatThrownBy(() -> fk.columnMapping().put("c", "d"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── Row / PendingUpdate field accessibility ──

  @Test
  void row_valuesAccessible() {
    com.luisppb16.dbseed.db.Row row = new com.luisppb16.dbseed.db.Row(Map.of("id", 1));
    assertThat(row.values()).containsEntry("id", 1);
  }

  @Test
  void pendingUpdate_fieldsAccessible() {
    com.luisppb16.dbseed.db.PendingUpdate pu = new com.luisppb16.dbseed.db.PendingUpdate("t", Map.of("fk", 1), Map.of("pk", 2));
    assertThat(pu.table()).isEqualTo("t");
    assertThat(pu.fkValues()).containsEntry("fk", 1);
    assertThat(pu.pkValues()).containsEntry("pk", 2);
  }
}
