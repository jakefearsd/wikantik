---
name: wiki-context
description: Use when you need company/wiki context to ground a task — session briefings, follow-up retrieval, and escalation over the Wikantik knowledge MCP tools
---

## Overview

Grounds a task in the Wikantik knowledge base using its MCP tool surface —
`get_briefing`, `assemble_bundle`, `retrieve_context`, `read_pages`,
`traverse`, `query_nodes`, `sparql_query`, and related read tools on the
wiki's knowledge MCP server.

> **Portability (MCP-only):** every action in this skill goes through MCP
> server tools. Never fall back to a raw HTTP request, curl, or a shell
> command against the wiki to answer a question or fetch content — if the
> MCP server is unreachable, say so and stop, don't route around it. This is
> what makes the skill run identically regardless of which agent host is
> driving it.

## 1. Session start — call `get_briefing`

At the start of a new session or task, before doing any other work, call
`get_briefing` with:
- `pins`: the project's configured pinned pages (if any)
- `clusters`: the project's configured clusters (if any)
- `prompt`: the user's first request, verbatim
- `scope_mode`: `prefer` (default) to widen beyond pins/clusters when useful,
  or `strict` to stay confined to them

The response is injection-ready markdown starting `# Wiki context briefing`,
including a coverage line and an "Available on request" pointer list —
treat it as authoritative standing context for the rest of the session.
Call `get_briefing` **once per session**; do not re-call it on later turns
just because the topic shifts — use follow-up retrieval instead (below).

If a host-specific mechanism has already injected a briefing automatically
before this skill runs (a client-side hook or rules file), skip this step —
don't call `get_briefing` a second time for the same session.

## 2. Follow-ups — `assemble_bundle` + the coverage block

For any question during the session that the initial briefing doesn't
already answer, call `assemble_bundle` with the question as the query. Every
bundle carries a coverage block (`sectionCount`, `distinctPageCount`,
`topSimilarity`, `confidence`). Branch on `confidence`:

- **`strong`** — cite the returned sections and proceed.
- **`partial`** or **`weak`** — the bundle is thin or low-similarity; don't
  answer from it as-is. Escalate (step 3) before making any claim.
- **`unknown`** — treat the same as `partial`/`weak`: escalate rather than
  assume.

Never present a `partial`/`weak`/`unknown` bundle's contents as if it were a
confident answer.

## 3. Escalation ladder

When `assemble_bundle` alone doesn't clear the bar, escalate in this order —
stop as soon as one step gives you what you need:

1. **`retrieve_context`** — broader page/section discovery when
   `assemble_bundle` under-returned or you need to find *which* pages are
   relevant before reading them in full.
2. **`read_pages`** (≤20 slugs per call) — pull full page bodies once you
   know which pages matter, to read past what a section snippet showed.
3. **`traverse`** / **`query_nodes`** — when the question is really about
   entities and their relationships (a Knowledge Graph question, not a
   passage-retrieval one).
4. **`sparql_query`** — for counts, enumeration, or any question whose
   answer is "how many" / "list all" rather than "what does the wiki say
   about X". Passage retrieval is a poor tool for counting; the ontology
   query surface is built for it.

If every rung of the ladder still comes up empty, say the wiki doesn't have
grounding for the question — don't fill the gap from general knowledge and
present it as wiki-sourced.

## 4. Citation discipline

Every claim attributed to the wiki must be traceable to something you
actually retrieved in this session:

- Cite as `slug @ version` using the citation handle returned alongside the
  section/page content (bundles and page reads carry version-pinned
  handles) — never a bare page name with no version.
- Never claim "the wiki says X" (or imply grounding) without a section you
  retrieved in this conversation backing it up. A `get_briefing` pointer
  that was never actually fetched, or a low-confidence bundle you chose not
  to escalate on, does not count as grounding.
- If the user later asks a question the current session's retrieved
  material doesn't cover, retrieve again (step 2 or 3) rather than
  reasoning from what's already in context — content may have changed since
  the session started.
