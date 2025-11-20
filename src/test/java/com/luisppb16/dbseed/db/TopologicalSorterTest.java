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
    @DisplayName("Should sort tables with no dependencies arbitrarily (or by name/stability)")
    void shouldSortIndependentTables() {
        Table t1 = createTable("A");
        Table t2 = createTable("B");

        SortResult result = TopologicalSorter.sort(List.of(t2, t1));

        assertEquals(2, result.ordered().size());
        // Since no dependencies, order is determined by SCC sort (which uses alphanumeric for within-SCC if relevant, or arbitrary).
        // Implementation uses `group.sort(String::compareTo)` within SCC.
        // Tarjan/Kahn might produce different SCC orderings depending on iteration.
        // But here A and B are separate SCCs.
        // Kahn: in-degree 0 for both. Queue order depends on iteration of entrySet.
        // Assuming consistent iteration, but let's just check membership.
        assertTrue(result.ordered().contains("A"));
        assertTrue(result.ordered().contains("B"));
        assertTrue(result.cycles().isEmpty());
    }

    @Test
    @DisplayName("Should sort simple dependency chain: Child -> Parent")
    void shouldSortDependencies() {
        // Child refers to Parent. So Parent must come BEFORE Child (INSERT order).
        // Wait, TopologicalSorter logic:
        // "Build a directed graph: table -> tables referenced by FK"
        // So Edge: Child -> Parent.
        // Topological Sort of this graph yields: Child, Parent?
        // Standard Topo Sort: if A -> B, A comes BEFORE B?
        // Kahn's algorithm: nodes with in-degree 0 are processed first.
        // In-degree of Parent is 1 (from Child). In-degree of Child is 0.
        // So Child is processed first.
        // So order: Child, Parent.
        // But for INSERT, we need Parent first!
        // "The insertion order of tables is preserved by LinkedHashMap in DataGenerator."
        // DataGenerator calls `orderByWordAndFk`?
        // Wait, `TopologicalSorter` is used WHERE?
        // `DataGenerator.java` does NOT use `TopologicalSorter`!
        // `DataGenerator` uses `orderByWordAndFk`.
        // `TopologicalSorter` seems unused in `DataGenerator`.
        // Maybe it's used in `GenerateSeedAction` or intended for future?
        // Regardless, I am testing `TopologicalSorter` logic.
        // If Graph is A -> B (A depends on B).
        // Topo Sort usually gives A then B if edge means "comes before"?
        // No, Topo Sort on dependency graph (A depends on B) usually gives B, A (build order).
        // Let's check Kahn's implementation in `TopologicalSorter`.
        // Graph: `table -> set of FK tables`. So A -> {B}.
        // In-degree:
        // A: 0 (no one points to A).
        // B: 1 (A points to B).
        // Queue starts with A.
        // Pop A. Add to order.
        // Decrement B in-degree -> 0. Add B to queue.
        // Order: A, B.
        // So it returns dependents first?
        // If so, it's "Delete Order", not "Insert Order".
        // Or maybe I am misinterpreting "directed graph".
        // Let's verify with test.

        Table parent = createTable("Parent");
        Table child = createTable("Child", "Parent"); // Child -> Parent

        SortResult result = TopologicalSorter.sort(List.of(child, parent));

        assertEquals(List.of("Child", "Parent"), result.ordered());
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
        var cycle = result.cycles().get(0);
        assertTrue(cycle.contains("A"));
        assertTrue(cycle.contains("B"));

        // Order should contain them grouped?
        // SCC will be {A, B}.
        // It is added to `orderScc`.
        // `group.sort(String::compareTo)`.
        // So A, B.
        assertEquals(List.of("A", "B"), result.ordered());
    }

    private Table createTable(String name, String... fkRefs) {
        List<ForeignKey> fks = new java.util.ArrayList<>();
        for (String ref : fkRefs) {
            fks.add(ForeignKey.builder()
                .pkTable(ref)
                .columnMapping(Map.of("col", "id"))
                .build());
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
