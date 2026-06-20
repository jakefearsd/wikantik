---
summary: Operator + agent guide for wiring Claude Code, Gemini CLI, and OpenAPI-only
  clients into Wikantik's three tool surfaces (~36 tools)
date: '2026-04-29'
cluster: wikantik-development
depends-on:
- KnowledgeGraphCore
- StructuralSpineDesign
related:
- WikantikDevelopment
- KnowledgeGraphCore
- AgentCookbook
- AgentGradeContentDesign
- HybridRetrieval
- StructuralSpineDesign
- KgInclusionPolicy
- GoodMcpDesign
- About
canonical_id: 01KQ0P44SBG2CF3EAWZF9SYJND
type: article
tags:
- development
- mcp
- ai
- agent-integration
- tools
status: deployed
hubs:
- WikantikDevelopmentHub
---
# MCP Integration

Wikantik exposes three separate agent-facing surfaces. Each is a distinct servlet path with its own auth filter, rate limiter, and tool set. Pick which surface to give an agent based on the trust you want to extend, then wire the client.

## Endpoint matrix

| Path | Module | Protocol | Tools | Capabilities | Auth filter | Default scope |
|---|---|---|---|---|---|---|
| `/wikantik-admin-mcp` | `wikantik-admin-mcp` | MCP Streamable HTTP | 26 read + write + analytics | tools, resources, prompts, completions | `McpAccessFilter` | `mcp` (or `all`) |
| `/knowledge-mcp` | `wikantik-knowledge` | MCP Streamable HTTP | 20 read-only retrieval + KG + spine + projection | tools | `KnowledgeMcpAccessFilter` | `mcp` (or `all`) |
| `/tools/*` | `wikantik-tools` | OpenAPI 3.1 | 2 (`search_wiki`, `get_page`) | OpenAPI document at `/tools/openapi.json` | `ToolsAccessFilter` | `tools` (or `all`) |

The two MCP endpoints share the same access-filter implementation and the same `wikantik-mcp.properties`, so a legacy property-file key (or a DB-backed `mcp` key) authorises both. The OpenAPI endpoint is independent: it has its own properties file and key scope.

Default to giving an agent **`/knowledge-mcp` only**. Authoring (`/wikantik-admin-mcp`) is a separate trust decision — grant it once you have decided that the agent should be allowed to write pages, mark them verified, propose KG entries, or rename/delete content. The OpenAPI surface exists for clients that cannot speak MCP at all (OpenWebUI, custom HTTP integrations, ChatGPT-style "Custom GPT" tools).

## Quickstart (5 minutes)

1. **Mint a key.** Log into the wiki as an admin, open the admin panel and go to API Keys (`/admin/apikeys`). Create a key bound to a real principal (e.g. your account or a dedicated service user) with scope `mcp`. Copy the plaintext token — it is shown once.

2. **Smoke-test the endpoint** with `curl`:

   ```bash
   # /knowledge-mcp — list tools
   curl -sS -X POST http://localhost:8080/knowledge-mcp \
     -H "Authorization: Bearer $WIKANTIK_KEY" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
   ```

   A 403 means the bearer token is wrong or the scope is insufficient. A 503 means the filter is fail-closed (no auth configured anywhere) — see *Authentication* below.

3. **Wire it into your client.** Drop the snippet for your client from *Client setup* below.

4. **Sanity-check inside the client.** Ask the agent to call `retrieve_context` with a query you know lands on a specific page; confirm the cited URLs match.

## Authentication and authorization

Every Wikantik agent surface fronts a servlet filter that checks each request before it reaches the transport servlet. The filters share a uniform model:

A request is **allowed** if it satisfies any one of:

1. A `Bearer` token that resolves to an active **DB-backed API key** with a matching scope.
2. A `Bearer` token that exactly matches one of the **legacy property-file keys** (`mcp.access.keys` / `tools.access.keys`).
3. A source IP inside one of the configured **CIDR allowlist** entries.

If none match, the response is `403 Access denied`. If *no* keys, no CIDRs, and `*.access.allowUnrestricted` is unset, the filter is **fail-closed** and returns `503 MCP not configured` (a CRITICAL line is also logged at startup). This is intentional: the rewrite preferred safe-by-default over silent open mode.

### DB-backed keys (preferred)

