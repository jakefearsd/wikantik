---
verified_at: '2026-10-24T23:05:52.124736736Z'
related:
- AgentCookbook
- ContentProgramPolicy
- ContentIntelligenceDesign
- ContentFeedbackLoopFocus
- WikantikContentIntelligence
- VerifyingAnAgentGeneratedPage
- ChoosingARetrievalMode
- WikiAuditSkill
audience:
- humans
- agents
status: active
verified_by: wikantik-cli
kg_include: true
summary: 'The recurring evidence-driven maintenance loop: sweep under-served queries,
  confirm with assemble_bundle, refresh stale tool pages, and measure DiD lift.'
canonical_id: 01KZG2CCNJ1Q9Y26KEHZ2XEJ7B
runbook:
  when_to_use:
  - Running a periodic wiki audit or maintenance pass
  - Deciding what content to write or fix next from evidence
  - A page seems unretrievable for queries it should answer
  steps:
  - 'D1: list_retrieval_queries(max_avg_results=1, since_days=<since last audit>)
    on admin-mcp — real queries the corpus answers poorly'
  - For each miss, confirm with assemble_bundle(query) on knowledge-mcp — the live
    gate
  - Confirmed miss with an owning page → fix that page's summary/headings with the
    query's discriminating vocabulary; re-check
  - Confirmed miss with NO owning page → queue a new page (project-anchored or seeding)
  - 'D2: for tool-practice clusters, flag pages with verified_at absent or >12 months
    → web-check fast-decaying claims, fix, mark_page_verified'
  - Record findings; never auto-fix summaries/tags mechanically
  pitfalls:
  - A clean verify_pages lint does NOT prove retrievability — only assemble_bundle
    does
  - Generic summary enrichment can DROP corpus-wide recall; use the query's own discriminating
    terms
  - list_retrieval_queries needs production traffic — it returns nothing useful on
    a fresh local instance
  - Re-verification means checking against the world (web), not rereading the page
tags:
- wiki-audit
- retrieval
- maintenance
- runbook
- agent-context
- seo
- content-intelligence
title: 'Wiki Audit Demand and Freshness Loop: Closed-Loop Retrieval and SEO Maintenance'
type: runbook
cluster: agent-cookbook
date: '2026-10-24'
---
# Wiki Audit Demand and Freshness Loop: Closed-Loop Retrieval & SEO Maintenance

The **Wiki Audit Demand and Freshness Loop** is the recurring, evidence-driven maintenance workflow defined by the [[ContentProgramPolicy|Content Program Policy]] and powered by [[ContentIntelligenceDesign|Content Intelligence (`wikantik-insights`)]]. Rather than guessing which wiki pages to edit or mechanically rewriting frontmatter in bulk, content agents execute two empirical loops—**D1 (Under-Served Query Demand)** and **D2 (Tool-Practice Freshness Verification)**—and measure causal impact over a 28-day Difference-in-Differences window.

---

## 1. End-to-End Demand & Freshness Workflow

```mermaid
flowchart TB
    subgraph Signals["1. Telemetry & Demand Ingestion"]
        GSC["Google Search Console &<br/>Bing Webmaster Tools<br/>(SearchVisibilityExporter -> /admin/insights/ingest)"]
        QLog[("retrieval_query_log<br/>Real Agent & Human Queries<br/>(list_retrieval_queries)")]
        Backlog["list_content_opportunities (admin-mcp)<br/>striking_distance · low_ctr ·<br/>agent_gap · stale_high_value"]
        GSC & QLog --> Backlog
    end

    subgraph D1["2. Loop D1: Under-Served Query Triage"]
        Gate["Live Retrieval Gate:<br/>assemble_bundle(query=miss.query)"]
        Own{"Does an owning\npage exist?"}
        FixPage["Elevate Owning Page:<br/>Inject discriminating query terms into<br/>summary + answering H2 heading + body"]
        SeedPage["Author New Page (write_pages)<br/>Anchored to Cluster Hub + KG Edges"]
        Backlog --> Gate --> Own
        Own -- "Yes (Rank/Recall Miss)" --> FixPage
        Own -- "No (True Corpus Gap)" --> SeedPage
    end

    subgraph D2["3. Loop D2: Tool-Practice Freshness Check"]
        Stale["Scan Practice Clusters for<br/>verified_at absent or > 12 months"]
        WebCheck["Web-Verify Fast-Decaying Claims:<br/>Versions, Licenses, CLI Flags, Models"]
        Stamp["Update Page + verify_pages +<br/>mark_page_verified(slugs=[...])"]
        Stale --> WebCheck --> Stamp
    end

    subgraph Measure["4. Indexing Push & 28-Day DiD Evaluation"]
        Ping["ping_search_engines (IndexNow / Bing)"]
        DiD["EffectEvaluator (28-Day Pre/Post vs. Controls)<br/>Measures Click, Impression & Recall Lift"]
        FixPage & SeedPage & Stamp --> Ping --> DiD
    end
```

---

## 2. Loop D1: Sweeping Under-Served Real Queries (`list_retrieval_queries` & `list_content_opportunities`)

### Step 1: Pull Real Misses and Ranked Content Opportunities
Start by querying `/wikantik-admin-mcp` for queries where real callers received zero or one result (`max_avg_results=1`) alongside the scored opportunity backlog:

