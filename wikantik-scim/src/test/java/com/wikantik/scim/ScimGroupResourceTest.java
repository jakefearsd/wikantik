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

    // -----------------------------------------------------------------------
    // GET /Groups/{name} — getByName
    // -----------------------------------------------------------------------

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
}
