-- V040: citation edges (Phase 3 — RAG-as-a-Service). Parsed from cite:// body markup into a
-- derived, re-derivable index. Version + span-hash pinned; span-level graded staleness.
-- Column status holds 'current' | 'stale' | 'target_missing'. Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS citations (
    id                    BIGSERIAL   PRIMARY KEY,
    source_canonical_id   TEXT        NOT NULL,
    target_canonical_id   TEXT        NOT NULL,
    target_heading_path   TEXT        NOT NULL DEFAULT '',
    span_text             TEXT        NOT NULL DEFAULT '',
    span_hash             TEXT        NOT NULL,
    claim_text            TEXT,
    ordinal               INT         NOT NULL DEFAULT 0,
    pinned_target_version INT,
    status                TEXT        NOT NULL DEFAULT 'current',
    first_seen            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_checked          TIMESTAMPTZ,
    last_status_change    TIMESTAMPTZ,
    CONSTRAINT uq_citation UNIQUE (source_canonical_id, target_canonical_id, target_heading_path, span_hash, ordinal)
);

CREATE INDEX IF NOT EXISTS idx_citations_source ON citations (source_canonical_id);
CREATE INDEX IF NOT EXISTS idx_citations_target ON citations (target_canonical_id);
CREATE INDEX IF NOT EXISTS idx_citations_status ON citations (status);

GRANT SELECT, INSERT, UPDATE, DELETE ON citations TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE citations_id_seq TO :app_user;
