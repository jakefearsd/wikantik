-- V039: general-purpose "must change password at next login" flag.
--
-- Adds users.password_must_change and flags the fresh-seeded default admin.
-- The UPDATE keys on the canonical admin123 hash that V002 seeds, so it is a
-- one-time bootstrap backstop: on a fresh database (V002 just seeded admin)
-- the default admin gets flagged; on any database where the admin password
-- was ever changed it is a no-op. The runtime never compares hashes — the
-- flag is set/cleared by the application (admin resets, email resets,
-- self-service password change).
--
-- Idempotent: ADD COLUMN IF NOT EXISTS; the UPDATE's password predicate makes
-- re-runs no-ops. No new grants needed (V002 already grants UPDATE on users).

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_must_change BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users
   SET password_must_change = TRUE
 WHERE login_name = 'admin'
   AND password = '{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg=='
   AND password_must_change = FALSE;
