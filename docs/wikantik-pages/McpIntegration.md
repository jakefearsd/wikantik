---
title: MCP Integration
cluster: wikantik-development
date: '2026-04-29'
type: article
status: active
depends-on:
- KnowledgeGraphCore
- StructuralSpineDesign
canonical_id: 01KQ0P44SBG2CF3EAWZF9SYJND
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
summary: Operator + agent guide for wiring Claude Code, Gemini CLI, and OpenAPI-only
  clients into Wikantik's three tool surfaces (52 tools when fully wired).
tags:
- development
- mcp
- ai
- agent-integration
- tools
---
# MCP Integration

Wikantik exposes three separate agent-facing surfaces. Each is a distinct servlet path with its own auth filter, rate limiter, and tool set. Pick which surface to give an agent based on the trust you want to extend, then wire the client.

## Endpoint matrix

| Path | Module | Protocol | Tools | Capabilities | Auth filter | Default scope |
|---|---|---|---|---|---|---|
| `/wikantik-admin-mcp` | `wikantik-admin-mcp` | MCP Streamable HTTP | 29 read + write + analytics | tools, resources, prompts, completions | `McpAccessFilter` | `mcp_admin` (or `all`) |
| `/knowledge-mcp` | `wikantik-knowledge` | MCP Streamable HTTP | 21 read-only retrieval + KG + spine + projection | tools | `McpAccessFilter` (registered as `KnowledgeMcpAccessFilter`, required scope `mcp_read`) | `mcp_read` (or `mcp_admin`, `all`) |
| `/tools/*` | `wikantik-tools` | OpenAPI 3.1 | 2 (`search_wiki`, `get_page`) | OpenAPI document at `/tools/openapi.json` | `ToolsAccessFilter` | `tools` (or `all`) |

That is 52 tools in all when everything is wired. The counts are maxima; `docs/admin/McpAgents.md` in the repository is the one document that owns them.

Both MCP endpoints register some tools **conditionally** on what a deployment has wired, so the counts above are the fully-wired maximum and a leaner install legitimately advertises fewer.

- `/knowledge-mcp` is not mounted at all unless the Knowledge Graph service, the context retrieval service or the structural index exists. Within it, `find_similar`, `get_page_for_agent`, `assemble_bundle`, `get_briefing`, `get_ontology`/`sparql_query` and `list_stale_citations` each need their own service.
- `/wikantik-admin-mcp` always registers 19 tools. Nine more need the Knowledge Graph service (`list_proposals`, `inspect_proposals`, `propose_knowledge`, `query_nodes`, `search_knowledge`, `list_orphaned_kg_nodes`, and, when the curation service is also present, `review_proposals`, `curate_edges`, `curate_nodes`) and `list_retrieval_queries` needs a datasource-backed query-log reader. `rename_cluster`, `list_content_opportunities` and `snooze_opportunity` are registered unconditionally and refuse at call time when the structural index or the content-intelligence subsystem is missing, so those three never vanish from `tools/list`.

The two MCP endpoints share the same access-filter class and the same `wikantik-mcp.properties` (CIDR allowlist, unrestricted flag, rate limits), and a DB-backed `mcp_admin` key authorises both. The OpenAPI endpoint is independent: it has its own properties file and key scope.

Default to giving an agent **`/knowledge-mcp` only**. Authoring (`/wikantik-admin-mcp`) is a separate trust decision — grant it once you have decided that the agent should be allowed to write pages, mark them verified, propose KG entries, or rename/delete content. The OpenAPI surface exists for clients that cannot speak MCP at all (OpenWebUI, custom HTTP integrations, ChatGPT-style "Custom GPT" tools).

## Quickstart (5 minutes)

1. **Mint a key.** Log into the wiki as an admin, open the admin panel and go to API Keys (`/admin/apikeys`). Create a key bound to a real principal (e.g. your account or a dedicated service user) with scope `mcp_admin` (or `mcp_read` if the agent only needs `/knowledge-mcp`). Copy the plaintext token — it is shown once.

2. **Smoke-test the endpoint** with `curl`. MCP over Streamable HTTP needs a session: send `initialize`, read the `Mcp-Session-Id` response header, send `notifications/initialized`, and carry that header on every later call (without it the request falls through to the HTTP filter chain and you get a raw 400/500):

   ```bash
   KEY=wkk_your_key_here        # the token shown once at creation
   H=(-H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream')

   curl -s -D headers.txt -X POST http://localhost:8080/knowledge-mcp "${H[@]}" -d '{
     "jsonrpc":"2.0","id":1,"method":"initialize",
     "params":{"protocolVersion":"2025-06-18","capabilities":{},
               "clientInfo":{"name":"curl","version":"0"}}}'
   SID=$(grep -i '^mcp-session-id' headers.txt | awk '{print $2}' | tr -d '\r')
   curl -s -X POST http://localhost:8080/knowledge-mcp "${H[@]}" -H "Mcp-Session-Id: $SID" \
     -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
   # list tools
   curl -s -X POST http://localhost:8080/knowledge-mcp "${H[@]}" -H "Mcp-Session-Id: $SID" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
   ```

   Responses may arrive as `text/event-stream`: take the line that starts with `data:` and parse the JSON after it. A 403 means the bearer token is wrong, revoked or the scope is insufficient. A 503 means the filter is fail-closed (no auth configured anywhere) — see *Authentication* below.

