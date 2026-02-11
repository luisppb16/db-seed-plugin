/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.schema;

import static com.luisppb16.dbseed.schema.SchemaDsl.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SchemaDslTest {

  // ── Construction ──

  @Test
  void schema_withTables() {
    Schema s = schema(table("users", column("id", SqlType.INT)));
    assertThat(s.tables()).hasSize(1);
    assertThat(s.tables().get(0).name()).isEqualTo("users");
  }

  @Test
  void schema_nullTables_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new SchemaDsl.Schema(null));
  }

  @Test
  void table_columns() {
    Table t = table("t", column("a", SqlType.INT), column("b", SqlType.VARCHAR));
    assertThat(t.columns()).hasSize(2);
  }

  @Test
  void table_nullName_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> table(null, column("a", SqlType.INT)));
  }

  @Test
  void pk_column() {
    Column c = pk("id", SqlType.INT);
    assertThat(c.primaryKey()).isTrue();
    assertThat(c.notNull()).isTrue();
    assertThat(c.unique()).isTrue();
  }

  @Test
  void fk_column() {
    Column c = fk("user_id", SqlType.INT, "users", "id");
    assertThat(c.isForeignKey()).isTrue();
    assertThat(c.foreignKey().table()).isEqualTo("users");
    assertThat(c.foreignKey().column()).isEqualTo("id");
  }

  @Test
  void fkRef_nulls_throwNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> new SchemaDsl.ForeignKeyReference(null, "id"));
    assertThatNullPointerException()
        .isThrownBy(() -> new SchemaDsl.ForeignKeyReference("users", null));
  }

  // ── toSql ──

  @Test
  void toSql_singleTableWithPk() {
    Schema s = schema(table("users", pk("id", SqlType.INT)));
    String sql = toSql(s);
    assertThat(sql).contains("CREATE TABLE users");
    assertThat(sql).contains("id INT PRIMARY KEY");
  }

  @Test
  void toSql_fkReferences() {
    Schema s =
        schema(
            table(
                "orders",
                pk("id", SqlType.INT),
                fk("user_id", SqlType.INT, "users", "id")));
    String sql = toSql(s);
    assertThat(sql).contains("REFERENCES users(id)");
  }

  @Test
  void toSql_defaultClause() {
    Schema s = schema(table("t", column("active", SqlType.BOOLEAN, true, "TRUE", false)));
    String sql = toSql(s);
    assertThat(sql).contains("DEFAULT TRUE");
  }

  @Test
  void toSql_emptyColumnsTable() {
    Schema s = schema(table("empty"));
    String sql = toSql(s);
    assertThat(sql).contains("CREATE TABLE empty ()");
  }

  @Test
  void toSql_multipleTables() {
    Schema s = schema(table("a", column("x", SqlType.INT)), table("b", column("y", SqlType.TEXT)));
    String sql = toSql(s);
    assertThat(sql).contains("CREATE TABLE a");
    assertThat(sql).contains("CREATE TABLE b");
  }

  @Test
  void toSql_columnOrderPreserved() {
    Schema s = schema(table("t", column("z", SqlType.INT), column("a", SqlType.INT)));
    String sql = toSql(s);
    int zIdx = sql.indexOf("z INT");
    int aIdx = sql.indexOf("a INT");
    assertThat(zIdx).isLessThan(aIdx);
  }
}
