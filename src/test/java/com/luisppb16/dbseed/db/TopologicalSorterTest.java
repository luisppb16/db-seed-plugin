/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.db.TopologicalSorter.SortResult;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TopologicalSorterTest {

  // ── Helpers ──

  private static Table tbl(final String name, final ForeignKey... fks) {
    return new Table(
        name,
        List.of(new Column("id", 4, null, true, false, false, 0, 0, null, null, Set.of())),
        List.of(),
        List.of(fks),
        List.of(),
        List.of());
  }

  private static ForeignKey fk(final String pkTable) {
    return new ForeignKey(null, pkTable, Map.of("fk_col", "id"), false);
  }

  private static ForeignKey nullableFk(final String pkTable) {
    return new ForeignKey(null, pkTable, Map.of("fk_col", "id"), false);
  }

  private static Table tblWithNullableCol(final String name, final ForeignKey... fks) {
    return new Table(
        name,
        List.of(
            new Column("id", 4, null, true, false, false, 0, 0, null, null, Set.of()),
            new Column("fk_col", 4, null, true, false, false, 0, 0, null, null, Set.of())),
        List.of(),
        List.of(fks),
        List.of(),
        List.of());
  }

  private static Table tblWithNonNullableCol(final String name, final ForeignKey... fks) {
    return new Table(
        name,
        List.of(
            new Column("id", 4, null, true, false, false, 0, 0, null, null, Set.of()),
            new Column("fk_col", 4, null, false, false, false, 0, 0, null, null, Set.of())),
        List.of(),
        List.of(fks),
        List.of(),
        List.of());
  }

  // ── sort tests ──

  @Test
  void sort_singleTable() {
    final SortResult result = TopologicalSorter.sort(List.of(tbl("A")));
    assertThat(result.ordered()).containsExactly("A");
    assertThat(result.cycles()).isEmpty();
  }

  @Test
  void sort_linearChain() {
    // A -> B -> C  (C depends on B, B depends on A)
    final SortResult result =
        TopologicalSorter.sort(List.of(tbl("A"), tbl("B", fk("A")), tbl("C", fk("B"))));
    assertThat(result.ordered()).containsExactly("A", "B", "C");
    assertThat(result.cycles()).isEmpty();
  }

  @Test
  void sort_diamondDependency() {
    // A is parent; B and C depend on A; D depends on B and C
    final SortResult result =
        TopologicalSorter.sort(
            List.of(tbl("A"), tbl("B", fk("A")), tbl("C", fk("A")), tbl("D", fk("B"), fk("C"))));
    final int idxA = result.ordered().indexOf("A");
    final int idxB = result.ordered().indexOf("B");
    final int idxC = result.ordered().indexOf("C");
    final int idxD = result.ordered().indexOf("D");
    assertThat(idxA).isLessThan(idxB);
    assertThat(idxA).isLessThan(idxC);
    assertThat(idxB).isLessThan(idxD);
    assertThat(idxC).isLessThan(idxD);
  }

  @Test
  void sort_independentTables_sortedWithinSccGroup() {
    // Independent tables are each their own SCC; within each SCC group they are sorted
    // but the SCC discovery order depends on graph traversal order
    final SortResult result = TopologicalSorter.sort(List.of(tbl("C"), tbl("A"), tbl("B")));
    assertThat(result.ordered()).containsExactlyInAnyOrder("A", "B", "C");
    assertThat(result.cycles()).isEmpty();
  }

  @Test
  void sort_emptyList() {
    final SortResult result = TopologicalSorter.sort(List.of());
    assertThat(result.ordered()).isEmpty();
    assertThat(result.cycles()).isEmpty();
  }

  @Test
  void sort_fkToNonexistentTable() {
    // FK references a table not in the list - should not crash
    final SortResult result = TopologicalSorter.sort(List.of(tbl("A", fk("missing"))));
    assertThat(result.ordered()).containsExactly("A");
  }

  // ── Cycle detection ──

  @Test
  void sort_twoTableCycle() {
    final SortResult result = TopologicalSorter.sort(List.of(tbl("A", fk("B")), tbl("B", fk("A"))));
    assertThat(result.cycles()).hasSize(1);
    assertThat(result.cycles().get(0)).containsExactlyInAnyOrder("A", "B");
  }

  @Test
  void sort_selfReferencingCycle() {
    final SortResult result = TopologicalSorter.sort(List.of(tbl("A", fk("A"))));
    assertThat(result.cycles()).hasSize(1);
    assertThat(result.cycles().get(0)).containsExactly("A");
  }

  @Test
  void sort_threeTableCycle() {
    final SortResult result =
        TopologicalSorter.sort(List.of(tbl("A", fk("B")), tbl("B", fk("C")), tbl("C", fk("A"))));
    assertThat(result.cycles()).hasSize(1);
    assertThat(result.cycles().get(0)).containsExactlyInAnyOrder("A", "B", "C");
  }

  @Test
  void sort_mixedCyclicAndAcyclic() {
    // D is independent; A <-> B form a cycle
    final SortResult result =
        TopologicalSorter.sort(List.of(tbl("A", fk("B")), tbl("B", fk("A")), tbl("D")));
    assertThat(result.cycles()).hasSize(1);
    assertThat(result.ordered()).contains("D", "A", "B");
  }

  @Test
  void sort_multipleDisjointCycles() {
    // A <-> B and C <-> D
    final SortResult result =
        TopologicalSorter.sort(
            List.of(tbl("A", fk("B")), tbl("B", fk("A")), tbl("C", fk("D")), tbl("D", fk("C"))));
    assertThat(result.cycles()).hasSize(2);
  }

  // ── requiresDeferredDueToNonNullableCycles ──

  @Test
  void requiresDeferred_nonNullableFkInCycle_true() {
    final Table a = tblWithNonNullableCol("A", fk("B"));
    final Table b = tblWithNonNullableCol("B", fk("A"));
    final Map<String, Table> tableMap = Map.of("A", a, "B", b);
    final SortResult sort = TopologicalSorter.sort(List.of(a, b));
    assertThat(TopologicalSorter.requiresDeferredDueToNonNullableCycles(sort, tableMap)).isTrue();
  }

  @Test
  void requiresDeferred_allNullable_false() {
    final Table a = tblWithNullableCol("A", fk("B"));
    final Table b = tblWithNullableCol("B", fk("A"));
    final Map<String, Table> tableMap = Map.of("A", a, "B", b);
    final SortResult sort = TopologicalSorter.sort(List.of(a, b));
    assertThat(TopologicalSorter.requiresDeferredDueToNonNullableCycles(sort, tableMap)).isFalse();
  }

  @Test
  void requiresDeferred_noCycles_false() {
    final Table a = tbl("A");
    final Table b = tbl("B", fk("A"));
    final Map<String, Table> tableMap = Map.of("A", a, "B", b);
    final SortResult sort = TopologicalSorter.sort(List.of(a, b));
    assertThat(TopologicalSorter.requiresDeferredDueToNonNullableCycles(sort, tableMap)).isFalse();
  }

  @Test
  void requiresDeferred_mixedNullableNonNullable_true() {
    final Table a = tblWithNullableCol("A", fk("B"));
    final Table b = tblWithNonNullableCol("B", fk("A"));
    final Map<String, Table> tableMap = Map.of("A", a, "B", b);
    final SortResult sort = TopologicalSorter.sort(List.of(a, b));
    assertThat(TopologicalSorter.requiresDeferredDueToNonNullableCycles(sort, tableMap)).isTrue();
  }
}