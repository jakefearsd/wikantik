---
date: ''
title: Agent Cookbook
related:
- AgentGradeContentDesign
- StructuralSpineDesign
- HybridRetrieval
- GoodMcpDesign
audience:
- agents
- humans
canonical_id: 01KQ44QTJGS34S8VKGDMPTK9TC
cluster: agent-cookbook
type: hub
tags:
- agent-context
- runbook
- cookbook
summary: 'Scenario-keyed runbooks for AI coding agents working with this wiki and
  codebase: retrieval, authoring, tooling, setup and maintenance.'
---
# Agent Cookbook

Scenario-keyed reference for AI coding agents that need to *do* something
specific against this wiki and its code. Each entry below is a
`type: runbook` page: structured frontmatter (`when_to_use`, `steps`,
`pitfalls`, `related_tools`, `references`) plus a short freeform body.
Read the page when the named scenario applies; cite it by canonical_id
when you act on it.

## How to use this cookbook

1. Pick the runbook whose `when_to_use` matches your situation.
2. Read its `runbook:` block over `/api/pages/for-agent/{canonical_id}` or
   `get_page_for_agent` on `/knowledge-mcp` — the steps are the contract.
3. The body prose elaborates and links to references.

The cookbook is small on purpose. New runbooks are added when observed
agent thrashing in MCP transcripts can be summarised in two-to-five
imperative steps. Topic-keyed how-tos go in narrative articles instead.

## Members

### Setup

- [ConnectingClaudeCodeToWikantikMcp](ConnectingClaudeCodeToWikantikMcp) — which URL and API key go in which file when wiring Claude Code to the read-only and admin MCP servers.

### Retrieval and citation

- [ChoosingARetrievalMode](ChoosingARetrievalMode) — BM25 vs. hybrid vs. graph traversal.
- [FindingTheRightMcpTool](FindingTheRightMcpTool) — decision tree across the live MCP surface.
- [CitingAWikiPage](CitingAWikiPage) — use canonical_id, never slug.
- [InterpretingHybridRetrievalMetrics](InterpretingHybridRetrievalMetrics) — what the Prometheus gauges mean.
- [HandlingEmbeddingServiceOutages](HandlingEmbeddingServiceOutages) — fail-closed BM25 fallback.
- [WhatToDoWhenANonExistentFunctionIsCited](WhatToDoWhenANonExistentFunctionIsCited) — verify before recommending.

### Authoring and contribution

- [VerifyingAnAgentGeneratedPage](VerifyingAnAgentGeneratedPage) — `mark_page_verified` + triage.
- [ProposingKnowledgeGraphEdges](ProposingKnowledgeGraphEdges) — `propose_knowledge` workflow.
- [RunningTheRetrievalQualityHarness](RunningTheRetrievalQualityHarness) — manual invocation.

### Code and tooling

- [WritingANewMcpTool](WritingANewMcpTool) — scaffolding, registration, description convention.
- [ExploringAModulesApiSurface](ExploringAModulesApiSurface) — grep + MCP patterns for unfamiliar modules.
- [AnsweringRestApiQuestions](AnsweringRestApiQuestions) — find the endpoint, method, and permission model.
- [PlanningAMigrationChange](PlanningAMigrationChange) — what tables, files, and tests to touch.
- [DebuggingFailingIntegrationTests](DebuggingFailingIntegrationTests) — port conflicts, Cargo, pgvector.
- [BuildingAndDeployingLocally](BuildingAndDeployingLocally) — one-screen canonical flow.
- [RunningTheJudgeExperimentHarness](RunningTheJudgeExperimentHarness) — `bin/kg-judge-experiment.sh`: compare Knowledge Graph proposal-judge models before changing the extractor's `--judge` default.

### Wiki maintenance and content strategy

