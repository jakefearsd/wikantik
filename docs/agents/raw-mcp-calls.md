# Raw MCP calls from scripts

Moved from CLAUDE.md (2026-11-28).

Send `initialize`, take the `Mcp-Session-Id` response header, send `notifications/initialized`, and put
that header on every `tools/call` (without it the request falls into the servlet filters and returns a
raw 400/500). Replies are SSE: JSON-parse the `data:` line and read `result.content[0].text`. Treat a
reply as rate-limited (429) only when it has **no** `data:` line — matching "429" in the body gave a
false retry and a duplicate save. SPARQL needs `PREFIX wk: <https://wiki.wikantik.com/ns/wikantik#>`;
`SELECT` returns `sparql-results-json` (`results.bindings`).
