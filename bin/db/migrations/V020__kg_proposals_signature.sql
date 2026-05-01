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

-- V020: Idempotency for kg_proposals. Same logical proposal arriving from a
-- re-run (or from multiple chunks of the same page) becomes an UPSERT that
-- merges support evidence, not a second row. Pending-only — reviewed history
-- (approved/rejected) is allowed to repeat.

ALTER TABLE kg_proposals
  ADD COLUMN IF NOT EXISTS signature       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS support         JSONB DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS support_count   INT  DEFAULT 0,
  ADD COLUMN IF NOT EXISTS first_seen_at   TIMESTAMP DEFAULT NOW(),
  ADD COLUMN IF NOT EXISTS last_seen_at    TIMESTAMP DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS kg_proposals_pending_signature_uq
  ON kg_proposals (signature)
  WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS kg_proposals_signature_idx ON kg_proposals (signature);

-- Permissions: re-grant in case the column-level defaults vary.
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposals TO :app_user;
