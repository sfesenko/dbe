package dbe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class MainTest {

    // ── parseFormat ──────────────────────────────────────────────────────────────

    @Test
    fun `parseFormat returns table when no format specified`() {
        assertEquals("table", parseFormat(arrayOf("mydb", "SELECT 1")))
    }

    @Test
    fun `parseFormat parses --format json`() {
        assertEquals("json", parseFormat(arrayOf("mydb", "SELECT 1", "--format", "json")))
    }

    @Test
    fun `parseFormat parses --format csv`() {
        assertEquals("csv", parseFormat(arrayOf("mydb", "SELECT 1", "--format", "csv")))
    }

    @Test
    fun `parseFormat parses --format table`() {
        assertEquals("table", parseFormat(arrayOf("mydb", "SELECT 1", "--format", "table")))
    }

    @Test
    fun `parseFormat is case insensitive for --format value`() {
        assertEquals("json", parseFormat(arrayOf("mydb", "SELECT 1", "--format", "JSON")))
        assertEquals("csv", parseFormat(arrayOf("mydb", "SELECT 1", "--format", "CSV")))
    }

    @Test
    fun `parseFormat accepts trailing positional json`() {
        assertEquals("json", parseFormat(arrayOf("mydb", "SELECT 1", "json")))
    }

    @Test
    fun `parseFormat accepts trailing positional csv`() {
        assertEquals("csv", parseFormat(arrayOf("mydb", "SELECT 1", "csv")))
    }

    @Test
    fun `parseFormat accepts trailing positional table`() {
        assertEquals("table", parseFormat(arrayOf("mydb", "SELECT 1", "table")))
    }

    @Test
    fun `parseFormat is case insensitive for positional format`() {
        assertEquals("json", parseFormat(arrayOf("mydb", "SELECT 1", "JSON")))
    }

    @Test
    fun `parseFormat prefers --format over positional`() {
        assertEquals("csv", parseFormat(arrayOf("mydb", "SELECT 1", "json", "--format", "csv")))
    }

    @Test
    fun `parseFormat throws when --format has no value`() {
        assertThrows<ArgParseException> { parseFormat(arrayOf("mydb", "SELECT 1", "--format")) }
    }

    @Test
    fun `parseFormat throws on unknown --format value`() {
        assertThrows<ArgParseException> { parseFormat(arrayOf("mydb", "SELECT 1", "--format", "xml")) }
    }

    @Test
    fun `parseFormat treats non-format positional as default table`() {
        // "xml" is not a supported format, so it's treated as a regular positional arg
        assertEquals("table", parseFormat(arrayOf("mydb", "SELECT 1", "xml")))
    }

    @Test
    fun `parseFormat handles --format before positionals`() {
        assertEquals("json", parseFormat(arrayOf("--format", "json", "mydb", "SELECT 1")))
    }

    // ── positionalArgs ───────────────────────────────────────────────────────────

    @Test
    fun `positionalArgs returns all when no --format`() {
        assertEquals(listOf("mydb", "SELECT 1"), positionalArgs(arrayOf("mydb", "SELECT 1")))
    }

    @Test
    fun `positionalArgs skips --format and its value`() {
        assertEquals(
            listOf("mydb", "SELECT 1"),
            positionalArgs(arrayOf("mydb", "SELECT 1", "--format", "json"))
        )
    }

    @Test
    fun `positionalArgs skips --format at beginning`() {
        assertEquals(
            listOf("mydb", "SELECT 1"),
            positionalArgs(arrayOf("--format", "json", "mydb", "SELECT 1"))
        )
    }

    @Test
    fun `positionalArgs handles --format in middle`() {
        assertEquals(
            listOf("mydb", "SELECT 1"),
            positionalArgs(arrayOf("mydb", "--format", "json", "SELECT 1"))
        )
    }

    @Test
    fun `positionalArgs returns empty for empty input`() {
        assertEquals(emptyList<String>(), positionalArgs(emptyArray()))
    }

    @Test
    fun `positionalArgs handles --format at end without value`() {
        // --format at end with no value: just skips --format itself
        assertEquals(listOf("mydb"), positionalArgs(arrayOf("mydb", "--format")))
    }

    // ── queryPositional ──────────────────────────────────────────────────────────

    @Test
    fun `queryPositional extracts database and query`() {
        val (db, sql) = queryPositional(arrayOf("mydb", "SELECT 1"))
        assertEquals("mydb", db)
        assertEquals("SELECT 1", sql)
    }

    @Test
    fun `queryPositional strips trailing positional format`() {
        val (db, sql) = queryPositional(arrayOf("mydb", "SELECT 1", "json"))
        assertEquals("mydb", db)
        assertEquals("SELECT 1", sql)
    }

    @Test
    fun `queryPositional strips --format flag and value`() {
        val (db, sql) = queryPositional(arrayOf("mydb", "SELECT 1", "--format", "json"))
        assertEquals("mydb", db)
        assertEquals("SELECT 1", sql)
    }

    @Test
    fun `queryPositional keeps non-format third positional`() {
        // "extra" is not a format, so it's kept as a positional arg
        // but queryPositional only returns first two, so it's ignored
        val (db, sql) = queryPositional(arrayOf("mydb", "SELECT 1", "extra"))
        assertEquals("mydb", db)
        assertEquals("SELECT 1", sql)
    }

    @Test
    fun `queryPositional throws when too few args`() {
        assertThrows<ArgParseException> { queryPositional(arrayOf("mydb")) }
    }

    @Test
    fun `queryPositional throws on empty args`() {
        assertThrows<ArgParseException> { queryPositional(emptyArray()) }
    }
}