```json
// 1A. Real zero/low-hit agent & user queries from retrieval_query_log
{
  "name": "list_retrieval_queries",
  "arguments": {
    "max_avg_results": 1,
    "since_days": 14,
    "limit": 50
  }
}

// 1B. Scored multi-engine SEO & agent-gap opportunities from wikantik-insights
{
  "name": "list_content_opportunities",
  "arguments": {
    "opportunity_type": "striking_distance",
    "limit": 25
  }
}
```

### Step 2: Confirm Every Candidate Against the Live `assemble_bundle` Gate
A historical miss in `retrieval_query_log` may already have been fixed, and a clean `verify_pages` lint check **never** proves retrievability. Always run the live retrieval gate on `/knowledge-mcp`:

```json
{
  "name": "assemble_bundle",
  "arguments": {
    "query": "<exact under-served query>",
    "mode": "hybrid",
    "maxTokens": 3000
  }
}
```

### Step 3: Remediate Confirmed Misses via Discriminating Vocabulary (Not Generic Fluff)
Recall how `EmbeddingTextBuilder.forDocument` embeds every section chunk $c_i$ of page $P$:

$$
\vec{u}_i = \text{Embed}\!\left(\text{``Page: ''} \mathbin{\|} P.\text{title} \mathbin{\|} \text{`` | Cluster: ''} \mathbin{\|} P.\text{cluster}_1 \mathbin{\|} \text{`` | Section: ''} \mathbin{\|} H_i \mathbin{\|} \text{`` — ''} \mathbin{\|} P.\text{summary} \mathbin{\\|} \text{`` — ''} \mathbin{\\|} B_i\right)
$$

- **Case A — An Owning Page Exists, But Failed to Surface in the Top 3:**
  1. Place the query's **exact discriminating vocabulary** into the page's frontmatter `summary` (120–155 chars)—this lifts **every** chunk of that page in dense similarity and adds a $\times 2.0$ BM25 boost.
  2. Rename the answering section's `##` heading ($H_i$) from a generic label (`## Configuration`, `## Overview`) to a topic-specific heading containing the target terms—this lifts that specific chunk into `assemble_bundle` rank #1.
  3. Re-run `assemble_bundle(query)` after the asynchronous re-embed completes (typically 2–5 seconds) to confirm the target section now surfaces in the top 3.
- **Case B — No Owning Page Exists (True Content Gap):**
  - Draft a comprehensive Gold Standard article (`1,200–2,500+ words`) or runbook anchored to an existing cluster Hub (`**Cluster Hub:** [[HubSlug]]`), link it bidirectionally from the Hub, and curate its core Knowledge Graph edges.

---

## 3. Loop D2: Tool-Practice Freshness & External Fact Verification

Knowledge decays at two very different speeds across the corpus:
- **Slow-Decaying Foundational Content:** Mathematical proofs, classical distributed-systems algorithms (`TwoPhaseCommit`, `Paxos`), and historical analyses remain valid for years.
- **Fast-Decaying Tool & Model Practice Content:** Articles in `generative-ai`, `agentic-coding`, `devops-sre`, `data-science-practice`, and `wikantik-development` that cite model versions (`DeepSeek-R1`, `Claude 4.6`, `Gemini 2.5`), CLI flags, licensing tiers, or library APIs decay within **6 to 12 months**.

### Execution Procedure for Loop D2:
1. Filter tool-practice clusters for pages where `verified_at` is missing or older than the freshness threshold (`wikantik.verification.stale_days`, or 12 months for external tool guides).
2. **Verify against the external world (or repository source of truth)—never just re-read the prose:**
   - For Wikantik codebase pages: grep `wikantik.properties`, `McpProtocolIT.EXPECTED_TOOLS`, `web.xml`, and `CHANGELOG.md` (see [[WhatToDoWhenANonExistentFunctionIsCited|What To Do When a Non-Existent Function Is Cited]]).
   - For external technology/model pages: perform live web research to verify current release numbers, deprecations, benchmarks, and pricing (escaping all currency with `\$`).
3. Apply updates via `update_page`, validate with `verify_pages`, and stamp verification via `mark_page_verified(slugs=[...], verifier="jakefear")` (see [[VerifyingAnAgentGeneratedPage|Verifying an Agent-Generated Page]]).
4. Notify search engines immediately via `ping_search_engines` (`IndexNow`).

---

## 4. Measuring Causal Lift: The 28-Day Difference-in-Differences Evaluator

When a page $p$ is updated at time $t_0$ to resolve an opportunity from `list_content_opportunities`, `wikantik-insights` automatically tracks the intervention over a 28-day post-treatment window against a matched control group $C$ of untouched pages in the same cluster:

$$
\Delta_{\text{DiD}}(p) = \left(\bar{Y}_{p,\text{post}} - \bar{Y}_{p,\text{pre}}\right) - \frac{1}{|C|} \sum_{c \in C} \left(\bar{Y}_{c,\text{post}} - \bar{Y}_{c,\text{pre}}\right)
$$

Where $\bar{Y}$ measures daily organic clicks, SERP position, or agent retrieval hit rate. This prevents seasonal traffic shifts from being misread as editorial wins and closes the [[SimpleAgilityFeedbackLoopPattern|Simple Agility Feedback Loop]].

---

**Cluster Hub:** [[AgentCookbook|Agent Cookbook]] (`agent-cookbook`)
