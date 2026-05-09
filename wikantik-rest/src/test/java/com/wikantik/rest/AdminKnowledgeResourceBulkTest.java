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
import com.wikantik.WikiEngine;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.JudgeVerdict;

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
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mockito unit tests for the {@code POST /admin/knowledge-graph/proposals/bulk-action}
 * endpoint added in Phase 2C.
 *
 * <p>Covers: happy path per action, partial failure, missing/unknown action,
 * missing/empty ids, missing reason for reject (→ 400), and judge-throws-exception
 * surfacing as a per-id failure with the exception message.
 */
class AdminKnowledgeResourceBulkTest {

    private TestEngine engine;
    private AdminKnowledgeResource servlet;
    private KnowledgeGraphService service;
    private final Gson gson = new Gson();

    private static final UUID P1 = UUID.fromString( "aaaaaaaa-0000-0000-0000-000000000001" );
    private static final UUID P2 = UUID.fromString( "bbbbbbbb-0000-0000-0000-000000000002" );

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        service = Mockito.mock( KnowledgeGraphService.class );
        ( (WikiEngine) engine ).setManager( KnowledgeGraphService.class, service );

        servlet = new AdminKnowledgeResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    // ---- approve happy path ----

    @Test
    void approve_happyPath_returnsSucceededList() throws Exception {
        Mockito.when( service.approveProposal( P1, "admin" ) ).thenReturn( proposal( P1, "approved" ) );
        Mockito.when( service.getProposal( P1 ) ).thenReturn( proposal( P1, "approved" ) );

        final JsonObject result = bulkAction( "approve", new UUID[]{ P1 }, null );

        assertEquals( "completed", result.get( "status" ).getAsString() );
        final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
        assertEquals( 1, succeeded.size() );
        assertEquals( P1.toString(), succeeded.get( 0 ).getAsString() );
        assertEquals( 0, result.getAsJsonArray( "failed" ).size() );
    }

    // ---- reject happy path ----

    @Test
    void reject_happyPath_returnsSucceededList() throws Exception {
        Mockito.when( service.rejectProposal( P1, "admin", "duplicate" ) ).thenReturn( proposal( P1, "rejected" ) );
        Mockito.when( service.getProposal( P1 ) ).thenReturn( proposal( P1, "rejected" ) );

        final JsonObject result = bulkAction( "reject", new UUID[]{ P1 }, "duplicate" );

        assertEquals( "completed", result.get( "status" ).getAsString() );
        assertEquals( 1, result.getAsJsonArray( "succeeded" ).size() );
        assertEquals( 0, result.getAsJsonArray( "failed" ).size() );
    }

    // ---- judge happy path ----

    @Test
    void judge_happyPath_returnsSucceededList() throws Exception {
        Mockito.when( service.judgeNow( P1, "admin" ) ).thenReturn( verdict() );

        final JsonObject result = bulkAction( "judge", new UUID[]{ P1 }, null );

        assertEquals( "completed", result.get( "status" ).getAsString() );
        assertEquals( 1, result.getAsJsonArray( "succeeded" ).size() );
        assertEquals( 0, result.getAsJsonArray( "failed" ).size() );
    }

    // ---- partial failure ----

    @Test
    void approve_partialFailure_splitsIntoSucceededAndFailed() throws Exception {
        Mockito.when( service.approveProposal( P1, "admin" ) ).thenReturn( proposal( P1, "approved" ) );
        Mockito.when( service.getProposal( P1 ) ).thenReturn( proposal( P1, "approved" ) );
        // P2 returns null → "Not found"
        Mockito.when( service.approveProposal( P2, "admin" ) ).thenReturn( null );

        final JsonObject result = bulkAction( "approve", new UUID[]{ P1, P2 }, null );

        assertEquals( "completed", result.get( "status" ).getAsString() );
        assertEquals( 1, result.getAsJsonArray( "succeeded" ).size() );
        assertEquals( 1, result.getAsJsonArray( "failed" ).size() );
        final JsonObject failedEntry = result.getAsJsonArray( "failed" ).get( 0 ).getAsJsonObject();
        assertEquals( P2.toString(), failedEntry.get( "id" ).getAsString() );
        assertTrue( failedEntry.get( "error" ).getAsString().contains( P2.toString() ) );
    }

    @Test
    void reject_partialFailure_splitsResults() throws Exception {
        Mockito.when( service.rejectProposal( P1, "admin", "spam" ) ).thenReturn( proposal( P1, "rejected" ) );
        Mockito.when( service.getProposal( P1 ) ).thenReturn( proposal( P1, "rejected" ) );
        Mockito.when( service.rejectProposal( P2, "admin", "spam" ) ).thenReturn( null );

        final JsonObject result = bulkAction( "reject", new UUID[]{ P1, P2 }, "spam" );

        assertEquals( 1, result.getAsJsonArray( "succeeded" ).size() );
        assertEquals( 1, result.getAsJsonArray( "failed" ).size() );
    }

