-- V008: kg_content_chunks — passage-level storage of wiki page content.
-- Text only in this migration; embeddings live in a separate future table
-- joined on chunk_id and are NOT created here.

CREATE TABLE IF NOT EXISTS kg_content_chunks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_name            TEXT        NOT NULL,
    chunk_index          INT         NOT NULL,
    heading_path         TEXT[]      NOT NULL DEFAULT '{}',
    text                 TEXT        NOT NULL,
    char_count           INT         NOT NULL,
    token_count_estimate INT         NOT NULL,
    content_hash         TEXT        NOT NULL,
    created              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT kg_content_chunks_page_index_uniq UNIQUE (page_name, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_page_name
    ON kg_content_chunks (page_name);
CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_content_hash
    ON kg_content_chunks (content_hash);

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_content_chunks TO :app_user;
