package dbe

import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.ConcurrentHashMap
import java.util.Properties

const val APP_VERSION = "1.0.0"

// ── Data Classes ────────────────────────────────────────────────────────────────

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val driverClass: String,
    val driverJar: String,
    val dialect: String? = null
)

data class DatabasesFile(
    val databases: Map<String, DatabaseConfig>
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<Any?>>
)

data class ConfigSourceDiagnostic(
    val path: String,
    val databaseNames: List<String>,
    val parseError: String? = null
)

data class ConfigDiagnostic(
    val searchedPaths: List<String>,
    val sources: List<ConfigSourceDiagnostic>,
    val databases: Map<String, DatabaseConfig>,
    val sourceOf: Map<String, String>
)

fun DatabaseConfig.resolvedDialect(): String? {
    val configuredDialect = dialect
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()
    return configuredDialect ?: inferDialect(jdbcUrl, driverClass)
}

private fun inferDialect(jdbcUrl: String, driverClass: String): String? {
    val candidates = listOf(jdbcUrl, driverClass).map { it.lowercase() }
    return when {
        candidates.any { it.contains("redshift") } -> "redshift"
        candidates.any { it.contains("postgresql") || it.contains(":postgres:") } -> "postgresql"
        candidates.any { it.contains("mariadb") } -> "mariadb"
        candidates.any { it.contains("mysql") } -> "mysql"
        candidates.any { it.contains("oracle") } -> "oracle"
        candidates.any { it.contains("sqlserver") || it.contains("microsoft.sqlserver") } -> "sqlserver"
        candidates.any { it.contains("sqlite") } -> "sqlite"
        candidates.any { it.contains(":h2:") || it.contains("org.h2") } -> "h2"
        else -> null
    }
}

// ── Configuration Loading ───────────────────────────────────────────────────────

/**
 * Resolve the directory where the dbe app lives (for databases.json next to the install).
 * 1. DBE_HOME env var (set manually or by wrapper script)
 * 2. The directory containing the running jar/classes
 *    If the jar is under Maven's `target/`, use the module root (parent of target).
 */
fun scriptDir(): File? {
    System.getenv("DBE_HOME")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return File(it) }

    return runCatching {
        val location = File(
            object {}.javaClass.protectionDomain.codeSource.location.toURI()
        )
        val dir = if (location.isDirectory) location else location.parentFile
        if (dir != null && dir.name == "target") dir.parentFile else dir
    }.getOrNull()
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
    return configSearchPaths().filter { it.exists() }
}

private fun configSearchPaths(): List<File> {
    return configSearchDirs()
        .map { File(it, "databases.json").canonicalFile }
        .distinct()
}

private fun parseConfig(configFile: File): DatabasesFile {
    val mapper = jacksonObjectMapper()
    return mapper.readValue<DatabasesFile>(configFile)
}

fun loadConfig(configFile: File): DatabasesFile {
    return try {
        parseConfig(configFile)
    } catch (e: Exception) {
        System.err.println("Warning: Failed to parse '${configFile.absolutePath}': ${e.message}")
        DatabasesFile(emptyMap())
    }
}

private fun expandTilde(value: String): String {
    val home = System.getProperty("user.home")
    return when {
        value == "~" -> home
        value.startsWith("~/") -> home + value.substring(1)
        else -> value
    }
}

private fun expandJdbcUrl(value: String): String {
    val expanded = expandTilde(value)
    if (expanded != value) return expanded

    val marker = value.indexOf(":~")
    if (marker >= 0 && (value.length == marker + 2 || value[marker + 2] == '/')) {
        return value.substring(0, marker + 1) +
            System.getProperty("user.home") +
            value.substring(marker + 2)
    }
    return value
}

private fun displayPath(path: String): String {
    val home = System.getProperty("user.home")
    return when {
        path == home -> "~"
        path.startsWith("$home/") || path.startsWith("$home\\") -> "~" + path.substring(home.length)
        else -> path
    }
}

private fun normalizeConfig(config: DatabaseConfig): DatabaseConfig {
    return config.copy(
        jdbcUrl = expandJdbcUrl(config.jdbcUrl),
        driverJar = expandTilde(config.driverJar)
    )
}

