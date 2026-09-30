-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: grant the new 'export' wiki permission (Obsidian vault export) to
-- Authenticated by default.
--
-- Conservative and idempotent, modelled on V043: the Authenticated wiki row is
-- only touched while it still holds V003's stock actions, so an operator who has
-- customised the row is never silently granted export. Re-running finds the row
-- no longer equal to the default and does nothing. Revoke via /admin/security.
UPDATE policy_grants
   SET actions = 'createPages,createGroups,export'
 WHERE principal_type = 'role' AND principal_name = 'Authenticated'
   AND permission_type = 'wiki' AND target = '*'
   AND actions = 'createPages,createGroups';
