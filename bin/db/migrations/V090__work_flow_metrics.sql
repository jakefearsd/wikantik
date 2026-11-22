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

-- V090: flow and DORA metrics (work management part 6).
--
-- A deployment may name the earlier deployment it remediates (fix-forward); the DORA figures read it to tell a
-- failed deployment from one that was fixed by the next. Several deployments may remediate one; a row cannot name
-- itself. The reference is not a cascade: nothing in work_deployment is ever deleted.
--
-- Three partial indexes serve the read-time figures (flow and DORA are computed on every request; no table stores a
-- computed metric). Each covers only the rows the figure reads:
--   idx_work_event_status         status events that start or finish work (cycle and lead time, throughput)
--   idx_work_deployment_verified  production deployments that went live (frequency, recovery)
--   idx_work_change_merged        merged changes by product and merge time (lead time, merge mix)
-- No table and no grant: UPDATE on work_deployment already exists, and the column is written by the INSERT.

ALTER TABLE work_deployment ADD COLUMN IF NOT EXISTS remediates_id BIGINT REFERENCES work_deployment (id);
ALTER TABLE work_deployment DROP CONSTRAINT IF EXISTS work_deployment_remediates_self_chk;
ALTER TABLE work_deployment ADD CONSTRAINT work_deployment_remediates_self_chk
    CHECK (remediates_id IS NULL OR remediates_id <> id);
CREATE INDEX IF NOT EXISTS idx_work_deployment_remediates ON work_deployment (remediates_id)
    WHERE remediates_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_work_event_status ON work_event (item_id, at)
    WHERE type = 'status' AND to_value IN ('done', 'in_progress');
CREATE INDEX IF NOT EXISTS idx_work_deployment_verified ON work_deployment (product_id, verified_at)
    WHERE environment = 'production' AND verified_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_change_merged ON work_change (product_id, closed_at)
    WHERE state = 'merged';
