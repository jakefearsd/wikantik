# MCP Agents and Tool Endpoints

This page is for administrators and developers who connect AI agents or other
programmatic clients to Wikantik. It covers the three agent-facing endpoints, how a
client authenticates and opens a session, example client configuration, and the full
tool catalogue. **This is the one page that owns tool counts**; other docs link here
instead of repeating the numbers.

## Endpoints at a glance

| Endpoint | Protocol | Tools | Access | Minimum key scope |
|---|---|---|---|---|
| `/wikantik-admin-mcp` | MCP (Streamable HTTP) | 29 read and write tools when fully wired | `McpAccessFilter` | `mcp` |
| `/knowledge-mcp` | MCP (Streamable HTTP) | 21 read-only tools when fully wired | `McpAccessFilter` (required scope `mcp_read`) | `mcp_read` |
| `/tools/*` | OpenAPI 3.1 over plain HTTP | 2 | `ToolsAccessFilter` | `tools` |

Counts are as of **2.4.53**. The URL patterns come from `web.xml` (`/tools/*`) and the
MCP bootstrapper (`/wikantik-admin-mcp`, `/knowledge-mcp`).

### The counts depend on wiring

The numbers above are the maximum, reached when every optional subsystem is present.
A deployment without a subsystem advertises fewer tools.

**`/wikantik-admin-mcp`** (registered in `McpToolRegistry`, verified against
`McpProtocolIT.EXPECTED_TOOLS`, which lists 29):

- 19 tools are always registered.
- 9 tools need the Knowledge Graph service: `list_proposals`, `inspect_proposals`,
  `propose_knowledge`, `query_nodes`, `search_knowledge`, `list_orphaned_kg_nodes`,
  and (only when the curation service is also present) `review_proposals`,
  `curate_edges`, `curate_nodes`.
- `list_retrieval_queries` needs a datasource-backed query-log reader.
- `rename_cluster`, `list_content_opportunities` and `snooze_opportunity` are
  registered unconditionally, but refuse at call time with an explanation when the
  structural index or the content-intelligence subsystem is missing. They stay in
  `tools/list` so the advertised surface does not vary with wiring.

**`/knowledge-mcp`** (registered in `KnowledgeMcpInitializer.assembleTools`; the
endpoint is not mounted at all unless the Knowledge Graph service, the context
retrieval service or the structural index exists):

| Tools | Registered only when |
|---|---|
| `discover_schema`, `query_nodes`, `get_node`, `traverse`, `search_knowledge` | the Knowledge Graph service is present |
| `find_similar` | the Knowledge Graph service **and** the node-similarity index are present |
| `retrieve_context`, `get_page`, `list_pages`, `list_metadata_values`, `read_pages` | the context retrieval service is present |
| `list_clusters`, `list_tags`, `list_pages_by_filter`, `get_page_by_id` | the structural index is present |
| `get_page_for_agent` | the for-agent projection service is present |
| `assemble_bundle` | the bundle assembly service is present (retrieval wired) |
| `get_briefing` | the briefing service is present |
| `get_ontology`, `sparql_query` | the ontology model manager is present (`wikantik.ontology.enabled`) |
| `list_stale_citations` | the citation repository is present (`wikantik.citations.enabled` with a datasource) |

