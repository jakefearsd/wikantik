---
audience:
- humans
type: hub
summary: 'Wikantik development hub: architecture, MCP agent surfaces, the retired
  Knowledge Graph, hybrid retrieval, feature design records and release runbooks.'
related:
- KgInclusionPolicy
- TextFormattingRules
- McpIntegration
title: 'Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent
  Briefings'
cluster: wikantik-development
tags:
- wikantik
- wikantik-development
- mcp
- knowledge-graphs
- search-engine
- rag
- architecture
date: '2026-05-15'
canonical_id: 01J7KQTCD38PBFSD7TD6ACJFDA
status: active
---
# Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent Briefings

Wikantik is a knowledge platform and agentic-memory engine designed to bridge human technical authoring with autonomous AI coding agents. It is a modernized Java 25 fork of the Apache JSPWiki engine with a React single-page UI, Lucene and dense-vector hybrid search, an Apache Jena SPARQL ontology, and Model Context Protocol (MCP) tool surfaces. (An LLM-extracted Knowledge Graph was part of the platform until it was retired in November 2026; related pages now come from page embeddings.) It serves both as a wiki and as a retrieval engine for AI agent sessions.

This hub details Wikantik platform internals, MCP integration patterns, search pipeline architectures, feature design records, and developer and release guides.

---

## 1. Quick-Reference: Wikantik Architecture Stack

| Layer | Technologies | Primary functionality |
|---|---|---|
| Agent surface | MCP servers `/wikantik-admin-mcp` (20 tools) and `/knowledge-mcp` (16 read-only tools when fully wired); OpenAPI tool server `/tools/*` (2 tools) | Authoring, retrieval, session briefings |
| Ontology and Page Graph | Apache Jena RDF/OWL ontology with read-only SPARQL; Page Graph of wikilink edges; embedding-based Similar pages (the LLM-extracted Knowledge Graph, `kg_*` tables, was retired in November 2026) | Ontology queries, link navigation, related pages |
| Hybrid search | Apache Lucene BM25 plus dense embeddings (pgvector, `lucene-hnsw` or in-memory), fused with Reciprocal Rank Fusion; BM25-only fallback when the embedder is down | Page search and cited context bundles |
| Storage and versioning | File-system page provider with per-page version history (`VersioningFileProvider`); PostgreSQL for users, groups, policy grants, embeddings and operational data, with the schema defined only by `bin/db/migrations/V*.sql` | Version history, diffs, durable metadata |
| Web UI and REST API | Java 25, Tomcat 11, React single-page app built with Vite; REST under `/api/*` and `/admin/*` | Reading, editing, curation, administration |

---

## 2. Core Subsystems & Developer Guides

### I. Start here
- **[WikantikArchitecture](WikantikArchitecture)**: The current architecture: modular Java engine, hybrid file and PostgreSQL storage, BM25 plus dense retrieval, ontology, and the agent MCP surface.
- **[WikantikDevelopment](WikantikDevelopment)**: Architecture decisions, the TDD mandate, the CI pipeline, the Maven module structure, and the feature timeline through 2.4.x.
- **[PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph)**: The canonical explainer of the Page Graph (wikilink edges) versus the Knowledge Graph (LLM-extracted entities, retired in November 2026). Read it before touching either.
- **[TestDrivenDevelopment](TestDrivenDevelopment)**, **[MvnCheatSheet](MvnCheatSheet)** and **[ContinuousIntegration](ContinuousIntegration)**: The test-first workflow, the Maven commands, and the CI pipeline.

### II. Agent Integration & MCP Tools
- **[WikantikAgentContextGuide](WikantikAgentContextGuide)**: Session briefings (`get_briefing`), mid-session grounding with `assemble_bundle`, the coverage signal, and the escalation ladder.
- **[McpIntegration](McpIntegration)**: Wiring Claude Code, Gemini CLI and OpenAPI-only clients into Wikantik's tool surfaces.
- **[McpAuthoringSurfaceReference](McpAuthoringSurfaceReference)**: Reference for agents authoring over MCP: tool set, rate limit, optimistic locking, frontmatter merge semantics and system-page refusals.
- **[FindingTheRightMcpTool](FindingTheRightMcpTool)** and **[AgentCookbook](AgentCookbook)**: Decision tree for choosing retrieval and authoring tools, and the scenario-keyed runbooks for agents.
- **[ConnectingClaudeCodeToWikantikMcp](ConnectingClaudeCodeToWikantikMcp)**: Which URLs and API key go in which file when wiring Claude Code to the production MCP servers.
- **[McpAuditTools](McpAuditTools)**: How the MCP surface is audited: registry and instructions drift tests, wire-level protocol integration tests, write-audit log lines, and metrics.
- **[WikiAuditSkill](WikiAuditSkill)** and **[ContentProgramPolicy](ContentProgramPolicy)**: The on-demand wiki audit skill, and the standing policy for wiki content work.

