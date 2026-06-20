# API-Key Self-Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a logged-in user view and reissue their own API keys through a gated `/preferences` section, backed by a new ownership-enforced `/api/self/apikeys` resource — secrets shown once, storage stays hashed-only.

**Architecture:** A new `SelfApiKeysResource` (wikantik-rest) rides the existing `/api/*` filter chain and scopes every operation to the caller's login (via `Wiki.session().find(...).isAuthenticated()`). It reuses `ApiKeyService` (wikantik-main) plus two new read helpers (`listByPrincipal`, `findById`). The frontend adds a `MyApiKeys` section inside the existing `UserPreferencesPage` (`/preferences` — no new SPA route), reusing a shared one-time-reveal modal extracted from the admin keys page.

**Tech Stack:** Java 21 servlets (`RestServletBase`), Gson, JUnit 5 + Mockito, H2 (PostgreSQL mode) for service unit tests, Cargo + Testcontainers for the IT, React + Vitest + Testing Library.

## Global Constraints

- **Storage stays hashed-only.** No plaintext/recoverable key storage. No schema change (no migration) — `api_keys` already has `principal_login`.
- **Ownership enforced server-side on every op.** List filtered by caller's login; rotate/revoke verify `principal_login == caller`.
- **Non-owned or unknown key id → HTTP 404** (uniform; no existence oracle). Unauthenticated → **401**. Invalid scope / malformed body → **400**.
- **`GET` returns the caller's ACTIVE (non-revoked) keys only**, metadata only — never `key_hash`, never plaintext.
- **Secret shown once** on generate/rotate (response `token` field); never returned by `GET`.
- **Gate = authenticated real account** (`session.isAuthenticated()`); a self-minted key carries only the caller's own permissions (no escalation).
- **No new SPA route** — the UI lives in the existing `/preferences` page (avoids web.xml + `SpaRoutingFilter.SPA_EXACT` dual-registration).
- **Never swallow exceptions** — at minimum `LOG.warn(...)` with context. Generate/rotate/revoke are **audited** (`apikey.issue` / `apikey.rotate` / `apikey.revoke`).
- Build check per module: `mvn test -pl <module> -Dtest=<Class>`; final gate `mvn clean install -Pintegration-tests -fae`.

---

### Task 1: `ApiKeyService` read helpers — `listByPrincipal` + `findById`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/apikeys/ApiKeyService.java` (add two methods after `list()`, ~line 247)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/apikeys/ApiKeyServiceTest.java` (add two tests; reuses the existing H2 `service` field + inline `api_keys` table from `setUp`)

**Interfaces:**
- Consumes: existing `ApiKeyService.Record`, `Scope`, `generate`, `revoke`, `readRow`, `TABLE`, `dataSource`, `LOG`.
- Produces: `List<Record> listByPrincipal(String principalLogin)` (active-only, newest first); `Optional<Record> findById(int id)` (active or revoked).

- [ ] **Step 1: Write the failing tests**

Add to `ApiKeyServiceTest.java` (imports `java.util.Optional` and `org.junit.jupiter.api.Assertions.*` are already present in that test; if `Optional` is missing add `import java.util.Optional;`):

```java
    @Test
    void listByPrincipalReturnsOnlyThatPrincipalsActiveKeysNewestFirst() {
        final ApiKeyService.Generated bob1 = service.generate( "bob", "k1", ApiKeyService.Scope.ALL, "admin" );
        service.generate( "bob", "k2", ApiKeyService.Scope.MCP, "admin" );
        service.generate( "carol", "k3", ApiKeyService.Scope.ALL, "admin" );
        service.revoke( bob1.record().id(), "admin" );            // k1 now revoked

        final java.util.List< ApiKeyService.Record > bobKeys = service.listByPrincipal( "bob" );
        Assertions.assertEquals( 1, bobKeys.size(), "revoked k1 excluded, carol excluded" );
        Assertions.assertEquals( "k2", bobKeys.get( 0 ).label() );
        Assertions.assertEquals( "bob", bobKeys.get( 0 ).principalLogin() );
        Assertions.assertTrue( service.listByPrincipal( "nobody" ).isEmpty() );
    }

    @Test
    void findByIdReturnsRecordOrEmpty() {
        final ApiKeyService.Generated g = service.generate( "dan", "laptop", ApiKeyService.Scope.TOOLS, "admin" );
        final Optional< ApiKeyService.Record > found = service.findById( g.record().id() );
        Assertions.assertTrue( found.isPresent() );
        Assertions.assertEquals( "dan", found.get().principalLogin() );
        Assertions.assertEquals( ApiKeyService.Scope.TOOLS, found.get().scope() );
        Assertions.assertTrue( service.findById( 999_999 ).isEmpty() );
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest='ApiKeyServiceTest#listByPrincipalReturnsOnlyThatPrincipalsActiveKeysNewestFirst+findByIdReturnsRecordOrEmpty'`
Expected: FAIL — compile error `cannot find symbol: method listByPrincipal` / `findById`.

