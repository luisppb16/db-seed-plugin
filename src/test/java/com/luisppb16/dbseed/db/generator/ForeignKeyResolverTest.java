/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.*;

import com.luisppb16.dbseed.db.PendingUpdate;
import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.*;
import org.junit.jupiter.api.Test;

class ForeignKeyResolverTest {

  private static Column intCol(String name) {
    return Column.builder().name(name).jdbcType(4).build();
  }

  private static Column nullableCol(String name) {
    return Column.builder().name(name).jdbcType(4).nullable(true).build();
  }

  private static ForeignKey fk(String pkTable, String fkCol, String pkCol) {
    return new ForeignKey(null, pkTable, Map.of(fkCol, pkCol), false);
  }

  private static ForeignKey uniqueFk(String pkTable, String fkCol, String pkCol) {
    return new ForeignKey(null, pkTable, Map.of(fkCol, pkCol), true);
  }

  private static Row mutableRow(Object... kvs) {
    LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kvs.length; i += 2) {
      m.put((String) kvs[i], kvs[i + 1]);
    }
    return new Row(m);
  }

  // ── Basic resolution ──

  @Test
  void parentChildResolution() {
    Table parent = new Table("parent", List.of(intCol("id")),
        List.of("id"), List.of(), List.of(), List.of());
    Table child = new Table("child", List.of(intCol("id"), intCol("parent_id")),
        List.of("id"), List.of(fk("parent", "parent_id", "id")), List.of(), List.of());

    Row parentRow = mutableRow("id", 100);
    Row childRow = mutableRow("id", 1, "parent_id", null);

    // LinkedHashMap to ensure parent is processed before child
    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(parentRow));
    data.put(child, List.of(childRow));

    ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
    resolver.resolve();
    assertThat(childRow.values().get("parent_id")).isEqualTo(100);
  }

  @Test
  void parentNotInMap_null() {
    Table child = new Table("child", List.of(intCol("id"), intCol("fk_id")),
        List.of("id"), List.of(fk("missing", "fk_id", "id")), List.of(), List.of());
    Row childRow = mutableRow("id", 1, "fk_id", 99);

    Map<String, Table> tableMap = Map.of("child", child);
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(child, List.of(childRow));

    ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
    resolver.resolve();
    assertThat(childRow.values().get("fk_id")).isNull();
  }

  @Test
  void emptyParentRows_null() {
    Table parent = new Table("parent", List.of(intCol("id")),
        List.of("id"), List.of(), List.of(), List.of());
    Table child = new Table("child", List.of(intCol("id"), nullableCol("parent_id")),
        List.of("id"), List.of(fk("parent", "parent_id", "id")), List.of(), List.of());

    Row childRow = mutableRow("id", 1, "parent_id", null);

    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of());
    data.put(child, List.of(childRow));

    // Empty parent rows causes IllegalArgumentException from ThreadLocalRandom.nextInt(0)
    assertThatThrownBy(() -> {
      ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
      resolver.resolve();
    }).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void multiColumnFk() {
    Table parent = new Table("parent", List.of(intCol("pk1"), intCol("pk2")),
        List.of("pk1", "pk2"), List.of(), List.of(), List.of());
    ForeignKey mfk = new ForeignKey(null, "parent",
        Map.of("fk1", "pk1", "fk2", "pk2"), false);
    Table child = new Table("child",
        List.of(intCol("id"), intCol("fk1"), intCol("fk2")),
        List.of("id"), List.of(mfk), List.of(), List.of());

    Row parentRow = mutableRow("pk1", 10, "pk2", 20);
    Row childRow = mutableRow("id", 1, "fk1", null, "fk2", null);

    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(parentRow));
    data.put(child, List.of(childRow));

    ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
    resolver.resolve();
    assertThat(childRow.values().get("fk1")).isEqualTo(10);
    assertThat(childRow.values().get("fk2")).isEqualTo(20);
  }

  // ── Deferred ──

  @Test
  void deferred_true_resolvesDirectly() {
    Table a = new Table("A", List.of(intCol("id"), intCol("b_id")),
        List.of("id"), List.of(fk("B", "b_id", "id")), List.of(), List.of());
    Table b = new Table("B", List.of(intCol("id"), intCol("a_id")),
        List.of("id"), List.of(fk("A", "a_id", "id")), List.of(), List.of());

    Row rowA = mutableRow("id", 1, "b_id", null);
    Row rowB = mutableRow("id", 2, "a_id", null);

    Map<String, Table> tableMap = new LinkedHashMap<>(Map.of("A", a, "B", b));
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(a, List.of(rowA));
    data.put(b, List.of(rowB));

    ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, true);
    List<PendingUpdate> updates = resolver.resolve();
    // With deferred=true, both resolve directly (no pending updates)
    assertThat(updates).isEmpty();
  }

  @Test
  void deferred_false_nullableFk_createsPendingUpdate() {
    // A has nullable FK to B. A must be processed BEFORE B so B isn't "inserted" yet.
    Table a = new Table("A", List.of(intCol("id"), nullableCol("b_id")),
        List.of("id"), List.of(fk("B", "b_id", "id")), List.of(), List.of());
    Table b = new Table("B", List.of(intCol("id")),
        List.of("id"), List.of(), List.of(), List.of());

    Row rowA = mutableRow("id", 1, "b_id", null);
    Row rowB = mutableRow("id", 2);

    // A first so it's processed before B is "inserted"
    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("A", a);
    tableMap.put("B", b);
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(a, List.of(rowA));
    data.put(b, List.of(rowB));

    ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
    List<PendingUpdate> updates = resolver.resolve();
    // A processed first, B not inserted yet, FK is nullable → pending update
    assertThat(updates).isNotEmpty();
    assertThat(rowA.values().get("b_id")).isNull();
  }

  @Test
  void deferred_false_nonNullableCycle_throws() {
    // A has non-nullable FK to B. A must be processed BEFORE B.
    Table a = new Table("A", List.of(intCol("id"), intCol("b_id")),
        List.of("id"), List.of(fk("B", "b_id", "id")), List.of(), List.of());
    Table b = new Table("B", List.of(intCol("id")),
        List.of("id"), List.of(), List.of(), List.of());

    Row rowA = mutableRow("id", 1, "b_id", null);
    Row rowB = mutableRow("id", 2);

    // A first so B is not yet "inserted" when A is processed
    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("A", a);
    tableMap.put("B", b);
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(a, List.of(rowA));
    data.put(b, List.of(rowB));

    // A processed first, B not inserted yet, FK is non-nullable → throws
    assertThatThrownBy(() -> {
      ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
      resolver.resolve();
    }).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cycle with non-nullable FK");
  }

  // ── Unique FK ──

  @Test
  void uniqueFk_distinctParents() {
    Table parent = new Table("parent", List.of(intCol("id")),
        List.of("id"), List.of(), List.of(), List.of());
    Table child = new Table("child", List.of(intCol("id"), intCol("parent_id")),
        List.of("id"), List.of(uniqueFk("parent", "parent_id", "id")),
        List.of(), List.of(List.of("parent_id")));

    Row p1 = mutableRow("id", 1);
    Row p2 = mutableRow("id", 2);
    Row c1 = mutableRow("id", 10, "parent_id", null);
    Row c2 = mutableRow("id", 11, "parent_id", null);

    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    // ensure parent first
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(p1, p2));
    data.put(child, List.of(c1, c2));

    ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
    resolver.resolve();
    // Each child should get a distinct parent
    Set<Object> parentIds = new HashSet<>();
    parentIds.add(c1.values().get("parent_id"));
    parentIds.add(c2.values().get("parent_id"));
    assertThat(parentIds).hasSize(2);
  }

  @Test
  void uniqueFk_moreChildrenThanParents_nonNullable_throws() {
    Table parent = new Table("parent", List.of(intCol("id")),
        List.of("id"), List.of(), List.of(), List.of());
    Table child = new Table("child", List.of(intCol("id"), intCol("parent_id")),
        List.of("id"), List.of(uniqueFk("parent", "parent_id", "id")),
        List.of(), List.of(List.of("parent_id")));

    Row p1 = mutableRow("id", 1);
    Row c1 = mutableRow("id", 10, "parent_id", null);
    Row c2 = mutableRow("id", 11, "parent_id", null);
    Row c3 = mutableRow("id", 12, "parent_id", null);

    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    // ensure parent first
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of(p1));
    data.put(child, List.of(c1, c2, c3));

    // 3 children but only 1 parent with unique FK – should throw for non-nullable
    assertThatThrownBy(() -> {
      ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
      resolver.resolve();
    }).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Not enough rows");
  }
}
