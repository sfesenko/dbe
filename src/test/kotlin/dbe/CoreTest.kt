package dbe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CoreTest {

    private fun writeJson(dir: Path, name: String, json: String): File =
        dir.resolve(name).toFile().canonicalFile.apply { writeText(json.trimIndent()) }

    // ── inferDialect / resolvedDialect ────────────────────────────────────────────

    @Test
    fun `inferDialect detects postgresql from jdbc url`() {
        assertEquals("postgresql", inferDialect("jdbc:postgresql://localhost/db", "org.postgresql.Driver"))
    }

    @Test
    fun `inferDialect detects postgresql from driver class only`() {
        assertEquals("postgresql", inferDialect("jdbc:something://localhost/db", "org.postgresql.Driver"))
    }

    @Test
    fun `inferDialect detects redshift over postgresql`() {
        assertEquals("redshift", inferDialect("jdbc:redshift://cluster/db", "com.amazon.redshift.Driver"))
    }

    @Test
    fun `inferDialect detects mysql`() {
        assertEquals("mysql", inferDialect("jdbc:mysql://localhost/db", "com.mysql.cj.jdbc.Driver"))
    }

    @Test
    fun `inferDialect detects mariadb over mysql`() {
        assertEquals("mariadb", inferDialect("jdbc:mariadb://localhost/db", "org.mariadb.jdbc.Driver"))
    }

    @Test
    fun `inferDialect detects oracle`() {
        assertEquals("oracle", inferDialect("jdbc:oracle:thin:@localhost:1521:db", "oracle.jdbc.OracleDriver"))
    }

    @Test
    fun `inferDialect detects sqlserver`() {
        assertEquals("sqlserver", inferDialect("jdbc:sqlserver://localhost:1433;database=db", "com.microsoft.sqlserver.jdbc.SQLServerDriver"))
    }

    @Test
    fun `inferDialect detects sqlite`() {
        assertEquals("sqlite", inferDialect("jdbc:sqlite:/path/to/db.sqlite", "org.sqlite.JDBC"))
    }

    @Test
    fun `inferDialect detects h2 from url`() {
        assertEquals("h2", inferDialect("jdbc:h2:mem:test", "org.h2.Driver"))
    }

    @Test
    fun `inferDialect detects h2 from driver class only`() {
        assertEquals("h2", inferDialect("jdbc:custom://localhost/db", "org.h2.Driver"))
    }

    @Test
    fun `inferDialect returns null for unknown`() {
        assertNull(inferDialect("jdbc:custom://localhost/db", "com.example.CustomDriver"))
    }

    @Test
    fun `resolvedDialect uses explicit dialect when set`() {
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://localhost/db",
            username = "u", password = "p",
            driverClass = "org.postgresql.Driver",
            driverJar = "/some.jar",
            dialect = "mysql"
        )
        assertEquals("mysql", config.resolvedDialect())
    }

    @Test
    fun `resolvedDialect trims and lowercases explicit dialect`() {
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:custom://localhost/db",
            username = "u", password = "p",
            driverClass = "com.example.Driver",
            driverJar = "/some.jar",
            dialect = "  PostgreSQL  "
        )
        assertEquals("postgresql", config.resolvedDialect())
    }

    @Test
    fun `resolvedDialect falls back to inference when dialect is blank`() {
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://localhost/db",
            username = "u", password = "p",
            driverClass = "org.postgresql.Driver",
            driverJar = "/some.jar",
            dialect = "  "
        )
        assertEquals("postgresql", config.resolvedDialect())
    }

    @Test
    fun `resolvedDialect falls back to inference when dialect is null`() {
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:mysql://localhost/db",
            username = "u", password = "p",
            driverClass = "com.mysql.cj.jdbc.Driver",
            driverJar = "/some.jar",
            dialect = null
        )
        assertEquals("mysql", config.resolvedDialect())
    }

    // ── expandTilde ──────────────────────────────────────────────────────────────

    @Test
    fun `expandTilde converts lone tilde to home`() {
        val home = System.getProperty("user.home")
        assertEquals(home, expandTilde("~"))
    }

    @Test
    fun `expandTilde converts tilde-slash path to home`() {
        val home = System.getProperty("user.home")
        assertEquals("$home/foo/bar", expandTilde("~/foo/bar"))
    }

    @Test
    fun `expandTilde does not expand tilde in middle`() {
        assertEquals("/foo/~bar", expandTilde("/foo/~bar"))
    }

    @Test
    fun `expandTilde does not expand tilde-prefix without slash`() {
        assertEquals("~foo", expandTilde("~foo"))
    }

    @Test
    fun `expandTilde leaves absolute path unchanged`() {
        assertEquals("/usr/lib/foo.jar", expandTilde("/usr/lib/foo.jar"))
    }

    @Test
    fun `expandTilde leaves relative path unchanged`() {
        assertEquals("lib/foo.jar", expandTilde("lib/foo.jar"))
    }

    @Test
    fun `expandTilde leaves empty string unchanged`() {
        assertEquals("", expandTilde(""))
    }

    // ── expandJdbcUrl ────────────────────────────────────────────────────────────

    @Test
    fun `expandJdbcUrl expands leading tilde in path portion`() {
        val home = System.getProperty("user.home")
        assertEquals("jdbc:h2:$home/test", expandJdbcUrl("jdbc:h2:~/test"))
    }

    @Test
    fun `expandJdbcUrl leaves url without tilde unchanged`() {
        assertEquals("jdbc:postgresql://localhost/db", expandJdbcUrl("jdbc:postgresql://localhost/db"))
    }

    @Test
    fun `expandJdbcUrl leaves absolute path in url unchanged`() {
        assertEquals("jdbc:h2:/abs/path/db", expandJdbcUrl("jdbc:h2:/abs/path/db"))
    }

    @Test
    fun `expandJdbcUrl does not expand tilde not followed by slash`() {
        assertEquals("jdbc:h2:~test", expandJdbcUrl("jdbc:h2:~test"))
    }

    // ── displayPath ──────────────────────────────────────────────────────────────

    @Test
    fun `displayPath abbreviates exact home`() {
        assertEquals("~", displayPath(System.getProperty("user.home")))
    }

    @Test
    fun `displayPath abbreviates home subpath`() {
        val home = System.getProperty("user.home")
        assertEquals("~/work/scripts", displayPath("$home/work/scripts"))
    }

    @Test
    fun `displayPath leaves unrelated path unchanged`() {
        assertEquals("/usr/lib/foo.jar", displayPath("/usr/lib/foo.jar"))
    }

    @Test
    fun `displayPath does not abbreviate home-prefix without slash`() {
        val home = System.getProperty("user.home")
        assertEquals("${home}extra", displayPath("${home}extra"))
    }

    // ── normalizeConfig ──────────────────────────────────────────────────────────

    @Test
    fun `normalizeConfig expands tilde in driverJar and jdbcUrl`() {
        val home = System.getProperty("user.home")
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:h2:~/testdb",
            username = "u", password = "p",
            driverClass = "org.h2.Driver",
            driverJar = "~/lib/h2.jar",
            dialect = null
        )
        val normalized = normalizeConfig(config)
        assertEquals("jdbc:h2:$home/testdb", normalized.jdbcUrl)
        assertEquals("$home/lib/h2.jar", normalized.driverJar)
    }

    @Test
    fun `normalizeConfig leaves absolute paths unchanged`() {
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://localhost/db",
            username = "u", password = "p",
            driverClass = "org.postgresql.Driver",
            driverJar = "/usr/lib/postgresql.jar",
            dialect = null
        )
        val normalized = normalizeConfig(config)
        assertEquals(config.jdbcUrl, normalized.jdbcUrl)
        assertEquals(config.driverJar, normalized.driverJar)
    }

    // ── parseConfig / loadConfig ─────────────────────────────────────────────────

    @Test
    fun `parseConfig reads valid json`(@TempDir dir: Path) {
        val file = dir.resolve("databases.json").toFile()
        file.writeText(
            """
            {
              "databases": {
                "mydb": {
                  "jdbcUrl": "jdbc:h2:mem:test",
                  "username": "sa",
                  "password": "",
                  "driverClass": "org.h2.Driver",
                  "driverJar": "/some.jar",
                  "dialect": "h2"
                }
              }
            }
            """.trimIndent()
        )
        val config = parseConfig(file)
        assertEquals(setOf("mydb"), config.databases.keys)
        assertEquals("jdbc:h2:mem:test", config.databases["mydb"]?.jdbcUrl)
    }

    @Test
    fun `parseConfig throws on invalid json`(@TempDir dir: Path) {
        val file = dir.resolve("databases.json").toFile()
        file.writeText("{ not valid json }")
        assertThrows<Exception> { parseConfig(file) }
    }

    @Test
    fun `loadConfig returns empty on invalid json`(@TempDir dir: Path) {
        val file = dir.resolve("databases.json").toFile()
        file.writeText("{ not valid json }")
        val config = loadConfig(file)
        assertTrue(config.databases.isEmpty())
    }

    @Test
    fun `loadConfig reads valid config`(@TempDir dir: Path) {
        val file = dir.resolve("databases.json").toFile()
        file.writeText(
            """
            {
              "databases": {
                "db1": {
                  "jdbcUrl": "jdbc:h2:mem:a",
                  "username": "sa",
                  "password": "",
                  "driverClass": "org.h2.Driver",
                  "driverJar": "/x.jar"
                },
                "db2": {
                  "jdbcUrl": "jdbc:mysql://localhost/b",
                  "username": "root",
                  "password": "secret",
                  "driverClass": "com.mysql.cj.jdbc.Driver",
                  "driverJar": "/y.jar"
                }
              }
            }
            """.trimIndent()
        )
        val config = loadConfig(file)
        assertEquals(setOf("db1", "db2"), config.databases.keys)
    }

    // ── drivers section ──────────────────────────────────────────────────────────

    @Test
    fun `parseConfig reads drivers section`(@TempDir dir: Path) {
        val file = writeJson(
            dir, "databases.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "~/pg.jar" }
              },
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:postgresql://localhost/db",
                  "username": "u",
                  "password": "p",
                  "driver": "postgresql"
                }
              }
            }
            """
        )

        val config = parseConfig(file)

        assertEquals(setOf("postgresql"), config.drivers.keys)
        assertEquals("org.postgresql.Driver", config.drivers.getValue("postgresql").`class`)
        assertEquals("~/pg.jar", config.drivers.getValue("postgresql").jar)
        assertEquals("postgresql", config.databases.getValue("db").driver)
    }

    @Test
    fun `inspectConfig resolves driver reference`(@TempDir dir: Path) {
        val file = writeJson(
            dir, "databases.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "~/pg.jar" }
              },
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:postgresql://localhost/db",
                  "username": "u",
                  "password": "p",
                  "driver": "postgresql"
                }
              }
            }
            """
        )

        val result = inspectConfig(listOf(file))
        val db = result.databases.getValue("db")
        val home = System.getProperty("user.home")

        assertEquals("org.postgresql.Driver", db.driverClass)
        assertEquals("$home/pg.jar", db.driverJar)
        assertEquals("postgresql", db.driverName)
        assertNull(db.driverError)
        assertEquals(file.path, result.driverSourceOf["postgresql"])
        assertEquals(listOf("db"), result.driverUsers["postgresql"])
    }

    @Test
    fun `inspectConfig resolves driver defined in another file`(@TempDir dir: Path) {
        val driversFile = writeJson(
            dir, "drivers-layer.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "/drivers/pg.jar" }
              }
            }
            """
        )
        val databasesFile = writeJson(
            dir, "databases-layer.json",
            """
            {
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:postgresql://localhost/db",
                  "username": "u",
                  "password": "p",
                  "driver": "postgresql"
                }
              }
            }
            """
        )

        val result = inspectConfig(listOf(driversFile, databasesFile))

        assertEquals("/drivers/pg.jar", result.databases.getValue("db").driverJar)
        assertEquals(driversFile.path, result.driverSourceOf["postgresql"])
        assertEquals(databasesFile.path, result.sourceOf["db"])
        assertEquals(listOf("db"), result.driverUsers["postgresql"])
    }

    @Test
    fun `inspectConfig resolves driver defined in a later file`(@TempDir dir: Path) {
        val databasesFile = writeJson(
            dir, "databases-layer.json",
            """
            {
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:postgresql://localhost/db",
                  "username": "u",
                  "password": "p",
                  "driver": "postgresql"
                }
              }
            }
            """
        )
        val driversFile = writeJson(
            dir, "drivers-layer.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "/drivers/pg.jar" }
              }
            }
            """
        )

        val result = inspectConfig(listOf(databasesFile, driversFile))

        assertEquals("/drivers/pg.jar", result.databases.getValue("db").driverJar)
        assertEquals(driversFile.path, result.driverSourceOf["postgresql"])
        assertEquals(listOf("db"), result.driverUsers["postgresql"])
    }

    @Test
    fun `inspectConfig lets later file override a referenced driver`(@TempDir dir: Path) {
        val file1 = writeJson(
            dir, "a.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "/a/pg.jar" }
              },
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:postgresql://localhost/db",
                  "username": "u",
                  "password": "p",
                  "driver": "postgresql"
                }
              }
            }
            """
        )
        val file2 = writeJson(
            dir, "b.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "/b/pg.jar" }
              }
            }
            """
        )

        val result = inspectConfig(listOf(file1, file2))

        assertEquals("/b/pg.jar", result.databases.getValue("db").driverJar)
        assertEquals(file2.path, result.driverSourceOf["postgresql"])
        assertEquals(listOf("db"), result.driverUsers["postgresql"])
    }

    @Test
    fun `inspectConfig reports unknown driver reference`(@TempDir dir: Path) {
        val file = writeJson(
            dir, "databases.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "/pg.jar" }
              },
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:h2:mem:test",
                  "username": "sa",
                  "password": "",
                  "driver": "missing-driver"
                }
              }
            }
            """
        )

        val result = inspectConfig(listOf(file))
        val db = result.databases.getValue("db")

        assertTrue(db.driverError!!.contains("missing-driver"), db.driverError)
        assertTrue(db.driverError.contains("postgresql"), db.driverError)
        assertNull(db.driverClass)
        assertNull(db.driverJar)
    }

    @Test
    fun `inspectConfig rejects driver reference combined with inline driver`(@TempDir dir: Path) {
        val file = writeJson(
            dir, "databases.json",
            """
            {
              "drivers": {
                "postgresql": { "class": "org.postgresql.Driver", "jar": "/pg.jar" }
              },
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:h2:mem:test",
                  "username": "sa",
                  "password": "",
                  "driver": "postgresql",
                  "driverClass": "org.h2.Driver",
                  "driverJar": "/h2.jar"
                }
              }
            }
            """
        )

        val result = inspectConfig(listOf(file))
        val db = result.databases.getValue("db")

        assertTrue(db.driverError!!.contains("both"), db.driverError)
    }

    @Test
    fun `inspectConfig reports database without driver configuration`(@TempDir dir: Path) {
        val file = writeJson(
            dir, "databases.json",
            """
            {
              "databases": {
                "db": {
                  "jdbcUrl": "jdbc:h2:mem:test",
                  "username": "sa",
                  "password": ""
                }
              }
            }
            """
        )

        val result = inspectConfig(listOf(file))
        val db = result.databases.getValue("db")

        assertTrue(db.driverError!!.contains("driver"), db.driverError)
    }

    // ── inspectConfig (with controlled search paths) ─────────────────────────────

    @Test
    fun `inspectConfig returns empty when no config files exist`(@TempDir dir: Path) {
        val result = inspectConfig(listOf(dir.resolve("databases.json").toFile()))
        assertTrue(result.databases.isEmpty())
        assertTrue(result.sources.isEmpty())
        assertEquals(1, result.searchedPaths.size)
    }

    @Test
    fun `inspectConfig loads single config file`(@TempDir dir: Path) {
        val file = dir.resolve("databases.json").toFile().canonicalFile
        file.writeText(
            """
            {
              "databases": {
                "mydb": {
                  "jdbcUrl": "jdbc:h2:mem:test",
                  "username": "sa",
                  "password": "",
                  "driverClass": "org.h2.Driver",
                  "driverJar": "/some.jar"
                }
              }
            }
            """.trimIndent()
        )
        val result = inspectConfig(listOf(file))
        assertEquals(1, result.sources.size)
        assertEquals(listOf("mydb"), result.sources[0].databaseNames)
        assertEquals(setOf("mydb"), result.databases.keys)
        assertEquals(file.path, result.sourceOf["mydb"])
    }

    @Test
    fun `inspectConfig merges multiple files with later overriding earlier`(@TempDir dir: Path) {
        val file1 = dir.resolve("a.json").toFile().canonicalFile
        file1.writeText(
            """
            {
              "databases": {
                "shared": { "jdbcUrl": "jdbc:h2:mem:a", "username": "sa", "password": "", "driverClass": "org.h2.Driver", "driverJar": "/a.jar" },
                "only_a": { "jdbcUrl": "jdbc:h2:mem:a2", "username": "sa", "password": "", "driverClass": "org.h2.Driver", "driverJar": "/a2.jar" }
              }
            }
            """.trimIndent()
        )
        val file2 = dir.resolve("b.json").toFile().canonicalFile
        file2.writeText(
            """
            {
              "databases": {
                "shared": { "jdbcUrl": "jdbc:h2:mem:b", "username": "sa", "password": "", "driverClass": "org.h2.Driver", "driverJar": "/b.jar" },
                "only_b": { "jdbcUrl": "jdbc:h2:mem:b2", "username": "sa", "password": "", "driverClass": "org.h2.Driver", "driverJar": "/b2.jar" }
              }
            }
            """.trimIndent()
        )
        val result = inspectConfig(listOf(file1, file2))
        assertEquals(setOf("shared", "only_a", "only_b"), result.databases.keys)
        // later file wins for "shared"
        assertEquals("jdbc:h2:mem:b", result.databases["shared"]?.jdbcUrl)
        assertEquals(file2.path, result.sourceOf["shared"])
        assertEquals(file1.path, result.sourceOf["only_a"])
        assertEquals(file2.path, result.sourceOf["only_b"])
    }

    @Test
    fun `inspectConfig records parse errors in sources`(@TempDir dir: Path) {
        val file = dir.resolve("bad.json").toFile()
        file.writeText("{ invalid json }")
        val result = inspectConfig(listOf(file))
        assertEquals(1, result.sources.size)
        assertTrue(result.sources[0].parseError != null)
        assertTrue(result.databases.isEmpty())
    }

    @Test
    fun `inspectConfig skips non-existent files`(@TempDir dir: Path) {
        val missing = dir.resolve("missing.json").toFile()
        val result = inspectConfig(listOf(missing))
        assertEquals(1, result.searchedPaths.size)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `inspectConfig applies tilde expansion to loaded configs`(@TempDir dir: Path) {
        val file = dir.resolve("databases.json").toFile()
        file.writeText(
            """
            {
              "databases": {
                "mydb": {
                  "jdbcUrl": "jdbc:h2:~/testdb",
                  "username": "sa",
                  "password": "",
                  "driverClass": "org.h2.Driver",
                  "driverJar": "~/lib/h2.jar"
                }
              }
            }
            """.trimIndent()
        )
        val result = inspectConfig(listOf(file))
        val home = System.getProperty("user.home")
        assertEquals("jdbc:h2:$home/testdb", result.databases["mydb"]?.jdbcUrl)
        assertEquals("$home/lib/h2.jar", result.databases["mydb"]?.driverJar)
    }

    @Test
    fun `inspectConfig deduplicates canonical paths`(@TempDir dir: Path) {
        val file = dir.resolve("databases.json").toFile()
        file.writeText("""{ "databases": {} }""")
        // Same file via different path representations
        val sameFile = File(dir.toFile(), "./databases.json").canonicalFile
        val result = inspectConfig(listOf(file, sameFile))
        // canonicalFile makes them the same, so distinct() keeps one
        assertEquals(1, result.searchedPaths.size)
    }

    @Test
    fun `collectMissingDriverJars groups equivalent paths for the same source`() {
        val relative = "missing-driver-${System.nanoTime()}.jar"
        val absolute = File(relative).absolutePath
        fun config(jar: String) = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
            driverClass = "org.h2.Driver",
            driverJar = jar
        )

        val result = collectMissingDriverJars(
            databases = mapOf(
                "relative" to config(relative),
                "absolute" to config(absolute)
            ),
            sourceOf = mapOf(
                "relative" to "same.json",
                "absolute" to "same.json"
            ),
            driverSourceOf = emptyMap()
        )

        val expected = MissingDriverJar(absolute, "same.json")
        assertEquals(setOf(expected), result.keys)
        assertEquals(listOf("absolute", "relative"), result.getValue(expected).sorted())
    }
}
