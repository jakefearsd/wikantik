-- seed-users.sql — Idempotent dev seed: ensure the default admin account exists.
-- Run automatically by deploy-local.sh on every deploy, and by the container
-- entrypoint when WIKANTIK_SEED_DEV_USERS=true.
--
-- Admin account: admin / admin123, flagged password_must_change so the first
-- login forces a real password. INSERT-IF-ABSENT ONLY — this seed never
-- overwrites an existing admin row, so a changed password (and its cleared
-- must-change flag) survives every redeploy.
--
-- To reset a forgotten local admin password back to admin123 by hand:
--   UPDATE users SET password = '{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg==',
--                    password_must_change = TRUE
--    WHERE login_name = 'admin';
--
-- Personal/local accounts (testbot, personal logins) belong in the gitignored
-- bin/db/seed-users.local.sql, which deploy-local.sh runs when present.
-- The `agents` service account is seeded by migration V035, not here.
--
-- The users table has unique constraints on BOTH login_name (PK) and
-- wiki_name, so a plain ON CONFLICT (login_name) can still violate the
-- wiki_name constraint when a different row owns 'Administrator'. The DO
-- block guards both.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM users WHERE login_name = 'admin')
     AND NOT EXISTS (SELECT 1 FROM users WHERE wiki_name = 'Administrator') THEN
    INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, password_must_change)
    VALUES (
      '-6852820166199419346',
      'admin@localhost',
      'Administrator',
      'admin',
      '{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg==',
      'Administrator',
      TRUE
    );
  END IF;
END $$;

INSERT INTO roles (login_name, role)
SELECT 'admin', 'Admin'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE login_name = 'admin' AND role = 'Admin');
