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
    val driverClass: String? = null,
    val driverJar: String? = null,
    val dialect: String? = null,
    val driverName: String? = null,
    val driverError: String? = null,
    val driverSource: String? = null
)

data class DriverConfig(
    val `class`: String,
    val jar: String
)

data class DatabaseEntry(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val driver: String? = null,
    val driverClass: String? = null,
    val driverJar: String? = null,
    val dialect: String? = null
)

data class DatabasesFile(
    val drivers: Map<String, DriverConfig> = emptyMap(),
    val databases: Map<String, DatabaseEntry> = emptyMap()
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<Any?>>
)

data class ConfigSourceDiagnostic(
    val path: String,
    val databaseNames: List<String>,
    val driverNames: List<String> = emptyList(),
    val parseError: String? = null
)

data class ConfigDiagnostic(
    val searchedPaths: List<String>,
    val sources: List<ConfigSourceDiagnostic>,
    val databases: Map<String, DatabaseConfig>,
    val sourceOf: Map<String, String>,
    val drivers: Map<String, DriverConfig> = emptyMap(),
    val driverSourceOf: Map<String, String> = emptyMap(),
    val driverUsers: Map<String, List<String>> = emptyMap()
)

fun DatabaseConfig.resolvedDialect(): String? {
    val configuredDialect = dialect
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()
    return configuredDialect ?: inferDialect(jdbcUrl, driverClass ?: "")
}

