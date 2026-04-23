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

-- PostgreSQL test schema for Testcontainers.
-- Combines production DDLs (postgresql.ddl, postgresql-knowledge.ddl,
-- postgresql-permissions.ddl) stripped of superuser commands, plus test seed data.

-- pgvector extension (pre-installed in pgvector/pgvector Docker image)
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- User / Group / Role tables (from postgresql.ddl)
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    uid            VARCHAR(100),
    email          VARCHAR(100),
    full_name      VARCHAR(100),
    login_name     VARCHAR(100) NOT NULL PRIMARY KEY,
    password       VARCHAR(100),
    wiki_name      VARCHAR(100),
    created        TIMESTAMP,
    modified       TIMESTAMP,
    lock_expiry    TIMESTAMP,
    bio            VARCHAR(1000),
    attributes     TEXT
);

CREATE TABLE IF NOT EXISTS roles (
    login_name     VARCHAR(100) NOT NULL,
    role           VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS groups (
    name           VARCHAR(100) NOT NULL PRIMARY KEY,
    creator        VARCHAR(100),
    created        TIMESTAMP,
    modifier       VARCHAR(100),
    modified       TIMESTAMP
);

CREATE TABLE IF NOT EXISTS group_members (
    name           VARCHAR(100) NOT NULL,
    member         VARCHAR(100) NOT NULL,
    CONSTRAINT group_members_pk PRIMARY KEY (name, member)
);

-- ============================================================
-- Policy grants (from postgresql-permissions.ddl)
-- ============================================================

CREATE TABLE IF NOT EXISTS policy_grants (
    id              SERIAL PRIMARY KEY,
    principal_type  VARCHAR(10) NOT NULL,
    principal_name  VARCHAR(255) NOT NULL,
    permission_type VARCHAR(10) NOT NULL,
    target          VARCHAR(255) NOT NULL,
    actions         VARCHAR(255) NOT NULL,
    UNIQUE(principal_type, principal_name, permission_type, target)
);

-- ============================================================
-- Knowledge Graph tables (from postgresql-knowledge.ddl)
-- ============================================================

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

CREATE TABLE IF NOT EXISTS kg_embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id       UUID,
    entity_type     VARCHAR(20) NOT NULL CHECK (entity_type IN ('node', 'relation')),
    entity_name     VARCHAR(255) NOT NULL,
    embedding       vector NOT NULL,
    model_version   INTEGER NOT NULL DEFAULT 0,
    created         TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(entity_name, entity_type, model_version)
);

CREATE INDEX IF NOT EXISTS idx_kg_embeddings_entity ON kg_embeddings(entity_id, entity_type);
CREATE INDEX IF NOT EXISTS idx_kg_embeddings_version ON kg_embeddings(model_version);

CREATE TABLE IF NOT EXISTS kg_content_embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id       UUID,
    entity_name     VARCHAR(255) NOT NULL,
    embedding       vector NOT NULL,
    model_version   INTEGER NOT NULL DEFAULT 0,
    created         TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(entity_name, model_version)
);

CREATE INDEX IF NOT EXISTS idx_kg_content_embeddings_version ON kg_content_embeddings(model_version);

