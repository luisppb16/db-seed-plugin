# 🗃️ DBSeed4SQL Plugin (IntelliJ IDEA).

**db-seed-plugin** is a plugin for **IntelliJ IDEA** that generates synthetic data to populate database schemas.
It automatically introspects a schema, analyzes dependencies between tables, and produces a consistent SQL script, respecting primary keys,
foreign keys, uniqueness constraints, and complex cycles. It is ideal for developers who need realistic and repeatable seeds for testing,
demos, QA, or development environments.

This project is developed in **Java 21** with **Gradle 9.4.1**, applying a modern programming style: `record`, `switch` with `yield`,
pattern matching, functional programming with Streams, `Optional` to avoid nulls, extensive use of **Lombok** (`@Builder`, `@Slf4j`,
`@UtilityClass`), and Javadoc documentation in Spanish. The architecture is organized into clear layers:

- **action**: IntelliJ actions (`SeedDatabaseAction`, `GenerateSeedAction`).
- **config**: configuration persistence and settings UI (`GenerationConfig`, `DbSeedSettingsState`, `ConnectionConfigPersistence`).
- **db**: main engine with introspection (`SchemaIntrospector`), topological sorting (`TopologicalSorter`), data generation (`DataGenerator`),
  and SQL construction (`SqlGenerator`).
- **model**: immutable models (`Table`, `Column`, `ForeignKey`, `RepetitionRule`).
- **registry**: driver and runtime registries used by the plugin.
- **schema**: DSL for manually defining schemas (`SchemaDsl`, `SqlType`).
- **ui**: Swing/IntelliJ dialogs and screens (`SeedDialog`, `PkUuidSelectionDialog`, `SchemaDesigner`).
- **util**: shared infrastructure and helper utilities.
---

### ✅ Marketplace Compliance Notes

To align with current JetBrains Marketplace approval criteria:

- Plugin metadata declares explicit compatibility (`since-build=251`, `until-build=261.*`) and the required Java module dependency.
- JDBC driver download is **explicitly confirmed by the user** before any external artifact is fetched.
- The plugin works locally by default; external network use is opt-in and user-triggered.
- AI generation calls only a user-configured Ollama endpoint and uses user-provided context plus schema metadata (table/column names).
- Generated SQL, dictionaries, and settings remain local to the IDE/project unless the user exports or shares them manually.

---

### 🚀 Dynamic Drivers

Instead of bundling every JDBC driver up front, DBSeed4SQL lets the user choose the database engine from a configurable list in
`src/main/resources/drivers.json`.

- If the selected driver is not installed, the plugin downloads it from **Maven Central** into `~/.dbseed-drivers/` after confirmation.
- The driver is registered dynamically in `DriverManager`, so the plugin stays lightweight.
- Each driver definition contains URL templates, credential requirements, and a preferred SQL dialect.

Example of the current `drivers.json` structure:

```json
[
  {
    "name": "PostgreSQL",
    "mavenGroupId": "org.postgresql",
    "mavenArtifactId": "postgresql",
    "version": "42.7.7",
    "driverClass": "org.postgresql.Driver",
    "urlTemplate": "jdbc:postgresql://localhost:5432/test",
    "requiresDatabaseName": true,
    "requiresUser": true,
    "requiresPassword": true,
    "requiresSchema": true,
    "dialect": "postgresql"
  },
  {
    "name": "MySQL",
    "mavenGroupId": "com.mysql",
    "mavenArtifactId": "mysql-connector-j",
    "version": "9.3.0",
    "driverClass": "com.mysql.cj.jdbc.Driver",
    "urlTemplate": "jdbc:mysql://localhost:3306/test",
    "requiresDatabaseName": true,
    "requiresUser": true,
    "requiresPassword": true,
    "requiresSchema": false,
    "dialect": "mysql"
  }
]
```

This makes the plugin **lighter, more extensible, and easier to maintain**.

