/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- Knowledge Graph tables for Wikantik
-- Run after postgresql.ddl: sudo -u postgres psql -d wikantik -f postgresql-knowledge.ddl
--
-- Prerequisites:
--   1. postgresql.ddl must have been run first (creates database and wikantik user)
--   2. Run this script as a PostgreSQL superuser (e.g., 'postgres')

-- Nodes: entities in the knowledge graph
CREATE TABLE IF NOT EXISTS kg_nodes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL UNIQUE,
    node_type       VARCHAR(100),
    source_page     VARCHAR(255),
    provenance      VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties      JSONB DEFAULT '{}'::jsonb,
    created         TIMESTAMP NOT NULL DEFAULT NOW(),
    modified        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_nodes_type ON kg_nodes(node_type);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_source_page ON kg_nodes(source_page);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_provenance ON kg_nodes(provenance);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_properties ON kg_nodes USING GIN (properties);

-- Edges: typed relationships between nodes
CREATE TABLE IF NOT EXISTS kg_edges (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id         UUID NOT NULL REFERENCES kg_nodes(id) ON DELETE CASCADE,
    target_id         UUID NOT NULL REFERENCES kg_nodes(id) ON DELETE CASCADE,
    relationship_type VARCHAR(100) NOT NULL,
    provenance        VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties        JSONB DEFAULT '{}'::jsonb,
    created           TIMESTAMP NOT NULL DEFAULT NOW(),
    modified          TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(source_id, target_id, relationship_type)
);

CREATE INDEX IF NOT EXISTS idx_kg_edges_source ON kg_edges(source_id);
CREATE INDEX IF NOT EXISTS idx_kg_edges_target ON kg_edges(target_id);
CREATE INDEX IF NOT EXISTS idx_kg_edges_type ON kg_edges(relationship_type);
CREATE INDEX IF NOT EXISTS idx_kg_edges_provenance ON kg_edges(provenance);

-- Proposals: staged knowledge additions from external agents
CREATE TABLE IF NOT EXISTS kg_proposals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_type   VARCHAR(50) NOT NULL,
    source_page     VARCHAR(255),
    proposed_data   JSONB NOT NULL,
    confidence      DOUBLE PRECISION DEFAULT 0.0,
    reasoning       TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'pending',
    reviewed_by     VARCHAR(100),
    created         TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kg_proposals_status ON kg_proposals(status);
CREATE INDEX IF NOT EXISTS idx_kg_proposals_source_page ON kg_proposals(source_page);

-- Rejections: negative knowledge to prevent re-proposals
CREATE TABLE IF NOT EXISTS kg_rejections (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposed_source       VARCHAR(255) NOT NULL,
    proposed_target       VARCHAR(255) NOT NULL,
    proposed_relationship VARCHAR(100) NOT NULL,
    rejected_by           VARCHAR(100),
    reason                TEXT,
    created               TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(proposed_source, proposed_target, proposed_relationship)
);

-- Grant permissions to wikantik application user
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_nodes TO wikantik;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_edges TO wikantik;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposals TO wikantik;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_rejections TO wikantik;
