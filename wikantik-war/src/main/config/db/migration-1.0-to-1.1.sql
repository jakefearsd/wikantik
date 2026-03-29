/*
 * Wikantik 1.0 → 1.1 Database Migration
 *
 * This script:
 *   1. Creates the policy_grants table (idempotent)
 *   2. Seeds production-appropriate policy grants
 *      - Anonymous users: VIEW ONLY (no editing)
 *      - Authenticated users: full edit/rename/create
 *      - Admin: AllPermission
 *   3. Ensures Admin group exists with admin user
 *
 * Usage:
 *   sudo -u postgres psql -d jspwiki -f migration-1.0-to-1.1.sql
 *
 * NOTE: The database name is assumed to be 'jspwiki' (unchanged from 1.0).
 * Adjust the -d parameter if your database has a different name.
 * The 'jspwiki' in GRANT statements refers to the application database USER,
 * not the database name — adjust if your application user is different.
 */

-- 1. Create policy_grants table
CREATE TABLE IF NOT EXISTS policy_grants (
    id              SERIAL PRIMARY KEY,
    principal_type  VARCHAR(10) NOT NULL,
    principal_name  VARCHAR(255) NOT NULL,
    permission_type VARCHAR(10) NOT NULL,
    target          VARCHAR(255) NOT NULL,
    actions         VARCHAR(255) NOT NULL,
    UNIQUE(principal_type, principal_name, permission_type, target)
);

-- Grant permissions to application user (adjust username if needed)
DO $$
BEGIN
    -- Try common application usernames
    BEGIN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON policy_grants TO jspwiki';
        EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE policy_grants_id_seq TO jspwiki';
    EXCEPTION WHEN undefined_object THEN
        BEGIN
            EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON policy_grants TO wikantik';
            EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE policy_grants_id_seq TO wikantik';
        EXCEPTION WHEN undefined_object THEN
            RAISE NOTICE 'Neither jspwiki nor wikantik user found. Grant permissions manually.';
        END;
    END;
END $$;

-- 2. Seed production policy grants
-- Clear any existing grants to ensure clean state
DELETE FROM policy_grants;

-- All users: can view pages and manage their own profile/login
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'page', '*', 'view');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'wiki', '*', 'editPreferences,editProfile,login');

-- Anonymous: VIEW ONLY — no editing, no page creation
-- (No page/wiki grants beyond what 'All' provides)

-- Asserted (cookie-identified): VIEW ONLY + can view groups
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'group', '*', 'view');

-- Authenticated: full editing capabilities
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'page', '*', 'modify,rename');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'wiki', '*', 'createPages,createGroups');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '*', 'view');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '<groupmember>', 'edit');

-- Admin: full permissions (AllPermission)
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'page', '*', '*');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'wiki', '*', '*');

-- 3. Ensure Admin group exists with admin user
INSERT INTO groups (name, created, modified)
SELECT 'Admin', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM groups WHERE name = 'Admin');

INSERT INTO group_members (name, member)
SELECT 'Admin', 'admin'
WHERE NOT EXISTS (SELECT 1 FROM group_members WHERE name = 'Admin' AND member = 'admin');
