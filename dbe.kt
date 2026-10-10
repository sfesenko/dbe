///usr/bin/env bash -c 'export DBE_HOME="$(cd "$(dirname "$1")" && pwd)" && exec jbang "$@"' _ "$0" "$@" ; exit $?

//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
//DEPS com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Properties

// ── Data Classes ────────────────────────────────────────────────────────────────

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val driverClass: String,
    val driverJar: String
)

data class DatabasesFile(
    val databases: Map<String, DatabaseConfig>
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<Any?>>
)

// ── Configuration Loading ───────────────────────────────────────────────────────

/**
 * Resolve the directory where dbe.kt lives.
 * 1. DBE_HOME env var (set automatically by the shebang, or manually)
 * 2. Search PATH for dbe.kt (covers `jbang dbe.kt` when script is in PATH)
 */
fun scriptDir(): File? {
    // 1. Env var (set by shebang or user)
    System.getenv("DBE_HOME")?.let { return File(it) }

    // 2. Search PATH
    val path = System.getenv("PATH") ?: return null
    for (dir in path.split(File.pathSeparator)) {
        val candidate = File(dir, "dbe.kt")
        if (candidate.exists()) return File(dir)
    }
    return null
}

fun configSearchDirs(): List<File> {
    val home = System.getProperty("user.home")
    return listOfNotNull(
        File(home, "work/notes"),
        File(home),
        scriptDir(),
        File(".")
    )
}

fun findConfigFiles(): List<File> {
    return configSearchDirs()
        .map { File(it, "databases.json").canonicalFile }
        .distinct()
        .filter { it.exists() }
}

fun loadConfig(configFile: File): DatabasesFile {
    val mapper = jacksonObjectMapper()
    try {
        return mapper.readValue<DatabasesFile>(configFile)
    } catch (e: Exception) {
        System.err.println("Warning: Failed to parse '${configFile.absolutePath}': ${e.message}")
        return DatabasesFile(emptyMap())
    }
}

/**
 * Loads and merges databases from all found databases.json files.
 * Files are loaded in order: ~/work/notes, ~, current dir.
 * Later files override earlier ones (so current dir wins).
 * Warnings are printed to stderr for collisions.
 */
fun loadMergedConfig(): Map<String, DatabaseConfig> {
    val configFiles = findConfigFiles()
    if (configFiles.isEmpty()) {
        val searched = configSearchDirs().map { File(it, "databases.json").canonicalPath }
        error("No databases.json found.\nSearched in:\n${searched.joinToString("\n") { "  - $it" }}")
    }

    val merged = mutableMapOf<String, DatabaseConfig>()
    val sourceOf = mutableMapOf<String, String>()  // dbName -> file path that defined it

    for (file in configFiles) {
        val config = loadConfig(file)
        for ((name, dbConfig) in config.databases) {
            val prev = sourceOf[name]
            if (prev != null) {
                System.err.println("Warning: Database '$name' in ${file.path} overrides definition from $prev")
            }
            merged[name] = dbConfig
            sourceOf[name] = file.path
        }
    }

    return merged
}

// ── JDBC Driver Loading ─────────────────────────────────────────────────────────

/**
 * Wraps a JDBC Driver loaded via a child classloader so that DriverManager can use it.
 * DriverManager refuses to use drivers loaded by a different classloader than the caller,
 * so we wrap it in a delegating Driver loaded by the system classloader.
 */
class DriverShim(private val driver: Driver) : Driver {
    override fun connect(url: String?, info: Properties?): Connection? = driver.connect(url, info)
    override fun acceptsURL(url: String?): Boolean = driver.acceptsURL(url)
    override fun getPropertyInfo(url: String?, info: Properties?) = driver.getPropertyInfo(url, info)
    override fun getMajorVersion(): Int = driver.majorVersion
    override fun getMinorVersion(): Int = driver.minorVersion
    override fun jdbcCompliant(): Boolean = driver.jdbcCompliant()
    override fun getParentLogger() = driver.parentLogger
}

fun loadDriver(jarPath: String, driverClass: String) {
    val jarFile = File(jarPath)
    if (!jarFile.exists()) {
        error("JDBC driver JAR not found: ${jarFile.absolutePath}\nPlease verify the 'driverJar' path in databases.json")
    }
    val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), ClassLoader.getSystemClassLoader())
    val driverInstance = Class.forName(driverClass, true, classLoader).getDeclaredConstructor().newInstance() as Driver
    DriverManager.registerDriver(DriverShim(driverInstance))
}

// ── Database Connection & Query Execution ───────────────────────────────────────

fun connect(config: DatabaseConfig): Connection {
    return DriverManager.getConnection(config.jdbcUrl, config.username, config.password)
}

fun executeQuery(connection: Connection, query: String): QueryResult {
    val stmt = connection.createStatement()
    val trimmedQuery = query.trim()

    // Detect if it's a SELECT-like query (returns a ResultSet)
    val isQuery = stmt.execute(trimmedQuery)

    if (isQuery) {
        val rs = stmt.resultSet
        val meta = rs.metaData
        val columnCount = meta.columnCount
        val columns = (1..columnCount).map { meta.getColumnLabel(it) }
        val rows = mutableListOf<List<Any?>>()

        while (rs.next()) {
            val row = (1..columnCount).map { i ->
                readColumnValue(rs, i, meta.getColumnType(i), meta.getColumnTypeName(i))
            }
            rows.add(row)
        }
        rs.close()
        stmt.close()
        return QueryResult(columns, rows)
    } else {
        val updateCount = stmt.updateCount
        stmt.close()
        return QueryResult(
            columns = listOf("affected_rows"),
            rows = listOf(listOf(updateCount))
        )
    }
}

