---
date: '2026-10-24'
related:
- AgentCookbook
- AgentGradeContentDesign
- StructuralSpineDesign
- WhatToDoWhenANonExistentFunctionIsCited
- WikiAuditDemandLoop
- ChoosingARetrievalMode
- McpAuthoringSurfaceReference
audience:
- humans
- agents
status: active
verified_by: wikantik-cli
kg_include: true
summary: 'End-to-end verification runbook: four quality gates, ConfidenceComputer
  math, verify_pages checks, and stamping authoritative pages via mark_page_verified.'
canonical_id: 01KQ4EMR2624F7E7F8PM46AEHG
runbook:
  when_to_use:
  - An AI-generated page has been spot-checked and is ready to mark verified
  - You are working through a stale-page triage list and want to mark several at once
  - A trusted author wants to flag an old page as known-stale rather than re-read
    it
  inputs:
  - A list of page slugs to mark
  - The verifier's login name (defaults to the MCP client's name, which is rarely
    a trusted author)
  - Optional explicit confidence override (`stale` is the common one)
  - Optional change note recorded with the save
  steps:
  - Spot-check each page — read it end-to-end and verify the claims against the codebase
    or external sources
  - Confirm the verifier's login is in the trusted_authors table (there is no admin
    UI for it) — only a recent stamp by a trusted verifier gives `authoritative`;
    a stamp by anyone else gives `provisional`
  - Call /wikantik-admin-mcp/mark_page_verified with `slugs` set to the slug list
    and `verifier` set to the trusted login (without it the verifier defaults to the
    MCP client name)
  - 'Read /admin/verification to confirm the new state — `confidence: authoritative`
    with a recent `verified_at`'
  - 'For known-stale pages, pass `confidence: stale` explicitly so the rule engine
    doesn''t over-promote them'
  pitfalls:
  - Marking pages verified without actually reading them — "mass-verify" is an antipattern
    that destroys the signal
  - Forgetting the trusted author — the stamp lands but confidence stays `provisional`;
    the verifier defaults to the MCP client name, so pass `verifier` explicitly and
    check the `trusted_authors` table
  - 'Pinning `confidence: stale` for a page that''s actually fine — the field is for
    known-stale, not for "I''m not sure"'
  - Passing `pageNames` — the tool takes `slugs` only, and a call without it is rejected
  - Mixing batch sizes — verify_pages handles structural/SEO checks separately; mark_page_verified
    is the verification-flag write
  related_tools:
  - /wikantik-admin-mcp/mark_page_verified
  - /wikantik-admin-mcp/verify_pages
  - /admin/verification
  references:
  - AgentGradeContentDesign
  - StructuralSpineDesign
tags:
- verification
- confidence
- runbook
- agent-context
- quality-gates
- mcp
title: 'Verifying an Agent-Generated Page: Quality Gates, ConfidenceComputer, and
  Stamping'
type: runbook
cluster: agent-cookbook
verified_at: '2026-10-24T23:09:33.237252712Z'
---
# Verifying an Agent-Generated Page: Quality Gates, `ConfidenceComputer`, and `mark_page_verified`

Verification metadata (`verified_at`, `verified_by`, and effective `confidence`) is the trust contract that tells both human engineers and downstream AI agents whether a wiki page has been rigorously checked against primary sources. In Wikantik, verification is **never** a mechanical rubber-stamp: a page is stamped with `mark_page_verified` only after passing **four mandatory verification gates** (Factual Grounding, Structural/Schema Lint, KaTeX/Mermaid Integrity, and Live `assemble_bundle` Retrieval Verification).

---

## 1. End-to-End Page Verification Pipeline & Confidence State Machine

```mermaid
flowchart LR
    subgraph Gate1["Gate 1: Source-of-Truth Fact Check"]
        Read["Read Page End-to-End"]
        CodeCheck["Codebase Claims:<br/>Grep wikantik.properties,<br/>McpProtocolIT, web.xml, git log"]
        WebCheck["Domain Claims:<br/>Verify equations, versions,<br/>benchmarks & escape currency (\$)"]
        Read --> CodeCheck & WebCheck
    end

    subgraph Gate2["Gate 2: Structural & Schema Gate"]
        VP["Call verify_pages(slugs=[...])<br/>0 brokenLinks · 0 missingMetadata<br/>0 seoWarnings · 120-155c summary"]
        CodeCheck & WebCheck --> VP
    end

    subgraph Gate3["Gate 3: Live Retrieval Gate"]
        Bundle["Call assemble_bundle(query=...)<br/>Target page ranks in Top 3<br/>with strong coverage"]
        VP --> Bundle
    end

    subgraph Gate4["Gate 4: Stamp & Confidence Resolution"]
        Stamp["Call mark_page_verified(<br/>slugs=[...], verifier='jakefear')"]
        Comp["ConfidenceComputer:<br/>trusted_authors + 90d window<br/>-> confidence = authoritative"]
        Bundle --> Stamp --> Comp
    end
```

---

## 2. How `ConfidenceComputer` Calculates Effective Page Confidence

