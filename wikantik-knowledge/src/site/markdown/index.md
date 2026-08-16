# Wikantik Knowledge MCP

Read-only MCP server at `/knowledge-mcp` for knowledge consumption by coding
agents: hybrid retrieval, Knowledge Graph traversal, schema discovery,
structural-spine navigation, agent-grade page projection, batched markdown
reads, ontology access via `get_ontology` and `sparql_query`, stale-citation
curation, and session-start context briefings.

This module also hosts the Knowledge Graph service itself — pgvector-backed
embeddings, the co-mention graph, and hub discovery — plus the
ontology-aware query expansion used by the hybrid retriever.
