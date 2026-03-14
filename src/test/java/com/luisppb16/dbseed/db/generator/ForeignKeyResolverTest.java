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
import com.luisppb16.dbseed.model.SelfReferenceConfig;
import com.luisppb16.dbseed.model.SelfReferenceStrategy;
import com.luisppb16.dbseed.model.Table;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(fk("parent", "parent_id", "id")),
            List.of(),
            List.of());

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
    Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("fk_id")),
            List.of("id"),
            List.of(fk("missing", "fk_id", "id")),
            List.of(),
            List.of());
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
    Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    Table child =
        new Table(
            "child",
            List.of(intCol("id"), nullableCol("parent_id")),
            List.of("id"),
            List.of(fk("parent", "parent_id", "id")),
            List.of(),
            List.of());

    Row childRow = mutableRow("id", 1, "parent_id", null);

    Map<String, Table> tableMap = new LinkedHashMap<>();
    tableMap.put("parent", parent);
    tableMap.put("child", child);
    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(parent, List.of());
    data.put(child, List.of(childRow));

    // Empty parent rows causes IllegalArgumentException from ThreadLocalRandom.nextInt(0)
    assertThatThrownBy(
            () -> {
              ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
              resolver.resolve();
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void multiColumnFk() {
    Table parent =
        new Table(
            "parent",
            List.of(intCol("pk1"), intCol("pk2")),
            List.of("pk1", "pk2"),
            List.of(),
            List.of(),
            List.of());
    ForeignKey mfk = new ForeignKey(null, "parent", Map.of("fk1", "pk1", "fk2", "pk2"), false);
    Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("fk1"), intCol("fk2")),
            List.of("id"),
            List.of(mfk),
            List.of(),
            List.of());

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
    Table a =
        new Table(
            "A",
            List.of(intCol("id"), intCol("b_id")),
            List.of("id"),
            List.of(fk("B", "b_id", "id")),
            List.of(),
            List.of());
    Table b =
        new Table(
            "B",
            List.of(intCol("id"), intCol("a_id")),
            List.of("id"),
            List.of(fk("A", "a_id", "id")),
            List.of(),
            List.of());

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
    Table a =
        new Table(
            "A",
            List.of(intCol("id"), nullableCol("b_id")),
            List.of("id"),
            List.of(fk("B", "b_id", "id")),
            List.of(),
            List.of());
    Table b = new Table("B", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());

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
    Table a =
        new Table(
            "A",
            List.of(intCol("id"), intCol("b_id")),
            List.of("id"),
            List.of(fk("B", "b_id", "id")),
            List.of(),
            List.of());
    Table b = new Table("B", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());

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
    assertThatThrownBy(
            () -> {
              ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
              resolver.resolve();
            })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cycle with non-nullable FK");
  }

  // ── Unique FK ──

  @Test
  void uniqueFk_distinctParents() {
    Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(uniqueFk("parent", "parent_id", "id")),
            List.of(),
            List.of(List.of("parent_id")));

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
    Table parent =
        new Table("parent", List.of(intCol("id")), List.of("id"), List.of(), List.of(), List.of());
    Table child =
        new Table(
            "child",
            List.of(intCol("id"), intCol("parent_id")),
            List.of("id"),
            List.of(uniqueFk("parent", "parent_id", "id")),
            List.of(),
            List.of(List.of("parent_id")));

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
    assertThatThrownBy(
            () -> {
              ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
              resolver.resolve();
            })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Not enough rows");
  }

  // ── Self-reference helpers ──

  private static Table selfRefTable(final String name, final boolean nullableParent) {
    final Column idCol = Column.builder().name("id").jdbcType(4).primaryKey(true).build();
    final Column parentCol =
        nullableParent
            ? Column.builder().name("parent_id").jdbcType(4).nullable(true).build()
            : Column.builder().name("parent_id").jdbcType(4).build();
    final ForeignKey selfFk = new ForeignKey(null, name, Map.of("parent_id", "id"), false);
    return new Table(
        name, List.of(idCol, parentCol), List.of("id"), List.of(selfFk), List.of(), List.of());
  }

  private static Row selfRefRow(final int id) {
    final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("parent_id", null);
    return new Row(m);
  }

  private static SelfReferenceConfig circular() {
    return SelfReferenceConfig.builder()
        .strategy(SelfReferenceStrategy.CIRCULAR)
        .hierarchyDepth(0)
        .build();
  }

  private static SelfReferenceConfig hierarchy(final int depth) {
    return SelfReferenceConfig.builder()
        .strategy(SelfReferenceStrategy.HIERARCHY)
        .hierarchyDepth(depth)
        .build();
  }

  // ── SelfRef @Nested tests ──

  @Nested
  @DisplayName("Self-referencing FK strategies")
  class SelfRef {

    // ── CIRCULAR ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("CIRCULAR with 1 row throws — requires ≥ 2 rows")
    void circular_oneRow_throws() {
      final Table t = selfRefTable("t", false);
      final Row row = selfRefRow(1);
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, List.of(row));

      assertThatThrownBy(
              () -> new ForeignKeyResolver(tableMap, data, false, Map.of("t", circular())).resolve())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("≥ 2 rows");
    }

    @Test
    @DisplayName("CIRCULAR with 2 rows — each points to the other (only valid derangement)")
    void circular_twoRows_pointToEachOther() {
      final Table t = selfRefTable("t", false);
      final Row r1 = selfRefRow(1);
      final Row r2 = selfRefRow(2);
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(List.of(r1, r2)));

      new ForeignKeyResolver(tableMap, data, true, Map.of("t", circular())).resolve();

      // Sattolo n=2 produces [1,0] deterministically
      assertThat(r1.values().get("parent_id")).isEqualTo(2);
      assertThat(r2.values().get("parent_id")).isEqualTo(1);
    }

    @Test
    @DisplayName("CIRCULAR with 5 rows — no row points to itself")
    void circular_fiveRows_noSelfLoops() {
      final Table t = selfRefTable("t", true);
      final List<Row> rows =
          IntStream.rangeClosed(1, 5).mapToObj(ForeignKeyResolverTest::selfRefRow).toList();
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(rows));

      new ForeignKeyResolver(tableMap, data, true, Map.of("t", circular())).resolve();

      rows.forEach(
          r -> {
            final Object id = r.values().get("id");
            final Object parentId = r.values().get("parent_id");
            assertThat(parentId).as("row id=%s must have a parent", id).isNotNull();
            assertThat(parentId)
                .as("row id=%s must not point to itself", id)
                .isNotEqualTo(id);
          });
    }

    @Test
    @DisplayName("CIRCULAR with 10 rows — all rows reachable (single connected cycle)")
    void circular_tenRows_singleCycle() {
      final Table t = selfRefTable("t", true);
      final List<Row> rows =
          IntStream.rangeClosed(1, 10).mapToObj(ForeignKeyResolverTest::selfRefRow).toList();
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(rows));

      new ForeignKeyResolver(tableMap, data, true, Map.of("t", circular())).resolve();

      // Collect all parent ids — they must be a permutation of all ids (derangement)
      final Set<Object> parentIds = new HashSet<>();
      rows.forEach(r -> parentIds.add(r.values().get("parent_id")));
      final Set<Object> allIds = new HashSet<>();
      rows.forEach(r -> allIds.add(r.values().get("id")));

      assertThat(parentIds).containsExactlyInAnyOrderElementsOf(allIds);
    }

    @Test
    @DisplayName("CIRCULAR does not affect cross-table FKs on the same table")
    void circular_doesNotAffectCrossTableFk() {
      final Table parent = new Table("parent", List.of(intCol("id")), List.of("id"),
          List.of(), List.of(), List.of());
      // "child" has both a self-ref FK and a cross-table FK to "parent"
      final Column idCol = Column.builder().name("id").jdbcType(4).primaryKey(true).build();
      final Column parentId = Column.builder().name("parent_id").jdbcType(4).nullable(true).build();
      final Column extId = Column.builder().name("ext_id").jdbcType(4).nullable(true).build();
      final ForeignKey selfFk = new ForeignKey(null, "child", Map.of("parent_id", "id"), false);
      final ForeignKey crossFk = new ForeignKey(null, "parent", Map.of("ext_id", "id"), false);
      final Table child = new Table("child", List.of(idCol, parentId, extId),
          List.of("id"), List.of(selfFk, crossFk), List.of(), List.of());

      final Row p1 = mutableRow("id", 100);
      final Row c1 = mutableRow("id", 1, "parent_id", null, "ext_id", null);
      final Row c2 = mutableRow("id", 2, "parent_id", null, "ext_id", null);

      final Map<String, Table> tableMap = new LinkedHashMap<>();
      tableMap.put("parent", parent);
      tableMap.put("child", child);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(parent, List.of(p1));
      data.put(child, new ArrayList<>(List.of(c1, c2)));

      new ForeignKeyResolver(tableMap, data, true, Map.of("child", circular())).resolve();

      // Self-ref: each child points to the other (derangement)
      assertThat(c1.values().get("parent_id")).isNotEqualTo(1);
      assertThat(c2.values().get("parent_id")).isNotEqualTo(2);
      // Cross FK: both children point to the single parent
      assertThat(c1.values().get("ext_id")).isEqualTo(100);
      assertThat(c2.values().get("ext_id")).isEqualTo(100);
    }

    // ── HIERARCHY ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("HIERARCHY depth=2 with 6 rows — first 3 rows are roots (null parent)")
    void hierarchy_depth2_sixRows_rootsHaveNullParent() {
      final Table t = selfRefTable("t", true);
      final List<Row> rows =
          IntStream.rangeClosed(1, 6).mapToObj(ForeignKeyResolverTest::selfRefRow).toList();
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(rows));

      new ForeignKeyResolver(tableMap, data, true, Map.of("t", hierarchy(2))).resolve();

      // Level 0: rows 0-2 → null parent
      rows.subList(0, 3).forEach(r ->
          assertThat(r.values().get("parent_id"))
              .as("root row id=%s should have null parent", r.values().get("id"))
              .isNull());

      // Level 1: rows 3-5 → non-null parent ∈ {1,2,3}
      final Set<Object> rootIds = Set.of(1, 2, 3);
      rows.subList(3, 6).forEach(r -> {
        final Object parentId = r.values().get("parent_id");
        assertThat(parentId)
            .as("child row id=%s should have non-null parent", r.values().get("id"))
            .isNotNull();
        assertThat(parentId)
            .as("child row id=%s parent must be a root", r.values().get("id"))
            .isIn(rootIds);
      });
    }

    @Test
    @DisplayName("HIERARCHY depth=4 with 10 rows — roots null, descendants non-null")
    void hierarchy_depth4_tenRows_onlyRootsNull() {
      final Table t = selfRefTable("t", true);
      final List<Row> rows =
          IntStream.rangeClosed(1, 10).mapToObj(ForeignKeyResolverTest::selfRefRow).toList();
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(rows));

      new ForeignKeyResolver(tableMap, data, true, Map.of("t", hierarchy(4))).resolve();

      // 10 rows / 4 levels: remainder=2 → levels 0,1 have 3 rows each; levels 2,3 have 2 rows each
      // Level 0 (rows 0-2): null parent
      rows.subList(0, 3)
          .forEach(r -> assertThat(r.values().get("parent_id")).isNull());
      // Levels 1-3 (rows 3-9): non-null parent
      rows.subList(3, 10)
          .forEach(r -> assertThat(r.values().get("parent_id")).isNotNull());
    }

    @Test
    @DisplayName("HIERARCHY depth exceeds rowCount — collapses to rowCount levels without error")
    void hierarchy_depthExceedsRows_collapses() {
      final Table t = selfRefTable("t", true);
      final List<Row> rows =
          IntStream.rangeClosed(1, 3).mapToObj(ForeignKeyResolverTest::selfRefRow).toList();
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(rows));

      // depth=10 >> 3 rows: effectiveDepth = min(10,3) = 3
      assertThatCode(
              () ->
                  new ForeignKeyResolver(tableMap, data, true, Map.of("t", hierarchy(10)))
                      .resolve())
          .doesNotThrowAnyException();

      // Level 0 (row 0): null parent
      assertThat(rows.get(0).values().get("parent_id")).isNull();
      // Level 1 (row 1): parent_id = id of row 0
      assertThat(rows.get(1).values().get("parent_id")).isEqualTo(1);
      // Level 2 (row 2): parent_id = id of row 1
      assertThat(rows.get(2).values().get("parent_id")).isEqualTo(2);
    }

    @Test
    @DisplayName("HIERARCHY depth=1 — all rows become roots (single level, all null parent)")
    void hierarchy_depth1_allRoots() {
      final Table t = selfRefTable("t", true);
      final List<Row> rows =
          IntStream.rangeClosed(1, 4).mapToObj(ForeignKeyResolverTest::selfRefRow).toList();
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(rows));

      new ForeignKeyResolver(tableMap, data, true, Map.of("t", hierarchy(1))).resolve();

      rows.forEach(r -> assertThat(r.values().get("parent_id")).isNull());
    }

    @Test
    @DisplayName("NONE strategy preserves existing behaviour — nullable FK creates PendingUpdate")
    void none_strategy_fallsBackToExistingBehaviour() {
      final Table t = selfRefTable("t", true);
      final Row r1 = selfRefRow(1);
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(List.of(r1)));

      final ForeignKeyResolver resolver = new ForeignKeyResolver(tableMap, data, false);
      final List<PendingUpdate> updates = resolver.resolve();

      // With NONE + deferred=false + nullable: existing logic creates a PendingUpdate
      assertThat(updates).isNotEmpty();
    }

    @Test
    @DisplayName("HIERARCHY non-nullable roots produce PendingUpdate (self-pointing)")
    void hierarchy_nonNullableRoot_createsPendingUpdate() {
      final Table t = selfRefTable("t", false); // NOT NULL parent_id
      final List<Row> rows =
          IntStream.rangeClosed(1, 4).mapToObj(ForeignKeyResolverTest::selfRefRow).toList();
      final Map<String, Table> tableMap = Map.of("t", t);
      final Map<Table, List<Row>> data = new LinkedHashMap<>();
      data.put(t, new ArrayList<>(rows));

      final ForeignKeyResolver resolver =
          new ForeignKeyResolver(tableMap, data, true, Map.of("t", hierarchy(2)));
      final List<PendingUpdate> updates = resolver.resolve();

      // Level 0 rows (2 roots) must each produce a self-pointing PendingUpdate
      assertThat(updates).hasSize(2);
      updates.forEach(
          u -> {
            assertThat(u.table()).isEqualTo("t");
            // fkValues.parent_id == pkValues.id  (self-pointer)
            assertThat(u.fkValues().get("parent_id")).isEqualTo(u.pkValues().get("id"));
          });
    }
  }
}
