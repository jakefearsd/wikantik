-- backfill-agent-default-owner.sql — one-time normalization (NOT a migration).
--
-- Assigns the `agents` service account (the default owner for AI-agent-authored
-- pages) to every canonical page that currently has no human owner: pages with
-- no page_owners row, and existing rows whose owner_login IS NULL (orphaned at
-- bootstrap because the frontmatter `author` was an agent name, not a login).
--
-- Idempotent and reversible: rows it writes are stamped
-- assigned_by = 'system:agent-default-backfill'. Re-running is a no-op once
-- every unowned page already points at `agents`. To undo, orphan them again:
--   UPDATE page_owners SET owner_login = NULL
--   WHERE owner_login = 'agents' AND assigned_by = 'system:agent-default-backfill';
--
-- Per the no-data-in-migrations rule, this page-level fixup lives here, not in a
-- Vxxx migration. The `agents` ACCOUNT itself is seeded by V035 (reference data).
--
-- Run (local):
--   PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki \
--       -f bin/db/one-shots/backfill-agent-default-owner.sql
--
-- Guard: do nothing unless the `agents` account exists, so we never write a
-- dangling owner_login.
\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM users WHERE login_name = 'agents') THEN
    RAISE EXCEPTION 'agents service account is missing — run migration V035 first';
  END IF;

  -- Canonical pages with no page_owners row at all → insert owned by agents.
  INSERT INTO page_owners (canonical_id, owner_login, assigned_by)
  SELECT pci.canonical_id, 'agents', 'system:agent-default-backfill'
  FROM page_canonical_ids pci
  LEFT JOIN page_owners po ON po.canonical_id = pci.canonical_id
  WHERE po.canonical_id IS NULL
  ON CONFLICT (canonical_id) DO NOTHING;

  -- Existing rows orphaned at bootstrap (NULL owner) → agents.
  UPDATE page_owners
  SET owner_login = 'agents', assigned_by = 'system:agent-default-backfill'
  WHERE owner_login IS NULL;
END $$;

SELECT owner_login, COUNT(*) AS pages
FROM page_owners
GROUP BY owner_login
ORDER BY pages DESC;
