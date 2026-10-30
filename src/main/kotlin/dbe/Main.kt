package dbe

import java.io.File
import java.sql.SQLException

// ── Usage & Help ────────────────────────────────────────────────────────────────

private fun printUsage() {
    System.err.println("""
        |Usage: dbe <database> <query> [--format table|json|csv]
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
        |
        |Examples:
        |  dbe mypostgres "SELECT * FROM users"
        |  dbe mypostgres "SELECT * FROM users" --format json
        |  dbe mymysql "SELECT id, name FROM products LIMIT 10" --format csv
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
                val overridden = source.databaseNames.filter {
                    diagnostic.sourceOf[it] != source.path
                }
                if (overridden.isNotEmpty()) {
                    output.println("    overridden: ${overridden.joinToString(", ")}")
                }
            }
        }
    }

    if (detailed) {
        output.println("Effective databases:")
        if (diagnostic.databases.isEmpty()) {
            output.println("  (none)")
        } else {
            diagnostic.databases.keys.sorted().forEach { name ->
                val config = diagnostic.databases.getValue(name)
                val dialect = config.resolvedDialect() ?: "unknown"
                output.println("  $name  dialect=$dialect  driverJar=${config.driverJar}")
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

private fun parseFormat(args: Array<String>): String {
    val idx = args.indexOf("--format")
    if (idx == -1) return "table"
    if (idx + 1 >= args.size) {
        System.err.println("Error: --format requires a value (table, json, csv)")
        System.exit(1)
    }
    val format = args[idx + 1]
    if (format.lowercase() !in listOf("json", "csv", "table")) {
        System.err.println("Error: Unknown output format '$format'. Supported formats: json, csv, table")
        System.exit(1)
    }
    return format
}

/**
 * Collect positional arguments (everything that is not --format/--format's value).
 */
private fun positionalArgs(args: Array<String>): List<String> {
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

    // Query mode: <database> <query> [--format <format>]
    val format = parseFormat(args)
    val positional = positionalArgs(args)

    if (positional.size < 2) {
        System.err.println("Error: Missing required arguments.\n")
        printUsage()
        System.exit(1)
    }

    val dbName = positional[0]
    val query = positional[1]

    try {
        val databases = loadMergedConfig()

        val dbConfig = databases[dbName] ?: run {
            System.err.println("Error: Database '$dbName' not found.\n")
            System.err.println("Available databases:")
            printDatabaseList(databases)
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
