# 🗃️ DBSeed4SQL Plugin (IntelliJ IDEA).

**db-seed-plugin** is a plugin for **IntelliJ IDEA** that generates synthetic data to populate database schemas.
It automatically introspects a schema, analyzes dependencies between tables, and produces a consistent SQL script, respecting primary keys,
foreign keys, and complex cycles. It is ideal for developers who need realistic and repeatable seeds for testing, demos, or development
environments.

This project is developed in **Java 25** with **Gradle 9.3.0**, applying a modern programming style: `record`, `switch` with `yield`, pattern
matching, functional programming with Streams, `Optional` to avoid nulls, extensive use of **Lombok** (`@Builder`, `@Slf4j`,
`@UtilityClass`), and Javadoc documentation in Spanish. The architecture is organized into clear layers:

- **action**: IntelliJ actions (`SeedDatabaseAction`, `GenerateSeedAction`).
- **config**: configuration persistence (`GenerationConfig`, `ConnectionConfigPersistence`).
- **db**: main engine with introspection (`SchemaIntrospector`), topological sorting (`TopologicalSorter`), data generation (
  `DataGenerator`), and SQL construction (`SqlGenerator`).
- **model**: immutable models (`Table`, `Column`, `ForeignKey`).
- **schema**: DSL for manually defining schemas (`SchemaDsl`, `SqlType`).
- **ui**: Swing/IntelliJ dialogs and screens (`SeedDialog`, `PkUuidSelectionDialog`, `SchemaDesigner`).

---

### 🚀 New Feature: Dynamic Drivers

In previous versions, the plugin only included fixed support for PostgreSQL or loaded many drivers into memory.
Now, when running the **Seed Database** action, the user can select the database engine from a configurable list in `drivers.json`.

- If the driver is not installed, the plugin automatically downloads it from **Maven Central** to `~/.dbseed-drivers/`.
- It is dynamically registered in the `DriverManager`, avoiding memory saturation with dozens of unnecessary JARs.
- Each driver comes with sample values for the JDBC URL, which speeds up the initial configuration.

Example of `drivers.json`:

```json
[
  {
    "name": "PostgreSQL",
    "mavenGroupId": "org.postgresql",
    "mavenArtifactId": "postgresql",
    "version": "42.7.3",
    "driverClass": "org.postgresql.Driver",
    "sampleUrl": "jdbc:postgresql://localhost:5432/dbname"
  },
  {
    "name": "MySQL",
    "mavenGroupId": "com.mysql",
    "mavenArtifactId": "mysql-connector-j",
    "version": "8.3.0",
    "driverClass": "com.mysql.cj.jdbc.Driver",
    "sampleUrl": "jdbc:mysql://localhost:3306/dbname"
  },
  {
    "name": "SQL Server",
    "mavenGroupId": "com.microsoft.sqlserver",
    "mavenArtifactId": "mssql-jdbc",
    "version": "12.6.0.jre11",
    "driverClass": "com.microsoft.sqlserver.jdbc.SQLServerDriver",
    "sampleUrl": "jdbc:sqlserver://localhost:1433;databaseName=dbname"
  }
]
```

This way, the plugin is **lighter, more extensible, and always uses the correct driver**.

---

### 📚 New Feature: Configurable Dictionaries for Data Generation

The plugin now offers enhanced control over string data generation by allowing users to select which dictionaries to use. Previously, data
generation relied solely on Faker's default (Latin) lorem ipsum. With this update, you can combine Faker's default with custom English and
Spanish word lists.

In the plugin settings, you will find three new checkboxes:

- **Use Latin Dictionary (Faker default)**: When checked, Faker's default lorem ipsum generation (often Latin-based) will be included.
- **Use English Dictionary**: When checked, words from an internal English dictionary will be used for string generation.
- **Use Spanish Dictionary**: When checked, words from an internal Spanish dictionary will be used for string generation.