3. **Wire it into your client.** Drop the snippet for your client from *Client setup* below.

4. **Sanity-check inside the client.** Ask the agent to call `assemble_bundle` with a query you know lands on a specific page; confirm the cited URLs match.

## Authentication and authorization

Every Wikantik agent surface fronts a servlet filter that checks each request before it reaches the transport servlet. The filters share a uniform model:

A request is **allowed** if it satisfies either of:

1. A `Bearer` token that resolves to an active **DB-backed API key** whose scope covers the endpoint.
2. A source IP inside one of the configured **CIDR allowlist** entries.

If neither matches, the response is `403 {"error":"Access denied"}`; a valid key with the wrong scope gets `403 {"error":"Key not authorized for MCP"}`. If there are no DB-backed keys (no datasource), no CIDRs, and `*.access.allowUnrestricted` is unset, the filter is **fail-closed** and returns `503` with `mcp_access_unconfigured` and `Retry-After: 86400` (a CRITICAL line is also logged at startup). This is intentional: safe-by-default over silent open mode. Authentication failures are 403, never 401.

### DB-backed keys (preferred)

Managed via the admin UI at `/admin/apikeys`, backed by the `api_keys` table. Each key is bound to a Wikantik principal, but on the MCP endpoints that binding is for attribution and the audit trail only. **Authorisation is the scope check in the access filter.** The admin MCP tools perform no per-user permission checks: any `mcp_admin` or `all` key can read and write every non-system page and curate the Knowledge Graph, so treat those keys as admin credentials. The knowledge endpoint is the opposite: the MCP transport carries no caller identity, so its read tools enforce page view ACLs as an **anonymous guest** and return only publicly viewable pages whichever principal owns the key. Page versions written through the admin endpoint are attributed to the MCP client's `clientInfo.name`, and every write is logged to `SecurityLog` (see [MCP Audit Tools](McpAuditTools)).

Available scopes (`ApiKeyService.Scope`):

| Scope | Wire string | Covers |
|---|---|---|
| `MCP_READ` | `mcp_read` | `/knowledge-mcp` only (read-only) |
| `MCP_ADMIN` | `mcp_admin` | `/wikantik-admin-mcp`, `/knowledge-mcp` (named `mcp` before 2.4.54; `mcp` is still accepted as an alias, and every use of a key stored as `mcp` logs a WARN) |
| `TOOLS` | `tools` | `/tools/*` |
| `ALL` | `all` | All three |

Scope matching uses `Scope.matches()` — `ALL` covers any required scope; within the MCP family a higher rank covers a lower one (`mcp_read` ⊂ `mcp_admin`), so an `mcp_read` key cannot reach `/wikantik-admin-mcp`; `TOOLS` matches only itself, so an MCP key does **not** cover `/tools/*` and vice versa. Non-admins can self-mint only `tools` and `mcp_read` keys (`/api/self/apikeys`).

Keys are stored as SHA-256 hashes; the plaintext is shown exactly once at creation. Revoke via `DELETE /admin/apikeys/{id}` or the admin UI.

REST surface (admin-only, protected by `AdminAuthFilter`):

```
GET    /admin/apikeys             list keys (hash masked, no plaintext)
POST   /admin/apikeys             generate; response carries one-time plaintext
DELETE /admin/apikeys/{id}        revoke
```

### Legacy property-file keys (removed)

The comma-separated `mcp.access.keys` / `mcp.access.key` / `tools.access.keys` properties are no longer read (removed in 2.0.21). Setting them does nothing; mint DB-backed keys instead. Only the CIDR allowlist and the unrestricted flag remain as property-driven access.

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

Every request is treated as a superuser. **Only use in trusted, single-tenant local development.** It is ignored if a CIDR allowlist is also configured, and the filter logs a startup warning each time the webapp comes up.

### Rate limiting

A sliding one-second-window limiter runs after auth, keyed by the resolved client identity (the API-key id, or the source IP). The values in the shipped `wikantik-mcp.properties` are small, and the deployment templates override them:

| Where | `mcp.ratelimit.global` | `mcp.ratelimit.perClient` |
|---|---|---|
| Shipped default (`wikantik-mcp.properties`) | 100 | 10 |
| Bare-metal template (`deploy-local.sh`) | 300 | 100 |
| Container (`MCP_RATE_LIMIT_GLOBAL` / `MCP_RATE_LIMIT_PER_CLIENT`) | 500 | 50 |

Both are requests per second, and `0` disables a limit. `/tools/*` has its own knobs (`tools.ratelimit.global`, `tools.ratelimit.perClient`, shipped defaults 100 and 10; `<= 0` disables). Excess requests get `429 {"error":"Rate limit exceeded"}` with `Retry-After: 1`, and each rejection is written to the `SecurityLog` logger. This limiter is separate from the per-IP `RateLimitFilter` on `/api/*`, `/sparql`, `/id/*` and `/export/*`, which does not cover the MCP or tool endpoints.

## Configuration reference

All keys are picked up from a `wikantik-mcp.properties` (or `wikantik-tools.properties`) loaded from the classpath. Defaults ship inside the JAR; operators override by placing a file with the same name on a parent classloader (e.g. `tomcat/tomcat-11/lib/wikantik-mcp.properties`). `bin/deploy-local.sh` renders the bare-metal one from `wikantik-war/src/main/config/tomcat/wikantik-mcp.properties.template`.

### `wikantik-mcp.properties` (admin MCP + knowledge MCP)

| Property | Default | Notes |
|---|---|---|
| `mcp.server.name` | `wikantik-mcp` | Reported in the admin server's MCP `initialize` handshake. |
| `mcp.server.title` | `Wikantik Knowledge Base` | Human-readable title of the admin server. |
| `mcp.server.version` | `2.0.0` | MCP surface version reported by the admin server; decoupled from the product release. (The knowledge server reports `wikantik-knowledge` with the product version.) |
| `mcp.instructions.file` | (blank) | Blank serves the bundled `wikantik-mcp-instructions.txt`. Set it to an **absolute** filesystem path to serve an operator-edited copy; a relative or unreadable path falls back to the bundled one. Admin server only. |
| `mcp.access.allowedCidrs` | (empty) | Comma-separated CIDR allowlist. |
| `mcp.access.allowUnrestricted` | `false` | Acknowledges the fail-closed default. |
| `mcp.ratelimit.global` | `100` | Requests/sec across all clients (`0` disables). The deployment templates override it, see *Rate limiting*. |
| `mcp.ratelimit.perClient` | `10` | Requests/sec per resolved client (`0` disables). |
| `wikantik.mcp.rate_limit.max_clients` | `10000` | Distinct clients the per-client limiter tracks. |
| `wikantik.mcp.kg_curation.bulk_limit` | `50` | Most proposals or curation operations one bulk call (`inspect_proposals`, `review_proposals`, `curate_edges`, `curate_nodes`) may carry. |

The same file feeds the `McpAccessFilter` instance on each endpoint, so one configuration block governs both.

### `wikantik-tools.properties` (OpenAPI tool server)

| Property | Default | Notes |
|---|---|---|
| `tools.access.allowedCidrs` | (empty) | Comma-separated CIDR allowlist. |
| `tools.access.allowUnrestricted` | `false` | Acknowledges the fail-closed default. |
| `tools.ratelimit.global` | `100` | Requests/sec across all `/tools/*` callers; `<= 0` disables. |
| `tools.ratelimit.perClient` | `10` | Requests/sec per client; `<= 0` disables. |
| `wikantik.public.baseURL` | (request scheme/host) | Used to build absolute citation URLs in tool responses. |

### Wider Wikantik properties that affect agent behaviour

| Property | Effect |
|---|---|
| `wikantik.datasource` | JNDI name of the PostgreSQL DataSource (default `jdbc/WikiDatabase`). Required: DB-backed API keys, the structural index, the KG inclusion policy and retrieval-quality CI all live there. |
| `wikantik.admin.bootstrap` | Bootstrap admin override for first-time setup; bypasses ACLs at startup only. |
| `wikantik.baseURL` | Absolute public origin; `ping_search_engines` and `preview_structured_data` need it to build sitemap and structured-data URLs. |
| `wikantik.indexnow.apiKey` | Required by `ping_search_engines` for IndexNow submissions. |
| `wikantik.systemPages.mcpEditable` | Default `About`. System pages that stay editable through `update_page`; every other system page refuses MCP writes, and delete/rename are refused for all of them. |
| `wikantik.structural_spine.enforcement.enabled` | Default `true`. Auto-assigns canonical IDs on save. |
| `wikantik.cluster_declaration.enforcement.enabled` | Default **`false`**. When enabled, a duplicate cluster declaration is a 422 at save. Ships dark: enabling it against a corpus that already contains duplicates makes the offending hub pages un-saveable — check `/admin/drift` first. |
| `wikantik.runbook.enforcement.enabled` | Default `true`. Schema-validates `type: runbook` pages at save time. |
| `wikantik.frontmatter.enforcement.enabled` | Default `true`. Save-time guard that rejects pages whose YAML frontmatter fails strict parsing (e.g. unquoted colon in `title:`). Disable only as a temporary escape hatch while running the audit at `GET /admin/frontmatter-issues` on an existing dirty corpus. |
| `wikantik.verification.stale_days` | Default `90`. After this many days, a verified page reports `confidence: stale`. |
| `wikantik.retrieval.cron.enabled` | Default `true`. Nightly retrieval-quality CI run. |
| `wikantik.retrieval.cron.hour_utc` | Default `3`. Hour of day (UTC) for the nightly run. |
| `wikantik.kg_policy.enabled` | Default `true`. Master switch for the cluster-primary KG inclusion policy. The per-cluster include/exclude rows are DB-backed (managed at `/admin/kg-policy/*`); a cluster with no row is excluded by default. A page-level `kg_include` frontmatter value overrides the cluster. See [KgInclusionPolicy](KgInclusionPolicy). |

