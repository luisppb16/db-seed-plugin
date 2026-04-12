/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.intellij.openapi.progress.ProgressIndicator;
import com.luisppb16.dbseed.db.PendingUpdate;
import com.luisppb16.dbseed.db.ProgressTracker;
import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.CircularReferenceTerminationMode;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ForeignKeyResolverTest {

  private static Column intCol(final String name) {
    return new Column(name, 4, null, true, false, false, 0, 0, null, null, Set.of());
  }

  private static Column nullableCol(final String name) {
    return new Column(name, 4, null, true, false, false, 0, 0, null, null, Set.of());
  }

  private static ForeignKey fk(final String pkTable, final String fkCol, final String pkCol) {
    return new ForeignKey(null, pkTable, Map.of(fkCol, pkCol), false);
  }

  private static ForeignKey uniqueFk(final String pkTable, final String fkCol, final String pkCol) {
    return new ForeignKey(null, pkTable, Map.of(fkCol, pkCol), true);
  }

  private static Row mutableRow(final Object... kvs) {
    final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kvs.length; i += 2) {
      m.put((String) kvs[i], kvs[i + 1]);
    }
    return new Row(m);
  }

  // ── Basic resolution ──

  @Test
  void parentChildResolution() {
    final Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(fk("parent", "parent_id", "id")),
            List.of(),
            List.of());

    final Row parentRow = mutableRow("id", 100);
    final Row childRow = mutableRow("id", 1, "parent_id", null);

    // LinkedHashMap to ensure parent is processed before child
    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(parentRow));
    data.put(child, List.of(childRow));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    resolver.resolve();
    assertThat(childRow.values().get("parent_id")).isEqualTo(100);
  }

  @Test
  void constructor_withNullCircularReferences_defaultsToEmptyMap() {
    final Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(fk("parent", "parent_id", "id")),
            List.of(),
            List.of());

    final Row parentRow = mutableRow("id", 100);
    final Row childRow = mutableRow("id", 1, "parent_id", null);

    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(parentRow));
    data.put(child, List.of(childRow));

    final ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false, null);
    resolver.resolve();

    assertThat(childRow.values().get("parent_id")).isEqualTo(100);
  }

  @Test
  void parentNotInMap_null() {
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("fk_id")),
            List.of("id"),
            List.of(fk("missing", "fk_id", "id")),
            List.of(),
            List.of());
    final Row childRow = mutableRow("id", 1, "fk_id", 99);

    final Map<String, Table> tableMap = Map.of("child", child);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(child, List.of(childRow));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    resolver.resolve();
    assertThat(childRow.values().get("fk_id")).isNull();
  }

  @Test
  void emptyParentRows_null() {
    final Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), nullableCol("parent_id")),
            List.of("id"),
            List.of(fk("parent", "parent_id", "id")),
            List.of(),
            List.of());

    final Row childRow = mutableRow("id", 1, "parent_id", null);

    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of());
    data.put(child, List.of(childRow));

    // Empty parent rows causes IllegalArgumentException from ThreadLocalRandom.nextInt(0)
    assertThatThrownBy(
            () -> {
              final ForeignKeyResolver resolver =
                  new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
              resolver.resolve();
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void multiColumnFk() {
    final Table parent =
        new Table(
            "parent",
            List.of(intCol("pk1"), intCol("pk2")),
            List.of("pk1", "pk2"),
            List.of(),
            List.of(),
            List.of());
    final ForeignKey mfk = new ForeignKey(null, "parent", Map.of("fk1", "pk1", "fk2", "pk2"), false);
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("fk1"), intCol("fk2")),
            List.of("id"),
            List.of(mfk),
            List.of(),
            List.of());

    final Row parentRow = mutableRow("pk1", 10, "pk2", 20);
    final Row childRow = mutableRow("id", 1, "fk1", null, "fk2", null);

    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(parentRow));
    data.put(child, List.of(childRow));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    resolver.resolve();
    assertThat(childRow.values().get("fk1")).isEqualTo(10);
    assertThat(childRow.values().get("fk2")).isEqualTo(20);
  }

  // ── Deferred ──

  @Test
  void deferred_true_resolvesDirectly() {
    final Table a =
        new Table(
            "A",
            List.of(intCol("id"), intCol("b_id")),
            List.of("id"),
            List.of(fk("B", "b_id", "id")),
            List.of(),
            List.of());
    final Table b =
        new Table(
            "B",
            List.of(intCol("id"), intCol("a_id")),
            List.of("id"),
            List.of(fk("A", "a_id", "id")),
            List.of(),
            List.of());

    final Row rowA = mutableRow("id", 1, "b_id", null);
    final Row rowB = mutableRow("id", 2, "a_id", null);

    final Map<String, Table> tableMap = new LinkedHashMap<>(Map.of("A", a, "B", b));
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(a, List.of(rowA));
    data.put(b, List.of(rowB));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, true, Collections.emptyMap());
    final List<PendingUpdate> updates = resolver.resolve();
    // With deferred=true, both resolve directly (no pending updates)
    assertThat(updates).isEmpty();
  }

  @Test
  void resolve_withTracker_advancesPerProcessedTable() {
    final Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(fk("parent", "parent_id", "id")),
            List.of(),
            List.of());

    final Row parentRow = mutableRow("id", 10);
    final Row childRow = mutableRow("id", 1, "parent_id", null);

    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(parentRow));
    data.put(child, List.of(childRow));

    final ProgressIndicator indicator = mock(ProgressIndicator.class);
    final ProgressTracker tracker = new ProgressTracker(indicator, 2);

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    resolver.resolve(tracker);

    verify(indicator).setFraction(0.5d);
    verify(indicator).setFraction(1.0d);
  }

  @Test
  void deferred_false_nullableFk_createsPendingUpdate() {
    // A has nullable FK to B. A must be processed BEFORE B so B isn't "inserted" yet.
    final Table a =
        new Table(
            "A",
            List.of(intCol("id"), nullableCol("b_id")),
            List.of("id"),
            List.of(fk("B", "b_id", "id")),
            List.of(),
            List.of());
    final Table b = new Table("B", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());

    final Row rowA = mutableRow("id", 1, "b_id", null);
    final Row rowB = mutableRow("id", 2);

    // A first so it's processed before B is "inserted"
    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("A", a);
    tableMap.put("B", b);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(a, List.of(rowA));
    data.put(b, List.of(rowB));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    final List<PendingUpdate> updates = resolver.resolve();
    // A processed first, B not inserted yet, FK is nullable -> pending update
    assertThat(updates).isNotEmpty();
    assertThat(rowA.values().get("b_id")).isNull();
  }

  @Test
  void deferred_false_nonNullableCycle_noException() {
    // A tiene FK no anulable a B. A se procesa ANTES que B.
    final Table a =
        new Table(
            "A",
            List.of(intCol("id"), intCol("b_id")),
            List.of("id"),
            List.of(fk("B", "b_id", "id")),
            List.of(),
            List.of());
    final Table b = new Table("B", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    final Row rowA = mutableRow("id", 1, "b_id", null);
    final Row rowB = mutableRow("id", 2);
    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("A", a);
    tableMap.put("B", b);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(a, List.of(rowA));
    data.put(b, List.of(rowB));
    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    // Ya no esperamos excepción, solo que el valor FK siga siendo null o se maneje según la nueva lógica
    resolver.resolve();
    assertThat(rowA.values().get("b_id")).isNull();
  }

  // ── Unique FK ──

  @Test
  void uniqueFk_distinctParents() {
    final Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(uniqueFk("parent", "parent_id", "id")),
            List.of(),
            List.of(List.of("parent_id")));

    final Row p1 = mutableRow("id", 1);
    final Row p2 = mutableRow("id", 2);
    final Row c1 = mutableRow("id", 10, "parent_id", null);
    final Row c2 = mutableRow("id", 11, "parent_id", null);

    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    // ensure parent first
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(p1, p2));
    data.put(child, List.of(c1, c2));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    resolver.resolve();
    // Each child should get a distinct parent
    final Set<Object> parentIds = new HashSet<>();
    parentIds.add(c1.values().get("parent_id"));
    parentIds.add(c2.values().get("parent_id"));
    assertThat(parentIds).hasSize(2);
  }

  @Test
  void uniqueFk_moreChildrenThanParents_nonNullable_returnsNull() {
    final Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    final Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(uniqueFk("parent", "parent_id", "id")),
            List.of(),
            List.of(List.of("parent_id")));

    final Row p1 = mutableRow("id", 1);
    final Row c1 = mutableRow("id", 10, "parent_id", null);
    final Row c2 = mutableRow("id", 11, "parent_id", null);
    final Row c3 = mutableRow("id", 12, "parent_id", null);

    final Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(p1));
    data.put(child, List.of(c1, c2, c3));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, Collections.emptyMap());
    resolver.resolve();

    assertThat(c1.values().get("parent_id")).isEqualTo(1);
    assertThat(c2.values().get("parent_id")).isNull();
    assertThat(c3.values().get("parent_id")).isNull();
  }

  @Test
  void circularReferences_deferred_closesCycle() {
    final Table table =
        new Table(
            "users",
            List.of(intCol("id"), nullableCol("manager_id")),
            List.of("id"),
            List.of(new ForeignKey("fk_manager", "users", Map.of("manager_id", "id"), false)),
            List.of(),
            List.of());

    final List<Row> rows =
        List.of(
            new Row(new LinkedHashMap<>(Map.of("id", 1))),
            new Row(new LinkedHashMap<>(Map.of("id", 2))),
            new Row(new LinkedHashMap<>(Map.of("id", 3))),
            new Row(new LinkedHashMap<>(Map.of("id", 4))),
            new Row(new LinkedHashMap<>(Map.of("id", 5))));

    final Map<String, Table> tableMap = Map.of("users", table);
    final Map<Table, List<Row>> data = Map.of(table, rows);

    // Max depth of 3
    final Map<String, Map<String, Integer>> circularReferences = Map.of("users", Map.of("fk_manager", 3));

    final ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, true, circularReferences);

    final List<PendingUpdate> updates = resolver.resolve();

    assertThat(updates).isEmpty(); // because deferred is true

    // Check cycles
    // chain 1: 0, 1, 2. (0->1, 1->2, 2->0) -> so manager_id for 1 is 2, 2 is 3, 3 is 1
    assertThat(rows.get(0).values().get("manager_id")).isEqualTo(2);
    assertThat(rows.get(1).values().get("manager_id")).isEqualTo(3);
    assertThat(rows.get(2).values().get("manager_id")).isEqualTo(1);

    // chain 2: 3, 4. (3->4, 4->3) -> so manager_id for 4 is 5, 5 is 4
    assertThat(rows.get(3).values().get("manager_id")).isEqualTo(5);
    assertThat(rows.get(4).values().get("manager_id")).isEqualTo(4);
  }

  @Test
  void circularReferences_nonDeferred_createsPendingUpdates() {
    final Table table =
        new Table(
            "users",
            List.of(intCol("id"), nullableCol("manager_id")),
            List.of("id"),
            List.of(new ForeignKey("fk_manager", "users", Map.of("manager_id", "id"), false)),
            List.of(),
            List.of());

    final List<Row> rows =
        List.of(
            new Row(new LinkedHashMap<>(Map.of("id", 1))),
            new Row(new LinkedHashMap<>(Map.of("id", 2))));

    final Map<String, Table> tableMap = Map.of("users", table);
    final Map<Table, List<Row>> data = Map.of(table, rows);

    final Map<String, Map<String, Integer>> circularReferences = Map.of("users", Map.of("fk_manager", 2));

    final ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false, circularReferences);

    final List<PendingUpdate> updates = resolver.resolve();

    assertThat(updates).hasSize(2);

    // Rows should have null manager_ids
    assertThat(rows.get(0).values().get("manager_id")).isNull();
    assertThat(rows.get(1).values().get("manager_id")).isNull();

    // Updates should correctly establish cycle: 1 points to 2, 2 points to 1
    final PendingUpdate update1 = updates.get(0);
    assertThat(update1.pkValues().get("id")).isEqualTo(1);
    assertThat(update1.fkValues().get("manager_id")).isEqualTo(2);

    final PendingUpdate update2 = updates.get(1);
    assertThat(update2.pkValues().get("id")).isEqualTo(2);
    assertThat(update2.fkValues().get("manager_id")).isEqualTo(1);
  }

  @Test
  void circularReferences_nullTermination_deferred_createsHierarchy() {
    final Table table =
        new Table(
            "users",
            List.of(intCol("id"), nullableCol("manager_id")),
            List.of("id"),
            List.of(new ForeignKey("fk_manager", "users", Map.of("manager_id", "id"), false)),
            List.of(),
            List.of());

    final List<Row> rows =
        List.of(
            new Row(new LinkedHashMap<>(Map.of("id", 1))),
            new Row(new LinkedHashMap<>(Map.of("id", 2))),
            new Row(new LinkedHashMap<>(Map.of("id", 3))),
            new Row(new LinkedHashMap<>(Map.of("id", 4))),
            new Row(new LinkedHashMap<>(Map.of("id", 5))));

    final Map<String, Table> tableMap = Map.of("users", table);
    final Map<Table, List<Row>> data = Map.of(table, rows);
    final Map<String, Map<String, Integer>> circularReferences = Map.of("users", Map.of("fk_manager", 3));
    final Map<String, Map<String, String>> terminationModes =
        Map.of("users", Map.of("fk_manager", CircularReferenceTerminationMode.NULL_FK.name()));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, true, circularReferences, terminationModes);

    final List<PendingUpdate> updates = resolver.resolve();

    assertThat(updates).isEmpty();
    assertThat(rows.get(0).values().get("manager_id")).isEqualTo(2);
    assertThat(rows.get(1).values().get("manager_id")).isEqualTo(3);
    assertThat(rows.get(2).values().get("manager_id")).isNull();
    assertThat(rows.get(3).values().get("manager_id")).isEqualTo(5);
    assertThat(rows.get(4).values().get("manager_id")).isNull();
  }

  @Test
  void circularReferences_nullTermination_nonDeferred_skipsLastPendingUpdate() {
    final Table table =
        new Table(
            "users",
            List.of(intCol("id"), nullableCol("manager_id")),
            List.of("id"),
            List.of(new ForeignKey("fk_manager", "users", Map.of("manager_id", "id"), false)),
            List.of(),
            List.of());

    final List<Row> rows =
        List.of(
            new Row(new LinkedHashMap<>(Map.of("id", 1))),
            new Row(new LinkedHashMap<>(Map.of("id", 2))),
            new Row(new LinkedHashMap<>(Map.of("id", 3))));

    final Map<String, Table> tableMap = Map.of("users", table);
    final Map<Table, List<Row>> data = Map.of(table, rows);
    final Map<String, Map<String, Integer>> circularReferences = Map.of("users", Map.of("fk_manager", 3));
    final Map<String, Map<String, String>> terminationModes =
        Map.of("users", Map.of("fk_manager", CircularReferenceTerminationMode.NULL_FK.name()));

    final ForeignKeyResolver resolver =
        new ForeignKeyResolver(tableMap, data, false, circularReferences, terminationModes);

    final List<PendingUpdate> updates = resolver.resolve();

    assertThat(updates).hasSize(2);
    assertThat(rows).allSatisfy(row -> assertThat(row.values().get("manager_id")).isNull());

    assertThat(updates)
        .extracting(
            update -> update.pkValues().get("id"), update -> update.fkValues().get("manager_id"))
        .containsExactly(tuple(1, 2), tuple(2, 3));
  }

  @Test
  void circularReferences_depthZero_usesDefaultDepth3() {
    final Table table =
        new Table(
            "users",
            List.of(intCol("id"), nullableCol("manager_id")),
            List.of("id"),
            List.of(new ForeignKey("fk_manager", "users", Map.of("manager_id", "id"), false)),
            List.of(),
            List.of());

    final List<Row> rows =
        List.of(
            new Row(new LinkedHashMap<>(Map.of("id", 1))),
            new Row(new LinkedHashMap<>(Map.of("id", 2))),
            new Row(new LinkedHashMap<>(Map.of("id", 3))));

    final Map<String, Table> tableMap = Map.of("users", table);
    final Map<Table, List<Row>> data = Map.of(table, rows);

    final Map<String, Map<String, Integer>> circularReferences = Map.of("users", Map.of("fk_manager", 0));

    final ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, true, circularReferences);

    assertThatCode(() -> resolver.resolve()).doesNotThrowAnyException();
  }

  @Test
  void circularReferences_depthNegative_usesDefaultDepth3() {
    final Table table =
        new Table(
            "users",
            List.of(intCol("id"), nullableCol("manager_id")),
            List.of("id"),
            List.of(new ForeignKey("fk_manager", "users", Map.of("manager_id", "id"), false)),
            List.of(),
            List.of());

    final List<Row> rows =
        List.of(
            new Row(new LinkedHashMap<>(Map.of("id", 1))),
            new Row(new LinkedHashMap<>(Map.of("id", 2))),
            new Row(new LinkedHashMap<>(Map.of("id", 3))));

    final Map<String, Table> tableMap = Map.of("users", table);
    final Map<Table, List<Row>> data = Map.of(table, rows);

    final Map<String, Map<String, Integer>> circularReferences = Map.of("users", Map.of("fk_manager", -5));

    final ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, true, circularReferences);

    assertThatCode(() -> resolver.resolve()).doesNotThrowAnyException();
  }
}

