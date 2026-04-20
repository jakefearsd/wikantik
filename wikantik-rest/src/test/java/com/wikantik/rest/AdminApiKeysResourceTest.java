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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
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

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST-layer tests for {@link AdminApiKeysResource}. The underlying
 * {@link ApiKeyService} is mocked via {@link ApiKeyServiceHolder#setForTesting}
 * so the tests exercise parsing, validation, and JSON shaping without
 * spinning up a database.
 */
class AdminApiKeysResourceTest {

    private TestEngine engine;
    private AdminApiKeysResource servlet;
    private ApiKeyService mockService;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new AdminApiKeysResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );

        mockService = mock( ApiKeyService.class );
        ApiKeyServiceHolder.setForTesting( mockService );
    }

    @AfterEach
    void tearDown() throws Exception {
        ApiKeyServiceHolder.setForTesting( null );
        if ( engine != null ) {
            engine.stop();
        }
    }

    // ----- GET -----

    @Test
    void listReturnsMaskedRows() throws Exception {
        final ApiKeyService.Record active = new ApiKeyService.Record(
                1, "abcdef0123456789aa", "alice", "laptop", ApiKeyService.Scope.TOOLS,
                Instant.parse( "2026-04-01T10:00:00Z" ), "admin", null, null, null );
        final ApiKeyService.Record revoked = new ApiKeyService.Record(
                2, "ffffffffffffffffff", "bob", null, ApiKeyService.Scope.MCP,
                Instant.parse( "2026-04-02T10:00:00Z" ), "admin",
                Instant.parse( "2026-04-03T12:00:00Z" ),
                Instant.parse( "2026-04-04T12:00:00Z" ), "admin" );
        when( mockService.list() ).thenReturn( List.of( active, revoked ) );

        final JsonObject obj = gson.fromJson( doGet(), JsonObject.class );
        final JsonArray keys = obj.getAsJsonArray( "keys" );
        assertEquals( 2, keys.size() );

        final JsonObject row0 = keys.get( 0 ).getAsJsonObject();
        assertEquals( 1, row0.get( "id" ).getAsInt() );
        assertEquals( "alice", row0.get( "principalLogin" ).getAsString() );
        assertEquals( "tools", row0.get( "scope" ).getAsString() );
        assertEquals( "abcdef012345", row0.get( "fingerprint" ).getAsString(),
                "Fingerprint must be first 12 chars of hash, never the full value" );
        assertTrue( row0.get( "active" ).getAsBoolean() );
        assertFalse( row0.has( "revokedAt" ) && !row0.get( "revokedAt" ).isJsonNull(),
                "Active key must not report a revokedAt timestamp" );

        final JsonObject row1 = keys.get( 1 ).getAsJsonObject();
        assertFalse( row1.get( "active" ).getAsBoolean() );
        assertEquals( "2026-04-04T12:00:00Z", row1.get( "revokedAt" ).getAsString() );
    }

    @Test
    void listReturns503WhenServiceUnavailable() throws Exception {
        ApiKeyServiceHolder.setForTesting( null );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( createRequest( null, null ), response );

        verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // ----- POST -----

    @Test
    void createMintsKeyAndReturnsPlaintextOnce() throws Exception {
        final ApiKeyService.Record record = new ApiKeyService.Record(
                5, "hash-hash-hash-hash", "alice", "laptop", ApiKeyService.Scope.ALL,
                Instant.parse( "2026-04-19T09:00:00Z" ), "admin", null, null, null );
        when( mockService.generate( anyString(), any(), any( ApiKeyService.Scope.class ), any() ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_plaintext-once", record ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "alice" );
        body.addProperty( "label", "laptop" );
        body.addProperty( "scope", "all" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( createRequest( null, body.toString() ), response );

        verify( response ).setStatus( HttpServletResponse.SC_CREATED );
        final JsonObject payload = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "wkk_plaintext-once", payload.get( "token" ).getAsString(),
                "Plaintext token must appear once on create" );
        assertEquals( 5, payload.get( "id" ).getAsInt() );
        assertEquals( "all", payload.get( "scope" ).getAsString() );
    }

    @Test
    void createRejectsMissingPrincipalLogin() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "label", "laptop" );
        body.addProperty( "scope", "tools" );

        final JsonObject obj = gson.fromJson( doPost( body.toString() ), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "principalLogin" ) );
        Mockito.verifyNoInteractions( mockService );
    }

    @Test
    void createRejectsBlankPrincipalLogin() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "   " );
        body.addProperty( "scope", "tools" );

        final JsonObject obj = gson.fromJson( doPost( body.toString() ), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        Mockito.verifyNoInteractions( mockService );
    }

    @Test
    void createRejectsUnknownScope() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "alice" );
        body.addProperty( "scope", "bogus" );

        final JsonObject obj = gson.fromJson( doPost( body.toString() ), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().toLowerCase().contains( "scope" ) );
        Mockito.verifyNoInteractions( mockService );
    }

    @Test
    void createDefaultsScopeToAllWhenOmitted() throws Exception {
        final ApiKeyService.Record record = new ApiKeyService.Record(
                7, "h", "alice", null, ApiKeyService.Scope.ALL,
                Instant.now(), "admin", null, null, null );
        when( mockService.generate( anyString(), any(), any( ApiKeyService.Scope.class ), any() ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_t", record ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "alice" );

        doPost( body.toString() );

        verify( mockService ).generate(
                Mockito.eq( "alice" ), Mockito.isNull(),
                Mockito.eq( ApiKeyService.Scope.ALL ), any() );
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        final JsonObject obj = gson.fromJson( doPost( "not json" ), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        Mockito.verifyNoInteractions( mockService );
    }

    @Test
    void createSurfacesIllegalArgumentFromService() throws Exception {
        when( mockService.generate( anyString(), any(), any( ApiKeyService.Scope.class ), any() ) )
                .thenThrow( new IllegalArgumentException( "principalLogin is required" ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "alice" );
        final JsonObject obj = gson.fromJson( doPost( body.toString() ), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void createSurfacesInternalErrorFromService() throws Exception {
        when( mockService.generate( anyString(), any(), any( ApiKeyService.Scope.class ), any() ) )
                .thenThrow( new IllegalStateException( "db down" ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "alice" );
        final JsonObject obj = gson.fromJson( doPost( body.toString() ), JsonObject.class );
        assertEquals( 500, obj.get( "status" ).getAsInt() );
    }

    @Test
    void createRecordsCurrentUserAsCreatedBy() throws Exception {
        final ApiKeyService.Record record = new ApiKeyService.Record(
                9, "h", "alice", null, ApiKeyService.Scope.TOOLS,
                Instant.now(), "ops-user", null, null, null );
        when( mockService.generate( anyString(), any(), any( ApiKeyService.Scope.class ), any() ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_t", record ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "alice" );
        body.addProperty( "scope", "tools" );

        final HttpServletRequest request = createRequest( null, body.toString() );
        final Principal principal = mock( Principal.class );
        when( principal.getName() ).thenReturn( "ops-user" );
        Mockito.doReturn( principal ).when( request ).getUserPrincipal();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        verify( mockService ).generate( Mockito.eq( "alice" ), Mockito.isNull(),
                Mockito.eq( ApiKeyService.Scope.TOOLS ), Mockito.eq( "ops-user" ) );
    }

    // ----- DELETE -----

    @Test
    void revokeSucceeds() throws Exception {
        when( mockService.revoke( Mockito.eq( 42 ), any() ) ).thenReturn( true );

        final JsonObject obj = gson.fromJson( doDelete( "42" ), JsonObject.class );
        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( 42, obj.get( "id" ).getAsInt() );
    }

    @Test
    void revokeReturns404WhenAlreadyRevokedOrMissing() throws Exception {
        when( mockService.revoke( anyInt(), any() ) ).thenReturn( false );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( createRequest( "99", null ), response );

        verify( response ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void revokeRejectsMissingId() throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( createRequest( null, null ), response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        Mockito.verifyNoInteractions( mockService );
    }

    @Test
    void revokeRejectsNonNumericId() throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( createRequest( "not-a-number", null ), response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        Mockito.verifyNoInteractions( mockService );
    }

    @Test
    void adminEndpointIsNotCrossOriginAllowed() {
        assertFalse( servlet.isCrossOriginAllowed() );
    }

    // ----- Helper methods -----

    private String doGet() throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doGet( createRequest( null, null ), response );
        return sw.toString();
    }

    private String doPost( final String body ) throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPost( createRequest( null, body ), response );
        return sw.toString();
    }

    private String doDelete( final String pathId ) throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doDelete( createRequest( pathId, null ), response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pathId, final String body ) {
        final String path = pathId != null ? "/admin/apikeys/" + pathId : "/admin/apikeys";
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( path );
        Mockito.doReturn( pathId != null ? "/" + pathId : null ).when( request ).getPathInfo();
        if ( body != null ) {
            try {
                Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();
            } catch ( final Exception e ) {
                throw new RuntimeException( e );
            }
        }
        return request;
    }
}
