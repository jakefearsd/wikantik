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

-- One-shot cleanup, 2026-04-30. Not a versioned migration.
--
-- Background: an MCP write-tool batch on 2026-04-30 rewrote frontmatter on a
-- handful of pages without preserving the existing canonical_id. The disk
-- frontmatter was committed (679f14758) with new ULIDs, but the existing
-- page_canonical_ids rows still hold the old ULIDs bound to the same slug.
-- The next save of any of these pages would otherwise trip the
-- page_canonical_ids_current_slug_key unique constraint.
--
-- The structural-spine filter has since been hardened to reuse a slug-bound
-- canonical_id rather than mint a fresh ULID when the agent omits one
-- (StructuralSpinePageFilter.preSave). That fix prevents recurrence; this
-- script unblocks the seven slugs already in the drifted state by deleting
-- the stale rows. The structural-spine bootstrap will repopulate from disk
-- on the next Tomcat start (or on the next save of each page).
--
-- Verified: zero inbound `relations:` references to the seven old IDs across
-- the corpus, so deletion does not orphan any cross-page link.
--
-- Run as the application role (it has DELETE on page_canonical_ids per V013):
--     PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki \
--         -f bin/db/cleanup-2026-04-30-stale-canonical-ids.sql

BEGIN;

DELETE FROM page_canonical_ids
WHERE current_slug IN (
    'AcidTransactionsAndIsolation',
    'ActorModelProgramming',
    'Aesthetics',
    'AiContentModerationSystems',
    'AiForDocumentation',
    'AiGovernanceFrameworks',
    'CSSThemeDark'
);
-- ON DELETE CASCADE on page_slug_history, page_relations, page_verification
-- handles dependent rows.

COMMIT;
