# First-Login Password Change + Fresh-Install Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A general-purpose `password_must_change` user flag, enforced server-side, set on fresh-install admin seeding / admin resets / email resets — plus consolidation of the three competing admin seeds, install-script hardening, and a docs truth pass.

**Architecture:** New `users.password_must_change` column (migration V039) surfaces through `UserProfile`/`JDBCUserDatabase` (wikantik-main), is gated by a new `MustChangePasswordFilter` on `/api/*` + `/admin/*` (wikantik-rest, session-cached via a small `PasswordChangeGate` helper), and drives a forced "Set a new password" SPA route. The container init SQL shrinks to extension-only so `migrate.sh` owns schema + the single canonical `admin/admin123` seed.

**Tech Stack:** Java 21 / Jakarta Servlet, PostgreSQL (Testcontainers for unit tests), React + Vitest, Maven Cargo ITs, bash.

**Spec:** `docs/superpowers/specs/2026-06-10-first-login-password-change-design.md`

**Spec deviations (verified against code, accepted):**
- `UserProfile` lives in **wikantik-main** (`com.wikantik.auth.user`), not wikantik-api. No API-module change.
- `JDBCUserDatabase` uses **hardcoded column names** in SQL constants (the `wikantik.userdatabase.*` column properties in its Javadoc are dead JSPWiki documentation — nothing reads them). So NO ini/template/entrypoint property additions. We follow the `lock_expiry` precedent and hardcode `password_must_change`.
- The REST error envelope is `{error, status, message}`; the gate adds a `code` field (`PASSWORD_CHANGE_REQUIRED`) in its own response, mirroring how `AdminAuthFilter` writes a literal JSON body.

**Execution notes for the orchestrator:**
- Task order/dependencies: 1 → {3, 5}; 3 → 4 → 7; 3 → 6; 2, 8, 9 independent; 10 last.
- Suggested subagent model tiers: Task 1 sonnet, Task 2 haiku, Task 3 sonnet, Task 4 sonnet, Task 5 haiku, Task 6 sonnet, Task 7 sonnet, Task 8 haiku, Task 9 haiku, Task 10 sonnet.
- House rules apply: no empty catch blocks (`LOG.warn` minimum); stage files by name (never `git add -A`); after any constructor/interface signature change run `mvn test-compile`, not just `compile`.

---

### Task 1: `password_must_change` through the user store (wikantik-main)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/UserProfile.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/DefaultUserProfile.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/auth/user/InMemoryUserDatabase.java`
- Modify: `wikantik-main/src/test/resources/postgresql-test.sql`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java`

- [ ] **Step 1: Check for other UserProfile implementors**

Run: `grep -rln "implements UserProfile" --include="*.java" wikantik-* | grep -v target`
Expected: only `DefaultUserProfile.java` (Mockito mocks don't count). If any other implementor appears, add the same trivial field/getter/setter from Step 4 to it.

- [ ] **Step 2: Add the column to the unit-test schema**

In `wikantik-main/src/test/resources/postgresql-test.sql`, the `users` table (starts line ~26) has `lock_expiry    TIMESTAMP,` — add below it, before `attributes`:

```sql
    password_must_change BOOLEAN NOT NULL DEFAULT FALSE,
```

(Adjust comma placement to fit the surrounding DDL.)

- [ ] **Step 3: Write the failing round-trip test**

In `JDBCUserDatabaseTest.java`, mirror an existing save/find test's setup (the class uses the shared Testcontainers PG started by `PostgresTestContainer` + `postgresql-test.sql`). Add:

```java
@Test
void testPasswordMustChangeRoundTrip() throws Exception {
    final UserProfile profile = m_db.newProfile();
    profile.setLoginName( "mustchange-user" );
    profile.setFullname( "Must Change" );
    profile.setEmail( "mustchange@example.com" );
    profile.setPassword( "Xk3-Round-Trip-77!" );
    profile.setPasswordMustChange( true );
    m_db.save( profile );

    UserProfile loaded = m_db.findByLoginName( "mustchange-user" );
    assertTrue( loaded.isPasswordMustChange(), "flag must survive INSERT + load" );

    loaded.setPasswordMustChange( false );
    m_db.save( loaded );
    assertFalse( m_db.findByLoginName( "mustchange-user" ).isPasswordMustChange(),
            "flag must survive UPDATE + load" );
}
```

Adapt the database field name (`m_db` vs `db`) and any required profile fields (e.g. wiki name) to match the sibling tests in the same class exactly.

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest#testPasswordMustChangeRoundTrip`
Expected: COMPILE FAILURE — `isPasswordMustChange()` / `setPasswordMustChange(boolean)` not defined. (A compile failure of the new test is the valid red state here.)

- [ ] **Step 5: Add the property to UserProfile + DefaultUserProfile**

`UserProfile.java` — add to the interface (near `isLocked()`):

```java
/**
 * Returns {@code true} if the user must change their password at next login —
 * set when an account is freshly seeded with a default password, when an
 * administrator sets the password, or when a reset email issues a temporary one.
 *
 * @return {@code true} if a password change is required before normal use
 */
boolean isPasswordMustChange();

/**
 * Sets the must-change-password flag.
 *
 * @param mustChange {@code true} to require a password change at next login
 */
void setPasswordMustChange( boolean mustChange );
```

`DefaultUserProfile.java` — add field near `private Date lockExpiry;`:

```java
private boolean passwordMustChange;
```

accessors near `isLocked()`/`setLockExpiry()`:

```java
@Override
public boolean isPasswordMustChange()
{
    return passwordMustChange;
}

@Override
public void setPasswordMustChange( final boolean mustChange )
{
    this.passwordMustChange = mustChange;
}
```

and in `copyOf(...)` (next to the `setLockExpiry` copy line, ~line 75):

```java
c.setPasswordMustChange( src.isPasswordMustChange() );
```

Do NOT touch `equals`/`hashCode`/`toString` (they intentionally cover only the five identity fields).

- [ ] **Step 6: Persist it in JDBCUserDatabase**

In `JDBCUserDatabase.java` (SQL constants at lines ~196–210), replace the two constants:

```java
private static final String INSERT_PROFILE = "INSERT INTO users (uid,email,full_name,password,wiki_name,modified,login_name,attributes,bio,created,password_must_change) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
private static final String UPDATE_PROFILE = "UPDATE users SET uid=?,email=?,full_name=?,password=?,wiki_name=?,modified=?,login_name=?,attributes=?,bio=?,lock_expiry=?,password_must_change=? WHERE login_name=?";
```

