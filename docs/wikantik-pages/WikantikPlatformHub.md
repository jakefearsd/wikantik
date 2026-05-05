---
canonical_id: 01KQTCA4WNJWEPHWKVP4KYJ22Y
verified_at: '2026-05-04T20:57:11.590236001Z'
verified_by: gemini-cli-mcp-client
---
-----
canonical_id: 01KQTCA4WNJWEPHWKVP4KYJ22Y
summary: The central hub for all Wikantik platform documentation, covering architecture,
  search, knowledge graph, and history.
type: hub
date: '2026-05-04'
status: official
cluster: wikantik-platform
title: Wikantik Platform Hub
related:
- 01KQTCAKV3BVHYPW20PHSFGXJR
- 01KQTCB8K3TXN8SKQFJ7WZ7FJC
- 01KQTCBW5GBFJVWYB8V1CP49P5
- 01KQTCC38PBFSD7TD6ACJFCZZ3
- 01KQTCCQ3H9K0M9E95ZCK3KHN5
---
# Wikantik Platform Hub

Welcome to the definitive documentation for the **Wikantik Platform**. Wikantik is an "Agentic Wiki" — a modern, full-stack knowledge management system designed for seamless collaboration between human experts and AI agents. 

Born from a radical modernization of Apache JSPWiki in early 2026, Wikantik combines traditional wiki collaboration with cutting-edge AI integration, a property knowledge graph, and a hybrid retrieval pipeline.

## Core Documentation

### [System Architecture](WikantikArchitecture)
An overview of the 18-module Maven structure, the hybrid storage model (Git + PostgreSQL), and the technology stack (React SPA, Tomcat 11, Java 21).

### [Search and Retrieval](WikantikSearchAndRetrieval)
A deep dive into the hybrid search engine that fuses traditional Lucene BM25, dense vector embeddings (pgvector), and Knowledge Graph co-mention reranking.

### [The Knowledge Graph](WikantikKnowledgeGraph)
How Wikantik turns wiki content into a queryable property graph using frontmatter projection, AI-driven proposals, and a robust provenance model.

### [History and Evolution](WikantikEvolutionFromJSPWiki)
The transition from the legacy JSPWiki architecture to the modern Wikantik platform, including what remains of the "ancient history."

### [Critique and Market Position](WikantikCritiqueAndMarketPosition)
A candid assessment of Wikantik's strengths and weaknesses, architectural suggestions for future growth, and how it compares to other open-source and commercial wiki systems.

## Key Features

- **Agent-First Design:** Built-in Model Context Protocol (MCP) servers for autonomous research and editing.
- **Structural Spine:** Mandatory YAML frontmatter ensuring every page is machine-readable and semantically rich.
- **Hybrid DI Bridge:** A modern Guice-based dependency injection system transitioning away from legacy Service Locators.
- **Modern Frontend:** A high-performance React SPA (Vite) replacing legacy JSP templates.
- **Knowledge Proposals:** A human-in-the-loop workflow for AI-suggested knowledge graph enrichments.
