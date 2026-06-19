-- V041: retrieval query log. Append-only capture of real retrieval queries (text + who asked
-- + which surface + how many results) to ground the eval corpus in real traffic. Written
-- fail-open + async by the retrieval endpoints; never on the request critical path.
-- actor_type ∈ 'human' | 'agent' | 'unknown'; source_surface ∈ 'api_bundle' | 'api_search'
-- | 'mcp_assemble_bundle' | 'tools_search_wiki'. Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS retrieval_query_log (
    id             BIGSERIAL   PRIMARY KEY,
    query_text     TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    source_surface TEXT        NOT NULL,
    result_count   INTEGER,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_retrieval_query_log_created_at ON retrieval_query_log (created_at);
CREATE INDEX IF NOT EXISTS idx_retrieval_query_log_actor_surface
    ON retrieval_query_log (actor_type, source_surface);

GRANT SELECT, INSERT ON retrieval_query_log TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE retrieval_query_log_id_seq TO :app_user;
