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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The parked long-polls of one session (section 7.5), at most one per participant. Every method only collects: it
 * returns the completions as runnables for the session to run after it has released its lock. Each runnable is guarded:
 * a waiter whose {@code complete} throws (a response already broken, say) is logged and never stops the others or turns
 * an already committed push into a failure. Guarded by the session lock.
 */
final class Waiters {

    private static final Logger LOG = LogManager.getLogger( Waiters.class );

    /** How a parked pull is answered, from what it asked for. */
    interface Answer {
        PullResponse of( String participantId, int since, long presenceSince );
    }

    private record Parked( int since, long presenceSince, Instant deadline, PullWaiter waiter ) {
    }

    private final UUID sessionId;
    private final Map< String, Parked > parked = new LinkedHashMap<>();

    Waiters( final UUID sessionId ) {
        this.sessionId = sessionId;
    }

    private Runnable completion( final String participantId, final PullWaiter w, final PullResponse r ) {
        return () -> {
            try {
                w.complete( r );
            } catch( final RuntimeException e ) {
                LOG.warn( "collab session {}: completing the parked pull of {} failed: {}", sessionId, participantId,
                    e.toString() );
            }
        };
    }

    /** Parks {@code w}; a pull the participant already had parked is displaced and answered {@code displacedAnswer}. */
    Runnable park( final String participantId, final int since, final long presenceSince, final Instant deadline,
                   final PullWaiter w, final PullResponse displacedAnswer ) {
        final Parked old = parked.put( participantId, new Parked( since, presenceSince, deadline, w ) );
        return old == null ? null : completion( participantId, old.waiter(), displacedAnswer );
    }

    /** Removes the participant's parked pull, answered {@code answer}; null when it had none. */
    Runnable remove( final String participantId, final PullResponse answer ) {
        final Parked old = parked.remove( participantId );
        return old == null ? null : completion( participantId, old.waiter(), answer );
    }

    /** Removes every parked pull, each answered by {@code answer}. */
    List< Runnable > drainAll( final Answer answer ) {
        final List< Runnable > out = new ArrayList<>( parked.size() );
        parked.forEach( ( id, p ) -> out.add( completion( id, p.waiter(), answer.of( id, p.since(), p.presenceSince() ) ) ) );
        parked.clear();
        return out;
    }

    /**
     * Removes the parked pulls whose {@code presenceSince} is below {@code presenceSeq}, except {@code exceptParticipant}'s
     * (the participant whose change it was), each answered by {@code answer}.
     */
    List< Runnable > drainWhere( final long presenceSeq, final String exceptParticipant, final Answer answer ) {
        final List< Runnable > out = new ArrayList<>();
        for( final Iterator< Map.Entry< String, Parked > > it = parked.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry< String, Parked > e = it.next();
            final Parked p = e.getValue();
            if( p.presenceSince() < presenceSeq && !e.getKey().equals( exceptParticipant ) ) {
                out.add( completion( e.getKey(), p.waiter(), answer.of( e.getKey(), p.since(), p.presenceSince() ) ) );
                it.remove();
            }
        }
        return out;
    }

    /** Removes the parked pulls whose deadline is not after {@code now}, each answered by {@code answer}. */
    List< Runnable > expire( final Instant now, final Answer answer ) {
        final List< Runnable > out = new ArrayList<>();
        parked.entrySet().removeIf( e -> {
            final Parked p = e.getValue();
            if( now.isBefore( p.deadline() ) ) {
                return false;
            }
            out.add( completion( e.getKey(), p.waiter(), answer.of( e.getKey(), p.since(), p.presenceSince() ) ) );
            return true;
        } );
        return out;
    }
}
