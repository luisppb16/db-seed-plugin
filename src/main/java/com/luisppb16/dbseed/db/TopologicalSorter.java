package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.ForeignKey;
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

@UtilityClass
public class TopologicalSorter {

  public static SortResult sort(List<Table> tables) {

    // Build a directed graph: table → tables referenced by FK.
    Map<String, Set<String>> graph =
        tables.stream()
            .collect(
                Collectors.toMap(
                    Table::name,
                    t ->
                        t.foreignKeys().stream()
                            .map(ForeignKey::pkTable)
                            .collect(Collectors.toCollection(LinkedHashSet::new)),
                    (a, b) -> a,
                    LinkedHashMap::new));

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
    IntStream.range(0, sccs.size())
        .forEach(
            i -> {
              inDegree.put(i, 0);
              sccGraph.computeIfAbsent(i, k -> new HashSet<>());
            });
    sccGraph.values().forEach(edges -> edges.forEach(v -> inDegree.merge(v, 1, Integer::sum)));

    // Topological sort of the SCCs.
    Deque<Integer> queue = new ArrayDeque<>();
    inDegree.entrySet().stream()
        .filter(e -> e.getValue() == 0)
        .map(Map.Entry::getKey)
        .forEach(queue::add);

    List<Integer> orderScc = new ArrayList<>();
    while (!queue.isEmpty()) {
      int scc = queue.remove();
      orderScc.add(scc);
      sccGraph
          .getOrDefault(scc, Set.of())
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
                            .getOrDefault(g.iterator().next(), Set.of())
                            .contains(g.iterator().next()))
            .map(Collections::unmodifiableSet)
            .toList();

    return new SortResult(List.copyOf(ordered), List.copyOf(cycles));
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
      this.graph = Objects.requireNonNull(graph);
    }

    List<Set<String>> run() {
      graph
          .keySet()
          .forEach(
              v -> {
                if (!indexMap.containsKey(v)) strongConnect(v);
              });
      return List.copyOf(result);
    }

    private void strongConnect(String v) {
      indexMap.put(v, index);
      lowMap.put(v, index);
      index++;
      stack.push(v);
      onStack.add(v);

      graph
          .getOrDefault(v, Set.of())
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
        result.add(Set.copyOf(scc));
      }
    }
  }
}