Managed via the admin UI at `/admin/apikeys`, backed by the `api_keys` table. Each key is bound to a Wikantik principal: when an agent presents the token, the filter wraps the request with that principal's identity. **All downstream JAAS / ACL / policy-grant checks then run against that principal**, so an agent only ever sees what its bound user can see, and writes are attributed to that user in version history and audit logs.

Available scopes (`ApiKeyService.Scope`):

| Scope | Wire string | Covers |
|---|---|---|
| `MCP` | `mcp` | `/wikantik-admin-mcp`, `/knowledge-mcp` |
| `TOOLS` | `tools` | `/tools/*` |
| `ALL` | `all` | All three |

Scope matching uses `Scope.matches()` — `ALL` covers any required scope; `MCP` does **not** cover `TOOLS` and vice versa.

Keys are stored as SHA-256 hashes; the plaintext is shown exactly once at creation. Revoke via `DELETE /admin/apikeys/{id}` or the admin UI.

REST surface (admin-only, protected by `AdminAuthFilter`):

```
GET    /admin/apikeys             list keys (hash masked, no plaintext)
POST   /admin/apikeys             generate; response carries one-time plaintext
DELETE /admin/apikeys/{id}        revoke
```

### Legacy keys

For lightweight single-user setups (or environments without a database), comma-separated bearer tokens can be set in properties:

```properties
mcp.access.keys   = generated-token-1,generated-token-2
tools.access.keys = different-token-for-openapi-clients
```

Legacy keys have no principal binding — every call lands as the wiki's default unauthenticated identity. Use only on private/local instances.

### CIDR allowlists

```properties
mcp.access.allowedCidrs   = 10.0.0.0/8,127.0.0.1/32
tools.access.allowedCidrs = 10.0.0.0/8
```

Useful when an agent runs on a sidecar pod / loopback and you want to skip the bearer token entirely. CIDR matches are evaluated against `ServletRequest.getRemoteAddr()` — make sure your reverse proxy preserves the client IP (or the CIDR list is meaningless).

### Unrestricted mode

```properties
mcp.access.allowUnrestricted   = true
tools.access.allowUnrestricted = true
```

Every request is treated as a superuser. **Only use in trusted, single-tenant local development.** When set, the filter logs a startup warning each time the webapp comes up.

### Rate limiting

Token-bucket rate limiter applied after auth. Defaults from `wikantik-mcp.properties`:

```properties
mcp.ratelimit.global    = 100   # requests/sec across all clients
mcp.ratelimit.perClient = 10    # requests/sec per resolved client identity
```

`/tools/*` has its own knobs (`tools.ratelimit.global`, `tools.ratelimit.perClient`, both default `0` = disabled). Excess requests get `429 Rate limit exceeded` with a `Retry-After: 1` header. Rate-limit hits are written to the `SecurityLog` logger.

## Configuration reference

All keys are picked up from a `wikantik-mcp.properties` (or `wikantik-tools.properties`) loaded from the classpath. Defaults ship inside the JAR; operators override by placing a file with the same name on a parent classloader (e.g. `tomcat/tomcat-11/lib/wikantik-mcp.properties`).

### `wikantik-mcp.properties` (admin MCP + knowledge MCP)

| Property | Default | Notes |
|---|---|---|
| `mcp.server.name` | `wikantik-mcp` | Reported in the MCP `initialize` handshake. |
| `mcp.server.title` | `Wikantik Knowledge Base` | Human-readable server title. |
| `mcp.server.version` | `2.0.0` | Reported alongside `Release.getVersionString()` to clients. |
| `mcp.instructions.file` | `wikantik-mcp-instructions.txt` | Classpath resource read at boot. Replaced by setting `mcp.instructions=...` inline. |
| `mcp.access.keys` | (empty) | Legacy comma-separated bearer tokens. |
| `mcp.access.key` | (empty) | Legacy single-token form (prefer `mcp.access.keys`). |
| `mcp.access.allowedCidrs` | (empty) | Comma-separated CIDR allowlist. |
| `mcp.access.allowUnrestricted` | `false` | Acknowledges the fail-closed default. |
| `mcp.ratelimit.global` | `100` | Requests/sec across all clients. |
| `mcp.ratelimit.perClient` | `10` | Requests/sec per resolved client. |

The same file feeds both `McpAccessFilter` (admin) and `KnowledgeMcpAccessFilter` (knowledge), so one configuration block governs both endpoints.

