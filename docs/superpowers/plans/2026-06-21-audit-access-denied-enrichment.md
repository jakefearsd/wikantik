# Audit `access.denied` Forensics Enrichment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate `target`, `sourceIp`, `userAgent`, `correlationId`, and `detail` on `access.denied` (and request-context on every audited security event) by reading data already flowing past `AuditEventListener` — no event/schema changes.

**Architecture:** A new `AuditRequestContext` reads the request-thread Log4j2 MDC (set synchronously by `RequestCorrelationFilter`). `AuditEventListener` maps the denied `Permission` (carried as `WikiSecurityEvent.getTarget()`) into the existing `targetType`/`targetId`/`targetLabel`/`detail` columns and stamps request-context columns on all audited entries. A shared `RequestContextKeys` constant holder keeps the filter (writer) and the audit reader from drifting on key names.

**Tech Stack:** Java 21, JUnit 5, Mockito, Log4j2 `ThreadContext`, Maven, Selenide/HttpClient ITs (Cargo + PostgreSQL).

## Global Constraints

- Java 21+, Maven 3.9+ (copied from CLAUDE.md prerequisites).
- TDD: every behavior change lands as a failing test first, per CLAUDE.md.
- Never swallow exceptions with empty catch blocks — log at least `LOG.warn()` with context.
- Forward-only: **no** changes to `WikiSecurityEvent`, the audit DB schema, `JdbcAuditRepository`, or `AuditEntry`. No purge/backfill of existing rows.
- The shared MDC-key holder lives in `wikantik-main` (`com.wikantik.audit.RequestContextKeys`). `wikantik-observability` already compile-depends on `wikantik-main`, and `wikantik-main` does **not** depend on `wikantik-observability` — so this placement is visible to both with no pom changes and no dependency cycle.
- MDC key string values are unchanged (`requestId`, `method`, `uri`, `remoteAddr`) plus one new key `userAgent`; existing log-pattern configs that reference the old keys keep working.

## File Structure

| File | Module | Responsibility |
|------|--------|----------------|
| `com/wikantik/audit/RequestContextKeys.java` (create) | wikantik-main | Single source of truth for the 5 MDC key names. |
| `com/wikantik/audit/AuditRequestContext.java` (create) | wikantik-main | Null-safe reader of request context from the Log4j2 MDC. |
| `com/wikantik/audit/AuditRequestContextTest.java` (create) | wikantik-main | Unit test for the reader. |
| `com/wikantik/observability/RequestCorrelationFilter.java` (modify) | wikantik-observability | Add `userAgent` capture; reference `RequestContextKeys`. |
| `com/wikantik/observability/RequestCorrelationFilterTest.java` (modify) | wikantik-observability | Assert `userAgent` set/cleared. |
| `com/wikantik/audit/AuditEventListener.java` (modify) | wikantik-main | Map denied `Permission` → target/detail; stamp request-context on all entries. |
| `com/wikantik/audit/AuditEventListenerTest.java` (modify) | wikantik-main | New cases for permission target + request-context. |
| `com/wikantik/its/rest/AuditLogIT.java` (modify) | wikantik-it-tests | Wire-level: anonymous 403 → `/admin/audit` row has target + sourceIp + correlationId. |

---

## Task 1: `RequestContextKeys` + `AuditRequestContext` (MDC reader)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/audit/RequestContextKeys.java`
- Create: `wikantik-main/src/main/java/com/wikantik/audit/AuditRequestContext.java`
- Test: `wikantik-main/src/test/java/com/wikantik/audit/AuditRequestContextTest.java`

**Interfaces:**
- Produces:
  - `RequestContextKeys.REQUEST_ID`, `.METHOD`, `.URI`, `.REMOTE_ADDR`, `.USER_AGENT` — `public static final String` constants (`"requestId"`, `"method"`, `"uri"`, `"remoteAddr"`, `"userAgent"`).
  - `AuditRequestContext.sourceIp()`, `.userAgent()`, `.correlationId()`, `.uri()`, `.method()` — all `static String`, return `null` when the key is absent or blank.

- [ ] **Step 1: Write the failing test**

