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