private val jsonMapper = jacksonObjectMapper()

fun readColumnValue(rs: ResultSet, index: Int, sqlType: Int, typeName: String): Any? {
    val value = when (sqlType) {
        Types.INTEGER, Types.SMALLINT, Types.TINYINT -> rs.getObject(index)?.let { (it as Number).toInt() }
        Types.BIGINT -> rs.getObject(index)?.let { (it as Number).toLong() }
        Types.FLOAT, Types.REAL -> rs.getObject(index)?.let { (it as Number).toFloat() }
        Types.DOUBLE -> rs.getObject(index)?.let { (it as Number).toDouble() }
        Types.DECIMAL, Types.NUMERIC -> rs.getBigDecimal(index)
        Types.BOOLEAN, Types.BIT -> rs.getObject(index)?.let { rs.getBoolean(index) }
        Types.DATE -> rs.getDate(index)?.toString()
        Types.TIME, Types.TIME_WITH_TIMEZONE -> rs.getTime(index)?.toString()
        Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> rs.getTimestamp(index)?.toString()
        Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rs.getBytes(index)?.let { "<binary ${it.size} bytes>" }
        Types.CLOB, Types.NCLOB -> rs.getClob(index)?.characterStream?.readText()
        Types.NULL -> null
        Types.OTHER -> {
            if (typeName.equals("json", ignoreCase = true) || typeName.equals("jsonb", ignoreCase = true)) {
                rs.getString(index)?.let { jsonMapper.readTree(it) }
            } else {
                rs.getString(index)
            }
        }
        else -> rs.getString(index)
    }
    return if (rs.wasNull()) null else value
}

// ── Output Formatters ───────────────────────────────────────────────────────────

fun formatJson(result: QueryResult): String {
    val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    val rows = result.rows.map { row ->
        result.columns.zip(row).toMap()
    }
    return mapper.writeValueAsString(rows)
}

fun formatCsv(result: QueryResult): String {
    val sb = StringBuilder()
    // Header
    sb.appendLine(result.columns.joinToString(",") { escapeCsvField(it) })
    // Rows
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
    // ANSI codes for terminal styling (only when outputting to a real terminal)
    val bold  = if (isTTY) "\u001b[1m" else ""
    val dim   = if (isTTY) "\u001b[2m" else ""
    val reset = if (isTTY) "\u001b[0m" else ""

    val nullStr = if (isTTY) "${dim}∅${reset}" else "∅"
    val nullDisplayLen = 1  // visible length of null marker

    if (result.rows.isEmpty()) {
        val header = result.columns.joinToString(" │ ") { "$bold$it$reset" }
        return "$header\n${dim}(0 rows)${reset}"
    }

    // Detect numeric columns for right-alignment
    val numericColumns = result.columns.indices.map { i ->
        result.rows.all { row ->
            val v = row.getOrNull(i)
            v == null || v is Number
        }
    }

    // Calculate column widths (accounting for actual display length)
    val colWidths = result.columns.mapIndexed { i, col ->
        val maxDataWidth = result.rows.maxOfOrNull { row ->
            val v = row.getOrNull(i)
            if (v == null) nullDisplayLen else v.toString().length
        } ?: 0
        maxOf(col.length, maxDataWidth)
    }

    // Max terminal width truncation (optional, keep rows readable)
    val maxColWidth = 60

    val cappedWidths = colWidths.map { minOf(it, maxColWidth) }

    val sb = StringBuilder()

    // ┌───┬───┐
    sb.appendLine(cappedWidths.joinToString("─┬─", prefix = "┌─", postfix = "─┐") { "─".repeat(it) })

    // │ col1 │ col2 │
    sb.appendLine(result.columns.mapIndexed { i, col ->
        "$bold${col.padEnd(cappedWidths[i])}$reset"
    }.joinToString(" │ ", prefix = "│ ", postfix = " │"))

    // ├───┼───┤
    sb.appendLine(cappedWidths.joinToString("─┼─", prefix = "├─", postfix = "─┤") { "─".repeat(it) })

    // Data rows
    for (row in result.rows) {
        sb.appendLine(row.mapIndexed { i, value ->
            val w = cappedWidths[i]
            if (value == null) {
                // Null: dim marker, left-padded to column width
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

    // └───┴───┘
    sb.appendLine(cappedWidths.joinToString("─┴─", prefix = "└─", postfix = "─┘") { "─".repeat(it) })

    // Row count
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

    // Validate format early
    if (format.lowercase() !in listOf("json", "csv", "table")) {
        System.err.println("Error: Unknown output format '$format'. Supported formats: json, csv, table")
        System.exit(1)
    }

    try {
        // Load and merge configuration from all databases.json files
        val databases = loadMergedConfig()

        // Find database
        val dbConfig = databases[dbName] ?: run {
            val available = databases.keys.sorted().joinToString(", ")
            System.err.println("Error: Database '$dbName' not found.\nAvailable databases: $available")
            System.exit(1)
            return  // unreachable, but satisfies compiler
        }

        // Load JDBC driver
        loadDriver(dbConfig.driverJar, dbConfig.driverClass)

        // Connect and execute
        connect(dbConfig).use { connection ->
            val result = executeQuery(connection, query)
            println(formatOutput(result, format))
        }

    } catch (e: SQLException) {
        System.err.println("SQL Error: ${e.message}\nSQL State: ${e.sqlState}\nError Code: ${e.errorCode}\nQuery: $query")
        System.exit(1)
    } catch (e: IllegalStateException) {
        // Errors thrown via error() function
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