Create `wikantik-main/src/test/java/com/wikantik/audit/AuditRequestContextTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.audit;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuditRequestContextTest {

    @BeforeEach
    @AfterEach
    void clearMdc() { ThreadContext.clearAll(); }

    @Test
    void readsValuesFromThreadContext() {
        ThreadContext.put( RequestContextKeys.REMOTE_ADDR, "203.0.113.7" );
        ThreadContext.put( RequestContextKeys.USER_AGENT,  "curl/8.4.0" );
        ThreadContext.put( RequestContextKeys.REQUEST_ID,  "req-123" );
        ThreadContext.put( RequestContextKeys.URI,         "/api/pages/SecretPage" );
        ThreadContext.put( RequestContextKeys.METHOD,      "PUT" );

        assertEquals( "203.0.113.7",           AuditRequestContext.sourceIp() );
        assertEquals( "curl/8.4.0",            AuditRequestContext.userAgent() );
        assertEquals( "req-123",               AuditRequestContext.correlationId() );
        assertEquals( "/api/pages/SecretPage", AuditRequestContext.uri() );
        assertEquals( "PUT",                   AuditRequestContext.method() );
    }

    @Test
    void returnsNullWhenKeysAbsent() {
        assertNull( AuditRequestContext.sourceIp() );
        assertNull( AuditRequestContext.userAgent() );
        assertNull( AuditRequestContext.correlationId() );
        assertNull( AuditRequestContext.uri() );
        assertNull( AuditRequestContext.method() );
    }

    @Test
    void treatsBlankAsNull() {
        ThreadContext.put( RequestContextKeys.REMOTE_ADDR, "   " );
        assertNull( AuditRequestContext.sourceIp() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=AuditRequestContextTest -q`
Expected: FAIL — compilation error, `RequestContextKeys` / `AuditRequestContext` do not exist.

- [ ] **Step 3: Write the constant holder**

Create `wikantik-main/src/main/java/com/wikantik/audit/RequestContextKeys.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.audit;

/**
 * Canonical Log4j2 {@code ThreadContext} (MDC) key names for per-request
 * metadata. Single source of truth shared by the writer
 * ({@code com.wikantik.observability.RequestCorrelationFilter}) and the reader
 * ({@link AuditRequestContext}) so the two cannot drift on a key rename.
 */
public final class RequestContextKeys {

    private RequestContextKeys() {}

    /** Unique per-request correlation id (also surfaced as the {@code X-Request-Id} header). */
    public static final String REQUEST_ID  = "requestId";
    /** HTTP method of the current request. */
    public static final String METHOD      = "method";
    /** Request URI of the current request. */
    public static final String URI         = "uri";
    /** Client IP address of the current request. */
    public static final String REMOTE_ADDR = "remoteAddr";
    /** {@code User-Agent} header of the current request. */
    public static final String USER_AGENT  = "userAgent";
}
```

- [ ] **Step 4: Write the reader**

Create `wikantik-main/src/main/java/com/wikantik/audit/AuditRequestContext.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.audit;

import org.apache.logging.log4j.ThreadContext;

/**
 * Null-safe reader of per-request metadata from the Log4j2 {@code ThreadContext}
 * (MDC) populated by {@code RequestCorrelationFilter}. Because security events
 * dispatch synchronously on the request thread, audit mapping can read these
 * values here. On non-request threads (schedulers, startup) the MDC is empty and
 * every getter returns {@code null} — the audit columns simply stay blank.
 */
public final class AuditRequestContext {

    private AuditRequestContext() {}

    public static String sourceIp()      { return trimToNull( ThreadContext.get( RequestContextKeys.REMOTE_ADDR ) ); }
    public static String userAgent()     { return trimToNull( ThreadContext.get( RequestContextKeys.USER_AGENT ) ); }
    public static String correlationId() { return trimToNull( ThreadContext.get( RequestContextKeys.REQUEST_ID ) ); }
    public static String uri()           { return trimToNull( ThreadContext.get( RequestContextKeys.URI ) ); }
    public static String method()        { return trimToNull( ThreadContext.get( RequestContextKeys.METHOD ) ); }

    private static String trimToNull( final String s ) {
        return ( s == null || s.isBlank() ) ? null : s;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=AuditRequestContextTest -q`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/audit/RequestContextKeys.java \
        wikantik-main/src/main/java/com/wikantik/audit/AuditRequestContext.java \
        wikantik-main/src/test/java/com/wikantik/audit/AuditRequestContextTest.java
