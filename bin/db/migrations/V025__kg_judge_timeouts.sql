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

-- V025: Per-proposal judge-call timeout tracking.
--
-- Records read-timeouts emitted by DefaultKgProposalJudgeService so:
--   * subsequent calls for the same proposal use a longer timeout
--     (multiplier = min(1 + timeout_count, 3));
--   * operators can surface chronic-timeout proposals in the admin UI
--     and decide whether to manually approve / reject / give up;
--   * we have diagnostic context (model, content size, error excerpt)
--     to distinguish content-shaped slowness from infra slowness.
--
-- The row is keyed by proposal_id (PK). On any successful HTTP
-- completion the row is deleted; on timeout the row is upserted with
-- the counter incremented. When a re-extraction creates a new proposal
-- UUID we naturally get a fresh start.

CREATE TABLE IF NOT EXISTS kg_judge_timeouts (
    proposal_id          UUID         PRIMARY KEY,
    content_sha256       VARCHAR(64)  NOT NULL,
    source_page          TEXT,
    proposal_type        VARCHAR(64),
    model_name           VARCHAR(128),
    content_bytes        INTEGER,
    timeout_count        INTEGER      NOT NULL DEFAULT 1,
    last_error_excerpt   TEXT,
    base_timeout_seconds INTEGER      NOT NULL,
    first_seen           TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_seen            TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT kg_judge_timeouts_count_check CHECK (timeout_count >= 1)
);

CREATE INDEX IF NOT EXISTS kg_judge_timeouts_count_idx
    ON kg_judge_timeouts (timeout_count DESC, last_seen DESC);
CREATE INDEX IF NOT EXISTS kg_judge_timeouts_content_sha_idx
    ON kg_judge_timeouts (content_sha256);

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_judge_timeouts TO :app_user;
