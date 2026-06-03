# SCIM Provisioning (Users) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SCIM 2.0 `/scim/v2/Users` provisioning so an IdP (Okta/Entra) can automate onboarding and — the priority — offboarding through a single unified, audited decommission path.

**Architecture:** A dedicated `wikantik-scim` module exposes bearer-authed SCIM servlets. A new `UserLifecycleService` (in `wikantik-main`) is the one audited way any account is deactivated/reactivated (via the existing `lockExpiry` mechanism); the admin UI lock/unlock is refactored onto it, and SCIM `active:false`/`DELETE` route through it (soft-decommission). `ScimUserMapper` maps `UserProfile ↔ SCIM JSON`; small filter/PATCH subsets cover what IdPs use.

**Tech Stack:** Java 21, Servlet `Filter`/`HttpServlet` (mirroring `wikantik-admin-mcp`/`wikantik-tools`), Gson (project JSON lib), JUnit 5 + Mockito, the existing `AuditService`, Cargo+Selenide/REST IT harness.

**Spec:** `docs/wikantik-pages/ScimProvisioningDesign.md`

**Reference patterns (read before the relevant task):**
- Bearer filter: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java` (+ `McpAccessFilterTest`).
- Small protocol-server module template: `wikantik-tools/` (pom, filter, servlet, war/web.xml wiring).
- Lock/unlock internals being refactored: `wikantik-rest/.../AdminUserResource.java` (`tryLockUser`/`tryUnlockUser`: `setLockExpiry(INDEFINITE_LOCK_EXPIRY)`/`setLockExpiry(null)` + `db.save`; sentinel `INDEFINITE_LOCK_EXPIRY` is year-9999).
- Audit: `com.wikantik.audit.AuditService` / `AuditEntry.builder()` (category `ADMIN`, outcome `SUCCESS`).
- User store: `UserDatabase.findByLoginName`/`save`/`getWikiNames`; `UserProfile` (`getUid`, `getLoginName`, `getFullName`, `getEmail`, `getWikiName`, `getAttributes()` map incl. `sso.subject`, `getLockExpiry`/`setLockExpiry`, `isLocked`).
- SSO marker: `com.wikantik.auth.sso.SSOAutoProvisionService.ATTR_SSO_SUBJECT` (`"sso.subject"`).
- web.xml registration site: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (where `AdminAuditResource` + the MCP filters are registered).

**No DB migration** — SCIM reuses the `users` table and the profile attributes map (`sso.subject` already persists for SSO).

**Build/verify note:** the full IT reactor can't run single-shot in this sandbox; use `mvn clean install -T 1C -DskipITs` then the relevant IT module with `-pl … -am`. Long builds must run via a subagent or steps under the tool timeout (see memory `reference_full_it_reactor_execution`).

---

## Task 1: Scaffold the `wikantik-scim` module

**Files:**
- Create: `wikantik-scim/pom.xml`
- Modify: root `pom.xml` (add `<module>wikantik-scim</module>` to `<modules>`)
- Modify: `wikantik-war/pom.xml` (add a `wikantik-scim` dependency so it's bundled)
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/package-info.java` (license header + package doc, so the module has a compilable source)

- [ ] **Step 1: Create the module pom mirroring `wikantik-tools`**

Read `wikantik-tools/pom.xml` and create `wikantik-scim/pom.xml` with the same structure: parent = the reactor parent, `artifactId` `wikantik-scim`, packaging `jar`, and dependencies on `wikantik-main` (scope `provided`, like the other surface modules), `wikantik-http`, `jakarta.servlet-api` (provided), Gson, Log4j2, and test deps (JUnit 5, Mockito) matching `wikantik-tools`. Keep the ASF license header.

- [ ] **Step 2: Register in the reactor + war**

Add `<module>wikantik-scim</module>` to the root `pom.xml` `<modules>` list (next to `wikantik-tools`). Add to `wikantik-war/pom.xml` `<dependencies>` a `wikantik-scim` entry mirroring how `wikantik-tools`/`wikantik-admin-mcp` are bundled there.

- [ ] **Step 3: Add `package-info.java`**

```java
// ASF license header (copy from wikantik-tools)
/**
 * SCIM 2.0 provisioning surface ({@code /scim/v2/*}). See
 * docs/wikantik-pages/ScimProvisioningDesign.md.
 */
package com.wikantik.scim;
```

- [ ] **Step 4: Verify the reactor sees the module**

Run: `mvn -q -pl wikantik-scim -am compile`
Expected: BUILD SUCCESS (the module builds, even if it only has package-info).

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/pom.xml pom.xml wikantik-war/pom.xml wikantik-scim/src/main/java/com/wikantik/scim/package-info.java
git commit -m "build(scim): scaffold wikantik-scim module"
```

---

## Task 2: `UserLifecycleService` — the unified decommission path

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/auth/UserLifecycleService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/UserLifecycleServiceTest.java`

This is the single audited mechanism for deactivate/reactivate, shared by the admin UI (Task 3) and SCIM (Task 7).

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.auth;

