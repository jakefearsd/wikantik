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

import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.CollabSessionRegistry.Refused;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Final review item 6: one author cannot take every seat of a session. An author already holding
 * {@link CollabSessionRegistry#MAX_SEATS_PER_AUTHOR} seats reclaims its own longest-idle seat for a new join, or is
 * refused 429 {@code session-full} (that author only) when every one of its seats is in use.
 */
class CollabSeatCapTest {

    private static final String PAGE = "Page";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final int POLL_SECONDS = 25;

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
        writer.seed( PAGE, "hello\n", Map.of() );
        clock = new MutableClock( T0 );
        registry = new CollabSessionRegistry( new InMemoryCollabStore(), writer, ALL, key -> null, clock,
            new CollabConfig( true, 6, 65536, POLL_SECONDS, 3600, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
    }

    private CollabSessionRegistry.JoinOutcome join( final String author ) {
        return registry.join( PAGE, new JoinRequest( author, null, author, author, null, null ) );
    }

    /** A presence-only push: the tab is alive. */
    private static void seen( final Joined j, final String author ) {
        final PushResponse r = j.session().push( j.response().participantId(), author, j.session().view().head(),
            List.of() );
        assertEquals( 200, r.status() );
    }

    @Test
    void oneAuthorCannotLockOutTheOthers() {
        final List< Joined > tabs = new ArrayList<>();
        for( int i = 0; i < CollabSessionRegistry.MAX_SEATS_PER_AUTHOR; i++ ) {
            tabs.add( assertInstanceOf( Joined.class, join( "alice" ) ) );
        }

        final Refused fifth = assertInstanceOf( Refused.class, join( "alice" ) );
        assertEquals( 429, fifth.status() );
        assertEquals( CollabSessionRegistry.FULL, fifth.reason() );
        assertInstanceOf( Refused.class, join( "alice" ) );

        assertInstanceOf( Joined.class, join( "bob" ) );
        assertInstanceOf( Joined.class, join( "carol" ) );
        assertEquals( CollabSessionRegistry.MAX_SEATS_PER_AUTHOR, tabs.size() );
    }

    @Test
    void aNewJoinReclaimsTheAuthorsLongestIdleSeat() {
        final List< Joined > tabs = new ArrayList<>();
        for( int i = 0; i < CollabSessionRegistry.MAX_SEATS_PER_AUTHOR; i++ ) {
            tabs.add( assertInstanceOf( Joined.class, join( "alice" ) ) );
        }
        clock.advance( Duration.ofSeconds( POLL_SECONDS + CollabSessionRegistry.IDLE_SEAT_SLACK_SECONDS + 1 ) );
        tabs.subList( 1, tabs.size() ).forEach( t -> seen( t, "alice" ) );   // the first tab went quiet

        assertInstanceOf( Joined.class, join( "alice" ) );

        final PushResponse stale = tabs.get( 0 ).session().push( tabs.get( 0 ).response().participantId(), "alice",
            0, List.of() );
        assertEquals( 409, stale.status(), "the idle seat left; its tab rejoins if it ever comes back" );
        assertEquals( CollabSession.REJOIN, stale.reason() );
        seen( tabs.get( 1 ), "alice" );
    }

    @Test
    void aSeatSeenWithinThePollWindowIsNeverReclaimed() {
        for( int i = 0; i < CollabSessionRegistry.MAX_SEATS_PER_AUTHOR; i++ ) {
            assertInstanceOf( Joined.class, join( "alice" ) );
        }
        clock.advance( Duration.ofSeconds( POLL_SECONDS ) );

        assertInstanceOf( Refused.class, join( "alice" ) );
    }
}
