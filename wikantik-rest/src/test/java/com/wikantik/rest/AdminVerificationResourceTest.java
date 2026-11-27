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
import com.wikantik.api.core.Engine;
import com.wikantik.api.pagegraph.Audience;
import com.wikantik.api.pagegraph.Confidence;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.Verification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminVerificationResourceTest {

    private StructuralIndexService svc;
    private AdminVerificationResource resource;

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        // verificationCounts() is a default method on the interface — let the mock
        // run the real default so it derives the mix from the stubbed
        // listPagesByFilter/verificationOf, matching production behaviour.
        when( svc.verificationCounts() ).thenCallRealMethod();
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new AdminVerificationResource();
        resource.setEngineForTesting( engine );
    }

    private static PageDescriptor desc( final String id, final String slug ) {
        return new PageDescriptor( id, slug, slug, PageType.ARTICLE,
                null, List.of(), "summary", Instant.EPOCH, false );
    }

    @Test
    void aggregates_per_confidence_counts() throws Exception {
        indexHolds( List.of( desc( "01A", "A" ), desc( "01B", "B" ), desc( "01C", "C" ) ) );
        when( svc.verificationOf( "01A" ) ).thenReturn( Optional.of(
                new Verification( Instant.now(), "alice", Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );
        when( svc.verificationOf( "01B" ) ).thenReturn( Optional.of(
                new Verification( null, null, Confidence.STALE, Audience.HUMANS_AND_AGENTS ) ) );
        when( svc.verificationOf( "01C" ) ).thenReturn( Optional.empty() );  // -> default unverified -> PROVISIONAL

        final JsonObject body = call( null );
        final JsonObject data = body.getAsJsonObject( "data" );
        final JsonObject byConf = data.getAsJsonObject( "by_confidence" );
        assertEquals( 3, data.get( "total_pages" ).getAsInt() );
        assertEquals( 1, byConf.get( "authoritative" ).getAsInt() );
        assertEquals( 1, byConf.get( "stale" ).getAsInt() );
        assertEquals( 1, byConf.get( "provisional" ).getAsInt() );
    }

    @Test
    void confidence_filter_narrows_rows_but_keeps_total() throws Exception {
        indexHolds( List.of( desc( "01A", "A" ), desc( "01B", "B" ) ) );
        when( svc.verificationOf( "01A" ) ).thenReturn( Optional.of(
                new Verification( Instant.now(), "alice", Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );
        when( svc.verificationOf( "01B" ) ).thenReturn( Optional.of(
                new Verification( null, null, Confidence.STALE, Audience.HUMANS_AND_AGENTS ) ) );

        final JsonObject body = call( "confidence=stale" );
        final JsonObject data = body.getAsJsonObject( "data" );
        assertEquals( 1, data.get( "count" ).getAsInt() );
        assertEquals( 2, data.get( "total_pages" ).getAsInt() );
        assertEquals( "B", data.getAsJsonArray( "pages" )
                .get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
    }

    @Test
    void min_days_stale_filter_excludes_recent_verifications() throws Exception {
        indexHolds( List.of( desc( "01A", "A" ), desc( "01B", "B" ) ) );
        when( svc.verificationOf( "01A" ) ).thenReturn( Optional.of(
                new Verification( Instant.now().minus( Duration.ofDays( 1 ) ), "alice",
                        Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );
        when( svc.verificationOf( "01B" ) ).thenReturn( Optional.of(
                new Verification( Instant.now().minus( Duration.ofDays( 95 ) ), "alice",
                        Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );

        final JsonObject body = call( "min_days_stale=30" );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
    }

    private void indexHolds( final List< PageDescriptor > all ) {
        when( svc.sitemap() ).thenReturn( new com.wikantik.api.pagegraph.Sitemap( all, all.size(), Instant.EPOCH ) );
        // what the real index does with StructuralFilter.none(): it caps one call at 100 pages
        when( svc.listPagesByFilter( any() ) ).thenAnswer( inv -> {
            final com.wikantik.api.pagegraph.StructuralFilter f = inv.getArgument( 0 );
            return all.subList( 0, Math.min( f.limit(), all.size() ) );
        } );
    }

    private static List< PageDescriptor > manyPages( final int n ) {
        final java.util.List< PageDescriptor > out = new java.util.ArrayList<>();
        for ( int i = 0; i < n; i++ ) {
            out.add( desc( String.format( "01%04d", i ), "P" + i ) );
        }
        return out;
    }

    /** The report covers the whole corpus: a wiki of more than 100 pages is not silently cut to its first 100. */
    @Test
    void aCorpusOfMoreThanOneHundredPagesIsReportedInFull() throws Exception {
        indexHolds( manyPages( 150 ) );

        final JsonObject data = call( "limit=1000" ).getAsJsonObject( "data" );

        assertEquals( 150, data.get( "total_pages" ).getAsInt() );
        assertEquals( 150, data.get( "count" ).getAsInt() );
        assertEquals( "P149", data.getAsJsonArray( "pages" ).get( 149 ).getAsJsonObject().get( "slug" ).getAsString() );
    }

    /**
     * {@code StructuralIndexService.verificationCounts()} once tallied {@code listPagesByFilter(StructuralFilter.none())},
     * which the index caps at 100 pages, so the confidence mix (this endpoint's {@code by_confidence} and the admin
     * overview) covered only the first 100 pages of a larger wiki. It reads the sitemap now.
     */
    @Test
    void theConfidenceMixCoversEveryPageNotTheFirstHundred() throws Exception {
        indexHolds( manyPages( 150 ) );

        final JsonObject data = call( "limit=1" ).getAsJsonObject( "data" );
        final JsonObject mix = data.getAsJsonObject( "by_confidence" );

        assertEquals( 150, mix.get( "authoritative" ).getAsInt() + mix.get( "provisional" ).getAsInt() + mix.get( "stale" ).getAsInt() );
    }

    @Test
    void limitIsClampedToOneThroughOneThousandAndJunkMeansTwoHundred() throws Exception {
        indexHolds( manyPages( 1200 ) );

        assertEquals( 1, call( "limit=0" ).getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( 1, call( "limit=-7" ).getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( 7, call( "limit=7" ).getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( 1000, call( "limit=1000" ).getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( 1000, call( "limit=1001" ).getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( 200, call( "limit=junk" ).getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( 200, call( null ).getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( 1200, call( "limit=1001" ).getAsJsonObject( "data" ).get( "total_pages" ).getAsInt() );
    }

    @Test
    void theMinDaysStaleBoundaryIsInclusiveAndAnUnverifiedPageAlwaysQualifies() throws Exception {
        indexHolds( List.of( desc( "01A", "A" ), desc( "01B", "B" ), desc( "01C", "C" ) ) );
        when( svc.verificationOf( "01A" ) ).thenReturn( Optional.of( new Verification(
                Instant.now().minus( Duration.ofDays( 30 ) ).minusSeconds( 60 ), "alice", Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );
        when( svc.verificationOf( "01B" ) ).thenReturn( Optional.of( new Verification(
                Instant.now().minus( Duration.ofDays( 29 ) ), "alice", Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );
        when( svc.verificationOf( "01C" ) ).thenReturn( Optional.empty() );

        final var rows = call( "min_days_stale=30" ).getAsJsonObject( "data" ).getAsJsonArray( "pages" );

        assertEquals( List.of( "A", "C" ), rows.asList().stream().map( e -> e.getAsJsonObject().get( "slug" ).getAsString() ).toList() );
    }

    @Test
    void aRowCarriesItsVerificationOrExplicitNullWhenNeverVerified() throws Exception {
        final Instant at = Instant.now().minus( Duration.ofDays( 10 ) );
        indexHolds( List.of( desc( "01A", "A" ), desc( "01C", "C" ) ) );
        when( svc.verificationOf( "01A" ) ).thenReturn( Optional.of( new Verification( at, "alice", Confidence.AUTHORITATIVE, Audience.AGENTS ) ) );
        when( svc.verificationOf( "01C" ) ).thenReturn( Optional.empty() );

        final var rows = call( null ).getAsJsonObject( "data" ).getAsJsonArray( "pages" );

        final JsonObject a = rows.get( 0 ).getAsJsonObject();
        assertEquals( "01A", a.get( "id" ).getAsString() );
        assertEquals( "A", a.get( "title" ).getAsString() );
        assertEquals( "authoritative", a.get( "confidence" ).getAsString() );
        assertEquals( Audience.AGENTS.wireName(), a.get( "audience" ).getAsString() );
        assertEquals( at.toString(), a.get( "verified_at" ).getAsString() );
        assertEquals( 10, a.get( "days_since_verified" ).getAsLong() );
        assertEquals( "alice", a.get( "verified_by" ).getAsString() );
        final JsonObject c = rows.get( 1 ).getAsJsonObject();
        assertTrue( c.has( "verified_at" ) && c.get( "verified_at" ).isJsonNull() || !c.has( "verified_at" ) );
        assertTrue( !c.has( "days_since_verified" ) );
        assertTrue( !c.has( "verified_by" ) );
        assertEquals( "provisional", c.get( "confidence" ).getAsString() );
    }

    @Test
    void anUnavailableIndexAnswersWithAJsonBody() throws Exception {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( null );
        final var r = new AdminVerificationResource();
        r.setEngineForTesting( engine );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        r.doGet( mock( HttpServletRequest.class ), resp );

        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "structural index unavailable", JsonParser.parseString( sw.toString() ).getAsJsonObject().get( "error" ).getAsString() );
    }

    @Test
    void service_absent_returns_503() throws Exception {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( null );
        final var r = new AdminVerificationResource();
        r.setEngineForTesting( engine );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        r.doGet( req, resp );
        verify( resp ).setStatus( 503 );
    }

    private JsonObject call( final String query ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        if ( query != null ) {
            for ( final String pair : query.split( "&" ) ) {
                final String[] kv = pair.split( "=" );
                when( req.getParameter( kv[ 0 ] ) ).thenReturn( kv.length > 1 ? kv[ 1 ] : null );
            }
        }
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        resource.doGet( req, resp );
        return JsonParser.parseString( sw.toString() ).getAsJsonObject();
    }
}
