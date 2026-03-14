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

---

# Research History: Personal Finance — Index Fund Investing for Early Retirement

This document records every action taken to research, write, and publish
an article cluster about using low-cost index funds and strategic account
allocation to achieve early retirement, using the JSPWiki MCP API.

## Environment

- **Wiki**: JSPWiki running on local Tomcat 11 at http://localhost:8080/
- **MCP endpoint**: http://localhost:8080/mcp (Streamable HTTP transport)
- **MCP server version**: 23 tools, 5 prompts, 3 completions (Phase 2 deployment)
- **Date**: 2026-03-14

---

## Action Log

### 1. Initialize MCP session

**Action**: Send MCP `initialize` request to establish session.
**Result**: Session `30950115-6f46-47ad-9bbb-de7981047213` established. Handshake completed.

### 2. Survey existing wiki content

**Action**: Use `search_pages` to find existing articles on index funds, expense ratios, tax-advantaged accounts, and early retirement. Two queries issued.
**Tool**: `search_pages` x2
**Existing pages found**:
- `ExpenseRatiosAndTheirEffectOnCompounding` — basic overview of expense ratio compounding
- `LowCostIndexFundInvesting` — introductory tutorial
- `TaxBenefitsOfRetirementAccounts` — existing tax article
- `TypesofInvestmentAccountsTutorial` — account types overview
- `CompoundInterestAndTaxAdvantagedAccounts` — compound interest in tax-advantaged context
- `MaximizingRetirementAccountContributions` — contribution strategies
- `RetirementAccountWithdrawalRules` — withdrawal rules
- Plus ~15 other related pages

**Action**: Used `read_page` on 2 key existing pages to understand depth and avoid duplication.
**Finding**: Existing pages are introductory tutorials. Our cluster can go deeper and focus specifically on early retirement strategy, linking back to existing content.

### 3. Plan article cluster structure

**Action**: Design the article hierarchy before writing any content.
**Tool**: None (planning step)
**Structure decided**:
- **Hub page**: `IndexFundInvestingForEarlyRetirement` — article index, key principles, links to existing content
- **5 sub-articles**:
  1. `ExpenseRatioDeepDive` — concrete dollar-cost analysis over 30 years, evidence against active management
  2. `IndexFundPortfolioConstruction` — three-fund portfolio, allocation by time horizon, rebalancing
  3. `AccountTypeStrategy` — why taxable brokerage accounts are essential, funding order, asset location
  4. `RothConversionLadder` — step-by-step mechanics for accessing retirement funds before 59.5
  5. `EarlyRetirementInvestmentPlan` — complete blueprint from first dollar to retirement day
- **Total**: 6 pages (1 hub + 5 sub-articles)
- **Linking**: Each sub-article links back to hub and to adjacent articles; hub links to all sub-articles and to related existing pages

**Metadata schema decided**:
- `type: "article"` (sub-articles) / `type: "reference"` (hub)
- `status: "active"`
- `tags`: always include `personal-finance`, `investing`, `FIRE`, plus topic-specific tags
- `related`: CamelCase page names for cross-references
- `summary`: one-line description
- `author`: `claude-code-researcher`

### 4. Create JSON payloads for all 6 articles

**Action**: Write each article's MCP `write_page` payload to a temporary JSON file using single-quoted heredocs to avoid bash metacharacter issues.
**Files created**: `/tmp/mcp_investing_hub.json`, `/tmp/mcp_expense_ratio.json`, `/tmp/mcp_index_portfolio.json`, `/tmp/mcp_account_strategy.json`, `/tmp/mcp_roth_ladder.json`, `/tmp/mcp_complete_plan.json`

**Lesson**: Reused the file-based payload pattern from Cluster 1. All content was written to files before any MCP calls were made, allowing review of the full cluster structure before publishing.

### 5. Publish hub page

**Action**: Send hub page payload via `curl -d @/tmp/mcp_investing_hub.json`.
**Tool**: `write_page`
**Result**: `{success: true, pageName: "IndexFundInvestingForEarlyRetirement", version: 1}`

### 6. Publish 5 sub-articles

**Action**: Published all 5 sub-articles sequentially via file-based payloads.
**Tool**: `write_page` x5
**Results**:
- `ExpenseRatioDeepDive` — v1 created
- `IndexFundPortfolioConstruction` — v1 created
- `AccountTypeStrategy` — v1 created
- `RothConversionLadder` — v1 created
- `EarlyRetirementInvestmentPlan` — v1 created