This allows for more realistic and contextually relevant data generation, especially for text-based fields. You can select any combination
of these options to tailor the generated string data to your specific needs.

---

### 🤖 New Feature: AI-Powered Data Generation (Ollama)

The plugin now integrates with [Ollama](https://ollama.com/) to generate context-aware, realistic seed data using local LLMs. Instead of relying solely on random/faker values, you can leverage AI to produce meaningful content for string columns.

- **AI Columns Selection**: A new "AI Columns" tab in the generation dialog lets you choose which string columns get AI-generated content.
- **Smart Defaults**: Columns named `description`, `title`, `bio`, `email`, etc. are pre-selected automatically.
- **Batch Generation**: AI values are generated in batches of 20 for maximum throughput, with automatic retries and deduplication.
- **Configurable Word Count**: Control output length from a single word up to full paragraphs.
- **Cancellable Processing**: The generation task is now cancellable — you can stop it at any time via the IDE progress bar.
- **Global AI Settings**: Enable/disable AI generation, set the Ollama URL and model, provide application context to guide the AI, and test connectivity — all from **Settings → DBSeed4SQL**.

---

### 🔧 Main Functionality

- Schema introspection via `DatabaseMetaData`.
- Table sorting with Tarjan's algorithm to detect cycles.
- Synthetic data generation with [DataFaker](https://www.datafaker.net/) respecting PKs, FKs, uniqueness, and nullability.
- Heuristics to recognize column names like `email`, `name`, or `uuid`.
- Cycle handling with deferred constraints (`SET CONSTRAINTS ALL DEFERRED`) or updates (`PendingUpdate`).
- Interactive selection of UUIDs in PKs through a UI dialog.
- Automatic opening of the generated SQL in the IntelliJ editor.
- Standalone visual schema designer to prototype tables and generate creation SQL.
- **AI-Powered Data Generation**: Generate realistic, context-aware seed data using a local Ollama LLM server.
- **Auto-Detect SQL Dialect**: The `DialectFactory` now auto-detects the dialect from the driver class or JDBC URL when no explicit dialect is configured.
- **Improved Password Input**: The database configuration dialog now features a "show password" toggle (eye icon) directly within the
  password field, allowing users to easily reveal or hide the entered password. This provides a more intuitive and secure user experience.
- **Batch Query Execution**: Optimized SQL script generation to execute queries in batches, significantly improving performance for large
  datasets.
- **Configuration Window**: Introduced a dedicated configuration window within the IDE, allowing users to easily customize default settings
  and generation parameters.

---

### ⚙️ Build and Distribution

The build is done with Gradle:

```bash
./gradlew clean buildPlugin
```

The plugin is packaged as a `.zip` in `build/distributions/`, which is the installable artifact in IntelliJ via **Settings → Plugins →
Install plugin from disk...**.

The **GitHub Actions** pipeline compiles the project on JDK 25, runs the tests, and automatically attaches the `.zip` as a downloadable
artifact in each published release.

---

### 📋 Quick Example

1. Select the database driver from the list (PostgreSQL, MySQL, SQL Server, etc.).
    - If it is not installed, the plugin will download it automatically.
2. Configure the connection in the **SeedDialog** (JDBC URL, user, password, schema).
3. Mark the PKs you want to treat as UUIDs in the **PkUuidSelectionDialog**.
4. The plugin introspects the schema, generates data, and builds an SQL script like:

```sql
BEGIN;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO "users" ("id", "name", "email")
VALUES ('9f1c...uuid...', 'Alice Doe', 'alice@example.com');
INSERT INTO "orders" ("id", "user_id", "amount")
VALUES ('c7a3...uuid...', '9f1c...uuid...', 42.50);

COMMIT;
```

The file is automatically opened in an editor within IntelliJ, ready to be executed or exported.

---

👨‍💻 Project created by **Luis Pepe** ([@LuisPPB16](https://github.com/luisppb16)), a Java developer specializing in backend, microservices,
and development tooling.