- [ ] **Step 3: Implement the two methods**

In `ApiKeyService.java`, immediately after the `list()` method (closes ~line 247), add:

```java
    /** Lists a principal's ACTIVE keys, newest first. */
    public List< Record > listByPrincipal( final String principalLogin ) {
        final String sql = "SELECT id, key_hash, principal_login, label, scope,"
                + " created_at, created_by, last_used_at, revoked_at, revoked_by"
                + " FROM " + TABLE
                + " WHERE principal_login = ? AND revoked_at IS NULL"
                + " ORDER BY created_at DESC";
        final List< Record > out = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, principalLogin );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    out.add( readRow( rs ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "API key listByPrincipal failed for '{}': {}", principalLogin, e.getMessage() );
        }
        return out;
    }

    /** Looks up a key by id (active or revoked); empty if not found. */
    public Optional< Record > findById( final int id ) {
        final String sql = "SELECT id, key_hash, principal_login, label, scope,"
                + " created_at, created_by, last_used_at, revoked_at, revoked_by"
                + " FROM " + TABLE + " WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( readRow( rs ) ) : Optional.empty();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "API key findById failed for id={}: {}", id, e.getMessage() );
            return Optional.empty();
        }
    }
```

(`Optional`, `List`, `ArrayList`, `Connection`, `PreparedStatement`, `ResultSet`, `SQLException` are already imported in this file.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest='ApiKeyServiceTest#listByPrincipalReturnsOnlyThatPrincipalsActiveKeysNewestFirst+findByIdReturnsRecordOrEmpty'`
Expected: PASS — `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/apikeys/ApiKeyService.java \
        wikantik-main/src/test/java/com/wikantik/auth/apikeys/ApiKeyServiceTest.java
git commit -m "feat(apikeys): add listByPrincipal + findById to ApiKeyService

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `SelfApiKeysResource` + servlet registration

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/SelfApiKeysResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/SelfApiKeysResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (add servlet decl + two `<servlet-mapping>` entries, mirroring `AdminApiKeysResource` at lines 634 & 934)

**Interfaces:**
- Consumes: `ApiKeyService.listByPrincipal/findById/generate/revoke` (Task 1), `ApiKeyServiceHolder.get`/`setForTesting`, `RestServletBase` helpers (`sendJson`, `sendError`, `parseJsonBody`, `getJsonString`, `getEngine`, `getSubsystems`), `Wiki.session().find(engine, request)`.
- Produces: endpoints `GET /api/self/apikeys`, `POST /api/self/apikeys`, `POST /api/self/apikeys/{id}/rotate`, `DELETE /api/self/apikeys/{id}`. JSON key row shape: `{ id:int, label:string|null, scope:"mcp"|"tools"|"all", createdAt:iso|null, lastUsedAt:iso|null }`; generate/rotate add `token:string`.
- Test seam: package-visible `String authenticatedLogin(HttpServletRequest)` (override in tests to inject login or null); service via `ApiKeyServiceHolder.setForTesting(mock)`.

- [ ] **Step 1: Write the failing unit test**

Create `wikantik-rest/src/test/java/com/wikantik/rest/SelfApiKeysResourceTest.java`:

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
package com.wikantik.rest;

import com.wikantik.TestEngine;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfApiKeysResourceTest {

    private TestEngine engine;
    private ApiKeyService mockService;
    private String stubLogin;            // null => anonymous

    private SelfApiKeysResource newServlet() throws Exception {
        final SelfApiKeysResource servlet = new SelfApiKeysResource() {
            @Override
            String authenticatedLogin( final HttpServletRequest request ) {
                return stubLogin;
            }
        };
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
        return servlet;
    }

    private static ApiKeyService.Record rec( final int id, final String principal,
            final String label, final ApiKeyService.Scope scope ) {
        return new ApiKeyService.Record( id, "hash" + id, principal, label, scope,
                Instant.parse( "2026-04-01T10:00:00Z" ), "admin", null, null, null );
    }

    private static HttpServletResponse mockResponse( final StringWriter sw ) throws Exception {
        final HttpServletResponse response = mock( HttpServletResponse.class );
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        return response;
    }

    @BeforeEach
    void setUp() {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        mockService = mock( ApiKeyService.class );
        ApiKeyServiceHolder.setForTesting( mockService );
        stubLogin = "alice";
    }

    @AfterEach
    void tearDown() {
        ApiKeyServiceHolder.setForTesting( null );
        if ( engine != null ) engine.stop();
    }

    @Test
    void getListsOnlyCallersKeysAndNeverHashOrPrincipal() throws Exception {
        when( mockService.listByPrincipal( "alice" ) )
                .thenReturn( List.of( rec( 1, "alice", "laptop", ApiKeyService.Scope.TOOLS ) ) );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( null );
        final StringWriter sw = new StringWriter();

        servlet.doGet( request, mockResponse( sw ) );

        verify( mockService ).listByPrincipal( "alice" );
        final String json = sw.toString();
        assertTrue( json.contains( "\"label\":\"laptop\"" ) );
        assertTrue( json.contains( "\"scope\":\"tools\"" ) );
        assertFalse( json.contains( "hash" ), "key_hash must never be serialized" );
    }

    @Test
    void getRejectsAnonymousWith401() throws Exception {
        stubLogin = null;
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( null );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doGet( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_UNAUTHORIZED );
        verify( mockService, never() ).listByPrincipal( anyString() );
    }

    @Test
    void postGeneratesKeyBoundToCallerAndReturnsTokenOnce() throws Exception {
        final ApiKeyService.Record r = rec( 7, "alice", "ci", ApiKeyService.Scope.MCP );
        when( mockService.generate( eq( "alice" ), eq( "ci" ), eq( ApiKeyService.Scope.MCP ), eq( "alice" ) ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_SECRET", r ) );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( null );
        when( request.getReader() ).thenReturn( new java.io.BufferedReader(
                new java.io.StringReader( "{\"label\":\"ci\",\"scope\":\"mcp\"}" ) ) );
        final StringWriter sw = new StringWriter();

        servlet.doPost( request, mockResponse( sw ) );

        verify( mockService ).generate( "alice", "ci", ApiKeyService.Scope.MCP, "alice" );
        assertTrue( sw.toString().contains( "wkk_SECRET" ) );
    }

    @Test
    void rotateRevokesOwnKeyThenIssuesReplacement() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "alice", "ci", ApiKeyService.Scope.MCP ) ) );
        when( mockService.revoke( 7, "alice" ) ).thenReturn( true );
        when( mockService.generate( "alice", "ci", ApiKeyService.Scope.MCP, "alice" ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_NEW", rec( 8, "alice", "ci", ApiKeyService.Scope.MCP ) ) );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/7/rotate" );
        final StringWriter sw = new StringWriter();

        servlet.doPost( request, mockResponse( sw ) );

        verify( mockService ).revoke( 7, "alice" );
        verify( mockService ).generate( "alice", "ci", ApiKeyService.Scope.MCP, "alice" );
        assertTrue( sw.toString().contains( "wkk_NEW" ) );
    }

    @Test
    void rotateOfAnotherUsersKeyReturns404AndDoesNotRevoke() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "bob", "ci", ApiKeyService.Scope.MCP ) ) );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/7/rotate" );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void deleteRevokesOwnKey() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "alice", "ci", ApiKeyService.Scope.MCP ) ) );
        when( mockService.revoke( 7, "alice" ) ).thenReturn( true );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/7" );
        final StringWriter sw = new StringWriter();

        servlet.doDelete( request, mockResponse( sw ) );

        verify( mockService ).revoke( 7, "alice" );
        assertTrue( sw.toString().contains( "\"success\":true" ) );
    }

    @Test
    void deleteOfAnotherUsersKeyReturns404() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "bob", "ci", ApiKeyService.Scope.MCP ) ) );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/7" );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doDelete( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
    }

    @Test
    void postWithInvalidScopeReturns400() throws Exception {
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( null );
        when( request.getReader() ).thenReturn( new java.io.BufferedReader(
                new java.io.StringReader( "{\"label\":\"x\",\"scope\":\"bogus\"}" ) ) );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=SelfApiKeysResourceTest`
Expected: FAIL — compile error `cannot find symbol: class SelfApiKeysResource`.

- [ ] **Step 3: Implement `SelfApiKeysResource`**

Create `wikantik-rest/src/main/java/com/wikantik/rest/SelfApiKeysResource.java`:

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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.wikantik.api.Wiki;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Self-service REST resource: a logged-in user manages API keys bound to their OWN
 * principal. Rides the {@code /api/*} filter chain; every operation is scoped to the
 * caller's login and ownership is enforced server-side.
 *
 * <ul>
 *   <li>{@code GET    /api/self/apikeys}            — caller's active keys (metadata only)</li>
 *   <li>{@code POST   /api/self/apikeys}            — generate {label, scope}; token shown once</li>
 *   <li>{@code POST   /api/self/apikeys/{id}/rotate}— revoke + reissue (same label/scope)</li>
 *   <li>{@code DELETE /api/self/apikeys/{id}}       — revoke</li>
 * </ul>
 */
public class SelfApiKeysResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SelfApiKeysResource.class );

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private ApiKeyService service() {
        return ApiKeyServiceHolder.get( getSubsystems().core().properties().asProperties() );
    }

    /**
     * Returns the caller's login name if authenticated, else {@code null}. Package-visible
     * so unit tests can inject an identity without a live session.
     */
    String authenticatedLogin( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        return session.isAuthenticated() ? session.getLoginPrincipal().getName() : null;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String login = authenticatedLogin( request );
        if ( login == null ) { unauthorized( response ); return; }
        final ApiKeyService svc = service();
        if ( svc == null ) { unavailable( response ); return; }

        final List< Map< String, Object > > rows = new ArrayList<>();
        for ( final ApiKeyService.Record r : svc.listByPrincipal( login ) ) {
            rows.add( selfRow( r ) );
        }
        sendJson( response, Map.of( "keys", rows ) );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String login = authenticatedLogin( request );
        if ( login == null ) { unauthorized( response ); return; }
        final ApiKeyService svc = service();
        if ( svc == null ) { unavailable( response ); return; }

        final Integer rotateId = parseRotateId( request.getPathInfo() );
        if ( rotateId != null ) {
            rotate( svc, login, rotateId, response );
            return;
        }
        if ( request.getPathInfo() != null && !"/".equals( request.getPathInfo() ) ) {
            sendNotFound( response, "Not found" );
            return;
        }
        generate( request, svc, login, response );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String login = authenticatedLogin( request );
        if ( login == null ) { unauthorized( response ); return; }
        final ApiKeyService svc = service();
        if ( svc == null ) { unavailable( response ); return; }

        final Integer id = parseLeadingId( request.getPathInfo() );
        if ( id == null ) { sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Key id required in path" ); return; }

        // Ownership gate: unknown OR not-owned OR already-revoked all collapse to 404 (no oracle).
        final Optional< ApiKeyService.Record > rec = svc.findById( id );
        if ( rec.isEmpty() || !login.equals( rec.get().principalLogin() ) || !rec.get().isActive() ) {
            sendNotFound( response, "Key not found: " + id );
            return;
        }
        if ( !svc.revoke( id, login ) ) {
            sendNotFound( response, "Key not found or already revoked: " + id );
            return;
        }
        audit( "apikey.revoke", id, rec.get().label(), login );
        LOG.info( "Self API key revoked: id={}, by={}", id, login );
        sendJson( response, Map.of( "success", true, "id", id ) );
    }

    private void generate( final HttpServletRequest request, final ApiKeyService svc,
            final String login, final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;                    // parseJsonBody already sent 400
        final String label = getJsonString( body, "label" );
        final ApiKeyService.Scope scope;
        try {
            scope = ApiKeyService.Scope.fromWire( getJsonString( body, "scope" ) );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid scope — must be one of mcp, tools, all" );
            return;
        }
        try {
            final ApiKeyService.Generated g = svc.generate( login, label, scope, login );
            audit( "apikey.issue", g.record().id(), g.record().label(), login );
            LOG.info( "Self API key generated: id={}, by={}, scope={}", g.record().id(), login, scope.wire() );
            respondWithToken( response, g );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final IllegalStateException e ) {
            LOG.error( "Self API key generation failed for {}: {}", login, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Key generation failed" );
        }
    }

    private void rotate( final ApiKeyService svc, final String login, final int id,
            final HttpServletResponse response ) throws IOException {
        final Optional< ApiKeyService.Record > rec = svc.findById( id );
        if ( rec.isEmpty() || !login.equals( rec.get().principalLogin() ) || !rec.get().isActive() ) {
            sendNotFound( response, "Key not found: " + id );
            return;
        }
        final ApiKeyService.Record old = rec.get();
        if ( !svc.revoke( id, login ) ) {
            sendNotFound( response, "Key not found or already revoked: " + id );
            return;
        }
        try {
            final ApiKeyService.Generated g = svc.generate( login, old.label(), old.scope(), login );
            audit( "apikey.rotate", g.record().id(), g.record().label(), login );
            LOG.info( "Self API key rotated: old={}, new={}, by={}", id, g.record().id(), login );
            respondWithToken( response, g );
        } catch ( final IllegalStateException e ) {
            LOG.error( "Self API key rotate (reissue) failed for {}: {}", login, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Key rotation failed" );
        }
    }

    private void respondWithToken( final HttpServletResponse response, final ApiKeyService.Generated g )
            throws IOException {
        final Map< String, Object > payload = new LinkedHashMap<>( selfRow( g.record() ) );
        payload.put( "token", g.plaintext() );
        response.setStatus( HttpServletResponse.SC_CREATED );
        sendJson( response, payload );
    }

    /** Metadata-only row: never the hash, never the plaintext, never the principal (always self). */
    private static Map< String, Object > selfRow( final ApiKeyService.Record r ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", r.id() );
        m.put( "label", r.label() );
        m.put( "scope", r.scope().wire() );
        m.put( "createdAt", toIso( r.createdAt() ) );
        m.put( "lastUsedAt", toIso( r.lastUsedAt() ) );
        return m;
    }

    private void audit( final String eventType, final int keyId, final String label, final String actor ) {
        try {
            final AuditService a = getEngine() instanceof com.wikantik.WikiEngine we ? we.getAuditService() : null;
            if ( a == null ) return;
            a.record( AuditEntry.builder()
                    .eventTime( Instant.now() )
                    .category( AuditCategory.ADMIN )
                    .eventType( eventType )
                    .outcome( AuditOutcome.SUCCESS )
                    .actorPrincipal( actor )
                    .actorType( "user" )
                    .targetType( "apikey" )
                    .targetId( String.valueOf( keyId ) )
                    .targetLabel( label != null ? label : String.valueOf( keyId ) )
                    .build() );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to record audit entry for {} (key {}): {}", eventType, keyId, e.getMessage(), e );
        }
    }

    /** Returns the id from a "/{id}/rotate" pathInfo, or null if it isn't a rotate path. */
    private static Integer parseRotateId( final String pathInfo ) {
        if ( pathInfo == null ) return null;
        final String[] parts = pathInfo.split( "/" );   // "/7/rotate" -> ["", "7", "rotate"]
        if ( parts.length == 3 && "rotate".equals( parts[2] ) ) {
            try { return Integer.valueOf( parts[1] ); } catch ( final NumberFormatException e ) { return null; }
        }
        return null;
    }

    /** Returns the leading numeric id from a "/{id}" pathInfo, or null. */
    private static Integer parseLeadingId( final String pathInfo ) {
        if ( pathInfo == null ) return null;
        final String[] parts = pathInfo.split( "/" );   // "/7" -> ["", "7"]
        if ( parts.length == 2 && !parts[1].isBlank() ) {
            try { return Integer.valueOf( parts[1] ); } catch ( final NumberFormatException e ) { return null; }
        }
        return null;
    }

    private static String toIso( final Instant i ) {
        return i != null ? i.toString() : null;
    }

    private void unauthorized( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
    }

    private void unavailable( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "API key service unavailable — no datasource configured" );
    }
}
```

- [ ] **Step 4: Register the servlet in web.xml**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, add a servlet declaration next to the other resource servlets (near the `AdminApiKeysResource` declaration ~line 634):

```xml
   <servlet>
       <servlet-name>SelfApiKeysResource</servlet-name>
       <servlet-class>com.wikantik.rest.SelfApiKeysResource</servlet-class>
   </servlet>
```

And two servlet-mappings next to the `AdminApiKeysResource` mappings (~line 934), mirroring the collection + id-path pair:

```xml
   <servlet-mapping>
       <servlet-name>SelfApiKeysResource</servlet-name>
       <url-pattern>/api/self/apikeys/*</url-pattern>
   </servlet-mapping>
   <servlet-mapping>
       <servlet-name>SelfApiKeysResource</servlet-name>
       <url-pattern>/api/self/apikeys</url-pattern>
   </servlet-mapping>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=SelfApiKeysResourceTest`
Expected: PASS — `Tests run: 8, Failures: 0, Errors: 0`.
(Note: `mvn test -pl wikantik-rest` resolves `wikantik-main` from `~/.m2`; if Task 1's `ApiKeyService` change isn't installed yet, run `mvn install -pl wikantik-main -DskipTests -q` first.)

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/SelfApiKeysResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/SelfApiKeysResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(apikeys): self-service /api/self/apikeys resource (list/generate/rotate/revoke)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Wire-level IT — mint via `/api/self/apikeys`, authenticate against `/knowledge-mcp`

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/SelfApiKeyLifecycleIT.java`

**Interfaces:**
- Consumes: the running Cargo Tomcat + the IT module's existing login/seed helpers (`RestSeedHelper`, base URL constants — read a sibling IT such as `AdminApiKeysBulkActionIT.java` in the same package for the exact helper names, login flow, and `awaitAdminReady` usage).
- Produces: end-to-end proof that a self-minted key authenticates a downstream MCP call.

- [ ] **Step 1: Read the sibling IT for the harness pattern**

Read `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AdminApiKeysBulkActionIT.java` in full to copy: base-URL constant, the authenticated-session helper (cookie jar / login POST), `RestSeedHelper.awaitAdminReady(...)`, and the JSON client used. Use the same imports and superclass.

- [ ] **Step 2: Write the IT**

Create `SelfApiKeyLifecycleIT.java` implementing this scenario (use the harness helpers discovered in Step 1; the assertions below are the contract):

```
1. Establish an authenticated browser-style session (cookie auth) as the seeded
   admin via the IT login helper; call RestSeedHelper.awaitAdminReady(...) first
   to avoid the first-call login race.
2. POST {base}/api/self/apikeys  body {"label":"it-key","scope":"all"}
     → assert HTTP 201, response JSON has a non-blank "token" starting with "wkk_",
       and the "scope" is "all".
3. Take the token. Open a Streamable-HTTP MCP session against {base}/knowledge-mcp:
       - POST initialize  with header  Authorization: Bearer <token>
         → assert HTTP 200 and capture the Mcp-Session-Id response header.
       - POST notifications/initialized  (same bearer + Mcp-Session-Id)
       - POST tools/list  (same bearer + Mcp-Session-Id)
         → assert HTTP 200 and the SSE/JSON body lists at least one tool.
   (This proves the self-minted key installs the caller's principal and passes
    KnowledgeMcpAccessFilter.)
4. GET {base}/api/self/apikeys  (authenticated session)
     → assert exactly one active key with label "it-key" and NO "keyHash"/"token"
       field present in any row.
5. DELETE {base}/api/self/apikeys/{id}  → assert HTTP 200 {"success":true}.
6. Re-issue an MCP initialize with the now-revoked bearer
     → assert HTTP 401/403 (revocation takes effect within the verify-cache TTL;
       if the harness needs it, allow up to ~2s and one retry).
```

Mirror the request/SSE plumbing from any existing MCP IT (search the IT modules for `Mcp-Session-Id` to find one). Keep the bearer-token MCP calls on a plain HTTP client (no cookie jar) so they exercise the key, not the session.

- [ ] **Step 3: Run the IT**

Run (sequential — no `-T`): `mvn clean install -pl wikantik-it-tests/wikantik-it-test-rest -am -Pintegration-tests -Dtest=SelfApiKeyLifecycleIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the IT passes (Cargo boots Tomcat against the pgvector container; the key mints and authenticates, then fails closed after revoke).

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/SelfApiKeyLifecycleIT.java
git commit -m "test(apikeys): IT — self-minted key authenticates MCP then fails closed after revoke

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Frontend plumbing — shared reveal modal + `api.self` client methods

**Files:**
- Create: `wikantik-frontend/src/components/apikeys/RevealedTokenModal.jsx` (extracted from the admin page)
- Modify: `wikantik-frontend/src/components/admin/AdminApiKeysPage.jsx` (remove the inline `RevealedTokenModal`, import the shared one)
- Modify: `wikantik-frontend/src/api/client.js` (add a `self` object)

**Interfaces:**
- Produces: `RevealedTokenModal({ token, record, onClose })` default export (unchanged behavior); `api.self.listApiKeys()`, `api.self.createApiKey({label, scope})`, `api.self.rotateApiKey(id)`, `api.self.revokeApiKey(id)`.
- Consumes: existing `request(...)` helper in `client.js`.

- [ ] **Step 1: Extract `RevealedTokenModal` to a shared file**

Create `wikantik-frontend/src/components/apikeys/RevealedTokenModal.jsx` containing the exact `RevealedTokenModal` function currently defined inline in `AdminApiKeysPage.jsx` (the `function RevealedTokenModal({ token, record, onClose }) { ... }` block), prefixed with `import { useState } from 'react';` and suffixed with `export default RevealedTokenModal;`. Copy it verbatim — it already renders `record.principalLogin` and optional `record.label`, both valid for the self case (principalLogin === the current user).

- [ ] **Step 2: Point the admin page at the shared modal**

In `AdminApiKeysPage.jsx`: delete the inline `function RevealedTokenModal(...) {...}` definition, and add near the top imports:

```jsx
import RevealedTokenModal from '../apikeys/RevealedTokenModal';
```

- [ ] **Step 3: Verify the admin page test still passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminApiKeysPage.test.jsx`
Expected: PASS (unchanged behavior — the modal is identical, only its location moved).

- [ ] **Step 4: Add `api.self` client methods**

In `wikantik-frontend/src/api/client.js`, add a `self` object as a sibling of the existing `admin:` block (top level of the `api` object):

```js
  self: {
    listApiKeys: () => request('/api/self/apikeys'),
    createApiKey: (data) =>
      request('/api/self/apikeys', { method: 'POST', body: JSON.stringify(data) }),
    rotateApiKey: (id) =>
      request(`/api/self/apikeys/${id}/rotate`, { method: 'POST' }),
    revokeApiKey: (id) =>
      request(`/api/self/apikeys/${id}`, { method: 'DELETE' }),
  },
```

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/apikeys/RevealedTokenModal.jsx \
        wikantik-frontend/src/components/admin/AdminApiKeysPage.jsx \
        wikantik-frontend/src/api/client.js
git commit -m "refactor(apikeys): share RevealedTokenModal + add api.self client methods

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: `MyApiKeys` section in `UserPreferencesPage`

**Files:**
- Create: `wikantik-frontend/src/components/MyApiKeys.jsx`
- Create: `wikantik-frontend/src/components/MyApiKeys.test.jsx`
- Modify: `wikantik-frontend/src/components/UserPreferencesPage.jsx` (render `<MyApiKeys />` as a new section)

**Interfaces:**
- Consumes: `api.self.*` (Task 4), `RevealedTokenModal` (Task 4).
- Produces: a self-contained `MyApiKeys` default-export component.

- [ ] **Step 1: Write the failing component test**

Create `wikantik-frontend/src/components/MyApiKeys.test.jsx`:

```jsx
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import MyApiKeys from './MyApiKeys';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: { self: {
    listApiKeys: vi.fn(),
    createApiKey: vi.fn(),
    rotateApiKey: vi.fn(),
    revokeApiKey: vi.fn(),
  } },
}));