### `wikantik-tools.properties` (OpenAPI tool server)

| Property | Default | Notes |
|---|---|---|
| `tools.access.keys` | (empty) | Legacy comma-separated bearer tokens. |
| `tools.access.allowedCidrs` | (empty) | Comma-separated CIDR allowlist. |
| `tools.access.allowUnrestricted` | `false` | Acknowledges the fail-closed default. |
| `tools.ratelimit.global` | `0` | `0` disables rate limiting. |
| `tools.ratelimit.perClient` | `0` | `0` disables rate limiting. |
| `wikantik.public.baseURL` | (request scheme/host) | Used to build absolute citation URLs in tool responses. |

### Wider Wikantik properties that affect agent behaviour

| Property | Effect |
|---|---|
| `wikantik.datasource` | Enables DB-backed API keys, structural index, KG inclusion policy, retrieval-quality CI. Defaults to JNDI `jdbc/wikantikDS`. |
| `wikantik.admin.bootstrap` | Bootstrap admin override for first-time setup; bypasses ACLs at startup only. |
| `wikantik.indexnow.apiKey` | Required by `ping_search_engines` for IndexNow submissions. |
| `wikantik.structural_spine.enforcement.enabled` | Default `true`. Auto-assigns canonical IDs and rejects bad relations on save. |
| `wikantik.runbook.enforcement.enabled` | Default `true`. Schema-validates `type: runbook` pages at save time. |
| `wikantik.frontmatter.enforcement.enabled` | Default `true`. Save-time guard that rejects pages whose YAML frontmatter fails strict parsing (e.g. unquoted colon in `title:`). Disable only as a temporary escape hatch while running the audit at `GET /admin/frontmatter-issues` on an existing dirty corpus. |
| `wikantik.verification.stale_days` | Default `90`. After this many days, a verified page reports `confidence: stale`. |
| `wikantik.retrieval.cron.enabled` | Default `true`. Nightly retrieval-quality CI run. |
| `wikantik.retrieval.cron.hour_utc` | Default `3`. Hour of day (UTC) for the nightly run. |
| `wikantik.kg.policy.default` | `exclude`. Cluster-primary KG inclusion policy. Per-page override via `kg_include` frontmatter. |

## Tool inventory

Tool names below are canonical wire identifiers (snake\_case). Every tool ships with at least one worked input/output example in its schema (Phase 6 of the agent-grade content rewrite, 2026-04-25) — agents should surface `examples` to the model when constructing the first call rather than reasoning purely from JSON Schema types.

### `/wikantik-admin-mcp` (26 tools)

**Read-only / analytics** — no author resolution required, registered with `readOnly=true` annotation:

| Tool | Purpose |
|---|---|
| `read_page` | Fetch raw Markdown body + parsed frontmatter for a page. |
| `get_page_history` | Version log: numbers, authors, dates, change notes. |
| `diff_page` | Unified diff between two versions of a page. |
| `get_backlinks` | Pages that link *to* a given page. |
| `get_outbound_links` | Pages a given page links *to*. |
| `get_broken_links` | Wiki-wide enumeration of references to nonexistent pages. |
| `get_orphaned_pages` | Pages with no inbound links (system pages excluded). |
| `get_wiki_stats` | Dashboard summary: total pages, broken/orphaned counts, recent changes. |
| `verify_pages` | Compound check across many pages — existence, link health, metadata completeness, optional SEO readiness. |
| `preview_structured_data` | What a page's frontmatter will produce in HTML — meta tags, OG/Twitter, JSON-LD, BreadcrumbList, Atom, News Sitemap. |
| `ping_search_engines` | Submit URLs/sitemap to Google Ping + IndexNow after a publish. |
| `list_proposals` | List pending knowledge-graph proposals (only when KG is configured). |

**Write / author-configurable** — implement `AuthorConfigurable`; the MCP exchange's `clientInfo.name` is injected as the default author before each call:

| Tool | Purpose |
|---|---|
| `write_pages` | Create or replace one or more pages. Author defaults from the client identity. |
| `update_page` | Patch a page with `expectedContentHash` for optimistic locking. |
| `rename_page` | Move a page (and optionally rewrite incoming links) with `confirm=true`. |
| `delete_pages` | Permanent delete with `confirm=true`. System pages refused. |
| `mark_page_verified` | Stamp `verified_at`/`verified_by`/`confidence` frontmatter. |
| `propose_knowledge` | Propose a new node or edge for the knowledge graph (KG-conditional). |

