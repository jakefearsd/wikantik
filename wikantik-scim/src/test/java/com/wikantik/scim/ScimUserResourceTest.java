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
package com.wikantik.scim;

import com.wikantik.WikiEngine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScimUserResource} covering error/edge branches not
 * exercised by the integration tests.
 */
class ScimUserResourceTest {

    private ScimUserResource resource;
    private WikiEngine mockEngine;
    private UserManager mockUserManager;
    private UserDatabase mockDb;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter sw;

    @BeforeEach
    void setUp() throws Exception {
        resource = new ScimUserResource();

        mockEngine = mock( WikiEngine.class );
        mockUserManager = mock( UserManager.class );
        mockDb = mock( UserDatabase.class );

        when( mockEngine.getManager( UserManager.class ) ).thenReturn( mockUserManager );
        when( mockUserManager.getUserDatabase() ).thenReturn( mockDb );
        // AuditService returns null — auditRecord() guards on null, so it's a no-op
        when( mockEngine.getAuditService() ).thenReturn( null );

        // Inject engine via reflection
        final Field f = ScimUserResource.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( resource, mockEngine );

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        // Default: no path info (collection endpoint)
        when( req.getPathInfo() ).thenReturn( null );
        when( req.getRequestURL() ).thenReturn( new StringBuffer( "http://localhost/scim/v2/Users" ) );
    }

    // -----------------------------------------------------------------------
    // POST /Users — create
    // -----------------------------------------------------------------------