In `save(...)`:
- INSERT branch: parameters 1–9 come from `setProfileParameters(...)`, parameter 10 is the `created` timestamp. Add after it: `ps.setBoolean( 11, profile.isPasswordMustChange() );` (use the actual PreparedStatement variable name in that branch).
- UPDATE branch (currently `ps4.setDate( 10, lockExpiry ); ps4.setString( 11, profile.getLoginName() );`): change to

```java
ps4.setDate( 10, lockExpiry );
ps4.setBoolean( 11, profile.isPasswordMustChange() );
ps4.setString( 12, profile.getLoginName() );
```

In `findByPreparedStatement(...)` (ResultSet→profile mapping, near the `lock_expiry` read at ~line 625):

```java
profile.setPasswordMustChange( rs.getBoolean( "password_must_change" ) );
```

(`getBoolean` returns `false` on SQL NULL; the column is NOT NULL DEFAULT FALSE anyway.)

- [ ] **Step 7: Update the InMemory test double**

`InMemoryUserDatabase.java` — in the private `copy(...)` method (lines ~101–119), next to `p.setLockExpiry( s.getLockExpiry() );` add:

```java
p.setPasswordMustChange( s.isPasswordMustChange() );
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest`
Expected: ALL PASS (whole class, to catch param-renumbering breakage in other save/find tests).

- [ ] **Step 9: Compile-check dependents (interface change!)**

Run: `mvn test-compile -pl wikantik-main,wikantik-rest -q`
Expected: BUILD SUCCESS. Fix any other UserProfile implementor surfaced by Step 1.

- [ ] **Step 10: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/user/UserProfile.java \
        wikantik-main/src/main/java/com/wikantik/auth/user/DefaultUserProfile.java \
        wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java \
        wikantik-main/src/test/java/com/wikantik/auth/user/InMemoryUserDatabase.java \
        wikantik-main/src/test/resources/postgresql-test.sql \
        wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java
git commit -m "feat(auth): password_must_change flag through UserProfile + JDBCUserDatabase"
```

---

### Task 2: Migration V039, seed consolidation, drift test

**Files:**
- Create: `bin/db/migrations/V039__password_must_change.sql`
- Modify: `bin/db/seed-users.sql` (full rewrite)
- Modify: `docker/db/001-init.sql` (full rewrite)
- Modify: `.gitignore`
- Create (UNTRACKED — do not git add): `bin/db/seed-users.local.sql`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/user/SeedCredentialDriftTest.java`

- [ ] **Step 1: Write the failing drift test**

Create `SeedCredentialDriftTest.java`:

