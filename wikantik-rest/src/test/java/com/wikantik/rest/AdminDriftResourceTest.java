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
import com.wikantik.WikiSubsystems;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.citation.CitationRepository;
import com.wikantik.citation.CitationRow;
import com.wikantik.drift.DriftCount;
import com.wikantik.drift.DriftSnapshotRepository;
import com.wikantik.drift.DriftSweepRecord;
import com.wikantik.drift.DriftSweepService;
import com.wikantik.drift.PageViolation;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminDriftResource}. All drift services are mocked — no database.
 */
class AdminDriftResourceTest {

    private DriftSweepService service;
    private DriftSnapshotRepository repo;
    private CitationRepository citationRepo;
    private AdminDriftResource servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter body;

    /** Test servlet: injects mocked subsystems, bypassing engine boot. */
    private final class Stub extends AdminDriftResource {
        @Override protected WikiSubsystems getSubsystems() {
            final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
            final PageGraphSubsystem.Services pg = new PageGraphSubsystem.Services(
                    null, null, null, null, null, service, citationRepo, null );
            when( subs.pageGraph() ).thenReturn( pg );
            return subs;
        }
        @Override DriftSnapshotRepository repository( final DriftSweepService service ) { return repo; }
    }

    @BeforeEach
    void setUp() throws Exception {
        service = mock( DriftSweepService.class );
        repo = mock( DriftSnapshotRepository.class );
        citationRepo = mock( CitationRepository.class );
        servlet = new Stub();
        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );
    }

    private JsonObject json() {
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    @Test
    void summaryEmptyStateBeforeFirstSweep() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/summary" );
        when( repo.latest() ).thenReturn( Optional.empty() );
        servlet.doGet( req, resp );
        verify( resp ).setStatus( 200 );
        assertTrue( json().get( "sweptAt" ).isJsonNull() );
        assertEquals( 0, json().getAsJsonArray( "counts" ).size() );
    }

    @Test
    void summaryIncludesCountsAndDeltas() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/summary" );
        when( repo.latest() ).thenReturn( Optional.of( new DriftSweepRecord( 2L,
                Instant.parse( "2026-06-09T05:00:00Z" ), 100, 1234L, "scheduled", true,
                List.of( new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 5 ),
                         new DriftCount( "frontmatter", "date.format", "WARNING", 3 ) ) ) ) );
        when( repo.previousBefore( 2L ) ).thenReturn( Optional.of( new DriftSweepRecord( 1L,
                Instant.parse( "2026-06-08T05:00:00Z" ), 99, 1000L, "scheduled", true,
                List.of( new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 8 ) ) ) ) );
        servlet.doGet( req, resp );
        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertEquals( "2026-06-09T05:00:00Z", out.get( "sweptAt" ).getAsString() );
        assertEquals( 100, out.get( "pagesScanned" ).getAsInt() );
        assertEquals( "scheduled", out.get( "triggeredBy" ).getAsString() );
        assertTrue( out.get( "shaclChecked" ).getAsBoolean() );
        final var counts = out.getAsJsonArray( "counts" );
        assertEquals( 2, counts.size() );
        final JsonObject status = counts.get( 0 ).getAsJsonObject();
        assertEquals( "status.noncanonical", status.get( "code" ).getAsString() );
        assertEquals( 5, status.get( "count" ).getAsInt() );
        assertEquals( -3, status.get( "delta" ).getAsInt() );
        final JsonObject date = counts.get( 1 ).getAsJsonObject();
        assertTrue( date.get( "delta" ).isJsonNull(), "code absent from previous sweep → null delta" );
    }

    @Test
    void trendReturnsWindowedSweeps() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/trend" );
        when( req.getParameter( "days" ) ).thenReturn( "7" );
        when( repo.trend( 7 ) ).thenReturn( List.of( new DriftSweepRecord( 1L,
                Instant.parse( "2026-06-08T05:00:00Z" ), 99, 1000L, "scheduled", true,
                List.of( new DriftCount( "frontmatter", "x", "WARNING", 8 ) ) ) ) );
        servlet.doGet( req, resp );
        verify( resp ).setStatus( 200 );
        assertEquals( 1, json().getAsJsonArray( "sweeps" ).size() );
    }

    @Test
    void pagesReturnsLiveList() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/pages" );
        when( req.getParameter( "family" ) ).thenReturn( "frontmatter" );
        when( req.getParameter( "code" ) ).thenReturn( "status.noncanonical" );
        when( service.currentPageList( "frontmatter", "status.noncanonical" ) ).thenReturn(
                List.of( new PageViolation( "Drifty", "status", "WARNING", "status.noncanonical",
                        "Non-canonical status", "active" ) ) );
        servlet.doGet( req, resp );
        verify( resp ).setStatus( 200 );
        final JsonObject first = json().getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject();
        assertEquals( "Drifty", first.get( "pageName" ).getAsString() );
        assertEquals( "active", first.get( "suggestion" ).getAsString() );
    }

    @Test
    void pagesWithoutCodeIs400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/pages" );
        when( req.getParameter( "family" ) ).thenReturn( "frontmatter" );
        when( req.getParameter( "code" ) ).thenReturn( null );
        servlet.doGet( req, resp );
        // RestServletBase.sendError writes the status via setStatus(int)
        verify( resp ).setStatus( 400 );
        verify( service, never() ).currentPageList( anyString(), anyString() );
    }

    @Test
    void sweepTriggerReturns202() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/sweep" );
        servlet.doPost( req, resp );
        verify( service ).triggerAsync( "manual" );
        verify( resp ).setStatus( 202 );
    }

    @Test
    void sweepWhileRunningReturns409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/sweep" );
        doThrow( new DriftSweepService.SweepAlreadyRunningException() )
                .when( service ).triggerAsync( "manual" );
        servlet.doPost( req, resp );
        verify( resp ).setStatus( 409 );
    }

    @Test
    void unknownGetEndpointIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/bogus" );
        servlet.doGet( req, resp );
        // sendNotFound delegates to sendError, which uses setStatus(404)
        verify( resp ).setStatus( 404 );
    }

    @Test
    void serviceUnavailableIs503() throws Exception {
        service = null;
        when( req.getPathInfo() ).thenReturn( "/summary" );
        servlet.doGet( req, resp );
        // sendError uses setStatus(int), never sendError(int, String)
        verify( resp ).setStatus( 503 );
    }

    @Test
    void statusReportsRunningProgress() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        when( service.progress() ).thenReturn(
                new DriftSweepService.SweepProgress( true, "frontmatter", 84, 312 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertTrue( out.get( "running" ).getAsBoolean() );
        assertEquals( "frontmatter", out.get( "phase" ).getAsString() );
        assertEquals( 84, out.get( "pagesScanned" ).getAsInt() );
        assertEquals( 312, out.get( "totalPages" ).getAsInt() );
    }

    @Test
    void statusReportsIdleWithNullPhase() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        when( service.progress() ).thenReturn(
                new DriftSweepService.SweepProgress( false, null, 0, 0 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertFalse( out.get( "running" ).getAsBoolean() );
        assertTrue( out.get( "phase" ).isJsonNull() );
        assertEquals( 0, out.get( "totalPages" ).getAsInt() );
    }

    @Test
    void statusSurfacesLastError() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        when( service.progress() ).thenReturn(
                new DriftSweepService.SweepProgress( false, null, 0, 0 ) );
        when( service.lastError() ).thenReturn( "drift sweep persistence failed" );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        assertEquals( "drift sweep persistence failed", json().get( "lastError" ).getAsString() );
    }

    // -------------------------------------------------------------------------
    // citations endpoint
    // -------------------------------------------------------------------------

    @Test
    void citationsCountsOnlyWhenNoPageParam() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/citations" );
        when( req.getParameter( "page" ) ).thenReturn( null );
        when( citationRepo.countsByStatus() ).thenReturn( Map.of(
                CitationStatus.CURRENT, 10,
                CitationStatus.STALE, 3 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        final JsonObject counts = out.getAsJsonObject( "counts" );
        assertEquals( 10, counts.get( "current" ).getAsInt() );
        assertEquals( 3, counts.get( "stale" ).getAsInt() );
        assertEquals( 0, counts.get( "target_missing" ).getAsInt() );
        assertEquals( 0, out.getAsJsonArray( "outbound" ).size() );
        assertEquals( 0, out.getAsJsonArray( "inbound" ).size() );
    }

    @Test
    void citationsWithPageReturnsOutboundAndNonCurrentInbound() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/citations" );
        when( req.getParameter( "page" ) ).thenReturn( "my-page" );
        when( req.getParameter( "direction" ) ).thenReturn( null );
        when( citationRepo.countsByStatus() ).thenReturn( Map.of(
                CitationStatus.CURRENT, 2,
                CitationStatus.STALE, 1,
                CitationStatus.TARGET_MISSING, 0 ) );

        final CitationRow outRow = new CitationRow( 1L, "my-page", "other-page", "## Intro",
                "see other-page", "abc123", "claim A", 0, 42, CitationStatus.CURRENT,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH );
        final CitationRow inCurrent = new CitationRow( 2L, "other-page", "my-page", "## Ref",
                "see my-page", "def456", "claim B", 0, null, CitationStatus.CURRENT,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH );
        final CitationRow inStale = new CitationRow( 3L, "third-page", "my-page", "## Ref",
                "old ref", "ghi789", "stale claim", 1, null, CitationStatus.STALE,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH );

        when( citationRepo.findBySource( "my-page" ) ).thenReturn( List.of( outRow ) );
        when( citationRepo.findByTarget( "my-page" ) ).thenReturn( List.of( inCurrent, inStale ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        // outbound includes all from findBySource
        assertEquals( 1, out.getAsJsonArray( "outbound" ).size() );
        final JsonObject outbound = out.getAsJsonArray( "outbound" ).get( 0 ).getAsJsonObject();
        assertEquals( "my-page", outbound.get( "sourceCanonicalId" ).getAsString() );
        assertEquals( "other-page", outbound.get( "targetCanonicalId" ).getAsString() );
        assertEquals( "## Intro", outbound.get( "targetHeadingPath" ).getAsString() );
        assertEquals( "current", outbound.get( "status" ).getAsString() );
        assertEquals( 42, outbound.get( "pinnedTargetVersion" ).getAsInt() );
        // inbound excludes CURRENT rows
        assertEquals( 1, out.getAsJsonArray( "inbound" ).size() );
        final JsonObject inbound = out.getAsJsonArray( "inbound" ).get( 0 ).getAsJsonObject();
        assertEquals( "third-page", inbound.get( "sourceCanonicalId" ).getAsString() );
        assertEquals( "stale", inbound.get( "status" ).getAsString() );
        assertTrue( inbound.get( "pinnedTargetVersion" ).isJsonNull() );
    }

    @Test
    void citationsWithNullRepoReturnsZeroCountsAndEmptyLists() throws Exception {
        citationRepo = null;
        servlet = new Stub();
        when( req.getPathInfo() ).thenReturn( "/citations" );
        when( req.getParameter( "page" ) ).thenReturn( "any-page" );
        // Re-wire resp writer for new servlet instance
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        final JsonObject counts = out.getAsJsonObject( "counts" );
        assertEquals( 0, counts.get( "current" ).getAsInt() );
        assertEquals( 0, counts.get( "stale" ).getAsInt() );
        assertEquals( 0, counts.get( "target_missing" ).getAsInt() );
        assertEquals( 0, out.getAsJsonArray( "outbound" ).size() );
        assertEquals( 0, out.getAsJsonArray( "inbound" ).size() );
    }
}
