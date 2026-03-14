# Research History: Conflicts & Equity Markets Article Cluster

This document records every action taken to research, write, and publish
a cluster of wiki articles about the historical impact of large-scale
international conflicts on worldwide equity markets, using the JSPWiki
MCP API.

## Environment

- **Wiki**: JSPWiki running on local Tomcat 11 at http://localhost:8080/
- **MCP endpoint**: http://localhost:8080/mcp (Streamable HTTP transport)
- **Protocol**: MCP JSON-RPC over HTTP POST
- **Date**: 2026-03-14

---

## Action Log

### 1. Verify MCP server is operational

**Action**: Send MCP `initialize` request to confirm the server identity and capabilities.
**Tool**: `curl` → MCP `initialize` JSON-RPC
**Headers required**: `Content-Type: application/json`, `Accept: text/event-stream, application/json`
**Result**: Server responded with `serverInfo.name: "JSPWiki MCP Server"`, protocol version `2025-03-26`. Capabilities confirmed: tools, resources, prompts.
**Session ID captured**: Stored in `/tmp/mcp_session_id` for subsequent requests.

**Lesson learned**: The `Accept` header must include both `text/event-stream` and `application/json` — the server rejects requests missing either. All responses use SSE format (`id:`, `event:`, `data:` lines).

### 2. Send `notifications/initialized` handshake

**Action**: Complete the MCP handshake so the server accepts tool calls.
**Tool**: `curl` → MCP `notifications/initialized` JSON-RPC
**Result**: No response body (expected for notifications). Server now ready for tool calls.

### 3. Plan article cluster structure

**Action**: Design the article hierarchy before writing any content.
**Tool**: None (planning step)
**Structure decided**:
- **Root page**: `ConflictsAndEquityMarkets` — hub page with article index and key themes
- **6 conflict case studies**: `WorldWarOneMarkets`, `WorldWarTwoMarkets`, `ColdWarMarkets`, `GulfWarMarkets`, `WarOnTerrorMarkets`, `RussiaUkraineWarMarkets`
- **1 cross-cutting analysis**: `ConflictMarketPatterns`
- **Total**: 8 pages (1 root + 7 sub-articles)
- **Linking**: Each sub-article links back to root; root links to all sub-articles; Main page links to root

### 4. Write root page — ConflictsAndEquityMarkets

**Action**: Create the hub page with article index, key themes, and metadata.
**Tool**: `curl` → MCP `tools/call` → `write_page`
**Arguments**:
- `pageName`: `ConflictsAndEquityMarkets`
- `content`: Markdown body with introduction, article index (links to all 7 sub-articles), and 5 key themes
- `metadata`: `{status: "active", date: "2026-03-14", type: "reference", tags: ["finance", "geopolitics", "equity-markets", "conflict", "history"], related: [all 7 sub-article names], summary: "Hub page..."}`
- `author`: `claude-code-researcher`
- `changeNote`: `Initial hub page for conflicts and equity markets article cluster`
**Result**: `{success: true, pageName: "ConflictsAndEquityMarkets", version: 1}`

### 5. Attempt batch write of first 3 sub-articles (FAILED)

**Action**: Use `batch_write_pages` to write WWI, WWII, and Cold War articles in a single call.
**Tool**: `curl` → MCP `tools/call` → `batch_write_pages`
**Result**: **FAILED** — Bash syntax error. The JSON payload contained parentheses (e.g., `(1947–1991)`) in article content that bash interpreted as subshell syntax when the JSON was inline in the curl command.
**Error**: `syntax error near unexpected token (`

**Lesson learned**: Never embed large JSON payloads with arbitrary text content directly in bash command strings. Parentheses, backticks, dollar signs, and other shell metacharacters in article content will be interpreted by bash.

### 6. Switch to file-based payload approach

**Action**: Write each article's JSON payload to a temporary file, then use `curl -d @/tmp/file.json` to send it, completely avoiding bash interpretation of the content.
**Tool**: Bash heredoc with `<< 'ENDJSON'` (single-quoted delimiter prevents variable expansion) to write files, then `curl -d @file`
**Files created**: `/tmp/mcp_wwi.json`, `/tmp/mcp_wwii.json`, `/tmp/mcp_coldwar.json`, `/tmp/mcp_gulfwar.json`, `/tmp/mcp_wot.json`, `/tmp/mcp_ukraine.json`, `/tmp/mcp_patterns.json`

**Lesson learned**: The file-based approach (`curl -d @file`) is the correct pattern for MCP API calls with complex content. Single-quoted heredoc delimiters (`<< 'ENDJSON'`) prevent bash from interpreting any content within the payload.