fun inspectConfig(): ConfigDiagnostic {
    val searchedPaths = configSearchPaths()
    val sources = mutableListOf<ConfigSourceDiagnostic>()
    val merged = linkedMapOf<String, DatabaseConfig>()
    val sourceOf = linkedMapOf<String, String>()

    for (file in searchedPaths.filter { it.exists() }) {
        try {
            val config = parseConfig(file)
            val names = config.databases.keys.sorted()
            sources += ConfigSourceDiagnostic(file.path, names)

            // name -> missing driverJar path
            val missingDrivers = linkedMapOf<String, MutableList<String>>()

            for ((name, rawConfig) in config.databases) {
                val normalized = normalizeConfig(rawConfig)
                val jarFile = File(normalized.driverJar)
                if (!jarFile.exists()) {
                    missingDrivers
                        .getOrPut(jarFile.absolutePath) { mutableListOf() }
                        .add(name)
                }
                if (sourceOf.containsKey(name)) {
                    System.err.println(
                        "Warning: '$name' overridden by ${displayPath(file.path)} (was ${displayPath(sourceOf.getValue(name))})"
                    )
                }
                merged[name] = normalized
                sourceOf[name] = file.path
            }

            for ((jarPath, dbNames) in missingDrivers) {
                System.err.println(
                    "Warning: ${displayPath(file.path)} — missing driverJar for ${dbNames.sorted().joinToString(", ")}"
                )
                System.err.println("  ${displayPath(jarPath)}")
            }
        } catch (e: Exception) {
            System.err.println("Warning: Failed to parse '${displayPath(file.absolutePath)}': ${e.message}")
            sources += ConfigSourceDiagnostic(
                path = file.path,
                databaseNames = emptyList(),
                parseError = e.message ?: e.javaClass.simpleName
            )
        }
    }

    return ConfigDiagnostic(
        searchedPaths = searchedPaths.map(File::getPath),
        sources = sources,
        databases = merged,
        sourceOf = sourceOf
    )
}

/**
 * Loads and merges databases from all found databases.json files.
 * Files are loaded in order: ~/work/notes, ~, current dir.
 * Later files override earlier ones (so current dir wins).
 * Warnings are printed to stderr for collisions.
 */
fun loadMergedConfig(): Map<String, DatabaseConfig> {
    val diagnostic = inspectConfig()
    if (diagnostic.sources.isEmpty()) {
        error(
            "No databases.json found.\nSearched in:\n" +
                diagnostic.searchedPaths.joinToString("\n") { "  - $it" }
        )
    }
    return diagnostic.databases
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

private data class LoadedDriver(
    val classLoader: URLClassLoader,
    val shim: DriverShim
)

private val loadedDrivers = ConcurrentHashMap<String, LoadedDriver>()

@Synchronized
fun loadDriver(
    databaseName: String,
    config: DatabaseConfig,
    configFile: String? = null
) {
    val jarFile = File(config.driverJar)
    if (!jarFile.exists()) {
        val source = configFile ?: "databases.json"
        error(
            "driverJar not found for '$databaseName'\n" +
                "  file: ${displayPath(source)}\n" +
                "  jar:  ${displayPath(jarFile.absolutePath)}"
        )
    }
    val key = "${jarFile.canonicalPath}|${config.driverClass}"
    if (loadedDrivers.containsKey(key)) return

    val classLoader = URLClassLoader(
        arrayOf(jarFile.toURI().toURL()),
        ClassLoader.getSystemClassLoader()
    )
    try {
        val driverInstance = Class.forName(config.driverClass, true, classLoader)
            .getDeclaredConstructor()
            .newInstance() as Driver
        val shim = DriverShim(driverInstance)
        DriverManager.registerDriver(shim)
        loadedDrivers[key] = LoadedDriver(classLoader, shim)
    } catch (e: Exception) {
        classLoader.close()
        throw e
    }
}

// ── Database Connection & Query Execution ───────────────────────────────────────

fun connect(config: DatabaseConfig): Connection {
    return DriverManager.getConnection(config.jdbcUrl, config.username, config.password)
}

fun executeQuery(connection: Connection, query: String): QueryResult {
    return connection.createStatement().use { stmt ->
        val isQuery = stmt.execute(query.trim())

        if (isQuery) {
            val rs = checkNotNull(stmt.resultSet) { "JDBC driver returned no result set" }
            rs.use { resultSet ->
                val meta = resultSet.metaData
                val columnCount = meta.columnCount
                val columns = (1..columnCount).map { meta.getColumnLabel(it) }
                val rows = mutableListOf<List<Any?>>()

                while (resultSet.next()) {
                    val row = (1..columnCount).map { i ->
                        readColumnValue(
                            resultSet,
                            i,
                            meta.getColumnType(i),
                            meta.getColumnTypeName(i)
                        )
                    }
                    rows.add(row)
                }
                QueryResult(columns, rows)
            }
        } else {
            QueryResult(
                columns = listOf("affected_rows"),
                rows = listOf(listOf(stmt.updateCount))
            )
        }
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
