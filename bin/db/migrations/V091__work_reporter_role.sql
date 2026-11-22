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

-- V091: work management intake. A fourth project role, 'reporter', below 'viewer': a person or a group who files
-- items into a project and sees only the items they reported. An agent's role is viewer or member, never reporter
-- or lead: the service and the store refuse it, and the CHECK makes it structural. Requires V074.
-- Idempotent: constraints are dropped if present and re-added; the index is IF NOT EXISTS.
-- Owner check before deploying (R3): SELECT key FROM work_project WHERE key ILIKE 'SUBMIT' must return no row, and
-- no agent row may hold a role other than viewer or member.
ALTER TABLE work_member DROP CONSTRAINT IF EXISTS work_member_role_check;
ALTER TABLE work_member DROP CONSTRAINT IF EXISTS work_member_role_chk;
ALTER TABLE work_member ADD CONSTRAINT work_member_role_chk
    CHECK (role IN ('reporter', 'viewer', 'member', 'lead'));
ALTER TABLE work_member DROP CONSTRAINT IF EXISTS work_member_agent_role_chk;
ALTER TABLE work_member ADD CONSTRAINT work_member_agent_role_chk
    CHECK (principal_kind <> 'agent' OR role IN ('viewer', 'member'));

-- "does this project have reporters at all": the outside-reporter read exits on this before touching items.
CREATE INDEX IF NOT EXISTS idx_work_member_reporters ON work_member (project_id) WHERE role = 'reporter';