### 7. MCP session expired — re-initialize

**Action**: First attempt to send file-based payloads returned empty responses. Re-initialized MCP session.
**Tool**: `curl` → MCP `initialize` + `notifications/initialized`
**Result**: New session ID obtained. Verified with a `read_page` call for `Main`.

**Lesson learned**: MCP sessions expire after inactivity. Always verify the session is live before a batch of writes, and be prepared to re-initialize.

### 8. Write WorldWarOneMarkets

**Action**: Send WWI article via file-based payload.
**Tool**: `curl -d @/tmp/mcp_wwi.json` → MCP `tools/call` → `write_page`
**Content summary**: Pre-war financial system, July-August 1914 crisis and global exchange closures, wartime market performance by country (US, UK, Germany, neutrals), lasting financial consequences (gold standard collapse, financial power shift to New York), lessons for market behaviour.
**Metadata**: `{type: "article", tags: ["finance", "world-war-one", "equity-markets", "history", "gold-standard"]}`
**Result**: `{success: true, pageName: "WorldWarOneMarkets", version: 1}`

### 9. Write WorldWarTwoMarkets

**Action**: Send WWII article via file-based payload.
**Tool**: `curl -d @/tmp/mcp_wwii.json` → MCP `tools/call` → `write_page`
**Content summary**: Gradual approach of war (1937-1939), exchange operations during war (most stayed open unlike WWI), market performance by region (US 130% rally from 1942 low, UK doubled, Axis markets destroyed), post-war settlement (Bretton Woods, Marshall Plan), great post-war bull market (900% Dow gain 1942-1966), comparison table with WWI.
**Metadata**: `{type: "article", tags: ["finance", "world-war-two", "equity-markets", "history", "bretton-woods"]}`
**Result**: `{success: true, pageName: "WorldWarTwoMarkets", version: 1}`

### 10. Write ColdWarMarkets

**Action**: Send Cold War article via file-based payload.
**Tool**: `curl -d @/tmp/mcp_coldwar.json` → MCP `tools/call` → `write_page`
**Content summary**: Bipolar financial world, key market episodes (Korean War, Suez Crisis, Cuban Missile Crisis, Vietnam War, oil crises, Soviet-Afghan War), defence sector premium, peace dividend (1989-1991), legacy for modern markets.
**Metadata**: `{type: "article", tags: ["finance", "cold-war", "equity-markets", "history", "defence-sector", "oil-crisis"]}`
**Result**: `{success: true, pageName: "ColdWarMarkets", version: 1}`

### 11. Write GulfWarMarkets

**Action**: Send Gulf War article via file-based payload.
**Tool**: `curl -d @/tmp/mcp_gulfwar.json` → MCP `tools/call` → `write_page`
**Content summary**: Iraqi invasion of Kuwait (oil doubled, S&P fell 20%), 5-month build-up period, Operation Desert Storm market turn (Dow surged 4.6%, oil fell 33%), sector performance table, CNN effect, post-war market environment, lessons (uncertainty > conflict, oil transmission, short wars bullish).
**Metadata**: `{type: "article", tags: ["finance", "gulf-war", "equity-markets", "history", "oil-prices", "buy-the-invasion"]}`
**Result**: `{success: true, pageName: "GulfWarMarkets", version: 1}`

### 12. Write WarOnTerrorMarkets

**Action**: Send War on Terror article via file-based payload.
**Tool**: `curl -d @/tmp/mcp_wot.json` → MCP `tools/call` → `write_page`
**Content summary**: 9/11 market shock (NYSE closed 4 days, 14.3% Dow loss first week), sector impacts (airlines -42%, defence rallied), Afghanistan war (prolonged $2.3T cost), Iraq war (Gulf War pattern repeated, prolonged occupation), defence sector transformation (spending doubled), broader economic consequences ($8T cumulative cost).
**Metadata**: `{type: "article", tags: ["finance", "war-on-terror", "911", "equity-markets", "history", "defence-sector"]}`
**Result**: `{success: true, pageName: "WarOnTerrorMarkets", version: 1}`

### 13. Write RussiaUkraineWarMarkets

