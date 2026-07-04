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
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.HubProposalRepository.HubProposal;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior-pinning tests for {@link HubProposalAdminHandlers}, extracted verbatim from
 * {@code AdminKnowledgeResource}. No dedicated test existed for this class before — it had
 * a repository/service/similarity trio of {@link java.util.function.Supplier}s that are
 * trivial to mock directly, so every branch is exercised here without any servlet-container
 * or {@code TestEngine} machinery.
 */
class HubProposalAdminHandlersTest {

    private final Gson gson = new Gson();

    private HubProposalRepository repo;
    private NodeMentionSimilarity similarity;
    private HubProposalService service;
    private HubProposalAdminHandlers handlers;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock( HubProposalRepository.class );
        similarity = Mockito.mock( NodeMentionSimilarity.class );
        service = Mockito.mock( HubProposalService.class );
        handlers = new HubProposalAdminHandlers( () -> repo, () -> similarity, () -> service );
    }

    private static HubProposal proposal( final int id, final String hub, final String page,
                                          final double raw, final double pct, final String status ) {
        return new HubProposal( id, hub, page, raw, pct, status, null, null, null,
                Instant.parse( "2026-05-20T10:00:00Z" ) );
    }

    // ----- GET /hub-proposals -----

    @Test
    void getHubProposalsReturns503WhenRepoNotConfigured() throws Exception {
        final HubProposalAdminHandlers noRepoHandlers =
                new HubProposalAdminHandlers( () -> null, () -> similarity, () -> service );
        final HttpServletRequest request = requestWithParams( null, null, null, null );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = responseCapturing( sw );

        noRepoHandlers.handleGetHubProposals( request, response );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "Hub proposals not configured", obj.get( "message" ).getAsString() );
    }

    @Test
    void getHubProposalsDefaultsStatusToPendingAndPagingDefaults() throws Exception {
        Mockito.when( repo.listProposals( "pending", null, 50, 0 ) ).thenReturn( List.of() );
        Mockito.when( repo.countByStatus( "pending" ) ).thenReturn( 0 );
        final HttpServletRequest request = requestWithParams( null, null, null, null );
        final StringWriter sw = new StringWriter();

        handlers.handleGetHubProposals( request, responseCapturing( sw ) );

        Mockito.verify( repo ).listProposals( "pending", null, 50, 0 );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 0, obj.get( "total" ).getAsInt() );
        assertEquals( 0, obj.getAsJsonArray( "proposals" ).size() );
    }

    @Test
    void getHubProposalsPropagatesStatusHubAndPaging() throws Exception {
        Mockito.when( repo.listProposals( "approved", "FinanceHub", 5, 10 ) )
                .thenReturn( List.of( proposal( 7, "FinanceHub", "IndexFunds", 0.9, 88.0, "approved" ) ) );
        Mockito.when( repo.countByStatus( "approved" ) ).thenReturn( 1 );
        final HttpServletRequest request = requestWithParams( "approved", "FinanceHub", "5", "10" );
        final StringWriter sw = new StringWriter();

        handlers.handleGetHubProposals( request, responseCapturing( sw ) );

        Mockito.verify( repo ).listProposals( "approved", "FinanceHub", 5, 10 );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 1, obj.get( "total" ).getAsInt() );
        final JsonObject p = obj.getAsJsonArray( "proposals" ).get( 0 ).getAsJsonObject();
        assertEquals( 7, p.get( "id" ).getAsInt() );
        assertEquals( "FinanceHub", p.get( "hub_name" ).getAsString() );
        assertEquals( "IndexFunds", p.get( "page_name" ).getAsString() );
    }

    // ----- POST /hub-proposals dispatch -----

    @Test
    void postHubProposalsMissingActionReturns400() throws Exception {
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = doPostCapturing( new String[] { "hub-proposals" }, "{}", sw );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertErrorJson( sw, HttpServletResponse.SC_BAD_REQUEST, "Action required" );
    }

    @Test
    void postHubProposalsReturns503WhenRepoNotConfigured() throws Exception {
        final HubProposalAdminHandlers noRepoHandlers =
                new HubProposalAdminHandlers( () -> null, () -> similarity, () -> service );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/hub-proposals/generate" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = responseCapturing( sw );

        noRepoHandlers.handlePostHubProposals( request, response, new String[] { "hub-proposals", "generate" } );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // ----- generate -----

    @Test
    void generateReturns412WhenSimilarityNotRegistered() throws Exception {
        final HubProposalAdminHandlers noSimHandlers =
                new HubProposalAdminHandlers( () -> repo, () -> null, () -> service );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/x" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = responseCapturing( sw );

        noSimHandlers.handlePostHubProposals( request, response, new String[] { "hub-proposals", "generate" } );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_PRECONDITION_FAILED );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "message" ).getAsString().contains( "NodeMentionSimilarity not available" ) );
        Mockito.verifyNoInteractions( service );
    }

    @Test
    void generateReturns412WhenSimilarityIndexNotReady() throws Exception {
        Mockito.when( similarity.isReady() ).thenReturn( false );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = doPostCapturing( new String[] { "hub-proposals", "generate" }, null, sw );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_PRECONDITION_FAILED );
        assertErrorJson( sw, HttpServletResponse.SC_PRECONDITION_FAILED,
                "Chunk embedding index must be populated before generating proposals" );
        Mockito.verifyNoInteractions( service );
    }

    @Test
    void generateReturns503WhenServiceNotRegistered() throws Exception {
        Mockito.when( similarity.isReady() ).thenReturn( true );
        final HubProposalAdminHandlers noServiceHandlers =
                new HubProposalAdminHandlers( () -> repo, () -> similarity, () -> null );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/x" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = responseCapturing( sw );

        noServiceHandlers.handlePostHubProposals( request, response, new String[] { "hub-proposals", "generate" } );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertErrorJson( sw, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubProposalService not available — knowledge graph not initialized" );
    }

    @Test
    void generateSuccessReportsCreatedCount() throws Exception {
        Mockito.when( similarity.isReady() ).thenReturn( true );
        Mockito.when( service.generateProposals() ).thenReturn( 4 );

        final StringWriter sw = new StringWriter();
        doPostCapturing( new String[] { "hub-proposals", "generate" }, null, sw );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "ok", obj.get( "status" ).getAsString() );
        assertEquals( 4, obj.get( "created" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "4 created" ) );
    }

    @Test
    void generateFailureReturns500WithMessage() throws Exception {
        Mockito.when( similarity.isReady() ).thenReturn( true );
        Mockito.when( service.generateProposals() ).thenThrow( new RuntimeException( "db exploded" ) );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = doPostCapturing( new String[] { "hub-proposals", "generate" }, null, sw );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
        assertErrorJson( sw, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub proposals generation failed: db exploded" );
    }

    // ----- bulk-approve / bulk-reject -----

    @Test
    void bulkApproveDefaultsReviewedByToAdminAndUpdatesStatus() throws Exception {
        final JsonObject body = new JsonObject();
        final com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        ids.add( 1 );
        ids.add( 2 );
        body.add( "ids", ids );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response =
                doPostCapturing( new String[] { "hub-proposals", "bulk-approve" }, body.toString(), sw );

        Mockito.verify( repo ).bulkUpdateStatus( List.of( 1, 2 ), "approved", "admin", null );
        Mockito.verify( response, Mockito.never() ).setStatus( Mockito.anyInt() );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "ok", obj.get( "status" ).getAsString() );
    }

    @Test
    void bulkApproveHonorsExplicitReviewedBy() throws Exception {
        final JsonObject body = new JsonObject();
        final com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        ids.add( 9 );
        body.add( "ids", ids );
        body.addProperty( "reviewedBy", "curator1" );

        doPost( new String[] { "hub-proposals", "bulk-approve" }, body.toString() );

        Mockito.verify( repo ).bulkUpdateStatus( List.of( 9 ), "approved", "curator1", null );
    }

    @Test
    void bulkRejectPassesReasonThrough() throws Exception {
        final JsonObject body = new JsonObject();
        final com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        ids.add( 3 );
        body.add( "ids", ids );
        body.addProperty( "reason", "low precision" );

        doPost( new String[] { "hub-proposals", "bulk-reject" }, body.toString() );

        Mockito.verify( repo ).bulkUpdateStatus( List.of( 3 ), "rejected", "admin", "low precision" );
    }

    @Test
    void bulkApproveMalformedJsonBodyIsRejectedBeforeRepoCall() throws Exception {
        final HttpServletResponse response =
                doPost( new String[] { "hub-proposals", "bulk-approve" }, "not-json" );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        Mockito.verify( repo, Mockito.never() ).bulkUpdateStatus( Mockito.any(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any() );
    }

    // ----- threshold-approve -----

    @Test
    void thresholdApproveMissingThresholdReturns400() throws Exception {
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response =
                doPostCapturing( new String[] { "hub-proposals", "threshold-approve" }, "{}", sw );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertErrorJson( sw, HttpServletResponse.SC_BAD_REQUEST, "threshold must be a number" );
        Mockito.verifyNoInteractions( repo );
    }

    @Test
    void thresholdApproveApprovesAllProposalsAboveThreshold() throws Exception {
        Mockito.when( repo.listProposalsAboveThreshold( 75.0 ) ).thenReturn( List.of(
                proposal( 1, "H", "P1", 0.5, 80.0, "pending" ),
                proposal( 2, "H", "P2", 0.6, 90.0, "pending" ) ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "threshold", 75.0 );
        final StringWriter sw = new StringWriter();
        doPostCapturing( new String[] { "hub-proposals", "threshold-approve" }, body.toString(), sw );

        Mockito.verify( repo ).bulkUpdateStatus( List.of( 1, 2 ), "approved", "admin", null );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "ok", obj.get( "status" ).getAsString() );
        assertEquals( 2, obj.get( "approved" ).getAsInt() );
    }

    // ----- per-id approve/reject -----

    @Test
    void byIdActionTooFewSegmentsReturns404() throws Exception {
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = doPostCapturing( new String[] { "hub-proposals", "7" }, null, sw );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        assertErrorJson( sw, HttpServletResponse.SC_NOT_FOUND, "Unknown hub-proposals action" );
    }

    @Test
    void byIdActionNonNumericIdReturns400() throws Exception {
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response =
                doPostCapturing( new String[] { "hub-proposals", "notanumber", "approve" }, null, sw );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertErrorJson( sw, HttpServletResponse.SC_BAD_REQUEST, "Invalid proposal ID" );
        Mockito.verifyNoInteractions( repo );
    }

    @Test
    void byIdApproveUpdatesStatusToApproved() throws Exception {
        final StringWriter sw = new StringWriter();
        doPostCapturing( new String[] { "hub-proposals", "42", "approve" }, null, sw );

        Mockito.verify( repo ).updateStatus( 42, "approved", "admin", null );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "ok", obj.get( "status" ).getAsString() );
    }

    @Test
    void byIdRejectUpdatesStatusToRejectedWithReason() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "reason", "duplicate hub" );

        doPost( new String[] { "hub-proposals", "13", "reject" }, body.toString() );

        Mockito.verify( repo ).updateStatus( 13, "rejected", "admin", "duplicate hub" );
    }

    @Test
    void byIdUnknownActionReturns404() throws Exception {
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response =
                doPostCapturing( new String[] { "hub-proposals", "13", "delete" }, null, sw );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        assertErrorJson( sw, HttpServletResponse.SC_NOT_FOUND, "Unknown action: delete" );
        Mockito.verifyNoInteractions( repo );
    }

    // ----- helpers -----

    private void assertErrorJson( final StringWriter sw, final int expectedStatus, final String expectedMessage ) {
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean(), "Response should be an error payload: " + sw );
        assertEquals( expectedStatus, obj.get( "status" ).getAsInt() );
        assertEquals( expectedMessage, obj.get( "message" ).getAsString() );
    }

    private HttpServletRequest requestWithParams( final String status, final String hub,
                                                    final String limit, final String offset ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/hub-proposals" );
        Mockito.doReturn( status ).when( request ).getParameter( "status" );
        Mockito.doReturn( hub ).when( request ).getParameter( "hub" );
        Mockito.doReturn( limit ).when( request ).getParameter( "limit" );
        Mockito.doReturn( offset ).when( request ).getParameter( "offset" );
        return request;
    }

    private HttpServletResponse responseCapturing( final StringWriter sw ) throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        return response;
    }

    private HttpServletResponse doPost( final String[] segments, final String body ) throws Exception {
        return doPostCapturing( segments, body, new StringWriter() );
    }

    private HttpServletResponse doPostCapturing( final String[] segments, final String body, final StringWriter sw )
            throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/" + String.join( "/", segments ) );
        if ( body != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();
        }
        final HttpServletResponse response = responseCapturing( sw );

        handlers.handlePostHubProposals( request, response, segments );
        return response;
    }
}
