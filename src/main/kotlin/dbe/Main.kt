package dbe

import java.io.File
import java.sql.SQLException

// ── Usage & Help ────────────────────────────────────────────────────────────────

private fun printUsage() {
    System.err.println("""
        |Usage: dbe <database> <query> [json|csv|table]
        |       dbe <database> <query> [--format table|json|csv]
        |       dbe --list
        |       dbe --diagnose
        |       dbe --show-skill
        |       dbe --mcp
        |
        |Modes:
        |  <database> <query>   Execute a SQL query and print results
        |  --list               List all configured databases with their dialects
        |  --diagnose           Show config search paths, merge order, and effective entries
        |  --show-skill         Print the packaged agent skill document
        |  --mcp                Start MCP (Model Context Protocol) server over stdio
        |  -h, --help           Show this help message
        |
        |Options:
        |  --format <format>    Output format: table (default), json, csv
        |                       Also accepted as a trailing positional arg: json|csv|table
        |
        |Examples:
        |  dbe mypostgres "SELECT * FROM users"
        |  dbe mypostgres "SELECT * FROM users" json
        |  dbe mypostgres "SELECT * FROM users" --format json
        |  dbe mymysql "SELECT id, name FROM products LIMIT 10" csv
        |  dbe --list
        |  dbe --mcp
        |
        |Configuration:
        |  databases.json is searched and merged from (later overrides earlier):
        |    1. ~/work/notes/databases.json
        |    2. ~/databases.json
        |    3. <dbe dir>/databases.json  (auto-detected or DBE_HOME)
        |    4. ./databases.json (current directory)
        |
        |Environment:
        |  DBE_HOME     Directory to search for databases.json
        |  DBE_DEBUG    Set to any value for full stack traces on errors
    """.trimMargin())
}

private fun printConfigDiagnostic(diagnostic: ConfigDiagnostic, detailed: Boolean, output: java.io.PrintStream) {
    output.println("Database configuration search:")
    for (path in diagnostic.searchedPaths) {
        val source = diagnostic.sources.firstOrNull { it.path == path }
        if (source == null) {
            output.println("  MISSING  $path")
            continue
        }

        if (source.parseError != null) {
            output.println("  INVALID  ${source.path}: ${source.parseError}")
        } else {
            output.println("  FOUND    ${source.path}")
            if (detailed) {
                val names = source.databaseNames.ifEmpty { listOf("(none)") }
                output.println("    databases: ${names.joinToString(", ")}")
                if (source.driverNames.isNotEmpty()) {
                    output.println("    drivers: ${source.driverNames.joinToString(", ")}")
                }
                val overridden = source.databaseNames.filter {
                    diagnostic.sourceOf[it] != source.path
                }
                if (overridden.isNotEmpty()) {
                    output.println("    overridden: ${overridden.joinToString(", ")}")
                }
                val overriddenDrivers = source.driverNames.filter {
                    diagnostic.driverSourceOf[it] != source.path
                }
                if (overriddenDrivers.isNotEmpty()) {
                    output.println("    overridden drivers: ${overriddenDrivers.joinToString(", ")}")
                }
            }
        }
    }

    if (detailed) {
        if (diagnostic.drivers.isNotEmpty()) {
            output.println("Effective drivers:")
            diagnostic.drivers.keys.sorted().forEach { name ->
                val driver = diagnostic.drivers.getValue(name)
                output.println("  $name  class=${driver.`class`}  jar=${driver.jar}")
                diagnostic.driverSourceOf[name]?.let { source ->
                    output.println("    source: ${displayPath(source)}")
                }
                val users = diagnostic.driverUsers[name].orEmpty()
                if (users.isNotEmpty()) {
                    output.println("    used by: ${users.joinToString(", ")}")
                }
            }
        }

        output.println("Effective databases:")
        if (diagnostic.databases.isEmpty()) {
            output.println("  (none)")
        } else {
            diagnostic.databases.keys.sorted().forEach { name ->
                val config = diagnostic.databases.getValue(name)
                val dialect = config.resolvedDialect() ?: "unknown"
                val driver = when {
                    config.driverName != null -> "driver=${config.driverName}"
                    config.driverJar != null -> "driverJar=${config.driverJar}"
                    else -> "driver=<missing>"
                }
                val error = config.driverError
                    ?.lineSequence()
                    ?.first()
                    ?.let { "  error=$it" }
                    .orEmpty()
                output.println("  $name  dialect=$dialect  $driver$error")
            }
        }
    }
}

