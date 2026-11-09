---
canonical_id: 01KQTCA4WNJWEPHWKVP4KYJ22Y
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
title: 'Wikantik Platform Hub: Knowledge Systems, Hybrid Search, and AI Agent Integrations'
cluster: wikantik-platform
type: hub
verified_at: '2026-10-24T23:02:37.552893905Z'
date: '2026-10-24'
related:
- About
- SimpleAgilityStackHub
- WikantikArchitecture
- WikantikSearchAndRetrieval
- WikantikKnowledgeGraph
- PageGraphVsKnowledgeGraph
- WikantikEvolutionFromJSPWiki
- WikantikCritiqueAndMarketPosition
- ContentIntelligenceDesign
- WikantikDevelopmentHub
- AgentCookbook
- RagServiceRoadmapHub
- GraphRerankStep
audience:
- humans
- agents
verified_by: wikantik-cli
status: active
summary: 'Wikantik platform hub: system architecture, BM25+pgvector hybrid search,
  Page Graph and SPARQL ontology, 36 MCP tools, and the RAG roadmap.'
---
# Wikantik Platform Hub: Knowledge Systems, Hybrid Search, and AI Agent Integrations

> **Part of the [[SimpleAgilityStackHub|Simple Agility Stack]].** Wikantik is the stack's **semantic knowledge and content engine**—an agent-grade wiki serving software architecture, operational runbooks, and domain research while doubling as the external memory and contract registry (`SA-13`) for autonomous coding and publishing agents. It optimizes its own corpus through [[ContentIntelligenceDesign|Closed-Loop Content Intelligence]] (following [[SimpleAgilityFeedbackLoopPattern|Simple Agility Feedback Loop Pattern]]) and emits Elastic Common Schema (ECS) JSON telemetry to `jakemon` per [[SimpleAgilityTelemetryContract|Simple Agility Telemetry Contract]].
>
> **New to Wikantik?** Start with the **[[About|About Wikantik: Manifesto & System Blueprint]]** for the core design philosophy and three end-to-end engineering and publishing workflows.

Wikantik bridges human technical authoring and machine reasoning. Every page is stored as human-readable CommonMark Markdown with validated YAML frontmatter (`FrontmatterSchema`), indexed into a dual **Apache Lucene BM25 + PostgreSQL 18 `pgvector` HNSW** retrieval pipeline, mapped across the **Page Graph** and **Citation Graph** plus an **Apache Jena TDB2 RDF/OWL ontology** of pages and concepts (the LLM-extracted Knowledge Graph was retired in November 2026), and exposed through **36 Model Context Protocol (MCP) tools** (16 read-only, 20 admin).

---

## 1. Platform Topology & Subsystem Map

```mermaid
flowchart TB
    subgraph Manifesto["1. Vision, Architecture & Evolution"]
        About["[[About]]<br/>Manifesto, 5 Commitments &<br/>3 Flagship Workflows"]
        Arch["[[WikantikArchitecture]]<br/>24-Module Java 25 Monolith,<br/>Three Stores & Security"]
        Evol["[[WikantikEvolutionFromJSPWiki]]<br/>March–October 2026 Evolution<br/>Through Release 2.4.56"]
        Crit["[[WikantikCritiqueAndMarketPosition]]<br/>Strengths, Trade-Offs &<br/>Competitive Matrix"]
    end

    subgraph Retrieval["2. Hybrid Search & RAG Context Bundles"]
        Search["[[WikantikSearchAndRetrieval]]<br/>Heading-Faithful Chunking,<br/>Contextual Embeddings & RRF Math"]
        Bundle["[[WikantikRagContextBundle]]<br/>GET /api/bundle & assemble_bundle<br/>Version-Pinned Citations"]
        AgentCtx["[[WikantikAgentContextGuide]]<br/>Session Briefings (get_briefing)<br/>& Coverage Escalation Ladder"]
    end

    subgraph Graphs["3. Graphs, Ontology & Closed-Loop Intelligence"]
        PGvKG["[[PageGraphVsKnowledgeGraph]]<br/>Page Graph vs. Knowledge Graph<br/>vs. Citations vs. RDF"]
        KG["[[WikantikKnowledgeGraph]]<br/>Retired Nov 2026 (historical):<br/>9 Classes, 21 Predicates, SHACL Gate"]
        CI["[[ContentIntelligenceDesign]]<br/>GSC + Bing + Agent Query Logs<br/>& 28-Day DiD EffectEvaluator"]
    end

    subgraph Roadmap["4. RAG-as-a-Service Roadmap"]
        RagHub["[[RagServiceRoadmapHub]]<br/>Target Architecture, Connectors,<br/>Composable Reranking & SDKs"]
    end

    Manifesto --> Retrieval --> Graphs --> Roadmap
```