### III. Retired Knowledge Graph & Semantic Metadata
The LLM-extracted Knowledge Graph (entities, the proposal workflow, the KG admin UI, the KG MCP tools and the inclusion policy) was retired in November 2026 and removed from the code; the pages below are kept as historical records.
- **[WikantikKnowledgeGraph](WikantikKnowledgeGraph)**: Historical: how the LLM-extracted Knowledge Graph worked: nodes, edges, provenance, the proposal workflow, the ontology layer and the agent surfaces.
- **[KnowledgeGraphCore](KnowledgeGraphCore)**, **[KnowledgeProposals](KnowledgeProposals)** and **[ProvenanceModel](ProvenanceModel)**: Historical: the graph store, the human-approved proposal workflow, and the provenance tiers.
- **[KgInclusionPolicy](KgInclusionPolicy)**: Historical: which pages contributed entities to the Knowledge Graph, and how a page overrode inclusion with `kg_include`.
- **[WikantikKnowledgeGraphAdmin](WikantikKnowledgeGraphAdmin)**: Historical: administering the Knowledge Graph.
- **[FrontmatterConventions](FrontmatterConventions)** and **[MarkdownLinks](MarkdownLinks)**: Frontmatter schema and validation, and link syntax including wikilinks, embeds and anchors. The full authoring reference is [TextFormattingRules](TextFormattingRules).
- **[StructuralSpineDesign](StructuralSpineDesign)**, **[AgentGradeContentDesign](AgentGradeContentDesign)** and **[ClusterDeclarationDesign](ClusterDeclarationDesign)**: The machine-queryable structural index, agent-grade content standards, and hub-declared clusters.

### IV. Search & Retrieval Quality
- **[HybridRetrieval](HybridRetrieval)**: Operator reference for BM25 plus dense hybrid retrieval: wiring, failure modes, configuration and Prometheus metrics.
- **[EvaluatingRetrievalQuality](EvaluatingRetrievalQuality)**: MRR, NDCG and hit-rate benchmarking for hybrid wiki search.
- **[InterpretingHybridRetrievalMetrics](InterpretingHybridRetrievalMetrics)**: Diagnosing reciprocal rank fusion (RRF) scores across BM25 and dense embeddings.
- **[RetrievalExperimentHarness](RetrievalExperimentHarness)** and **[WikantikSearchRefinement](WikantikSearchRefinement)**: Running the offline embedding-comparison harness, and iterating on the retrieval evaluation query set.
- **[SearchIndexAndKnowledgeGraphRebuild](SearchIndexAndKnowledgeGraphRebuild)**: Operator runbook for rebuilding chunks, Lucene and embeddings (its entity-extraction sections are historical since the Knowledge Graph retirement).
- **[RagServiceRoadmapHub](RagServiceRoadmapHub)**: The RAG-as-a-service roadmap and where each capability brief stands.
- **[KnowledgeGraphRerank](KnowledgeGraphRerank)**: Historical record of the Knowledge Graph rerank stage, removed in 2026-07 after measuring zero retrieval lift.

### V. Feature Design Records
- **[ContentIntelligenceDesign](ContentIntelligenceDesign)**: The content feedback loop: search-visibility history, agent demand signals, a gated opportunity backlog and effect measurement (the `wikantik-insights` module; shipped through v2.4.6).
- **[ObsidianExportDesign](ObsidianExportDesign)** and **[ObsidianVaultImportDesign](ObsidianVaultImportDesign)**: Exporting a slice of the wiki as an Obsidian vault zip (`GET /api/export`), and importing a zipped vault (`/api/import/obsidian/*`).
- **[NativeWikilinksDesign](NativeWikilinksDesign)**, **[EditorQualityOfLifeDesign](EditorQualityOfLifeDesign)**, **[EditorWorkspaceDesign](EditorWorkspaceDesign)** and **[LivePreviewDailyNotesDesign](LivePreviewDailyNotesDesign)**: Native `[[wikilinks]]` and embeds, editor paste and link completion, the quick switcher and command palette, and Live Preview with daily notes.
- **[ADR-001](ADR-001)**: Extracting the manager interfaces into the API module.

