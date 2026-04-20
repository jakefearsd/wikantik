---
title: OpenWebUI Tool Server
summary: Configure and operate the Wikantik OpenAPI tool server for LLM integrations.
tags: [ops, integration, openwebui, llm]
---

# OpenWebUI Tool Server

Wikantik ships an OpenAPI 3.1 tool server under `/tools/` that exposes hybrid search and
page retrieval as LLM-callable tools. It is designed for OpenWebUI and any other OpenAPI
tool runtime; the OpenAPI document at `/tools/openapi.json` advertises two operations:

| Operation    | Method + path         | Purpose                                               |
|--------------|-----------------------|-------------------------------------------------------|
| `search_wiki` | `POST /tools/search_wiki` | BM25 + hybrid search across the wiki corpus       |
| `get_page`   | `GET  /tools/page/{name}` | Fetch a single page's Markdown body with truncation |

## Configuration

Tool-server access has two layers:

1. **DB-backed API keys** (recommended) — generated via the admin UI at
   `/admin/apikeys`, bound to a Wikantik principal, and stored SHA-256 hashed. Each
   call runs under the key's principal, so page ACLs and JAAS permissions apply
   exactly as they would for that user's interactive session.
2. **Transport-layer guards** — CIDR allowlist, rate limits, and the "no config =
   fail closed" safety net live in `wikantik-tools.properties`. Defaults ship inside
   the module JAR; overrides go in `tomcat/lib/wikantik-tools.properties`.

| Property                                     | Purpose                                               | Default |
|----------------------------------------------|-------------------------------------------------------|---------|
| `tools.access.keys`                          | Legacy comma-separated Bearer tokens (no principal binding — avoid in new deployments) | *(none)* |
| `tools.access.allowedCidrs`                  | Comma-separated CIDR allowlist (e.g. `10.0.0.0/8`)    | *(none)* |
| `tools.access.allowUnrestricted`             | Explicit opt-in to run with no auth and no CIDR       | `false` |
| `tools.ratelimit.global`                     | Global requests per second (0 disables)               | `100`   |
| `tools.ratelimit.perClient`                  | Per-client requests per second (0 disables)           | `10`    |
| `wikantik.public.baseURL`                    | Public base URL used for citation links + OpenAPI `servers` entry | *(request host)* |

### Generating a key

Navigate to **Administration → API Keys** and click **Generate Key**. Pick:

- **Principal** — the login that the tool server should impersonate when running
  calls. Page ACLs and JAAS permissions are evaluated against this user.
- **Label** — freeform note (e.g. `OpenWebUI production`) to identify the key later.
- **Scope** — `tools` (OpenAPI only), `mcp` (MCP server only), or `all` (both).

The plaintext token (`wkk_…`) is shown once at creation time and only the SHA-256
hash is persisted. Revoking a key is a single click; any client using the revoked
token starts receiving HTTP 403 immediately.

### Fail-closed semantics

When none of the DB-backed keys, legacy `tools.access.keys`, `tools.access.allowedCidrs`,
or `tools.access.allowUnrestricted=true` is configured, the server refuses every request
with `503 Service Unavailable` and logs a CRITICAL line at startup. With at least one
DB-backed key generated via the admin UI, the server accepts Bearer tokens from that
table — no configuration reload required.

## Calling the tools

### `search_wiki`

```bash
curl -X POST http://localhost:8080/tools/search_wiki \
    -H "Authorization: Bearer $TOOLS_KEY" \
    -H "Content-Type: application/json" \
    -d '{"query": "hybrid retrieval", "maxResults": 5}'
```

Response:

```json
{
  "query": "hybrid retrieval",
  "results": [
    {
      "name": "HybridRetrieval",
      "url": "https://wiki.example.com/wiki/HybridRetrieval",
      "score": 18,
      "summary": "Fusion of BM25 and dense vector ranking",
      "tags": ["search", "design"],
      "snippet": "…fused ranking …",
      "lastModified": "2026-04-12T18:22:11Z"
    }
  ],
  "total": 1
}
```

`maxResults` is clamped to `[1, 25]`; values outside the range snap to the default of 10.

### `get_page`

```bash
curl -H "Authorization: Bearer $TOOLS_KEY" \
    "http://localhost:8080/tools/page/HybridRetrieval?maxChars=4000"
```

The response strips YAML frontmatter from `text`, includes `summary`/`tags` from
frontmatter as top-level fields, and reports truncation via `truncated`/`totalChars`/
`truncatedAt` when the body exceeds the limit (default 6000 chars, hard cap 20000).

## Observability

The tool server publishes to the process-wide Micrometer registry (scraped at
`/observability/metrics`). All meters are prefixed `wikantik.tools`:

| Meter                                         | Type     | Notes |
|-----------------------------------------------|----------|-------|
| `wikantik.tools.requests{endpoint,status}`    | counter  | endpoint ∈ {search_wiki, get_page, openapi}; status ∈ {success, error, not_found} |
| `wikantik.tools.search.results_returned`      | counter  | total result rows emitted |
| `wikantik.tools.get_page.truncated`           | counter  | get_page responses that hit the truncation cap |

## OpenWebUI wiring

In OpenWebUI:

1. Workspace → Tools → Add Tool → *OpenAPI URL*.
2. Paste `https://wiki.example.com/tools/openapi.json` and your Bearer token.
3. OpenWebUI reads the two operations verbatim; the per-operation `description` fields
   double as the LLM's tool prompts, so no extra authoring is required.

## Security notes

- DB-backed keys are bound to a Wikantik principal; each call runs as that user so
  page ACLs and JAAS permissions apply exactly as they would for an interactive
  session. Pick the principal deliberately — the tool caller inherits their view
  permissions, so grant narrowly.
- Legacy config-file keys (`tools.access.keys`) are unbound and behave like a shared
  service account that bypasses JAAS. Prefer DB-backed keys for any new deployment
  and drop the legacy list once all clients have migrated.
- Rate limits are sliding-window, 1-second buckets; the `Retry-After: 1` header is
  returned with every 429.
