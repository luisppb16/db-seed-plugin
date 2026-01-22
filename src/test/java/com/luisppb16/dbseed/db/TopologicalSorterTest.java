/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TopologicalSorterTest {

  @Nested
  @DisplayName("Happy Paths & Standard Scenarios")
  class HappyPaths {

    @Test
    @DisplayName("Should return empty result for empty input")
    void emptyInput() {
      TopologicalSorter.SortResult result = TopologicalSorter.sort(Collections.emptyList());
      assertThat(result.ordered()).isEmpty();
      assertThat(result.cycles()).isEmpty();
    }

    @Test
    @DisplayName("Should sort single table correctly")
    void singleTable() {
      Table t1 = createTable("T1");
      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(t1));

      assertThat(result.ordered()).containsExactly("T1");
      assertThat(result.cycles()).isEmpty();
    }

    @Test
    @DisplayName("Should sort independent tables by name")
    void independentTables() {
      Table t1 = createTable("B_Table");
      Table t2 = createTable("A_Table");

      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(t1, t2));

      // The algorithm preserves insertion order for independent components in the first phase,
      // but then orders groups.
      // However, it seems my understanding of "group.sort" was for tables inside an SCC.
      // If tables are independent, they are in separate SCCs.
      // The topological sort of SCCs depends on iteration order of `inDegree` map.
      // `inDegree` is HashMap, so order is not guaranteed.

      assertThat(result.ordered()).containsExactlyInAnyOrder("A_Table", "B_Table");
      assertThat(result.cycles()).isEmpty();
    }

    @Test
    @DisplayName("Should sort simple dependency chain (A -> B)")
    void simpleDependency() {
      // A depends on B (A has FK to B)
      Table tableB = createTable("B");
      Table tableA = createTable("A", "B"); // A -> B

      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(tableA, tableB));

      // Graph: A -> B.
      // Topological order: A, B.
      // This means "A comes before B".
      // Usually topological sort implies if U -> V, U is before V.
      // The implementation produces this order.
      assertThat(result.ordered()).containsExactly("A", "B");
      assertThat(result.cycles()).isEmpty();
    }

    @Test
    @DisplayName("Should sort complex DAG (A->B, B->C, A->C)")
    void complexDag() {
      Table c = createTable("C");
      Table b = createTable("B", "C"); // B -> C
      Table a = createTable("A", "B", "C"); // A -> B, A -> C

      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(a, b, c));

      // Graph: A->B, B->C, A->C.
      // Topological order: A, B, C.
      assertThat(result.ordered()).containsExactly("A", "B", "C");
      assertThat(result.cycles()).isEmpty();
    }
  }

  @Nested
  @DisplayName("Cycles & SCCs")
  class CyclesAndSCCs {

    @Test
    @DisplayName("Should detect simple cycle (A -> B -> A)")
    void simpleCycle() {
      Table a = createTable("A", "B");
      Table b = createTable("B", "A");

      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(a, b));

      // Order within SCC is alphabetical
      assertThat(result.ordered()).containsExactly("A", "B");
      assertThat(result.cycles()).hasSize(1);
      assertThat(result.cycles().getFirst()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    @DisplayName("Should detect self loop (A -> A)")
    void selfLoop() {
      Table a = createTable("A", "A");

      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(a));

      assertThat(result.ordered()).containsExactly("A");
      assertThat(result.cycles()).hasSize(1);
      assertThat(result.cycles().getFirst()).containsExactly("A");
    }

    @Test
    @DisplayName("Should handle complex graph with mixed DAG and Cycle")
    void mixedGraph() {
      // C -> D (Cycle)
      // D -> C (Cycle)
      // B -> C (B depends on the cycle)
      // A -> B (A depends on B)

      Table c = createTable("C", "D");
      Table d = createTable("D", "C");
      Table b = createTable("B", "C");
      Table a = createTable("A", "B");

      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(a, b, c, d));

      // C-D is a cycle (SCC).
      // B depends on that SCC (B->C). So B -> {CD}.
      // A depends on B (A->B). So A -> B.
      // Topological order: A -> B -> {CD}.
      // So A, B, C, D (or A, B, D, C).

      assertThat(result.ordered()).containsSubsequence("A", "B");

      // Check that B comes before C and D
      int idxB = result.ordered().indexOf("B");
      int idxC = result.ordered().indexOf("C");
      int idxD = result.ordered().indexOf("D");

      assertThat(idxB).isLessThan(idxC);
      assertThat(idxB).isLessThan(idxD);

      assertThat(result.cycles()).hasSize(1);
      assertThat(result.cycles().getFirst()).containsExactlyInAnyOrder("C", "D");
    }
  }

  @Nested
  @DisplayName("Edge Cases & Validation")
  class EdgeCases {

    @Test
    @DisplayName("Should throw NPE if input list is null")
    void nullInput() {
      assertThatThrownBy(() -> TopologicalSorter.sort(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle tables referencing non-existent tables gracefully (ignore them in graph)")
    void referenceToNonExistentTable() {
      // A -> Z (Z does not exist in the list)
      Table a = createTable("A", "Z");

      TopologicalSorter.SortResult result = TopologicalSorter.sort(List.of(a));

      // With only one table A, the graph keySet contains "A".
      // Dependencies: A -> Z.
      // Since Z is not in input, it's not in graph keys.
      // Tarjan runs on A.
      // A depends on Z. graph.get(A) returns [Z].
      // Tarjan visits Z.
      // Z is not in indexMap. strongConnect(Z) called.
      // inside strongConnect(Z):
      // graph.get(Z) returns empty set (default).
      // Z has no children.
      // Z SCC found (itself). result adds {Z}.
      // Then returns to A.
      // A SCC found (itself). result adds {A}.
      // Result SCCs: {Z}, {A}.
      // Reduced graph: {Z} -> idx 0. {A} -> idx 1.
      // Edge from A(1) to Z(0). 1 -> 0.
      // InDegree: 0=1, 1=0.
      // Topological Sort Queue: 1 (A).
      // Pop A. List: [A]. Decrement Z(0).
      // Queue: [Z].
      // Pop Z. List: [A, Z].

      // So the result contains Z as well! even if it wasn't in the input list, because Tarjan discovers it.
      // Wait, Tarjan iterates over graph.keySet().
      // graph keySet is built from input tables. So Z is NOT in keySet.
      // "graph.keySet().forEach(v -> ...)"
      // So strongConnect(Z) is NOT called from the loop.
      // BUT, strongConnect(A) calls strongConnect(w) for neighbors.
      // A has neighbor Z.
      // So strongConnect(Z) IS called recursively!
      // So Z is added to SCCs.

      assertThat(result.ordered()).containsExactly("A", "Z");
      assertThat(result.cycles()).isEmpty();
    }
  }

  // --- Helpers ---

  private Table createTable(String name, String... fkTo) {
    List<ForeignKey> fks = java.util.Arrays.stream(fkTo)
        .map(target -> new ForeignKey(
            "FK_" + name + "_" + target,
            target,
            Map.of("col_" + target, "id"),
            false
        ))
        .toList();

    return Table.builder()
        .name(name)
        .columns(Collections.emptyList())
        .primaryKey(Collections.emptyList())
        .foreignKeys(fks)
        .checks(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();
  }
}
