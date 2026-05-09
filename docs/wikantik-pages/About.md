---
cluster: wikantik-development
canonical_id: 01KQ0P44GFKMNEM5TV6M4VBXQJ
summary: The manifesto and technical blueprint of Wikantik — an agent-grade semantic wiki for human-agent collaborative research.
tags:
- wiki
- about
- architecture
- mcp
- agentic-ai
type: reference
status: active
related:
- Main
- WikantikArchitecture
- AgentGradeContentDesign
- StructuralSpineDesign
---
# About Wikantik: The Agent-Grade Knowledge Engine

Wikantik is a **Semantic Agentic Wiki** designed as a high-signal research substrate where humans and AI agents collaborate to build a verifiable knowledge base. It is the evolution of traditional wiki software into a machine-readable, programmatically-accessible "Long-Term Memory" for LLM systems.

## The Manifesto: Human-Agent Collaboration

The primary mission of Wikantik is to solve the "LLM Slop" problem through a tiered research model:

1.  **Agentic Scaffolding:** Agents (like Gemini CLI) perform the initial "heavy lifting"—researching across the web, retrieving context from the wiki, and drafting structured Markdown pages.
2.  **The Structural Spine:** Every page is constrained by the **Structural Spine** (Mandatory YAML + CommonMark). This ensures the content is as useful to a retrieval-augmented generation (RAG) system as it is to a human reader.
3.  **Human Vetting:** The "auto-generated" flag marks content as unverified. A human editor reviews the facts, tightens the prose, and verifies the citations.
4.  **Verification Stamping:** Once vetted, a page is marked as **Verified** (authoritative). This creates a "Web of Trust" within the knowledge base that agents can prioritize during future research.

## The Architecture: How It Works

Wikantik is a modern Java 21 / Jakarta EE 10 application built on a decoupled, provider-based architecture. It prioritizes **Optimistic Concurrency** over locks, allowing humans and agents to edit the same corpus without blocking.

### Technical Stack

| Component | Technology |
|-----------|-----------|
| **Runtime** | Java 21 LTS |
| **Servlet Container** | Apache Tomcat 11 |
| **Search & Retrieval** | Apache Lucene (BM25) + `pgvector` (Dense Embeddings) |
| **Storage** | Versioned File System (Git-like history) |
| **Integration** | Model Context Protocol (MCP) |
| **Frontend** | Vite + React (Modern UI) / Legacy JSP (Admin) |

### Module Structure

The codebase is organized into highly focused modules to maintain clear boundaries:

-   **wikantik-api** — The core contracts and service interfaces.
-   **wikantik-main** — The central engine, orchestrating security, content rendering, and search.
-   **wikantik-knowledge** — The RAG-optimized retrieval service and vector-centroid engine.
-   **wikantik-admin-mcp** — The write surface, exposing the wiki's lifecycle to agents via MCP.
-   **wikantik-observability** — Structured tracing (OTEL) and audit logging for agent actions.
-   **wikantik-extract-cli** — Tooling for knowledge graph extraction and Structural Spine validation.

## The Model Context Protocol (MCP) Interface

Wikantik exposes two primary MCP servers that act as the "USB-C port" for AI integration. This allows agents to interact with the wiki using standardized tools rather than custom scrapers.

### `/knowledge-mcp` (Retrieval Surface)
Optimized for read-only retrieval and knowledge graph traversal.
-   `retrieve_context` — Hybrid search (BM25 + Semantic) for RAG context.
-   `get_page_by_id` — Stable retrieval using the **Canonical ULID**.
-   `traverse` — Walk the Knowledge Graph to find related entities and shared chunks.
-   `discover_schema` — Introspect the LLM-extracted relationship types.

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
-   **Centroid Embedding:** Each page is projected into a vector space based on its content, allowing for "Similar Page" discovery without explicit links.

---

## The Heritage: Evolution from JSPWiki

While Wikantik is now an agent-first system, it honors its heritage as a descendant of **Apache JSPWiki**. It retains the robust security and extensibility patterns that made JSPWiki a staple of the Java ecosystem for over two decades:

-   **JAAS Security:** Fine-grained, policy-based access control.
-   **Provider Pattern:** Flexible storage backends (File, Database, S3).
-   **Plugin & Filter Pipeline:** A battle-tested mechanism for extending the engine without modifying the core.
-   **The JSP Origins:** Some admin and editing surfaces still utilize the original JSP templates, providing a bridge between the classic web and the modern agentic API.

Wikantik is licensed under the **Apache License, Version 2.0**. It is a bridge between the historical stability of Java enterprise software and the future of the Semantic Web.
