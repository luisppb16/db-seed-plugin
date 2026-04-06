/*
 * *****************************************************************************
 *  * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 *  * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.dialect.DialectFactory;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.util.DriverLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class SqliteDatabaseSeedingIntegrationTest {

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

  private static HttpServer startOllamaServer(final String responseLines) throws IOException {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/generate", new OllamaGenerateHandler(responseLines));
    server.start();
    return server;
  }

  private static String escapeJson(final String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  private static Path resolveClasspathArtifact(final String artifactFragment) {
    return java.util.Arrays.stream(
            System.getProperty("java.class.path", "").split(java.io.File.pathSeparator))
        .map(Path::of)
        .filter(path -> path.getFileName() != null)
        .filter(path -> path.getFileName().toString().contains(artifactFragment))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("Artifact not found in classpath: " + artifactFragment));
  }

  @BeforeEach
  void resetSettings() {
    settings = IntegrationTestSupport.defaultSettings();
  }

  @Test
  void testFullSeedingWorkflow_sqliteFromBundledSchema() throws Exception {
    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-sqlite-main");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyProjectSqlFile(
          connection, "docker/initdb/schema_SQLite.sql", IntegrationTestSupport.ddlOnly());

      final IntegrationTestSupport.WorkflowResult outcome =
          IntegrationTestSupport.runWorkflow(
              connection,
              null,
              IntegrationTestSupport.SQLITE_DRIVER,
              IntegrationTestSupport.defaults(2));

      assertThat(outcome.tables()).isNotEmpty();
      assertThat(outcome.orderedTables()).hasSize(outcome.tables().size());
      assertThat(outcome.generatedSql()).contains("INSERT INTO");

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM users"))
          .isEqualTo(2);
      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM products"))
          .isEqualTo(2);
      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM orders"))
          .isEqualTo(2);
    }
  }

  @Test
  void testSoftDeleteColumns_withSpacesAndEmptyTokens_areApplied() throws Exception {
    final String schemaSql =
        """
        CREATE TABLE items (
            id INTEGER PRIMARY KEY,
            deleted INTEGER,
            archived INTEGER
        );
        """;

    final IntegrationTestSupport.WorkflowOptions options =
        new IntegrationTestSupport.WorkflowOptions(
            4,
            false,
            Map.of(),
            Map.of(),
            Map.of(),
            false,
            false,
            false,
            " deleted, , archived ,",
            false,
            "1",
            2,
            Map.of(),
            "");

    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-soft-delete-csv");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection, schemaSql, IntegrationTestSupport.allStatements());

      final IntegrationTestSupport.WorkflowResult outcome =
          IntegrationTestSupport.runWorkflow(
              connection, null, IntegrationTestSupport.SQLITE_DRIVER, options);

      assertThat(outcome.generatedSql()).contains("INSERT INTO");
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(*) FROM items WHERE deleted = 1"))
          .isEqualTo(4);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(*) FROM items WHERE archived = 1"))
          .isEqualTo(4);
    }
  }

  @Test
  void testCycleHandling_nullableCycleProducesPendingUpdatesOnSqlite() throws Exception {
    final String schemaSql =
        """
        PRAGMA foreign_keys = ON;
        CREATE TABLE authors (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            primary_book_id INTEGER,
            FOREIGN KEY (primary_book_id) REFERENCES books(id)
        );
        CREATE TABLE books (
            id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            author_id INTEGER,
            FOREIGN KEY (author_id) REFERENCES authors(id)
        );
        """;

    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-cycle-nullable");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection, schemaSql, IntegrationTestSupport.allStatements());

      final IntegrationTestSupport.WorkflowResult outcome =
          IntegrationTestSupport.prepareWorkflow(
              connection,
              null,
              IntegrationTestSupport.SQLITE_DRIVER,
              IntegrationTestSupport.defaults(4));

      assertThat(outcome.sortResult().cycles()).hasSize(1);
      assertThat(outcome.deferred()).isFalse();
      assertThat(outcome.generationResult().updates()).isNotEmpty();
      assertThat(outcome.generatedSql()).contains("UPDATE");

      IntegrationTestSupport.executeStatements(connection, List.of("PRAGMA foreign_keys = OFF"));
      IntegrationTestSupport.executeScript(connection, outcome.generatedSql());
      IntegrationTestSupport.executeStatements(connection, List.of("PRAGMA foreign_keys = ON"));

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM authors"))
          .isEqualTo(4);
      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM books"))
          .isEqualTo(4);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  """
                  SELECT COUNT(*)
                  FROM authors a
                  LEFT JOIN books b ON a.primary_book_id = b.id
                  WHERE a.primary_book_id IS NOT NULL AND b.id IS NULL
                  """))
          .isZero();
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  """
                  SELECT COUNT(*)
                  FROM books b
                  LEFT JOIN authors a ON b.author_id = a.id
                  WHERE b.author_id IS NOT NULL AND a.id IS NULL
                  """))
          .isZero();
    }
  }

  @Test
  void testAiGeneration_populatesConfiguredColumnsUsingOllamaStub() throws Exception {
    settings.setUseAiGeneration(true);
    settings.setOllamaModel("stub-model");
    settings.setAiApplicationContext("blog platform");
    settings.setAiWordCount(2);

    final HttpServer server =
        startOllamaServer("ai_value_1\nai_value_2\nai_value_3\nai_value_4\nai_value_5");
    settings.setOllamaUrl("http://127.0.0.1:" + server.getAddress().getPort());

    final String schemaSql =
        """
        CREATE TABLE articles (
            id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            content TEXT NOT NULL
        );
        """;

    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-ai-positive");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection, schemaSql, IntegrationTestSupport.allStatements());

      final IntegrationTestSupport.WorkflowOptions options =
          new IntegrationTestSupport.WorkflowOptions(
              4,
              false,
              Map.of(),
              Map.of(),
              Map.of(),
              false,
              false,
              false,
              null,
              false,
              null,
              2,
              Map.of("articles", Set.of("content")),
              settings.getAiApplicationContext());

      final IntegrationTestSupport.WorkflowResult outcome =
          IntegrationTestSupport.runWorkflow(
              connection, null, IntegrationTestSupport.SQLITE_DRIVER, options);

      assertThat(outcome.generatedSql()).contains("INSERT INTO");
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(*) FROM articles WHERE content LIKE 'ai_value_%'"))
          .isEqualTo(4);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void testAiGeneration_fallsBackToRegularGenerationWhenServerIsUnavailable() throws Exception {
    settings.setUseAiGeneration(true);
    settings.setOllamaUrl("http://127.0.0.1:9");
    settings.setOllamaModel("missing-model");
    settings.setAiApplicationContext("fallback scenario");

    final String schemaSql =
        """
        CREATE TABLE notes (
            id INTEGER PRIMARY KEY,
            content TEXT NOT NULL
        );
        """;

    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-ai-fallback");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection, schemaSql, IntegrationTestSupport.allStatements());

      final IntegrationTestSupport.WorkflowOptions options =
          new IntegrationTestSupport.WorkflowOptions(
              3,
              false,
              Map.of(),
              Map.of(),
              Map.of(),
              false,
              false,
              false,
              null,
              false,
              null,
              2,
              Map.of("notes", Set.of("content")),
              settings.getAiApplicationContext());

      IntegrationTestSupport.runWorkflow(
          connection, null, IntegrationTestSupport.SQLITE_DRIVER, options);

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM notes"))
          .isEqualTo(3);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection,
                  "SELECT COUNT(*) FROM notes WHERE content IS NOT NULL AND TRIM(content) <> ''"))
          .isEqualTo(3);
    }
  }

  @Test
  void testDriverLoader_usesCachedSqliteJarWithoutNetwork() throws Exception {
    final Path cacheDirectory =
        Path.of(System.getProperty("user.home"), ".db-seed-plugin", "drivers");
    Files.createDirectories(cacheDirectory);

    final Path sourceJar = resolveClasspathArtifact("sqlite-jdbc");
    final Path cachedJar = cacheDirectory.resolve("sqlite-jdbc-3.46.1.3.jar");
    Files.copy(sourceJar, cachedJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    DriverLoader.ensureDriverPresent(IntegrationTestSupport.SQLITE_DRIVER);

    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-driver-ok");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      assertThat(connection.isValid(2)).isTrue();
    }
  }

  @Test
  void testDriverLoader_invalidCoordinatesFailDuringDownload() throws Exception {
    final DriverInfo invalidDriver =
        DriverInfo.builder()
            .name("Invalid JDBC")
            .mavenGroupId("invalid.group")
            .mavenArtifactId("missing-driver")
            .version("0.0.0")
            .driverClass("invalid.Driver")
            .urlTemplate("jdbc:invalid://localhost/test")
            .dialect("standard")
            .build();

    final Path cachedJar =
        Path.of(
            System.getProperty("user.home"),
            ".db-seed-plugin",
            "drivers",
            "missing-driver-0.0.0.jar");
    Files.deleteIfExists(cachedJar);

    assertThatThrownBy(() -> DriverLoader.ensureDriverPresent(invalidDriver))
        .isInstanceOf(IOException.class);
  }

  @Test
  void testInvalidConnectionUrl_raisesSqlException() throws Exception {
    final Path missingDirectory = Files.createTempDirectory("dbseed-missing-db");
    final String url = "jdbc:sqlite:file:" + missingDirectory.resolve("missing.db") + "?mode=ro";

    assertThatThrownBy(() -> DriverManager.getConnection(url)).isInstanceOf(SQLException.class);
  }

  @Test
  void testEmptySchemaAndTableWithoutPrimaryKey_areHandled() throws Exception {
    final Path emptyDb = IntegrationTestSupport.newTempSqlitePath("dbseed-empty");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + emptyDb)) {
      final List<Table> tables =
          SchemaIntrospector.introspect(
              connection, null, DialectFactory.resolve(IntegrationTestSupport.SQLITE_DRIVER));
      assertThat(tables).isEmpty();
    }

    final Path noPkDb = IntegrationTestSupport.newTempSqlitePath("dbseed-no-pk");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + noPkDb)) {
      IntegrationTestSupport.applyInlineSql(
          connection,
          "CREATE TABLE audit_log (event_type TEXT NOT NULL, payload TEXT NOT NULL)",
          IntegrationTestSupport.allStatements());

      IntegrationTestSupport.runWorkflow(
          connection,
          null,
          IntegrationTestSupport.SQLITE_DRIVER,
          IntegrationTestSupport.defaults(5));

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM audit_log"))
          .isEqualTo(5);
    }
  }

  @Test
  void testColumnExclusions_omitsExcludedColumns() throws Exception {
    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-exclusions");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection,
          "CREATE TABLE profile (id INTEGER PRIMARY KEY, bio TEXT NOT NULL, secret_key TEXT)",
          IntegrationTestSupport.allStatements());

      final IntegrationTestSupport.WorkflowOptions options =
          new IntegrationTestSupport.WorkflowOptions(
              5,
              false,
              Map.of(),
              Map.of("profile", List.of("secret_key")),
              Map.of(),
              false,
              false,
              false,
              null,
              false,
              null,
              2,
              Map.of(),
              "");

      IntegrationTestSupport.runWorkflow(
          connection, null, IntegrationTestSupport.SQLITE_DRIVER, options);

      long totalGenerados =
          IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM profile");
      assertThat(totalGenerados).isGreaterThan(0);

      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(*) FROM profile WHERE secret_key IS NULL"))
          .isEqualTo(totalGenerados);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(*) FROM profile WHERE bio IS NOT NULL"))
          .isEqualTo(totalGenerados);
    }
  }

  @Test
  void testRepetitionRules_createsRepeatedData() throws Exception {
    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-repetition");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection,
          "CREATE TABLE company (id INTEGER PRIMARY KEY, name TEXT NOT NULL, industry TEXT NOT NULL, hq TEXT NOT NULL)",
          IntegrationTestSupport.allStatements());

      final com.luisppb16.dbseed.model.RepetitionRule rule =
          new com.luisppb16.dbseed.model.RepetitionRule(
              3, Map.of("industry", "Tech"), Set.of("hq"));

      final IntegrationTestSupport.WorkflowOptions options =
          new IntegrationTestSupport.WorkflowOptions(
              5,
              false,
              Map.of(),
              Map.of(),
              Map.of("company", List.of(rule)),
              false,
              false,
              false,
              null,
              false,
              null,
              2,
              Map.of(),
              "");

      IntegrationTestSupport.runWorkflow(
          connection, null, IntegrationTestSupport.SQLITE_DRIVER, options);

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM company"))
          .isGreaterThanOrEqualTo(3);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(*) FROM company WHERE industry = 'Tech'"))
          .isEqualTo(3);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(DISTINCT hq) FROM company WHERE industry = 'Tech'"))
          .isEqualTo(1);
    }
  }

  @Test
  void testSoftDeletes_usesConfiguredDefault() throws Exception {
    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-softdelete");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection,
          "CREATE TABLE record (id INTEGER PRIMARY KEY, value TEXT, deleted_at TEXT)",
          IntegrationTestSupport.allStatements());

      final IntegrationTestSupport.WorkflowOptions options =
          new IntegrationTestSupport.WorkflowOptions(
              2,
              false,
              Map.of(),
              Map.of(),
              Map.of(),
              false,
              false,
              false,
              "deleted_at",
              false,
              "NULL",
              2,
              Map.of(),
              "");

      IntegrationTestSupport.runWorkflow(
          connection, null, IntegrationTestSupport.SQLITE_DRIVER, options);

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM record"))
          .isEqualTo(2);
      assertThat(
              IntegrationTestSupport.queryForLong(
                  connection, "SELECT COUNT(*) FROM record WHERE deleted_at IS NULL"))
          .isEqualTo(2);
    }
  }

  @Test
  void testSelfReferencingFk_handlesNullable() throws Exception {
    final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-self-ref");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      IntegrationTestSupport.applyInlineSql(
          connection,
          """
          CREATE TABLE employee (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL,
              manager_id INTEGER,
              FOREIGN KEY (manager_id) REFERENCES employee(id)
          );
          """,
          IntegrationTestSupport.allStatements());

      IntegrationTestSupport.runWorkflow(
          connection,
          null,
          IntegrationTestSupport.SQLITE_DRIVER,
          IntegrationTestSupport.defaults(5));

      assertThat(IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM employee"))
          .isEqualTo(5);
    }
  }

  @Test
  void testLargeVolumeGeneration_finishesWithinReasonableTime() {
    assertTimeout(
        Duration.ofSeconds(20),
        () -> {
          final Path sqlitePath = IntegrationTestSupport.newTempSqlitePath("dbseed-large-volume");
          try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
            IntegrationTestSupport.applyInlineSql(
                connection,
                "CREATE TABLE events (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)",
                IntegrationTestSupport.allStatements());

            IntegrationTestSupport.runWorkflow(
                connection,
                null,
                IntegrationTestSupport.SQLITE_DRIVER,
                IntegrationTestSupport.defaults(1_200));

            assertThat(
                    IntegrationTestSupport.queryForLong(connection, "SELECT COUNT(*) FROM events"))
                .isEqualTo(1_200);
          }
        });
  }

  private record OllamaGenerateHandler(String responseLines) implements HttpHandler {

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
      final String requestBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      final String responseBody =
          requestBody.contains("\"prompt\":\"\"")
              ? "{\"response\":\"warm\"}"
              : "{\"response\":\"" + escapeJson(responseLines) + "\"}";

      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    }
  }
}
