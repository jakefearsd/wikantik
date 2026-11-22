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

-- V094: indexes for cross-project find and the DORA deployment read (work hardening, #157).
--
-- Found by EXPLAIN ANALYZE over 100k items, 400k events, 20k deployments and 30k changes:
--
-- Deployments. The DORA deployment read keeps a production deployment that started OR was verified in the window.
-- Neither arm had an index that serves a project-scoped read (idx_work_deployment_verified leads with product_id,
-- and nothing covered started_at), so the project route read every production deployment. One partial index per arm,
-- each over only the rows the read can see.
--
-- Find. The default sort is newest-updated first with a keyset cursor, a row comparison on (updated_at, id), over
-- every visible project at once (project_id = ANY(...)). One index over those two columns lets the planner walk the
-- sort order backwards and stop at the page size. Only the default sort is indexed: updated_at changes on nearly
-- every item write, so each extra index on work_item defeats HOT updates and adds an index write to every edit on the
-- hottest table. The other sorts (created, priority, due) still sort the matching rows; measured over 100k items
-- that cost 4-5 ms for one project and 28-30 ms for ten, which is a documented limit at small-team scale.

-- Plain CREATE INDEX IF NOT EXISTS: re-applying is a no-op.

CREATE INDEX IF NOT EXISTS idx_work_deployment_started ON work_deployment (started_at)
    WHERE environment = 'production' AND started_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_deployment_live ON work_deployment (verified_at)
    WHERE environment = 'production' AND verified_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_work_item_find_updated ON work_item (updated_at, id);
