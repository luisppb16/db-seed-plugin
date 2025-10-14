package com.luisppb16.dbseed.config;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
@UtilityClass
public class ConnectionConfigPersistence {

  private static final String URL_KEY = "dbseed.connection.url";
  private static final String USER_KEY = "dbseed.connection.user";
  private static final String PASSWORD_KEY = "dbseed.connection.password";
  private static final String SCHEMA_KEY = "dbseed.connection.schema";
  private static final String ROWS_KEY = "dbseed.connection.rows";
  private static final String DEFERRED_KEY = "dbseed.connection.deferred";

  private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/postgres";
  private static final String DEFAULT_USER = "postgres";
  private static final String DEFAULT_SCHEMA = "public";
  private static final int DEFAULT_ROWS = 10;
  private static final boolean DEFAULT_DEFERRED = false;

  public static void save(@NotNull Project project, @NotNull GenerationConfig config) {
    PropertiesComponent properties = PropertiesComponent.getInstance(project);

    properties.setValue(URL_KEY, config.url());
    properties.setValue(USER_KEY, config.user());
    properties.setValue(PASSWORD_KEY, config.password());
    properties.setValue(SCHEMA_KEY, config.schema());
    properties.setValue(ROWS_KEY, String.valueOf(config.rowsPerTable()));
    properties.setValue(DEFERRED_KEY, String.valueOf(config.deferred()));

    log.info("Connection configuration saved for project {}.", project.getName());
  }

  @NotNull
  public static GenerationConfig load(@NotNull Project project) {
    PropertiesComponent properties = PropertiesComponent.getInstance(project);

    String url = properties.getValue(URL_KEY, DEFAULT_URL);
    String user = properties.getValue(USER_KEY, DEFAULT_USER);
    String password = properties.getValue(PASSWORD_KEY, "");
    String schema = properties.getValue(SCHEMA_KEY, DEFAULT_SCHEMA);
    int rows = properties.getInt(ROWS_KEY, DEFAULT_ROWS);
    boolean deferred = properties.getBoolean(DEFERRED_KEY, DEFAULT_DEFERRED);

    log.debug(
        "Configuration loaded: url={}, user={}, schema={}, rows={}, deferred={}",
        url,
        user,
        schema,
        rows,
        deferred);

    return GenerationConfig.builder()
        .url(url)
        .user(user)
        .password(password)
        .schema(schema)
        .rowsPerTable(rows)
        .deferred(deferred)
        .build();
  }
}
