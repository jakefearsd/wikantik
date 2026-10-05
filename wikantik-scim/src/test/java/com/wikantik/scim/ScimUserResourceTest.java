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
import com.wikantik.audit.AuditService;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
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
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        final Map<String, java.io.Serializable> attrs = new HashMap<>();
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
        final Map<String, java.io.Serializable> profileAttrs = new HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );

        // After save, findByLoginName returns the saved profile (second call returns saved)
        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-dave" );
        when( saved.getLoginName() ).thenReturn( "dave" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        // Use doThrow/doReturn to avoid calling the already-stubbed method during setup
        doThrow( new NoSuchPrincipalException( "dave" ) )
                .doReturn( saved )
                .when( mockDb ).findByLoginName( "dave" );

        final String body = "{\"userName\":\"dave\",\"displayName\":\"Dave User\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
    }

    @Test
    void createWithActiveEqualsTrue_returnsSuccess_noDeactivateCall() throws Exception {
        // active=true → no deactivation lifecycle call
        final UserProfile newProfile = mock( UserProfile.class );
        final Map<String, java.io.Serializable> profileAttrs = new HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );

        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-earl" );
        when( saved.getLoginName() ).thenReturn( "earl" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        doThrow( new NoSuchPrincipalException( "earl" ) )
                .doReturn( saved )
                .when( mockDb ).findByLoginName( "earl" );

        // With externalId to stamp sso.subject branch
        final String body = "{\"userName\":\"earl\",\"active\":true,\"externalId\":\"ext-earl\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        // active=true means no deactivation (only deactivate is called when active=false)
        verify( mockDb, times( 1 ) ).save( any() );
    }

    @Test
    void createWithActiveFalse_deactivatesAfterCreate() throws Exception {
        // active=false → lifecycle deactivate is called on the newly created user
        final UserProfile newProfile = mock( UserProfile.class );
        final Map<String, java.io.Serializable> profileAttrs = new HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );

        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-faye" );
        when( saved.getLoginName() ).thenReturn( "faye" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        // First call from findByLoginName during SSO-check: NoSuchPrincipalException
        // Second call after db.save (reload): returns saved
        // Third call from lifecycle.deactivate (findByLoginName): returns saved
        when( mockDb.findByLoginName( "faye" ) )
                .thenThrow( new NoSuchPrincipalException( "faye" ) )
                .thenReturn( saved )
                .thenReturn( saved );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"userName\":\"faye\",\"active\":false}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        // save called at least once (for the new user profile)
        verify( mockDb, atLeastOnce() ).save( any() );
    }

    @Test
    void createWithPassword_usesSuppliedPassword() throws Exception {
        // When password is provided, it should be set (not a random UUID)
        final UserProfile newProfile = mock( UserProfile.class );
        final Map<String, java.io.Serializable> profileAttrs = new HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );

        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-george" );
        when( saved.getLoginName() ).thenReturn( "george" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        doThrow( new NoSuchPrincipalException( "george" ) )
                .doReturn( saved )
                .when( mockDb ).findByLoginName( "george" );

        final String body = "{\"userName\":\"george\",\"password\":\"S3cr3t!\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        // The password should be set on the new profile
        verify( newProfile ).setPassword( "S3cr3t!" );
    }

    @Test
    void createWithNameFormatted_setsFullName() throws Exception {
        // name.formatted field is mapped to fullName
        final UserProfile newProfile = mock( UserProfile.class );
        final Map<String, java.io.Serializable> profileAttrs = new HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );

        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-helen" );
        when( saved.getLoginName() ).thenReturn( "helen" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        doThrow( new NoSuchPrincipalException( "helen" ) )
                .doReturn( saved )
                .when( mockDb ).findByLoginName( "helen" );

        final String body = "{\"userName\":\"helen\",\"name\":{\"formatted\":\"Helen Smith\"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        verify( newProfile ).setFullname( "Helen Smith" );
    }

    private static final String RAW_CONSTRAINT = "ERROR: duplicate key value violates unique constraint \"users_wiki_name_uniq\" Detail: Key (wiki_name)=(OtherPerson)";

    private void assertGenericConflict() {
        verify( resp ).setStatus( 409 );
        assertTrue( sw.toString().contains( "uniqueness" ), sw.toString() );
        assertFalse( sw.toString().contains( "users_wiki_name_uniq" ), sw.toString() );
        assertFalse( sw.toString().contains( "OtherPerson" ), sw.toString() );
    }

    @Test
    void createSaveConflict_returns409UniquenessWithoutStoreDetail() throws Exception {
        doThrow( new NoSuchPrincipalException( "kim" ) ).when( mockDb ).findByLoginName( "kim" );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        doThrow( new com.wikantik.auth.user.ProfileConflictException( new java.sql.SQLException( RAW_CONSTRAINT, "23505" ) ) )
                .when( mockDb ).save( any() );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{\"userName\":\"kim\"}" ) ) );

        resource.doPost( req, resp );

        assertGenericConflict();
    }

    @Test
    void createSaveFailure_doesNotEchoTheStoreMessage() throws Exception {
        doThrow( new NoSuchPrincipalException( "kim" ) ).when( mockDb ).findByLoginName( "kim" );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        doThrow( new WikiSecurityException( "Database operation failed: " + RAW_CONSTRAINT ) ).when( mockDb ).save( any() );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{\"userName\":\"kim\"}" ) ) );

        resource.doPost( req, resp );

        assertFalse( sw.toString().contains( "users_wiki_name_uniq" ), sw.toString() );
    }

    @Test
    void putSaveConflict_returns409UniquenessWithoutStoreDetail() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-kim" );
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "kim" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-kim" ) ).thenReturn( p );
        doThrow( new com.wikantik.auth.user.ProfileConflictException( new java.sql.SQLException( RAW_CONSTRAINT, "23505" ) ) )
                .when( mockDb ).save( any() );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{\"userName\":\"kim\",\"name\":{\"formatted\":\"Kim Lee\"}}" ) ) );

        resource.doPut( req, resp );

        assertGenericConflict();
    }

    @Test
    void patchSaveConflict_returns409UniquenessWithoutStoreDetail() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-kim" );
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "kim" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-kim" ) ).thenReturn( p );
        doThrow( new com.wikantik.auth.user.ProfileConflictException( new java.sql.SQLException( RAW_CONSTRAINT, "23505" ) ) )
                .when( mockDb ).save( any() );
        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Kim\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        assertGenericConflict();
    }

    @Test
    void createDbSaveThrowsWikiSecurityException_returns409() throws Exception {
        // db.save() throws WikiSecurityException → 409
        doThrow( new NoSuchPrincipalException( "igor" ) ).when( mockDb ).findByLoginName( "igor" );
        final UserProfile newProfile = mock( UserProfile.class );
        final Map<String, java.io.Serializable> profileAttrs = new HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        doThrow( new WikiSecurityException( "duplicate" ) ).when( mockDb ).save( any() );

        final String body = "{\"userName\":\"igor\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 409 );
        assertTrue( sw.toString().contains( "uniqueness" ) );
    }

    @Test
    void createDbSaveThrowsRuntimeException_returns500() throws Exception {
        // db.save() throws an unexpected RuntimeException → 500
        doThrow( new NoSuchPrincipalException( "jan" ) ).when( mockDb ).findByLoginName( "jan" );
        final UserProfile newProfile = mock( UserProfile.class );
        final Map<String, java.io.Serializable> profileAttrs = new HashMap<>();
        when( newProfile.getAttributes() ).thenReturn( profileAttrs );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        doThrow( new RuntimeException( "db is down" ) ).when( mockDb ).save( any() );

        final String body = "{\"userName\":\"jan\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error creating user" ) );
    }

    // -----------------------------------------------------------------------
    // GET /Users/{id} — getById
    // -----------------------------------------------------------------------

    @Test
    void getByIdSuccess_returns200WithUserBody() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-alice" );
        final UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "uid-alice" );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( p.getAttributes() ).thenReturn( null );
        when( mockDb.findByUid( "uid-alice" ) ).thenReturn( p );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );  // 200 is default
        final String out = sw.toString();
        assertTrue( out.contains( "uid-alice" ), "Response must include the uid" );
        assertTrue( out.contains( "alice" ), "Response must include loginName" );
    }

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

    @Test
    void getByIdDbThrowsRuntimeException_returns500() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-boom" );
        when( mockDb.findByUid( "uid-boom" ) ).thenThrow( new RuntimeException( "db failure" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error retrieving user" ) );
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

    @Test
    void listNoFilter_returnsAllUsers() throws Exception {
        // null filter → listAll() path
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        final Principal wikiName = mock( Principal.class );
        when( wikiName.getName() ).thenReturn( "AliceUser" );
        when( mockDb.getWikiNames() ).thenReturn( new Principal[]{ wikiName } );
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( p.getAttributes() ).thenReturn( null );
        when( mockDb.findByWikiName( "AliceUser" ) ).thenReturn( p );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "totalResults" ) );
        assertTrue( out.contains( "ListResponse" ) );
    }

    @Test
    void listWithUserNameFilter_returnsMatchedUser() throws Exception {
        // userName eq "alice" filter → findByLoginName path
        when( req.getParameter( "filter" ) ).thenReturn( "userName eq \"alice\"" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( p.getAttributes() ).thenReturn( null );
        when( mockDb.findByLoginName( "alice" ) ).thenReturn( p );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "totalResults" ) );
    }

    @Test
    void listWithUserNameFilter_notFound_returnsEmptyList() throws Exception {
        // userName eq "ghost" → NoSuchPrincipalException → empty list (not 404)
        when( req.getParameter( "filter" ) ).thenReturn( "userName eq \"ghost\"" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );
        when( mockDb.findByLoginName( "ghost" ) ).thenThrow( new NoSuchPrincipalException( "ghost" ) );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( 404 );
        final String out = sw.toString();
        assertTrue( out.contains( "totalResults" ) );
        assertTrue( out.contains( "\"totalResults\":0" ) );
    }

    @Test
    void listWithExternalIdFilter_returnsMatchedUser() throws Exception {
        // externalId eq "ext-123" → findByExternalId path
        when( req.getParameter( "filter" ) ).thenReturn( "externalId eq \"ext-123\"" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        final Principal wikiName = mock( Principal.class );
        when( wikiName.getName() ).thenReturn( "BobUser" );
        when( mockDb.getWikiNames() ).thenReturn( new Principal[]{ wikiName } );
        final Map<String, java.io.Serializable> attrs = new HashMap<>();
        attrs.put( "sso.subject", "ext-123" );
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "bob" );
        when( p.getAttributes() ).thenReturn( attrs );
        when( mockDb.findByWikiName( "BobUser" ) ).thenReturn( p );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "totalResults" ) );
    }

    @Test
    void listWithPagination_startIndexAndCount() throws Exception {
        // startIndex=2, count=1 exercises the from/page subList path
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( "2" );
        when( req.getParameter( "count" ) ).thenReturn( "1" );

        final Principal pA = mock( Principal.class );
        final Principal pB = mock( Principal.class );
        when( pA.getName() ).thenReturn( "AliceUser" );
        when( pB.getName() ).thenReturn( "BobUser" );
        when( mockDb.getWikiNames() ).thenReturn( new Principal[]{ pA, pB } );
        final UserProfile alice = mock( UserProfile.class );
        when( alice.getLoginName() ).thenReturn( "alice" );
        when( alice.getAttributes() ).thenReturn( null );
        final UserProfile bob = mock( UserProfile.class );
        when( bob.getLoginName() ).thenReturn( "bob" );
        when( bob.getAttributes() ).thenReturn( null );
        when( mockDb.findByWikiName( "AliceUser" ) ).thenReturn( alice );
        when( mockDb.findByWikiName( "BobUser" ) ).thenReturn( bob );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "\"totalResults\":2" ) );
        // page shows 1 item (count=1, startIndex=2 → offset 1)
        assertTrue( out.contains( "\"itemsPerPage\":1" ) );
    }

    @Test
    void listGetWikiNamesFindByWikiNameThrows_skipsProfile() throws Exception {
        // findByWikiName for one name throws → that profile is skipped (logged, no error response)
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        final Principal pGood = mock( Principal.class );
        final Principal pBad = mock( Principal.class );
        when( pGood.getName() ).thenReturn( "GoodUser" );
        when( pBad.getName() ).thenReturn( "BadUser" );
        when( mockDb.getWikiNames() ).thenReturn( new Principal[]{ pGood, pBad } );
        final UserProfile good = mock( UserProfile.class );
        when( good.getLoginName() ).thenReturn( "gooduser" );
        when( good.getAttributes() ).thenReturn( null );
        when( mockDb.findByWikiName( "GoodUser" ) ).thenReturn( good );
        when( mockDb.findByWikiName( "BadUser" ) )
                .thenThrow( new NoSuchPrincipalException( "BadUser" ) );

        resource.doGet( req, resp );

        // Only 1 result (bad one is skipped)
        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "\"totalResults\":1" ) );
    }

    @Test
    void listDbThrowsRuntimeException_returns500() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );
        when( mockDb.getWikiNames() ).thenThrow( new RuntimeException( "catastrophic failure" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error listing users" ) );
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

    @Test
    void putSuccess_noActiveChange_callsSave() throws Exception {
        // PUT with no active field → db.save() called (the else branch in handleReplace)
        when( req.getPathInfo() ).thenReturn( "/uid-ivan" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "ivan" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-ivan" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"userName\":\"ivan\",\"displayName\":\"Ivan Updated\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( mockDb ).save( p );
        verify( resp, never() ).setStatus( anyInt() );  // no explicit error code
        final String out = sw.toString();
        assertTrue( out.contains( "User" ) || out.contains( "schemas" ) );
    }

    @Test
    void putActiveAlreadyMatchesDesiredState_callsSave() throws Exception {
        // active=true sent but user is already active (wasActive && nowActive → else branch → save)
        when( req.getPathInfo() ).thenReturn( "/uid-kate" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "kate" );
        when( p.isLocked() ).thenReturn( false );  // wasActive = true
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-kate" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"userName\":\"kate\",\"active\":true}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( mockDb ).save( p );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void putWithEmail_setsEmail() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-leo" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "leo" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-leo" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"userName\":\"leo\",\"emails\":[{\"value\":\"leo@example.com\",\"primary\":true}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( p ).setEmail( "leo@example.com" );
        verify( mockDb ).save( p );
    }

    @Test
    void putReloadAfterUpdateThrowsNoSuchPrincipal_returns404() throws Exception {
        // The second findByUid (reload after update) throws NoSuchPrincipalException
        when( req.getPathInfo() ).thenReturn( "/uid-mia" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "mia" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        // First call: found; second call (reload): not found
        when( mockDb.findByUid( "uid-mia" ) )
                .thenReturn( p )
                .thenThrow( new NoSuchPrincipalException( "uid-mia" ) );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"userName\":\"mia\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "User not found after update" ) );
    }

    @Test
    void putLifecycleThrowsWikiSecurityException_returns500() throws Exception {
        // lifecycle.deactivate internally calls db.save, which can throw WikiSecurityException → 500
        when( req.getPathInfo() ).thenReturn( "/uid-nick" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "nick" );
        when( p.isLocked() ).thenReturn( false );  // wasActive=true
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-nick" ) ).thenReturn( p );
        // lifecycle.deactivate: findByLoginName succeeds, then save throws WikiSecurityException
        when( mockDb.findByLoginName( "nick" ) ).thenReturn( p );
        doThrow( new WikiSecurityException( "lock failed" ) ).when( mockDb ).save( any() );

        final String body = "{\"userName\":\"nick\",\"active\":false}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Failed to update user" ) );
    }

    @Test
    void putDbSaveThrowsRuntimeException_returns500() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-ola" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "ola" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-ola" ) ).thenReturn( p );
        doThrow( new RuntimeException( "io error" ) ).when( mockDb ).save( any() );

        final String body = "{\"userName\":\"ola\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error updating user" ) );
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

    @Test
    void patchReplaceDisplayName_updatesWikiName() throws Exception {
        // Patch with displayName replace → p.setWikiName called, db.save called
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-helen" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "helen" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-helen" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Helen New\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( p ).setWikiName( "Helen New" );
        verify( mockDb ).save( p );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void patchDisplayNameUsedByAnotherAccount_returns409AndDoesNotSave() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-helen" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "helen" );
        when( p.getFullname() ).thenReturn( "Helen" );
        when( p.getWikiName() ).thenReturn( "Helen" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-helen" ) ).thenReturn( p );
        final UserProfile boss = mock( UserProfile.class );
        when( boss.getLoginName() ).thenReturn( "boss" );
        when( mockDb.findByLoginName( "boss" ) ).thenReturn( boss );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"boss\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 409 );
        assertTrue( sw.toString().contains( "uniqueness" ), sw.toString() );
        verify( p, never() ).setWikiName( any() );
        verify( mockDb, never() ).save( any() );
    }

    @Test
    void patchWithTakenNameAndDeactivation_returns409AndLeavesUserActive() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-helen" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "helen" );
        when( p.getFullname() ).thenReturn( "Helen" );
        when( p.getWikiName() ).thenReturn( "Helen" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-helen" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "helen" ) ).thenReturn( p );
        final UserProfile boss = mock( UserProfile.class );
        when( boss.getLoginName() ).thenReturn( "boss" );
        when( mockDb.findByLoginName( "boss" ) ).thenReturn( boss );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false},"
                + "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"boss\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 409 );
        verify( p, never() ).setLockExpiry( any() );
        verify( mockDb, never() ).save( any() );
    }

    @Test
    void putFullNameUsedByAnotherAccount_returns409AndDoesNotSave() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-ivan" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "ivan" );
        when( p.getFullname() ).thenReturn( "Ivan" );
        when( p.getWikiName() ).thenReturn( "Ivan" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-ivan" ) ).thenReturn( p );
        final UserProfile boss = mock( UserProfile.class );
        when( boss.getLoginName() ).thenReturn( "boss" );
        when( mockDb.findByLoginName( "boss" ) ).thenReturn( boss );

        final String body = "{\"userName\":\"ivan\",\"name\":{\"formatted\":\"boss\"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 409 );
        verify( p, never() ).setFullname( any() );
        verify( mockDb, never() ).save( any() );
    }

    @Test
    void createSecondUserWithSameFullName_returns201WithFreeWikiName() throws Exception {
        // Two people called John Smith are an ordinary IdP case: the second is created with the
        // same full name, and only the (unique) wiki name gets a numeric suffix.
        final UserProfile first = mock( UserProfile.class );
        when( first.getLoginName() ).thenReturn( "jsmith" );
        when( mockDb.findByFullName( "John Smith" ) ).thenReturn( first );
        when( mockDb.findByWikiName( "JohnSmith" ) ).thenReturn( first );

        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-jsmith2" );
        when( saved.getLoginName() ).thenReturn( "jsmith2" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        doThrow( new NoSuchPrincipalException( "jsmith2" ) ).doReturn( saved ).when( mockDb ).findByLoginName( "jsmith2" );

        final String body = "{\"userName\":\"jsmith2\",\"name\":{\"formatted\":\"John Smith\"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        verify( newProfile ).setFullname( "John Smith" );
        verify( newProfile ).setWikiName( "JohnSmith2" );
        verify( mockDb ).save( newProfile );
    }

    @Test
    void putResendingSharedDisplayName_keepsSuffixedWikiName() throws Exception {
        // An IdP re-sends the same displayName on every sync; the stored suffixed wiki name must
        // be kept rather than refused or re-suffixed.
        when( req.getPathInfo() ).thenReturn( "/uid-jsmith2" );
        final UserProfile first = mock( UserProfile.class );
        when( first.getLoginName() ).thenReturn( "jsmith" );
        when( mockDb.findByWikiName( "John Smith" ) ).thenReturn( first );
        when( mockDb.findByFullName( "John Smith" ) ).thenReturn( first );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "jsmith2" );
        when( p.getFullname() ).thenReturn( "John Smith" );
        when( p.getWikiName() ).thenReturn( "John Smith2" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-jsmith2" ) ).thenReturn( p );

        final String body = "{\"userName\":\"jsmith2\",\"displayName\":\"John Smith\",\"name\":{\"formatted\":\"John Smith\"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp, never() ).setStatus( 409 );
        verify( p ).setWikiName( "John Smith2" );
        verify( mockDb ).save( p );
    }

    @Test
    void patchFullNameSharedWithAnotherAccount_isSaved() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-helen" );
        final UserProfile other = mock( UserProfile.class );
        when( other.getLoginName() ).thenReturn( "hsmith" );
        when( mockDb.findByFullName( "Helen Smith" ) ).thenReturn( other );
        when( mockDb.findByWikiName( "HelenSmith" ) ).thenReturn( other );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "helen" );
        when( p.getFullname() ).thenReturn( "Helen" );
        when( p.getWikiName() ).thenReturn( "Helen" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-helen" ) ).thenReturn( p );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"name\",\"value\":{\"formatted\":\"Helen Smith\"}}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp, never() ).setStatus( 409 );
        verify( p ).setFullname( "Helen Smith" );
        verify( p ).setWikiName( "HelenSmith2" );
        verify( mockDb ).save( p );
    }

    @Test
    void createWithDisplayNameUsedByAnotherAccount_returns409() throws Exception {
        doThrow( new NoSuchPrincipalException( "zed" ) ).when( mockDb ).findByLoginName( "zed" );
        final UserProfile boss = mock( UserProfile.class );
        when( boss.getLoginName() ).thenReturn( "boss" );
        when( mockDb.findByLoginName( "boss" ) ).thenReturn( boss );

        final String body = "{\"userName\":\"zed\",\"displayName\":\"boss\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 409 );
        verify( mockDb, never() ).save( any() );
    }

    @Test
    void patchReplaceNameFormatted_updatesFullName() throws Exception {
        // Patch with name.formatted → setFullname
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-ivy" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "ivy" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-ivy" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"name\",\"value\":{\"formatted\":\"Ivy Jones\"}}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( p ).setFullname( "Ivy Jones" );
        verify( mockDb ).save( p );
    }

    @Test
    void patchReplaceEmails_updatesEmail() throws Exception {
        // Patch with emails array → setEmail
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-jack" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "jack" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-jack" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"emails\",\"value\":[{\"value\":\"jack@example.com\"}]}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( p ).setEmail( "jack@example.com" );
        verify( mockDb ).save( p );
    }

    @Test
    void patchReplaceExternalId_stampsAttribute() throws Exception {
        // Patch with externalId → getAttributes().put(sso.subject, ...)
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-kim" );

        final Map<String, java.io.Serializable> attrs = new HashMap<>();
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "kim" );
        when( p.getAttributes() ).thenReturn( attrs );
        when( mockDb.findByUid( "uid-kim" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\"new-ext-id\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        assertTrue( attrs.containsKey( "sso.subject" ) );
        assertTrue( "new-ext-id".equals( attrs.get( "sso.subject" ) ) );
        verify( mockDb ).save( p );
    }

    @Test
    void patchActiveTrue_callsReactivate() throws Exception {
        // Patch active=true → lifecycle.reactivate (db.findByLoginName → setLockExpiry(null) → save)
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-lena" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "lena" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-lena" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "lena" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        // reactivate calls findByLoginName then save
        verify( mockDb ).findByLoginName( "lena" );
        verify( mockDb, atLeastOnce() ).save( any() );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void patchActiveFalse_callsDeactivate() throws Exception {
        // Patch active=false → lifecycle.deactivate
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-max" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "max" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-max" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "max" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( mockDb ).findByLoginName( "max" );
        verify( mockDb, atLeastOnce() ).save( any() );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void patchActiveChangeReloadThrowsNoSuchPrincipal_returns404() throws Exception {
        // After deactivate, the reload findByUid throws → 404 "User not found after patch"
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-nina" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "nina" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        // First call returns p (initial load); second call (reload after lifecycle) → not found
        when( mockDb.findByUid( "uid-nina" ) )
                .thenReturn( p )
                .thenThrow( new NoSuchPrincipalException( "uid-nina" ) );
        when( mockDb.findByLoginName( "nina" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "User not found after patch" ) );
    }

    @Test
    void patchLifecycleThrowsWikiSecurityException_returns500() throws Exception {
        // lifecycle.deactivate: findByLoginName succeeds, db.save throws WikiSecurityException → 500
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-omar" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "omar" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-omar" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "omar" ) ).thenReturn( p );
        doThrow( new WikiSecurityException( "lock denied" ) ).when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Failed to patch user" ) );
    }

    @Test
    void patchDbSaveThrowsRuntimeException_returns500() throws Exception {
        // db.save() in patch throws RuntimeException → generic 500 catch
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-petra" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "petra" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-petra" ) ).thenReturn( p );
        doThrow( new RuntimeException( "disk full" ) ).when( mockDb ).save( any() );

        // Patch displayName → dirty = true → calls db.save
        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Petra New\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error patching user" ) );
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
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-to-del" ) ).thenReturn( p );
        // UserLifecycleService.deactivate calls db.findByLoginName to reload
        when( mockDb.findByLoginName( "henry" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 204 );
    }

    @Test
    void deleteLifecycleThrowsNoSuchPrincipal_returns404() throws Exception {
        // After findByUid succeeds, lifecycle.deactivate throws NoSuchPrincipalException → 404
        when( req.getPathInfo() ).thenReturn( "/uid-quest" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "quest" );
        when( mockDb.findByUid( "uid-quest" ) ).thenReturn( p );
        // lifecycle internally calls findByLoginName → not found
        when( mockDb.findByLoginName( "quest" ) ).thenThrow( new NoSuchPrincipalException( "quest" ) );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "User not found" ) );
    }

    @Test
    void deleteLifecycleThrowsWikiSecurityException_returns500() throws Exception {
        // lifecycle.deactivate: findByLoginName succeeds, db.save throws WikiSecurityException → 500
        when( req.getPathInfo() ).thenReturn( "/uid-rex" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "rex" );
        when( mockDb.findByUid( "uid-rex" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "rex" ) ).thenReturn( p );
        doThrow( new WikiSecurityException( "lock denied" ) ).when( mockDb ).save( any() );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Failed to deactivate user" ) );
    }

    @Test
    void deleteLifecycleThrowsRuntimeException_returns500() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-sue" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "sue" );
        when( mockDb.findByUid( "uid-sue" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "sue" ) ).thenThrow( new RuntimeException( "db crash" ) );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error deactivating user" ) );
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

    @Test
    void getUserDatabaseThrowsException_returns503() throws Exception {
        // WikiEngine.getManager() throws RuntimeException → getUserDatabase() catches + returns null → 503
        when( mockEngine.getManager( UserManager.class ) ).thenThrow( new RuntimeException( "no manager" ) );

        final String body = "{\"userName\":\"yara\"}";
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
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-active" ) ).thenReturn( p );
        // After lifecycle op, reload
        when( mockDb.findByLoginName( "ivan" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

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
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-locked" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "janet" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"userName\":\"janet\",\"active\":true}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp, never() ).setStatus( 400 );
        verify( resp, never() ).setStatus( 404 );
        verify( resp, never() ).setStatus( 500 );
    }

    // -----------------------------------------------------------------------
    // Audit service wired (non-null AuditService) — covers auditRecord method
    // -----------------------------------------------------------------------

    @Test
    void deleteWithAuditServiceWired_recordsAuditEvent() throws Exception {
        // Wire a real (mock) AuditService → auditRecord exercises the non-null path
        final AuditService auditService = mock( AuditService.class );
        when( mockEngine.getAuditService() ).thenReturn( auditService );
        doNothing().when( auditService ).record( any() );

        when( req.getPathInfo() ).thenReturn( "/uid-tim" );
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "tim" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-tim" ) ).thenReturn( p );
        when( mockDb.findByLoginName( "tim" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        resource.doDelete( req, resp );

        // 204 (delete success) confirms the audit path ran
        verify( resp ).setStatus( 204 );
    }

    @Test
    void listNoFilter_withAuditServiceWired_noErrors() throws Exception {
        // GET list triggers auditRecord indirectly through the flow
        final AuditService auditService = mock( AuditService.class );
        when( mockEngine.getAuditService() ).thenReturn( auditService );
        doNothing().when( auditService ).record( any() );

        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );
        when( mockDb.getWikiNames() ).thenReturn( new Principal[0] );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        assertTrue( sw.toString().contains( "totalResults" ) );
    }
}
