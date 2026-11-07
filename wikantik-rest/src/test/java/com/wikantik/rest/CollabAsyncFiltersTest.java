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
import com.wikantik.audit.RequestContextKeys;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.FakeAsyncContext;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.observability.RequestCorrelationFilter;
import com.wikantik.observability.RequestMetricsFilter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The async filters of {@code /api/collab/*} around the real {@link CollabResource} (plan amendment, REST Task 7):
 * {@link RequestCorrelationFilter} and {@link RequestMetricsFilter} wrap a parked long-poll, which is then answered by
 * the container timeout, by a wake on another thread, or by an I/O error, or cannot go async at all. The metrics are
 * recorded exactly once, when the request really completes, and the answer is written under the parked request's log
 * context (MDC) even on the thread of the push that woke it.
 */
class CollabAsyncFiltersTest {

    private static final String PAGE = "PageName";

    private SimpleMeterRegistry meters;
    private CollabSessionRegistry registry;
    private CollabResource resource;
    private CollabSession session;
    private JoinResponse ada;

    private final CollabPermissions allow = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    private final CollabResource.Access access = new CollabResource.Access() {
        @Override
        public CollabResource.Caller caller( final HttpServletRequest request ) {
            return new CollabResource.Caller( "ada", "ada" );
        }

        @Override
        public boolean mayView( final CollabResource.Caller caller, final String pageName ) {
            return true;
        }

        @Override
        public boolean mayEdit( final CollabResource.Caller caller, final String pageName, final boolean create ) {
            return true;
        }
    };

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "Hello world", Map.of() );
        registry = new CollabSessionRegistry( new InMemoryCollabStore(), writer, allow, k -> k,
            new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ),
            new CollabConfig( true, 20, 65536, 25, 45, 30, 100, 500, 10, 0, 1000 ) );
        resource = new CollabResource( registry, access, ( ms, task ) -> task.run(), 0 );
        final CollabSessionRegistry.Joined j = ( CollabSessionRegistry.Joined ) registry.join( PAGE,
            JoinRequest.of( "ada", "ada", null ) );
        session = j.session();
        ada = j.response();
    }

    @AfterEach
    void clearContext() {
        ThreadContext.clearMap();
    }

    /** One request through the filters; the writer records the request id in the MDC at the moment of each write. */
    private final class Exchange {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        final List< String > writeContexts = new ArrayList<>();
        int status = 200;
        FakeAsyncContext async;

        Exchange( final boolean asyncWorks ) throws Exception {
            final ServletContext ctx = mock( ServletContext.class );
            when( ctx.getAttribute( "com.wikantik.observability.meterRegistry" ) ).thenReturn( meters );
            when( req.getServletContext() ).thenReturn( ctx );
            when( req.getMethod() ).thenReturn( "GET" );
            when( req.getPathInfo() ).thenReturn( "/" + PAGE + "/updates" );
            when( req.getRequestURI() ).thenReturn( "/api/collab/" + PAGE + "/updates" );
            when( req.getHeader( "X-Request-Id" ) ).thenReturn( "req-parked" );
            final Map< String, String > q = Map.of( "sessionId", session.id().toString(), "participantId",
                ada.participantId(), "since", "0", "metaSince", "0", "presenceSince",
                Long.toString( session.view().presenceSeq() ), "eventsSince", Long.toString( ada.eventSeq() ) );
            when( req.getParameter( anyString() ) ).thenAnswer( i -> q.get( i.< String >getArgument( 0 ) ) );
            if( asyncWorks ) {
                when( req.startAsync() ).thenAnswer( i -> {
                    async = new FakeAsyncContext( req, resp );
                    return async;
                } );
            } else {
                when( req.startAsync() ).thenThrow( new IllegalStateException( "a filter is not async-supported" ) );
            }
            when( req.isAsyncStarted() ).thenAnswer( i -> async != null && !async.isCompleted() );
            when( req.getAsyncContext() ).thenAnswer( i -> async );
            doAnswer( i -> {
                status = i.getArgument( 0 );
                return null;
            } ).when( resp ).setStatus( anyInt() );
            when( resp.getStatus() ).thenAnswer( i -> status );
            final Writer recording = new Writer() {
                @Override
                public void write( final char[] buf, final int off, final int len ) {
                    writeContexts.add( ThreadContext.get( RequestContextKeys.REQUEST_ID ) );
                    out.write( buf, off, len );
                }

                @Override
                public void flush() {
                    // nothing buffered
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
            when( resp.getWriter() ).thenReturn( new PrintWriter( recording, true ) );
        }

        void run() throws Exception {
            final FilterChain servlet = ( rq, rs ) -> resource.doGet( ( HttpServletRequest ) rq,
                ( HttpServletResponse ) rs );
            final FilterChain metrics = ( rq, rs ) -> new RequestMetricsFilter().doFilter( rq, rs, servlet );
            new RequestCorrelationFilter().doFilter( req, resp, metrics );
        }

        JsonObject body() {
            return JsonParser.parseString( out.toString() ).getAsJsonObject();
        }
    }

    private long recorded( final int status ) {
        final Timer t = meters.find( "http.server.requests" ).tag( "status", Integer.toString( status ) ).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void aContainerTimeoutAnswersOnceAndIsTimedOnCompletion() throws Exception {
        final Exchange x = new Exchange( true );
        x.run();
        assertNotNull( x.async, "parked" );
        assertEquals( 0, recorded( 200 ), "not timed while parked" );
        assertTrue( ThreadContext.isEmpty(), "the correlation filter cleared the request thread" );

        x.async.fireTimeout();

        x.async.assertCompletedOnce();
        assertEquals( 1, recorded( 200 ) );
        assertEquals( 0, x.body().getAsJsonArray( "updates" ).size() );
        assertEquals( List.of( "req-parked" ), x.writeContexts.stream().distinct().toList(),
            "written under the parked request's MDC" );
    }

    @Test
    void aWakeOnAnotherThreadWritesUnderTheParkedRequestsContext() throws Exception {
        final Exchange x = new Exchange( true );
        x.run();
        final Thread pusher = new Thread( () -> {
            ThreadContext.put( RequestContextKeys.REQUEST_ID, "req-pusher" );
            final String doc = session.view().doc();
            session.push( ada.participantId(), "ada", 0,
                List.of( new Update( ada.clientId(), ChangeSet.replace( doc.length(), 0, 0, "X" ) ) ) );
            assertEquals( "req-pusher", ThreadContext.get( RequestContextKeys.REQUEST_ID ),
                "the pusher's own context is restored" );
        } );
        pusher.start();
        pusher.join( 10_000 );
        assertFalse( pusher.isAlive() );

        x.async.assertCompletedOnce();
        assertEquals( 1, recorded( 200 ) );
        assertEquals( 1, x.body().getAsJsonArray( "updates" ).size() );
        assertEquals( List.of( "req-parked" ), x.writeContexts.stream().distinct().toList() );
    }

    @Test
    void anIoErrorCompletesOnceAndIsTimedOnce() throws Exception {
        final Exchange x = new Exchange( true );
        x.run();
        for( final var l : x.async.listeners() ) {
            l.onError( new AsyncEvent( x.async, new java.io.IOException( "client went away" ) ) );
        }
        x.async.assertCompletedOnce();
        assertEquals( 1, recorded( 200 ) );
        assertEquals( "", x.out.toString(), "nothing written to a broken connection" );
        // the session's own timeout later finds the waiter answered already
        x.async.assertCompletedOnce();
    }

    @Test
    void whenAsyncCannotStartTheAnswerIsSynchronousAndTimedNow() throws Exception {
        final Exchange x = new Exchange( false );
        x.run();
        assertEquals( 500, x.status );
        assertEquals( "async-unsupported", x.body().get( "reason" ).getAsString() );
        assertEquals( 1, recorded( 500 ) );
    }
}
