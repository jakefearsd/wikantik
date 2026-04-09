/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

-- Hub membership tables for Wikantik
-- Run after postgresql-knowledge.ddl: sudo -u postgres psql -d wikantik -f postgresql-hub.ddl
--
-- Prerequisites:
--   1. postgresql-knowledge.ddl must have been run first (creates pgvector extension)
--   2. Run this script as a PostgreSQL superuser (e.g., 'postgres')

-- Hub centroids: averaged content embedding vectors for each Hub
CREATE TABLE IF NOT EXISTS hub_centroids (
    id              SERIAL PRIMARY KEY,
    hub_name        VARCHAR(255) NOT NULL UNIQUE,
    centroid        vector(512) NOT NULL,
    model_version   INTEGER NOT NULL,
    member_count    INTEGER NOT NULL,
    created         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Hub proposals: pending/approved/rejected Hub membership suggestions
CREATE TABLE IF NOT EXISTS hub_proposals (
    id               SERIAL PRIMARY KEY,
    hub_name         VARCHAR(255) NOT NULL,
    page_name        VARCHAR(255) NOT NULL,
    raw_similarity   DOUBLE PRECISION NOT NULL,
    percentile_score DOUBLE PRECISION NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'pending',
    reason           TEXT,
    reviewed_by      VARCHAR(255),
    reviewed_at      TIMESTAMP,
    created          TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (hub_name, page_name)
);

CREATE INDEX IF NOT EXISTS idx_hub_proposals_status ON hub_proposals(status);
CREATE INDEX IF NOT EXISTS idx_hub_proposals_hub ON hub_proposals(hub_name);

-- Grant permissions to application user (jspwiki)
GRANT SELECT, INSERT, UPDATE, DELETE ON hub_centroids TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON hub_proposals TO jspwiki;
GRANT USAGE, SELECT ON SEQUENCE hub_centroids_id_seq TO jspwiki;
GRANT USAGE, SELECT ON SEQUENCE hub_proposals_id_seq TO jspwiki;
