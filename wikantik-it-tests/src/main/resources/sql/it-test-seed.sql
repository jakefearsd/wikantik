-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- IT seed data. Runs after migrate.sh has created users/roles/groups/
-- group_members via V002__core_users_groups.sql. Idempotent (ON CONFLICT).

-- -----------------------------------------------------------------------
-- Test users (janne for JDBCPluginIT admin login, plus group fixtures)
-- -----------------------------------------------------------------------
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, attributes)
VALUES (
  '-7739839977499061014',
  'janne@ecyrd.com',
  'Janne Jalkanen',
  'janne',
  '{SSHA}1WFv9OV11pD5IySgVH3sFa2VlCyYjbLrcVT/qw==',
  'JanneJalkanen',
  'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAF3CAAAAAIAAAABdAAKYXR0cmlidXRlMXQAK3NvbWUgcmFuZG9tIHZhbHVlXG5hdHRyaWJ1dGUyPWFub3RoZXIgdmFsdWV4'
)
ON CONFLICT (login_name) DO NOTHING;

INSERT INTO roles (login_name, role) VALUES ('janne', 'Authenticated')
ON CONFLICT DO NOTHING;

-- Give JanneJalkanen admin group membership so IT suites that need admin
-- can log in as janne. GroupManager.isUserInRole matches by principal name,
-- and after login the session carries the wiki-name WikiPrincipal.
INSERT INTO group_members (name, member) VALUES ('Admin', 'JanneJalkanen')
ON CONFLICT DO NOTHING;

-- Fixture users carried over from the retired XML user database (userdatabase.xml).
-- IT suites reference these by login/wiki name — e.g. CommentThreadIT logs in as
-- "Alice" (password "password") to verify the my-mentions round-trip. Hashes are the
-- exact {SSHA} values the XML seed used, so the recorded passwords still validate.
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name) VALUES
  ( 'it-seed-user-alice',   'alice@example.com',   'Alice',           'Alice',   '{SSHA}3V4zI5W6mT+x5NIHKI2KFQIYBdnAYKNOE9Aj+Q==', 'Alice' ),
  ( 'it-seed-user-bob',     'bob@example.com',     'Bob',             'Bob',     '{SSHA}NP3aAmiwK0gHywTe4qbY6klKDqnZ+F9ym9YiLg==', 'Bob' ),
  ( 'it-seed-user-charlie', 'charlie@example.com', 'Charlie',         'Charlie', '{SSHA}wn81B14F9axtTVYsipQKC2OWQHlc6EcpMSe58Q==', 'Charlie' ),
  ( 'it-seed-user-fred',    'fred@example.com',    'Fred Flintstone', 'Fred',    '{SSHA}iDeE9dysPUE28SWd6yeIqiIj9sIVyiMM7VnMKQ==', 'FredFlintstone' ),
  ( 'it-seed-user-biff',    'biff@example.com',    'Biff',            'Biff',    '{SSHA}xKAIienaZZHhKTGCNv5Li6lzeemaSs6ZYXTHFQ==', 'Biff' )
ON CONFLICT (login_name) DO NOTHING;

INSERT INTO roles (login_name, role) VALUES
  ( 'Alice', 'Authenticated' ),
  ( 'Bob', 'Authenticated' ),
  ( 'Charlie', 'Authenticated' ),
  ( 'Fred', 'Authenticated' ),
  ( 'Biff', 'Authenticated' )
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------
-- JDBCPluginIT sample data
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
  id integer NOT NULL PRIMARY KEY,
  name varchar(100) NOT NULL,
  category varchar(50),
  price decimal(10,2),
  in_stock integer
);

-- Grant DML to the app user so the JDBCPlugin (running as jspwiki) can read it.
GRANT SELECT, INSERT, UPDATE, DELETE ON products TO jspwiki;

TRUNCATE products;
INSERT INTO products (id, name, category, price, in_stock) VALUES
  (1, 'Laptop',       'Electronics', 999.99, 10),
  (2, 'Mouse',        'Electronics',  29.99, 50),
  (3, 'Keyboard',     'Electronics',  79.99, 25),
  (4, 'Office Chair', 'Furniture',   299.99,  8),
  (5, 'Desk Lamp',    'Furniture',    49.99, 30);

-- -----------------------------------------------------------------------
-- Supplementary group fixtures
-- -----------------------------------------------------------------------
INSERT INTO groups (name, created, modified) VALUES
  ('TV',         NOW(), NOW()),
  ('Literature', NOW(), NOW()),
  ('Art',        NOW(), NOW())
ON CONFLICT DO NOTHING;

INSERT INTO group_members (name, member) VALUES
  ('TV',         'Archie Bunker'),
  ('TV',         'BullwinkleMoose'),
  ('TV',         'Fred Friendly'),
  ('Literature', 'Charles Dickens'),
  ('Literature', 'Homer')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------