## Tool inventory

Tool names below are canonical wire identifiers (snake\_case). Every tool ships with at least one worked input/output example in its schema (Phase 6 of the agent-grade content rewrite, 2026-04-25) — agents should surface `examples` to the model when constructing the first call rather than reasoning purely from JSON Schema types.

### `/wikantik-admin-mcp` (29 tools when fully wired)

**Read-only / analytics** — no author resolution required, annotated `readOnlyHint`:

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
| `verify_pages` | Compound check across many pages — existence, link health, metadata completeness, optional SEO and retrieval readiness. |
| `preview_structured_data` | What a page's frontmatter will produce in HTML — meta tags, OG/Twitter, JSON-LD, BreadcrumbList, Atom, News Sitemap. |
| `ping_search_engines` | Submit URLs/sitemap to IndexNow after a publish. |
| `list_proposals` | List pending knowledge-graph proposals (only when KG is configured). |
| `inspect_proposals` | Inspect specific proposals in detail before review. |
| `query_nodes` | KG node query with **admin bypass**, so curators see freshly-created entities. |
| `search_knowledge` | KG full-text search with the same admin bypass. |
| `list_orphaned_kg_nodes` | Degree-0 KG entities, for orphan triage at scale. |
| `list_retrieval_queries` | Real query-log traffic across all four agent surfaces. |
| `list_content_opportunities` | The ranked content-opportunity backlog from the Content Intelligence subsystem, with the evidence behind each item. Read its `suppressed` list as well as `opportunities`. |

**Write tools** — all but `delete_pages` implement `AuthorConfigurable`, so the MCP exchange's `clientInfo.name` is injected as the default author before each call:

| Tool | Purpose |
|---|---|
| `write_pages` | Create one or more pages (fails any that already exist). Author defaults from the client identity. |
| `update_page` | Patch a page with `expectedContentHash` for optimistic locking. Frontmatter merges; `removeKeys` retires a field. |
| `rename_page` | Move a page (and optionally rewrite incoming links) with `confirm=true`. |
| `rename_cluster` | Rewrite a cluster path across every member, sub-clusters included. **An unconfirmed call returns the plan — that is success, not an error.** Refuses a target another hub already declares. |
| `delete_pages` | Permanent delete with `confirm=true`. System pages refused. |
| `mark_page_verified` | Stamp `verified_at`/`verified_by`/`confidence` frontmatter. |
| `propose_knowledge` | Propose a new node or edge for the knowledge graph (KG-conditional). |
| `review_proposals` | Approve / reject pending KG proposals. |
| `curate_nodes` | Direct KG node curation (create/update/delete). |
| `curate_edges` | Direct KG edge curation; refuses an edge that violates a SHACL shape. |
| `snooze_opportunity` | Decline a content opportunity for 1–365 days; a reason is required. |

The admin endpoint also serves resources, prompts, and completions (see *Capabilities*).

### `/knowledge-mcp` (21 tools when fully wired)

**Knowledge-graph traversal & introspection** (registered when `KnowledgeGraphService` is configured):

| Tool | Purpose |
|---|---|
| `discover_schema` | Enumerate node types, edge types, properties, mention coverage. |
| `query_nodes` | Filter / search graph nodes by type and properties. |
| `get_node` | One node + its declared edges with provenance. |
| `traverse` | Bounded BFS from a starting node with depth and provenance filters. |
| `search_knowledge` | Full-text search over node names + property values. |
| `find_similar` | Embedding-nearest-neighbour over node mentions (only when `NodeMentionSimilarity` is configured). |