**Action**: Send Russia-Ukraine war article via file-based payload.
**Tool**: `curl -d @/tmp/mcp_ukraine.json` → MCP `tools/call` → `write_page`
**Content summary**: Pre-invasion build-up, invasion shock (differentiated by region), commodity market chaos (oil $130, gas +400%, nickel trading suspended), energy weaponisation (Nord Stream), sanctions regime (SWIFT, $300B frozen reserves, 1000+ corporate exits), European defence rearmament (Rheinmetall +200%), market adaptation (inflation transmission, supply chain reconfiguration, EM reassessment).
**Metadata**: `{type: "article", tags: ["finance", "russia-ukraine-war", "equity-markets", "sanctions", "energy-crisis", "defence-rearmament"]}`
**Result**: `{success: true, pageName: "RussiaUkraineWarMarkets", version: 1}`

### 14. Write ConflictMarketPatterns

**Action**: Send cross-cutting patterns article via file-based payload.
**Tool**: `curl -d @/tmp/mcp_patterns.json` → MCP `tools/call` → `write_page`
**Content summary**: 6 patterns identified across all conflicts: (1) uncertainty premium with data table, (2) predictable sector rotation (winners: defence/energy/commodities, losers: airlines/discretionary/financials), (3) recovery speed (median 3-6 months) with data table, (4) government response matters more, (5) globalisation amplifies then fragments, (6) each war reshapes market structure. Practical implications for investors.
**Metadata**: `{type: "article", tags: ["finance", "equity-markets", "conflict", "patterns", "sector-rotation", "investment-lessons"]}`
**Result**: `{success: true, pageName: "ConflictMarketPatterns", version: 1}`

### 15. Update Main page with article cluster link

**Action**: Read current Main page, then update it to add the Conflicts and Equity Markets cluster to the Featured Research section.
**Tool**: `curl` → MCP `tools/call` → `read_page` (Main), then `write_page` (Main)
**Changes**: Added "Conflicts and Global Equity Markets (March 2026)" subsection under "Featured Research" with a link to `ConflictsAndEquityMarkets` hub page. Reorganised existing Russia-Ukraine War section as a second subsection.
**Optimistic locking**: Used `expectedVersion: 1` to ensure no concurrent edits.
**Result**: `{success: true, pageName: "Main", version: 1}`

---

## Summary

| # | Action | MCP Tool | Page | Result |
|---|--------|----------|------|--------|
| 1 | Initialize session | `initialize` | — | Session established |
| 2 | Complete handshake | `notifications/initialized` | — | Ready |
| 3 | Plan structure | — | — | 8 pages designed |
| 4 | Write root page | `write_page` | ConflictsAndEquityMarkets | v1 created |
| 5 | Batch write (failed) | `batch_write_pages` | WWI/WWII/ColdWar | Bash escaping error |
| 6 | Switch to file payloads | — | — | Pattern established |
| 7 | Re-initialize session | `initialize` | — | New session |
| 8 | Write WWI article | `write_page` | WorldWarOneMarkets | v1 created |
| 9 | Write WWII article | `write_page` | WorldWarTwoMarkets | v1 created |
| 10 | Write Cold War article | `write_page` | ColdWarMarkets | v1 created |
| 11 | Write Gulf War article | `write_page` | GulfWarMarkets | v1 created |
| 12 | Write War on Terror article | `write_page` | WarOnTerrorMarkets | v1 created |
| 13 | Write Russia-Ukraine article | `write_page` | RussiaUkraineWarMarkets | v1 created |
| 14 | Write Patterns article | `write_page` | ConflictMarketPatterns | v1 created |
| 15 | Update Main page | `write_page` | Main | v1 updated |

## Key Lessons for MCP Skill Development

1. **Always use file-based payloads** (`curl -d @file`) for `write_page` and `batch_write_pages` calls — never inline JSON with article content in bash commands.
2. **Single-quoted heredoc delimiters** (`<< 'EOF'`) prevent bash variable expansion and metacharacter interpretation in payload files.
3. **SSE response parsing** requires extracting the `data:` line and parsing the nested JSON (tool result text is JSON-stringified inside the MCP response).
4. **Session management**: MCP sessions expire after inactivity. Verify session liveness before batch operations; re-initialize if needed.
5. **`--max-time` on curl** is essential — SSE connections stay open and will hang without a timeout.
6. **Individual `write_page` calls are more reliable than `batch_write_pages`** for complex content — easier to debug failures and retry individual pages.
7. **Optimistic locking** (`expectedVersion`) should be used on page updates to prevent accidental overwrites.
8. **Plan the article structure first** — design the page hierarchy, inter-page links, and metadata schema before writing any content. This ensures consistent cross-references and metadata.
9. **Metadata schema consistency**: Use the same frontmatter field names and tag conventions across all articles in a cluster for queryability via `query_metadata`.
10. **Author attribution**: Use a descriptive author name (e.g., `claude-code-researcher`) rather than the default `MCP` for audit trail clarity.
