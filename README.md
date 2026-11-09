# dbe — CLI Database Explorer

dbe is a Kotlin/JVM CLI and MCP server for running SQL against configured JDBC
databases. JDBC drivers are loaded from JARs at runtime.

## Build

Requires JDK 17 or newer. Runtime requires a JDBC driver JAR for each
configured driver.

```bash
./mvnw package
java -jar target/dbe-1.0-SNAPSHOT.jar --help
```

The `./dbe` launcher runs the built JAR:

```bash
./dbe --list
./dbe local "SELECT * FROM users LIMIT 5" --format json
```

## CLI

```text
dbe <database> <query> [--format table|json|csv]
dbe --list
dbe --diagnose
dbe --show-skill
dbe --mcp
```

Default output is `table`; `json` and `csv` are also supported. `UPDATE`,
`INSERT`, and `DELETE` return `affected_rows`. `--show-skill` prints the
packaged agent instructions.

## Configuration

`databases.json` is merged from these locations, later files overriding
earlier ones:

1. `~/work/notes/databases.json`
2. `~/databases.json`
3. `DBE_HOME` or the application directory
4. `./databases.json`

```json
{
  "drivers": {
    "postgresql": {
      "class": "org.postgresql.Driver",
      "jar": "~/jdbc-drivers/postgresql-42.7.13.jar"
    }
  },
  "databases": {
    "local": {
      "jdbcUrl": "jdbc:postgresql://localhost:5432/example",
      "username": "readonly",
      "password": "replace-me",
      "driver": "postgresql"
    }
  }
}
```

Replace the example credentials and driver path with your own.

Required database fields are `jdbcUrl`, `username`, and `password`. A database
must use either `driver` or the inline `driverClass` + `driverJar` pair, not
both. `dialect` is optional and inferred from the JDBC URL or driver class.

The `drivers` section is optional; each entry requires `class` and `jar`.
Drivers merge by name across files, and database references resolve after all
files are merged. Leading `~/` and a lone `~` are expanded in driver JAR paths
and in JDBC URLs such as `jdbc:sqlite:~/data/app.db`.

Use `--diagnose` to inspect effective databases, drivers, sources, and
warnings.

## MCP server

```bash
./dbe --mcp
```

Stdio MCP server exposing `list_databases` and `query`.

```json
{
  "mcpServers": {
    "dbe": {
      "command": "/path/to/dbe",
      "args": ["--mcp"]
    }
  }
}
```

`query` permits arbitrary SQL, including writes. A database account with
appropriate privileges is recommended.