-- IT policy overrides — legacy tests grant broad edit rights to anonymous
-- users so pages could be created/modified without login. Production grants
-- only `view`
-- to role All; these extra IT grants keep the existing test expectations.
-- -----------------------------------------------------------------------
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) VALUES
    ('role', 'All',           'page',  '*', 'view,edit,modify,delete,upload,rename,comment'),
    ('role', 'All',           'wiki',  '*', 'createPages,createGroups,editPreferences,editProfile,login'),
    ('role', 'Anonymous',     'page',  '*', 'view,edit,modify,delete,upload,rename,comment'),
    ('role', 'Anonymous',     'wiki',  '*', 'createPages,createGroups')
ON CONFLICT (principal_type, principal_name, permission_type, target) DO UPDATE
    SET actions = EXCLUDED.actions;

-- -----------------------------------------------------------------------
-- KG curation IT fixtures (KgCurationIT)
-- Fixed UUIDs so WithMcpTestSetup can expose them as static constants.
-- -----------------------------------------------------------------------

-- Two seed nodes: one used as a merge-self target, one as the edge endpoint.
INSERT INTO kg_nodes (id, name, node_type, source_page, provenance)
VALUES
  ('aaaaaaaa-0001-0000-0000-000000000001', 'KgCurationSeedNode', 'concept', 'KgCurationSeedPage', 'human-authored'),
  ('aaaaaaaa-0002-0000-0000-000000000002', 'KgCurationEdgeSrc',  'concept', 'KgCurationSeedPage', 'human-authored')
ON CONFLICT (name) DO NOTHING;

-- One HUMAN_CURATED edge between the two seed nodes (for confirm + delete tests).
INSERT INTO kg_edges (id, source_id, target_id, relationship_type, provenance)
VALUES (
  'bbbbbbbb-0001-0000-0000-000000000001',
  'aaaaaaaa-0002-0000-0000-000000000002',
  'aaaaaaaa-0001-0000-0000-000000000001',
  'related_to',
  'human-curated'
)
ON CONFLICT (source_id, target_id, relationship_type) DO NOTHING;

-- Pending new-node proposal (inspect_proposals + review_proposals approve path).
INSERT INTO kg_proposals (id, proposal_type, source_page, proposed_data, confidence, status)
VALUES (
  'cccccccc-0001-0000-0000-000000000001',
  'new-node',
  'KgCurationSeedPage',
  '{"name":"SeedProposalNode","type":"concept"}'::jsonb,
  0.85,
  'pending'
)
ON CONFLICT DO NOTHING;

-- Pending new-edge proposal (review_proposals reject-without-reason error path).
INSERT INTO kg_proposals (id, proposal_type, source_page, proposed_data, confidence, status)
VALUES (
  'cccccccc-0002-0000-0000-000000000002',
  'new-edge',
  'KgCurationSeedPage',
  '{"source":"KgCurationEdgeSrc","target":"KgCurationSeedNode","relationship":"related_to"}'::jsonb,
  0.75,
  'pending'
)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Extra KG curation seeds for coverage gaps (KgCurationIT additional cases).
-- ---------------------------------------------------------------------------

-- Page on the exclusion list, plus a pending proposal whose source_page is
-- exactly that excluded name. Approving the proposal must surface
-- warnings_by_proposal containing the "in kg_excluded_pages list" message.
-- (Approval succeeds — the exclusion list governs extraction, not retroactive
-- curation; see the §6 edge-case table in 2026-05-13-kg-curation-mcp-design.md.)
INSERT INTO kg_excluded_pages (page_name, reason)
VALUES ('KgCurationExcludedPage', 'page_override')
ON CONFLICT (page_name) DO NOTHING;

INSERT INTO kg_proposals (id, proposal_type, source_page, proposed_data, confidence, status)
VALUES (
  'dddddddd-0001-0000-0000-000000000001',
  'new-node',
  'KgCurationExcludedPage',
  '{"name":"ExcludedSeedNode","type":"concept"}'::jsonb,
  0.80,
  'pending'
)
ON CONFLICT DO NOTHING;

-- Pending new-node proposal whose proposed name COLLIDES with an existing seed
-- node — list_proposals must surface node_exists=true (plus existing_node_id).
INSERT INTO kg_proposals (id, proposal_type, source_page, proposed_data, confidence, status)
VALUES (
  'dddddddd-0002-0000-0000-000000000002',
  'new-node',
  'KgCurationSeedPage',
  '{"name":"KgCurationSeedNode","type":"concept"}'::jsonb,
  0.65,
  'pending'
)
ON CONFLICT DO NOTHING;

-- Previously-rejected triple — guarantees list_proposals exposes
-- edge_previously_rejected=true for any pending proposal whose data matches.
INSERT INTO kg_rejections (proposed_source, proposed_target, proposed_relationship,
                            rejected_by, reason)
