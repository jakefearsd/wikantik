/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- PostgreSQL DDL for database-backed authorization policy.
-- Adds the policy_grants table and seeds it with defaults matching wikantik.policy.
-- Run AFTER postgresql.ddl (groups and group_members tables already exist).
--
-- Usage:
--   sudo -u postgres psql -d wikantik -f postgresql-permissions.ddl

-- Policy grants table: replaces the wikantik.policy file
CREATE TABLE IF NOT EXISTS policy_grants (
    id              SERIAL PRIMARY KEY,
    principal_type  VARCHAR(10) NOT NULL,
    principal_name  VARCHAR(255) NOT NULL,
    permission_type VARCHAR(10) NOT NULL,
    target          VARCHAR(255) NOT NULL,
    actions         VARCHAR(255) NOT NULL,
    UNIQUE(principal_type, principal_name, permission_type, target)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON policy_grants TO wikantik;
GRANT USAGE, SELECT ON SEQUENCE policy_grants_id_seq TO wikantik;

-- Seed default policy grants (matches wikantik.policy defaults)

-- All users: can view pages and manage their own profile
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'page', '*', 'view')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'wiki', '*', 'editPreferences,editProfile,login')
ON CONFLICT DO NOTHING;

-- Anonymous: can modify pages (implies edit, comment, view) and create pages
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'page', '*', 'modify')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'wiki', '*', 'createPages')
ON CONFLICT DO NOTHING;

-- Asserted (cookie-identified): same page permissions as Anonymous, plus can view groups
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'page', '*', 'modify')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'wiki', '*', 'createPages')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'group', '*', 'view')
ON CONFLICT DO NOTHING;

-- Authenticated: can modify/rename pages, create pages/groups, view groups, edit own groups
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'page', '*', 'modify,rename')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'wiki', '*', 'createPages,createGroups')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '*', 'view')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '<groupmember>', 'edit')
ON CONFLICT DO NOTHING;

-- Admin: full permissions (AllPermission)
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'page', '*', '*')
ON CONFLICT DO NOTHING;
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'wiki', '*', '*')
ON CONFLICT DO NOTHING;

-- Seed Admin group with admin user (idempotent — skips if already present)
INSERT INTO groups (name, created, modified)
SELECT 'Admin', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM groups WHERE name = 'Admin');

INSERT INTO group_members (name, member)
SELECT 'Admin', 'admin'
WHERE NOT EXISTS (SELECT 1 FROM group_members WHERE name = 'Admin' AND member = 'admin');
