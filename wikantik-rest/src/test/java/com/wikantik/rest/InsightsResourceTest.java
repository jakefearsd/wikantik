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
import com.wikantik.insights.EngineTotal;
import com.wikantik.insights.InsightsStore;
import com.wikantik.insights.TrendPoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InsightsResource}. Injects a mock {@link InsightsStore} through the
 * package-private {@link InsightsResource#buildStore()} seam, exactly as its javadoc says tests
 * should — no live JNDI environment or datasource required.
 */
class InsightsResourceTest {

    private InsightsStore store;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter buf;

    /** A resource whose {@code buildStore()} returns a fixed mock (or {@code null}). */
    private static final class TestableInsightsResource extends InsightsResource {
        private final InsightsStore fixedStore;
        TestableInsightsResource( final InsightsStore fixedStore ) { this.fixedStore = fixedStore; }
        @Override InsightsStore buildStore() { return fixedStore; }
    }

    @BeforeEach
    void setUp() throws Exception {
        store = mock( InsightsStore.class );
        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        buf = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( buf ) );
    }

    private JsonObject body() {
        return JsonParser.parseString( buf.toString() ).getAsJsonObject();
    }

    private InsightsResource resourceWith( final InsightsStore s ) {
        return new TestableInsightsResource( s );
    }

    // -------------------------------------------------------------------------

    @Test
    void doGet_returnsEmptyResultWhenNoSnapshotExists() throws Exception {
        when( store.latestSnapshotDate( InsightsResource.DEFAULT_SITE ) ).thenReturn( Optional.empty() );

        resourceWith( store ).doGet( req, resp );

        final JsonObject b = body();
        assertEquals( InsightsResource.DEFAULT_SITE, b.get( "site" ).getAsString() );
        assertTrue( b.get( "snapshotDate" ).isJsonNull() );
        assertEquals( 0, b.getAsJsonArray( "engines" ).size() );
        assertEquals( 0, b.getAsJsonArray( "trend" ).size() );
        // Never reached trend/engineTotals once there's no snapshot.
        verify( store, never() ).engineTotals( anyString(), any() );
        verify( store, never() ).trend( anyString(), any() );
    }

    @Test
    void doGet_returnsEngineRowsAndTrendForLatestSnapshot() throws Exception {
        final LocalDate snapshotDate = LocalDate.of( 2026, 8, 1 );
        when( store.latestSnapshotDate( InsightsResource.DEFAULT_SITE ) )
                .thenReturn( Optional.of( snapshotDate ) );
        when( store.engineTotals( InsightsResource.DEFAULT_SITE, snapshotDate ) ).thenReturn( List.of(
                new EngineTotal( "google", 30L, 300L, 4.5 ),
                // Yandex-style row: no position emitted -- must serialize as JSON null, not 0.
                new EngineTotal( "yandex", 0L, 0L, null ) ) );
        when( store.trend( eq( InsightsResource.DEFAULT_SITE ), any( LocalDate.class ) ) )
                .thenReturn( List.of( new TrendPoint( snapshotDate, "google", 30L, 300L ) ) );

        resourceWith( store ).doGet( req, resp );

        final JsonObject b = body();
        assertEquals( "2026-08-01", b.get( "snapshotDate" ).getAsString() );

        final JsonArray engines = b.getAsJsonArray( "engines" );
        assertEquals( 2, engines.size() );

        final JsonObject googleRow = engines.get( 0 ).getAsJsonObject();
        assertEquals( "google", googleRow.get( "engine" ).getAsString() );
        assertEquals( 30, googleRow.get( "clicks" ).getAsInt() );
        assertEquals( 300, googleRow.get( "impressions" ).getAsInt() );
        assertEquals( 0.1, googleRow.get( "ctr" ).getAsDouble(), 0.00001 );
        assertEquals( 4.5, googleRow.get( "position" ).getAsDouble(), 0.00001 );

        final JsonObject yandexRow = engines.get( 1 ).getAsJsonObject();
        assertEquals( 0.0, yandexRow.get( "ctr" ).getAsDouble(), 0.00001, "0 impressions must not divide by zero" );
        assertTrue( yandexRow.get( "position" ).isJsonNull(), "missing position must serialize as null, not 0" );

        final JsonArray trend = b.getAsJsonArray( "trend" );
        assertEquals( 1, trend.size() );
        assertEquals( "google", trend.get( 0 ).getAsJsonObject().get( "engine" ).getAsString() );
    }

    @Test
    void doGet_usesRequestedSiteAndDaysParameters() throws Exception {
        when( req.getParameter( "site" ) ).thenReturn( "example.com" );
        when( req.getParameter( "days" ) ).thenReturn( "30" );
        when( store.latestSnapshotDate( "example.com" ) ).thenReturn( Optional.empty() );

        resourceWith( store ).doGet( req, resp );

        verify( store ).latestSnapshotDate( "example.com" );
        assertEquals( "example.com", body().get( "site" ).getAsString() );
    }

    @Test
    void doGet_clampsDaysBelowMinimum() throws Exception {
        when( req.getParameter( "days" ) ).thenReturn( "0" );
        when( store.latestSnapshotDate( InsightsResource.DEFAULT_SITE ) )
                .thenReturn( Optional.of( LocalDate.of( 2026, 1, 1 ) ) );
        when( store.engineTotals( anyString(), any() ) ).thenReturn( List.of() );
        when( store.trend( anyString(), any() ) ).thenReturn( List.of() );

        resourceWith( store ).doGet( req, resp );

        // days=0 clamps to MIN_DAYS=1 -> since = today - 0 = today.
        verify( store ).trend( eq( InsightsResource.DEFAULT_SITE ), eq( LocalDate.now() ) );
    }

    @Test
    void doGet_clampsDaysAboveMaximum() throws Exception {
        when( req.getParameter( "days" ) ).thenReturn( "999999" );
        when( store.latestSnapshotDate( InsightsResource.DEFAULT_SITE ) )
                .thenReturn( Optional.of( LocalDate.of( 2026, 1, 1 ) ) );
        when( store.engineTotals( anyString(), any() ) ).thenReturn( List.of() );
        when( store.trend( anyString(), any() ) ).thenReturn( List.of() );

        resourceWith( store ).doGet( req, resp );

        final LocalDate expectedSince = LocalDate.now().minusDays( InsightsResource.MAX_DAYS - 1L );
        verify( store ).trend( eq( InsightsResource.DEFAULT_SITE ), eq( expectedSince ) );
    }

    @Test
    void doGet_returns400OnNonIntegerDaysParameter() throws Exception {
        when( req.getParameter( "days" ) ).thenReturn( "not-a-number" );

        resourceWith( store ).doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( body().get( "message" ).getAsString().contains( "days" ) );
        verify( store, never() ).latestSnapshotDate( anyString() );
    }

    @Test
    void doGet_returns503WhenStoreUnavailable() throws Exception {
        resourceWith( null ).doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( body().get( "message" ).getAsString().toLowerCase().contains( "not available" ) );
    }

    @Test
    void doGet_returns500WhenStoreThrows() throws Exception {
        when( store.latestSnapshotDate( InsightsResource.DEFAULT_SITE ) )
                .thenThrow( new RuntimeException( "boom" ) );

        resourceWith( store ).doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
        assertFalse( body().get( "message" ).getAsString().isBlank() );
    }

    @Test
    void isCrossOriginAllowed_isFalse() {
        assertFalse( new InsightsResource().isCrossOriginAllowed() );
    }
}
