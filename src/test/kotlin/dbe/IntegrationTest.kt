package dbe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class IntegrationTest {

    companion object {
        private const val JDBC_URL = "jdbc:h2:mem:dbetest;DB_CLOSE_DELAY=-1"
        private const val USER = "sa"
        private const val PASSWORD = ""

        private val h2JarPath: String by lazy {
            File(org.h2.Driver::class.java.protectionDomain.codeSource.location.toURI()).path
        }

        private val dbConfig = DatabaseConfig(
            jdbcUrl = JDBC_URL,
            username = USER,
            password = PASSWORD,
            driverClass = "org.h2.Driver",
            driverJar = h2JarPath,
            dialect = "h2"
        )

        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            // Ensure the H2 driver is loaded via dbe's loadDriver mechanism
            loadDriver("test-db", dbConfig, "test")

            DriverManager.getConnection(JDBC_URL, USER, PASSWORD).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS sample (
                            id INTEGER PRIMARY KEY,
                            big_val BIGINT,
                            text_val VARCHAR(100),
                            flag BOOLEAN,
                            price DECIMAL(10, 2),
                            created DATE,
                            updated TIMESTAMP,
                            optional VARCHAR(50)
                        )
                    """.trimIndent())
                }
            }
        }
    }

    // ── loadDriver ───────────────────────────────────────────────────────────────

    @Test
    fun `loadDriver does not throw for valid jar and class`() {
        // Already loaded in setupDatabase; calling again exercises the cache path
        loadDriver("test-db", dbConfig, "test")
    }

    @Test
    fun `loadDriver throws for missing jar`() {
        val badConfig = dbConfig.copy(driverJar = "/nonexistent/path/to/driver.jar")
        val ex = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            loadDriver("missing-db", badConfig, "test-config.json")
        }
        assertTrue(ex.message!!.contains("driverJar not found"))
        assertTrue(ex.message!!.contains("missing-db"))
        assertTrue(ex.message!!.contains("test-config.json"))
    }

    // ── connect ──────────────────────────────────────────────────────────────────

    @Test
    fun `connect returns a usable connection`() {
        connect(dbConfig).use { conn ->
            assertNotNull(conn)
            assertTrue(conn.createStatement().execute("SELECT 1"))
        }
    }

    // ── executeQuery: SELECT ──────────────────────────────────────────────────────

    @Test
    fun `executeQuery returns columns and rows for SELECT`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val) VALUES (1, 'alice'), (2, 'bob')") }

            val result = executeQuery(conn, "SELECT id, text_val FROM sample ORDER BY id")
            assertEquals(listOf("ID", "TEXT_VAL"), result.columns)
            assertEquals(2, result.rows.size)
            assertEquals(1, result.rows[0][0])
            assertEquals("alice", result.rows[0][1])
            assertEquals(2, result.rows[1][0])
            assertEquals("bob", result.rows[1][1])
        }
    }

    @Test
    fun `executeQuery returns affected_rows for INSERT`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }

            val result = executeQuery(conn, "INSERT INTO sample (id, text_val) VALUES (10, 'test')")
            assertEquals(listOf("affected_rows"), result.columns)
            assertEquals(1, result.rows.size)
            assertEquals(1, result.rows[0][0])
        }
    }

    @Test
    fun `executeQuery returns affected_rows for UPDATE`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val) VALUES (1, 'old'), (2, 'old'), (3, 'old')") }

            val result = executeQuery(conn, "UPDATE sample SET text_val = 'new' WHERE id <= 2")
            assertEquals(listOf("affected_rows"), result.columns)
            assertEquals(2, result.rows[0][0])
        }
    }

    @Test
    fun `executeQuery returns affected_rows for DELETE`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val) VALUES (1, 'a'), (2, 'b')") }

            val result = executeQuery(conn, "DELETE FROM sample WHERE id = 1")
            assertEquals(listOf("affected_rows"), result.columns)
            assertEquals(1, result.rows[0][0])
        }
    }

    @Test
    fun `executeQuery handles empty result set`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }

            val result = executeQuery(conn, "SELECT id FROM sample")
            assertEquals(listOf("ID"), result.columns)
            assertTrue(result.rows.isEmpty())
        }
    }

    // ── readColumnValue ──────────────────────────────────────────────────────────

    @Test
    fun `readColumnValue handles INTEGER as Int`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id) VALUES (42)") }

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id FROM sample").use { rs ->
                    rs.next()
                    val meta = rs.metaData
                    val value = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals(42, value)
                    assertTrue(value is Int)
                }
            }
        }
    }

    @Test
    fun `readColumnValue handles BIGINT as Long`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, big_val) VALUES (1, 9000000000)") }

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT big_val FROM sample").use { rs ->
                    rs.next()
                    val meta = rs.metaData
                    val value = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals(9000000000L, value)
                    assertTrue(value is Long)
                }
            }
        }
    }

    @Test
    fun `readColumnValue handles VARCHAR as String`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val) VALUES (1, 'hello world')") }

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT text_val FROM sample").use { rs ->
                    rs.next()
                    val meta = rs.metaData
                    val value = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals("hello world", value)
                }
            }
        }
    }

    @Test
    fun `readColumnValue handles BOOLEAN`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, flag) VALUES (1, TRUE), (2, FALSE)") }

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT flag FROM sample ORDER BY id").use { rs ->
                    rs.next()
                    val meta = rs.metaData
                    val value = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals(true, value)

                    rs.next()
                    val value2 = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals(false, value2)
                }
            }
        }
    }

    @Test
    fun `readColumnValue handles DECIMAL as BigDecimal`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, price) VALUES (1, 19.99)") }

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT price FROM sample").use { rs ->
                    rs.next()
                    val meta = rs.metaData
                    val value = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals(java.math.BigDecimal("19.99"), value)
                }
            }
        }
    }

    @Test
    fun `readColumnValue handles NULL`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, optional) VALUES (1, NULL)") }

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT optional FROM sample").use { rs ->
                    rs.next()
                    val meta = rs.metaData
                    val value = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals(null, value)
                }
            }
        }
    }

    @Test
    fun `readColumnValue handles DATE as string`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, created) VALUES (1, DATE '2026-01-15')") }

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT created FROM sample").use { rs ->
                    rs.next()
                    val meta = rs.metaData
                    val value = readColumnValue(rs, 1, meta.getColumnType(1), meta.getColumnTypeName(1))
                    assertEquals("2026-01-15", value)
                }
            }
        }
    }

    // ── Full round-trip with formatters ───────────────────────────────────────────

    @Test
    fun `full round-trip query to json format`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val, flag) VALUES (1, 'json-test', TRUE)") }

            val result = executeQuery(conn, "SELECT id, text_val, flag FROM sample ORDER BY id")
            val json = formatJson(result)
            assertTrue(json.contains("\"ID\""))
            assertTrue(json.contains("\"TEXT_VAL\""))
            assertTrue(json.contains("json-test"))
        }
    }

    @Test
    fun `full round-trip query to csv format`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val) VALUES (1, 'csv,test'), (2, 'plain')") }

            val result = executeQuery(conn, "SELECT id, text_val FROM sample ORDER BY id")
            val csv = formatCsv(result)
            val lines = csv.split("\n")
            assertEquals("ID,TEXT_VAL", lines[0])
            assertEquals("1,\"csv,test\"", lines[1])
            assertEquals("2,plain", lines[2])
        }
    }

    @Test
    fun `full round-trip query to table format`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val) VALUES (1, 'table-test')") }

            val result = executeQuery(conn, "SELECT id, text_val FROM sample")
            val table = formatTable(result)
            assertTrue(table.contains("ID"))
            assertTrue(table.contains("TEXT_VAL"))
            assertTrue(table.contains("table-test"))
            assertTrue(table.contains("(1 row)"))
        }
    }

    @Test
    fun `formatOutput dispatches through full query pipeline`() {
        connect(dbConfig).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM sample") }
            conn.createStatement().use { it.execute("INSERT INTO sample (id, text_val) VALUES (1, 'dispatch')") }

            val result = executeQuery(conn, "SELECT id, text_val FROM sample")
            val csv = formatOutput(result, "csv")
            assertTrue(csv.contains("dispatch"))
        }
    }
}
