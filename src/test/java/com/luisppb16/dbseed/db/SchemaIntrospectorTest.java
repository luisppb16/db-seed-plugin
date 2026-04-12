/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.luisppb16.dbseed.db.dialect.DatabaseDialect;
import com.luisppb16.dbseed.db.dialect.StandardDialect;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaIntrospectorTest {

  private final DatabaseDialect h2Dialect = new StandardDialect("h2.properties");
  private Connection conn;

  @BeforeEach
  void setUp() throws SQLException {
    conn = DriverManager.getConnection("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
  }

  @AfterEach
  void tearDown() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DROP ALL OBJECTS");
    }
    conn.close();
  }

  private void exec(final String sql) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
    }
  }

  private Table findTable(final List<Table> tables, final String name) {
    return tables.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst().orElse(null);
  }

  private Column findColumn(final Table table, final String name) {
    return table.columns().stream()
        .filter(c -> c.name().equalsIgnoreCase(name))
        .findFirst()
        .orElse(null);
  }

  // ── Basic ──

  @Test
  void singleTable() throws SQLException {
    exec("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100))");
    final List<Table> tables = SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect);
    assertThat(tables).hasSize(1);
    assertThat(tables.get(0).name()).isEqualToIgnoringCase("users");
  }

  @Test
  void multipleTables() throws SQLException {
    exec("CREATE TABLE a (id INT PRIMARY KEY)");
    exec("CREATE TABLE b (id INT PRIMARY KEY)");
    final List<Table> tables = SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect);
    assertThat(tables).hasSize(2);
  }

  @Test
  void noTables() throws SQLException {
    final List<Table> tables = SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect);
    assertThat(tables).isEmpty();
  }

  @Test
  void nullConnection_throwsNPE() {
    assertThatNullPointerException()
        .isThrownBy(() -> SchemaIntrospector.introspect(null, "PUBLIC", h2Dialect));
  }

  // ── Columns ──

  @Test
  void column_intType() throws SQLException {
    exec("CREATE TABLE t (val INT)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "VAL");
    assertThat(c.jdbcType()).isEqualTo(Types.INTEGER);
  }

  @Test
  void column_varcharLength() throws SQLException {
    exec("CREATE TABLE t (name VARCHAR(50))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "NAME");
    assertThat(c.length()).isEqualTo(50);
  }

  @Test
  void column_nullable() throws SQLException {
    exec("CREATE TABLE t (val INT)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "VAL");
    assertThat(c.nullable()).isTrue();
  }

  @Test
  void column_notNull() throws SQLException {
    exec("CREATE TABLE t (val INT NOT NULL)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "VAL");
    assertThat(c.nullable()).isFalse();
  }

  @Test
  void column_decimalScalePrecision() throws SQLException {
    exec("CREATE TABLE t (price DECIMAL(10, 3))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "PRICE");
    assertThat(c.length()).isEqualTo(10);
    assertThat(c.scale()).isEqualTo(3);
  }

  @Test
  void column_uuidFlag() throws SQLException {
    exec("CREATE TABLE t (id UUID PRIMARY KEY)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "ID");
    assertThat(c.uuid()).isTrue();
  }

  // ── Primary Keys ──

  @Test
  void singlePk() throws SQLException {
    exec("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    assertThat(t.primaryKey()).containsExactly("ID");
  }

  @Test
  void compositePk() throws SQLException {
    exec("CREATE TABLE t (a INT, b INT, PRIMARY KEY (a, b))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    assertThat(t.primaryKey()).containsExactlyInAnyOrder("A", "B");
  }

  @Test
  void noPk() throws SQLException {
    exec("CREATE TABLE t (val INT)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    assertThat(t.primaryKey()).isEmpty();
  }

  // ── Foreign Keys ──

  @Test
  void singleFkMapping() throws SQLException {
    exec("CREATE TABLE parent (id INT PRIMARY KEY)");
    exec("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT REFERENCES parent(id))");
    final Table child = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "CHILD");
    assertThat(child.foreignKeys()).hasSize(1);
    final ForeignKey fk = child.foreignKeys().get(0);
    assertThat(fk.pkTable()).isEqualToIgnoringCase("parent");
    assertThat(fk.columnMapping()).containsEntry("PARENT_ID", "ID");
  }

  @Test
  void compositeFk() throws SQLException {
    exec("CREATE TABLE parent (a INT, b INT, PRIMARY KEY (a, b))");
    exec(
        "CREATE TABLE child (id INT PRIMARY KEY, fa INT, fb INT, FOREIGN KEY (fa, fb) REFERENCES parent(a, b))");
    final Table child = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "CHILD");
    final ForeignKey fk = child.foreignKeys().get(0);
    assertThat(fk.columnMapping()).hasSize(2);
  }

  @Test
  void fkWithUnique_uniqueOnFkTrue() throws SQLException {
    exec("CREATE TABLE parent (id INT PRIMARY KEY)");
    exec("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT UNIQUE REFERENCES parent(id))");
    final Table child = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "CHILD");
    final ForeignKey fk = child.foreignKeys().get(0);
    assertThat(fk.uniqueOnFk()).isTrue();
  }

  @Test
  void fkWithoutUnique() throws SQLException {
    exec("CREATE TABLE parent (id INT PRIMARY KEY)");
    exec("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT REFERENCES parent(id))");
    final Table child = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "CHILD");
    final ForeignKey fk = child.foreignKeys().get(0);
    assertThat(fk.uniqueOnFk()).isFalse();
  }

  // ── Check constraints ──

  @Test
  void checkConstraint_betweenBounds() throws SQLException {
    exec("CREATE TABLE t (val INT CHECK (val BETWEEN 1 AND 100))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "VAL");
    assertThat(c.minValue()).isEqualTo(1);
    assertThat(c.maxValue()).isEqualTo(100);
  }

  @Test
  void checkConstraint_inListAllowedValues() throws SQLException {
    exec("CREATE TABLE t (status VARCHAR(20) CHECK (status IN ('A', 'B', 'C')))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "STATUS");
    assertThat(c.allowedValues()).containsExactlyInAnyOrder("A", "B", "C");
  }

  @Test
  void checkConstraint_rawCheckInTable() throws SQLException {
    exec("CREATE TABLE t (val INT CHECK (val >= 5 AND val <= 50))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    assertThat(t.checks()).isNotEmpty();
  }

  // ── Unique keys ──

  @Test
  void uniqueConstraint() throws SQLException {
    exec("CREATE TABLE t (id INT PRIMARY KEY, email VARCHAR(100) UNIQUE)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    assertThat(t.uniqueKeys()).isNotEmpty();
    assertThat(t.uniqueKeys().stream().anyMatch(uk -> uk.contains("EMAIL"))).isTrue();
  }

  @Test
  void compositeUnique() throws SQLException {
    exec("CREATE TABLE t (id INT PRIMARY KEY, a INT, b INT, UNIQUE(a, b))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    assertThat(t.uniqueKeys().stream().anyMatch(uk -> uk.containsAll(List.of("A", "B")))).isTrue();
  }

  // ── UUID detection ──

  @Test
  void column_uuidByTypeName_detected() throws SQLException {
    exec("CREATE TABLE t (id UUID)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "ID");
    assertThat(c.uuid()).isTrue();
  }

  @Test
  void column_regularVarchar_notUuid() throws SQLException {
    exec("CREATE TABLE t (name VARCHAR(100))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "NAME");
    assertThat(c.uuid()).isFalse();
  }

  @Test
  void column_intType_notUuid() throws SQLException {
    exec("CREATE TABLE t (id INT)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "ID");
    assertThat(c.uuid()).isFalse();
  }

  @Test
  void column_booleanType_notUuid() throws SQLException {
    exec("CREATE TABLE t (active BOOLEAN)");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "ACTIVE");
    assertThat(c.uuid()).isFalse();
  }

  // ── Integer overflow protection in check constraints ──

  @Test
  void checkConstraint_largeBoundsStillWorks() throws SQLException {
    exec("CREATE TABLE t (val INT CHECK (val BETWEEN 0 AND 2147483647))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column c = findColumn(t, "VAL");
    assertThat(c.minValue()).isEqualTo(0);
    assertThat(c.maxValue()).isEqualTo(2147483647);
  }

  @Test
  void checkConstraint_noExceptionOnExtremeValues() throws SQLException {
    exec("CREATE TABLE t (val BIGINT CHECK (val >= -100 AND val <= 100))");
    final List<Table> tables = SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect);
    assertThat(tables).isNotEmpty();
  }

  // ── Multiple tables with mixed types ──

  @Test
  void mixedColumnTypes_allDetected() throws SQLException {
    exec(
        "CREATE TABLE t ("
            + "id INT PRIMARY KEY, "
            + "name VARCHAR(50), "
            + "price DECIMAL(10,2), "
            + "active BOOLEAN, "
            + "created TIMESTAMP, "
            + "uid UUID"
            + ")");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    assertThat(t.columns()).hasSize(6);
    assertThat(findColumn(t, "ID").jdbcType()).isEqualTo(Types.INTEGER);
    assertThat(findColumn(t, "NAME").jdbcType()).isEqualTo(Types.VARCHAR);
    assertThat(findColumn(t, "UID").uuid()).isTrue();
  }

  // ── Schema filter ──

  @Test
  void schemaFilter_specificSchemaOnly() throws SQLException {
    exec("CREATE SCHEMA IF NOT EXISTS test_schema");
    exec("CREATE TABLE test_schema.t1 (id INT PRIMARY KEY)");
    exec("CREATE TABLE t2 (id INT PRIMARY KEY)");

    final List<Table> filtered = SchemaIntrospector.introspect(conn, "TEST_SCHEMA", h2Dialect);
    assertThat(filtered).hasSize(1);
    assertThat(filtered.get(0).name()).isEqualToIgnoringCase("T1");
  }

  @Test
  void decimalBounds_preserved() throws SQLException {
    exec("CREATE TABLE t (id INT PRIMARY KEY, val DECIMAL(5,2) CHECK (val BETWEEN 1.5 AND 99.5))");
    final Table t = findTable(SchemaIntrospector.introspect(conn, "PUBLIC", h2Dialect), "T");
    final Column val = findColumn(t, "VAL");
    assertThat(val.minValue()).isEqualTo(1.5);
    assertThat(val.maxValue()).isEqualTo(99.5);
  }
}