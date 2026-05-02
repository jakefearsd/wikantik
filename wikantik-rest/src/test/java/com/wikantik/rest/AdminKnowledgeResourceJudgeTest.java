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
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiEngine;
import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.knowledge.judge.JudgeRunner;
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
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the judge-related endpoints on {@link AdminKnowledgeResource}:
 * <ul>
 *   <li>POST /admin/knowledge-graph/judge/run — fire-and-forget runner trigger</li>
 *   <li>POST /admin/knowledge-graph/proposals/{id}/judge — synchronous judge</li>
 *   <li>GET  /admin/knowledge-graph/proposals/{id}/reviews — audit history</li>
 * </ul>
 */
class AdminKnowledgeResourceJudgeTest {

    private TestEngine engine;
    private AdminKnowledgeResource servlet;
    private KnowledgeGraphService service;
    private final Gson gson = new Gson();

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

    // --- POST /judge/run ---

    @Test
    void postJudgeRun_returns202WhenRunnerPresent() throws Exception {
        final JudgeRunner runner = Mockito.mock( JudgeRunner.class );
        ( (WikiEngine) engine ).setManager( JudgeRunner.class, runner );

        final JsonObject obj = call( request( "/judge/run" ), "POST" );
        assertEquals( "started", obj.get( "status" ).getAsString() );
    }

    @Test
    void postJudgeRun_returns503WhenRunnerAbsent() throws Exception {
        // JudgeRunner not registered in engine — no setManager call
        final JsonObject obj = call( request( "/judge/run" ), "POST" );
        assertEquals( 503, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postJudgeRun_returns400WhenSubPathMissing() throws Exception {
        final JudgeRunner runner = Mockito.mock( JudgeRunner.class );
        ( (WikiEngine) engine ).setManager( JudgeRunner.class, runner );

        final JsonObject obj = call( request( "/judge" ), "POST" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // --- POST /proposals/{id}/judge ---

    @Test
    void postProposalJudge_returnsVerdictJson() throws Exception {
        final UUID id = UUID.randomUUID();
        final JudgeVerdict verdict = new JudgeVerdict( "approved", 0.9, "looks good", "test-model" );
        Mockito.when( service.judgeNow( Mockito.eq( id ), Mockito.anyString() ) ).thenReturn( verdict );

        final JsonObject obj = call( request( "/proposals/" + id + "/judge" ), "POST" );
        assertEquals( "approved", obj.get( "verdict" ).getAsString() );
        assertEquals( 0.9, obj.get( "confidence" ).getAsDouble(), 0.001 );
        assertEquals( "looks good", obj.get( "rationale" ).getAsString() );
        assertEquals( "test-model", obj.get( "model" ).getAsString() );
    }

    @Test
    void postProposalJudge_returns503OnIllegalState() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.judgeNow( Mockito.eq( id ), Mockito.anyString() ) )
            .thenThrow( new IllegalStateException( "judge not configured" ) );

        final JsonObject obj = call( request( "/proposals/" + id + "/judge" ), "POST" );
        assertEquals( 503, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postProposalJudge_returns404OnIllegalArgument() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.judgeNow( Mockito.eq( id ), Mockito.anyString() ) )
            .thenThrow( new IllegalArgumentException( "proposal not found" ) );

        final JsonObject obj = call( request( "/proposals/" + id + "/judge" ), "POST" );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // --- GET /proposals/{id}/reviews ---

    @Test
    void getProposalReviews_returnsReviewsList() throws Exception {
        final UUID proposalId = UUID.randomUUID();
        final UUID reviewId = UUID.randomUUID();
        final KgProposalReview review = new KgProposalReview(
            reviewId, proposalId,
            KgProposalReview.REVIEWER_MACHINE, "test-model",
            "approved", 0.85, "evidence strong",
            Instant.parse( "2026-05-01T10:00:00Z" )
        );
        Mockito.when( service.listReviews( proposalId ) ).thenReturn( List.of( review ) );

        final JsonObject obj = call( request( "/proposals/" + proposalId + "/reviews" ), "GET" );
        assertTrue( obj.has( "reviews" ) );
        assertEquals( 1, obj.getAsJsonArray( "reviews" ).size() );
        final JsonObject r = obj.getAsJsonArray( "reviews" ).get( 0 ).getAsJsonObject();
        assertEquals( reviewId.toString(), r.get( "id" ).getAsString() );
        assertEquals( "machine", r.get( "reviewer_kind" ).getAsString() );
        assertEquals( "approved", r.get( "verdict" ).getAsString() );
        assertEquals( "evidence strong", r.get( "rationale" ).getAsString() );
    }

    @Test
    void getProposalReviews_emptyListWhenNoReviews() throws Exception {
        final UUID proposalId = UUID.randomUUID();
        Mockito.when( service.listReviews( proposalId ) ).thenReturn( List.of() );

        final JsonObject obj = call( request( "/proposals/" + proposalId + "/reviews" ), "GET" );
        assertTrue( obj.has( "reviews" ) );
        assertEquals( 0, obj.getAsJsonArray( "reviews" ).size() );
    }

    @Test
    void getProposalReviews_returns400ForBadUuid() throws Exception {
        final JsonObject obj = call( request( "/proposals/not-a-uuid/reviews" ), "GET" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // --- helpers ---

    private HttpServletRequest request( final String pathInfo ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/knowledge-graph" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return req;
    }

    private JsonObject call( final HttpServletRequest req, final String method ) throws Exception {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        switch ( method ) {
            case "GET"  -> servlet.doGet( req, resp );
            case "POST" -> servlet.doPost( req, resp );
            default -> fail( "unexpected method: " + method );
        }
        final String body = sw.toString();
        return body.isEmpty() ? new JsonObject() : gson.fromJson( body, JsonObject.class );
    }
}
