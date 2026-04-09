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
import com.wikantik.knowledge.HubDiscoveryRepository.HubDiscoveryProposal;
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubDiscoveryService.AcceptResult;
import com.wikantik.knowledge.HubDiscoveryService.RunSummary;
import com.wikantik.knowledge.HubNameCollisionException;

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
        when( service.runDiscovery() ).thenReturn( new RunSummary( 5, 142, 37, 814 ) );

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

    // ---- unknown path ----

    @Test
    void unknownPath_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/bogus" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }
}
