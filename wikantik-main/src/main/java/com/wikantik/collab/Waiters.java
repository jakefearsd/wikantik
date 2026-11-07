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

import com.wikantik.collab.model.PullResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The parked long-polls of one session (section 7.5), at most one per participant. Every method only collects: it
 * returns the completions as runnables for the session to run after it has released its lock. Guarded by the session
 * lock.
 */
final class Waiters {

    private record Parked( int since, Instant deadline, PullWaiter waiter ) {
    }

    private final Map< String, Parked > parked = new LinkedHashMap<>();

    private static Runnable completion( final PullWaiter w, final PullResponse r ) {
        return () -> w.complete( r );
    }

    /** Parks {@code w}; a pull the participant already had parked is displaced and answered {@code displacedAnswer}. */
    Runnable park( final String participantId, final int since, final Instant deadline, final PullWaiter w,
                   final PullResponse displacedAnswer ) {
        final Parked old = parked.put( participantId, new Parked( since, deadline, w ) );
        return old == null ? null : completion( old.waiter(), displacedAnswer );
    }

    /** Removes the participant's parked pull, answered {@code answer}; null when it had none. */
    Runnable remove( final String participantId, final PullResponse answer ) {
        final Parked old = parked.remove( participantId );
        return old == null ? null : completion( old.waiter(), answer );
    }

    /** Removes every parked pull, each answered {@code answer.apply(participantId, since)}. */
    List< Runnable > drainAll( final BiFunction< String, Integer, PullResponse > answer ) {
        final List< Runnable > out = new ArrayList<>( parked.size() );
        parked.forEach( ( id, p ) -> out.add( completion( p.waiter(), answer.apply( id, p.since() ) ) ) );
        parked.clear();
        return out;
    }

    /** Removes the parked pulls whose deadline is not after {@code now}, each answered {@code answer.apply(participantId)}. */
    List< Runnable > expire( final Instant now, final Function< String, PullResponse > answer ) {
        final List< Runnable > out = new ArrayList<>();
        parked.entrySet().removeIf( e -> {
            if( now.isBefore( e.getValue().deadline() ) ) {
                return false;
            }
            out.add( completion( e.getValue().waiter(), answer.apply( e.getKey() ) ) );
            return true;
        } );
        return out;
    }
}