internal fun inferDialect(jdbcUrl: String, driverClass: String): String? {
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

internal fun configSearchPaths(): List<File> {
    return configSearchDirs()
        .map { File(it, "databases.json").canonicalFile }
        .distinct()
}

internal fun parseConfig(configFile: File): DatabasesFile {
    val mapper = jacksonObjectMapper()
    return mapper.readValue<DatabasesFile>(configFile)
}

fun loadConfig(configFile: File): DatabasesFile {
    return try {
        parseConfig(configFile)
    } catch (e: Exception) {
        System.err.println("Warning: Failed to parse '${configFile.absolutePath}': ${e.message}")
        DatabasesFile()
    }
}

internal fun expandTilde(value: String): String {
    val home = System.getProperty("user.home")
    return when {
        value == "~" -> home
        value.startsWith("~/") -> home + value.substring(1)
        else -> value
    }
}

internal fun expandJdbcUrl(value: String): String {
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

internal fun displayPath(path: String): String {
    val home = System.getProperty("user.home")
    return when {
        path == home -> "~"
        path.startsWith("$home/") || path.startsWith("$home\\") -> "~" + path.substring(home.length)
        else -> path
    }
}

internal fun normalizeConfig(config: DatabaseConfig): DatabaseConfig {
    return config.copy(
        jdbcUrl = expandJdbcUrl(config.jdbcUrl),
        driverJar = config.driverJar?.let(::expandTilde)
    )
}

private data class PendingDatabase(
    val entry: DatabaseEntry,
    val source: String
)

private data class DriverOverride(
    val name: String,
    val previousSource: String,
    val newSource: String
)

internal data class MissingDriverJar(
    val jarPath: String,
    val source: String
)

private fun missingDriverMessage(name: String, drivers: Map<String, DriverConfig>): String {
    val available = drivers.keys.sorted()
    return if (available.isEmpty()) {
        "driver '$name' is not defined\n  no drivers are defined"
    } else {
        "driver '$name' is not defined\n  available drivers: ${available.joinToString(", ")}"
    }
}

internal fun resolveDatabaseConfig(
    entry: DatabaseEntry,
    drivers: Map<String, DriverConfig>
): DatabaseConfig {
    val base = DatabaseConfig(
        jdbcUrl = entry.jdbcUrl,
        username = entry.username,
        password = entry.password,
        dialect = entry.dialect,
        driverName = entry.driver
    )
    val hasInlineDriver = entry.driverClass != null || entry.driverJar != null

    val resolved = when {
        entry.driver != null && hasInlineDriver -> base.copy(
            driverError = "both 'driver' and inline 'driverClass'/'driverJar' are set"
        )

        entry.driver != null -> {
            val driver = drivers[entry.driver]
            if (driver == null) {
                base.copy(driverError = missingDriverMessage(entry.driver, drivers))
            } else {
                base.copy(driverClass = driver.`class`, driverJar = driver.jar)
            }
        }

        entry.driverClass != null && entry.driverJar != null -> base.copy(
            driverClass = entry.driverClass,
            driverJar = entry.driverJar
        )

        hasInlineDriver -> base.copy(
            driverError = "both 'driverClass' and 'driverJar' are required when not using 'driver'"
        )

        else -> base.copy(
            driverError = "missing 'driver' reference or inline 'driverClass'/'driverJar'"
        )
    }

    return normalizeConfig(resolved)
}

internal fun collectMissingDriverJars(
    databases: Map<String, DatabaseConfig>,
    sourceOf: Map<String, String>,
    driverSourceOf: Map<String, String>
): Map<MissingDriverJar, List<String>> {
    val missing = linkedMapOf<MissingDriverJar, MutableList<String>>()
    for ((name, config) in databases) {
        val jar = config.driverJar ?: continue
        if (config.driverError == null && !File(jar).exists()) {
            val source = config.driverName
                ?.let { driverSourceOf[it] }
                ?: sourceOf[name]
                ?: "databases.json"
            val key = MissingDriverJar(File(jar).absolutePath, source)
            missing.getOrPut(key) { mutableListOf() }.add(name)
        }
    }
    return missing.mapValues { (_, names) -> names.toList() }
}

fun inspectConfig(): ConfigDiagnostic =
    inspectConfig(configSearchPaths())

fun inspectConfig(searchPaths: List<File>): ConfigDiagnostic {
    val searchedPaths = searchPaths.map { it.canonicalFile }.distinct()
    val sources = mutableListOf<ConfigSourceDiagnostic>()
    val mergedDrivers = linkedMapOf<String, DriverConfig>()
    val driverSourceOf = linkedMapOf<String, String>()
    val driverOverrides = mutableListOf<DriverOverride>()
    val pendingDatabases = linkedMapOf<String, PendingDatabase>()
    val sourceOf = linkedMapOf<String, String>()

    for (file in searchedPaths.filter { it.exists() }) {
        try {
            val config = parseConfig(file)
            sources += ConfigSourceDiagnostic(
                path = file.path,
                databaseNames = config.databases.keys.sorted(),
                driverNames = config.drivers.keys.sorted()
            )

            for ((name, rawDriver) in config.drivers) {
                val driver = rawDriver.copy(jar = expandTilde(rawDriver.jar))
                val previous = mergedDrivers[name]
                val previousSource = driverSourceOf[name]
                if (previous != null && previousSource != null && previous != driver) {
                    driverOverrides += DriverOverride(name, previousSource, file.path)
                }
                mergedDrivers[name] = driver
                driverSourceOf[name] = file.path
            }

            for ((name, entry) in config.databases) {
                if (sourceOf.containsKey(name)) {
                    System.err.println(
                        "Warning: '$name' overridden by ${displayPath(file.path)} (was ${displayPath(sourceOf.getValue(name))})"
                    )
                }
                pendingDatabases[name] = PendingDatabase(entry, file.path)
                sourceOf[name] = file.path
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

    val merged = linkedMapOf<String, DatabaseConfig>()
    for ((name, pending) in pendingDatabases) {
        val config = resolveDatabaseConfig(pending.entry, mergedDrivers)
        val resolved = if (config.driverError == null && config.driverName != null) {
            config.copy(driverSource = driverSourceOf[config.driverName])
        } else {
            config
        }
        if (resolved.driverError != null) {
            System.err.println(
                "Warning: ${displayPath(pending.source)} — database '$name': ${resolved.driverError}"
            )
        }
        merged[name] = resolved
    }

    val driverUsers = mergedDrivers.keys.associateWith { driverName ->
        merged
            .filterValues { it.driverName == driverName && it.driverError == null }
            .keys
            .sorted()
    }

    for (override in driverOverrides) {
        val users = driverUsers[override.name].orEmpty()
        val usedBy = if (users.isEmpty()) "" else "; used by: ${users.joinToString(", ")}"
        System.err.println(
            "Warning: driver '${override.name}' overridden by ${displayPath(override.newSource)} " +
                "(was ${displayPath(override.previousSource)})$usedBy"
        )
    }

    val missingDriverJars = collectMissingDriverJars(merged, sourceOf, driverSourceOf)

    for ((missing, dbNames) in missingDriverJars) {
        System.err.println(
            "Warning: ${displayPath(missing.source)} — missing driverJar for ${dbNames.sorted().joinToString(", ")}"
        )
        System.err.println("  ${displayPath(missing.jarPath)}")
    }

    return ConfigDiagnostic(
        searchedPaths = searchedPaths.map(File::getPath),
        sources = sources,
        databases = merged,
        sourceOf = sourceOf,
        drivers = mergedDrivers,
        driverSourceOf = driverSourceOf,
        driverUsers = driverUsers
    )
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

/**
 * Loads and registers the JDBC driver for [databaseName] from [DatabaseConfig.driverJar].
 *
 * Drivers are cached per `(driverJar, driverClass)` for the lifetime of the process,
 * so replacing the JAR on disk requires a restart to take effect.
 */
@Synchronized
fun loadDriver(
    databaseName: String,
    config: DatabaseConfig,
    configFile: String? = null
) {
    config.driverError?.let { message ->
        val source = config.driverSource ?: configFile ?: "databases.json"
        error(
            "Cannot load driver for '$databaseName'\n" +
                "  file: ${displayPath(source)}\n" +
                "  $message"
        )
    }

    val driverClass = config.driverClass
        ?: error("driverClass is missing for '$databaseName'")
    val driverJar = config.driverJar
        ?: error("driverJar is missing for '$databaseName'")
    val jarFile = File(driverJar)
    if (!jarFile.exists()) {
        val source = config.driverSource ?: configFile ?: "databases.json"
        error(
            "driverJar not found for '$databaseName'\n" +
                "  file: ${displayPath(source)}\n" +
                "  jar:  ${displayPath(jarFile.absolutePath)}"
        )
    }
    val key = "${jarFile.canonicalPath}|$driverClass"
    if (loadedDrivers.containsKey(key)) return

    val classLoader = URLClassLoader(
        arrayOf(jarFile.toURI().toURL()),
        ClassLoader.getSystemClassLoader()
    )
    try {
        val driverInstance = Class.forName(driverClass, true, classLoader)
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

/**
 * Opens a connection using [config]. The caller must ensure the driver for this
 * database was registered first via [loadDriver].
 */
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
