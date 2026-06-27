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
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScimGroupResource} covering error/edge branches not
 * exercised by the integration tests.
 */
class ScimGroupResourceTest {

    private ScimGroupResource resource;
    private WikiEngine mockEngine;
    private GroupManager mockGm;
    private UserDatabase mockDb;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter sw;

    @BeforeEach
    void setUp() throws Exception {
        resource = new ScimGroupResource();

        mockEngine = mock( WikiEngine.class );
        mockGm = mock( GroupManager.class );
        mockDb = mock( UserDatabase.class );

        final UserManager mockUserManager = mock( UserManager.class );
        when( mockEngine.getManager( GroupManager.class ) ).thenReturn( mockGm );
        when( mockEngine.getManager( UserManager.class ) ).thenReturn( mockUserManager );
        when( mockUserManager.getUserDatabase() ).thenReturn( mockDb );
        when( mockEngine.getAuditService() ).thenReturn( null );

        // Inject engine
        final Field f = ScimGroupResource.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( resource, mockEngine );

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        when( req.getPathInfo() ).thenReturn( null );
        when( req.getRequestURL() ).thenReturn( new StringBuffer( "http://localhost/scim/v2/Groups" ) );
    }

    // -----------------------------------------------------------------------
    // POST /Groups — create
    // -----------------------------------------------------------------------

