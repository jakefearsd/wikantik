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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Minimises a failing trace (F1): removes steps while {@code stillFails} holds, first in halving chunks (fast on long
 * traces), then one step at a time until no single step can go. Replay is by trace, not by seed, so a shorter trace is
 * judged by replaying exactly its remaining steps; steps that no longer make sense (a message that is never sent, an edit
 * past the end of a shorter document) are skipped or clamped by the scheduler.
 *
 * <p>"Still fails" should mean <em>the same failure</em>: {@link #shrinkSameFailure} matches the outcome's
 * {@link SimTrace.Outcome#kind} (invariant number, convergence or protocol sub-kind, exception class and first frame), so
 * a shrink cannot wander from one bug to another. Every shrink is capped at {@code maxReplays} predicate calls
 * ({@value #MAX_REPLAYS} by default) so a failure report stays bounded; at the cap the shortest trace found so far is
 * returned.</p>
 */
public final class Shrinker {

    /** Default bound on predicate calls (replays) per shrink. */
    public static final int MAX_REPLAYS = 2_000;
    private static final Logger LOG = LogManager.getLogger( Shrinker.class );

    private Shrinker() {
    }

    /** Shrinks while a replay against {@code factory} (and {@code reload} for crash steps) fails the same way. */
    public static SimTrace shrinkSameFailure( final SimTrace failing, final Function< SimEnv, SimServer > factory,
                                              final Function< SimEnv, SimServer > reload ) {
        final SimTrace.Outcome original = SimScheduler.replay( failing, factory, reload ).outcome();
        if( original.status() != SimTrace.Status.FAIL ) {
            throw new IllegalArgumentException( "the trace does not fail on replay: " + original.message() );
        }
        return shrink( failing, c -> SimScheduler.replay( c, factory, reload ).outcome().sameFailure( original ) );
    }

    public static SimTrace shrink( final SimTrace failing, final Predicate< SimTrace > stillFails ) {
        return shrink( failing, stillFails, MAX_REPLAYS );
    }

    public static SimTrace shrink( final SimTrace failing, final Predicate< SimTrace > stillFails, final int maxReplays ) {
        final Budget budget = new Budget( stillFails, maxReplays );
        List< SimTrace.Step > steps = new ArrayList<>( failing.steps() );
        for( int chunk = steps.size() / 2; chunk > 1 && !budget.spent(); chunk /= 2 ) {
            steps = removeChunks( failing, steps, chunk, budget );
        }
        boolean shrunk = true;
        while( shrunk && !budget.spent() ) {
            shrunk = false;
            for( int i = steps.size() - 1; i >= 0 && !budget.spent(); i-- ) {
                final List< SimTrace.Step > candidate = new ArrayList<>( steps );
                candidate.remove( i );
                if( budget.test( failing.withSteps( candidate ) ) ) {
                    steps = candidate;
                    shrunk = true;
                }
            }
        }
        if( budget.spent() ) {
            LOG.warn( "shrink of seed {} ({}) stopped at the {}-replay cap with {} of {} steps left", failing.seed(),
                failing.profile(), maxReplays, steps.size(), failing.steps().size() );
        }
        return failing.withSteps( steps );
    }

    /** Counts predicate calls; once {@code max} calls were made it answers false without replaying. */
    private static final class Budget implements Predicate< SimTrace > {
        private final Predicate< SimTrace > inner;
        private final int max;
        private int used;

        Budget( final Predicate< SimTrace > inner, final int max ) {
            if( max < 1 ) {
                throw new IllegalArgumentException( "maxReplays must be >= 1: " + max );
            }
            this.inner = inner;
            this.max = max;
        }

        boolean spent() {
            return used >= max;
        }

        @Override
        public boolean test( final SimTrace t ) {
            if( spent() ) {
                return false;
            }
            used++;
            return inner.test( t );
        }
    }

    private static List< SimTrace.Step > removeChunks( final SimTrace failing, final List< SimTrace.Step > start,
                                                       final int chunk, final Budget stillFails ) {
        List< SimTrace.Step > steps = start;
        int at = 0;
        while( at < steps.size() && !stillFails.spent() ) {
            final List< SimTrace.Step > candidate = new ArrayList<>( steps.subList( 0, at ) );
            candidate.addAll( steps.subList( Math.min( steps.size(), at + chunk ), steps.size() ) );
            if( candidate.size() < steps.size() && stillFails.test( failing.withSteps( candidate ) ) ) {
                steps = candidate;
            } else {
                at += chunk;
            }
        }
        return steps;
    }
}
