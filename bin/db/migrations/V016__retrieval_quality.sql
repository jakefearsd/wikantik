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

-- V016: Retrieval-quality CI persistence.
--
-- Phase 5 of the Agent-Grade Content design (see
-- docs/wikantik-pages/AgentGradeContentDesign.md, "Retrieval-quality CI").
-- Three tables:
--   * retrieval_query_sets — named bundles of evaluation queries
--   * retrieval_queries    — (query_set_id, query_id) -> (text, expected ids)
--   * retrieval_runs       — one row per (set, mode) execution with aggregate
--                            nDCG / Recall / MRR scores
--
-- The seed query set ships in V017. Tables are idempotent.

CREATE TABLE IF NOT EXISTS retrieval_query_sets (
    id            VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS retrieval_queries (
    query_set_id  VARCHAR(64)  NOT NULL REFERENCES retrieval_query_sets(id) ON DELETE CASCADE,
    query_id      VARCHAR(64)  NOT NULL,
    query_text    TEXT         NOT NULL,
    expected_ids  TEXT[]       NOT NULL,
    PRIMARY KEY (query_set_id, query_id)
);

CREATE TABLE IF NOT EXISTS retrieval_runs (
    run_id        BIGSERIAL    PRIMARY KEY,
    query_set_id  VARCHAR(64)  NOT NULL REFERENCES retrieval_query_sets(id) ON DELETE CASCADE,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ,
    mode          VARCHAR(32)  NOT NULL,
    ndcg_at_5     NUMERIC(5,4),
    ndcg_at_10    NUMERIC(5,4),
    recall_at_20  NUMERIC(5,4),
    mrr           NUMERIC(5,4),
    notes         JSONB
);

CREATE INDEX IF NOT EXISTS ix_retrieval_runs_set_start
    ON retrieval_runs(query_set_id, started_at DESC);

CREATE INDEX IF NOT EXISTS ix_retrieval_runs_mode
    ON retrieval_runs(mode);

GRANT SELECT, INSERT, UPDATE, DELETE
    ON retrieval_query_sets, retrieval_queries, retrieval_runs
    TO :app_user;

GRANT USAGE, SELECT ON SEQUENCE retrieval_runs_run_id_seq TO :app_user;