- [WikiAuditDemandLoop](WikiAuditDemandLoop) — the recurring audit loop: sweep under-served queries with `list_retrieval_queries`, confirm with `assemble_bundle`, flag stale pages.
- [ContentFeedbackLoopFocus](ContentFeedbackLoopFocus) — what content agents should work on, and what to avoid, derived from measured search data (see [ContentIntelligenceDesign](ContentIntelligenceDesign)).

## Authoring a new runbook

1. Pick a scenario that has bitten you (or an agent you're observing) at
   least twice.
2. Phrase it as a `when_to_use` entry — the trigger should be unambiguous.
3. Write 2–5 imperative `steps`.
4. List the `pitfalls` you'd warn a colleague about. `(none known)` is
   permitted but discouraged.
5. Save through the wiki — `RunbookValidationPageFilter` will reject
   schema violations with a `FilterException` naming the issue kind.

## Editorial / ongoing

This section captures the maintenance cadence for the cookbook and the
verification system that backs it. Neither item needs an engineering
pass — both grow over time as the project evolves.

### Cookbook expansion

The 15 seed runbooks listed under [Members](#members) (everything
except the Setup and Wiki maintenance groups and
RunningTheJudgeExperimentHarness) cover the scenarios that were obvious
at launch (2026-04-25). New runbooks land when one of three triggers
fires:

- **Observed agent thrashing.** An MCP transcript shows an agent
  retrying the same operation 3+ times with different phrasings, or
  reaching for the wrong tool repeatedly. If the right behaviour can be
  summarised in 2–5 imperative steps, it's a runbook candidate.
- **A new subsystem ships** that an agent will need to use. Write the
  runbook *with* the subsystem's first release, not after the
  third bug report.
- **Recurring human questions.** If the same human asks the same
  question of the wiki twice, that's data — write the runbook.

What does NOT belong as a runbook:

- Topic explainers ("how does X work?") — those go in narrative articles.
- One-off recipes that won't generalise.
- Speculative procedures with no observed need.

The validator (`FrontmatterRunbookValidator`) enforces structural rules
at save time: ≥1 `when_to_use`, ≥2 `steps`, ≥1 `pitfalls`;
`related_tools` entries that are either a path under `/api/`, `/admin/`,
`/knowledge-mcp/`, `/wikantik-admin-mcp/` or `/tools/`, or a bare
snake_case or kebab-case tool name; and `references` that resolve to
canonical_ids or page titles. A save that violates any of these returns
`FilterException` naming the issue kind.

### Trusted-authors registry growth

Verification confidence is computed by `ConfidenceComputer` from
`verified_at` + the verifier's membership in the `trusted_authors`
table:

- A `mark_page_verified` call from a trusted author within the last
  90 days → `confidence: authoritative`.
- A `mark_page_verified` call from a non-trusted author → stays
  `provisional` (the timestamp lands; the confidence does not promote).
- No verification, or verification older than 90 days →
  `confidence: stale`.

The 90-day window is `wikantik.verification.stale_days` (default 90),
and a page can pin its own `confidence:` in frontmatter, which
overrides the computed value.

The wiki currently runs single-developer, so `trusted_authors` has one
row (`jakefear`). Adding contributors is a one-line `INSERT` per
person:

```sql
INSERT INTO trusted_authors (login_name, notes)
VALUES ('alice', 'primary maintainer, security')
ON CONFLICT (login_name) DO NOTHING;
```

`TrustedAuthorsDao` loads the table once at startup and caches it; the
cache refreshes only on writes made through that class, and nothing in
the application writes to it. A row added with SQL is therefore not
seen until the wiki restarts; after the restart the new author's
verifications start producing `authoritative` results.

When to add a new trusted author:

- They've shipped at least one substantive review of an existing
  page (a non-trivial edit, not a typo fix).
- They understand the verification semantics (see
  [VerifyingAnAgentGeneratedPage](VerifyingAnAgentGeneratedPage)).
- They're willing to be cited as the verifier of pages they mark.

When to remove a trusted author:

- They're no longer active.
- They've left the project.
- A pattern of mass-marking emerges — verification only retains
  signal if every `authoritative` mark is backed by a real read.
