-- seed-users.sql — Idempotent dev seed: ensure known login accounts exist.
-- Run automatically by deploy-local.sh on every deploy.
--
-- Implementation note: the users table has unique constraints on BOTH
-- login_name (PK) and wiki_name. Postgres only allows one ON CONFLICT
-- target per statement, so a naive INSERT … ON CONFLICT (login_name) DO
-- UPDATE still fails with a wiki_name uniqueness violation when a
-- *different* row already owns the proposed wiki_name. We guard the seed
-- with a pre-check: if a row with the proposed wiki_name already exists
-- under a different login_name, the seed is a no-op for that account
-- (the operator already has an equivalent user — don't clobber it).
-- Otherwise upsert by login_name as usual.

-- Admin account: admin / admin123
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM users WHERE wiki_name = 'Administrator' AND login_name <> 'admin') THEN
    INSERT INTO users (uid, email, full_name, login_name, password, wiki_name)
    VALUES (
      '-6852820166199419346',
      'admin@localhost',
      'Administrator',
      'admin',
      '{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg==',
      'Administrator'
    )
    ON CONFLICT (login_name) DO UPDATE
      SET password  = EXCLUDED.password,
          email     = EXCLUDED.email,
          full_name = EXCLUDED.full_name,
          wiki_name = EXCLUDED.wiki_name;
  END IF;
END $$;

DELETE FROM roles WHERE login_name = 'admin' AND role = 'Admin';
INSERT INTO roles (login_name, role) VALUES ('admin', 'Admin');

-- Basic user account: jakefear@gmail.com / passw0rd
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM users WHERE wiki_name = 'JakeFear' AND login_name <> 'jakefear@gmail.com') THEN
    INSERT INTO users (uid, email, full_name, login_name, password, wiki_name)
    VALUES (
      '-7234567890123456789',
      'jakefear@gmail.com',
      'Jake Fear',
      'jakefear@gmail.com',
      '{SHA-256}Y4V5SEIjsLbLdfhb/fgG+SHjSPSnKQszCndaucWNX1EHzXBOYy8HNw==',
      'JakeFear'
    )
    ON CONFLICT (login_name) DO UPDATE
      SET password  = EXCLUDED.password,
          email     = EXCLUDED.email,
          full_name = EXCLUDED.full_name,
          wiki_name = EXCLUDED.wiki_name;
  END IF;
END $$;

-- `agents` service account: the default owner for AI-agent-authored pages
-- (wikantik.page_ownership.default_owner). Required system account, no roles,
-- un-loginable password (SHA-256 of a discarded random secret). Mirrors the
-- V035 migration so local dev also has the account.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM users WHERE wiki_name = 'Agents' AND login_name <> 'agents') THEN
    INSERT INTO users (uid, email, full_name, login_name, password, wiki_name)
    VALUES (
      'agents-service-account',
      'agents@localhost',
      'AI Agents (service account)',
      'agents',
      '{SHA-256}Ub6qgHVQhnEDNdD2l202SLf1pVllUU6X/WO7cVsmn4LCrK6lT97JFw==',
      'Agents'
    )
    ON CONFLICT (login_name) DO NOTHING;
  END IF;
END $$;