private fun skillText(): String? {
    Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("dbe/SKILL.md")
        ?.use { return it.bufferedReader().readText() }

    val codeLocation = runCatching {
        File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
    }.getOrNull()
    val codeDirectory = codeLocation?.let { if (it.isDirectory) it else it.parentFile }
    val candidates = listOfNotNull(
        System.getenv("DBE_HOME")?.let { File(it, "SKILL.md") },
        codeDirectory?.let { File(it, "SKILL.md") },
        File(System.getProperty("user.dir"), "SKILL.md")
    ).map { it.canonicalFile }.distinct()

    return candidates.firstOrNull { it.isFile }?.readText()
}

private fun printSkill() {
    val skill = skillText()
    if (skill == null) {
        System.err.println("Error: SKILL.md is not packaged and could not be found next to the application.")
        System.exit(1)
        return
    }
    print(skill)
}

private fun printDatabaseList(databases: Map<String, DatabaseConfig>) {
    if (databases.isEmpty()) {
        println("No databases configured.")
        return
    }

    val names = databases.keys.sorted()
    val maxNameLength = names.maxOf { it.length }
    for (name in names) {
        val dialect = databases.getValue(name).resolvedDialect() ?: "unknown"
        println("${name.padEnd(maxNameLength)}  dialect=$dialect")
    }
}

// ── Argument Parsing ────────────────────────────────────────────────────────────

internal val supportedFormats = setOf("json", "csv", "table")

internal class ArgParseException(message: String) : RuntimeException(message)

internal fun parseFormat(args: Array<String>): String {
    val idx = args.indexOf("--format")
    if (idx != -1) {
        if (idx + 1 >= args.size) {
            throw ArgParseException("--format requires a value (table, json, csv)")
        }
        val format = args[idx + 1]
        if (format.lowercase() !in supportedFormats) {
            throw ArgParseException("Unknown output format '$format'. Supported formats: json, csv, table")
        }
        return format.lowercase()
    }

    // Legacy: dbe <database> <query> [json|csv|table]
    val positional = positionalArgs(args)
    if (positional.size >= 3 && positional[2].lowercase() in supportedFormats) {
        return positional[2].lowercase()
    }
    return "table"
}

/**
 * Collect positional arguments (everything that is not --format/--format's value).
 */
internal fun positionalArgs(args: Array<String>): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] == "--format") {
            i += 2 // skip --format and its value
        } else {
            result.add(args[i])
            i++
        }
    }
    return result
}

internal fun queryPositional(args: Array<String>): Pair<String, String> {
    val positional = positionalArgs(args).toMutableList()
    if (positional.size >= 3 && positional[2].lowercase() in supportedFormats) {
        positional.removeAt(2)
    }
    if (positional.size < 2) {
        throw ArgParseException("Missing required arguments.")
    }
    return positional[0] to positional[1]
}

// ── Main ────────────────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
        printUsage()
        System.exit(if (args.isEmpty()) 1 else 0)
    }

    // --mcp mode
    if (args[0] == "--mcp") {
        runMcpServer()
        return
    }

    // --show-skill mode
    if (args[0] == "--show-skill") {
        printSkill()
        return
    }

    // --diagnose mode
    if (args[0] in listOf("--diagnose", "diagnose")) {
        printConfigDiagnostic(inspectConfig(), detailed = true, output = System.out)
        return
    }

    // --list mode
    if (args[0] in listOf("--list", "list")) {
        try {
            val diagnostic = inspectConfig()
            printConfigDiagnostic(diagnostic, detailed = false, output = System.err)
            if (diagnostic.sources.isEmpty()) {
                error(
                    "No databases.json found.\nSearched in:\n" +
                        diagnostic.searchedPaths.joinToString("\n") { "  - $it" }
                )
            }
            printDatabaseList(diagnostic.databases)
        } catch (e: IllegalStateException) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        }
        return
    }

    // Query mode: <database> <query> [json|csv|table] or --format <format>
    val format: String
    val dbName: String
    val query: String
    try {
        format = parseFormat(args)
        val (d, q) = queryPositional(args)
        dbName = d
        query = q
    } catch (e: ArgParseException) {
        System.err.println("Error: ${e.message}\n")
        printUsage()
        System.exit(1)
        return
    }

    try {
        val diagnostic = inspectConfig()
        if (diagnostic.sources.isEmpty()) {
            error(
                "No databases.json found.\nSearched in:\n" +
                    diagnostic.searchedPaths.joinToString("\n") { "  - $it" }
            )
        }
        val databases = diagnostic.databases

        val dbConfig = databases[dbName] ?: run {
            System.err.println("Error: Database '$dbName' not found.\n")
            System.err.println("Available databases:")
            printDatabaseList(databases)
            System.exit(1)
            return
        }

        loadDriver(dbName, dbConfig, diagnostic.sourceOf[dbName])

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
