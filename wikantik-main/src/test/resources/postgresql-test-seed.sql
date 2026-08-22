-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- PostgreSQL test SEED DATA for PostgresTestDb (com.wikantik.jdbc.testing).
-- Schema comes from the real bin/db/migrations, applied by MigrationApplier
-- before this file runs — this file contains no DDL, data only.

-- Users (password hashes are application-level, DB-independent).
-- NOTE (drift finding): the old hand-written fixture also seeded a second 'admin' user row
-- here. V002__core_users_groups.sql now seeds a default admin (same login_name/uid, since
-- login_name is the PRIMARY KEY) whenever `users` is empty, which the migrations always apply
-- before this file runs — a second unconditional INSERT for 'admin' would violate that primary
-- key. No PG-backed test depended on the old fixture's specific admin password hash or its
-- 'admin@locahost' (typo) email, so that row is dropped here in favour of V002's seed.
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, attributes)
VALUES ('-7739839977499061014', 'janne@ecyrd.com', 'Janne Jalkanen', 'janne',
        '{SSHA}1WFv9OV11pD5IySgVH3sFa2VlCyYjbLrcVT/qw==', 'JanneJalkanen',
        'attribute1=some random value' || chr(10) || 'attribute2=another value');

-- Roles
INSERT INTO roles (login_name, role) VALUES ('janne', 'Authenticated');
INSERT INTO roles (login_name, role) VALUES ('admin', 'Authenticated');
INSERT INTO roles (login_name, role) VALUES ('admin', 'Admin');

-- Groups
INSERT INTO groups (name, created, modified)
VALUES ('TV', '2006-06-20 14:50:54', '2006-06-20 14:50:54');
INSERT INTO group_members (name, member) VALUES ('TV', 'Archie Bunker');
INSERT INTO group_members (name, member) VALUES ('TV', 'BullwinkleMoose');
INSERT INTO group_members (name, member) VALUES ('TV', 'Fred Friendly');

INSERT INTO groups (name, created, modified)
VALUES ('Literature', '2006-06-20 14:50:54', '2006-06-20 14:50:54');
INSERT INTO group_members (name, member) VALUES ('Literature', 'Charles Dickens');
INSERT INTO group_members (name, member) VALUES ('Literature', 'Homer');

INSERT INTO groups (name, created, modified)
VALUES ('Art', '2006-06-20 14:50:54', '2006-06-20 14:50:54');

-- 'Admin' the group (as opposed to 'Admin' the role) is already seeded by
-- V002__core_users_groups.sql — same PRIMARY KEY (name), so it is not re-inserted here, only
-- given this fixture's extra member.
INSERT INTO group_members (name, member) VALUES ('Admin', 'Administrator');

-- Default policy grants. V003__policy_grants.sql seeds the production baseline
-- (All/Asserted-group-view/Authenticated/Admin); these additional rows are
-- test-only permissiveness (anonymous+asserted modify/createPages) that
-- production deliberately does not ship by default.
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'page', '*', 'view') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'wiki', '*', 'editPreferences,editProfile,login') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'page', '*', 'modify') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'wiki', '*', 'createPages') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'page', '*', 'modify') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'wiki', '*', 'createPages') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'group', '*', 'view') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'page', '*', 'modify,rename') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'wiki', '*', 'createPages,createGroups') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '*', 'view') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '<groupmember>', 'edit') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'page', '*', '*') ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'wiki', '*', '*') ON CONFLICT DO NOTHING;
