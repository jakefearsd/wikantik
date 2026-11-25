# MCP Agents and Tool Endpoints

This page is for administrators and developers who connect AI agents or other
programmatic clients to Wikantik. It covers the agent-facing endpoints, how a
client authenticates and opens a session, example client configuration, and the full
tool catalogue. **This is the one page that owns tool counts**; other docs link here
instead of repeating the numbers.

## Endpoints at a glance

| Endpoint | Protocol | Tools | Access | Minimum key scope |
|---|---|---|---|---|
| `/wikantik-admin-mcp` | MCP (Streamable HTTP) | 23 read and write tools | `McpAccessFilter` | `mcp_admin` |
| `/knowledge-mcp` | MCP (Streamable HTTP) | 16 read-only tools | `McpAccessFilter`, registered as `KnowledgeMcpAccessFilter` (required scope `mcp_read`) | `mcp_read` |
| `/work-mcp` | MCP (Streamable HTTP) | 16 work-management tools | API key only (see below) | `work` |
| `/tools/*` | OpenAPI 3.1 over plain HTTP | 2 | `ToolsAccessFilter` | `tools` |

Counts are as of **2.8.3**. The URL patterns come from `web.xml` (`/tools/*`) and the
MCP bootstrapper (`/wikantik-admin-mcp`, `/knowledge-mcp`).

### The counts depend on wiring

The numbers above are what a default deployment advertises; a deployment without an optional subsystem advertises fewer.

**`/wikantik-admin-mcp`** (registered in `McpToolRegistry`, verified against
`McpProtocolIT.EXPECTED_TOOLS`, which lists 23):

- 21 tools are always registered.
- `list_retrieval_queries` needs a datasource-backed query-log reader.
- `rename_cluster`, `list_content_opportunities` and `snooze_opportunity` are
  registered unconditionally, but refuse at call time with an explanation when the
  structural index or the content-intelligence subsystem is missing. They stay in
  `tools/list` so the advertised surface does not vary with wiring.

**`/knowledge-mcp`** (registered in `KnowledgeMcpInitializer.assembleTools`; the
endpoint is not mounted at all unless the context retrieval service or the structural
index exists):

| Tools | Registered only when |
|---|---|
| `retrieve_context`, `get_page`, `list_pages`, `list_metadata_values`, `read_pages` | the context retrieval service is present |
| `list_clusters`, `list_tags`, `list_pages_by_filter`, `get_page_by_id`, `get_page_neighbors` | the structural index is present |
| `get_page_for_agent` | the for-agent projection service is present |
| `assemble_bundle` | the bundle assembly service is present (retrieval wired) |
| `get_briefing` | the briefing service is present |
| `get_ontology`, `sparql_query` | the ontology model manager is present (`wikantik.ontology.enabled`) |
| `list_stale_citations` | the citation repository is present (`wikantik.citations.enabled` with a datasource) |