The admin endpoint also serves resources, prompts, and completions (see *Capabilities*).

### `/knowledge-mcp` (20 tools)

**Knowledge-graph traversal & introspection** (registered when `KnowledgeGraphService` is configured):

| Tool | Purpose |
|---|---|
| `discover_schema` | Enumerate node types, edge types, properties, mention coverage. |
| `query_nodes` | Filter / search graph nodes by type and properties. |
| `get_node` | One node + its declared edges with provenance. |
| `traverse` | Bounded BFS from a starting node with depth and provenance filters. |
| `search_knowledge` | Full-text search over node names + property values. |
| `find_similar` | Embedding-nearest-neighbour over node mentions (only when `NodeMentionSimilarity` is configured). |

**Hybrid retrieval / RAG** (registered when `ContextRetrievalService` is configured):

| Tool | Purpose |
|---|---|
| `retrieve_context` | **Primary RAG entry point.** Hybrid BM25 + dense (RRF-fused) with fail-closed BM25 fallback. Returns chunked results with citation URLs and confidence. (Graph-aware rerank is wired but off by default; production runs BM25+dense only.) |
| `get_page` | Pinned fetch of a specific page once you know its name. |
| `list_pages` | Browse-style page enumeration, optionally prefix-filtered. |
| `list_metadata_values` | Distinct frontmatter keys + their values across the corpus — discovery before targeted filtering. |

**Structural-spine navigation** (registered when `StructuralIndexService` is configured) — fastest path for *structural* questions because they hit the index, not full-text search:

| Tool | Purpose |
|---|---|
| `list_clusters` | Enumerate clusters with hub pages and member counts. |
| `list_tags` | Enumerate tags with frequency. |
| `list_pages_by_filter` | Pages matching `cluster=`, `tag=`, `type=`, etc. |
| `get_page_by_id` | Resolve a page by its stable `canonical_id` (ULID). |
| `traverse_relations` | Walk typed relations declared in frontmatter (`relations:` block) — declared graph, not derived. |

**Agent-grade projection** (registered when `ForAgentProjectionService` is configured):

| Tool | Purpose |
|---|---|
| `get_page_for_agent` | Token-budgeted projection of a page: summary, key facts, headings outline, typed relations, recent changes, MCP tool hints, verification state. **Default-of-choice for "read this page"** — falls back gracefully via `degraded` flag and `missing_fields` rather than failing the whole request. Memoised in `wikantik.forAgentCache` (1h TTL). |

### `/tools/*` (2 tools)

| Tool | Path | Purpose |
|---|---|---|
| `search_wiki` | `GET /tools/search_wiki?q=...&max_results=...` | OpenAPI shape over `ContextRetrievalService` (same hybrid retrieval as `retrieve_context`). Response shape includes citation URLs and ranked snippets. |
| `get_page` | `GET /tools/get_page?name=...` | Single page fetch with frontmatter parsed into a structured field. |

OpenAPI 3.1 document live at `GET /tools/openapi.json`. Every operation includes worked `example` payloads — sufficient for ChatGPT Custom GPT tools, OpenWebUI tool servers, or any other client that consumes a static OpenAPI document.

## Capabilities on `/wikantik-admin-mcp`

Beyond tools, the admin server advertises the standard MCP capability surface:

### Resources (6)

Direct read-only data access without invoking a tool. Resource subscriptions are enabled (`resources(true, true)`); the server publishes `notifications/resources/updated` whenever a page save or delete crosses the `WikiEventManager` (wired by `WikiEventSubscriptionBridge`).

```
wiki://pages                          — list every page
wiki://recent-changes                 — recent modifications, newest first
wiki://pages/{pageName}               — read a page (template)
wiki://pages/{pageName}/version/{v}   — read a specific version (template)
wiki://pages/{pageName}/attachments   — list attachments (template)
wiki://pages/{pageName}/backlinks     — backlinks for a page (template)
```

### Prompts (8)

Guided workflows the agent can invoke verbatim. Each prompt declares typed arguments (some prompts accept page names with autocompletion).

