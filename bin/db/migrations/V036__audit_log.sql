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

-- V036: Tamper-evident, append-only audit log. Append-only to the app role
-- (INSERT/SELECT only); UPDATE/DELETE revoked. RANGE-partitioned by month so a
-- future (v2) partition-drop purge is a no-op. DDL-only, idempotent.

-- Global monotonic sequence: assigns chain order (seq).
CREATE SEQUENCE IF NOT EXISTS audit_log_seq;

-- Partitioned parent. PK must include the partition key (created_at).
CREATE TABLE IF NOT EXISTS audit_log (
    seq             BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    event_time      TIMESTAMPTZ  NOT NULL,
    category        TEXT         NOT NULL,
    event_type      TEXT         NOT NULL,
    actor_id        TEXT,
    actor_principal TEXT,
    actor_type      TEXT         NOT NULL,
    target_type     TEXT,
    target_id       TEXT,
    target_label    TEXT,
    outcome         TEXT         NOT NULL,
    source_ip       TEXT,
    user_agent      TEXT,
    correlation_id  TEXT,
    detail          JSONB,
    prev_hash       CHAR(64)     NOT NULL,
    row_hash        CHAR(64)     NOT NULL,
    PRIMARY KEY ( seq, created_at )
) PARTITION BY RANGE ( created_at );

-- Initial monthly partitions (current + next two months). Created defensively
-- with IF NOT EXISTS; the writer's bootstrap also ensures the current partition
-- exists (Task 6) so deployment never hits a missing-partition insert error.
CREATE TABLE IF NOT EXISTS audit_log_2026_06 PARTITION OF audit_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE IF NOT EXISTS audit_log_2026_07 PARTITION OF audit_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS audit_log_2026_08 PARTITION OF audit_log
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

-- Indexes support chain walks (seq) and the admin filters.
CREATE INDEX IF NOT EXISTS idx_audit_log_seq         ON audit_log ( seq );
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at  ON audit_log ( created_at );
CREATE INDEX IF NOT EXISTS idx_audit_log_actor       ON audit_log ( actor_id );
CREATE INDEX IF NOT EXISTS idx_audit_log_event_type  ON audit_log ( event_type );
CREATE INDEX IF NOT EXISTS idx_audit_log_target      ON audit_log ( target_id );

-- Append-only to the app role: grant INSERT/SELECT, revoke UPDATE/DELETE.
-- :app_user is the standard psql variable used across these migrations.
GRANT  SELECT, INSERT ON audit_log TO :app_user;
REVOKE UPDATE, DELETE ON audit_log FROM :app_user;
GRANT  USAGE, SELECT  ON SEQUENCE audit_log_seq TO :app_user;
