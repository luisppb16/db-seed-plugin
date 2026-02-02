/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexSchemaTest {

  @Test
  @DisplayName("Should handle circular dependencies with self-referencing table")
  void circularDependencySelfReferencing() {
    // Table: Employee (id, manager_id -> Employee.id)
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).primaryKey(true).build();
    Column managerId = Column.builder().name("manager_id").jdbcType(Types.INTEGER).nullable(true).build();

    ForeignKey fk = new ForeignKey("fk_emp_mgr", "employee", Map.of("manager_id", "id"), false);

    Table employee = Table.builder()
        .name("employee")
        .columns(List.of(id, managerId))
        .primaryKey(List.of("id"))
        .foreignKeys(List.of(fk))
        .checks(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();

    DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
        .tables(List.of(employee))
        .rowsPerTable(10)
        .excludedColumns(Collections.emptyMap())
        .build();

    DataGenerator.GenerationResult result = DataGenerator.generate(params);

    List<Row> rows = result.rows().get(employee);
    assertThat(rows).hasSize(10);

    // Verify that manager_id refers to existing IDs or is null
    List<Object> ids = rows.stream().map(r -> r.values().get("id")).toList();
    assertThat(rows).allSatisfy(row -> {
      Object mgrId = row.values().get("manager_id");
      if (mgrId != null) {
        assertThat(ids).contains(mgrId);
      }
    });
  }

  @Test
  @DisplayName("Should handle mutual dependency (A -> B -> A)")
  void mutualDependency() {
    Column aId = Column.builder().name("id").jdbcType(Types.INTEGER).primaryKey(true).build();
    Column aBId = Column.builder().name("b_id").jdbcType(Types.INTEGER).nullable(true).build();
    
    Column bId = Column.builder().name("id").jdbcType(Types.INTEGER).primaryKey(true).build();
    Column bAId = Column.builder().name("a_id").jdbcType(Types.INTEGER).nullable(true).build();

    ForeignKey fkAtoB = new ForeignKey("fk_a_b", "table_b", Map.of("b_id", "id"), false);
    ForeignKey fkBtoA = new ForeignKey("fk_b_a", "table_a", Map.of("a_id", "id"), false);

    Table tableA = Table.builder()
        .name("table_a")
        .columns(List.of(aId, aBId))
        .primaryKey(List.of("id"))
        .foreignKeys(List.of(fkAtoB))
        .checks(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();

    Table tableB = Table.builder()
        .name("table_b")
        .columns(List.of(bId, bAId))
        .primaryKey(List.of("id"))
        .foreignKeys(List.of(fkBtoA))
        .checks(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();

    DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
        .tables(List.of(tableA, tableB))
        .rowsPerTable(10)
        .excludedColumns(Collections.emptyMap())
        .build();

    DataGenerator.GenerationResult result = DataGenerator.generate(params);

    List<Row> rowsA = result.rows().get(tableA);
    List<Row> rowsB = result.rows().get(tableB);

    assertThat(rowsA).hasSize(10);
    assertThat(rowsB).hasSize(10);

    List<Object> idsA = rowsA.stream().map(r -> r.values().get("id")).toList();
    List<Object> idsB = rowsB.stream().map(r -> r.values().get("id")).toList();

    assertThat(rowsA).allSatisfy(row -> {
      Object val = row.values().get("b_id");
      if (val != null) {
        assertThat(idsB).contains(val);
      }
    });

    assertThat(rowsB).allSatisfy(row -> {
      Object val = row.values().get("a_id");
      if (val != null) {
        assertThat(idsA).contains(val);
      }
    });
  }

  @Test
  @DisplayName("Should handle Postgres ANY ARRAY check constraint")
  void handlePostgresAnyArrayCheck() {
    Column actorGuid = Column.builder()
        .name("actorGUID")
        .jdbcType(Types.OTHER)
        .primaryKey(true)
        .uuid(true)
        .build();
    
    Column actorType = Column.builder()
        .name("actorType")
        .jdbcType(Types.VARCHAR)
        .nullable(false)
        .build();

    String checkConstraint = "\"actorType\" = ANY (ARRAY ['Person'::text, 'Cyberpersona'::text, 'Organization'::text, 'Audience'::text])";

    Table actor = Table.builder()
        .name("Actor")
        .columns(List.of(actorGuid, actorType))
        .primaryKey(List.of("actorGUID"))
        .checks(List.of(checkConstraint))
        .foreignKeys(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();

    DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
        .tables(List.of(actor))
        .rowsPerTable(20)
        .excludedColumns(Collections.emptyMap())
        .build();

    DataGenerator.GenerationResult result = DataGenerator.generate(params);

    List<Row> rows = result.rows().get(actor);
    assertThat(rows).hasSize(20);

    Set<String> allowedValues = Set.of("Person", "Cyberpersona", "Organization", "Audience");
    
    assertThat(rows).allSatisfy(row -> {
      String type = (String) row.values().get("actorType");
      assertThat(allowedValues).contains(type);
    });
  }

  @Test
  @DisplayName("Should handle Postgres ANY ARRAY with casts and parentheses")
  void handlePostgresAnyArrayWithCasts() {
    Column rootStatus = Column.builder()
        .name("rootStatus")
        .jdbcType(Types.VARCHAR)
        .nullable(false)
        .build();

    String checkConstraint = "((\"rootStatus\")::text = ANY ((ARRAY ['Active'::character varying, 'Deleted'::character varying])::text[]))";

    Table actor = Table.builder()
        .name("Actor")
        .columns(List.of(rootStatus))
        .primaryKey(Collections.emptyList())
        .checks(List.of(checkConstraint))
        .foreignKeys(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();

    DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
        .tables(List.of(actor))
        .rowsPerTable(10)
        .excludedColumns(Collections.emptyMap())
        .build();

    DataGenerator.GenerationResult result = DataGenerator.generate(params);

    List<Row> rows = result.rows().get(actor);
    Set<String> allowedValues = Set.of("Active", "Deleted");

    assertThat(rows).allSatisfy(row -> {
      String status = (String) row.values().get("rootStatus");
      assertThat(allowedValues).contains(status);
    });
  }

  @Test
  @DisplayName("Should handle complex hierarchy from schema.sql")
  void handleComplexHierarchy() {
    Column actorGuid = Column.builder().name("actorGUID").jdbcType(Types.OTHER).primaryKey(true).uuid(true).build();
    Column actorName = Column.builder().name("actorName").jdbcType(Types.VARCHAR).nullable(false).build();
    Table actorTable = Table.builder()
        .name("Actor")
        .columns(List.of(actorGuid, actorName))
        .primaryKey(List.of("actorGUID"))
        .checks(Collections.emptyList())
        .foreignKeys(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();

    Column personGuid = Column.builder().name("actorGUID").jdbcType(Types.OTHER).primaryKey(true).uuid(true).build();
    ForeignKey personToActor = new ForeignKey("FK_Person_Actor", "Actor", Map.of("actorGUID", "actorGUID"), true);
    Table personTable = Table.builder()
        .name("Person")
        .columns(List.of(personGuid))
        .primaryKey(List.of("actorGUID"))
        .checks(Collections.emptyList())
        .foreignKeys(List.of(personToActor))
        .uniqueKeys(Collections.emptyList())
        .build();

    Column relGuid = Column.builder().name("actorRelationGUID").jdbcType(Types.OTHER).primaryKey(true).uuid(true).build();
    Column guid1 = Column.builder().name("actorGUID1").jdbcType(Types.OTHER).nullable(false).uuid(true).build();
    Column guid2 = Column.builder().name("actorGUID2").jdbcType(Types.OTHER).nullable(false).uuid(true).build();
    ForeignKey fk1 = new ForeignKey("FK_ActorRelation_Actor", "Actor", Map.of("actorGUID1", "actorGUID"), false);
    ForeignKey fk2 = new ForeignKey("FK_ActorRelation_Actor_02", "Actor", Map.of("actorGUID2", "actorGUID"), false);
    
    Table actorRelationTable = Table.builder()
        .name("ActorRelation")
        .columns(List.of(relGuid, guid1, guid2))
        .primaryKey(List.of("actorRelationGUID"))
        .checks(Collections.emptyList())
        .foreignKeys(List.of(fk1, fk2))
        .uniqueKeys(Collections.emptyList())
        .build();

    DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
        .tables(List.of(actorTable, personTable, actorRelationTable))
        .rowsPerTable(10)
        .excludedColumns(Collections.emptyMap())
        .build();

    DataGenerator.GenerationResult result = DataGenerator.generate(params);

    assertThat(result.rows().get(actorTable)).hasSize(10);
    assertThat(result.rows().get(personTable)).hasSize(10);
    assertThat(result.rows().get(actorRelationTable)).hasSize(10);
    
    List<Object> actorGuids = result.rows().get(actorTable).stream().map(r -> r.values().get("actorGUID")).toList();
    List<Object> personGuids = result.rows().get(personTable).stream().map(r -> r.values().get("actorGUID")).toList();
    assertThat(actorGuids).containsAll(personGuids);
    
    result.rows().get(actorRelationTable).forEach(row -> {
        assertThat(actorGuids).contains(row.values().get("actorGUID1"));
        assertThat(actorGuids).contains(row.values().get("actorGUID2"));
    });
  }
}
