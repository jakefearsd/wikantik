-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- V024: Staged validation infrastructure for Knowledge Graph proposals.
-- Introduces tier columns (machine/human) on kg_proposals, kg_nodes, and kg_edges,
-- plus kg_proposal_reviews audit table for tracking machine and human decisions.

ALTER TABLE kg_proposals
    ADD COLUMN IF NOT EXISTS tier               VARCHAR(16) NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS machine_status     VARCHAR(16),
    ADD COLUMN IF NOT EXISTS machine_confidence DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS machine_judged_at  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS machine_model      VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_proposals' AND constraint_name = 'kg_proposals_tier_check'
    ) THEN
        ALTER TABLE kg_proposals
            ADD CONSTRAINT kg_proposals_tier_check
            CHECK (tier IN ('none','machine','human'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_proposals' AND constraint_name = 'kg_proposals_human_terminal_check'
    ) THEN
        ALTER TABLE kg_proposals
            ADD CONSTRAINT kg_proposals_human_terminal_check
            CHECK (tier <> 'human' OR status IN ('approved','rejected'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS kg_proposal_reviews (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id   UUID         NOT NULL REFERENCES kg_proposals(id) ON DELETE CASCADE,
    reviewer_kind VARCHAR(16)  NOT NULL CHECK (reviewer_kind IN ('machine','human')),
    reviewer_id   VARCHAR(100) NOT NULL,
    verdict       VARCHAR(16)  NOT NULL CHECK (verdict IN ('approved','rejected','abstain')),
    confidence    DOUBLE PRECISION,
    rationale     TEXT,
    created       TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS kg_proposal_reviews_proposal_idx
    ON kg_proposal_reviews (proposal_id, created DESC);

ALTER TABLE kg_nodes
    ADD COLUMN IF NOT EXISTS tier                   VARCHAR(16) NOT NULL DEFAULT 'human',
    ADD COLUMN IF NOT EXISTS provenance_proposal_id UUID;
ALTER TABLE kg_edges
    ADD COLUMN IF NOT EXISTS tier                   VARCHAR(16) NOT NULL DEFAULT 'human',
    ADD COLUMN IF NOT EXISTS provenance_proposal_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_nodes' AND constraint_name = 'kg_nodes_tier_check') THEN
        ALTER TABLE kg_nodes ADD CONSTRAINT kg_nodes_tier_check CHECK (tier IN ('machine','human'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_edges' AND constraint_name = 'kg_edges_tier_check') THEN
        ALTER TABLE kg_edges ADD CONSTRAINT kg_edges_tier_check CHECK (tier IN ('machine','human'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS kg_nodes_tier_idx        ON kg_nodes (tier);
CREATE INDEX IF NOT EXISTS kg_nodes_tier_name_idx   ON kg_nodes (tier, name);
CREATE INDEX IF NOT EXISTS kg_edges_tier_source_idx ON kg_edges (tier, source_id);
CREATE INDEX IF NOT EXISTS kg_edges_tier_target_idx ON kg_edges (tier, target_id);
CREATE INDEX IF NOT EXISTS kg_nodes_provenance_idx  ON kg_nodes (provenance_proposal_id);
CREATE INDEX IF NOT EXISTS kg_edges_provenance_idx  ON kg_edges (provenance_proposal_id);

UPDATE kg_proposals SET tier = 'human' WHERE status = 'approved' AND tier = 'none';

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposals       TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_nodes           TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_edges           TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposal_reviews TO :app_user;