    @Test
    void createMissingUserName_returns400() throws Exception {
        final String body = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 400 );
        final String out = sw.toString();
        assertTrue( out.contains( "invalidValue" ), "body must contain scimType 'invalidValue'" );
        assertTrue( out.contains( "userName is required" ), "body must describe the problem" );
    }

    @Test
    void createDbUnavailable_returns503() throws Exception {
        // Override: db unavailable
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        final String body = "{\"userName\":\"alice\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void createNonSsoLocalAccountAlreadyExists_returns409() throws Exception {
        final UserProfile existing = mock( UserProfile.class );
        // getAttributes() returns null → linked == null → non-SSO account
        when( existing.getAttributes() ).thenReturn( null );
        when( mockDb.findByLoginName( "bob" ) ).thenReturn( existing );

        final String body = "{\"userName\":\"bob\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 409 );
        final String out = sw.toString();
        assertTrue( out.contains( "uniqueness" ) );
        assertTrue( out.contains( "not SSO-linked" ) );
    }

    @Test
    void createSsoLinkedAccountAlreadyExists_returns409() throws Exception {
        // Account exists and IS SSO-linked → second 409 branch
        final java.util.Map<String, java.io.Serializable> attrs = new java.util.HashMap<>();
        attrs.put( "sso.subject", "ext-sub-123" );
        final UserProfile existing = mock( UserProfile.class );
        when( existing.getAttributes() ).thenReturn( attrs );
        when( mockDb.findByLoginName( "carol" ) ).thenReturn( existing );

        final String body = "{\"userName\":\"carol\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 409 );
        assertTrue( sw.toString().contains( "uniqueness" ) );
    }

    @Test
    void createMalformedJson_returns400InvalidSyntax() throws Exception {
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "not-json{{" ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidSyntax" ) );
    }

    @Test
    void createSuccess_returns201() throws Exception {
        // newProfile() returns a mock that behaves like a fresh profile
        final UserProfile newProfile = mock( UserProfile.class );
        final java.util.Map<String, java.io.Serializable> profileAttrs = new java.util.HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );

        // After save, findByLoginName returns the saved profile (second call returns saved)
        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-dave" );
        when( saved.getLoginName() ).thenReturn( "dave" );
        when( saved.getAttributes() ).thenReturn( new java.util.HashMap<>() );
        // Use doThrow/doReturn to avoid calling the already-stubbed method during setup
        doThrow( new NoSuchPrincipalException( "dave" ) )
                .doReturn( saved )
                .when( mockDb ).findByLoginName( "dave" );

        final String body = "{\"userName\":\"dave\",\"displayName\":\"Dave User\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
    }

    // -----------------------------------------------------------------------
    // GET /Users/{id} — getById
    // -----------------------------------------------------------------------

    @Test
    void getByIdMissingUser_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/no-such-uid" );
        when( mockDb.findByUid( "no-such-uid" ) )
                .thenThrow( new NoSuchPrincipalException( "no-such-uid" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "User not found" ) );
    }

    @Test
    void getByIdDbUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/some-uid" );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    // -----------------------------------------------------------------------
    // GET /Users — list
    // -----------------------------------------------------------------------

    @Test
    void listDbUnavailable_returns503() throws Exception {
        when( mockUserManager.getUserDatabase() ).thenReturn( null );
        when( req.getParameter( "filter" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void listUnsupportedFilterAttribute_returns400() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "email eq \"foo@bar.com\"" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidFilter" ) );
    }

    @Test
    void listUnsupportedFilterSyntax_returns400() throws Exception {
        // ScimFilterParser.UnsupportedFilterException for unrecognized filter syntax
        when( req.getParameter( "filter" ) ).thenReturn( "pr email" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidFilter" ) );
    }

    // -----------------------------------------------------------------------
    // PUT /Users — replace
    // -----------------------------------------------------------------------

    @Test
    void putNoIdInPath_returns400() throws Exception {
        // pathInfo is null → extractId returns null
        resource.doPut( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "PUT requires a user id" ) );
    }

    @Test
    void putMissingUser_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-missing" );
        when( mockDb.findByUid( "uid-missing" ) )
                .thenThrow( new NoSuchPrincipalException( "uid-missing" ) );

        final String body = "{\"userName\":\"whoever\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "User not found" ) );
    }

    @Test
    void putDbUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/some-uid" );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    // -----------------------------------------------------------------------
    // PATCH /Users — partial update (via service())
    // -----------------------------------------------------------------------

    @Test
    void patchNoIdInPath_returns400() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        // pathInfo null → no id

        resource.service( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "PATCH requires a user id" ) );
    }

    @Test
    void patchMissingUser_returns404() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-gone" );
        when( mockDb.findByUid( "uid-gone" ) )
                .thenThrow( new NoSuchPrincipalException( "uid-gone" ) );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "User not found" ) );
    }

    @Test
    void patchDbUnavailable_returns503() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/some-uid" );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        resource.service( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void patchUnsupportedPath_returns400InvalidPath() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-ok" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "frank" );
        when( mockDb.findByUid( "uid-ok" ) ).thenReturn( p );

        // ScimPatchApplier throws UnsupportedPatchException for complex value-path filters
        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"].value\",\"value\":\"x@y.com\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidPath" ) );
    }

    @Test
    void patchMissingOperationsArray_returns400InvalidPath() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-ok" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "grace" );
        when( mockDb.findByUid( "uid-ok" ) ).thenReturn( p );

        // No Operations array → UnsupportedPatchException
        final String body = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidPath" ) );
    }

    // -----------------------------------------------------------------------
    // DELETE /Users — deactivate
    // -----------------------------------------------------------------------

    @Test
    void deleteNoIdInPath_returns400() throws Exception {
        resource.doDelete( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "DELETE requires a user id" ) );
    }

    @Test
    void deleteMissingUser_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-gone" );
        when( mockDb.findByUid( "uid-gone" ) )
                .thenThrow( new NoSuchPrincipalException( "uid-gone" ) );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "User not found" ) );
    }

    @Test
    void deleteDbUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/some-uid" );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void deleteSuccess_returns204() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-to-del" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "henry" );
        when( p.getAttributes() ).thenReturn( new java.util.HashMap<>() );
        when( mockDb.findByUid( "uid-to-del" ) ).thenReturn( p );
        // UserLifecycleService.deactivate calls db.findByLoginName to reload
        when( mockDb.findByLoginName( "henry" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 204 );
    }

    // -----------------------------------------------------------------------
    // Engine not a WikiEngine → db unavailable → 503
    // -----------------------------------------------------------------------

    @Test
    void engineNotWikiEngine_dbUnavailable_returns503() throws Exception {
        // Override engine with a plain Engine mock (not WikiEngine) so getUserDatabase() returns null
        final com.wikantik.api.core.Engine plainEngine = mock( com.wikantik.api.core.Engine.class );
        final Field f = ScimUserResource.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( resource, plainEngine );

        final String body = "{\"userName\":\"zara\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    // -----------------------------------------------------------------------
    // Active transitions via PUT
    // -----------------------------------------------------------------------

    @Test
    void putDeactivateTransition_callsLifecycle() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-active" );

        // Profile is currently active (not locked)
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "ivan" );
        when( p.isLocked() ).thenReturn( false );  // wasActive = true
        when( p.getAttributes() ).thenReturn( new java.util.HashMap<>() );
        when( mockDb.findByUid( "uid-active" ) ).thenReturn( p );
        // After lifecycle op, reload
        when( mockDb.findByLoginName( "ivan" ) ).thenReturn( p );

        final String body = "{\"userName\":\"ivan\",\"active\":false}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        // No error status — lifecycle runs UserLifecycleService.deactivate internally
        // The response should be 200 (default from mock = 0, not set to error codes)
        verify( resp, never() ).setStatus( 400 );
        verify( resp, never() ).setStatus( 404 );
        verify( resp, never() ).setStatus( 500 );
    }

    @Test
    void putReactivateTransition_callsLifecycle() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-locked" );

        // Profile is locked (inactive)
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "janet" );
        when( p.isLocked() ).thenReturn( true );  // wasActive = false
        when( p.getAttributes() ).thenReturn( new java.util.HashMap<>() );
        when( mockDb.findByUid( "uid-locked" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "janet" ) ).thenReturn( p );

        final String body = "{\"userName\":\"janet\",\"active\":true}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp, never() ).setStatus( 400 );
        verify( resp, never() ).setStatus( 404 );
        verify( resp, never() ).setStatus( 500 );
    }
}
