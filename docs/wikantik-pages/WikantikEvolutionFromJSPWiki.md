---
hubs:
- WikantikPlatformHub
- WikantikDevelopment
status: active
date: '2026-06-20'
summary: How Wikantik evolved from the Apache JSPWiki fork in 2026 — what changed,
  what remains, and the philosophical shift to agent-grade knowledge management.
tags:
- history
- jspwiki
- architecture
- evolution
- migration
- platform
type: article
cluster: wikantik-platform
canonical_id: 01KQTCC38PBFSD7TD6ACJFCZZ3
title: 'History and Evolution: From JSPWiki to Wikantik'
---
# History and Evolution: From JSPWiki to Wikantik

While Wikantik is a modern platform built for the agent era, its roots go back to one of the oldest Java-based wiki engines: **Apache JSPWiki**. Understanding this evolution explains several of the platform's architectural continuities and its unique hybrid nature.

## The Fork (March–April 2026)

In early 2026, the project began as a radical experiment to see if a legacy enterprise wiki could be transformed into an agentic knowledge base — a system where AI agents are first-class citizens alongside human editors.

The initial modernization was exceptionally compressed, taking place over two months. Key milestones:

- **JSP-to-React Migration:** The entire JSP-based rendering engine was replaced with a Vite-powered React SPA served at `/`.
- **Security Modernization:** Static XML policy files were replaced with a dynamic, database-backed RBAC system (`policy_grants` table, manageable via admin UI). JAAS-based SSO (OIDC + SAML via pac4j) was added.
- **The Knowledge Layer:** The `kg_*` tables and pgvector-backed embeddings introduced the Knowledge Graph and hybrid search.
- **MCP Integration:** Two dedicated Model Context Protocol servers were built to expose wiki internals to AI agents: `/wikantik-admin-mcp` (26 write/analytics tools) and `/knowledge-mcp` (read-only retrieval).

## Continued Evolution (May–June 2026)

After the initial fork, the platform continued to grow substantially:

- **Agent-grade content (2026-04-25):** `type: runbook` pages, page verification metadata, the `get_page_for_agent` token-budgeted projection, and nightly retrieval-quality CI.
- **Structural spine (2026-05-02):** `canonical_id` (rename-stable page identifiers), `cluster:` hub membership, and the `list_clusters`/`list_tags`/`list_pages_by_filter` structural query tools. The `relations:` frontmatter block was removed at this point — edges are now KG-extracted or wikilink-derived, not YAML-declared.
- **SCIM 2.0 provisioning (2026-05-xx):** `/scim/v2/*` — IdP-driven user and group onboarding/offboarding.
- **RDF/OWL ontology (2026-06-08):** The `wikantik-ontology` module added a queryable `wikantik:` T-Box (9 entity + 5 content classes, 21 KG predicates), a SHACL write-time gate, Postgres→RDF projectors, a public SPARQL endpoint at `/sparql`, dereferenceable IRIs at `/id/{type}/{id}`, and RDF dumps at `/export/`. SEO JSON-LD `@type` is now re-sourced from the ontology.
- **RAG-as-a-Service (2026-06-14):** The context bundle (`GET /api/bundle?q=`, `assemble_bundle` MCP tool) assembles ranked, de-duplicated, version-pinned-cited sections — the wiki acts as a knowledge base that any agent or pipeline can query for grounded retrieval, without answer synthesis. Derived pages (Apache Tika 3.3.0 ingest via `POST /api/ingest`) allow documents to be ingested and machine-reflowed from source attachments.
- **Bundle hybrid source (2026-06-19):** The context bundle default source switched from dense-only to chunk-level BM25+dense RRF hybrid, improving section recall@12 further.

## What Remains from JSPWiki

Despite the overhaul, several JSPWiki components remain as load-bearing foundations:

1. **Page Provider Logic:** The core patterns for reading and writing page files to the filesystem (`VersioningFileProvider`) still follow the provider interfaces established by JSPWiki, though these are now behind the `PageProvider` abstraction in `wikantik-api`.
2. **Plugin/Filter Ecosystem:** Wikantik retains the classic `Plugin` and `PageFilter` interfaces. Most have been modernized or replaced by agent-grade variants; the `[{Plugin}]()` syntax is auto-normalized for the Flexmark renderer.
3. **Lucene BM25 Core:** The fundamental Lucene-based full-text indexing remains the BM25 backbone of hybrid search and its fail-closed fallback when the embedding service (Ollama) is unavailable.
4. **Flexmark Markdown Rendering:** The Markdown rendering pipeline (Flexmark AST → `MarkdownRenderer`) is Wikantik-built but retains the pre-JSPWiki-era plugin embedding model.

Note: the `jspwiki.*` property key namespace was progressively renamed to `wikantik.*` during the rebrand. Modern deployments use `wikantik-custom.properties` with `wikantik.*` keys.

## The Philosophical Shift

| Era | Primary User | Primary Goal | Storage |
|-----|--------------|--------------|---------|
| **JSPWiki Era** | Human editors | Documenting for humans | Files + XML |
| **Wikantik Era** | Humans + AI agents | Knowledge synthesis + agent retrieval | Files + PostgreSQL + pgvector + RDF |

## Why Not Start from Scratch?

JSPWiki provided a battle-tested core for page management, attachment handling, versioning, and basic wiki semantics. This allowed Wikantik to focus engineering energy on the high-value AI integration, hybrid retrieval, Knowledge Graph, and ontology layers rather than reinventing content management.

Today, Wikantik is effectively a "Ship of Theseus" — nearly every visible surface has been replaced, but the page provider foundations and Lucene core remain.

## See Also

- [WikantikPlatformHub](WikantikPlatformHub) — platform cluster hub
- [WikantikDevelopment](WikantikDevelopment) — engineering history and feature timeline
- [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph) — the two graph subsystems explained
