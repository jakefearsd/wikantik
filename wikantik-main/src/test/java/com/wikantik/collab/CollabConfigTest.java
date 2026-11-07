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
package com.wikantik.collab;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollabConfigTest {

    private final List< String > warns = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void captureLog() {
        appender = new AbstractAppender( "collabConfigTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                if ( e.getLevel().isMoreSpecificThan( org.apache.logging.log4j.Level.WARN ) ) {
                    warns.add( e.getMessage().getFormattedMessage() );
                }
            }
        };
        appender.start();
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        final org.apache.logging.log4j.core.config.Configuration config = ctx.getConfiguration();
        final org.apache.logging.log4j.core.config.LoggerConfig lc =
                org.apache.logging.log4j.core.config.LoggerConfig.newBuilder().setAdditivity( false )
                        .setLevel( org.apache.logging.log4j.Level.WARN ).setLoggerName( CollabConfig.class.getName() )
                        .setIncludeLocation( "false" ).setRefs( new org.apache.logging.log4j.core.config.AppenderRef[ 0 ] )
                        .setConfig( config ).build();
        lc.addAppender( appender, org.apache.logging.log4j.Level.WARN, null );
        config.addLogger( CollabConfig.class.getName(), lc );
        ctx.updateLoggers();
    }

    @AfterEach
    void releaseLog() {
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        ctx.getConfiguration().removeLogger( CollabConfig.class.getName() );
        ctx.updateLoggers();
        appender.stop();
    }

    private boolean warned( final String key ) {
        return warns.stream().anyMatch( w -> w.contains( key ) );
    }

    @Test
    void pollTimeoutAboveSixtySecondsFallsBackWithAWarning() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.poll.timeoutSeconds", "61" );
        p.setProperty( "wikantik.collab.presence.timeoutSeconds", "120" );
        final CollabConfig c = CollabConfig.from( p );
        assertEquals( 25, c.pollTimeoutSeconds() );
        assertEquals( 120, c.presenceTimeoutSeconds() );
        assertTrue( warned( "wikantik.collab.poll.timeoutSeconds" ), warns.toString() );
    }

    @Test
    void sixtySecondsIsTheLargestPollTimeout() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.poll.timeoutSeconds", "60" );
        p.setProperty( "wikantik.collab.presence.timeoutSeconds", "90" );
        assertEquals( 60, CollabConfig.from( p ).pollTimeoutSeconds() );
        assertTrue( warns.isEmpty(), warns.toString() );
    }

    @Test
    void pollTimeoutNotBelowThePresenceTimeoutFallsBackToBothDefaultsWithAWarning() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.poll.timeoutSeconds", "30" );
        p.setProperty( "wikantik.collab.presence.timeoutSeconds", "30" );
        final CollabConfig c = CollabConfig.from( p );
        assertEquals( 25, c.pollTimeoutSeconds() );
        assertEquals( 45, c.presenceTimeoutSeconds() );
        assertTrue( warned( "wikantik.collab.presence.timeoutSeconds" ), warns.toString() );
    }

    @Test
    void maxUpdateBytesIsCappedAtSixteenMebibytes() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.maxUpdateBytes", Integer.toString( 16 * 1024 * 1024 + 1 ) );
        assertEquals( 65536, CollabConfig.from( p ).maxUpdateBytes() );
        assertTrue( warned( "wikantik.collab.maxUpdateBytes" ), warns.toString() );
        warns.clear();
        p.setProperty( "wikantik.collab.maxUpdateBytes", Integer.toString( 16 * 1024 * 1024 ) );
        assertEquals( 16 * 1024 * 1024, CollabConfig.from( p ).maxUpdateBytes() );
        assertTrue( warns.isEmpty(), warns.toString() );
    }

    @Test
    void unparseableValuesFallBackWithAWarning() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.maxParticipants", "twenty" );
        p.setProperty( "wikantik.collab.draft.retentionDays", "1.5" );
        final CollabConfig c = CollabConfig.from( p );
        assertEquals( 20, c.maxParticipants() );
        assertEquals( 0, c.draftRetentionDays() );
        assertTrue( warned( "wikantik.collab.maxParticipants" ), warns.toString() );
        assertTrue( warned( "wikantik.collab.draft.retentionDays" ), warns.toString() );
    }

    @Test
    void theDefaultsWarnAboutNothing() {
        CollabConfig.from( new Properties() );
        assertTrue( warns.isEmpty(), warns.toString() );
    }

    @Test
    void defaultsMatchTheDesign() {
        final CollabConfig c = CollabConfig.from( new Properties() );
        assertFalse( c.enabled() );
        assertEquals( 20, c.maxParticipants() );
        assertEquals( 65536, c.maxUpdateBytes() );
        assertEquals( 25, c.pollTimeoutSeconds() );
        assertEquals( 45, c.presenceTimeoutSeconds() );
        assertEquals( 30, c.permissionRecheckSeconds() );
        assertEquals( 2000, c.historyInMemoryUpdates() );
        assertEquals( 500, c.snapshotEveryUpdates() );
        assertEquals( 10, c.memoryIdleEvictMinutes() );
        assertEquals( 0, c.draftRetentionDays() );
        assertEquals( 20, c.pushesPerSecond() );
        assertEquals( CollabConfig.defaults(), c );
    }

    @Test
    void explicitValuesAreRead() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.enabled", "true" );
        p.setProperty( "wikantik.collab.maxParticipants", "5" );
        p.setProperty( "wikantik.collab.pushesPerSecond", "7" );
        p.setProperty( "wikantik.collab.draft.retentionDays", "30" );
        final CollabConfig c = CollabConfig.from( p );
        assertTrue( c.enabled() );
        assertEquals( 5, c.maxParticipants() );
        assertEquals( 7, c.pushesPerSecond() );
        assertEquals( 30, c.draftRetentionDays() );
    }

    @Test
    void nonPositiveValuesFallBackToTheDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.maxParticipants", "0" );
        p.setProperty( "wikantik.collab.maxUpdateBytes", "-1" );
        p.setProperty( "wikantik.collab.poll.timeoutSeconds", "0" );
        p.setProperty( "wikantik.collab.presence.timeoutSeconds", "-5" );
        p.setProperty( "wikantik.collab.permission.recheckSeconds", "0" );
        p.setProperty( "wikantik.collab.history.inMemoryUpdates", "0" );
        p.setProperty( "wikantik.collab.snapshot.everyUpdates", "-9" );
        p.setProperty( "wikantik.collab.memory.idleEvictMinutes", "0" );
        p.setProperty( "wikantik.collab.pushesPerSecond", "0" );
        p.setProperty( "wikantik.collab.draft.retentionDays", "-1" );
        assertEquals( CollabConfig.defaults(), CollabConfig.from( p ) );
    }

    @Test
    void zeroRetentionMeansKeepForever() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.draft.retentionDays", "0" );
        assertEquals( 0, CollabConfig.from( p ).draftRetentionDays() );
    }
}