### VI. Build, Release & Operations
- **[WikantikReleaseAndDeployProcess](WikantikReleaseAndDeployProcess)** and **[WikantikContentVsCodeDeployment](WikantikContentVsCodeDeployment)**: Cutting and shipping a release, and why page content never ships with a code deploy.
- **[WikantikOnDocker](WikantikOnDocker)** and **[PostgreSQLLocalDeployment](PostgreSQLLocalDeployment)**: Deploying with Docker Compose, and bootstrapping a fresh PostgreSQL deployment.
- **[AdminSecurityUi](AdminSecurityUi)** and **[DatabaseBackedPermissions](DatabaseBackedPermissions)**: The `/admin/security` screen for groups and policy grants, and the `policy_grants` table behind it.
- **[SendingEmailFromTheWiki](SendingEmailFromTheWiki)** and **[AttachmentManagement](AttachmentManagement)**: SMTP relay setup for transactional email, and attachment uploads and limits.
- **[WikantikGettingStarted](WikantikGettingStarted)** and **[WikantikOperations](WikantikOperations)**: First-time setup of a local install, and the day-to-day operations runbook (scripts, container and remote deployment, load testing, the entity extractor).
- **[WikantikBackupAndRecovery](WikantikBackupAndRecovery)** and **[WikantikDatabaseMigrations](WikantikDatabaseMigrations)**: Backup tiers, the off-box copy and restore drills; and the numbered-migration schema model run by `bin/db/migrate.sh`.
- **[WikantikCloudDeployment](WikantikCloudDeployment)** and **[WikantikCloudCostTiers](WikantikCloudCostTiers)**: Running Wikantik on a cloud VM with Terraform and Docker Compose, and choosing a GenAI cost tier (`wikantik.genai.mode`).
- **[WikantikConfigurationReference](WikantikConfigurationReference)**: Every configuration key with its shipped default, generated from `ini/wikantik.properties`.
- **[WikantikTestingGuide](WikantikTestingGuide)**, **[WikantikCodeQuality](WikantikCodeQuality)**, **[WikantikFrontendArchitecture](WikantikFrontendArchitecture)** and **[WikantikLoadTesting](WikantikLoadTesting)**: The test suites and `bin/run-tests.sh`, the coverage and complexity ratchets, the React SPA's structure, and the k6 load-test harness.

### VII. Administrator Guides
- **[WikantikAdminPanel](WikantikAdminPanel)** and **[WikantikSecurityGuide](WikantikSecurityGuide)**: A map of every `/admin` screen, and the security model end to end (who counts as an administrator, policy grants, ACLs, headers, rate limits).
- **[WikantikSingleSignOn](WikantikSingleSignOn)** and **[WikantikScimProvisioning](WikantikScimProvisioning)**: OIDC/SAML sign-in through pac4j, and SCIM 2.0 user and group provisioning from an identity provider.
- **[WikantikApiKeys](WikantikApiKeys)**, **[WikantikAuditLog](WikantikAuditLog)** and **[WikantikPageOwnership](WikantikPageOwnership)**: Minting and scoping API keys for MCP and the tool server, the tamper-evident audit log, and page owners.
- **[WikantikConnectors](WikantikConnectors)** and **[WikantikDerivedPagesAndIngest](WikantikDerivedPagesAndIngest)**: Syncing external sources (the seven connector types, credentials, the SSRF egress guard), and turning documents into machine-owned derived pages with `POST /api/ingest`.
- **[WikantikContentIntelligence](WikantikContentIntelligence)**: The Search Visibility screen and the ranked opportunity backlog (`/admin/insights`), with data sources, the ingest endpoint and tuning keys.
- **[WikantikRagContextBundle](WikantikRagContextBundle)**: Reference for `/api/bundle` and `/api/briefing`: parameters, the coverage signal, version-pinned citations and configuration.
- **[WikantikOntologyManagement](WikantikOntologyManagement)**, **[WikantikDriftDashboard](WikantikDriftDashboard)** and **[WikantikHubDiscovery](WikantikHubDiscovery)**: Curating the RDF/OWL ontology, working the `/admin/drift` screen, and operating Hub Discovery.
- **[WikantikSeoAndCrawling](WikantikSeoAndCrawling)** and **[WikantikIndexingSupport](WikantikIndexingSupport)**: What Wikantik serves to crawlers, and the raw-content, change-feed and sitemap endpoints for RAG pipelines.