const KEYS = [
  { id: 1, label: 'laptop', scope: 'tools', createdAt: '2026-06-01T10:00:00Z', lastUsedAt: '2026-06-18T14:40:00Z' },
  { id: 2, label: null, scope: 'mcp', createdAt: '2026-06-02T10:00:00Z', lastUsedAt: null },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.self.listApiKeys.mockResolvedValue({ keys: KEYS });
});

describe('MyApiKeys', () => {
  it('lists the user’s keys', async () => {
    render(<MyApiKeys />);
    await screen.findByText('laptop');
    expect(screen.getByText('tools')).toBeInTheDocument();
  });

  it('generates a key and reveals the token once', async () => {
    api.self.createApiKey.mockResolvedValue({ id: 3, label: 'ci', scope: 'all', token: 'wkk_SECRET' });
    render(<MyApiKeys />);
    await screen.findByText('laptop');

    // Toolbar "+ New key" opens the form; the form's "Generate key" submit does the work.
    fireEvent.click(screen.getByRole('button', { name: /new key/i }));
    fireEvent.click(screen.getByRole('button', { name: /generate key/i }));
    // Reveal modal shows the token once.
    expect(await screen.findByText('wkk_SECRET')).toBeInTheDocument();
    expect(api.self.createApiKey).toHaveBeenCalled();
  });

  it('revokes a key after confirmation', async () => {
    api.self.revokeApiKey.mockResolvedValue({ success: true, id: 1 });
    render(<MyApiKeys />);
    await screen.findByText('laptop');

    // The row's "Revoke" opens a confirm whose primary button is "Revoke key".
    const row = screen.getByText('laptop').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /revoke/i }));
    fireEvent.click(await screen.findByRole('button', { name: /revoke key/i }));
    await waitFor(() => expect(api.self.revokeApiKey).toHaveBeenCalledWith(1));
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/MyApiKeys.test.jsx`
Expected: FAIL — `Failed to resolve import "./MyApiKeys"`.

- [ ] **Step 3: Implement `MyApiKeys.jsx`**

Create `wikantik-frontend/src/components/MyApiKeys.jsx`. Build a self-contained section: a heading + explainer, a "Generate key" button opening a small form (label + scope select: `tools`/`mcp`/`all`), a table of active keys (Label / Scope / Created / Last used / actions Rotate + Revoke), the shared `RevealedTokenModal` on the create/rotate response, and an inline confirm before revoke. Reuse existing CSS classes (`btn`, `btn-primary`, `btn-ghost`, `modal-overlay`, `modal-content`) for visual consistency with the admin page. Match the markup the test asserts: a `Generate key` button, a `<tr>` per key whose row contains a `Revoke` button, and a confirm dialog whose primary button is labelled exactly `Revoke`. Concretely:

```jsx
import { useState, useEffect } from 'react';
import { api } from '../api/client';
import RevealedTokenModal from './apikeys/RevealedTokenModal';

const SCOPES = ['tools', 'mcp', 'all'];

export default function MyApiKeys() {
  const [keys, setKeys] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState({ label: '', scope: 'tools' });
  const [busy, setBusy] = useState(false);
  const [revealed, setRevealed] = useState(null);          // { token, record }
  const [confirmRevoke, setConfirmRevoke] = useState(null); // key row

  const load = async () => {
    setLoading(true);
    try {
      const data = await api.self.listApiKeys();
      setKeys(data.keys || []);
      setError(null);
    } catch (err) {
      setError(err.message || 'Failed to load API keys');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const generate = async (e) => {
    e?.preventDefault();
    setBusy(true);
    try {
      const result = await api.self.createApiKey({ label: form.label || null, scope: form.scope });
      // result carries the metadata row + plaintext token; principalLogin shown in the
      // reveal modal — set it to the current key's account label for display.
      setRevealed({ token: result.token, record: { ...result, principalLogin: 'you' } });
      setFormOpen(false);
      setForm({ label: '', scope: 'tools' });
      await load();
    } catch (err) {
      setError(err.message || 'Failed to generate key');
    } finally {
      setBusy(false);
    }
  };

  const rotate = async (id) => {
    setBusy(true);
    try {
      const result = await api.self.rotateApiKey(id);
      setRevealed({ token: result.token, record: { ...result, principalLogin: 'you' } });
      await load();
    } catch (err) {
      setError(err.message || 'Failed to rotate key');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (id) => {
    setBusy(true);
    try {
      await api.self.revokeApiKey(id);
      setConfirmRevoke(null);
      await load();
    } catch (err) {
      setError(err.message || 'Failed to revoke key');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section style={{ marginTop: 'var(--space-xl)' }}>
      <h2 style={{ fontFamily: 'var(--font-display)' }}>API Keys</h2>
      <p>
        Keys are bound to your account and act with <strong>your</strong> permissions.
        Secrets are shown once — store them somewhere safe.
      </p>
      {error && <div className="error-banner" role="alert">{error}</div>}

      <button className="btn btn-primary" onClick={() => setFormOpen(true)} disabled={busy}>
        + New key
      </button>

      {loading ? (
        <p>Loading…</p>
      ) : keys.length === 0 ? (
        <p>You have no active API keys.</p>
      ) : (
        <table className="data-table" style={{ marginTop: 'var(--space-md)', width: '100%' }}>
          <thead>
            <tr><th>Label</th><th>Scope</th><th>Created</th><th>Last used</th><th></th></tr>
          </thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id}>
                <td>{k.label || <em>—</em>}</td>
                <td><code>{k.scope}</code></td>
                <td>{fmt(k.createdAt)}</td>
                <td>{fmt(k.lastUsedAt)}</td>
                <td>
                  <button className="btn btn-ghost" onClick={() => rotate(k.id)} disabled={busy}>Rotate</button>
                  <button className="btn btn-ghost" onClick={() => setConfirmRevoke(k)} disabled={busy}>Revoke</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {formOpen && (
        <div className="modal-overlay" onClick={() => setFormOpen(false)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h3>Generate API key</h3>
            <form onSubmit={generate}>
              <label>Label
                <input value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })}
                       placeholder="e.g. laptop, ci-bot" />
              </label>
              <label>Scope
                <select value={form.scope} onChange={(e) => setForm({ ...form, scope: e.target.value })}>
                  {SCOPES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </label>
              <div className="modal-actions">
                <button type="button" className="btn btn-ghost" onClick={() => setFormOpen(false)}>Cancel</button>
                <button type="submit" className="btn btn-primary" disabled={busy}>Generate key</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {revealed && (
        <RevealedTokenModal token={revealed.token} record={revealed.record} onClose={() => setRevealed(null)} />
      )}

      {confirmRevoke && (
        <div className="modal-overlay" onClick={() => setConfirmRevoke(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h3>Revoke this key?</h3>
            <p>Any client using this key will immediately start receiving HTTP 403.</p>
            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={() => setConfirmRevoke(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={() => revoke(confirmRevoke.id)} disabled={busy}>Revoke key</button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function fmt(iso) {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleDateString(); } catch { return iso; }
}
```

Label discipline the test relies on (keep these exact, distinct strings): the toolbar button is **`+ New key`** (matches `/new key/i`, opens the form); the form submit is **`Generate key`** (matches `/generate key/i`, does the work); the row action is **`Revoke`** and the confirm-dialog primary is **`Revoke key`** (so `/revoke key/i` targets only the confirm, never the row button). `Rotate` is a direct action (no confirm).

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/MyApiKeys.test.jsx`
Expected: PASS — 3 tests green.

- [ ] **Step 5: Mount the section in `UserPreferencesPage`**

In `wikantik-frontend/src/components/UserPreferencesPage.jsx`, add the import at the top:

```jsx
import MyApiKeys from './MyApiKeys';
```

and render `<MyApiKeys />` near the end of the page body, after the password-change section and before the delete-account section (so destructive actions stay last). Place it inside the same content container the other sections use.

- [ ] **Step 6: Run the preferences page test to confirm no regression**

Run: `cd wikantik-frontend && npx vitest run src/components/UserPreferencesPage.test.jsx`
Expected: PASS (the new section mounts; mock `api.self.listApiKeys` if the existing test's `api` mock is strict — if the test fails because `api.self` is undefined in its mock, extend that test's `vi.mock` for `client` to include a `self.listApiKeys` returning `{ keys: [] }`).

- [ ] **Step 7: Commit**

```bash
git add wikantik-frontend/src/components/MyApiKeys.jsx \
        wikantik-frontend/src/components/MyApiKeys.test.jsx \
        wikantik-frontend/src/components/UserPreferencesPage.jsx \
        wikantik-frontend/src/components/UserPreferencesPage.test.jsx
git commit -m "feat(apikeys): self-service 'API Keys' section in user preferences

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Final Verification (after all tasks)

- [ ] **Unit + module build:** `mvn clean install -T 1C -DskipITs` → BUILD SUCCESS.
- [ ] **Frontend:** `cd wikantik-frontend && npx vitest run` → all green.
- [ ] **Full IT reactor (gate):** `mvn clean install -Pintegration-tests -fae` → all green (includes `SelfApiKeyLifecycleIT`).
- [ ] **Live smoke (localhost):** `bin/deploy-local.sh` (no migration this time, but redeploys the WAR), log in as `testbot`, open `/preferences` → API Keys, generate a key, confirm the one-time reveal, use it against `/knowledge-mcp`, then revoke it.
```
