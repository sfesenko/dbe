package dbe

import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

// ── Output Formatters ───────────────────────────────────────────────────────────

private val prettyJsonMapper: JsonMapper = (jacksonObjectMapper() as JsonMapper)
    .rebuild()
    .enable(SerializationFeature.INDENT_OUTPUT)
    .build()

fun formatJson(result: QueryResult): String {
    val rows = result.rows.map { row ->
        result.columns.zip(row).toMap()
    }
    return prettyJsonMapper.writeValueAsString(rows)
}

fun formatCsv(result: QueryResult): String {
    val sb = StringBuilder()
    sb.appendLine(result.columns.joinToString(",") { escapeCsvField(it) })
    for (row in result.rows) {
        sb.appendLine(row.joinToString(",") { escapeCsvField(it?.toString() ?: "") })
    }
    // Remove only the final line terminator; trimEnd() would also strip
    // legitimate trailing whitespace from the last field.
    return sb.toString().removeSuffix("\n").removeSuffix("\r")
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
    val bold  = if (isTTY) "\u001b[1m" else ""
    val dim   = if (isTTY) "\u001b[2m" else ""
    val reset = if (isTTY) "\u001b[0m" else ""

    val nullStr = if (isTTY) "${dim}∅${reset}" else "∅"
    // Must stay in sync with the display width of the null marker above.
    val nullDisplayLen = 1

    if (result.rows.isEmpty()) {
        val header = result.columns.joinToString(" │ ") { "$bold$it$reset" }
        return "$header\n${dim}(0 rows)${reset}"
    }

    val numericColumns = result.columns.indices.map { i ->
        result.rows.all { row ->
            val v = row.getOrNull(i)
            v == null || v is Number
        }
    }

    val colWidths = result.columns.mapIndexed { i, col ->
        val maxDataWidth = result.rows.maxOfOrNull { row ->
            val v = row.getOrNull(i)
            if (v == null) nullDisplayLen else v.toString().length
        } ?: 0
        maxOf(col.length, maxDataWidth)
    }

    val maxColWidth = 60
    val cappedWidths = colWidths.map { minOf(it, maxColWidth) }

    val sb = StringBuilder()

    sb.appendLine(cappedWidths.joinToString("─┬─", prefix = "┌─", postfix = "─┐") { "─".repeat(it) })

    sb.appendLine(result.columns.mapIndexed { i, col ->
        "$bold${col.padEnd(cappedWidths[i])}$reset"
    }.joinToString(" │ ", prefix = "│ ", postfix = " │"))

    sb.appendLine(cappedWidths.joinToString("─┼─", prefix = "├─", postfix = "─┤") { "─".repeat(it) })

    for (row in result.rows) {
        sb.appendLine(row.mapIndexed { i, value ->
            val w = cappedWidths[i]
            if (value == null) {
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

    sb.appendLine(cappedWidths.joinToString("─┴─", prefix = "└─", postfix = "─┘") { "─".repeat(it) })

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