import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLifecycleServiceTest {

    private static final class CapturingAudit implements AuditService {
        final List<AuditEntry> entries = new ArrayList<>();
        public void record( AuditEntry e ) { entries.add( e ); }
        public java.util.List<com.wikantik.audit.PersistedAuditEntry> query( com.wikantik.audit.AuditQuery q ) { return List.of(); }
        public java.util.Optional<Long> verifyChain( long a, long b ) { return java.util.Optional.empty(); }
        public long droppedCount() { return 0; }
    }

    @Test
    void deactivateSetsIndefiniteLockAndAudits() throws Exception {
        UserDatabase db = mock( UserDatabase.class );
        UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "u-1" );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( db.findByLoginName( "alice" ) ).thenReturn( p );
        CapturingAudit audit = new CapturingAudit();

        UserLifecycleService svc = new UserLifecycleService( db, audit );
        svc.deactivate( "alice", "admin-bob", "scim" );

        // Lock set far in the future (indefinite), profile saved.
        verify( p ).setLockExpiry( argThat( d -> d != null && d.after( new Date() ) ) );
        verify( db ).save( p );
        // Audit event recorded with the trigger-agnostic type + source in detail.
        assertEquals( 1, audit.entries.size() );
        AuditEntry e = audit.entries.get( 0 );
        assertEquals( AuditCategory.ADMIN, e.category() );
        assertEquals( "user.deactivate", e.eventType() );
        assertEquals( "user", e.targetType() );
        assertEquals( "alice", e.targetId() );
        assertEquals( "admin-bob", e.actorPrincipal() );
        assertTrue( e.detail() != null && e.detail().contains( "scim" ), "source in detail" );
    }

    @Test
    void reactivateClearsLockAndAudits() throws Exception {
        UserDatabase db = mock( UserDatabase.class );
        UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( db.findByLoginName( "alice" ) ).thenReturn( p );
        CapturingAudit audit = new CapturingAudit();

        UserLifecycleService svc = new UserLifecycleService( db, audit );
        svc.reactivate( "alice", "admin-bob", "admin-ui" );

        verify( p ).setLockExpiry( null );
        verify( db ).save( p );
        assertEquals( "user.reactivate", audit.entries.get( 0 ).eventType() );
    }

    @Test
    void auditFailureNeverBlocksTheLifecycleChange() throws Exception {
        UserDatabase db = mock( UserDatabase.class );
        UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( db.findByLoginName( "alice" ) ).thenReturn( p );
        AuditService throwing = mock( AuditService.class );
        doThrow( new RuntimeException( "audit down" ) ).when( throwing ).record( any() );

        UserLifecycleService svc = new UserLifecycleService( db, throwing );
        // Must not throw — the save already happened; auditing is best-effort.
        assertDoesNotThrow( () -> svc.deactivate( "alice", "x", "scim" ) );
        verify( db ).save( p );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl wikantik-main test-compile`
Expected: FAIL — `UserLifecycleService` does not exist. (Confirm `UserDatabase.save` and `UserProfile.setLockExpiry`/`getUid`/`getLoginName` signatures against the real interfaces; adjust the test if they differ — e.g. `save` may declare `throws WikiSecurityException`.)

- [ ] **Step 3: Write `UserLifecycleService`**

```java
package com.wikantik.auth;

import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Calendar;
import java.util.Date;

/**
 * The single audited mechanism for deactivating / reactivating a user account.
 * Deactivation uses the existing indefinite-lock mechanism (a far-future
 * {@code lockExpiry}); the row is retained so audit and ownership references stay
 * intact. Shared by the admin UI and SCIM so decommission behaves identically
 * regardless of trigger.
 */
public final class UserLifecycleService {

    private static final Logger LOG = LogManager.getLogger( UserLifecycleService.class );

    /** Year-9999 sentinel — "locked indefinitely" without TIMESTAMP overflow. */
    static final Date INDEFINITE_LOCK_EXPIRY = indefinite();

    private static Date indefinite() {
        final Calendar c = Calendar.getInstance();
        c.clear();
        c.set( 9999, Calendar.DECEMBER, 31, 23, 59, 59 );
        return c.getTime();
    }

    private final UserDatabase db;
    private final AuditService audit;

    public UserLifecycleService( final UserDatabase db, final AuditService audit ) {
        this.db = db;
        this.audit = audit;
    }

    /** Deactivate (indefinite lock). {@code actor} = who initiated; {@code source}
     *  = trigger (e.g. "scim", "admin-ui"). */
    public void deactivate( final String loginName, final String actor, final String source )
            throws com.wikantik.auth.WikiSecurityException {
        final UserProfile p = db.findByLoginName( loginName );
        p.setLockExpiry( INDEFINITE_LOCK_EXPIRY );
        db.save( p );
        emit( "user.deactivate", p, actor, source );
    }

    /** Reactivate (clear the lock). */
    public void reactivate( final String loginName, final String actor, final String source )
            throws com.wikantik.auth.WikiSecurityException {
        final UserProfile p = db.findByLoginName( loginName );
        p.setLockExpiry( null );
        db.save( p );
        emit( "user.reactivate", p, actor, source );
    }

    private void emit( final String eventType, final UserProfile p, final String actor, final String source ) {
        try {
            audit.record( AuditEntry.builder()
                    .eventTime( Instant.now() )
                    .category( AuditCategory.ADMIN )
                    .eventType( eventType )
                    .outcome( AuditOutcome.SUCCESS )
                    .actorPrincipal( actor )
                    .actorType( actor == null ? "system" : "user" )
                    .targetType( "user" )
                    .targetId( p.getLoginName() )
                    .targetLabel( p.getLoginName() )
                    .detail( "{\"source\":\"" + ( source == null ? "" : source ) + "\"}" )
                    .build() );
        } catch ( final RuntimeException e ) {
            // Auditing is best-effort; the lifecycle change already persisted.
            LOG.warn( "Failed to record {} audit for user={}: {}", eventType, p.getLoginName(), e.getMessage(), e );
        }
    }
}
```

(Verify the real checked-exception type on `UserDatabase.save` — likely
`com.wikantik.auth.WikiSecurityException` — and match the method signatures to it.
If `save` throws a different/no checked exception, adjust the `throws` clauses and
the test accordingly.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl wikantik-main test -Dtest=UserLifecycleServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/UserLifecycleService.java \
        wikantik-main/src/test/java/com/wikantik/auth/UserLifecycleServiceTest.java
git commit -m "feat(auth): UserLifecycleService — unified audited deactivate/reactivate"
```

---

## Task 3: Route admin lock/unlock through `UserLifecycleService`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java` (`tryLockUser`/`tryUnlockUser` and the single-item lock/unlock handlers delegate to the service; keep the existing audit intent — but now the audit event comes from the service, so remove any now-duplicate inline audit `record()` for lock/unlock added in the audit-log work and rely on the service's `user.deactivate`/`user.reactivate`).

- [ ] **Step 1: Resolve the service + delegate**

In `AdminUserResource`, obtain a `UserLifecycleService` (construct it from the resolved `UserDatabase` + `getEngine().getAuditService()`, or via the engine if you register it there — simplest: build it on demand: `new UserLifecycleService( getUserDatabase(), auditService )` where `auditService = getEngine() instanceof WikiEngine we ? we.getAuditService() : null`). If `auditService` is null, pass a no-op or guard — but since the v1 audit work added instrumentation here, the engine path already yields a non-null service in deployment; for safety, if null, fall back to the previous inline `setLockExpiry`+`save` (no audit). Then:
- `tryLockUser(login)` → `lifecycle.deactivate( login, currentActor, "admin-ui" )`, returning `Optional.empty()` on success / the error message on failure.
- `tryUnlockUser(login)` → `lifecycle.reactivate( login, currentActor, "admin-ui" )`.
- The single-item POST `/lock` and `/unlock` handlers call the same.

Remove any inline `auditService.record(...)` calls for lock/unlock that the audit-log feature added to this resource (the service now emits `user.deactivate`/`user.reactivate`) to avoid double-recording. Leave all OTHER admin instrumentation (policy/apikey) untouched. `currentActor` = the request principal name (same accessor the resource already uses).

- [ ] **Step 2: Compile + run the resource's tests**

Run: `mvn -q -pl wikantik-rest -am test-compile && mvn -q -pl wikantik-rest test -Dtest=AdminUserResource*`
Expected: BUILD SUCCESS; existing `AdminUserResource` tests pass (adjust any that asserted the old inline audit event name to expect `user.deactivate`/`user.reactivate`, if such a unit test exists).

- [ ] **Step 3: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java
git commit -m "refactor(auth): admin lock/unlock route through UserLifecycleService"
```

---

## Task 4: SCIM JSON types + `ScimUserMapper`

**Files:**
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimUserMapper.java`
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimError.java` (SCIM error-response helper)
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimUserMapperTest.java`

Use Gson (`JsonObject`/`JsonArray`) — the project's JSON lib. The mapper converts a `UserProfile` to a SCIM `User` `JsonObject` and reads a SCIM `User` `JsonObject` into the fields needed to create/update a profile.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScimUserMapperTest {

    @Test
    void toScimEmitsCoreSchemaAndActiveFromLock() {
        UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "u-1" );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( p.getFullName() ).thenReturn( "Alice Smith" );
        when( p.getEmail() ).thenReturn( "alice@example.com" );
        when( p.getWikiName() ).thenReturn( "AliceSmith" );
        when( p.isLocked() ).thenReturn( false );
        Map<String,Object> attrs = new HashMap<>();
        attrs.put( "sso.subject", "ext-123" );
        when( p.getAttributes() ).thenReturn( attrs );

        JsonObject o = ScimUserMapper.toScim( p, "https://host/scim/v2/Users" );

        assertTrue( o.getAsJsonArray( "schemas" ).toString().contains( "urn:ietf:params:scim:schemas:core:2.0:User" ) );
        assertEquals( "u-1", o.get( "id" ).getAsString() );
        assertEquals( "alice", o.get( "userName" ).getAsString() );
        assertEquals( "ext-123", o.get( "externalId" ).getAsString() );
        assertTrue( o.get( "active" ).getAsBoolean() );
        assertEquals( "alice@example.com",
                o.getAsJsonArray( "emails" ).get( 0 ).getAsJsonObject().get( "value" ).getAsString() );
        assertTrue( o.getAsJsonObject( "meta" ).get( "location" ).getAsString().endsWith( "/u-1" ) );
    }

    @Test
    void lockedProfileMapsToActiveFalse() {
        UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "u-2" );
        when( p.getLoginName() ).thenReturn( "bob" );
        when( p.isLocked() ).thenReturn( true );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        JsonObject o = ScimUserMapper.toScim( p, "https://host/scim/v2/Users" );
        assertFalse( o.get( "active" ).getAsBoolean() );
    }

    @Test
    void readCreateFieldsExtractsUserNameEmailExternalId() {
        JsonObject in = JsonParser.parseString(
            "{\"userName\":\"carol\",\"externalId\":\"ext-9\","
          + "\"name\":{\"formatted\":\"Carol Jones\"},"
          + "\"emails\":[{\"primary\":true,\"value\":\"carol@x.com\"}]}" ).getAsJsonObject();
        ScimUserMapper.CreateFields f = ScimUserMapper.readCreate( in );
        assertEquals( "carol", f.userName() );
        assertEquals( "ext-9", f.externalId() );
        assertEquals( "Carol Jones", f.fullName() );
        assertEquals( "carol@x.com", f.email() );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl wikantik-scim -am test-compile`
Expected: FAIL — `ScimUserMapper` does not exist.

- [ ] **Step 3: Write `ScimUserMapper` (+ `ScimError`)**

```java
package com.wikantik.scim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.auth.user.UserProfile;

/** Maps {@link UserProfile} to/from the SCIM 2.0 core User schema (Gson). */
public final class ScimUserMapper {

    public static final String SCHEMA_USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String ATTR_SSO_SUBJECT = "sso.subject";

    private ScimUserMapper() {}

    /** UserProfile → SCIM User JSON. {@code usersBaseUrl} is the collection URL
     *  (e.g. https://host/scim/v2/Users); meta.location = baseUrl + "/" + id. */
    public static JsonObject toScim( final UserProfile p, final String usersBaseUrl ) {
        final JsonObject o = new JsonObject();
        final JsonArray schemas = new JsonArray();
        schemas.add( SCHEMA_USER );
        o.add( "schemas", schemas );
        o.addProperty( "id", p.getUid() );
        o.addProperty( "userName", p.getLoginName() );
        final Object ext = p.getAttributes() == null ? null : p.getAttributes().get( ATTR_SSO_SUBJECT );
        if ( ext != null ) o.addProperty( "externalId", String.valueOf( ext ) );
        if ( p.getFullName() != null ) {
            final JsonObject name = new JsonObject();
            name.addProperty( "formatted", p.getFullName() );
            o.add( "name", name );
        }
        if ( p.getWikiName() != null ) o.addProperty( "displayName", p.getWikiName() );
        if ( p.getEmail() != null ) {
            final JsonArray emails = new JsonArray();
            final JsonObject em = new JsonObject();
            em.addProperty( "value", p.getEmail() );
            em.addProperty( "primary", true );
            emails.add( em );
            o.add( "emails", emails );
        }
        o.addProperty( "active", !p.isLocked() );
        final JsonObject meta = new JsonObject();
        meta.addProperty( "resourceType", "User" );
        meta.addProperty( "location", usersBaseUrl + "/" + p.getUid() );
        o.add( "meta", meta );
        return o;
    }

    /** Fields read from an inbound SCIM User for create/replace. */
    public record CreateFields( String userName, String externalId, String fullName,
                                String email, String displayName, Boolean active, String password ) {}

    public static CreateFields readCreate( final JsonObject in ) {
        final String userName = str( in, "userName" );
        final String externalId = str( in, "externalId" );
        String fullName = null;
        if ( in.has( "name" ) && in.get( "name" ).isJsonObject() ) {
            final JsonObject n = in.getAsJsonObject( "name" );
            fullName = n.has( "formatted" ) ? n.get( "formatted" ).getAsString()
                    : join( str( n, "givenName" ), str( n, "familyName" ) );
        }
        String email = null;
        if ( in.has( "emails" ) && in.get( "emails" ).isJsonArray() && in.getAsJsonArray( "emails" ).size() > 0 ) {
            // Prefer primary:true, else the first.
            for ( final var el : in.getAsJsonArray( "emails" ) ) {
                final JsonObject e = el.getAsJsonObject();
                if ( e.has( "primary" ) && e.get( "primary" ).getAsBoolean() ) { email = str( e, "value" ); break; }
            }
            if ( email == null ) email = str( in.getAsJsonArray( "emails" ).get( 0 ).getAsJsonObject(), "value" );
        }
        final String displayName = str( in, "displayName" );
        final Boolean active = in.has( "active" ) && !in.get( "active" ).isJsonNull()
                ? in.get( "active" ).getAsBoolean() : null;
        final String password = str( in, "password" );
        return new CreateFields( userName, externalId, fullName, email, displayName, active, password );
    }

    private static String str( final JsonObject o, final String k ) {
        return ( o.has( k ) && !o.get( k ).isJsonNull() ) ? o.get( k ).getAsString() : null;
    }
    private static String join( final String a, final String b ) {
        if ( a == null && b == null ) return null;
        return ( ( a == null ? "" : a ) + " " + ( b == null ? "" : b ) ).trim();
    }
}
```

```java
package com.wikantik.scim;

import com.google.gson.JsonObject;

/** Builds a SCIM error response body (RFC 7644 §3.12). */
public final class ScimError {
    private ScimError() {}
    public static JsonObject body( final int status, final String scimType, final String detail ) {
        final JsonObject o = new JsonObject();
        final com.google.gson.JsonArray schemas = new com.google.gson.JsonArray();
        schemas.add( "urn:ietf:params:scim:api:messages:2.0:Error" );
        o.add( "schemas", schemas );
        o.addProperty( "status", String.valueOf( status ) );
        if ( scimType != null ) o.addProperty( "scimType", scimType );
        if ( detail != null ) o.addProperty( "detail", detail );
        return o;
    }
}
```

Add ASF license headers. Verify `UserProfile` accessor names (`getUid`, `getLoginName`, `getFullName`, `getEmail`, `getWikiName`, `isLocked`, `getAttributes`) against the real interface and adjust if any differ.

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl wikantik-scim -am test -Dtest=ScimUserMapperTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimUserMapper.java \
        wikantik-scim/src/main/java/com/wikantik/scim/ScimError.java \
        wikantik-scim/src/test/java/com/wikantik/scim/ScimUserMapperTest.java
git commit -m "feat(scim): UserProfile<->SCIM User mapper + SCIM error helper"
```

---

## Task 5: `ScimFilterParser` + `ScimPatchApplier`

**Files:**
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimFilterParser.java`
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimPatchApplier.java`
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimFilterParserTest.java`
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimPatchApplierTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.wikantik.scim;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ScimFilterParserTest {

    @Test
    void parsesUserNameEq() {
        ScimFilterParser.Eq eq = ScimFilterParser.parse( "userName eq \"alice\"" ).orElseThrow();
        assertEquals( "userName", eq.attribute() );
        assertEquals( "alice", eq.value() );
    }

    @Test
    void parsesExternalIdEq() {
        ScimFilterParser.Eq eq = ScimFilterParser.parse( "externalId eq \"ext-9\"" ).orElseThrow();
        assertEquals( "externalId", eq.attribute() );
        assertEquals( "ext-9", eq.value() );
    }

    @Test
    void nullOrEmptyFilterIsNoConstraint() {
        assertTrue( ScimFilterParser.parse( null ).isEmpty() );
        assertTrue( ScimFilterParser.parse( "" ).isEmpty() );
    }

    @Test
    void unsupportedFilterThrows() {
        assertThrows( ScimFilterParser.UnsupportedFilterException.class,
                () -> ScimFilterParser.parse( "userName sw \"a\"" ) );
        assertThrows( ScimFilterParser.UnsupportedFilterException.class,
                () -> ScimFilterParser.parse( "displayName eq \"x\"" ) ); // attr not supported
    }
}
```

```java
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScimPatchApplierTest {

    private JsonObject patch( String ops ) {
        return JsonParser.parseString(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":" + ops + "}"
        ).getAsJsonObject();
    }

    @Test
    void replaceActiveFalseIsDetected() {
        ScimPatchApplier.Result r = ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]" ) );
        assertEquals( Boolean.FALSE, r.activeChange() );
    }

    @Test
    void replaceActiveTrueIsDetected() {
        ScimPatchApplier.Result r = ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]" ) );
        assertEquals( Boolean.TRUE, r.activeChange() );
    }

    @Test
    void replaceWithoutPathButActiveInValueObject() {
        // Entra often sends {op:replace, value:{active:false}}
        ScimPatchApplier.Result r = ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"value\":{\"active\":false}}]" ) );
        assertEquals( Boolean.FALSE, r.activeChange() );
    }

    @Test
    void complexValuePathRejected() {
        assertThrows( ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"].value\",\"value\":\"x\"}]" ) ) );
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q -pl wikantik-scim -am test-compile`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write `ScimFilterParser`**

```java
package com.wikantik.scim;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the minimal SCIM filter subset IdPs use: {@code <attr> eq "<value>"}
 *  for {@code userName} and {@code externalId} only. */
public final class ScimFilterParser {

    public record Eq( String attribute, String value ) {}

    public static final class UnsupportedFilterException extends RuntimeException {
        public UnsupportedFilterException( final String m ) { super( m ); }
    }

    private static final Set<String> SUPPORTED = Set.of( "userName", "externalId" );
    private static final Pattern EQ = Pattern.compile( "^\\s*(\\w+)\\s+eq\\s+\"([^\"]*)\"\\s*$" );

    private ScimFilterParser() {}

    public static Optional<Eq> parse( final String filter ) {
        if ( filter == null || filter.isBlank() ) return Optional.empty();
        final Matcher m = EQ.matcher( filter );
        if ( !m.matches() ) {
            throw new UnsupportedFilterException( "Only '<attr> eq \"value\"' is supported: " + filter );
        }
        final String attr = m.group( 1 );
        if ( !SUPPORTED.contains( attr ) ) {
            throw new UnsupportedFilterException( "Filtering on '" + attr + "' is not supported" );
        }
        return Optional.of( new Eq( attr, m.group( 2 ) ) );
    }
}
```

- [ ] **Step 4: Write `ScimPatchApplier`**

```java
package com.wikantik.scim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Interprets a SCIM PatchOp (RFC 7644 §3.5.2), supporting the subset IdPs use:
 * add/remove/replace on simple paths. The load-bearing case is toggling
 * {@code active}; other simple attribute changes are surfaced for the resource to
 * apply. Complex value-path filters (e.g. {@code emails[type eq "work"]}) are
 * rejected.
 */
public final class ScimPatchApplier {

    public static final class UnsupportedPatchException extends RuntimeException {
        public UnsupportedPatchException( final String m ) { super( m ); }
    }

    /** What the patch asks for. {@code activeChange} is non-null when the patch
     *  toggles active; {@code attributes} holds other simple replace/add values. */
    public record Result( Boolean activeChange, JsonObject attributes ) {}

    private ScimPatchApplier() {}

    public static Result apply( final JsonObject patchOp ) {
        Boolean activeChange = null;
        final JsonObject attrs = new JsonObject();
        if ( !patchOp.has( "Operations" ) || !patchOp.get( "Operations" ).isJsonArray() ) {
            throw new UnsupportedPatchException( "PatchOp missing Operations array" );
        }
        for ( final JsonElement opEl : patchOp.getAsJsonArray( "Operations" ) ) {
            final JsonObject op = opEl.getAsJsonObject();
            final String operation = op.has( "op" ) ? op.get( "op" ).getAsString().toLowerCase() : "";
            final String path = op.has( "path" ) && !op.get( "path" ).isJsonNull()
                    ? op.get( "path" ).getAsString() : null;
            if ( path != null && ( path.contains( "[" ) || path.contains( "." ) && path.contains( " " ) ) ) {
                throw new UnsupportedPatchException( "Complex PATCH path not supported: " + path );
            }
            switch ( operation ) {
                case "replace", "add" -> {
                    if ( "active".equals( path ) ) {
                        activeChange = op.get( "value" ).getAsBoolean();
                    } else if ( path == null && op.has( "value" ) && op.get( "value" ).isJsonObject() ) {
                        // No path: the value object carries the attributes (Entra style).
                        final JsonObject v = op.getAsJsonObject( "value" );
                        if ( v.has( "active" ) ) activeChange = v.get( "active" ).getAsBoolean();
                        v.entrySet().forEach( e -> { if ( !"active".equals( e.getKey() ) ) attrs.add( e.getKey(), e.getValue() ); } );
                    } else if ( path != null ) {
                        attrs.add( path, op.get( "value" ) );
                    }
                }
                case "remove" -> {
                    if ( "active".equals( path ) ) {
                        throw new UnsupportedPatchException( "Cannot remove 'active'" );
                    }
                    // Removing a simple attribute → represent as JSON null.
                    if ( path != null ) attrs.add( path, com.google.gson.JsonNull.INSTANCE );
                }
                default -> throw new UnsupportedPatchException( "Unsupported op: " + operation );
            }
        }
        return new Result( activeChange, attrs );
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn -q -pl wikantik-scim -am test -Dtest=ScimFilterParserTest,ScimPatchApplierTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimFilterParser.java \
        wikantik-scim/src/main/java/com/wikantik/scim/ScimPatchApplier.java \
        wikantik-scim/src/test/java/com/wikantik/scim/ScimFilterParserTest.java \
        wikantik-scim/src/test/java/com/wikantik/scim/ScimPatchApplierTest.java
git commit -m "feat(scim): filter + patch subset parsers"
```

---

## Task 6: `ScimAccessFilter` (bearer auth)

**Files:**
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimAccessFilter.java`
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimAccessFilterTest.java`

Mirror `wikantik-admin-mcp/.../McpAccessFilter.java`: a servlet `Filter` that reads `Authorization: Bearer <token>`, constant-time compares it to the configured token, and on mismatch writes a 401 with a SCIM error body. Read `McpAccessFilter` first to match its init/compare/deny shape.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.scim;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScimAccessFilterTest {

    @Test
    void validBearerPasses() throws Exception {
        ScimAccessFilter f = new ScimAccessFilter( "secret-token" );
        HttpServletRequest req = mock( HttpServletRequest.class );
        HttpServletResponse resp = mock( HttpServletResponse.class );
        FilterChain chain = mock( FilterChain.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer secret-token" );
        f.doFilter( req, resp, chain );
        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( 401 );
    }

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        ScimAccessFilter f = new ScimAccessFilter( "secret-token" );
        for ( String h : new String[]{ null, "Bearer nope", "secret-token" } ) {
            HttpServletRequest req = mock( HttpServletRequest.class );
            HttpServletResponse resp = mock( HttpServletResponse.class );
            FilterChain chain = mock( FilterChain.class );
            StringWriter sw = new StringWriter();
            when( req.getHeader( "Authorization" ) ).thenReturn( h );
            when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
            f.doFilter( req, resp, chain );
            verify( chain, never() ).doFilter( any(), any() );
            verify( resp ).setStatus( 401 );
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl wikantik-scim -am test-compile`
Expected: FAIL — `ScimAccessFilter` does not exist.

- [ ] **Step 3: Write `ScimAccessFilter`**

Provide a constructor taking the token (for the unit test) AND a no-arg constructor + `init(FilterConfig)` that reads the token from the `wikantik.scim.token` system property / context-param (mirror how `McpAccessFilter` resolves its config). Constant-time compare via `MessageDigest.isEqual`. On deny: `resp.setStatus(401)`, content type `application/scim+json`, write `ScimError.body(401, null, "invalid or missing bearer token")`.

```java
package com.wikantik.scim;

import com.google.gson.Gson;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ScimAccessFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( ScimAccessFilter.class );
    private static final String BEARER = "Bearer ";
    private static final Gson GSON = new Gson();

    private volatile String token;

    public ScimAccessFilter() {}
    public ScimAccessFilter( final String token ) { this.token = token; }

    @Override
    public void init( final FilterConfig cfg ) {
        if ( token == null ) {
            String t = System.getProperty( "wikantik.scim.token" );
            if ( ( t == null || t.isBlank() ) && cfg != null ) t = cfg.getInitParameter( "wikantik.scim.token" );
            this.token = t;
            if ( t == null || t.isBlank() ) {
                LOG.warn( "ScimAccessFilter: no wikantik.scim.token configured — all SCIM requests will be denied." );
            }
        }
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;
        final String auth = req.getHeader( "Authorization" );
        if ( token != null && !token.isBlank() && auth != null && auth.startsWith( BEARER )
                && constantTimeEquals( auth.substring( BEARER.length() ), token ) ) {
            chain.doFilter( request, response );
            return;
        }
        resp.setStatus( 401 );
        resp.setContentType( "application/scim+json" );
        resp.getWriter().write( GSON.toJson( ScimError.body( 401, null, "invalid or missing bearer token" ) ) );
    }

    private static boolean constantTimeEquals( final String a, final String b ) {
        return MessageDigest.isEqual( a.getBytes( StandardCharsets.UTF_8 ), b.getBytes( StandardCharsets.UTF_8 ) );
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl wikantik-scim -am test -Dtest=ScimAccessFilterTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimAccessFilter.java \
        wikantik-scim/src/test/java/com/wikantik/scim/ScimAccessFilterTest.java
git commit -m "feat(scim): bearer-token access filter"
```

---

## Task 7: `ScimUserResource` servlet + discovery + web.xml registration

**Files:**
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimUserResource.java` (HttpServlet)
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimDiscoveryResource.java` (ServiceProviderConfig/Schemas/ResourceTypes)
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (register the filter on `/scim/v2/*` and the two servlets)

This is the glue: it wires `ScimUserMapper`, `ScimFilterParser`, `ScimPatchApplier`, the `UserDatabase`/`UserManager`, `UserLifecycleService`, and `AuditService`, and implements the SSO fail-closed rule. Resolve the engine the way other servlets do (`Wiki.engine().find(...)` / the pattern `WikiPageFormatFilter` uses) and obtain managers from it. Read `wikantik-tools`'s servlet for the engine-lookup + Gson response idiom.

- [ ] **Step 1: Implement `ScimUserResource` (HttpServlet)**

Dispatch by method + path-info:
- `POST /scim/v2/Users` — `ScimUserMapper.readCreate`; **SSO fail-closed:** if a profile already exists for `userName` and it lacks `sso.subject`, return `409` `ScimError(409,"uniqueness",…)`; otherwise create the profile (set `loginName`, `fullName`, `email`, `wikiName`; put `sso.subject = externalId` in attributes; set a random password if none supplied via `CryptoUtil`/the existing password hasher), `userManager.setUserProfile(...)` (or `db.save`), audit `scim.user.create`, respond `201` with `toScim` + `Location` header. If `CreateFields.active()==Boolean.FALSE`, immediately `lifecycle.deactivate(login, actor, "scim")`.
- `GET /scim/v2/Users/{id}` — find by uid (iterate `getWikiNames`/`findByLoginName` as needed), `200` + `toScim`, or `404`.
- `GET /scim/v2/Users?filter=…&startIndex=&count=` — `ScimFilterParser.parse`; if present, look up by `userName` (→ `findByLoginName`) or `externalId` (scan profiles' `sso.subject`); with no filter, list all (paged). Respond a SCIM `ListResponse` (`schemas:["urn:ietf:params:scim:api:messages:2.0:ListResponse"]`, `totalResults`, `startIndex`, `itemsPerPage`, `Resources:[…]`). On `UnsupportedFilterException` → `400` `invalidFilter`.
- `PUT /scim/v2/Users/{id}` — replace mapped attributes; if `active` transitions, route through `lifecycle`; audit `scim.user.update`; `200`.
- `PATCH /scim/v2/Users/{id}` — `ScimPatchApplier.apply`; if `activeChange!=null` → `lifecycle.deactivate/reactivate(login, actor, "scim")`; apply other simple attrs + `db.save`; audit `scim.user.update`; `200`. On `UnsupportedPatchException` → `400` `invalidPath`. The actor for audit is a fixed principal like `"scim"` (the IdP), since SCIM requests aren't a human session.
- `DELETE /scim/v2/Users/{id}` — soft delete: `lifecycle.deactivate(login, "scim", "scim/delete")`; respond `204`. (Row retained.)

All responses `application/scim+json`. Build JSON with Gson. Resolve `usersBaseUrl` from the request (`req.getRequestURL()` up to `/Users`).

- [ ] **Step 2: Implement `ScimDiscoveryResource`**

`GET /scim/v2/ServiceProviderConfig` → a static SCIM ServiceProviderConfig advertising: `patch.supported=true`, `filter.supported=true` (with `maxResults`), `bulk.supported=false`, `changePassword.supported=false`, `sort.supported=false`, `etag.supported=false`, auth scheme `oauthbearertoken`. `GET /scim/v2/Schemas` and `/ResourceTypes` → the User schema + User resource-type descriptors. These can be served as constant JSON strings (keep them in the class). Mirror SCIM RFC 7643 §8 examples; keep them minimal but valid.

- [ ] **Step 3: Register in `web.xml`**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, add (mirroring the existing MCP/audit servlet+filter blocks):
- a `<filter>` + `<filter-mapping>` for `ScimAccessFilter` on `/scim/v2/*`;
- `<servlet>` + `<servlet-mapping>` for `ScimUserResource` on `/scim/v2/Users` and `/scim/v2/Users/*`;
- `<servlet>` + `<servlet-mapping>` for `ScimDiscoveryResource` on `/scim/v2/ServiceProviderConfig`, `/scim/v2/Schemas`, `/scim/v2/ResourceTypes`.
Verify the filter is mapped BEFORE the servlets (filter order) and covers all three paths.

- [ ] **Step 4: Compile + targeted tests**

Run: `mvn -q -pl wikantik-scim -am compile`
Expected: BUILD SUCCESS. (Resource-level behavior is covered by the IT in Task 8; if any pure-logic helper was added to the resource, unit-test it, but do not write brittle servlet-container unit tests.)

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimUserResource.java \
        wikantik-scim/src/main/java/com/wikantik/scim/ScimDiscoveryResource.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(scim): Users resource + discovery endpoints + web.xml wiring"
```

---

## Task 8: Integration test — full SCIM lifecycle

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimUsersIT.java`

Mirror `AuditLogIT`/`KgPolicyAdminIT` for the HttpClient + baseUrl harness. The SCIM token must be configured for the IT deployment — set `wikantik.scim.token` (e.g. via the IT module's system properties / the Cargo container config the way other test props are passed); if that wiring isn't obvious, set it as a context-param in the IT web.xml overlay or a system property in the failsafe config. Report how you wired it.

- [ ] **Step 1: Write the IT**

Cover, with `Authorization: Bearer <it token>` and content type `application/scim+json`:
1. **Auth:** a request with no/bad token → `401`.
2. **Create:** `POST /scim/v2/Users` with `userName`,`externalId`,`name`,`emails` → `201`, body has `id`, `active:true`, `meta.location`.
3. **List+filter:** `GET /scim/v2/Users?filter=userName eq "<name>"` → 1 result; `filter=externalId eq "<ext>"` → 1 result.
4. **Deactivate:** `PATCH /Users/{id}` `replace active:false` → `200`, then `GET` shows `active:false`; assert via `GET /admin/audit?eventType=user.deactivate` that an audit row with `source:scim` exists.
5. **Reactivate:** `PATCH active:true` → `active:true`.
6. **Soft delete:** `DELETE /Users/{id}` → `204`; a subsequent `GET /Users/{id}` still returns the user with `active:false` (row retained), and an audit `user.deactivate` exists.
7. **Discovery:** `GET /scim/v2/ServiceProviderConfig` → `200` with `patch.supported:true`.
8. **Unsupported filter:** `GET /Users?filter=displayName eq "x"` → `400` `invalidFilter`.

- [ ] **Step 2: Run the IT module**

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`
Expected: BUILD SUCCESS; `ScimUsersIT` passes. (Long — may need retry; if a background run is wall-killed, re-run. Run via a subagent if needed.)

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimUsersIT.java
git commit -m "test(scim): wire-level SCIM Users lifecycle IT"
```

---

## Task 9: Docs, CHANGELOG, agent-surface, final build

**Files:**
- Modify: `CHANGELOG.md` (Unreleased → Added: SCIM provisioning)
- Modify: `CLAUDE.md` (agent-facing surface table: add the `/scim/v2/*` endpoint row + the `wikantik-scim` module to the Module Structure list)
- Modify: `docs/wikantik-pages/ScimProvisioningDesign.md` (note the as-built SCIM token wiring if it differed from the spec)

- [ ] **Step 1: Update docs**

Add a CHANGELOG "Added" entry: `feat: SCIM 2.0 user provisioning (/scim/v2/Users) with a unified audited decommission path`. Add a row to the CLAUDE.md agent-surface table for `/scim/v2/*` (wikantik-scim, SCIM 2.0, bearer auth) and a `wikantik-scim` bullet in the Module Structure section.

- [ ] **Step 2: Full unit build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS (all modules incl. the new `wikantik-scim`).

- [ ] **Step 3: IT gate (two-halves; the rest module carries `ScimUsersIT`)**

Run (via subagent if it exceeds the tool budget): `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`
Expected: BUILD SUCCESS, `ScimUsersIT` + the existing `AuditLogIT` green.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md CLAUDE.md docs/wikantik-pages/ScimProvisioningDesign.md
git commit -m "docs(scim): changelog, agent-surface, as-built notes"
```

---

## Self-review

- **Spec coverage:** module → T1; unified `UserLifecycleService` → T2 + admin refactor T3; mapper/schema → T4; filter+PATCH subsets → T5; bearer auth → T6; Users resource + discovery + SSO fail-closed + soft-delete + audit → T7; full lifecycle IT (incl. audit assertion + 401 + soft-delete retention) → T8; docs/agent-surface → T9. Out-of-scope (Groups, hard delete, full grammar) correctly absent.
- **Placeholder scan:** logic-bearing units (T2,T4,T5,T6) have full code; T7 (servlet glue) and T1/T3/web.xml give exact endpoints/behaviors + the pattern files to mirror rather than 400 lines of boilerplate, and name every collaborator by its defined type. No TODO/TBD.
- **Type consistency:** `UserLifecycleService.deactivate/reactivate(login,actor,source)`, `ScimUserMapper.toScim/readCreate`+`CreateFields`, `ScimFilterParser.parse→Eq`/`UnsupportedFilterException`, `ScimPatchApplier.apply→Result(activeChange,attributes)`/`UnsupportedPatchException`, `ScimError.body(status,scimType,detail)`, `ScimAccessFilter` — all used consistently across tasks. Audit event types: `user.deactivate`/`user.reactivate` (from the service, source in detail) vs `scim.user.create`/`scim.user.update` (resource ops) — matches the spec.
- **Verify-before-code reminders:** real `UserProfile`/`UserDatabase` accessor + checked-exception signatures (T2, T4) and the engine-lookup idiom (T7) must be confirmed against the actual classes; each such task says so.
