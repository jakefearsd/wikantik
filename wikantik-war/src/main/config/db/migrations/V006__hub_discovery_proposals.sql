-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: Hub discovery proposals
--
-- Creates hub_discovery_proposals, the review queue for cluster-based hub
-- suggestions produced by HubDiscoveryService. Accept and dismiss both
-- DELETE the row — there is no status column — so a row's existence means
-- "pending review". Fully idempotent.
--
-- Depends on V004 (pgvector extension, though this table uses JSONB not vectors).

CREATE TABLE IF NOT EXISTS hub_discovery_proposals (
    id              SERIAL PRIMARY KEY,
    suggested_name  TEXT             NOT NULL,
    exemplar_page   TEXT             NOT NULL,
    member_pages    JSONB            NOT NULL,
    coherence_score DOUBLE PRECISION NOT NULL,
    created         TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hub_discovery_proposals_created
    ON hub_discovery_proposals ( created DESC );

GRANT SELECT, INSERT, UPDATE, DELETE ON hub_discovery_proposals TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE hub_discovery_proposals_id_seq TO :app_user;