---

### 🧩 Supported Databases

The bundled driver catalog currently includes support definitions for:

- Amazon Aurora MySQL
- Amazon Redshift
- Apache Derby
- Apache Hive
- Azure SQL Database
- CockroachDB
- Google BigQuery
- H2
- HSQLDB
- IBM Db2
- MariaDB
- MySQL
- Oracle
- PostgreSQL
- SQL Server
- SQLite

Dialect auto-detection is also available when no explicit dialect is configured.

---

### 🔐 Connection Profiles and Project Settings

DBSeed4SQL supports reusable **connection profiles** so you do not need to type JDBC connection data every time.

- Save a profile directly from the seed dialog.
- Switch between profiles from the same form.
- Manage and sanitize saved profiles from the settings UI.
- Keep the active profile per project through `DbSeedProjectState`.

This is especially useful when you work with several local databases, test environments, or tenant-specific schemas.

---

### 📚 Configurable Dictionaries for Data Generation

The plugin offers enhanced control over string data generation by allowing users to select which dictionaries to use. Previously, data
generation relied solely on Faker's default lorem ipsum. With this feature, you can combine Faker's default output with internal English and
Spanish dictionaries.

In the plugin settings, you will find three checkboxes:

- **Use Latin Dictionary (Faker default)**: Includes Faker's default lorem-style word generation.
- **Use English Dictionary**: Uses words from an internal English dictionary.
- **Use Spanish Dictionary**: Uses words from an internal Spanish dictionary.

This allows for more realistic and contextually relevant text generation, especially for demo or QA environments.

---

### 🤖 AI-Powered Data Generation (Ollama)

