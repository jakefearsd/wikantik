# Wikantik MCP Core

Shared MCP server substrate used by both `/wikantik-admin-mcp` and
`/knowledge-mcp`: the `McpTool` interface, result and argument utilities,
audit logging, the endpoint bootstrapper, and the access filter. It also
hosts the two tools common to both endpoints, `QueryNodesTool` and
`SearchKnowledgeTool`.

Extracted as its own module so the read-only knowledge-mcp server does not
have to depend on the write-capable admin-mcp module.
