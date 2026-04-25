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
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.Audience;
import com.wikantik.api.structure.Confidence;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.Verification;
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
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new AdminVerificationResource();
        resource.setEngineForTesting( engine );
    }

    private static PageDescriptor desc( final String id, final String slug ) {
        return new PageDescriptor( id, slug, slug, PageType.ARTICLE,
                null, List.of(), "summary", Instant.EPOCH );
    }

    @Test
    void aggregates_per_confidence_counts() throws Exception {
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                desc( "01A", "A" ), desc( "01B", "B" ), desc( "01C", "C" ) ) );
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
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                desc( "01A", "A" ), desc( "01B", "B" ) ) );
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
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                desc( "01A", "A" ), desc( "01B", "B" ) ) );
        when( svc.verificationOf( "01A" ) ).thenReturn( Optional.of(
                new Verification( Instant.now().minus( Duration.ofDays( 1 ) ), "alice",
                        Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );
        when( svc.verificationOf( "01B" ) ).thenReturn( Optional.of(
                new Verification( Instant.now().minus( Duration.ofDays( 95 ) ), "alice",
                        Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );

        final JsonObject body = call( "min_days_stale=30" );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
    }

    @Test
    void service_absent_returns_503() throws Exception {
        final Engine engine = mock( Engine.class );
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
