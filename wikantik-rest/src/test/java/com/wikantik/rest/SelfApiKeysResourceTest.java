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
    void setUp() throws Exception {
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
        assertTrue( sw.toString().contains( "\"success\": true" ) );
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