git commit -m "feat(audit): MDC key holder + AuditRequestContext reader"
```

---

## Task 2: Capture `userAgent` in `RequestCorrelationFilter`

**Files:**
- Modify: `wikantik-observability/src/main/java/com/wikantik/observability/RequestCorrelationFilter.java`
- Test: `wikantik-observability/src/test/java/com/wikantik/observability/RequestCorrelationFilterTest.java`

**Interfaces:**
- Consumes: `com.wikantik.audit.RequestContextKeys` (Task 1).
- Produces: a `userAgent` MDC entry during request processing, removed in `finally`.

**Mockito note:** the existing tests run under `MockitoExtension` (STRICT_STUBS). Once the filter calls `request.getHeader("User-Agent")`, a per-test stub for `getHeader("X-Request-Id")` would otherwise trigger a strict-stubbing argument mismatch. Add **one** lenient `@BeforeEach` stub for `User-Agent` (below) so the call matches a stub; existing assertions are untouched.

- [ ] **Step 1: Write the failing tests**

In `RequestCorrelationFilterTest.java`, add a lenient User-Agent stub to `setUp()` and two assertions. Replace the existing `setUp()` method:

```java
    @BeforeEach
    void setUp() {
        filter = new RequestCorrelationFilter();
        ThreadContext.clearAll();
        // Filter now reads User-Agent on every request; lenient so tests that
        // don't assert it aren't flagged as unnecessary stubbing.
        lenient().when( request.getHeader( "User-Agent" ) ).thenReturn( "JUnit-Agent/1.0" );
    }
```

Add a new test method:

```java
    @Test
    void enrichesMdcWithUserAgent() throws Exception {
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( null );
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/wiki/Main" );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        final AtomicReference<String> capturedUa = new AtomicReference<>();
        doAnswer( inv -> {
            capturedUa.set( ThreadContext.get( "userAgent" ) );
            return null;
        } ).when( chain ).doFilter( request, response );

        filter.doFilter( request, response, chain );

        assertEquals( "JUnit-Agent/1.0", capturedUa.get() );
        assertNull( ThreadContext.get( "userAgent" ), "userAgent should be cleared after filter completes" );
    }
```

- [ ] **Step 2: Run tests to verify the new one fails**

Run: `mvn test -pl wikantik-observability -Dtest=RequestCorrelationFilterTest -q`
Expected: FAIL — `enrichesMdcWithUserAgent` sees `null` (filter does not set `userAgent` yet).

- [ ] **Step 3: Modify the filter**

In `RequestCorrelationFilter.java`, add the import:

```java
import com.wikantik.audit.RequestContextKeys;
```

Replace the four local `MDC_*` constant declarations:

```java
    static final String HEADER_REQUEST_ID = "X-Request-Id";
    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_METHOD = "method";
    static final String MDC_URI = "uri";
    static final String MDC_REMOTE_ADDR = "remoteAddr";
```

with references to the shared holder plus a User-Agent header constant:

```java
    static final String HEADER_REQUEST_ID = "X-Request-Id";
    static final String HEADER_USER_AGENT = "User-Agent";
    static final String MDC_REQUEST_ID = RequestContextKeys.REQUEST_ID;
    static final String MDC_METHOD = RequestContextKeys.METHOD;
    static final String MDC_URI = RequestContextKeys.URI;
    static final String MDC_REMOTE_ADDR = RequestContextKeys.REMOTE_ADDR;
    static final String MDC_USER_AGENT = RequestContextKeys.USER_AGENT;
```

In `doFilter`, after the `MDC_REMOTE_ADDR` put, add the User-Agent put (store empty string when absent so `ThreadContext` never receives null):

```java
        ThreadContext.put( MDC_REMOTE_ADDR, httpRequest.getRemoteAddr() );
        final String userAgent = httpRequest.getHeader( HEADER_USER_AGENT );
        ThreadContext.put( MDC_USER_AGENT, userAgent == null ? "" : userAgent );
```

In the `finally` block, add the matching removal:

```java
            ThreadContext.remove( MDC_REMOTE_ADDR );
            ThreadContext.remove( MDC_USER_AGENT );
