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
- Assign a `cluster` identifier (kebab-case slug) for the cluster — reuse existing ID if extending
- **Output:** page name list, link graph, metadata template

### 3. GENERATE — Create all payloads
- Write every page's JSON payload to a file (`/tmp/mcp_<PageName>.json`)
- For **new pages**: use single-quoted heredocs (`<< 'ENDJSON'`) or Python `json.dump()`
- For **page updates**: use `patch_page` for surgical edits (see "Updating Existing Pages" below)
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
- `mcp_get_outbound_links` / `mcp_get_backlinks` to verify link graph integrity — these work correctly for both wiki-syntax and Markdown-style `[text](PageName)` links
- **Output:** verification report

### 6. DOCUMENT — Record what was done
- Append cluster details to `docs/research_history.md`
- For new clusters: record topic, all pages created, tools used, cross-links, lessons learned
- For single-article additions: a brief entry is sufficient (page name, what it adds, which pages were updated)

## EXTEND — Add articles to an existing cluster

This is the most common operation after initial cluster creation. The workflow:

1. **Create** the new article's JSON payload (heredoc for new page content) — include the cluster's existing `cluster` identifier in frontmatter
2. **Publish** the new article with `mcp_write_page`
3. **Update the hub page** with `patch_page` to insert a link to the new article (use `insert_after` with the nearest existing link as marker)
4. **Update related articles** with `patch_page` to inject cross-references — both within the cluster and in other existing pages
5. **Update metadata** `related:` lists with `update_metadata` using `append_to_list` action
6. **Verify** with `mcp_get_outbound_links`, `mcp_get_backlinks`, and `mcp_get_broken_links`

### Extending with `patch_page` (preferred)

Use `patch_page` to make surgical edits without reading the full page content:

```bash
cat << 'ENDJSON' > /tmp/mcp_patch_hub.json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "patch_page",
    "arguments": {
      "pageName": "MyHubPage",
      "operations": [
        {
          "action": "insert_after",
          "marker": "- [Existing Article](ExistingArticle)",
          "content": "- [New Article](NewArticle) — description of new article"
        }
      ],
      "expectedVersion": 1,
      "author": "claude-code-researcher",
      "changeNote": "Add NewArticle to cluster index"
    }
  }
}
ENDJSON
source .claude/skills/wiki-article-cluster/references/mcp-session-helper.sh && mcp_write_page /tmp/mcp_patch_hub.json
```

Available `patch_page` actions:
- `insert_after` — insert content after a marker string (ideal for adding links to a list)
- `insert_before` — insert content before a marker string
- `append_to_section` — append content at the end of a named section
- `replace_section` — replace a section's content (keeps the heading)

Use `batch_patch_pages` to patch multiple pages in a single call when updating cross-references across several related pages.

### Updating metadata with `update_metadata`

Use `update_metadata` for frontmatter-only changes (adding tags, updating `related` lists):

```bash
cat << 'ENDJSON' > /tmp/mcp_update_meta.json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "update_metadata",
    "arguments": {
      "pageName": "ExistingPage",
      "operations": [
        {"field": "related", "action": "append_to_list", "value": "NewArticle"},
        {"field": "tags", "action": "append_to_list", "value": "new-tag"}
      ],
      "author": "claude-code-researcher",
      "changeNote": "Add cross-reference to NewArticle"
    }
  }
}
ENDJSON
source .claude/skills/wiki-article-cluster/references/mcp-session-helper.sh && mcp_write_page /tmp/mcp_update_meta.json
```

Available `update_metadata` actions:
- `set` — overwrite a field value
- `append_to_list` — add to a list (idempotent — skips if already present)
- `remove_from_list` — remove from a list
- `delete` — remove a field entirely

## MCP Session Management

Source the helper script, then call `mcp_init` before any tool calls:

```bash
source .claude/skills/wiki-article-cluster/references/mcp-session-helper.sh
mcp_init
```

**Important:** Shell state does not persist between Claude Code Bash tool calls. You must `source` the helper script at the start of **every** Bash command that uses its functions:

```bash
# Every Bash call needs this prefix
source .claude/skills/wiki-article-cluster/references/mcp-session-helper.sh && mcp_write_page /tmp/mcp_MyPage.json
```

Sessions expire after inactivity. If a tool call returns an empty response or error, call `mcp_init` again to re-establish the session.

## Payload Construction Rules

### New pages — heredoc

For initial page creation, use file-based payloads with single-quoted heredocs:

```bash
cat << 'ENDJSON' > /tmp/mcp_MyPage.json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "write_page",
    "arguments": {
      "pageName": "MyPage",
      "content": "---\ntype: article\ncluster: my-cluster\ntags: [topic]\nrelated: [HubPage]\nstatus: active\nsummary: One-line description\n---\n# My Page\n\nBody content here.",
      "author": "claude-code-researcher",
      "changeNote": "Initial creation"
    }
  }
}
ENDJSON

source .claude/skills/wiki-article-cluster/references/mcp-session-helper.sh && mcp_write_page /tmp/mcp_MyPage.json
```

### Updating existing pages — `patch_page` (preferred)

For modifying existing pages (injecting links, adding sections), use `patch_page` instead of the old read-modify-write pattern. This is a single atomic operation — no need to read the page first, do string manipulation, and write it back.

