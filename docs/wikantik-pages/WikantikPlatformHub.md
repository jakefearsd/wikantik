---
audience:
- humans
related:
- SimpleAgilityStackHub
- WikantikArchitecture
- WikantikSearchAndRetrieval
- WikantikKnowledgeGraph
- WikantikEvolutionFromJSPWiki
- WikantikCritiqueAndMarketPosition
- ContentIntelligenceDesign
verified_at: '2026-05-02T00:00:00Z'
date: 2026-05-02T00:00:00Z
cluster: wikantik-platform
type: hub
title: 'Wikantik Platform Hub: Knowledge Systems, Hybrid Search, and AI Agent Integrations'
auto-generated: false
tags:
- wikantik-platform
- knowledge-base
- hybrid-search
- knowledge-graphs
- mcp
- agentic-ai
- architecture
- simple-agility
canonical_id: 01KQTCA4WNJWEPHWKVP4KYJ22Y
summary: 'Wikantik platform hub: architecture, hybrid BM25 plus dense search, the
  Knowledge Graph and ontology, MCP agent integrations, and the RAG roadmap.'
kg_include: true
verified_by: jakefear
status: active
---
# Wikantik Platform Hub: Knowledge Systems, Hybrid Search, and AI Agent Integrations

> **Part of the [Simple Agility stack](SimpleAgilityStackHub).** Wikantik is the stack's **knowledge and content** component — an agentic wiki serving software documentation, support content, and Search Engine Optimization (SEO) content, and the runbook store the stack's on-call automation reads. It optimizes itself by joining its own data against what the stack's observability plane (jakemon) makes visible; the first fully-built instance of that loop is [ContentIntelligenceDesign](ContentIntelligenceDesign), and the pattern it follows is [SimpleAgilityFeedbackLoopPattern](SimpleAgilityFeedbackLoopPattern). Its scoped `admin:<area>` grants and admin Model Context Protocol (MCP) tools are the template for the agentic surfaces every stack product exposes ([SimpleAgilityOnCallAutomation](SimpleAgilityOnCallAutomation)).
>
> **Stack standards Wikantik follows** (see [SimpleAgilityDecisionLog](SimpleAgilityDecisionLog)):
>
> - **Logs:** Elastic Common Schema (ECS) JSON on stdout, with `service.name=wikantik` and `trace.id` for cross-product correlation ([telemetry contract](SimpleAgilityTelemetryContract) §2). Wikantik already uses log4j2's ECS layout; the remaining work is items W1 to W6 on [SimpleAgilityTelemetryAdoption](SimpleAgilityTelemetryAdoption).
> - **Instance names:** each deployment is named `wiki-<env>` (`wiki-prod`, `wiki-support`, `wiki-dev`). The plane attaches this name as the `service_instance` label.

Wikantik is a collaborative knowledge platform that serves both human knowledge workers and autonomous AI agents. It combines a React web UI and Markdown pages with YAML frontmatter, Lucene and dense-vector hybrid search, an LLM-extracted Knowledge Graph with an Apache Jena SPARQL ontology, and Model Context Protocol (MCP) tool surfaces, so the wiki doubles as a queryable external memory for AI workflows.

This hub organizes platform architecture, administrative governance, search internals, and agent integrations.

---

## 1. Quick-Reference: Platform Capabilities

| Capability | Underlying technology | Core function |
|---|---|---|
| Hybrid search | Lucene BM25 plus dense embeddings (pgvector, `lucene-hnsw` or in-memory) fused with Reciprocal Rank Fusion; BM25-only fallback when the embedder is down | Page search and cited context bundles (`GET /api/bundle`) |
| Knowledge Graph | LLM-extracted entities and edges in PostgreSQL (`kg_*` tables), plus an Apache Jena RDF/OWL ontology with a public read-only SPARQL endpoint | Entity traversal and curation, `sparql_query` |
| AI agent interface | Model Context Protocol: `/wikantik-admin-mcp` (29 tools) and `/knowledge-mcp` (21 read-only tools when fully wired); OpenAPI tool server at `/tools/*` | Retrieval, authoring, curation, session briefings |
| Collaborative editing | Markdown with YAML frontmatter, a CodeMirror editor in the React single-page app, per-page version history and diffs | Authoring, review, restore |
| Security and governance | JAAS authentication (database accounts, SSO, SCIM provisioning), page ACLs, database-backed policy grants, scoped `admin:<area>` grants, API-key scopes (`tools`, `mcp_read`, `mcp_admin`) | Who may view, edit and administer what |

---

## 2. Core Hubs & Guides

### Architecture and history
- **[WikantikArchitecture](WikantikArchitecture)**: Modular Java engine, hybrid file and PostgreSQL storage, BM25 plus dense retrieval, Knowledge Graph and ontology, and the agent MCP surface.
- **[WikantikSearchAndRetrieval](WikantikSearchAndRetrieval)**: The hybrid search pipeline: BM25 lexical retrieval, dense vector retrieval, and RRF fusion.
- **[WikantikKnowledgeGraph](WikantikKnowledgeGraph)**: How the LLM-extracted Knowledge Graph works: nodes, edges, provenance, the proposal workflow, the ontology layer and the agent surfaces.
- **[WikantikEvolutionFromJSPWiki](WikantikEvolutionFromJSPWiki)**: How Wikantik evolved from the Apache JSPWiki fork, and what changed along the way.
- **[WikantikCritiqueAndMarketPosition](WikantikCritiqueAndMarketPosition)**: A candid assessment of strengths, architectural weaknesses and competitive position.
- **[GraphRerankStep](GraphRerankStep)**: Historical record of the graph-proximity rerank, removed in 2026-07.

### Hubs and guides
- **[WikantikDevelopmentHub](WikantikDevelopmentHub)**: Developer guides, MCP server internals, design records for recent features, and release runbooks.
- **[RagServiceRoadmapHub](RagServiceRoadmapHub)**: The RAG-as-a-service roadmap: capability briefs, the target architecture, and the status of each brief.
- **[WikantikAgentContextGuide](WikantikAgentContextGuide)**: Agent context briefings, the coverage signal, and the escalation ladder.
- **[AdminSecurityUi](AdminSecurityUi)**: The `/admin/security` screen for groups and policy grants, and who can open it.
- **[ContentIntelligenceDesign](ContentIntelligenceDesign)**: The content feedback loop: search-visibility history, agent demand signals, a gated opportunity backlog and effect measurement.
- **[SimpleAgilityStackHub](SimpleAgilityStackHub)**: The stack Wikantik is part of — observability plane, telemetry contract, feedback-loop pattern, on-call automation.
