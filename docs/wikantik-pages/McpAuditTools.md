---
audience:
- humans
part-of:
- McpIntegration
status: active
title: MCP Audit Tools
type: article
canonical_id: 01KQ0P44SB1JGDBZKMCYXWCNA0
kg_include: true
date: '2026-03-20'
enables:
- WikiAuditSkill
tags:
- development
- mcp
- audit
- tools
related:
- WikantikDevelopment
summary: Status of the designed but never built MCP content-audit tools, and the checks
  that audit the MCP surface itself.
cluster: wikantik-development
---
# MCP Audit Tools

The MCP audit tools are a designed but not yet built set of MCP tools that would power the [wiki audit skill](WikiAuditSkill). These tools would provide structured access to content quality metrics and validation results.

## Design Status

A design spec exists at `docs/superpowers/specs/2026-03-20-mcp-audit-tools-design.md`. The tools would be added to the existing authoring MCP server at `/mcp`.

## Planned Tools

- **scan_broken_links** — Find pages with links to non-existent pages
- **scan_missing_frontmatter** — Find pages missing required metadata fields
- **scan_orphaned_pages** — Find pages with no inbound references
- **validate_frontmatter** — Check frontmatter against schema conventions
- **audit_cluster** — Comprehensive quality report for a page cluster

> **Status (2026-10-13):** None of these five tools was built, and the design spec was removed from the repository tree in the docs archive pass (it remains in git history). The authoring server is now `/wikantik-admin-mcp`, and its tool list is the exact set pinned by `McpProtocolIT.EXPECTED_TOOLS`, which contains no `scan_*`, `validate_frontmatter` or `audit_cluster`. The same jobs are done with existing tools:
>
> | Planned tool | Use today |
> |---|---|
> | `scan_broken_links` | `get_broken_links` (wiki-wide), or the `broken_links` check of `verify_pages` |
> | `scan_missing_frontmatter` | the `metadata_completeness` check of `verify_pages` (opt-in `seo_readiness` and `retrieval_readiness` add more) |
> | `scan_orphaned_pages` | `get_orphaned_pages` |
> | `validate_frontmatter` | `POST /api/frontmatter/validate`; the schema is also enforced at save time, and `GET /admin/frontmatter-issues` scans the whole corpus |
> | `audit_cluster` | `list_pages_by_filter` for the cluster's members, then `verify_pages` over them; the `wiki-health-check` MCP prompt walks a whole-wiki version |
>
> Demand-side audits use `list_retrieval_queries` and `list_content_opportunities`. The `wiki-audit` skill was written against compound tools (`get_cluster_map`, `audit_cluster`, `audit_cross_cluster`, `apply_audit_fixes`) that were retired in the 2026-06-20 tool reconciliation, so until it is rewritten it drives these live tools directly.

## Auditing the MCP surface itself

A separate question is how the MCP endpoints (`/wikantik-admin-mcp` and `/knowledge-mcp`) are checked for drift and misuse. Agents fail quietly when the tool surface misdescribes itself: a tool that is registered but never explained, an instructions text that still names a removed tool, or a schema that marks an optional argument as required. For adding a tool without tripping these checks see [Writing a New MCP Tool](WritingANewMcpTool); for the surface itself see [MCP Integration](McpIntegration).

### What is checked, and where