---

## 2. Quick-Reference: Core Platform Capabilities

| Capability Layer | Underlying Technologies | Primary Human & Agent Interfaces |
| :--- | :--- | :--- |
| **Authoring & Structural Spine** | CommonMark Markdown, YAML frontmatter (`FrontmatterSchema`), 26-char ULID `canonical_id`, `ADR-0009` Cluster Declarations, React 19 SPA with CodeMirror 6 Live Preview | Browser Reader/Editor (`/`), `[[WikiLinks]]`, `![[Embeds]]`, Obsidian Vault Import/Export, `/wikantik-admin-mcp` (`update_page`, `write_pages`, `verify_pages`, `rename_cluster`) |
| **Hybrid Search & RAG Bundles** | Apache Lucene BM25 + PostgreSQL 18 `pgvector 0.8.6` HNSW fused via Reciprocal Rank Fusion ($k=60$); context-prefixed chunk embeddings (`+14%` `Recall@12`) | Browser Search (`GET /api/search`), Cited Context Bundles (`GET /api/bundle`, `assemble_bundle`), Session Briefings (`GET /api/briefing`, `get_briefing`) |
| **Page Graph, Citations & RDF Ontology** | Page Graph (`page_links`), Citation Graph (`citations`), Apache Jena TDB2 (`wikantik.ttl` T-Box with the page and concept graphs projected from the wiki); related pages come from page embeddings (the Knowledge Graph was retired in November 2026) | Interactive Cytoscape UI (`/page-graph`), Public Linked Data (`/sparql`, `/id/*`, `/export/*`), MCP (`get_page_neighbors`, `sparql_query`) |
| **Agent Tool Surfaces (38 Tools)** | Streamable HTTP MCP servers with database-backed API keys (`mcp_read`, `mcp_admin`, `tools`) and sliding-window rate limiters | `/knowledge-mcp` (**16 read-only tools**), `/wikantik-admin-mcp` (**20 authoring/telemetry tools**), `/tools/*` (**2 OpenAPI 3.1 tools**) |
| **Closed-Loop Content Intelligence** | `wikantik-insights` fact store joining Google Search Console, Bing Webmaster Tools, and `retrieval_query_log` with a 28-day Difference-in-Differences `EffectEvaluator` | Admin UI (`/admin/insights`), MCP (`list_content_opportunities`, `snooze_opportunity`, `list_retrieval_queries`, `ping_search_engines`) |
| **Enterprise Security & Ops** | JAAS auth, PostgreSQL `policy_grants`, scoped `admin:<area>` grants, pac4j OIDC/SAML SSO, SCIM 2.0 (`/scim/v2`), tamper-evident hash-chained audit log | `/admin/security` ([[AdminSecurityUi|Admin Security UI]]), `/admin/apikeys`, `/admin/audit`, Prometheus `/metrics` + ECS JSON logs |

---

## 3. Core Platform Articles & Architecture Guides

