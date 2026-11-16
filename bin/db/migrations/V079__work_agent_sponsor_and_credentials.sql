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

-- V079: agents get a sponsor and their own credentials (work management phase 3a).
--
-- work_agent.sponsor is the login of the person who stands behind the agent; it is looked up live on every
-- request and never copied into access rows. NULL means the agent is suspended until someone takes it over.
-- work_agent_sponsor_history is append-only: one row per change, so an agent's action at time T can be tied to
-- its sponsor at T. work_agent_credential holds the hash of the agent's own token; at most one is live per agent.
-- api_key_id becomes nullable here and is dropped, with work_agent_key_history, by V080.
--
-- Idempotent. DDL only.

ALTER TABLE work_agent ADD COLUMN IF NOT EXISTS sponsor TEXT;
DO $$
BEGIN
    -- Guarded so the file still applies cleanly once V080 has dropped the column.
    IF EXISTS ( SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'work_agent' AND column_name = 'api_key_id' ) THEN
        ALTER TABLE work_agent ALTER COLUMN api_key_id DROP NOT NULL;
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_work_agent_sponsor ON work_agent (sponsor) WHERE sponsor IS NOT NULL;

CREATE TABLE IF NOT EXISTS work_agent_sponsor_history (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id   BIGINT NOT NULL REFERENCES work_agent (id),
    sponsor    TEXT,
    since      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    changed_by TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_work_agent_sponsor_history ON work_agent_sponsor_history (agent_id, since DESC);

CREATE TABLE IF NOT EXISTS work_agent_credential (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id     BIGINT NOT NULL REFERENCES work_agent (id),
    token_hash   TEXT NOT NULL UNIQUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by   TEXT NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    revoked_at   TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_work_agent_credential_live ON work_agent_credential (agent_id)
    WHERE revoked_at IS NULL;

-- The sponsor history is append-only for the app role; credentials are revoked by UPDATE, never deleted.
GRANT SELECT, INSERT ON work_agent_sponsor_history TO :app_user;
GRANT SELECT, INSERT, UPDATE ON work_agent_credential TO :app_user;
