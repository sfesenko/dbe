///usr/bin/env jbang "$0" "$@" ; exit $?

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

fun resolveConfigPath(): File {
    // Look for databases.json next to the script, then in current directory
    val scriptDir = File(object {}.javaClass.protectionDomain.codeSource?.location?.toURI()?.path ?: ".").parentFile
    val candidates = listOfNotNull(
        scriptDir?.let { File(it, "databases.json") },
        File("databases.json"),
        File(System.getProperty("user.home"), ".dbe/databases.json")
    )
    return candidates.firstOrNull { it.exists() }
        ?: error("Configuration file 'databases.json' not found.\nSearched in:\n${candidates.joinToString("\n") { "  - ${it.absolutePath}" }}")
}

fun loadConfig(configFile: File): DatabasesFile {
    val mapper = jacksonObjectMapper()
    try {
        return mapper.readValue<DatabasesFile>(configFile)
    } catch (e: Exception) {
        error("Failed to parse '${configFile.absolutePath}':\n${e.message}\n\nExpected format:\n{\n  \"databases\": {\n    \"mydb\": {\n      \"jdbcUrl\": \"jdbc:...\",\n      \"username\": \"...\",\n      \"password\": \"...\",\n      \"driverClass\": \"...\",\n      \"driverJar\": \"/path/to/driver.jar\"\n    }\n  }\n}")
    }
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
                readColumnValue(rs, i, meta.getColumnType(i))
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

fun readColumnValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
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
        |  databases.json is searched in:
        |    1. Same directory as the script
        |    2. Current working directory
        |    3. ~/.dbe/databases.json
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
        // Load configuration
        val configFile = resolveConfigPath()
        val config = loadConfig(configFile)

        // Find database
        val dbConfig = config.databases[dbName] ?: run {
            val available = config.databases.keys.sorted().joinToString(", ")
            System.err.println("Error: Database '$dbName' not found in ${configFile.name}.\nAvailable databases: $available")
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
