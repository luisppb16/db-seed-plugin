/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.schema;

import static com.luisppb16.dbseed.schema.SchemaDsl.column;
import static com.luisppb16.dbseed.schema.SchemaDsl.fk;
import static com.luisppb16.dbseed.schema.SchemaDsl.pk;
import static com.luisppb16.dbseed.schema.SchemaDsl.schema;
import static com.luisppb16.dbseed.schema.SchemaDsl.table;
import static com.luisppb16.dbseed.schema.SchemaDsl.toSql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.luisppb16.dbseed.schema.SchemaDsl.Column;
import com.luisppb16.dbseed.schema.SchemaDsl.Schema;
import com.luisppb16.dbseed.schema.SchemaDsl.Table;
import org.junit.jupiter.api.Test;

class SchemaDslTest {

  // ── Construction ──

  @Test
  void schema_withTables() {
    final Schema s = schema(table("users", column("id", SqlType.INT)));
    assertThat(s.tables()).hasSize(1);
    assertThat(s.tables().get(0).name()).isEqualTo("users");
  }

  @Test
  void table_columns() {
    final Table t = table("t", column("a", SqlType.INT), column("b", SqlType.VARCHAR));
    assertThat(t.columns()).hasSize(2);
  }

  @Test
  void pk_column() {
    final Column c = pk("id", SqlType.INT);
    assertThat(c.primaryKey()).isTrue();
    assertThat(c.notNull()).isTrue();
    assertThat(c.unique()).isTrue();
  }

  @Test
  void fk_column() {
    final Column c = fk("user_id", SqlType.INT, "users", "id");
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
    final Schema s = schema(table("users", pk("id", SqlType.INT)));
    final String sql = toSql(s);
    assertThat(sql).contains("CREATE TABLE \"users\"");
    assertThat(sql).contains("\"id\" INT PRIMARY KEY");
  }

  @Test
  void toSql_fkReferences() {
    final Schema s =
        schema(table("orders", pk("id", SqlType.INT), fk("user_id", SqlType.INT, "users", "id")));
    final String sql = toSql(s);
    assertThat(sql).contains("REFERENCES \"users\"(\"id\")");
  }

  @Test
  void toSql_defaultClause() {
    final Schema s = schema(table("t", column("active", SqlType.BOOLEAN, true, "TRUE", false)));
    final String sql = toSql(s);
    assertThat(sql).contains("DEFAULT TRUE");
  }

  @Test
  void toSql_emptyColumnsTable() {
    final Schema s = schema(table("empty"));
    final String sql = toSql(s);
    assertThat(sql).contains("CREATE TABLE \"empty\" ()");
  }

  @Test
  void toSql_multipleTables() {
    final Schema s =
        schema(table("a", column("x", SqlType.INT)), table("b", column("y", SqlType.TEXT)));
    final String sql = toSql(s);
    assertThat(sql).contains("CREATE TABLE \"a\"");
    assertThat(sql).contains("CREATE TABLE \"b\"");
  }

  @Test
  void toSql_columnOrderPreserved() {
    final Schema s = schema(table("t", column("z", SqlType.INT), column("a", SqlType.INT)));
    final String sql = toSql(s);
    final int zIdx = sql.indexOf("\"z\" INT");
    final int aIdx = sql.indexOf("\"a\" INT");
    assertThat(zIdx).isLessThan(aIdx);
  }

  // ── Identifier quoting ──

  @Test
  void toSql_reservedWord_quoted() {
    final Schema s = schema(table("order", column("select", SqlType.INT)));
    final String sql = toSql(s);
    assertThat(sql).contains("CREATE TABLE \"order\"");
    assertThat(sql).contains("\"select\" INT");
  }

  @Test
  void toSql_specialCharsInName_quoted() {
    final Schema s = schema(table("my table", column("my column", SqlType.INT)));
    final String sql = toSql(s);
    assertThat(sql).contains("\"my table\"");
    assertThat(sql).contains("\"my column\"");
  }

  @Test
  void toSql_embeddedDoubleQuote_escaped() {
    final Schema s = schema(table("tab\"le", column("co\"l", SqlType.INT)));
    final String sql = toSql(s);
    assertThat(sql).contains("\"tab\"\"le\"");
    assertThat(sql).contains("\"co\"\"l\"");
  }

  @Test
  void toSql_sqlInjectionInTableName_safe() {
    final Schema s = schema(table("users\"; DROP TABLE users; --", column("id", SqlType.INT)));
    final String sql = toSql(s);
    assertThat(sql).contains("\"users\"\"; DROP TABLE users; --\"");
    assertThat(sql).doesNotContain("CREATE TABLE \"users\";");
  }

  @Test
  void toSql_fkReferences_quoted() {
    final Schema s =
        schema(
            table(
                "child",
                pk("id", SqlType.INT),
                fk("parent_id", SqlType.INT, "parent table", "pk col")));
    final String sql = toSql(s);
    assertThat(sql).contains("REFERENCES \"parent table\"(\"pk col\")");
  }

  @Test
  void toSql_uniqueAndNotNull_withQuoting() {
    final Schema s = schema(table("t", column("name", SqlType.VARCHAR, true, null, true)));
    final String sql = toSql(s);
    assertThat(sql).contains("\"name\" VARCHAR(255) NOT NULL");
    assertThat(sql).contains("UNIQUE");
  }
}
