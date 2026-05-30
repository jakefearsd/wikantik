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

-- V035: Seed the `agents` service account.
--
-- This is a REQUIRED SYSTEM ACCOUNT (reference data, like the bootstrap admin
-- seeded in V002) — not a business-data backfill. PageOwnerService stores it as
-- the default owner for AI-agent-authored pages whose frontmatter `author` is
-- not a real login (wikantik.page_ownership.default_owner, default `agents`),
-- giving a consistent ownership structure instead of leaving those pages
-- orphaned. Ownership is keyed on login_name only, so the account needs no
-- roles. The password is a SHA-256 hash of a discarded random secret, so the
-- account is effectively un-loginable.
--
-- The one-time backfill of EXISTING pages onto this owner is intentionally NOT
-- here (no data fixups in versioned migrations) — see
-- bin/db/one-shots/backfill-agent-default-owner.sql.
--
-- Idempotent: ON CONFLICT DO NOTHING, plus a wiki_name-uniqueness pre-check so
-- re-applying is a no-op and an operator's pre-existing `Agents` wiki_name is
-- never clobbered.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM users WHERE wiki_name = 'Agents' AND login_name <> 'agents') THEN
    INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, created, modified)
    VALUES (
      'agents-service-account',
      'agents@localhost',
      'AI Agents (service account)',
      'agents',
      '{SHA-256}Ub6qgHVQhnEDNdD2l202SLf1pVllUU6X/WO7cVsmn4LCrK6lT97JFw==',
      'Agents',
      NOW(),
      NOW()
    )
    ON CONFLICT (login_name) DO NOTHING;
  END IF;
END $$;
