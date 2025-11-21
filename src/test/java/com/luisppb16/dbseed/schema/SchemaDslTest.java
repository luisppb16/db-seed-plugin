/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.schema.SchemaDsl.Column;
import com.luisppb16.dbseed.schema.SchemaDsl.Schema;
import com.luisppb16.dbseed.schema.SchemaDsl.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaDslTest {

  @Test
  @DisplayName("Should build schema with tables and columns")
  void shouldBuildSchema() {
    Schema schema =
        SchemaDsl.schema(
            SchemaDsl.table(
                "users",
                SchemaDsl.pk("id", SqlType.INT),
                SchemaDsl.column("name", SqlType.VARCHAR)),
            SchemaDsl.table(
                "posts",
                SchemaDsl.pk("id", SqlType.INT),
                SchemaDsl.column("title", SqlType.VARCHAR),
                SchemaDsl.fk("user_id", SqlType.INT, "users", "id")));

    assertNotNull(schema);
    assertEquals(2, schema.tables().size());

    Table users = schema.tables().getFirst();
    assertEquals("users", users.name());
    assertEquals(2, users.columns().size());
    assertTrue(users.columns().getFirst().primaryKey());
    assertEquals("id", users.columns().getFirst().name());
    assertEquals(SqlType.INT, users.columns().getFirst().type());
    assertEquals("name", users.columns().get(1).name());
    assertEquals(SqlType.VARCHAR, users.columns().get(1).type());

    Table posts = schema.tables().get(1);
    assertEquals("posts", posts.name());
    assertEquals(3, posts.columns().size());
    assertTrue(posts.columns().getFirst().primaryKey());
    assertEquals("id", posts.columns().getFirst().name());
    assertEquals(SqlType.INT, posts.columns().getFirst().type());
    assertEquals("title", posts.columns().get(1).name());
    assertEquals(SqlType.VARCHAR, posts.columns().get(1).type());

    Column fkCol = posts.columns().get(2);
    assertEquals("user_id", fkCol.name());
    assertEquals(SqlType.INT, fkCol.type());
    assertTrue(fkCol.isForeignKey());
    assertNotNull(fkCol.foreignKey());
    assertEquals("users", fkCol.foreignKey().table());
    assertEquals("id", fkCol.foreignKey().column());
  }

  @Test
  @DisplayName("Should generate SQL for a schema with multiple tables and foreign keys")
  void shouldGenerateSqlWithMultipleTablesAndForeignKeys() {
    Schema schema =
        SchemaDsl.schema(
            SchemaDsl.table(
                "users",
                SchemaDsl.pk("id", SqlType.INT),
                SchemaDsl.column("name", SqlType.VARCHAR)),
            SchemaDsl.table(
                "posts",
                SchemaDsl.pk("id", SqlType.INT),
                SchemaDsl.column("title", SqlType.VARCHAR),
                SchemaDsl.fk("user_id", SqlType.INT, "users", "id")));

    String sql = SchemaDsl.toSql(schema);

    String expectedUsersTableSql =
        "CREATE TABLE users (  id INT PRIMARY KEY NOT NULL UNIQUE,  name VARCHAR(255));";
    String expectedPostsTableSql =
        "CREATE TABLE posts (  id INT PRIMARY KEY NOT NULL UNIQUE,  title VARCHAR(255),  user_id INT REFERENCES users(id));";

    assertTrue(sql.contains(expectedUsersTableSql));
    assertTrue(sql.contains(expectedPostsTableSql));
  }

  @Test
  @DisplayName("Should generate SQL for various column types and constraints")
  void shouldGenerateSqlWithVariousColumnTypesAndConstraints() {
    Schema schema =
        SchemaDsl.schema(
            SchemaDsl.table(
                "products",
                SchemaDsl.pk("product_id", SqlType.INT),
                SchemaDsl.column("name", SqlType.VARCHAR, true, null, false), // notNull
                SchemaDsl.column(
                    "description", SqlType.TEXT, false, null, false), // nullable (default)
                SchemaDsl.column("price", SqlType.DECIMAL, false, "0.00", false), // defaultValue
                SchemaDsl.column(
                    "is_active", SqlType.BOOLEAN, false, "TRUE", false), // defaultValue
                SchemaDsl.column("created_at", SqlType.TIMESTAMP, true, null, false), // notNull
                SchemaDsl.column("unique_code", SqlType.VARCHAR, false, null, true))); // unique

    String sql = SchemaDsl.toSql(schema);

    String expectedProductsTableSql =
        """
        CREATE TABLE products (
          product_id INT PRIMARY KEY NOT NULL UNIQUE,
          name VARCHAR(255) NOT NULL,
          description TEXT,
          price DECIMAL(10, 2) DEFAULT 0.00,
          is_active BOOLEAN DEFAULT TRUE,
          created_at TIMESTAMP NOT NULL,
          unique_code VARCHAR(255) UNIQUE
        );""";

    assertTrue(sql.contains(expectedProductsTableSql));
  }

  @Test
  @DisplayName("Should generate SQL for an empty schema")
  void shouldGenerateSqlForEmptySchema() {
    Schema schema = SchemaDsl.schema();
    String sql = SchemaDsl.toSql(schema);
    assertEquals("", sql.trim());
  }

  @Test
  @DisplayName("Should generate SQL for a table with no columns (edge case)")
  void shouldGenerateSqlForTableWithNoColumns() {
    Schema schema = SchemaDsl.schema(SchemaDsl.table("empty_table"));
    String sql = SchemaDsl.toSql(schema);
    String expectedSql = "CREATE TABLE empty_table ();";
    assertTrue(sql.contains(expectedSql));
  }
}
