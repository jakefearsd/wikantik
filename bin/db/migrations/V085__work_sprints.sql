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

-- V085: sprints, the item sprint and estimate (work management part 2).
--
-- A sprint is a named date range in one project, planned, active or closed (at most one active per project).
-- Its figures are NULL until it starts (committed) and until it closes (completed); the record check ties each
-- state to the columns it needs. Work items gain an optional sprint and an estimate in half points from 0 to
-- 999.5; an epic is never planned into a sprint nor estimated. Additive and idempotent: no data backfill.
-- A sprint is never deleted; work_item already grants UPDATE. Index idx_work_event_sprint_moves serves the
-- sprint-move history of a project.

CREATE TABLE IF NOT EXISTS work_sprint (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES work_project (id),
  name TEXT NOT NULL, goal TEXT NOT NULL DEFAULT '',
  starts_on DATE NOT NULL, ends_on DATE NOT NULL CHECK (ends_on >= starts_on),
  state TEXT NOT NULL DEFAULT 'planned' CHECK (state IN ('planned','active','closed')),
  started_at TIMESTAMPTZ, closed_at TIMESTAMPTZ,
  committed_items INTEGER, committed_points NUMERIC(7,1),
  completed_items INTEGER, completed_points NUMERIC(7,1),
  version INTEGER NOT NULL DEFAULT 1,
  UNIQUE (project_id, name),
  CONSTRAINT work_sprint_record_chk CHECK (
    (state = 'planned') = (started_at IS NULL)
    AND (state = 'closed') = (closed_at IS NOT NULL)
    AND (started_at IS NULL) = (committed_items IS NULL)
    AND (closed_at IS NULL) = (completed_items IS NULL)));
CREATE UNIQUE INDEX IF NOT EXISTS idx_work_sprint_active ON work_sprint (project_id) WHERE state = 'active';
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS sprint_id BIGINT REFERENCES work_sprint (id);
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS estimate NUMERIC(4,1)
  CHECK (estimate IS NULL OR (estimate >= 0 AND estimate <= 999.5 AND estimate * 2 = floor(estimate * 2)));
ALTER TABLE work_item DROP CONSTRAINT IF EXISTS work_item_epic_unplanned_chk;
ALTER TABLE work_item ADD CONSTRAINT work_item_epic_unplanned_chk
  CHECK (level <> 'epic' OR (sprint_id IS NULL AND estimate IS NULL));
CREATE INDEX IF NOT EXISTS idx_work_item_sprint ON work_item (sprint_id) WHERE sprint_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_event_sprint_moves ON work_event (project_id, item_id)
  WHERE type = 'field' AND note = 'sprint';
-- A sprint is never deleted; work_item already grants UPDATE.
GRANT SELECT, INSERT, UPDATE ON work_sprint TO :app_user;