```java
package com.wikantik.auth.user;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fresh-install "must change password" bootstrap relies on V002, V039 and
 * seed-users.sql all carrying the SAME canonical admin/admin123 hash: V039's
 * backstop UPDATE only fires when the stored hash equals the seeded literal.
 * If any copy drifts, a fresh install silently stops flagging the default
 * admin. This test pins the three SQL files to the canonical literal.
 */
class SeedCredentialDriftTest {

    private static final String CANONICAL_ADMIN_HASH =
            "{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg==";

    private static Path repoRoot() {
        Path dir = Paths.get( System.getProperty( "user.dir" ) ).toAbsolutePath();
        while ( dir != null && !Files.isDirectory( dir.resolve( "bin/db/migrations" ) ) ) {
            dir = dir.getParent();
        }
        if ( dir == null ) {
            throw new IllegalStateException( "Could not locate repo root (bin/db/migrations) above " + System.getProperty( "user.dir" ) );
        }
        return dir;
    }

    private static String read( final String relative ) throws IOException {
        return Files.readString( repoRoot().resolve( relative ) );
    }

    @Test
    void v002SeedsTheCanonicalAdminHash() throws IOException {
        assertTrue( read( "bin/db/migrations/V002__core_users_groups.sql" ).contains( CANONICAL_ADMIN_HASH ),
                "V002 admin seed must use the canonical admin123 hash" );
    }

    @Test
    void v039BackstopTargetsTheCanonicalAdminHash() throws IOException {
        assertTrue( read( "bin/db/migrations/V039__password_must_change.sql" ).contains( CANONICAL_ADMIN_HASH ),
                "V039 backstop must match the hash V002 seeds, or fresh installs stop being flagged" );
    }

    @Test
    void seedUsersSqlUsesTheCanonicalAdminHash() throws IOException {
        assertTrue( read( "bin/db/seed-users.sql" ).contains( CANONICAL_ADMIN_HASH ),
                "seed-users.sql admin seed must use the canonical admin123 hash" );
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=SeedCredentialDriftTest`
Expected: FAIL — `v039BackstopTargetsTheCanonicalAdminHash` throws `NoSuchFileException` (V039 doesn't exist yet). The other two pass.

- [ ] **Step 3: Create the migration**

Create `bin/db/migrations/V039__password_must_change.sql`:

```sql
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
```

- [ ] **Step 4: Run the drift test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=SeedCredentialDriftTest`
Expected: 3/3 PASS.

- [ ] **Step 5: Rewrite seed-users.sql (admin-only, insert-if-absent)**

Replace the ENTIRE contents of `bin/db/seed-users.sql` with:

```sql
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
```

- [ ] **Step 6: Preserve the personal accounts into the local hook (UNTRACKED)**

Create `bin/db/seed-users.local.sql` containing the `jakefear@gmail.com` DO-block VERBATIM as it exists in the current `bin/db/seed-users.sql` (lines 38–57, the "Basic user account" block) — copy it BEFORE rewriting in Step 5, or recover it from `git show HEAD:bin/db/seed-users.sql`. Do NOT `git add` this file.

Append to `.gitignore`:

```
bin/db/seed-users.local.sql
```

- [ ] **Step 7: Shrink the container init SQL**

Replace the ENTIRE contents of `docker/db/001-init.sql` with:

```sql
-- Container-path database init. Runs once via docker-entrypoint-initdb.d when
-- the pgdata volume is empty. The PostgreSQL image creates the database and
-- user from POSTGRES_DB/POSTGRES_USER; everything else — schema, the single
-- canonical admin seed (admin/admin123, password_must_change=TRUE), grants —
-- comes from bin/db/migrations via migrate.sh, which the wikantik container
-- entrypoint runs before Tomcat starts. Keeping schema out of this file
-- prevents drift between the container and bare-metal install paths.
--
-- pgvector is required by the Knowledge Graph, hybrid retrieval, and Page
-- Graph subsystems; the base image (pgvector/pgvector:pg18) ships the
-- extension, and installing it needs superuser — which only this initdb
-- context has.

CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 8: Sanity-check migrate.sh ordering assumption**

Run: `grep -n "schema_migrations\|for.*V\*" bin/db/migrate.sh | head`
Confirm migrate.sh applies `V*.sql` in version order (V002 seeds admin before V039 flags it). No change expected — just verify and note in the task report.

- [ ] **Step 9: Commit (NOT the local seed file)**

```bash
git add bin/db/migrations/V039__password_must_change.sql bin/db/seed-users.sql \
        docker/db/001-init.sql .gitignore \
        wikantik-main/src/test/java/com/wikantik/auth/user/SeedCredentialDriftTest.java
git commit -m "feat(db): V039 password_must_change + single canonical admin seed

001-init.sql shrinks to extension-only (migrations own schema+seed on both
install paths); seed-users.sql is admin-only insert-if-absent; personal
accounts move to gitignored seed-users.local.sql."
git status --short   # verify seed-users.local.sql shows as untracked (??), nothing staged remains
```

---

### Task 3: PasswordChangeGate + AuthResource wiring (wikantik-rest)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/PasswordChangeGate.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java`

Context: `AuthResource.handleLogin` is at lines ~428–484 (success block builds `{success, username}`); `handleGetMe` at ~112–132 builds `{authenticated, login, fullName, roles}`; the password-change block inside `handleUpdateProfile` is at ~381–409; `handleResetPassword` at ~511–568 sets `profile.setPassword(randomPassword)` after the mail succeeds. `AuthResourceTest` uses `TestEngine` + `HttpMockFactory` + Mockito (see its existing tests for the request/response mock pattern). CAUTION (known gotcha): `HttpMockFactory` shares one `"mock-session"` id across mock requests — auth state set up in one test can leak into a later "anonymous" request; follow whatever isolation the sibling tests in this class already use.

- [ ] **Step 1: Write the failing tests**

Add to `AuthResourceTest.java` (adapt mock construction — `BufferedReader` body, `PrintWriter` capture — from the existing login test in the same class):

```java
@Test
void loginResponseCarriesMustChangePasswordFlag() throws Exception {
    final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
    final UserProfile profile = db.newProfile();
    profile.setLoginName( "flagged" );
    profile.setFullname( "Flagged User" );
    profile.setEmail( "flagged@example.com" );
    profile.setPassword( "Xk3-Valid-Pass-77!" );
    profile.setPasswordMustChange( true );
    db.save( profile );

    final HttpServletResponse response = mockResponseWithWriter();   // mirror sibling tests
    final HttpServletRequest request = mockLoginRequest( "flagged", "Xk3-Valid-Pass-77!" ); // mirror sibling tests
    servlet.doPost( request, response );

    final JsonObject json = parseResponseJson();                     // mirror sibling tests
    assertTrue( json.get( "success" ).getAsBoolean() );
    assertTrue( json.get( "mustChangePassword" ).getAsBoolean() );
}

@Test
void selfServicePasswordChangeClearsTheFlag() throws Exception {
    final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
    final UserProfile profile = db.newProfile();
    profile.setLoginName( "flagged2" );
    profile.setFullname( "Flagged Two" );
    profile.setEmail( "flagged2@example.com" );
    profile.setPassword( "Xk3-Valid-Pass-77!" );
    profile.setPasswordMustChange( true );
    db.save( profile );

    // PUT /api/auth/profile as flagged2 with currentPassword+newPassword — mirror
    // the existing handleUpdateProfile test in this class for session/mock setup.
    putProfileAs( "flagged2", "{\"currentPassword\":\"Xk3-Valid-Pass-77!\",\"newPassword\":\"Nw9-Fresh-Pass-31!\"}" );

    assertFalse( db.findByLoginName( "flagged2" ).isPasswordMustChange() );
}

@Test
void resetPasswordSetsTheFlag() throws Exception {
    // Mirror the existing reset-password test's mail mocking in this class.
    // After a successful reset for a known email, the profile must be flagged:
    // assertTrue( db.findByEmail( "..." ).isPasswordMustChange() );
}
```

The three helper-call names above (`mockResponseWithWriter`, `mockLoginRequest`, `parseResponseJson`, `putProfileAs`) are placeholders for whatever the sibling tests in `AuthResourceTest` actually do — REPLICATE the sibling pattern inline; do not invent new helpers unless the class already has them. If the class has no reset-password test with mail mocking, implement `resetPasswordSetsTheFlag` by calling the servlet with a mocked `MailUtil` static (Mockito `MockedStatic`) the same way other static mocks are used in this class — and if mail mocking proves impractical, test the flag-set behavior at the `handleResetPassword` seam that IS practical and note it in the report.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-rest -Dtest=AuthResourceTest`
Expected: the three new tests FAIL (missing `mustChangePassword` key / flag not cleared / flag not set). Existing tests still pass.

- [ ] **Step 3: Create PasswordChangeGate**

Create `wikantik-rest/src/main/java/com/wikantik/rest/PasswordChangeGate.java` (include the standard ASF license header copied from a sibling file):

```java
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.user.UserDatabase;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves and caches the per-user "must change password" state for a request.
 *
 * The flag lives on the user profile ({@code users.password_must_change});
 * lookups are cached on the HttpSession so the enforcement filter does not hit
 * the database per request. The cache is written on login, refreshed lazily on
 * first gated request, and cleared when the user changes their own password.
 * Consequence (accepted trade-off): an admin flagging a user with a live
 * session takes effect at that user's next login, not mid-session.
 */
public final class PasswordChangeGate {

    /** HttpSession attribute caching the Boolean flag for the session's user. */
    public static final String SESSION_ATTRIBUTE = "wikantik.passwordMustChange";

    /** Structured error code emitted by the enforcement filter. */
    public static final String ERROR_CODE = "PASSWORD_CHANGE_REQUIRED";

    private static final Logger LOG = LogManager.getLogger( PasswordChangeGate.class );

    private PasswordChangeGate() {
    }

    /**
     * Returns whether the named user must change their password, consulting the
     * HttpSession cache first and falling back to a profile lookup (which is
     * then cached when a session exists).
     */
    public static boolean mustChangePassword( final Engine engine, final HttpServletRequest request,
                                              final String loginName ) {
        final HttpSession httpSession = request.getSession( false );
        if ( httpSession != null ) {
            final Object cached = httpSession.getAttribute( SESSION_ATTRIBUTE );
            if ( cached instanceof Boolean flag ) {
                return flag;
            }
        }
        boolean flag = false;
        try {
            final UserDatabase db = AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase();
            flag = db.findByLoginName( loginName ).isPasswordMustChange();
        } catch ( final NoSuchPrincipalException e ) {
            // Authenticated principal without a local profile (e.g. container-managed
            // identity) — nothing to flag, but worth a trace if it happens unexpectedly.
            LOG.warn( "No profile for authenticated principal '{}' while checking password gate: {}",
                    loginName, e.getMessage() );
        }
        cache( request, flag );
        return flag;
    }

    /** Caches the flag on the HttpSession when one exists. */
    public static void cache( final HttpServletRequest request, final boolean flag ) {
        final HttpSession httpSession = request.getSession( false );
        if ( httpSession != null ) {
            httpSession.setAttribute( SESSION_ATTRIBUTE, flag );
        }
    }
}
```

If `AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase()` does not compile (the bridge returns `AuthSubsystem.Services`), check how `RememberMeAuthFilter` and `AdminUserResource.getUserDatabase()` (line 92: `getSubsystems().auth().users().getUserDatabase()`) reach the database and use that exact chain.

- [ ] **Step 4: Wire AuthResource**

(a) `handleLogin` success block — after the `setLoginCookie` call and before building `result`, resolve and cache the flag, then include it:

```java
boolean mustChange = false;
try {
    final UserDatabase udb = getSubsystems().auth().users().getUserDatabase();
    mustChange = udb.findByLoginName( username ).isPasswordMustChange();
} catch ( final NoSuchPrincipalException e ) {
    LOG.warn( "No profile found for '{}' after successful login: {}", username, e.getMessage() );
}
PasswordChangeGate.cache( request, mustChange );

final Map< String, Object > result = new LinkedHashMap<>();
result.put( "success", true );
result.put( "username", username );
result.put( "mustChangePassword", mustChange );
sendJson( response, result );
```

(b) `handleGetMe` — inside the `session.isAuthenticated()` branch add:

```java
result.put( "mustChangePassword",
        PasswordChangeGate.mustChangePassword( engine, request, session.getLoginPrincipal().getName() ) );
```

(c) `handleUpdateProfile` password-change block — after the NIST validation passes, alongside `profile.setPassword( newPassword );` add:

```java
profile.setPasswordMustChange( false );
```

and AFTER the subsequent `db.save( profile );` succeeds add:

```java
if ( newPassword != null && !newPassword.isBlank() ) {
    PasswordChangeGate.cache( request, false );
}
```

(adapt: if the save is shared with non-password updates, guard the cache-clear on the password actually having been changed, exactly as shown).

(d) `handleResetPassword` — change the post-mail save block to:

```java
profile.setPassword( randomPassword );
profile.setPasswordMustChange( true );
db.save( profile );
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl wikantik-rest -Dtest=AuthResourceTest`
Expected: ALL PASS (new and pre-existing).

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/PasswordChangeGate.java \
        wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java
git commit -m "feat(rest): mustChangePassword in login/status; set on email reset, clear on self-change"
```

---

### Task 4: MustChangePasswordFilter + web.xml (wikantik-rest, wikantik-war)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/MustChangePasswordFilter.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/MustChangePasswordFilterTest.java`

Context: model the filter on `AdminAuthFilter` (same package): engine stored in `init()` via `Wiki.engine().find( filterConfig.getServletContext(), null )`; session via `Wiki.session().find( engine, req )`; OPTIONS passthrough; an `isSpaNavigation(req)` helper that lets browser HTML GETs through (COPY `AdminAuthFilter`'s actual `isSpaNavigation` implementation verbatim); 403 with a literal JSON body. Model the test on `AdminAuthFilterTest` (same test package).

- [ ] **Step 1: Write the failing tests**

Create `MustChangePasswordFilterTest.java` mirroring `AdminAuthFilterTest`'s setup (TestEngine, filter init with mocked FilterConfig returning `engine.getServletContext()`, mocked request/response/chain). Tests:

```java
@Test
void anonymousRequestPassesThrough()        // unauthenticated session → chain.doFilter called
@Test
void authenticatedUnflaggedUserPassesThrough() // create+login profile with flag false → chain called
@Test
void flaggedUserIsRejectedWithStructuredCode() {
    // profile with setPasswordMustChange(true), authenticated session,
    // GET /api/pages with Accept: application/json
    // assert: chain NOT called, status 403, body contains
    // "\"code\":\"PASSWORD_CHANGE_REQUIRED\"" and "\"error\":true"
}
@Test
void flaggedUserMayStillCallAuthEndpoints() // request URI /api/auth/profile → chain called
@Test
void spaNavigationPassesThrough()           // GET with Accept: text/html → chain called
```

Write complete test methods following the sibling class's mock idioms (request URI/method/Accept-header stubbing, `Mockito.verify( chain ).doFilter(...)` vs `Mockito.verifyNoInteractions( chain )`, response status capture). For "authenticated session" setup, use however `AdminAuthFilterTest` arranges an authenticated `WikiSession` (e.g. `TestEngine`'s login helpers / `WikiSessionTest` utilities). Remember the shared `"mock-session"` id gotcha — keep per-test isolation identical to the sibling class.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-rest -Dtest=MustChangePasswordFilterTest`
Expected: COMPILE FAILURE (filter class missing) — valid red.

- [ ] **Step 3: Implement the filter**

```java
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Enforces the "must change password" gate on /api/* and /admin/*: an
 * authenticated session whose user is flagged (users.password_must_change)
 * gets 403 + {"code":"PASSWORD_CHANGE_REQUIRED"} for everything except the
 * auth surface it needs to fix the situation (/api/auth/* — login, logout,
 * status, the password-changing profile PUT, reset-password). Browser HTML
 * navigations pass through so the SPA shell can load and route the user to
 * the change-password screen; the data calls behind it stay gated.
 *
 * Registered in web.xml after RememberMeAuthFilter (so re-authenticated
 * sessions are visible) and before AdminAuthFilter.
 */
public class MustChangePasswordFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( MustChangePasswordFilter.class );

    /** Paths a flagged user may still reach (prefix match on the context-relative path). */
    private static final String[] EXEMPT_PREFIXES = { "/api/auth/", "/api/health" };

    private Engine engine;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        engine = Wiki.engine().find( filterConfig.getServletContext(), null );
        LOG.info( "MustChangePasswordFilter initialized" );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        if ( "OPTIONS".equalsIgnoreCase( req.getMethod() ) || isExempt( req ) || isSpaNavigation( req ) ) {
            chain.doFilter( request, response );
            return;
        }

        final Session session = Wiki.session().find( engine, req );
        if ( !session.isAuthenticated() ) {
            chain.doFilter( request, response );
            return;
        }

        final String loginName = session.getLoginPrincipal().getName();
        if ( PasswordChangeGate.mustChangePassword( engine, req, loginName ) ) {
            LOG.info( "Password-change gate blocked {} {} for user '{}'",
                    req.getMethod(), req.getRequestURI(), loginName );
            resp.setStatus( HttpServletResponse.SC_FORBIDDEN );
            resp.setCharacterEncoding( "UTF-8" );
            resp.setContentType( "application/json" );
            resp.getWriter().write( "{\"error\":true,\"status\":403,\"code\":\""
                    + PasswordChangeGate.ERROR_CODE
                    + "\",\"message\":\"You must change your password before continuing\"}" );
            return;
        }

        chain.doFilter( request, response );
    }

    private boolean isExempt( final HttpServletRequest req ) {
        final String path = req.getRequestURI().substring( req.getContextPath().length() );
        for ( final String prefix : EXEMPT_PREFIXES ) {
            if ( path.startsWith( prefix ) ) {
                return true;
            }
        }
        return false;
    }

    // COPY AdminAuthFilter's isSpaNavigation(...) implementation verbatim here.
    private boolean isSpaNavigation( final HttpServletRequest req ) {
        throw new UnsupportedOperationException( "replace with AdminAuthFilter's implementation" );
    }

    @Override
    public void destroy() {
    }
}
```

Replace the `isSpaNavigation` body with `AdminAuthFilter`'s exact implementation (and add the `destroy()` only if the sibling filters have it). Add the ASF license header.

- [ ] **Step 4: Register in web.xml**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, add the `<filter>` declaration AND `<filter-mapping>` immediately AFTER `RememberMeAuthFilter`'s and BEFORE `AdminAuthFilter`'s (filter-mapping order in this file defines chain order):

```xml
<filter>
    <filter-name>MustChangePasswordFilter</filter-name>
    <filter-class>com.wikantik.rest.MustChangePasswordFilter</filter-class>
