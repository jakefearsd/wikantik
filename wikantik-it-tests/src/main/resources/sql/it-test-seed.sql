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
