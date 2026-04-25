# Agent-Grade Content — Phase 6 Implementation Plan

> **Status:** Prospective. Phase 6 is the final phase of the
> [Agent-Grade Content design](../../wikantik-pages/AgentGradeContentDesign.md).

**Goal:** Add at least one worked input/output example to every MCP tool's
JSON schema across the three agent-facing endpoints (`/wikantik-admin-mcp`,
`/knowledge-mcp`, `/tools/*`). Worked examples raise agent first-call
success rates dramatically over type-only schemas. This is pure tool
description authoring — no new tools, no refactors, no semantic changes.

**Design source:** `AgentGradeContentDesign.md` line ~392
("Tool-description upgrade") with the canonical `search_knowledge` example
at line 398–441.

## SDK constraint discovered

`io.modelcontextprotocol.spec.McpSchema$JsonSchema` is a Java record with
six fixed fields: `(type, properties, required, additionalProperties,
defs, definitions)`. There is no any-setter / any-getter, so a top-level
`examples` key on the input schema **cannot** be added by extending the
`JsonSchema` record — Jackson serialization drops anything not declared
on the record.

`McpSchema$Tool.outputSchema`, however, is a free `Map<String, Object>`,
so a top-level `examples` array there serializes cleanly.

**Strategy:**
- **Inputs:** add `"examples"` on each individual property inside the
  `properties` map (per-property examples are valid JSON Schema and
  survive serialization). For tools with multiple arguments, the example
  values across properties form a coherent example call.
- **Outputs:** populate `outputSchema` (currently unused everywhere) as a
  free Map containing a top-level `examples` array. This is where the
  realistic shape lives — agents see one or two concrete payloads.

Net effect on the wire JSON matches the design doc spec for the
`search_knowledge` exemplar.

## What ships

| Module | Tools updated | Notes |
|--------|---------------|-------|
| `wikantik-admin-mcp` | 18 (16 always-on + 2 KG-conditional) | `get_backlinks`, `get_page_history`, `diff_page`, `get_outbound_links`, `get_broken_links`, `get_orphaned_pages`, `get_wiki_stats`, `verify_pages`, `preview_structured_data`, `ping_search_engines`, `delete_pages`, `read_page`, `rename_page`, `write_pages`, `update_page`, `mark_page_verified`, `list_proposals`, `propose_knowledge` |
| `wikantik-knowledge` | 16 | `discover_schema`, `query_nodes`, `get_node`, `traverse`, `search_knowledge`, `find_similar`, `retrieve_context`, `get_page`, `list_pages`, `list_metadata_values`, `list_clusters`, `list_tags`, `list_pages_by_filter`, `get_page_by_id`, `traverse_relations`, `get_page_for_agent` |
| `wikantik-tools` | 2 | `search_wiki`, `get_page` |

Total: **36** tool definitions touched.

## Task list

| # | Task |
|---|------|
| P6-T1 | Discover SDK shape, draft per-property + outputSchema pattern, prove with one tool (`get_backlinks`) and a unit-test assertion |
| P6-T2 | Apply pattern to all 18 admin MCP tools — one commit |
| P6-T3 | Apply pattern to all 16 knowledge MCP tools — one commit |
| P6-T4 | Apply pattern to the 2 tool-server tools (`wikantik-tools`) — one commit |
| P6-T5 | Full build + smoke verification (dump a serialised tool definition in a test) |
| P6-T6 | Convert plan to retrospective, update CLAUDE.md, update memory |

## Self-review

- The design doc shows `examples` as a top-level array on `inputSchema`.
  We deviate: top-level on `outputSchema` (free Map) and per-property on
  `inputSchema` (record-bound). Equivalent agent UX, dictated by the SDK.
- Per-property examples in inputSchema are standard JSON Schema and
  understood by every spec-compliant client.
- We don't touch tool execution logic, argument parsing, descriptions, or
  required lists. Adding `examples` is additive only.
- No new tests beyond a couple of definition-level assertions to lock the
  example presence; semantic behaviour is unchanged.
- Tool-count assertions in registry tests aren't affected — counts
  unchanged.