</filter>
```

```xml
<filter-mapping>
    <filter-name>MustChangePasswordFilter</filter-name>
    <url-pattern>/api/*</url-pattern>
</filter-mapping>
<filter-mapping>
    <filter-name>MustChangePasswordFilter</filter-name>
    <url-pattern>/admin/*</url-pattern>
</filter-mapping>
```

(Match the declaration-block vs mapping-block layout convention used by the existing entries in this file.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl wikantik-rest -Dtest=MustChangePasswordFilterTest`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/MustChangePasswordFilter.java \
        wikantik-rest/src/test/java/com/wikantik/rest/MustChangePasswordFilterTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): MustChangePasswordFilter gates /api + /admin with PASSWORD_CHANGE_REQUIRED"
```

---

### Task 5: AdminUserResource sets the flag (wikantik-rest)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `AdminUserResourceTest.java`, mirroring its existing create/update test mock idioms:

```java
@Test
void adminCreatedUserIsFlaggedForPasswordChange() {
    // POST /admin/users with {loginName:"newbie", password:"Xk3-Valid-Pass-77!", fullName:"New Bee"}
    // then assert db.findByLoginName("newbie").isPasswordMustChange() is true
}

@Test
void adminSettingPasswordFlagsTheUser() {
    // seed an unflagged profile "existing" with a password, flag false;
    // PUT /admin/users/existing with {"password":"Nw9-Fresh-Pass-31!"}
    // assert db.findByLoginName("existing").isPasswordMustChange() is true
}

@Test
void adminUpdateWithoutPasswordDoesNotFlag() {
    // seed unflagged profile "plain"; PUT /admin/users/plain with {"fullName":"Renamed"}
    // assert flag still false
}

@Test
void profileToMapExposesPasswordMustChange() {
    // GET /admin/users/<flagged user> — response JSON contains "passwordMustChange": true
}
```

Write them as complete tests using the sibling tests' request/response mock pattern.

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl wikantik-rest -Dtest=AdminUserResourceTest`
Expected: the four new tests FAIL.

- [ ] **Step 3: Implement**

In `handleCreateUser` (line ~460), after `profile.setPassword( password );` add:

```java
profile.setPasswordMustChange( true );
```

In `handleUpdateUser` (line ~515), inside the `if ( password != null && !password.isBlank() )` block after `profile.setPassword( password );` add:

```java
profile.setPasswordMustChange( true );
```

In `profileToMap` (line ~644), after the `locked` block add:

```java
map.put( "passwordMustChange", profile.isPasswordMustChange() );
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn test -pl wikantik-rest -Dtest=AdminUserResourceTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java
git commit -m "feat(rest): admin-set passwords flag password_must_change; expose in admin user JSON"
```

---

### Task 6: SPA forced password change (wikantik-frontend + SPA route registration)

**Files:**
- Modify: `wikantik-frontend/src/hooks/useAuth.jsx`
- Modify: `wikantik-frontend/src/components/LoginPage.jsx`
- Create: `wikantik-frontend/src/components/ChangePasswordPage.jsx`
- Modify: `wikantik-frontend/src/main.jsx`
- Modify: `wikantik-frontend/src/App.jsx` (or wherever the `<App />` layout component lives)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java:96` (SPA_EXACT)
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (SpaRoutingFilter mapping)
- Test: `wikantik-frontend/src/components/ChangePasswordPage.test.jsx`, extend `wikantik-frontend/src/components/LoginPage.test.jsx`

REMINDER: a new SPA route needs BOTH web.xml (SpaRoutingFilter `<url-pattern>`) and the `SPA_EXACT` array — either alone silently 404s. Reuse `src/components/ui/` primitives and existing form styles (mirror LoginPage's markup/classes); plain inline reimplementation is a review-reject.

- [ ] **Step 1: Write the failing tests**

`ChangePasswordPage.test.jsx` — mirror `LoginPage.test.jsx`'s render helper (router + AuthProvider mocking pattern):

```jsx
it('renders current/new/confirm password fields and submit', () => { /* getByTestId x4 */ });
it('rejects mismatched new passwords client-side', async () => { /* type mismatch, expect error text, no api call */ });
it('submits currentPassword+newPassword to updateProfile and navigates away on success', async () => {
  // mock api.updateProfile to resolve; assert called with
  // { currentPassword: 'old', newPassword: 'new-strong' } and navigation to /wiki/Main
});
it('surfaces server validation errors', async () => { /* mock rejection with err.body.message; expect message rendered */ });
```

Extend `LoginPage.test.jsx`:

```jsx
it('navigates to /change-password when login response requires it', async () => {
  // mock useAuth login to resolve { success: true, mustChangePassword: true }
  // submit; assert navigate('/change-password')
});
```

Write complete tests using the file's existing mocking utilities (vi.mock of the api client / useAuth, MemoryRouter, data-testids).

- [ ] **Step 2: Run to verify failure**

Run: `cd wikantik-frontend && npm test -- ChangePasswordPage LoginPage`
Expected: ChangePasswordPage tests fail (component missing); new LoginPage test fails.
NOTE (known flakiness): vitest concurrency can produce false failures — re-run a failing file alone before treating it as real.

- [ ] **Step 3: Implement frontend changes**

(a) `useAuth.jsx` — return the login response so callers can branch:

```jsx
const login = async (username, password) => {
  const result = await api.login(username, password);
  await refresh();
  return result;
};
```

(b) `LoginPage.jsx` — in `handleSubmit`:

```jsx
const result = await login(username, password);
navigate(result?.mustChangePassword ? '/change-password' : '/wiki/Main');
```

(c) `ChangePasswordPage.jsx` — new component. Structure/classes mirror `LoginPage.jsx` (same card/form layout); content:

```jsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

