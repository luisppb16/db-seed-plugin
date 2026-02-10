/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Table;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/**
 * Advanced topological sorting algorithm for database table dependency resolution in the DBSeed plugin.
 * <p>
 * This utility class implements sophisticated algorithms for determining the correct order
 * in which database tables should be processed during data generation, taking into account
 * foreign key dependencies. It uses Tarjan's algorithm to detect strongly connected components
 * (cycles) and applies topological sorting to determine a valid processing order. The class
 * handles complex scenarios including circular dependencies and provides mechanisms for
 * identifying tables that require deferred constraint processing due to non-nullable foreign keys.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Computing topological order of tables based on foreign key dependencies</li>
 *   <li>Detecting and identifying cyclic dependencies in the table graph</li>
 *   <li>Determining when deferred constraint processing is required</li>
 *   <li>Providing efficient algorithms for dependency resolution</li>
 *   <li>Returning both ordered tables and detected cycles for further processing</li>
 *   <li>Optimizing for performance with large numbers of tables and complex relationships</li>
 * </ul>
 * </p>
 * <p>
 * The implementation uses Tarjan's algorithm for strongly connected components detection
 * combined with Kahn's algorithm for topological sorting. It handles complex scenarios
 * where tables have mutual dependencies and provides detailed information about cycles
 * that may require special handling during data generation. The class is designed to be
 * efficient even with large schemas containing numerous tables and complex relationships.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 */
@UtilityClass
public class TopologicalSorter {

  public static SortResult sort(List<Table> tables) {

    // Build a directed graph: dependency → tables that depend on it.
    Map<String, Set<String>> graph = new LinkedHashMap<>();
    tables.forEach(t -> graph.put(t.name(), new LinkedHashSet<>()));

    tables.forEach(
        t ->
            t.foreignKeys()
                .forEach(
                    fk -> {
                      if (graph.containsKey(fk.pkTable())) {
                        graph.get(fk.pkTable()).add(t.name());
                      }
                    }));

    // Detect strongly connected components (SCCs) with Tarjan.
    Tarjan tarjan = new Tarjan(graph);
    List<Set<String>> sccs = tarjan.run();

    // Index: table → index of its SCC.
    Map<String, Integer> idx = new HashMap<>();
    IntStream.range(0, sccs.size()).forEach(i -> sccs.get(i).forEach(n -> idx.put(n, i)));

    // Reduced graph between SCCs.
    Map<Integer, Set<Integer>> sccGraph = new HashMap<>();
    graph.forEach(
        (fromNode, targets) -> {
          int from = idx.get(fromNode);
          targets.forEach(
              toNode -> {
                int to = idx.get(toNode);
                if (from != to) {
                  sccGraph.computeIfAbsent(from, k -> new HashSet<>()).add(to);
                }
              });
        });

    // Calculate in-degrees for Kahn's algorithm.
    Map<Integer, Integer> inDegree = new HashMap<>();
    IntStream.range(0, sccs.size()).forEach(i -> inDegree.put(i, 0)); // Initialize all to 0
    sccGraph.values().forEach(edges -> edges.forEach(v -> inDegree.merge(v, 1, Integer::sum)));

    // Topological sort of the SCCs.
    Deque<Integer> queue =
        inDegree.entrySet().stream()
            .filter(e -> e.getValue() == 0)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(ArrayDeque::new));

    List<Integer> orderScc = new ArrayList<>();
    while (!queue.isEmpty()) {
      int scc = queue.remove();
      orderScc.add(scc);
      sccGraph
          .getOrDefault(scc, Collections.emptySet()) // Use Collections.emptySet() for clarity
          .forEach(
              v -> {
                int deg = inDegree.merge(v, -1, Integer::sum);
                if (deg == 0) queue.add(v);
              });
    }

    // Build the final order of tables.
    List<String> ordered = new ArrayList<>();
    orderScc.forEach(
        id -> {
          List<String> group = new ArrayList<>(sccs.get(id));
          group.sort(String::compareTo);
          ordered.addAll(group);
        });

    // Detect cycles (SCCs with more than one node or with a self-loop).
    List<Set<String>> cycles =
        sccs.stream()
            .filter(
                g ->
                    g.size() > 1
                        || graph
                            .getOrDefault(
                                g.iterator().next(),
                                Collections.emptySet()) // Use Collections.emptySet()
                            .contains(g.iterator().next()))
            .map(Collections::unmodifiableSet)
            .toList();

    return new SortResult(ordered, cycles); // Removed redundant List.copyOf
  }

  public static boolean requiresDeferredDueToNonNullableCycles(
      final SortResult sort, final Map<String, Table> tableMap) {

    return sort.cycles().stream()
        .anyMatch(
            cycle ->
                cycle.stream()
                    .map(tableMap::get)
                    .filter(Objects::nonNull)
                    .anyMatch(
                        table ->
                            table.foreignKeys().stream()
                                .filter(fk -> cycle.contains(fk.pkTable()))
                                .anyMatch(
                                    fk ->
                                        fk.columnMapping().keySet().stream()
                                            .map(table::column)
                                            .filter(Objects::nonNull)
                                            .anyMatch(c -> !c.nullable()))));
  }

  public record SortResult(List<String> ordered, List<Set<String>> cycles) {}

  private static final class Tarjan {
    private final Map<String, Set<String>> graph;
    private final Map<String, Integer> indexMap = new HashMap<>();
    private final Map<String, Integer> lowMap = new HashMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private final Set<String> onStack = new HashSet<>();
    private final List<Set<String>> result = new ArrayList<>();
    private int index = 0;

    Tarjan(Map<String, Set<String>> graph) {
      this.graph =
          Objects.requireNonNull(graph, "Graph cannot be null"); // Added null check message
    }

    List<Set<String>> run() {
      graph
          .keySet()
          .forEach(
              v -> {
                if (!indexMap.containsKey(v)) strongConnect(v);
              });
      return result; // Removed redundant List.copyOf
    }

    private void strongConnect(String v) {
      indexMap.put(v, index);
      lowMap.put(v, index);
      index++;
      stack.push(v);
      onStack.add(v);

      graph
          .getOrDefault(v, Collections.emptySet()) // Use Collections.emptySet()
          .forEach(
              w -> {
                if (!indexMap.containsKey(w)) {
                  strongConnect(w);
                  lowMap.put(v, Math.min(lowMap.get(v), lowMap.get(w)));
                } else if (onStack.contains(w)) {
                  lowMap.put(v, Math.min(lowMap.get(v), indexMap.get(w)));
                }
              });

      if (Objects.equals(lowMap.get(v), indexMap.get(v))) {
        Set<String> scc = new LinkedHashSet<>();
        String w;
        do {
          w = stack.pop();
          onStack.remove(w);
          scc.add(w);
        } while (!w.equals(v));
        result.add(Collections.unmodifiableSet(scc)); // Make SCCs unmodifiable
      }
    }
  }
}
