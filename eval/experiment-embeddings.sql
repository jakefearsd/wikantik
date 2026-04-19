-- Sandbox schema for retrieval experimentation.
-- NOT a migration — never link this file from bin/db/migrations/.
--
-- Purpose: hold per-model dense embeddings of kg_content_chunks rows for
-- offline comparison. Vectors are stored as raw little-endian float32 BYTEA
-- so the table is dimension-agnostic and can host models with different
-- output dimensions in the same table. This is fine for ~10K chunks —
-- cosine similarity is computed in Java during evaluation. Once a winner
-- is chosen this table is dropped and a pgvector-typed column is added to
-- the production search path.
--
-- Apply with:
--   psql -h <host> -U <admin> -d <db> -f eval/experiment-embeddings.sql
--
-- Drop with:
--   psql ... -c 'DROP TABLE IF EXISTS experiment_embeddings;'
--
-- Prerequisite: kg_content_chunks must be populated before ExperimentIndexer
-- has anything to embed. On a fresh checkout this table is empty; populate it
-- by calling POST /admin/content/rebuild-indexes on the running wiki (takes a
-- few minutes for ~1K pages). The FK below only enforces referential
-- integrity — it does not trigger chunking.

CREATE TABLE IF NOT EXISTS experiment_embeddings (
    chunk_id   UUID        NOT NULL REFERENCES kg_content_chunks(id) ON DELETE CASCADE,
    model_code TEXT        NOT NULL,
    dim        INT         NOT NULL,
    vec        BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_id, model_code)
);

CREATE INDEX IF NOT EXISTS idx_experiment_embeddings_model
    ON experiment_embeddings (model_code);

-- Must be granted to whichever role the harness connects as — the default
-- wiki app user (`jspwiki`) normally has this, but name it explicitly so
-- the script is safe to re-run after a db reset.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jspwiki') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON experiment_embeddings TO jspwiki';
    END IF;
END $$;