**Hybrid retrieval / RAG** (registered when `ContextRetrievalService` is configured; `assemble_bundle` and `get_briefing` additionally need the bundle and briefing services):

| Tool | Purpose |
|---|---|
| `assemble_bundle` | **Primary answer-grounding tool.** Returns ranked, de-duplicated, version-pinned, citation-bearing section text as a context bundle — never a synthesized answer. Carries a `coverage` block and a `mode=hybrid\|dense\|lexical` toggle. |
| `retrieve_context` | Page/section **discovery**. Hybrid BM25 + dense (RRF-fused) with fail-closed BM25 fallback. (Graph-aware rerank was removed in 2026-07; production runs BM25+dense only.) |
| `get_briefing` | Session-start context briefing: budgeted, de-duplicated, injection-ready markdown assembled from pins, clusters, and the task prompt. |
| `get_page` | Pinned fetch of one page's frontmatter metadata and URL once you know its name (read the body with `read_pages`). |
| `read_pages` | Batched raw-markdown read, up to 20 pages in one call. |
| `list_pages` | Browse-style page enumeration, optionally prefix-filtered. |
| `list_metadata_values` | Distinct frontmatter keys + their values across the corpus — discovery before targeted filtering. |
| `list_stale_citations` | Citations whose grounding span has drifted since it was pinned. |

**Ontology** (registered when the ontology manager is wired):

| Tool | Purpose |
|---|---|
| `get_ontology` | The formal `wikantik:` T-Box. |
| `sparql_query` | Read-only SPARQL over the ontology. |

**Structural-spine navigation** (registered when `StructuralIndexService` is configured) — fastest path for *structural* questions because they hit the index, not full-text search:

| Tool | Purpose |
|---|---|
| `list_clusters` | Enumerate clusters with hub pages and member counts. |
| `list_tags` | Enumerate tags with frequency. |
| `list_pages_by_filter` | Pages matching `cluster=`, `tag=`, `type=`, etc. |
| `get_page_by_id` | Resolve a page by its stable `canonical_id` (ULID). |

> **Removed 2026-05-02:** `traverse_relations` no longer exists. It walked the typed `relations:` frontmatter block, which was deleted with typed relations. For page-to-page traversal use `get_backlinks` / `get_outbound_links` on `/wikantik-admin-mcp` (real wikilink edges), or `traverse` on this server for the derived KG co-mention graph.

**Agent-grade projection** (registered when `ForAgentProjectionService` is configured):

| Tool | Purpose |
|---|---|
| `get_page_for_agent` | Token-budgeted projection of a page: summary, key facts, headings outline, recent changes, derived `agent_hints`, verification state. **Default-of-choice for "read this page"** — falls back gracefully via `degraded` flag and `missing_fields` rather than failing the whole request. Memoised in `wikantik.forAgentCache` (1h TTL). |

### `/tools/*` (2 tools)

| Tool | Request | Purpose |
|---|---|---|
| `search_wiki` | `POST /tools/search_wiki` with JSON body `{"query": "...", "maxResults": 10}` (`query` required; `maxResults` 1–25, default 10) | OpenAPI shape over `ContextRetrievalService` (same hybrid retrieval as `retrieve_context`). Response shape includes citation URLs and ranked snippets. |
| `get_page` | `GET /tools/page/{name}`, optional `?maxChars=` (default truncated, hard cap 20,000) | Single page fetch with frontmatter parsed into a structured field. |

The OpenAPI 3.1 document is served at `GET /tools/openapi.json` (also `GET /tools/`). The `operationId`s are `search_wiki` and `get_page`, and every operation includes worked `example` payloads — sufficient for ChatGPT Custom GPT tools, OpenWebUI tool servers, or any other client that consumes a static OpenAPI document.

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

> These are MCP **prompts** — guided workflows — not tools. The similarly-named compound *tools* (`publish_cluster`, `extend_cluster`, …) were retired; do not try to call them.

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

Because of those gates, **adding an admin tool means editing `wikantik-mcp-instructions.txt` and `McpProtocolIT.EXPECTED_TOOLS` too** — otherwise the build goes red (see [Writing a New MCP Tool](WritingANewMcpTool)); `McpProtocolIT.EXPECTED_TOOLS` currently lists 29 tools. The instructions text is served by the admin endpoint only; the knowledge endpoint serves a short inline string. The legacy locking section (`lock_page` / `unlock_page`) is gone — concurrency is now optimistic via `expectedContentHash` on `update_page`.

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