| Name | Purpose |
|---|---|
| `create-article` | Structured article creation with metadata. |
| `summarize-topic` | Cross-page research and synthesis on a topic. |
| `audit-links` | Link-integrity check around a starting page. |
| `rename-page` | Safe rename workflow with backlinks update. |
| `wiki-health-check` | End-to-end wiki health report. |
| `publish-cluster` | Hub + sub-articles cluster publish, with verify pass. |
| `extend-cluster` | Add a new article into an existing cluster, including cross-references. |
| `seo-audit` | Run `verify_pages` with `seo_readiness` and consume `preview_structured_data`. |

### Completions (3)

Page-name autocompletion served against `ReferenceManager.findCreated()`:

```
audit-links / pageName
rename-page / oldName
rename-page / newName
```

### Server-side instructions

The MCP `initialize` response carries an instructions text loaded from `wikantik-mcp-instructions.txt` (classpath). The instructions cover page format (Markdown + YAML frontmatter), CamelCase naming, link syntax, and tool usage. Three safeguards keep the text in sync with the live registry:

1. **Build-time (unit):** `InstructionsRegistryDriftTest` (under `wikantik-admin-mcp/src/test/`) loads the resource at test time, asks `McpToolRegistry` what it actually registers, and asserts every registered tool is mentioned by name and every tool-shaped mention is registered. The unit-test build fails on drift, so a stale instructions file cannot reach packaging unnoticed.
2. **Build-time (wire):** `McpInstructionsDriftIT` (under `wikantik-it-tests/wikantik-selenide-tests`) does the same check against a deployed server: it pulls the `instructions` text from the SDK's `getServerInstructions()` and the live tool list from `tools/list`, then asserts both directions of the diff. This is the safety net for operator-supplied `mcp.instructions.file` overrides — the unit test only sees the file shipped in the WAR; the IT sees whatever the deployed server actually returns.
3. **Runtime:** `McpServerInitializer.logToolNameDriftIfAny` re-runs the same check on every server start and warns at `WARN` level if the loaded file (bundled or override) has drifted from the registered tool set.

The instructions were rewritten end-to-end on 2026-04-30 to match the current 26-tool surface (`read_page`, `write_pages`, `update_page`, `delete_pages`, `rename_page`, `mark_page_verified`, `verify_pages`, `preview_structured_data`, `ping_search_engines`, `get_backlinks`, `get_outbound_links`, `get_broken_links`, `get_orphaned_pages`, `get_wiki_stats`, `get_page_history`, `diff_page`, plus `list_proposals` / `propose_knowledge` when the knowledge service is wired, plus additional analytics and curation tools added since). The legacy locking section (`lock_page` / `unlock_page`) is gone — concurrency is now optimistic via `expectedContentHash` on `update_page`.

## Page model agents need to understand

Every wiki page is Markdown with optional YAML frontmatter:

```markdown
---
canonical_id: 01KQ0...      # ULID auto-assigned by the structural-spine filter
type: article               # article | hub | runbook | reference | report | ...
cluster: wikantik-development
tags: [mcp, integration]
related: [About, KnowledgeGraphCore]
depends-on: [KnowledgeGraphCore]
summary: Short, used for meta description / og:description / Atom <summary>
date: 2026-04-29
verified_at: 2026-04-29T14:00:00Z
verified_by: jakefear
confidence: authoritative   # authoritative | provisional | stale (usually computed)
audience: [humans, agents]
kg_include: true            # cluster-policy override
---
# Page Title

Body in Markdown. Internal links use [text](PageName).
```

Save-time enforcement is on: pages without `canonical_id` get one auto-assigned and injected; pages with invalid `relations:` are rejected; `type: runbook` pages are schema-validated; cluster KG-inclusion policy is applied. Most of the agent surface area assumes these invariants — read `[StructuralSpineDesign](StructuralSpineDesign)` and `[AgentGradeContentDesign](AgentGradeContentDesign)` for the full model.

### Frontmatter validation and normalization

YAML quoting trips up agents: a value like `title: Woodworking Joinery: Structural Mechanics` parses as a nested mapping start because of the unquoted colon, and the page used to save with empty metadata + a `WARN` log. Two layers now close that hole:

