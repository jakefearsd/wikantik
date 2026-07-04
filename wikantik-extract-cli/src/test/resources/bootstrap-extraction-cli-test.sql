-- Minimal schema for BootstrapExtractionCliRunTest — just enough for a
-- zero-page run() to reach BootstrapEntityExtractionIndexer.State.COMPLETED:
-- kg_content_chunks (listDistinctPageNames/stats), kg_excluded_pages +
-- kg_nodes (KgInclusionFilter join used by getAllNodes()), and
-- kg_node_embeddings (--rebuild-node-embeddings truncation target).

CREATE TABLE IF NOT EXISTS kg_content_chunks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_name            TEXT NOT NULL,
    chunk_index          INT  NOT NULL,
    heading_path         TEXT[] NOT NULL DEFAULT '{}',
    text                 TEXT NOT NULL,
    char_count           INT  NOT NULL,
    token_count_estimate INT  NOT NULL,
    content_hash         TEXT NOT NULL,
    created              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT bootstrap_cli_chunks_page_index_uniq UNIQUE (page_name, chunk_index)
);

CREATE TABLE IF NOT EXISTS kg_excluded_pages (
    page_name   TEXT PRIMARY KEY,
    reason      TEXT NOT NULL,
    excluded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kg_nodes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    node_type   VARCHAR(100),
    source_page VARCHAR(255),
    provenance  VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties  JSONB DEFAULT '{}'::jsonb,
    created     TIMESTAMP NOT NULL DEFAULT NOW(),
    modified    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kg_node_embeddings (
    node_id      UUID NOT NULL,
    model_code   VARCHAR(64) NOT NULL DEFAULT 'unknown',
    content_hash VARCHAR(64) NOT NULL,
    embedding    TEXT,
    embedded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
