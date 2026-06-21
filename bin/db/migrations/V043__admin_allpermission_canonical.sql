-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: converge the default Admin god-mode grants onto the canonical
-- 'all' (AllPermission) row.
--
-- V003 seeded the Admin role with two wildcard-action rows -- ('role','Admin','page','*','*')
-- and ('role','Admin','wiki','*','*') -- which both resolve to AllPermission at runtime (any
-- '*'-action grant is omnipotent). With the first-class 'all' permission type, a single
-- ('role','Admin','all','*','*') row expresses the same grant unambiguously and is what the
-- admin UI now shows.
--
-- This migration is conservative and idempotent:
--   1. It inserts the canonical 'all' row ONLY when the install still has the default Admin
--      god-mode (at least one of the seeded page/wiki '*' rows). An operator who deliberately
--      removed Admin's AllPermission is therefore never silently re-granted it.
--   2. It then removes the now-redundant default page/wiki god-mode rows.
-- Re-running is a no-op: after step 2 there is nothing to match in step 1's EXISTS guard.
-- The 'all' row is inserted BEFORE the delete so Admin never has a window with no god-mode grant.

INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
SELECT 'role', 'Admin', 'all', '*', '*'
WHERE EXISTS (
    SELECT 1 FROM policy_grants
     WHERE principal_type = 'role' AND principal_name = 'Admin'
       AND permission_type IN ('page', 'wiki') AND target = '*' AND actions = '*'
)
ON CONFLICT (principal_type, principal_name, permission_type, target) DO NOTHING;

DELETE FROM policy_grants
 WHERE principal_type = 'role' AND principal_name = 'Admin'
   AND permission_type IN ('page', 'wiki') AND target = '*' AND actions = '*';
