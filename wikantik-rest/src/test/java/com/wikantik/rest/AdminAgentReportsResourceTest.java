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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiEngine;
import com.wikantik.agentreports.AgentReport;
import com.wikantik.agentreports.AgentReportService;
import com.wikantik.agentreports.ReportQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAgentReportsResource}. Non-admin refusal (403) is {@link AdminAuthFilter}'s job and
 * is covered by its own tests; this resource is only reached by an admin.
 */
class AdminAgentReportsResourceTest {

    private AgentReportService service;
    private WikiEngine engine;
    private AdminAgentReportsResource resource;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        service = mock( AgentReportService.class );
        engine = mock( WikiEngine.class );
        when( engine.agentReportService() ).thenReturn( service );
        resource = new AdminAgentReportsResource();
        resource.setEngine( engine );
        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );
    }

    private static AgentReport report( final String kind ) {
        return new AgentReport( 1L, Instant.parse( "2026-11-25T00:00:00Z" ), "claude-code", "h", "jake", "ws", kind,
                List.of( "wikantik" ), List.of(), null, null, "t", "done", List.of(), null, List.of(), null );
    }

    @Test
    void kindOfWorkFilterReachesTheStoreAndOnlyThoseReportsComeBack() throws Exception {
        when( req.getParameter( "kind_of_work" ) ).thenReturn( "build" );
        when( service.list( any() ) ).thenReturn( List.of( report( "build" ) ) );
        resource.doGet( req, resp );
        final ArgumentCaptor< ReportQuery > c = ArgumentCaptor.forClass( ReportQuery.class );
        verify( service ).list( c.capture() );
        assertEquals( "build", c.getValue().kindOfWork() );
        final JsonObject o = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( 1, o.get( "count" ).getAsInt() );
        assertEquals( "build", o.getAsJsonArray( "reports" ).get( 0 ).getAsJsonObject().get( "kind_of_work" ).getAsString() );
    }

    @Test
    void badSinceIs400() throws Exception {
        when( req.getParameter( "since" ) ).thenReturn( "yesterday" );
        resource.doGet( req, resp );
        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( body.toString().contains( "since" ) );
    }

    @Test
    void unavailableStoreIs503() throws Exception {
        when( engine.agentReportService() ).thenReturn( null );
        resource.doGet( req, resp );
        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( body.toString().contains( "report store unavailable" ) );
    }

    @Test
    void storeFailureIs500NotAThrow() throws Exception {
        when( service.list( any() ) ).thenThrow( new IllegalStateException( "db down" ) );
        resource.doGet( req, resp );
        verify( resp ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
    }
}
