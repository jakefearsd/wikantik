---
name: wiki-article-cluster
description: Use when creating, publishing, or updating a cluster of wiki articles on a topic via the JSPWiki MCP API — covers content research, article planning, MCP publishing, and verification
---

## Overview

Full-cycle article cluster publishing: from topic research through MCP-powered publication to verified, interlinked wiki pages with consistent metadata. Also covers extending existing clusters with new articles.

## Workflow

Six phases for new clusters, plus an EXTEND workflow for adding articles to existing clusters:

### 1. DISCOVER — Survey existing content
- `mcp_search_pages` for topic keywords; `mcp_read_page` on top hits
- `list_metadata_values` to learn tag/type conventions already in use
- **Output:** existing content map, metadata conventions to follow

### 2. PLAN — Design the cluster
- Define hub page + sub-articles, CamelCase page names
- Design inter-page links (hub-to-sub, sub-to-sub, sub-to-existing)
- Define metadata schema: consistent `type`, `tags`, `related`, `status` across all pages
- **Output:** page name list, link graph, metadata template

### 3. GENERATE — Create all payloads
- Write every page's JSON payload to a file (`/tmp/mcp_<PageName>.json`)
- For **new pages**: use single-quoted heredocs (`<< 'ENDJSON'`) or Python `json.dump()`
- For **page updates**: use Python to read current content, make surgical changes, and generate payloads with `expectedVersion` (see "Updating Existing Pages" below)
- Hub page links to all sub-articles; each sub-article links back to hub
- **Output:** one JSON file per page, ready for `mcp_write_page`

### 4. PUBLISH — Send to wiki via MCP
- Initialize MCP session (source `mcp-session-helper.sh`, call `mcp_init`)
- Publish hub page first so sub-articles can link back to a real page
- Publish sub-articles individually with `mcp_write_page`
- Set `author` to a descriptive name (e.g. `claude-code-researcher`), not the default `MCP`
- **Output:** confirmed page versions

