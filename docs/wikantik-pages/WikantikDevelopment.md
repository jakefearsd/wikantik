---
cluster: wikantik-development
title: Wikantik Development
related:
- WikantikArchitecture
- WikantikKnowledgeGraph
- WikantikEvolutionFromJSPWiki
- TestDrivenDevelopment
- HexagonalArchitecture
- ContinuousIntegration
type: hub
summary: Hub for Wikantik platform development — architecture decisions, TDD mandate,
  CI pipeline, and feature milestones from JSPWiki to 2.0.x.
verified_by: gemini-cli-mcp-client
status: active
canonical_id: 01KQ0P44YWV8Q0JMN1H2H5EGDX
date: '2026-06-20'
verified_at: '2026-05-04T21:10:44.598011331Z'
tags:
- development
- architecture
- tdd
- engineering
- ci
- history
---
# Wikantik Development

This cluster documents the development history of the Wikantik platform itself — the architecture decisions, feature implementations, and engineering patterns that have shaped the system over time.

## Core Engineering Pillars

- **[Test Driven Development (TDD)](TestDrivenDevelopment):** The mandatory TDD-first mandate: a failing test demonstrates every defect before a fix is written.
- **[Hexagonal Architecture](HexagonalArchitecture):** Core wiki logic is decoupled from infrastructure (PostgreSQL, React, MCP) via manager interfaces in `wikantik-api`.
- **[Continuous Integration (CI)](ContinuousIntegration):** Tag-triggered `release.yml` on GitHub Actions builds + publishes `ghcr.io/jakefearsd/wikantik:<version>`; integration tests run via Selenide + Cargo-launched Tomcat with PostgreSQL + pgvector; nightly retrieval-quality CI (nDCG/Recall/MRR) gates embedding regressions.
- **Database Migrations:** Every schema change ships a numbered idempotent `V<NNN>__description.sql` under `bin/db/migrations/`; applied by `migrate.sh` on every deploy. Currently V001–V037.

## Module Structure

Wikantik is a Maven multi-module reactor. Key modules:

| Module | Role |
|--------|------|
| `wikantik-api` | Core interfaces — manager contracts, frontmatter types, KG service, Page Graph interfaces |
| `wikantik-main` | Main implementation — Markdown rendering (Flexmark), providers, auth (JAAS), search, entity extraction, math parser |
| `wikantik-event` | Event system for decoupled component communication |
| `wikantik-util` | Utility classes and helpers |
| `wikantik-cache` | EhCache-based caching layer (1-hour TTL, 10K entry capacity) |
| `wikantik-http` | Servlet filters — CSRF, CORS, CSP, security headers, SPA routing |
| `wikantik-rest` | REST/JSON API (`/api/*`) and admin endpoints (`/admin/*`) |
| `wikantik-admin-mcp` | Admin MCP server at `/wikantik-admin-mcp` — 26 tools |
| `wikantik-knowledge` | Knowledge MCP server at `/knowledge-mcp` — 19–20 read-only tools; also hosts the KG service, hybrid retriever, RAG bundle |
| `wikantik-ontology` | RDF/OWL ontology (Apache Jena + TDB2) — T-Box, projectors, SHACL gate, public SPARQL surface |
| `wikantik-ingest` | Derived-page document extraction (Apache Tika 3.3.0 + flexmark-html2md) |
| `wikantik-scim` | SCIM 2.0 provisioning at `/scim/v2/*` |
| `wikantik-tools` | OpenAPI 3.1 tool server at `/tools/*` — 2 tools for non-MCP clients |
| `wikantik-observability` | Health checks, Prometheus metrics, request correlation |
| `wikantik-frontend` | React SPA (Vite) — reader, editor, admin panel, Page Graph viewer, Knowledge Graph viewer |
| `wikantik-war` | WAR packaging; bundles the React build |
| `wikantik-it-tests` | Integration tests (Selenide, REST, Cargo-launched Tomcat + PostgreSQL + pgvector) |

## Architecture Evolution

Wikantik began as a fork of Apache JSPWiki. Through a sustained modernization effort starting March 2026, the platform was transformed:

