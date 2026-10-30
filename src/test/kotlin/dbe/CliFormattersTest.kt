package dbe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CliFormattersTest {

    private fun result(columns: List<String>, vararg rows: List<Any?>) =
        QueryResult(columns, rows.toList())

    // ── escapeCsvField ───────────────────────────────────────────────────────────

    @Test
    fun `escapeCsvField leaves simple field unchanged`() {
        assertEquals("hello", escapeCsvField("hello"))
    }

    @Test
    fun `escapeCsvField quotes field with comma`() {
        assertEquals("\"a,b\"", escapeCsvField("a,b"))
    }

    @Test
    fun `escapeCsvField quotes field with double quote and escapes it`() {
        assertEquals("\"say \"\"hi\"\"\"", escapeCsvField("say \"hi\""))
    }

    @Test
    fun `escapeCsvField quotes field with newline`() {
        assertEquals("\"line1\nline2\"", escapeCsvField("line1\nline2"))
    }

    @Test
    fun `escapeCsvField quotes field with comma and quote`() {
        // input: a,"b → replace " with "" → a,""b → wrap → "a,""b"
        assertEquals("\"a,\"\"b\"", escapeCsvField("a,\"b"))
    }

    @Test
    fun `escapeCsvField leaves empty string unchanged`() {
        assertEquals("", escapeCsvField(""))
    }

    // ── formatJson ──────────────────────────────────────────────────────────────

    @Test
    fun `formatJson produces list of row objects`() {
        val r = result(listOf("id", "name"), listOf(1, "alice"), listOf(2, "bob"))
        val json = formatJson(r)
        assertTrue(json.contains("\"id\""))
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("alice"))
        assertTrue(json.contains("bob"))
    }

    @Test
    fun `formatJson handles empty rows`() {
        val r = result(listOf("id", "name"))
        val json = formatJson(r)
        // Pretty-printed empty array is "[ ]"
        assertEquals("[ ]", json.trim())
    }

    @Test
    fun `formatJson includes null values`() {
        val r = result(listOf("id", "name"), listOf(1, null))
        val json = formatJson(r)
        assertTrue(json.contains("null"))
    }

    @Test
    fun `formatJson zips columns with row values`() {
        val r = result(listOf("a", "b"), listOf("x", "y"))
        val json = formatJson(r)
        assertTrue(json.contains("\"a\" : \"x\""))
        assertTrue(json.contains("\"b\" : \"y\""))
    }

    // ── formatCsv ───────────────────────────────────────────────────────────────

    @Test
    fun `formatCsv outputs header and rows`() {
        val r = result(listOf("id", "name"), listOf(1, "alice"), listOf(2, "bob"))
        val csv = formatCsv(r)
        val lines = csv.split("\n")
        assertEquals("id,name", lines[0])
        assertEquals("1,alice", lines[1])
        assertEquals("2,bob", lines[2])
    }

    @Test
    fun `formatCsv outputs only header for empty rows`() {
        val r = result(listOf("id", "name"))
        val csv = formatCsv(r)
        assertEquals("id,name", csv.trim())
    }

    @Test
    fun `formatCsv escapes commas in values`() {
        val r = result(listOf("name"), listOf("a,b"))
        val csv = formatCsv(r)
        assertTrue(csv.contains("\"a,b\""))
    }

    @Test
    fun `formatCsv escapes quotes in values`() {
        val r = result(listOf("name"), listOf("say \"hi\""))
        val csv = formatCsv(r)
        assertTrue(csv.contains("\"say \"\"hi\"\"\""))
    }

    @Test
    fun `formatCsv preserves trailing spaces in last field`() {
        val r = result(listOf("name"), listOf("hello "))
        val csv = formatCsv(r)
        assertEquals("name\nhello ", csv)
    }

    @Test
    fun `formatCsv represents null as empty string`() {
        val r = result(listOf("id", "name"), listOf(1, null))
        val csv = formatCsv(r)
        val lines = csv.split("\n")
        assertEquals("1,", lines[1])
    }

    // ── formatTable ──────────────────────────────────────────────────────────────

    @Test
    fun `formatTable shows no columns message when columns empty`() {
        val r = QueryResult(emptyList(), emptyList())
        assertEquals("(no columns)", formatTable(r))
    }

    @Test
    fun `formatTable shows header and zero rows message`() {
        val r = result(listOf("id", "name"))
        val table = formatTable(r)
        assertTrue(table.contains("id"))
        assertTrue(table.contains("name"))
        assertTrue(table.contains("(0 rows)"))
    }

    @Test
    fun `formatTable shows row count`() {
        val r = result(listOf("id"), listOf(1), listOf(2), listOf(3))
        val table = formatTable(r)
        assertTrue(table.contains("(3 rows)"))
    }

    @Test
    fun `formatTable shows singular row count`() {
        val r = result(listOf("id"), listOf(1))
        val table = formatTable(r)
        assertTrue(table.contains("(1 row)"))
    }

    @Test
    fun `formatTable displays null as null symbol`() {
        val r = result(listOf("id", "name"), listOf(1, null))
        val table = formatTable(r)
        assertTrue(table.contains("∅"))
    }

    @Test
    fun `formatTable does not emit ANSI codes when not a TTY`() {
        // System.console() is null during tests → no ANSI
        val r = result(listOf("id"), listOf(1))
        val table = formatTable(r)
        assertFalse(table.contains("\u001b["))
    }

    @Test
    fun `formatTable draws box borders`() {
        val r = result(listOf("id", "name"), listOf(1, "alice"))
        val table = formatTable(r)
        assertTrue(table.contains("┌"))
        assertTrue(table.contains("┐"))
        assertTrue(table.contains("├"))
        assertTrue(table.contains("┤"))
        assertTrue(table.contains("└"))
        assertTrue(table.contains("┘"))
    }

    // ── formatOutput ────────────────────────────────────────────────────────────

    @Test
    fun `formatOutput dispatches to json`() {
        val r = result(listOf("id"), listOf(1))
        val out = formatOutput(r, "json")
        assertTrue(out.contains("\"id\""))
    }

    @Test
    fun `formatOutput dispatches to csv`() {
        val r = result(listOf("id"), listOf(1))
        val out = formatOutput(r, "csv")
        assertTrue(out.contains("id"))
        assertTrue(out.contains("1"))
    }

    @Test
    fun `formatOutput dispatches to table`() {
        val r = result(listOf("id"), listOf(1))
        val out = formatOutput(r, "table")
        assertTrue(out.contains("┌"))
    }

    @Test
    fun `formatOutput is case insensitive`() {
        val r = result(listOf("id"), listOf(1))
        val jsonUpper = formatOutput(r, "JSON")
        val jsonLower = formatOutput(r, "json")
        assertEquals(jsonLower, jsonUpper)
    }

    @Test
    fun `formatOutput throws on unknown format`() {
        val r = result(listOf("id"), listOf(1))
        assertThrows<IllegalStateException> { formatOutput(r, "xml") }
    }
}
