---
name: agent-activity-digest
description: Use only when the owner asks for the weekly agent activity digest. Reads one ISO week of agent field reports and jakemon's MCP call events, compares them with the skill registry and the previous digest, and publishes a wiki page AgentActivityDigest<YYYY>W<ww> that ends with candidate requests for the owner.
version: 1
---

# Agent activity digest

Produce the weekly picture of how agents used the wiki and the products' MCP tools, and hand the owner
a short list of things worth deciding. The contract is `SimpleAgilityAgentActivity` on the wiki; the
owner decides what becomes work (SA-23).

## Hard rules

- Run only when the owner asks. Never schedule or self-start.
- Never file work items, issues or change requests, and never change product code. The output is one wiki
  page plus one link on the hub.
- Never copy secrets, tokens, passwords, personal data or customer content from a report into the page.
  Quote the gist, not the raw text, when a report contains any of these.
- Use MCP tools only for wiki work (see the `wiki-content` skill). No curl against the wiki, except the
  read-only Loki query below, which is not the wiki.

## 1. Fix the week

Use the ISO week the owner names (default: the last complete one). The window is Monday 00:00Z to the
next Monday 00:00Z. The page name is `AgentActivityDigest<YYYY>W<ww>` with the ISO week-year and a
zero-padded week, e.g. `AgentActivityDigest2026W48`.

## 2. Gather

### Reports

Admin MCP tool `list_agent_reports` with `since` and `until` as ISO dates (Monday and the following
Monday), `limit` 500. Optional filters: `kind_of_work`, `product`, `client`, `skill`, `outcome`. The
result is `{reports: [...], count}` with every report field. If `count` equals the limit, page by
narrowing the date range and say so in the digest.

### Events

jakemon's Loki HTTP API at `http://192.168.5.52:3100` (docker2; reachable on the LAN only). Event fields
are JSON; read them with `| json` (dots become underscores: `event_action`, `event_outcome`,
`mcp_server`, `mcp_tool`, `mcp_client_name`, `mcp_session_hash`, `event_duration`, `mcp_response_bytes`).
Stream labels: `service`, `service_instance` (Wikantik prod is `wiki-prod`; Pressroom is
`pressroom-prod`), `service_name`, `host`, `container`, `deployment`, `job`.

Calls by product, endpoint, tool and outcome over the week (set `time` to the window end):

```bash
curl -sG http://192.168.5.52:3100/loki/api/v1/query \
  --data-urlencode 'query=sum by (service_instance, mcp_server, mcp_tool, event_outcome) (count_over_time({service_instance=~".+"} | json | event_action="mcp_tool_called" [7d]))' \
  --data-urlencode 'time=2026-12-07T00:00:00Z'
```

Distinct sessions for one day (run once per day of the week, `time` = that day's end):

```
count(sum by (service_instance, mcp_session_hash) (count_over_time({service_instance=~".+"} | json | event_action="mcp_tool_called" [1d])))
```

Client mix over the week:

```
sum by (service_instance, mcp_client_name) (count_over_time({service_instance=~".+"} | json | event_action="mcp_tool_called" [7d]))
``` If Loki is unreachable or returns nothing, say so in the digest under the MCP usage
heading and continue from the reports alone. Do not invent counts.

### Registry and previous digest

- Read wiki page `SimpleAgilitySkillRegistry` (knowledge MCP `read_pages`): the skills, their versions and
  what changed when.
- `list_pages_by_filter` with `cluster=agent-activity`; the newest `AgentActivityDigest*` page is the
  previous digest. Read it for the comparison. If none exists, say this is the first digest.

### Subject area

Subject area is derived, not reported. For each wiki ref in `context_used`, read its frontmatter
(knowledge MCP `get_page` or `read_pages`) and map it to its cluster. Refs that are not wiki pages (repo,
web, blog) count as "other sources".

## 3. Write the page

Write it with admin MCP `write_pages`, following the `wiki-content` skill: `type: article`,
`cluster: agent-activity`, tags, a 50 to 160 character `summary`, CamelCase `related` links. Use specific
headings, never "Overview" or "Summary". Sections, in this order:

1. **Volume and mix** by kind of work, by product and by subject area (counts, with outcomes).
2. **Context that helped**, with page links, ranked by how many reports said `helped: yes`.
3. **Context missing or wrong**: group the `context_missing` texts; name pages that agents wished existed.
4. **Friction by product and tool**, blocking items first, with counts and report links where possible.
5. **Skill changes this period** from the registry, with the before and after on the kinds of work they
   affect (compare against the previous digest).
6. **Skill version mismatches**: reports naming a `name@version` the registry does not list.
7. **Change since the previous digest**: what rose, fell, appeared or was resolved.
8. **MCP usage from events**: total calls, failure rate, top tools, sessions per day, client mix, and
   tools never called (list each endpoint's registered tools from its `tools/list` if available;
   otherwise say that list was not available).
9. **Candidate requests**: for the owner to accept, reshape or drop. Each has product, problem, evidence
   (links to pages or the figures above) and a suggested change. They are suggestions only: do not file
   them anywhere.

## 4. Link it

Read `AgentActivityHub` (`read_page`) and add a link to the new page under "Digests" with `update_page`
and `expectedContentHash`. Keep the existing order convention on that list.

## 5. Report on this run

This is substantive work: at the end, file a field report with the `agent-field-report` skill.
