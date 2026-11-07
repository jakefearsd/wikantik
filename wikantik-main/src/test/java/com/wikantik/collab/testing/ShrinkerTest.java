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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShrinkerTest {

    private static Set< Integer > ids( final SimTrace t ) {
        return t.steps().stream().map( SimTrace.Step::id ).collect( Collectors.toSet() );
    }

    @Test
    void dropsStepsThatDoNotMatter() {
        final SimTrace t = new SimScheduler( 3, SimProfile.CALM, SimScheduler.STUB, 2 ).run( 80 );
        assertEquals( 80, t.steps().size() );
        final SimTrace shrunk = Shrinker.shrink( t, c -> ids( c ).containsAll( Set.of( 5, 17, 63 ) ) );
        assertEquals( List.of( 5, 17, 63 ), shrunk.steps().stream().map( SimTrace.Step::id ).toList() );
        assertEquals( t.seed(), shrunk.seed() );
        assertEquals( t.profile(), shrunk.profile() );
        assertEquals( t.initialDoc(), shrunk.initialDoc() );
    }

    @Test
    void keepsEveryStepWhenEachOneMatters() {
        final SimTrace t = new SimScheduler( 4, SimProfile.CALM, SimScheduler.STUB, 2 ).run( 12 );
        final AtomicInteger calls = new AtomicInteger();
        final SimTrace shrunk = Shrinker.shrink( t, c -> {
            calls.incrementAndGet();
            return c.steps().size() == 12;
        } );
        assertEquals( t.steps(), shrunk.steps() );
        assertEquals( true, calls.get() >= 12, "every single-step removal must be tried" );
    }

    @Test
    void theReplayBudgetBoundsAShrink() {
        final SimTrace t = new SimScheduler( 5, SimProfile.CALM, SimScheduler.STUB, 2 ).run( 400 );
        final AtomicInteger calls = new AtomicInteger();
        final SimTrace shrunk = Shrinker.shrink( t, c -> {
            calls.incrementAndGet();
            return c.steps().size() >= t.steps().size() - 3; // can drop only three single steps: many candidates fail
        }, 100 );
        assertEquals( 100, calls.get() );
        assertEquals( true, shrunk.steps().size() >= t.steps().size() - 3 );
        assertEquals( 2_000, Shrinker.MAX_REPLAYS );
    }

    @Test
    void theDefaultBudgetIsTwoThousandReplays() {
        final SimTrace t = new SimScheduler( 6, SimProfile.CALM, SimScheduler.STUB, 2 ).run( 3_000 );
        final AtomicInteger calls = new AtomicInteger();
        Shrinker.shrink( t, c -> {
            calls.incrementAndGet();
            return false;
        } );
        assertEquals( true, calls.get() <= Shrinker.MAX_REPLAYS, calls.get() + " replays" );
    }

    @Test
    void sameFailureMeansTheSameKind() {
        final SimTrace.Outcome inv2 = new SimTrace.Outcome( SimTrace.Status.FAIL, "invariant 2 (head ...) violated: a", "3" );
        final SimTrace.Outcome inv2b = new SimTrace.Outcome( SimTrace.Status.FAIL, "invariant 2 (head ...) violated: b", "9" );
        final SimTrace.Outcome inv3 = new SimTrace.Outcome( SimTrace.Status.FAIL, "invariant 3 (replay) violated: a", "3" );
        assertEquals( "invariant 2", inv2.kind() );
        assertTrue( inv2.sameFailure( inv2b ) );
        assertFalse( inv2.sameFailure( inv3 ) );
        final SimTrace.Outcome ise = new SimTrace.Outcome( SimTrace.Status.FAIL, "exception: x", "1", "exception java.lang.IllegalStateException at A.b(A.java:1)" );
        final SimTrace.Outcome iae = new SimTrace.Outcome( SimTrace.Status.FAIL, "exception: x", "1", "exception java.lang.IllegalArgumentException at A.b(A.java:1)" );
        final SimTrace.Outcome otherFrame = new SimTrace.Outcome( SimTrace.Status.FAIL, "exception: x", "1", "exception java.lang.IllegalStateException at A.c(A.java:9)" );
        assertFalse( ise.sameFailure( iae ) );
        assertFalse( ise.sameFailure( otherFrame ) );
        final SimTrace.Outcome pass = new SimTrace.Outcome( SimTrace.Status.PASS, "converged", "end" );
        assertFalse( pass.sameFailure( pass ) );
    }

    @Test
    void aShrinkKeepsTheConvergenceSubKind() {
        final SimTrace failing = new SimScheduler( 1, SimProfile.CALM, SimScheduler.STUB, 2 ).run( 60 );
        final SimTrace.Outcome doc = new SimTrace.Outcome( SimTrace.Status.FAIL, "convergence: client A differs", "end",
            "convergence/doc" );
        final SimTrace.Outcome version = new SimTrace.Outcome( SimTrace.Status.FAIL, "convergence: client A at version", "end",
            "convergence/version" );
        assertEquals( "convergence", doc.category() );
        assertEquals( doc.category(), version.category() );
        assertFalse( doc.sameFailure( version ) );
        // the kind survives a JSON round trip of a failing trace
        final SimTrace t = new SimTrace( 1, SimProfile.CALM, "P", 100, 4, "none", 1, "", failing.steps(), doc, List.of() );
        assertEquals( "convergence/doc", SimTrace.fromJson( t.toJson() ).outcome().kind() );
    }
}