    @Test
    void judge_throwingException_surfacesAsPerIdFailure() throws Exception {
        Mockito.when( service.judgeNow( P1, "admin" ) )
                .thenThrow( new IllegalStateException( "judge timeout" ) );

        final JsonObject result = bulkAction( "judge", new UUID[]{ P1 }, null );

        assertEquals( "completed", result.get( "status" ).getAsString() );
        assertEquals( 0, result.getAsJsonArray( "succeeded" ).size() );
        final JsonObject failedEntry = result.getAsJsonArray( "failed" ).get( 0 ).getAsJsonObject();
        assertEquals( P1.toString(), failedEntry.get( "id" ).getAsString() );
        assertEquals( "judge timeout", failedEntry.get( "error" ).getAsString() );
    }

    // ---- request-level validation ----

    @Test
    void missingAction_returns400() throws Exception {
        final JsonObject body = new JsonObject();
        final JsonArray ids = new JsonArray();
        ids.add( P1.toString() );
        body.add( "ids", ids );

        final JsonObject result = callWithBody( "/proposals/bulk-action", "POST", body );
        assertEquals( 400, result.get( "status" ).getAsInt() );
    }

    @Test
    void unknownAction_returns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", "delete" );
        final JsonArray ids = new JsonArray();
        ids.add( P1.toString() );
        body.add( "ids", ids );

        final JsonObject result = callWithBody( "/proposals/bulk-action", "POST", body );
        assertEquals( 400, result.get( "status" ).getAsInt() );
    }

    @Test
    void missingIds_returns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", "approve" );

        final JsonObject result = callWithBody( "/proposals/bulk-action", "POST", body );
        assertEquals( 400, result.get( "status" ).getAsInt() );
    }

    @Test
    void emptyIds_returns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", "approve" );
        body.add( "ids", new JsonArray() );

        final JsonObject result = callWithBody( "/proposals/bulk-action", "POST", body );
        assertEquals( 400, result.get( "status" ).getAsInt() );
    }

    @Test
    void rejectWithoutReason_returns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", "reject" );
        final JsonArray ids = new JsonArray();
        ids.add( P1.toString() );
        body.add( "ids", ids );
        // no reason field

        final JsonObject result = callWithBody( "/proposals/bulk-action", "POST", body );
        assertEquals( 400, result.get( "status" ).getAsInt() );
    }

    @Test
    void rejectWithBlankReason_returns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", "reject" );
        body.addProperty( "reason", "   " );
        final JsonArray ids = new JsonArray();
        ids.add( P1.toString() );
        body.add( "ids", ids );

        final JsonObject result = callWithBody( "/proposals/bulk-action", "POST", body );
        assertEquals( 400, result.get( "status" ).getAsInt() );
    }

    @Test
    void malformedUuidInIds_surfacesAsPerIdFailure() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", "approve" );
        final JsonArray ids = new JsonArray();
        ids.add( "not-a-uuid" );
        body.add( "ids", ids );

        final JsonObject result = callWithBody( "/proposals/bulk-action", "POST", body );
        // Should still complete with failed entry, not a 400 at request level
        assertEquals( "completed", result.get( "status" ).getAsString() );
        assertEquals( 1, result.getAsJsonArray( "failed" ).size() );
    }

    @Test
    void messageReflectsSucceededCount() throws Exception {
        Mockito.when( service.approveProposal( P1, "admin" ) ).thenReturn( proposal( P1, "approved" ) );
        Mockito.when( service.getProposal( P1 ) ).thenReturn( proposal( P1, "approved" ) );

        final JsonObject result = bulkAction( "approve", new UUID[]{ P1 }, null );

        assertTrue( result.get( "message" ).getAsString().contains( "1 of 1" ) );
    }

    // ---- helpers ----

    private JsonObject bulkAction( final String action, final UUID[] ids, final String reason )
            throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", action );
        final JsonArray idsArr = new JsonArray();
        for ( final UUID id : ids ) {
            idsArr.add( id.toString() );
        }
        body.add( "ids", idsArr );
        if ( reason != null ) {
            body.addProperty( "reason", reason );
        }
        return callWithBody( "/proposals/bulk-action", "POST", body );
    }

    private HttpServletRequest request( final String pathInfo ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
                "/admin/knowledge-graph" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return req;
    }

    private JsonObject callWithBody( final String pathInfo, final String method, final JsonObject body )
            throws Exception {
        final HttpServletRequest req = request( pathInfo );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) )
                .when( req ).getReader();
        return call( req, method );
    }

    private JsonObject call( final HttpServletRequest req, final String method ) throws Exception {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        switch ( method ) {
            case "GET"    -> servlet.doGet( req, resp );
            case "POST"   -> servlet.doPost( req, resp );
            case "DELETE" -> servlet.doDelete( req, resp );
            default -> fail( "unexpected method: " + method );
        }
        final String responseBody = sw.toString();
        return responseBody.isEmpty() ? new JsonObject() : gson.fromJson( responseBody, JsonObject.class );
    }

    private static KgProposal proposal( final UUID id, final String status ) {
        return new KgProposal( id, "new-edge", "TestPage.md", Map.<String, Object>of(),
                0.8, "test reasoning", status, null,
                Instant.parse( "2026-05-01T10:00:00Z" ), null,
                "none", null, null, null, null );
    }

    private static JudgeVerdict verdict() {
        return new JudgeVerdict( "approved", 0.9, "looks good", "test-model" );
    }
}