### I. Manifesto, System Architecture & Market Position
- **[[About|About Wikantik: Manifesto and Architecture of the Agent-Grade Knowledge Platform]]** — The flagship manifesto, five architectural commitments, mathematical retrieval foundations, MCP tool inventory, and three end-to-end workflows (cross-repo `SA-13` briefings, self-healing runbooks, and ontology-driven publishing).
- **[[WikantikArchitecture|Wikantik System Architecture]]** — Deep reference for the 24-module Java 25 reactor, three-store persistence model (filesystem + PostgreSQL 18 + Jena TDB2), rendering pipeline, security model, and architectural critique.
- **[[WikantikEvolutionFromJSPWiki|History and Evolution: From Apache JSPWiki to Wikantik 2.4.56]]** — How the March–October 2026 modernization transformed Apache JSPWiki across four engineering phases.
- **[[WikantikCritiqueAndMarketPosition|Wikantik: Critique and Market Position]]** — Candid assessment of strengths, operational trade-offs, and feature comparison against BookStack, Outline, Wiki.js, MediaWiki, Confluence, and Notion.

### II. Hybrid Retrieval, Context Bundles & the Retired Knowledge Graph
- **[[WikantikSearchAndRetrieval|Wikantik Hybrid Search Architecture]]** — Heading-faithful chunking (`ContentChunker`), contextual document embeddings (`EmbeddingTextBuilder`), BM25 + `pgvector` RRF equations, and retrieval mode selection.
- **[[WikantikKnowledgeGraph|The Wikantik Knowledge Graph (Retired)]]** — Historical record of the LLM-extracted Knowledge Graph (9 entity classes, 21 relationship predicates, mention-centroids, proposal workflow), retired in November 2026.
- **[[PageGraphVsKnowledgeGraph|Page Graph vs. Knowledge Graph]]** — Canonical boundary between editorial wikilinks (`page_links`), the retired semantic entities (`kg_*`), section-pinned citations (`citations`), and the RDF ontology.
- **[[GraphRerankStep|Graph Rerank Step (Historical)]]** — Why page-level co-mention BFS reranking measured zero net recall lift in the `2026-06-16` ceiling experiment and was deleted in `2026-07`.

### III. Architectural Governance & Normative Contracts
- **[[SimpleAgilityStackHub|Simple Agility Stack Hub]]** (`simple-agility`) — Ecosystem architecture and cross-repo coordination.
- **[[SimpleAgilityDecisionLog|Simple Agility Architectural Decision Log]]** — Normative architectural decisions (SA-1 through SA-13) governing Wikantik APIs, storage, and cross-repo boundaries.
- **[[SimpleAgilityTelemetryAdoption|Simple Agility Telemetry Adoption Matrix]]** — Stack-wide observability, OpenTelemetry, and structured JSON logging contracts.

### III. Companion Hubs & RAG-as-a-Service Roadmap
- **[[WikantikDevelopmentHub|Wikantik Platform Development Hub]]** (`wikantik-development`) — Developer internals, MCP protocol specifications ([[McpIntegration|MCP Integration]]), operator runbooks ([[WikantikOperations|Wikantik Operations]]), and feature design records.
- **[[AgentCookbook|Agent Cookbook]]** (`agent-cookbook`) — Scenario-keyed runbooks for coding and curation agents working with Wikantik MCP tools and the codebase.
- **[[RagServiceRoadmapHub|RAG-as-a-Service Roadmap Hub]]** — Target architecture ([[RoadmapTargetArchitecture|Target Architecture]]) and capability briefs covering [[RoadmapAgenticRetrieval|Agentic Retrieval]], [[RoadmapComposableReranking|Composable Reranking]], [[RoadmapConnectorFramework|Connector Framework]], [[RoadmapExternalAclInheritance|External ACL Inheritance]], [[RoadmapMultimodalIngestion|Multimodal Ingestion]], [[RoadmapRetrievalEvaluationObservability|Retrieval Evaluation & Observability]], [[RoadmapClientSdkDeveloperExperience|Client SDK & DX]], [[RoadmapMultiTenancyPosture|Multi-Tenancy Posture]], [[RoadmapCompliancePosture|Compliance Posture]], and [[RoadmapPackagingAndPricing|Open-Core Packaging & Pricing]].
