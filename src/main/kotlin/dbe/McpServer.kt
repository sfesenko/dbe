package dbe

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.modelcontextprotocol.spec.McpSchema.Tool
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.io.InputStream
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private val mapper = jacksonObjectMapper() as JsonMapper
private val prettyMapper: JsonMapper = mapper.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build()
private val mcpJsonMapper = JacksonMcpJsonMapper(mapper)

private fun errorResult(message: String): CallToolResult =
    CallToolResult.builder().addTextContent(message).isError(true).build()

// ── Tool: list_databases ────────────────────────────────────────────────────────

internal fun handleListDatabases(searchPaths: List<File> = configSearchPaths()): CallToolResult {
    return try {
        val diagnostic = inspectConfig(searchPaths)
        if (diagnostic.sources.isEmpty()) {
            return errorResult(
                "Error: No databases.json found.\nSearched in:\n" +
                    diagnostic.searchedPaths.joinToString("\n") { "  - $it" }
            )
        }
        val databases = diagnostic.databases
        val names = databases.keys.sorted()
        val details = names.associateWith { name ->
            mapOf(
                "dialect" to databases.getValue(name).resolvedDialect()
            )
        }
        val text = prettyMapper.writeValueAsString(
            mapOf(
                "databases" to names,
                "databaseDetails" to details
            )
        )
        CallToolResult.builder().addTextContent(text).build()
    } catch (e: Exception) {
        errorResult("Error: ${e.message}")
    }
}

private val listDatabasesTool = SyncToolSpecification.builder()
    .tool(
        Tool.builder()
            .name("list_databases")
            .description("List all configured databases, including their SQL dialect when available")
            .inputSchema(mcpJsonMapper, """{ "type": "object", "properties": {} }""")
            .build()
    )
    .callHandler { _: McpSyncServerExchange, _: McpSchema.CallToolRequest ->
        handleListDatabases()
    }
    .build()

// ── Tool: query ─────────────────────────────────────────────────────────────────

internal fun handleQuery(
    database: String?,
    sql: String?,
    searchPaths: List<File> = configSearchPaths()
): CallToolResult {
    val databaseName = database
        ?: return errorResult("Error: 'database' argument is required")
    val statement = sql
        ?: return errorResult("Error: 'sql' argument is required")

    return try {
        val diagnostic = inspectConfig(searchPaths)
        if (diagnostic.sources.isEmpty()) {
            return errorResult(
                "Error: No databases.json found.\nSearched in:\n" +
                    diagnostic.searchedPaths.joinToString("\n") { "  - $it" }
            )
        }
        val databases = diagnostic.databases
        val dbConfig = databases[databaseName]
            ?: return errorResult(
                "Error: Database '$databaseName' not found. Available: ${databases.keys.sorted().joinToString(", ")}"
            )

        loadDriver(databaseName, dbConfig, diagnostic.sourceOf[databaseName])

        val result = connect(dbConfig).use { connection ->
            executeQuery(connection, statement)
        }

        val structured = mapOf(
            "columns" to result.columns,
            "rows" to result.rows
        )
        val text = prettyMapper.writeValueAsString(structured)
        CallToolResult.builder().addTextContent(text).build()

    } catch (e: SQLException) {
        val msg = "SQL Error: ${e.message}\nSQL State: ${e.sqlState}\nError Code: ${e.errorCode}"
        errorResult(msg)
    } catch (e: Exception) {
        errorResult("Error: ${e.message}")
    }
}

private val queryTool = SyncToolSpecification.builder()
    .tool(
        Tool.builder()
            .name("query")
            .description("Execute a SQL query against a configured database. Call list_databases first to discover the SQL dialect when needed.")
            .inputSchema(mcpJsonMapper, """
                {
                  "type": "object",
                  "properties": {
                    "database": {
                      "type": "string",
                      "description": "Name of the database (as defined in databases.json)"
                    },
                    "sql": {
                      "type": "string",
                      "description": "SQL query to execute"
                    }
                  },
                  "required": ["database", "sql"]
                }
            """.trimIndent())
            .build()
    )
    .callHandler { _: McpSyncServerExchange, request: McpSchema.CallToolRequest ->
        val args = request.arguments() ?: emptyMap()
        handleQuery(args["database"] as? String, args["sql"] as? String)
    }
    .build()

// ── Entry Point ─────────────────────────────────────────────────────────────────

internal class EofAwareInputStream(
    private val delegate: InputStream,
    private val onEof: () -> Unit
) : InputStream() {
    private val notified = AtomicBoolean(false)

    private fun eof(): Int {
        if (notified.compareAndSet(false, true)) {
            onEof()
        }
        return -1
    }

    override fun read(): Int {
        val value = delegate.read()
        return if (value < 0) eof() else value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException()
        }
        val count = delegate.read(buffer, offset, length)
        return if (count < 0) eof() else count
    }

    override fun close() {
        delegate.close()
    }
}

fun runMcpServer() {
    val disconnected = CountDownLatch(1)
    val input = EofAwareInputStream(System.`in`) {
        disconnected.countDown()
    }
    val transportProvider = StdioServerTransportProvider(mcpJsonMapper, input, System.out)

    val server: McpSyncServer = McpServer.sync(transportProvider)
        .serverInfo("dbe", APP_VERSION)
        .capabilities(
            ServerCapabilities.builder()
                .tools(true)
                .build()
        )
        .tools(listDatabasesTool, queryTool)
        .build()

    val config = inspectConfig()
    System.err.println("dbe MCP configuration files:")
    config.sources.forEach { source ->
        System.err.println("  ${source.path}")
    }
    if (config.sources.isEmpty()) {
        System.err.println("  (none found; searched ${config.searchedPaths.size} paths)")
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        disconnected.countDown()
    })

    disconnected.await()
    server.close()
}
