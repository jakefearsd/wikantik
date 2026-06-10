# First-Login Password Change + Fresh-Install Cleanup — Design

**Date:** 2026-06-10
**Status:** Approved

## Problem

A brand-new admin on a fresh build hits several tripwires:

1. **Three competing admin seeds, two different passwords.**
   `V002__core_users_groups.sql` seeds `admin/admin123` (only if `users` is
   empty); `docker/db/001-init.sql` runs first on the container path, creates
   the tables itself, and seeds `admin` with a *different* hash (password
   `admin`) — so V002's seed no-ops and a fresh container's real credential
   contradicts `docs/DockerDeployment.md` and the README (`admin123`).
   First login on the container path fails by construction.
2. **Published default credential in production.** Even with dev seeding off,
   a fresh DB gets admin with a known password, no forced change, no warning.
3. **`seed-users.sql` ships the maintainer's personal account**
   (`jakefear@gmail.com/passw0rd`) into every install, while docs claim it
   seeds `testbot`. It also resets admin's password on every deploy.
4. **`001-init.sql` duplicates the schema** (with `DROP TABLE`s) instead of
   letting `migrate.sh` own it — a drift bomb between install paths.
5. **`install-fresh.sh` defaults `DB_APP_PASSWORD` to `ChangeMe123!`** and
   only warns when the migrate role is skipped — a failure that detonates at
   the first ALTER-based migration weeks later.
6. **Docs disagree**: CLAUDE.md says `admin/admin`; README says `admin123`;
   CLAUDE.md/README still describe hand-editing ROOT.xml, which
   `deploy-local.sh` now renders from `.env`.

## Decision

Keep `admin/admin123` as the seeded default, but mark it **must change
password at first login** — via a real, general-purpose per-user flag (NOT
derived from the password hash), because the same mechanism serves
administrative resets and forced rotations. The flag is set only when the
database is freshly seeded, never re-applied by routine deploys.

All secondary fixes ride along: container-init consolidation, seed cleanup
with a gitignored local hook, `install-fresh.sh` hardening, a first-start
banner, and a docs truth pass.

## 1. Data model

New migration `V039__password_must_change.sql`:

- `ALTER TABLE users ADD COLUMN IF NOT EXISTS password_must_change BOOLEAN NOT NULL DEFAULT FALSE;`
- One-time bootstrap backstop:
  `UPDATE users SET password_must_change = TRUE WHERE login_name = 'admin' AND password = '{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg==';`
  (the canonical `admin123` hash V002 and `seed-users.sql` both seed)
  On a fresh DB this flags the admin V002 just seeded; on any DB where the
  admin password has been changed it is a no-op. The hash literal appears
  here once as bootstrap glue — the runtime never compares hashes.
- Idempotent; `:app_user` grants unchanged (users already has
  SELECT/INSERT/UPDATE/DELETE).

Java surface:

- `UserProfile` (wikantik-api): `isPasswordMustChange()` /
  `setPasswordMustChange(boolean)`.
- `DefaultUserProfile` (wikantik-main): field + accessors.
- `JDBCUserDatabase`: read/write the column via a new
  `wikantik.userdatabase.passwordMustChange` property (default
  `password_must_change`), added to the ini defaults, the bare-metal
  properties template, and the container entrypoint's generated properties.
- InMemory test doubles updated to carry the field.

## 2. Flag lifecycle

Set TRUE by:

- Fresh-install seeding (V039 backstop; `seed-users.sql` inserts admin with
  the flag set when it creates the row).
- An admin setting/resetting another user's password via `/admin`
  (`AdminUserResource`).
- The `POST /api/auth/reset-password` email flow (it mails a generated
  temporary password — exactly a credential the user must replace).
- Forced rotation = operator bulk `UPDATE users SET password_must_change =
  TRUE …`; no code needed.

Cleared by:

- The user successfully changing their own password through the existing
  `AuthResource` profile path (current-password verification + NIST 800-63B
  validation already apply, so `admin123` cannot be re-chosen).

## 3. Enforcement (server-side)

- On session establishment — password login in `AuthResource`, and the
  remember-me re-auth path in `RememberMeAuthFilter` — the loaded profile's
  flag is stashed on the session.
