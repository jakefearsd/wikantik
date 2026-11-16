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

-- V081: change records and acknowledgement (work management phase 3b).
--
-- work_change is one branch of work against one product, attached to one item: open -> in_review -> merged, or
-- abandoned. An approval names the commit it approved (reviewed_head_sha); a merge records how it happened
-- (merge_kind). Rows are never deleted. The ack columns flag an item an agent closed with no person in the path
-- until a person acknowledges it.

CREATE TABLE IF NOT EXISTS work_change (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id              BIGINT NOT NULL REFERENCES work_item (id),
    product_id           BIGINT NOT NULL REFERENCES work_product (id),
    branch               TEXT NOT NULL,
    base_branch          TEXT NOT NULL,
    head_sha             TEXT,
    review_url           TEXT,
    state                TEXT NOT NULL DEFAULT 'open'
                         CHECK (state IN ('open', 'in_review', 'merged', 'abandoned')),
    merged_sha           TEXT,
    merge_kind           TEXT CHECK (merge_kind IN ('reviewed', 'unreviewed', 'direct')),
    opened_by            TEXT NOT NULL,
    opened_by_agent_id   BIGINT REFERENCES work_agent (id),
    reviewed_by          TEXT,
    reviewed_by_agent_id BIGINT REFERENCES work_agent (id),
    reviewed_head_sha    TEXT,
    reviewed_at          TIMESTAMP WITH TIME ZONE,
    merged_by            TEXT,
    merged_by_agent_id   BIGINT REFERENCES work_agent (id),
    opened_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    closed_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT work_change_merge_kind_chk CHECK ((state = 'merged') = (merge_kind IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_work_change_live_branch ON work_change (item_id, branch)
    WHERE state IN ('open', 'in_review');
CREATE INDEX IF NOT EXISTS idx_work_change_item ON work_change (item_id);

ALTER TABLE work_item ADD COLUMN IF NOT EXISTS ack_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS acknowledged_by TEXT;
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX IF NOT EXISTS idx_work_item_ack ON work_item (project_id)
    WHERE ack_required AND acknowledged_at IS NULL;

-- Changes are closed by UPDATE, never deleted.
GRANT SELECT, INSERT, UPDATE ON work_change TO :app_user;
