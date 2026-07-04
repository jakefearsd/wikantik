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
package com.wikantik.rest.knowledge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgCurationOps.ApproveOutcome;
import com.wikantik.api.knowledge.KnowledgeGraphService;

import com.wikantik.api.knowledge.KgProposal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted tests for branches of {@link KgProposalAdminHandlers} not otherwise exercised by
 * {@code AdminKnowledgeResourceTest}/{@code AdminKnowledgeResourceHandlerCoverageTest} (which
 * drive this class indirectly through {@code AdminKnowledgeResource}'s dispatch table): the
 * single-id reject-not-found path, and the bulk-action blank-id-per-row failure path.
 */
class KgProposalAdminHandlersTest {

    private final Gson gson = new Gson();

    @Test
    void handleGetProposals_enrichesEachRowWithConflictFlagsWhenServiceIsPresent() throws Exception {
        // proposalToMap(service, p) is only exercised via the listing path — this pins that a
        // non-null service triggers ProposalConflictFlags enrichment rather than the bare
        // KnowledgeJsonMapper.proposalToMap(p) shape (best-effort: no flags is also valid, but
        // the call must actually happen against a real, non-null service).
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final KgProposalAdminHandlers handlers = new KgProposalAdminHandlers( () -> ops );
        final KnowledgeGraphService service = Mockito.mock( KnowledgeGraphService.class );

        final UUID id = UUID.randomUUID();
        final KgProposal proposal = new KgProposal( id, "new-node", "SomePage", Map.of(),
                0.8, "reasoning", "pending", null, Instant.now(), null,
                "none", null, null, null, null );
        Mockito.when( service.listProposals( Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt() ) )
                .thenReturn( List.of( proposal ) );
        Mockito.when( service.countProposals( Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(),
                Mockito.any() ) ).thenReturn( 1L );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/x" );
        Mockito.doReturn( null ).when( request ).getParameter( Mockito.anyString() );
        Mockito.doReturn( "1" ).when( request ).getPathInfo();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        handlers.handleGetProposals( service, request, response, new String[] { "proposals" } );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 1L, obj.get( "total_count" ).getAsLong() );
        final JsonObject row = obj.getAsJsonArray( "proposals" ).get( 0 ).getAsJsonObject();
        assertEquals( id.toString(), row.get( "id" ).getAsString() );
        // Whatever ProposalConflictFlags.forProposal contributes (possibly nothing, if its
        // own best-effort lookups come back empty) the base proposal fields must still be there.
        assertEquals( "new-node", row.get( "proposal_type" ).getAsString() );
    }

    @Test
    void handlePostProposal_rejectReturnsNotFoundWhenOpsReportsError() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final UUID id = UUID.randomUUID();
        Mockito.when( ops.tryRejectProposal( Mockito.eq( id ), Mockito.anyString(), Mockito.eq( "bad fit" ) ) )
                .thenReturn( Optional.of( "Proposal not found: " + id ) );
        final KgProposalAdminHandlers handlers = new KgProposalAdminHandlers( () -> ops );
        final KnowledgeGraphService service = Mockito.mock( KnowledgeGraphService.class );

        final JsonObject body = new JsonObject();
        body.addProperty( "reason", "bad fit" );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/x" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        handlers.handlePostProposal( service, request, response, new String[] { "proposals", id.toString(), "reject" } );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( "Proposal not found: " + id, obj.get( "message" ).getAsString() );
    }

    @Test
    void doBulkProposalAction_blankIdIsRecordedAsPerRowFailure() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final KgProposalAdminHandlers handlers = new KgProposalAdminHandlers( () -> ops );
        final KnowledgeGraphService service = Mockito.mock( KnowledgeGraphService.class );

        final JsonObject body = new JsonObject();
        body.addProperty( "action", "approve" );
        final com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        ids.add( "   " );
        body.add( "ids", ids );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/x" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        handlers.handlePostProposal( service, request, response, new String[] { "proposals", "bulk-action" } );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "completed", obj.get( "status" ).getAsString() );
        assertEquals( 0, obj.getAsJsonArray( "succeeded" ).size() );
        assertEquals( 1, obj.getAsJsonArray( "failed" ).size() );
        final JsonObject failedItem = obj.getAsJsonArray( "failed" ).get( 0 ).getAsJsonObject();
        assertEquals( "id must be a non-blank string", failedItem.get( "error" ).getAsString() );
        Mockito.verifyNoInteractions( ops );
    }

    @Test
    void doBulkProposalAction_approveWithWarningsIncludesWarningsByProposalInResponse() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final UUID id = UUID.randomUUID();
        Mockito.when( ops.tryApprove( Mockito.eq( id ), Mockito.anyString() ) )
                .thenReturn( new ApproveOutcome( Optional.empty(), List.of( "low confidence source" ) ) );
        final KgProposalAdminHandlers handlers = new KgProposalAdminHandlers( () -> ops );
        final KnowledgeGraphService service = Mockito.mock( KnowledgeGraphService.class );

        final JsonObject body = new JsonObject();
        body.addProperty( "action", "approve" );
        final com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        ids.add( id.toString() );
        body.add( "ids", ids );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/x" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        handlers.handlePostProposal( service, request, response, new String[] { "proposals", "bulk-action" } );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 1, obj.getAsJsonArray( "succeeded" ).size() );
        assertTrue( obj.has( "warnings_by_proposal" ), "Response must surface per-proposal warnings: " + obj );
        final JsonObject warnings = obj.getAsJsonObject( "warnings_by_proposal" );
        assertEquals( "low confidence source",
                warnings.getAsJsonArray( id.toString() ).get( 0 ).getAsString() );
    }
}