1. **MCP-side normalization (Layer 1).** `WritePagesTool` and `UpdatePageTool` route every save through `FrontmatterNormalizer`: any embedded `---` block is parsed strictly, merged with the explicit `metadata` argument (explicit wins on conflict), and re-emitted via `FrontmatterWriter` (SnakeYAML's emitter, which always quotes correctly). If the embedded YAML is malformed, the tool returns a structured error with the message + 1-based line/column + a quoting hint instead of silently dropping metadata.
2. **Save-time validation (Layer 2).** `FrontmatterValidationPageFilter` runs at `preSave` priority `-1006` (before chunking, defaults, structural spine, runbook validation). Rejects any save whose `---` block fails `FrontmatterParser.parseStrict`. Catches non-MCP paths (REST, JSP editor). Gated by `wikantik.frontmatter.enforcement.enabled` (default `true`).

`FrontmatterParser.parse(...)` keeps its graceful-degrade behaviour for read-side callers — the strict path is opt-in via `parseStrict(...)` and only used on writes. Operators adopting the validator on an existing wiki should run `GET /admin/frontmatter-issues` first to find broken pages, fix them, then leave the flag at its default `true`.

`GET /admin/frontmatter-issues` returns `{data: {issues: [{pageName, error, line?, column?}], issue_count, scanned, error_count}}`. Scan cost is O(N) page reads on the request thread — it's a migration tool, not a polling target.

## Why this rewrite is worth the effort to wire up

The point of the rebuild was *agents reading agents-grade content*, not humans browsing. Three load-bearing capabilities:

1. **`get_page_for_agent` is the right default for "read this page"**, not `read_page` or `get_page`. It returns a token-budgeted projection — summary, key facts, headings outline, typed relations, recent changes, MCP tool hints, verification state — without the full markdown body. Per-extractor try/catch: failure of one section surfaces as a `degraded` flag plus `missing_fields`, not a total error. Memoised by `(canonical_id, updated_at_millis)` so repeat reads in a session are essentially free.

2. **Verification metadata is part of the wire format.** `verified_at`, `verified_by`, `confidence`, and `audience` flow through the `/for-agent` projection and the structural index. Authors stamp pages via `mark_page_verified`; operators triage at `GET /admin/verification?confidence=stale`. Agents can — and should — refuse to act on pages where `confidence: stale`.

3. **Runbooks are a first-class type.** A page with `type: runbook` carries a structured `runbook:` block (`when_to_use`, `inputs`, `steps`, `pitfalls`, `related_tools`, `references`), validated at save time and re-validated on read. Tell agents: when planning a multi-step task, search for runbooks first (e.g. `list_pages_by_filter` with `type=runbook`, or `retrieve_context` filtered by type). The seed corpus lives at `[AgentCookbook](AgentCookbook)`.

A fourth, quieter improvement: every tool now ships with worked input/output **examples** in its schema (top-level for OpenAPI ops; `outputSchema.examples` plus per-property `examples` on MCP tools). First-call success rates rise materially when the agent sees concrete payloads instead of inferring from types alone.

## Client setup

### Claude Code (`.mcp.json` in the repo, or `~/.claude/mcp_servers.json` global)

```json
{
  "mcpServers": {
    "wikantik-knowledge": {
      "type": "http",
      "url": "http://localhost:8080/knowledge-mcp",
      "headers": { "Authorization": "Bearer YOUR_KEY" }
    },
    "wikantik-admin": {
      "type": "http",
      "url": "http://localhost:8080/wikantik-admin-mcp",
      "headers": { "Authorization": "Bearer YOUR_AUTHORING_KEY" }
    }
  }
}
```

Claude Code surfaces tool examples to the model. Add the admin server only on agents you trust to write.

### Claude Desktop (`claude_desktop_config.json`)

Same shape under `mcpServers`. Claude Desktop currently only supports the `stdio` transport directly, so for a remote HTTP MCP endpoint use `mcp-remote` as a wrapper:

```json
{
  "mcpServers": {
    "wikantik-knowledge": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "https://wiki.example.com/knowledge-mcp",
        "--header", "Authorization: Bearer YOUR_KEY"
      ]
    }
  }
}
```

### Gemini CLI (`~/.gemini/settings.json`)

```json
{
  "mcpServers": {
    "wikantik-knowledge": {
      "httpUrl": "http://localhost:8080/knowledge-mcp",
      "headers": { "Authorization": "Bearer YOUR_KEY" }
    }
  }
}
```

Gemini CLI loads MCP server metadata at session start and activates tools on demand.

### OpenWebUI / non-MCP HTTP clients

Point at the OpenAPI tool server:

```
URL:           http://localhost:8080/tools/openapi.json
Auth header:   Authorization: Bearer YOUR_TOOLS_KEY
```

OpenWebUI (and similar) consume the OpenAPI document directly. Two operations are exposed: `search_wiki` and `get_page`.

### Plain HTTP / scripted use

```bash
# Hybrid retrieval over /knowledge-mcp
curl -sS -X POST http://localhost:8080/knowledge-mcp \
  -H "Authorization: Bearer $WIKANTIK_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"retrieve_context",
                 "arguments":{"query":"how do agents read pages","maxResults":5}}}'

# OpenAPI tool server
curl -sS "http://localhost:8080/tools/search_wiki?q=hybrid+retrieval&max_results=5" \
  -H "Authorization: Bearer $TOOLS_KEY"
```

## Recommended agent system prompt

Drop a fragment like this into the agent's project / system prompt so it does not blindly full-text-search:

> You have access to a Wikantik MCP server. Use it like this:
>
> - **Read a page:** prefer `get_page_for_agent` over `read_page` / `get_page`. It returns a token-budgeted projection with summary, key facts, headings outline, typed relations, and verification state. Treat any field listed in `missing_fields` as unavailable rather than empty. Treat `confidence: stale` as untrusted information.
> - **Search content:** prefer `retrieve_context` over `search_knowledge` for question-answering — it is the hybrid BM25 + dense (RRF-fused) path. Use citation URLs from the response when reporting findings.
> - **Browse structure:** for cluster / tag / type questions use the structural-spine tools (`list_clusters`, `list_tags`, `list_pages_by_filter`, `traverse_relations`, `get_page_by_id`). Do not full-text-search structural questions; the spine is faster and exact.
> - **Plan multi-step work:** before doing complex authoring or maintenance, search for `type: runbook` pages on the topic (`list_pages_by_filter` with `type=runbook` or `retrieve_context` with the right query) and follow the `steps:` block.
> - **Discover the schema:** call `discover_schema` once per session before constructing custom KG queries; call `list_metadata_values` before custom frontmatter filters.
> - **Edit pages (if authoring is enabled):** use `update_page` with `expectedContentHash` for optimistic locking. Always provide a `changeNote`. Use `mark_page_verified` to stamp verification after a substantive review. Never call `delete_pages` or `rename_page` without `confirm=true`.

## Workflow recipes

### RAG / question answering

1. `retrieve_context` with the user's query.
2. For each top-ranked hit, fetch with `get_page_for_agent` (cheap thanks to the cache).
3. Cite with the citation URLs from the retrieval response, not by hand-crafting URLs.

### Authoring an article cluster

1. `discover_schema` + `list_clusters` to confirm cluster naming.
2. Use the `publish-cluster` MCP prompt on `/wikantik-admin-mcp` to generate hub + members.
3. `write_pages` (one round trip).
4. `verify_pages` with `checks=["seo_readiness", ...]` to confirm cross-references and metadata.
5. `preview_structured_data` on the hub to confirm JSON-LD / breadcrumb shape.
6. `mark_page_verified` on each page once the cluster is reviewed.
7. `ping_search_engines` (requires `wikantik.indexnow.apiKey`).

### Link / health audit

1. `get_wiki_stats` for the dashboard.
2. `get_broken_links` and `get_orphaned_pages` for the worklist.
3. Use the `audit-links` prompt on a starting page to walk the local link graph.
4. After fixes, `verify_pages` to confirm.

### Knowledge-graph traversal from a known page

1. `get_page_for_agent` to get the page's `canonical_id`.
2. `traverse_relations` with that id to walk the *declared* graph.
3. Use `traverse` (KG, not spine) only when the declared graph is insufficient — it walks the derived co-mention graph and is more permissive.

### Retrieval-quality investigation

1. `GET /admin/retrieval-quality?limit=20` to read recent runs.
2. `POST /admin/retrieval-quality/run` with `{"query_set_id": "core-agent-queries", "mode": "HYBRID_GRAPH"}` to trigger an ad-hoc run.
3. Diff the per-mode nDCG@5/@10, Recall@20, MRR. Smoke gate is `nDCG@5 >= 0.5`.

## Observability

Prometheus metrics relevant to integrations:

- `wikantik_for_agent_response_bytes` — histogram of `/for-agent` response sizes.
- `wikantik_retrieval_ndcg_at_5` / `_at_10` / `_recall_at_20` / `_mrr` — gauges keyed by `{set, mode}`.
- `wikantik_tools_*` — request counters, latencies, and rate-limit / auth rejections for `/tools/*` (registered via `ToolsMetricsBridge`).

Log streams worth tailing:

- `SecurityLog` — every auth failure, every rate-limit rejection on every endpoint.
- Log lines tagged `MCP` and `Knowledge MCP` cover startup, tool registration, drift warnings, and shutdown.

## Safety checklist before pointing an agent at production

1. Mint a DB-backed key bound to a real principal — never share legacy property-file keys across agents.
2. Use `scope=mcp` for read-only research agents. Reserve `scope=all` for human-supervised authoring, never automation.
3. Set `mcp.access.allowedCidrs` to the intended source range; do not rely on bearer tokens alone for agents on shared networks.
4. Confirm `mcp.access.allowUnrestricted` is unset (or `false`) in production. The fail-closed default is what you want.
5. Confirm rate limits are enforced (`mcp.ratelimit.global > 0`).
6. Confirm `wikantik.runbook.enforcement.enabled=true` (default) so authoring agents cannot land malformed runbooks.
7. Confirm `wikantik.structural_spine.enforcement.enabled=true` (default) so authoring agents cannot land bad relations.
8. Decide your KG inclusion policy — see `[KgInclusionPolicy](KgInclusionPolicy)`.
9. Make sure your reverse proxy passes through:
   - The `Authorization` header.
   - The `Accept: text/event-stream` header (the Streamable HTTP transport requires SSE).
   - The client IP (so CIDR allowlists work).
10. If you supply your own `mcp.instructions.file` override, mirror the live tool registry in it. The bundled file is regression-tested by `InstructionsRegistryDriftTest`; an external override is not.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `503 MCP not configured` | Filter is fail-closed — no API keys, no CIDRs, `allowUnrestricted` not set. Set one. |
| `403 Access denied` | Bearer token unknown / revoked, or scope insufficient (`tools` key against `/wikantik-admin-mcp` will 403). |
| `403 Key not authorized for MCP` | A DB-backed key with `scope=tools` was presented at an MCP endpoint. Mint a key with `scope=mcp` or `scope=all`. |
| `429 Rate limit exceeded` | Rate-limit knobs too tight, or runaway agent. Inspect `SecurityLog`. |
| MCP tools list empty on `/knowledge-mcp` | None of `KnowledgeGraphService`, `ContextRetrievalService`, `StructuralIndexService`, `ForAgentProjectionService` are configured — check `wikantik.datasource` and the embeddings setup. |
| `get_page_for_agent` returns `degraded: true` | One of the four extractors failed; the named field appears in `missing_fields`. Check log for the underlying exception. |
| Startup log warns about tool-name drift | An external `mcp.instructions.file` override disagrees with the live registry. The bundled instructions are regression-tested at build time; only an operator-supplied override can drift in a live deployment. Refresh the override or remove it. |
| Authoring tool says `expectedContentHash` mismatch | Optimistic-lock guard fired. Re-fetch the page, recompute the hash, retry. |
| `WikiEngine could not be created — MCP server not started` | The bootstrap servlet listener ran before SPIs were ready. Confirm `WikiBootstrapServletContextListener` is wired with a lower `load-on-startup` value than the MCP listeners (admin = 2, knowledge = 3, tools = 2). |

## Pointers

- `[GoodMcpDesign](GoodMcpDesign)` — the principles the tool surface was built against.
- `[AgentGradeContentDesign](AgentGradeContentDesign)` — the design that drove the rewrite (verification metadata, `/for-agent`, runbook type, retrieval-quality CI, examples).
- `[AgentCookbook](AgentCookbook)` — runbook-style how-tos that seed the retrieval-quality CI.
- `[StructuralSpineDesign](StructuralSpineDesign)` — clusters, tags, canonical IDs, typed relations, generated `Main.md`.
- `[HybridRetrieval](HybridRetrieval)` — what `retrieve_context` and `search_wiki` do under the hood.
- `[KgInclusionPolicy](KgInclusionPolicy)` — cluster-primary policy + page-level override.
- `[FindingTheRightMcpTool](FindingTheRightMcpTool)` — agent-facing chooser.
- `[McpAuditTools](McpAuditTools)` — the analytics subset of the admin MCP tools, with examples.

[{Relationships}]
