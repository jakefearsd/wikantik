-- V042: track each account's last successful authentication.
--
-- Adds users.last_login, stamped by the application on every LOGIN_AUTHENTICATED
-- event (interactive form login, SSO, and remember-me cookie re-auth) via a
-- targeted UPDATE that leaves the `modified` timestamp untouched. Surfaced in the
-- admin user list alongside `created`. NULL means the account has not
-- authenticated since this column was added (rendered as "—" in the UI).
--
-- Idempotent: ADD COLUMN IF NOT EXISTS. DDL-only — no backfill (a historical
-- last-login is unknowable). No new grants needed (V002 already grants UPDATE on users).

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login TIMESTAMP;