export default function ChangePasswordPage() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const { refresh } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    if (newPassword !== confirmPassword) {
      setError('New passwords do not match');
      return;
    }
    setSaving(true);
    try {
      await api.updateProfile({ currentPassword, newPassword });
      await refresh();
      navigate('/wiki/Main');
    } catch (err) {
      setError(err.body?.message || err.message || 'Failed to change password');
    } finally {
      setSaving(false);
    }
  };

  // Render: explanation copy ("Your account requires a new password before you
  // can continue — the current one was assigned, not chosen.") + three password
  // inputs (data-testid: change-current, change-new, change-confirm) + submit
  // (data-testid: change-submit) + error display, using LoginPage's card/form
  // markup and ui/ primitives.
}
```

(d) `main.jsx` — add to the route table (top-level, next to `/login`):

```jsx
<Route path="/change-password" element={<ChangePasswordPage />} />
```

(e) `App.jsx` (the `<App />` layout that wraps all routes) — add a redirect effect using the component's existing hook imports:

```jsx
const { user } = useAuth();
const location = useLocation();
const navigate = useNavigate();
useEffect(() => {
  if (user?.authenticated && user.mustChangePassword
      && location.pathname !== '/change-password') {
    navigate('/change-password', { replace: true });
  }
}, [user, location.pathname, navigate]);
```

This also covers mid-session flagging: any gated 403 fires the existing `wikantik:auth-required` event → `refresh()` → `/api/auth/user` returns `mustChangePassword: true` → this effect redirects. If App.jsx already destructures `useAuth()`/`useLocation()`, merge rather than duplicate.

(f) `SpaRoutingFilter.java:96` — add `"/change-password"` to `SPA_EXACT`:

```java
private static final String[] SPA_EXACT = { "/admin", "/search", "/page-graph", "/knowledge-graph", "/preferences", "/reset-password", "/blog", "/login", "/me/mentions", "/change-password" };
```

(g) `web.xml` — add to the SpaRoutingFilter `<filter-mapping>`:

```xml
<url-pattern>/change-password</url-pattern>
```

- [ ] **Step 4: Run frontend tests + rest compile**

Run: `cd wikantik-frontend && npm test` and `mvn test-compile -pl wikantik-rest -q`
Expected: vitest ALL PASS (re-run individual files alone if concurrency flakes); compile clean.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/hooks/useAuth.jsx wikantik-frontend/src/components/LoginPage.jsx \
        wikantik-frontend/src/components/ChangePasswordPage.jsx wikantik-frontend/src/main.jsx \
        wikantik-frontend/src/App.jsx wikantik-frontend/src/components/ChangePasswordPage.test.jsx \
        wikantik-frontend/src/components/LoginPage.test.jsx \
        wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(spa): forced /change-password flow for must-change-password logins"
```

