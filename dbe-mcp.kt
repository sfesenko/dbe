///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS tools.jackson.core:jackson-databind:3.0.3
//DEPS tools.jackson.module:jackson-module-kotlin:3.0.3
//DEPS io.modelcontextprotocol.sdk:mcp-bom:1.0.0@pom
//DEPS io.modelcontextprotocol.sdk:mcp

//SOURCES dbe-core.kt

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
import java.sql.SQLException

val mapper = jacksonObjectMapper() as JsonMapper
val prettyMapper = mapper.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build()
val mcpJsonMapper = JacksonMcpJsonMapper(mapper)

// ── Tool: list_databases ────────────────────────────────────────────────────────

val listDatabasesTool = SyncToolSpecification.builder()
    .tool(
        Tool.builder()
            .name("list_databases")
            .description("List all configured databases, including their SQL dialect when available")
            .inputSchema(mcpJsonMapper, """{ "type": "object", "properties": {} }""")
            .build()
    )
    .callHandler { _: McpSyncServerExchange, _: McpSchema.CallToolRequest ->
        try {
            val databases = loadMergedConfig()
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
            CallToolResult.builder().addTextContent("Error: ${e.message}").isError(true).build()
        }
    }
    .build()

// ── Tool: query ─────────────────────────────────────────────────────────────────

val queryTool = SyncToolSpecification.builder()
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
        try {
            val database = request.arguments()["database"] as? String
                ?: return@callHandler CallToolResult.builder()
                    .addTextContent("Error: 'database' argument is required").isError(true).build()
            val sql = request.arguments()["sql"] as? String
                ?: return@callHandler CallToolResult.builder()
                    .addTextContent("Error: 'sql' argument is required").isError(true).build()

            val databases = loadMergedConfig()
            val dbConfig = databases[database]
                ?: return@callHandler CallToolResult.builder()
                    .addTextContent("Error: Database '$database' not found. Available: ${databases.keys.sorted().joinToString(", ")}")
                    .isError(true).build()

            loadDriver(dbConfig.driverJar, dbConfig.driverClass)

            val result = connect(dbConfig).use { connection ->
                executeQuery(connection, sql)
            }

            val structured = mapOf(
                "columns" to result.columns,
                "rows" to result.rows
            )
            val text = prettyMapper.writeValueAsString(structured)
            CallToolResult.builder().addTextContent(text).build()

        } catch (e: SQLException) {
            val msg = "SQL Error: ${e.message}\nSQL State: ${e.sqlState}\nError Code: ${e.errorCode}"
            CallToolResult.builder().addTextContent(msg).isError(true).build()
        } catch (e: Exception) {
            CallToolResult.builder().addTextContent("Error: ${e.message}").isError(true).build()
        }
    }
    .build()

// ── Main ────────────────────────────────────────────────────────────────────────

fun main() {
    val transportProvider = StdioServerTransportProvider(mcpJsonMapper)

    val server: McpSyncServer = McpServer.sync(transportProvider)
        .serverInfo("dbe", "1.0.0")
        .capabilities(ServerCapabilities.builder()
            .tools(true)
            .build())
        .tools(listDatabasesTool, queryTool)
        .build()

    Runtime.getRuntime().addShutdownHook(Thread { server.close() })

    Thread.currentThread().join()
}
