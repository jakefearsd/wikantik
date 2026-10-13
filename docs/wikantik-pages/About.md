---
cluster: wikantik-development
type: reference
related:
- Main
- WikantikArchitecture
- AgentGradeContentDesign
- StructuralSpineDesign
tags:
- wiki
- about
- architecture
- mcp
- agentic-ai
canonical_id: 01KQ0P44GFKMNEM5TV6M4VBXQJ
summary: The manifesto and technical blueprint of Wikantik — an agent-grade semantic
  wiki for human-agent collaborative research.
status: active
---
# About Wikantik: The Agent-Grade Knowledge Engine

Wikantik is a **Semantic Agentic Wiki** designed as a high-signal research substrate where humans and AI agents collaborate to build a verifiable knowledge base. It is the evolution of traditional wiki software into a machine-readable, programmatically-accessible "Long-Term Memory" for LLM systems.

## The Manifesto: Human-Agent Collaboration

The primary mission of Wikantik is to solve the "LLM Slop" problem through a tiered research model:

1.  **Agentic Scaffolding:** Agents (like Gemini CLI) perform the initial "heavy lifting"—researching across the web, retrieving context from the wiki, and drafting structured Markdown pages.
2.  **The Structural Spine:** Every page is constrained by the **Structural Spine** (YAML frontmatter + CommonMark; a `canonical_id` is added on save). This ensures the content is as useful to a retrieval-augmented generation (RAG) system as it is to a human reader.
3.  **Human Vetting:** The "auto-generated" flag marks content as unverified. A human editor reviews the facts, tightens the prose, and verifies the citations.
4.  **Verification Stamping:** Once vetted, a page is marked as **Verified** (authoritative). This creates a "Web of Trust" within the knowledge base that agents can prioritize during future research.

## The Architecture: How It Works

Wikantik is a modern Java 25 / Jakarta Servlet 6.1 application (Tomcat 11) built on a decoupled, provider-based architecture. It prioritizes **Optimistic Concurrency** over locks, allowing humans and agents to edit the same corpus without blocking.

### Technical Stack

| Component | Technology |
|-----------|-----------|
| **Runtime** | Java 25 LTS |
| **Servlet Container** | Apache Tomcat 11 |
| **Search & Retrieval** | Apache Lucene (BM25) + dense embeddings (stored in `pgvector`; served by Lucene HNSW by default) |
| **Database** | PostgreSQL with `pgvector` (accounts, groups, policy grants, comments, Knowledge Graph, embeddings) |
| **Storage** | Versioned file system with per-page history |
| **Integration** | Model Context Protocol (MCP) |
| **Frontend** | Vite + React single-page app (reader, editor and admin panel) |

### Module Structure

The codebase is organized into highly focused modules to maintain clear boundaries:

-   **wikantik-api** — The core contracts and service interfaces.
-   **wikantik-main** — The central engine, orchestrating security, content rendering, and search.
-   **wikantik-knowledge** — The RAG-optimized retrieval service and the read-only `/knowledge-mcp` endpoint.
-   **wikantik-admin-mcp** — The write surface, exposing the wiki's lifecycle to agents via MCP.
-   **wikantik-observability** — Health checks, Prometheus metrics, request correlation, rate limiting and JFR profiling.
-   **wikantik-extract-cli** — Command-line tools for Knowledge Graph extraction and policy, Main page generation, document ingest and corpus-divergence checks.

## The Model Context Protocol (MCP) Interface

Wikantik exposes two primary MCP servers that act as the "USB-C port" for AI integration. This allows agents to interact with the wiki using standardized tools rather than custom scrapers.

### `/knowledge-mcp` (Retrieval Surface)
Optimized for read-only retrieval and knowledge graph traversal.
-   `retrieve_context` — Hybrid search (BM25 + Semantic) for RAG context.
-   `get_page_by_id` — Stable retrieval using the **Canonical ULID**.
-   `traverse` — Walk the Knowledge Graph to find related entities and shared chunks.
-   `discover_schema` — Introspect the LLM-extracted relationship types.
-   `assemble_bundle` — Assemble a ranked, de-duplicated, version-pinned-cited context bundle for a question (no answer synthesis).

### `/wikantik-admin-mcp` (Write Surface)
The authoritative management interface for the wiki lifecycle.
-   `update_page` — Edit with **Optimistic Locking** (using `expectedContentHash`).
-   `write_pages` — Batch-create articles with metadata validation.
-   `propose_knowledge` — Agents propose new Graph edges for human approval.
-   `mark_page_verified` — Stamping content as human-vetted and authoritative.

## Semantic Integrity: The Structural Spine

Content in Wikantik is not "just text." Every page must adhere to the **Structural Spine**, which includes:

-   **Canonical ID:** A 26-character ULID that remains stable even if a page is renamed.
-   **Typed Metadata:** Frontmatter that drives SEO, JSON-LD, and Agentic Discovery.
-   **Mention-centroid embeddings:** Pages are chunked and embedded for dense retrieval, and each Knowledge Graph entity gets a vector that is the centroid of the chunks that mention it, so `find_similar` can surface related entities without explicit links.

---

## The Heritage: Evolution from JSPWiki

While Wikantik is now an agent-first system, it honors its heritage as a descendant of **Apache JSPWiki**. It retains the robust security and extensibility patterns that made JSPWiki a staple of the Java ecosystem for over two decades:

-   **JAAS Security:** Fine-grained, policy-based access control.
-   **Provider Pattern:** Pluggable storage providers. The ones that ship are file-system based: `FileSystemProvider` and the default `VersioningFileProvider`.
-   **Plugin & Filter Pipeline:** A battle-tested mechanism for extending the engine without modifying the core.
-   **Markup pipeline:** Pages render through a Markdown parser with the original `[{Plugin}]()` syntax kept for dynamic content.

For hands-on guides, see the user documentation on [GitHub](https://github.com/jakefearsd/wikantik/tree/main/docs/user).

Wikantik is licensed under the **Apache License, Version 2.0**. It is a bridge between the historical stability of Java enterprise software and the future of the Semantic Web.
