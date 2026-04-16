-- seed-users.sql — Idempotent dev seed: ensure known login accounts exist.
-- Run automatically by deploy-local.sh on every deploy.
-- Uses ON CONFLICT to upsert so re-running is safe.

-- Admin account: admin / admin123
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

DELETE FROM roles WHERE login_name = 'admin' AND role = 'Admin';
INSERT INTO roles (login_name, role) VALUES ('admin', 'Admin');

-- Basic user account: jakefear@gmail.com / passw0rd
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
