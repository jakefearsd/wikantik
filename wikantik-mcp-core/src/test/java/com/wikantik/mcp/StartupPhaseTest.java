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
package com.wikantik.mcp;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed startup phase is logged under the endpoint's message and reported so startup stops. */
class StartupPhaseTest {

    private static final String LOGGER = "com.wikantik.mcp.StartupPhaseTest.capture";
    private static final Logger LOG = LogManager.getLogger( LOGGER );
    private static final String MSG = "MCP server startup failed during X — endpoint unavailable: {}";

    private static List< LogEvent > logged( final Runnable action ) {
        final List< LogEvent > events = new CopyOnWriteArrayList<>();
        final AbstractAppender capture = new AbstractAppender( "StartupPhaseCapture", null, null, true,
                Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent event ) {
                events.add( event.toImmutable() );
            }
        };
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final LoggerConfig own = LoggerConfig.newBuilder().withLoggerName( LOGGER ).withLevel( Level.ALL )
                .withAdditivity( false ).withConfig( ctx.getConfiguration() ).build();
        capture.start();
        own.addAppender( capture, Level.ALL, null );
        ctx.getConfiguration().addLogger( LOGGER, own );
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            ctx.getConfiguration().removeLogger( LOGGER );
            ctx.updateLoggers();
            capture.stop();
        }
        return events;
    }

    @Test
    void callReturnsValueAndLogsNothing() {
        final String[] value = new String[ 1 ];
        final List< LogEvent > events = logged( () -> value[ 0 ] = StartupPhase.call( LOG, MSG, () -> "transport" ) );
        assertEquals( "transport", value[ 0 ] );
        assertTrue( events.isEmpty() );
    }

    @Test
    void callLogsErrorWithCauseAndReturnsNull() {
        final String[] value = { "unset" };
        final List< LogEvent > events = logged( () -> value[ 0 ] = StartupPhase.call( LOG, MSG, () -> {
            throw new IllegalStateException( "boom" );
        } ) );
        assertNull( value[ 0 ] );
        assertEquals( 1, events.size() );
        assertEquals( Level.ERROR, events.get( 0 ).getLevel() );
        assertEquals( "MCP server startup failed during X — endpoint unavailable: boom",
                events.get( 0 ).getMessage().getFormattedMessage() );
        assertNotNull( events.get( 0 ).getThrown() );
    }

    @Test
    void runReturnsTrueAndLogsNothing() {
        final boolean[] ok = new boolean[ 1 ];
        final List< LogEvent > events = logged( () -> ok[ 0 ] = StartupPhase.run( LOG, MSG, () -> { } ) );
        assertTrue( ok[ 0 ] );
        assertTrue( events.isEmpty() );
    }

    @Test
    void runLogsErrorWithCauseAndReturnsFalse() {
        final boolean[] ok = { true };
        final List< LogEvent > events = logged( () -> ok[ 0 ] = StartupPhase.run( LOG, MSG, () -> {
            throw new java.io.IOException( "disk" );
        } ) );
        assertFalse( ok[ 0 ] );
        assertEquals( 1, events.size() );
        assertEquals( "MCP server startup failed during X — endpoint unavailable: disk",
                events.get( 0 ).getMessage().getFormattedMessage() );
        assertNotNull( events.get( 0 ).getThrown() );
    }
}
