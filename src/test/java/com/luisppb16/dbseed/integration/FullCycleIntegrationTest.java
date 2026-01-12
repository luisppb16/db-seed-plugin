/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.model.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FullCycleIntegrationTest {

  @Test
  @DisplayName("Full cycle: Standard PK/FK and Check Constraints")
  void testStandardCycle() throws Exception {
    String url = "jdbc:h2:mem:standard;DB_CLOSE_DELAY=-1";
    try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            "CREATE TABLE USERS ("
                + "id INT PRIMARY KEY, "
                + "name VARCHAR(100) NOT NULL, "
                + "age INT, "
                + "CHECK (age >= 18 AND age <= 100))");

        stmt.execute(
            "CREATE TABLE ORDERS ("
                + "order_id INT PRIMARY KEY, "
                + "user_id INT NOT NULL, "
                + "amount DECIMAL(10, 2) NOT NULL, "
                + "FOREIGN KEY (user_id) REFERENCES USERS(id))");
      }

      executeCycleAndVerify(conn, 2, Map.of("USERS", 10, "ORDERS", 10));
    }
  }

  @Test
  @DisplayName("Full cycle: Composite Primary Keys and Unique Constraints")
  void testCompositeKeysAndUnique() throws Exception {
    String url = "jdbc:h2:mem:composite;DB_CLOSE_DELAY=-1";
    try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
      try (Statement stmt = conn.createStatement()) {
        // Composite PK (dept_id, emp_id)
        stmt.execute(
            "CREATE TABLE EMPLOYEES ("
                + "dept_id INT, "
                + "emp_id INT, "
                + "email VARCHAR(255), "
                + "PRIMARY KEY (dept_id, emp_id), "
                + "UNIQUE (email))");

        // FK referencing composite PK
        stmt.execute(
            "CREATE TABLE ASSIGNMENTS ("
                + "task_id INT PRIMARY KEY, "
                + "dept_id INT, "
                + "emp_id INT, "
                + "FOREIGN KEY (dept_id, emp_id) REFERENCES EMPLOYEES(dept_id, emp_id))");
      }

      executeCycleAndVerify(conn, 2, Map.of("EMPLOYEES", 20, "ASSIGNMENTS", 20));

      // Verify composite PK uniqueness is implicit by insertion success
      // Verify unique constraint on email by checking generated data
      // Note: The insertion success implicitly verifies uniqueness enforcement by the DB.
      // We explicitly check that distinct count matches non-null count to verify generator
      // behavior.

      try (Statement stmt = conn.createStatement();
          ResultSet rs =
              stmt.executeQuery("SELECT COUNT(*) FROM EMPLOYEES WHERE email IS NOT NULL")) {
        assertTrue(rs.next());
        int nonNullCount = rs.getInt(1);

        try (ResultSet rs2 = stmt.executeQuery("SELECT COUNT(DISTINCT email) FROM EMPLOYEES")) {
          assertTrue(rs2.next());
          assertEquals(nonNullCount, rs2.getInt(1), "All non-null emails must be unique");
        }
      }
    }
  }

  @Test
  @DisplayName("Full cycle: Circular Dependencies (Self-Referencing)")
  void testCircularDependencies() throws Exception {
    String url = "jdbc:h2:mem:circular;DB_CLOSE_DELAY=-1";
    try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
      try (Statement stmt = conn.createStatement()) {
        // Self-referencing table: Manager is also an employee
        // Manager ID is nullable to allow breaking the cycle (root node) or deferred update if
        // non-nullable (but infinite recursion if strict)
        // H2 allows deferred constraints? Application logic handles it by inserting NULL first if
        // nullable.
        stmt.execute(
            "CREATE TABLE STAFF ("
                + "id INT PRIMARY KEY, "
                + "name VARCHAR(100), "
                + "manager_id INT, "
                + "FOREIGN KEY (manager_id) REFERENCES STAFF(id))");
      }

      // Introspect & Generate
      List<Table> tables = SchemaIntrospector.introspect(conn, "PUBLIC");
      assertEquals(1, tables.size());

      DataGenerator.GenerationResult genResult = generateData(tables, 50);

      // Verify that updates are generated if cycles are detected and handled via updates.
      // For non-deferred generation, cycles are broken by inserting NULL (if nullable)
      // and adding an UPDATE statement.
      assertFalse(
          genResult.updates().isEmpty(), "Should have pending updates for self-reference cycle");

      // Execute SQL
      String sql = SqlGenerator.generate(genResult.rows(), genResult.updates(), false);
      executeSql(conn, sql);

      // Verify data
      try (Statement stmt = conn.createStatement();
          ResultSet rs =
              stmt.executeQuery("SELECT COUNT(*) FROM STAFF WHERE manager_id IS NOT NULL")) {
        assertTrue(rs.next());
        // Most rows should have a manager (randomly picked from 50 rows)
        // Since updates apply to all rows to set FKs.
        assertTrue(rs.getInt(1) > 0, "Some staff should have managers");
      }
    }
  }

  @Test
  @DisplayName("Full cycle: Data Types and Check Variants")
  void testDataTypesAndChecks() throws Exception {
    String url = "jdbc:h2:mem:types;DB_CLOSE_DELAY=-1";
    try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            "CREATE TABLE PRODUCTS ("
                + "id UUID PRIMARY KEY, "
                + "code VARCHAR(10) NOT NULL CHECK (LENGTH(code) >= 5), "
                + "category VARCHAR(20) NOT NULL CHECK (category IN ('ELECTRONICS', 'BOOKS', 'TOYS')), "
                + "is_active BOOLEAN, "
                + "created_at TIMESTAMP, "
                + "price DECIMAL(10, 2) NOT NULL CHECK (price > 0))");
      }

      // Introspect
      List<Table> tables = SchemaIntrospector.introspect(conn, "PUBLIC");
      assertEquals(1, tables.size());

      // Generate
      DataGenerator.GenerationResult genResult = generateData(tables, 20);

      // Execute
      String sql = SqlGenerator.generate(genResult.rows(), genResult.updates(), false);
      executeSql(conn, sql);

      // Verify
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT * FROM PRODUCTS")) {
        while (rs.next()) {
          String code = rs.getString("code");
          String category = rs.getString("category");
          double price = rs.getDouble("price");

          if (code != null) {
            assertTrue(code.length() >= 5, "Code length check failed: " + code);
          }
          if (category != null) {
            assertTrue(
                List.of("ELECTRONICS", "BOOKS", "TOYS").contains(category),
                "Category check failed: " + category);
          }
          // Price is double, rs.getDouble returns 0.0 if null? No, only for primitive.
          // But we made them NOT NULL in DDL above, so they shouldn't be null.
          // If we keep assertion assuming non-null, it's fine now.

          assertTrue(price > 0, "Price check failed: " + price);
          assertNotNull(rs.getObject("id")); // UUID check
        }
      }
    }
  }

  private void executeCycleAndVerify(
      Connection conn, int expectedTables, Map<String, Integer> expectedRows) throws Exception {
    List<Table> tables = SchemaIntrospector.introspect(conn, "PUBLIC");
    assertEquals(expectedTables, tables.size());

    // Use the max expected rows for generation, or default to 20 if expectations are lower.
    // But wait, `expectedRows` in `testStandardCycle` is 10.
    // If I generate 20, I get 20 in DB.
    // So I should use the requested count from the map if possible, or just pass it as param.
    // I'll just default to 10 for standard test or update standard test expectation.
    // Let's infer max from map.
    int maxRows = expectedRows.values().stream().max(Integer::compareTo).orElse(20);

    DataGenerator.GenerationResult genResult = generateData(tables, maxRows);

    String sql = SqlGenerator.generate(genResult.rows(), genResult.updates(), false);
    executeSql(conn, sql);

    try (Statement stmt = conn.createStatement()) {
      for (Map.Entry<String, Integer> entry : expectedRows.entrySet()) {
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + entry.getKey())) {
          assertTrue(rs.next());
          assertEquals(entry.getValue(), rs.getInt(1), "Row count mismatch for " + entry.getKey());
        }
      }
    }
  }

  private DataGenerator.GenerationResult generateData(List<Table> tables, int rows) {
    return DataGenerator.generate(
        DataGenerator.GenerationParameters.builder()
            .tables(tables)
            .rowsPerTable(rows)
            .deferred(false)
            .pkUuidOverrides(Collections.emptyMap())
            .excludedColumns(Collections.emptyMap())
            .useEnglishDictionary(false)
            .useSpanishDictionary(false)
            .build());
  }

  private void executeSql(Connection conn, String sql) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      String[] statements = sql.split(";\n");
      for (String s : statements) {
        if (s.trim().isEmpty()) continue;
        stmt.execute(s);
      }
    }
  }
}