See the EXTEND section above for examples.

### Full page rewrites — Python pattern (fallback)

For complex changes that `patch_page` cannot express (e.g., restructuring multiple sections simultaneously), fall back to Python:

```python
python3 << 'PYEOF'
import json

current_content = "..."  # from mcp_read_page output

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
            "changeNote": "Restructure sections",
            "expectedVersion": 1
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
| `cluster` | Cluster identifier (kebab-case)  | `retirement-planning`          |
| `status`  | Lifecycle state                  | `draft`, `active`, `archived`  |
| `summary` | One-line description             | `Overview of budgeting basics` |

All pages in a cluster must use the same metadata schema for queryability via `query_metadata`.

### Sub-Clusters

Sub-clusters are topical subdivisions within an existing cluster, implemented by **naming convention** rather than structural hierarchy. Use a `/` separator in the `cluster` field:

```
cluster: retirement-planning              # top-level cluster
cluster: retirement-planning/eu-retirement  # sub-cluster
```

**Rules:**
- The sub-cluster has its own hub page (`type: hub`) that links to its sub-articles and back to the parent cluster hub
- The parent cluster hub links to the sub-cluster hub (typically under a new heading or as a bullet in the relevant section)
- Sub-cluster articles use the full `parent/sub` identifier in their `cluster` field
- `query_metadata` with `field=cluster, value=retirement-planning/eu-retirement` returns only sub-cluster pages; a prefix search for `retirement-planning` returns both parent and sub-cluster pages
- The Main page links to the sub-cluster hub alongside its parent for discoverability

**Example:** The `retirement-planning/eu-retirement` sub-cluster:
- Hub: `EuRetirementSavingsGuide` (cluster: `retirement-planning/eu-retirement`, type: `hub`)
- Articles: `GermanRetirementSystem`, `EuRetirementTaxComparison` (cluster: `retirement-planning/eu-retirement`, type: `article`)
- Parent hub `RetirementPlanningGuide` links to `EuRetirementSavingsGuide` under an "International Perspective" heading

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Inline JSON in bash commands | Always use `curl -d @file` with file-based payloads |
| Double-quoted heredocs for payloads | Use single-quoted delimiters (`<< 'ENDJSON'`) to prevent expansion |
| Using full read-modify-write for small edits | Use `patch_page` for surgical changes (insert_after, append_to_section, etc.) |
| Using `write_page` to change only metadata | Use `update_metadata` instead — safer, no risk of corrupting body content |
| Forgetting to `source` helper per Bash call | Shell state resets — source at the start of every command |
| Session expiry mid-batch | Check session liveness before batch writes; call `mcp_init` on error |
| Skipping existing content survey | Always DISCOVER first — prevents duplication, enables linking |
| Inconsistent metadata across cluster | Define schema in PLAN phase, apply uniformly |
| Publishing sub-articles before hub | Publish hub first so backlinks resolve immediately |
| Updating pages without `expectedVersion` | Always use optimistic locking to prevent accidental overwrites |
| Forgetting WAR redeployment after code changes | New MCP tools require `cp JSPWiki.war` + Tomcat restart |

## Available MCP Tools

### Content tools
| Tool | Purpose | When to use |
|------|---------|-------------|
| `write_page` | Create or fully replace a page | New pages, full rewrites |
| `patch_page` | Surgical edits (insert, append, replace sections) | Adding links, extending content |
| `batch_write_pages` | Create multiple pages in one call | Initial cluster publishing |
| `batch_patch_pages` | Patch multiple pages in one call | Cross-reference updates across cluster |
| `update_metadata` | Modify frontmatter without touching body | Adding tags, updating `related` lists |

### Discovery tools
| Tool | Purpose |
|------|---------|
| `read_page` | Read page content and metadata |
| `search_pages` | Full-text search |
| `list_pages` | List page names with optional prefix filter |
| `query_metadata` | Find pages by frontmatter fields |
| `list_metadata_values` | Discover field names and values in use |

### Link graph tools
| Tool | Purpose |
|------|---------|
| `get_outbound_links` | Pages linked from a given page |
| `get_backlinks` | Pages linking to a given page |
| `get_broken_links` | All broken links across the wiki |
| `get_orphaned_pages` | Pages with no incoming links |
| `scan_markdown_links` | Classify links as local/external/anchor (richer than `get_outbound_links`) |

### Verification tools
| Tool | Purpose |
|------|---------|
| `get_wiki_stats` | Total pages, broken links, orphans, recent changes |
| `get_page_history` | Version history for a page |
| `diff_page` | Diff between two versions |

## Quick Reference

| Phase    | MCP Tools                                          | Output              |
|----------|----------------------------------------------------|----------------------|
| DISCOVER | `search_pages`, `read_page`, `list_metadata_values`| Content map          |
| PLAN     | (design work, no MCP calls)                        | Page list, link graph|
| GENERATE | (file creation, no MCP calls)                      | JSON payload files   |
| PUBLISH  | `write_page`, `batch_write_pages`                  | Page versions        |
| VERIFY   | `read_page`, `get_broken_links`, `get_outbound_links`, `get_backlinks` | Verification report |
| DOCUMENT | (append to research_history.md)                    | Updated history      |
| EXTEND   | `patch_page` / `batch_patch_pages`, `update_metadata` | Updated pages   |
