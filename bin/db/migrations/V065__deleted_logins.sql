-- V065: tombstones for hard-deleted accounts (#107). A login with no account used to show "as written"
-- on old content and could be registered again by anyone, who then inherited the old account's edits and
-- comments. UserDeletionService records the login here before deleting the user row; the account snapshot
-- shows a tombstoned login as "former member", and every create path refuses it. Releasing a name is a
-- deliberate admin act: DELETE FROM deleted_logins WHERE login_name = '...'.
--
-- Idempotent: CREATE TABLE IF NOT EXISTS. DDL-only (no backfill: deletions before V065 stay as written).

CREATE TABLE IF NOT EXISTS deleted_logins (
    login_name TEXT        PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
GRANT SELECT, INSERT ON deleted_logins TO :app_user;
