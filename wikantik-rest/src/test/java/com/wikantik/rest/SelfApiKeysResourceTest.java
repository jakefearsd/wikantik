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
    private boolean stubAdmin;           // true => caller holds AllPermission
    /** Keys the work registry says are (or were) agent keys, by id, with the agent's name. */
    private final java.util.Map< Integer, String > agentKeys = new java.util.HashMap<>();
    private java.sql.SQLException agentLookupFails;
    private boolean seesRegistry;
    private boolean workRunning = true;

    private SelfApiKeysResource newServlet() throws Exception {
        final SelfApiKeysResource servlet = new SelfApiKeysResource() {
            @Override
            String authenticatedLogin( final HttpServletRequest request ) {
                return stubLogin;
            }
            @Override
            boolean callerIsAdmin( final HttpServletRequest request ) {
                return stubAdmin;
            }
            @Override
            Optional< String > agentKeyName( final int keyId ) throws java.sql.SQLException {
                if ( agentLookupFails != null ) {
                    throw agentLookupFails;
                }
                return Optional.ofNullable( agentKeys.get( keyId ) );
            }
            @Override
            boolean callerSeesAgentRegistry( final HttpServletRequest request ) {
                return seesRegistry;
            }
            @Override
            boolean workRunning() {
                return workRunning;
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
                Instant.parse( "2026-04-01T10:00:00Z" ), "admin", null, null, null, false );
    }

    private static HttpServletResponse mockResponse( final StringWriter sw ) throws Exception {
        final HttpServletResponse response = mock( HttpServletResponse.class );
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        return response;
    }

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        mockService = mock( ApiKeyService.class );
        ApiKeyServiceHolder.setForTesting( mockService );
        stubLogin = "alice";
        stubAdmin = false;
    }

    private HttpServletRequest postRequest( final String pathInfo, final String body ) throws Exception {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( pathInfo );
        if ( body != null ) {
            when( request.getReader() ).thenReturn( new java.io.BufferedReader( new java.io.StringReader( body ) ) );
        }
        return request;
    }

    private void stubGenerate( final ApiKeyService.Scope scope ) {
        when( mockService.generate( eq( "alice" ), any(), eq( scope ), eq( "alice" ) ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_SECRET", rec( 9, "alice", "x", scope ) ) );
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
        // GSON.setPrettyPrinting() emits "key": "value" (with space after colon)
        assertTrue( json.contains( "\"label\": \"laptop\"" ) );
        assertTrue( json.contains( "\"scope\": \"tools\"" ) );
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
    void postRejectsAnonymousWith401() throws Exception {
        stubLogin = null;
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( null );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_UNAUTHORIZED );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void deleteRejectsAnonymousWith401() throws Exception {
        stubLogin = null;
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/7" );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doDelete( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_UNAUTHORIZED );
        verify( mockService, never() ).findById( anyInt() );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
    }

    @Test
    void postGeneratesKeyBoundToCallerAndReturnsTokenOnce() throws Exception {
        stubAdmin = true;
        final ApiKeyService.Record r = rec( 7, "alice", "ci", ApiKeyService.Scope.MCP_ADMIN );
        when( mockService.generate( eq( "alice" ), eq( "ci" ), eq( ApiKeyService.Scope.MCP_ADMIN ), eq( "alice" ) ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_SECRET", r ) );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( null );
        when( request.getReader() ).thenReturn( new java.io.BufferedReader(
                new java.io.StringReader( "{\"label\":\"ci\",\"scope\":\"mcp\"}" ) ) );
        final StringWriter sw = new StringWriter();

        servlet.doPost( request, mockResponse( sw ) );

        verify( mockService ).generate( "alice", "ci", ApiKeyService.Scope.MCP_ADMIN, "alice" );
        assertTrue( sw.toString().contains( "wkk_SECRET" ) );
    }

    @Test
    void rotateRevokesOwnKeyThenIssuesReplacement() throws Exception {
        stubAdmin = true;
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "alice", "ci", ApiKeyService.Scope.MCP_ADMIN ) ) );
        when( mockService.revoke( 7, "alice" ) ).thenReturn( true );
        when( mockService.generate( "alice", "ci", ApiKeyService.Scope.MCP_ADMIN, "alice" ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_NEW", rec( 8, "alice", "ci", ApiKeyService.Scope.MCP_ADMIN ) ) );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/7/rotate" );
        final StringWriter sw = new StringWriter();

        servlet.doPost( request, mockResponse( sw ) );

        verify( mockService ).revoke( 7, "alice" );
        verify( mockService ).generate( "alice", "ci", ApiKeyService.Scope.MCP_ADMIN, "alice" );
        assertTrue( sw.toString().contains( "wkk_NEW" ) );
    }

    @Test
    void rotateOfAnotherUsersKeyReturns404AndDoesNotRevoke() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "bob", "ci", ApiKeyService.Scope.MCP_ADMIN ) ) );
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
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "alice", "ci", ApiKeyService.Scope.MCP_ADMIN ) ) );
        when( mockService.revoke( 7, "alice" ) ).thenReturn( true );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/7" );
        final StringWriter sw = new StringWriter();

        servlet.doDelete( request, mockResponse( sw ) );

        verify( mockService ).revoke( 7, "alice" );
        assertTrue( sw.toString().contains( "\"success\": true" ) );
    }

    @Test
    void deleteOfAnotherUsersKeyReturns404() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "bob", "ci", ApiKeyService.Scope.MCP_ADMIN ) ) );
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
        final StringWriter out = new StringWriter();
        final HttpServletResponse response = mockResponse( out );

        servlet.doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( out.toString().contains( ApiKeyService.Scope.validWireNames() ),
                "400 message must list every valid scope: " + out );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void nonAdminCannotMintMcpScope() throws Exception {
        final SelfApiKeysResource servlet = newServlet();
        final StringWriter out = new StringWriter();
        final HttpServletResponse response = mockResponse( out );

        servlet.doPost( postRequest( null, "{\"label\":\"x\",\"scope\":\"mcp\"}" ), response );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        final String message = com.google.gson.JsonParser.parseString( out.toString() )
                .getAsJsonObject().get( "message" ).getAsString();
        assertTrue( message.endsWith( "you may create keys with scope mcp_read, tools, work" ),
                "403 must name exactly the scopes the caller may mint: " + message );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void nonAdminCannotMintAllScope() throws Exception {
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doPost( postRequest( null, "{\"label\":\"x\",\"scope\":\"all\"}" ), response );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void nonAdminOmittedScopeDefaultsToMcpRead() throws Exception {
        stubGenerate( ApiKeyService.Scope.MCP_READ );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doPost( postRequest( null, "{\"label\":\"x\"}" ), response );

        verify( response ).setStatus( HttpServletResponse.SC_CREATED );
        verify( mockService ).generate( "alice", "x", ApiKeyService.Scope.MCP_READ, "alice" );
        verify( mockService, never() ).generate( anyString(), any(), eq( ApiKeyService.Scope.ALL ), anyString() );
    }

    @Test
    void nonAdminMayMintToolsAndMcpRead() throws Exception {
        stubGenerate( ApiKeyService.Scope.TOOLS );
        stubGenerate( ApiKeyService.Scope.MCP_READ );
        final SelfApiKeysResource servlet = newServlet();

        final HttpServletResponse r1 = mockResponse( new StringWriter() );
        servlet.doPost( postRequest( null, "{\"label\":\"x\",\"scope\":\"tools\"}" ), r1 );
        verify( r1 ).setStatus( HttpServletResponse.SC_CREATED );

        final HttpServletResponse r2 = mockResponse( new StringWriter() );
        servlet.doPost( postRequest( null, "{\"label\":\"x\",\"scope\":\"mcp_read\"}" ), r2 );
        verify( r2 ).setStatus( HttpServletResponse.SC_CREATED );

        verify( mockService ).generate( "alice", "x", ApiKeyService.Scope.TOOLS, "alice" );
        verify( mockService ).generate( "alice", "x", ApiKeyService.Scope.MCP_READ, "alice" );
    }

    @Test
    void nonAdminMayMintWorkKey() throws Exception {
        stubGenerate( ApiKeyService.Scope.WORK );
        final SelfApiKeysResource servlet = newServlet();
        final StringWriter out = new StringWriter();
        final HttpServletResponse r = mockResponse( out );

        servlet.doPost( postRequest( null, "{\"label\":\"x\",\"scope\":\"work\"}" ), r );

        verify( r ).setStatus( HttpServletResponse.SC_CREATED );
        verify( mockService ).generate( "alice", "x", ApiKeyService.Scope.WORK, "alice" );
        assertTrue( out.toString().contains( "\"work\"" ), out.toString() );
    }

    @Test
    void adminMayMintAllScope() throws Exception {
        stubAdmin = true;
        stubGenerate( ApiKeyService.Scope.ALL );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doPost( postRequest( null, "{\"label\":\"x\",\"scope\":\"all\"}" ), response );

        verify( response ).setStatus( HttpServletResponse.SC_CREATED );
        verify( mockService ).generate( "alice", "x", ApiKeyService.Scope.ALL, "alice" );
    }

    @Test
    void nonAdminRotateOfPrivilegedKeyIsRefusedAndKeyKept() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "alice", "ci", ApiKeyService.Scope.MCP_ADMIN ) ) );
        final SelfApiKeysResource servlet = newServlet();
        final StringWriter out = new StringWriter();
        final HttpServletResponse response = mockResponse( out );

        servlet.doPost( postRequest( "/7/rotate", null ), response );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void nonAdminRotateOfSelfServiceKeySucceeds() throws Exception {
        when( mockService.findById( 7 ) ).thenReturn( Optional.of( rec( 7, "alice", "ci", ApiKeyService.Scope.TOOLS ) ) );
        when( mockService.revoke( 7, "alice" ) ).thenReturn( true );
        stubGenerate( ApiKeyService.Scope.TOOLS );
        final SelfApiKeysResource servlet = newServlet();
        final HttpServletResponse response = mockResponse( new StringWriter() );

        servlet.doPost( postRequest( "/7/rotate", null ), response );

        verify( response ).setStatus( HttpServletResponse.SC_CREATED );
        verify( mockService ).revoke( 7, "alice" );
    }

    /** A servlet with no test seams overridden: identity and admin standing come from the real session. */
    private SelfApiKeysResource realServlet() throws Exception {
        final SelfApiKeysResource servlet = new SelfApiKeysResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
        return servlet;
    }

    private HttpServletRequest loggedInRequest( final String login, final String password, final String body ) throws Exception {
        final HttpServletRequest request = com.wikantik.HttpMockFactory.createIsolatedHttpRequest( "/api/self/apikeys" );
        when( request.getPathInfo() ).thenReturn( null );
        when( request.getReader() ).thenReturn( new java.io.BufferedReader( new java.io.StringReader( body ) ) );
        final com.wikantik.api.core.Session session = com.wikantik.api.spi.Wiki.session().find( engine, request );
        engine.getManager( com.wikantik.auth.AuthenticationManager.class ).login( session, request, login, password );
        assertTrue( session.isAuthenticated(), "test precondition: " + login + " is logged in" );
        return request;
    }

    @Test
    void realNonAdminSessionCannotMintMcpScope() throws Exception {
        final HttpServletRequest request = loggedInRequest( com.wikantik.auth.Users.JANNE,
                com.wikantik.auth.Users.JANNE_PASS, "{\"label\":\"x\",\"scope\":\"mcp\"}" );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        realServlet().doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void realAdminSessionMayMintAllScope() throws Exception {
        when( mockService.generate( eq( "admin" ), any(), eq( ApiKeyService.Scope.ALL ), eq( "admin" ) ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_ADMIN", rec( 11, "admin", "x", ApiKeyService.Scope.ALL ) ) );
        final HttpServletRequest request = loggedInRequest( com.wikantik.auth.Users.ADMIN,
                com.wikantik.auth.Users.ADMIN_PASS, "{\"label\":\"x\",\"scope\":\"all\"}" );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        realServlet().doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_CREATED );
        verify( mockService ).generate( "admin", "x", ApiKeyService.Scope.ALL, "admin" );
    }

    // ---- agent keys are managed only through the work registry

    private static final String AGENT_KEY_REFUSAL =
        "This key belongs to a work agent. Rotate or retire the agent on the Agents page.";

    @Test
    void rotateOfAKeyBoundToAnAgentIs409AndNothingChanges() throws Exception {
        when( mockService.findById( 5 ) ).thenReturn( Optional.of( rec( 5, "alice", "work-agent:codex", ApiKeyService.Scope.WORK ) ) );
        agentKeys.put( 5, "codex" );
        final StringWriter out = new StringWriter();
        final HttpServletResponse response = mockResponse( out );

        newServlet().doPost( postRequest( "/5/rotate", null ), response );

        verify( response ).setStatus( HttpServletResponse.SC_CONFLICT );
        assertTrue( out.toString().contains( AGENT_KEY_REFUSAL ), out.toString() );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void revokeOfAKeyBoundToAnAgentIs409AndNothingChanges() throws Exception {
        when( mockService.findById( 5 ) ).thenReturn( Optional.of( rec( 5, "alice", "work-agent:codex", ApiKeyService.Scope.WORK ) ) );
        agentKeys.put( 5, "codex" );
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getPathInfo() ).thenReturn( "/5" );
        final StringWriter out = new StringWriter();
        final HttpServletResponse response = mockResponse( out );

        newServlet().doDelete( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_CONFLICT );
        assertTrue( out.toString().contains( AGENT_KEY_REFUSAL ), out.toString() );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
    }

    @Test
    void aOnceBoundRotatedAwayKeyIsRefusedToo() throws Exception {
        // The registry reports keys that were rotated away as agent keys as well (work_agent_key_history).
        when( mockService.findById( 4 ) ).thenReturn( Optional.of( rec( 4, "alice", "work-agent:codex", ApiKeyService.Scope.WORK ) ) );
        agentKeys.put( 4, "codex" );
        final HttpServletResponse rotate = mockResponse( new StringWriter() );
        newServlet().doPost( postRequest( "/4/rotate", null ), rotate );
        verify( rotate ).setStatus( HttpServletResponse.SC_CONFLICT );
        final HttpServletRequest del = mock( HttpServletRequest.class );
        when( del.getPathInfo() ).thenReturn( "/4" );
        final HttpServletResponse revoke = mockResponse( new StringWriter() );
        newServlet().doDelete( del, revoke );
        verify( revoke ).setStatus( HttpServletResponse.SC_CONFLICT );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
    }

    @Test
    void anOrdinaryWorkKeyLabelledLikeAnAgentKeyStillRotatesAndRevokes() throws Exception {
        // The label is not the discriminator: only the registry binding is.
        when( mockService.findById( 6 ) ).thenReturn( Optional.of( rec( 6, "alice", "work-agent:x", ApiKeyService.Scope.WORK ) ) );
        when( mockService.revoke( 6, "alice" ) ).thenReturn( true );
        stubGenerate( ApiKeyService.Scope.WORK );
        final HttpServletResponse rotate = mockResponse( new StringWriter() );
        newServlet().doPost( postRequest( "/6/rotate", null ), rotate );
        verify( rotate ).setStatus( HttpServletResponse.SC_CREATED );
        verify( mockService ).revoke( 6, "alice" );
    }

    @Test
    void anAgentKeyCheckThatFailsRefusesWith503AndChangesNothing() throws Exception {
        when( mockService.findById( 5 ) ).thenReturn( Optional.of( rec( 5, "alice", "work-agent:codex", ApiKeyService.Scope.WORK ) ) );
        agentLookupFails = new java.sql.SQLException( "down", "08006" );
        final HttpServletResponse response = mockResponse( new StringWriter() );

        newServlet().doPost( postRequest( "/5/rotate", null ), response );

        verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
    }

    @Test
    void anotherUsersAgentKeyIsStill404NotAnAgentKeyOracle() throws Exception {
        when( mockService.findById( 5 ) ).thenReturn( Optional.of( rec( 5, "bob", "work-agent:codex", ApiKeyService.Scope.WORK ) ) );
        agentKeys.put( 5, "codex" );
        final HttpServletResponse response = mockResponse( new StringWriter() );
        newServlet().doPost( postRequest( "/5/rotate", null ), response );
        verify( response ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void listingMarksAgentKeysAndNamesTheAgentOnlyForARegistryReader() throws Exception {
        when( mockService.listByPrincipal( "alice" ) ).thenReturn( List.of(
            rec( 5, "alice", "work-agent:codex", ApiKeyService.Scope.WORK ),
            rec( 6, "alice", "laptop", ApiKeyService.Scope.WORK ) ) );
        agentKeys.put( 5, "codex" );
        final HttpServletRequest request = mock( HttpServletRequest.class );

        final StringWriter hidden = new StringWriter();
        newServlet().doGet( request, mockResponse( hidden ) );
        final com.google.gson.JsonArray rows = com.google.gson.JsonParser.parseString( hidden.toString() )
            .getAsJsonObject().getAsJsonArray( "keys" );
        assertTrue( rows.get( 0 ).getAsJsonObject().get( "agentKey" ).getAsBoolean() );
        assertFalse( rows.get( 0 ).getAsJsonObject().has( "agent" ), "no registry access: no agent name" );
        assertFalse( rows.get( 1 ).getAsJsonObject().get( "agentKey" ).getAsBoolean() );

        seesRegistry = true;
        final StringWriter shown = new StringWriter();
        newServlet().doGet( request, mockResponse( shown ) );
        final com.google.gson.JsonArray named = com.google.gson.JsonParser.parseString( shown.toString() )
            .getAsJsonObject().getAsJsonArray( "keys" );
        assertEquals( "codex", named.get( 0 ).getAsJsonObject().get( "agent" ).getAsString() );
        assertFalse( named.get( 1 ).getAsJsonObject().has( "agent" ) );
    }

    @Test
    void withWorkOffNoKeyIsAnAgentKey() throws Exception {
        // The real lookup: the test engine has work disabled, so the registry is absent and the answer is "no".
        final SelfApiKeysResource servlet = realServlet();
        assertEquals( Optional.empty(), servlet.agentKeyName( 5 ) );
        assertFalse( servlet.callerSeesAgentRegistry( mock( HttpServletRequest.class ) ) );
    }

    // ---- P32: with work management not running, the guard cannot tell, so a work key is not rotated

    private static final String WORK_NOT_RUNNING =
        "Work management is not running, so a key with the work scope cannot be rotated right now.";

    @Test
    void withWorkNotRunningAWorkKeyIsNotRotated() throws Exception {
        workRunning = false;
        when( mockService.findById( 5 ) ).thenReturn( Optional.of( rec( 5, "alice", "anything", ApiKeyService.Scope.WORK ) ) );
        final StringWriter out = new StringWriter();
        final HttpServletResponse response = mockResponse( out );

        newServlet().doPost( postRequest( "/5/rotate", null ), response );

        verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( out.toString().contains( WORK_NOT_RUNNING ), out.toString() );
        verify( mockService, never() ).revoke( anyInt(), anyString() );
        verify( mockService, never() ).generate( anyString(), any(), any(), anyString() );
    }

    @Test
    void withWorkNotRunningOtherKeysStillRotateAndAWorkKeyStillRevokes() throws Exception {
        workRunning = false;
        when( mockService.findById( 6 ) ).thenReturn( Optional.of( rec( 6, "alice", "ci", ApiKeyService.Scope.TOOLS ) ) );
        when( mockService.revoke( 6, "alice" ) ).thenReturn( true );
        stubGenerate( ApiKeyService.Scope.TOOLS );
        final HttpServletResponse rotate = mockResponse( new StringWriter() );
        newServlet().doPost( postRequest( "/6/rotate", null ), rotate );
        verify( rotate ).setStatus( HttpServletResponse.SC_CREATED );

        when( mockService.findById( 5 ) ).thenReturn( Optional.of( rec( 5, "alice", "w", ApiKeyService.Scope.WORK ) ) );
        when( mockService.revoke( 5, "alice" ) ).thenReturn( true );
        final HttpServletRequest del = mock( HttpServletRequest.class );
        when( del.getPathInfo() ).thenReturn( "/5" );
        final StringWriter out = new StringWriter();
        newServlet().doDelete( del, mockResponse( out ) );
        verify( mockService ).revoke( 5, "alice" );
        assertTrue( out.toString().contains( "\"success\": true" ), out.toString() );
    }

    @Test
    void withWorkRunningAnOrdinaryWorkKeyRotatesAsBefore() throws Exception {
        when( mockService.findById( 5 ) ).thenReturn( Optional.of( rec( 5, "alice", "w", ApiKeyService.Scope.WORK ) ) );
        when( mockService.revoke( 5, "alice" ) ).thenReturn( true );
        stubGenerate( ApiKeyService.Scope.WORK );
        final HttpServletResponse response = mockResponse( new StringWriter() );
        newServlet().doPost( postRequest( "/5/rotate", null ), response );
        verify( response ).setStatus( HttpServletResponse.SC_CREATED );
    }

    @Test
    void theRealWorkRunningCheckIsFalseWhenTheEngineHasNoWorkService() throws Exception {
        assertFalse( realServlet().workRunning() );
    }
}
