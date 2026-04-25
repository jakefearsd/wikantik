-- D17: Deduplicate the `users` table on wiki_name and add a UNIQUE constraint
-- so future inserts cannot recreate the duplicate.
--
-- Background: JDBCUserDatabase.findByWikiName() throws "More than one profile
-- in database!" when two rows share the same wiki_name. This migration:
--   1. Identifies duplicate (wiki_name) groups, keeping the row with the
--      lexicographically smallest login_name (deterministic).
--   2. Deletes the losing rows from `users`. Cascades to `roles` are NOT
--      automatic; we delete role rows that belonged to the removed login_names
--      so we don't leave orphans.
--   3. Adds a UNIQUE constraint on wiki_name (NULLs allowed; PostgreSQL treats
--      multiple NULL wiki_name rows as distinct, which is correct).
--
-- The migration is idempotent: re-running on a clean database is a no-op
-- because there are no duplicates and the constraint is already present.

-- 1. Build a working set of (wiki_name, kept login_name) pairs and the rows to delete.
WITH ranked AS (
    SELECT login_name, wiki_name,
           ROW_NUMBER() OVER (
               PARTITION BY wiki_name ORDER BY login_name
           ) AS rn
    FROM users
    WHERE wiki_name IS NOT NULL AND wiki_name <> ''
),
losers AS (
    SELECT login_name FROM ranked WHERE rn > 1
)
DELETE FROM roles WHERE login_name IN (SELECT login_name FROM losers);

WITH ranked AS (
    SELECT login_name, wiki_name,
           ROW_NUMBER() OVER (
               PARTITION BY wiki_name ORDER BY login_name
           ) AS rn
    FROM users
    WHERE wiki_name IS NOT NULL AND wiki_name <> ''
)
DELETE FROM users WHERE login_name IN (
    SELECT login_name FROM ranked WHERE rn > 1
);

-- 2. Add a UNIQUE constraint on wiki_name (no-op if already present).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'users_wiki_name_uniq' AND conrelid = 'users'::regclass
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_wiki_name_uniq UNIQUE (wiki_name);
    END IF;
END $$;