Body in Markdown. Internal links use Markdown link syntax.
```

`cluster:` is **scalar-or-list** on a non-hub page: a page may belong to several clusters, and the first entry is the *primary* one that drives breadcrumbs, JSON-LD `articleSection`, sidebar placement and the embedding prefix. A `type: hub` page's `cluster:` must stay scalar — the hub is what *declares* the cluster exists. Sub-clusters are `parent/child`, one level deep.

Save-time enforcement is on: pages without `canonical_id` get one auto-assigned and injected; `type: runbook` pages are schema-validated; cluster KG-inclusion policy is applied. Most of the agent surface area assumes these invariants — read [StructuralSpineDesign](StructuralSpineDesign) and [AgentGradeContentDesign](AgentGradeContentDesign) for the full model.

### Frontmatter validation and normalization

YAML quoting trips up agents: a value like `title: Woodworking Joinery: Structural Mechanics` parses as a nested mapping start because of the unquoted colon, and the page used to save with empty metadata + a `WARN` log. Two layers now close that hole:

1. **MCP-side normalization (Layer 1).** `WritePagesTool` and `UpdatePageTool` route every save through `FrontmatterNormalizer`: any embedded `---` block is parsed strictly, merged with the explicit `metadata` argument (explicit wins on conflict), and re-emitted via `FrontmatterWriter` (SnakeYAML's emitter, which always quotes correctly). If the embedded YAML is malformed, the tool returns a structured error with the message + 1-based line/column + a quoting hint instead of silently dropping metadata.
2. **Save-time validation (Layer 2).** `SchemaValidationPageFilter` (which replaced the older `FrontmatterValidationPageFilter`) runs at `preSave` priority `-1006`. It folds in the strict-YAML parse (`FrontmatterParser.parseStrict`) and the server-authoritative frontmatter schema: ERROR violations, including malformed YAML, refuse the save, while WARNING violations come back on a successful response. It catches non-MCP paths too (REST, the editor). Gated by `wikantik.frontmatter.enforcement.enabled` (default `true`).

`FrontmatterParser.parse(...)` keeps its graceful-degrade behaviour for read-side callers — the strict path is opt-in via `parseStrict(...)` and only used on writes. The parser also bounds hostile input: alias fan-out 50, nesting depth 50, 1 MB per frontmatter block, duplicate keys refused. Operators adopting the validator on an existing wiki should run `GET /admin/frontmatter-issues` first to find broken pages, fix them, then leave the flag at its default `true`.

`GET /admin/frontmatter-issues` returns `{data: {issues: [{pageName, error, line?, column?}], issue_count, scanned, error_count}}`. Scan cost is O(N) page reads on the request thread — it's a migration tool, not a polling target.

## Why this rewrite is worth the effort to wire up

The point of the rebuild was *agents reading agents-grade content*, not humans browsing. Three load-bearing capabilities:

1. **`get_page_for_agent` is the right default for "read this page"**, not `read_page` or `get_page`. It returns a token-budgeted projection — summary, key facts, headings outline, recent changes, derived `agent_hints`, verification state — without the full markdown body. Per-extractor try/catch: failure of one section surfaces as a `degraded` flag plus `missing_fields`, not a total error. Memoised by `(canonical_id, updated_at_millis)` so repeat reads in a session are essentially free.

2. **Verification metadata is part of the wire format.** `verified_at`, `verified_by`, `confidence`, and `audience` flow through the `/for-agent` projection and the structural index. Authors stamp pages via `mark_page_verified`; operators triage at `GET /admin/verification?confidence=stale`. Agents can — and should — refuse to act on pages where `confidence: stale`.

3. **Runbooks are a first-class type.** A page with `type: runbook` carries a structured `runbook:` block (`when_to_use`, `inputs`, `steps`, `pitfalls`, `related_tools`, `references`), validated at save time and re-validated on read. Tell agents: when planning a multi-step task, search for runbooks first (e.g. `list_pages_by_filter` with `type=runbook`, or `retrieve_context` filtered by type). The seed corpus lives at [AgentCookbook](AgentCookbook).

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

OpenWebUI (and similar) consume the OpenAPI document directly. Two operations are exposed: `search_wiki` and `get_page`. Mint the key with scope `tools` (or `all`).

### Plain HTTP / scripted use

```bash
# Context bundle over /knowledge-mcp (after the initialize handshake above; reuse its Mcp-Session-Id)
curl -sS -X POST http://localhost:8080/knowledge-mcp \
  -H "Authorization: Bearer $WIKANTIK_KEY" \
  -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"assemble_bundle",
                 "arguments":{"query":"how do agents read pages"}}}'

# OpenAPI tool server
curl -sS -X POST http://localhost:8080/tools/search_wiki \
  -H "Authorization: Bearer $TOOLS_KEY" -H 'Content-Type: application/json' \
  -d '{"query":"hybrid retrieval","maxResults":5}'
