---
name: dbe-query
description: Query JDBC databases via dbe. Use when the user asks to run SQL, inspect tables, look up rows, or check database state.
---

# dbe

```bash
./dbe --list
./dbe --diagnose
./dbe <database> "<sql>" [json|csv|table]
./dbe <database> "<sql>" [--format table|json|csv]
```

Default format is `table`. Use trailing `json` or `--format json` when parsing results. DML returns `affected_rows`.

Config: merge `databases.json` from `~/work/notes` → `~` → `DBE_HOME`/JAR dir → `./` (later wins). Define JDBC drivers once under `drivers` and reference them from databases with `driver`; inline `driverClass`/`driverJar` still works. Driver references resolve across all merged files. `driverJar` and driver `jar` support leading `~/`.

MCP: `./dbe --mcp` → tools `list_databases`, `query` (`database`, `sql`). Writes allowed. Errors/diagnostics on stderr.

Debug: `DBE_DEBUG=1`.
