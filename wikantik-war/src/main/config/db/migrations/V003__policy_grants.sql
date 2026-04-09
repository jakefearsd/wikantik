-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: database-backed authorization policy grants
--
-- Creates the policy_grants table used by DefaultAuthorizationManager and
-- seeds it with the baseline role permissions. Fully idempotent — uses
-- CREATE TABLE IF NOT EXISTS and ON CONFLICT DO NOTHING on the seed rows.

CREATE TABLE IF NOT EXISTS policy_grants (
    id              SERIAL       PRIMARY KEY,
    principal_type  VARCHAR(10)  NOT NULL,
    principal_name  VARCHAR(255) NOT NULL,
    permission_type VARCHAR(10)  NOT NULL,
    target          VARCHAR(255) NOT NULL,
    actions         VARCHAR(255) NOT NULL,
    UNIQUE (principal_type, principal_name, permission_type, target)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON policy_grants         TO :app_user;
GRANT USAGE, SELECT                  ON SEQUENCE policy_grants_id_seq TO :app_user;

-- Seed default policy grants. ON CONFLICT preserves any overrides the
-- operator has made via the admin UI.
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) VALUES
    ('role', 'All',           'page',  '*',              'view'),
    ('role', 'All',           'wiki',  '*',              'editPreferences,editProfile,login'),
    ('role', 'Asserted',      'group', '*',              'view'),
    ('role', 'Authenticated', 'page',  '*',              'modify,rename'),
    ('role', 'Authenticated', 'wiki',  '*',              'createPages,createGroups'),
    ('role', 'Authenticated', 'group', '*',              'view'),
    ('role', 'Authenticated', 'group', '<groupmember>',  'edit'),
    ('role', 'Admin',         'page',  '*',              '*'),
    ('role', 'Admin',         'wiki',  '*',              '*')
ON CONFLICT (principal_type, principal_name, permission_type, target) DO NOTHING;
