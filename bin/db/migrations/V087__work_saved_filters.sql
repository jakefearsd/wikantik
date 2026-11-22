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

-- V087: saved filters (work management part 4).
--
-- A saved filter is a person's named search over work items: owner is the login that saved it, filter is the
-- search as the JSON wire form of FindFilter (no cursor, no limit). A private filter (shared = FALSE) may span
-- projects and carries no project; a shared filter is pinned to its own project (P4 ruling Q5), so the CHECK
-- refuses a shared filter without one. A name is unique per owner. Rows are a person's own settings and may be
-- deleted. Additive and idempotent: no data backfill.

CREATE TABLE IF NOT EXISTS work_saved_filter (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL,
  project_id BIGINT REFERENCES work_project (id), shared BOOLEAN NOT NULL DEFAULT FALSE,
  filter JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (NOT shared OR project_id IS NOT NULL), UNIQUE (owner, name));

GRANT SELECT, INSERT, UPDATE, DELETE ON work_saved_filter TO :app_user;
