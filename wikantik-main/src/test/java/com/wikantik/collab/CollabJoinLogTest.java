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

import com.wikantik.collab.CollabSessionRegistry.Refused;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Re-review logging: a client repeating a refused join (a malformed seed, a join of a page a rename is still moving
 * into) warns at most once a minute per author; the rest go to DEBUG.
 */
class CollabJoinLogTest {

    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    private static final CollabPermissions ALL = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    private MutableClock clock;
    private CollabSessionRegistry registry;

    @BeforeEach
    void setUp() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( "Page", "hello\n", Map.of() );
        clock = new MutableClock( T0 );
        registry = new CollabSessionRegistry( new InMemoryCollabStore(), writer, ALL, key -> null, clock,
            new CollabConfig( true, 20, 65536, 25, 45, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
    }

    private static long warningsContaining( final List< LogEvent > events, final String text ) {
        return events.stream().filter( e -> e.getLevel() == Level.WARN )
            .filter( e -> e.getMessage().getFormattedMessage().contains( text ) ).count();
    }

    @Test
    void aRepeatedlyMalformedSeedWarnsOnceAMinute() {
        final JoinRequest bad = new JoinRequest( "ada", null, "ada", "Ada", null, Map.of( "__yaml__", "a: b" ) );
        final List< LogEvent > first = CollabLogCapture.warnings( () -> {
            for( int i = 0; i < 4; i++ ) {
                assertEquals( 400, assertInstanceOf( Refused.class, registry.join( "NewPage", bad ) ).status() );
            }
        } );
        assertEquals( 1, warningsContaining( first, "refused" ), first::toString );
        clock.advance( Duration.ofMinutes( 1 ) );
        final List< LogEvent > later = CollabLogCapture.warnings( () -> registry.join( "NewPage", bad ) );
        assertEquals( 1, warningsContaining( later, "refused" ), "a minute on it warns again" );
    }

    @Test
    void repeatedJoinsOfAPageARenameIsMovingIntoWarnOnceAMinute() {
        registry.renameStarting( "Page", "Moved" );
        final JoinRequest r = new JoinRequest( "ada", null, "ada", "Ada", null, null );
        final List< LogEvent > got = CollabLogCapture.warnings( () -> {
            for( int i = 0; i < 4; i++ ) {
                assertEquals( 503, assertInstanceOf( Refused.class, registry.join( "Moved", r ) ).status() );
            }
        } );
        assertEquals( 1, warningsContaining( got, "rename into it is pending" ), got::toString );
    }
}
