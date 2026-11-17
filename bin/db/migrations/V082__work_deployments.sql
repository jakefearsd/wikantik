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

-- V082: deployments (work management phase 4).
--
-- A deployment is one product version going to an environment, carrying a set of items. It moves forward only:
-- requested -> approved -> running -> verified | failed, and verified | failed -> rolled_back. Marking it
-- verified needs evidence. Its history is append-only, as work_event is; a deployment belongs to a product, not
-- a project, so its history cannot live in work_event (which needs a project).

CREATE TABLE IF NOT EXISTS work_deployment (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id            BIGINT NOT NULL REFERENCES work_product (id),
    environment           TEXT NOT NULL DEFAULT 'production',
    version               TEXT NOT NULL,
    state                 TEXT NOT NULL DEFAULT 'requested'
                          CHECK (state IN ('requested', 'approved', 'running', 'verified', 'failed', 'rolled_back')),
    requested_by          TEXT NOT NULL,
    requested_by_agent_id BIGINT REFERENCES work_agent (id),
    approved_by           TEXT,
    approved_by_agent_id  BIGINT REFERENCES work_agent (id),
    requested_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    approved_at           TIMESTAMP WITH TIME ZONE,
    started_at            TIMESTAMP WITH TIME ZONE,
    verified_at           TIMESTAMP WITH TIME ZONE,
    closed_at             TIMESTAMP WITH TIME ZONE,
    evidence              TEXT,
    CONSTRAINT work_deployment_evidence_chk
        CHECK (state <> 'verified' OR (evidence IS NOT NULL AND btrim(evidence) <> ''))
);
CREATE INDEX IF NOT EXISTS idx_work_deployment_product ON work_deployment (product_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_work_deployment_waiting ON work_deployment (product_id) WHERE state = 'requested';

CREATE TABLE IF NOT EXISTS work_deployment_item (
    deployment_id BIGINT NOT NULL REFERENCES work_deployment (id),
    item_id       BIGINT NOT NULL REFERENCES work_item (id),
    PRIMARY KEY (deployment_id, item_id)
);
CREATE INDEX IF NOT EXISTS idx_work_deployment_item_item ON work_deployment_item (item_id);

CREATE TABLE IF NOT EXISTS work_deployment_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deployment_id BIGINT NOT NULL REFERENCES work_deployment (id),
    at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    actor         TEXT NOT NULL,
    actor_kind    TEXT NOT NULL CHECK (actor_kind IN ('user', 'agent')),
    agent_id      BIGINT REFERENCES work_agent (id),
    from_state    TEXT,
    to_state      TEXT NOT NULL,
    note          TEXT
);
CREATE INDEX IF NOT EXISTS idx_work_deployment_event ON work_deployment_event (deployment_id, at, id);

-- A deployment moves by UPDATE and is never deleted; what it carries and its history are only ever added to.
GRANT SELECT, INSERT, UPDATE ON work_deployment TO :app_user;
GRANT SELECT, INSERT ON work_deployment_item, work_deployment_event TO :app_user;
