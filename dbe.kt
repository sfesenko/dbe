///usr/bin/env bash -c 'export DBE_HOME="$(cd "$(dirname "$1")" && pwd)" && exec jbang "$@"' _ "$0" "$@" ; exit $?

//DEPS tools.jackson.core:jackson-databind:3.0.3
//DEPS tools.jackson.module:jackson-module-kotlin:3.0.3

//SOURCES dbe-core.kt

import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.SQLException

// ── Output Formatters ───────────────────────────────────────────────────────────

private val prettyJsonMapper: JsonMapper = (jacksonObjectMapper() as JsonMapper)
    .rebuild()
    .enable(SerializationFeature.INDENT_OUTPUT)
    .build()

fun formatJson(result: QueryResult): String {
    val rows = result.rows.map { row ->
        result.columns.zip(row).toMap()
    }
    return prettyJsonMapper.writeValueAsString(rows)
}

fun formatCsv(result: QueryResult): String {
    val sb = StringBuilder()
    sb.appendLine(result.columns.joinToString(",") { escapeCsvField(it) })
    for (row in result.rows) {
        sb.appendLine(row.joinToString(",") { escapeCsvField(it?.toString() ?: "") })
    }
    return sb.toString().trimEnd()
}

fun escapeCsvField(field: String): String {
    return if (field.contains(',') || field.contains('"') || field.contains('\n')) {
        "\"${field.replace("\"", "\"\"")}\""
    } else {
        field
    }
}

fun formatTable(result: QueryResult): String {
    if (result.columns.isEmpty()) return "(no columns)"

    val isTTY = System.console() != null
    val bold  = if (isTTY) "\u001b[1m" else ""
    val dim   = if (isTTY) "\u001b[2m" else ""
    val reset = if (isTTY) "\u001b[0m" else ""

    val nullStr = if (isTTY) "${dim}∅${reset}" else "∅"
    val nullDisplayLen = 1

    if (result.rows.isEmpty()) {
        val header = result.columns.joinToString(" │ ") { "$bold$it$reset" }
        return "$header\n${dim}(0 rows)${reset}"
    }

    val numericColumns = result.columns.indices.map { i ->
        result.rows.all { row ->
            val v = row.getOrNull(i)
            v == null || v is Number
        }
    }

    val colWidths = result.columns.mapIndexed { i, col ->
        val maxDataWidth = result.rows.maxOfOrNull { row ->
            val v = row.getOrNull(i)
            if (v == null) nullDisplayLen else v.toString().length
        } ?: 0
        maxOf(col.length, maxDataWidth)
    }

    val maxColWidth = 60
    val cappedWidths = colWidths.map { minOf(it, maxColWidth) }

    val sb = StringBuilder()

    sb.appendLine(cappedWidths.joinToString("─┬─", prefix = "┌─", postfix = "─┐") { "─".repeat(it) })

    sb.appendLine(result.columns.mapIndexed { i, col ->
        "$bold${col.padEnd(cappedWidths[i])}$reset"
    }.joinToString(" │ ", prefix = "│ ", postfix = " │"))

    sb.appendLine(cappedWidths.joinToString("─┼─", prefix = "├─", postfix = "─┤") { "─".repeat(it) })

    for (row in result.rows) {
        sb.appendLine(row.mapIndexed { i, value ->
            val w = cappedWidths[i]
            if (value == null) {
                val padding = " ".repeat(w - nullDisplayLen)
                if (numericColumns[i]) "$padding$nullStr" else "$nullStr$padding"
            } else {
                var str = value.toString()
                if (str.length > maxColWidth) {
                    str = str.take(maxColWidth - 1) + "…"
                }
                if (numericColumns[i]) str.padStart(w) else str.padEnd(w)
            }
        }.joinToString(" │ ", prefix = "│ ", postfix = " │"))
    }

    sb.appendLine(cappedWidths.joinToString("─┴─", prefix = "└─", postfix = "─┘") { "─".repeat(it) })

    sb.append("${dim}(${result.rows.size} row${if (result.rows.size != 1) "s" else ""})${reset}")

    return sb.toString()
}

fun formatOutput(result: QueryResult, format: String): String {
    return when (format.lowercase()) {
        "json" -> formatJson(result)
        "csv" -> formatCsv(result)
        "table" -> formatTable(result)
        else -> error("Unknown output format: '$format'. Supported formats: json, csv, table")
    }
}

// ── Usage & Help ────────────────────────────────────────────────────────────────

fun printUsage() {
    System.err.println("""
        |Usage: dbe.kt <database> <query> [format]
        |
        |Arguments:
        |  database  Name of the database (as defined in databases.json)
        |  query     SQL query to execute
        |  format    Output format: json, csv, table (default: table)
        |
        |Examples:
        |  dbe.kt mypostgres "SELECT * FROM users"
        |  dbe.kt mypostgres "SELECT * FROM users" json
        |  dbe.kt mymysql "SELECT id, name FROM products LIMIT 10" csv
        |
        |Configuration:
        |  databases.json is searched and merged from (later overrides earlier):
        |    1. ~/work/notes/databases.json
        |    2. ~/databases.json
        |    3. <dbe.kt dir>/databases.json  (auto-detected or DBE_HOME)
        |    4. ./databases.json (current directory)
    """.trimMargin())
}

// ── Main ────────────────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
        printUsage()
        System.exit(if (args.isEmpty()) 1 else 0)
    }

    if (args.size < 2) {
        System.err.println("Error: Missing required arguments.\n")
        printUsage()
        System.exit(1)
    }

    val dbName = args[0]
    val query = args[1]
    val format = if (args.size >= 3) args[2] else "table"

    if (format.lowercase() !in listOf("json", "csv", "table")) {
        System.err.println("Error: Unknown output format '$format'. Supported formats: json, csv, table")
        System.exit(1)
    }

    try {
        val databases = loadMergedConfig()

        val dbConfig = databases[dbName] ?: run {
            val available = databases.keys.sorted().joinToString(", ")
            System.err.println("Error: Database '$dbName' not found.\nAvailable databases: $available")
            System.exit(1)
            return
        }

        loadDriver(dbConfig.driverJar, dbConfig.driverClass)

        connect(dbConfig).use { connection ->
            val result = executeQuery(connection, query)
            println(formatOutput(result, format))
        }

    } catch (e: SQLException) {
        System.err.println("SQL Error: ${e.message}\nSQL State: ${e.sqlState}\nError Code: ${e.errorCode}\nQuery: $query")
        System.exit(1)
    } catch (e: IllegalStateException) {
        System.err.println("Error: ${e.message}")
        System.exit(1)
    } catch (e: Exception) {
        System.err.println("Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
        if (System.getenv("DBE_DEBUG") != null) {
            e.printStackTrace()
        } else {
            System.err.println("Set DBE_DEBUG=1 for stack trace.")
        }
        System.exit(1)
    }
}
