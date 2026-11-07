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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Minimises a failing trace (F1): removes steps while {@code stillFails} holds, first in halving chunks (fast on long
 * traces), then one step at a time until no single step can go. Replay is by trace, not by seed, so a shorter trace is
 * judged by replaying exactly its remaining steps; steps that no longer make sense (a message that is never sent, an edit
 * past the end of a shorter document) are skipped or clamped by the scheduler.
 */
public final class Shrinker {

    private Shrinker() {
    }

    public static SimTrace shrink( final SimTrace failing, final Predicate< SimTrace > stillFails ) {
        List< SimTrace.Step > steps = new ArrayList<>( failing.steps() );
        for( int chunk = steps.size() / 2; chunk > 1; chunk /= 2 ) {
            steps = removeChunks( failing, steps, chunk, stillFails );
        }
        boolean shrunk = true;
        while( shrunk ) {
            shrunk = false;
            for( int i = steps.size() - 1; i >= 0; i-- ) {
                final List< SimTrace.Step > candidate = new ArrayList<>( steps );
                candidate.remove( i );
                if( stillFails.test( failing.withSteps( candidate ) ) ) {
                    steps = candidate;
                    shrunk = true;
                }
            }
        }
        return failing.withSteps( steps );
    }

    private static List< SimTrace.Step > removeChunks( final SimTrace failing, final List< SimTrace.Step > start,
                                                       final int chunk, final Predicate< SimTrace > stillFails ) {
        List< SimTrace.Step > steps = start;
        int at = 0;
        while( at < steps.size() ) {
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
