---
canonical_id: 01KQ44QTJGS34S8VKGDMPTK9TC
title: Agent Cookbook
type: hub
cluster: agent-cookbook
audience: [agents, humans]
summary: Hub of scenario-keyed runbooks for AI coding agents working with this wiki and codebase. Each entry is a `type: runbook` page whose schema-validated frontmatter answers a specific recurring question.
tags:
  - agent-context
  - runbook
  - cookbook
related:
  - AgentGradeContentDesign
  - StructuralSpineDesign
  - HybridRetrieval
  - GoodMcpDesign
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

## Authoring a new runbook

1. Pick a scenario that has bitten you (or an agent you're observing) at
   least twice.
2. Phrase it as a `when_to_use` entry — the trigger should be unambiguous.
3. Write 2–5 imperative `steps`.
4. List the `pitfalls` you'd warn a colleague about. `(none known)` is
   permitted but discouraged.
5. Save through the wiki — `RunbookValidationPageFilter` will reject
   schema violations with a `FilterException` naming the issue kind.
