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
import com.wikantik.collab.CollabSession;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Final review item 5: a client repeating a refused request (a malformed or oversize body, a malformed push) does not
 * flood the log: at most one WARN a minute per address or participant, the rest at DEBUG.
 */
class CollabRefusalLogTest {

    /** The WARN events of the {@code com.wikantik.rest} package logged while {@code r} ran. */
    private static List< LogEvent > warnings( final ThrowingRunnable r ) throws Exception {
        final String pkg = "com.wikantik.rest";
        final List< LogEvent > got = Collections.synchronizedList( new ArrayList<>() );
        final AbstractAppender appender = new AbstractAppender( "collabRefusalCapture", null, null, true,
            Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                got.add( e.toImmutable() );
            }
        };
        appender.start();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration cfg = ctx.getConfiguration();
        final LoggerConfig lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN )
            .setLoggerName( pkg ).setIncludeLocation( "false" ).setRefs( new AppenderRef[ 0 ] ).setConfig( cfg ).build();
        lc.addAppender( appender, Level.WARN, null );
        cfg.addLogger( pkg, lc );
        ctx.updateLoggers();
        try {
            r.run();
        } finally {
            cfg.removeLogger( pkg );
            ctx.updateLoggers();
            appender.stop();
        }
        return List.copyOf( got );
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static HttpServletRequest request( final String address, final String body ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
        final ByteArrayInputStream in = new ByteArrayInputStream( bytes );
        when( req.getRemoteAddr() ).thenReturn( address );
        when( req.getRequestURI() ).thenReturn( "/api/collab/Page/updates" );
        when( req.getContentLengthLong() ).thenReturn( ( long ) bytes.length );
        when( req.getInputStream() ).thenReturn( new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener( final ReadListener l ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() {
                return in.read();
            }
        } );
        return req;
    }

    @Test
    void repeatedMalformedOrOversizeBodiesFromOneAddressWarnAtMostOnceAMinute() throws Exception {
        final String address = "test-" + UUID.randomUUID();   // a fresh minute for this test
        final List< LogEvent > got = warnings( () -> {
            for( int i = 0; i < 5; i++ ) {
                CollabRequests.readJson( request( address, "{[[[" ), 1024 );
                CollabRequests.readJson( request( address, "x".repeat( 2048 ) ), 1024 );
            }
        } );
        assertEquals( 1, got.stream().filter( e -> e.getMessage().getFormattedMessage().contains( "malformed" ) ).count(),
            got::toString );
        assertEquals( 1, got.stream().filter( e -> e.getMessage().getFormattedMessage().contains( "over 1024 bytes" ) )
            .count(), got::toString );
    }

    @Test
    void repeatedMalformedPushesFromOneParticipantWarnAtMostOnceAMinute() throws Exception {
        final CollabSession s = mock( CollabSession.class );
        when( s.id() ).thenReturn( UUID.randomUUID() );
        final String participant = "p-" + UUID.randomUUID();
        final JsonObject body = new JsonObject();
        body.addProperty( "version", "not a number" );
        final List< LogEvent > got = warnings( () -> {
            for( int i = 0; i < 5; i++ ) {
                assertEquals( 400, CollabPush.run( s, participant, "ada", body, 10, ( st, re ) -> { } ).status() );
            }
        } );
        assertEquals( 1, got.size(), got::toString );
        assertTrue( got.get( 0 ).getMessage().getFormattedMessage().contains( participant ) );
    }
}
