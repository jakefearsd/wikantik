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

-- V014: Page verification + trusted-authors registry.
--
-- Phase 1 of the Agent-Grade Content design (see
-- docs/wikantik-pages/AgentGradeContentDesign.md). Adds:
--   * page_verification — derived projection of frontmatter
--     verified_at / verified_by / audience plus the computed confidence
--   * trusted_authors  — small registry that promotes a verified author's
--     pages from "provisional" to "authoritative" confidence
--
-- Frontmatter remains the source of truth; this table is rebuildable
-- from a full corpus scan. Idempotent.

CREATE TABLE IF NOT EXISTS page_verification (
    canonical_id   CHAR(26)    PRIMARY KEY REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    verified_at    TIMESTAMPTZ,
    verified_by    VARCHAR(64),
    confidence     VARCHAR(16) NOT NULL DEFAULT 'provisional'
        CHECK (confidence IN ('authoritative','provisional','stale')),
    audience       VARCHAR(32) NOT NULL DEFAULT 'humans-and-agents'
        CHECK (audience IN ('humans','agents','humans-and-agents')),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_page_verification_confidence  ON page_verification(confidence);
CREATE INDEX IF NOT EXISTS ix_page_verification_verified_at ON page_verification(verified_at);

CREATE TABLE IF NOT EXISTS trusted_authors (
    login_name    VARCHAR(64)  PRIMARY KEY,
    notes         TEXT,
    added_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON page_verification, trusted_authors TO :app_user;