- A gate on `/api/*` + `/admin/*` rejects requests from a flagged session
  with **HTTP 403 + structured error code `PASSWORD_CHANGE_REQUIRED`**,
  allowlisting only: auth status (`GET /api/auth`), login/logout, and the
  password-change call. A successful password change clears the session flag
  in the same request.
- `POST /api/auth/login` and `GET /api/auth` responses gain
  `mustChangePassword: true|false`.
- Out of scope by construction: SSO sessions (no password auth), MCP / SCIM /
  `/tools/*` (API-key/bearer auth), anonymous reads, `/api/health`,
  `/metrics`.
- Accepted trade-off: an admin reset while the target holds a live session
  takes effect at that user's next login, not mid-session (avoids a
  per-request DB read).

## 4. SPA flow

- Login response (or any 403 `PASSWORD_CHANGE_REQUIRED`) routes to a forced
  "Set a new password" screen — the existing profile password-change form,
  stripped down, with copy explaining why.
- On success, re-fetch `/api/auth` and continue to the originally requested
  route.
- If implemented as a new route (rather than a state of `/login`), it gets
  the usual dual registration: web.xml AND `SpaRoutingFilter.SPA_EXACT`.

## 5. One canonical seed (container init consolidation)

- `docker/db/001-init.sql` shrinks to
  `CREATE EXTENSION IF NOT EXISTS vector;` only. Schema, admin seed, and
  flag all come from `migrate.sh`, which the container entrypoint already
  runs before Tomcat starts. This kills the `admin`-vs-`admin123` split and
  the duplicated schema/DROP TABLE hazard.
- `seed-users.sql` becomes **admin-only, insert-if-absent** — no password
  clobber on redeploy, so the flag's fresh-DB-only semantics hold. The
  recover-a-forgotten-local-admin-password trick is documented as a manual
  one-liner instead.
- Personal/testbot accounts move to a gitignored
  `bin/db/seed-users.local.sql`; `deploy-local.sh` runs it after
  `seed-users.sql` when present. (Jake's local copy created as part of
  implementation, untracked.)
- The container entrypoint's `WIKANTIK_SEED_DEV_USERS` path keeps running
  `seed-users.sql` (now admin-only) — docs updated to match.

## 6. install-fresh.sh hardening + first-start banner

- `install-fresh.sh`: remove the `ChangeMe123!` default — refuse with a
  clear message when `DB_APP_PASSWORD` is unset. The migrate-role skip
  becomes an explicit failure with instructions unless `--no-migrate-role`
  is passed (today's warning detonates at the first ALTER migration).
- `deploy-local.sh` and the container entrypoint end with a banner: the URL,
  `admin / admin123`, and "you will be required to set a new password at
  first login." `deploy-local.sh` checks the flag via psql and prints
  "admin password already set" on subsequent runs instead.

## 7. Docs truth pass

Align CLAUDE.md (`admin / admin` → `admin / admin123`; retire the stale
"edit ROOT.xml by hand" step), README, `docs/DockerDeployment.md`,
`docs/WikantikOperations.md`, `docs/PostgreSQLLocalDeployment.md` with the
single canonical credential story and the forced-change behavior.

## 8. Testing (TDD)

- **Unit:** flag round-trip in `JDBCUserDatabase`; gate filter 403s with
  `PASSWORD_CHANGE_REQUIRED` and honors the allowlist; flag set on
  admin-reset and email-reset, cleared on self-change; login/status JSON
  carries the flag; remember-me re-auth picks up the flag.
- **Migration:** V039 idempotent on re-apply; backstop fires only on the
  canonical hash.
- **IT (Cargo, fresh DB):** login `admin/admin123` → API write rejected with
  `PASSWORD_CHANGE_REQUIRED` → change password → write succeeds → flag stays
  clear across redeploy/re-seed.
- **Frontend:** vitest coverage for forced-change routing.
- Gate the commit on the full `mvn clean install -Pintegration-tests -fae`
  reactor, per house rules.

## Out of scope

- Mid-session invalidation when an admin flags a user with a live session.
- Password expiry policies / scheduled rotation tooling (the column makes
  them possible later; nothing else ships now).
- A first-run setup wizard (rejected in favor of default + forced change).
