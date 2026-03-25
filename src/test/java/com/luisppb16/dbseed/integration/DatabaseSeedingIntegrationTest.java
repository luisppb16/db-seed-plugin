/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseSeedingIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("dbseed")
          .withUsername("dbseed")
          .withPassword("dbseed");

  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4.0")
          .withDatabaseName("dbseed")
          .withUsername("dbseed")
          .withPassword("dbseed");

  private static DbSeedSettingsState settings;
  private static MockedStatic<DbSeedSettingsState> settingsMock;

  @BeforeAll
  static void setUpSettings() {
    settings = IntegrationTestSupport.defaultSettings();
    settingsMock = Mockito.mockStatic(DbSeedSettingsState.class);
    settingsMock.when(DbSeedSettingsState::getInstance).thenAnswer(invocation -> settings);
  }

  @AfterAll
  static void tearDownSettings() {
    if (settingsMock != null) {
      settingsMock.close();
    }
  }

  static Stream<IntegrationTestSupport.ContainerEngine> relationalEngines() {
    return Stream.of(
        new IntegrationTestSupport.ContainerEngine(
            "PostgreSQL",
            IntegrationTestSupport.POSTGRES_DRIVER,
            "public",
            "docker/initdb/schema_Postgres.sql",
            () -> DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())),
        new IntegrationTestSupport.ContainerEngine(
            "MySQL",
            IntegrationTestSupport.MYSQL_DRIVER,
            MYSQL.getDatabaseName(),
            "docker/initdb/schema_MySQL.sql",
            () -> DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("relationalEngines")
  void testFullSeedingWorkflow_acrossContainerDatabases(
      final IntegrationTestSupport.ContainerEngine engine) throws Exception {
    try (Connection connection = engine.connectionSupplier().open()) {
      IntegrationTestSupport.applyProjectSqlFile(
          connection, engine.schemaFile(), IntegrationTestSupport.ddlOnly());

      final IntegrationTestSupport.WorkflowResult outcome =
          IntegrationTestSupport.runWorkflow(
              connection, engine.schemaName(), engine.driverInfo(), IntegrationTestSupport.defaults(3));

      assertThat(outcome.tables()).isNotEmpty();
      assertThat(outcome.orderedTables()).hasSize(outcome.tables().size());
      assertThat(outcome.sortResult().cycles()).isEmpty();
      assertThat(outcome.generatedSql()).contains("INSERT INTO");
      assertThat(outcome.generationResult().updates()).isEmpty();

      final Table users = IntegrationTestSupport.findTable(outcome.tables(), "users");
      final Table orders = IntegrationTestSupport.findTable(outcome.tables(), "orders");
      final Table reviews = IntegrationTestSupport.findTable(outcome.tables(), "reviews");

      assertThat(users.primaryKey()).isNotEmpty();
      assertThat(orders.foreignKeys()).isNotEmpty();
      assertThat(reviews.checks()).isNotEmpty();

      if ("postgresql".equals(engine.driverInfo().dialect())) {
        final Table articles = IntegrationTestSupport.findTable(outcome.tables(), "articles");
        final Column tagsColumn = articles.column("tags");
        assertThat(tagsColumn).isNotNull();
        assertThat(tagsColumn.jdbcType()).isEqualTo(java.sql.Types.ARRAY);
        assertThat(tagsColumn.typeName()).containsIgnoringCase("text");
      }

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM users"))
          .isEqualTo(3);
      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM orders"))
          .isEqualTo(3);
      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM reviews"))
          .isEqualTo(3);

      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  "SELECT COUNT(*) - COUNT(DISTINCT username) FROM users WHERE username IS NOT NULL"))
          .isZero();
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  "SELECT COUNT(*) - COUNT(DISTINCT email) FROM users WHERE email IS NOT NULL"))
          .isZero();
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  """
                  SELECT COUNT(*)
                  FROM orders o
                  LEFT JOIN users u ON o.user_id = u.id
                  WHERE o.user_id IS NOT NULL AND u.id IS NULL
                  """))
          .isZero();
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  """
                  SELECT COUNT(*)
                  FROM order_items oi
                  LEFT JOIN orders o ON oi.order_id = o.id
                  WHERE oi.order_id IS NOT NULL AND o.id IS NULL
                  """))
          .isZero();
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  """
                  SELECT COUNT(*)
                  FROM order_items oi
                  LEFT JOIN products p ON oi.product_id = p.id
                  WHERE oi.product_id IS NOT NULL AND p.id IS NULL
                  """))
          .isZero();
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  "SELECT COUNT(*) FROM reviews WHERE rating < 1 OR rating > 5"))
          .isZero();
    }
  }

  @Test
  void testCycleHandling_nonNullableCycle_usesDeferredModeOnMySql() throws Exception {
    final String schemaSql =
        """
        CREATE TABLE authors (
            id INT PRIMARY KEY,
            featured_book_id INT NOT NULL
        );

        CREATE TABLE books (
            id INT PRIMARY KEY,
            author_id INT NOT NULL
        );

        ALTER TABLE authors
            ADD CONSTRAINT fk_author_book FOREIGN KEY (featured_book_id) REFERENCES books(id);
        ALTER TABLE books
            ADD CONSTRAINT fk_book_author FOREIGN KEY (author_id) REFERENCES authors(id);
        """;

    try (Connection connection =
        DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
      IntegrationTestSupport.applyInlineSql(connection, schemaSql, IntegrationTestSupport.allStatements());

      final IntegrationTestSupport.WorkflowResult outcome =
          IntegrationTestSupport.runWorkflow(
              connection,
              MYSQL.getDatabaseName(),
              IntegrationTestSupport.MYSQL_DRIVER,
              IntegrationTestSupport.defaults(2));

      assertThat(outcome.sortResult().cycles()).hasSize(1);
      assertThat(outcome.deferred()).isTrue();
      assertThat(outcome.generatedSql()).contains("SET FOREIGN_KEY_CHECKS = 0");
      assertThat(outcome.generatedSql()).contains("START TRANSACTION");

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM authors"))
          .isEqualTo(2);
      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM books"))
          .isEqualTo(2);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  """
                  SELECT COUNT(*)
                  FROM authors a
                  LEFT JOIN books b ON a.featured_book_id = b.id
                  WHERE b.id IS NULL
                  """))
          .isZero();
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  """
                  SELECT COUNT(*)
                  FROM books b
                  LEFT JOIN authors a ON b.author_id = a.id
                  WHERE a.id IS NULL
                  """))
          .isZero();
    }
  }
}