---

### Task 7: End-to-end IT (wikantik-it-test-rest)

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/MustChangePasswordIT.java`

Context: the IT stack starts a fresh pgvector container, runs `bin/db/migrate.sh` (V002 seeds `admin/admin123`, V039 flags it), then `it-test-seed.sql` (adds `janne` — used by all other ITs, so mutating `admin` here is safe). Model class structure (Cargo base URL discovery, GSON, HttpClient) on `ScimDeactivationAuthIT.java` in the same package — copy its base-url/system-property setup exactly. Use ONE test method so the phase order is guaranteed.

- [ ] **Step 1: Write the IT**

```java
package com.wikantik.its.rest;

// imports + class-level boilerplate copied from ScimDeactivationAuthIT (base URL, GSON)

/**
 * Fresh-install first-login flow: the V002-seeded admin (admin/admin123) is
 * flagged by V039, so its first session is gated to the auth surface until the
 * password is changed; after the change the gate lifts and stays lifted.
 */
class MustChangePasswordIT {

    private static final String NEW_PASSWORD = "Wk-Adm1n-Fresh-9472!";

    @Test
    void adminFirstLoginForcesPasswordChange() throws Exception {
        // Cookie-holding client: one session across the phases.
        final CookieManager cookies = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler( cookies )
                .followRedirects( HttpClient.Redirect.NORMAL ).build();

        // 1. Login as the freshly-seeded admin: succeeds AND reports the flag.
        HttpResponse< String > login = post( client, "/api/auth/login",
                GSON.toJson( Map.of( "username", "admin", "password", "admin123" ) ) );
        assertEquals( 200, login.statusCode(), login.body() );
        JsonObject loginJson = JsonParser.parseString( login.body() ).getAsJsonObject();
        assertTrue( loginJson.get( "mustChangePassword" ).getAsBoolean(), login.body() );

        // 2. Status probe carries the flag.
        HttpResponse< String > me = get( client, "/api/auth/user" );
        assertTrue( JsonParser.parseString( me.body() ).getAsJsonObject()
                .get( "mustChangePassword" ).getAsBoolean(), me.body() );

        // 3. A non-auth API call is gated with the structured code.
        HttpResponse< String > gated = get( client, "/admin/users" );
        assertEquals( 403, gated.statusCode(), gated.body() );
        assertTrue( gated.body().contains( "PASSWORD_CHANGE_REQUIRED" ), gated.body() );

        // 4. Self-service password change through the allowlisted endpoint.
        HttpResponse< String > change = put( client, "/api/auth/profile",
                GSON.toJson( Map.of( "currentPassword", "admin123", "newPassword", NEW_PASSWORD ) ) );
        assertEquals( 200, change.statusCode(), change.body() );

        // 5. Gate lifted in the same session.
        HttpResponse< String > ungated = get( client, "/admin/users" );
        assertEquals( 200, ungated.statusCode(), ungated.body() );

        // 6. A fresh session with the new password is unflagged.
        final HttpClient fresh = HttpClient.newBuilder()
                .cookieHandler( new CookieManager( null, CookiePolicy.ACCEPT_ALL ) )
                .followRedirects( HttpClient.Redirect.NORMAL ).build();
        HttpResponse< String > relogin = post( fresh, "/api/auth/login",
                GSON.toJson( Map.of( "username", "admin", "password", NEW_PASSWORD ) ) );
        assertEquals( 200, relogin.statusCode(), relogin.body() );
        assertFalse( JsonParser.parseString( relogin.body() ).getAsJsonObject()
                .get( "mustChangePassword" ).getAsBoolean(), relogin.body() );
    }

