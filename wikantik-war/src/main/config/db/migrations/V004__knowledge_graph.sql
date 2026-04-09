-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: knowledge graph tables and vector embeddings
--
-- Creates kg_nodes, kg_edges, kg_proposals, kg_rejections, kg_embeddings,
-- and kg_content_embeddings along with the pgvector extension. Fully
-- idempotent.
--
-- Note: CREATE EXTENSION requires superuser or a role with CREATE on the
-- database. migrate.sh honours :app_user for grants but the extension is
-- installed under whatever role actually runs the script.

CREATE EXTENSION IF NOT EXISTS vector;

-- Nodes: entities in the knowledge graph
CREATE TABLE IF NOT EXISTS kg_nodes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    node_type   VARCHAR(100),
    source_page VARCHAR(255),
    provenance  VARCHAR(50)  NOT NULL DEFAULT 'human-authored',
    properties  JSONB        DEFAULT '{}'::jsonb,
    created     TIMESTAMP    NOT NULL DEFAULT NOW(),
    modified    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_nodes_type        ON kg_nodes (node_type);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_source_page ON kg_nodes (source_page);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_provenance  ON kg_nodes (provenance);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_properties  ON kg_nodes USING GIN (properties);

-- Edges: typed relationships between nodes
CREATE TABLE IF NOT EXISTS kg_edges (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id         UUID         NOT NULL REFERENCES kg_nodes(id) ON DELETE CASCADE,
    target_id         UUID         NOT NULL REFERENCES kg_nodes(id) ON DELETE CASCADE,
    relationship_type VARCHAR(100) NOT NULL,
    provenance        VARCHAR(50)  NOT NULL DEFAULT 'human-authored',
    properties        JSONB        DEFAULT '{}'::jsonb,
    created           TIMESTAMP    NOT NULL DEFAULT NOW(),
    modified          TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (source_id, target_id, relationship_type)
);

CREATE INDEX IF NOT EXISTS idx_kg_edges_source     ON kg_edges (source_id);
CREATE INDEX IF NOT EXISTS idx_kg_edges_target     ON kg_edges (target_id);
CREATE INDEX IF NOT EXISTS idx_kg_edges_type       ON kg_edges (relationship_type);
CREATE INDEX IF NOT EXISTS idx_kg_edges_provenance ON kg_edges (provenance);

-- Proposals: staged knowledge additions from external agents
CREATE TABLE IF NOT EXISTS kg_proposals (
    id            UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_type VARCHAR(50)      NOT NULL,
    source_page   VARCHAR(255),
    proposed_data JSONB            NOT NULL,
    confidence    DOUBLE PRECISION DEFAULT 0.0,
    reasoning     TEXT,
    status        VARCHAR(50)      NOT NULL DEFAULT 'pending',
    reviewed_by   VARCHAR(100),
    created       TIMESTAMP        NOT NULL DEFAULT NOW(),
    reviewed_at   TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kg_proposals_status      ON kg_proposals (status);
CREATE INDEX IF NOT EXISTS idx_kg_proposals_source_page ON kg_proposals (source_page);

-- Rejections: negative knowledge to prevent re-proposals
CREATE TABLE IF NOT EXISTS kg_rejections (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    proposed_source       VARCHAR(255) NOT NULL,
    proposed_target       VARCHAR(255) NOT NULL,
    proposed_relationship VARCHAR(100) NOT NULL,
    rejected_by           VARCHAR(100),
    reason                TEXT,
    created               TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (proposed_source, proposed_target, proposed_relationship)
);

-- KG embeddings: ComplEx vectors for structural similarity and link prediction
CREATE TABLE IF NOT EXISTS kg_embeddings (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id     UUID,
    entity_type   VARCHAR(20)  NOT NULL CHECK (entity_type IN ('node', 'relation')),
    entity_name   VARCHAR(255) NOT NULL,
    embedding     vector(100)  NOT NULL,
    model_version INTEGER      NOT NULL DEFAULT 0,
    created       TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (entity_name, entity_type, model_version)
);

CREATE INDEX IF NOT EXISTS idx_kg_embeddings_entity  ON kg_embeddings (entity_id, entity_type);
CREATE INDEX IF NOT EXISTS idx_kg_embeddings_version ON kg_embeddings (model_version);

-- Content embeddings: TF-IDF vectors for text-based content similarity
CREATE TABLE IF NOT EXISTS kg_content_embeddings (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id     UUID,
    entity_name   VARCHAR(255) NOT NULL,
    embedding     vector(512)  NOT NULL,
    model_version INTEGER      NOT NULL DEFAULT 0,
    created       TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (entity_name, model_version)
);

CREATE INDEX IF NOT EXISTS idx_kg_content_embeddings_version ON kg_content_embeddings (model_version);

-- Grants for the application user
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_nodes              TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_edges              TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposals          TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_rejections         TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_embeddings         TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_content_embeddings TO :app_user;
