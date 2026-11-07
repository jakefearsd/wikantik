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

import com.wikantik.collab.testing.SimServer.PullResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.testing.SimServer.Wakeup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        final String category = failing.outcome().category();
        final SimTrace shrunk = Shrinker.shrink( failing,
            c -> SimScheduler.replay( c, FORGETFUL ).outcome().category().equals( category ) );
        assertTrue( shrunk.steps().size() < failing.steps().size(), "nothing was shrunk" );
        assertTrue( shrunk.steps().size() <= 6, "shrunk trace still has " + shrunk.steps().size() + " steps" );
        assertEquals( SimTrace.Status.FAIL, SimScheduler.replay( shrunk, FORGETFUL ).outcome().status() );
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
    void crashRecoverIsDefinedButNotYetWeighted() {
        for( final SimProfile p : SimProfile.values() ) {
            assertEquals( 0, p.weight( SimProfile.Kind.CRASH_RECOVER ), p.name() );
            assertEquals( 0, p.weight( SimProfile.Kind.EXTERNAL_SAVE ), p.name() );
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
        public Joined join( final String authorKey ) {
            return d.join( authorKey );
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
        public void leave( final String participantId ) {
            d.leave( participantId );
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
    }
}