    // post/get/put helpers: java.net.http.HttpRequest with Content-Type/Accept
    // application/json against the Cargo base URL — copy ScimDeactivationAuthIT's
    // request-building style, parameterized by the shared client.
}
```

Write the helpers fully, copying `ScimDeactivationAuthIT`'s base-URL resolution. Note: `GET /admin/users` is sent with `Accept: application/json` so it is NOT treated as SPA navigation by the gate.

- [ ] **Step 2: Run the single IT module (clean — never trust a stale war overlay)**

Run (from repo root, NO `-T`):
```bash
mvn clean install -pl wikantik-it-tests/wikantik-it-test-rest -am -Pintegration-tests \
    -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=MustChangePasswordIT
```
Expected: BUILD SUCCESS with `MustChangePasswordIT` green. (The `-Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false` pair skips wikantik-main's ~4000 serial unit tests without skipping ITs; `clean` is mandatory because IT modules overlay wikantik-war and a stale overlay silently drops web.xml changes → false 404s. If `-Dit.test` isn't honored by the module's failsafe config, run without it and let the module's full IT set run.)

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/MustChangePasswordIT.java
git commit -m "test(it): fresh-install admin first-login forced password change end-to-end"
```

---

### Task 8: Shell scripts — banners, local seed hook, install hardening

**Files:**
- Modify: `bin/deploy-local.sh`
- Modify: `docker/entrypoint.sh`
- Modify: `bin/db/install-fresh.sh`

No unit-test harness exists for these; verification is `bash -n` + targeted manual checks listed below.

- [ ] **Step 1: deploy-local.sh — local seed hook + message truth**

Replace the seeding success message at line ~489:

```bash
    print_status "Admin account ensured in ${WIKI_DB} (admin/admin123 — first login requires choosing a new password)"
```

Immediately AFTER the seed-users if/elif/else block (after line ~498), add the local hook:

```bash
# Optional local-only accounts (personal logins, testbot). Gitignored; absent
# on fresh clones — that's fine.
LOCAL_SEED_SQL="${SCRIPT_DIR}/db/seed-users.local.sql"
if [[ -f "${LOCAL_SEED_SQL}" ]]; then
    if PGPASSWORD="${POSTGRES_PASSWORD}" psql \
           -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" \
           -U "${POSTGRES_USER}" -d "${WIKI_DB}" \
           -f "${LOCAL_SEED_SQL}" -q 2>/dev/null \
       || psql -d "${WIKI_DB}" -f "${LOCAL_SEED_SQL}" -q 2>/dev/null \
       || psql -U postgres -d "${WIKI_DB}" -f "${LOCAL_SEED_SQL}" -q 2>/dev/null; then
        print_status "Local user accounts seeded from seed-users.local.sql"
    else
        print_warning "seed-users.local.sql present but could not be applied — run it manually."
    fi
fi
```

- [ ] **Step 2: deploy-local.sh — first-start banner**

At the END of the script (after the `startup.sh` call and its print_status, line ~511), add:

```bash
# First-login guidance: tell the operator exactly what to expect. The flag
# query degrades gracefully (empty = unknown, e.g. password auth refused).
ADMIN_MUST_CHANGE="$(PGPASSWORD="${POSTGRES_PASSWORD}" psql \
    -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" \
    -U "${POSTGRES_USER}" -d "${WIKI_DB}" -tAc \
    "SELECT password_must_change FROM users WHERE login_name='admin'" 2>/dev/null || true)"
echo ""
echo "==========================================="
echo " Wikantik is starting at http://localhost:8080/"
if [[ "${ADMIN_MUST_CHANGE// /}" == "t" ]]; then
    echo ""
    echo "   First login:  admin / admin123"
    echo "   You will be required to choose a new password on first login."
elif [[ "${ADMIN_MUST_CHANGE// /}" == "f" ]]; then
    echo ""
    echo "   Admin password already set — log in with your chosen password."
fi
echo "==========================================="
```

- [ ] **Step 3: entrypoint.sh — container first-start banner**

In `docker/entrypoint.sh`, immediately BEFORE the final `exec "$@"` (line ~313), add:

```bash
# --- First-login guidance ---
# On a fresh database the migrations just seeded admin/admin123 with
# password_must_change=TRUE; tell the operator. Query failure (e.g. exotic
# auth setups) just suppresses the banner — never blocks startup.
ADMIN_MUST_CHANGE="$(PGHOST="${POSTGRES_HOST}" PGPORT="${POSTGRES_PORT}" \
    PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD}" \
    psql -d "${POSTGRES_DB}" -tAc \
    "SELECT password_must_change FROM users WHERE login_name='admin'" 2>/dev/null || true)"
if [ "${ADMIN_MUST_CHANGE}" = "t" ]; then
  echo "============================================================"
  echo " Wikantik first start: log in at ${WIKANTIK_BASE_URL:-http://localhost:8080/}"
  echo "   Username: admin"
  echo "   Password: admin123"
  echo " You will be required to choose a new password on first login."
  echo "============================================================"
fi
```

Also update the stale env-var doc comment at line ~28 (`WIKANTIK_SEED_DEV_USERS  "true" to insert admin/admin123 + testbot`) to:

```bash
#   WIKANTIK_SEED_DEV_USERS      "true" to ensure the default admin exists
#                                (admin/admin123, must-change-on-first-login)
#                                via bin/db/seed-users.sql (dev only)
```

and the seeding echo at line ~306 to `echo "Ensuring default admin account (admin/admin123, first login forces a change)..."`.

- [ ] **Step 4: install-fresh.sh — require DB_APP_PASSWORD, explicit migrate-role decision**

In `bin/db/install-fresh.sh`:

(a) Replace line 46 (`DB_APP_PASSWORD="${DB_APP_PASSWORD:-ChangeMe123!}"`) with:

```bash
DB_APP_PASSWORD="${DB_APP_PASSWORD:-}"
```

(b) Add arg parsing for `--no-migrate-role` next to the existing `-h|--help` case (line ~35):