```

Update the class Javadoc `ThreadContext keys` list to include `userAgent` (one line):

```java
 *   <li>{@code userAgent} — client User-Agent header</li>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-observability -Dtest=RequestCorrelationFilterTest -q`
Expected: PASS (all existing tests + `enrichesMdcWithUserAgent`).

- [ ] **Step 5: Commit**

```bash
git add wikantik-observability/src/main/java/com/wikantik/observability/RequestCorrelationFilter.java \
        wikantik-observability/src/test/java/com/wikantik/observability/RequestCorrelationFilterTest.java
git commit -m "feat(observability): capture User-Agent in request MDC via shared keys"
```

---

## Task 3: Enrich `AuditEventListener` (permission target + request context)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/audit/AuditEventListener.java`
- Test: `wikantik-main/src/test/java/com/wikantik/audit/AuditEventListenerTest.java`

**Interfaces:**
- Consumes: `AuditRequestContext` (Task 1); `com.wikantik.auth.permissions.{PagePermission,WikiPermission,GroupPermission,AllPermission}`; `java.security.Permission`.
- Produces: `access.denied` entries with `targetType`/`targetId`/`targetLabel`/`detail`; all audited entries carry `sourceIp`/`userAgent`/`correlationId` when on a request thread.

Mapping rule for the denied `Permission` (the `WikiSecurityEvent` target):

| Permission | `targetType` | `targetId` | `targetLabel` |
|---|---|---|---|
| `PagePermission` | `page` | `getPage()` | `getActions() + " → " + getPage()` |
| `GroupPermission` | `group` | `getGroup()` | `getActions() + " → " + getGroup()` |
| `AllPermission` | `all` | `*` | `admin (AllPermission)` |
| `WikiPermission` | `wiki` | `getActions()` | `getActions()` |
| other `Permission` | `permission` | `getName()` | `getActions() + " → " + getName()` |
| `null` / non-`Permission` | (unset) | (unset) | (unset) |

`detail` for `access.denied`: `{"permission":"<getName()>","uri":"<ctx>","method":"<ctx>"}`, omitting `uri`/`method` when null.

- [ ] **Step 1: Write the failing tests**

In `AuditEventListenerTest.java`, add imports near the top (after the existing imports):

```java
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
```

Add these test methods to the class:

```java
    // ---------------------------------------------------------------- access.denied target

    @AfterEach
    void clearMdc() { ThreadContext.clearAll(); }

    @Test
    void accessDeniedWithPagePermissionMapsPageTarget() {
        final PagePermission perm = new PagePermission( "*:SecretPage", "edit" );
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, named( "alice" ), perm ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "access.denied", e.eventType() );
        assertEquals( AuditOutcome.DENIED, e.outcome() );
        assertEquals( "alice", e.actorPrincipal() );
        assertEquals( "page", e.targetType() );
        assertEquals( "SecretPage", e.targetId() );
        assertEquals( "edit → SecretPage", e.targetLabel() );
        assertTrue( e.detail().contains( "\"permission\":\"*:SecretPage\"" ),
            "detail should carry the permission name: " + e.detail() );
    }

    @Test
    void accessDeniedWithWikiPermissionMapsWikiTarget() {
        // WikiPermission.getActions() always returns the action lower-cased (per its Javadoc),
        // so the target id/label are "createpages", not "createPages".
        final WikiPermission perm = new WikiPermission( "*", "createPages" );
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, named( "bob" ), perm ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "wiki", e.targetType() );
        assertEquals( "createpages", e.targetId() );
        assertEquals( "createpages", e.targetLabel() );
    }

    @Test
    void accessDeniedWithNullPermissionDoesNotThrowAndStillRecords() {
        // Mirrors DefaultAuthorizationManager's session==null branch: principal and target both null.
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, null, null ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "access.denied", e.eventType() );
        assertNull( e.targetType() );
        assertNull( e.targetId() );
        assertNull( e.detail() );
    }

    @Test
    void securityEntryStampsRequestContextFromMdc() {
        ThreadContext.put( "remoteAddr", "203.0.113.7" );
        ThreadContext.put( "userAgent",  "curl/8.4.0" );
        ThreadContext.put( "requestId",  "req-xyz" );

        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.LOGIN_FAILED, named( "mallory" ) ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "203.0.113.7", e.sourceIp() );
        assertEquals( "curl/8.4.0",  e.userAgent() );
        assertEquals( "req-xyz",      e.correlationId() );
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=AuditEventListenerTest -q`
Expected: FAIL — `targetType`/`detail`/`sourceIp` are null on the new cases.

