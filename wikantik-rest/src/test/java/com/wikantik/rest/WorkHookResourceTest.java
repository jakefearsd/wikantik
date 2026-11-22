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

import com.wikantik.work.CodeEvent;
import com.wikantik.work.HookDelivery;
import com.wikantik.work.WorkCodeHostService;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkUnavailableException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The inbound code-host hook: one 404 for every refusal, 413 before any check, 204 for anything verified. */
class WorkHookResourceTest {

    private static final String SECRET_HEADER = "sha256=" + "ab".repeat( 32 );
    private static final String PUSH = """
        {"ref":"refs/heads/PLAT-1-x","after":"aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "repository":{"full_name":"octo/plat","default_branch":"main","pushed_at":1772366400},
         "commits":[{"id":"aaaaaaa1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","message":"TOPSECRETMESSAGE PLAT-1","distinct":true}]}""";

    private WorkService svc;
    private WorkCodeHostService host;
    private HookDelivery delivery;
    private boolean enabled;
    private long maxBody;
    private boolean workOn;
    private WorkHookResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        svc = mock( WorkService.class );
        final WorkFeatures features = mock( WorkFeatures.class );
        host = mock( WorkCodeHostService.class );
        delivery = mock( HookDelivery.class );
        when( delivery.productKey() ).thenReturn( "plat" );
        when( svc.features() ).thenReturn( features );
        when( features.codeHost() ).thenReturn( host );
        when( host.apply( any(), any() ) ).thenReturn( new WorkCodeHostService.Applied( 1, 0, 0 ) );
        enabled = true;
        workOn = true;
        maxBody = 1024L * 1024L;
        servlet = new WorkHookResource() {
            @Override
            protected WorkService workService() {
                return workOn ? svc : null;
            }

            @Override
            protected boolean codeHostEnabled() {
                return enabled;
            }

            @Override
            protected long maxBodyBytes() {
                return maxBody;
            }
        };
    }

    private static final class Body extends ServletInputStream {
        private final ByteArrayInputStream in;
        private int served;

        Body( final byte[] bytes ) {
            in = new ByteArrayInputStream( bytes );
        }

        @Override
        public int read() {
            served++;
            return in.read();
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener( final ReadListener listener ) {
            throw new UnsupportedOperationException();
        }
    }

    private record Out( int status, String body, Map< String, String > headers ) { }

    private HttpServletRequest request( final String method, final String pathInfo, final byte[] body,
                                        final Map< String, String > headers, final String contentType ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getMethod() ).thenReturn( method );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        when( req.getContentType() ).thenReturn( contentType );
        when( req.getContentLengthLong() ).thenReturn( (long) body.length );
        when( req.getInputStream() ).thenReturn( new Body( body ) );
        headers.forEach( ( k, v ) -> when( req.getHeader( k ) ).thenReturn( v ) );
        return req;
    }

    private Out run( final HttpServletRequest req ) throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        final int[] status = { 200 };
        Mockito.doAnswer( inv -> {
            status[ 0 ] = inv.getArgument( 0 );
            return null;
        } ).when( resp ).setStatus( anyInt() );
        final Map< String, String > headers = new java.util.TreeMap<>();
        Mockito.doAnswer( inv -> {
            headers.put( inv.getArgument( 0 ), inv.getArgument( 1 ) );
            return null;
        } ).when( resp ).setHeader( anyString(), anyString() );
        servlet.service( req, resp );
        return new Out( status[ 0 ], sw.toString(), headers );
    }

    private Out post( final String path, final String body, final Map< String, String > headers ) throws Exception {
        return run( request( "POST", path, body.getBytes( StandardCharsets.UTF_8 ), headers, "application/json" ) );
    }

    private void signedAs( final String format ) throws Exception {
        when( host.authenticate( eq( "plat" ), eq( format ), any(), any() ) ).thenReturn( Optional.of( delivery ) );
    }

    private static final Map< String, String > GITHUB_PUSH = Map.of( "X-GitHub-Event", "push",
        "X-GitHub-Delivery", "d-1", "X-Hub-Signature-256", SECRET_HEADER );

    @Test
    void everyRejectionIsTheSame404() throws Exception {
        when( host.authenticate( any(), any(), any(), any() ) ).thenReturn( Optional.empty() );
        final List< Out > outs = new ArrayList<>();
        outs.add( post( "/plat/github", PUSH, GITHUB_PUSH ) );                           // authenticate empty
        outs.add( post( "/plat/github", PUSH, Map.of() ) );                              // no signature at all
        outs.add( post( "/plat/gitlab", PUSH, GITHUB_PUSH ) );                           // unknown format
        outs.add( post( "/plat", PUSH, GITHUB_PUSH ) );                                  // wrong shape
        outs.add( post( "/plat/github/extra", PUSH, GITHUB_PUSH ) );
        outs.add( post( "/", PUSH, GITHUB_PUSH ) );
        outs.add( post( null, PUSH, GITHUB_PUSH ) );
        for ( final String method : List.of( "GET", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH" ) ) {
            outs.add( run( request( method, "/plat/github", new byte[ 0 ], GITHUB_PUSH, null ) ) );
        }
        enabled = false;
        outs.add( post( "/plat/github", PUSH, GITHUB_PUSH ) );                           // endpoint off
        enabled = true;
        workOn = false;
        outs.add( post( "/plat/github", PUSH, GITHUB_PUSH ) );                           // work off
        for ( final Out o : outs ) {
            assertEquals( 404, o.status() );
            assertEquals( outs.get( 0 ).body(), o.body(), "byte-identical bodies" );
            assertEquals( outs.get( 0 ).headers(), o.headers(), "no distinguishing header" );
            assertFalse( o.headers().containsKey( "Access-Control-Allow-Origin" ) );
        }
        assertEquals( "Not found.", com.google.gson.JsonParser.parseString( outs.get( 0 ).body() ).getAsJsonObject()
            .get( "message" ).getAsString() );
        verify( host, never() ).apply( any(), any() );
    }

    @Test
    void aRejectedDeliveryIsCounted() throws Exception {
        when( host.authenticate( any(), any(), any(), any() ) ).thenReturn( Optional.empty() );
        post( "/plat/github", PUSH, GITHUB_PUSH );
        verify( host ).rejected();
    }

    @Test
    void theSignatureHeaderIsPerFormat() throws Exception {
        signedAs( "github" );
        signedAs( "generic" );
        post( "/plat/github", PUSH, Map.of( "X-Hub-Signature-256", "sha256=aa", "X-Wikantik-Signature", "sha256=bb" ) );
        verify( host ).authenticate( eq( "plat" ), eq( "github" ), any(), eq( "sha256=aa" ) );
        post( "/plat/generic", "{}", Map.of( "X-Hub-Signature-256", "sha256=aa", "X-Wikantik-Signature", "sha256=bb" ) );
        verify( host ).authenticate( eq( "plat" ), eq( "generic" ), any(), eq( "sha256=bb" ) );
    }

    @Test
    void theSignatureIsCheckedOverTheRawBytes() throws Exception {
        signedAs( "github" );
        final byte[] raw = ( PUSH + "  \n" ).getBytes( StandardCharsets.UTF_8 );
        run( request( "POST", "/plat/github", raw, GITHUB_PUSH, "application/json" ) );
        final ArgumentCaptor< byte[] > seen = ArgumentCaptor.forClass( byte[].class );
        verify( host ).authenticate( eq( "plat" ), eq( "github" ), seen.capture(), any() );
        assertTrue( java.util.Arrays.equals( raw, seen.getValue() ) );
    }

    @Test
    void aVerifiedDeliveryIs204WhateverItHolds() throws Exception {
        signedAs( "github" );
        signedAs( "generic" );
        final String deep = "[".repeat( 100_000 );
        final List< Out > outs = List.of(
            post( "/plat/github", PUSH, GITHUB_PUSH ),
            post( "/plat/github", "{\"zen\":\"x\"}", Map.of( "X-GitHub-Event", "ping", "X-Hub-Signature-256", SECRET_HEADER ) ),
            post( "/plat/github", "not json at all {{", GITHUB_PUSH ),
            post( "/plat/github", "", GITHUB_PUSH ),
            post( "/plat/github", deep, GITHUB_PUSH ),
            post( "/plat/github", "[1,2,3]", GITHUB_PUSH ),
            post( "/plat/generic", "{\"kind\":\"push\",\"branch\":\"b\",\"at\":\"2026-03-01T10:00:00Z\"}", Map.of( "X-Wikantik-Signature", SECRET_HEADER ) ),
            post( "/plat/generic", "\"scalar\"", Map.of( "X-Wikantik-Signature", SECRET_HEADER ) ) );
        for ( final Out o : outs ) {
            assertEquals( 204, o.status() );
            assertEquals( "", o.body() );
        }
        // every verified delivery is applied, a ping and garbage with an empty translation included
        verify( host, Mockito.times( outs.size() ) ).apply( any(), any() );
        final ArgumentCaptor< List< CodeEvent > > events = ArgumentCaptor.captor();
        verify( host, Mockito.times( outs.size() ) ).apply( any(), events.capture() );
        assertEquals( 1, events.getAllValues().get( 0 ).size() );
        assertTrue( events.getAllValues().get( 1 ).isEmpty() );
        assertTrue( events.getAllValues().get( 2 ).isEmpty() );
        assertTrue( events.getAllValues().get( 4 ).isEmpty(), "a deeply nested body is refused by the parser" );
        assertEquals( 1, events.getAllValues().get( 6 ).size() );
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsARejectedDelivery() throws Exception {
        signedAs( "github" );
        signedAs( "generic" );
        final Map< String, String > generic = Map.of( "X-Wikantik-Signature", SECRET_HEADER );
        for ( final String bad : List.of( "hello", "\"scalar\"", "42", "[1,2,3]", "", "not json at all {{" ) ) {
            assertEquals( 204, post( "/plat/github", bad, GITHUB_PUSH ).status(), bad );
        }
        for ( final String bad : List.of( "hello", "true" ) ) {
            assertEquals( 204, post( "/plat/generic", bad, generic ).status(), bad );
        }
        verify( host, Mockito.times( 8 ) ).rejected();
        final ArgumentCaptor< List< CodeEvent > > events = ArgumentCaptor.captor();
        verify( host, Mockito.times( 8 ) ).apply( any(), events.capture() );
        assertTrue( events.getAllValues().stream().allMatch( List::isEmpty ), "nothing is translated from it" );
        post( "/plat/generic", "[{\"kind\":\"push\",\"branch\":\"b\",\"at\":\"2026-03-01T10:00:00Z\"}]", generic );
        post( "/plat/github", "{\"zen\":\"x\"}", Map.of( "X-GitHub-Event", "ping", "X-Hub-Signature-256", SECRET_HEADER ) );
        verify( host, Mockito.times( 8 ) ).rejected();
    }

    @Test
    void anEventWithoutItsOwnTimeIsARejectedDeliveryNeverStampedOnArrival() throws Exception {
        signedAs( "github" );
        signedAs( "generic" );
        final Map< String, String > generic = Map.of( "X-Wikantik-Signature", SECRET_HEADER );
        final String closed = "{\"kind\":\"review_closed\",\"branch\":\"PLAT-1-x\",\"review\":{\"number\":12}}";
        final String oneOfTwo = "[{\"kind\":\"push\",\"branch\":\"b\",\"at\":\"2026-03-01T10:00:00Z\"},"
            + "{\"kind\":\"review_reopened\",\"branch\":\"b\",\"review\":{\"number\":12},\"at\":\"yesterday\"}]";
        final String untimedPush = PUSH.replace( ",\"pushed_at\":1772366400", "" );
        final List< LogEvent > lines = logged( null, () -> {
            try {
                assertEquals( 204, post( "/plat/generic", closed, generic ).status() );
                assertEquals( 204, post( "/plat/generic", oneOfTwo, generic ).status() );
                assertEquals( 204, post( "/plat/github", untimedPush, GITHUB_PUSH ).status() );
            } catch ( final Exception e ) {
                throw new IllegalStateException( e );
            }
        } );
        verify( host, Mockito.times( 3 ) ).rejected();
        final ArgumentCaptor< List< CodeEvent > > events = ArgumentCaptor.captor();
        verify( host, Mockito.times( 3 ) ).apply( any(), events.capture() );
        assertTrue( events.getAllValues().stream().allMatch( List::isEmpty ), "nothing of such a delivery is applied" );
        final List< String > warned = lines.stream().filter( e -> e.getLevel() == Level.WARN )
            .map( e -> e.getMessage().getFormattedMessage() ).toList();
        assertTrue( warned.stream().anyMatch( l -> l.contains( "a review_closed event has no usable time" ) ), warned.toString() );
        assertTrue( warned.stream().anyMatch( l -> l.contains( "a review_reopened event has no usable time" ) ), warned.toString() );
        assertTrue( warned.stream().anyMatch( l -> l.contains( "a push event has no usable time" ) ), warned.toString() );
        assertTrue( warned.stream().filter( l -> l.contains( "outcome=rejected" ) ).count() == 3, warned.toString() );
    }

    @Test
    void aVerifiedFormEncodedDeliveryIs415WhileAnUnverifiedOneStaysThe404() throws Exception {
        signedAs( "github" );
        final String form = "payload=%7B%7D";
        final Out verified = run( request( "POST", "/plat/github", form.getBytes( StandardCharsets.UTF_8 ), GITHUB_PUSH,
            "application/x-www-form-urlencoded; charset=UTF-8" ) );
        assertEquals( 415, verified.status() );
        verify( host, never() ).apply( any(), any() );
        when( host.authenticate( any(), any(), any(), any() ) ).thenReturn( Optional.empty() );
        final Out unverified = run( request( "POST", "/plat/github", form.getBytes( StandardCharsets.UTF_8 ), GITHUB_PUSH,
            "application/x-www-form-urlencoded" ) );
        assertEquals( 404, unverified.status() );
    }

    @Test
    void anOversizedBodyIs413AndIsNotChecked() throws Exception {
        maxBody = 2048;
        signedAs( "github" );
        final byte[] big = new byte[ 5000 ];
        final Out declared = run( request( "POST", "/plat/github", big, GITHUB_PUSH, "application/json" ) );
        assertEquals( 413, declared.status() );
        // no declared length (chunked): at most max + 1 bytes are read
        final HttpServletRequest chunked = request( "POST", "/plat/github", big, GITHUB_PUSH, "application/json" );
        when( chunked.getContentLengthLong() ).thenReturn( -1L );
        final Body stream = new Body( big );
        when( chunked.getInputStream() ).thenReturn( stream );
        assertEquals( 413, run( chunked ).status() );
        assertTrue( stream.served <= maxBody + 1, "read " + stream.served );
        verify( host, never() ).authenticate( any(), any(), any(), any() );
        verify( host, never() ).apply( any(), any() );
    }

    @Test
    void aBodyExactlyAtTheLimitIsChecked() throws Exception {
        maxBody = 2048;
        signedAs( "github" );
        final Out o = run( request( "POST", "/plat/github", new byte[ 2048 ], GITHUB_PUSH, "application/json" ) );
        assertEquals( 204, o.status() );
    }

    @Test
    void noSessionIsTouched() throws Exception {
        signedAs( "github" );
        final HttpServletRequest req = request( "POST", "/plat/github", PUSH.getBytes( StandardCharsets.UTF_8 ), GITHUB_PUSH,
            "application/json" );
        run( req );
        verify( req, never() ).getSession();
        verify( req, never() ).getSession( Mockito.anyBoolean() );
        verify( req, never() ).getHeader( "Authorization" );
        verify( req, never() ).getCookies();
        verify( req, never() ).getHeader( "Origin" );
    }

    @Test
    void aDatabaseFailureIs503WithNoDetail() throws Exception {
        signedAs( "github" );
        when( host.apply( any(), any() ) ).thenThrow( new SQLException( "secret row value", "23505" ) );
        final Out sql = post( "/plat/github", PUSH, GITHUB_PUSH );
        assertEquals( 503, sql.status() );
        assertEquals( "Work is unavailable right now.", com.google.gson.JsonParser.parseString( sql.body() ).getAsJsonObject()
            .get( "message" ).getAsString() );
        assertFalse( sql.body().contains( "secret" ) );
        Mockito.doThrow( new WorkUnavailableException( "directory down" ) ).when( host ).apply( any(), any() );
        assertEquals( 503, post( "/plat/github", PUSH, GITHUB_PUSH ).status() );
    }

    private static List< LogEvent > logged( final org.apache.logging.log4j.Logger unused, final Runnable action ) {
        final List< LogEvent > events = new CopyOnWriteArrayList<>();
        final AbstractAppender capture = new AbstractAppender( "WorkHookCapture", null, null, true, Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent event ) {
                events.add( event.toImmutable() );
            }
        };
        final String name = WorkHookResource.class.getName();
        final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
        final LoggerConfig own = LoggerConfig.newBuilder().withLoggerName( name ).withLevel( Level.ALL )
            .withAdditivity( false ).withConfig( ctx.getConfiguration() ).build();
        capture.start();
        own.addAppender( capture, Level.ALL, null );
        ctx.getConfiguration().addLogger( name, own );
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            ctx.getConfiguration().removeLogger( name );
            ctx.updateLoggers();
            capture.stop();
        }
        return events;
    }

    @Test
    void theDeliveryLineCarriesNoBodyText() throws Exception {
        signedAs( "github" );
        when( host.authenticate( eq( "evil" ), any(), any(), any() ) ).thenReturn( Optional.empty() );
        final Map< String, String > hostile = Map.of( "X-GitHub-Event", "push\nFORGED line", "X-GitHub-Delivery",
            "id\r\n[ERROR] forged " + "x".repeat( 200 ), "X-Hub-Signature-256", SECRET_HEADER );
        final List< LogEvent > events = logged( null, () -> {
            try {
                post( "/plat/github", PUSH, hostile );
                post( "/evil/github", PUSH, hostile );
                post( "/plat/github", "TOPSECRETMESSAGE not json", GITHUB_PUSH );
            } catch ( final Exception e ) {
                throw new IllegalStateException( e );
            }
        } );
        assertEquals( 3, events.size(), "one delivery line per delivery" );
        for ( final LogEvent e : events ) {
            final String line = e.getMessage().getFormattedMessage();
            assertFalse( line.contains( "TOPSECRETMESSAGE" ), line );
            assertFalse( line.contains( SECRET_HEADER ) || line.contains( "sha256" ), line );
            assertFalse( line.contains( "\n" ) || line.contains( "\r" ), line );
            assertFalse( line.contains( "forged" ) && line.length() > 400, line );
        }
        final String first = events.get( 0 ).getMessage().getFormattedMessage();
        assertTrue( first.contains( "outcome=ignored" ) && first.contains( "applied=1" ) && first.contains( "product=plat" ), first );
        assertTrue( events.get( 1 ).getMessage().getFormattedMessage().contains( "outcome=rejected" ) );
    }

    @Test
    void theConfiguredLimitIsRaisedToAtLeast1024() {
        final WorkHookResource real = new WorkHookResource() {
            @Override
            protected java.util.Properties properties() {
                final java.util.Properties p = new java.util.Properties();
                p.setProperty( "wikantik.work.codehost.maxBodyBytes", "10" );
                return p;
            }
        };
        assertEquals( 1024L, real.maxBodyBytes() );
        final WorkHookResource dflt = new WorkHookResource() {
            @Override
            protected java.util.Properties properties() {
                return new java.util.Properties();
            }
        };
        assertEquals( 1_048_576L, dflt.maxBodyBytes() );
        assertFalse( dflt.codeHostEnabled() );
    }
}