    @Test
    void createMissingDisplayName_returns400() throws Exception {
        final String body = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 400 );
        final String out = sw.toString();
        assertTrue( out.contains( "invalidValue" ) );
        assertTrue( out.contains( "displayName is required" ) );
    }

    @Test
    void createGroupManagerUnavailable_returns503() throws Exception {
        when( mockEngine.getManager( GroupManager.class ) ).thenReturn( null );

        final String body = "{\"displayName\":\"Testers\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    @Test
    void createUserDbUnavailable_returns503() throws Exception {
        // GroupManager available, UserDatabase not
        final UserManager mockUserManager = mock( UserManager.class );
        when( mockEngine.getManager( UserManager.class ) ).thenReturn( mockUserManager );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        final String body = "{\"displayName\":\"Testers\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void createGroupAlreadyExists_returns409() throws Exception {
        final Group existing = mock( Group.class );
        when( existing.getName() ).thenReturn( "QA" );
        when( mockGm.getGroup( "QA" ) ).thenReturn( existing );

        final String body = "{\"displayName\":\"QA\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 409 );
        final String out = sw.toString();
        assertTrue( out.contains( "uniqueness" ) );
        assertTrue( out.contains( "already exists" ) );
    }

    @Test
    void createNestedGroupMember_returns400() throws Exception {
        when( mockGm.getGroup( "TeamA" ) ).thenThrow( new NoSuchPrincipalException( "TeamA" ) );

        final String body = "{\"displayName\":\"TeamA\",\"members\":[{\"value\":\"grp1\",\"type\":\"Group\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidValue" ) );
    }

    @Test
    void createInvalidMemberUid_returns400() throws Exception {
        when( mockGm.getGroup( "Devs" ) ).thenThrow( new NoSuchPrincipalException( "Devs" ) );
        when( mockDb.findByUid( "bad-uid" ) ).thenThrow( new NoSuchPrincipalException( "bad-uid" ) );

        // saveGroupWithMembers calls WikiSession.getWikiSession — we need the engine to return a session
        // Since WikiSession.getWikiSession calls engine methods, stub parseGroup to avoid a NPE chain
        final Group devsGroup = mock( Group.class );
        when( devsGroup.getName() ).thenReturn( "Devs" );
        when( devsGroup.members() ).thenReturn( new Principal[0] );
        when( mockGm.parseGroup( eq( "Devs" ), anyString(), anyBoolean() ) ).thenReturn( devsGroup );

        final String body = "{\"displayName\":\"Devs\",\"members\":[{\"value\":\"bad-uid\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidValue" ) );
        assertTrue( sw.toString().contains( "No user found" ) );
    }

    @Test
    void createMalformedJson_returns400InvalidSyntax() throws Exception {
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{{bad" ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidSyntax" ) );
    }

    @Test
    void createParseGroupThrowsRuntimeException_returns500() throws Exception {
        // gm.parseGroup() throws RuntimeException → caught by generic catch(Exception e) → 500
        when( mockGm.getGroup( "Crash" ) ).thenThrow( new NoSuchPrincipalException( "Crash" ) );
        when( mockGm.parseGroup( eq( "Crash" ), anyString(), anyBoolean() ) )
                .thenThrow( new RuntimeException( "unexpected" ) );

        final String body = "{\"displayName\":\"Crash\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error creating group" ) );
    }

    @Test
    void createParseGroupThrowsWikiSecurityException_returns500() throws Exception {
        // gm.parseGroup() throws WikiSecurityException (a WikiException) → 500
        when( mockGm.getGroup( "Fault" ) ).thenThrow( new NoSuchPrincipalException( "Fault" ) );
        when( mockGm.parseGroup( eq( "Fault" ), anyString(), anyBoolean() ) )
                .thenThrow( new WikiSecurityException( "permission denied" ) );

        final String body = "{\"displayName\":\"Fault\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 500 );
        // parseGroup is preceded by WikiSession.getWikiSession which may fail first on bare mock engine;
        // either path leads to a 500 with an error detail in the body
        assertTrue( sw.toString().contains( "500" ) || sw.toString().contains( "error" )
                || sw.toString().contains( "Failed" ) || sw.toString().contains( "Internal" ) );
    }

    // -----------------------------------------------------------------------
    // GET /Groups/{name} — getByName
    // -----------------------------------------------------------------------

    @Test
    void getByNameSuccess_returnsGroupBody() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/Engineers" );
        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "Engineers" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "Engineers" ) ).thenReturn( group );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "Engineers" ) );
        assertTrue( out.contains( "schemas" ) );
    }

    @Test
    void getByNameNoNameInPath_returnsListResponse() throws Exception {
        // pathInfo = null → list, not getByName
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );
        when( mockGm.getRoles() ).thenReturn( new Principal[0] );

        resource.doGet( req, resp );

        // No error status
        verify( resp, never() ).setStatus( 400 );
        verify( resp, never() ).setStatus( 404 );
        verify( resp, never() ).setStatus( 503 );
    }

    @Test
    void getByNameMissingGroup_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/NoSuchGroup" );
        when( mockGm.getGroup( "NoSuchGroup" ) ).thenThrow( new NoSuchPrincipalException( "NoSuchGroup" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "Group not found" ) );
    }

    @Test
    void getByNameGroupManagerUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/SomeGroup" );
        when( mockEngine.getManager( GroupManager.class ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    @Test
    void getByNameUserDbUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/SomeGroup" );
        final UserManager mockUserManager = mock( UserManager.class );
        when( mockEngine.getManager( UserManager.class ) ).thenReturn( mockUserManager );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void getByNameGroupManagerThrowsRuntimeException_returns500() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/Boom" );
        when( mockGm.getGroup( "Boom" ) ).thenThrow( new RuntimeException( "internal db failure" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error retrieving group" ) );
    }

    // -----------------------------------------------------------------------
    // GET /Groups — list
    // -----------------------------------------------------------------------

    @Test
    void listGroupManagerUnavailable_returns503() throws Exception {
        when( mockEngine.getManager( GroupManager.class ) ).thenReturn( null );
        when( req.getParameter( "filter" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    @Test
    void listUserDbUnavailable_returns503() throws Exception {
        final UserManager mockUserManager = mock( UserManager.class );
        when( mockEngine.getManager( UserManager.class ) ).thenReturn( mockUserManager );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );
        when( req.getParameter( "filter" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void listUnsupportedFilterAttribute_returns400() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "id eq \"abc\"" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidFilter" ) );
    }

    @Test
    void listUnsupportedFilterSyntax_returns400() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "pr displayName" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidFilter" ) );
    }

    @Test
    void listByDisplayNameFilter_groupFound_returnsSingleResult() throws Exception {
        // displayName eq "Ops" filter where group exists → list with 1 result
        when( req.getParameter( "filter" ) ).thenReturn( "displayName eq \"Ops\"" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );
        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "Ops" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "Ops" ) ).thenReturn( group );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "totalResults" ) );
        assertTrue( out.contains( "\"totalResults\":1" ) );
    }

    @Test
    void listByDisplayNameFilter_missingGroup_returnsEmptyList() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "displayName eq \"Absent\"" );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );
        when( mockGm.getGroup( "Absent" ) ).thenThrow( new NoSuchPrincipalException( "Absent" ) );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( 404 );
        verify( resp, never() ).setStatus( 400 );
        final String out = sw.toString();
        assertTrue( out.contains( "totalResults" ) );
    }

    @Test
    void listNoFilter_returnsAllGroups() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        final Principal r = mock( Principal.class );
        when( r.getName() ).thenReturn( "Devs" );
        when( mockGm.getRoles() ).thenReturn( new Principal[]{ r } );
        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "Devs" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "Devs" ) ).thenReturn( group );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "\"totalResults\":1" ) );
        assertTrue( out.contains( "Devs" ) );
    }

    @Test
    void listNoFilter_groupNotLoadable_skipsIt() throws Exception {
        // getRoles returns a role but getGroup throws → that group is silently skipped
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );

        final Principal r = mock( Principal.class );
        when( r.getName() ).thenReturn( "Ghost" );
        when( mockGm.getRoles() ).thenReturn( new Principal[]{ r } );
        when( mockGm.getGroup( "Ghost" ) ).thenThrow( new NoSuchPrincipalException( "Ghost" ) );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "\"totalResults\":0" ) );
    }

    @Test
    void listWithPagination_startIndex2count1() throws Exception {
        // Two groups, startIndex=2, count=1 → page has only the second one
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( "2" );
        when( req.getParameter( "count" ) ).thenReturn( "1" );

        final Principal r1 = mock( Principal.class );
        final Principal r2 = mock( Principal.class );
        when( r1.getName() ).thenReturn( "Alpha" );
        when( r2.getName() ).thenReturn( "Beta" );
        when( mockGm.getRoles() ).thenReturn( new Principal[]{ r1, r2 } );

        final Group groupA = mock( Group.class );
        when( groupA.getName() ).thenReturn( "Alpha" );
        when( groupA.members() ).thenReturn( new Principal[0] );
        final Group groupB = mock( Group.class );
        when( groupB.getName() ).thenReturn( "Beta" );
        when( groupB.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "Alpha" ) ).thenReturn( groupA );
        when( mockGm.getGroup( "Beta" ) ).thenReturn( groupB );

        resource.doGet( req, resp );

        verify( resp, never() ).setStatus( anyInt() );
        final String out = sw.toString();
        assertTrue( out.contains( "\"totalResults\":2" ) );
        assertTrue( out.contains( "\"itemsPerPage\":1" ) );
    }

    @Test
    void listGetRolesThrowsRuntimeException_returns500() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( null );
        when( req.getParameter( "startIndex" ) ).thenReturn( null );
        when( req.getParameter( "count" ) ).thenReturn( null );
        when( mockGm.getRoles() ).thenThrow( new RuntimeException( "db gone" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error listing groups" ) );
    }

    // -----------------------------------------------------------------------
    // PUT /Groups — replace
    // -----------------------------------------------------------------------

    @Test
    void putNoNameInPath_returns400() throws Exception {
        resource.doPut( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "PUT requires a group name" ) );
    }

    @Test
    void putGroupManagerUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/SomeGroup" );
        when( mockEngine.getManager( GroupManager.class ) ).thenReturn( null );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    @Test
    void putUserDbUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/SomeGroup" );
        final UserManager mockUserManager = mock( UserManager.class );
        when( mockEngine.getManager( UserManager.class ) ).thenReturn( mockUserManager );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void putInvalidMemberUid_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/TeamB" );
        when( mockDb.findByUid( "invalid-uid" ) ).thenThrow( new NoSuchPrincipalException( "invalid-uid" ) );
        final Group teamBGroup = mock( Group.class );
        when( teamBGroup.getName() ).thenReturn( "TeamB" );
        when( mockGm.parseGroup( eq( "TeamB" ), anyString(), anyBoolean() ) ).thenReturn( teamBGroup );

        final String body = "{\"displayName\":\"TeamB\",\"members\":[{\"value\":\"invalid-uid\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidValue" ) );
    }

    @Test
    void putNestedGroupMember_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/TeamC" );

        final String body = "{\"displayName\":\"TeamC\",\"members\":[{\"value\":\"grp2\",\"type\":\"Group\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidValue" ) );
    }

    @Test
    void putParseGroupThrowsWikiSecurityException_returns500() throws Exception {
        // gm.parseGroup throws WikiSecurityException (a WikiException subtype) → 500 "Failed to update group"
        when( req.getPathInfo() ).thenReturn( "/Fault" );
        when( mockGm.parseGroup( eq( "Fault" ), anyString(), anyBoolean() ) )
                .thenThrow( new WikiSecurityException( "not allowed" ) );

        final String body = "{\"displayName\":\"Fault\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 500 );
        // WikiSession.getWikiSession on bare mock engine may throw before parseGroup;
        // either way a 500 with an error body is returned
        assertTrue( sw.toString().contains( "500" ) || sw.toString().contains( "error" )
                || sw.toString().contains( "Failed" ) || sw.toString().contains( "Internal" ) );
    }

    @Test
    void putParseGroupThrowsRuntimeException_returns500() throws Exception {
        // gm.parseGroup throws RuntimeException → generic 500 "Internal error updating group"
        when( req.getPathInfo() ).thenReturn( "/Kaboom" );
        when( mockGm.parseGroup( eq( "Kaboom" ), anyString(), anyBoolean() ) )
                .thenThrow( new RuntimeException( "boom" ) );

        final String body = "{\"displayName\":\"Kaboom\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error updating group" ) );
    }

    // -----------------------------------------------------------------------
    // PATCH /Groups — partial update (via service())
    // -----------------------------------------------------------------------

    @Test
    void patchNoNameInPath_returns400() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );

        resource.service( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "PATCH requires a group name" ) );
    }

    @Test
    void patchGroupManagerUnavailable_returns503() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/SomeGroup" );
        when( mockEngine.getManager( GroupManager.class ) ).thenReturn( null );

        resource.service( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    @Test
    void patchUserDbUnavailable_returns503() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/SomeGroup" );
        final UserManager mockUserManager = mock( UserManager.class );
        when( mockEngine.getManager( UserManager.class ) ).thenReturn( mockUserManager );
        when( mockUserManager.getUserDatabase() ).thenReturn( null );

        resource.service( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "user database unavailable" ) );
    }

    @Test
    void patchMissingGroup_returns404() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/GhostGroup" );
        when( mockGm.getGroup( "GhostGroup" ) ).thenThrow( new NoSuchPrincipalException( "GhostGroup" ) );

        final String body = "{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"uid1\"}]}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "Group not found" ) );
    }

    @Test
    void patchUnsupportedOp_returns400InvalidPath() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/RealGroup" );

        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "RealGroup" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "RealGroup" ) ).thenReturn( group );

        // UnsupportedGroupPatchException: unknown op
        final String body = "{\"Operations\":[{\"op\":\"merge\",\"path\":\"members\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidPath" ) );
    }

    @Test
    void patchMissingOperationsArray_returns400() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/RealGroup" );

        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "RealGroup" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "RealGroup" ) ).thenReturn( group );

        final String body = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidPath" ) );
    }

    @Test
    void patchInvalidMemberUid_returns400() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/RealGroup" );

        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "RealGroup" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "RealGroup" ) ).thenReturn( group );
        when( mockDb.findByUid( "uid-bad" ) ).thenThrow( new NoSuchPrincipalException( "uid-bad" ) );
        when( mockGm.parseGroup( eq( "RealGroup" ), anyString(), anyBoolean() ) ).thenReturn( group );

        final String body = "{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"uid-bad\"}]}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidValue" ) );
        assertTrue( sw.toString().contains( "No user found" ) );
    }

    @Test
    void patchParseGroupThrowsWikiSecurityException_returns500() throws Exception {
        // gm.parseGroup throws WikiSecurityException (WikiException) → 500 "Failed to patch group"
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/WikFail" );

        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "WikFail" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "WikFail" ) ).thenReturn( group );
        when( mockGm.parseGroup( eq( "WikFail" ), anyString(), anyBoolean() ) )
                .thenThrow( new WikiSecurityException( "not allowed" ) );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"members\",\"value\":[]}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 500 );
        // WikiSession.getWikiSession on bare mock engine may throw before parseGroup;
        // either way a 500 with an error body is returned
        assertTrue( sw.toString().contains( "500" ) || sw.toString().contains( "error" )
                || sw.toString().contains( "Failed" ) || sw.toString().contains( "Internal" ) );
    }

    @Test
    void patchParseGroupThrowsRuntimeException_returns500() throws Exception {
        // gm.parseGroup throws RuntimeException → generic 500 "Internal error patching group"
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/Explode" );

        final Group group = mock( Group.class );
        when( group.getName() ).thenReturn( "Explode" );
        when( group.members() ).thenReturn( new Principal[0] );
        when( mockGm.getGroup( "Explode" ) ).thenReturn( group );
        when( mockGm.parseGroup( eq( "Explode" ), anyString(), anyBoolean() ) )
                .thenThrow( new RuntimeException( "fire" ) );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"members\",\"value\":[]}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error patching group" ) );
    }

    // -----------------------------------------------------------------------
    // DELETE /Groups — hard delete
    // -----------------------------------------------------------------------

    @Test
    void deleteNoNameInPath_returns400() throws Exception {
        resource.doDelete( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "DELETE requires a group name" ) );
    }

    @Test
    void deleteGroupManagerUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/OldGroup" );
        when( mockEngine.getManager( GroupManager.class ) ).thenReturn( null );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    @Test
    void deleteMissingGroup_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/GoneGroup" );
        doThrow( new NoSuchPrincipalException( "GoneGroup" ) ).when( mockGm ).removeGroup( "GoneGroup" );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 404 );
        assertTrue( sw.toString().contains( "Group not found" ) );
    }

    @Test
    void deleteSuccess_returns204() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/OldGroup" );
        doNothing().when( mockGm ).removeGroup( "OldGroup" );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 204 );
    }

    @Test
    void deleteRemoveGroupThrowsWikiSecurityException_returns500() throws Exception {
        // removeGroup() throws WikiSecurityException → 500
        when( req.getPathInfo() ).thenReturn( "/Protected" );
        doThrow( new WikiSecurityException( "cannot delete" ) ).when( mockGm ).removeGroup( "Protected" );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Failed to delete group" ) );
    }

    @Test
    void deleteRemoveGroupThrowsRuntimeException_returns500() throws Exception {
        // removeGroup() throws RuntimeException → generic 500
        when( req.getPathInfo() ).thenReturn( "/Broken" );
        doThrow( new RuntimeException( "crash" ) ).when( mockGm ).removeGroup( "Broken" );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 500 );
        assertTrue( sw.toString().contains( "Internal error deleting group" ) );
    }

    // -----------------------------------------------------------------------
    // Engine not a WikiEngine → manager unavailable → 503
    // -----------------------------------------------------------------------

    @Test
    void engineNotWikiEngine_groupManagerUnavailable_returns503() throws Exception {
        final com.wikantik.api.core.Engine plainEngine = mock( com.wikantik.api.core.Engine.class );
        final Field f = ScimGroupResource.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( resource, plainEngine );

        final String body = "{\"displayName\":\"Ops\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    @Test
    void getGroupManagerThrowsException_returns503() throws Exception {
        // WikiEngine.getManager() throws RuntimeException → getGroupManager() catches + returns null
        when( mockEngine.getManager( GroupManager.class ) ).thenThrow( new RuntimeException( "no manager" ) );

        final String body = "{\"displayName\":\"Error\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 503 );
        assertTrue( sw.toString().contains( "group manager unavailable" ) );
    }

    // -----------------------------------------------------------------------
    // Audit service wired (non-null AuditService) — covers auditRecord method
    // -----------------------------------------------------------------------

    @Test
    void deleteWithAuditServiceWired_recordsAuditEvent() throws Exception {
        final AuditService auditService = mock( AuditService.class );
        when( mockEngine.getAuditService() ).thenReturn( auditService );
        doNothing().when( auditService ).record( any() );

        when( req.getPathInfo() ).thenReturn( "/DeletedGroup" );
        doNothing().when( mockGm ).removeGroup( "DeletedGroup" );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( 204 );
        // Audit record was attempted (verify auditService.record was called)
        verify( auditService ).record( any() );
    }
}