To see what your deployment really exposes, call `tools/list` (see
[Open a session with curl](#open-a-session-with-curl)). A default local server returns exactly the 16 names in the knowledge catalogue below.

### /work-mcp is key-only

`/work-mcp` exists only when `wikantik.work.enabled=true` (otherwise it is a 404). Unlike the other endpoints it **ignores** `mcp.access.allowUnrestricted` and `mcp.access.allowedCidrs`: a request without a valid `work` (or `all`) API key is refused with 403, whatever the network settings. It accepts two kinds of credential. An **agent token** (it starts with `wka_`, issued when the agent is registered at `/work/agents`) acts as that agent: on every tool call the agent is resolved again with its sponsor as they are now, so a retired agent, an agent nobody sponsors, or one whose sponsor's account is gone runs nothing, and an agent never does more than its sponsor could. A personal **`work` API key** acts as its owner with no administrator rights; it is never an agent. Neither is of any use on `/wikantik-admin-mcp`, `/knowledge-mcp` or `/tools/*`. See [WikantikOperations](WikantikOperations.md) section 1.15.

## Get a key

Both MCP endpoints and `/tools/*` accept a **bearer token** that is a database-minted
API key. Mint one at **Admin → API Keys** (`/admin/apikeys`); the plaintext is shown
once. [ApiKeys.md](ApiKeys.md) is the full reference for issuing, scoping and revoking
keys, including the self-service surface. Non-admins can self-mint only `tools`, `mcp_read` and `work`
keys; `mcp_admin` and `all` keys are issued by an administrator at `/admin/apikeys`. A work agent does not use an API key at all: it has its own token (it starts with `wka_`), issued when the agent is registered at `/work/agents` and never listed, rotated or revoked on the key pages. A key with the `work` scope is an ordinary personal key that acts as its owner.
The short version:

| You want an agent that… | Scope |
|---|---|
| reads and searches content over `/knowledge-mcp` only | `mcp_read` |
| curates content over `/wikantik-admin-mcp` (and can also use `/knowledge-mcp`) | `mcp_admin` |
| calls only the OpenAPI `/tools/*` endpoints | `tools` |
| works items as an agent or a person over `/work-mcp` and `/api/work/*` | `work` |
| reaches every key-protected surface | `all` |

`/wikantik-admin-mcp` is **fully privileged**. The principal bound to the key is used
only for attribution and the audit trail. Authorisation is the scope check in the access
filter, the admin tools perform no per-user permission checks, and pages are saved
through a request-less context, so any `mcp_admin` or `all` key can read and write every
non-system page. Treat `mcp_admin` keys as admin credentials.
The scope was named `mcp` before 2.4.54; keys stored under the old name still work and log a
WARN on each use (see [Legacy `mcp` scope name](ApiKeys.md#legacy-mcp-scope-name)).
A narrower `mcp_content` tier is planned
([GitHub issue #62](https://github.com/jakefearsd/wikantik/issues/62)) but not shipped.
`/knowledge-mcp` is different: the MCP transport carries no caller
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

Use an `mcp_read` key for the knowledge entry and an `mcp_admin` key for the admin entry.

An agent name must not be the name of a person known to the wiki's user directory: registering or renaming an agent to such a name is refused. Where a collision already exists, the person wins and keeps their notices and mail; agents are never notified.

To let a coding agent work claimable items, register it at `/work/agents` and give it its key:

```json
{
  "mcpServers": {
    "wikantik-work": {
      "type": "http",
      "url": "https://wiki.example.com/work-mcp",
      "headers": { "Authorization": "Bearer ${WIKANTIK_WORK_KEY}" }
    }
  }
}
```

Keep the admin entry out of any shared or checked-in config unless the key comes from
the environment.

## Use /tools/* from non-MCP clients

`/tools/*` is an OpenAPI 3.1 tool server for clients that do not speak MCP, such as
OpenWebUI. It exposes two tools over three HTTP routes, all gated by a `tools` (or `all`) key:

| Operation | Request | Purpose |
|---|---|---|
| `GET /tools/openapi.json` (also `GET /tools/`) | none | the OpenAPI document that describes the tools |
| `POST /tools/search_wiki` | JSON body `{"query": "...", "maxResults": 10}` (`query` required; `maxResults` 1-25, default 10) | full-text wiki search |
| `GET /tools/page/{name}` | none | fetch one page by name |

Point the client at `https://wiki.example.com/tools/openapi.json` and supply the key as
a bearer token.

## Tool catalogue

"Access" says what the tool can change: **read** tools never modify wiki content;
**write** tools do.

### /wikantik-admin-mcp (23 tools by default)

The authoritative per-tool descriptions are in
`wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`, which the server
returns as its instructions on `initialize`. Every write tool refuses system pages
(CSS themes, menu fragments, help pages, `Main`) unless the page is listed in
`wikantik.systemPages.mcpEditable` (default `About`).

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
| `list_retrieval_queries` | Real retrieval queries from the query log, ranked by frequency | read |
| `list_content_opportunities` | The ranked content-opportunity backlog; see [ContentIntelligence.md](ContentIntelligence.md) | read |
| `list_reader_engagement` | Per-page reader engagement (views, typical active time, scroll depth, landings by channel, returning share, web vitals) from the analytics rollups | read |
| `snooze_opportunity` | Decline an opportunity for 1-365 days with a required reason | write |
| `list_comment_threads` | Reader comment threads the commenter made available to agents, by page or cluster; each comment is marked `trusted` or not | read |
| `respond_to_comment_thread` | Reply to, resolve or dismiss a comment thread as an agent; `resolve` needs a trusted comment, `dismiss` needs a reason | write (comments only) |

### /knowledge-mcp (16 tools by default)

All 16 are read-only with respect to content. `assemble_bundle` and `get_briefing`
record query telemetry (`retrieval_query_log`, `briefing_log`), which does not change
wiki content. Tools that take the guest view gate described under
[Get a key](#get-a-key) do not return restricted pages: `assemble_bundle`, `get_briefing`,
`retrieve_context`, `get_page`, `read_pages`, `get_page_for_agent`, `get_page_by_id`,
`get_page_neighbors`, `list_pages`, `list_pages_by_filter`, `list_clusters` and
`list_metadata_values`. These take no gate: `list_tags`, `list_stale_citations`, `get_ontology` and
`sparql_query`; the last two read the ontology, which is already limited to publicly
viewable content. If the guest session cannot be built at startup, the gate degrades to
allow-all and logs an `ERROR`.

| Tool | Purpose |
|---|---|
| `assemble_bundle` | Primary answer-grounding tool: ranked, de-duplicated, version-pinned, cited section texts (no synthesized answer); returns a `coverage` block |
| `get_briefing` | Session-start context briefing as injection-ready markdown; call once at the start of a session |
| `retrieve_context` | Discover which pages and sections are relevant to a query |
| `get_page` | Fetch one page's frontmatter metadata and URL by name |
| `read_pages` | Batched raw-markdown read of up to 20 pages |
| `get_page_for_agent` | Token-budgeted page projection: summary, key facts, outline, verification state, and `draft_active` (true while someone is editing the page collaboratively or its shared draft holds unpublished changes; your write still lands and merges into the draft, but a human may publish over it soon) |
| `get_page_by_id` | Resolve a rename-stable `canonical_id` to the current page descriptor |
| `get_page_neighbors` | A page's 1-hop Page Graph neighborhood without bodies: wikilinks out and in, plus cluster siblings and hub |
| `list_pages` | Browse pages by metadata filters |
| `list_pages_by_filter` | List pages by structural filter (type, cluster, tags, freshness) from the Page Graph index; the `tags` filter also matches inline `#tags`, and descriptors carry `inlineTags` |
| `list_clusters` | Every cluster with its hub page, article count and last update |
| `list_tags` | The tag dictionary with page counts (frontmatter and inline `#tags`, by normalised form) |
| `list_metadata_values` | Distinct values of a frontmatter field with counts |
| `get_ontology` | The formal ontology T-Box (classes, properties, SKOS schemes) |
| `sparql_query` | Read-only SPARQL over the materialized ontology (10,000-row cap, 30 s timeout) |
| `list_stale_citations` | Citations whose status is `stale` or `target_missing` |

**Removed Knowledge Graph tools.** The Knowledge Graph (entity nodes, edges, proposals, curation and the `query_nodes`, `traverse`, `search_knowledge`, `find_similar` family) was deleted; neither server registers those tools. For content discovery use `retrieve_context` and `assemble_bundle`.

### /work-mcp (16 tools)

Registered in `WorkMcpInitializer.assembleTools` and verified against `WorkMcpIT.EXPECTED_TOOLS`; the descriptions an agent sees are in `wikantik-work-mcp/src/main/resources/wikantik-work-mcp-instructions.txt`. An agent sees only the projects it was admitted to. Item text is returned as Markdown source and is data, not instructions.

| Tool | Purpose | Access |
|---|---|---|
| `get_work_context` | Who the key acts as, the projects it can see (with each one's active sprint), its current claims and the claim lifetime | read |
| `list_claimable` | Items the agent could claim now (unclaimed, ready or review, in a queue it serves) | read |
| `get_item` | One item in full: body, status, claim, links, comments | read |
| `claim_item` | Claim an item, or renew the agent's own claim | write |
| `update_item` | Change title, body, status, priority, type, labels, estimate or due date (never who has the item or its sprint; an agent cannot move a draft forward) | write |
| `comment_on_item` | Add a comment | write |
| `handoff_item` | Give the item to a queue or a person with a status and a note; ends the agent's claim | write |
| `release_item` | Give up the agent's claim so someone else can take it | write |
| `create_item` | Create a draft item (with an optional type and labels) for a person to refine | write |
| `open_change` | Record a branch of work on an item (optionally `first_commit_at`, the time of its first commit); with a merged commit, record a change merged directly (needs a lead to have allowed direct commits) | write |
| `update_change` | A change's newest commit, review link or `first_commit_at`; ask for a review; abandon it | write |
| `review_change` | Approve a change at its current commit, or send it back. Never an agent that opened it or put a commit on it | write |
| `merge_change` | Report a change merged. Only the agent whose approval stands on the current commit, and only above the supervised level | write |
| `request_deployment` | Ask for a product version to be deployed, carrying the items it ships; optionally `remediates`, the number of the earlier failed (or verified, or rolled-back) deployment of the same product and environment that this one fixes | write |
| `get_deployment` | One deployment: its state, the carried items the caller can see, its evidence, and the deployment it remediates (if any) | read |
| `update_deployment` | Move a deployment on: approved (only where the product lets an agent), rejected (withdraw one not yet running), running, verified (evidence required), failed, rolled back | write |

`get_item` and `claim_item` also show the item's changes and its `effective` autonomy level (`supervised`, `delegated` or `autonomous`), which decides what an agent may do without a person: approve work as ready (autonomous only), merge what it reviewed and close the item (delegated and autonomous). An approval is for one commit, an agent never reviews or merges a change it opened or put a commit on, and an item cannot be done while one of its changes is open or in review. A change the code host recorded (see [WikantikOperations](WikantikOperations.md) section 1.19) also shows `first_commit_at`, `source`, `review_number`, `policy_flag`, `flag_acknowledged` and `superseded_by`; people with no wiki account appear as `<name> (outside the wiki)`, and the code host's events appear with actor `GitHub` and `actorKind: system`. A pull request the code host has already merged answers `change_closed:` to an agent's merge report.

Refusals come back as tool errors with a stable prefix (`denied:`, `stale:`, `not_claimable:`, `not_claimed:`, `archived:`, `open_changes:`, `not_approved:`, `not_in_review:`, `change_closed:`, `awaiting_deployment:`, `bad_transition:`, or the name of the field at fault); an unknown or hidden item is always `Not found.`

### Work attachments for agents

`get_item` lists an item's attachments (name, size, type and REST path), but no MCP tool carries file bytes: an agent uploads and downloads over REST with its own `wka_` token. The agent must be a member of the project, and the limits are in [WikantikOperations](WikantikOperations.md) section 1.18.

```bash
curl -H "Authorization: Bearer $WKA_TOKEN" -F "file=@screenshot.png" \
  https://wiki.example.com/api/work/items/PLAT-12/attachments     # 201 {..., "markdown": "![screenshot.png](work-attachment:7)"}
curl -H "Authorization: Bearer $WKA_TOKEN" -o out.png \
  https://wiki.example.com/api/work/items/PLAT-12/attachments/7
```

Put the returned `markdown` in the item's text or a comment to show it there.

## Where to go next

- [ApiKeys.md](ApiKeys.md): issue, scope and revoke keys.
- [Security.md](Security.md): the rest of the security model.
- [RagContextBundle.md](RagContextBundle.md): how `assemble_bundle` builds a bundle.
- [OntologyManagement.md](OntologyManagement.md): the ontology behind `get_ontology` and `sparql_query`.
