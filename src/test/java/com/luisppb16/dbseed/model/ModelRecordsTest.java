/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luisppb16.dbseed.db.PendingUpdate;
import com.luisppb16.dbseed.db.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    final List<Column> mutable = new ArrayList<>();
    mutable.add(new Column("a", 4, null, true, false, false, 0, 0, null, null, Set.of()));
    final Table t = new Table("t", mutable, List.of(), List.of(), List.of(), List.of());
    // El IDE recomienda que la lambda tenga solo una invocación que pueda lanzar excepción
    assertThatThrownBy(() -> { t.columns().add(new Column("b", 4, null, true, false, false, 0, 0, null, null, Set.of())); })
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void table_columnLookupByName() {
    final Column c = new Column("age", 4, null, true, false, false, 0, 0, null, null, Set.of());
    final Table t = new Table("t", List.of(c), List.of(), List.of(), List.of(), List.of());
    assertThat(t.column("AGE")).isSameAs(c);
  }

  @Test
  void table_columnLookup_nonExistentReturnsNull() {
    final Table t = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of());
    assertThat(t.column("missing")).isNull();
  }

  @Test
  void table_fkColumnNames() {
    final ForeignKey fk = new ForeignKey(null, "parent", Map.of("parent_id", "id"), false);
    final Table t = new Table("t", List.of(), List.of(), List.of(fk), List.of(), List.of());
    assertThat(t.fkColumnNames()).containsExactly("parent_id");
  }

  @Test
  void table_fkColumnNames_empty() {
    final Table t = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of());
    assertThat(t.fkColumnNames()).isEmpty();
  }

  // ── Column ──

  @Test
  void column_nullName_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Column(null, 4, null, true, false, false, 0, 0, null, null, Set.of()));
  }

  @Test
  void column_hasAllowedValues_true() {
    final Column c = new Column("x", 4, null, true, false, false, 0, 0, null, null, Set.of("a"));
    assertThat(c.hasAllowedValues()).isTrue();
  }

  @Test
  void column_hasAllowedValues_false() {
    final Column c = new Column("x", 4, null, true, false, false, 0, 0, null, null, Set.of());
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
    assertThatNullPointerException().isThrownBy(() -> new ForeignKey("fk1", "parent", null, false));
  }

  @Test
  void fk_blankName_generatesName() {
    final ForeignKey fk = new ForeignKey("", "parent", Map.of("col1", "id"), false);
    assertThat(fk.name()).startsWith("fk_parent");
  }

  @Test
  void fk_providedName_kept() {
    final ForeignKey fk = new ForeignKey("my_fk", "parent", Map.of("col1", "id"), false);
    assertThat(fk.name()).isEqualTo("my_fk");
  }

  @Test
  void fk_immutableMapping() {
    final Map<String, String> mutable = new HashMap<>(Map.of("a", "b"));
    final ForeignKey fk = new ForeignKey("fk", "p", mutable, false);
    // El IDE recomienda que la lambda tenga solo una invocación que pueda lanzar excepción
    assertThatThrownBy(() -> { fk.columnMapping().put("c", "d"); })
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── Row / PendingUpdate field accessibility ──

  @Test
  void row_valuesAccessible() {
    final Row row = new Row(Map.of("id", 1));
    assertThat(row.values()).containsEntry("id", 1);
  }

  @Test
  void pendingUpdate_fieldsAccessible() {
    final PendingUpdate pu = new PendingUpdate("t", Map.of("fk", 1), Map.of("pk", 2));
    assertThat(pu.table()).isEqualTo("t");
    assertThat(pu.fkValues()).containsEntry("fk", 1);
    assertThat(pu.pkValues()).containsEntry("pk", 2);
  }
}