### 5. VERIFY — Check publication integrity
- `mcp_read_page` on every published page to confirm content exists
- `mcp_get_broken_links` to catch any missed references
- `mcp_get_stats` for overall health check
- `mcp_get_outbound_links` / `mcp_get_backlinks` — optional, but note these may return empty for Markdown-style links (JSPWiki's reference manager tracks WikiLink syntax, not `[text](page)` links)
- **Output:** verification report

### 6. DOCUMENT — Record what was done
- Append cluster details to `docs/research_history.md`
- For new clusters: record topic, all pages created, tools used, cross-links, lessons learned
- For single-article additions: a brief entry is sufficient (page name, what it adds, which pages were updated)

## EXTEND — Add articles to an existing cluster

This is the most common operation after initial cluster creation. The workflow:

1. **Create** the new article's JSON payload
2. **Publish** the new article with `mcp_write_page`
3. **Update the hub page** to add a link to the new article (use the Python update pattern with `expectedVersion`)
4. **Update related articles** to inject backlinks — both within the cluster and in other existing pages
5. **Update metadata** `related:` lists in affected pages
6. **Verify** with `mcp_read_page` and `mcp_get_broken_links`

## MCP Session Management

Source the helper script, then call `mcp_init` before any tool calls:

```bash
source docs/superpowers/skills/wiki-article-cluster/references/mcp-session-helper.sh
mcp_init
```

**Important:** Shell state does not persist between Claude Code Bash tool calls. You must `source` the helper script at the start of **every** Bash command that uses its functions:

```bash
# Every Bash call needs this prefix
source docs/superpowers/skills/wiki-article-cluster/references/mcp-session-helper.sh && mcp_write_page /tmp/mcp_MyPage.json
```

Sessions expire after inactivity. If a tool call returns an empty response or error, call `mcp_init` again to re-establish the session.

## Payload Construction Rules

### New pages — heredoc or Python

For initial page creation, use file-based payloads with single-quoted heredocs:

```bash
cat << 'ENDJSON' > /tmp/mcp_MyPage.json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "write_page",
    "arguments": {
      "pageName": "MyPage",
      "content": "---\ntype: article\ntags: [topic]\nrelated: [HubPage]\nstatus: active\nsummary: One-line description\n---\n# My Page\n\nBody content here.",
      "author": "claude-code-researcher",
      "changeNote": "Initial creation"
    }
  }
}
ENDJSON

source docs/superpowers/skills/wiki-article-cluster/references/mcp-session-helper.sh && mcp_write_page /tmp/mcp_MyPage.json
```

### Updating existing pages — Python pattern

For modifying existing pages (injecting links, adding sections, updating metadata), use Python. This handles JSON escaping correctly and supports surgical string replacement:

```python
python3 << 'PYEOF'
import json

# Current content (from mcp_read_page output or known state)
current_content = "..."  # the existing page content

# Make targeted changes
new_content = current_content.replace(
    "## See Also",
    "## New Section\n\nNew content here.\n\n## See Also"
)

payload = {
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": {
        "name": "write_page",
        "arguments": {
            "pageName": "ExistingPage",
            "content": new_content,
            "author": "claude-code-researcher",
            "changeNote": "Add new section with cross-references",
            "expectedVersion": 1  # optimistic locking — prevents accidental overwrites
        }
    }
}

with open("/tmp/mcp_update_ExistingPage.json", "w") as f:
    json.dump(payload, f)
print("Update payload created")
PYEOF
```

Then publish: `source .../mcp-session-helper.sh && mcp_write_page /tmp/mcp_update_ExistingPage.json`

## Metadata Conventions

Standard frontmatter for cluster articles:

| Field     | Purpose                          | Example                        |
|-----------|----------------------------------|--------------------------------|
| `type`    | Page classification              | `hub` (hub pages), `article` (sub-articles) |
| `tags`    | Topic tags (list)                | `[finance, budgeting]`         |
| `date`    | Publication date (ISO)           | `2026-03-14`                   |
| `related` | Linked CamelCase page names     | `[PersonalFinanceHub, Saving]` |
| `status`  | Lifecycle state                  | `draft`, `active`, `archived`  |
| `summary` | One-line description             | `Overview of budgeting basics` |

All pages in a cluster must use the same metadata schema for queryability via `query_metadata`.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Inline JSON in bash commands | Always use `curl -d @file` with file-based payloads |
| Double-quoted heredocs for payloads | Use single-quoted delimiters (`<< 'ENDJSON'`) to prevent expansion |
| Using heredocs for page updates | Use Python `json.dump()` for surgical content changes |
| Forgetting to `source` helper per Bash call | Shell state resets — source at the start of every command |
| Session expiry mid-batch | Check session liveness before batch writes; call `mcp_init` on error |
| Skipping existing content survey | Always DISCOVER first — prevents duplication, enables linking |
| Inconsistent metadata across cluster | Define schema in PLAN phase, apply uniformly |
| Publishing sub-articles before hub | Publish hub first so backlinks resolve immediately |
| Trusting `get_outbound_links` for verification | These don't work with Markdown links — use `read_page` + `get_broken_links` instead |
| Updating pages without `expectedVersion` | Always use optimistic locking to prevent accidental overwrites |
| Forgetting WAR redeployment after code changes | New MCP tools require `cp JSPWiki.war` + Tomcat restart |

## Quick Reference

| Phase    | MCP Tools                                          | Output              |
|----------|----------------------------------------------------|----------------------|
| DISCOVER | `mcp_search_pages`, `mcp_read_page`, `list_metadata_values`| Content map          |
| PLAN     | (design work, no MCP calls)                        | Page list, link graph|
| GENERATE | (file creation, no MCP calls)                      | JSON payload files   |
| PUBLISH  | `mcp_write_page`                                   | Page versions        |
| VERIFY   | `mcp_read_page`, `mcp_get_broken_links`, `mcp_get_stats` | Verification report |
| DOCUMENT | (append to research_history.md)                    | Updated history      |
| EXTEND   | `mcp_read_page` → Python update → `mcp_write_page` | Updated pages       |
