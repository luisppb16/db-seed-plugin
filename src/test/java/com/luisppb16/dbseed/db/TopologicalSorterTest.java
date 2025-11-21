/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.db.TopologicalSorter.SortResult;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TopologicalSorterTest {

  @Test
  @DisplayName("Should sort tables with no dependencies, order based on input list")
  void shouldSortIndependentTables() {
    Table t1 = createTable("A");
    Table t2 = createTable("B");

    // Input order: B, A
    SortResult result = TopologicalSorter.sort(List.of(t2, t1));

    assertEquals(2, result.ordered().size());
    // With no dependencies, the order of independent tables is determined by their
    // appearance in the input list, due to LinkedHashMap and ArrayDeque usage.
    assertEquals(List.of("B", "A"), result.ordered());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  @DisplayName("Should sort simple dependency chain: Child -> Parent (Child comes before Parent)")
  void shouldSortDependencies() {
    // Child refers to Parent.
    // The graph edge is Child -> Parent.
    // TopologicalSorter produces an order where dependents come before their dependencies.
    // This means it's a "delete order" or "process dependents first" order.
    Table parent = createTable("Parent");
    Table child = createTable("Child", "Parent"); // Child depends on Parent

    SortResult result = TopologicalSorter.sort(List.of(child, parent));

    assertEquals(List.of("Child", "Parent"), result.ordered());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  @DisplayName("Should sort a complex dependency chain: A -> B -> C")
  void shouldSortComplexDependencyChain() {
    Table c = createTable("C");
    Table b = createTable("B", "C"); // B depends on C
    Table a = createTable("A", "B"); // A depends on B

    // Input order: A, B, C
    SortResult result = TopologicalSorter.sort(List.of(a, b, c));

    // Expected order: A, B, C (dependents before dependencies)
    assertEquals(List.of("A", "B", "C"), result.ordered());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  @DisplayName("Should sort when multiple tables depend on one: A -> C, B -> C")
  void shouldSortMultipleDependentsToOne() {
    Table c = createTable("C");
    Table a = createTable("A", "C"); // A depends on C
    Table b = createTable("B", "C"); // B depends on C

    // Input order: A, B, C
    SortResult result = TopologicalSorter.sort(List.of(a, b, c));

    // A and B are independent of each other but both depend on C.
    // Their relative order is determined by the input list order.
    assertEquals(List.of("A", "B", "C"), result.ordered());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  @DisplayName(
      "Should sort mixed dependencies and independent tables: A -> B, C -> B, D (independent)")
  void shouldSortMixedDependenciesAndIndependents() {
    Table b = createTable("B");
    Table a = createTable("A", "B"); // A depends on B
    Table c = createTable("C", "B"); // C depends on B
    Table d = createTable("D"); // D is independent

    // Input order: A, C, D, B
    SortResult result = TopologicalSorter.sort(List.of(a, c, d, b));

    // A, C, D are initial nodes with in-degree 0. Their relative order is based on input.
    // B is processed after A and C.
    assertEquals(List.of("A", "C", "D", "B"), result.ordered());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  @DisplayName("Should handle an empty list of tables")
  void shouldHandleEmptyTableList() {
    SortResult result = TopologicalSorter.sort(Collections.emptyList());

    assertTrue(result.ordered().isEmpty());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  @DisplayName("Should handle a single table")
  void shouldHandleSingleTable() {
    Table t1 = createTable("SingleTable");
    SortResult result = TopologicalSorter.sort(List.of(t1));

    assertEquals(List.of("SingleTable"), result.ordered());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  @DisplayName("Should detect cycles")
  void shouldDetectCycles() {
    // A -> B -> A
    Table a = createTable("A", "B");
    Table b = createTable("B", "A");

    SortResult result = TopologicalSorter.sort(List.of(a, b));

    assertFalse(result.cycles().isEmpty());
    assertEquals(1, result.cycles().size());
    var cycle = result.cycles().getFirst(); // Fixed: Replaced get(0) with getFirst()
    assertTrue(cycle.contains("A"));
    assertTrue(cycle.contains("B"));

    // For a cycle, the tables within the cycle are grouped into an SCC.
    // The SCC is then added to the ordered list, with its elements sorted alphabetically.
    assertEquals(List.of("A", "B"), result.ordered());
  }

  @Test
  @DisplayName("Should detect cycles with self-referencing table")
  void shouldDetectSelfReferencingCycle() {
    // A -> A
    Table a = createTable("A", "A");

    SortResult result = TopologicalSorter.sort(List.of(a));

    assertFalse(result.cycles().isEmpty());
    assertEquals(1, result.cycles().size());
    var cycle = result.cycles().getFirst();
    assertTrue(cycle.contains("A"));
    assertEquals(1, cycle.size()); // Cycle contains only A

    assertEquals(List.of("A"), result.ordered());
  }

  @Test
  @DisplayName("Should sort complex graph with multiple SCCs and dependencies between them")
  void shouldSortComplexGraphWithSccs() {
    // Graph:
    // A -> B
    // B -> C
    // C -> A (Cycle: A, B, C)
    // D -> E
    // E -> F
    // G -> A
    // H (independent)

    Table a = createTable("A", "B");
    Table b = createTable("B", "C");
    Table c = createTable("C", "A"); // A, B, C form an SCC

    Table e = createTable("E", "F");
    Table d = createTable("D", "E"); // D -> E -> F

    Table g = createTable("G", "A"); // G depends on the A,B,C SCC
    Table h = createTable("H"); // H is independent

    SortResult result = TopologicalSorter.sort(List.of(a, b, c, d, e, g, h));

    // Expected:
    // SCC {A, B, C} will be sorted alphabetically internally: [A, B, C]
    // D -> E -> F: [D, E, F]
    // G -> {A,B,C}: G comes before [A,B,C]
    // H is independent.

    // Initial in-degrees for SCCs/nodes:
    // {A,B,C} SCC: 1 (from G)
    // D: 0
    // E: 1 (from D)
    // F: 1 (from E)
    // G: 0
    // H: 0

    // Kahn's on SCC graph:
    // Queue: [D, G, H] (order based on input list)
    // Pop D. Ordered: [D]. E in-degree becomes 0. Add E.
    // Pop G. Ordered: [D, G]. {A,B,C} SCC in-degree becomes 0. Add {A,B,C} SCC.
    // Pop H. Ordered: [D, G, H].
    // Queue: [E, {A,B,C} SCC]
    // Pop E. Ordered: [D, G, H, E]. F in-degree becomes 0. Add F.
    // Queue: [{A,B,C} SCC, F]
    // Pop {A,B,C} SCC. Ordered: [D, G, H, E, A, B, C] (A,B,C sorted internally)
    // Pop F. Ordered: [D, G, H, E, A, B, C, F]

    assertEquals(List.of("D", "G", "H", "E", "A", "B", "C", "F"), result.ordered());
    assertFalse(result.cycles().isEmpty());
    assertEquals(1, result.cycles().size());
    var cycle = result.cycles().getFirst();
    assertTrue(cycle.contains("A"));
    assertTrue(cycle.contains("B"));
    assertTrue(cycle.contains("C"));
    assertEquals(3, cycle.size());
  }

  private Table createTable(String name, String... fkRefs) {
    List<ForeignKey> fks = new java.util.ArrayList<>();
    for (String ref : fkRefs) {
      fks.add(ForeignKey.builder().pkTable(ref).columnMapping(Map.of("col", "id")).build());
    }

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