VALUES ('KgPreRejectedSource', 'KgPreRejectedTarget', 'related_to',
        'kg-curation-it', 'seeded rejection for IT')
ON CONFLICT (proposed_source, proposed_target, proposed_relationship) DO NOTHING;

INSERT INTO kg_proposals (id, proposal_type, source_page, proposed_data, confidence, status)
VALUES (
  'dddddddd-0003-0000-0000-000000000003',
  'new-edge',
  'KgCurationSeedPage',
  '{"source":"KgPreRejectedSource","target":"KgPreRejectedTarget","relationship":"related_to"}'::jsonb,
  0.55,
  'pending'
)
ON CONFLICT DO NOTHING;

-- Seed nodes used as endpoints for curate_edges upsert + delete_and_reject
-- coverage. Distinct from the existing aaaaaaaa-... pair so the upsert tests
-- can create a fresh edge without colliding with the seeded edge.
INSERT INTO kg_nodes (id, name, node_type, source_page, provenance)
VALUES
  ('dddddddd-1001-0000-0000-000000000001', 'KgUpsertSrcNode',  'concept', 'KgCurationSeedPage', 'human-authored'),
  ('dddddddd-1002-0000-0000-000000000002', 'KgUpsertTgtNode',  'concept', 'KgCurationSeedPage', 'human-authored')
ON CONFLICT (name) DO NOTHING;

-- Page-typed seed (node_type != 'concept') used by the mixed-edge guard IT.
-- Pair this with KgUpsertSrcNode (concept) to trigger the 2026-05-11 guard
-- in KgEdgeRepository.isMixedEdgeEndpoints, which the curation MCP path
-- surfaces as a structured EdgeResult.fail(...) citing the page/entity
-- boundary policy.
INSERT INTO kg_nodes (id, name, node_type, source_page, provenance)
VALUES
  ('dddddddd-1003-0000-0000-000000000003', 'KgUpsertPageNode', 'article', 'KgUpsertPageNode',   'human-authored')
ON CONFLICT (name) DO NOTHING;

-- Seed nodes used for curate_nodes merge happy-path coverage. Two distinct
-- nodes so source != target and the merge can succeed.
INSERT INTO kg_nodes (id, name, node_type, source_page, provenance)
VALUES
  ('dddddddd-2001-0000-0000-000000000001', 'KgMergeSourceNode', 'concept', 'KgCurationSeedPage', 'human-authored'),
  ('dddddddd-2002-0000-0000-000000000002', 'KgMergeTargetNode', 'concept', 'KgCurationSeedPage', 'human-authored')
ON CONFLICT (name) DO NOTHING;

-- Seed node used as the deletion target for curate_nodes.delete happy-path coverage.
INSERT INTO kg_nodes (id, name, node_type, source_page, provenance)
VALUES
  ('dddddddd-3001-0000-0000-000000000001', 'KgCurateNodeDeletable', 'concept', 'KgCurationSeedPage', 'human-authored')
ON CONFLICT (name) DO NOTHING;

-- Already-approved proposal for the §6 "re-review" guard IT coverage.
-- status=approved so review_proposals with verdict=approve must surface
-- a per-id error containing "already reviewed".
INSERT INTO kg_proposals (id, proposal_type, source_page, proposed_data, confidence, status, tier,
                          reviewed_by, reviewed_at)
VALUES (
  'eeeeeeee-0001-0000-0000-000000000001',
  'new-node',
  'KgCurationSeedPage',
  '{"name":"AlreadyApprovedNode","type":"concept"}'::jsonb,
  0.90,
  'approved',
  'human',
  'kg-curation-it',
  NOW()
)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Visibility test fixtures for KgCurationVisibilityIT (Task 8 / Fix 1 wire IT)
-- Proves admin-bypass: admin /wikantik-admin-mcp query_nodes + search_knowledge
-- must surface entities whose source_page is on kg_excluded_pages.
-- ---------------------------------------------------------------------------

INSERT INTO kg_excluded_pages (page_name, reason)
VALUES ('KgVisibilityExcludedPage', 'page_override')
ON CONFLICT (page_name) DO NOTHING;

INSERT INTO kg_nodes (id, name, node_type, source_page, provenance)
VALUES (
    'ffffffff-0001-0000-0000-000000000001',
    'KgVisibilityExcludedNode',
    'concept',
    'KgVisibilityExcludedPage',
    'human-authored'
) ON CONFLICT (name) DO NOTHING;

INSERT INTO kg_nodes (id, name, node_type, source_page, provenance)
VALUES (
    'ffffffff-0002-0000-0000-000000000002',
    'KgVisibilityAllowedNode',
    'concept',
    'KgVisibilityAllowedPage',
    'human-authored'
) ON CONFLICT (name) DO NOTHING;