-- Passage-level content chunks (V008)
CREATE TABLE IF NOT EXISTS kg_content_chunks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_name            TEXT        NOT NULL,
    chunk_index          INT         NOT NULL,
    heading_path         TEXT[]      NOT NULL DEFAULT '{}',
    text                 TEXT        NOT NULL,
    char_count           INT         NOT NULL,
    token_count_estimate INT         NOT NULL,
    content_hash         TEXT        NOT NULL,
    created              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT kg_content_chunks_page_index_uniq UNIQUE (page_name, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_page_name
    ON kg_content_chunks (page_name);
CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_content_hash
    ON kg_content_chunks (content_hash);

-- Dense embeddings projection of kg_content_chunks (V009)
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

-- Chunk → KG node mention bridge (V011). Populated by the extractor in Phase 2;
-- readers tolerate an empty table in Phase 1 by returning empty results.
CREATE TABLE IF NOT EXISTS chunk_entity_mentions (
    chunk_id     UUID        NOT NULL REFERENCES kg_content_chunks(id) ON DELETE CASCADE,
    node_id      UUID        NOT NULL REFERENCES kg_nodes(id)           ON DELETE CASCADE,
    confidence   REAL        NOT NULL DEFAULT 1.0,
    extractor    TEXT        NOT NULL,
    extracted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (chunk_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_chunk_entity_mentions_node
    ON chunk_entity_mentions (node_id);
CREATE INDEX IF NOT EXISTS idx_chunk_entity_mentions_chunk
    ON chunk_entity_mentions (chunk_id);
CREATE INDEX IF NOT EXISTS idx_chunk_entity_mentions_extractor
    ON chunk_entity_mentions (extractor);

-- ============================================================
-- Test seed data
-- ============================================================

-- Users (password hashes are application-level, DB-independent)
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, attributes)
VALUES ('-7739839977499061014', 'janne@ecyrd.com', 'Janne Jalkanen', 'janne',
        '{SSHA}1WFv9OV11pD5IySgVH3sFa2VlCyYjbLrcVT/qw==', 'JanneJalkanen',
        'attribute1=some random value' || chr(10) || 'attribute2=another value');

INSERT INTO users (uid, email, full_name, login_name, password, wiki_name)
VALUES ('-6852820166199419346', 'admin@locahost', 'Administrator', 'admin',
        '{SSHA}6YNKYMwXICUf5pMvYUZumgbFCxZMT2njtUQtJw==', 'Administrator');

-- Roles
INSERT INTO roles (login_name, role) VALUES ('janne', 'Authenticated');
INSERT INTO roles (login_name, role) VALUES ('admin', 'Authenticated');
INSERT INTO roles (login_name, role) VALUES ('admin', 'Admin');

-- Groups
INSERT INTO groups (name, created, modified)
VALUES ('TV', '2006-06-20 14:50:54', '2006-06-20 14:50:54');
INSERT INTO group_members (name, member) VALUES ('TV', 'Archie Bunker');
INSERT INTO group_members (name, member) VALUES ('TV', 'BullwinkleMoose');
INSERT INTO group_members (name, member) VALUES ('TV', 'Fred Friendly');

INSERT INTO groups (name, created, modified)
VALUES ('Literature', '2006-06-20 14:50:54', '2006-06-20 14:50:54');
INSERT INTO group_members (name, member) VALUES ('Literature', 'Charles Dickens');
INSERT INTO group_members (name, member) VALUES ('Literature', 'Homer');

INSERT INTO groups (name, created, modified)
VALUES ('Art', '2006-06-20 14:50:54', '2006-06-20 14:50:54');

INSERT INTO groups (name, created, modified)
VALUES ('Admin', '2006-06-20 14:50:54', '2006-06-20 14:50:54');
INSERT INTO group_members (name, member) VALUES ('Admin', 'Administrator');

-- Default policy grants (from postgresql-permissions.ddl)
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'page', '*', 'view') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'wiki', '*', 'editPreferences,editProfile,login') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'page', '*', 'modify') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'wiki', '*', 'createPages') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'page', '*', 'modify') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'wiki', '*', 'createPages') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'group', '*', 'view') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'page', '*', 'modify,rename') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'wiki', '*', 'createPages,createGroups') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '*', 'view') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '<groupmember>', 'edit') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'page', '*', '*') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'wiki', '*', '*') ON CONFLICT DO NOTHING;

-- Hub membership tables
CREATE TABLE IF NOT EXISTS hub_centroids (
    id              SERIAL PRIMARY KEY,
    hub_name        VARCHAR(255) NOT NULL UNIQUE,
    centroid        vector(512) NOT NULL,
    model_version   INTEGER NOT NULL,
    member_count    INTEGER NOT NULL,
    created         TIMESTAMP NOT NULL DEFAULT NOW()
);

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

CREATE TABLE IF NOT EXISTS hub_discovery_proposals (
    id              SERIAL PRIMARY KEY,
    suggested_name  TEXT             NOT NULL,
    exemplar_page   TEXT             NOT NULL,
    member_pages    JSONB            NOT NULL,
    coherence_score DOUBLE PRECISION NOT NULL,
    status          VARCHAR(20)      NOT NULL DEFAULT 'pending',
    reviewed_by     VARCHAR(255),
    reviewed_at     TIMESTAMPTZ,
    created         TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hub_discovery_proposals_created
    ON hub_discovery_proposals ( created DESC );

CREATE INDEX IF NOT EXISTS idx_hub_discovery_proposals_status
    ON hub_discovery_proposals ( status );

-- JDBCPlugin test table
CREATE TABLE IF NOT EXISTS employees (
    id   SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    dept VARCHAR(50)
);
