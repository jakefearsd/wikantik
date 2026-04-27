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

-- V018: knowledge-graph inclusion/exclusion policy tables.
-- Per-cluster policy + append-only audit log + soft-delete excluded pages.
-- See docs/superpowers/specs/2026-04-27-kg-inclusion-policy-design.md.
-- Idempotent.

CREATE TABLE IF NOT EXISTS kg_cluster_policy (
    cluster      TEXT PRIMARY KEY,
    action       TEXT NOT NULL CHECK (action IN ('include','exclude')),
    reason       TEXT,
    set_by       TEXT NOT NULL,
    set_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS kg_policy_audit (
    id          BIGSERIAL PRIMARY KEY,
    cluster     TEXT NOT NULL,
    old_action  TEXT,
    new_action  TEXT NOT NULL,
    reason      TEXT,
    actor       TEXT NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_policy_audit_cluster_changed
    ON kg_policy_audit (cluster, changed_at DESC);

CREATE TABLE IF NOT EXISTS kg_excluded_pages (
    page_name   TEXT PRIMARY KEY,
    reason      TEXT NOT NULL CHECK (reason IN
                  ('system_page','cluster_policy','page_override')),
    excluded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_excluded_pages_reason
    ON kg_excluded_pages (reason);

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_cluster_policy   TO :app_user;
GRANT SELECT, INSERT                  ON kg_policy_audit    TO :app_user;
GRANT USAGE,  SELECT                   ON SEQUENCE kg_policy_audit_id_seq TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_excluded_pages   TO :app_user;
