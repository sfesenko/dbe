import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.ResultSet
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
    System.getenv("DBE_HOME")?.let { return File(it) }

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
    val sourceOf = mutableMapOf<String, String>()

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
