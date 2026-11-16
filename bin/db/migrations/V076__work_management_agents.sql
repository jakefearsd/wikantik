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
-- V076: work management phase 2 (agents). Queues, the agent registry, claim columns on work_item, and the
-- columns that record which agent made a write. Requires V074 (work_*) and V010 (api_keys).
-- A work_agent binds exactly one API key; an agent's role on a project is a work_member row (kind 'agent').
-- Claims carry no autonomy logic; work_event stays append-only (no new grants on it).
-- api_keys.scope gains 'work' (V061 pattern: drop-if-exists + re-add, keeping every scope in force).
-- Idempotent: IF NOT EXISTS throughout, constraints guarded by pg_constraint, re-grants are no-ops.

CREATE TABLE IF NOT EXISTS work_queue (
    key         TEXT PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9-]{1,29}$'),
    name        TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    retired_at  TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS work_agent (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE
               CHECK (name ~ '^[a-z0-9][a-z0-9._-]{0,39}(@[a-z0-9][a-z0-9.-]{0,39})?$'),
    vendor     TEXT NOT NULL DEFAULT '',
    runtime    TEXT NOT NULL DEFAULT '',
    api_key_id INTEGER NOT NULL UNIQUE REFERENCES api_keys (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    retired_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS work_agent_queue (
    agent_id  BIGINT NOT NULL REFERENCES work_agent (id),
    queue_key TEXT   NOT NULL REFERENCES work_queue (key),
    PRIMARY KEY (agent_id, queue_key)
);

CREATE INDEX IF NOT EXISTS idx_work_agent_queue_queue ON work_agent_queue (queue_key);

ALTER TABLE work_item ADD COLUMN IF NOT EXISTS assignee_queue    TEXT REFERENCES work_queue (key);
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS claimed_by_agent  BIGINT REFERENCES work_agent (id);
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS claimed_at        TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS claim_expires_at  TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS reporter_agent_id BIGINT REFERENCES work_agent (id);
ALTER TABLE work_comment ADD COLUMN IF NOT EXISTS author_agent_id BIGINT REFERENCES work_agent (id);
ALTER TABLE work_item_revision ADD COLUMN IF NOT EXISTS edited_by_agent_id BIGINT REFERENCES work_agent (id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'work_item_one_assignee_chk') THEN
        ALTER TABLE work_item ADD CONSTRAINT work_item_one_assignee_chk
            CHECK (assignee IS NULL OR assignee_queue IS NULL);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'work_item_claim_chk') THEN
        ALTER TABLE work_item ADD CONSTRAINT work_item_claim_chk
            CHECK ((claimed_by_agent IS NULL) = (claim_expires_at IS NULL)
               AND (claimed_by_agent IS NULL) = (claimed_at IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'work_event_agent_fk') THEN
        ALTER TABLE work_event ADD CONSTRAINT work_event_agent_fk
            FOREIGN KEY (agent_id) REFERENCES work_agent (id);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_work_item_claimable ON work_item (assignee_queue, status, priority)
    WHERE claimed_by_agent IS NULL AND assignee_queue IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_item_claim_expiry ON work_item (claim_expires_at)
    WHERE claimed_by_agent IS NOT NULL;

ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS api_keys_scope_chk;
ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_scope_chk CHECK (scope IN ('mcp', 'mcp_admin', 'mcp_read', 'tools', 'all', 'work'));

-- work_agent_queue rows are derived (replaced on agent update), so the app role may delete them.
GRANT SELECT, INSERT, UPDATE ON work_queue, work_agent TO :app_user;
GRANT SELECT, INSERT, DELETE ON work_agent_queue TO :app_user;
