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
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.judge.KgJudgeConfig;
import com.wikantik.knowledge.judge.KgMaterializationService;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-level unit tests for GET /admin/knowledge-graph/judge/status.
 */
class AdminKnowledgeResourceJudgeStatusTest {

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

    private static JudgeRunner realRunner() {
        final KgProposalRepository proposals = Mockito.mock( KgProposalRepository.class );
        final KgRejectionRepository rejections = Mockito.mock( KgRejectionRepository.class );
        Mockito.when( proposals.getProposalsForJudging( Mockito.anyInt() ) ).thenReturn( List.of() );
        final KgMaterializationService mat = new KgMaterializationService(
            Mockito.mock( KgNodeRepository.class ), Mockito.mock( KgEdgeRepository.class ),
            proposals, rejections );
        final KgJudgeConfig cfg = new KgJudgeConfig( true, "x", "test-model",
            false, 5, 50, 1, 30, 3, "30m" );
        return new JudgeRunner( proposals, rejections,
            Mockito.mock( com.wikantik.api.knowledge.KgProposalJudgeService.class ), mat, cfg );
    }

    @Test
    void getJudgeStatus_returns200WithExpectedKeysWhenRunnerRegistered() throws Exception {
        Mockito.when( service.countPendingUnjudgedProposals() ).thenReturn( 3L );
        final JudgeRunner runner = realRunner();
        ( (WikiEngine) engine ).setManager( JudgeRunner.class, runner );
        // Re-initialize the servlet so it definitely picks up the current engine state.
        // This guards against a known test-isolation pattern where the servlet's engine
        // field, set during @BeforeEach, can diverge when multiple tests run in sequence
        // (WikiEngine.getInstance static synchronization interacts with successive
        // TestEngine constructions storing themselves in fresh mock servlet contexts).
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );

        final JsonObject obj = call( request( "/judge/status" ) );

        assertTrue( obj.get( "configured" ).getAsBoolean() );
        assertFalse( obj.get( "in_flight" ).getAsBoolean() );
        assertEquals( 3, obj.get( "queue_depth" ).getAsInt() );
        assertTrue( obj.has( "last_run_submitted" ) );
        assertTrue( obj.has( "last_run_completed" ) );
        assertTrue( obj.has( "last_run_started_at" ) );
        assertTrue( obj.has( "last_run_finished_at" ) );
        assertTrue( obj.has( "last_run_error" ) );
    }

    @Test
    void getJudgeStatus_returns200WithConfiguredFalseWhenRunnerNotRegistered() throws Exception {
        Mockito.when( service.countPendingUnjudgedProposals() ).thenReturn( 5L );
        // JudgeRunner not registered

        final JsonObject obj = call( request( "/judge/status" ) );

        assertFalse( obj.get( "configured" ).getAsBoolean() );
        assertFalse( obj.get( "in_flight" ).getAsBoolean() );
        assertEquals( 5, obj.get( "queue_depth" ).getAsInt() );
    }

    @Test
    void getJudgeStatus_returns400WhenSubPathMissing() throws Exception {
        final JsonObject obj = call( request( "/judge" ) );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void getJudgeStatus_returns400WhenSubPathIsNotStatus() throws Exception {
        final JsonObject obj = call( request( "/judge/run" ) );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // --- helpers ---

    private HttpServletRequest request( final String pathInfo ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/admin/knowledge-graph" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return req;
    }

    private JsonObject call( final HttpServletRequest req ) throws Exception {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doGet( req, resp );
        final String body = sw.toString();
        return body.isEmpty() ? new JsonObject() : gson.fromJson( body, JsonObject.class );
    }
}
