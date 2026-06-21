---
status: active
date: '2025-05-15'
summary: The definitive standard for "Agent-Grade" content in Wikantik. Defines the
  Structural Spine, verification metadata, and requirements for RAG-ready technical
  documentation.
tags:
- design
- structural-spine
- rag
- agent-context
- metadata
type: design
cluster: wikantik-development
canonical_id: 01KQ0P60JSKT204TXF6BTNEDDE
title: Agent-Grade Content Design
author: gemini-cli
---

# Agent-Grade Content Design: The Structural Spine

> 🌐 **Product overview:** [Agent-grade content on wikantik.com](https://www.wikantik.com/platform/agent-grade-content.html) — a plain-language walkthrough for readers and AI agents.


To ensure Wikantik content is optimized for both human readability and **Agent-Grade** RAG (Retrieval-Augmented Generation) consumption, every page must adhere to the "Structural Spine." This standard ensures that agents can reliably extract facts, navigate relationships, and assess the confidence of the information they retrieve.

## I. The Mandate: "Agent-First, Human-Friendly"

Traditional documentation is narrative-heavy and structure-light. Agent-Grade content reverses this, prioritizing machine-readable metadata and explicit structural cues without sacrificing human utility.

### A. The Structural Spine Components
1.  **Mandatory Frontmatter:** Valid YAML containing core identity and status.
2.  **Canonical Identity:** Stable ULIDs that persist across renames.
3.  **Typed Relations:** Explicit knowledge graph edges.
4.  **Verification Metadata:** Human-in-the-loop audit trails.

## II. Frontmatter Schema and Requirements

All pages MUST begin with a triple-dash fenced YAML block. The following fields are mandatory for the "Structural Spine":

```yaml
---
canonical_id: 01H8G3Z1K6... # Mandatory: 26-char ULID
title: CamelCaseTitle     # Mandatory: Matches the file name
type: article|design|runbook|reference # Mandatory: Page classification
cluster: topic-area       # Mandatory: The logical hub
status: active|draft|stale # Mandatory: Lifecycle status
date: 'YYYY-MM-DD'        # Mandatory: Last major revision date
auto-generated: false     # Mandatory: Explicitly set to false after human rewrite
summary: "50-160 character description for SEO and RAG snippet extraction."
tags: [tag1, tag2]        # Recommended: For discovery and news sitemaps
---
```

### A. The `auto-generated` Flag
The `auto-generated` flag is a **technical debt marker**. Pages marked `true` are treated as low-confidence stubs. Setting this to `false` is a declaration that the content has been rigorously overhauled to meet the high-density standards defined in `GEMINI.md`.

## III. Verification Metadata (The Confidence Protocol)

Agents need to know the *provenance* and *freshness* of information. Wikantik uses the following fields to track verification:

*   **`verified_at`**: ISO-8601 timestamp of the last manual audit.
*   **`verified_by`**: The login name of the human verifier.
*   **`confidence`**: `authoritative | provisional | stale`.
    *   *Authoritative:* Verified by a trusted expert within the last 90 days.
    *   *Provisional:* High-quality but requires final audit.
    *   *Stale:* Content is >180 days old without a re-verification.

## IV. Design for RAG (Retrieval-Augmented Generation)

To be "RAG-ready," content must be optimized for chunking and embedding.

### A. High-Density Headers
Use descriptive, noun-heavy headers (e.g., "II. The Multivariable Calculus of Backpropagation" instead of "How it works"). This provides clear semantic signals to the embedding model.

### B. Explicit Citations
When referencing other pages, use the format `[LinkText](PageName)`. For external authoritative sources, provide full URLs and context. Agents use these links to build the "Knowledge Graph" during traversal.

### C. The `runbook` Page Type
Runbooks are a specialized type for procedural content. They must include a `runbook:` block in the frontmatter:
*   `when_to_use`: Specific triggers for the procedure.
*   `steps`: An ordered list of atomic actions.
*   `pitfalls`: Common failure modes and mitigations.

## V. Verification Workflow

Before a page is considered "Agent-Grade," it must pass the following check:
1.  **Syntactic Check:** Valid YAML and CommonMark.
2.  **Semantic Check:** Summary matches body content; tags are relevant.
3.  **Identity Check:** `canonical_id` is present and unique.
4.  **Audit:** Use the `mark_page_verified` tool to stamp the metadata.

## Phase 7 — Derived agent hints + agent batch reads (2026-05-10)

Tuning of the agent surface based on external-agent feedback (Gemini's report on the discovery → action gap and per-page read tax). Four deliverables landed in one spec:

1. **Derived `agent_hints` on `/for-agent`** — new projection field with `prefer_tools` (ranked from `McpToolHintsResolver` across the page + cluster hub) and `prefer_pages` (cluster hub + intra-cluster wikilink centrality, with a 1.5× bonus for verified-authoritative pages). Computed at projection time by `AgentHintsDeriver` — zero author burden. The original temptation was an authored `agent_hints:` frontmatter block (mirroring `runbook:`), but author burden was judged too high for adoption.
2. **Hub summary overlay** — when an authored hub summary matches `(?i)^\s*(an?\s+)?index of (pages?|articles?|content)\s+(on|about|covering|for)\b`, `HubSummarySynthesizer` overlays a Top-3 highlight built from the derived `prefer_pages`. Read-only — never writes back to the page body. Signalled to consumers via `summary_synthesized: true`.
3. **`read_pages` MCP tool on `/knowledge-mcp`** — batched raw-markdown reads (cap 20). Per-page failures (`not_found`, `internal_error`) come back as data on a 200 response; only input validation fails the call. Removes the per-page read tax for cluster-spanning research.
4. **`/admin/agent-grade-audit` weak-signal report** — operator surface listing pages flagged by `no_cluster`, `no_inbound_cluster_links` (non-hubs only), `generic_hub_summary`, `no_verified_at`, or `stale_verification`. Sorted by flag count then `canonical_id`. The schema-burden alternative (mandatory frontmatter) was rejected; this audit is the operator's compensating tool to find pages worth manual improvement.

Three Prometheus counters land alongside: `wikantik_agent_hints_derivation_failures_total`, `wikantik_hub_summary_synthesis_total`, `wikantik_read_pages_partial_failures_total{reason}`.

Spec: [docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md](../superpowers/specs/2026-05-10-derived-agent-hints-design.md). Implementation plan: [docs/superpowers/plans/2026-05-10-derived-agent-hints.md](../superpowers/plans/2026-05-10-derived-agent-hints.md).

## Conclusion: The Goal of Structural Integrity

The Structural Spine is not just about formatting; it is about **Epistemic Reliability**. By adhering to these standards, we ensure that Wikantik remains a high-trust repository where agents can navigate complex technical landscapes without hallucinating or losing context.
