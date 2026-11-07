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
package com.wikantik.collab.testing;

import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.testing.InMemoryCollabStore.Op;
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PullResult;
import com.wikantik.collab.testing.SimServer.PushResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F8: crash points come from the doubles; recovery is a fresh server over the same store and page writer (E35, E37).
 * Every test runs against the stub and against the real session.
 */
class RecoveryTest {

    /** How to build, and how to reload, one kind of server over the given doubles. */
    interface Server {
        SimServer fresh( InMemoryCollabStore store, ScriptedPageWriter writer, MutableClock clock, long max, int ring );

        SimServer reload( CollabStore store, CollabPageWriter writer, MutableClock clock, long max, int ring );
    }

    private static final Server STUB = new Server() {
        @Override
        public SimServer fresh( final InMemoryCollabStore st, final ScriptedPageWriter w, final MutableClock c, final long max,
                                final int ring ) {
            return new StubCollabAuthority( PAGE, st, w, c, max, ring );
        }

        @Override
        public SimServer reload( final CollabStore st, final CollabPageWriter w, final MutableClock c, final long max,
                                 final int ring ) {
            return StubCollabAuthority.reload( PAGE, st, w, c, max, ring );
        }

        @Override
        public String toString() {
            return "stub";
        }
    };

    /** The session with the default snapshot cadence, so the log is not truncated under these short tests. */
    private static final Server SESSION = new Server() {
        @Override
        public SimServer fresh( final InMemoryCollabStore st, final ScriptedPageWriter w, final MutableClock c, final long max,
                                final int ring ) {
            return CollabSessionSimServer.create( PAGE, st, w, c, max, ring, 500 );
        }

        @Override
        public SimServer reload( final CollabStore st, final CollabPageWriter w, final MutableClock c, final long max,
                                 final int ring ) {
            return CollabSessionSimServer.reload( PAGE, st, w, c, max, ring, 500 );
        }

        @Override
        public String toString() {
            return "session";
        }
    };

    static Stream< Server > servers() {
        return Stream.of( STUB, SESSION );
    }

