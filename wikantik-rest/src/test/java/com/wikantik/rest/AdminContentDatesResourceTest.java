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
import com.wikantik.seo.ContentDate;
import com.wikantik.seo.ContentDateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for {@link AdminContentDatesResource}; the service and page list are injected via a subclass. */
class AdminContentDatesResourceTest {

    private ContentDateService service;
    private List< String > pages;
    private Map< String, Instant > existing;
    private AdminContentDatesResource servlet;

    private final class Stub extends AdminContentDatesResource {
        @Override
        protected ContentDateService contentDateService() {
            return service;
        }

        @Override
        protected List< String > allPageNames() {
            return pages;
        }
    }

    @BeforeEach
    void setUp() {
        service = mock( ContentDateService.class );
        pages = List.of( "A", "B", "C" );
        existing = Map.of();
        when( service.allContentDates() ).thenAnswer( inv -> existing );
        when( service.recompute( anyString() ) ).thenAnswer(
            inv -> new ContentDate( inv.getArgument( 0 ), Instant.EPOCH, 1 ) );
        servlet = new Stub();
    }

    private record Reply( int status, JsonObject json ) {}

    private Reply call( final boolean post, final String path, final String force ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        when( req.getPathInfo() ).thenReturn( path );
        when( req.getParameter( "force" ) ).thenReturn( force );
        if( post ) {
            servlet.doPost( req, resp );
        } else {
            servlet.doGet( req, resp );
        }
        final var captor = org.mockito.ArgumentCaptor.forClass( Integer.class );
        verify( resp, atLeastOnce() ).setStatus( captor.capture() );
        final int status = captor.getAllValues().get( captor.getAllValues().size() - 1 );
        return new Reply( status, JsonParser.parseString( body.toString() ).getAsJsonObject() );
    }

    private JsonObject awaitTerminal() throws Exception {
        final long deadline = System.currentTimeMillis() + 10_000;
        while( System.currentTimeMillis() < deadline ) {
            final JsonObject s = call( false, "/status", null ).json();
            final String state = s.get( "state" ).getAsString();
            if( !"running".equals( state ) ) {
                return s;
            }
            Thread.sleep( 10 );
        }
        return fail( "backfill did not finish" );
    }

    @Test
    void statusIsIdleBeforeAnyRun() throws Exception {
        final Reply r = call( false, "/status", null );
        assertEquals( 200, r.status() );
        assertEquals( "idle", r.json().get( "state" ).getAsString() );
    }

    @Test
    void backfillReturns202ThenCompletesWithEveryPageRecomputed() throws Exception {
        final Reply r = call( true, "/backfill", null );
        assertEquals( 202, r.status() );
        assertEquals( "running", r.json().get( "state" ).getAsString() );

        final JsonObject s = awaitTerminal();
        assertEquals( "done", s.get( "state" ).getAsString() );
        assertEquals( 3, s.get( "total" ).getAsInt() );
        assertEquals( 3, s.get( "done" ).getAsInt() );
        assertEquals( 0, s.get( "failed" ).getAsInt() );
        for( final String p : pages ) {
            verify( service ).recompute( p );
        }
    }

    @Test
    void secondPostWhileRunningIs409() throws Exception {
        final CountDownLatch started = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        when( service.recompute( "A" ) ).thenAnswer( inv -> {
            started.countDown();
            release.await( 10, TimeUnit.SECONDS );
            return new ContentDate( "A", Instant.EPOCH, 1 );
        } );
        assertEquals( 202, call( true, "/backfill", null ).status() );
        assertTrue( started.await( 10, TimeUnit.SECONDS ) );

        assertEquals( 409, call( true, "/backfill", null ).status() );
        assertEquals( "running", call( false, "/status", null ).json().get( "state" ).getAsString() );

        release.countDown();
        assertEquals( "done", awaitTerminal().get( "state" ).getAsString() );
    }

    @Test
    void onePageThrowingCountsAsFailedAndOthersFinish() throws Exception {
        when( service.recompute( "B" ) ).thenThrow( new IllegalStateException( "boom" ) );
        call( true, "/backfill", null );

        final JsonObject s = awaitTerminal();
        assertEquals( "done", s.get( "state" ).getAsString() );
        assertEquals( 3, s.get( "total" ).getAsInt() );
        assertEquals( 1, s.get( "failed" ).getAsInt() );
        assertEquals( 2, s.get( "done" ).getAsInt() );
        verify( service ).recompute( "C" );
    }

    @Test
    void withoutForceExistingRowsAreLeftUntouched() throws Exception {
        existing = Map.of( "B", Instant.EPOCH );
        call( true, "/backfill", "false" );

        final JsonObject s = awaitTerminal();
        assertEquals( 2, s.get( "total" ).getAsInt() );
        verify( service, never() ).recompute( "B" );
        verify( service ).recompute( "A" );
        verify( service ).recompute( "C" );
    }

    @Test
    void forceRecomputesEvenExistingRows() throws Exception {
        existing = Map.of( "B", Instant.EPOCH );
        call( true, "/backfill", "true" );

        final JsonObject s = awaitTerminal();
        assertEquals( 3, s.get( "total" ).getAsInt() );
        verify( service ).recompute( "B" );
    }

    @Test
    void noServiceAnswers503() throws Exception {
        service = null;
        final Reply post = call( true, "/backfill", null );
        assertEquals( 503, post.status() );
        assertTrue( post.json().get( "message" ).getAsString().contains( "datasource" ) );
        assertEquals( 503, call( false, "/status", null ).status() );
    }

    @Test
    void unknownPathIs404() throws Exception {
        assertEquals( 404, call( true, "/nope", null ).status() );
        assertEquals( 404, call( false, "/nope", null ).status() );
    }
}