The plugin integrates with [Ollama](https://ollama.com/) to generate context-aware, realistic seed data using local LLMs. Instead of relying
solely on random/faker values, you can leverage AI to produce meaningful content for string columns.

- **AI Columns Selection**: A dedicated tab in the generation dialog lets you choose which string columns receive AI-generated content.
- **Smart Defaults**: Columns named `description`, `title`, `bio`, `email`, etc. are pre-selected automatically.
- **Batch Generation**: AI values are generated in batches with retries and deduplication.
- **Configurable Word Count**: Control output length from a single word up to full paragraphs.
- **Request Timeout Control**: Configure the Ollama request timeout from settings.
- **Cancellable Processing**: The generation task can be canceled from the IDE progress UI.
- **Global AI Settings**: Enable/disable AI generation, set the Ollama URL and model, provide domain context, and test connectivity from
  **Settings → DBSeed4SQL**.

---

### 🎯 Advanced Column Rules and Exclusion Safety

Recent iterations of the plugin introduced more precise controls for generation behavior:

- **Repetition Rules Panel**: Configure how column values repeat across generated rows.
- **Regex-Based Values**: Generate values from a Java regex pattern for individual columns.
- **Excluded Tables / Columns**: Omit risky or irrelevant entities from generation.
- **FK Exclusion Warnings**: The dialog warns when exclusions could create invalid foreign-key scenarios.
- **Selected Count Feedback**: Selection dialogs show clearer counts and improve bulk actions.

These controls help tune generated data without manually editing SQL afterwards.

---

### 🔁 Circular Reference Improvements

Circular dependencies between tables are handled predictably across supported dialects.

- The planner first identifies strongly connected components (Tarjan) to isolate cyclic groups.
- For engines that support it, inserts run with deferred constraints inside a transaction.
- For engines without deferred constraints, DBSeed4SQL performs a two-step strategy (`INSERT` + targeted `UPDATE`) to resolve FK cycles.
- Circular-reference handling can be configured per table with explicit termination modes.

Generated scripts preserve referential integrity while reducing manual post-processing for cyclic schemas.

---

### 🔧 Main Functionality

- Schema introspection via `DatabaseMetaData`.
- Table sorting with Tarjan's algorithm to detect cycles.
- Synthetic data generation with [DataFaker](https://www.datafaker.net/) respecting PKs, FKs, uniqueness, and nullability.
- Heuristics to recognize semantic column names like `email`, `name`, `uuid`, `title`, or `description`.
- Robust circular reference handling with deferred constraints (`SET CONSTRAINTS ALL DEFERRED`) or targeted post-insert updates.
- Interactive selection of UUID primary keys through a dedicated UI dialog.
- Automatic opening of the generated SQL in the IntelliJ editor.
- Visual schema designer to prototype tables and export creation SQL.
- Batch query generation for better performance with larger datasets.
- Configurable soft-delete columns and numeric precision defaults.
- Improved password input with a show/hide toggle directly in the connection dialog.

---

### ✅ Compatibility and Requirements

- **IntelliJ Platform**: builds `251` through `261.*`.
- **Java**: Java 21.
- **Build Tool**: Gradle wrapper `9.4.1`.
- **Optional AI Runtime**: a reachable Ollama server if you enable AI generation.
- **Optional Docker**: useful for local database smoke testing and integration scenarios.

---

### 🛠️ Installation and First Run

You can use the plugin in two common ways:

1. Install it from the **JetBrains Marketplace** once the release is published.
2. Build the plugin locally and install the generated `.zip` from disk in IntelliJ via:
   **Settings → Plugins → Install Plugin from Disk...**

Typical first-run flow:

1. Open **Tools → DBSeed4SQL**.
2. Select the database driver.
3. Confirm driver download if it is not available locally.
4. Enter connection details in `SeedDialog`.
5. Optionally save the connection as a reusable profile.
6. Configure UUID handling, exclusions, repetition rules, AI columns, and circular-reference behavior.
7. Generate and review the SQL script opened automatically in the editor.

---

### ⚙️ Build, Run and Test Locally

The project uses the Gradle IntelliJ Platform plugin.

Build the plugin:

```bash
./gradlew clean buildPlugin
```

Run the plugin in a sandbox IDE:

```bash
./gradlew runIde
```

Run the automated tests:

```bash
./gradlew test
```

When `buildPlugin` completes, Gradle produces the installable plugin distribution under `build/distributions/`.

---

### 🐳 Local Database Playground

The repository includes a `docker/` folder with a ready-to-use local stack for experimentation:

- PostgreSQL
- MySQL
- SQLite

The schemas are stored in `docker/initdb/`, and SQLite writes to `docker/sqlite_data/test.db`.

Start the local databases with:

```bash
cd docker
docker compose up -d
```

This is useful when manually testing introspection, FK handling, and dialect-specific generation output.

---

### 📋 Quick Example

1. Select the database driver from the list (PostgreSQL, MySQL, SQL Server, SQLite, etc.).
   - If it is not installed, the plugin will offer to download it automatically.
2. Configure the connection in the **SeedDialog** (JDBC URL, user, password, schema).
3. Optionally save the connection as a profile for future runs.
4. Mark the PKs you want to treat as UUIDs in the **PkUuidSelectionDialog**.
5. Optionally exclude tables/columns, configure repetition rules, or enable AI generation.
6. The plugin introspects the schema, generates data, and builds an SQL script like:

```text
BEGIN;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO "<users_table>" ("id", "name", "email")
VALUES ('<uuid>', '<generated_name>', '<generated_email>');

INSERT INTO "<orders_table>" ("id", "user_id", "amount")
VALUES ('<uuid>', '<users_table.id>', <generated_amount>);

COMMIT;
```

The file is automatically opened in an editor within IntelliJ, ready to be executed, reviewed, or exported.

---

### 📝 Changelog

The release history is tracked in `CHANGELOG.md`. The latest documented version is **`1.3.6`**.

---

👨‍💻 Project created by **Luis Pepe** ([@LuisPPB16](https://github.com/luisppb16)), a Java developer specializing in backend,
microservices, and development tooling.
