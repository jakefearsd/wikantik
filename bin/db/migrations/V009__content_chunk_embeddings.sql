-- V009: content_chunk_embeddings — dense-vector projection of kg_content_chunks.
-- One row per (chunk_id, model_code) so multiple models can coexist during a
-- swap. BYTEA storage holds little-endian float32 payloads; see
-- com.wikantik.search.embedding.experiment.VectorCodec for the shared codec.

CREATE TABLE IF NOT EXISTS content_chunk_embeddings (
    chunk_id   UUID        NOT NULL,
    model_code TEXT        NOT NULL,
    dim        INT         NOT NULL,
    vec        BYTEA       NOT NULL,
    updated    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (chunk_id, model_code),
    FOREIGN KEY (chunk_id) REFERENCES kg_content_chunks(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_cce_model
    ON content_chunk_embeddings (model_code);

GRANT SELECT, INSERT, UPDATE, DELETE ON content_chunk_embeddings TO :app_user;