To see what your deployment really exposes, call `tools/list` (see
[Open a session with curl](#open-a-session-with-curl)). A local development server with
everything wired returns exactly the 21 names in the knowledge catalogue below.

## Get a key

Both MCP endpoints and `/tools/*` accept a **bearer token** that is a database-minted
API key. Mint one at **Admin → API Keys** (`/admin/apikeys`); the plaintext is shown
once. [ApiKeys.md](ApiKeys.md) is the full reference for issuing, scoping and revoking
keys, including the self-service surface. The short version:

| You want an agent that… | Scope |
|---|---|
| reads and searches content over `/knowledge-mcp` only | `mcp_read` |
| curates content and the Knowledge Graph over `/wikantik-admin-mcp` (and can also use `/knowledge-mcp`) | `mcp` |
| calls only the OpenAPI `/tools/*` endpoints | `tools` |
| reaches every key-protected surface | `all` |

On `/wikantik-admin-mcp` the key runs as the principal you name, so page ACLs and
policy grants apply to every tool call; give a curation agent an account with only the
permissions it needs. `/knowledge-mcp` is different: the MCP transport carries no caller
identity, so its read tools enforce page view ACLs as an **anonymous guest** and return
only publicly viewable pages, whichever principal owns the key. A caller that needs
restricted content has to use the privileged admin endpoint.

Authentication has two other ways to succeed, per `McpAccessFilter` and
`ToolsAccessFilter`: the caller's IP is in `mcp.access.allowedCidrs` /
`tools.access.allowedCidrs`, or `mcp.access.allowUnrestricted=true` /
`tools.access.allowUnrestricted=true` (both default `false`). With no key, no CIDR
allowlist and no unrestricted flag configured, the endpoint fails closed with HTTP 503.
A key with the wrong scope gets HTTP 403. Each endpoint also has its own rate limit
(`mcp.ratelimit.global` 100, `mcp.ratelimit.perClient` 10; the `tools.ratelimit.*`
keys have the same defaults). See [Security.md](Security.md#api-keys-and-agent-access).

## Open a session with curl

MCP over Streamable HTTP needs three steps, in order: `initialize`, then read the
`Mcp-Session-Id` response header, then send `notifications/initialized`. Every later
call must carry that header. Without it, the request falls through to the HTTP filter
chain and you get a raw 400/500 instead of an MCP response.

```bash
BASE=http://localhost:8080
KEY=wkk_your_key_here         # the token shown once at creation (tokens start with wkk_)
H=(-H "Authorization: Bearer $KEY" \
   -H 'Content-Type: application/json' \
   -H 'Accept: application/json, text/event-stream')

# 1. initialize; keep the response headers
curl -s -D headers.txt -X POST "$BASE/knowledge-mcp" "${H[@]}" -d '{
  "jsonrpc":"2.0","id":1,"method":"initialize",
  "params":{"protocolVersion":"2025-06-18","capabilities":{},
            "clientInfo":{"name":"curl","version":"0"}}}'

# 2. extract the session id
SID=$(grep -i '^mcp-session-id' headers.txt | awk '{print $2}' | tr -d '\r')

# 3. confirm initialization (returns 202, no body)
curl -s -X POST "$BASE/knowledge-mcp" "${H[@]}" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 4. list the tools this deployment exposes
curl -s -X POST "$BASE/knowledge-mcp" "${H[@]}" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Responses to `tools/call` and `tools/list` may arrive as `text/event-stream`. Split the
body on newlines, take the line that starts with `data:`, and parse the JSON after it;
the tool output is in `result.content[0].text`. The MCP SDK (2.0.1) accepts protocol
versions `2024-11-05`, `2025-03-26`, `2025-06-18` and `2025-11-25`.

A call looks like this:

```bash
curl -s -X POST "$BASE/knowledge-mcp" "${H[@]}" -H "Mcp-Session-Id: $SID" -d '{
  "jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"assemble_bundle","arguments":{"query":"how do I deploy locally"}}}'
```

## Configure Claude Code

Add the endpoints to a project `.mcp.json` (or with `claude mcp add --transport http`).
The `type: "http"` entries use Streamable HTTP and send your key as a bearer header:

```json
{
  "mcpServers": {
    "wikantik-knowledge": {
      "type": "http",
      "url": "https://wiki.example.com/knowledge-mcp",
      "headers": { "Authorization": "Bearer ${WIKANTIK_MCP_READ_KEY}" }
    },
    "wikantik-admin": {
      "type": "http",
      "url": "https://wiki.example.com/wikantik-admin-mcp",
      "headers": { "Authorization": "Bearer ${WIKANTIK_MCP_ADMIN_KEY}" }
    }
  }
}
```

Use an `mcp_read` key for the knowledge entry and an `mcp` key for the admin entry.
Keep the admin entry out of any shared or checked-in config unless the key comes from
the environment.

## Use /tools/* from non-MCP clients

`/tools/*` is an OpenAPI 3.1 tool server for clients that do not speak MCP, such as
OpenWebUI. It exposes two operations, both gated by a `tools` (or `all`) key:

| Operation | Request | Purpose |
|---|---|---|
| `GET /tools/openapi.json` (also `GET /tools/`) | none | the OpenAPI document that describes the tools |
| `POST /tools/search_wiki` | JSON body `{"query": "...", "maxResults": 10}` (`query` required; `maxResults` 1-25, default 10) | full-text wiki search |
| `GET /tools/page/{name}` | none | fetch one page by name |

Point the client at `https://wiki.example.com/tools/openapi.json` and supply the key as
a bearer token.

## Tool catalogue

"Access" says what the tool can change: **read** tools never modify wiki content or the
Knowledge Graph; **write** tools do.

### /wikantik-admin-mcp (29 tools when fully wired)

The authoritative per-tool descriptions are in
`wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`, which the server
returns as its instructions on `initialize`. Every write tool refuses system pages
(CSS themes, menu fragments, help pages, `Main`) unless the page is listed in
`wikantik.systemPages.mcpEditable`.

| Tool | Purpose | Access |
|---|---|---|
| `read_page` | Raw Markdown body, frontmatter and `contentHash` of one page | read |
| `get_page_history` | Version history of a page, newest first | read |
| `diff_page` | Text diff between two versions of a page | read |
| `get_backlinks` | Pages that link to a given page | read |
| `get_outbound_links` | Pages a given page links to | read |
| `get_broken_links` | Every reference to a page that does not exist | read |
| `get_orphaned_pages` | Pages with no incoming links | read |
| `get_wiki_stats` | Total pages, broken-link count, orphan count, recent changes | read |
| `verify_pages` | Compound integrity and optional SEO-readiness check across pages | read |
| `preview_structured_data` | Meta description, Open Graph, JSON-LD and feed output a page produces | read |
| `ping_search_engines` | Notify Google ping or IndexNow of content changes (needs absolute `wikantik.baseURL`; IndexNow needs `wikantik.indexnow.apiKey`) | read (calls external services) |
| `write_pages` | Batch-create new pages | write |
| `update_page` | Edit a page with optimistic locking (`expectedContentHash`); merges frontmatter | write |
| `delete_pages` | Batch-delete pages (`confirm=true` required; skips pages with backlinks unless told otherwise) | write |
| `rename_page` | Rename a page, moving history and attachments, optionally rewriting referrers | write |
| `rename_cluster` | Rewrite a cluster path across every member page; returns a plan unless `confirm=true` | write |
| `mark_page_verified` | Stamp `verified_at`/`verified_by` and optionally pin `confidence` | write |
| `list_proposals` | Query Knowledge Graph proposals | read |
| `inspect_proposals` | Bulk deep-dive on 1-50 proposals with conflict flags | read |
| `propose_knowledge` | Submit a Knowledge Graph proposal for human review | write |
| `review_proposals` | Bulk approve, reject or judge 1-50 proposals | write |
| `curate_edges` | Bulk edge upsert, confirm, delete, delete-and-reject (1-50 ops) | write |
| `curate_nodes` | Bulk node upsert, delete, merge (1-50 ops) | write |
| `query_nodes` | Filter and list Knowledge Graph nodes with the inclusion filter bypassed | read |
| `search_knowledge` | Fuzzy search over Knowledge Graph node names and properties, filter bypassed | read |
| `list_orphaned_kg_nodes` | Knowledge Graph nodes with no incident edges | read |
| `list_retrieval_queries` | Real retrieval queries from the query log, ranked by frequency | read |
| `list_content_opportunities` | The ranked content-opportunity backlog; see [ContentIntelligence.md](ContentIntelligence.md) | read |
| `snooze_opportunity` | Decline an opportunity for 1-365 days with a required reason | write |

### /knowledge-mcp (21 tools when fully wired)

All 21 are read-only with respect to content. `assemble_bundle` and `get_briefing`
record query telemetry (`retrieval_query_log`, `briefing_log`), which does not change
wiki content. Page-returning tools apply the guest view gate described under
[Get a key](#get-a-key): restricted pages are not returned.

| Tool | Purpose |
|---|---|
| `assemble_bundle` | Primary answer-grounding tool: ranked, de-duplicated, version-pinned, cited section texts (no synthesized answer); returns a `coverage` block |
| `get_briefing` | Session-start context briefing as injection-ready markdown; call once at the start of a session |
| `retrieve_context` | Discover which pages and sections are relevant to a query |
| `get_page` | Fetch one page's frontmatter metadata and URL by name |
| `read_pages` | Batched raw-markdown read of up to 20 pages |
| `get_page_for_agent` | Token-budgeted page projection: summary, key facts, outline, relations, verification state |
| `get_page_by_id` | Resolve a rename-stable `canonical_id` to the current page descriptor |
| `list_pages` | Browse pages by metadata filters |
| `list_pages_by_filter` | List pages by structural filter (type, cluster, tags, freshness) from the Page Graph index |
| `list_clusters` | Every cluster with its hub page, article count and last update |
| `list_tags` | The tag dictionary with page counts |
| `list_metadata_values` | Distinct values of a frontmatter field with counts |
| `discover_schema` | Shape of the Knowledge Graph: node types, relationship types, property keys |
| `query_nodes` | Search Knowledge Graph nodes by type, properties and provenance |
| `get_node` | Full details for one node, including edges and provenance |
| `traverse` | Walk the co-mention graph from a seed node |
| `search_knowledge` | Full-text search over Knowledge Graph node names and properties |
| `find_similar` | Nodes similar to a given node by mention-centroid embedding |
| `get_ontology` | The formal ontology T-Box (classes, properties, SKOS schemes) |
| `sparql_query` | Read-only SPARQL over the materialized ontology (10,000-row cap, 30 s timeout) |
| `list_stale_citations` | Citations whose status is `stale` or `target_missing` |

The two servers share `query_nodes` and `search_knowledge`, but the admin variants
bypass the Knowledge Graph inclusion policy so curators see freshly created entities;
the knowledge variants do not.

## Where to go next

- [ApiKeys.md](ApiKeys.md): issue, scope and revoke keys.
- [Security.md](Security.md): the rest of the security model.
- [RagContextBundle.md](RagContextBundle.md): how `assemble_bundle` builds a bundle.
- [KgInclusionPolicy.md](KgInclusionPolicy.md): which pages the Knowledge Graph tools can see.
- [OntologyManagement.md](OntologyManagement.md): the ontology behind `get_ontology` and `sparql_query`.