```

## Recommended agent system prompt

Drop a fragment like this into the agent's project / system prompt so it does not blindly full-text-search:

> You have access to a Wikantik MCP server. Use it like this:
>
> - **Answer a question:** prefer `assemble_bundle` — it returns ranked, de-duplicated, version-pinned, cited section text to ground on. Cite the handles it returns; do not hand-craft URLs. Check the `coverage` block: `weak` or `partial` confidence means refine the query rather than answer thinly.
> - **Read a page:** prefer `get_page_for_agent` over `read_page` / `get_page`. It returns a token-budgeted projection with summary, key facts, headings outline, `agent_hints`, and verification state. Treat any field listed in `missing_fields` as unavailable rather than empty. Treat `confidence: stale` as untrusted information.
> - **Browse structure:** for cluster / tag / type questions use the structural-spine tools (`list_clusters`, `list_tags`, `list_pages_by_filter`, `get_page_by_id`). Do not full-text-search structural questions; the spine is faster and exact.
> - **Plan multi-step work:** before doing complex authoring or maintenance, search for `type: runbook` pages on the topic (`list_pages_by_filter` with `type=runbook`) and follow the `steps:` block.
> - **Discover the schema:** call `discover_schema` once per session before constructing custom KG queries; call `list_metadata_values` before custom frontmatter filters.
> - **Edit pages (if authoring is enabled):** use `update_page` with `expectedContentHash` for optimistic locking (it records `update_page` as the change note; only `mark_page_verified` and `delete_pages` accept a `changeNote`). Use `mark_page_verified` to stamp verification after a substantive review. Never call `delete_pages` or `rename_page` without `confirm=true`.

## Workflow recipes

### RAG / question answering

1. `assemble_bundle` with the user's question — this is the grounding call.
2. If you need more of a page than the bundle returned, fetch with `get_page_for_agent` (cheap thanks to the cache).
3. Cite with the citation handles from the bundle, not by hand-crafting URLs.

### Authoring an article cluster

1. `discover_schema` + `list_clusters` to confirm cluster naming.
2. Use the `publish-cluster` MCP prompt on `/wikantik-admin-mcp` to generate hub + members.
3. `write_pages` (one round trip).
4. `verify_pages` with `checks=["seo_readiness", "retrieval_readiness"]` to confirm cross-references and metadata.
5. `preview_structured_data` on the hub to confirm JSON-LD / breadcrumb shape.
6. `mark_page_verified` on each page once the cluster is reviewed.
7. `ping_search_engines` (requires `wikantik.indexnow.apiKey`).

### Link / health audit

1. `get_wiki_stats` for the dashboard.
2. `get_broken_links` and `get_orphaned_pages` for the worklist.
3. Use the `audit-links` prompt on a starting page to walk the local link graph.
4. After fixes, `verify_pages` to confirm.

### Graph traversal from a known page

1. `get_page_for_agent` to get the page's `canonical_id`.
2. For **Page Graph** (real wikilinks): `get_outbound_links` / `get_backlinks` on `/wikantik-admin-mcp`.
3. For the **Knowledge Graph** (derived co-mention/typed-relation edges): `traverse` on `/knowledge-mcp`.

Do not conflate the two: the Page Graph is what authors wrote, the Knowledge Graph is what extraction inferred.

### Renaming a cluster

1. Call `rename_cluster` **without** `confirm` to get the plan — which pages change, and any conflict with a hub that already declares the target.
2. Review the plan, then re-call with `confirm=true`. Page names, `canonical_id`s and URLs do not move; only `cluster:` frontmatter is rewritten.

### Retrieval-quality investigation

1. `GET /admin/retrieval-quality?limit=20` to read recent runs.
2. `POST /admin/retrieval-quality/run` with `{"query_set_id": "core-agent-queries", "mode": "HYBRID"}` to trigger an ad-hoc run. (`HYBRID_GRAPH` still parses for historical rows but is retired — the graph rerank was removed in 2026-07.)
3. Diff the per-mode nDCG@5/@10, Recall@20, MRR. Smoke gate is `nDCG@5 >= 0.5`.
4. `list_retrieval_queries` with `max_avg_results=1` surfaces the real queries the corpus answers poorly.

## Observability

Prometheus metrics relevant to integrations:

- `wikantik_for_agent_response_bytes` — histogram of `/for-agent` response sizes.
- `wikantik_retrieval_ndcg_at_5` / `_at_10` / `_recall_at_20` / `_mrr` — gauges keyed by `{set, mode}`.
- `wikantik_tools_*` — request counters, latencies, and rate-limit / auth rejections for `/tools/*` (registered via `ToolsMetricsBridge`).

Log streams worth tailing:

- `SecurityLog` — every auth failure, every rate-limit rejection on every endpoint.
- Log lines tagged `MCP` and `Knowledge MCP` cover startup, tool registration, drift warnings, and shutdown.

## Safety checklist before pointing an agent at production

1. Mint a DB-backed key bound to a real principal, one per agent, so a leak can be revoked alone. Property-file keys no longer exist.
2. Use `scope=mcp_read` for read-only research agents. `mcp_admin` is a fully privileged admin credential (no per-user checks, can write every non-system page); reserve it, and `all`, for human-supervised authoring, never for unattended automation.
3. Set `mcp.access.allowedCidrs` to the intended source range; do not rely on bearer tokens alone for agents on shared networks.
4. Confirm `mcp.access.allowUnrestricted` is unset (or `false`) in production. The fail-closed default is what you want.
5. Confirm rate limits are enforced (`mcp.ratelimit.global > 0`).
6. Confirm `wikantik.runbook.enforcement.enabled=true` (default) so authoring agents cannot land malformed runbooks.
7. Confirm `wikantik.structural_spine.enforcement.enabled=true` (default) so every page gets a canonical ID.
8. Decide your KG inclusion policy — see [KgInclusionPolicy](KgInclusionPolicy).
9. Make sure your reverse proxy passes through:
   - The `Authorization` header.
   - The `Accept: text/event-stream` header (the Streamable HTTP transport requires SSE).
   - The client IP (so CIDR allowlists work).
10. If you supply your own `mcp.instructions.file` override (an absolute path), mirror the live tool registry in it. The bundled file is regression-tested by `InstructionsRegistryDriftTest`; an external override is not.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `503` with `mcp_access_unconfigured` | Filter is fail-closed — no DB-backed keys (no datasource), no CIDRs, `allowUnrestricted` not set. Fix the datasource, or set a CIDR list. |
| `403 Access denied` | No `Authorization: Bearer` header, or the token is unknown / revoked, and the caller IP is not in `mcp.access.allowedCidrs`. |
| `403 Key not authorized for MCP` | A valid key whose scope does not cover the endpoint: a `tools` key at an MCP endpoint, or an `mcp_read` key at `/wikantik-admin-mcp`. Mint a key with `scope=mcp_read` (knowledge endpoint), `scope=mcp_admin` or `scope=all`. |
| `429 Rate limit exceeded` | Rate-limit knobs too tight, or runaway agent; back off for the `Retry-After: 1` second. Inspect `SecurityLog`. |
| `/knowledge-mcp` is not mounted (404) or lists no tools | None of `KnowledgeGraphService`, `ContextRetrievalService`, `StructuralIndexService` is configured — check `wikantik.datasource` and the embeddings setup. |
| `/knowledge-mcp` advertises fewer than 21 tools, or `/wikantik-admin-mcp` fewer than 29 | Expected on a partial deployment — several tools register conditionally on what is wired. Not an error. |
| `get_page_for_agent` returns `degraded: true` | One of the extractors failed; the named field appears in `missing_fields`. Check log for the underlying exception. |
| Startup log warns about tool-name drift | An external `mcp.instructions.file` override disagrees with the live registry. The bundled instructions are regression-tested at build time; only an operator-supplied override can drift in a live deployment. Refresh the override or remove it. |
| Authoring tool says `hash mismatch` | Optimistic-lock guard fired. The response carries `currentHash`, `currentVersion` and `latestContent` — rebase against it and retry without a separate read. |
| `rename_cluster` returned a plan instead of applying | Working as designed. An unconfirmed call always returns the plan; re-call with `confirm=true`. |
| `WikiEngine could not be created — MCP server not started` | The MCP listener ran before the engine could be created. Confirm `web.xml` still lists `WikiBootstrapServletContextListener` ahead of the MCP, knowledge and tools initializers. |

## Pointers

- [GoodMcpDesign](GoodMcpDesign) — the principles the tool surface was built against.
- [AgentGradeContentDesign](AgentGradeContentDesign) — the design that drove the rewrite (verification metadata, `/for-agent`, runbook type, retrieval-quality CI, examples).
- [AgentCookbook](AgentCookbook) — runbook-style how-tos that seed the retrieval-quality CI.
- [StructuralSpineDesign](StructuralSpineDesign) — clusters, tags, canonical IDs, generated `Main.md`.
- [HybridRetrieval](HybridRetrieval) — what `assemble_bundle`, `retrieve_context` and `search_wiki` do under the hood.
- [KgInclusionPolicy](KgInclusionPolicy) — cluster-primary policy + page-level override.
- [FindingTheRightMcpTool](FindingTheRightMcpTool) — agent-facing chooser.
- [McpAuditTools](McpAuditTools) — how the MCP surface itself is checked: drift tests, protocol ITs, audit log lines, metrics.
- [WritingANewMcpTool](WritingANewMcpTool) — adding a tool and the checks it trips.
- [ConnectingClaudeCodeToWikantikMcp](ConnectingClaudeCodeToWikantikMcp) — wiring Claude Code to the production servers.

[{Relationships}]