### 7. Update Main page

**Action**: Read Main page (v1), added "Index Fund Investing for Early Retirement" section to Featured Research.
**Tool**: `read_page` → `write_page` with `expectedVersion: 1`
**Result**: `{success: true, pageName: "Main", version: 1}`

### 8. Redeploy WAR for new MCP tools

**Action**: During verification, discovered the deployed WAR was from before the Phase 2 MCP commit (06:46 vs 07:58 build). The new tools (get_outbound_links, get_broken_links, etc.) returned "Unknown tool" errors.
**Fix**: Stopped Tomcat, removed extracted webapp, copied new WAR, restarted Tomcat. Re-initialized MCP session.
**Result**: All 23 tools now available.

**Lesson learned**: After building new MCP tools, you must redeploy the WAR to Tomcat. The build alone does not update the running server. A skill should check the WAR timestamp against the build timestamp and warn or redeploy automatically.

### 9. Verify article cluster

**Action**: Used `read_page` to verify all 6 articles exist with correct metadata and content.
**Tool**: `read_page` x6
**Result**: All 6 pages exist at v1 with correct tags and metadata.
**Action**: Used `get_outbound_links` on hub page to test new Phase 2 tool.
**Result**: Tool works (returns empty — Markdown-style links require rendering to populate the reference manager index).

---

## Summary

| # | Action | MCP Tool | Page | Result |
|---|--------|----------|------|--------|
| 1 | Initialize session | `initialize` | — | Session established |
| 2 | Survey existing content | `search_pages` x2, `read_page` x2 | Various | 15+ related pages found |
| 3 | Plan structure | — | — | 6 pages designed |
| 4 | Create payloads | — | — | 6 JSON files written |
| 5 | Publish hub page | `write_page` | IndexFundInvestingForEarlyRetirement | v1 created |
| 6 | Publish sub-articles | `write_page` x5 | ExpenseRatioDeepDive, IndexFundPortfolioConstruction, AccountTypeStrategy, RothConversionLadder, EarlyRetirementInvestmentPlan | All v1 created |
| 7 | Update Main page | `read_page`, `write_page` | Main | Updated with cluster link |
| 8 | Redeploy WAR | — | — | New tools available |
| 9 | Verify cluster | `read_page` x6, `get_outbound_links` | All 6 pages | All verified |

## Additional Lessons for MCP Skill Development

Building on Cluster 1 lessons, this session revealed:

11. **Survey existing content before writing**: Use `search_pages` and `read_page` to understand what already exists. This prevents duplication and enables linking to existing articles, making the new cluster part of the existing knowledge graph.
12. **Design the full cluster before publishing any page**: Write all JSON payloads first, review the cross-reference structure, then publish. This prevents orphaned links and ensures consistent metadata schemas.
13. **WAR redeployment is a separate step from building**: New MCP tools are not available until the WAR is redeployed to Tomcat. A skill should track this or automate it.
14. **Markdown link syntax vs. WikiLink syntax**: JSPWiki's reference manager may not track Markdown-style `[text](PageName)` links the same way as traditional WikiLinks. The `get_outbound_links` and `get_backlinks` tools may return incomplete results for Markdown-only pages. A skill should be aware of this limitation.
15. **Article cluster workflow is highly parallelisable**: All sub-article payloads can be created simultaneously, and all `write_page` calls are independent. A skill should use `batch_write_pages` or parallel tool calls for efficiency. However, the hub page should be published first (or at least designed first) so that sub-articles can link back to it.
16. **Reuse payload pattern without re-learning it**: This session reused the file-based payload approach immediately, without any trial-and-error. This confirms that encoding the pattern in a skill would eliminate the initial learning curve for new sessions.
17. **Content research (existing pages) + content generation + MCP publishing are three distinct phases**: A skill should structure the workflow as: (1) discover existing content, (2) plan and generate articles, (3) publish via MCP, (4) verify. Each phase has different tool requirements and failure modes.

## Proposed Skill Workflow (Both Clusters Combined)

Based on both article cluster sessions, the optimal MCP article publishing skill should follow this workflow:

```
1. DISCOVER
   - search_pages for related content
   - read_page on top results to understand depth
   - list_metadata_values to understand tag/type conventions
   - Output: existing content map, metadata conventions

2. PLAN
   - Design hub + sub-article structure
   - Define metadata schema (type, tags, related)
   - Define inter-page links (hub↔sub, sub↔sub, sub→existing)
   - Output: page name list, link graph, metadata template

3. GENERATE
   - Create JSON payloads for all pages
   - Use file-based approach (write to /tmp, use curl -d @file)
   - Single-quoted heredocs for payload creation
   - Output: /tmp/mcp_*.json files ready to send

4. PUBLISH
   - Initialize MCP session (check WAR deployment timestamp)
   - Publish hub page first
   - Publish sub-articles (can be parallel)
   - Update Main page or other entry points
   - Output: page versions confirmed

5. VERIFY
   - read_page on all published pages
   - get_outbound_links / get_backlinks to check link graph
   - get_wiki_stats for overall health
   - Output: verification report

6. DOCUMENT
   - Append to research_history.md
   - Record actions, tools used, results, lessons learned
```

---

# Research History: Retirement Planning Guide — Strategic Decumulation Decisions

This document records every action taken to research, write, and publish
an article cluster about strategic retirement planning decisions — Roth conversions,
Social Security claiming, withdrawal sequencing, safe withdrawal rates, Medicare,
RMDs, and retirement income planning — using the JSPWiki MCP API.

## Environment

- **Wiki**: JSPWiki running on local Tomcat 11 at http://localhost:8080/
- **MCP endpoint**: http://localhost:8080/mcp (Streamable HTTP transport)
- **MCP server version**: 23 tools (Phase 2 deployment)
- **Date**: 2026-03-14
- **Skill used**: `wiki-article-cluster` (first use of the new skill)

---

## Action Log

### 1. Initialize MCP session

**Action**: Send MCP `initialize` request to establish session.
**Result**: Session established. Handshake completed.

### 2. Survey existing wiki content (DISCOVER phase)

**Action**: Used `search_pages` with two queries ("retirement social security roth" and "withdrawal accounts tax planning") to find existing articles. Used `list_metadata_values` to check tag conventions.
**Tool**: `search_pages` x2, `list_metadata_values` x1
**Existing pages found**:
- `RothConversionLadder` — detailed mechanics of the early-access ladder (5-year seasoning, step-by-step)
- `EarlyRetirementInvestmentPlan` — complete accumulation plan with 4% rule, savings rates
- `IndexFundInvestingForEarlyRetirement` — hub for existing personal finance cluster
- `TaxPlanningForRetirement` — short stub page
- `RetirementAccountWithdrawalRules` — rules reference table
- `SettingFinancialGoalsForRetirement` — goal-setting article
- Plus 15+ other retirement/tax/account pages

**Action**: Used `read_page` on 6 key existing pages to understand depth and identify gaps.
**Finding**: Existing content focuses on accumulation and rules. Gap identified: no strategic decision frameworks for decumulation phase. No Social Security analysis, no IRMAA/Medicare planning, no withdrawal sequencing strategy, no safe withdrawal rate deep dive.

### 3. Plan article cluster structure (PLAN phase)

**Action**: Design cluster focusing on strategic decisions rather than rules.
**Structure decided**:
- **Hub page**: `RetirementPlanningGuide` — decision map showing interconnections
- **7 sub-articles**:
  1. `RothConversionStrategy` — strategic framework beyond the existing ladder
  2. `SocialSecurityClaimingStrategy` — claiming age, breakeven, spousal/survivor benefits
  3. `SafeWithdrawalRates` — 4% rule deep dive, sequence risk, dynamic strategies
  4. `RetirementWithdrawalSequencing` — account draw order, tax bracket management by phase
  5. `MedicarePlanningAndHealthcare` — ACA bridge, IRMAA, enrollment windows
  6. `RequiredMinimumDistributions` — SECURE Act 2.0 rules, pre-RMD strategies, QCDs
  7. `RetirementIncomeBlueprint` — bucket strategy, floor-and-upside, comprehensive example
- **Total**: 8 pages (1 hub + 7 sub-articles, ~9,665 words total)
- **Key design decision**: Focus on decision frameworks over rule recitation; show how decisions cascade across articles

### 4. Create JSON payloads (GENERATE phase)

**Action**: Wrote all 8 article payloads to temporary files using single-quoted heredocs.
**Files created**: `/tmp/mcp_retirement_hub.json`, `/tmp/mcp_roth_strategy.json`, `/tmp/mcp_social_security.json`, `/tmp/mcp_safe_withdrawal.json`, `/tmp/mcp_withdrawal_sequencing.json`, `/tmp/mcp_medicare.json`, `/tmp/mcp_rmd.json`, `/tmp/mcp_income_blueprint.json`
**Pattern reused**: File-based payload approach with `curl -d @file`, single-quoted heredocs.

