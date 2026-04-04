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

-- H2-compatible Knowledge Graph schema for unit tests
-- H2 does not support JSONB or gen_random_uuid(); use TEXT for JSON columns
-- and RANDOM_UUID() for UUID generation.

-- Nodes: entities in the knowledge graph
CREATE TABLE IF NOT EXISTS kg_nodes (
    id              UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    node_type       VARCHAR(100),
    source_page     VARCHAR(255),
    provenance      VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties      TEXT DEFAULT '{}',
    created         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Edges: typed relationships between nodes
CREATE TABLE IF NOT EXISTS kg_edges (
    id                UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    source_id         UUID NOT NULL,
    target_id         UUID NOT NULL,
    relationship_type VARCHAR(100) NOT NULL,
    provenance        VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties        TEXT DEFAULT '{}',
    created           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_id, target_id, relationship_type),
    FOREIGN KEY (source_id) REFERENCES kg_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (target_id) REFERENCES kg_nodes(id) ON DELETE CASCADE
);

-- Proposals: staged knowledge additions from external agents
CREATE TABLE IF NOT EXISTS kg_proposals (
    id              UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    proposal_type   VARCHAR(50) NOT NULL,
    source_page     VARCHAR(255),
    proposed_data   TEXT NOT NULL,
    confidence      DOUBLE PRECISION DEFAULT 0.0,
    reasoning       TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'pending',
    reviewed_by     VARCHAR(100),
    created         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at     TIMESTAMP
);

-- Rejections: negative knowledge to prevent re-proposals
CREATE TABLE IF NOT EXISTS kg_rejections (
    id                    UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    proposed_source       VARCHAR(255) NOT NULL,
    proposed_target       VARCHAR(255) NOT NULL,
    proposed_relationship VARCHAR(100) NOT NULL,
    rejected_by           VARCHAR(100),
    reason                TEXT,
    created               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(proposed_source, proposed_target, proposed_relationship)
);
