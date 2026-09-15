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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.insights.Opportunity;
import com.wikantik.insights.SuppressedRule;
import com.wikantik.insights.runtime.ContentOpportunityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InsightsBacklogResource}. The resource reaches the
 * {@link ContentOpportunityService} via {@code getEngine() instanceof WikiEngine} rather than a
 * dedicated seam, so tests mock {@link WikiEngine} directly (same pattern as
 * {@code AdminKgPolicyResourceTest}) and inject it with the protected, same-package
 * {@link RestServletBase#setEngine}.
 */
class InsightsBacklogResourceTest {

    private WikiEngine engine;
    private ContentOpportunityService service;
    private InsightsBacklogResource resource;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter buf;

    @BeforeEach
    void setUp() throws Exception {
        engine = mock( WikiEngine.class );
        service = mock( ContentOpportunityService.class );
        when( engine.contentOpportunityService() ).thenReturn( service );

        resource = new InsightsBacklogResource();
        resource.setEngine( engine );

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        buf = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( buf ) );
    }

    private JsonObject body() {
        return JsonParser.parseString( buf.toString() ).getAsJsonObject();
    }

    private static ContentOpportunityService.BacklogView emptyView() {
        return new ContentOpportunityService.BacklogView(
                List.of(), List.of(), Set.of(), LocalDate.of( 2026, 8, 1 ), "wiki.wikantik.com", "builtin" );
    }

    // -------------------------------------------------------------------------

    @Test
    void doGet_returns503WhenSubsystemUnavailable_engineNotWikiEngine() throws Exception {
        resource.setEngine( mock( Engine.class ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( body().get( "message" ).getAsString().toLowerCase().contains( "not available" ) );
    }

    @Test
    void doGet_returns503WhenServiceIsNull() throws Exception {
        when( engine.contentOpportunityService() ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void doGet_rendersOpportunitiesSuppressedAndMetadata() throws Exception {
        final Opportunity opp = new Opportunity( "agent_gap", "how do I deploy", 6.0,
                Map.of( "occurrences", 3 ), "Write the missing section.",
                LocalDate.of( 2026, 8, 2 ), false );
        final SuppressedRule suppressed = new SuppressedRule( "engine_divergence", "traffic_gate", 2626.0, 5000.0 );
        final ContentOpportunityService.BacklogView view = new ContentOpportunityService.BacklogView(
                List.of( opp ), List.of( suppressed ), Set.of( "agent_gap" ),
                LocalDate.of( 2026, 8, 1 ), "wiki.wikantik.com", "imported:2026-08-14" );
        when( service.backlog( isNull(), isNull(), isNull(), eq( 50 ), eq( false ) ) ).thenReturn( view );

        resource.doGet( req, resp );

        final JsonObject b = body();
        assertEquals( "wiki.wikantik.com", b.get( "site" ).getAsString() );
        assertEquals( "2026-08-01", b.get( "generatedAt" ).getAsString() );
        assertEquals( "imported:2026-08-14", b.get( "ctrCurveSource" ).getAsString() );
        assertEquals( 1, b.get( "count" ).getAsInt() );

        final JsonArray opportunities = b.getAsJsonArray( "opportunities" );
        assertEquals( 1, opportunities.size() );
        final JsonObject oppRow = opportunities.get( 0 ).getAsJsonObject();
        assertEquals( "agent_gap", oppRow.get( "type" ).getAsString() );
        assertEquals( "how do I deploy", oppRow.get( "target" ).getAsString() );
        assertEquals( 6.0, oppRow.get( "priority" ).getAsDouble(), 0.0001 );
        assertFalse( oppRow.get( "calibrated" ).getAsBoolean() );
        assertEquals( "2026-08-02", oppRow.get( "firstSeen" ).getAsString() );

        final JsonArray suppressedArr = b.getAsJsonArray( "suppressed" );
        assertEquals( 1, suppressedArr.size() );
        final JsonObject suppressedRow = suppressedArr.get( 0 ).getAsJsonObject();
        assertEquals( "engine_divergence", suppressedRow.get( "type" ).getAsString() );
        assertEquals( "traffic_gate", suppressedRow.get( "reason" ).getAsString() );
        assertEquals( 2626.0, suppressedRow.get( "measured" ).getAsDouble(), 0.0001 );
        assertEquals( 5000.0, suppressedRow.get( "required" ).getAsDouble(), 0.0001 );

        final JsonArray uncalibrated = b.getAsJsonArray( "uncalibratedTypes" );
        assertEquals( 1, uncalibrated.size() );
        assertEquals( "agent_gap", uncalibrated.get( 0 ).getAsString() );
    }

    @Test
    void doGet_forwardsSiteTypeAndIncludeSnoozedParameters() throws Exception {
        when( req.getParameter( "site" ) ).thenReturn( "example.com" );
        when( req.getParameter( "type" ) ).thenReturn( "stale_high_traffic" );
        when( req.getParameter( "includeSnoozed" ) ).thenReturn( "true" );
        when( service.backlog( eq( "example.com" ), eq( "stale_high_traffic" ), isNull(), eq( 50 ), eq( true ) ) )
                .thenReturn( emptyView() );

        resource.doGet( req, resp );

        verify( service ).backlog( "example.com", "stale_high_traffic", null, 50, true );
    }

    @Test
    void doGet_parsesValidMinPriority() throws Exception {
        when( req.getParameter( "minPriority" ) ).thenReturn( "2.5" );
        when( service.backlog( isNull(), isNull(), eq( 2.5 ), eq( 50 ), eq( false ) ) )
                .thenReturn( emptyView() );

        resource.doGet( req, resp );

        verify( service ).backlog( null, null, 2.5, 50, false );
    }

    @Test
    void doGet_returns400OnInvalidMinPriority() throws Exception {
        when( req.getParameter( "minPriority" ) ).thenReturn( "not-a-number" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( body().get( "message" ).getAsString().contains( "minPriority" ) );
        verify( service, never() ).backlog( anyString(), anyString(), anyDouble(), anyInt(), anyBoolean() );
    }

    @Test
    void doGet_parsesValidLimit() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "10" );
        when( service.backlog( isNull(), isNull(), isNull(), eq( 10 ), eq( false ) ) )
                .thenReturn( emptyView() );

        resource.doGet( req, resp );

        verify( service ).backlog( null, null, null, 10, false );
    }

    @Test
    void doGet_returns400OnNonNumericLimit() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "abc" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( body().get( "message" ).getAsString().contains( "limit" ) );
    }

    @Test
    void doGet_returns400OnLimitBelowOne() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "0" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( body().get( "message" ).getAsString().contains( "1 and 200" ) );
    }

    @Test
    void doGet_returns400OnLimitAboveMax() throws Exception {
        when( req.getParameter( "limit" ) ).thenReturn( "201" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void doGet_returns500WhenServiceThrows() throws Exception {
        when( service.backlog( isNull(), isNull(), isNull(), eq( 50 ), eq( false ) ) )
                .thenThrow( new RuntimeException( "boom" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
        assertFalse( body().get( "message" ).getAsString().isBlank() );
    }

    @Test
    void isCrossOriginAllowed_isFalse() {
        assertFalse( new InsightsBacklogResource().isCrossOriginAllowed() );
    }
}