- [ ] **Step 3: Modify the listener**

In `AuditEventListener.java`, add imports:

```java
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;

import java.security.Permission;
```

Replace `mapSecurity` with a version that adds permission-target mapping (for `access.denied`) and request-context enrichment:

```java
    private AuditEntry mapSecurity( final WikiSecurityEvent se ) {
        final SecurityAudit mapping = SECURITY_AUDITS.get( se.getType() );
        if ( mapping == null ) return null;
        final String principal = principalName( se );
        final AuditEntry.Builder b = AuditEntry.builder()
            .eventTime( Instant.now() )
            .category( mapping.category() ).eventType( mapping.eventType() ).outcome( mapping.outcome() )
            .actorPrincipal( principal )
            .actorType( principal == null ? "anonymous" : "user" );
        if ( se.getType() == WikiSecurityEvent.ACCESS_DENIED ) {
            applyPermissionTarget( b, se.getTarget() );
        }
        enrichRequestContext( b );
        return b.build();
    }
```

Add `enrichRequestContext` to `mapPage` and `mapRename` as well. In `mapPage`, change the `return AuditEntry.builder()...build();` to capture the builder and enrich it:

```java
    private AuditEntry mapPage( final WikiPageEvent pe ) {
        final String eventType = PAGE_EVENT_TYPES.get( pe.getType() );
        if ( eventType == null ) return null;
        final String pageName = pe.getPageName();
        final AuditEntry.Builder b = AuditEntry.builder()
            .eventTime( Instant.now() )
            .category( AuditCategory.CONTENT )
            .eventType( eventType )
            .outcome( AuditOutcome.SUCCESS )
            .actorType( "system" )
            .targetType( "page" )
            .targetId( pageName )
            .targetLabel( pageName );
        enrichRequestContext( b );
        return b.build();
    }
```

In `mapRename`, likewise:

```java
    private AuditEntry mapRename( final WikiPageRenameEvent re ) {
        final String oldName = re.getOldPageName();
        final String newName = re.getNewPageName();
        final String detail = "{\"from\":\"" + escape( oldName ) + "\",\"to\":\"" + escape( newName ) + "\"}";
        final AuditEntry.Builder b = AuditEntry.builder()
            .eventTime( Instant.now() )
            .category( AuditCategory.CONTENT )
            .eventType( "page.rename" )
            .outcome( AuditOutcome.SUCCESS )
            .actorType( "system" )
            .targetType( "page" )
            .targetId( newName )
            .targetLabel( newName )
            .detail( detail );
        enrichRequestContext( b );
        return b.build();
    }
```

Add the two new private helpers (place them after `mapRename`, before `escape`):

```java
    /** Stamps request-context columns from the request-thread MDC; no-ops to null off-thread. */
    private void enrichRequestContext( final AuditEntry.Builder b ) {
        b.sourceIp( AuditRequestContext.sourceIp() )
         .userAgent( AuditRequestContext.userAgent() )
         .correlationId( AuditRequestContext.correlationId() );
    }

    /**
     * Maps the denied {@link Permission} (carried as the security event's target) into the
     * target columns plus a {@code detail} JSON. A null or non-Permission target leaves the
     * target columns unset (e.g. the session==null deny branch).
     */
    private void applyPermissionTarget( final AuditEntry.Builder b, final Object target ) {
        if ( !( target instanceof Permission perm ) ) return;
        final String type;
        final String id;
        final String label;
        if ( perm instanceof PagePermission pp ) {
            type = "page";  id = pp.getPage();  label = pp.getActions() + " → " + pp.getPage();
        } else if ( perm instanceof GroupPermission gp ) {
            type = "group"; id = gp.getGroup(); label = gp.getActions() + " → " + gp.getGroup();
        } else if ( perm instanceof AllPermission ) {
            type = "all";   id = "*";           label = "admin (AllPermission)";
        } else if ( perm instanceof WikiPermission wp ) {
            type = "wiki";  id = wp.getActions(); label = wp.getActions();
        } else {
            type = "permission"; id = perm.getName(); label = perm.getActions() + " → " + perm.getName();
        }
        b.targetType( type ).targetId( id ).targetLabel( label )
         .detail( deniedDetail( perm.getName() ) );
    }

    /** Builds the access.denied detail JSON, omitting uri/method when not on a request thread. */
    private static String deniedDetail( final String permissionName ) {
        final StringBuilder sb = new StringBuilder( "{\"permission\":\"" )
            .append( escape( permissionName ) ).append( '"' );
        final String uri = AuditRequestContext.uri();
        final String method = AuditRequestContext.method();
        if ( uri != null )    sb.append( ",\"uri\":\"" ).append( escape( uri ) ).append( '"' );
        if ( method != null ) sb.append( ",\"method\":\"" ).append( escape( method ) ).append( '"' );
        return sb.append( '}' ).toString();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=AuditEventListenerTest -q`