### 5. Publish hub page (PUBLISH phase)

**Tool**: `write_page`
**Result**: `{success: true, pageName: "RetirementPlanningGuide", version: 1}`

### 6. Publish 7 sub-articles

**Tool**: `write_page` x7
**Results**:
- `RothConversionStrategy` — v1 created (1,098 words)
- `SocialSecurityClaimingStrategy` — v1 created (1,375 words)
- `SafeWithdrawalRates` — v1 created (1,386 words)
- `RetirementWithdrawalSequencing` — v1 created (1,155 words)
- `MedicarePlanningAndHealthcare` — v1 created (1,358 words)
- `RequiredMinimumDistributions` — v1 created (1,308 words)
- `RetirementIncomeBlueprint` — v1 created (1,467 words)

### 7. Update Main page

**Tool**: `read_page` → `write_page` with `expectedVersion: 1`
**Change**: Added "Retirement Planning Guide (March 2026)" as first item in Featured Research.
**Result**: `{success: true, pageName: "Main", version: 1}`

### 8. Verify cluster (VERIFY phase)

**Tool**: `read_page` x8, `get_broken_links`, `get_wiki_stats`
**Results**:
- All 8 pages exist at v1 with correct content and metadata
- Zero broken links involving cluster pages
- Wiki total: 1,100 pages

---

## Summary

| # | Action | MCP Tool | Page | Result |
|---|--------|----------|------|--------|
| 1 | Initialize session | `initialize` | — | Session established |
| 2 | Survey existing content | `search_pages` x2, `read_page` x6, `list_metadata_values` | Various | 20+ related pages found |
| 3 | Plan structure | — | — | 8 pages designed |
| 4 | Create payloads | — | — | 8 JSON files written |
| 5 | Publish hub page | `write_page` | RetirementPlanningGuide | v1 created |
| 6 | Publish sub-articles | `write_page` x7 | 7 articles | All v1 created |
| 7 | Update Main page | `read_page`, `write_page` | Main | Updated with cluster link |
| 8 | Verify cluster | `read_page` x8, `get_broken_links`, `get_wiki_stats` | All 8 pages | All verified |

## Cross-Links to Existing Content

This cluster links back to the existing personal finance cluster at these points:
- Hub → `IndexFundInvestingForEarlyRetirement`, `RothConversionLadder`, `AccountTypeStrategy`, `EarlyRetirementInvestmentPlan`, `TaxBenefitsOfRetirementAccounts`, `RetirementAccountWithdrawalRules`, `HealthSavingsAccounts`
- `RothConversionStrategy` → `RothConversionLadder`, `AccountTypeStrategy`
- `RetirementWithdrawalSequencing` → `AccountTypeStrategy`, `RetirementAccountWithdrawalRules`, `HealthSavingsAccounts`
- `MedicarePlanningAndHealthcare` → `HealthSavingsAccounts`
- `SafeWithdrawalRates` → `EarlyRetirementInvestmentPlan`
- `RetirementIncomeBlueprint` → `EarlyRetirementInvestmentPlan`, `IndexFundPortfolioConstruction`, `AccountTypeStrategy`

## Observations

18. **The wiki-article-cluster skill worked on first use**: The 6-phase workflow (DISCOVER → PLAN → GENERATE → PUBLISH → VERIFY → DOCUMENT) provided clear structure. No trial-and-error on MCP mechanics — the helper script and payload patterns eliminated the learning curve from clusters 1 and 2.
19. **Decision-framework articles are more interconnected than factual articles**: The retirement planning cluster has far more cross-references between articles than the conflicts cluster, because each decision affects the others. The hub page's "How the Decisions Connect" table was essential for making these cascading effects visible.
20. **Complementing vs. duplicating existing content**: The existing `RothConversionLadder` covers mechanics; the new `RothConversionStrategy` covers strategy. The existing `RetirementAccountWithdrawalRules` covers rules; the new `RetirementWithdrawalSequencing` covers strategy. This division worked well — articles link to each other without repeating content.

---

# Cluster 4: Generative AI Adoption Guide

**Date**: 2026-03-14
**Topic**: Adopting generative AI as an individual contributor or small team
**Skill used**: wiki-article-cluster (6-phase workflow)

## Pages Created (7 total)

### Hub Page
- **GenerativeAiAdoptionGuide** (hub) — Complete adoption roadmap for individual contributors and small teams