- **Frontend:** JSP templates replaced with a React SPA (Vite + React Router), served at `/`
- **Storage:** File-based providers augmented with PostgreSQL for users, groups, permissions, audit log, and the Knowledge Graph; pgvector for dense embeddings
- **Security:** XML policy files migrated to database-backed `policy_grants` table with admin UI; SCIM 2.0 provisioning added at `/scim/v2/*`; JAAS-based SSO (OIDC + SAML via pac4j) with session fixation defense and SameSite=Lax cookies
- **AI Integration:** Two dedicated MCP servers — `/wikantik-admin-mcp` (26 write/analytics tools) and `/knowledge-mcp` (19–20 read-only retrieval + ontology tools) — plus an OpenAPI 3.1 tool server at `/tools/*`
- **Retrieval:** Hybrid BM25 + dense (Lucene HNSW / pgvector) via RRF; chunk-level contextual embeddings; RAG-as-a-Service context bundle at `GET /api/bundle` + `assemble_bundle` MCP
- **Ontology:** RDF/OWL ontology layer (`wikantik-ontology`) — 9 entity + 5 content classes, SHACL write-time gate, public SPARQL endpoint, JSON-LD SEO re-sourced from the ontology
- **Observability:** Health checks, Prometheus `/metrics`; monitoring handled by the external **jakemon** stack (Grafana Alloy + central Prometheus + Loki + Grafana)

## Key Design Decisions

1. **[ADR-001: Extract Manager Interfaces to API](ADR-001)** — Decouples MCP modules from the core engine; `wikantik-api` is the only dependency MCP modules need for type-safe engine access.
2. **Frontmatter as Source of Truth** — Wiki page YAML frontmatter is the canonical source for cluster membership, tags, type, audience, verification, and SEO metadata. The server-authoritative `FrontmatterSchema` drives live validation and Save-gating in the editor.
3. **Page Graph vs Knowledge Graph** — Two strictly separate subsystems. The Page Graph tracks real wikilink edges; the Knowledge Graph tracks LLM-extracted entities and relations. See [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph).
4. **Fail-closed retrieval** — The hybrid retriever degrades to BM25 when the embedding service (Ollama) is unavailable; the context bundle dense source uses Lucene HNSW in-process (the docker1 production default) so there is no mandatory external network dependency for search.

## Feature Timeline

| Date | Feature |
|------|---------|
| 2026-03-23 | Test stub conversion for decoupled testing |
| 2026-03-28 | JSP → React SPA migration |
| 2026-04-03 | User profile management, attachment handling |
| 2026-04-04 | Knowledge Graph core — entity extraction, `kg_*` tables, pgvector embeddings |
| 2026-04-25 | Agent-grade content layer — `type: runbook`, page verification, `get_page_for_agent`, retrieval-quality CI |
| 2026-05-02 | Structural spine — `canonical_id`, `cluster:`, `list_clusters`/`list_tags`/`list_pages_by_filter`; typed `relations:` frontmatter removed |
| 2026-05-30 | Session stability — SameSite=Lax auth cookies, remember-me re-auth filter |
| 2026-06-05 | SCIM deactivation auth guard; auth lock enforcement per-login-module |
| 2026-06-08 | RDF/OWL ontology layer — T-Box, SHACL gate, public SPARQL/JSON-LD/dumps, SEO `@type` re-sourced from ontology |
| 2026-06-09 | Structured frontmatter editor — live validation, Save-gating, page-scoped KG curation panel |
| 2026-06-14 | RAG-as-a-Service Phase 1 — context bundle (`GET /api/bundle`, `assemble_bundle` MCP), derived pages (Tika ingest), chunker heading-fidelity fix, contextual embeddings |
| 2026-06-19 | Bundle hybrid source (BM25+dense RRF default-on); admin-MCP reconciled to 26 tools; `list_retrieval_queries` added |

## See Also

- [WikantikArchitecture](WikantikArchitecture) — detailed module breakdown
- [WikantikKnowledgeGraph](WikantikKnowledgeGraph) — semantic network documentation
- [WikantikEvolutionFromJSPWiki](WikantikEvolutionFromJSPWiki) — the history from JSPWiki fork to Wikantik