Expected: PASS (existing parameterized/page/rename tests + the 4 new cases).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/audit/AuditEventListener.java \
        wikantik-main/src/test/java/com/wikantik/audit/AuditEventListenerTest.java
git commit -m "feat(audit): map denied permission to target + stamp request context"
```

---

## Task 4: Wire-level IT — anonymous 403 surfaces target + sourceIp

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AuditLogIT.java`

**Interfaces:**
- Consumes: the running IT deployment (`/admin/users` behind `AdminAuthFilter`, `/admin/audit` read), the enriched listener (Task 3), and the User-Agent/correlation MDC (Tasks 1–2).
- Produces: nothing downstream — terminal verification.

**Approach:** Send an anonymous `GET /admin/users` carrying a unique client-supplied `X-Request-Id`. `AdminAuthFilter` calls `checkPermission(session, AllPermission)`, which fires `ACCESS_DENIED` with the `AllPermission` target on the request thread, where `RequestCorrelationFilter` has stamped the MDC. Then authenticate as admin and poll `/admin/audit` for the `access.denied` row whose `correlationId` equals the supplied id — a unique key immune to other tests' denials. Assert `targetType == "all"` and a non-blank `sourceIp`.

- [ ] **Step 1: Write the failing test**

In `AuditLogIT.java`, add a poll helper next to `pollForEvent` (after it):

```java
    /** Polls {@code GET /admin/audit} for an access.denied row with the given correlationId. */
    private JsonObject pollForAccessDenied( final String correlationId, final long timeoutMs )
            throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse<String> resp = get( "/admin/audit?limit=1000" );
            assertEquals( 200, resp.statusCode(), "GET /admin/audit should return 200, got: " + resp.body() );
            final JsonArray rows = JsonParser.parseString( resp.body() ).getAsJsonArray();
            for ( final JsonElement el : rows ) {
                final JsonObject row = el.getAsJsonObject();
                final String type = row.has( "eventType" ) && !row.get( "eventType" ).isJsonNull()
                        ? row.get( "eventType" ).getAsString() : "";
                final String corr = row.has( "correlationId" ) && !row.get( "correlationId" ).isJsonNull()
                        ? row.get( "correlationId" ).getAsString() : "";
                if ( "access.denied".equals( type ) && correlationId.equals( corr ) ) return row;
            }
            Thread.sleep( POLL_INTERVAL_MS );
        }
        fail( "Timed out waiting for access.denied row with correlationId=" + correlationId );
        return null; // unreachable
    }
```

Add the test method (use `@Order( 10 )` — one past the current max of 9):

```java
    @Test
    @Order( 10 )
    void accessDeniedCarriesTargetAndSourceIp() throws IOException, InterruptedException {
        logoutAdmin(); // ensure the next request is anonymous

        final String marker = "audit-it-deny-" + System.nanoTime();
        final HttpResponse<String> denied = client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/admin/users" ) )
                        .header( "Accept", "application/json" )
                        .header( "X-Request-Id", marker )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, denied.statusCode(),
                "anonymous /admin/users should be forbidden: " + denied.body() );

        loginAsAdmin();
        final JsonObject row = pollForAccessDenied( marker, POLL_TIMEOUT_MS );

        assertEquals( "all", row.get( "targetType" ).getAsString(),
                "AllPermission denial should map targetType=all" );
        final String sourceIp = row.has( "sourceIp" ) && !row.get( "sourceIp" ).isJsonNull()
                ? row.get( "sourceIp" ).getAsString() : null;
        assertNotNull( sourceIp, "access.denied should carry sourceIp" );
        assertFalse( sourceIp.isBlank(), "sourceIp should be non-blank" );
    }
```