### Sub-Articles
- **UnderstandingGenerativeAi** — How LLMs work, capabilities, limitations, and the transformer architecture explained for practitioners
- **GenerativeAiToolsForIndividuals** — Current tool landscape: ChatGPT, Claude, Gemini, GitHub Copilot, local options, and selection criteria
- **PracticalPromptEngineering** — Structured prompting techniques: system prompts, chain-of-thought, few-shot, and iterative refinement
- **RunningLocalLlms** — Hardware requirements, Ollama/llama.cpp setup, model selection, and why running local models builds essential intuition
- **AiAugmentedWorkflows** — Integration patterns: writing, coding, research, data analysis, and building personal automation pipelines
- **AcceleratingAiLearning** — Learning strategies: deliberate practice, failure journals, community engagement, and measuring progress

## Existing Pages Updated

- **Main** — Added Generative AI Adoption Guide to Featured Research section
- **MachineLearning** — Added "Generative AI" section with links to cluster
- **ArtificialIntelligence** — Added "Generative AI Adoption" section with links to cluster
- **AIModelTraining** — Added "Running Your Own Models" section with links to cluster

## MCP Tools Used

- `search_pages` — Discovered existing AI-related pages (MachineLearning, ArtificialIntelligence, AIModelTraining)
- `read_page` — Read existing content to understand conventions and avoid duplication
- `list_metadata_values` — Checked existing tag/type conventions
- `write_page` — Published all 7 new pages and updated 4 existing pages
- `get_broken_links` — Verified no broken links introduced
- `get_wiki_stats` — Confirmed wiki health (1113 total pages)
- `get_outbound_links` / `get_backlinks` — Attempted link graph verification (note: Markdown-style links not parsed by these tools)

## Cross-Linking Strategy

### Within Cluster
- Hub links to all 6 sub-articles in structured sections
- Each sub-article links back to hub and to 3-5 related sub-articles
- AcceleratingAiLearning references RunningLocalLlms for cost-free experimentation
- PracticalPromptEngineering and AiAugmentedWorkflows cross-reference heavily

### To Existing Pages
- MachineLearning → UnderstandingGenerativeAi, RunningLocalLlms, AiAugmentedWorkflows
- ArtificialIntelligence → GenerativeAiToolsForIndividuals, PracticalPromptEngineering, RunningLocalLlms, AcceleratingAiLearning
- AIModelTraining → RunningLocalLlms, GenerativeAiAdoptionGuide

## Observations

21. **Practical over theoretical**: The cluster deliberately targets practitioners, not researchers. Articles focus on "how to actually use this" rather than mathematical foundations. The RunningLocalLlms article emphasizes the *insight-building* value of local models, not just their technical setup.
22. **The learning acceleration angle is distinctive**: Most AI adoption guides skip the meta-skill of learning to learn about AI. The AcceleratingAiLearning article's "failure journal" and "prompt variation exercise" techniques give readers concrete practice methods.
23. **Context window resumed cleanly**: This cluster was created across a context window boundary (session ran out of context mid-generation). The session helper script and file-based payload pattern made it easy to pick up exactly where work stopped — all 6 completed payloads survived in /tmp/.

---

# Cluster 5: Spousal Green Card Guide

**Date**: 2026-03-14
**Topic**: Obtaining a US green card for a spouse — marriage-based immigration
**Skill used**: wiki-article-cluster (refined version with EXTEND workflow, convenience functions)

## Pages Created (8 total)

### Hub Page
- **SpousalGreenCardGuide** (hub, 6,460 chars) — Complete process overview, two pathways, timeline, costs, decision map

### Sub-Articles
- **MarriageBasedImmigrationCategories** (5,698 chars) — IR-1 vs. CR-1 vs. K-1, immediate relative classification, LPR sponsor differences
- **FilingTheI130Petition** (6,664 chars) — Foundation of every spousal case: evidence, fees, concurrent filing, common mistakes
- **AdjustmentOfStatusProcess** (7,169 chars) — I-485 path for spouses in the US, concurrent filing package, combo card, medical exam
- **ConsularProcessingPath** (7,530 chars) — NVC processing, DS-260, embassy interview, administrative processing, country-specific issues
- **ImmigrationFinancialRequirements** (7,341 chars) — I-864 Affidavit of Support, income thresholds, assets, joint sponsors, legal obligation
- **TheGreenCardInterview** (7,834 chars) — Preparation, common questions, Stokes fraud interview, evidence to bring, possible outcomes
- **ConditionalResidenceAndI751** (8,300 chars) — 2-year conditional card, filing window, waivers for divorce/abuse/hardship, common scenarios