| Audit dimension | Mechanism | What it catches |
|---|---|---|
| Tool registration (admin) | `McpProtocolIT.listToolsReturnsAllTools` compares `tools/list` with the exact `EXPECTED_TOOLS` set (29 tools) | A tool added, dropped or renamed without updating the test |
| Schema shape (admin) | `McpProtocolIT.listToolsContainsCorrectSchemas`: every tool has a description and an `inputSchema` of type `object`; required parameters are pinned for `get_backlinks`, `write_pages` and `update_page` (where `content` must stay optional) | An argument accidentally made required or left undescribed |
| Registry unit checks (admin) | `McpToolRegistryTest`: tool names are unique, every tool has a definition, at least 16 tools register | Duplicate or half-built tools |
| Instructions vs registry, build time | `InstructionsRegistryDriftTest` (`wikantik-admin-mcp/src/test`): every registered tool is mentioned by name in `wikantik-mcp-instructions.txt`, and every tool-shaped token in it is registered | Instructions teaching agents to call tools that do not exist |
| Instructions vs registry, wire | `McpInstructionsDriftIT`: the same two-way diff against the `instructions` text and `tools/list` of a deployed server | An operator-supplied `mcp.instructions.file` that has drifted |
| Instructions vs registry, boot | `McpServerInitializer.logToolNameDriftIfAny` logs a WARN at startup | Drift in a live deployment |
| Knowledge MCP gating | `KnowledgeMcpToolAssemblyTest` (KG tools drop out when the Knowledge Graph service is absent, the rest stay) and `ToolDescriptionRoutingTest` (routing guidance such as the `coverage` and `sparql_query` hints stays in the descriptions) | Wiring regressions and silent description drift |
| Knowledge MCP over the wire | `KnowledgeMcpToolsIT` in `wikantik-it-tests/wikantik-it-test-rest` | A tool that registers but fails against a live server |
| System-page protection | `McpSystemPageProtectionIT` | A write tool that stops refusing system pages |
| Concurrency guard | `update_page` requires `expectedContentHash`; on drift it returns `{updated:false, error:"hash mismatch", currentHash, currentVersion, latestContent}` | Lost updates (optimistic locking, no write-ahead log) |

The IT module runs with `mcp.access.allowUnrestricted = true`, so these tests do not exercise bearer-key authentication; key and scope behaviour is covered at the access-filter level and by the `/tools/*` integration tests.

### Runtime audit trail

- **Every tool call** logs one INFO line from the endpoint's initializer: `MCP tools/call name=<tool> client=<clientInfo.name> surface=read|write durationMs=<n> isError=<bool>` on the admin endpoint (`threw=<exception class>` replaces `isError` if the tool throws), and `Knowledge MCP tools/call name=... client=... durationMs=... isError=...` on the knowledge endpoint.
- **State-changing admin tools** also write to the `SecurityLog` logger: `MCP_WRITE tool=<tool> action=<created|updated|renamed|refused-system-page|...> target=<page> author=<author>`; bulk KG operations log `action=bulk attempted= succeeded= failed=`. The author is the MCP client's `clientInfo.name` unless the call passes an explicit one. MCP tools run with the access filter as their only gate, so this log is the forensic record.
- **Access-filter outcomes** (denials and rate-limit hits) are logged to `SecurityLog` and returned as:

| Status | Body | Cause |
|---|---|---|
| 403 | `{"error":"Access denied"}` | No key, unknown or revoked key, and the caller IP is not in `mcp.access.allowedCidrs` |
| 403 | `{"error":"Key not authorized for MCP"}` | A valid key whose scope does not cover the endpoint (for example a `tools` key, or an `mcp_read` key on the admin endpoint) |
| 429 | `{"error":"Rate limit exceeded"}` plus `Retry-After: 1` | `mcp.ratelimit.global` / `mcp.ratelimit.perClient` exceeded |
| 503 | `{"error":"mcp_access_unconfigured", ...}` plus `Retry-After: 86400` | No DB-backed keys, no CIDR allowlist and `mcp.access.allowUnrestricted` is not set |

Authentication failures are 403, not 401.

### Metrics and health probes

- `RequestMetricsFilter` (see `web.xml`) times HTTP requests to `/wikantik-admin-mcp`, `/knowledge-mcp` and `/tools/*`; they appear on the Prometheus endpoint `/metrics`.
- Domain meters relevant to agents include `wikantik_for_agent_response_bytes`, `wikantik_read_pages_partial_failures_total{reason}` (from `read_pages`) and `wikantik_tools_*` (the OpenAPI server).
- Liveness is `GET /api/health`. There is no MCP-specific probe; to check an MCP endpoint end to end, open a session (`initialize`, `notifications/initialized`) and call `tools/list`, then compare the count with the fully wired figures in [MCP Integration](McpIntegration).
- `bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik` checks the health, metrics and structured-log contract that the observability plane expects.

[{Relationships}]
