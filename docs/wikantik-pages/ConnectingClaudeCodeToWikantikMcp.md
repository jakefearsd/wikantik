---
title: Connecting Claude Code to the Wikantik MCP Servers
cluster: agent-cookbook
date: '2026-06-10'
status: active
type: runbook
canonical_id: 01KTSK63EQRECV1AA4T94XHCH5
audience:
- agents
- humans
runbook:
  when_to_use:
  - You want an AI agent (Claude Code) to read, search, and reason over this wiki
  - You want an agent to curate the wiki — write pages or edit the Knowledge Graph
  - You are bootstrapping an agent's first connection (a human step — see the catch-22
    below)
  inputs:
  - 'An API key: `mcp_read` for read-only (self-service under /preferences, or admin-issued)
    or `mcp_admin` for write (admin-issued at /admin/apikeys); shown once, so treat
    it like a password'
  - The MCP server URL (read-only and/or admin)
  - Claude Code installed and on your PATH
  steps:
  - 'Issue an API key: a read-only `mcp_read` key under /preferences (any signed-in
    user) or at /admin/apikeys; a write-capable `mcp_admin` key at /admin/apikeys
    (administrators only)'
  - Decide read-only (knowledge-mcp) vs write (wikantik-admin-mcp) — default to read-only
  - Add the server with `claude mcp add --transport http` (user scope), OR commit
    a `.mcp.json` that references the key via `${WIKANTIK_MCP_KEY}`
  - Verify with `claude mcp list` (expect `✓ Connected`) or `/mcp` inside a session
  - Ask the agent something that needs the wiki and confirm it calls the tools
  pitfalls:
  - Never hardcode or commit the API key — use user scope or a `${WIKANTIK_MCP_KEY}`
    env var
  - 'Running `claude mcp add` against a project `.mcp.json` rewrites existing `${VAR}`
    placeholders to the literal key value (bug #18692) — restore with `git checkout
    -- .mcp.json` afterward'
  - The transport type for these Streamable-HTTP servers is `http`, not `sse` or `streamable-http`
  - Only add the admin server if the agent genuinely needs to write — read-only is
    the safe default
  - 'Match the key scope to the server: an `mcp_read` key works on knowledge-mcp only;
    wikantik-admin-mcp answers 403 to it'
  - The catch-22 — an agent can't fetch this page until it is already connected, so
    the first setup is a human task
  related_tools:
  - /knowledge-mcp/search_knowledge
  - /knowledge-mcp/get_page_for_agent
  - /wikantik-admin-mcp/update_page
summary: How to wire a Claude Code instance to the production Wikantik MCP servers
  — which URLs and API key go in which file, securely, for read-only or write access.
tags:
- mcp
- claude-code
- agents
- setup
- runbook
---
# Connecting Claude Code to the Wikantik MCP Servers

Wikantik exposes two [Model Context Protocol](https://wiki.wikantik.com/wiki/GoodMcpDesign)
servers so an AI agent can use the wiki directly — searching, traversing the
Knowledge Graph, reading token-budgeted page projections, and (optionally)
curating content. This page is the setup: **which URL and which key go in which
file**, securely, so a [Claude Code](https://code.claude.com/docs/en/mcp) instance
can read — or write — this wiki.

> **The catch-22.** This article explains how to connect an agent to the wiki, but
> an agent can't read it until it's already connected. So the *first* connection is
> a human task: read this directly, wire up Claude Code, and from then on your agent
> can search, read, and cite this very page — and everything else here — on its own.

## The two servers

| Server | URL | What it's for | Tools | Key scope |
|---|---|---|---|---|
| **Knowledge MCP** (read-only) | `https://wiki.wikantik.com/knowledge-mcp` | Retrieval: the `assemble_bundle` context bundle, hybrid search, Knowledge-Graph traversal, structural-spine navigation, the `get_page_for_agent` projection, `sparql_query`, `get_ontology` | 21, read-only | `mcp_read` (or higher) |
| **Admin MCP** (write / curate) | `https://wiki.wikantik.com/wikantik-admin-mcp` | Curation: page writes, KG node/edge curation, proposals, verification stamping, bulk cluster rename | 29 | `mcp_admin` (or `all`) |

Both servers register some tools only when the subsystem behind them is wired
(for example the Knowledge Graph tools), so 21 and 29 are the fully-wired counts —
a leaner install advertising fewer is not an error. Call `tools/list` (or run `/mcp`
in Claude Code) to see what your deployment really exposes.

Most people want the **read-only Knowledge MCP** for a coding agent — it can read
and reason but can never change anything. Add the **Admin MCP** only if you want the
agent to *maintain* the wiki (write pages, curate the graph).

Both servers authenticate the same way: an `Authorization: Bearer <API_KEY>` header.
Keys look like `wkk_...`, and the key's **scope** decides which server it can reach:

| Scope | Knowledge MCP | Admin MCP |
|---|---|---|
| `mcp_read` | yes (read-only) | no |
| `mcp_admin` | yes | yes (fully privileged) |
| `all` | yes | yes |
| `tools` | no | no (it is for the `/tools/*` OpenAPI endpoints) |

Two things to know about what a key can see and do:

- The Knowledge MCP checks page view ACLs as an anonymous guest, whichever account the key belongs to, so it returns only publicly viewable pages. Restricted pages are reachable only through the Admin MCP.
- The Admin MCP performs no per-user permission checks. An `mcp_admin` or `all` key can read and write every non-system page and curate the Knowledge Graph, so treat it as an admin credential.

## Step 1 — Issue an API key

- **Read-only (`mcp_read`).** Any signed-in user can mint one under **Preferences → API Keys** (`/preferences`).
- **Write (`mcp_admin`, or `all`).** An administrator issues it at **`/admin/apikeys`** (Admin → API Keys → **+ Generate Key**; pick the principal login, a label and the scope). Non-administrators cannot mint these scopes.

Pick the narrowest scope that does the job. **Copy the key — it is shown only once**,
and treat it like a password. The older scope name `mcp` is still accepted as an alias
for `mcp_admin`, but use `mcp_admin` for new keys.

## Step 2 — Decide where the key lives (the part that matters)

**Never commit the key to version control.** Pick one of two safe patterns:

- **Personal / quickest — user scope.** Add the server at *user* scope; the key is
  stored in `~/.claude.json` (not version-controlled) and is available in every
  project. Best when the key is just yours.
- **Shareable with a team — `.mcp.json` + env var.** Commit a project `.mcp.json`,
  but reference the key through an environment variable (`${WIKANTIK_MCP_KEY}`). The
  config is shared; the secret is not — each teammate sets their own env var.

## Step 3a — Quick path (CLI, user scope)

```bash
claude mcp add \
  --transport http \
  --header "Authorization: Bearer YOUR_KEY_HERE" \
  --scope user \
  wikantik-knowledge \
  https://wiki.wikantik.com/knowledge-mcp
```

To also give the agent write access, repeat with the admin URL and a distinct name
(this needs an `mcp_admin` or `all` key; an `mcp_read` key is refused by this server):

```bash
claude mcp add \
  --transport http \
  --header "Authorization: Bearer YOUR_KEY_HERE" \
  --scope user \
  wikantik-admin \
  https://wiki.wikantik.com/wikantik-admin-mcp
```

## Step 3b — Shareable path (`.mcp.json` + env var)

Create **`.mcp.json`** at your project root. Note `"type": "http"` (Streamable HTTP)
and the env-var placeholder for the key:

```json
{
  "mcpServers": {
    "wikantik-knowledge": {
      "type": "http",
      "url": "https://wiki.wikantik.com/knowledge-mcp",
      "headers": { "Authorization": "Bearer ${WIKANTIK_MCP_KEY}" }
    }
  }
}
```

Put the actual key in your shell environment — e.g. in `~/.zshrc`/`~/.bashrc`, or in
a `.gitignore`d file you `source`:

```bash
export WIKANTIK_MCP_KEY="your_key_here"
```

Then start Claude Code (`claude`) from that shell. Commit `.mcp.json`; **do not**
commit the key.

> **Gotcha:** running `claude mcp add` against a project that already has a
> `.mcp.json` will resolve your `${WIKANTIK_MCP_KEY}` placeholder into the literal
> key value (a [known bug](https://github.com/anthropics/claude-code/issues/18692)).
> If that happens, restore the placeholder: `git checkout -- .mcp.json`.

## Step 4 — Verify

```bash
claude mcp list            # expect:  wikantik-knowledge   ✓ Connected
claude mcp get wikantik-knowledge
```

Or, inside a Claude Code session, run the `/mcp` command to see each server, its
status, and the tools it exposes. A `! Needs authentication` or `✗ Failed to
connect` means the key or URL is wrong. On the wire, HTTP 403 means the key is
missing, revoked or has too low a scope for that server; 429 means you were
rate-limited; 503 means the server has no API-key service (no datasource), no CIDR
allowlist and is not set `allowUnrestricted`, so it rejects everything (it fails closed).

## Step 5 — Use it

Now just ask, and the agent reaches the wiki for you:

- *"Search the wiki for our deploy runbook and summarize the steps."* → `assemble_bundle` → `get_page_for_agent`
- *"What does the wiki say implements Raft consensus?"* → Knowledge-Graph traversal / `sparql_query`
- *(with the admin server)* *"Update the summary on the CockroachDB page."* → `update_page`

## Pitfalls

- **Don't hardcode or commit the key.** Use user scope, or the `${WIKANTIK_MCP_KEY}` env var.
- **`claude mcp add` can rewrite `${VAR}` to the literal key** in a project `.mcp.json` (bug #18692) — restore with `git checkout -- .mcp.json`.
- **Transport type is `http`** for these Streamable-HTTP servers — not `sse` or `streamable-http`.
- **Default to read-only.** Add the Admin MCP only when the agent must write.
- **Match the scope to the server.** An `mcp_read` key works on the Knowledge MCP only; the Admin MCP answers 403 to it.
- **The catch-22:** the first connection is a human task — an agent can't fetch this page until it's wired up.

## Related

- [Finding the right MCP tool](https://wiki.wikantik.com/wiki/FindingTheRightMcpTool) — picking the right tool once you're connected
- [Good MCP design](https://wiki.wikantik.com/wiki/GoodMcpDesign) — the principles these servers follow