## Existing Pages Updated
- **Main** — Added Spousal Green Card Guide to Featured Research section

## MCP Tools Used
- `mcp_search_pages` — Confirmed no existing immigration content
- `mcp_read_page` — Read Main page for update; verified all 8 published pages
- `list_metadata_values` — Checked existing tag conventions
- `mcp_write_page` — Published 8 new pages and updated Main
- `mcp_get_broken_links` — Confirmed no new broken links (8 pre-existing)
- `mcp_get_stats` — Wiki health confirmed at 1,121 total pages

## Observations

24. **Refined skill convenience functions worked well**: Using `mcp_search_pages`, `mcp_get_broken_links`, and `mcp_get_stats` directly was cleaner than `mcp_call` with manual JSON arguments. The helper script improvements paid off immediately.
25. **First non-financial, non-geopolitical cluster**: Immigration law is a different domain but the 6-phase workflow applied without modification. The skill is genuinely domain-agnostic.
26. **Process-oriented clusters need decision trees**: Immigration has clear branching logic (spouse in US vs. abroad, married < 2 years vs. 2+). The hub page's comparison tables and "How the Decisions Connect" section from the retirement cluster pattern worked well here too.

---

# Cluster 6: Linux for Windows Users

**Date**: 2026-03-14
**Topic**: Transitioning from Windows to Linux — skills, tools, advantages, and the learning path
**Skill used**: wiki-article-cluster (refined version)

## Pages Created (8 total)

### Hub Page
- **LinuxForWindowsUsers** (hub, 7,402 chars) — Mindset shift, learning path, Windows-to-Linux concept mapping, low-risk starting options

### Sub-Articles
- **WhyLearnLinuxDeeply** (8,114 chars) — Career advantages, server dominance stats, technical benefits of understanding the stack
- **ChoosingALinuxDistribution** (7,768 chars) — Distro families (Debian/Ubuntu, Red Hat/Fedora, Arch), recommendations by use case, desktop environments
- **LinuxCommandLineEssentials** (8,045 chars) — Navigation, file ops, piping, text processing, process management, building fluency
- **LinuxFilesystemAndPermissions** (8,099 chars) — Directory tree, everything-is-a-file, users/groups, chmod/chown, permission model
- **LinuxPackageManagement** (7,164 chars) — apt/dnf/pacman, repos, universal formats (Flatpak/Snap/AppImage), updates, building from source
- **LinuxShellScriptingFundamentals** (7,260 chars) — Variables, conditionals, loops, functions, practical scripts, cron, dotfiles
- **LinuxSystemAdministration** (8,880 chars) — systemd, process management, logs, networking, SSH, user management, diagnostic checklist

## Existing Pages Updated
- **Main** — Added Linux for Windows Users to Featured Research
- **LinuxCommands** — Added "Learning Linux In Depth" section with cluster cross-references

## Observations

27. **Windows-concept mapping accelerates learning**: The hub's "Windows Concepts and Their Linux Equivalents" table and each article's Windows analogies give readers anchors for new concepts. This teaching technique (connecting new knowledge to existing knowledge) is specific to transition-oriented clusters.
28. **Technical clusters benefit from runnable examples**: Every article includes actual commands the reader can run. The shell scripting article has complete, working scripts. This is different from the decision-framework style of the retirement/immigration clusters.
29. **Six clusters, zero skill workflow modifications needed**: The 6-phase workflow has now been used across finance, geopolitics, immigration, AI adoption, and Linux — confirming it is genuinely domain-agnostic.

### Cluster 6 Extension: FundamentalsOfProgramming

**Action**: Added a cross-cluster article connecting the Linux and Generative AI clusters — covering how agentic software development changes what programming fundamentals matter, and how aspiring professionals should balance coding skills, domain expertise, and AI fluency.

**Pages created**: FundamentalsOfProgramming (14,318 chars)
**Pages updated**: LinuxForWindowsUsers (added step 5 to learning path + further reading), WhyLearnLinuxDeeply (added further reading cross-reference), AiAugmentedWorkflows (added further reading cross-reference)

### MCP Service Improvement Observations for Cluster Extension Workflows

The EXTEND workflow (adding a single article to an existing cluster and updating related pages) exposed several areas where the MCP API could be improved:

30. **No patch/append operation**: Every page update requires a full read-modify-write cycle. To add one line to a page's "Further Reading" section, I must: (a) read the entire page content, (b) parse the JSON, (c) find the insertion point via string manipulation, (d) rebuild the full content string, (e) write the entire page back with `expectedVersion`. A `patch_page` or `append_to_page` tool that accepts a section name and new content would eliminate steps (a)-(d) and dramatically reduce the chance of accidental content corruption during string manipulation.

31. **No batch update with selective changes**: When extending a cluster, I typically need to update 3-5 existing pages with small, similar changes (add a link to Further Reading, add a page name to the `related:` metadata list). Each requires its own read-modify-write cycle with its own Python script. A `batch_update_pages` tool that accepts a list of `{pageName, changes: [{section, action: "append"|"replace", content}]}` would reduce 10-15 MCP calls to one.

32. **No structured metadata operations**: The frontmatter metadata (`related:`, `tags:`) is embedded in the page content as a text string. To add a page name to the `related:` list, I must parse the content, find the YAML-like frontmatter, modify the list, and serialize it back. A dedicated `update_metadata` tool (e.g., `update_metadata({pageName, field: "related", action: "append", value: "NewPage"})`) would be safer and more efficient.

33. **No content search-and-insert**: The most common update pattern is "find this text in the page and insert new text before/after it." The current API requires downloading the full content, doing client-side string manipulation, and uploading the full content. A `content_transform` tool accepting `{pageName, find: "## Further Reading", insertBefore: "- [New Link](NewPage) — description\n"}` would handle the most common extension case in a single call.

34. **The full read-modify-write cycle is fragile at scale**: For this single-article extension, I updated 3 pages. Each required: (1) `read_page` call, (2) Python script to parse nested JSON, extract content, do string replacement, generate payload file, (3) `write_page` call. That is 6 MCP calls and 3 Python scripts for what is conceptually "add a link to 3 pages." At cluster scale (updating 8-10 pages when injecting cross-references), this becomes the dominant time cost and the most error-prone phase. The risk is that a string replacement targets the wrong text or misses because the content changed between read and write.

35. **`expectedVersion` is good but insufficient for concurrent safety**: The optimistic locking prevents lost updates, but the read-modify-write gap means the content could change between the read (where I capture the version) and the write (where I submit it). For a single-user wiki this is fine, but it would be a real problem in a multi-user environment. A compare-and-swap on content hash (not just version number) would be more robust.

---

## Cluster Extension: InvestingInYourTwenties (2026-03-14)

