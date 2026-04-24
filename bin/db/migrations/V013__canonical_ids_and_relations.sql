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

-- V013: Canonical page IDs, slug history, and typed relations.
--
-- Phase 1 of the Structural Spine (see
-- docs/wikantik-pages/StructuralSpineDesign.md). Adds:
--   * page_canonical_ids   — stable identity surviving renames
--   * page_slug_history    — audit trail of slug changes per canonical_id
--   * page_relations       — typed authored relationships between pages
--
-- Phase 1 only populates page_canonical_ids and page_slug_history.
-- page_relations is created here so Phase 2 can land without another
-- DDL migration. Idempotent.

CREATE TABLE IF NOT EXISTS page_canonical_ids (
    canonical_id   CHAR(26)     PRIMARY KEY,
    current_slug   VARCHAR(512) NOT NULL UNIQUE,
    title          VARCHAR(512) NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    cluster        VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS page_slug_history (
    canonical_id   CHAR(26)     NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    previous_slug  VARCHAR(512) NOT NULL,
    renamed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (canonical_id, previous_slug)
);

CREATE TABLE IF NOT EXISTS page_relations (
    source_id      CHAR(26)     NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    target_id      CHAR(26)     NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    relation_type  VARCHAR(32)  NOT NULL
        CHECK (relation_type IN ('part-of','example-of','prerequisite-for','supersedes','contradicts','implements','derived-from')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source_id, target_id, relation_type)
);

CREATE INDEX IF NOT EXISTS ix_page_relations_target      ON page_relations(target_id, relation_type);
CREATE INDEX IF NOT EXISTS ix_page_relations_source_type ON page_relations(source_id, relation_type);
CREATE INDEX IF NOT EXISTS ix_canonical_ids_type         ON page_canonical_ids(type);
CREATE INDEX IF NOT EXISTS ix_canonical_ids_cluster      ON page_canonical_ids(cluster);

GRANT SELECT, INSERT, UPDATE, DELETE ON page_canonical_ids, page_slug_history, page_relations TO :app_user;
