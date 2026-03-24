# Wikantik

Wikantik is a modular Java 21 wiki engine built on JEE technologies, providing a
full-featured wiki with Markdown rendering, pluggable storage providers, search,
attachments, JAAS-based security, and an MCP server for AI agent integration.

## Modules

| Module | Description |
|--------|-------------|
| wikantik-api | Core interfaces and contracts |
| wikantik-main | Main wiki implementation |
| wikantik-event | Event subsystem for decoupled communication |
| wikantik-util | Utility classes |
| wikantik-bootstrap | Startup and SPI initialization |
| wikantik-cache | EhCache-based caching layer |
| wikantik-cache-memcached | Distributed Memcached adapter |
| wikantik-http | Servlet filters and HTTP utilities |
| wikantik-markdown | Markdown syntax support via Flexmark |
| wikantik-mcp | MCP server for AI agent integration |
| wikantik-war | WAR assembly for deployment |

## Reports

Use the **Reports** menu to navigate code quality metrics, test results,
coverage, Javadoc, and dependency analysis.