```bash
NO_MIGRATE_ROLE=0
for arg in "$@"; do
    case "${arg}" in
        -h|--help)
            awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
            exit 0
            ;;
        --no-migrate-role)
            NO_MIGRATE_ROLE=1
            ;;
    esac
done
```

(replace the existing single-arg `case "${1:-}"` block).

(c) Add upfront validation BEFORE step 1 (before the `db_exists=` line, ~line 64):

```bash
# Fail fast on missing secrets — better here than a default credential in
# production or an ALTER-migration ownership failure weeks from now.
role_preexists=$(super_psql -d postgres -c \
    "SELECT 1 FROM pg_roles WHERE rolname = '${DB_APP_USER}';" 2>/dev/null || true)
if [[ "${role_preexists// /}" != "1" && -z "${DB_APP_PASSWORD}" ]]; then
    print_err "DB_APP_PASSWORD is required to create the ${DB_APP_USER} role (no default is provided)."
    echo "  Re-run with: DB_APP_PASSWORD='<a real password>' $0"
    exit 1
fi
if [[ -z "${DB_MIGRATE_PASSWORD:-}" && "${NO_MIGRATE_ROLE}" -ne 1 ]]; then
    print_err "DB_MIGRATE_PASSWORD is not set. Without the migrate role, future ALTER-based"
    print_err "migrations will fail with 'must be owner of table'."
    echo "  Either re-run with DB_MIGRATE_PASSWORD set (recommended), or pass --no-migrate-role"
    echo "  to explicitly accept running future migrations as ${PGUSER}."
    exit 1
fi
```

(d) Update the step-5 else-branch (lines ~108–111) to reflect the explicit opt-out:

```bash
else
    print_warn "--no-migrate-role: skipping create-migrate-user. Future migrations must run as ${PGUSER}."
fi
```

(e) Remove the now-dead `print_warn "Set DB_APP_PASSWORD env var..."` at line ~83, and update the header-comment Usage/env-var docs (lines 9–28) to document the new requirement and flag.

- [ ] **Step 5: Verify syntax + behavior probes**

```bash
bash -n bin/deploy-local.sh && bash -n docker/entrypoint.sh && bash -n bin/db/install-fresh.sh
bin/db/install-fresh.sh --help | head -20          # help still renders
DB_APP_PASSWORD= bash bin/db/install-fresh.sh 2>&1 | head -5 || true
```
Expected: all `bash -n` clean; the third command fails fast with the DB_MIGRATE_PASSWORD or DB_APP_PASSWORD message (depending on local role state) WITHOUT touching the database.

- [ ] **Step 6: Commit**

```bash
git add bin/deploy-local.sh docker/entrypoint.sh bin/db/install-fresh.sh
git commit -m "feat(install): first-login banners, seed-users.local.sql hook, install-fresh fail-fast secrets"
```

---

### Task 9: Docs truth pass

**Files:**
- Modify: `CLAUDE.md`, `README.md`, `docs/DockerDeployment.md`, `docs/WikantikOperations.md`, `docs/PostgreSQLLocalDeployment.md`

- [ ] **Step 1: Apply the corrections**

For each, locate by grep and fix:

1. `CLAUDE.md` — `# Access at http://localhost:8080/ — default login: admin / admin` → `# Access at http://localhost:8080/ — first login: admin / admin123 (a new password is required on first login)`. Also in the first-time-setup numbered steps: step 4 currently says "Set your PostgreSQL password in the context file (path shown by script output): tomcat/.../ROOT.xml" — replace with: `# 4. deploy-local.sh renders ROOT.xml and wikantik-custom.properties from .env — on the very first run it copies .env.example to .env and exits; edit POSTGRES_PASSWORD in .env and re-run bin/deploy-local.sh`.
2. `README.md` — grep `admin / admin123` and any "edit ROOT.xml" first-time-setup step; align both with the CLAUDE.md wording above (first login forces a password change; .env drives config rendering).
3. `docs/DockerDeployment.md` (line ~91, the `WIKANTIK_SEED_DEV_USERS` row) — replace `Set true to insert admin/admin123 + testbot dev accounts on start (via bin/db/seed-users.sql). **Never set in production.**` with `Set true to ensure the default admin (admin/admin123, must-change-on-first-login) exists on start (via bin/db/seed-users.sql). Fresh databases get the same flagged admin from migration V002+V039 regardless. **Never set in production.**`. Also add one sentence wherever the doc describes first login: the seeded admin must choose a new password at first login.
4. `docs/WikantikOperations.md` (line ~256) — same `WIKANTIK_SEED_DEV_USERS` correction as above (no testbot, no second account).
5. `docs/PostgreSQLLocalDeployment.md` (line ~98) — `Default login: admin / admin123.` → `First login: admin / admin123 — you will be required to choose a new password.`

- [ ] **Step 2: Verify no stale claims remain**

Run: `grep -rn "admin / admin\b\|admin/admin\b\|testbot dev accounts\|jakefear@gmail.com/passw0rd" CLAUDE.md README.md docs/*.md | grep -v wikantik-pages`
Expected: no hits describing seeded credentials that contradict the new behavior (the CLAUDE.md "Manual Testing Credentials" section about the testbot account in the local DB is still TRUE — testbot exists in the developer database — leave it).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md docs/DockerDeployment.md docs/WikantikOperations.md docs/PostgreSQLLocalDeployment.md
git commit -m "docs: single canonical admin credential story (admin123 + forced first-login change)"
```

---

### Task 10: Full verification gate

- [ ] **Step 1: Full unit build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS. (Known: some wikantik-main provider tests are parallel-flaky — if one fails, re-run that class alone before investigating.)

- [ ] **Step 2: Frontend suite**

Run: `cd wikantik-frontend && npm test`
Expected: ALL PASS (re-run individual files alone on concurrency flakes).

- [ ] **Step 3: Full IT reactor**

Run (sequential, NEVER `-T`; this takes a long time — run it per-module with `-pl ... -am` one at a time, or delegate to a subagent, rather than a single backgrounded shell that risks a wall-kill):

```bash
mvn clean install -Pintegration-tests -fae
```
Expected: BUILD SUCCESS across all IT submodules. Note: index-dependent Selenide ITs must keep using startup fixtures; nothing in this plan touches them, so failures there are environmental — re-run the failing module alone before chasing.

- [ ] **Step 4: Manual smoke (fresh-install simulation, optional but recommended)**

```bash
mvn clean install -DskipTests -T 1C && bin/deploy-local.sh
# expect the banner; if the local admin was already changed, expect "Admin password already set"
```

- [ ] **Step 5: Done — no commit (previous tasks each committed); report results verbatim**
