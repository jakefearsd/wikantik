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

import com.wikantik.api.core.Engine;
import com.wikantik.knowledge.HubDiscoveryException;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubDiscoveryRepository.DismissedProposal;
import com.wikantik.knowledge.HubDiscoveryRepository.HubDiscoveryProposal;
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubDiscoveryService.AcceptResult;
import com.wikantik.knowledge.HubDiscoveryService.RunSummary;
import com.wikantik.knowledge.HubNameCollisionException;
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.HubOverviewException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminHubDiscoveryResourceTest {

    private HubDiscoveryService service;
    private HubDiscoveryRepository repo;
    private Engine engine;
    private AdminHubDiscoveryResource resource;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter respBody;

    @BeforeEach
    void setUp() throws Exception {
        service = mock( HubDiscoveryService.class );
        repo = mock( HubDiscoveryRepository.class );
        engine = mock( Engine.class );
        when( engine.getManager( HubDiscoveryService.class ) ).thenReturn( service );
        when( engine.getManager( HubDiscoveryRepository.class ) ).thenReturn( repo );

        resource = new AdminHubDiscoveryResource();
        resource.setEngine( engine );

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        respBody = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( respBody ) );
    }

    // ---- /run ----

    @Test
    void postRun_happyPath_returnsSummary() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( service.runDiscovery() ).thenReturn( new RunSummary( 5, 142, 37, 0, 814 ) );

        resource.doPost( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "\"proposalsCreated\": 5" ) || body.contains( "\"proposalsCreated\":5" ),
            "Expected proposalsCreated:5 in: " + body );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void postRun_serviceUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( engine.getManager( HubDiscoveryService.class ) ).thenReturn( null );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // ---- /proposals ----

    @Test
    void getProposals_returnsList() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals" );
        when( req.getParameter( "limit" ) ).thenReturn( "10" );
        when( req.getParameter( "offset" ) ).thenReturn( "0" );

        final HubDiscoveryProposal proposal = new HubDiscoveryProposal(
            1, "JavaHub", "Java", List.of( "Java", "Kotlin", "Scala" ), 0.87, Instant.now() );
        when( repo.list( 10, 0 ) ).thenReturn( List.of( proposal ) );
        when( repo.count() ).thenReturn( 1 );

        resource.doGet( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "JavaHub" ), "Expected JavaHub in: " + body );
        assertTrue( body.contains( "\"total\"" ), "Expected total in: " + body );
    }

    @Test
    void getProposals_limitCappedTo200() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals" );
        when( req.getParameter( "limit" ) ).thenReturn( "9999" );
        when( req.getParameter( "offset" ) ).thenReturn( "0" );
        when( repo.list( 200, 0 ) ).thenReturn( List.of() );
        when( repo.count() ).thenReturn( 0 );

        resource.doGet( req, resp );

        verify( repo ).list( 200, 0 );
    }

    // ---- /proposals/{id}/accept ----

    @Test
    void postAccept_happyPath_returns200() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"name\":\"JavaHub\",\"members\":[\"Java\",\"Kotlin\",\"Scala\"]}" ) ) );
        when( service.acceptProposal( 17, "JavaHub", List.of( "Java", "Kotlin", "Scala" ), "admin" ) )
            .thenReturn( new AcceptResult( "JavaHub", 3 ) );
        when( req.getRemoteUser() ).thenReturn( "admin" );

        resource.doPost( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "JavaHub" ), "Expected JavaHub in: " + body );
    }

    @Test
    void postAccept_collision_returns409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"name\":\"JavaHub\",\"members\":[\"Java\",\"Kotlin\"]}" ) ) );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( service.acceptProposal( anyInt(), anyString(), anyList(), anyString() ) )
            .thenThrow( new HubNameCollisionException( "JavaHub" ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
        assertTrue( respBody.toString().contains( "JavaHub" ), "Expected JavaHub in error: " + respBody );
    }

    @Test
    void postAccept_notFound_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"name\":\"JavaHub\",\"members\":[\"Java\",\"Kotlin\"]}" ) ) );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( service.acceptProposal( anyInt(), anyString(), anyList(), anyString() ) )
            .thenThrow( new HubDiscoveryException( "not found", null ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void postAccept_badMember_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"name\":\"JavaHub\",\"members\":[\"Evil\"]}" ) ) );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( service.acceptProposal( anyInt(), anyString(), anyList(), anyString() ) )
            .thenThrow( new IllegalArgumentException( "Member not in original proposal: Evil" ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void postAccept_emptyName_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"name\":\"\",\"members\":[\"Java\",\"Kotlin\"]}" ) ) );
        when( req.getRemoteUser() ).thenReturn( "admin" );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( service, never() ).acceptProposal( anyInt(), anyString(), anyList(), anyString() );
    }

    @Test
    void postAccept_emptyMembers_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"name\":\"JavaHub\",\"members\":[]}" ) ) );
        when( req.getRemoteUser() ).thenReturn( "admin" );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( service, never() ).acceptProposal( anyInt(), anyString(), anyList(), anyString() );
    }

    // ---- /proposals/{id}/dismiss ----

    @Test
    void postDismiss_happyPath_returns204() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/dismiss" );
        when( req.getRemoteUser() ).thenReturn( "admin" );

        resource.doPost( req, resp );

        verify( service ).dismissProposal( 17, "admin" );
        verify( resp ).setStatus( HttpServletResponse.SC_NO_CONTENT );
    }

    @Test
    void postDismiss_notFound_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/dismiss" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        doThrow( new HubDiscoveryException( "not found", null ) )
            .when( service ).dismissProposal( 17, "admin" );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // ---- /proposals/dismissed ----

    @Test
    void getDismissed_returnsList() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed" );
        when( req.getParameter( "limit" ) ).thenReturn( "25" );
        when( req.getParameter( "offset" ) ).thenReturn( "0" );

        final DismissedProposal row = new DismissedProposal(
            42, "JavaHub", "Java", List.of( "Java", "Kotlin", "Scala" ), 0.87,
            Instant.parse( "2026-04-01T00:00:00Z" ), "alice",
            Instant.parse( "2026-04-02T00:00:00Z" ) );
        when( repo.listDismissed( 25, 0 ) ).thenReturn( List.of( row ) );
        when( repo.countDismissed() ).thenReturn( 1 );

        resource.doGet( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "\"total\"" ), "Expected total in: " + body );
        assertTrue( body.contains( "JavaHub" ), "Expected JavaHub in: " + body );
        assertTrue( body.contains( "alice" ), "Expected reviewedBy in: " + body );
        assertTrue( body.contains( "2026-04-02" ), "Expected reviewedAt in: " + body );
    }

    @Test
    void getDismissed_limitCappedTo200() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed" );
        when( req.getParameter( "limit" ) ).thenReturn( "9999" );
        when( req.getParameter( "offset" ) ).thenReturn( "0" );
        when( repo.listDismissed( 200, 0 ) ).thenReturn( List.of() );
        when( repo.countDismissed() ).thenReturn( 0 );

        resource.doGet( req, resp );

        verify( repo ).listDismissed( 200, 0 );
    }

    @Test
    void getDismissed_serviceUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed" );
        when( engine.getManager( HubDiscoveryRepository.class ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // ---- DELETE /proposals/dismissed/{id} ----

    @Test
    void deleteDismissed_happyPath_returns204() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed/17" );
        when( repo.deleteDismissed( 17 ) ).thenReturn( true );

        resource.doDelete( req, resp );

        verify( repo ).deleteDismissed( 17 );
        verify( resp ).setStatus( HttpServletResponse.SC_NO_CONTENT );
    }

    @Test
    void deleteDismissed_missingOrNotDismissed_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed/17" );
        when( repo.deleteDismissed( 17 ) ).thenReturn( false );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void deleteDismissed_unknownDeletePath_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/bogus" );

        resource.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        verify( repo, never() ).deleteDismissed( anyInt() );
    }

    // ---- POST /proposals/dismissed/bulk-delete ----

    @Test
    void bulkDeleteDismissed_happyPath_returnsDeletedCount() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed/bulk-delete" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"ids\":[1,2,3]}" ) ) );
        when( repo.deleteDismissedBulk( List.of( 1, 2, 3 ) ) ).thenReturn( 3 );

        resource.doPost( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "\"deleted\"" ) && body.contains( "3" ),
            "Expected deleted:3 in: " + body );
        verify( repo ).deleteDismissedBulk( List.of( 1, 2, 3 ) );
    }

    @Test
    void bulkDeleteDismissed_emptyIds_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed/bulk-delete" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{\"ids\":[]}" ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( repo, never() ).deleteDismissedBulk( anyList() );
    }

    @Test
    void bulkDeleteDismissed_missingIds_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed/bulk-delete" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "{}" ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( repo, never() ).deleteDismissedBulk( anyList() );
    }

    @Test
    void bulkDeleteDismissed_tooManyIds_returns400() throws Exception {
        final StringBuilder sb = new StringBuilder( "{\"ids\":[" );
        for ( int i = 0; i < 501; i++ ) {
            if ( i > 0 ) sb.append( ',' );
            sb.append( i );
        }
        sb.append( "]}" );
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed/bulk-delete" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( sb.toString() ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( repo, never() ).deleteDismissedBulk( anyList() );
    }

    @Test
    void bulkDeleteDismissed_invalidJson_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/dismissed/bulk-delete" );
        when( req.getReader() ).thenReturn( new BufferedReader(
            new StringReader( "not-json" ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verify( repo, never() ).deleteDismissedBulk( anyList() );
    }

    // ---- unknown path ----

    @Test
    void unknownPath_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/bogus" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // ---- /hubs ----

    @Test
    void getHubs_returnsListSortedByService() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs" );

        final HubOverviewService overview = mock( HubOverviewService.class );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( overview );
        when( overview.listHubOverviews() ).thenReturn( List.of(
            new HubOverviewService.HubOverviewSummary( "AHub", 3, 5, 1, 0.42, true ),
            new HubOverviewService.HubOverviewSummary( "BHub", 2, 0, 0, 0.71, true )
        ) );

        resource.doGet( req, resp );

        final String body = respBody.toString();
        assertTrue( body.contains( "AHub" ), "Expected AHub in: " + body );
        assertTrue( body.contains( "BHub" ), "Expected BHub in: " + body );
        assertTrue( body.contains( "\"total\"" ) );
        assertTrue( body.contains( "\"hubs\"" ) );
    }

    @Test
    void getHubs_serviceUnavailable_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/hubs" );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }
}