**Cluster**: IndexFundInvestingForEarlyRetirement (Cluster 2)
**New article**: [InvestingInYourTwenties](http://localhost:8080/Wiki.jsp?page=InvestingInYourTwenties) — Why investors in their 20s should strongly consider 100% equities, and how risk tolerance changes with age
**Tools used**: `write_page` (new article), `patch_page` (hub page + retirement guide updates), `get_outbound_links`, `get_backlinks`, `get_broken_links`
**Pages updated**: IndexFundInvestingForEarlyRetirement (added link under "The Allocation Decision"), RetirementPlanningGuide (added cross-reference under "Background")

### Observations on New MCP Tools

36. **`patch_page` eliminates the read-modify-write cycle**: Observations 30, 33, and 34 from the previous session are now resolved. Adding a link to the hub page required one `patch_page` call with `insert_after` instead of the previous workflow of: read page → Python string manipulation → write page. The entire hub page update was a single JSON payload. This is a transformative improvement for the EXTEND workflow.

37. **`get_outbound_links` now works for Markdown pages**: Phase 1 of the simplify-mcp work fixed Markdown link tracking in MarkdownParser. The new article (pure Markdown with `[text](PageName)` links) returned all 11 outbound links correctly via `get_outbound_links`. Observations in the SKILL.md about link tools returning empty for Markdown pages are now outdated and should be corrected.

38. **`get_backlinks` confirms bidirectional linking**: After patching the two hub pages, `get_backlinks` for InvestingInYourTwenties immediately returned both IndexFundInvestingForEarlyRetirement and RetirementPlanningGuide. This validates that the reference manager now tracks Markdown links in real time.

39. **`patch_page` with `expectedVersion` provides safe concurrent updates**: The patch_page tool accepts `expectedVersion` for optimistic locking, resolving concern 35 — you get atomic read+patch in a single server-side operation, eliminating the read-modify-write gap entirely.

40. **Content hash available but not needed**: `patch_page` returns a `contentHash` in the response, which could be used for subsequent `expectedContentHash` locking. For this workflow it wasn't needed since each page was only patched once, but it's available for more complex scenarios.

41. **SKILL.md needs updates**: The skill still documents workarounds for problems that no longer exist (Python read-modify-write for updates, link tool caveats for Markdown). Should be updated to use `patch_page` for the EXTEND workflow and remove Markdown link caveats. **Done** — SKILL.md rewritten in this session.

---

## Cluster Extension: SequenceOfReturnsRisk (2026-03-14)

**Cluster**: RetirementPlanningGuide (Cluster 3)
**New article**: [SequenceOfReturnsRisk](http://localhost:8080/Wiki.jsp?page=SequenceOfReturnsRisk) — Deep dive on sequence of returns risk and 5 concrete protection strategies (bond tent, cash buckets, dynamic spending, guaranteed income floor, flexible timing)
**Tools used**: `write_page` (new article), `batch_patch_pages` (3 cross-reference updates in one call), `get_outbound_links`, `get_backlinks`, `get_broken_links`
**Pages updated**: RetirementPlanningGuide (added to "The Foundation" section), SafeWithdrawalRates (added cross-reference after sequence risk intro), InvestingInYourTwenties (added See Also link)

### Observations on Updated SKILL.md Workflow

42. **`batch_patch_pages` is the ideal EXTEND tool**: Updating 3 pages with cross-references was a single MCP call instead of the previous 6-call pattern (3x read + 3x Python + 3x write). The updated SKILL.md correctly documents this as the primary EXTEND mechanism.

43. **SKILL.md workflow held up well**: Following the updated DISCOVER → write_page → batch_patch_pages → verify flow was smooth. No need to fall back to the Python read-modify-write pattern at any point. The `insert_after` action with a marker string from the existing content was precise enough for all 3 updates.

44. **Link verification is now reliable**: All 9 outbound Markdown links and 3 backlinks were correctly tracked. The link graph tools are now fully trustworthy for Markdown pages, confirming the Phase 1 fix works in production across multiple articles.

---

## Metadata Backfill: Cluster Identifiers (2026-03-14)

**Action**: Added explicit `cluster` field (kebab-case string) to all 48 cluster article frontmatter, enabling programmatic cluster membership queries via `query_metadata` and `list_metadata_values`.

**Tool**: `update_metadata` with `action: "set"` — 48 calls total, zero failures.

### Cluster IDs Assigned

| Cluster ID | Hub Page | Article Count |
|---|---|---|
| `conflicts-equity-markets` | ConflictsAndEquityMarkets | 8 (hub + 7 sub-articles) |
| `index-fund-investing` | IndexFundInvestingForEarlyRetirement | 7 (hub + 5 sub + 1 extension) |
| `retirement-planning` | RetirementPlanningGuide | 9 (hub + 7 sub + 1 extension) |
| `generative-ai` | GenerativeAiAdoptionGuide | 7 (hub + 6 sub-articles) |
| `spousal-green-card` | SpousalGreenCardGuide | 8 (hub + 7 sub-articles) |
| `linux-for-windows-users` | LinuxForWindowsUsers | 9 (hub + 7 sub + 1 cross-cluster extension) |

**Cross-cluster note**: FundamentalsOfProgramming connects the Linux and Generative AI clusters. Assigned to `linux-for-windows-users` (where it was created as an extension). Cross-cluster relationships are handled by the existing `related` field.

### Verification

- `list_metadata_values` with `field: "cluster"` returns all 6 cluster IDs
- `query_metadata` with `field: "cluster", value: "retirement-planning"` returns all 9 pages
- `get_broken_links` shows 8 pre-existing broken links — no new breakage from metadata-only updates
- SKILL.md updated with `cluster` field documentation (metadata table, PLAN phase, payload template, EXTEND workflow)

### Observations

45. **`update_metadata` with `set` action is ideal for backfills**: Adding a single field to 48 pages required no content reads, no version tracking, and no risk of body corruption. Each call was ~1 second. The tool was designed exactly for this use case.

46. **Page names in research_history.md diverged from plan assumptions**: The plan used approximate page names (e.g., `WW1MarketImpact`) that differed from actual names (e.g., `WorldWarOneMarkets`). All 6 clusters had significant naming differences. Lesson: always verify page names via `list_pages` or `search_pages` before batch operations.

47. **Single-cluster assignment works well**: Every page has exactly one `cluster` value. The `type: hub` vs `type: article` field distinguishes role within a cluster. Cross-cluster links use the existing `related` field. No need for multi-cluster membership.
