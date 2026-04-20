# OpenWebUI Tool Server Design

**Date:** 2026-04-19
**Status:** Draft — spike scope
**Owner:** Jake Fear
**Related:**
- `2026-04-17-retrieval-eval-baseline.md` — the retrieval stack this tool surfaces
- `docs/wikantik-pages/HybridRetrieval.md` — operator reference for the hybrid search path
- `wikantik-mcp/` — the API-key + CIDR + rate-limit filter pattern we reuse

---

## 1. Problem

Wikantik already has a high-quality hybrid retriever (`HybridSearchService`). We want OpenWebUI — a separate process, possibly on a separate host — to use Wikantik's corpus as a RAG source without duplicating the index or re-embedding the pages. OpenWebUI's native "external knowledge" paths either:

- (a) call an OpenAPI-spec'd **Tool Server** that the LLM tool-calls during chat, or
- (b) hit a searxng-compatible web-search endpoint that the UI invokes before every message.

Option (a) is strictly better: the LLM decides when retrieval is useful, arguments (query, k) can be tuned per call, and responses feed into the model via the function-calling mechanism instead of blind prefix injection. This spec is for (a).

## 2. How OpenWebUI Tool Servers work

OpenWebUI admins register a tool server by URL plus an optional bearer token. The UI fetches `GET /openapi.json` from that base URL, parses the OpenAPI 3.x document, and exposes every declared operation as a tool the user can enable per-model. When a tool is enabled and the chat model emits a tool call, OpenWebUI:

1. Maps `tool_call.name` to an `operationId` in the spec.
2. Builds the HTTP request from the operation's path, method, parameters, and request-body schema.
3. Adds `Authorization: Bearer <token>` if configured.
4. Forwards the JSON response verbatim to the LLM as tool output.

Key implications:

- **The tool server is an ordinary HTTP server.** No persistent connection, no MCP protocol framing, no JSON-RPC. Each tool call is one request/response.
- **The OpenAPI document is the contract.** Accuracy matters: if the spec declares a required parameter that the endpoint actually ignores, the LLM will sometimes fail to call the tool.
- **Responses must be LLM-consumable JSON.** Narrative strings are fine; deeply nested objects waste tokens. Keep shapes flat and named.
- **Per-operation descriptions are prompts.** The `summary`/`description` fields in the OpenAPI spec are read by the model and influence when/how it invokes the tool.

## 3. Scope

### In scope (Phase 9a — the spike)

- A new `wikantik-tools` Maven module (or a package in `wikantik-rest` — see §13) that serves:
  - `GET  /tools/openapi.json`  — OpenAPI 3.1 document
  - `POST /tools/search_wiki`   — hybrid retrieval tool
  - `GET  /tools/page/{name}`   — full-page fetch for follow-up drilldown
- API-key auth, CIDR allowlist, rate limiting — reusing the filter pattern from `McpAccessFilter`.
- Result payloads include page name, canonical URL, frontmatter summary, ranked-chunk excerpt, tags, and score.
- Unit tests for the filter, the search endpoint, and the spec schema.
- Updated `wikantik.properties` and a new `docs/wikantik-pages/OpenWebUIToolServer.md` operator doc.

### Out of scope (deferred — Phase 9b+)

- DB-backed API keys with revocation via admin UI. The spike reuses the MCP config-file model; a later phase can migrate both MCP and tool-server keys onto the same schema.
- Per-key principal binding so ACLs can apply. See §10 for the interim policy.
- Additional tools: `list_recent_changes`, `get_similar_pages`, `get_tag_index`. Easy to add once the harness exists, but not required for a first OpenWebUI wire-up.
- A JSON-Schema validator for incoming requests; we rely on the OpenAPI document + manual parsing.

## 4. Architecture

```
OpenWebUI (remote)
     │   Authorization: Bearer <key>
     │   POST /tools/search_wiki  { query, k }
     ▼
┌────────────────────────────────────────────────┐
│ Tomcat (Wikantik webapp)                       │
│                                                │
│   ToolAccessFilter ──► ToolRateLimiter         │  (parallels MCP)
│           │                                    │
│           ▼                                    │
│   ToolsOpenApiServlet ── serves openapi.json   │
│   ToolsSearchServlet  ── POST /tools/search    │
│   ToolsPageServlet    ── GET  /tools/page/...  │
│                │                               │
│                ├─► SearchManager.findPages()   │
│                ├─► HybridSearchService.rerank()│
│                ├─► PageManager.getPureText()   │
│                └─► ContentChunkRepository      │
└────────────────────────────────────────────────┘
```

Request flow for `search_wiki`:

