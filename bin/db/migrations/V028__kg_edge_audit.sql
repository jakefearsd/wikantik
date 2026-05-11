-- V028__kg_edge_audit.sql
-- Append-only audit trail of admin-UI-driven kg_edges mutations.
-- Schema: see docs/superpowers/specs/2026-05-11-kg-edge-curation-v0-design.md

CREATE TABLE IF NOT EXISTS kg_edge_audit (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    edge_id   UUID         NOT NULL,
    action    VARCHAR(10)  NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE')),
    before    JSONB,
    after     JSONB,
    actor     VARCHAR(100) NOT NULL,
    reason    TEXT,
    created   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_edge_audit_edge_created
    ON kg_edge_audit (edge_id, created DESC);

GRANT SELECT, INSERT ON kg_edge_audit TO :app_user;
