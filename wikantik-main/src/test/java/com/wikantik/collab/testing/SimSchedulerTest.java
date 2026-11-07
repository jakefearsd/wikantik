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

import com.wikantik.collab.testing.SimProfile.Kind;
import com.wikantik.collab.testing.SimServer.PullResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.testing.SimServer.Wakeup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimSchedulerTest {

    @Test
    void theSameSeedReplaysTheSameTrace() {
        for( final SimProfile p : SimProfile.values() ) {
            final SimTrace a = new SimScheduler( 42, p, SimScheduler.STUB, 3 ).run( 200 );
            final SimTrace b = new SimScheduler( 42, p, SimScheduler.STUB, 3 ).run( 200 );
            assertEquals( SimTrace.Status.PASS, a.outcome().status(), p + ": " + a.outcome().message() );
            assertEquals( a.toJson(), b.toJson(), p.name() );
            // replay is by trace, not by seed: the recorded steps alone reproduce everything
            assertEquals( a.toJson(), SimScheduler.replay( a, SimScheduler.STUB ).toJson(), p.name() );
            assertNotEquals( a.toJson(), new SimScheduler( 43, p, SimScheduler.STUB, 3 ).run( 200 ).toJson(), p.name() );
        }
    }

    @Test
    void traceJsonRoundTrips() {
        final SimTrace t = new SimScheduler( 7, SimProfile.LOSSY, SimScheduler.STUB, 4 ).run( 150 );
        final SimTrace back = SimTrace.fromJson( t.toJson() );
        assertEquals( t.toJson(), back.toJson() );
        assertEquals( t.steps(), back.steps() );
        assertEquals( 4, back.clientTraces().size() );
        assertTrue( back.clientTraces().get( 0 ).get( 0 ).contains( "\"type\":\"join\"" ) );
    }

    @Test
    void theRunExercisesTheProtocolPaths() {
        // across a handful of lossy seeds the simulator must hit every protocol path it claims to model
        final StringBuilder all = new StringBuilder();
        for( long seed = 1; seed <= 30; seed++ ) {
            final SimTrace t = new SimScheduler( seed, SimProfile.LOSSY, SimScheduler.STUB, 3 ).run( 200 );
            assertEquals( SimTrace.Status.PASS, t.outcome().status(), seed + ": " + t.outcome().message() );
            all.append( t.toJson() );
        }
        final String s = all.toString();
        for( final String needle : List.of( "\"action\": \"DROP\"", "\"action\": \"DUPLICATE\"", "\"action\": \"TIMEOUT\"",
                "\"action\": \"ADVANCE_CLOCK\"", "\"action\": \"PUBLISH\"", "\"status\": 409", "\"type\": \"resync\"" ) ) {
            assertTrue( s.contains( needle ), "never saw " + needle );
        }
    }

    /** A server that forgets who sent each update: clients cannot recognise their own, so they double-apply. */
    private static final Function< SimEnv, SimServer > FORGETFUL = env -> new ForwardingServer( SimScheduler.STUB.apply( env ) ) {
        @Override
        PullResult rewrite( final PullResult r ) {
            final List< Update > anon = r.updates().stream().map( u -> new Update( "someone-else", u.changes() ) ).toList();
            return new PullResult( r.status(), anon, r.version(), r.parked(), r.reason() );
        }
    };

    @Test
    void aDivergentServerIsReportedAndShrunk() {
        SimTrace failing = null;
        for( long seed = 1; seed <= 50 && failing == null; seed++ ) {
            final SimTrace t = new SimScheduler( seed, SimProfile.CALM, FORGETFUL, 2 ).run( 120 );
            if( t.outcome().status() == SimTrace.Status.FAIL ) {
                failing = t;
            }
        }
        assertTrue( failing != null, "the forgetful server was never caught" );
        final SimTrace shrunk = Shrinker.shrinkSameFailure( failing, FORGETFUL, SimScheduler.NO_RELOAD );
        assertTrue( shrunk.steps().size() < failing.steps().size(), "nothing was shrunk" );
        assertTrue( shrunk.steps().size() <= 6, "shrunk trace still has " + shrunk.steps().size() + " steps" );
        final SimTrace.Outcome again = SimScheduler.replay( shrunk, FORGETFUL ).outcome();
        assertTrue( again.sameFailure( failing.outcome() ), failing.outcome().kind() + " became " + again.kind() );
    }

    @Test
    void aPoisonedServerEndsTheSeedAsUnsupported() {
        final Function< SimEnv, SimServer > poisonsAtOnce = env -> new ForwardingServer( SimScheduler.STUB.apply( env ) ) {
            @Override
            public InvariantView view() {
                final InvariantView v = super.view();
                return new InvariantView( v.sessionId(), v.doc(), v.head(), v.baseBody(), v.local(), v.basePageVersion(),
                    v.ring(), v.oversize(), v.maxPageBytes(), v.store(), v.writer(), v.pageName(), v.externalChangePending(),
                    true, v.oversizeCeiling(), true );
            }
        };
        final SimTrace t = new SimScheduler( 1, SimProfile.CALM, poisonsAtOnce, 2 ).run( 50 );
        assertEquals( SimTrace.Status.UNSUPPORTED, t.outcome().status() );
    }

    @Test
    void crashRecoverAndLeaveAreWeightedWhereTheRulingsSay() {
        for( final SimProfile p : SimProfile.values() ) {
            assertEquals( 0, p.weight( Kind.EXTERNAL_SAVE ), p.name() );
        }
        assertTrue( SimProfile.LOSSY.weight( Kind.CRASH_RECOVER ) > 0 );
        assertTrue( SimProfile.LOSSY.weight( Kind.LEAVE ) > 0 );
        assertTrue( SimProfile.AGENT_HEAVY.weight( Kind.LEAVE ) > 0 );
        assertEquals( 0, SimProfile.CALM.weight( Kind.CRASH_RECOVER ) );
        assertEquals( 0, SimProfile.CALM.weight( Kind.LEAVE ) );
        // A10 truncation: TIGHT only in the profiles (the stub corpus); the session adds it everywhere by its overlay
        for( final SimProfile p : SimProfile.values() ) {
            assertEquals( p == SimProfile.TIGHT, p.weight( Kind.TRUNCATE ) > 0, p.name() );
            assertTrue( CollabSessionSimServer.OVERLAY.weights().get( Kind.TRUNCATE ) > 0 );
        }
        assertEquals( 2, SimProfile.TIGHT.ringSize() );
    }

    @Test
    void anOverlayChangesOnlyTheServerItBelongsTo() {
        // a zero-weight action added to the profiles must not move the stub's random draws (its corpus stays put)
        final SimTrace plain = new SimScheduler( 5, SimProfile.LOSSY, SimScheduler.STUB, 3 ).run( 120 );
        final SimTrace noOverlay = new SimScheduler( 5, SimProfile.LOSSY, SimScheduler.STUB, SimScheduler.STUB_RELOAD,
            SimScheduler.Overlay.NONE, 3 ).run( 120 );
        assertEquals( plain.toJson(), noOverlay.toJson() );
        final SimTrace overlaid = new SimScheduler( 5, SimProfile.LOSSY, SimScheduler.STUB, SimScheduler.STUB_RELOAD,
            CollabSessionSimServer.OVERLAY, 3 ).run( 120 );
        assertTrue( overlaid.toJson().contains( "\"action\": \"TRUNCATE\"" ) );
        assertFalse( plain.toJson().contains( "\"action\": \"TRUNCATE\"" ) );
    }

    @Test
    void anExceptionKindNamesTheFirstWikantikFrame() {
        // a failure inside a JDK call: the kind names the wiki code that made the call, not the JDK frame
        final Function< SimEnv, SimServer > throwsInJdk = env -> new ForwardingServer( SimScheduler.STUB.apply( env ) ) {
            @Override
            public PushResult push( final String participantId, final String authorKey, final int version,
                                    final List< Update > updates ) {
                return List.< PushResult >of().get( version + 1 );
            }
        };
        final SimTrace.Outcome o = new SimScheduler( 1, SimProfile.CALM, throwsInJdk, 2 ).run( 80 ).outcome();
        assertEquals( SimTrace.Status.FAIL, o.status() );
        assertTrue( o.kind().matches( "exception java\\.lang\\.\\w*IndexOutOfBoundsException at "
            + "com\\.wikantik\\.collab\\.testing\\.SimSchedulerTest.*" ), o.kind() );
    }

    /** A CALM run with extra steps spliced in after every {@code every}-th step (ids are kept unique). */
    private static SimTrace spliced( final long seed, final Kind kind, final int every ) {
        final SimTrace t = new SimScheduler( seed, SimProfile.CALM, SimScheduler.STUB, 3 ).run( 150 );
        final List< SimTrace.Step > steps = new ArrayList<>();
        for( final SimTrace.Step st : t.steps() ) {
            steps.add( st );
            if( st.id() % every == every - 1 ) {
                final SimTrace.Step extra = SimTrace.Step.of( 10_000 + st.id(), kind );
                steps.add( kind == Kind.LEAVE ? extra.client( st.id() % 3 ) : extra );
            }
        }
        return t.withSteps( steps );
    }

    @Test
    void aCrashReloadsTheServerOverTheSameStoreAndTheClientsConverge() {   // E37 + F8
        for( long seed = 1; seed <= 20; seed++ ) {
            final SimTrace r = SimScheduler.replay( spliced( seed, Kind.CRASH_RECOVER, 20 ), SimScheduler.STUB );
            assertEquals( SimTrace.Status.PASS, r.outcome().status(), seed + ": " + r.outcome().message() );
            assertEquals( 7L, r.counters().get( SimScheduler.CRASHES ), seed + ": " + r.counters() );
            assertTrue( r.counters().get( SimScheduler.REJOINS ) + r.counters().get( SimScheduler.RESYNCS ) > 0,
                seed + ": " + r.counters() );
            // replay stays deterministic across reloads (participant ids carry the per-store boot generation)
            assertEquals( r.toJson(), SimScheduler.replay( r, SimScheduler.STUB ).toJson() );
        }
    }

    @Test
    void aCrashStepNeedsAReloadForTheFactory() {
        final SimTrace t = spliced( 1, Kind.CRASH_RECOVER, 20 );
        final Function< SimEnv, SimServer > wrapped = env -> new ForwardingServer( SimScheduler.STUB.apply( env ) );
        final SimTrace.Outcome o = SimScheduler.replay( t, wrapped ).outcome();
        assertEquals( SimTrace.Status.FAIL, o.status() );
        assertTrue( o.kind().startsWith( "exception java.lang.UnsupportedOperationException at " ), o.kind() );
    }

    @Test
    void aDroppedParticipantRejoinsKeepingItsUnsentEdits() {   // 7.2 expired participants
        long kept = 0;
        for( long seed = 1; seed <= 20; seed++ ) {
            final SimTrace r = SimScheduler.replay( spliced( seed, Kind.LEAVE, 9 ), SimScheduler.STUB );
            assertEquals( SimTrace.Status.PASS, r.outcome().status(), seed + ": " + r.outcome().message() );
            assertTrue( r.counters().get( SimScheduler.LEAVES ) > 0, seed + ": " + r.counters() );
            // the server still knows the clientID, so the client gets it back and keeps its whole state (7.2)
            kept += r.counters().get( SimScheduler.RECONNECTS );
        }
        assertTrue( kept > 0, "no dropped participant ever rejoined with its edits kept" );
    }

    /**
     * Answers the first non-empty push with a spurious 413 (so the client resyncs while a duplicate of that push is still
     * in flight); with {@code honourLeave} false its leave does nothing, so the resyncing client's old participant stays
     * alive.
     */
    private static Function< SimEnv, SimServer > spurious413( final boolean honourLeave ) {
        return env -> new ForwardingServer( SimScheduler.STUB.apply( env ) ) {
            private boolean refused;

            @Override
            public PushResult push( final String participantId, final String authorKey, final int version,
                                    final List< Update > updates ) {
                if( !updates.isEmpty() && !refused ) {
                    refused = true;
                    return new PushResult( 413, view().head(), "oversize" );
                }
                return super.push( participantId, authorKey, version, updates );
            }

            @Override
            public Left leave( final String participantId, final String authorKey ) {
                return honourLeave ? super.leave( participantId, authorKey ) : new Left( 200, null );
            }
        };
    }

    /**
     * One client types, pushes, and the push is duplicated on the wire; the first copy is refused 413, the client
     * resyncs (leave, then join), and only then is the duplicate delivered, addressed to the abandoned participant.
     */
    private static SimTrace stragglerAfterResync() {
        final List< SimTrace.Step > steps = List.of(
            SimTrace.Step.of( 0, Kind.LOCAL_EDIT ).client( 0 ).edit( 3, 3, "d" ),
            SimTrace.Step.of( 1, Kind.SEND_PUSH ).client( 0 ),         // push 1.0
            SimTrace.Step.of( 2, Kind.DUPLICATE ).message( "1.0" ),     // its copy 2.0
            SimTrace.Step.of( 3, Kind.DELIVER ).message( "1.0" ),       // 413 -> 3.0
            SimTrace.Step.of( 4, Kind.DELIVER ).message( "3.0" ),       // resync: leave 4.0
            SimTrace.Step.of( 5, Kind.DELIVER ).message( "4.0" ),       // left 5.0
            SimTrace.Step.of( 6, Kind.DELIVER ).message( "5.0" ),       // join 6.0
            SimTrace.Step.of( 7, Kind.DELIVER ).message( "6.0" ),       // joined 7.0
            SimTrace.Step.of( 8, Kind.DELIVER ).message( "7.0" ),       // fresh state; "abcd" to the recovery panel
            SimTrace.Step.of( 9, Kind.DELIVER ).message( "2.0" ) );     // the straggler
        return new SimTrace( 1, SimProfile.CALM, "SimPage", 64 * 1024, 4, "none", 1, "abc", steps,
            SimTrace.Outcome.NOT_RUN, List.of() );
    }

    @Test
    void aStragglerPushAcceptedAfterAResyncIsCaught() {   // A10
        final SimTrace.Outcome o = SimScheduler.replay( stragglerAfterResync(), spurious413( false ) ).outcome();
        assertEquals( "protocol/straggler", o.kind(), o.message() );
        assertTrue( o.message().contains( "A10" ), o.message() );
        assertEquals( "9", o.at() );
    }

    @Test
    void leavingBeforeAResyncKeepsTheStragglerOut() {   // A10
        final SimTrace r = SimScheduler.replay( stragglerAfterResync(), spurious413( true ) );
        assertEquals( SimTrace.Status.PASS, r.outcome().status(), r.outcome().message() );
        assertEquals( 1L, r.counters().get( SimScheduler.RETIRED_REFUSED ) );
        assertEquals( 1L, r.counters().get( SimScheduler.RESYNCS ) );
        // the typed text went to the recovery panel only: the server never applied it
        assertTrue( r.outcome().message().startsWith( "converged at head 0 " ), r.outcome().message() );
    }

    @Test
    void eachProfileExploresWhatItClaims() {
        final Map< SimProfile, Map< String, Long > > seen = new EnumMap<>( SimProfile.class );
        for( final SimProfile p : List.of( SimProfile.LOSSY, SimProfile.CALM, SimProfile.AGENT_HEAVY, SimProfile.TIGHT ) ) {
            final Map< String, Long > sum = new TreeMap<>();
            for( long seed = 1; seed <= 60; seed++ ) {
                final SimTrace t = new SimScheduler( seed, p, SimScheduler.STUB, 2 + (int) ( seed % 3 ) ).run( 200 );
                assertEquals( SimTrace.Status.PASS, t.outcome().status(), p + " " + seed + ": " + t.outcome().message() );
                t.counters().forEach( ( k, v ) -> sum.merge( k, v, Long::sum ) );
            }
            assertEquals( new TreeSet<>( SimScheduler.COUNTERS ), sum.keySet(), p.name() );
            seen.put( p, sum );
        }
        final Map< String, Long > lossy = seen.get( SimProfile.LOSSY );
        for( final String c : SimScheduler.COUNTERS ) {
            if( !List.of( SimScheduler.TRUNCATIONS, SimScheduler.GONE_PULLS ).contains( c ) ) {
                assertTrue( lossy.get( c ) > 0, "LOSSY never exercised " + c + ": " + lossy );
            }
        }
        // TIGHT is the A10 regime: everything LOSSY does, plus truncation and the 410 resync it causes
        final Map< String, Long > tight = seen.get( SimProfile.TIGHT );
        for( final String c : SimScheduler.COUNTERS ) {
            if( !c.equals( SimScheduler.OUT_OF_ORDER_PULLS ) ) {
                assertTrue( tight.get( c ) > 0, "TIGHT never exercised " + c + ": " + tight );
            }
        }
        final Map< String, Long > calm = seen.get( SimProfile.CALM );
        // one pull at a time on a lossless network: CALM's own path is the long-poll timeout
        assertTrue( calm.get( SimScheduler.EXPIRED_PULLS ) > 0, "CALM never exercised a parked-pull expiry: " + calm );
        for( final String c : List.of( SimScheduler.DUPLICATE_ANSWERS, SimScheduler.LEAVES, SimScheduler.CRASHES ) ) {
            assertEquals( 0L, calm.get( c ), "CALM has no faults, yet " + c + ": " + calm );
        }
        final Map< String, Long > agents = seen.get( SimProfile.AGENT_HEAVY );
        for( final String c : List.of( SimScheduler.LEAVES, SimScheduler.RECONNECTS, SimScheduler.DUPLICATE_ANSWERS ) ) {
            assertTrue( agents.get( c ) > 0, "AGENT_HEAVY never exercised " + c + ": " + agents );
        }
    }

    @Test
    void theSessionExploresTheRecoveryAndA10Paths() {
        // the session fuzz is only as good as the paths it reaches: crashes and reloads, 410s after truncation, reissued
        // clientIDs, rebased rejoins and resyncs
        final Map< String, Long > sum = new TreeMap<>();
        for( final SimProfile p : List.of( SimProfile.TIGHT, SimProfile.LOSSY ) ) {
            for( long seed = 1; seed <= 60; seed++ ) {
                final SimTrace t = SimTarget.SESSION.scheduler( seed, p, 2 + (int) ( seed % 3 ) ).run( 200 );
                assertEquals( SimTrace.Status.PASS, t.outcome().status(), p + " " + seed + ": " + t.outcome().message() );
                t.counters().forEach( ( k, v ) -> sum.merge( k, v, Long::sum ) );
                // replay of a session run is deterministic too (ids carry the clock and a per-store load count)
                if( seed % 20 == 0 ) {
                    assertEquals( t.toJson(), SimTarget.SESSION.replay( t ).toJson(), p + " " + seed );
                }
            }
        }
        for( final String c : List.of( SimScheduler.CRASHES, SimScheduler.TRUNCATIONS, SimScheduler.GONE_PULLS,
                SimScheduler.RECONNECTS, SimScheduler.REJOINS, SimScheduler.RESYNCS, SimScheduler.RETIRED_REFUSED ) ) {
            assertTrue( sum.get( c ) > 0, "the session never exercised " + c + ": " + sum );
        }
    }

    @Test
    void aTraceHoldingALoneSurrogateStillWritesAsUtf8AndRoundTrips() throws Exception {
        // a failing run can carry a broken document (and messages cut mid-pair); the failure files must still be written
        final String broken = "a\uD83Db\uDE00";
        final SimTrace t = new SimTrace( 1, SimProfile.CALM, "P", 100, 4, "none", 1, broken, List.of(),
            new SimTrace.Outcome( SimTrace.Status.FAIL, "convergence: " + broken, "end" ), List.of() );
        final String json = t.toJson();
        java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode( java.nio.CharBuffer.wrap( json ) );
        assertEquals( broken, SimTrace.fromJson( json ).initialDoc() );
        assertEquals( "convergence: " + broken, SimTrace.fromJson( json ).outcome().message() );
    }

    /** Delegates everything; {@link #rewrite} post-processes every pull answer, immediate or parked. */
    private static class ForwardingServer implements SimServer {
        private final SimServer d;

        ForwardingServer( final SimServer d ) {
            this.d = d;
        }

        PullResult rewrite( final PullResult r ) {
            return r;
        }

        @Override
        public Joined join( final String authorKey, final String priorClientId, final String initialText,
                            final Map< String, Object > initialMeta ) {
            return d.join( authorKey, priorClientId, initialText, initialMeta );
        }

        @Override
        public PushResult push( final String participantId, final String authorKey, final int version, final List< Update > updates ) {
            return d.push( participantId, authorKey, version, updates );
        }

        @Override
        public PullResult pull( final String participantId, final String authorKey, final int since ) {
            return rewrite( d.pull( participantId, authorKey, since ) );
        }

        @Override
        public PublishResult publish( final String participantId, final String authorKey, final int version, final String note ) {
            return d.publish( participantId, authorKey, version, note );
        }

        @Override
        public Left leave( final String participantId, final String authorKey ) {
            return d.leave( participantId, authorKey );
        }

        @Override
        public void evict( final String participantId ) {
            d.evict( participantId );
        }

        @Override
        public List< Wakeup > drainWakeups() {
            return d.drainWakeups().stream().map( w -> new Wakeup( w.participantId(), rewrite( w.result() ) ) ).toList();
        }

        @Override
        public InvariantView view() {
            return d.view();
        }

        @Override
        public void tick() {
            d.tick();
        }

        @Override
        public void truncate() {
            d.truncate();
        }
    }
}