- [ ] **Step 2: Build the IT war fresh and run the suite (full reactor install required for the war overlay)**

Per the IT-war-overlay gotcha, the IT module overlays `wikantik-war`; a stale unpacked overlay drops servlet/filter changes. Run a clean reactor build so Tasks 1–3 are in the war:

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`
Expected (first run, before app code is built into the war): if executed before Step-3 artifacts are installed, FAIL at the new assertion (null `correlationId`/`sourceIp`). After Tasks 1–3 are committed and installed, this is the PASS gate.

> Note: the failing-first demonstration for this IT is the unit-level coverage in Tasks 1–3; the IT is the integration gate. If you want an explicit red, temporarily stub the listener to skip `enrichRequestContext` and observe the timeout, then restore.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AuditLogIT.java
git commit -m "test(it): assert access.denied carries target + sourceIp end-to-end"
```

---

## Task 5: Final verification

- [ ] **Step 1: Unit build (no parallel flakiness — see CLAUDE.md)**

Run: `mvn clean install -DskipITs`
Expected: BUILD SUCCESS; `AuditRequestContextTest`, `AuditEventListenerTest`, `RequestCorrelationFilterTest` all green.

- [ ] **Step 2: Full IT reactor (sequential, fail-at-end)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS; `AuditLogIT` green including `accessDeniedCarriesTargetAndSourceIp`.

- [ ] **Step 3: Manual prod-grid sanity (optional, post-deploy)**

After deploy, trigger an anonymous restricted access and confirm the OBSERVABILITY → Audit row now shows `target` (e.g. `edit → SecretPage` / `all`), `sourceIp`, `userAgent`, and a `detail` with the endpoint. No DB migration is required.

---

## Self-Review

**Spec coverage:**
- Root-cause fix (read `se.getTarget()`) → Task 3 `applyPermissionTarget`. ✓
- `sourceIp`/`userAgent`/`correlationId` on all audited events → Task 3 `enrichRequestContext` in `mapSecurity`/`mapPage`/`mapRename`. ✓
- `userAgent` MDC capture → Task 2. ✓
- Shared MDC-key single source of truth → Task 1 `RequestContextKeys`, consumed by Tasks 2–3. ✓
- Action-qualified `targetLabel` + per-permission `targetType` table → Task 3. ✓
- `detail` JSON (permission + uri + method, null-omitting) → Task 3 `deniedDetail`. ✓
- `login.failed` gains source IP/agent → covered by uniform `enrichRequestContext` (Task 3 test `securityEntryStampsRequestContextFromMdc`). ✓
- Null-permission no-NPE → Task 3 test `accessDeniedWithNullPermissionDoesNotThrowAndStillRecords`. ✓
- Coverage via `/api/*` and `/admin/*` → exercised by Task 4 IT (`/admin/users`). ✓
- No schema/event/repository changes, forward-only, no purge → Global Constraints; nothing in any task edits those files. ✓
- Out-of-scope (MCP/Tools token denials) → untouched. ✓
- Tests enumerated in spec (`AuditRequestContextTest`, `AuditEventListenerTest`, `RequestCorrelationFilterTest`, extended `AuditLogIT`) → Tasks 1–4. ✓

**Placeholder scan:** No TBD/TODO; all code blocks are complete; the deferred constant-holder placement is resolved (wikantik-main). ✓

**Type consistency:** `RequestContextKeys.{REQUEST_ID,METHOD,URI,REMOTE_ADDR,USER_AGENT}` and `AuditRequestContext.{sourceIp,userAgent,correlationId,uri,method}` are referenced with identical names across Tasks 1–3. `applyPermissionTarget(Builder, Object)`, `enrichRequestContext(Builder)`, `deniedDetail(String)` defined and called consistently in Task 3. IT helper `pollForAccessDenied(String,long)` defined and called in Task 4. ✓