1. `ToolAccessFilter` validates Bearer token or source CIDR. On success, sets a request attribute with the client ID for downstream logging and rate-limit keying.
2. `ToolsSearchServlet.doPost` parses the JSON body, applies defaults/clamps, and constructs a `Context` for the search (`ContextEnum.WIKI_FIND`).
3. `SearchManager.findPages()` produces the BM25 list.
4. `HybridSearchService.rerank()` fuses with dense. On any failure path it degrades to BM25 — same fail-closed contract as `SearchResource`.
5. `ResultShaper` builds the JSON payload:
   - For each page hit: load frontmatter via `FrontmatterParser`, load top-N ranked chunks from `ContentChunkRepository`, assemble the response entry.
6. Response is sent as `application/json`.

`ToolsPageServlet` is simpler: load the page, parse frontmatter, return body + metadata. No chunking involved.

## 5. Auth model

### Reuse the MCP pattern — verbatim

`McpAccessFilter` is already production-grade: constant-time Bearer comparison, CIDR allowlist, rate limiter, fail-closed default. We clone it rather than share it to avoid coupling the two module lifecycles, but the code is structurally identical.

**New properties** (in a new `wikantik-tools.properties` loaded the same way as `wikantik-mcp.properties`):

```
tools.access.keys              = <comma-separated Bearer tokens>
tools.access.allowedCidrs      = 10.0.0.0/8, 192.168.0.0/16
tools.access.allowUnrestricted = false   # must be explicit to skip auth
tools.ratelimit.global         = 100     # requests/sec, 0 = off
tools.ratelimit.perClient      = 10
```

Key generation: operators run `openssl rand -hex 32`, add the output to `tools.access.keys`, restart. This mirrors MCP exactly. Phase 9b introduces a `tool_api_keys` table and an admin UI panel.

### Identity model

All valid keys have **equal privileges**. The filter does not create a JAAS `Subject` or set a request principal; requests reach the endpoint with no authenticated user, same as MCP does today.

This is a deliberate Phase 9a limitation. See §10 for the ACL implications.

## 6. API surface

### Operation: `search_wiki`

`POST /tools/search_wiki`

**Request body:**
```json
{
  "query": "how does hybrid retrieval fail closed",
  "k": 5,
  "include_excerpt": true
}
```

- `query` (string, required): the natural-language query
- `k` (integer, optional, default 5, clamped to [1, 20]): max results to return
- `include_excerpt` (boolean, optional, default true): include chunk text in the response. When false, only metadata (name, url, summary, tags, score) is returned — useful for "is there a page about X?" probes that keep the LLM context small.

**Response:**
```json
{
  "query": "how does hybrid retrieval fail closed",
  "total": 3,
  "results": [
    {
      "page_name": "HybridRetrieval",
      "title": "Hybrid Retrieval",
      "url": "https://wiki.example.com/HybridRetrieval",
      "summary": "Operator reference for Wikantik's BM25 + dense hybrid retrieval...",
      "tags": ["search", "retrieval", "embeddings"],
      "score": 0.8437,
      "excerpt": [
        {
          "ordinal": 0,
          "text": "HybridSearchService.rerank() is the single choke point..."
        },
        {
          "ordinal": 3,
          "text": "Every abnormal path returns the input BM25 list unchanged..."
        }
      ],
      "last_modified": "2026-04-19T14:02:00Z"
    }
  ]
}
```

**Behaviour:**
- On `query` missing/blank: `400 {"error":"query is required"}`.
- On search internal failure: `500 {"error":"..."}` (same shape as `SearchResource`).
- Excerpts: up to 3 chunks per page, selected by dense score when available, else by BM25 context snippets. Each excerpt ≤ 600 chars (~150 tokens). Caller's context-window responsibility beyond that.

### Operation: `get_page`

`GET /tools/page/{name}`

**Path param:** `name` — page name, URL-encoded.

**Response:**
```json
{
  "page_name": "HybridRetrieval",
  "title": "Hybrid Retrieval",
  "url": "https://wiki.example.com/HybridRetrieval",
  "summary": "Operator reference for ...",
  "tags": ["search", "retrieval"],
  "content": "...raw markdown, stripped of frontmatter, truncated to 8000 chars...",
  "truncated": false,
  "last_modified": "2026-04-19T14:02:00Z"
}
```

- Body is capped at 8000 chars (≈ 2000 tokens). `truncated: true` when the original was longer. Keeps a single tool call from nuking the LLM context.
- `404` when the page does not exist.

## 7. OpenAPI document

Served by `ToolsOpenApiServlet` as a static string (or a Jackson-serialized POJO) at `/tools/openapi.json`. OpenAPI 3.1 because OpenWebUI's parser accepts both 3.0 and 3.1 and 3.1 has nicer JSON-Schema support.

Key rules:

- `info.title` = `"Wikantik Knowledge Tools"`; `info.version` mirrors the build version header (`X-Build-Version`).
- Every operation has `operationId`, `summary`, and a `description` that is **written as a prompt fragment**. Example for `search_wiki`:

  > "Search the Wikantik knowledge base for pages matching a natural-language query. Use when the user asks a question whose answer is likely documented in internal wiki pages. Returns the top-k matching pages with summaries, tags, and excerpted chunks. Prefer narrow queries — multi-word phrases beat single keywords."

- Request/response schemas are inlined (not `$ref`'d) to keep the document standalone and trivially cacheable.
- A single `securityScheme` of type `http` / `bearer` is declared and referenced at the top level; OpenWebUI picks this up and adds the UI field for the token.

## 8. Response-shape design notes

- **Token budget.** Assume a 32k-context model. A single RAG call with 5 results × (summary ~300 chars + 3 excerpts × 600 chars) ≈ 11 KB ≈ 2750 tokens. Leaves plenty of headroom for the model's actual reasoning. `include_excerpt: false` drops this to ~1.5 KB.
- **Citation affordance.** `url` must be absolute so the LLM can render a clickable citation. Introduce a new property:
  ```
  wikantik.public.baseURL = https://wiki.example.com
  ```
  `ToolsConfig.publicBaseUrl()` reads this; falls back to `wikantik.sitemap.baseURL` if set; else falls back to the request's own `X-Forwarded-Proto`/`Host` headers; else emits a relative URL with a single WARN on first call.
- **Score normalization.** Fused RRF scores are un-bounded. Divide by the max fused score in the result set so the LLM sees a 0–1 range it can reason about. Drop `score` from the spec if normalization turns out to confuse; reconsider after a dogfood pass.
- **Tags as arrays, never comma-separated strings.** Models handle structured arrays better in tool outputs.

## 9. ACL story

**This is the critical design decision — read carefully before implementing.**

MCP's filter model gives every key equal, unauthenticated privilege. Applied to a tool server that can return chunk text from any page, this is effectively a side door that bypasses view ACLs. Acceptable iff one of:

1. Every wiki page is world-readable (no view ACLs on any page).
2. The operator explicitly acknowledges that any OpenWebUI instance holding a tool key can read all pages.

### Phase 9a policy (mandatory)

- On startup, the tool-server lifecycle listener scans the policy-grants table and the indexed page set. If **any** page has a non-default view ACL or any role other than `All` is granted view, log a WARN:

  > `ToolAccessFilter: wiki has restricted pages but tool-server keys bypass ACLs. Every holder of a tools.access.keys value can read all pages. Set wikantik.tools.acl.enforce=strict (unimplemented in 9a) or explicitly acknowledge with wikantik.tools.acl.acknowledge-bypass=true.`

- If `wikantik.tools.acl.acknowledge-bypass=false` (the default) and the wiki has restricted pages, **fail-closed at startup**: the tool servlets respond `503 {"error":"tool server disabled: ACL bypass not acknowledged"}` for every request.

- Document this prominently in `OpenWebUIToolServer.md` and in the `tools.access.*` property comments.

### Phase 9b upgrade path (future)

Introduce `tool_api_keys(key_hash, principal, scopes, created_at, revoked_at)`. The filter resolves the key to a principal and pushes a JAAS `Subject` into the request so that `PermissionFilter` / existing ACL checks apply unchanged. At that point the bypass acknowledgement flag goes away.

## 10. Observability

Register against the shared Micrometer registry via `MeterRegistryHolder.get()` (same pattern as `HybridMetricsBridge`). Meter names prefixed `wikantik.tools`:

| Meter                                          | Type    | Tags                              | Meaning |
|------------------------------------------------|---------|-----------------------------------|---------|
| `wikantik.tools.requests`                      | counter | `op=search_wiki\|get_page\|spec`, `outcome=success\|client_error\|server_error` | Per-operation request counter |
| `wikantik.tools.search.results_returned`       | summary | —                                 | Distribution of `k` actually returned (post-clamp, post-ACL) |
| `wikantik.tools.search.latency_ms`             | timer   | `op=search_wiki\|get_page`        | End-to-end latency |
| `wikantik.tools.auth.denied`                   | counter | `reason=bad_key\|rate_limit\|acl_bypass_not_acknowledged` | Rejections for ops dashboards |

Logging:
- Successful call: `INFO tools {} op={} k={} latency_ms={} client={}`.
- Rejection: `WARN SecurityLog tools denied: reason={} ip={} client={}` — same `SecurityLog` logger MCP uses, so existing alerting picks it up.
- Never log query strings or page content at INFO. Queries can contain PII.

## 11. Testing strategy

### Unit tests

- `ToolAccessFilterTest` — cloned from the MCP filter test; adjust property names. Asserts:
  - Valid bearer passes; invalid returns 403.
  - Fail-closed default (no keys, no CIDR, no unrestricted flag) returns 503.
  - Rate limiter rejects beyond burst.
- `ToolsSearchServletTest` — spins a mock `Engine` with a fixed `HybridSearchService`; asserts response shape, `k` clamping, `include_excerpt=false` omits the excerpt field, blank `query` returns 400.
- `ToolsPageServletTest` — happy path + 404 + truncation.
- `ToolsOpenApiServletTest` — parses the served JSON with a Swagger Parser, asserts every `operationId` resolves to a real servlet path and the security scheme is declared.
- `ResultShaperTest` — unit tests for the JSON shape alone with stubbed page/chunk inputs so the servlet tests don't have to re-verify it.

### Integration test

New submodule `wikantik-it-test-tools` (or reuse `wikantik-it-test-rest`) spinning the Cargo Tomcat with:
- A valid `tools.access.keys` config.
- Three fixture pages with distinct frontmatter and tags.
- A test that hits `/tools/openapi.json` and parses it, then hits `/tools/search_wiki` and `/tools/page/*` and asserts the responses.

### Manual / smoke

- Stand up OpenWebUI locally, point it at the running Wikantik tool server, enable the tool on a chat model, ask a question, verify the LLM tool-calls and cites a result.

## 12. Module placement

Two options:

### Option A: new module `wikantik-tools`
- Mirrors the `wikantik-mcp` structure (own properties file, own filter, own lifecycle listener).
- Clean separation, independent versioning, easy to remove.
- Cost: new `pom.xml`, new classloader registration in `wikantik-war`.

### Option B: new package `com.wikantik.rest.tools` inside `wikantik-rest`
- Cheaper: no new module, reuses `RestServletBase`, one less classloader hop.
- Coupling cost: properties file shared with other REST code; filter has to be wired alongside `AdminAuthFilter` in the same module.

**Recommendation: Option A.** The MCP module has already proven the "own module with its own auth filter" pattern and we'll want the same ergonomics for the tool server (independent operator docs, independent rate limiting, independent metrics tagging). The incremental cost is small and the blast-radius isolation is worth it.

## 13. Implementation plan

Suggested ordering (each step independently green before the next):

1. **Skeleton module** — `wikantik-tools/pom.xml`, `ToolsConfig`, `ToolsAccessFilter` (copy + rename from MCP), `ToolsRateLimiter` (likewise). Minimal `ToolsOpenApiServlet` that returns a hard-coded stub spec. Wire into the WAR. Write filter + rate limiter tests.
2. **`search_wiki` endpoint** — `ToolsSearchServlet`, `ResultShaper`, chunk-lookup helper. Wire to `HybridSearchService`. Unit tests + shape tests.
3. **`get_page` endpoint** — `ToolsPageServlet`. 404 + truncation tests.
4. **OpenAPI document** — real spec, replacing the stub. Swagger-parser round-trip test.
5. **ACL bypass guard** — startup scan + 503 fail-closed behaviour + acknowledgement flag. Test with a fixture that installs a restricted page.
6. **Observability** — metrics bridge + security logging. Verify meters appear on `/observability/metrics`.
7. **Integration test** — spin Tomcat + hit all three endpoints via HTTP.
8. **Docs** — `OpenWebUIToolServer.md` operator reference with curl examples, the OpenAPI URL to paste into OpenWebUI admin, and the ACL acknowledgement warning.

Each step is a single commit; test suite must stay green throughout.

## 14. Open questions

1. **URL path prefix.** `/tools/...` collides with nothing today, but it's a very generic prefix. Consider `/api/tools/...` or `/wikantik-tools/...` to keep the namespace obviously wiki-owned. Defaulting to `/tools/` in the spec; easy to rename.
2. **Score semantics.** Should we omit `score` from the response by default and expose it only via an opt-in query flag? A 0–1 normalized score is safe for the LLM; the raw RRF value is operator-only.
3. **Tag-only probe.** Worth adding `tags_filter: ["search", "embeddings"]` to the `search_wiki` request now, or waiting until an LLM actually asks for it in a dogfood session? Leaning toward "wait": adds parser code that may never earn its keep.
4. **Streaming.** OpenWebUI can handle streaming tool responses for large outputs. Not needed at the current payload sizes; revisit if we add a `summarize_pages` tool that actually writes narrative.
5. **Multi-key scoping for Phase 9b.** When keys become principal-bound, do we want per-key rate limits (already supported via clientId in the rate limiter) or per-principal rate limits (requires a second limiter layer)?

## 15. Success criteria

- OpenWebUI, configured with the tool-server URL and a bearer key, tool-calls `search_wiki` on an RAG-relevant question and cites a real Wikantik page in its answer.
- Turning off the embedding backend (to simulate outage) does not break the tool server: it still returns BM25 results.
- Zero regressions in the existing test suite.
- `wikantik.tools.requests` and `wikantik.tools.search.latency_ms` both appear at `/observability/metrics` during load.
