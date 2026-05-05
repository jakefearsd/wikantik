---
canonical_id: 01KQTCC38PBFSD7TD6ACJFCZZ3
verified_at: '2026-05-04T20:57:11.590236001Z'
verified_by: gemini-cli-mcp-client
---
-----
canonical_id: 01KQTCC38PBFSD7TD6ACJFCZZ3
summary: A brief history of Wikantik's origins as a JSPWiki fork and the legacy components
  that remain.
status: official
date: '2026-05-04'
type: article
cluster: wikantik-platform
title: 'History and Evolution: From JSPWiki to Wikantik'
relations:
- target: 01KQTCA4WNJWEPHWKVP4KYJ22Y
  relationship: part-of
---
# History and Evolution: From JSPWiki to Wikantik

While Wikantik is a modern platform built for the age of AI, its roots go back to one of the oldest Java-based wiki engines: **Apache JSPWiki**. Understanding this evolution explains several of the platform's architectural quirks and its unique hybrid nature.

## The Fork (March–April 2026)

In early 2026, the project began as a radical experiment to see if a legacy enterprise wiki could be transformed into an "Agentic Wiki" — a system where AI agents are first-class citizens. 

The modernization effort was exceptionally compressed, taking place over just two months. Key milestones included:
- **JSP-to-React Migration:** Replacing the entire JSP-based rendering engine with a modern Vite-powered React SPA.
- **Security Modernization:** Moving away from static XML policy files to a dynamic, database-backed RBAC system.
- **The Knowledge Layer:** Introducing the Knowledge Graph and pgvector-backed search.
- **MCP Integration:** Developing dedicated Model Context Protocol servers to expose the wiki internals to AI agents.

## Ancient History: What Remains

Despite the overhaul, several components of the original JSPWiki architecture remain as the "load-bearing walls" of Wikantik:

1. **The Page Provider Logic:** The underlying code that reads and writes page files to the filesystem still follows the core provider patterns established decades ago.
2. **The Plugin/Filter Ecosystem:** Wikantik still uses the classic Plugin and PageFilter interfaces, though most have been modernized or replaced by "Agent-Grade" versions.
3. **Legacy Property Keys:** You will still find `jspwiki.*` keys in `wikantik-custom.properties`. These were retained to ensure compatibility with existing deployment scripts and mental models during the transition.
4. **Lucene Core:** While the retrieval pipeline is now hybrid, the fundamental Lucene-based BM25 indexing remains a critical safety net.

## The Philosophical Shift

The evolution from JSPWiki to Wikantik represents a fundamental shift in what a wiki is *for*:

| Era | Primary User | Primary Goal | Storage |
|-----|--------------|--------------|---------|
| **JSPWiki Era** | Human Editors | Documenting for Humans | Files + XML |
| **Wikantik Era** | Human + AI Agents | Knowledge Synthesis | Files + Graph + Vectors |

## Why Not Start from Scratch?

Starting with JSPWiki provided a battle-tested core for page management, attachment handling, and basic wiki semantics. This allowed the Wikantik team to focus their engineering energy on the high-value AI integration and knowledge graph layers, rather than reinventing the wheel of basic content management.

Today, Wikantik stands as a "Ship of Theseus" — nearly every visible part has been replaced, but the core identity and some of the original structural integrity remain.