When `mark_page_verified` writes `verified_at = NOW()` and `verified_by = <verifier>` into a page's YAML frontmatter, the post-save structural spine listener syncs those fields into the PostgreSQL `page_verification` table. At read time (`get_page_for_agent`, `assemble_bundle`, and `/admin/verification`), `ConfidenceComputer` evaluates the effective confidence tier using a deterministic precedence rule over the staleness window $W_{\text{stale}}$ (configured by `wikantik.verification.stale_days`, default `90` days) and the `trusted_authors` table:

$$
\text{confidence}(P) = \begin{cases}
P.\text{confidence\_override} & \text{if explicit } \text{confidence:} \text{ is set in frontmatter} \\
\text{stale} & \text{if } P.\text{verified\_at} = \varnothing \;\lor\; (\text{now} - P.\text{verified\_at}) > W_{\text{stale}} \\
\text{authoritative} & \text{if } (\text{now} - P.\text{verified\_at}) \le W_{\text{stale}} \;\land\; P.\text{verified\_by} \in \text{trusted\_authors} \\
\text{provisional} & \text{if } (\text{now} - P.\text{verified\_at}) \le W_{\text{stale}} \;\land\; P.\text{verified\_by} \notin \text{trusted\_authors}
\end{cases}
$$

> **Critical Distinction (`trusted_authors` Table vs. `wikantik.frontmatter.trustedAuthors` Property):**
> - The PostgreSQL **`trusted_authors` table** (keyed by `login_name`) is what `ConfidenceComputer` reads to grant `authoritative` status.
> - The `wikantik.frontmatter.trustedAuthors` property only controls whether saving a page emits an advisory `verified_by.untrusted` lint warning—it never elevates `provisional` to `authoritative`.
> - If you omit `verifier` (or `verifiedBy`) when calling `mark_page_verified`, the server defaults to the MCP client's `clientInfo.name` (e.g., `claude-code` or `wikantik-cli`), which is **not** in `trusted_authors` and therefore yields `provisional`. Always pass the trusted human login explicitly when executing an authorized verification pass.

---

## 3. The Four Mandatory Pre-Stamp Quality Gates

Before calling `mark_page_verified`, verify all four gates:

| Gate | Verification Command / Tool | Pass Criteria |
| :--- | :--- | :--- |
| **1. Factual & Codebase Grounding** | `grep -rn`, `CHANGELOG.md`, web search (see [[WhatToDoWhenANonExistentFunctionIsCited|Non-Existent Function Runbook]]) | Every cited Java class, config property, MCP tool name, CLI flag, or external benchmark is confirmed to exist. |
| **2. Frontmatter, Link & Math Integrity** | `verify_pages(slugs=[...], checks=["broken_links", "metadata_completeness", "seo_readiness", "heading_structure"])` | `totalBrokenLinks: 0`, `missingMetadata: []`, `seoWarnings: []`, summary is **120–155 chars**, all currency is escaped (`\$100`), display math `$$` delimiters sit on dedicated lines, and `**Cluster Hub:**` link is present. |
| **3. Visual & Structural Depth** | `./bin/wikantik inspect <Slug>` and `./bin/wikantik lint <Slug>` | Deep-dive articles contain `1,200–2,500+ words` (Hubs: `500–800+ words`), at least one `mermaid` diagram, comparative Markdown tables, and topic-specific `##` headings. |
| **4. Live `assemble_bundle` Retrieval Rank** | `./bin/wikantik test-retrieval --slug <Slug> "<target query>"` or `assemble_bundle` | The page's primary sections surface in the top results with `coverage.confidence = "strong"`. |

---

## 4. Worked JSON-RPC Sequence

### Step 1: Run `verify_pages` on `/wikantik-admin-mcp`
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "verify_pages",
    "arguments": {
      "slugs": ["WikantikSearchAndRetrieval", "PageGraphVsKnowledgeGraph"],
      "checks": [
        "broken_links",
        "metadata_completeness",
        "seo_readiness",
        "heading_structure"
      ]
    }
  }
}
```

### Step 2: Stamp `mark_page_verified` on `/wikantik-admin-mcp`
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "mark_page_verified",
    "arguments": {
      "slugs": ["WikantikSearchAndRetrieval", "PageGraphVsKnowledgeGraph"],
      "verifier": "jakefear"
    }
  }
}
```

> **Note on System Pages:** System pages such as `About` or `Main` refuse `mark_page_verified` stamps over MCP (`"system page — refusing to stamp via MCP"`). That is expected behavior enforced by `SystemPageRegistry`.

---

## 5. Pitfalls & Anti-Patterns

- **Mass-Verifying Unread Pages:** Never pass 100 slugs to `mark_page_verified` without reading and checking each page. Poisoning the `authoritative` signal destroys agent trust across the entire cluster.
- **Passing `pageNames` Instead of `slugs`:** Both `verify_pages` and `mark_page_verified` require the JSON property `"slugs": [...]`. Passing `"pageNames"` or `"pages"` fails JSON schema validation.
- **Misusing `confidence: stale`:** Only pin `confidence: stale` when a trusted reviewer has confirmed a page contains outdated claims that cannot be fixed immediately—never use it to mean "unreviewed".

---

**Cluster Hub:** [[AgentCookbook|Agent Cookbook]] (`agent-cookbook`)
