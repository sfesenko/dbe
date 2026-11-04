package dbe

import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class McpServerHandlerTest {

    @TempDir
    lateinit var tempDir: Path

    private val h2JarPath: String by lazy {
        Paths.get(org.h2.Driver::class.java.protectionDomain.codeSource.location.toURI()).toString()
    }

    private fun configFile(vararg names: String): File {
        val entries = names.joinToString(",") { name ->
            """
            "$name": {
              "jdbcUrl": "jdbc:h2:mem:mcp_$name;DB_CLOSE_DELAY=-1",
              "username": "sa",
              "password": "",
              "driverClass": "org.h2.Driver",
              "driverJar": "$h2JarPath",
              "dialect": "h2"
            }
            """.trimIndent()
        }
        return tempDir.resolve("databases.json").toFile().apply {
            writeText("""{ "databases": { $entries } }""")
        }
    }

    private fun CallToolResult.text(): String =
        content.filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text }

    // ── handleListDatabases ─────────────────────────────────────────────────────

    @Test
    fun `handleListDatabases returns configured databases`() {
        val config = configFile("alpha", "beta")

        val result = handleListDatabases(listOf(config))

        assertEquals(false, result.isError)
        assertTrue(result.text().contains("alpha"))
        assertTrue(result.text().contains("beta"))
        assertTrue(result.text().contains("h2"))
    }

    @Test
    fun `handleListDatabases reports missing configuration`() {
        val missing = tempDir.resolve("missing.json").toFile()

        val result = handleListDatabases(listOf(missing))

        assertEquals(true, result.isError)
        assertTrue(result.text().contains("No databases.json found"))
        assertTrue(result.text().contains("Searched in:"))
    }

    // ── handleQuery: error paths ─────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(
        value = [
            "NULL, SELECT 1, 'database' argument is required",
            "alpha, NULL, 'sql' argument is required",
            "missing, SELECT 1, Database 'missing' not found",
        ],
        nullValues = ["NULL"]
    )
    fun `handleQuery reports argument and lookup errors`(database: String?, sql: String?, expected: String) {
        val config = configFile("alpha")

        val result = handleQuery(database, sql, listOf(config))

        assertEquals(true, result.isError)
        assertTrue(result.text().contains(expected), result.text())
    }

    @Test
    fun `handleToolRequest tolerates a missing arguments map`() {
        val request = McpSchema.CallToolRequest("query", null)

        val result = handleToolRequest(request)

        assertEquals(true, result.isError)
        assertTrue(result.text().contains("'database' argument is required"))
    }

    @Test
    fun `handleQuery reports missing configuration`() {
        val missing = tempDir.resolve("missing.json").toFile()

        val result = handleQuery("alpha", "SELECT 1", listOf(missing))

        assertEquals(true, result.isError)
        assertTrue(result.text().contains("No databases.json found"))
    }

    @Test
    fun `handleQuery reports SQL errors`() {
        val config = configFile("alpha")

        val result = handleQuery("alpha", "SELECT * FROM does_not_exist", listOf(config))

        assertEquals(true, result.isError)
        assertTrue(result.text().contains("SQL Error:"), result.text())
    }

    // ── handleQuery: happy path ──────────────────────────────────────────────────

    @Test
    fun `handleQuery executes statements and returns rows`() {
        val config = configFile("happy")
        val paths = listOf(config)

        assertEquals(false, handleQuery("happy", "CREATE TABLE IF NOT EXISTS sample (id INT, name VARCHAR(20))", paths).isError)
        assertEquals(false, handleQuery("happy", "INSERT INTO sample VALUES (42, 'alice')", paths).isError)

        val result = handleQuery("happy", "SELECT id, name FROM sample", paths)

        assertEquals(false, result.isError)
        assertTrue(result.text().contains("\"ID\""), result.text())
        assertTrue(result.text().contains("\"NAME\""), result.text())
        assertTrue(result.text().contains("42"), result.text())
        assertTrue(result.text().contains("alice"), result.text())
    }

    @Test
    fun `handleQuery returns affected rows for DML`() {
        val config = configFile("dml")
        val paths = listOf(config)

        handleQuery("dml", "CREATE TABLE IF NOT EXISTS sample (id INT)", paths)
        val result = handleQuery("dml", "INSERT INTO sample VALUES (1), (2)", paths)

        assertEquals(false, result.isError)
        assertTrue(result.text().contains("affected_rows"), result.text())
    }

    // ── driver loading ───────────────────────────────────────────────────────────

    @Test
    fun `handleQuery reports a missing driverJar`() {
        val config = tempDir.resolve("databases.json").toFile().apply {
            writeText(
                """
                {
                  "databases": {
                    "broken": {
                      "jdbcUrl": "jdbc:h2:mem:broken",
                      "username": "sa",
                      "password": "",
                      "driverClass": "org.h2.Driver",
                      "driverJar": "/nonexistent/driver.jar"
                    }
                  }
                }
                """.trimIndent()
            )
        }

        val result = handleQuery("broken", "SELECT 1", listOf(config))

        assertEquals(true, result.isError)
        assertTrue(result.text().contains("driverJar not found"), result.text())
    }

    @Test
    fun `handleQuery executes against a configured database`() {
        val config = configFile("external")

        val result = handleQuery("external", "SELECT 1 AS answer", listOf(config))

        assertEquals(false, result.isError)
        assertTrue(result.text().contains("ANSWER"), result.text())
    }
}
