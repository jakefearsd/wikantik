---
related:
- WikantikArchitecture
- WikantikSearchAndRetrieval
- WikantikKnowledgeGraph
- WikantikEvolutionFromJSPWiki
- WikantikCritiqueAndMarketPosition
canonical_id: 01KQTCA4WNJWEPHWKVP4KYJ22Y
verified_by: jakefear
auto-generated: false
type: hub
status: active
tags:
- wikantik
- platform
- architecture
- hybrid-search
- hub
summary: Central hub for Wikantik platform documentation, encompassing system architecture,
  hybrid search, and knowledge graph engineering.
title: Wikantik Platform Hub
cluster: wikantik-platform
verified_at: '2026-05-02T00:00:00Z'
date: 2026-05-02T00:00:00Z
---

# Wikantik Platform Hub

Wikantik is an "Agentic Wiki"—a full-stack knowledge management system designed for synchronous collaboration between human authors and AI agents. It represents a 2026 modernization of the Apache JSPWiki codebase, replacing legacy JSP templates with a React-based SPA and a multi-modal retrieval engine.

## Core Documentation

### [System Architecture](WikantikArchitecture)
Covers the 18-module Maven structure, the hybrid storage model (Git-backed files + PostgreSQL), and the primary tech stack (Java 21, Tomcat 11, Guice DI).

### [Search and Retrieval](WikantikSearchAndRetrieval)
Details the hybrid search pipeline that integrates traditional Lucene (BM25), dense vector embeddings (pgvector), and Knowledge Graph co-mention reranking.

### [The Knowledge Graph](WikantikKnowledgeGraph)
Explains the transformation of wiki content into a queryable property graph using frontmatter projection and AI-driven entity extraction.

### [History and Evolution](WikantikEvolutionFromJSPWiki)
Traces the architectural transition from legacy JSPWiki to the modern Wikantik platform, highlighting major refactoring milestones.

### [Critique and Market Position](WikantikCritiqueAndMarketPosition)
A technical assessment of Wikantik's architectural trade-offs, market position relative to Obsidian/Confluence, and future scaling roadmap.

## Key Platform Features

- **Model Context Protocol (MCP):** Native tool servers allowing agents to perform autonomous research, page creation, and verification.
- **Structural Spine:** A enforced schema using YAML frontmatter to ensure every page is machine-readable and indexed.
- **Hybrid DI Bridge:** A transitionary Guice-based injection system that decouples legacy managers from the core engine.
- **Modern UI:** A high-performance React frontend using Vite, offering real-time previews and rich metadata editing.
- **Knowledge Proposals:** A human-in-the-loop workflow for reviewing and approving AI-suggested graph relationships.
