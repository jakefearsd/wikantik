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
package com.wikantik.auth.apikeys;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Keys stored under the legacy {@code "mcp"} scope name keep working as {@code mcp_admin}, but each
 * use is reported at WARN (throttled per key and client address) so an operator can find the
 * clients that still need a re-minted key. Minting with the legacy name is reported too.
 */
class LegacyScopeUseLogTest {

    private List< LogEvent > captured;
    private AbstractAppender appender;
    private LoggerConfig loggerConfig;
    private Level priorLevel;
    private boolean addedLoggerConfig;

    @BeforeEach
    void setUp() {
        captured = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        appender = new AbstractAppender( "LegacyScopeUseLog-" + System.nanoTime(), null,
                PatternLayout.createDefaultLayout(), true, null ) {
            @Override
            public void append( final LogEvent event ) {
                captured.add( event.toImmutable() );
            }
        };
        appender.start();
        config.addAppender( appender );
        final String loggerName = LegacyScopeUseLog.class.getName();
        loggerConfig = config.getLoggerConfig( loggerName );
        addedLoggerConfig = !loggerConfig.getName().equals( loggerName );
        if ( addedLoggerConfig ) {
            loggerConfig = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.WARN )
                    .setLoggerName( loggerName ).setIncludeLocation( "true" )
                    .setRefs( new AppenderRef[ 0 ] ).setConfig( config ).build();
            config.addLogger( loggerName, loggerConfig );
        }
        priorLevel = loggerConfig.getLevel();
        loggerConfig.setLevel( Level.WARN );
        loggerConfig.addAppender( appender, Level.WARN, null );
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        loggerConfig.removeAppender( appender.getName() );
        if ( addedLoggerConfig ) {
            ctx.getConfiguration().removeLogger( loggerConfig.getName() );
        } else {
            loggerConfig.setLevel( priorLevel );
        }
        ctx.getConfiguration().getAppenders().remove( appender.getName() );
        ctx.updateLoggers();
        appender.stop();
    }

    private static ApiKeyService.Record key( final int id, final boolean legacy ) {
        return new ApiKeyService.Record( id, "hash-" + id, "harness-bot", "nightly-sync", ApiKeyService.Scope.MCP_ADMIN,
                Instant.EPOCH, "admin", null, null, null, legacy );
    }

    private static HttpServletRequest request( final String ip, final String userAgent ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( ip );
        when( req.getHeader( "User-Agent" ) ).thenReturn( userAgent );
        return req;
    }

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {
        private final AtomicReference< Instant > now = new AtomicReference<>( Instant.parse( "2026-10-06T00:00:00Z" ) );
        void advance( final Duration d ) { now.updateAndGet( i -> i.plus( d ) ); }
        @Override public Instant instant() { return now.get(); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone( final java.time.ZoneId zone ) { return this; }
    }

    @Test
    void useOfALegacyKeyIsWarnedWithEverythingNeededToFindTheClient() {
        new LegacyScopeUseLog( Clock.systemUTC() ).keyUsed( "MCP", key( 7, true ), request( "10.1.2.3", "claude-code/2.1" ) );

        assertEquals( 1, captured.size(), "one WARN: " + captured );
        final LogEvent e = captured.get( 0 );
        assertEquals( Level.WARN, e.getLevel() );
        final String msg = e.getMessage().getFormattedMessage();
        for ( final String expected : List.of( "'mcp'", "mcp_admin", "MCP", "id=7", "nightly-sync", "harness-bot",
                "10.1.2.3", "claude-code/2.1" ) ) {
            assertTrue( msg.contains( expected ), "WARN should name " + expected + ": " + msg );
        }
    }

    @Test
    void aCurrentKeyIsNotWarned() {
        new LegacyScopeUseLog( Clock.systemUTC() ).keyUsed( "MCP", key( 8, false ), request( "10.1.2.3", "ua" ) );
        assertTrue( captured.isEmpty(), "unexpected: " + captured );
    }

    @Test
    void repeatsAreThrottledPerKeyAndAddressForAnHour() {
        final MovableClock clock = new MovableClock();
        final LegacyScopeUseLog log = new LegacyScopeUseLog( clock );

        log.keyUsed( "MCP", key( 7, true ), request( "10.1.2.3", "ua" ) );
        log.keyUsed( "MCP", key( 7, true ), request( "10.1.2.3", "ua" ) );
        assertEquals( 1, captured.size(), "the same key from the same address is reported once an hour" );

        log.keyUsed( "MCP", key( 7, true ), request( "10.9.9.9", "ua" ) );
        log.keyUsed( "Knowledge MCP", key( 9, true ), request( "10.1.2.3", "ua" ) );
        assertEquals( 3, captured.size(), "a new address or another key is reported straight away" );

        clock.advance( LegacyScopeUseLog.INTERVAL.plusSeconds( 1 ) );
        log.keyUsed( "MCP", key( 7, true ), request( "10.1.2.3", "ua" ) );
        assertEquals( 4, captured.size(), "reported again once the interval has passed" );
    }

    @Test
    void mintingWithTheLegacyNameIsWarned() {
        LegacyScopeUseLog.mintRequested( "/admin/apikeys", "jake", request( "10.4.4.4", "curl/8.5" ) );

        assertEquals( 1, captured.size(), "one WARN: " + captured );
        final String msg = captured.get( 0 ).getMessage().getFormattedMessage();
        for ( final String expected : List.of( "'mcp'", "mcp_admin", "/admin/apikeys", "jake", "10.4.4.4", "curl/8.5" ) ) {
            assertTrue( msg.contains( expected ), "WARN should name " + expected + ": " + msg );
        }
    }

    @Test
    void theAccessFilterReportsALegacyKeyAndStillAllowsIt() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_old" ) ).thenReturn( Optional.of( key( 11, true ) ) );
        final AbstractApiAccessFilter filter = new AbstractApiAccessFilter( new AbstractApiAccessFilter.Surface(
                "MCP", "mcp.access", ApiKeyService.Scope.MCP_ADMIN,
                AbstractApiAccessFilter.Outcome.Denied.of( 503, "{}" ), "{}" ), null, false, s -> true, svc ) { };
        final HttpServletRequest req = request( "10.5.5.5", "old-harness/1.0" );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_old" );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, filter.authorize( req ) );
        assertEquals( 1, captured.size(), "one WARN: " + captured );
        assertTrue( captured.get( 0 ).getMessage().getFormattedMessage().contains( "old-harness/1.0" ) );
    }
}