    private static final String PAGE = "Page";
    private static final String BODY = "hello  \nworld\n";
    private static final long MAX = 1000;
    private static final int RING = 3;

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of() );
        writer.rewriteOnSave( SimEnv.rewrite( "strip-trailing-spaces" ) );
        clock = new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) );
    }

    private SimServer fresh( final Server server ) {
        return server.fresh( store, writer, clock, MAX, RING );
    }

    private BiFunction< CollabStore, CollabPageWriter, SimServer > reload( final Server server ) {
        return ( s, w ) -> server.reload( s, w, clock, MAX, RING );
    }

    private static PushResult type( final SimServer s, final Joined j, final String author, final String text ) {
        final String doc = s.view().doc();
        final int head = s.view().head();
        return s.push( j.participantId(), author, head,
            List.of( new Update( j.clientId(), ChangeSet.replace( doc.length(), doc.length(), doc.length(), text ) ) ) );
    }

    private static void assertSameCommittedState( final InvariantView expected, final InvariantView actual ) {
        assertEquals( expected.sessionId(), actual.sessionId() );
        assertEquals( expected.doc(), actual.doc() );
        assertEquals( expected.head(), actual.head() );
        assertEquals( expected.baseBody(), actual.baseBody() );
        assertEquals( expected.basePageVersion(), actual.basePageVersion() );
        assertEquals( expected.ring(), actual.ring() );
        assertEquals( expected.oversize(), actual.oversize() );
        // local is recomputed by TextDiff on recovery (7.1): equal by effect, not necessarily by structure
        assertEquals( expected.local().apply( expected.baseBody() ), actual.local().apply( actual.baseBody() ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void e35_aFailedAppendLeavesMemoryUnchanged( final Server server ) {
        final SimServer s = fresh( server );
        final Joined a = s.join( "alice" );
        assertEquals( 200, type( s, a, "alice", "one" ).status() );
        final InvariantView before = s.view();
        final List< StoredUpdate > logBefore = store.updatesSince( before.sessionId(), 0 );

        store.failOn( Op.APPEND_UPDATES, 1 );
        final PushResult failed = type( s, a, "alice", "two" );

        assertEquals( 503, failed.status() );
        assertEquals( "store", failed.reason() );
        assertEquals( before.head(), failed.version() );
        assertSameCommittedState( before, s.view() );
        assertEquals( before.local(), s.view().local() );
        assertEquals( logBefore, store.updatesSince( before.sessionId(), 0 ) );
        CollabInvariants.check( s.view(), true );

        // the retry succeeds
        final PushResult retried = type( s, a, "alice", "two" );
        assertEquals( 200, retried.status() );
        assertEquals( "hello  \nworld\nonetwo", s.view().doc() );
        CollabInvariants.check( s.view(), true );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void aFailedAppendIsNotRecovered( final Server server ) {
        final SimServer s = fresh( server );
        final Joined a = s.join( "alice" );
        type( s, a, "alice", "one" );
        store.failOn( Op.APPEND_UPDATES, 1 );
        assertEquals( 503, type( s, a, "alice", "lost" ).status() );

        final SimServer r = Recovery.recover( store, writer, reload( server ) );

        assertEquals( "hello  \nworld\none", r.view().doc() );
        assertSameCommittedState( s.view(), r.view() );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void recoveredEqualsCommitted( final Server server ) {
        final SimServer s = fresh( server );
        final Joined a = s.join( "alice" );
        final Joined b = s.join( "bob" );
        for( int i = 0; i < 5; i++ ) {
            assertEquals( 200, type( s, i % 2 == 0 ? a : b, i % 2 == 0 ? "alice" : "bob", "x" + i + "  \n" ).status() );
        }
        // the publish rewrites (strips trailing spaces), appending a server update; then more unpublished edits
        assertEquals( 200, s.publish( a.participantId(), "alice", s.view().head(), "p1" ).status() );
        assertEquals( 200, type( s, b, "bob", "after  " ).status() );
        assertEquals( 200, type( s, a, "alice", "!" ).status() );
        final InvariantView committed = s.view();
        assertTrue( committed.basePageVersion() > 1 );
        assertNotEquals( committed.baseBody(), committed.doc() );

        final SimServer r = Recovery.recover( store, writer, reload( server ) );

        assertNotSame( s, r );
        assertSameCommittedState( committed, r.view() );
        CollabInvariants.check( r.view(), true );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void clientsContinueAfterRecovery( final Server server ) {
        final SimServer s = fresh( server );
        final Joined a = s.join( "alice" );
        type( s, a, "alice", "one" );
        final SimServer r = Recovery.recover( store, writer, reload( server ) );

        // the old participant is gone: rejoin
        assertEquals( 409, type( r, a, "alice", "two" ).status() );
        assertEquals( "rejoin", r.pull( a.participantId(), "alice", 0 ).reason() );
        final Joined again = r.join( "alice" );
        assertEquals( r.view().head(), again.version() );
        assertEquals( r.view().doc(), again.doc() );
        // a pull from before the crash is still served from the recovered ring
        final PullResult caughtUp = r.pull( again.participantId(), "alice", 0 );
        assertEquals( 200, caughtUp.status() );
        assertEquals( 1, caughtUp.updates().size() );
        assertEquals( 200, type( r, again, "alice", "two" ).status() );
        assertEquals( 200, r.publish( again.participantId(), "alice", r.view().head(), "after" ).status() );
        CollabInvariants.check( r.view(), true );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void participantIdsAreNeverReusedAcrossRecoveries( final Server server ) {
        // a straggler request from a pre-crash participant must never be taken for a post-crash one (A10)
        final SimServer s = fresh( server );
        final Joined before = s.join( "alice" );
        final SimServer r1 = Recovery.recover( store, writer, reload( server ) );
        final Joined first = r1.join( "alice" );
        final SimServer r2 = Recovery.recover( store, writer, reload( server ) );
        final Joined second = r2.join( "alice" );
        assertNotEquals( before.participantId(), first.participantId() );
        assertNotEquals( first.participantId(), second.participantId() );
        assertNotEquals( before.participantId(), second.participantId() );
        assertNotEquals( before.clientId(), first.clientId() );
        assertNotEquals( first.clientId(), second.clientId() );
        assertEquals( 409, type( r2, before, "alice", "late" ).status() );
        assertEquals( 409, type( r2, first, "alice", "late" ).status() );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void recoveryOfAnOversizeDocumentEntersOversizeState( final Server server ) {
        final SimServer s = server.fresh( store, writer, clock, 5, RING );
        assertTrue( s.view().oversize() );
        final SimServer r = Recovery.recover( store, writer, ( st, w ) -> server.reload( st, w, clock, 5, RING ) );
        assertTrue( r.view().oversize() );
        assertEquals( s.view().oversizeCeiling(), r.view().oversizeCeiling() );
        CollabInvariants.check( r.view(), true );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "servers" )
    void reloadWithoutASessionFails( final Server server ) {
        assertThrows( IllegalStateException.class,
            () -> server.reload( new InMemoryCollabStore(), writer, clock, MAX, RING ) );
    }
}
