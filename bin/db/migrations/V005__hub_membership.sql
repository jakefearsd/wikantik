-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: Hub membership centroids and proposals
--
-- Creates hub_centroids (averaged TF-IDF vector per Hub) and hub_proposals
-- (pending/approved/rejected Hub membership suggestions) used by
-- HubProposalService and HubProposalRepository. Fully idempotent.
--
-- Depends on V004 (pgvector extension).

CREATE TABLE IF NOT EXISTS hub_centroids (
    id            SERIAL       PRIMARY KEY,
    hub_name      VARCHAR(255) NOT NULL UNIQUE,
    centroid      vector(512)  NOT NULL,
    model_version INTEGER      NOT NULL,
    member_count  INTEGER      NOT NULL,
    created       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS hub_proposals (
    id               SERIAL           PRIMARY KEY,
    hub_name         VARCHAR(255)     NOT NULL,
    page_name        VARCHAR(255)     NOT NULL,
    raw_similarity   DOUBLE PRECISION NOT NULL,
    percentile_score DOUBLE PRECISION NOT NULL,
    status           VARCHAR(20)      NOT NULL DEFAULT 'pending',
    reason           TEXT,
    reviewed_by      VARCHAR(255),
    reviewed_at      TIMESTAMP,
    created          TIMESTAMP        NOT NULL DEFAULT NOW(),
    UNIQUE (hub_name, page_name)
);

CREATE INDEX IF NOT EXISTS idx_hub_proposals_status ON hub_proposals (status);
CREATE INDEX IF NOT EXISTS idx_hub_proposals_hub    ON hub_proposals (hub_name);

GRANT SELECT, INSERT, UPDATE, DELETE ON hub_centroids  TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON hub_proposals  TO :app_user;
GRANT USAGE, SELECT                  ON SEQUENCE hub_centroids_id_seq TO :app_user;
GRANT USAGE, SELECT                  ON SEQUENCE hub_proposals_id_seq TO :app_user;
