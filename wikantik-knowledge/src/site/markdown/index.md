# Wikantik Knowledge MCP

Read-only MCP server at `/knowledge-mcp` for knowledge consumption by coding
agents: hybrid retrieval, the RAG context bundle (`assemble_bundle`),
structural-spine navigation, Page Graph neighbours, agent-grade page projection,
batched markdown reads, ontology access via `get_ontology` and `sparql_query`,
stale-citation curation, and session-start context briefings.

The module also hosts the retrieval-side services behind those tools: chunking,
pgvector-backed content embeddings, the bundle and briefing assemblers, the
retrieval-quality and bundle evaluation runners, and the ontology-aware query
expansion used by the hybrid retriever. (The Knowledge Graph and its tools were
removed in 2.5.13; the `kg_content_chunks` table name is a leftover.)
